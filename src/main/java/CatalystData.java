import com.fasterxml.jackson.databind.JsonNode;

/**
 * Catalyst metrics parsed from Alpha Vantage NEWS_SENTIMENT API.
 * Used by the Institutional Flow Layer (Layer 3) to identify catalysts.
 */
public class CatalystData {

    public double sentimentScore;       // -1 to +1 (bearish to bullish)
    public double relevanceScore;       // 0 to 1 (how relevant news is)
    public int newsCount24h;            // number of news articles in last 24h
    public boolean earningsBeat;        // did company beat earnings?
    public boolean guidanceRaise;       // did company raise guidance?
    public boolean analystUpgrade;     // did analyst upgrade rating?
    public boolean analystDowngrade;   // did analyst downgrade rating?
    public boolean hasEarningsMention; // news mentions earnings
    public boolean hasAIMention;       // news mentions AI
    public boolean hasMnaMention;      // news mentions M&A
    public boolean hasFDAStage;        // news mentions FDA stage
    public boolean hasPartnership;     // news mentions partnership
    public String nextEarningsDate;     // ISO date string of next earnings announcement (YYYY-MM-DD)
    public long lastUpdatedEpochMs;

    /** Compute a 0-10 catalyst score. */
    public int computeScore() {
        int score = 0;

        // Sentiment score contribution
        if (sentimentScore > 0.3) score += 2;
        else if (sentimentScore > 0.1) score += 1;
        else if (sentimentScore < -0.3) score -= 1;

        // Relevance score contribution
        if (relevanceScore > 0.7) score += 1;

        // News volume contribution
        if (newsCount24h > 10) score += 1;
        else if (newsCount24h > 5) score += 1;

        // Specific catalysts
        if (earningsBeat) score += 2;
        if (guidanceRaise) score += 2;
        if (analystUpgrade) score += 2;
        if (analystDowngrade) score -= 1;

        // Thematic catalysts
        if (hasEarningsMention) score += 1;
        if (hasAIMention) score += 1;
        if (hasMnaMention) score += 2;
        if (hasFDAStage) score += 1;
        if (hasPartnership) score += 1;

        return Math.max(0, Math.min(10, score));
    }

    /**
     * Check if earnings are within N days from today.
     * @param days Number of days to check (e.g., 3 for "within 3 days")
     * @return true if earnings date is within the specified days, false otherwise or if no earnings date available
     */
    public boolean isEarningsWithinDays(int days) {
        if (nextEarningsDate == null || nextEarningsDate.isEmpty()) {
            return false; // No earnings date available, assume safe
        }

        try {
            java.time.LocalDate earningsDate = java.time.LocalDate.parse(nextEarningsDate);
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate futureDate = today.plusDays(days);

            // Check if earnings date is between today and futureDate (inclusive)
            return !earningsDate.isBefore(today) && !earningsDate.isAfter(futureDate);
        } catch (Exception e) {
            return false; // Parse error, assume safe
        }
    }

    /** Parse an Alpha Vantage NEWS_SENTIMENT JSON node. */
    public static CatalystData fromNewsSentiment(JsonNode node, String ticker) {
        CatalystData d = new CatalystData();
        d.lastUpdatedEpochMs = System.currentTimeMillis();

        // Extract overall sentiment
        if (node.has("feed") && node.path("feed").isArray()) {
            JsonNode feed = node.path("feed");
            double totalSentiment = 0;
            double totalRelevance = 0;
            int count = 0;

            for (JsonNode article : feed) {
                double sentiment = article.path("overall_sentiment_score").asDouble(0);
                double relevance = article.path("relevance_score").asDouble(0);
                String title = article.path("title").asText("").toLowerCase();
                String summary = article.path("summary").asText("").toLowerCase();

                totalSentiment += sentiment;
                totalRelevance += relevance;
                count++;

                // Check for specific catalysts in title/summary
                if (title.contains("earnings") || summary.contains("earnings")) {
                    d.hasEarningsMention = true;
                }
                if (title.contains("beat") || summary.contains("beat")) {
                    d.earningsBeat = true;
                }
                if (title.contains("guidance") || summary.contains("guidance")) {
                    if (title.contains("raise") || summary.contains("raise") ||
                        title.contains("increase") || summary.contains("increase")) {
                        d.guidanceRaise = true;
                    }
                }
                if (title.contains("upgrade") || summary.contains("upgrade")) {
                    d.analystUpgrade = true;
                }
                if (title.contains("downgrade") || summary.contains("downgrade")) {
                    d.analystDowngrade = true;
                }
                if (title.contains("ai") || summary.contains("artificial intelligence")) {
                    d.hasAIMention = true;
                }
                if (title.contains("acquisition") || summary.contains("acquisition") ||
                    title.contains("merger") || summary.contains("merger")) {
                    d.hasMnaMention = true;
                }
                if (title.contains("fda") || summary.contains("fda")) {
                    d.hasFDAStage = true;
                }
                if (title.contains("partnership") || summary.contains("partner")) {
                    d.hasPartnership = true;
                }
            }

            if (count > 0) {
                d.sentimentScore = totalSentiment / count;
                d.relevanceScore = totalRelevance / count;
                d.newsCount24h = count;
            }
        }

        return d;
    }

    @Override
    public String toString() {
        return String.format(
            "Catalyst[Sentiment=%.2f Relevance=%.2f News=%d Beat=%b GuidanceRaise=%b Upgrade=%b]",
            sentimentScore, relevanceScore, newsCount24h, earningsBeat, guidanceRaise, analystUpgrade
        );
    }
}
