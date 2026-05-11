import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fundamental strength metrics parsed from Alpha Vantage OVERVIEW API.
 * Used by the Institutional Flow Layer (Layer 2) to filter garbage stocks.
 */
public class FundamentalData {

    public double marketCap;
    public double revenueGrowth;      // decimal (0.25 = +25%)
    public double eps;
    public double profitMargin;       // decimal
    public double operatingMargin;    // decimal
    public double peRatio;
    public double analystTargetPrice;
    public double beta;
    public double debtToEquity;
    public double analystUpside;      // computed: (target - current) / current
    public String sector;
    public String industry;
    public long lastUpdatedEpochMs;

    /** Compute a 0-10 fundamental quality score. */
    public int computeScore(double currentPrice) {
        int score = 0;
        if (revenueGrowth > 0.20)      score += 2;
        else if (revenueGrowth > 0.10) score += 1;

        if (eps > 0)                   score += 2;

        if (profitMargin > 0.15)       score += 1;
        else if (profitMargin > 0.10)  score += 1; // same points, wider gate

        if (operatingMargin > 0.15)    score += 1;

        if (marketCap > 10_000_000_000.0)       score += 2;
        else if (marketCap > 5_000_000_000.0)   score += 1;
        else if (marketCap > 2_000_000_000.0)   score += 1; // avoid micro-cap garbage

        if (currentPrice > 0 && analystTargetPrice > currentPrice * 1.05) score += 1;

        if (debtToEquity > 0 && debtToEquity < 0.5) score += 1;
        else if (debtToEquity >= 0.5 && debtToEquity < 1.0) score += 1; // still acceptable

        // Penalty for extreme valuation (bubble territory)
        if (peRatio > 0 && peRatio > 100) score -= 1;

        return Math.max(0, Math.min(10, score));
    }

    /** Parse an Alpha Vantage OVERVIEW JSON node. */
    public static FundamentalData fromOverview(JsonNode node, double currentPrice) {
        FundamentalData d = new FundamentalData();
        d.marketCap            = parseDoubleSafe(node.path("MarketCapitalization").asText("0"));
        d.revenueGrowth        = parseDoubleSafe(node.path("QuarterlyRevenueGrowthYOY").asText("0"));
        d.eps                  = parseDoubleSafe(node.path("EPS").asText("0"));
        d.profitMargin         = parseDoubleSafe(node.path("ProfitMargin").asText("0"));
        d.operatingMargin      = parseDoubleSafe(node.path("OperatingMarginTTM").asText("0"));
        d.peRatio              = parseDoubleSafe(node.path("PERatio").asText("0"));
        d.analystTargetPrice   = parseDoubleSafe(node.path("AnalystTargetPrice").asText("0"));
        d.beta                 = parseDoubleSafe(node.path("Beta").asText("0"));
        d.debtToEquity         = parseDoubleSafe(node.path("DebtToEquityRatio").asText("0"));
        d.sector               = node.path("Sector").asText("");
        d.industry             = node.path("Industry").asText("");
        d.lastUpdatedEpochMs   = System.currentTimeMillis();

        if (currentPrice > 0 && d.analystTargetPrice > 0) {
            d.analystUpside = (d.analystTargetPrice - currentPrice) / currentPrice;
        }
        return d;
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("None") || s.equalsIgnoreCase("null")) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public String toString() {
        return String.format(
            "Fundamental[MC=%.1fB RevGrowth=%.1f%% EPS=%.2f PM=%.1f%% OM=%.1f%% PE=%.1f Target=%.2f Beta=%.2f D/E=%.2f]",
            marketCap / 1e9,
            revenueGrowth * 100,
            eps,
            profitMargin * 100,
            operatingMargin * 100,
            peRatio,
            analystTargetPrice,
            beta,
            debtToEquity
        );
    }
}
