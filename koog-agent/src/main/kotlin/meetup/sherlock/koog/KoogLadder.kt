package meetup.sherlock.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import meetup.sherlock.live.Ladder
import meetup.sherlock.live.LiveSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The same ladder on Koog: same incident, model, system prompt, tools and evidence rules as SpringLadder. */
private val modelName = System.getenv("OLLAMA_MODEL") ?: "qwen3:8b"
private val model = LLModel(provider = LLMProvider.Ollama, id = modelName,
    capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Schema.JSON.Standard), contextLength = 16384)
private val params = OllamaParams(temperature = 0.0, think = false, maxTokens = 2048)
private fun config(maxIterations: Int) = AIAgentConfig(prompt = prompt("sherlock", params = params) { system(Ladder.SYSTEM) },
    model = model, maxAgentIterations = maxIterations)

fun runLadder(step: String, question: String) {
    println("Koog 1.2.0 | Ollama $modelName | $step")
    val executor = MultiLLMPromptExecutor(NoThinkOllama(OllamaClient(baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434",
        contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(16384))))
    try {
        if (step == "step0") { println(runBlocking { step0(executor, question) }); return }
        // Шаги 1–2 — сырые ответы tools, с шага 3 — компактные. В диалоге (2–5) время всех реплик суммируется.
        LiveSession(step !in setOf("step1", "step2"), if (step == "step1") 180 else 300).use { lab ->
            when (step) {
                "step1" -> println(lab.run({ q -> runBlocking { step1(executor, lab, q) } }, question))
                "step2", "step3" -> history(executor, lab, question)
                "step4" -> investigation(executor, lab, question, actions = false)
                "step5" -> investigation(executor, lab, question, actions = true)
                else -> throw IllegalArgumentException("Steps: step0..step5")
            }
        }
    } finally { executor.close() }
}

/**
 * Koog 1.2.0: смена toolChoice (её делает subgraphWithTask) пересоздаёт параметры через базовый LLMParams.copy()
 * и теряет OllamaParams.think. Без этой обёртки qwen3 «думает» внутри подграфов, и этап идёт в разы дольше.
 */
class NoThinkOllama(private val client: OllamaClient) : LLMClient() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val p = prompt.params
        val params = if (p is OllamaParams && p.think == false) p else OllamaParams(temperature = p.temperature, maxTokens = p.maxTokens,
            numberOfChoices = p.numberOfChoices, speculation = p.speculation, schema = p.schema, toolChoice = p.toolChoice,
            user = p.user, additionalProperties = p.additionalProperties, think = false)
        return client.execute(prompt.copy(params = params), model, tools)
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel) = client.moderate(prompt, model)
    override fun llmProvider() = client.llmProvider()
    override fun close() = client.close()
}

/** Шаг 0. Один вызов: модель не видит систему и может только гадать. */
suspend fun step0(executor: PromptExecutor, question: String): String =
    executor.execute(prompt("step0", params = params) { system(Ladder.SYSTEM); user(question) }, model).textContent()

/** Шаг 1. Цикл агента — граф из трёх узлов: запрос к LLM → выполнение tools → отправка результатов. Лимит — maxAgentIterations. */
val toolLoop = strategy<String, String>("tool-loop") {
    val callLLM by nodeLLMRequest()
    val executeTools by nodeExecuteTools()
    val sendResults by nodeLLMSendToolResults()
    edge(nodeStart forwardTo callLLM)
    edge(callLLM forwardTo executeTools onToolCalls { true })
    edge(callLLM forwardTo nodeFinish onTextMessage { true })
    edge(executeTools forwardTo sendResults)
    edge(sendResults forwardTo executeTools onToolCalls { true })
    edge(sendResults forwardTo nodeFinish onTextMessage { true })
}

suspend fun step1(executor: PromptExecutor, lab: LiveSession, question: String): String {
    var call = 0
    val agent = AIAgent(executor, config(maxIterations = 26), toolLoop, ToolRegistry { tools(KoogReadTools(lab)) }) {
        install(EventHandler) {
            onLLMCallCompleted { ctx -> println("\n-- LLM call ${++call}: отправлено ${ctx.prompt.messages.size} сообщений, ${ctx.response?.metaInfo?.inputTokensCount} prompt tokens") }
        }
    }
    return try { agent.run(question) } finally { agent.close() }
}

/** Шаги 2–3. Стандартный цикл Koog + ChatMemory: оператор продолжает разговор, собранные улики не теряются. */
fun history(executor: PromptExecutor, lab: LiveSession, question: String) {
    val memory = InMemoryChatHistoryProvider()
    val session = UUID.randomUUID().toString()
    val turn = java.util.function.Function<String, String> { text ->
        runBlocking { chatTurn(executor, lab, memory, session, text, KoogReadTools(lab)) + "\n" + shape(memory, session) }
    }
    lab.chat(turn, turn, question)
}

