
public class SloanAnalysis {

    /**
     * מחשב את יחס סלואן (Sloan Ratio).
     * @param netIncome רווח נקי.
     * @param freeCashFlow תזרים מזומנים חופשי (או Cash Flow from Operations).
     * @param totalAssets סך נכסים.
     * @return יחס סלואן באחוזים.
     */
    public static double calculateSloanRatio(double netIncome, double freeCashFlow, double totalAssets) {
        if (totalAssets <= 0) return 0.0;

        // חישוב הפער בין רווח למזומן
        return (netIncome - freeCashFlow) / totalAssets;
    }

    public static String getVerdict(double ratio) {
        if (ratio >= -0.10 && ratio <= 0.10) {
            return "🟢 איכות רווחים גבוהה (הרווח מגובה במזומן)";
        } else if (ratio > 0.10 && ratio <= 0.25 || ratio < -0.10 && ratio >= -0.25) {
            return "🟡 איכות רווחים בינונית (חשד להצטברות Accruals)";
        } else {
            return "🔴 איכות רווחים נמוכה (פער מסוכן בין הדיווח למציאות הכספית!)";
        }
    }
}