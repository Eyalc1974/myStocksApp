
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Stochastic {

    /**
     * מחשב את הסטוקסטיק אוסילטור (%K ו-%D).
     * @param prices רשימת מחירי הסגירה.
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param kPeriod תקופת החישוב של %K (לרוב 14).
     * @param dPeriod תקופת החישוב של %D (לרוב 3).
     * @return רשימה של Double[] כאשר [0]=%K, [1]=%D.
     */
    public static List<Double[]> calculateStochastic(List<Double> prices, List<Double> highPrices, List<Double> lowPrices, int kPeriod, int dPeriod) {

        // נדרשים נתונים מלאים
        if (prices.size() < kPeriod) {
            return Collections.emptyList();
        }

        List<Double> kValues = new ArrayList<>();

        // חישוב %K
        for (int i = 0; i < prices.size(); i++) {
            if (i < kPeriod - 1) {
                kValues.add(null);
                continue;
            }

            // מציאת המחיר הגבוה והנמוך ביותר בחלון של kPeriod
            double highestHigh = highPrices.subList(i - kPeriod + 1, i + 1).stream().mapToDouble(v -> v).max().getAsDouble();
            double lowestLow = lowPrices.subList(i - kPeriod + 1, i + 1).stream().mapToDouble(v -> v).min().getAsDouble();
            double currentPrice = prices.get(i);

            double k = 100.0 * ((currentPrice - lowestLow) / (highestHigh - lowestLow));
            kValues.add(k);
        }

        // חישוב %D (ממוצע נע פשוט של %K)
        List<Double> dValues = new ArrayList<>();

        for (int i = 0; i < kValues.size(); i++) {
            if (kValues.get(i) == null || i < kPeriod + dPeriod - 2) {
                dValues.add(null);
                continue;
            }

            // חישוב SMA עבור D-Period של ערכי %K
            double sumK = 0.0;
            int startIndex = i - dPeriod + 1;
            for (int j = 0; j < dPeriod; j++) {
                sumK += kValues.get(startIndex + j);
            }
            dValues.add(sumK / dPeriod);
        }

        List<Double[]> results = new ArrayList<>();
        for (int i = 0; i < kValues.size(); i++) {
            results.add(new Double[]{kValues.get(i), dValues.get(i)});
        }
        return results;
    }
}