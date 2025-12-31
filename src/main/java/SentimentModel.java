/**
 * Sentiment Model - מודל ניתוח סנטימנט מחדשות ודעת קהל
 * מנתח טקסטים ומחזיר ציון סנטימנט משוקלל
 */
public class SentimentModel {

    private static final double BULLISH_THRESHOLD = 0.6;
    private static final double BEARISH_THRESHOLD = 0.4;

    // מילים חיוביות חזקות
    private static final String[] STRONG_POSITIVE = {
        "surge", "soar", "skyrocket", "breakthrough", "exceptional", "outstanding",
        "record high", "beat expectations", "strong buy", "outperform", "upgrade",
        "bullish", "optimistic", "growth", "profit", "success", "innovative"
    };
    
    // מילים חיוביות מתונות
    private static final String[] MILD_POSITIVE = {
        "gain", "rise", "increase", "improve", "positive", "good", "solid",
        "steady", "stable", "confident", "opportunity", "potential", "buy"
    };
    
    // מילים שליליות חזקות
    private static final String[] STRONG_NEGATIVE = {
        "crash", "plunge", "collapse", "disaster", "fraud", "scandal", "bankrupt",
        "default", "layoffs", "warning", "downgrade", "sell", "bearish", "crisis"
    };
    
    // מילים שליליות מתונות
    private static final String[] MILD_NEGATIVE = {
        "decline", "drop", "fall", "loss", "weak", "concern", "risk", "volatile",
        "uncertainty", "challenge", "pressure", "miss", "underperform", "hold"
    };

    public static class SentimentResult {
        public double score;          // 0.0 (very bearish) to 1.0 (very bullish)
        public String sentiment;      // STRONG_BULLISH, BULLISH, NEUTRAL, BEARISH, STRONG_BEARISH
        public String verdict;
        public int positiveWords;
        public int negativeWords;
        public double confidence;     // 0.0 to 1.0
    }

    /**
     * מנתח סנטימנט מטקסט ומחזיר תוצאה מפורטת
     */
    public static SentimentResult analyzeSentiment(String text) {
        SentimentResult result = new SentimentResult();
        
        if (text == null || text.isBlank()) {
            result.score = 0.5;
            result.sentiment = "NEUTRAL";
            result.verdict = "⚪️ No data for sentiment analysis";
            result.confidence = 0.0;
            return result;
        }
        
        String lower = text.toLowerCase();
        
        // ספירת מילות מפתח
        int strongPos = countMatches(lower, STRONG_POSITIVE);
        int mildPos = countMatches(lower, MILD_POSITIVE);
        int strongNeg = countMatches(lower, STRONG_NEGATIVE);
        int mildNeg = countMatches(lower, MILD_NEGATIVE);
        
        result.positiveWords = strongPos + mildPos;
        result.negativeWords = strongNeg + mildNeg;
        
        // חישוב ציון משוקלל
        double positiveScore = (strongPos * 2.0) + (mildPos * 1.0);
        double negativeScore = (strongNeg * 2.0) + (mildNeg * 1.0);
        double totalSignals = positiveScore + negativeScore;
        
        if (totalSignals == 0) {
            result.score = 0.5;
            result.confidence = 0.1;
        } else {
            // נרמול לטווח 0-1
            result.score = (positiveScore + 0.5 * totalSignals) / (2 * totalSignals);
            result.score = Math.max(0.0, Math.min(1.0, result.score));
            result.confidence = Math.min(1.0, totalSignals / 10.0);
        }
        
        // בדיקת מילות מפתח ספציפיות שמשנות ציון
        if (lower.contains("very_positive") || lower.contains("strong_bullish")) {
            result.score = Math.max(result.score, 0.85);
        } else if (lower.contains("very_negative") || lower.contains("strong_bearish")) {
            result.score = Math.min(result.score, 0.15);
        }
        
        // קביעת סנטימנט לפי ציון
        if (result.score >= 0.75) {
            result.sentiment = "STRONG_BULLISH";
            result.verdict = String.format("🟢 STRONG BULLISH: סנטימנט חיובי מאוד (%.0f%%). %d אזכורים חיוביים.",
                result.score * 100, result.positiveWords);
        } else if (result.score >= 0.6) {
            result.sentiment = "BULLISH";
            result.verdict = String.format("🟢 BULLISH: סנטימנט חיובי (%.0f%%).",
                result.score * 100);
        } else if (result.score >= 0.4) {
            result.sentiment = "NEUTRAL";
            result.verdict = String.format("⚪️ NEUTRAL: סנטימנט מעורב (%.0f%%).",
                result.score * 100);
        } else if (result.score >= 0.25) {
            result.sentiment = "BEARISH";
            result.verdict = String.format("🔴 BEARISH: סנטימנט שלילי (%.0f%%).",
                result.score * 100);
        } else {
            result.sentiment = "STRONG_BEARISH";
            result.verdict = String.format("🔴 STRONG BEARISH: סנטימנט שלילי מאוד (%.0f%%). %d אזכורים שליליים!",
                result.score * 100, result.negativeWords);
        }
        
        return result;
    }

    /**
     * גרסה פשוטה עם שני פרמטרים
     */
    public static String getSentimentVerdict(double sentimentScore, double averageScore) {
        if (sentimentScore > BULLISH_THRESHOLD && sentimentScore > averageScore * 1.1) {
            return "🟢 STRONG BULLISH (Opinion is very positive)";
        } else if (sentimentScore >= BULLISH_THRESHOLD) {
            return "🟢 BULLISH (Positive sentiment)";
        } else if (sentimentScore < BEARISH_THRESHOLD) {
            return "🔴 STRONG BEARISH (High negative press)";
        } else if (sentimentScore < 0.45) {
            return "🔴 BEARISH (Negative sentiment)";
        }
        return "⚪️ NEUTRAL";
    }

    /**
     * גרסה שמקבלת JSON/טקסט
     */
    public static String getSentimentVerdict(String sentimentJson) {
        SentimentResult result = analyzeSentiment(sentimentJson);
        return result.verdict;
    }
    
    /**
     * מחזיר ציון סנטימנט (0-1)
     */
    public static double getSentimentScore(String text) {
        return analyzeSentiment(text).score;
    }

    private static int countMatches(String text, String[] keywords) {
        int count = 0;
        for (String keyword : keywords) {
            int idx = 0;
            while ((idx = text.indexOf(keyword, idx)) != -1) {
                count++;
                idx += keyword.length();
            }
        }
        return count;
    }
}