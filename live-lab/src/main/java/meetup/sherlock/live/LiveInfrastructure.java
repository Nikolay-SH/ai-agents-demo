package meetup.sherlock.live;

import com.google.gson.Gson;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.sql.*;
import java.util.*;

public final class LiveInfrastructure {
    private final DockerBackend docker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    public LiveInfrastructure(DockerBackend docker) { this.docker = docker; }
    public String listServices() { return docker.listServices(); }
    public String getServiceInfo(String service) {
        String state = docker.state(service);
        return service.equals("payments-db") ? state + "\nUse getDatabaseActivity for live DB sessions."
            : state + "\nStatus: " + get(service, "/demo/status") + "\nHealth: " + get(service, "/actuator/health");
    }
    public String getMetrics(String service) {
        if (service.equals("payments-db")) return getDatabaseActivity();
        String uri = service.equals("checkout") ? "/checkout" : "/payments";
        String tag = URLEncoder.encode("uri:" + uri, java.nio.charset.StandardCharsets.UTF_8);
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
    public String getLogs(String service, String query) { return docker.logs(service, query); }
    public String getDatabaseActivity() {
        String url = System.getenv().getOrDefault("LIVE_DB_URL", "jdbc:postgresql://127.0.0.1:15432/sherlock?connectTimeout=3&socketTimeout=5&ApplicationName=sherlock-observer");
        try (Connection connection = DriverManager.getConnection(url, "sherlock", "sherlock-demo")) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
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
        } catch (SQLException e) { return "DATABASE_UNAVAILABLE: " + e.getSQLState() + "; diagnostic query did not complete"; }
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
