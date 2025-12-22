import java.util.List;
import java.util.Map;

public record MonitoringSnapshot(
        String symbol,
        long asOfEpochMillis,
        Map<String, Double> returnsByDays,
        Map<String, String> recommendationByDays,
        Map<String, Double> scoreByDays,
        Map<String, Double> indicatorValues,
        Map<String, String> indicatorNotes,
        Map<String, String> fundamentals,
        Map<String, String> risk,
        Double newsSentimentScore,
        List<Map<String, String>> newsTop
) {
}
