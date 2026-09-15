package meetup.sherlock.live;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Real data sources. compact=false returns raw payloads; compact=true returns short facts (context engineering step). */
public final class LiveInfrastructure implements SessionGate.Database {
    private static final String DB_URL = System.getenv().getOrDefault("LIVE_DB_URL", "jdbc:postgresql://127.0.0.1:15432/sherlock?connectTimeout=3&socketTimeout=5&ApplicationName=sherlock-observer");
    private static final String PROMETHEUS = System.getenv().getOrDefault("PROMETHEUS_URL", "http://127.0.0.1:19090");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final DockerBackend docker;
    private final boolean compact;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    public LiveInfrastructure(DockerBackend docker) { this(docker, false); }
    public LiveInfrastructure(DockerBackend docker, boolean compact) { this.docker = docker; this.compact = compact; }
    public String listServices() { return docker.listServices(); }

    public String getServiceInfo(String service) {
        if (!compact) {
            String state = docker.state(service);
            return service.equals("payments-db") ? state + "\nUse getDatabaseActivity for live DB sessions."
                : state + "\nStatus: " + get(service, "/demo/status") + "\nHealth: " + get(service, "/actuator/health");
        }
        JsonObject state = docker.dockerState(service);
        String container = service + ": container " + state.get("Status").getAsString() + " since " + state.get("StartedAt").getAsString();
        if (service.equals("payments-db")) return container + "; PostgreSQL, client: payment. Sessions and locks: getDatabaseActivity.";
        JsonObject status = json(service, "/demo/status");
        return container + "; bootId=" + (status == null ? "unknown" : status.get("bootId").getAsString())
            + "; dependencies=" + dependencies(service) + "; health=" + health(json(service, "/actuator/health"));
    }

    public String getMetrics(String service) {
        if (service.equals("payments-db")) return getDatabaseActivity();
        String uri = switch (service) { case "checkout" -> "/checkout"; case "payment" -> "/payments"; default -> throw new IllegalArgumentException("Services: checkout, payment, payments-db"); };
        return compact ? compactMetrics(service, uri) : rawMetrics(service, uri);
    }
    private String rawMetrics(String service, String uri) {
        String tag = URLEncoder.encode("uri:" + uri, StandardCharsets.UTF_8);
        var result = new LinkedHashMap<String, String>();
        result.put("collectedAt", java.time.Instant.now().toString());
        result.put("status", get(service, "/demo/status"));
        result.put("requests_since_boot", get(service, "/actuator/metrics/http.server.requests?tag=" + tag));
        result.put("server_errors_since_boot", get(service, "/actuator/metrics/http.server.requests?tag=" + tag + "&tag=outcome%3ASERVER_ERROR"));
        result.put("uptime_seconds", get(service, "/actuator/metrics/process.uptime"));
        result.put("jvm_memory_bytes", get(service, "/actuator/metrics/jvm.memory.used"));
        if (service.equals("payment")) {
            result.put("pool_active", get(service, "/actuator/metrics/hikaricp.connections.active"));
            result.put("pool_pending", get(service, "/actuator/metrics/hikaricp.connections.pending"));
        }
        result.put("interpretation", "COUNTERS SINCE BOOT, not a rolling error rate. 404 means no matching meter, not guaranteed zero. After restart use new bootId and collect another sample.");
        return gson.toJson(result);
    }
    private String compactMetrics(String service, String uri) {
        String all = "{application=\"" + service + "\",uri=\"" + uri + "\"}";
        String errors = "{application=\"" + service + "\",uri=\"" + uri + "\",outcome=\"SERVER_ERROR\"}";
        try {
            var out = new StringBuilder(service + " POST " + uri + " (Prometheus, scrape every 5s):");
            for (String window : List.of("1m", "20s")) {
                Double rps = prom("sum(rate(http_server_requests_seconds_count" + all + "[" + window + "]))");
                Double failed = prom("sum(rate(http_server_requests_seconds_count" + errors + "[" + window + "]))");
                out.append("\n  last ").append(window).append(": ").append(rps == null || rps == 0 ? "no requests"
                    : String.format(Locale.ROOT, "%.2f req/s, 5xx %.0f%%", rps, 100 * (failed == null ? 0 : failed) / rps));
            }
            Double p95 = prom("histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket" + all + "[1m])))");
            out.append("\n  p95 latency (1m): ").append(p95 == null || p95.isNaN() ? "n/a" : String.format(Locale.ROOT, "%.2fs", p95));
            if (service.equals("payment")) {
                Double active = prom("sum(hikaricp_connections_active{application=\"payment\"})");
                Double pending = prom("sum(hikaricp_connections_pending{application=\"payment\"})");
                out.append("\n  JDBC pool: ").append(active == null ? "no pool metrics (pool closed or not created)"
                    : String.format(Locale.ROOT, "active=%.0f, waiting for connection=%.0f", active, pending == null ? 0 : pending));
            }
            JsonObject status = json(service, "/demo/status");
            return out.append("\n  bootId=").append(status == null ? "unavailable" : status.get("bootId").getAsString())
                .append(". After a restart or fix wait ~20s before judging the 20s window.").toString();
        } catch (IllegalStateException e) { return "PROMETHEUS_UNAVAILABLE: " + e.getMessage(); }
    }

