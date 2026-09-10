package meetup.sherlock;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Acceptance rubric for this demo incident, not a general root-cause oracle or LLM critic. */
public final class EvidenceReview {
    public enum Status { SUFFICIENT_TRACE, NEEDS_EVIDENCE, SOURCE_UNAVAILABLE }
    public record Assessment(Status status, String feedback) {}
    private EvidenceReview() {}

    public static Assessment assess(List<IncidentLab.Event> events) {
        boolean versions = events.stream().anyMatch(e ->
            e.result().contains("v1.4 errors=48%, v1.5 errors=48%") ||
            e.result().contains("blocked_clients=payment v1.4,payment v1.5") ||
            (e.tool().equals("getLogs") && e.result().contains("version=1.4") && e.result().contains("version=1.5")));
        boolean inspected = events.stream().anyMatch(e -> e.tool().equals("runDiagnostic") &&
            e.arguments().equals("payments-db, DB_LOCKS") && e.result().contains("blocker_tx="));
        boolean unavailable = events.stream().anyMatch(e -> e.tool().equals("runDiagnostic") &&
            e.arguments().equals("payments-db, DB_LOCKS") && e.result().startsWith("UNAVAILABLE"));
        if (unavailable) return new Assessment(Status.SOURCE_UNAVAILABLE,
            "Источник прямой диагностики недоступен. Вывод о владельце блокировки не подтверждён.");
        if (!inspected) return new Assessment(Status.NEEDS_EVIDENCE,
            "Отчёт остановился на симптомах. В критериях этого учебного стенда требуется прямая диагностика DB_LOCKS для payments-db: она устанавливает источник блокировки, если он есть. Выполни эту доступную проверку; не угадывай её результат и не ограничивайся рекомендацией выполнить её человеку.");
        if (!versions) return new Assessment(Status.NEEDS_EVIDENCE,
            "Есть диагностическая улика, но не проверена альтернатива: затрагивает ли проблема только новую версию или также старую? Для сравнения версий payment доступна диагностика VERSION_BREAKDOWN.");
        return new Assessment(Status.SUFFICIENT_TRACE,
            "Получены прямые диагностические данные и проверка альтернативы. Это проверка полноты трассы, не гарантия правильности текста отчёта.");
    }

    public static String investigate(Function<String, String> agent, IncidentLab lab, String question) {
        String mode = System.getenv().getOrDefault("INVESTIGATION_MODE", "reviewed");
        if (mode.equals("basic")) return agent.apply(question);
        if (!mode.equals("reviewed")) throw new IllegalArgumentException("INVESTIGATION_MODE: basic or reviewed");
        String request = question;
        for (int attempt = 0; attempt < 3; attempt++) {
            String draft = agent.apply(request);
            Assessment review = assess(lab.events());
            System.out.println("\nEVIDENCE REVIEW: " + review.status() + " — " + review.feedback());
            if (lab.budgetExhausted()) return "INCOMPLETE: tool budget exhausted.\n" + draft;
            if (review.status() == Status.SOURCE_UNAVAILABLE)
                return "INCOMPLETE: diagnostic source unavailable. Model draft below is not a confirmed diagnosis.\n" + draft;
            if (review.status() == Status.SUFFICIENT_TRACE && referencesExist(draft, lab.events().size()))
                return draft;
            if (attempt == 2) return "INCOMPLETE: evidence review did not pass after 3 attempts. Unverified model draft:\n" + draft;
            StringBuilder evidence = new StringBuilder();
            for (var event : lab.events()) evidence.append("[E").append(event.step()).append("] ")
                .append(event.tool()).append('(').append(event.arguments()).append("): ")
                .append(event.result()).append('\n');
            request = question + "\n\nПроверка предыдущего ответа: " + review.feedback()
                + "\nСсылайся только на существующие ID улик. Продолжи расследование инструментами; используй уже собранные данные."
                + "\n\nСобранные улики:\n" + evidence + "\n\nПредыдущий черновик, требующий проверки:\n" + draft;
        }
        throw new IllegalStateException("Unreachable");
    }
    static boolean referencesExist(String draft, int count) {
        if (draft == null || draft.isBlank()) return false;
        var matcher = Pattern.compile("\\[E(\\d+)\\]").matcher(draft);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                int id = Integer.parseInt(matcher.group(1));
                if (id < 1 || id > count) return false;
            } catch (NumberFormatException e) { return false; }
        }
        return found;
    }
}
