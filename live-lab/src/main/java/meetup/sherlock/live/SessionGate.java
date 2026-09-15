package meetup.sherlock.live;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Human-approved pg_terminate_backend. Only a session that currently blocks others; approval is bound to pid + backend start. */
public final class SessionGate {
    public record Session(int pid, String backendStart, String application, String state, int blocked, String query) {}
    public interface Database {
        Session inspect(int pid);
        boolean terminate(int pid);
    }
    private final Database db;
    private final RestartGate.Approval approval;
    private final BooleanSupplier active;
    private final Set<Integer> decided = new HashSet<>();
    private boolean closed;
    public SessionGate(Database db, RestartGate.Approval approval, BooleanSupplier active) {
        this.db = db; this.approval = approval; this.active = active;
    }
    public String terminate(int pid, String reason) {
        synchronized (this) { if (closed || !active.getAsBoolean()) return "CANCELLED: investigation is closed"; }
        Session session = db.inspect(pid);
        if (session == null) return "DENIED: no session with pid " + pid + " in database sherlock";
        if (session.blocked() == 0) return "DENIED: pid " + pid + " blocks no other session; only blocking sessions may be terminated";
        synchronized (this) { if (!decided.add(pid)) return "ALREADY_DECIDED: this session cannot be requested again in this run"; }
        String target = "pid=" + pid + " application=" + session.application() + " state=" + session.state()
            + " blocks=" + session.blocked() + " query=" + clean(session.query(), 120);
        var request = new RestartGate.Request(UUID.randomUUID().toString(),
            new RestartGate.Target("payments-db", target, session.backendStart(), "sherlock-live"), clean(reason, 800), "TERMINATE POSTGRESQL SESSION");
        RestartGate.Decision decision = approval.ask(request);
        if (decision != RestartGate.Decision.APPROVE) return decision == RestartGate.Decision.TIMEOUT
            ? "TIMEOUT: no approval received; session NOT terminated"
            : "REJECTED: human declined; session NOT terminated. Do not request it again in this run.";
        Session current = db.inspect(pid);
        if (current == null || !current.backendStart().equals(session.backendStart()) || current.blocked() == 0)
            return "STALE: session ended, changed or no longer blocks others; NOT terminated";
        synchronized (this) {
            if (closed || !active.getAsBoolean()) return "CANCELLED: investigation stopped while waiting for human";
            return db.terminate(pid)
                ? "EXECUTED: human approved; pid " + pid + " terminated, its transaction rolled back. Recovery is NOT yet verified."
                : "FAILED: pg_terminate_backend returned false; state unknown, inspect database activity";
        }
    }
    private static String clean(String text, int max) {
        String value = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ");
        return value.substring(0, Math.min(max, value.length()));
    }
    public synchronized void close() { closed = true; }
}
