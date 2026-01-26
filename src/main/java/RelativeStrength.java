import java.util.List;

/**
 * Relative Strength (RS) - מדד כוח יחסי
 * משווה את ביצועי המניה לביצועי מדד ה-S&P 500 (SPY)
 * מזהה מניות "מנהיגות" שמובילות את השוק לעומת מניות "נגררות"
 */
public class RelativeStrength {

    /**
     * מחשב את מדד הכוח היחסי.
     * @param stockReturn תשואת המניה בתקופה (למשל 0.15 עבור 15% עליה)
     * @param marketReturn תשואת המדד (SPY) באותה תקופה
     * @return יחס הכוח (מעל 1.0 אומר שהמניה חזקה מהשוק)
     */
    public static double calculateRSRatio(double stockReturn, double marketReturn) {
        // נוסחת בסיס להשוואת ביצועים
        // אם השוק ירד 10% (-0.1) והמניה ירדה רק 5% (-0.05), היחס יהיה גבוה מ-1
        return (1 + stockReturn) / (1 + marketReturn);
    }

    /**
     * מחשב תשואה מרשימת מחירים היסטוריים
     * @param prices רשימת מחירי סגירה (מהישן לחדש)
     * @param periodDays מספר ימי מסחר לחישוב (63 = 3 חודשים, 126 = 6 חודשים)
     * @return התשואה באחוזים (0.15 = 15%)
     */
    public static double calculateReturn(List<Double> prices, int periodDays) {
        if (prices == null || prices.size() < periodDays + 1) {
            return Double.NaN;
        }
        int endIdx = prices.size() - 1;
        int startIdx = endIdx - periodDays;
        if (startIdx < 0) startIdx = 0;
        
        double startPrice = prices.get(startIdx);
        double endPrice = prices.get(endIdx);
        
        if (startPrice <= 0) return Double.NaN;
        
        return (endPrice - startPrice) / startPrice;
    }

    /**
     * מחשב RS Ratio מרשימות מחירים
     * @param stockPrices מחירי המניה
     * @param spyPrices מחירי SPY
     * @param periodDays מספר ימי מסחר
     * @return יחס הכוח היחסי
     */
    public static double calculateRSFromPrices(List<Double> stockPrices, List<Double> spyPrices, int periodDays) {
        double stockReturn = calculateReturn(stockPrices, periodDays);
        double spyReturn = calculateReturn(spyPrices, periodDays);
        
        if (Double.isNaN(stockReturn) || Double.isNaN(spyReturn)) {
            return Double.NaN;
        }
        
        return calculateRSRatio(stockReturn, spyReturn);
    }

    /**
     * מחזיר ניקוד עבור הכוח היחסי
     * @param rsRatio יחס הכוח היחסי
     * @return ניקוד (0-30 נקודות)
     */
    public static int getRSPoints(double rsRatio) {
        if (Double.isNaN(rsRatio)) return 0;
        if (rsRatio >= 1.20) return 30; // המניה חזקה מהשוק ב-20% ומעלה (מנהיגת שוק)
        if (rsRatio >= 1.10) return 20; // חזקה ב-10%
        if (rsRatio >= 1.00) return 10; // חזקה מהשוק במידה מועטה
        return 0; // המניה חלשה מהשוק (Underperforming)
    }

    /**
     * מחזיר קטגוריה טקסטואלית
     * @param rsRatio יחס הכוח היחסי
     * @return קטגוריה (LEADER, PERFORMER, LAGGARD)
     */
    public static String getCategory(double rsRatio) {
        if (Double.isNaN(rsRatio)) return "N/A";
        if (rsRatio >= 1.10) return "LEADER";      // מנהיג שוק
        if (rsRatio >= 0.90) return "PERFORMER";   // ביצועי שוק
        return "LAGGARD";                           // נגרר/חלש
    }

    /**
     * מחזיר תיאור עברית
     */
    public static String getCategoryHebrew(double rsRatio) {
        if (Double.isNaN(rsRatio)) return "לא זמין";
        if (rsRatio >= 1.10) return "מנהיג שוק";
        if (rsRatio >= 0.90) return "ביצועי שוק";
        return "נגרר";
    }

    /**
     * מחזיר אייקון חץ לתצוגה
     * @param rsRatio יחס הכוח היחסי
     * @return חץ (↑↑, →, ↓)
     */
    public static String getArrowIcon(double rsRatio) {
        if (Double.isNaN(rsRatio)) return "?";
        if (rsRatio >= 1.10) return "↑↑";  // Market Leader - חץ ירוק כפול
        if (rsRatio >= 0.90) return "→";   // Market Performer - חץ אפור
        return "↓";                         // Laggard - חץ אדום
    }

    /**
     * מחזיר צבע HTML לתצוגה
     */
    public static String getColor(double rsRatio) {
        if (Double.isNaN(rsRatio)) return "#9ca3af";
        if (rsRatio >= 1.10) return "#22c55e";  // ירוק
        if (rsRatio >= 0.90) return "#9ca3af";  // אפור
        return "#ef4444";                        // אדום
    }

    /**
     * Result class for RS analysis
     */
    public static class RSResult {
        public final double rsRatio3M;      // RS יחס 3 חודשים
        public final double rsRatio6M;      // RS יחס 6 חודשים
        public final double stockReturn3M;  // תשואת מניה 3 חודשים
        public final double stockReturn6M;  // תשואת מניה 6 חודשים
        public final double spyReturn3M;    // תשואת SPY 3 חודשים
        public final double spyReturn6M;    // תשואת SPY 6 חודשים
        public final int points;            // ניקוד
        public final String category;       // קטגוריה
        public final String arrow;          // חץ לתצוגה
        public final String color;          // צבע לתצוגה

        public RSResult(double rsRatio3M, double rsRatio6M, 
                       double stockReturn3M, double stockReturn6M,
                       double spyReturn3M, double spyReturn6M) {
            this.rsRatio3M = rsRatio3M;
            this.rsRatio6M = rsRatio6M;
            this.stockReturn3M = stockReturn3M;
            this.stockReturn6M = stockReturn6M;
            this.spyReturn3M = spyReturn3M;
            this.spyReturn6M = spyReturn6M;
            
            // Primary RS is 3M
            this.points = getRSPoints(rsRatio3M);
            this.category = getCategory(rsRatio3M);
            this.arrow = getArrowIcon(rsRatio3M);
            this.color = getColor(rsRatio3M);
        }

        public String getSummary() {
            if (Double.isNaN(rsRatio3M)) return "לא זמין";
            return String.format("%s (RS: %.2f, Stock: %+.1f%%, SPY: %+.1f%%)", 
                getCategoryHebrew(rsRatio3M), rsRatio3M, 
                stockReturn3M * 100, spyReturn3M * 100);
        }
    }

    /**
     * חישוב מלא של RS עם כל הנתונים
     */
    public static RSResult analyze(List<Double> stockPrices, List<Double> spyPrices) {
        // 63 ימי מסחר ≈ 3 חודשים, 126 ימי מסחר ≈ 6 חודשים
        double stockReturn3M = calculateReturn(stockPrices, 63);
        double stockReturn6M = calculateReturn(stockPrices, 126);
        double spyReturn3M = calculateReturn(spyPrices, 63);
        double spyReturn6M = calculateReturn(spyPrices, 126);
        
        double rs3M = calculateRSRatio(stockReturn3M, spyReturn3M);
        double rs6M = calculateRSRatio(stockReturn6M, spyReturn6M);
        
        return new RSResult(rs3M, rs6M, stockReturn3M, stockReturn6M, spyReturn3M, spyReturn6M);
    }
}
