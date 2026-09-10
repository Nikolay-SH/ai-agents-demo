package meetup.sherlock.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "demo.role=payment", "demo.jdbc-url=jdbc:h2:mem:payments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "demo.db-user=sa", "demo.db-password="
})
class PaymentIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;

    // Catches a synthetic fault flag: closure must break actual JDBC while status remains reachable.
    @Test void writesPaymentThenClosedPoolProduces503WithoutChangingBootId() {
        var before = http.getForEntity("/demo/status", java.util.Map.class);
        assertThat(before.getStatusCode().value()).isEqualTo(200);
        assertThat(before.getBody()).containsEntry("name", "payment");
        assertThat(http.postForEntity("/payments", null, String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isEqualTo(1);
        assertThat(http.postForEntity("/demo/faults/close-pool", null, String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(pool.isClosed()).isTrue();
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class))
            .hasRootCauseMessage("HikariDataSource HikariDataSource (payment-pool) has been closed.");
        assertThat(http.postForEntity("/payments", null, String.class).getStatusCode().value()).isEqualTo(503);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(503);
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/demo/status", java.util.Map.class).getBody().get("bootId"))
            .isEqualTo(before.getBody().get("bootId"));
        assertThat(http.postForEntity("/checkout", null, String.class).getStatusCode().value()).isEqualTo(404);
    }
}