    public String getLogs(String service, String query) {
        if (!compact) return docker.logs(service, query);
        var lines = docker.logLines(service, query);
        if (lines.isEmpty()) return "NO_MATCHES in the last 120 log lines / 5 minutes";
        record Group(int count, String first, String last) {}
        var groups = new LinkedHashMap<String, Group>();
        for (String line : lines) {
            String time = line.length() > 19 ? line.substring(11, 19) : "";
            // Drop Docker/app timestamps and thread, mask ids: identical failures collapse into one line.
            String message = line.replaceFirst("^\\S+\\s+(\\d{4}-\\d\\d-\\d\\dT\\S+\\s+)?", "").replaceFirst("\\[[^\\]]*\\]\\s+", "");
            message = UUID_TEXT.matcher(message).replaceAll("<id>");
            groups.merge(message.substring(0, Math.min(300, message.length())), new Group(1, time, time), (a, b) -> new Group(a.count() + 1, a.first(), b.last()));
        }
        var out = new StringBuilder(lines.size() + " matching lines (last 120 lines / 5 min), " + groups.size() + " distinct:");
        groups.entrySet().stream().limit(10).forEach(e -> out.append(String.format("%n×%d %s–%s %s", e.getValue().count(), e.getValue().first(), e.getValue().last(), e.getKey())));
        return out.toString();
    }

    public String getDatabaseActivity() {
        try (Connection connection = connect()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                if (!compact) {
                    ResultSet rs = statement.executeQuery("""
                        SELECT pid, application_name, state, wait_event_type, wait_event,
                               pg_blocking_pids(pid)::text AS blocking_pids,
                               (clock_timestamp() - query_start)::text AS query_age,
                               left(query, 180) AS query
                        FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid()
                        ORDER BY query_start LIMIT 20
                        """);
                    var rows = new ArrayList<Map<String, Object>>();
                    while (rs.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                    return gson.toJson(rows);
                }
                // Waiters come and go (payment retries every ~3s): sample up to 3s until one is seen. Held table locks are always visible.
                String snapshot = "";
                for (int sample = 0; sample < 6 && !snapshot.contains("WAITING FOR LOCK"); sample++) {
                    if (sample > 0) try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    snapshot = compactSessions(statement.executeQuery("""
                        SELECT a.pid, a.application_name, a.state, a.wait_event_type, pg_blocking_pids(a.pid) AS blocked_by,
                               extract(epoch FROM clock_timestamp() - a.xact_start)::int AS xact_seconds,
                               left(regexp_replace(a.query, '\\s+', ' ', 'g'), 100) AS query,
                               (SELECT string_agg(l.mode || ' on ' || c.relname, ', ') FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                                WHERE l.pid = a.pid AND l.granted AND c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace) AS holds
                        FROM pg_stat_activity a
                        WHERE a.datname = current_database() AND a.pid <> pg_backend_pid()
                        ORDER BY a.backend_start LIMIT 50
                        """));
                }
                return snapshot;
            }
        } catch (SQLException e) { return "DATABASE_UNAVAILABLE: " + e.getSQLState() + "; diagnostic query did not complete"; }
    }
    private static String compactSessions(ResultSet rs) throws SQLException {
        record Row(int pid, String app, String state, String waitType, List<Integer> blockedBy, Integer xact, String query, String holds) {}
        var rows = new ArrayList<Row>();
        var blocks = new HashMap<Integer, List<Integer>>();
        while (rs.next()) {
            var blockedBy = Arrays.asList((Integer[]) rs.getArray("blocked_by").getArray());
            int pid = rs.getInt("pid");
            blockedBy.forEach(b -> blocks.computeIfAbsent(b, k -> new ArrayList<>()).add(pid));
            rows.add(new Row(pid, rs.getString("application_name"), rs.getString("state"), rs.getString("wait_event_type"),
                blockedBy, (Integer) rs.getObject("xact_seconds"), rs.getString("query"), rs.getString("holds")));
        }
        var out = new StringBuilder(rows.size() + " sessions in database sherlock" + (rows.isEmpty() ? " (no client is connected)" : ":"));
        var quiet = new TreeMap<String, Integer>();
        for (Row r : rows) {
            var blocking = blocks.getOrDefault(r.pid(), List.of());
            if (blocking.isEmpty() && r.blockedBy().isEmpty() && r.holds() == null && "idle".equals(r.state())) { quiet.merge(r.app() + " idle", 1, Integer::sum); continue; }
            out.append("\npid ").append(r.pid()).append(' ').append(r.app().isEmpty() ? "(no app name)" : r.app()).append(": ").append(r.state());
            if ("Lock".equals(r.waitType())) out.append(", WAITING FOR LOCK, blocked by ").append(r.blockedBy());
            if (r.holds() != null) out.append(", holds ").append(r.holds());
            if (!blocking.isEmpty()) out.append(", BLOCKS ").append(blocking.size()).append(" session(s) ").append(blocking);
            if (r.xact() != null) out.append(", transaction open ").append(r.xact()).append('s');
            out.append(", query: ").append(r.query());
        }
        if (!quiet.isEmpty()) out.append("\nidle, no locks involved: ").append(quiet);
        return out.toString();
    }