private suspend fun chatTurn(executor: PromptExecutor, lab: LiveSession, memory: InMemoryChatHistoryProvider, session: String,
                             text: String, vararg tools: ai.koog.agents.core.tools.reflect.ToolSet): String {
    val agent = AIAgent(executor, config(maxIterations = 60), toolLoop, ToolRegistry { tools.forEach { tools(it) } }) {
        install(ChatMemory) { chatHistoryProvider(memory); windowSize(200) }
    }
    return try { agent.run(text, session) } finally { agent.close() }
}

private suspend fun shape(memory: InMemoryChatHistoryProvider, session: String): String {
    val history = memory.load(session)
    // В Koog вызов tool — часть сообщения Assistant, результат — часть сообщения User.
    val parts = history.flatMap { it.parts }
    return "-- память диалога: ${history.size} сообщений ${history.groupingBy { it.role }.eachCount()}, " +
        "tool calls=${parts.count { it is MessagePart.Tool.Call }}, tool results=${parts.count { it is MessagePart.Tool.Result }}"
}

private val PHASES = setOf("triage", "plan", "verify", "review", "act", "settle", "recovery", "report")

@Serializable data class Hypothesis(val service: String, val claim: String, val check: String)
@Serializable data class Triage(val symptoms: String, val hypotheses: List<Hypothesis>)
@Serializable enum class Status { CONFIRMED, REJECTED, UNKNOWN }
@Serializable data class Verdict(val status: Status, val evidence: List<Int>, val explanation: String)
@Serializable data class ActionOutcome(val summary: String)

/** Шаги 4–5. Стратегия — граф Koog. Этапы — подграфы со своим набором tools и типизированным результатом, решения — узлы с обычным кодом. */
fun investigation(executor: PromptExecutor, lab: LiveSession, question: String, actions: Boolean) {
    val memory = InMemoryChatHistoryProvider()
    val session = UUID.randomUUID().toString()
    val confirmed = AtomicBoolean()   // действия доступны модели только после подтверждённой причины
    val first = java.util.function.Function<String, String> { q ->
        runBlocking {
            val registry = ToolRegistry { tools(KoogReadTools(lab)); if (actions) tools(KoogActionTools(lab)) }
            val agent = AIAgent(executor, config(maxIterations = 300), sherlockStrategy(lab, actions, confirmed), registry) {
                install(ChatMemory) { chatHistoryProvider(memory); windowSize(400) }
                install(EventHandler) {   // показываем путь по графу: какой этап сейчас выполняется
                    onSubgraphExecutionStarting { ctx -> if (ctx.subgraph.name in PHASES) println("\n-- граф Koog: этап ${ctx.subgraph.name}") }
                    onNodeExecutionStarting { ctx -> if (ctx.node.name in PHASES) println("\n-- граф Koog: узел ${ctx.node.name}") }
                }
            }
            try { agent.run(q, session) } finally { agent.close() }
        }
    }
    val next = java.util.function.Function<String, String> { text ->
        runBlocking {
            val tools = if (confirmed.get() && actions) arrayOf(KoogReadTools(lab), KoogActionTools(lab)) else arrayOf(KoogReadTools(lab))
            chatTurn(executor, lab, memory, session, text, *tools) + "\n" + shape(memory, session)
        }
    }
    lab.chat(first, next, question)
}

/**
 * Этап графа: цикл tools с набором инструментов этого этапа, затем отдельный запрос без tools за JSON.
 * Если модель ответила пусто или JSON не разобран — безопасное значение по умолчанию, прогон не падает.
 */
inline fun <reified I, reified O> phase(name: String, tools: List<ToolBase<*, *>>, fallback: O, crossinline task: (I) -> String) =
    subgraph<I, O>(name, tools = tools) {
        val ask by node<I, String> { input -> task(input) }
        val callLLM by nodeLLMRequest()
        val executeTools by nodeExecuteTools()
        val sendResults by nodeLLMSendToolResults()
        val result by node<String, O> {
            llm.writeSession {
                appendPrompt { user("Запиши итог этого этапа по схеме. Используй только факты и номера улик из разговора.") }
                requestLLMStructured<O>().onFailure { println("\n-- код: этап $name — JSON не разобран (${it::class.simpleName}), взято значение по умолчанию") }
                    .getOrNull()?.data ?: fallback
            }
        }
        edge(nodeStart forwardTo ask)
        edge(ask forwardTo callLLM)
        edge(callLLM forwardTo executeTools onToolCalls { true })
        edge(callLLM forwardTo result transformed { it.textContent() })
        edge(executeTools forwardTo sendResults)
        edge(sendResults forwardTo executeTools onToolCalls { true })
        edge(sendResults forwardTo result transformed { it.textContent() })
        edge(result forwardTo nodeFinish)
    }

