
public class BeneishMScore {

    /**
     * מחשב את ציון ה-M-Score לפי 8 המדדים של בייניש.
     * @return ציון ה-M-Score. ציון גבוה מ (1.78-) מעיד על סבירות גבוהה למניפולציה.
     */
    public static double calculateMScore(
            double dsri,  // Days Sales in Receivables Index
            double gmi,   // Gross Margin Index
            double aqi,   // Asset Quality Index
            double sgi,   // Sales Growth Index
            double depi,  // Depreciation Index
            double sgai,  // SGA Expenses Index
            double lvgi,  // Leverage Index
            double tata   // Total Accruals to Total Assets
    ) {
        // נוסחת רגרסיה משוקללת
        return -4.84 + (0.92 * dsri) + (0.52 * gmi) + (0.40 * aqi) + (0.89 * sgi)
                + (0.115 * depi) - (0.172 * sgai) + (4.041 * tata) - (0.327 * lvgi);
    }

    public static String getVerdict(double mScore) {
        if (mScore < -1.78) {
            return "🟢 SAFE (סבירות נמוכה למניפולציה חשבונאית)";
        } else {
            return "🔴 MANIPULATOR (חשד גבוה למניפולציה בדו\"חות!)";
        }
    }
}