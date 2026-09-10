package meetup.sherlock.spring;

import meetup.sherlock.IncidentLab;
import org.springframework.ai.tool.annotation.Tool;

public final class SpringTools {
    private final IncidentLab lab;
    public SpringTools(IncidentLab lab) { this.lab = lab; }
    @Tool(description = "Get CPU, memory, latency and error rate for a service")
    public String getMetrics(String service) { return lab.getMetrics(service); }
    @Tool(description = "Search service logs with a literal case-insensitive query; empty query returns all")
    public String getLogs(String service, String query) { return lab.getLogs(service, query); }
    @Tool(description = "Get recent deployments with timestamps and rollout details")
    public String getDeployments() { return lab.getDeployments(); }
    @Tool(description = "Get dependencies, owner and available diagnostics for a service")
    public String getServiceInfo(String service) { return lab.getServiceInfo(service); }
    @Tool(description = "Run a read-only diagnostic: VERSION_BREAKDOWN for payment, THREAD_DUMP for checkout or payment, DB_LOCKS for payments-db. No shell commands")
    public String runDiagnostic(String service, String diagnosticType) { return lab.runDiagnostic(service, diagnosticType); }
    @Tool(description = "Request a simulated service restart. Requires separate human approval; PENDING means not executed")
    public String restartService(String service) { return lab.restartService(service); }
}
