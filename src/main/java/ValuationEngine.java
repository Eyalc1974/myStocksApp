public class ValuationEngine {

    public static double getWeightedFairValue(double dcfValue, double grahamValue, double pegFairPrice) {
        // ניתן לתת משקלים שונים לכל מודל לפי העדפה
        return (dcfValue * 0.5) + (grahamValue * 0.3) + (pegFairPrice * 0.2);
    }

    public static void printSafetyMargin(double currentPrice, double weightedFairValue) {
        if (currentPrice <= 0) {
            return;
        }

        double margin = (weightedFairValue / currentPrice) - 1;

        System.out.println("\n--- שווי משוקלל ומרווח ביטחון ---");
        System.out.printf("שווי הוגן משוקלל: $%.2f%n", weightedFairValue);
        System.out.printf("מרווח ביטחון נוכחי: %.2f%%%n", margin * 100);

        if (margin > 0.25) {
            System.out.println("קנייה בטוחה: יש מרווח ביטחון של מעל 25%.");
        } else if (margin < 0) {
            System.out.println("יקר מדי: המחיר מעל השווי המשוקלל.");
        }
    }
}
