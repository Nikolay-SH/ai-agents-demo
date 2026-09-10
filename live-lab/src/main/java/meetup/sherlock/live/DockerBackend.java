package meetup.sherlock.live;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed Compose project and argument-vector commands; never executes LLM-provided shell. */
public final class DockerBackend implements RestartGate.Backend {
    private final Path root;
    private final Gson gson = new Gson();
    public DockerBackend(Path root) {
        this.root = root.toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.root.resolve("compose.yaml"))) throw new IllegalArgumentException("Run from project root or set SHERLOCK_PROJECT_DIR");
    }
    private List<String> compose(String... arguments) {
        var result = new ArrayList<>(List.of("docker", "compose", "--project-directory", root.toString(),
            "-f", root.resolve("compose.yaml").toString(), "-p", "sherlock-live"));
        result.addAll(Arrays.asList(arguments)); return result;
    }
    public String listServices() {
        String format = "{\"service\":{{json .Service}},\"state\":{{json .State}},\"health\":{{json .Health}},\"image\":{{json .Image}},\"id\":{{json .ID}}}";
        return run(compose("ps", "--all", "--format", format), Duration.ofSeconds(10));
    }
    public RestartGate.Target inspect(String service) {
        checkService(service);
        String id = run(compose("ps", "--all", "--quiet", service), Duration.ofSeconds(10)).strip();
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalStateException("Expected exactly one existing container for " + service);
        String format = "{\"id\":{{json .Id}},\"startedAt\":{{json .State.StartedAt}},\"project\":{{json (index .Config.Labels \"com.docker.compose.project\")}},\"service\":{{json (index .Config.Labels \"com.docker.compose.service\")}}}";
        JsonObject obj = JsonParser.parseString(run(List.of("docker", "inspect", "--format", format, id), Duration.ofSeconds(10))).getAsJsonObject();
        var target = new RestartGate.Target(obj.get("service").getAsString(), obj.get("id").getAsString(),
            obj.get("startedAt").getAsString(), obj.get("project").getAsString());
        if (!"sherlock-live".equals(target.project()) || !service.equals(target.service())) throw new IllegalStateException("Container outside demo project");
        return target;
    }
    public String state(String service) {
        var target = inspect(service);
        String state = run(List.of("docker", "inspect", "--format", "{{json .State}}", target.id()), Duration.ofSeconds(10));
        return gson.toJson(target) + "\nDocker state: " + state;
    }
    public String logs(String service, String query) {
        var target = inspect(service);
        String output = run(List.of("docker", "logs", "--since", "5m", "--tail", "120", "--timestamps", target.id()), Duration.ofSeconds(10));
        String search = query == null ? "" : query.toLowerCase(Locale.ROOT);
        var matches = output.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(search)).toList();
        if (matches.isEmpty()) return "NO_MATCHES in the last 120 log lines / 5 minutes";
        String excerpt = String.join("\n", matches.subList(Math.max(0, matches.size() - 20), matches.size()));
        return "Latest " + Math.min(20, matches.size()) + " matching lines (searched last 120 lines / 5 minutes):\n"
            + excerpt.substring(Math.max(0, excerpt.length() - 8000));
    }
    public void restart(RestartGate.Target target, RestartGate.Dispatch dispatch) {
        if (!Set.of("checkout", "payment").contains(target.service()) || !"sherlock-live".equals(target.project())
            || !target.id().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Restart target not allowed");
        // Validate again immediately before dispatch; operate on immutable container ID, not a name.
        if (!target.equals(inspect(target.service()))) throw new IllegalStateException("Container changed before dispatch");
        run(List.of("docker", "restart", "--time", "10", target.id()), Duration.ofSeconds(30), dispatch);
    }
    private static void checkService(String service) {
        if (!Set.of("checkout", "payment", "payments-db").contains(service)) throw new IllegalArgumentException("Services: checkout, payment, payments-db");
    }
    private String run(List<String> command, Duration timeout) {
        return run(command, timeout, ProcessBuilder::start);
    }
    private String run(List<String> command, Duration timeout, RestartGate.Dispatch dispatch) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("sherlock-docker-", ".out");
            process = dispatch.start(new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS);
                throw new IllegalStateException("Docker command timeout; execution outcome may be unknown, inspect state before retrying");
            }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IllegalStateException("Docker command failed: " + text.substring(0, Math.min(text.length(), 1200)));
            return text;
        } catch (IOException e) { throw new IllegalStateException("Cannot execute Docker CLI", e); }
        catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt(); throw new IllegalStateException("Docker command interrupted; inspect state before retrying");
        } finally {
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) {}
        }
    }
}
