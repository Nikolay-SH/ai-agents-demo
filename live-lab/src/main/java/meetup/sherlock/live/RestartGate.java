package meetup.sherlock.live;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class RestartGate {
    public record Target(String service, String id, String startedAt, String project) {}
    public record Request(String id, Target target, String reason) {}
    public enum Decision { APPROVE, REJECT, TIMEOUT }
    public interface Backend {
        Target inspect(String service);
        void restart(Target target, Dispatch dispatch);
    }
    @FunctionalInterface public interface Dispatch { Process start(ProcessBuilder builder) throws java.io.IOException; }
    @FunctionalInterface public interface Approval { Decision ask(Request request); }
    private final Backend backend;
    private final Approval approval;
    private final Set<String> decided = new HashSet<>();
    private volatile boolean closed;
    private final java.util.function.BooleanSupplier active;
    public RestartGate(Backend backend, Approval approval) { this(backend, approval, () -> true); }
    public RestartGate(Backend backend, Approval approval, java.util.function.BooleanSupplier active) {
        this.backend = backend; this.approval = approval; this.active = active;
    }
    private synchronized Process dispatch(ProcessBuilder builder) throws java.io.IOException {
        if (closed || !active.getAsBoolean()) throw new IllegalStateException("CANCELLED: investigation closed before dispatch");
        return builder.start();
    }
    public String restart(String service, String reason) {
        if (!Set.of("checkout", "payment").contains(service)) return "DENIED: only checkout/payment in sherlock-live may be restarted";
        Target target;
        synchronized (this) {
            if (closed) return "CANCELLED: investigation is closed";
            if (decided.contains(service)) return "ALREADY_DECIDED: this service cannot request another restart in this run";
            decided.add(service);
        }
        target = backend.inspect(service);
        if (!valid(target, service)) return "DENIED: container identity/project/service does not match";
        if (closed || !active.getAsBoolean()) return "CANCELLED: investigation closed";
        String cleanReason = reason == null ? "" : reason.replaceAll("\\p{Cntrl}", " ");
        Request request = new Request(UUID.randomUUID().toString(), target, cleanReason.substring(0, Math.min(800, cleanReason.length())));
        Decision decision = approval.ask(request);
        {
            if (closed || !active.getAsBoolean()) return "CANCELLED: investigation stopped while waiting for human";
            if (decision != Decision.APPROVE) return decision == Decision.TIMEOUT
                ? "TIMEOUT: no approval received; restart NOT executed"
                : "REJECTED: human declined; restart NOT executed. Do not request it again in this run.";
            Target current = backend.inspect(service);
            if (!target.equals(current) || !valid(current, service)) return "STALE: container changed since confirmation; restart NOT executed";
            if (closed || !active.getAsBoolean()) return "CANCELLED: investigation stopped during inspection";
            backend.restart(current, this::dispatch);
            return "EXECUTED: human approved restart of " + service + " container=" + current.id()
                + ". Restart command succeeded; recovery is NOT yet verified. Check live status and request metrics.";
        }
    }
    private boolean valid(Target target, String service) {
        return target != null && service.equals(target.service()) && "sherlock-live".equals(target.project())
            && target.id() != null && target.id().matches("[a-f0-9]{64}") && target.startedAt() != null;
    }
    public synchronized void close() { closed = true; }
}
