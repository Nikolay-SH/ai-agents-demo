package meetup.sherlock.spring;

import meetup.sherlock.live.Ladder;
import meetup.sherlock.live.LiveSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One incident, six steps: from a bare LLM call to an agent with history, strategy and human-approved actions. */
public final class SpringLadder {
    static final String QUESTION = Ladder.QUESTION;
    static final String SYSTEM = Ladder.SYSTEM;
    static final String MODEL = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3:8b");

    public static void main(String[] args) {
        if (args.length == 0 || !args[0].matches("(?i)step[0-5]")) throw new IllegalArgumentException("Usage: step0..step5 [question]");
        var chatModel = OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434")).build())
            .options(options())
            .build();
        run(args[0].toLowerCase(Locale.ROOT), chatModel, args.length > 1 ? args[1] : QUESTION);
    }
    /** Same settings for every step: temperature 0, 16k context, 2048 tokens per answer, no thinking. */
    static OllamaChatOptions options(ToolCallback... tools) {
        return (OllamaChatOptions) OllamaChatOptions.builder().model(MODEL).temperature(0.0).numCtx(16384).numPredict(2048)
            .toolCallbacks(tools).disableThinking().build();
    }

    static void run(String step, ChatModel model, String question) {
        System.out.println("Spring AI 2.0.1 | Ollama " + MODEL + " | " + step);
        if (step.equals("step0")) { System.out.println(step0(model, question)); return; }
        // Шаги 1–2 — сырые ответы tools, с шага 3 — компактные. В диалоге (2–5) время всех реплик суммируется.
        try (var lab = new LiveSession(!step.equals("step1") && !step.equals("step2"), step.equals("step1") ? 180 : 300)) {
            switch (step) {
                case "step1" -> System.out.println(lab.run(q -> step1(model, lab, q), question));
                case "step2", "step3" -> history(model, lab, question);
                case "step4" -> strategy(model, lab, question, false);
                case "step5" -> strategy(model, lab, question, true);
                default -> throw new IllegalArgumentException("Steps: step0..step5");
            }
        }
    }