    /** A lock holder blocks others only while someone waits (payment retries every ~3s), so sample for up to 3s. */
    public SessionGate.Session inspect(int pid) {
        SessionGate.Session session = null;
        for (int sample = 0; sample < 6; sample++) {
            session = inspectOnce(pid);
            if (session == null || session.blocked() > 0) return session;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return session; }
        }
        return session;
    }
    private SessionGate.Session inspectOnce(int pid) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT a.backend_start::text, a.application_name, a.state, left(a.query, 120),
                       (SELECT count(*) FROM pg_stat_activity b WHERE a.pid = ANY(pg_blocking_pids(b.pid)))::int
                FROM pg_stat_activity a
                WHERE a.pid = ? AND a.datname = current_database() AND a.pid <> pg_backend_pid()
                """)) {
            statement.setQueryTimeout(3);
            statement.setInt(1, pid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new SessionGate.Session(pid, rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(5), rs.getString(4)) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("DATABASE_UNAVAILABLE: " + e.getSQLState()); }
    }
    public boolean terminate(int pid) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setQueryTimeout(3);
            statement.setInt(1, pid);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        } catch (SQLException e) { throw new IllegalStateException("DATABASE_UNAVAILABLE: " + e.getSQLState() + "; outcome unknown, inspect database activity"); }
    }
    private static Connection connect() throws SQLException { return DriverManager.getConnection(DB_URL, "sherlock", "sherlock-demo"); }

    /** Declared dependencies from the live /demo/status endpoint; empty for the database or unknown names. */
    public List<String> dependencies(String service) {
        if (!service.equals("checkout") && !service.equals("payment")) return List.of();
        JsonObject status = json(service, "/demo/status");
        var result = new ArrayList<String>();
        if (status != null) status.getAsJsonArray("dependencies").forEach(d -> result.add(d.getAsString()));
        return result;
    }
    public String awaitReady(String service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            String health = get(service, "/actuator/health");
            if (health.startsWith("HTTP 200")) return "Process health is UP. " + get(service, "/demo/status") + ". Verify request success separately.";
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return "Recovery not verified: service health did not become UP within readiness wait.";
    }
    private static String health(JsonObject health) {
        if (health == null) return "UNAVAILABLE";
        var parts = new ArrayList<String>();
        var components = health.getAsJsonObject("components");
        if (components != null) for (var entry : components.entrySet()) {
            var component = entry.getValue().getAsJsonObject();
            String status = component.get("status").getAsString();
            if (!entry.getKey().equals("db") && status.equals("UP")) continue;
            var details = component.getAsJsonObject("details");
            parts.add(entry.getKey() + "=" + status + (details != null && details.has("error") ? " (" + details.get("error").getAsString() + ")" : ""));
        }
        return health.get("status").getAsString() + (parts.isEmpty() ? "" : " " + parts);
    }
    private Double prom(String query) {
        try {
            var request = HttpRequest.newBuilder(URI.create(PROMETHEUS + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(3)).GET().build();
            var result = JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
                .getAsJsonObject().getAsJsonObject("data").getAsJsonArray("result");
            return result.isEmpty() ? null : Double.valueOf(result.get(0).getAsJsonObject().getAsJsonArray("value").get(1).getAsString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e.getClass().getSimpleName());
        }
    }
    /** Parsed body for HTTP 200/503 (health reports DOWN with 503), otherwise null. */
    private JsonObject json(String service, String path) {
        String response = get(service, path);
        if (!response.startsWith("HTTP 200 ") && !response.startsWith("HTTP 503 ")) return null;
        try { return JsonParser.parseString(response.substring(9)).getAsJsonObject(); }
        catch (RuntimeException e) { return null; }
    }
    private String get(String service, String path) {
        int port = switch (service) { case "checkout" -> 18081; case "payment" -> 18082; default -> throw new IllegalArgumentException("HTTP services: checkout/payment"); };
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(3)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return "HTTP " + response.statusCode() + " " + body.substring(0, Math.min(10000, body.length()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "HTTP_UNAVAILABLE: " + e.getClass().getSimpleName();
        }
    }
}
