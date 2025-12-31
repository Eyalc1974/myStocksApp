import java.util.ArrayList;
import java.util.List;

/**
 * ATR (Average True Range) - מדד תנודתיות
 * משמש למדידת טווח מחירים ממוצע וניהול סיכונים
 */
public class ATR {

    /**
     * מחשב את אינדיקטור Average True Range (ATR) עם Wilder's Smoothing.
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param period תקופת החישוב (לרוב 14).
     * @return רשימה של ערכי ATR.
     */
    public static List<Double> calculateATR(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, int period) {

        List<Double> trValues = new ArrayList<>();
        List<Double> atrValues = new ArrayList<>();

        if (highPrices == null || highPrices.size() < period) {
            return atrValues;
        }

        // 1. חישוב True Range (TR) לכל יום
        for (int i = 0; i < highPrices.size(); i++) {
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double closePrev = (i > 0) ? closingPrices.get(i - 1) : high;

            // TR = max(High - Low, |High - Close_Prev|, |Low - Close_Prev|)
            double tr = Math.max(high - low, Math.max(Math.abs(high - closePrev), Math.abs(low - closePrev)));
            trValues.add(tr);
        }

        // 2. חישוב ATR עם Wilder's Smoothing
        for (int i = 0; i < trValues.size(); i++) {
            if (i < period - 1) {
                // לא מספיק נתונים עדיין
                atrValues.add(null);
            } else if (i == period - 1) {
                // ATR ראשון = ממוצע פשוט של TR לתקופה הראשונה
                double sum = 0;
                for (int j = 0; j < period; j++) {
                    sum += trValues.get(j);
                }
                atrValues.add(sum / period);
            } else {
                // Wilder's Smoothing: ATR = ((ATR_prev * (period - 1)) + TR_current) / period
                double prevATR = atrValues.get(i - 1);
                double currentTR = trValues.get(i);
                double currentATR = ((prevATR * (period - 1)) + currentTR) / period;
                atrValues.add(currentATR);
            }
        }

        return atrValues;
    }

    /**
     * מחשב True Range ליום בודד
     */
    public static double calculateTrueRange(double high, double low, double previousClose) {
        return Math.max(high - low, Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
    }

    /**
     * מחזיר פסק דין על רמת התנודתיות
     * @param atr ערך ה-ATR
     * @param currentPrice המחיר הנוכחי
     * @return פסק דין על התנודתיות
     */
    public static String getVerdict(double atr, double currentPrice) {
        if (Double.isNaN(atr) || atr <= 0 || currentPrice <= 0) {
            return "⚪️ לא ניתן לחשב תנודתיות";
        }

        // ATR כאחוז מהמחיר (נרמול)
        double atrPercent = (atr / currentPrice) * 100;

        if (atrPercent < 1.0) {
            return String.format("🟢 תנודתיות נמוכה (ATR: %.2f, %.1f%% מהמחיר). מניה יציבה.", atr, atrPercent);
        } else if (atrPercent < 2.5) {
            return String.format("🟡 תנודתיות בינונית (ATR: %.2f, %.1f%% מהמחיר). סטנדרטי.", atr, atrPercent);
        } else if (atrPercent < 5.0) {
            return String.format("🟠 תנודתיות גבוהה (ATR: %.2f, %.1f%% מהמחיר). דורש ניהול סיכונים.", atr, atrPercent);
        } else {
            return String.format("🔴 תנודתיות קיצונית! (ATR: %.2f, %.1f%% מהמחיר). מניה מאוד מסוכנת!", atr, atrPercent);
        }
    }

    /**
     * מחשב Stop-Loss מומלץ על בסיס ATR
     * @param entryPrice מחיר הכניסה
     * @param atr ערך ה-ATR
     * @param multiplier מכפיל ATR (לרוב 1.5-3)
     * @return מחיר Stop-Loss מומלץ
     */
    public static double calculateStopLoss(double entryPrice, double atr, double multiplier) {
        return entryPrice - (atr * multiplier);
    }

    /**
     * מחשב Take-Profit מומלץ על בסיס ATR
     * @param entryPrice מחיר הכניסה
     * @param atr ערך ה-ATR
     * @param multiplier מכפיל ATR (לרוב 2-4)
     * @return מחיר Take-Profit מומלץ
     */
    public static double calculateTakeProfit(double entryPrice, double atr, double multiplier) {
        return entryPrice + (atr * multiplier);
    }

    /**
     * מחזיר המלצות לניהול פוזיציה על בסיס ATR
     */
    public static String getPositionManagementAdvice(double entryPrice, double atr) {
        if (atr <= 0 || entryPrice <= 0) {
            return "לא ניתן לחשב המלצות";
        }

        double stopLoss = calculateStopLoss(entryPrice, atr, 2.0);
        double takeProfit = calculateTakeProfit(entryPrice, atr, 3.0);
        double riskReward = (takeProfit - entryPrice) / (entryPrice - stopLoss);

        return String.format(
            "📊 ניהול פוזיציה (ATR=%.2f):\n" +
            "   • Stop-Loss (2×ATR): $%.2f\n" +
            "   • Take-Profit (3×ATR): $%.2f\n" +
            "   • יחס סיכון/סיכוי: 1:%.1f",
            atr, stopLoss, takeProfit, riskReward
        );
    }
}