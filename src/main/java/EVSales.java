/**
 * EV/Sales Analysis - ניתוח יחס שווי חברה למכירות
 * מודד כמה משלם השוק על כל דולר הכנסות
 */
public class EVSales {

    // ממוצעים לפי סקטור (לצורך השוואה)
    public static final double TECH_AVERAGE = 6.0;
    public static final double HEALTHCARE_AVERAGE = 4.0;
    public static final double CONSUMER_AVERAGE = 2.0;
    public static final double INDUSTRIAL_AVERAGE = 1.5;
    public static final double FINANCIAL_AVERAGE = 3.0;
    public static final double GENERAL_AVERAGE = 2.5;

    /**
     * מחשב את יחס Enterprise Value to Sales (EV/Sales).
     * @param marketCapitalization שווי שוק כולל.
     * @param totalDebt סך ההתחייבויות (חוב).
     * @param cashAndEquivalents מזומנים ושווי מזומנים.
     * @param annualRevenue הכנסות שנתיות.
     * @return יחס EV/Sales.
     */
    public static double calculateEVSalesRatio(
            double marketCapitalization, double totalDebt, double cashAndEquivalents, double annualRevenue) {

        if (annualRevenue <= 0) {
            return Double.NaN;
        }

        double enterpriseValue = marketCapitalization + totalDebt - cashAndEquivalents;
        return enterpriseValue / annualRevenue;
    }

    /**
     * מחשב את ה-Enterprise Value
     */
    public static double calculateEnterpriseValue(double marketCap, double totalDebt, double cash) {
        return marketCap + totalDebt - cash;
    }

    /**
     * מחזיר פסק דין על בסיס יחס EV/Sales
     * @param evSalesRatio יחס EV/Sales המחושב
     * @return פסק דין טקסטואלי
     */
    public static String getVerdict(double evSalesRatio) {
        return getVerdict(evSalesRatio, GENERAL_AVERAGE);
    }

    /**
     * מחזיר פסק דין עם השוואה לממוצע ענפי
     * @param evSalesRatio יחס EV/Sales המחושב
     * @param industryAverage ממוצע ענפי להשוואה
     * @return פסק דין טקסטואלי
     */
    public static String getVerdict(double evSalesRatio, double industryAverage) {
        if (Double.isNaN(evSalesRatio) || evSalesRatio <= 0) {
            return "⚪️ לא ניתן לחשב EV/Sales (נתונים חסרים)";
        }

        double relativeToAvg = evSalesRatio / industryAverage;

        if (evSalesRatio < 1.0) {
            return String.format("🟢 זול מאוד! EV/Sales = %.2f (מתחת להכנסות). ייתכן הזדמנות או בעיה בחברה.",
                evSalesRatio);
        } else if (relativeToAvg < 0.5) {
            return String.format("🟢 זול משמעותית! EV/Sales = %.2f (%.0f%% מתחת לממוצע %.1f).",
                evSalesRatio, (1 - relativeToAvg) * 100, industryAverage);
        } else if (relativeToAvg < 0.8) {
            return String.format("🟢 זול יחסית. EV/Sales = %.2f (מתחת לממוצע %.1f).",
                evSalesRatio, industryAverage);
        } else if (relativeToAvg <= 1.2) {
            return String.format("🟡 תמחור הוגן. EV/Sales = %.2f (קרוב לממוצע %.1f).",
                evSalesRatio, industryAverage);
        } else if (relativeToAvg <= 2.0) {
            return String.format("🟠 יקר יחסית. EV/Sales = %.2f (%.0f%% מעל ממוצע %.1f).",
                evSalesRatio, (relativeToAvg - 1) * 100, industryAverage);
        } else {
            return String.format("🔴 יקר מאוד! EV/Sales = %.2f (%.0f%% מעל ממוצע %.1f). דורש צמיחה גבוהה להצדקה.",
                evSalesRatio, (relativeToAvg - 1) * 100, industryAverage);
        }
    }

    /**
     * מחזיר את ממוצע הענף המתאים
     */
    public static double getIndustryAverage(String sector) {
        if (sector == null) return GENERAL_AVERAGE;
        
        String s = sector.toLowerCase();
        if (s.contains("tech") || s.contains("software") || s.contains("internet")) {
            return TECH_AVERAGE;
        } else if (s.contains("health") || s.contains("pharma") || s.contains("bio")) {
            return HEALTHCARE_AVERAGE;
        } else if (s.contains("consumer") || s.contains("retail")) {
            return CONSUMER_AVERAGE;
        } else if (s.contains("industrial") || s.contains("manufactur")) {
            return INDUSTRIAL_AVERAGE;
        } else if (s.contains("financ") || s.contains("bank") || s.contains("insurance")) {
            return FINANCIAL_AVERAGE;
        }
        return GENERAL_AVERAGE;
    }

    /**
     * מחשב ומחזיר פסק דין מלא
     */
    public static String analyzeAndGetVerdict(double marketCap, double totalDebt, double cash, 
                                               double revenue, String sector) {
        double evSales = calculateEVSalesRatio(marketCap, totalDebt, cash, revenue);
        double industryAvg = getIndustryAverage(sector);
        return getVerdict(evSales, industryAvg);
    }
}