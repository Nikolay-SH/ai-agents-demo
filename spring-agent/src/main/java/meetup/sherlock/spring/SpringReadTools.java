package meetup.sherlock.spring;

import meetup.sherlock.live.LiveSession;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Read-only diagnostics for the ladder steps. Output is raw or compact depending on the LiveSession. */
public final class SpringReadTools {

    private static final String SERVICE = "checkout | payment | payments-db";
    private final LiveSession lab;
    public SpringReadTools(LiveSession lab) { this.lab = lab; }

    @Tool(description = "List Docker Compose services of the incident project and their state")
    public String listServices() { return lab.listServices(); }

    @Tool(description = "Container state, declared dependencies and health of a service")
    public String getServiceInfo(@ToolParam(description = SERVICE) String service) { return lab.getServiceInfo(service); }

    @Tool(description = "Request rate, server errors and latency of checkout or payment; for payments-db returns database sessions")
    public String getMetrics(@ToolParam(description = SERVICE) String service) { return lab.getMetrics(service); }

    @Tool(description = "Recent log lines of a service that contain a literal, case-insensitive query")
    public String getLogs(@ToolParam(description = SERVICE) String service,
                          @ToolParam(description = "Literal text to search; empty string returns all recent lines", required = false) String query) {
        return lab.getLogs(service, query == null ? "" : query);
    }

    @Tool(description = "Current PostgreSQL sessions: state, lock waits and which pid blocks which")
    public String getDatabaseActivity() { return lab.getDatabaseActivity(); }
}
