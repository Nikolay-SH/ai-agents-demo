package meetup.sherlock.spring;

import meetup.sherlock.DemoSupport;
import meetup.sherlock.EvidenceReview;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

public final class SpringSherlock {
    public static void main(String[] args) throws Exception {

        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3:8b");
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

        var chatModel = OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
            .options(OllamaChatOptions.builder().model(model).temperature(0.0).numCtx(16384).numPredict(2048).disableThinking().build())
            .build();
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
