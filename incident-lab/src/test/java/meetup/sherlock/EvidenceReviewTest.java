package meetup.sherlock;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceReviewTest {
    private IncidentLab lab(IncidentLab.Scenario scenario) {
        return new IncidentLab(scenario, 16, Duration.ofSeconds(30), e -> {});
    }
    @Test void symptomOnlyDraftTriggersAnotherInvestigationWithExistingEvidence() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD);
        var attempts = new AtomicInteger();
        String report = EvidenceReview.investigate(request -> {
            if (attempts.getAndIncrement() == 0) {
                lab.getLogs("checkout", "timeout");
                return "Payment is slow [E1]";
            }
            assertTrue(request.contains("[E1]"));
            assertTrue(request.contains("timeout calling payment"));
            lab.runDiagnostic("payments-db", "DB_LOCKS");
            lab.runDiagnostic("payment", "VERSION_BREAKDOWN");
            return "Blocker identified [E2]; both versions affected [E3]";
        }, lab, "Investigate checkout");
        assertEquals(2, attempts.get());
        assertFalse(report.contains("INCOMPLETE"));
    }
    @Test void repeatedUnsupportedDraftsStopAfterThreeAttempts() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD);
        var attempts = new AtomicInteger();
        String report = EvidenceReview.investigate(request -> { attempts.incrementAndGet(); return "It is fixed"; }, lab, "Investigate");
        assertEquals(3, attempts.get());
        assertTrue(report.startsWith("INCOMPLETE"));
    }
    @Test void directDiagnosticCanAlsoSupplyEvidenceAboutBothAffectedVersions() {
        var lab = lab(IncidentLab.Scenario.FALSE_LEAD);
        lab.runDiagnostic("payments-db", "DB_LOCKS");
        assertEquals(EvidenceReview.Status.SUFFICIENT_TRACE, EvidenceReview.assess(lab.events()).status());
    }
    @Test void unavailableSourceDoesNotBecomeConfirmedEvenIfModelClaimsSuccess() {
        var lab = lab(IncidentLab.Scenario.DIAGNOSTIC_UNAVAILABLE);
        String report = EvidenceReview.investigate(request -> {
            lab.runDiagnostic("payments-db", "DB_LOCKS");
            return "Confirmed!";
        }, lab, "Investigate");
        assertTrue(report.startsWith("INCOMPLETE"));
    }
    @Test void citationIdsMustExistButThisDoesNotValidateTheirMeaning() {
        assertTrue(EvidenceReview.referencesExist("Observation [E1]", 2));
        assertFalse(EvidenceReview.referencesExist("Observation [E3]", 2));
        assertFalse(EvidenceReview.referencesExist("Observation [E0]", 2));
        assertFalse(EvidenceReview.referencesExist("No citations", 2));
        assertFalse(EvidenceReview.referencesExist("[E999999999999999999999]", 2));
    }
}
