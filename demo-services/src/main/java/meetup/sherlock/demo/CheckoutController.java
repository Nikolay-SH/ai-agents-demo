package meetup.sherlock.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "demo.role", havingValue = "checkout")
class CheckoutController {
    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final URI payments;
    CheckoutController(@Value("${demo.payment-url}") String paymentUrl) { payments = URI.create(paymentUrl + "/payments"); }
    @PostMapping("/checkout") ResponseEntity<?> checkout() {
        var requestId = UUID.randomUUID();
        try {
            var request = HttpRequest.newBuilder(payments).timeout(Duration.ofSeconds(4))
                .header("X-Request-Id", requestId.toString()).POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                log.error("checkout failed requestId={} dependency=payment upstreamStatus={}", requestId, response.statusCode());
                return unavailable(requestId);
            }
            log.info("checkout succeeded requestId={} dependency=payment", requestId);
            return ResponseEntity.ok(Map.of("status", "complete", "requestId", requestId));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("checkout failed requestId={} dependency=payment exception={}: {}", requestId,
                failure.getClass().getSimpleName(), failure.getMessage());
            return unavailable(requestId);
        }
    }
    private ResponseEntity<?> unavailable(UUID requestId) {
        return ResponseEntity.status(503).body(Map.of("status", "unavailable", "dependency", "payment", "requestId", requestId));
    }
}
