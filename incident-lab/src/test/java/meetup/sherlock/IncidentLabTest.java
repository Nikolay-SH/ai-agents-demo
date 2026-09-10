package meetup.sherlock;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class IncidentLabTest {
    private IncidentLab lab(IncidentLab.Scenario scenario, int budget) {
        return new IncidentLab(scenario, budget, Duration.ofSeconds(90), e -> {});
    }
    @Test void evidenceSupportsSharedDependencyAndIdentifiesBlocker() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(lab.getLogs("checkout", "timeout").contains("payment"));
        assertTrue(lab.getDeployments().contains("14:32"));
        String versions = lab.runDiagnostic("payment", "VERSION_BREAKDOWN");
        assertTrue(versions.contains("v1.4 errors=48%, v1.5 errors=48%"));
        assertTrue(lab.getServiceInfo("payment").contains("payments-db"));
        String locks = lab.runDiagnostic("payments-db", "DB_LOCKS");
        assertTrue(locks.contains("blocker_tx=884"));
        assertTrue(locks.contains("application=reporting-job"));
        assertTrue(locks.contains("AccessExclusiveLock"));
    }
    @Test void advertisedDiagnosticsWorkForTheirServices() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(lab.runDiagnostic("checkout", "THREAD_DUMP").contains("HTTP responses from payment"));
        assertTrue(lab.runDiagnostic("payment", "THREAD_DUMP").contains("JDBC"));
        assertTrue(lab.runDiagnostic("payment", "VERSION_BREAKDOWN").contains("Both cohorts"));
        assertTrue(lab.runDiagnostic("payments-db", "DB_LOCKS").contains("blocker_tx"));
    }
    @Test void unavailableDiagnosticNeverReturnsFabricatedEvidence() {
        var lab = lab(IncidentLab.Scenario.DIAGNOSTIC_UNAVAILABLE, 3);
        String result = lab.runDiagnostic("payments-db", "DB_LOCKS");
        assertTrue(result.contains("UNAVAILABLE"));
        assertFalse(result.contains("884"));
        assertFalse(lab.getLogs("payments-db", "").contains("reporting-job"));
    }
    @Test void restartRequiresOneTimeOperatorDecisionAndDoesNotFixExternalBlocker() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(lab.restartService("payment").contains("PENDING_HUMAN_APPROVAL"));
        lab.restartService("payment");
        assertEquals(0, lab.restartCount());
        assertEquals(1, lab.approvals().size());
        String id = lab.approvals().get(0).id();
        assertThrows(IllegalArgumentException.class, () -> lab.decide("invented", true));
        lab.decide(id, true);
        assertEquals(1, lab.restartCount());
        assertThrows(IllegalArgumentException.class, () -> lab.decide(id, true));
        assertTrue(lab.getMetrics("payment").contains("errors=48%"));
    }
    @Test void rejectionAndDatabasePolicyPreventExecution() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(lab.restartService("payments-db").contains("DENIED"));
        assertTrue(lab.approvals().isEmpty());
        lab.restartService("checkout");
        lab.decide(lab.approvals().get(0).id(), false);
        assertEquals(0, lab.restartCount());
        assertTrue(lab.approvals().isEmpty());
    }
    @Test void toolBudgetLatchesAndPreventsLaterMutations() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 1);
        lab.getMetrics("payment");
        assertThrows(IncidentLab.BudgetExceeded.class, () -> lab.restartService("payment"));
        assertThrows(IncidentLab.BudgetExceeded.class, lab::getDeployments);
        assertEquals(1, lab.events().size());
        assertTrue(lab.approvals().isEmpty());
    }
    @Test void deadlinePreventsFurtherCallsWithoutSleeping() {
        var clock = new AtomicLong(0);
        var lab = new IncidentLab(IncidentLab.Scenario.FALSE_LEAD, 16, Duration.ofSeconds(2), e -> {}, clock::get);
        lab.getDeployments();
        clock.set(Duration.ofSeconds(2).toNanos());
        assertThrows(IncidentLab.BudgetExceeded.class, () -> lab.getMetrics("checkout"));
    }
    @Test void unknownServicesAndShellCommandsAreRejectedAndConsumeBudget() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(lab.getMetrics("made-up").contains("INVALID_ARGUMENT"));
        assertTrue(lab.runDiagnostic("payment", "rm -rf /").contains("INVALID_ARGUMENT"));
        assertTrue(lab.runDiagnostic("payment", "DB_LOCKS").contains("INVALID_ARGUMENT"));
        assertEquals(3, lab.events().size());
    }
    @Test void logSearchFiltersAndRunStateIsIsolated() {
        var one = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        var two = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        assertTrue(one.getLogs("checkout", "TIMEOUT").contains("request=c42"));
        assertTrue(one.getLogs("checkout", "unknown").contains("NO_MATCHES"));
        assertTrue(two.events().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> one.events().clear());
    }
    @Test void failedRunDiscardsPendingActionsAndLateToolCallsCannotExecute() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD, 16);
        lab.restartService("payment");
        lab.closeInvestigation(true);
        assertTrue(lab.approvals().isEmpty());
        assertThrows(IncidentLab.BudgetExceeded.class, () -> lab.restartService("checkout"));
        assertTrue(lab.operatorMetrics("payment").contains("errors=48%"));
        assertEquals(0, lab.restartCount());
    }
}
