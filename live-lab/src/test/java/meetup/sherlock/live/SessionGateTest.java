package meetup.sherlock.live;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SessionGateTest {
    static class Db implements SessionGate.Database {
        SessionGate.Session session = new SessionGate.Session(42, "t1", "reporting-job", "idle in transaction", 3, "LOCK TABLE payments");
        int terminated;
        public SessionGate.Session inspect(int pid) { return pid == 42 ? session : null; }
        public boolean terminate(int pid) { terminated++; return true; }
    }
    @Test void onlyApprovedBlockerIsTerminatedOnce() {
        var db = new Db();
        var gate = new SessionGate(db, r -> RestartGate.Decision.APPROVE, () -> true);
        assertTrue(gate.terminate(42, "blocks payments").startsWith("EXECUTED"));
        assertTrue(gate.terminate(42, "again").startsWith("ALREADY_DECIDED"));
        assertEquals(1, db.terminated);
    }
    @Test void unknownOrNonBlockingSessionNeverReachesHuman() {
        var db = new Db();
        var gate = new SessionGate(db, r -> fail("No confirmation should be shown"), () -> true);
        assertTrue(gate.terminate(7, "x").startsWith("DENIED"));
        db.session = new SessionGate.Session(42, "t1", "payment", "active", 0, "INSERT");
        assertTrue(gate.terminate(42, "x").startsWith("DENIED"));
        assertEquals(0, db.terminated);
    }
    @Test void rejectionTimeoutAndStaleSessionDoNotTerminate() {
        for (var decision : RestartGate.Decision.values()) {
            if (decision == RestartGate.Decision.APPROVE) continue;
            var db = new Db();
            assertFalse(new SessionGate(db, r -> decision, () -> true).terminate(42, "x").startsWith("EXECUTED"));
            assertEquals(0, db.terminated);
        }
        var db = new Db();
        var gate = new SessionGate(db, r -> {
            db.session = new SessionGate.Session(42, "t2", "reporting-job", "idle in transaction", 3, "LOCK TABLE payments");
            return RestartGate.Decision.APPROVE;
        }, () -> true);
        assertTrue(gate.terminate(42, "x").startsWith("STALE"));
        assertEquals(0, db.terminated);
    }
    @Test void deadlineDuringApprovalCancels() {
        var db = new Db();
        var active = new AtomicBoolean(true);
        var gate = new SessionGate(db, r -> { active.set(false); return RestartGate.Decision.APPROVE; }, active::get);
        assertTrue(gate.terminate(42, "x").startsWith("CANCELLED"));
        assertEquals(0, db.terminated);
    }
}
