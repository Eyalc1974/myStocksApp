
import java.util.List;

/**
 * Market Regime Filter - מסנן שוק לניצחון על ה-S&P 500
 * 
 * שלושה מרכיבים עיקריים:
 * 1. בדיקת מצב השוק הכללי (S&P 500 מעל SMA 200)
 * 2. Relative Strength - חוזק יחסי של מניה מול S&P 500 ב-3 חודשים אחרונים
 * 3. בונוס צמיחה - Revenue Growth מעל 20%
 */
public class MarketRegimeFilter {

    private static final String SP500_SYMBOL = "SPY"; // ETF שעוקב אחרי S&P 500

    // No caching - always fetch fresh data from AlphaVantage

    /**
     * תוצאת בדיקת מצב השוק
     */
    public static class MarketRegimeResult {
        public boolean marketBullish;           // האם השוק בולי (SPY > SMA200)
        public double spyCurrentPrice;          // מחיר נוכחי של SPY
        public double spySma200;                // SMA 200 של SPY
        public String marketVerdict;            // תיאור מצב השוק
        
        public Double relativeStrength3M;       // חוזק יחסי 3 חודשים (stock return / SPY return)
        public boolean outperformsSpy;          // האם המניה מנצחת את SPY
        public String relativeStrengthVerdict;  // תיאור החוזק היחסי
        
        public Double revenueGrowthRate;        // שיעור צמיחת הכנסות
        public boolean highGrowth;              // האם צמיחה מעל 20%
        public int growthBonus;                 // בונוס נקודות לציון
        
        public int totalBonus;                  // סה"כ בונוס לציון הסופי
        public boolean passesFilter;            // האם עוברת את הפילטר
    }

    /**
     * בודק את מצב השוק הכללי - האם S&P 500 מעל ה-SMA 200 שלו
     */
    public static boolean isMarketBullish() {
        try {
            List<Double> spyPrices = fetchSpyPrices();
            if (spyPrices == null || spyPrices.size() < 200) {
                // אין מספיק נתונים - נניח שוק ניטרלי
                return true;
            }
            
            double currentPrice = spyPrices.get(spyPrices.size() - 1);
            double sma200 = calculateSMA200(spyPrices);
            
            return currentPrice > sma200;
        } catch (Exception e) {
            // בשגיאה - לא חוסמים, מחזירים true
            return true;
        }
    }

