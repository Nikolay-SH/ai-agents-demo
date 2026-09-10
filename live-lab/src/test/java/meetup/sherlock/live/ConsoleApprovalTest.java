package meetup.sherlock.live;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleApprovalTest {
    private RestartGate.Request request() {
        return new RestartGate.Request("request-123", new RestartGate.Target("payment", "a".repeat(64), "boot", "sherlock-live"), "pool closed");
    }
    @Test void exactTokenApprovesAndOtherInputOrEofRejects() {
        for (String input : new String[]{"approve request-123\n", "yes\n", "approve other\n", "\n", ""}) {
            var out = new ByteArrayOutputStream();
            try (var approval = new ConsoleApproval(new StringReader(input), new PrintStream(out), Duration.ofSeconds(1))) {
                assertEquals(input.startsWith("approve request-123") ? RestartGate.Decision.APPROVE : RestartGate.Decision.REJECT,
                    approval.ask(request()));
                assertTrue(out.toString().contains("REAL DOCKER RESTART"));
                assertTrue(out.toString().contains("Service: payment"));
            }
        }
    }
}
