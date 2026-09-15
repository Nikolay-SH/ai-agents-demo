package meetup.sherlock.spring;

import meetup.sherlock.DemoSupport;
import meetup.sherlock.EvidenceReview;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

public final class SpringSherlock {
    static final String MODEL = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3:8b");
    /** Same settings for every step: temperature 0, 16k context, 2048 tokens per answer, no thinking. */
    static OllamaChatOptions options(ToolCallback... tools) {
        return (OllamaChatOptions) OllamaChatOptions.builder().model(MODEL).temperature(0.0).numCtx(16384).numPredict(2048)
            .toolCallbacks(tools).disableThinking().build();
    }
    public static void main(String[] args) throws Exception {
        String model = MODEL;
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

        var chatModel = OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
            .options(options())
            .build();
        if (args.length > 0 && args[0].matches("(?i)step[0-5]")) {
            SpringLadder.run(args[0].toLowerCase(java.util.Locale.ROOT), chatModel, args.length > 1 ? args[1] : SpringLadder.QUESTION);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("LIVE")) {
            try (var session = new meetup.sherlock.live.LiveSession()) {
                System.out.println("Spring AI 2.0.1 | Ollama " + model + " | REAL Docker tools + human approval");
                var liveClient = ChatClient.builder(chatModel).defaultSystem(meetup.sherlock.live.LiveSession.SYSTEM)
                    .defaultTools(new SpringLiveTools(session)).build();
                System.out.println(session.run(request -> liveClient.prompt().user(request).call().content(),
                    args.length > 1 ? args[1] : meetup.sherlock.live.LiveSession.QUESTION));
            }
            return;
        }
        var lab = DemoSupport.lab(DemoSupport.scenario(args));
        System.out.println("Spring AI 2.0.1 | Ollama " + model + " | synthetic incident");
        var client = ChatClient.builder(chatModel).defaultSystem(DemoSupport.SYSTEM)
            .defaultTools(new SpringTools(lab)).build();
        DemoSupport.runBounded(() -> EvidenceReview.investigate(
            request -> client.prompt().user(request).call().content(), lab, DemoSupport.question(args)), lab);
        DemoSupport.approvals(lab);
    }
}
