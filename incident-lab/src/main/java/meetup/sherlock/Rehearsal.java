package meetup.sherlock;

public final class Rehearsal {
    public static void main(String[] args) throws Exception {
        System.out.println("REHEARSAL — scripted tool calls, NO LLM. Synthetic incident snapshot at 14:42.");
        String mode = args.length == 0 ? "false-lead" : args[0];
        var lab = DemoSupport.lab(mode.equals("unavailable") ? "DIAGNOSTIC_UNAVAILABLE" : "FALSE_LEAD");
        if (mode.equals("approval")) {
            lab.restartService("payment");
            DemoSupport.approvals(lab);
            return;
        }
        if (!mode.equals("false-lead") && !mode.equals("unavailable") && !mode.equals("budget"))
            throw new IllegalArgumentException("Modes: false-lead, unavailable, approval, budget");
        try {
            lab.getMetrics("checkout");
            lab.getLogs("checkout", "timeout");
            lab.getServiceInfo("payment");
            lab.getDeployments();
            System.out.println("\nSCRIPTED HYPOTHESIS: deployment payment v1.5 caused the incident. Needs evidence.");
            lab.runDiagnostic("payment", "VERSION_BREAKDOWN");
            System.out.println("\nSCRIPTED UPDATE: both versions affected; inspect common dependency.");
            lab.getMetrics("payments-db");
            lab.runDiagnostic("payment", "THREAD_DUMP");
            lab.runDiagnostic("payments-db", "DB_LOCKS");
            if (mode.equals("unavailable")) {
                lab.runDiagnostic("payments-db", "DB_LOCKS");
                System.out.println("\nSCRIPTED REPORT: DB contention suspected; lock owner not verified. Escalate to database owner.");
            } else if (mode.equals("budget")) {
                while (true) lab.runDiagnostic("payments-db", "DB_LOCKS");
            } else {
                System.out.println("\nSCRIPTED REPORT: transaction 884 from reporting-job blocks payment_orders. Ask the DB owner to assess terminating/rolling back that transaction; then verify errors, latency and lock waiters. Restart payment will not remove the external blocker.");
            }
        } catch (IncidentLab.BudgetExceeded e) { System.out.println("\nINCOMPLETE: " + e.getMessage()); }
    }
}
