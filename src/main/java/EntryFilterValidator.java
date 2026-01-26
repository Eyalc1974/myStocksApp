import java.util.ArrayList;
import java.util.List;

/**
 * Entry Filter Validator - בודק אם מניה עוברת את כל הפילטרים הקשיחים לכניסה
 * 
 * פילטרים:
 * 1. SMA200 - מניה חייבת להיות מעל הממוצע הנע 200
 * 2. RSI Overbought - מניה לא יכולה להיות בקניית יתר (RSI > 70)
 * 3. Volume - נפח מסחר חייב להיות גבוה מהממוצע
 * 4. ATR Stop-Loss - חישוב סטופ-לוס ו-Take-Profit מבוסס ATR
 */
public class EntryFilterValidator {

    public static class FilterResult {
        public boolean passesAll = true;
        public List<String> failedFilters = new ArrayList<>();
        public List<String> passedFilters = new ArrayList<>();
        
        // ATR-based levels
        public double suggestedStopLoss = 0;
        public double suggestedTakeProfit = 0;
        public double atrValue = 0;
        
        // Current values for display
        public double currentPrice = 0;
        public double sma200 = 0;
        public double currentRsi = 0;
        public double volumeRatio = 0;
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (passesAll) {
                sb.append("✅ עוברת את כל הפילטרים\n");
            } else {
                sb.append("❌ לא עוברת פילטרים:\n");
                for (String failed : failedFilters) {
                    sb.append("   • ").append(failed).append("\n");
                }
            }
            if (!passedFilters.isEmpty()) {
                sb.append("✅ עוברת: ");
                sb.append(String.join(", ", passedFilters));
                sb.append("\n");
            }
            if (suggestedStopLoss > 0) {
                sb.append(String.format("📊 Stop-Loss: $%.2f | Take-Profit: $%.2f\n", 
                    suggestedStopLoss, suggestedTakeProfit));
            }
            return sb.toString();
        }
    }

    /**
     * בודק אם מניה עוברת את כל הפילטרים הקשיחים
     * 
     * @param closingPrices רשימת מחירי סגירה (הישן ביותר ראשון)
     * @param highPrices רשימת מחירי שיא
     * @param lowPrices רשימת מחירי שפל
     * @param volumes רשימת נפחי מסחר
     * @param currentPrice המחיר הנוכחי
     * @return תוצאת הבדיקה
     */
    public static FilterResult validate(
            List<Double> closingPrices,
            List<Double> highPrices,
            List<Double> lowPrices,
            List<Long> volumes,
            double currentPrice
    ) {
        FilterResult result = new FilterResult();
        result.currentPrice = currentPrice;
        
        ScoringConfig.EntryFiltersConfig filters = ScoringConfig.getActiveEntryFilters();
        
        // --- פילטר 1: SMA200 ---
        if (filters.sma200FilterEnabled) {
            result.sma200 = calculateSMA200(closingPrices);
            if (Double.isFinite(result.sma200) && result.sma200 > 0) {
                if (currentPrice > result.sma200) {
                    result.passedFilters.add("SMA200 (" + String.format("%.2f", result.sma200) + ")");
                } else {
                    result.passesAll = false;
                    result.failedFilters.add("מתחת ל-SMA200 (" + String.format("%.2f < %.2f", currentPrice, result.sma200) + ") - מגמת ירידה");
                }
            }
        }
        
        // --- פילטר 2: RSI Overbought ---
        if (filters.rsiFilterEnabled) {
            List<Double> rsiValues = RSI.calculateRSI(closingPrices, 14);
            if (rsiValues != null && !rsiValues.isEmpty()) {
                Double lastRsi = null;
                for (int i = rsiValues.size() - 1; i >= 0; i--) {
                    if (rsiValues.get(i) != null) {
                        lastRsi = rsiValues.get(i);
                        break;
                    }
                }
                if (lastRsi != null) {
                    result.currentRsi = lastRsi;
                    if (lastRsi < filters.rsiMaxThreshold) {
                        result.passedFilters.add("RSI (" + String.format("%.1f", lastRsi) + ")");
                    } else {
                        result.passesAll = false;
                        result.failedFilters.add("RSI בקניית יתר (" + String.format("%.1f > %.0f", lastRsi, filters.rsiMaxThreshold) + ")");
                    }
                }
            }
        }
        
        // --- פילטר 3: Volume ---
        if (filters.volumeFilterEnabled && volumes != null && !volumes.isEmpty()) {
            result.volumeRatio = calculateVolumeRatio(volumes, filters.volumeAvgPeriod);
            if (result.volumeRatio > 0) {
                if (result.volumeRatio >= filters.volumeMinRatioToAvg) {
                    result.passedFilters.add("נפח (" + String.format("%.0f%%", (result.volumeRatio - 1) * 100) + " מעל ממוצע)");
                } else {
                    result.passesAll = false;
                    result.failedFilters.add("נפח נמוך (" + String.format("%.0f%%", (result.volumeRatio - 1) * 100) + 
                        " מהממוצע, נדרש: +" + String.format("%.0f%%", (filters.volumeMinRatioToAvg - 1) * 100) + ")");
                }
            }
        }
        
        // --- פילטר 4: ATR Stop-Loss (חישוב, לא חסימה) ---
        if (filters.atrStopLossEnabled && highPrices != null && lowPrices != null) {
            List<Double> atrValues = ATR.calculateATR(highPrices, lowPrices, closingPrices, filters.atrPeriod);
            if (atrValues != null && !atrValues.isEmpty()) {
                Double lastAtr = null;
                for (int i = atrValues.size() - 1; i >= 0; i--) {
                    if (atrValues.get(i) != null) {
                        lastAtr = atrValues.get(i);
                        break;
                    }
                }
                if (lastAtr != null && lastAtr > 0) {
                    result.atrValue = lastAtr;
                    result.suggestedStopLoss = ATR.calculateStopLoss(currentPrice, lastAtr, filters.atrStopLossMultiplier);
                    // Calculate take profit using R:R ratio: Target = Entry + (StopDistance × R:R)
                    double stopDistance = lastAtr * filters.atrStopLossMultiplier;
                    result.suggestedTakeProfit = currentPrice + (stopDistance * filters.riskRewardRatio);
                    result.passedFilters.add("ATR (" + String.format("%.2f", lastAtr) + ")");
                }
            }
        }
        
        return result;
    }

    /**
     * בדיקה מהירה - האם מניה עוברת את כל הפילטרים (ללא פרטים)
     */
    public static boolean passesAllFilters(
            List<Double> closingPrices,
            List<Double> highPrices,
            List<Double> lowPrices,
            List<Long> volumes,
            double currentPrice
    ) {
        return validate(closingPrices, highPrices, lowPrices, volumes, currentPrice).passesAll;
    }

    /**
     * מחשב SMA200 מרשימת מחירי סגירה
     */
    private static double calculateSMA200(List<Double> closingPrices) {
        if (closingPrices == null || closingPrices.size() < 200) {
            return Double.NaN;
        }
        
        double sum = 0;
        int start = closingPrices.size() - 200;
        for (int i = start; i < closingPrices.size(); i++) {
            sum += closingPrices.get(i);
        }
        return sum / 200.0;
    }

    /**
     * מחשב יחס נפח נוכחי לממוצע
     */
    private static double calculateVolumeRatio(List<Long> volumes, int avgPeriod) {
        if (volumes == null || volumes.size() < avgPeriod + 1) {
            return 0;
        }
        
        // נפח היום (אחרון ברשימה)
        long todayVolume = volumes.get(volumes.size() - 1);
        
        // ממוצע נפח לתקופה (לא כולל היום)
        double avgVolume = 0;
        int start = volumes.size() - 1 - avgPeriod;
        for (int i = start; i < volumes.size() - 1; i++) {
            avgVolume += volumes.get(i);
        }
        avgVolume /= avgPeriod;
        
        if (avgVolume <= 0) return 0;
        
        return todayVolume / avgVolume;
    }

    /**
     * מחזיר תיאור מילולי של הפילטרים הפעילים
     */
    public static String getActiveFiltersDescription() {
        ScoringConfig.EntryFiltersConfig filters = ScoringConfig.getActiveEntryFilters();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 פילטרים פעילים:\n");
        
        // Gate 1: Safety
        if (filters.vetoMScoreEnabled) {
            sb.append("   ✓ M-Score < ").append(String.format("%.2f", filters.vetoMScoreThreshold)).append(" (סיכון הונאה)\n");
        }
        if (filters.vetoZScoreEnabled) {
            sb.append("   ✓ Z-Score > ").append(String.format("%.1f", filters.vetoZScoreThreshold)).append(" (סיכון פשיטת רגל)\n");
        }
        
        // Gate 2: RS
        if (filters.rsEntryFilterEnabled) {
            sb.append("   ✓ RS > ").append(String.format("%.2f", filters.rsEntryThreshold)).append(" לכניסה\n");
            sb.append("   ✓ RS < ").append(String.format("%.2f", filters.rsExitThreshold)).append(" ליציאה\n");
        }
        
        // Gate 3: Technical
        if (filters.sma200FilterEnabled) {
            sb.append("   ✓ מחיר > SMA200 (מגמה ראשית)\n");
        }
        if (filters.smaFilterEnabled) {
            sb.append("   ✓ מחיר > SMA").append(filters.smaPeriod).append(" (תזמון כניסה)\n");
        }
        if (filters.rsiFilterEnabled) {
            sb.append("   ✓ RSI: ").append(String.format("%.0f", filters.rsiMinThreshold))
              .append(" - ").append(String.format("%.0f", filters.rsiMaxThreshold)).append("\n");
        }
        if (filters.volumeFilterEnabled) {
            sb.append("   ✓ נפח > ").append(String.format("%.0f%%", (filters.volumeMinRatioToAvg - 1) * 100)).append(" מממוצע\n");
        }
        
        // Risk Management
        if (filters.atrStopLossEnabled) {
            sb.append("   ✓ Stop-Loss: ATR x").append(String.format("%.1f", filters.atrStopLossMultiplier)).append("\n");
        }
        if (filters.trailingStopEnabled) {
            sb.append("   ✓ Trailing Stop פעיל\n");
        }
        sb.append("   ✓ סיכון לעסקה: ").append(String.format("%.1f%%", filters.riskPercentPerTrade)).append("\n");
        sb.append("   ✓ יחס R:R: 1:").append(String.format("%.1f", filters.riskRewardRatio)).append("\n");
        
        return sb.toString();
    }
}
