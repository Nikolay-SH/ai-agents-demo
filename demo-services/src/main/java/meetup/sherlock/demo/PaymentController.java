package meetup.sherlock.demo;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "demo.role", havingValue = "payment")
class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final JdbcTemplate jdbc;
    private final HikariDataSource pool;
    PaymentController(JdbcTemplate jdbc, HikariDataSource pool) { this.jdbc = jdbc; this.pool = pool; }
    @PostMapping("/payments") ResponseEntity<?> payment() {
        var id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO payments (id, amount_cents) VALUES (?, ?)", id, 1990);
            log.info("payment succeeded paymentId={} amountCents=1990", id);
            return ResponseEntity.ok(Map.of("status", "paid", "paymentId", id));
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            log.error("payment failed paymentId={} exception={} cause={}: {}", id,
                failure.getClass().getSimpleName(), cause.getClass().getSimpleName(), cause.getMessage());
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "paymentId", id));
        }
    }
    @PostMapping("/demo/faults/close-pool") Map<String, Object> closePool() {
        log.warn("DEMO fault injection requested: closing actual HikariDataSource pool={}", pool.getPoolName());
        pool.close();
        return Map.of("fault", "close-pool", "poolClosed", pool.isClosed());
    }
}
