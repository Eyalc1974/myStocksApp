
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FinalScoringEngine {

    public static class AnalysisResult {
        public int finalScore; // 0-100
        public String recommendation; // BUY, HOLD, SELL, AVOID
        public List<String> keyInsights = new ArrayList<>();
        public String scoringMode; // Current scoring mode used
    }

    public static AnalysisResult computeFinalScore(
            double zScore, double mScore, double sloanRatio, // Risk
            double fScore, double peg, double dcfMargin,    // Fundamental
            boolean technicalBullish, double rsi,           // Technical
            double ccc, double roicWaccSpread,              // Efficiency
            double grahamMarginOfSafety,                    // Graham Valuation
            int marketRegimeBonus, boolean marketBullish,   // Market Regime Filter
            double relativeStrength, double revenueGrowth   // RS & Growth
    ) {
        AnalysisResult result = new AnalysisResult();
        double score = 0;

        // Load current scoring configuration
        ScoringConfig.ModeConfig config = ScoringConfig.getActiveModeConfig();
        result.scoringMode = ScoringConfig.getActiveMode();
        Map<String, ScoringConfig.IndicatorConfig> indicators = config.indicators;

        // --- שלב 1: VETO (בדיקת חסימה) ---
        if (config.vetoEnabled && (mScore > -1.78 || zScore < 1.1)) {
            result.finalScore = 0;
            result.recommendation = "🛑 AVOID (High Fraud/Bankruptcy Risk)";
            result.keyInsights.add("חסימה: חשד למניפולציה חשבונאית או סיכון פשיטת רגל מיידי.");
            return result;
        }

        // --- שלב 2: שקלול פונדמנטלי ---
        ScoringConfig.IndicatorConfig fScoreInd = indicators.get("fScore");
        if (fScoreInd != null && fScoreInd.enabled && fScore >= fScoreInd.threshold) {
            score += fScoreInd.points;
        }

        ScoringConfig.IndicatorConfig pegInd = indicators.get("peg");
        if (pegInd != null && pegInd.enabled && peg < pegInd.threshold && peg > 0) {
            score += pegInd.points;
        }

        ScoringConfig.IndicatorConfig dcfInd = indicators.get("dcfMargin");
        if (dcfInd != null && dcfInd.enabled) {
            if (dcfMargin > dcfInd.thresholdHigh) {
                score += dcfInd.points;
            } else if (dcfMargin > dcfInd.thresholdLow) {
                score += dcfInd.points / 2;
            }
        }

        // --- שלב 3: שקלול טכני ---
        ScoringConfig.IndicatorConfig bullishInd = indicators.get("technicalBullish");
        if (bullishInd != null && bullishInd.enabled && technicalBullish) {
            score += bullishInd.points;
        }

        ScoringConfig.IndicatorConfig rsiInd = indicators.get("rsiHealthy");
        if (rsiInd != null && rsiInd.enabled && rsi > rsiInd.min && rsi < rsiInd.max) {
            score += rsiInd.points;
        }

        // RSI Oversold bonus (for swing/momentum trading)
        ScoringConfig.IndicatorConfig rsiOversoldInd = indicators.get("rsiOversold");
        if (rsiOversoldInd != null && rsiOversoldInd.enabled && rsi < rsiOversoldInd.threshold) {
            score += rsiOversoldInd.points;
            result.keyInsights.add("תובנה: RSI ב-Oversold (" + String.format("%.1f", rsi) + ") - הזדמנות קנייה טכנית");
        }

        // --- שלב 4: יעילות וצמיחה ---
        ScoringConfig.IndicatorConfig roicInd = indicators.get("roicWacc");
        if (roicInd != null && roicInd.enabled && roicWaccSpread > roicInd.threshold) {
            score += roicInd.points;
        }

        ScoringConfig.IndicatorConfig cccInd = indicators.get("ccc");
        if (cccInd != null && cccInd.enabled && ccc < cccInd.threshold && ccc > 0) {
            score += cccInd.points;
        }

        // --- שלב 5: Graham Valuation (bonus) ---
        ScoringConfig.IndicatorConfig grahamInd = indicators.get("grahamMoS");
        if (grahamInd != null && grahamInd.enabled && Double.isFinite(grahamMarginOfSafety)) {
            if (grahamMarginOfSafety >= grahamInd.thresholdHigh) {
                score += grahamInd.pointsHigh;
            } else if (grahamMarginOfSafety >= grahamInd.thresholdLow) {
                score += grahamInd.pointsLow;
            }
        }

        // --- שלב 6: Market Regime Filter (מסנן שוק לניצחון על S&P 500) ---
        score += marketRegimeBonus;

        if (!marketBullish) {
            result.keyInsights.add("אזהרה: שוק דובי - SPY מתחת ל-SMA200");
        }

        if (Double.isFinite(relativeStrength)) {
            if (relativeStrength > 1.15) {
                result.keyInsights.add("תובנה: מניה מובילה - מנצחת את S&P 500 ביותר מ-15%");
            } else if (relativeStrength < 0.85) {
                result.keyInsights.add("אזהרה: מניה נגררת - נחותה מ-S&P 500 ביותר מ-15%");
            }
        }

        if (Double.isFinite(revenueGrowth) && revenueGrowth > 0.20) {
            result.keyInsights.add("תובנה: צמיחת הכנסות גבוהה (" + String.format("%.0f%%", revenueGrowth * 100) + ") - מניית צמיחה");
        }

        result.finalScore = (int) Math.min(Math.max(score, 0), 100);

        // --- קביעת המלצה סופית (using configurable thresholds) ---
        int strongBuyTh = config.strongBuyThreshold > 0 ? config.strongBuyThreshold : 80;
        int buyTh = config.buyThreshold > 0 ? config.buyThreshold : 60;
        int holdTh = config.holdThreshold > 0 ? config.holdThreshold : 40;
        
        if (score >= strongBuyTh) result.recommendation = "🚀 STRONG BUY";
        else if (score >= buyTh) result.recommendation = "🟢 BUY";
        else if (score >= holdTh) result.recommendation = "🟡 HOLD";
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

    // Backward compatibility overload (without Market Regime parameters)
    public static AnalysisResult computeFinalScore(
            double zScore, double mScore, double sloanRatio,
            double fScore, double peg, double dcfMargin,
            boolean technicalBullish, double rsi,
            double ccc, double roicWaccSpread,
            double grahamMarginOfSafety
    ) {
        return computeFinalScore(zScore, mScore, sloanRatio, fScore, peg, dcfMargin,
                technicalBullish, rsi, ccc, roicWaccSpread, grahamMarginOfSafety,
                0, true, Double.NaN, Double.NaN);
    }

    // Backward compatibility overload (without Graham parameter)
    public static AnalysisResult computeFinalScore(
            double zScore, double mScore, double sloanRatio,
            double fScore, double peg, double dcfMargin,
            boolean technicalBullish, double rsi,
            double ccc, double roicWaccSpread
    ) {
        return computeFinalScore(zScore, mScore, sloanRatio, fScore, peg, dcfMargin,
                technicalBullish, rsi, ccc, roicWaccSpread, Double.NaN,
                0, true, Double.NaN, Double.NaN);
    }
}