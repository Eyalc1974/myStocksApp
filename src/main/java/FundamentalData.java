import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fundamental strength metrics parsed from Alpha Vantage OVERVIEW API.
 * Used by the Institutional Flow Layer (Layer 2) to filter garbage stocks.
 */
public class FundamentalData {

    public double marketCap;
    public double revenueGrowth;      // decimal (0.25 = +25%)
    public double epsGrowth;          // decimal (0.15 = +15% YoY)
    public double eps;
    public double profitMargin;       // decimal
    public double operatingMargin;    // decimal
    public double peRatio;
    public double analystTargetPrice;
    public double beta;
    public double debtToEquity;
    public double analystUpside;      // computed: (target - current) / current
    public double institutionalScore; // 0-10 institutional ownership quality
    public String sector;
    public String industry;
    public long lastUpdatedEpochMs;

    // Enhanced fields from additional Alpha Vantage APIs
    public double grossMargin;        // from INCOME_STATEMENT
    public double ebitdaMargin;       // from INCOME_STATEMENT
    public double revenueGrowthYoY;    // precise YoY revenue growth
    public double epsGrowthYoY;       // precise YoY EPS growth
    public double freeCashFlow;       // from CASH_FLOW
    public double fcfMargin;          // FCF / Revenue
    public double dividendPayoutRatio; // dividends / FCF
    public double currentRatio;       // from BALANCE_SHEET
    public double cashToDebt;         // cash / total debt
    public double bookValuePerShare;  // from BALANCE_SHEET
    public double earningsSurprisePct; // from EARNINGS
    public int consecutiveBeats;      // consecutive earnings beats
    public double dividendYield;      // from DIVIDENDS
    public boolean paysDividend;      // from DIVIDENDS

    /** Compute a 0-15 enhanced fundamental quality score. */
    public int computeScore(double currentPrice) {
        int score = 0;

        // Growth metrics (4 points)
        if (revenueGrowthYoY > 0.20)      score += 2;
        else if (revenueGrowthYoY > 0.10) score += 1;

        if (epsGrowthYoY > 0.15)          score += 2;
        else if (epsGrowthYoY > 0.10)     score += 1;

        // Profit quality (3 points)
        if (grossMargin > 0.40)           score += 1;
        else if (grossMargin > 0.30)      score += 1;

        if (ebitdaMargin > 0.20)          score += 1;
        else if (ebitdaMargin > 0.15)     score += 1;

        if (profitMargin > 0.15)          score += 1;

        // Cash flow quality (3 points)
        if (freeCashFlow > 0)             score += 1;
        if (fcfMargin > 0.15)             score += 2;
        else if (fcfMargin > 0.10)       score += 1;

        // Financial stability (3 points)
        if (debtToEquity > 0 && debtToEquity < 0.5) score += 1;
        else if (debtToEquity >= 0.5 && debtToEquity < 1.0) score += 1;

        if (currentRatio > 1.5)           score += 1;
        else if (currentRatio > 1.2)     score += 1;

        if (cashToDebt > 0.3)             score += 1;

        // Catalyst - earnings surprise (2 points)
        if (earningsSurprisePct > 5.0)   score += 2;
        else if (earningsSurprisePct > 0) score += 1;

        // Market size (1 point)
        if (marketCap > 10_000_000_000.0)       score += 1;
        else if (marketCap > 2_000_000_000.0)   score += 1;

        // Analyst sentiment (1 point)
        if (currentPrice > 0 && analystTargetPrice > currentPrice * 1.10) score += 1;

        // Dividend stability (1 point - bonus)
        if (paysDividend && dividendYield > 1.0 && dividendPayoutRatio < 0.6) score += 1;

        // Penalty for extreme valuation
        if (peRatio > 0 && peRatio > 100) score -= 1;

        return Math.max(0, Math.min(15, score));
    }

