
import java.util.ArrayList;
import java.util.List;

public class OBV {

    /**
     * מחשב את אינדיקטור On-Balance Volume (OBV).
     * @param closingPrices רשימת מחירי הסגירה.
     * @param volumeData רשימת נפחי המסחר (ווליום).
     * @return רשימה של ערכי OBV.
     */
    public static List<Long> calculateOBV(List<Double> closingPrices, List<Long> volumeData) {

        List<Long> obvValues = new ArrayList<>();

        if (closingPrices == null || volumeData == null || closingPrices.size() != volumeData.size() || closingPrices.isEmpty()) {
            return obvValues;
        }

        // OBV מתחיל עם נפח המסחר של היום הראשון
        long currentOBV = volumeData.get(0);
        obvValues.add(currentOBV);

        // חישוב OBV לכל יום לאחר היום הראשון
        for (int i = 1; i < closingPrices.size(); i++) {
            double currentPrice = closingPrices.get(i);
            double previousPrice = closingPrices.get(i - 1);
            long currentVolume = volumeData.get(i);

            if (currentPrice > previousPrice) {
                // אם המחיר עלה: מוסיפים את הווליום הנוכחי ל-OBV
                currentOBV += currentVolume;
            } else if (currentPrice < previousPrice) {
                // אם המחיר ירד: מחסירים את הווליום הנוכחי מה-OBV
                currentOBV -= currentVolume;
            }
            // אם המחיר לא השתנה, OBV נשאר אותו הדבר

            obvValues.add(currentOBV);
        }

        return obvValues;
    }
}