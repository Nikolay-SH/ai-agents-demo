package meetup.sherlock.spring;

import meetup.sherlock.live.LiveSession;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Critical actions. The model can only request them; a human decides in the console, code executes. */
public final class SpringActionTools {
    private static final String REASON = "Evidence-based reason with [E#] references, shown to the human";
    private final LiveSession lab;
    public SpringActionTools(LiveSession lab) { this.lab = lab; }
    @Tool(description = "Request a REAL restart of checkout or payment. Blocks until a human approves or rejects in the console; REJECTED means nothing changed")
    public String restartService(@ToolParam(description = "checkout | payment") String service, @ToolParam(description = REASON) String reason) {
        return lab.restartService(service, reason);
    }
    @Tool(description = "Request termination of a PostgreSQL session that blocks other sessions (pid from getDatabaseActivity). Blocks until a human approves; the session's open transaction is rolled back")
    public String terminateSession(@ToolParam(description = "pid of the blocking session") int pid, @ToolParam(description = REASON) String reason) {
        return lab.terminateSession(pid, reason);
    }
}
