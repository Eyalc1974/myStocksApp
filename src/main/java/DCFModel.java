public class DCFModel {

    /**
     * מחשב את הערך הנוכחי הנקי (NPV) של תזרים מזומנים חופשי עתידי.
     * הערה: מודל פשוט שמניח שיעור צמיחה קבוע לשנים קדימה ושווי סופי (Terminal Value).
     *
     * @param initialFCF תזרים המזומנים החופשי של השנה הנוכחית (FCF).
     * @param growthRate שיעור הצמיחה השנתי הצפוי (למשל, 0.05 עבור 5%).
     * @param discountRate שיעור ההיוון (WACC) (למשל, 0.10 עבור 10%).
     * @param forecastYears מספר השנים לתחזית.
     * @param terminalGrowthRate שיעור הצמיחה לצורך Terminal Value (לרוב 0.02).
     * @return הערך הנוכחי של החברה (Fair Value).
     */
    public static double calculateFairValue(double initialFCF, double growthRate, double discountRate,
                                            int forecastYears, double terminalGrowthRate) {

        double presentValue = 0.0;
        double currentFCF = initialFCF;

        // 1. חישוב הערך הנוכחי של תזרימי המזומנים הצפויים (השנים המפורשות)
        for (int t = 1; t <= forecastYears; t++) {
            currentFCF *= (1 + growthRate); // תחזית FCF לשנה t
            double pv = currentFCF / Math.pow(1 + discountRate, t);
            presentValue += pv;
        }

        // 2. חישוב Terminal Value (שווי סופי)
        // השווי לאחר תקופת התחזית, באמצעות נוסחת צמיחה נצחית (Perpetuity)
        double terminalFCF = currentFCF * (1 + terminalGrowthRate);
        double terminalValue = terminalFCF / (discountRate - terminalGrowthRate);

        // 3. חישוב הערך הנוכחי של ה-Terminal Value
        double pvTerminalValue = terminalValue / Math.pow(1 + discountRate, forecastYears);

        // 4. הערך הנוכחי הכולל
        return presentValue + pvTerminalValue;
    }
}