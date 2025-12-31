/**
 * Graham Valuation Model - מודל הערכת שווי לפי בנג'מין גרהם
 * 
 * כולל שתי גישות:
 * 1. Graham Number - תקרת מחיר שמרנית: √(22.5 × EPS × BVPS)
 * 2. Graham Intrinsic Value Formula - שווי פנימי: V = EPS × (8.5 + 2g) × 4.4/Y
 */
public class GrahamValuation {

    private static final double BASE_PE_NO_GROWTH = 8.5;
    private static final double GROWTH_MULTIPLIER = 2.0;
    private static final double HISTORICAL_AAA_YIELD = 4.4;
    private static final double DEFAULT_CURRENT_AAA_YIELD = 5.0;

    /**
     * מחשב את השווי ההוגן לפי בנימין גרהם (Graham Number).
     * @param eps - רווח למניה (Trailing 12 Months)
     * @param bvps - ערך מאזני למניה (Book Value Per Share)
     * @return המחיר המקסימלי להשקעת ערך
     */
    public static double calculateGrahamNumber(double eps, double bvps) {
        if (eps <= 0 || bvps <= 0) return 0;

        // הנוסחה: שורש של (22.5 * רווח * הון עצמי)
        double result = Math.sqrt(22.5 * eps * bvps);
        return result;
    }

    /**
     * מחשב את השווי הפנימי לפי נוסחת גרהם המורחבת.
     * V = EPS × (8.5 + 2g) × 4.4/Y
     * 
     * @param eps רווח למניה (Earnings Per Share)
     * @param growthRatePercent קצב צמיחה צפוי באחוזים (לדוגמה: 10 עבור 10%)
     * @param currentAAAYield תשואת אג"ח AAA נוכחית באחוזים (ברירת מחדל: 5%)
     * @return השווי הפנימי המחושב
     */
    public static double calculateIntrinsicValue(double eps, double growthRatePercent, double currentAAAYield) {
        if (eps <= 0) return 0.0;
        
        double cappedGrowth = Math.max(0, Math.min(growthRatePercent, 25));
        double yield = (currentAAAYield > 0) ? currentAAAYield : DEFAULT_CURRENT_AAA_YIELD;
        
        double baseMultiplier = BASE_PE_NO_GROWTH + (GROWTH_MULTIPLIER * cappedGrowth);
        double yieldAdjustment = HISTORICAL_AAA_YIELD / yield;
        
        return eps * baseMultiplier * yieldAdjustment;
    }

    /**
     * גרסה פשוטה - משתמש בתשואת אג"ח ברירת מחדל
     */
    public static double calculateIntrinsicValue(double eps, double growthRatePercent) {
        return calculateIntrinsicValue(eps, growthRatePercent, DEFAULT_CURRENT_AAA_YIELD);
    }

    /**
     * מחשב את מרווח הביטחון (Margin of Safety)
     */
    public static double calculateMarginOfSafety(double intrinsicValue, double currentPrice) {
        if (intrinsicValue <= 0 || currentPrice <= 0) return 0.0;
        return (intrinsicValue - currentPrice) / intrinsicValue;
    }

    /**
     * בודק אם המניה עומדת בקריטריון המשולב של גרהם (P/E × P/B < 22.5)
     */
    public static boolean meetsCombinedCriterion(double peRatio, double pbRatio) {
        if (peRatio <= 0 || pbRatio <= 0) return false;
        return (peRatio * pbRatio) <= 22.5;
    }

    public static String getVerdict(double currentPrice, double grahamNumber) {
        if (grahamNumber == 0) return "N/A";
        double margin = (grahamNumber / currentPrice) - 1;

        if (currentPrice < grahamNumber) {
            return String.format("UNDERVALUED (Margin: %.2f%%)", margin * 100);
        } else {
            return "OVERVALUED (Above Graham Ceiling)";
        }
    }

    /**
     * פסק דין מורחב הכולל את נוסחת השווי הפנימי
     */
    public static String getExtendedVerdict(double currentPrice, double grahamNumber, double intrinsicValue) {
        StringBuilder sb = new StringBuilder();
        
        // Graham Number verdict
        if (grahamNumber > 0) {
            double gnMargin = (grahamNumber / currentPrice) - 1;
            if (currentPrice < grahamNumber) {
                sb.append(String.format("🟢 Graham Number: מתחת לתקרה (מרווח: %.1f%%)", gnMargin * 100));
            } else {
                sb.append("🔴 Graham Number: מעל התקרה השמרנית");
            }
        }
        
        // Intrinsic Value verdict
        if (intrinsicValue > 0) {
            double ivMargin = calculateMarginOfSafety(intrinsicValue, currentPrice);
            if (sb.length() > 0) sb.append(" | ");
            
            if (ivMargin >= 0.33) {
                sb.append(String.format("🟢 IV: זול מאוד (MoS: %.1f%%)", ivMargin * 100));
            } else if (ivMargin >= 0.15) {
                sb.append(String.format("🟡 IV: זול יחסית (MoS: %.1f%%)", ivMargin * 100));
            } else if (ivMargin >= 0) {
                sb.append(String.format("🟠 IV: מחיר הוגן (MoS: %.1f%%)", ivMargin * 100));
            } else {
                sb.append(String.format("🔴 IV: יקר (מעל ב-%.1f%%)", Math.abs(ivMargin) * 100));
            }
        }
        
        return sb.length() > 0 ? sb.toString() : "N/A";
    }
}