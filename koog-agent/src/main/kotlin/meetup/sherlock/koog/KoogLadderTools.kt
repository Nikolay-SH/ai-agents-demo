package meetup.sherlock.koog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import meetup.sherlock.live.LiveSession

private const val SERVICE = "checkout | payment | payments-db"
private const val REASON = "Evidence-based reason with [E#] references, shown to the human"

/** Read-only diagnostics for the ladder steps; same descriptions as SpringReadTools. */
class KoogReadTools(private val lab: LiveSession) : ToolSet {
    @Tool @LLMDescription("List Docker Compose services of the incident project and their state")
    fun listServices(): String = lab.listServices()
    @Tool @LLMDescription("Container state, declared dependencies and health of a service")
    fun getServiceInfo(@LLMDescription(SERVICE) service: String): String = lab.getServiceInfo(service)
    @Tool @LLMDescription("Request rate, server errors and latency of checkout or payment; for payments-db returns database sessions")
    fun getMetrics(@LLMDescription(SERVICE) service: String): String = lab.getMetrics(service)
    @Tool @LLMDescription("Recent log lines of a service that contain a literal, case-insensitive query")
    fun getLogs(@LLMDescription(SERVICE) service: String,
                @LLMDescription("Literal text to search; empty string returns all recent lines") query: String = ""): String = lab.getLogs(service, query)
    @Tool @LLMDescription("Current PostgreSQL sessions: state, lock waits and which pid blocks which")
    fun getDatabaseActivity(): String = lab.getDatabaseActivity()
}

/** Critical actions. The model can only request them; a human decides in the console, code executes. */
class KoogActionTools(private val lab: LiveSession) : ToolSet {
    @Tool @LLMDescription("Request a REAL restart of checkout or payment. Blocks until a human approves or rejects in the console; REJECTED means nothing changed")
    fun restartService(@LLMDescription("checkout | payment") service: String, @LLMDescription(REASON) reason: String): String = lab.restartService(service, reason)
    @Tool @LLMDescription("Request termination of a PostgreSQL session that blocks other sessions (pid from getDatabaseActivity). Blocks until a human approves; the session's open transaction is rolled back")
    fun terminateSession(@LLMDescription("pid of the blocking session") pid: Int, @LLMDescription(REASON) reason: String): String = lab.terminateSession(pid, reason)
}
