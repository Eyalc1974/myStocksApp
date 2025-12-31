/**
 * DuPont Analysis - ניתוח דופונט לפירוק איכות ה-ROE
 * מפרק את התשואה על ההון לשלושה מרכיבים: רווחיות, יעילות ומינוף
 */
public class DuPontAnalysis {

    public static class DuPontResult {
        public double profitMargin;      // Net Income / Sales
        public double assetTurnover;     // Sales / Total Assets
        public double equityMultiplier;  // Total Assets / Equity
        public double roe;               // ROE = PM × AT × EM
        public String qualityRating;     // HIGH, MEDIUM, LOW, RISKY
        public String verdict;
        public String roePrimaryDriver;  // מה מניע את ה-ROE
    }

    /**
     * מבצע ניתוח דופונט ומחזיר תוצאות מפורטות
     */
    public static DuPontResult analyze(double netIncome, double sales, double totalAssets, double totalEquity) {
        DuPontResult result = new DuPontResult();
        
        if (sales <= 0 || totalAssets <= 0 || totalEquity <= 0) {
            result.qualityRating = "N/A";
            result.verdict = "⚪️ לא ניתן לחשב - נתונים חסרים או לא תקינים";
            return result;
        }

        // חישוב שלושת המרכיבים
        result.profitMargin = netIncome / sales;
        result.assetTurnover = sales / totalAssets;
        result.equityMultiplier = totalAssets / totalEquity;
        result.roe = result.profitMargin * result.assetTurnover * result.equityMultiplier;

        // זיהוי המניע העיקרי של ה-ROE
        double pmContribution = Math.abs(result.profitMargin);
        double atContribution = result.assetTurnover;
        double emContribution = result.equityMultiplier - 1; // מינוף מעל 1
        
        if (pmContribution > atContribution && pmContribution > emContribution * 0.1) {
            result.roePrimaryDriver = "Profit Margin (רווחיות גבוהה)";
        } else if (atContribution > emContribution * 0.1) {
            result.roePrimaryDriver = "Asset Turnover (יעילות בנכסים)";
        } else {
            result.roePrimaryDriver = "Leverage (מינוף פיננסי)";
        }

        // קביעת איכות ה-ROE
        if (result.equityMultiplier > 4.0) {
            result.qualityRating = "RISKY";
            result.verdict = String.format("🔴 ROE מסוכן (%.1f%%): מינוף קיצוני (%.1fx). הרווח מגיע מחוב, לא מביצועים!",
                result.roe * 100, result.equityMultiplier);
        } else if (result.equityMultiplier > 3.0 && result.profitMargin < 0.10) {
            result.qualityRating = "LOW";
            result.verdict = String.format("🟠 ROE באיכות נמוכה (%.1f%%): מינוף גבוה (%.1fx) עם רווחיות חלשה (%.1f%%).",
                result.roe * 100, result.equityMultiplier, result.profitMargin * 100);
        } else if (result.profitMargin > 0.15 && result.equityMultiplier < 2.5) {
            result.qualityRating = "HIGH";
            result.verdict = String.format("🟢 ROE באיכות גבוהה (%.1f%%): רווחיות חזקה (%.1f%%) עם מינוף סביר. יתרון תחרותי!",
                result.roe * 100, result.profitMargin * 100);
        } else if (result.assetTurnover > 1.5 && result.equityMultiplier < 2.5) {
            result.qualityRating = "HIGH";
            result.verdict = String.format("🟢 ROE באיכות גבוהה (%.1f%%): יעילות נכסים מצוינת (%.2fx). מודל עסקי יעיל!",
                result.roe * 100, result.assetTurnover);
        } else {
            result.qualityRating = "MEDIUM";
            result.verdict = String.format("🟡 ROE סביר (%.1f%%): איזון בין רווחיות (%.1f%%), יעילות (%.2fx) ומינוף (%.1fx).",
                result.roe * 100, result.profitMargin * 100, result.assetTurnover, result.equityMultiplier);
        }

        return result;
    }

    /**
     * מחזיר פסק דין טקסטואלי
     */
    public static String getVerdict(double netIncome, double sales, double totalAssets, double totalEquity) {
        return analyze(netIncome, sales, totalAssets, totalEquity).verdict;
    }

    /**
     * מחזיר את ה-ROE המחושב
     */
    public static double calculateROE(double netIncome, double sales, double totalAssets, double totalEquity) {
        DuPontResult result = analyze(netIncome, sales, totalAssets, totalEquity);
        return result.roe;
    }

    /**
     * בודק אם ה-ROE הוא "ROE איכותי" (לא מבוסס מינוף מוגזם)
     */
    public static boolean isQualityROE(double netIncome, double sales, double totalAssets, double totalEquity) {
        DuPontResult result = analyze(netIncome, sales, totalAssets, totalEquity);
        return "HIGH".equals(result.qualityRating);
    }

    /**
     * גרסה עם הדפסה לקונסול (תאימות אחורה)
     */
    public static void printAnalysis(double netIncome, double sales, double totalAssets, double totalEquity) {
        DuPontResult result = analyze(netIncome, sales, totalAssets, totalEquity);
        
        System.out.println("\n--- ניתוח DuPont (פירוק איכות הרווח) ---");
        System.out.printf("1. שולי רווח (Profit Margin): %.2f%%%n", result.profitMargin * 100);
        System.out.printf("2. תחלופת נכסים (Asset Turnover): %.2fx%n", result.assetTurnover);
        System.out.printf("3. מינוף פיננסי (Equity Multiplier): %.2fx%n", result.equityMultiplier);
        System.out.printf("==> תשואה על ההון (ROE): %.2f%%%n", result.roe * 100);
        System.out.printf("מניע עיקרי: %s%n", result.roePrimaryDriver);
        System.out.println(result.verdict);
    }
}
