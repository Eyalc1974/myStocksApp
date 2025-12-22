public class DuPontAnalysis {

    /**
     * מפרק את ה-ROE לשלושה מרכיבים עיקריים.
     * @param netIncome רווח נקי
     * @param sales הכנסות/מכירות
     * @param totalAssets סך נכסים
     * @param totalEquity הון עצמי
     */
    public static void analyze(double netIncome, double sales, double totalAssets, double totalEquity) {
        if (sales <= 0 || totalAssets <= 0 || totalEquity <= 0) {
            return;
        }

        // 1. שולי רווח (יעילות תפעולית)
        double profitMargin = netIncome / sales;
        // 2. תחלופת נכסים (יעילות בשימוש בנכסים)
        double assetTurnover = sales / totalAssets;
        // 3. מינוף פיננסי (רמת החוב ביחס להון)
        double equityMultiplier = totalAssets / totalEquity;

        double roe = profitMargin * assetTurnover * equityMultiplier;

        System.out.println("--- ניתוח DuPont (פירוק איכות הרווח) ---");
        System.out.printf("1. יעילות (Profit Margin): %.2f%%%n", profitMargin * 100);
        System.out.printf("2. ניצול נכסים (Asset Turnover): %.2f%n", assetTurnover);
        System.out.printf("3. מינוף (Equity Multiplier): %.2f%n", equityMultiplier);
        System.out.printf("==> תשואה על ההון (ROE): %.2f%%%n", roe * 100);

        // לוגיקת פרשנות
        if (equityMultiplier > 3.0) {
            System.out.println("אזהרה: ה-ROE גבוה בעיקר בשל מינוף (חוב) גבוה. זהו רווח 'מסוכן'.");
        } else if (profitMargin > 0.20) {
            System.out.println("איכות גבוהה: הרווחיות מגיעה משולי רווח גבוהים (יתרון תחרותי).");
        }
    }
}
