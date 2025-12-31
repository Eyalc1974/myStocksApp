
public class TrailingStopLoss {

    /**
     * מחשב את מחיר הסטופ-לוס החדש.
     * @param currentPrice המחיר הנוכחי של המניה.
     * @param lastStopPrice הסטופ-לוס הקודם שהיה לנו.
     * @param atr ה-ATR הנוכחי (מדד התנודתיות).
     * @param multiplier מכפיל (בד"כ 2.0 או 3.0) לקביעת המרחק.
     * @return מחיר הסטופ-לוס המעודכן.
     */
    public static double updateStopLoss(double currentPrice, double lastStopPrice, double atr, double multiplier) {
        // המרחק שנרצה לשמור מהמחיר הנוכחי בהתבסס על תנודתיות
        double trailingDistance = atr * multiplier;
        double newStopCandidate = currentPrice - trailingDistance;

        // הסטופ-לוס יכול רק לעלות, לעולם לא לרדת!
        return Math.max(lastStopPrice, newStopCandidate);
    }

    /**
     * בודק האם הגיע הזמן למכור.
     */
    public static boolean shouldSell(double currentPrice, double currentStopPrice) {
        return currentPrice <= currentStopPrice;
    }
}