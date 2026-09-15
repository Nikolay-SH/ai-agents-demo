package meetup.sherlock.live;

/** Operator harness using real production tool implementations; not an LLM agent. */
public final class LiveOperator {
    public static void main(String[] args) {
        System.out.println("OPERATOR HARNESS — real Docker tools, NO LLM");
        try (var session = new LiveSession(Boolean.parseBoolean(System.getenv("COMPACT")), 180)) {
            String mode = args.length == 0 ? "status" : args[0];
            switch (mode) {
                case "status" -> { session.listServices(); session.getServiceInfo("checkout"); session.getServiceInfo("payment"); }
                case "metrics" -> session.getMetrics(args.length > 1 ? args[1] : "payment");
                case "logs" -> session.getLogs(args.length > 1 ? args[1] : "payment", "");
                case "database" -> session.getDatabaseActivity();
                case "restart" -> session.restartService(args.length > 1 ? args[1] : "payment", "Operator exercises human approval on the local demo");
                case "terminate" -> session.terminateSession(Integer.parseInt(args[1]), "Operator exercises human approval on the local demo");
                default -> throw new IllegalArgumentException("Modes: status, metrics, logs, database, restart [checkout|payment], terminate <pid>");
            }
        }
    }
}
