package meetup.sherlock.demo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "demo.role=checkout")
class CheckoutIntegrationTest {
    static HttpServer payment;
    static AtomicInteger upstreamStatus = new AtomicInteger(200);
    static AtomicInteger requests = new AtomicInteger();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        payment = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        payment.createContext("/payments", exchange -> {
            requests.incrementAndGet();
            int status = exchange.getRequestMethod().equals("POST") ? upstreamStatus.get() : 405;
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        payment.start();
        registry.add("demo.payment-url", () -> "http://127.0.0.1:" + payment.getAddress().getPort());
    }
    @AfterAll static void cleanup() { payment.stop(0); }
    @Autowired TestRestTemplate http;
    // Catches success responses that skip the downstream call or hide downstream failures.
    @Test void propagatesRealDownstreamFailureAndRejectsPaymentOnlyFault() {
        assertThat(http.postForEntity("/checkout", null, String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(requests.get()).isEqualTo(1);
        upstreamStatus.set(503);
        assertThat(http.postForEntity("/checkout", null, String.class).getStatusCode().value()).isEqualTo(503);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(http.postForEntity("/demo/faults/close-pool", null, String.class).getStatusCode().value()).isEqualTo(404);
    }
}
