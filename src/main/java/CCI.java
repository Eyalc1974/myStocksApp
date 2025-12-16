
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;

public class CCI {

    /**
     * מחשב את אינדיקטור Commodity Channel Index (CCI).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param period תקופת החישוב (לרוב 20).
     * @return רשימה של ערכי CCI.
     */
    public static List<Double> calculateCCI(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, int period) {

        List<Double> cciValues = new ArrayList<>();
        int dataSize = closingPrices.size();

        if (dataSize < period) {
            return cciValues;
        }

        // 1. חישוב המחיר הטיפוסי (TP = High + Low + Close / 3)
        List<Double> typicalPrices = new ArrayList<>();
        for (int i = 0; i < dataSize; i++) {
            double tp = (highPrices.get(i) + lowPrices.get(i) + closingPrices.get(i)) / 3.0;
            typicalPrices.add(tp);
        }

        // 2. חישוב CCI לכל יום
        for (int i = 0; i < dataSize; i++) {
            if (i < period - 1) {
                cciValues.add(null);
                continue;
            }

            // החלון הנוכחי לחישוב (N ימים)
            List<Double> windowTP = typicalPrices.subList(i - period + 1, i + 1);

            // חישוב הממוצע הטיפוסי (Mean Deviation)
            double meanTP = windowTP.stream().mapToDouble(Double::doubleValue).average().getAsDouble();

            // חישוב סטיית תקן (Mean Deviation) של המחירים הטיפוסיים בחלון
            StandardDeviation sd = new StandardDeviation(false); // false = ללא תיקון דרגות חופש
            double meanDeviation = sd.evaluate(windowTP.stream().mapToDouble(Double::doubleValue).toArray());

            // 3. חישוב CCI
            Double currentTP = typicalPrices.get(i);

            // הנוסחה: CCI = (TP - MeanTP) / (0.015 * MeanDeviation)
            double cci = 0;
            if (meanDeviation != 0) {
                cci = (currentTP - meanTP) / (0.015 * meanDeviation);
            }

            cciValues.add(cci);
        }

        return cciValues;
    }
}