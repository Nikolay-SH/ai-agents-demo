package meetup.sherlock.koog

import meetup.sherlock.live.LiveSession
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet

class KoogLiveTools(private val lab: LiveSession) : ToolSet {
    @Tool @LLMDescription("List actual Docker Compose services")
    fun listServices(): String = lab.listServices()
    @Tool @LLMDescription("Get real container identity, dependencies and health")
    fun getServiceInfo(service: String): String = lab.getServiceInfo(service)
    @Tool @LLMDescription("Get real HTTP counters, uptime and JVM metrics since startup")
    fun getMetrics(service: String): String = lab.getMetrics(service)
    @Tool @LLMDescription("Read recent actual Docker logs, filtered by literal query; empty query returns all")
    fun getLogs(service: String, query: String): String = lab.getLogs(service, query)
    @Tool @LLMDescription("Read current PostgreSQL sessions and blocking PIDs with a fixed read-only query")
    fun getDatabaseActivity(): String = lab.getDatabaseActivity()
    @Tool @LLMDescription("Request a REAL restart of checkout or payment. Blocks for human console approval. Rejection means no restart. After EXECUTED verify recovery with tools")
    fun restartService(service: String, reason: String): String = lab.restartService(service, reason)
}