fun sherlockStrategy(lab: LiveSession, actions: Boolean, confirmed: AtomicBoolean): AIAgentGraphStrategy<String, String> {
    val read = ToolRegistry { tools(KoogReadTools(lab)) }.tools
    val all = ToolRegistry { tools(KoogReadTools(lab)); tools(KoogActionTools(lab)) }.tools
    val queue = ArrayDeque<Hypothesis>()
    val checked = mutableSetOf<String>()
    val log = StringBuilder("\n\n=== ХОД СТРАТЕГИИ (код, не LLM) ===")
    var cause: Hypothesis? = null
    var current: Hypothesis? = null
    var rounds = 0
    fun next(h: Hypothesis) = h.copy(service = Ladder.service(h.service)).also { current = it; rounds++ }

    return strategy("sherlock") {
        val triage by phase<String, Triage>("triage", read, Triage(Ladder.QUESTION, emptyList())) { q -> Ladder.triage(q) }
        val plan by node<Triage, Hypothesis?> { t -> queue.addAll(t.hypotheses.take(3)); queue.removeFirstOrNull() }
        val verify by phase<Hypothesis, Verdict>("verify", read, Verdict(Status.UNKNOWN, emptyList(), "код: ответ модели не разобран")) { h -> Ladder.verify(h.claim, h.service) }
        // Код, а не модель: проверка улик и обход зависимостей. Выход — следующая гипотеза или null.
        val review by node<Verdict, Hypothesis?> { raw ->
            val h = current!!
            val reason = if (raw.status == Status.CONFIRMED) Ladder.downgradeReason(lab, h.service, raw.evidence) else null
            val verdict = if (reason == null) raw else raw.copy(status = Status.UNKNOWN, explanation = reason)
            checked += h.service
            log.append("\n${h.service}: ${h.claim} → ${verdict.status} ${verdict.evidence} ${verdict.explanation}")
            if (verdict.status == Status.CONFIRMED) {
                cause = h
                // Сбой сервиса может быть симптомом его зависимости: граф берётся из живого /demo/status, а не из промпта.
                val dependency = lab.dependencies(h.service).firstOrNull { it !in checked }
                if (dependency != null) log.append("\n  код: у ${h.service} есть непроверенная зависимость $dependency")
                dependency?.let { Hypothesis(it, "сбой ${h.service} вызван проблемой в его зависимости $it", "проверь $it напрямую") }
            } else if (cause != null) null else queue.removeFirstOrNull()
        }
        val act by phase<Hypothesis, ActionOutcome>("act", all, ActionOutcome("")) { h -> Ladder.act(h.claim, h.service) }
        val settle by node<ActionOutcome, Unit> {
            log.append("\nдействия: ").append(Ladder.describe(Ladder.actions(lab)))
            if (Ladder.actions(lab).any { it.result().startsWith("EXECUTED") }) delay(20_000)
        }
        val recovery by phase<Unit, Verdict>("recovery", read, Verdict(Status.UNKNOWN, emptyList(), "код: ответ модели не разобран")) { Ladder.RECOVERY }
        val report by node<Unit, String> {
            confirmed.set(cause != null)
            val text = llm.writeSession { appendPrompt { user(Ladder.report(actions)) }; requestLLMWithoutTools().textContent() }
            text + Ladder.referenceCheck(text, lab) + log
        }

        edge(nodeStart forwardTo triage)
        edge(triage forwardTo plan)
        edge(plan forwardTo verify onCondition { it != null } transformed { next(it!!) })
        edge(plan forwardTo report onCondition { it == null } transformed { log.append("\nгипотез нет"); Unit })
        edge(verify forwardTo review)
        edge(review forwardTo verify onCondition { it != null && rounds < 5 } transformed { next(it!!) })
        edge(review forwardTo act onCondition { actions && cause != null } transformed { cause!! })
        edge(review forwardTo report transformed { if (cause == null) log.append("\nпричина не подтверждена" + if (actions) "; действия кодом не предлагались" else ""); Unit })
        edge(act forwardTo settle)
        edge(settle forwardTo recovery onCondition { Ladder.actions(lab).any { it.result().startsWith("EXECUTED") } })
        edge(settle forwardTo report)
        edge(recovery forwardTo report transformed { v ->
            val reason = if (v.status == Status.CONFIRMED) Ladder.downgradeReason(lab, "checkout", v.evidence) else null
            log.append("\nвосстановление: ${if (reason == null) v.status else Status.UNKNOWN} ${v.evidence} ${reason ?: v.explanation}"); Unit
        })
        edge(report forwardTo nodeFinish)
    }
}
