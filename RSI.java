import java.util.ArrayList;
import java.util.List;

public class RSI {

    /**
     * מחשב את מדד החוזק היחסי (RSI).
     * @param prices רשימת מחירי הסגירה.
     * @param period תקופת החישוב (לרוב 14 ימים).
     * @return רשימה של ערכי RSI.
     */
    public static List<Double> calculateRSI(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1) {
            return new ArrayList<>();
        }

        List<Double> rsiValues = new ArrayList<>();
        // מוסיף ערכי Null עבור התקופה הראשונית שבה אין מספיק נתונים
        for (int i = 0; i < period; i++) {
            rsiValues.add(null);
        }

        // חישוב ממוצע עליות וירידות ראשוני (Simple Average)
        double initialAvgGain = 0;
        double initialAvgLoss = 0;

        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) {
                initialAvgGain += change;
            } else {
                initialAvgLoss += Math.abs(change);
            }
        }
        initialAvgGain /= period;
        initialAvgLoss /= period;

        // חישוב ה-RSI הראשון
        double rs = initialAvgLoss == 0 ? 99.99 : initialAvgGain / initialAvgLoss;
        rsiValues.add(100.0 - (100.0 / (1.0 + rs)));

        // חישוב החלקה עוקב (Wilder's Smoothing)
        double currentAvgGain = initialAvgGain;
        double currentAvgLoss = initialAvgLoss;

        for (int i = period + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? Math.abs(change) : 0;

            // החלקה (Exponential Moving Average)
            currentAvgGain = (currentAvgGain * (period - 1) + gain) / period;
            currentAvgLoss = (currentAvgLoss * (period - 1) + loss) / period;

            rs = currentAvgLoss == 0 ? 99.99 : currentAvgGain / currentAvgLoss;
            rsiValues.add(100.0 - (100.0 / (1.0 + rs)));
        }

        return rsiValues;
    }
}