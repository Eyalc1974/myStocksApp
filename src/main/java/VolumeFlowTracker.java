import java.util.ArrayList;
import java.util.List;

/**
 * Volume Flow Tracker - Analyzes buying vs selling pressure over multiple days
 * and recommends buy/sell price zones based on volume flow and support/resistance.
 */
public class VolumeFlowTracker {

    public static class DayFlow {
        public String date;
        public double buyPercent;    // Percentage of buying pressure (0-100)
        public double sellPercent;   // Percentage of selling pressure (0-100)
        public String signal;        // "Accumulation", "Distribution", "Neutral"
        public double open;
        public double close;
        public double priceChange;   // Daily change in $ (close - open)
        public double priceChangePct; // Daily change in % ((close - open) / open * 100)
        public long volume;
        public double cmf;           // Chaikin Money Flow for that day
        public double mfm;           // Money Flow Multiplier
    }

    public static class FlowAnalysis {
        public String ticker;
        public List<DayFlow> dailyFlow = new ArrayList<>();
        public double weeklyBuyPressure;     // Average buy pressure over the week
        public double weeklySellPressure;    // Average sell pressure over the week
        public String weeklyTrend;           // "Strong Accumulation", "Accumulation", "Distribution", "Strong Distribution", "Neutral"
        
        // Price recommendations
        public double currentPrice;
        public double buyZoneLow;
        public double buyZoneHigh;
        public double sellZoneLow;
        public double sellZoneHigh;
        public double stopLoss;
        public double supportLevel;
        public double resistanceLevel;
        
        // Additional insights
        public String recommendation;
        public List<String> insights = new ArrayList<>();
    }

    /**
     * Analyze volume flow for a stock over the last N days
     */
    public static FlowAnalysis analyze(String ticker, 
                                       List<Double> closingPrices, 
                                       List<Double> highPrices, 
                                       List<Double> lowPrices, 
                                       List<Double> openPrices,
                                       List<Long> volumes, 
                                       List<String> dates,
                                       int daysToAnalyze) {
        
        FlowAnalysis result = new FlowAnalysis();
        result.ticker = ticker;
        
        if (closingPrices == null || closingPrices.size() < daysToAnalyze + 1) {
            result.recommendation = "אין מספיק נתונים לניתוח";
            return result;
        }
        
        int dataSize = closingPrices.size();
        int startIdx = Math.max(0, dataSize - daysToAnalyze);
        
        // Current price
        result.currentPrice = closingPrices.get(dataSize - 1);
        
        // Calculate daily flow for each day
        double totalBuyPressure = 0;
        double totalSellPressure = 0;
        int validDays = 0;
        
        for (int i = startIdx; i < dataSize; i++) {
            DayFlow day = new DayFlow();
            
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double close = closingPrices.get(i);
            double open = (openPrices != null && i < openPrices.size()) ? openPrices.get(i) : close;
            long volume = volumes.get(i);
            
            day.date = (i < dates.size()) ? dates.get(i) : "Day " + (i - startIdx + 1);
            day.open = open;
            day.close = close;
            day.priceChange = close - open;
            day.priceChangePct = (open > 0) ? ((close - open) / open * 100) : 0;
            day.volume = volume;
            
            // Calculate Money Flow Multiplier (MFM)
            double highMinusLow = high - low;
            double mfm = 0;
            if (highMinusLow > 0) {
                mfm = ((close - low) - (high - close)) / highMinusLow;
            }
            day.mfm = mfm;
            
            // Convert MFM to buy/sell percentage
            // MFM ranges from -1 (all selling) to +1 (all buying)
            // Convert to 0-100% scale
            day.buyPercent = Math.max(0, Math.min(100, (mfm + 1) * 50));
            day.sellPercent = 100 - day.buyPercent;
            
            // Determine signal based on MFM
            if (mfm > 0.3) {
                day.signal = "Strong Accumulation";
            } else if (mfm > 0.1) {
                day.signal = "Accumulation";
            } else if (mfm < -0.3) {
                day.signal = "Strong Distribution";
            } else if (mfm < -0.1) {
                day.signal = "Distribution";
            } else {
                day.signal = "Neutral";
            }
            
            totalBuyPressure += day.buyPercent;
            totalSellPressure += day.sellPercent;
            validDays++;
            
            result.dailyFlow.add(day);
        }
        
        // Calculate weekly averages
        if (validDays > 0) {
            result.weeklyBuyPressure = totalBuyPressure / validDays;
            result.weeklySellPressure = totalSellPressure / validDays;
        }
        
        // Determine weekly trend
        double netPressure = result.weeklyBuyPressure - result.weeklySellPressure;
        if (netPressure > 20) {
            result.weeklyTrend = "Strong Accumulation";
        } else if (netPressure > 5) {
            result.weeklyTrend = "Accumulation";
        } else if (netPressure < -20) {
            result.weeklyTrend = "Strong Distribution";
        } else if (netPressure < -5) {
            result.weeklyTrend = "Distribution";
        } else {
            result.weeklyTrend = "Neutral";
        }
        
        // Calculate support and resistance levels
        calculateSupportResistance(result, closingPrices, highPrices, lowPrices, daysToAnalyze);
        
        // Calculate buy/sell zones
        calculatePriceZones(result);
        
        // Generate recommendation
        generateRecommendation(result);
        
        return result;
    }
    