    /** Parse an Alpha Vantage OVERVIEW JSON node. */
    public static FundamentalData fromOverview(JsonNode node, double currentPrice) {
        FundamentalData d = new FundamentalData();
        d.marketCap            = parseDoubleSafe(node.path("MarketCapitalization").asText("0"));
        d.revenueGrowth        = parseDoubleSafe(node.path("QuarterlyRevenueGrowthYOY").asText("0"));
        d.epsGrowth            = parseDoubleSafe(node.path("QuarterlyEarningsGrowthYOY").asText("0"));
        d.eps                  = parseDoubleSafe(node.path("EPS").asText("0"));
        d.profitMargin         = parseDoubleSafe(node.path("ProfitMargin").asText("0"));
        d.operatingMargin      = parseDoubleSafe(node.path("OperatingMarginTTM").asText("0"));
        d.peRatio              = parseDoubleSafe(node.path("PERatio").asText("0"));
        d.analystTargetPrice   = parseDoubleSafe(node.path("AnalystTargetPrice").asText("0"));
        d.beta                 = parseDoubleSafe(node.path("Beta").asText("0"));
        d.debtToEquity         = parseDoubleSafe(node.path("DebtToEquityRatio").asText("0"));
        d.institutionalScore   = parseDoubleSafe(node.path("InstitutionalOwnership").asText("0")) / 10.0;
        d.sector               = node.path("Sector").asText("");
        d.industry             = node.path("Industry").asText("");
        d.lastUpdatedEpochMs   = System.currentTimeMillis();

        if (currentPrice > 0 && d.analystTargetPrice > 0) {
            d.analystUpside = (d.analystTargetPrice - currentPrice) / currentPrice;
        }
        return d;
    }

    /** Parse Alpha Vantage INCOME_STATEMENT API and enhance existing data. */
    public void enrichFromIncomeStatement(JsonNode node) {
        JsonNode annualReports = node.path("annualReports");
        if (annualReports.isArray() && annualReports.size() >= 2) {
            JsonNode latest = annualReports.get(0);
            JsonNode previous = annualReports.get(1);

            double latestRevenue = parseDoubleSafe(latest.path("totalRevenue").asText("0"));
            double previousRevenue = parseDoubleSafe(previous.path("totalRevenue").asText("0"));
            double latestGrossProfit = parseDoubleSafe(latest.path("grossProfit").asText("0"));
            double latestEbitda = parseDoubleSafe(latest.path("ebitda").asText("0"));
            double latestEps = parseDoubleSafe(latest.path("eps").asText("0"));
            double previousEps = parseDoubleSafe(previous.path("eps").asText("0"));

            // Calculate precise YoY growth
            if (previousRevenue > 0) {
                revenueGrowthYoY = (latestRevenue - previousRevenue) / previousRevenue;
            }
            if (previousEps > 0) {
                epsGrowthYoY = (latestEps - previousEps) / previousEps;
            }

            // Calculate margins
            if (latestRevenue > 0) {
                grossMargin = latestGrossProfit / latestRevenue;
                ebitdaMargin = latestEbitda / latestRevenue;
            }
        }
    }

    /** Parse Alpha Vantage CASH_FLOW API and enhance existing data. */
    public void enrichFromCashFlow(JsonNode node, double revenue) {
        JsonNode annualReports = node.path("annualReports");
        if (annualReports.isArray() && annualReports.size() > 0) {
            JsonNode latest = annualReports.get(0);

            double operatingCashflow = parseDoubleSafe(latest.path("operatingCashflow").asText("0"));
            double capitalExpenditures = parseDoubleSafe(latest.path("capitalExpenditures").asText("0"));
            double dividendPayout = parseDoubleSafe(latest.path("dividendPayout").asText("0"));

            // Calculate Free Cash Flow (capex is negative in API)
            freeCashFlow = operatingCashflow + capitalExpenditures;

            // Calculate FCF margin
            if (revenue > 0) {
                fcfMargin = freeCashFlow / revenue;
            }

            // Calculate dividend payout ratio
            if (freeCashFlow > 0) {
                dividendPayoutRatio = Math.abs(dividendPayout) / freeCashFlow;
            }
        }
    }

