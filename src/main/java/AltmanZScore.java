public class AltmanZScore {
    public static double calculateZScore(double workingCapital, double totalAssets,
                                         double retainedEarnings, double ebit,
                                         double marketCap, double totalLiabilities,
                                         double sales) {

        double A = workingCapital / totalAssets;
        double B = retainedEarnings / totalAssets;
        double C = ebit / totalAssets;
        double D = marketCap / totalLiabilities;
        double E = sales / totalAssets;

        return (1.2 * A) + (1.4 * B) + (3.3 * C) + (0.6 * D) + (1.0 * E);
    }

    public static String getVerdict(double zScore) {
        if (zScore >= 2.99) return "Safe Zone (Low Bankruptcy Risk)";
        if (zScore >= 1.81) return "Gray Zone (Moderate Risk)";
        return "Distress Zone (High Bankruptcy Risk!)";
    }
}