    /**
     * Calculate support and resistance levels using recent highs/lows
     */
    private static void calculateSupportResistance(FlowAnalysis result, 
                                                   List<Double> closes, 
                                                   List<Double> highs, 
                                                   List<Double> lows,
                                                   int period) {
        int dataSize = closes.size();
        int startIdx = Math.max(0, dataSize - period);
        
        double recentHigh = Double.MIN_VALUE;
        double recentLow = Double.MAX_VALUE;
        double sumClose = 0;
        int count = 0;
        
        for (int i = startIdx; i < dataSize; i++) {
            recentHigh = Math.max(recentHigh, highs.get(i));
            recentLow = Math.min(recentLow, lows.get(i));
            sumClose += closes.get(i);
            count++;
        }
        
        result.resistanceLevel = recentHigh;
        result.supportLevel = recentLow;
        
        // Also consider pivot points
        double avgClose = sumClose / count;
        double pivot = (recentHigh + recentLow + closes.get(dataSize - 1)) / 3;
        
        // Adjust support/resistance based on pivot
        if (result.currentPrice > pivot) {
            result.supportLevel = Math.max(result.supportLevel, pivot - (recentHigh - pivot) * 0.382);
        } else {
            result.resistanceLevel = Math.min(result.resistanceLevel, pivot + (pivot - recentLow) * 0.382);
        }
    }
    
    /**
     * Calculate recommended buy and sell price zones
     */
    private static void calculatePriceZones(FlowAnalysis result) {
        double current = result.currentPrice;
        double support = result.supportLevel;
        double resistance = result.resistanceLevel;
        double range = resistance - support;
        
        // Buy zone: near support level
        result.buyZoneLow = support;
        result.buyZoneHigh = support + range * 0.25; // Lower 25% of range
        
        // Sell zone: near resistance level
        result.sellZoneLow = resistance - range * 0.25; // Upper 25% of range
        result.sellZoneHigh = resistance;
        
        // Stop loss: below support with some buffer
        result.stopLoss = support - range * 0.1;
        
        // Adjust based on weekly trend
        if (result.weeklyTrend.contains("Accumulation")) {
            // In accumulation, can buy a bit higher
            result.buyZoneHigh = support + range * 0.35;
            result.insights.add("💡 לחץ קנייה חזק - אפשר לקנות גם במחיר מעט גבוה יותר");
        } else if (result.weeklyTrend.contains("Distribution")) {
            // In distribution, be more conservative
            result.buyZoneLow = support - range * 0.05;
            result.buyZoneHigh = support + range * 0.15;
            result.stopLoss = support - range * 0.15;
            result.insights.add("⚠️ לחץ מכירה - המתן למחיר נמוך יותר לפני קנייה");
        }
    }
    
    /**
     * Generate final recommendation based on all analysis
     */
    private static void generateRecommendation(FlowAnalysis result) {
        StringBuilder rec = new StringBuilder();
        
        double current = result.currentPrice;
        
        // Check where current price is relative to zones
        if (current <= result.buyZoneHigh) {
            if (result.weeklyTrend.contains("Accumulation")) {
                rec.append("🟢 קנייה מומלצת - המחיר באזור הקנייה ויש לחץ קנייה");
            } else if (result.weeklyTrend.contains("Distribution")) {
                rec.append("🟡 המתנה - המחיר באזור הקנייה אבל יש לחץ מכירה");
            } else {
                rec.append("🟢 קנייה אפשרית - המחיר באזור הקנייה");
            }
        } else if (current >= result.sellZoneLow) {
            if (result.weeklyTrend.contains("Distribution")) {
                rec.append("🔴 מכירה מומלצת - המחיר באזור המכירה ויש לחץ מכירה");
            } else if (result.weeklyTrend.contains("Accumulation")) {
                rec.append("🟡 החזקה - המחיר גבוה אבל יש לחץ קנייה");
            } else {
                rec.append("🟠 שקול מכירה - המחיר באזור המכירה");
            }
        } else {
            // Price in middle zone
            if (result.weeklyTrend.contains("Strong Accumulation")) {
                rec.append("🟢 קנייה - לחץ קנייה חזק מאוד");
            } else if (result.weeklyTrend.contains("Strong Distribution")) {
                rec.append("🔴 מכירה - לחץ מכירה חזק מאוד");
            } else {
                rec.append("🟡 המתנה - המחיר באזור הביניים");
            }
        }
        
        result.recommendation = rec.toString();
        
        // Add price-based insights
        double distanceToSupport = (current - result.supportLevel) / current * 100;
        double distanceToResistance = (result.resistanceLevel - current) / current * 100;
        
        if (distanceToSupport < 3) {
            result.insights.add("📍 המחיר קרוב לרמת תמיכה (" + String.format("%.1f%%", distanceToSupport) + ")");
        }
        if (distanceToResistance < 3) {
            result.insights.add("📍 המחיר קרוב לרמת התנגדות (" + String.format("%.1f%%", distanceToResistance) + ")");
        }
        
        // Add volume trend insight
        if (result.dailyFlow.size() >= 2) {
            DayFlow lastDay = result.dailyFlow.get(result.dailyFlow.size() - 1);
            DayFlow prevDay = result.dailyFlow.get(result.dailyFlow.size() - 2);
            
            if (lastDay.buyPercent > prevDay.buyPercent + 10) {
                result.insights.add("📈 לחץ הקנייה עלה משמעותית ביום האחרון");
            } else if (lastDay.sellPercent > prevDay.sellPercent + 10) {
                result.insights.add("📉 לחץ המכירה עלה משמעותית ביום האחרון");
            }
        }
    }
}
