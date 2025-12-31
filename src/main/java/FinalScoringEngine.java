
import java.util.ArrayList;
import java.util.List;

public class FinalScoringEngine {

    public static class AnalysisResult {
        public int finalScore; // 0-100
        public String recommendation; // BUY, HOLD, SELL, AVOID
        public List<String> keyInsights = new ArrayList<>();
    }

    public static AnalysisResult computeFinalScore(
            double zScore, double mScore, double sloanRatio, // Risk
            double fScore, double peg, double dcfMargin,    // Fundamental
            boolean technicalBullish, double rsi,           // Technical
            double ccc, double roicWaccSpread,              // Efficiency
            double grahamMarginOfSafety                     // Graham Valuation
    ) {
        AnalysisResult result = new AnalysisResult();
        double score = 0;

        // --- שלב 1: VETO (בדיקת חסימה) ---
        if (mScore > -1.78 || zScore < 1.1) {
            result.finalScore = 0;
            result.recommendation = "🛑 AVOID (High Fraud/Bankruptcy Risk)";
            result.keyInsights.add("חסימה: חשד למניפולציה חשבונאית או סיכון פשיטת רגל מיידי.");
            return result;
        }

        // --- שלב 2: שקלול פונדמנטלי (50 נקודות) ---
        if (fScore >= 7) score += 15;
        if (peg < 1.2) score += 15;
        if (dcfMargin > 0.20) score += 20; // מרווח ביטחון מעל 20%
        else if (dcfMargin > 0) score += 10;

        // --- שלב 3: שקלול טכני (30 נקודות) ---
        if (technicalBullish) score += 15;
        if (rsi > 40 && rsi < 65) score += 15; // מומנטום בריא (לא קניית יתר)

        // --- שלב 4: יעילות וצמיחה (20 נקודות) ---
        if (roicWaccSpread > 0.05) score += 10;
        if (ccc < 40) score += 10; // יעילות הון חוזר

        // --- שלב 5: Graham Valuation (bonus up to 10 נקודות) ---
        if (Double.isFinite(grahamMarginOfSafety)) {
            if (grahamMarginOfSafety >= 0.33) score += 10; // מרווח ביטחון גרהם 33%+
            else if (grahamMarginOfSafety >= 0.15) score += 5; // מרווח ביטחון סביר
        }

        result.finalScore = (int) Math.min(score, 100);

        // --- קביעת המלצה סופית ---
        if (score >= 80) result.recommendation = "🚀 STRONG BUY";
        else if (score >= 60) result.recommendation = "🟢 BUY";
        else if (score >= 40) result.recommendation = "🟡 HOLD";
        else result.recommendation = "🔴 SELL / AVOID";

        // הוספת תובנות
        if (roicWaccSpread > 0.1) result.keyInsights.add("תובנה: החברה היא 'מכונת יצירת ערך' (ROIC גבוה מאוד).");
        if (sloanRatio > 0.2) result.keyInsights.add("אזהרה: איכות הרווחים נמוכה (Accruals גבוהים).");
        if (Double.isFinite(grahamMarginOfSafety) && grahamMarginOfSafety >= 0.33) {
            result.keyInsights.add("תובנה: מרווח ביטחון גרהם גבוה (33%+) - מניה זולה לפי הערכת ערך קלאסית.");
        } else if (Double.isFinite(grahamMarginOfSafety) && grahamMarginOfSafety < 0) {
            result.keyInsights.add("אזהרה: המניה מעל השווי הפנימי לפי גרהם - עלולה להיות יקרה.");
        }

        return result;
    }

    // Backward compatibility overload (without Graham parameter)
    public static AnalysisResult computeFinalScore(
            double zScore, double mScore, double sloanRatio,
            double fScore, double peg, double dcfMargin,
            boolean technicalBullish, double rsi,
            double ccc, double roicWaccSpread
    ) {
        return computeFinalScore(zScore, mScore, sloanRatio, fScore, peg, dcfMargin,
                technicalBullish, rsi, ccc, roicWaccSpread, Double.NaN);
    }
}