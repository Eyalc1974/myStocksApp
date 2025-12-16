
import java.util.HashMap;
import java.util.Map;

public class PivotPoints {

    /**
     * מחשב את נקודות הציר המרכזיות (PP, R1, S1, R2, S2) על בסיס נתוני יום קודם.
     * @param highPrev המחיר הגבוה של היום הקודם.
     * @param lowPrev המחיר הנמוך של היום הקודם.
     * @param closePrev מחיר הסגירה של היום הקודם.
     * @return מפה (Map) המכילה את כל רמות הציר.
     */
    public static Map<String, Double> calculatePivots(double highPrev, double lowPrev, double closePrev) {

        Map<String, Double> pivotLevels = new HashMap<>();

        // 1. נקודת ציר מרכזית (PP - Pivot Point)
        double pp = (highPrev + lowPrev + closePrev) / 3.0;
        pivotLevels.put("PP", pp);

        // 2. רמות התנגדות (Resistance)
        double r1 = (2.0 * pp) - lowPrev;
        double r2 = pp + (highPrev - lowPrev);
        pivotLevels.put("R1", r1);
        pivotLevels.put("R2", r2);

        // 3. רמות תמיכה (Support)
        double s1 = (2.0 * pp) - highPrev;
        double s2 = pp - (highPrev - lowPrev);
        pivotLevels.put("S1", s1);
        pivotLevels.put("S2", s2);

        return pivotLevels;
    }
}