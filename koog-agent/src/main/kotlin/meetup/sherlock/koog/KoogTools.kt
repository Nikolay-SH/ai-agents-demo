package meetup.sherlock.koog

import meetup.sherlock.IncidentLab
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet

class KoogTools(private val lab: IncidentLab) : ToolSet {
    @Tool @LLMDescription("Get CPU, memory, latency and error rate for a service")
    fun getMetrics(service: String): String = lab.getMetrics(service)
    @Tool @LLMDescription("Search service logs with a literal case-insensitive query; empty query returns all")
    fun getLogs(service: String, query: String): String = lab.getLogs(service, query)
    @Tool @LLMDescription("Get recent deployments with timestamps and rollout details")
    fun getDeployments(): String = lab.getDeployments()
    @Tool @LLMDescription("Get dependencies, owner and available diagnostics for a service")
    fun getServiceInfo(service: String): String = lab.getServiceInfo(service)
    @Tool @LLMDescription("Run a read-only diagnostic: VERSION_BREAKDOWN for payment, THREAD_DUMP for checkout or payment, DB_LOCKS for payments-db. No shell commands")
    fun runDiagnostic(service: String, diagnosticType: String): String = lab.runDiagnostic(service, diagnosticType)
    @Tool @LLMDescription("Request a simulated service restart. Requires separate human approval; PENDING means not executed")
    fun restartService(service: String): String = lab.restartService(service)
}
