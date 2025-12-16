public class FundamentalAnalysis {

    // הקלאס הזה אינו דורש יצירת מופע, לכן ניתן להגדיר את הקונסטרוקטור כפרטי
    private FundamentalAnalysis() {
        // מונע יצירת מופע (instance) של הקלאס - נשתמש רק במתודות הסטטיות.
    }

    /**
     * מחשב את יחס המחיר לרווח (P/E Ratio).
     * @param price מחיר המניה הנוכחי.
     * @param eps רווח למניה (Earnings Per Share).
     * @return יחס P/E.
     */
    public static double calculatePERatio(double price, double eps) {
        if (eps <= 0) {
            // טיפול במקרה של הפסד או EPS אפס (מחזיר NaN).
            return Double.NaN;
        }
        return price / eps;
    }

    /**
     * מחשב את יחס החוב להון (Debt-to-Equity Ratio).
     * @param totalDebt החוב הכולל של החברה.
     * @param shareholdersEquity הון עצמי של בעלי המניות.
     * @return יחס חוב להון.
     */
    public static double calculateDERatio(double totalDebt, double shareholdersEquity) {
        if (shareholdersEquity <= 0) {
            // מונע חלוקה באפס
            return Double.NaN;
        }
        return totalDebt / shareholdersEquity;
    }

    // בתוך הקלאס FundamentalAnalysis.java

    /**
     * מחשב את יחס PEG (P/E Ratio / Growth Rate).
     * @param peRatio יחס מחיר לרווח נוכחי.
     * @param expectedGrowthRate שיעור הצמיחה הצפוי ברווחים (באחוזים, לדוגמה 20).
     * @return יחס PEG.
     */
    public static double calculatePEGRatio(double peRatio, double expectedGrowthRate) {
        // PEG אינו רלוונטי עבור הפסדים או צמיחה אפסית
        if (Double.isNaN(peRatio) || expectedGrowthRate <= 0) {
            return Double.NaN;
        }
        // הנוסחה: P/E חלקי הצמיחה באחוזים (לא עשרוני)
        return peRatio / expectedGrowthRate;
    }

    // ניתן להוסיף כאן מתודות נוספות לחישוב יחסים פונדמנטליים אחרים
    // למשל: calculatePriceToBookRatio(price, bookValuePerShare)
}