    /** Шаг 0. Один вызов: модель не видит систему и может только гадать. */
    static String step0(ChatModel model, String question) {
        return model.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(question)))).getResult().getOutput().getText();
    }

    /** Шаг 1. Tools и цикл вручную: агент — это цикл, история — список сообщений, лимит — условие цикла. */
    static String step1(ChatModel model, LiveSession lab, String question) {
        var options = options(ToolCallbacks.from(new SpringReadTools(lab)));
        var tools = ToolCallingManager.builder().build();
        var prompt = new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(question)), options);
        for (int call = 1; call <= 12; call++) {
            ChatResponse response = model.call(prompt);
            System.out.printf("%n-- LLM call %d: отправлено %d сообщений, %d prompt tokens%n",
                call, prompt.getInstructions().size(), response.getMetadata().getUsage().getPromptTokens());
            if (!response.hasToolCalls()) return response.getResult().getOutput().getText();
            prompt = new Prompt(tools.executeToolCalls(prompt, response).conversationHistory(), options);
        }
        return "INCOMPLETE: лимит 12 вызовов LLM исчерпан";
    }

    /** Шаги 2–3. Тот же цикл внутри ChatClient + память: оператор продолжает разговор, собранные улики не теряются. */
    static void history(ChatModel model, LiveSession lab, String question) {
        var chat = Conversation.create(model, 100);
        Function<String, String> turn = text -> chat.ask(text, new SpringReadTools(lab)).content() + "\n" + chat.shape();
        lab.chat(turn, turn, question);
    }

    enum Status { CONFIRMED, REJECTED, UNKNOWN }
    record Hypothesis(String service, String claim, String check) {}
    record Triage(String symptoms, List<Hypothesis> hypotheses) {}
    record Verdict(Status status, List<Integer> evidence, String explanation) {}

    /** Шаги 4–5. Этапы задаёт код, модель заполняет их. Код проверяет улики, идёт по зависимостям и выдаёт действия только после подтверждения. */
    static void strategy(ChatModel model, LiveSession lab, String question, boolean actions) {
        var phase = Conversation.create(model, 200);
        Object[] read = { new SpringReadTools(lab) };
        Object[] all = actions ? new Object[]{ new SpringReadTools(lab), new SpringActionTools(lab) } : read;
        var confirmed = new java.util.concurrent.atomic.AtomicBoolean();   // действия доступны модели только после подтверждённой причины
        Function<String, String> investigate = q -> {
            var log = new StringBuilder("\n\n=== ХОД СТРАТЕГИИ (код, не LLM) ===");
            Triage triage = phase.result(Ladder.triage(q), Triage.class, read);
            Deque<Hypothesis> queue = new ArrayDeque<>(triage.hypotheses() == null ? List.of() : triage.hypotheses().stream().limit(3).toList());
            Set<String> checked = new HashSet<>();
            Hypothesis cause = null;
            int rounds = 0;
            for (Hypothesis h = queue.poll(); h != null && rounds++ < 5; ) {
                h = new Hypothesis(Ladder.service(h.service()), h.claim(), h.check());
                Verdict verdict = review(lab, h, phase.result(Ladder.verify(h.claim(), h.service()), Verdict.class, read));
                checked.add(h.service());
                log.append("\n").append(h.service()).append(": ").append(h.claim()).append(" → ").append(verdict.status())
                    .append(" ").append(verdict.evidence()).append(" ").append(verdict.explanation());
                if (verdict.status() == Status.CONFIRMED) {
                    cause = h;
                    // Сбой сервиса может быть симптомом его зависимости: граф берётся из живого /demo/status, а не из промпта.
                    String dependency = lab.dependencies(h.service()).stream().filter(d -> !checked.contains(d)).findFirst().orElse(null);
                    if (dependency != null) log.append("\n  код: у ").append(h.service()).append(" есть непроверенная зависимость ").append(dependency);
                    h = dependency == null ? null : new Hypothesis(dependency, "сбой " + h.service() + " вызван проблемой в его зависимости " + dependency, "проверь " + dependency + " напрямую");
                } else h = cause != null ? null : queue.poll();
            }
            confirmed.set(cause != null);
            if (cause == null) log.append("\nпричина не подтверждена").append(actions ? "; действия кодом не предлагались" : "");
            else if (actions) {
                phase.ask(Ladder.act(cause.claim(), cause.service()), all).content();
                var done = Ladder.actions(lab);
                log.append("\nдействия: ").append(Ladder.describe(done));
                if (done.stream().anyMatch(e -> e.result().startsWith("EXECUTED"))) {
                    try { Thread.sleep(20_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "INCOMPLETE: interrupted" + log; }
                    Verdict recovered = review(lab, new Hypothesis("checkout", "checkout восстановился", "getMetrics checkout"), phase.result(Ladder.RECOVERY, Verdict.class, read));
                    log.append("\nвосстановление: ").append(recovered.status()).append(" ").append(recovered.evidence()).append(" ").append(recovered.explanation());
                }
            }
            String report = phase.ask(Ladder.report(actions)).content();
            return report + Ladder.referenceCheck(report, lab) + log;
        };
        lab.chat(investigate, text -> phase.ask(text, confirmed.get() ? all : read).content() + "\n" + phase.shape(), question);
    }

    /** Код, а не модель: CONFIRMED только со ссылками на реальные улики, среди которых есть прямая проверка этого сервиса. */
    static Verdict review(LiveSession lab, Hypothesis h, Verdict verdict) {
        if (verdict == null || verdict.status() == null) return new Verdict(Status.UNKNOWN, List.of(), "код: модель не вернула вердикт");
        String reason = verdict.status() == Status.CONFIRMED ? Ladder.downgradeReason(lab, h.service(), verdict.evidence()) : null;
        return reason == null ? verdict : new Verdict(Status.UNKNOWN, verdict.evidence(), reason);
    }

    /** Один разговор на сессию: цикл tools внутри ChatClient, память внутри цикла — сохраняет каждый вызов tool и его результат. */
    record Conversation(ChatClient client, ChatMemory memory, String id) {
        static Conversation create(ChatModel model, int window) {
            var memory = MessageWindowChatMemory.builder().maxMessages(window).build();
            var client = ChatClient.builder(model).defaultSystem(SYSTEM)
                .defaultAdvisors(
                    ToolCallingAdvisor.builder().disableInternalConversationHistory().build(),
                    MessageChatMemoryAdvisor.builder(memory).order(ToolCallingAdvisor.DEFAULT_ORDER + 1).build())
                .build();
            return new Conversation(client, memory, UUID.randomUUID().toString());
        }
        ChatClient.CallResponseSpec ask(String text, Object... tools) {
            return client.prompt().user(text).tools(tools).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id)).call();
        }
        /** Two calls: tools gather evidence as free text, then JSON without tools. With Ollama, entity() constrains the output format and blocks tool calls. */
        <T> T result(String text, Class<T> type, Object... tools) {
            ask(text, tools).content();
            return ask("Запиши итог этого этапа по схеме. Используй только факты и номера улик из разговора.").entity(type);
        }
        String shape() {
            List<Message> history = memory.get(id);
            return "-- память диалога: " + history.size() + " сообщений " + history.stream()
                .collect(Collectors.groupingBy(Message::getMessageType, TreeMap::new, Collectors.counting()));
        }
    }
}
