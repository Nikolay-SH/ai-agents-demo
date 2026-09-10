package meetup.sherlock.spring;

import meetup.sherlock.DemoSupport;
import meetup.sherlock.EvidenceReview;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

public final class SpringSherlock {
    public static void main(String[] args) throws Exception {
        var lab = DemoSupport.lab(DemoSupport.scenario(args));
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3:8b");
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        System.out.println("LIVE Spring AI 2.0.1 | Ollama " + model + " | synthetic incident");
        var chatModel = OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
            .options(OllamaChatOptions.builder().model(model).temperature(0.0).numCtx(16384).numPredict(2048).disableThinking().build())
            .build();
        var client = ChatClient.builder(chatModel).defaultSystem(DemoSupport.SYSTEM)
            .defaultTools(new SpringTools(lab)).build();
        DemoSupport.runBounded(() -> EvidenceReview.investigate(
            request -> client.prompt().user(request).call().content(), lab, DemoSupport.question(args)), lab);
        DemoSupport.approvals(lab);
    }
}
