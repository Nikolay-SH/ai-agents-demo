package meetup.sherlock.live;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RestartGateTest {
    static class Backend implements RestartGate.Backend {
        RestartGate.Target target = new RestartGate.Target("payment", "a".repeat(64), "boot-1", "sherlock-live");
        int restarts;
        public RestartGate.Target inspect(String service) { return target; }
        public void restart(RestartGate.Target target, RestartGate.Dispatch dispatch) { restarts++; }
    }
    @Test void rejectionPreventsRestartAndCannotBeRepeated() {
        var backend = new Backend(); var asks = new AtomicInteger();
        var gate = new RestartGate(backend, request -> { asks.incrementAndGet(); return RestartGate.Decision.REJECT; });
        assertTrue(gate.restart("payment", "pool closed").contains("REJECTED"));
        assertTrue(gate.restart("payment", "try again").contains("ALREADY_DECIDED"));
        assertEquals(0, backend.restarts); assertEquals(1, asks.get());
    }
    @Test void approvalRestartsExactlyTheInspectedContainerOnce() {
        var backend = new Backend();
        var gate = new RestartGate(backend, request -> {
            assertEquals("payment", request.target().service());
            assertEquals(backend.target.id(), request.target().id());
            assertEquals("sherlock-live", request.target().project());
            assertFalse(request.id().isBlank());
            return RestartGate.Decision.APPROVE;
        });
        assertTrue(gate.restart("payment", "pool closed").contains("EXECUTED"));
        assertTrue(gate.restart("payment", "again").contains("ALREADY_DECIDED"));
        assertEquals(1, backend.restarts);
    }
    @Test void timeoutPreventsExecution() {
        var backend = new Backend();
        var gate = new RestartGate(backend, r -> RestartGate.Decision.TIMEOUT);
        assertTrue(gate.restart("payment", "pool closed").contains("TIMEOUT"));
        assertEquals(0, backend.restarts);
    }
    @Test void changedContainerOrBootInvalidatesApproval() {
        for (boolean newId : new boolean[]{true,false}) {
            var backend = new Backend();
            var gate = new RestartGate(backend, r -> {
                backend.target = new RestartGate.Target("payment", newId ? "b".repeat(64) : r.target().id(), "boot-2", "sherlock-live");
                return RestartGate.Decision.APPROVE;
            });
            assertTrue(gate.restart("payment", "pool closed").contains("STALE"));
            assertEquals(0, backend.restarts);
        }
    }
    @Test void unrelatedProjectAndDatabaseNeverReachConfirmation() {
        var backend = new Backend();
        var gate = new RestartGate(backend, r -> fail("No confirmation should be shown"));
        assertTrue(gate.restart("payments-db", "restart db").contains("DENIED"));
        assertTrue(gate.restart("payment; rm -rf /", "bad service").contains("DENIED"));
        backend.target = new RestartGate.Target("payment", "a".repeat(64), "boot-1", "other-project");
        assertTrue(gate.restart("payment", "pool closed").contains("DENIED"));
        assertEquals(0, backend.restarts);
    }
    @Test void finalDispatchChecksBudgetAfterBackendInspection() {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var backend = new Backend() {
            public void restart(RestartGate.Target target, RestartGate.Dispatch dispatch) {
                active.set(false);
                var failure = assertThrows(IllegalStateException.class,
                    () -> dispatch.start(new ProcessBuilder("must-never-execute")));
                assertTrue(failure.getMessage().contains("CANCELLED"));
            }
        };
        var gate = new RestartGate(backend, r -> RestartGate.Decision.APPROVE, active::get);
        gate.restart("payment", "pool closed");
        assertEquals(0, backend.restarts);
    }
    @Test void cancellationDuringReinspectionPreventsExecution() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var backend = new Backend() {
            int inspections;
            public RestartGate.Target inspect(String service) {
                if (++inspections == 2) {
                    entered.countDown();
                    try { assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                return target;
            }
        };
        var gate = new RestartGate(backend, r -> RestartGate.Decision.APPROVE);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var result = executor.submit(() -> gate.restart("payment", "pool closed"));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var closing = executor.submit(gate::close);
            try { closing.get(300, java.util.concurrent.TimeUnit.MILLISECONDS); }
            finally { release.countDown(); }
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS).contains("CANCELLED"));
            assertEquals(0, backend.restarts);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void cancellationWhileHumanDecidesPreventsExecution() {
        var backend = new Backend();
        var holder = new RestartGate[1];
        holder[0] = new RestartGate(backend, r -> { holder[0].close(); return RestartGate.Decision.APPROVE; });
        assertTrue(holder[0].restart("payment", "pool closed").contains("CANCELLED"));
        assertEquals(0, backend.restarts);
    }
}
