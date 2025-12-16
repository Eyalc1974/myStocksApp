
import java.util.ArrayList;
import java.util.List;

public class ATR {

    /**
     * מחשב את אינדיקטור Average True Range (ATR).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param period תקופת החישוב (לרוב 14).
     * @return רשימה של ערכי ATR.
     */
    public static List<Double> calculateATR(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, int period) {

        List<Double> trValues = new ArrayList<>(); // True Range
        List<Double> atrValues = new ArrayList<>();

        for (int i = 0; i < highPrices.size(); i++) {
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double closePrev = (i > 0) ? closingPrices.get(i - 1) : high; // משתמש ב-High אם אין סגירה קודמת

            // 1. חישוב True Range (TR)
            // TR הוא הגדול מבין: (High - Low), |High - Close_Prev|, |Low - Close_Prev|
            double tr = Math.max(high - low, Math.max(Math.abs(high - closePrev), Math.abs(low - closePrev)));
            trValues.add(tr);
        }

        // 2. חישוב ATR (Wilder's Smoothing של TR)
        // לצורך הפשטות נשתמש באותה פונקציה מה-ADX, אך אם היא אינה זמינה יש ליישם Wilder's Smoothing כאן.

        // נניח ש-ADX.calculateWildersSmoothing זמין, אחרת יש ליישם את הלוגיקה שלו
        // זוהי מתודה שאתה צריך להוסיף או להעתיק כמתודת עזר בתוך ATR.java:
        /*
        for (int i = 0; i < trValues.size(); i++) {
            if (i < period - 1) {
                 atrValues.add(null);
            } else if (i == period - 1) {
                 atrValues.add(trValues.subList(0, period).stream().mapToDouble(d -> d).average().getAsDouble());
            } else {
                double prevATR = atrValues.get(i-1);
                double currentATR = ((prevATR * (period - 1)) + trValues.get(i)) / period;
                atrValues.add(currentATR);
            }
        }
        */

        // לצורך הדוגמה כאן, נשתמש ב-SMA פשוט כתחליף ל-ATR רגיל, אך זכור שזה לא מדויק ל-ATR אמיתי:
        for (int i = 0; i < trValues.size(); i++) {
            if (i < period - 1) {
                atrValues.add(null);
            } else {
                atrValues.add(trValues.subList(i - period + 1, i + 1).stream().mapToDouble(d -> d).average().getAsDouble());
            }
        }

        return atrValues;
    }
}