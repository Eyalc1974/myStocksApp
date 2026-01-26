/**
 * Risk Manager - ניהול סיכונים וגודל פוזיציה
 * מחשב כמה מניות לקנות כך שהנזק במקרה הפסד יהיה מוגדר מראש
 */
public class RiskManager {

    // ברירות מחדל
    public static final double DEFAULT_RISK_PERCENTAGE = 0.01;  // 1% סיכון לעסקה
    public static final double DEFAULT_ATR_MULTIPLIER = 2.5;    // מכפיל ATR לסטופ-לוס

    /**
     * מחשב כמה מניות לקנות.
     * @param accountEquity סך ההון בתיק (למשל 36,000$).
     * @param riskPercentage אחוז סיכון לעסקה (למשל 0.01 עבור 1%).
     * @param entryPrice מחיר הכניסה למניה.
     * @param atr מדד התנודתיות (ATR).
     * @return מספר המניות לקנייה.
     */
    public static int calculatePositionSize(double accountEquity, double riskPercentage, double entryPrice, double atr) {
        return calculatePositionSize(accountEquity, riskPercentage, entryPrice, atr, DEFAULT_ATR_MULTIPLIER);
    }

    /**
     * מחשב כמה מניות לקנות עם מכפיל ATR מותאם אישית.
     * @param accountEquity סך ההון בתיק.
     * @param riskPercentage אחוז סיכון לעסקה.
     * @param entryPrice מחיר הכניסה למניה.
     * @param atr מדד התנודתיות (ATR).
     * @param atrMultiplier מכפיל ATR לסטופ-לוס (2.0-3.0 נהוג).
     * @return מספר המניות לקנייה.
     */
    public static int calculatePositionSize(double accountEquity, double riskPercentage, double entryPrice, double atr, double atrMultiplier) {
        if (accountEquity <= 0 || entryPrice <= 0 || atr <= 0) return 0;
        
        // 1. קביעת סכום הכסף המקסימלי להפסד בעסקה אחת
        double amountToRisk = accountEquity * riskPercentage;

        // 2. קביעת מרחק הסטופ-לוס
        double stopLossDistance = atr * atrMultiplier;

        // 3. חישוב כמות המניות: סכום הסיכון חלקי המרחק לסטופ
        if (stopLossDistance <= 0) return 0;
        
        int shares = (int) (amountToRisk / stopLossDistance);
        
        // הגבלה: שלא נקנה יותר כסף ממה שיש לנו בתיק (מינוף 1:1)
        int maxSharesPossible = (int) (accountEquity / entryPrice);
        
        return Math.min(shares, maxSharesPossible);
    }

    /**
     * מחשב את סכום ההשקעה הכולל
     */
    public static double calculateTotalInvestment(int shares, double entryPrice) {
        return shares * entryPrice;
    }

    /**
     * מחשב את אחוז ההשקעה מהתיק
     */
    public static double calculatePortfolioPercentage(int shares, double entryPrice, double accountEquity) {
        if (accountEquity <= 0) return 0;
        return (shares * entryPrice) / accountEquity;
    }

    /**
     * מחשב את ההפסד המקסימלי הצפוי
     */
    public static double calculateMaxLoss(int shares, double atr, double atrMultiplier) {
        return shares * atr * atrMultiplier;
    }

    /**
     * מחשב את הרווח הפוטנציאלי (עם יחס סיכון/תגמול)
     * @param riskRewardRatio יחס תגמול לסיכון (למשל 2.0 = רווח פי 2 מהסיכון)
     */
    public static double calculatePotentialProfit(int shares, double atr, double atrMultiplier, double riskRewardRatio) {
        return shares * atr * atrMultiplier * riskRewardRatio;
    }

    /**
     * Result class for position sizing analysis
     */
    public static class PositionResult {
        public final int shares;                // מספר מניות לקנייה
        public final double totalInvestment;    // סכום השקעה כולל
        public final double portfolioPercent;   // אחוז מהתיק
        public final double stopLossPrice;      // מחיר סטופ-לוס
        public final double maxLoss;            // הפסד מקסימלי
        public final double takeProfitPrice;    // מחיר Take Profit
        public final double potentialProfit;    // רווח פוטנציאלי
        public final double riskRewardRatio;    // יחס סיכון/תגמול

        public PositionResult(int shares, double entryPrice, double accountEquity, 
                             double atr, double atrMultiplier, double riskRewardRatio) {
            this.shares = shares;
            this.totalInvestment = shares * entryPrice;
            this.portfolioPercent = accountEquity > 0 ? (this.totalInvestment / accountEquity) : 0;
            this.stopLossPrice = entryPrice - (atr * atrMultiplier);
            this.maxLoss = shares * atr * atrMultiplier;
            this.takeProfitPrice = entryPrice + (atr * atrMultiplier * riskRewardRatio);
            this.potentialProfit = shares * atr * atrMultiplier * riskRewardRatio;
            this.riskRewardRatio = riskRewardRatio;
        }

        public String getSummaryHebrew() {
            return String.format(
                "קנה %d מניות (%.1f%% מהתיק) | השקעה: $%.0f | הפסד מקס: $%.0f | רווח פוטנציאלי: $%.0f",
                shares, portfolioPercent * 100, totalInvestment, maxLoss, potentialProfit
            );
        }

        public String getSummaryEnglish() {
            return String.format(
                "Buy %d shares (%.1f%% of portfolio) | Investment: $%.0f | Max Loss: $%.0f | Potential Profit: $%.0f",
                shares, portfolioPercent * 100, totalInvestment, maxLoss, potentialProfit
            );
        }
    }

    /**
     * חישוב מלא של גודל פוזיציה עם כל הפרטים
     */
    public static PositionResult analyze(double accountEquity, double riskPercentage, 
                                         double entryPrice, double atr, 
                                         double atrMultiplier, double riskRewardRatio) {
        int shares = calculatePositionSize(accountEquity, riskPercentage, entryPrice, atr, atrMultiplier);
        return new PositionResult(shares, entryPrice, accountEquity, atr, atrMultiplier, riskRewardRatio);
    }

    /**
     * חישוב עם ברירות מחדל
     */
    public static PositionResult analyzeWithDefaults(double accountEquity, double entryPrice, double atr) {
        return analyze(accountEquity, DEFAULT_RISK_PERCENTAGE, entryPrice, atr, DEFAULT_ATR_MULTIPLIER, 2.0);
    }
}
