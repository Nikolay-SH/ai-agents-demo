package meetup.sherlock.koog

import meetup.sherlock.DemoSupport
import meetup.sherlock.EvidenceReview
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val lab = DemoSupport.lab(DemoSupport.scenario(args))
    val modelName = System.getenv("OLLAMA_MODEL") ?: "qwen3:8b"
    val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    println("LIVE Koog 1.2.0 | Ollama $modelName | synthetic incident")
    DemoSupport.runBounded({
        EvidenceReview.investigate({ request ->
            runBlocking {
                val executor = MultiLLMPromptExecutor(OllamaClient(
                    baseUrl = baseUrl,
                    contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(16384)
                ))
                try {
                    val agent = AIAgent(
                        promptExecutor = executor,
                        agentConfig = AIAgentConfig(
                            prompt = prompt("sherlock", params = OllamaParams(
                                temperature = 0.0, think = false, maxTokens = 2048
                            )) { system(DemoSupport.SYSTEM) },
                            model = LLModel(
                                provider = LLMProvider.Ollama, id = modelName,
                                capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
                                contextLength = 16384
                            ),
                            maxAgentIterations = 40
                        ),
                        toolRegistry = ToolRegistry { tools(KoogTools(lab)) }
                    )
                    try { agent.run(request) } finally { agent.close() }
                } finally {
                    // Agent.close() does not own/close the externally supplied executor in Koog 1.2.0.
                    executor.close()
                }
            }
        }, lab, DemoSupport.question(args))
    }, lab)
    DemoSupport.approvals(lab)
}
