
public class GrahamNumber {

    /**
     * מחשב את מספר גרהם - המחיר המקסימלי להשקעת ערך שמרנית.
     * @param eps רווח למניה (Earnings Per Share).
     * @param bookValuePerShare ערך בספרים למניה.
     * @return המחיר המקסימלי (Intrinsic Value לפי גרהם).
     */
    public static double calculateGrahamPrice(double eps, double bookValuePerShare) {
        // אם אחד הנתונים שלילי, הנוסחה לא תקפה (לחברה אין ערך לפי גרהם)
        if (eps <= 0 || bookValuePerShare <= 0) {
            return 0.0;
        }

        // חישוב: שורש של (22.5 * EPS * BVPS)
        return Math.sqrt(22.5 * eps * bookValuePerShare);
    }

    public static String getVerdict(double currentPrice, double grahamPrice) {
        if (grahamPrice <= 0) return "⚪️ לא ניתן לחישוב (רווח או הון שליליים)";

        double marginOfSafety = (grahamPrice / currentPrice) - 1;

        if (currentPrice < grahamPrice) {
            return String.format("🟢 מניה זולה (מתחת למספר גרהם). מרווח ביטחון: %.2f%%", marginOfSafety * 100);
        } else {
            return "🔴 מניה יקרה (מעל למספר גרהם). השוק מתמחר ציפיות מעבר לנכסים והרווחים הנוכחיים.";
        }
    }
}