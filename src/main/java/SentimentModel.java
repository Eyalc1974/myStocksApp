// בתוך SentimentModel.java

public class SentimentModel {
    // קריטריון ניקוד:
    private static final double BULLISH_THRESHOLD = 0.6;
    private static final double BEARISH_THRESHOLD = 0.4;

    public static String getSentimentVerdict(double sentimentScore, double averageScore) {
        // ... קוד לחילוץ הציון מתוך JSON

        if (sentimentScore > BULLISH_THRESHOLD && sentimentScore > averageScore * 1.1) {
            return "STRONG BULLISH (Opinion is very positive)";
        } else if (sentimentScore < BEARISH_THRESHOLD) {
            return "STRONG BEARISH (High negative press)";
        }
        return "NEUTRAL";
    }

    // Overload that accepts the raw JSON string and maps it to a score
    public static String getSentimentVerdict(String sentimentJson) {
        if (sentimentJson == null || sentimentJson.isBlank()) {
            return "NEUTRAL";
        }

        double sentimentScore = 0.5; // default neutral
        double averageScore = 0.5;

        try {
            String lower = sentimentJson.toLowerCase();
            if (lower.contains("very_positive") || lower.contains("strong_bullish") || lower.contains("bullish")) {
                sentimentScore = 0.8;
            } else if (lower.contains("very_negative") || lower.contains("strong_bearish") || lower.contains("bearish")) {
                sentimentScore = 0.2;
            }
        } catch (Exception e) {
            // keep defaults on error
        }

        return getSentimentVerdict(sentimentScore, averageScore);
    }
}