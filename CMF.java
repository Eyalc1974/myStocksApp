
import java.util.ArrayList;
import java.util.List;

public class CMF {

    /**
     * מחשב את אינדיקטור Chaikin Money Flow (CMF).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param volumeData רשימת נפחי המסחר.
     * @param period תקופת החישוב (לרוב 20).
     * @return רשימה של ערכי CMF.
     */
    public static List<Double> calculateCMF(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, List<Long> volumeData, int period) {

        List<Double> cmfValues = new ArrayList<>();
        if (closingPrices.size() < period) {
            return cmfValues;
        }

        // חישוב Money Flow Multiplier (MFM) ו-Money Flow Volume (MFV)
        List<Double> mfvValues = new ArrayList<>();

        for (int i = 0; i < closingPrices.size(); i++) {
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double close = closingPrices.get(i);
            long volume = volumeData.get(i);

            // 1. Money Flow Multiplier (MFM)
            double highMinusLow = high - low;
            double mfm = 0;
            if (highMinusLow > 0) {
                mfm = ((close - low) - (high - close)) / highMinusLow;
            }

            // 2. Money Flow Volume (MFV)
            mfvValues.add(mfm * volume);
        }

        // 3. חישוב CMF: (סכום MFV לתקופה) / (סכום Volume לתקופה)
        for (int i = 0; i < closingPrices.size(); i++) {
            if (i < period - 1) {
                cmfValues.add(null);
                continue;
            }

            // חישוב סכום MFV וסכום Volume בחלון הנוכחי
            double sumMFV = 0;
            long sumVolume = 0;

            for (int j = i - period + 1; j <= i; j++) {
                sumMFV += mfvValues.get(j);
                sumVolume += volumeData.get(j);
            }

            double cmf = (sumVolume > 0) ? sumMFV / sumVolume : 0.0;
            cmfValues.add(cmf);
        }

        return cmfValues;
    }
}