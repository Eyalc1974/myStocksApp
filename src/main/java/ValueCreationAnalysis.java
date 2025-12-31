
public class ValueCreationAnalysis {
    /**
     * מחשב את ה-Economic Spread.
     * @param roic תשואה על הון מושקע (למשל 0.15 עבור 15%).
     * @param wacc עלות גיוס הון (למשל 0.08 עבור 8%).
     * @return ההפרש (Spread).
     */
    public static double calculateEconomicSpread(double roic, double wacc) {
        return roic - wacc;
    }

    public static String getVerdict(double roic, double wacc) {
        double spread = calculateEconomicSpread(roic, wacc);

        if (spread > 0.05) {
            return String.format("🌟 Value Creator: החברה מייצרת ערך משמעותי (Spread: %.2f%%).", spread * 100);
        } else if (spread > 0) {
            return String.format("🟢 Marginal Value Creator: החברה מכסה את עלות ההון שלה (Spread: %.2f%%).", spread * 100);
        } else {
            return String.format("🔴 Value Destroyer: החברה שורפת ערך! עלות גיוס ההון גבוהה מהתשואה (Spread: %.2f%%).", spread * 100);
        }
    }
}