    /**
     * מחשב את ה-SMA 200 של רשימת מחירים
     */
    private static double calculateSMA200(List<Double> prices) {
        if (prices == null || prices.size() < 200) {
            return Double.NaN;
        }
        
        double sum = 0;
        int start = prices.size() - 200;
        for (int i = start; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return sum / 200.0;
    }

    /**
     * מחשב את התשואה של נייר ערך ב-N ימים אחרונים
     */
    private static double calculateReturn(List<Double> prices, int days) {
        if (prices == null || prices.size() < days + 1) {
            return Double.NaN;
        }
        
        double currentPrice = prices.get(prices.size() - 1);
        double oldPrice = prices.get(prices.size() - 1 - days);
        
        if (oldPrice == 0) return Double.NaN;
        return (currentPrice - oldPrice) / oldPrice;
    }

    /**
     * מחשב Relative Strength של מניה מול S&P 500 ב-3 חודשים (~63 ימי מסחר)
     * ערך > 1 = המניה מנצחת את המדד
     */
    public static double calculateRelativeStrength(List<Double> stockPrices, int tradingDays) {
        try {
            List<Double> spyPrices = fetchSpyPrices();
            if (spyPrices == null || stockPrices == null) {
                return Double.NaN;
            }
            
            double stockReturn = calculateReturn(stockPrices, tradingDays);
            double spyReturn = calculateReturn(spyPrices, tradingDays);
            
            if (Double.isNaN(stockReturn) || Double.isNaN(spyReturn) || spyReturn == 0) {
                return Double.NaN;
            }
            
            // Relative Strength = (1 + stock return) / (1 + spy return)
            return (1 + stockReturn) / (1 + spyReturn);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * ניתוח מלא של Market Regime עבור מניה
     * 
     * @param stockPrices רשימת מחירי הסגירה של המניה
     * @param revenueGrowthRate שיעור צמיחת ההכנסות (כעשרוני, למשל 0.25 = 25%)
     * @return תוצאת הניתוח המלאה
     */
    public static MarketRegimeResult analyze(List<Double> stockPrices, Double revenueGrowthRate) {
        MarketRegimeResult result = new MarketRegimeResult();
        result.totalBonus = 0;
        result.passesFilter = true; // ברירת מחדל - עוברת
        
        // --- שלב 1: בדיקת מצב השוק הכללי ---
        try {
            List<Double> spyPrices = fetchSpyPrices();
            if (spyPrices != null && spyPrices.size() >= 200) {
                result.spyCurrentPrice = spyPrices.get(spyPrices.size() - 1);
                result.spySma200 = calculateSMA200(spyPrices);
                result.marketBullish = result.spyCurrentPrice > result.spySma200;
                
                if (result.marketBullish) {
                    result.marketVerdict = "🟢 שוק בולי (SPY > SMA200)";
                    result.totalBonus += 5; // בונוס קטן לשוק חיובי
                } else {
                    result.marketVerdict = "🔴 שוק דובי (SPY < SMA200) - זהירות!";
                    result.totalBonus -= 10; // קנס משמעותי לשוק שלילי
                    // בשוק דובי - עדיף להיזהר אבל לא לחסום לגמרי
                }
            } else {
                result.marketBullish = true; // ברירת מחדל
                result.marketVerdict = "⚪ אין מספיק נתוני שוק";
            }
        } catch (Exception e) {
            result.marketBullish = true;
            result.marketVerdict = "⚪ שגיאה בבדיקת שוק";
        }
        
        // --- שלב 2: Relative Strength (3 חודשים = ~63 ימי מסחר) ---
        try {
            int tradingDays3M = 63;
            result.relativeStrength3M = calculateRelativeStrength(stockPrices, tradingDays3M);
            
            if (result.relativeStrength3M != null && Double.isFinite(result.relativeStrength3M)) {
                result.outperformsSpy = result.relativeStrength3M > 1.0;
                
                if (result.relativeStrength3M > 1.15) {
                    // מנצחת את המדד ביותר מ-15%
                    result.relativeStrengthVerdict = "🚀 מובילה את השוק (RS=" + String.format("%.2f", result.relativeStrength3M) + ")";
                    result.totalBonus += 15;
                } else if (result.relativeStrength3M > 1.0) {
                    // מנצחת את המדד
                    result.relativeStrengthVerdict = "🟢 חזקה מהשוק (RS=" + String.format("%.2f", result.relativeStrength3M) + ")";
                    result.totalBonus += 10;
                } else if (result.relativeStrength3M > 0.85) {
                    // קרובה למדד
                    result.relativeStrengthVerdict = "🟡 ביצועים דומים לשוק (RS=" + String.format("%.2f", result.relativeStrength3M) + ")";
                    // ללא בונוס
                } else {
                    // נגררת אחרי המדד
                    result.relativeStrengthVerdict = "🔴 נגררת אחרי השוק (RS=" + String.format("%.2f", result.relativeStrength3M) + ")";
                    result.totalBonus -= 5;
                }
            } else {
                result.relativeStrengthVerdict = "⚪ אין מספיק נתונים ל-RS";
            }
        } catch (Exception e) {
            result.relativeStrengthVerdict = "⚪ שגיאה בחישוב RS";
        }
        
        // --- שלב 3: בונוס צמיחה (Revenue Growth > 20%) ---
        result.revenueGrowthRate = revenueGrowthRate;
        if (revenueGrowthRate != null && Double.isFinite(revenueGrowthRate)) {
            result.highGrowth = revenueGrowthRate > 0.20;
            
            if (revenueGrowthRate > 0.30) {
                // צמיחה מעל 30% - בונוס גדול
                result.growthBonus = 15;
                result.totalBonus += 15;
            } else if (revenueGrowthRate > 0.20) {
                // צמיחה מעל 20% - בונוס בינוני
                result.growthBonus = 10;
                result.totalBonus += 10;
            } else if (revenueGrowthRate > 0.10) {
                // צמיחה סבירה
                result.growthBonus = 5;
                result.totalBonus += 5;
            } else if (revenueGrowthRate < 0) {
                // צמיחה שלילית - קנס
                result.growthBonus = -5;
                result.totalBonus -= 5;
            } else {
                result.growthBonus = 0;
            }
        } else {
            result.growthBonus = 0;
        }
        
        // --- החלטה סופית ---
        // בשוק דובי עם מניה חלשה - נמליץ להיזהר
        if (!result.marketBullish && result.relativeStrength3M != null && result.relativeStrength3M < 0.9) {
            result.passesFilter = false;
        }
        
        return result;
    }

    /**
     * שולף את נתוני S&P 500 (SPY) מ-Alpha Vantage - always fetches fresh data
     */
    private static List<Double> fetchSpyPrices() {
        try {
            // שמירת ה-ticker הנוכחי
            String savedTicker = null;
            try {
                java.lang.reflect.Field tickerField = DataFetcher.class.getDeclaredField("TICKER");
                tickerField.setAccessible(true);
                savedTicker = (String) tickerField.get(null);
            } catch (Exception ignore) {}
            
            // שליפת נתוני SPY - always fresh from AlphaVantage
            DataFetcher.setTicker(SP500_SYMBOL);
            String jsonData = DataFetcher.fetchStockData();
            
            // שחזור ה-ticker המקורי
            if (savedTicker != null) {
                DataFetcher.setTicker(savedTicker);
            }
            
            if (jsonData == null || jsonData.isBlank()) {
                return null;
            }
            
            return PriceJsonParser.extractClosingPrices(jsonData);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * clearCache - no-op since caching is disabled
     */
    public static void clearCache() {
        // No caching - nothing to clear
    }

    /**
     * פסק דין מסכם על סמך Market Regime
     */
    public static String getOverallVerdict(MarketRegimeResult result) {
        if (result == null) return "⚪ אין נתונים";
        
        StringBuilder sb = new StringBuilder();
        
        if (!result.passesFilter) {
            sb.append("🛑 לא עוברת פילטר שוק");
        } else if (result.totalBonus >= 25) {
            sb.append("🚀 מניה מובילה בשוק חזק");
        } else if (result.totalBonus >= 15) {
            sb.append("🟢 מניה חזקה");
        } else if (result.totalBonus >= 5) {
            sb.append("🟡 מניה סבירה");
        } else if (result.totalBonus >= 0) {
            sb.append("⚪ מניה ניטרלית");
        } else {
            sb.append("🔴 מניה חלשה");
        }
        
        sb.append(" (בונוס: ").append(result.totalBonus > 0 ? "+" : "").append(result.totalBonus).append(")");
        
        return sb.toString();
    }
}
