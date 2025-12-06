
import java.util.ArrayList;
import java.util.List;

public class MACD {

    // שיטת עזר לחישוב ממוצע נע מעריכי (EMA)
    private static List<Double> calculateEMA(List<Double> prices, int period) {
        List<Double> emaValues = new ArrayList<>();
        if (prices == null || prices.isEmpty()) {
            return emaValues;
        }

        // מקדם החלקה (Smoothing factor)
        double multiplier = 2.0 / (period + 1.0);

        // EMA ראשוני הוא ה-SMA של התקופה הראשונה
        double currentEMA = 0;
        for (int i = 0; i < period; i++) {
            // מוסיף Nullים או 0.0 עבור תקופת החישוב הראשונית
            emaValues.add(null);
            if (i < prices.size()) {
                currentEMA += prices.get(i);
            }
        }

        // חישוב ה-EMA הראשון (SMA של התקופה)
        if (prices.size() >= period) {
            currentEMA /= period;
            emaValues.set(period - 1, currentEMA);
        } else {
            return emaValues; // אין מספיק נתונים
        }

        // חישוב ה-EMA עבור התקופות הבאות
        for (int i = period; i < prices.size(); i++) {
            double price = prices.get(i);
            currentEMA = (price - currentEMA) * multiplier + currentEMA;
            emaValues.add(currentEMA);
        }

        return emaValues;
    }

    /**
     * מחשב את אינדיקטור MACD ואת קו האות שלו.
     * @param prices רשימת מחירי סגירה.
     * @return רשימה של Double[] כאשר [0]=MACD, [1]=Signal Line.
     */
    public static List<Double[]> calculateMACD(List<Double> prices) {
        // מחשב שני קווי EMA
        List<Double> ema12 = calculateEMA(prices, 12);
        List<Double> ema26 = calculateEMA(prices, 26);

        // ודא שיש מספיק נתונים
        if (prices.size() < 26) {
            return new ArrayList<>();
        }

        List<Double> macdLine = new ArrayList<>();

        // חישוב קו MACD (EMA12 - EMA26)
        for (int i = 0; i < prices.size(); i++) {
            Double macdValue = null;
            if (ema12.get(i) != null && ema26.get(i) != null) {
                macdValue = ema12.get(i) - ema26.get(i);
            }
            macdLine.add(macdValue);
        }

        // חישוב קו האות (EMA9 של קו MACD)
        // חשוב: נשלח רק את הערכים הלא-Nullיים של קו MACD
        List<Double> validMACD = new ArrayList<>();
        for (Double val : macdLine) {
            if (val != null) {
                validMACD.add(val);
            }
        }
        List<Double> signalLinePartial = calculateEMA(validMACD, 9);

        // איחוד התוצאות
        List<Double[]> results = new ArrayList<>();
        int signalIndex = 0;
        for (int i = 0; i < prices.size(); i++) {
            Double macd = macdLine.get(i);
            Double signal = null;

            // מתחילים לשלב את קו האות לאחר 26+9-2 ימים (בגלל EMA)
            if (macd != null && signalIndex < signalLinePartial.size()) {
                signal = signalLinePartial.get(signalIndex);
                signalIndex++;
            }

            // מוסיפים את התוצאה לרישום: [MACD, Signal Line]
            results.add(new Double[]{macd, signal});
        }

        return results;
    }
}