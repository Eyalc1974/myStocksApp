
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class BollingerBands {

    /**
     * מחשב סטיית תקן (Standard Deviation) עבור חלון נתון.
     */
    private static double calculateStdDev(List<Double> sublist) {
        if (sublist.isEmpty()) return 0;

        double mean = sublist.stream().mapToDouble(a -> a).average().getAsDouble();
        double variance = sublist.stream().mapToDouble(a -> Math.pow(a - mean, 2)).sum() / sublist.size();
        return Math.sqrt(variance);
    }

    /**
     * מחשב את שלוש רצועות בולינגר: עליונה, אמצעית (SMA), ותחתונה.
     * @param prices רשימת מחירי הסגירה.
     * @param period תקופת החישוב (לרוב 20).
     * @param numStdDevs מספר סטיות התקן (לרוב 2).
     * @return רשימה של Double[] כאשר [0]=Upper Band, [1]=Middle Band (SMA), [2]=Lower Band.
     */
    public static List<Double[]> calculateBands(List<Double> prices, int period, double numStdDevs) {

        List<Double[]> bandValues = new ArrayList<>();

        // קודם נחשב את ה-SMA (Middle Band) ואז את סטיות התקן
        List<Double> middleBand = TechnicalAnalysisModel.calculateSMA(prices, period);

        for (int i = 0; i < prices.size(); i++) {
            if (middleBand.get(i) == null) {
                bandValues.add(new Double[]{null, null, null});
                continue;
            }

            // קבלת נתוני המחיר לחלון הנוכחי
            List<Double> sublist = prices.subList(i - period + 1, i + 1);

            double stdDev = calculateStdDev(sublist);
            double sma = middleBand.get(i);

            double upper = sma + (stdDev * numStdDevs);
            double lower = sma - (stdDev * numStdDevs);

            // [Upper Band, Middle Band (SMA), Lower Band]
            bandValues.add(new Double[]{upper, sma, lower});
        }
        return bandValues;
    }
}