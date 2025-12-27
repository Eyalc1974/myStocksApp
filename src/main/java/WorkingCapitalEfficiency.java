
public class WorkingCapitalEfficiency {

    /**
     * מחשב את מחזור המרת המזומנים (CCC).
     * @return מספר הימים שלוקח למזומן לחזור לקופה.
     */
    public static double calculateCCC(double dio, double dso, double dpo) {
        return dio + dso - dpo;
    }

    public static String getVerdict(double ccc, double industryAverage) {
        if (ccc < 0) {
            return "🌟 מודל עסקי פנומנלי (Negative CCC): החברה מקבלת מזומן לפני שהיא משלמת על המלאי!";
        } else if (ccc < industryAverage * 0.8) {
            return "🟢 יעילות גבוהה: החברה מנהלת הון חוזר טוב יותר מהממוצע בתעשייה.";
        } else if (ccc > industryAverage * 1.2) {
            return "🔴 חוסר יעילות: הכסף של החברה " + "תקוע" + " במלאי או אצל לקוחות שאינם משלמים.";
        }
        return "⚪️ יעילות ממוצעת.";
    }

    public static String getVerdict(double ccc) {
        if (ccc < 0) {
            return "🌟 מודל עסקי פנומנלי (Negative CCC): החברה מקבלת מזומן לפני שהיא משלמת על המלאי!";
        }
        if (ccc <= 30) {
            return "🟢 יעילות גבוהה: מחזור המרת המזומנים קצר.";
        }
        if (ccc >= 90) {
            return "🔴 חוסר יעילות: מחזור המרת המזומנים ארוך והכסף תקוע בהון חוזר.";
        }
        return "⚪️ יעילות ממוצעת.";
    }
}