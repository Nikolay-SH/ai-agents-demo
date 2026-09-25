package meetup.sherlock.live;

import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

public final class LiveSession implements AutoCloseable {
    public record Event(int id, String tool, String arguments, String result) {}
    private final ActiveBudget budget;
    private final ConsoleApproval console;
    private final RestartGate gate;
    private final SessionGate sessions;
    private final LiveInfrastructure infra;
    private int sequence;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, String> actions = new ConcurrentHashMap<>();
    /** compact=true: tools return short facts instead of raw payloads. RUN_TIMEOUT_SECONDS overrides the default active time. */
    public LiveSession(boolean compact, int defaultTimeoutSeconds) {
        var docker = new DockerBackend(Path.of(System.getenv().getOrDefault("SHERLOCK_PROJECT_DIR", ".")));
        infra = new LiveInfrastructure(docker, compact);
        budget = new ActiveBudget(Integer.parseInt(System.getenv().getOrDefault("MAX_TOOL_CALLS", "24")),
            Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("RUN_TIMEOUT_SECONDS", String.valueOf(defaultTimeoutSeconds)))));
        console = new ConsoleApproval(new InputStreamReader(System.in), System.out,
            Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("APPROVAL_TIMEOUT_SECONDS", "120"))));
        RestartGate.Approval human = request -> {
            budget.pause();
            try { return console.ask(request); } finally { budget.resume(); }
        };
        gate = new RestartGate(docker, human, () -> !budget.expired());
        sessions = new SessionGate(infra, human, () -> !budget.expired());
    }
    private synchronized String call(String name, String args, Supplier<String> action) {
        budget.charge();
        int id = ++sequence;
        System.out.printf("%n[E%d] %s(%s) — %s%n", id, name, args.replaceAll("\\p{Cntrl}", " "), Instant.now());
        String result;
        try { result = action.get(); }
        catch (RuntimeException e) { result = "TOOL_ERROR: " + e.getMessage(); }
        System.out.println(result);
        events.add(new Event(id, name, args, result));
        return "[E" + id + "] " + result;
    }
    /** Evidence journal written by code; strategy checks model claims against it. */
    public List<Event> events() { return List.copyOf(events); }
    public List<String> dependencies(String service) { return infra.dependencies(service); }
    public String listServices() { return call("listServices", "", infra::listServices); }
    public String getServiceInfo(String service) { return call("getServiceInfo", service, () -> infra.getServiceInfo(service)); }
    public String getMetrics(String service) { return call("getMetrics", service, () -> infra.getMetrics(service)); }
    public String getLogs(String service, String query) { return call("getLogs", service + ", query=" + query, () -> infra.getLogs(service, query)); }
    public String getDatabaseActivity() { return call("getDatabaseActivity", "", infra::getDatabaseActivity); }
    public String restartService(String service, String reason) {
        return call("restartService", service, () -> {
            String result = action("restart " + service, () -> gate.restart(service, reason));
            return result.startsWith("EXECUTED") ? result + "\n" + infra.awaitReady(service) : result;
        });
    }
    public String terminateSession(int pid, String reason) {
        return call("terminateSession", "pid=" + pid, () -> action("terminate pid " + pid, () -> sessions.terminate(pid, reason)));
    }
    private String action(String label, Supplier<String> action) {
        int evidenceId = sequence;
        actions.put(evidenceId, label + ": REQUESTED; outcome pending or unknown");
        String result = action.get();
        actions.put(evidenceId, label + ": " + result);
        return result;
    }
    public String run(Function<String, String> agent, String question) {
        try { return audited(turn(agent, question)); } finally { close(); }
    }
    /** Multi-turn conversation: the operator answers after each reply; typing time is not charged to the budget. */
    public void chat(Function<String, String> firstTurn, Function<String, String> nextTurn, String question) {
        try {
            String reply = turn(firstTurn, question);
            while (true) {
                System.out.println("\n" + reply);
                if (budget.expired()) break;
                budget.pause();
                String next;
                try { next = console.readLine("\noperator (Enter — завершить)> "); } finally { budget.resume(); }
                if (next == null || next.isBlank()) break;
                reply = turn(nextTurn, next);
            }
            System.out.println(audited(""));
        } finally { close(); }
    }
    private String turn(Function<String, String> agent, String question) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "live-agent"); t.setDaemon(true); return t; });
        Future<String> result = worker.submit(() -> agent.apply(question));
        try {
            while (!budget.expired()) {
                try { return result.get(500, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) {}
            }
            budget.close(); gate.close(); sessions.close(); result.cancel(true);
            return "INCOMPLETE: active investigation deadline reached; no further tools or approvals allowed.";
        } catch (ExecutionException e) {
            return "INCOMPLETE: agent failed (" + e.getCause().getClass().getSimpleName() + "). Review the collected evidence.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return "INCOMPLETE: interrupted";
        } finally { result.cancel(true); worker.shutdownNow(); }
    }
    private String audited(String report) {
        String log = actions.isEmpty() ? "restartService/terminateSession НЕ вызывались; агент ничего не менял."
            : new java.util.TreeMap<>(actions).toString().replaceAll("\\p{Cntrl}", " ");
        return report + "\n\n=== ФАКТИЧЕСКИЙ ЖУРНАЛ ДЕЙСТВИЙ (код, не LLM) ===\n" + log;
    }
    public void close() { budget.close(); gate.close(); sessions.close(); console.close(); }
}
