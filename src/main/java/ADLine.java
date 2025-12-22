
import java.util.ArrayList;
import java.util.List;

public class ADLine {

    /**
     * מחשב את אינדיקטור Accumulation/Distribution Line (A/D Line).
     * @param highPrices רשימת מחירי שיא.
     * @param lowPrices רשימת מחירי שפל.
     * @param closingPrices רשימת מחירי סגירה.
     * @param volumeData רשימת נפחי המסחר.
     * @return רשימה של ערכי A/D Line.
     */
    public static List<Double> calculateADLine(List<Double> highPrices, List<Double> lowPrices, List<Double> closingPrices, List<Long> volumeData) {

        List<Double> adLineValues = new ArrayList<>();

        if (closingPrices.size() != volumeData.size() || closingPrices.isEmpty()) {
            return adLineValues;
        }

        double currentADL = 0;

        for (int i = 0; i < closingPrices.size(); i++) {
            double high = highPrices.get(i);
            double low = lowPrices.get(i);
            double close = closingPrices.get(i);
            long volume = volumeData.get(i);

            // 1. חישוב Money Flow Multiplier (MFM)
            double highMinusLow = high - low;
            double mfm = 0;
            if (highMinusLow > 0) {
                // המכפיל מודד היכן נסגר המחיר ביחס לטווח היומי (High-Low)
                mfm = ((close - low) - (high - close)) / highMinusLow;
            }

            // 2. חישוב Money Flow Volume (MFV)
            double mfv = mfm * volume;

            // 3. צבירה: הוספה ל-A/D Line הקיים
            currentADL += mfv;
            adLineValues.add(currentADL);
        }

        return adLineValues;
    }
}