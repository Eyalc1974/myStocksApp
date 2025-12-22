import java.util.ArrayList;
import java.util.List;

public class TechnicalAnalysisModel {

    /**
     * Calculates a Simple Moving Average (SMA) series.
     * For the first (window-1) entries, returns null to indicate insufficient data.
     *
     * @param prices list of closing prices
     * @param window the SMA window size
     * @return a list of SMA values aligned with input prices length
     */
    public static List<Double> calculateSMA(List<Double> prices, int window) {
        List<Double> result = new ArrayList<>();
        if (prices == null || prices.isEmpty() || window <= 0) {
            return result;
        }

        double sum = 0.0;
        for (int i = 0; i < prices.size(); i++) {
            Double price = prices.get(i);
            // Treat null prices as 0 to avoid NPE, but keep alignment
            double val = price == null ? 0.0 : price;
            sum += val;

            if (i < window - 1) {
                // Not enough data yet for a full window
                result.add(null);
            } else {
                if (i >= window) {
                    Double oldPrice = prices.get(i - window);
                    sum -= (oldPrice == null ? 0.0 : oldPrice);
                }
                result.add(sum / window);
            }
        }
        return result;
    }
}
