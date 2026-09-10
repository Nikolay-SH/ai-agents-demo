package meetup.sherlock.spring;

import meetup.sherlock.live.LiveSession;
import org.springframework.ai.tool.annotation.Tool;

public final class SpringLiveTools {
    private final LiveSession lab;
    public SpringLiveTools(LiveSession lab) { this.lab = lab; }
    @Tool(description = "List actual Docker Compose services")
    public String listServices() { return lab.listServices(); }
    @Tool(description = "Get real container identity, dependencies and health")
    public String getServiceInfo(String service) { return lab.getServiceInfo(service); }
    @Tool(description = "Get real HTTP counters, uptime and JVM metrics since startup")
    public String getMetrics(String service) { return lab.getMetrics(service); }
    @Tool(description = "Read recent actual Docker logs, filtered by literal query; empty query returns all")
    public String getLogs(String service, String query) { return lab.getLogs(service, query); }
    @Tool(description = "Read current PostgreSQL sessions and blocking PIDs with a fixed read-only query")
    public String getDatabaseActivity() { return lab.getDatabaseActivity(); }
    @Tool(description = "Request a REAL restart of checkout or payment. Blocks for human console approval. Rejection means no restart. After EXECUTED verify recovery with tools")
    public String restartService(String service, String reason) { return lab.restartService(service, reason); }
}
