import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class FundamentalMomentumAnalyzer {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Detect institutional accumulation patterns
     * Signs: High volume for multiple days, slow steady rise, close near high, small declines on low volume
     */
    public static int detectInstitutionalAccumulation(JsonNode priceData, int lookbackDays) {
        int score = 0;
        int consecutiveHighVolumeDays = 0;
        int consecutiveUpDays = 0;
        int closesNearHigh = 0;

        if (priceData == null || priceData.isEmpty()) return 0;

        List<JsonNode> recentDays = new ArrayList<>();
        var timeSeries = priceData.path("Time Series (Daily)");
        timeSeries.fields().forEachRemaining(entry -> recentDays.add(entry.getValue()));
        
        if (recentDays.size() < lookbackDays) return 0;

        // Calculate average volume for baseline
        double avgVolume = 0;
        for (int i = 0; i < Math.min(recentDays.size(), 50); i++) {
            avgVolume += recentDays.get(i).path("6. volume").asDouble();
        }
        avgVolume /= Math.min(recentDays.size(), 50);

        // Analyze recent days
        for (int i = 0; i < Math.min(recentDays.size(), lookbackDays); i++) {
            JsonNode day = recentDays.get(i);
            double volume = day.path("6. volume").asDouble();
            double close = day.path("4. close").asDouble();
            double high = day.path("2. high").asDouble();
            double low = day.path("3. low").asDouble();
            double open = day.path("1. open").asDouble();

            // High volume check (1.5x average)
            if (volume > avgVolume * 1.5) {
                consecutiveHighVolumeDays++;
                if (consecutiveHighVolumeDays >= 3) score += 2;
            } else {
                consecutiveHighVolumeDays = 0;
            }

            // Slow steady rise (up day with reasonable gain)
            if (close > open && (close - open) / open < 0.05) { // Less than 5% gain
                consecutiveUpDays++;
                if (consecutiveUpDays >= 5) score += 2;
            } else {
                consecutiveUpDays = 0;
            }

            // Close near high (within top 25% of daily range)
            double dailyRange = high - low;
            if (dailyRange > 0 && (high - close) / dailyRange < 0.25) {
                closesNearHigh++;
                if (closesNearHigh >= lookbackDays / 2) score += 2;
            }

            // Small declines on low volume (down day with volume < average)
            if (close < open && volume < avgVolume) {
                score += 1;
            }
        }

        return Math.min(score, 10); // Cap at 10
    }

    /**
     * Calculate relative strength score vs SPY (0-100)
     * Uses 20, 60, and 120 day periods
     */
    public static int calculateRelativeStrengthScore(String tickerJson, String spyJson) {
        try {
            double rs20 = calculateRSForPeriod(tickerJson, spyJson, 20);
            double rs60 = calculateRSForPeriod(tickerJson, spyJson, 60);
            double rs120 = calculateRSForPeriod(tickerJson, spyJson, 120);

            // Weighted average: recent periods matter more
            double weightedRS = (rs20 * 0.5) + (rs60 * 0.3) + (rs120 * 0.2);
            
            // Convert to 0-100 scale (RS of 1.0 = 50, RS of 2.0 = 100)
            return (int) Math.min(100, Math.max(0, (weightedRS - 0.5) * 100));
        } catch (Exception e) {
            return 50; // Neutral if calculation fails
        }
    }

    private static double calculateRSForPeriod(String tickerJson, String spyJson, int days) {
        try {
            JsonNode tickerData = mapper.readTree(tickerJson).path("Time Series (Daily)");
            JsonNode spyData = mapper.readTree(spyJson).path("Time Series (Daily)");

            List<Double> tickerReturns = new ArrayList<>();
            List<Double> spyReturns = new ArrayList<>();

            var tickerIter = tickerData.fields();
            var spyIter = spyData.fields();

            for (int i = 0; i < days && tickerIter.hasNext() && spyIter.hasNext(); i++) {
                double tickerClose = tickerIter.next().getValue().path("4. close").asDouble();
                double spyClose = spyIter.next().getValue().path("4. close").asDouble();
                
                tickerReturns.add(tickerClose);
                spyReturns.add(spyClose);
            }

            if (tickerReturns.size() < 2 || spyReturns.size() < 2) return 1.0;

            double tickerReturn = (tickerReturns.get(0) - tickerReturns.get(tickerReturns.size() - 1)) / tickerReturns.get(tickerReturns.size() - 1);
            double spyReturn = (spyReturns.get(0) - spyReturns.get(spyReturns.size() - 1)) / spyReturns.get(spyReturns.size() - 1);

            if (spyReturn == 0) return 1.0;
            return tickerReturn / spyReturn;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * Calculate sector strength score
     * Returns score 0-10 based on sector performance vs market
     */
    public static int calculateSectorStrength(String sector, Map<String, Double> sectorPerformance) {
        if (sector == null || sectorPerformance == null || !sectorPerformance.containsKey(sector)) {
            return 5; // Neutral if no data
        }

        double sectorPerf = sectorPerformance.get(sector);
        double marketPerf = sectorPerformance.getOrDefault("MARKET", 0.0);

        double relativePerf = sectorPerf - marketPerf;

        // Score based on relative performance
        if (relativePerf > 5.0) return 10;
        if (relativePerf > 3.0) return 8;
        if (relativePerf > 1.0) return 6;
        if (relativePerf > 0.0) return 5;
        if (relativePerf > -1.0) return 4;
        if (relativePerf > -3.0) return 2;
        return 0;
    }

    /**
     * Detect catalysts from news sentiment
     * Scores: AI partnership=8, new product=4, earnings beat 15%=10, FDA approval=9, buyback=5, acquisition=7
     */
    public static int detectCatalystScore(String newsSentimentJson) {
        if (newsSentimentJson == null) return 0;

        int score = 0;
        try {
            JsonNode root = mapper.readTree(newsSentimentJson);
            JsonNode feed = root.path("feed");

            if (feed.isArray()) {
                for (JsonNode article : feed) {
                    String title = article.path("title").asText().toLowerCase();
                    String summary = article.path("summary").asText().toLowerCase();
                    String text = title + " " + summary;

                    // AI partnership
                    if (text.contains("ai partnership") || text.contains("artificial intelligence") && text.contains("partnership")) {
                        score += 8;
                    }
                    // New product launch
                    else if (text.contains("new product") || text.contains("product launch") || text.contains("release")) {
                        score += 4;
                    }
                    // FDA approval
                    else if (text.contains("fda approval") || text.contains("approved by fda")) {
                        score += 9;
                    }
                    // Buyback
                    else if (text.contains("buyback") || text.contains("share repurchase")) {
                        score += 5;
                    }
                    // Acquisition
                    else if (text.contains("acquisition") || text.contains("acquired") || text.contains("merger")) {
                        score += 7;
                    }
                    // Guidance raise
                    else if (text.contains("guidance raise") || text.contains("raised guidance") || text.contains("outlook")) {
                        score += 6;
                    }
                }
            }
        } catch (Exception e) {
            return 0;
        }

        return Math.min(score, 10); // Cap at 10
    }

    /**
     * Calculate earnings revision score
     * +3 if earnings surprise > 10%
     * +2 if guidance raise
     * +2 if EPS revisions positive
     */
    public static int calculateEarningsRevisionScore(double earningsSurprise, boolean hasPositiveRevision, boolean guidanceRaise) {
        int score = 0;

        if (earningsSurprise > 10.0) score += 3;
        if (hasPositiveRevision) score += 2;
        if (guidanceRaise) score += 2;

        return Math.min(score, 10);
    }

    /**
     * Overall fundamental momentum score (0-50)
     * Combines all factors with weights
     */
    public static double calculateFundamentalMomentumScore(
            double revenueGrowth,
            double epsGrowth,
            int relativeStrengthScore,
            int sectorStrength,
            int catalystScore,
            int earningsRevisionScore,
            int institutionalFlowScore,
            double rvol
    ) {
        double score = 0.0;

        // Revenue growth > 15% (max 10 points)
        if (revenueGrowth > 15.0) score += 10;
        else if (revenueGrowth > 10.0) score += 7;
        else if (revenueGrowth > 5.0) score += 4;

        // EPS growth > 20% (max 10 points)
        if (epsGrowth > 20.0) score += 10;
        else if (epsGrowth > 15.0) score += 7;
        else if (epsGrowth > 10.0) score += 4;

        // Relative strength 90+ (max 10 points)
        score += (relativeStrengthScore / 10.0);

        // Sector strength (max 5 points)
        score += (sectorStrength / 2.0);

        // Catalyst score (max 5 points)
        score += (catalystScore / 2.0);

        // Earnings revision (max 5 points)
        score += (earningsRevisionScore / 2.0);

        // Institutional flow (max 3 points)
        score += (institutionalFlowScore * 0.3);

        // RVOL bonus (max 2 points)
        if (rvol > 2.0) score += 2;
        else if (rvol > 1.5) score += 1.5;
        else if (rvol > 1.0) score += 1;

        return Math.min(score, 50.0);
    }

    /**
     * Calculate quality growth score (0-50)
     * Focuses on business quality + momentum + catalysts
     */
    public static double calculateQualityGrowthScore(
            double revenueGrowth,
            double epsGrowth,
            double roe,
            double debtToEquity,
            int relativeStrengthScore,
            boolean priceAboveSMA50,
            boolean priceAboveSMA200,
            boolean earningsBeat,
            boolean analystUpgrade,
            boolean guidanceRaise,
            double rvol
    ) {
        double score = 0.0;

        // Fundamental Quality (max 20 points)
        // Revenue growth > 15% (max 5 points)
        if (revenueGrowth > 20.0) score += 5;
        else if (revenueGrowth > 15.0) score += 4;
        else if (revenueGrowth > 10.0) score += 2;

        // EPS growth > 20% (max 5 points)
        if (epsGrowth > 25.0) score += 5;
        else if (epsGrowth > 20.0) score += 4;
        else if (epsGrowth > 15.0) score += 2;

        // ROE > 15% (max 5 points)
        if (roe > 20.0) score += 5;
        else if (roe > 15.0) score += 4;
        else if (roe > 10.0) score += 2;

        // Debt/Equity < 0.5 (max 5 points)
        if (debtToEquity < 0.3) score += 5;
        else if (debtToEquity < 0.5) score += 4;
        else if (debtToEquity < 0.8) score += 2;

        // Momentum (max 15 points)
        // Relative strength > 80 (max 8 points)
        if (relativeStrengthScore > 90) score += 8;
        else if (relativeStrengthScore > 80) score += 6;
        else if (relativeStrengthScore > 70) score += 4;

        // Above SMA50 (max 4 points)
        if (priceAboveSMA50) score += 4;

        // Above SMA200 (max 3 points)
        if (priceAboveSMA200) score += 3;

        // Catalysts (max 10 points)
        if (earningsBeat) score += 3;
        if (analystUpgrade) score += 4;
        if (guidanceRaise) score += 3;

        // Volume confirmation (max 5 points)
        if (rvol > 2.0) score += 5;
        else if (rvol > 1.5) score += 4;
        else if (rvol > 1.2) score += 2;

        return Math.min(score, 50.0);
    }

    /**
     * Check if stock meets quality growth entry criteria
     */
    public static boolean meetsQualityGrowthCriteria(
            double revenueGrowth,
            double epsGrowth,
            double roe,
            double debtToEquity,
            int relativeStrengthScore,
            boolean priceAboveSMA50,
            boolean priceAboveSMA200,
            boolean earningsBeat,
            boolean analystUpgrade,
            boolean guidanceRaise,
            double rvol
    ) {
        return revenueGrowth > 15.0 &&
               epsGrowth > 20.0 &&
               roe > 15.0 &&
               debtToEquity < 0.5 &&
               relativeStrengthScore > 80 &&
               priceAboveSMA50 &&
               priceAboveSMA200 &&
               earningsBeat &&
               analystUpgrade &&
               guidanceRaise &&
               rvol > 1.5;
    }
}
