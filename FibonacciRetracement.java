
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public class FibonacciRetracement {

    private static final double R_38_2 = 0.382;
    private static final double R_50_0 = 0.50;
    private static final double R_61_8 = 0.618;

    /**
     * מחשב את רמות הפיבונאצ'י העיקריות על בסיס השיא והשפל הנתונים.
     * @param high מחיר השיא האחרון (שיא המגמה).
     * @param low מחיר השפל האחרון (תחתית המגמה).
     * @return מפה של רמות פיבונאצ'י.
     */
    public static Map<String, Double> calculateLevels(double high, double low) {

        Map<String, Double> levels = new HashMap<>();
        double range = high - low; // הטווח המלא של המגמה

        // הרמה המרכזית 0.50 (50%)
        levels.put("R50", high - (range * R_50_0));

        // הרמה 0.618 (61.8%)
        levels.put("R61", high - (range * R_61_8));

        // הרמה 0.382 (38.2%)
        levels.put("R38", high - (range * R_38_2));

        levels.put("R0", high); // שיא המגמה
        levels.put("R100", low); // תחתית המגמה

        return levels;
    }
}