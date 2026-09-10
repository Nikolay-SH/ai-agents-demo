package meetup.sherlock.live;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ActiveBudgetTest {
    @Test void humanWaitDoesNotConsumeAgentTimeAndNoToolsRunWhilePaused() {
        var now = new AtomicLong();
        var budget = new ActiveBudget(3, Duration.ofSeconds(10), now::get);
        now.set(Duration.ofSeconds(3).toNanos()); budget.charge(); budget.pause();
        now.set(Duration.ofSeconds(100).toNanos());
        assertFalse(budget.expired());
        assertThrows(IllegalStateException.class, budget::charge);
        budget.resume(); budget.charge();
        now.set(Duration.ofSeconds(107).toNanos()); assertTrue(budget.expired());
    }
    @Test void callLimitAndClosePreventFurtherTools() {
        var budget = new ActiveBudget(1, Duration.ofSeconds(10));
        budget.charge(); assertThrows(IllegalStateException.class, budget::charge);
        assertTrue(budget.expired(), "Call exhaustion must stop the agent watchdog too");
        budget.close(); assertTrue(budget.expired());
    }
}
