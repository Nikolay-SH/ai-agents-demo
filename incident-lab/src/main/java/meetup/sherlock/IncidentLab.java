package meetup.sherlock;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Fixed evidence snapshot, not a real production connection. New instance = new isolated run. */
public final class IncidentLab {
    public enum Scenario { FALSE_LEAD, DIAGNOSTIC_UNAVAILABLE }
    public enum Diagnostic { VERSION_BREAKDOWN, THREAD_DUMP, DB_LOCKS }
    public record Event(int step, String tool, String arguments, String result) {}
    public record Approval(String id, String service, String environment, String action) {}
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String reason) { super(reason); }
    }

    private final Scenario scenario;
    private final int maxCalls;
    private final long started;
    private final long durationNanos;
    private final LongSupplier clock;
    private final Consumer<Event> observer;
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Approval> pending = new LinkedHashMap<>();
    private boolean stopped;
    private boolean closed;
    private int restarts;

    public IncidentLab(Scenario scenario, int maxCalls, Duration duration, Consumer<Event> observer) {
        this(scenario, maxCalls, duration, observer, System::nanoTime);
    }
    IncidentLab(Scenario scenario, int maxCalls, Duration duration, Consumer<Event> observer, LongSupplier clock) {
        if (maxCalls < 1 || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Positive budgets required");
        this.scenario = Objects.requireNonNull(scenario);
        this.maxCalls = maxCalls;
        this.clock = clock;
        this.started = clock.getAsLong();
        this.durationNanos = duration.toNanos();
        this.observer = observer;
    }

    private synchronized String call(String tool, String arguments, Supplier<String> action) {
        if (closed || stopped || events.size() >= maxCalls || clock.getAsLong() - started >= durationNanos) {
            stopped = true;
            throw new BudgetExceeded("Investigation stopped: tool-call/time budget exhausted. Evidence is incomplete.");
        }
        String result;
        try { result = action.get(); }
        catch (IllegalArgumentException e) { result = "INVALID_ARGUMENT: " + e.getMessage(); }
        Event event = new Event(events.size() + 1, tool, arguments, result);
        events.add(event);
        observer.accept(event);
        return "[E" + event.step() + "] " + result;
    }

    private void service(String service) {
        if (!Set.of("checkout", "payment", "payments-db").contains(service))
            throw new IllegalArgumentException("Unknown service. Available: checkout, payment, payments-db");
    }
    public String getMetrics(String service) {
        return call("getMetrics", service, () -> {
            service(service);
            return metrics(service);
        });
    }
    private String metrics(String service) {
            return switch (service) {
                case "checkout" -> "window=14:37–14:42; cpu=42%; memory=61%; p95=8100ms; errors=47%; baseline errors=0.2%, p95=180ms";
                case "payment" -> "window=14:37–14:42; cpu=35%; memory=72%; p95=7800ms; errors=48%; baseline errors=0.1%, p95=95ms";
                default -> "window=14:37–14:42; cpu=22%; memory=68%; connections=100/100; lock_waiters=86; baseline lock_waiters=0";
            };
    }
    public String getLogs(String service, String query) {
        return call("getLogs", service + ", query=" + query, () -> {
            service(service);
            String logs = switch (service) {
                case "checkout" -> "14:37:04 ERROR request=c42 timeout calling payment POST /charge\n14:38:01 ERROR request=c81 timeout calling payment POST /charge";
                case "payment" -> "14:37:02 ERROR version=1.4 request=c42 SQL lock timeout updating payment_orders\n14:37:03 ERROR version=1.5 request=c81 SQL lock timeout updating payment_orders";
                default -> scenario == Scenario.DIAGNOSTIC_UNAVAILABLE
                    ? "14:37:02 WARN waiting sessions=86; transaction details unavailable in this log source"
                    : "14:36:58 WARN transaction=884 application=reporting-job lock acquired on payment_orders\n14:37:02 WARN transaction=884 still open; waiting sessions=86";
            };
            String found = logs.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .reduce((a,b) -> a + "\n" + b).orElse("NO_MATCHES (literal case-insensitive search; empty query returns all)");
            return found;
        });
    }
    public String getDeployments() {
        return call("getDeployments", "", () -> "14:32 payment canary 1.4 -> 1.5, 20% traffic; both versions still serving. No checkout deployment today. This is deployment history, not a root-cause verdict.");
    }
    public String getServiceInfo(String service) {
        return call("getServiceInfo", service, () -> {
            service(service);
            return switch (service) {
                case "checkout" -> "checkout -> payment; owner=Checkout team; environment=demo; diagnostics=THREAD_DUMP";
                case "payment" -> "payment -> payments-db; owner=Payments team; environment=demo; diagnostics=VERSION_BREAKDOWN,THREAD_DUMP";
                default -> "payments-db: PostgreSQL; clients=payment,reporting-job; owner=Database team; environment=demo; diagnostics=DB_LOCKS";
            };
        });
    }
    public String runDiagnostic(String service, String diagnosticType) {
        return call("runDiagnostic", service + ", " + diagnosticType, () -> {
            service(service);
            Diagnostic diagnostic;
            try { diagnostic = Diagnostic.valueOf(diagnosticType); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Allowed diagnostics: VERSION_BREAKDOWN, THREAD_DUMP, DB_LOCKS. Shell commands are not supported."); }
            return switch (diagnostic) {
                case VERSION_BREAKDOWN -> {
                    if (!service.equals("payment")) throw new IllegalArgumentException("VERSION_BREAKDOWN requires payment");
                    yield "14:32–14:36: v1.4 errors=0.1%, v1.5 errors=0.1%; 14:37–14:42: v1.4 errors=48%, v1.5 errors=48%. Both cohorts degrade at 14:37.";
                }
                case THREAD_DUMP -> switch (service) {
                    case "payment" -> "86 payment request threads waiting in JDBC executeUpdate(payment_orders); no application deadlock detected";
                    case "checkout" -> "47 checkout request threads waiting for HTTP responses from payment; no application deadlock detected";
                    default -> throw new IllegalArgumentException("THREAD_DUMP requires checkout or payment");
                };
                case DB_LOCKS -> {
                    if (!service.equals("payments-db")) throw new IllegalArgumentException("DB_LOCKS requires payments-db");
                    if (scenario == Scenario.DIAGNOSTIC_UNAVAILABLE)
                        yield "UNAVAILABLE: diagnostic endpoint timed out. No lock inspection result obtained. Do not infer the lock owner.";
                    yield "snapshot=14:42; blocker_tx=884; application=reporting-job; state=idle in transaction; started=14:36:58; table=payment_orders; mode=AccessExclusiveLock; blocked_sessions=86; blocked_clients=payment v1.4,payment v1.5";
                }
            };
        });
    }
    /** Model can request an action, never authorize it. Approval is deliberately absent from tool adapters. */
    public String restartService(String service) {
        return call("restartService", service, () -> {
            service(service);
            if (service.equals("payments-db")) return "DENIED: database restarts are outside this demo's action policy";
            Approval existing = pending.values().stream().filter(a -> a.service().equals(service)).findFirst().orElse(null);
            Approval approval = existing != null ? existing : new Approval(UUID.randomUUID().toString(), service, "demo", "restart");
            pending.put(approval.id(), approval);
            return "PENDING_HUMAN_APPROVAL id=" + approval.id() + " service=" + service + " environment=demo action=restart. NOT EXECUTED. Restart does not terminate transactions belonging to another DB client.";
        });
    }
    /** Trusted local operator path; cannot be invoked by the LLM. One-time decision. */
    public synchronized String decide(String id, boolean approved) {
        Approval action = pending.remove(id);
        if (action == null) throw new IllegalArgumentException("Unknown or already decided approval");
        if (!approved) return "REJECTED: " + action.service() + " unchanged";
        restarts++;
        return "SIMULATED_RESTART: " + action.service() + "; external database blocker remains. No real infrastructure was changed.";
    }
    public synchronized List<Event> events() { return List.copyOf(events); }
    public synchronized List<Approval> approvals() { return List.copyOf(pending.values()); }
    public synchronized int restartCount() { return restarts; }
    public synchronized boolean budgetExhausted() { return stopped; }
    public synchronized void closeInvestigation(boolean discardPending) {
        closed = true;
        if (discardPending) pending.clear();
    }
    /** Operator verification is outside the agent's completed investigation budget. */
    public synchronized String operatorMetrics(String service) {
        service(service);
        return metrics(service);
    }
}
