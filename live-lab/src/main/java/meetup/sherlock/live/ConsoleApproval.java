package meetup.sherlock.live;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;

public final class ConsoleApproval implements RestartGate.Approval, AutoCloseable {
    private final BufferedReader input;
    private final PrintStream output;
    private final Duration timeout;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "human-approval-input"); thread.setDaemon(true); return thread;
    });
    private boolean disabled;
    public ConsoleApproval(Reader input, PrintStream output, Duration timeout) {
        this.input = new BufferedReader(input); this.output = output; this.timeout = timeout;
    }
    public synchronized RestartGate.Decision ask(RestartGate.Request request) {
        if (disabled) return RestartGate.Decision.REJECT;
        output.printf("%n=== HUMAN APPROVAL REQUIRED — %s ===%nProject: %s%nService: %s%nTarget: %s%nReason: %s%n",
            request.action(), request.target().project(), request.target().service(), request.target().id(), request.reason());
        output.println("Type exactly: approve " + request.id());
        output.println("Enter / any other response / EOF rejects. Timeout: " + timeout.toSeconds() + " seconds.");
        output.flush();
        Future<String> answer = reader.submit(input::readLine);
        try {
            String line = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) disabled = true;
            return ("approve " + request.id()).equals(line) ? RestartGate.Decision.APPROVE : RestartGate.Decision.REJECT;
        } catch (TimeoutException | InterruptedException e) {
            disabled = true;
            answer.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return RestartGate.Decision.TIMEOUT;
        } catch (ExecutionException e) {
            disabled = true;
            return RestartGate.Decision.REJECT;
        }
    }
    /** Operator's next chat turn. Shares the reader with approvals, so buffered input is never split between two readers. */
    public synchronized String readLine(String prompt) {
        if (disabled) return null;
        output.print(prompt);
        output.flush();
        try {
            String line = reader.submit(input::readLine).get();
            if (line == null) disabled = true;
            return line;
        } catch (ExecutionException e) {
            disabled = true;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    public void close() { reader.shutdownNow(); }
}
