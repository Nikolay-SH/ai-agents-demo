package meetup.sherlock.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class StatusController {
    private final String bootId = UUID.randomUUID().toString();
    private final String role;
    StatusController(@Value("${demo.role}") String role) {
        if (!List.of("checkout", "payment").contains(role)) throw new IllegalArgumentException("demo.role must be checkout or payment");
        this.role = role;
    }
    @GetMapping("/demo/status") Map<String, Object> status() {
        return Map.of("name", role, "dependencies", List.of(role.equals("checkout") ? "payment" : "payments-db"),
            "version", "0.1.0", "bootId", bootId, "pid", ProcessHandle.current().pid());
    }
}