    /** Parse Alpha Vantage BALANCE_SHEET API and enhance existing data. */
    public void enrichFromBalanceSheet(JsonNode node, double sharesOutstanding) {
        JsonNode annualReports = node.path("annualReports");
        if (annualReports.isArray() && annualReports.size() > 0) {
            JsonNode latest = annualReports.get(0);

            double totalAssets = parseDoubleSafe(latest.path("totalAssets").asText("0"));
            double totalLiabilities = parseDoubleSafe(latest.path("totalLiabilities").asText("0"));
            double currentAssets = parseDoubleSafe(latest.path("totalCurrentAssets").asText("0"));
            double currentLiabilities = parseDoubleSafe(latest.path("totalCurrentLiabilities").asText("0"));
            double longTermDebt = parseDoubleSafe(latest.path("longTermDebt").asText("0"));
            double cashAndEquivalents = parseDoubleSafe(latest.path("cashAndCashEquivalents").asText("0"));
            double shareholderEquity = parseDoubleSafe(latest.path("totalShareholderEquity").asText("0"));

            // Calculate current ratio
            if (currentLiabilities > 0) {
                currentRatio = currentAssets / currentLiabilities;
            }

            // Calculate cash to debt ratio
            if (longTermDebt > 0) {
                cashToDebt = cashAndEquivalents / longTermDebt;
            }

            // Calculate book value per share
            if (sharesOutstanding > 0) {
                bookValuePerShare = shareholderEquity / sharesOutstanding;
            }
        }
    }

    /** Parse Alpha Vantage EARNINGS API and enhance existing data. */
    public void enrichFromEarnings(JsonNode node) {
        JsonNode quarterlyEarnings = node.path("quarterlyEarnings");
        if (quarterlyEarnings.isArray() && quarterlyEarnings.size() > 0) {
            int beats = 0;
            double latestSurprise = 0.0;

            // Check last 4 quarters for consecutive beats
            int quartersToCheck = Math.min(4, quarterlyEarnings.size());
            for (int i = 0; i < quartersToCheck; i++) {
                JsonNode quarter = quarterlyEarnings.get(i);
                double reportedEPS = parseDoubleSafe(quarter.path("reportedEPS").asText("0"));
                double estimatedEPS = parseDoubleSafe(quarter.path("estimatedEPS").asText("0"));

                if (estimatedEPS > 0) {
                    double surprise = (reportedEPS - estimatedEPS) / estimatedEPS;
                    if (surprise > 0) {
                        beats++;
                    }
                    if (i == 0) {
                        latestSurprise = surprise * 100; // Convert to percentage
                    }
                }
            }

            consecutiveBeats = beats;
            earningsSurprisePct = latestSurprise;
        }
    }

    /** Parse Alpha Vantage DIVIDENDS API and enhance existing data. */
    public void enrichFromDividends(JsonNode node, double currentPrice) {
        JsonNode data = node.path("data");
        if (data.isArray() && data.size() > 0) {
            paysDividend = true;

            // Calculate annual dividend from recent payments
            double totalAnnualDividend = 0.0;
            int paymentsCount = 0;

            // Sum last 4 quarters of dividends
            for (int i = 0; i < Math.min(4, data.size()); i++) {
                JsonNode payment = data.get(i);
                double amount = parseDoubleSafe(payment.path("amount").asText("0"));
                totalAnnualDividend += amount;
                paymentsCount++;
            }

            // Calculate dividend yield
            if (currentPrice > 0 && totalAnnualDividend > 0) {
                dividendYield = (totalAnnualDividend / currentPrice) * 100;
            }
        } else {
            paysDividend = false;
            dividendYield = 0.0;
        }
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
