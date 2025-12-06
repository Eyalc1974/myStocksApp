
import java.util.ArrayList;
import java.util.List;

public class MFI {

    /**
     * מחשב את המדד Money Flow Index (MFI).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param volumeData רשימת נפחי המסחר.
     * @param period תקופת החישוב (לרוב 14).
     * @return רשימה של ערכי MFI.
     */
    public static List<Double> calculateMFI(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, List<Long> volumeData, int period) {

        List<Double> mfiValues = new ArrayList<>();
        if (closingPrices.size() < period) {
            return mfiValues;
        }

        // חישוב Typical Price ו-Money Flow
        List<Double> typicalPrices = new ArrayList<>();
        List<Double> moneyFlow = new ArrayList<>();

        for (int i = 0; i < closingPrices.size(); i++) {
            // Typical Price = (High + Low + Close) / 3
            double tp = (highPrices.get(i) + lowPrices.get(i) + closingPrices.get(i)) / 3.0;
            typicalPrices.add(tp);

            // Money Flow = Typical Price * Volume
            moneyFlow.add(tp * volumeData.get(i));
        }

        // חישוב MFI
        for (int i = 0; i < closingPrices.size(); i++) {
            if (i < period) {
                mfiValues.add(null);
                continue;
            }

            double positiveMoneyFlow = 0; // זרימת כסף חיובית
            double negativeMoneyFlow = 0; // זרימת כסף שלילית

            // חישוב חלון התקופה
            for (int j = i - period + 1; j <= i; j++) {
                // בודק את Typical Price הנוכחי מול הקודם
                if (typicalPrices.get(j) > typicalPrices.get(j - 1)) {
                    positiveMoneyFlow += moneyFlow.get(j);
                } else if (typicalPrices.get(j) < typicalPrices.get(j - 1)) {
                    negativeMoneyFlow += moneyFlow.get(j);
                }
            }

            double moneyRatio = positiveMoneyFlow / negativeMoneyFlow;

            // Money Flow Index = 100 - (100 / (1 + Money Ratio))
            double mfi = 100.0 - (100.0 / (1.0 + moneyRatio));
            mfiValues.add(mfi);
        }

        return mfiValues;
    }
}