
import java.util.ArrayList;
import java.util.List;

public class ADX {

    // ADX הוא חישוב מורכב שדורש DMI (Directional Movement Index)

    // פונקציית עזר לחישוב ממוצע אקספוננציאלי חלק (Wilder's Smoothing)
    private static List<Double> calculateWildersSmoothing(List<Double> values, int period) {
        List<Double> result = new ArrayList<>();
        if (values.size() < period) return result;

        // התקופה הראשונה היא ממוצע פשוט
        double initialSum = 0;
        for (int i = 0; i < period; i++) {
            Double v = values.get(i);
            initialSum += (v != null ? v : 0.0);
            result.add(null); // מוסיף nullים בהתחלה
        }
        double smoothValue = initialSum / period;
        result.set(period - 1, smoothValue);

        // חישוב עוקב
        for (int i = period; i < values.size(); i++) {
            Double v = values.get(i);
            double add = (v != null ? v : 0.0);
            smoothValue = ((smoothValue * (period - 1)) + add) / period;
            result.add(smoothValue);
        }
        return result;
    }

    /**
     * מחשב את אינדיקטור ADX (Average Directional Index).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param period תקופת החישוב (לרוב 14).
     * @return רשימה של Double[] כאשר [0]=ADX, [1]=+DI, [2]=-DI.
     */
    public static List<Double[]> calculateADX(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, int period) {

        List<Double> trValues = new ArrayList<>(); // True Range
        List<Double> plusDM = new ArrayList<>();   // Plus Directional Movement
        List<Double> minusDM = new ArrayList<>();  // Minus Directional Movement

        for (int i = 0; i < highPrices.size(); i++) {
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double closePrev = (i > 0) ? closingPrices.get(i - 1) : high; // משתמש ב-High אם אין סגירה קודמת

            // 1. חישוב True Range (TR)
            double tr = Math.max(high - low, Math.max(Math.abs(high - closePrev), Math.abs(low - closePrev)));
            trValues.add(tr);

            // 2. חישוב Directional Movement (DM)
            double upMove = high - highPrices.get(i > 0 ? i - 1 : i);
            double downMove = lowPrices.get(i > 0 ? i - 1 : i) - low;

            double pDM = (upMove > downMove && upMove > 0) ? upMove : 0;
            double mDM = (downMove > upMove && downMove > 0) ? downMove : 0;

            plusDM.add(pDM);
            minusDM.add(mDM);
        }

        // 3. החלקת TR, +DM ו-DM-
        List<Double> smoothTR = calculateWildersSmoothing(trValues, period);
        List<Double> smoothPlusDM = calculateWildersSmoothing(plusDM, period);
        List<Double> smoothMinusDM = calculateWildersSmoothing(minusDM, period);

        List<Double> plusDI = new ArrayList<>();
        List<Double> minusDI = new ArrayList<>();
        List<Double> dxValues = new ArrayList<>();

        for (int i = 0; i < highPrices.size(); i++) {
            Double pDI = null;
            Double mDI = null;
            Double dx = null;

            if (smoothTR.get(i) != null && smoothTR.get(i) != 0) {
                // 4. חישוב DI (+DI ו-DI-)
                pDI = 100 * (smoothPlusDM.get(i) / smoothTR.get(i));
                mDI = 100 * (smoothMinusDM.get(i) / smoothTR.get(i));

                // 5. חישוב DX
                double sumDI = pDI + mDI;
                if (sumDI != 0) {
                    dx = 100 * (Math.abs(pDI - mDI) / sumDI);
                }
            }
            plusDI.add(pDI);
            minusDI.add(mDI);
            dxValues.add(dx);
        }

        // 6. חישוב ADX (Wilder's Smoothing של DX)
        List<Double> adxValues = calculateWildersSmoothing(dxValues, period);

        List<Double[]> results = new ArrayList<>();
        for (int i = 0; i < highPrices.size(); i++) {
            // [ADX, +DI, -DI]
            results.add(new Double[]{adxValues.get(i), plusDI.get(i), minusDI.get(i)});
        }
        return results;
    }
}