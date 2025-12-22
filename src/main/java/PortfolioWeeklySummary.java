import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class PortfolioWeeklySummary {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Double parseDouble(JsonNode root, String key) {
        try {
            if (root == null) return null;
            JsonNode n = root.get(key);
            if (n == null) return null;
            String s = n.asText();
            if (s == null || s.isBlank() || "None".equalsIgnoreCase(s)) return null;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static class FundamentalSnapshot {
        Double fcf;
        Double sharesOutstanding;
        Double epsTtm;
        Double peRatio;
        Double growthRate;
    }

    private static FundamentalSnapshot fetchFundamentals(String ticker) {
        FundamentalSnapshot fs = new FundamentalSnapshot();

        try {
            String ovJson = DataFetcher.fetchCompanyOverview(ticker);
            if (ovJson != null && !ovJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(ovJson);
                if (svc != null && !svc.isBlank()) {
                    return fs;
                }
                JsonNode ov = JSON.readTree(ovJson);
                Double sh = parseDouble(ov, "SharesOutstanding");
                if (sh != null && sh > 0) fs.sharesOutstanding = sh;
                fs.epsTtm = parseDouble(ov, "EPS");
                fs.peRatio = parseDouble(ov, "PERatio");
                Double revYoY = parseDouble(ov, "QuarterlyRevenueGrowthYOY");
                Double epsYoY = parseDouble(ov, "QuarterlyEarningsGrowthYOY");
                Double g = (revYoY != null) ? revYoY : epsYoY;
                if (g != null) fs.growthRate = clamp(g, -0.20, 0.35);
            }
        } catch (Exception ignore) {}

        try {
            String cfJson = DataFetcher.fetchCashFlow(ticker);
            if (cfJson != null && !cfJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(cfJson);
                if (svc != null && !svc.isBlank()) {
                    return fs;
                }
                JsonNode root = JSON.readTree(cfJson);
                JsonNode arr = root.path("annualReports");
                if (arr != null && arr.isArray() && arr.size() > 0) {
                    JsonNode y0 = arr.get(0);
                    Double ocf = parseDouble(y0, "operatingCashflow");
                    Double capex = parseDouble(y0, "capitalExpenditures");
                    if (ocf != null) {
                        double cpx = (capex == null) ? 0.0 : capex;
                        fs.fcf = ocf - cpx;
                    }
                }
            }
        } catch (Exception ignore) {}

        if (fs.growthRate == null) {
            try {
                String incJson = DataFetcher.fetchIncomeStatement(ticker);
                if (incJson != null && !incJson.isBlank()) {
                    String svc = PriceJsonParser.extractServiceMessage(incJson);
                    if (svc != null && !svc.isBlank()) {
                        return fs;
                    }
                    JsonNode root = JSON.readTree(incJson);
                    JsonNode arr = root.path("annualReports");
                    if (arr != null && arr.isArray() && arr.size() > 1) {
                        Double rev0 = parseDouble(arr.get(0), "totalRevenue");
                        Double rev1 = parseDouble(arr.get(1), "totalRevenue");
                        if (rev0 != null && rev1 != null && rev1 != 0) {
                            fs.growthRate = clamp((rev0 - rev1) / rev1, -0.20, 0.35);
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

        return fs;
    }

    // User portfolio tickers (mutable)
    private static final List<String> PORTFOLIO = new ArrayList<>(Arrays.asList(
            "GOOG", "SPY", "C", "JPM", "XOM", "PLTR", "EBAY", "AMZN", "UNH", "IGV"
    ));

    // Alpha Vantage free tier is 5 requests/min. Throttle between tickers.
    private static boolean ENABLE_THROTTLE = true;
    private static long THROTTLE_MS = 12_500; // ~5 req/min

    // Allow limiting the number of tickers for faster web runs
    private static int MAX_TICKERS = -1; // -1 means use full portfolio

    public static void configureThrottle(boolean enable, long throttleMs) {
        ENABLE_THROTTLE = enable;
        THROTTLE_MS = Math.max(0, throttleMs);
    }

    public static void setMaxTickers(int max) {
        MAX_TICKERS = max;
    }

    // Keep last used tickers for embedding charts in WebServer
    private static List<String> LAST_TICKERS = new ArrayList<>();
    public static List<String> getLastTickers() {
        return new ArrayList<>(LAST_TICKERS);
    }

    // Expose portfolio management APIs
    public static synchronized List<String> getPortfolio() {
        return new ArrayList<>(PORTFOLIO);
    }

    public static synchronized boolean addTicker(String raw) {
        if (raw == null) return false;
        String t = raw.trim().toUpperCase();
        if (t.isEmpty()) return false;
        // Basic validation: letters, digits, dot, dash, colon
        if (!t.matches("[A-Z0-9.:-]{1,10}")) return false;
        if (PORTFOLIO.contains(t)) return false;
        PORTFOLIO.add(t);
        return true;
    }

    public static synchronized boolean removeTicker(String raw) {
        if (raw == null) return false;
        String t = raw.trim().toUpperCase();
        return PORTFOLIO.remove(t);
    }

    // Replace entire portfolio (used to load from persistence)
    public static synchronized void setPortfolio(List<String> symbols) {
        PORTFOLIO.clear();
        if (symbols == null) return;
        for (String raw : symbols) {
            if (raw == null) continue;
            String t = raw.trim().toUpperCase();
            if (t.isEmpty()) continue;
            if (!t.matches("[A-Z0-9.:-]{1,10}")) continue;
            if (!PORTFOLIO.contains(t)) PORTFOLIO.add(t);
        }
    }

    private static class ForecastResult {
        final double lastClose;
        final double atr14;
        final double nextWeekLow;
        final double nextWeekHigh;
        final String directionBias; // Up / Down / Neutral
        final String technicalSignal;
        final String fundamentalSignal;
        final String finalVerdict;
        final double adx;
        final double momentum12mPct; // 12-1 momentum (or fallback)
        final double maxDrawdownPct;

        ForecastResult(double lastClose,
                       double atr14,
                       double nextWeekLow,
                       double nextWeekHigh,
                       String directionBias,
                       String technicalSignal,
                       String fundamentalSignal,
                       String finalVerdict,
                       double adx,
                       double momentum12mPct,
                       double maxDrawdownPct) {
            this.lastClose = lastClose;
            this.atr14 = atr14;
            this.nextWeekLow = nextWeekLow;
            this.nextWeekHigh = nextWeekHigh;
            this.directionBias = directionBias;
            this.technicalSignal = technicalSignal;
            this.fundamentalSignal = fundamentalSignal;
            this.finalVerdict = finalVerdict;
            this.adx = adx;
            this.momentum12mPct = momentum12mPct;
            this.maxDrawdownPct = maxDrawdownPct;
        }
    }

    private static ForecastResult analyzeAndForecast(String ticker) throws Exception {
        // Fetch data
        DataFetcher.setTicker(ticker);
        String json = DataFetcher.fetchStockData();

        // Parse series
        List<Double> closes = PriceJsonParser.extractClosingPrices(json);
        List<Double> highs = PriceJsonParser.extractHighPrices(json);
        List<Double> lows = PriceJsonParser.extractLowPrices(json);
        List<Long> volume = null;
        try { volume = PriceJsonParser.extractVolumeData(json); } catch (Exception ignore) {}

        if (closes == null || closes.size() < 30) {
            throw new Exception("Insufficient data (<30 bars)");
        }
        if (highs == null || highs.isEmpty() || lows == null || lows.isEmpty()) {
            highs = closes;
            lows = closes;
        }

        double lastClose = closes.get(closes.size() - 1);

        // Momentum (12-1) and Max Drawdown
        double momentum12mPct = calculateMomentum12m(closes);
        double maxDrawdownPct = calculateMaxDrawdownPct(closes);

        // Indicators
        List<Double> rsi = RSI.calculateRSI(closes, 14);
        double latestRSI = rsi.get(rsi.size() - 1);

        List<Double[]> macd = MACD.calculateMACD(closes);
        Double[] latestMacd = macd.get(macd.size() - 1);
        double macdLine = latestMacd[0];
        double macdSignal = latestMacd[1];

        List<Double> atr = ATR.calculateATR(highs, lows, closes, 14);
        double atr14 = atr.get(atr.size() - 1);

        List<Double[]> adxList = ADX.calculateADX(highs, lows, closes, 14);
        Double[] latestAdx = adxList.get(adxList.size() - 1);
        double adx = latestAdx[0];
        double plusDI = latestAdx[1];
        double minusDI = latestAdx[2];

        // SMA for context
        List<Double> sma20 = TechnicalAnalysisModel.calculateSMA(closes, 20);
        double latestSMA = sma20.get(sma20.size() - 1);

        // Technical signal (aligned with your runner logic)
        String technicalSignal = "NEUTRAL";
        if (adx > 25 && minusDI > plusDI && macdLine < macdSignal && lastClose < latestSMA) {
            technicalSignal = "STRONG SELL/SHORT";
        } else if (latestRSI < 30 && macdLine > macdSignal && lastClose > latestSMA) {
            technicalSignal = "BUY on DIP";
        } else if (latestRSI > 70) {
            technicalSignal = "SELL/PROFIT TAKE";
        }

        // Fundamental (Alpha Vantage: OVERVIEW + CASH_FLOW + INCOME_STATEMENT)
        FundamentalSnapshot fs = fetchFundamentals(ticker);
        double fairPricePerShare = Double.NaN;
        double peg = Double.NaN;
        try {
            double initialFcf = (fs.fcf != null) ? fs.fcf : Double.NaN;
            double shares = (fs.sharesOutstanding != null && fs.sharesOutstanding > 0) ? fs.sharesOutstanding : Double.NaN;
            double growth = (fs.growthRate != null) ? fs.growthRate : 0.04;
            if (!Double.isNaN(initialFcf) && !Double.isNaN(shares) && shares > 0) {
                double fairValue = DCFModel.calculateFairValue(initialFcf, growth, 0.12, 5, 0.02);
                fairPricePerShare = fairValue / shares;
            }
            Double pe = fs.peRatio;
            if (pe == null && fs.epsTtm != null && fs.epsTtm != 0) {
                pe = lastClose / fs.epsTtm;
            }
            double growthPct = clamp(growth * 100.0, 0.1, 35.0);
            if (pe != null && !Double.isNaN(pe)) {
                peg = FundamentalAnalysis.calculatePEGRatio(pe, growthPct);
            }
        } catch (Exception ignore) {}

        String fundamentalSignal = "NEUTRAL";
        if (!Double.isNaN(fairPricePerShare) && lastClose < fairPricePerShare && !Double.isNaN(peg) && peg <= 1.0) {
            fundamentalSignal = "STRONG BUY (Long Term)";
        } else if (!Double.isNaN(fairPricePerShare) && (lastClose > fairPricePerShare * 1.5 || (!Double.isNaN(peg) && peg > 2.0))) {
            fundamentalSignal = "OVERVALUED (Avoid)";
        }

        // Combined verdict (same scoring approach)
        int fundamentalScore = 0;
        if (fundamentalSignal.contains("STRONG BUY")) fundamentalScore += 3;
        else if (fundamentalSignal.contains("OVERVALUED")) fundamentalScore -= 3;

        int technicalScore = 0;
        if (technicalSignal.contains("BUY on DIP") || technicalSignal.contains("BUY on STRENGTH")) technicalScore += 2;
        else if (technicalSignal.contains("STRONG SELL") || technicalSignal.contains("SELL/PROFIT TAKE")) technicalScore -= 2;

        String finalVerdict;
        if (fundamentalScore >= 3 && technicalScore >= 0) finalVerdict = "STRONG BUY (Long Term Entry)";
        else if (fundamentalScore >= 3 && technicalScore < 0) finalVerdict = "HOLD/WAIT (Strong Value, but Short-Term Weakness)";
        else if (fundamentalScore < 0) finalVerdict = "AVOID/SELL (Overvalued)";
        else finalVerdict = "NEUTRAL (No clear edge)";

        // Next-week forecast using ATR-based expected move (sqrt(5) ~ trading week)
        double expectedMove = atr14 * Math.sqrt(5.0);
        double weekLow = Math.max(0.0, lastClose - expectedMove);
        double weekHigh = lastClose + expectedMove;

        // Direction bias from MACD/RSI/ADX
        String bias;
        if (macdLine > macdSignal && latestRSI >= 45 && plusDI >= minusDI) bias = "Up";
        else if (macdLine < macdSignal && latestRSI <= 55 && minusDI > plusDI) bias = "Down";
        else bias = "Neutral";

        return new ForecastResult(lastClose, atr14, weekLow, weekHigh, bias, technicalSignal, fundamentalSignal, finalVerdict, adx, momentum12mPct, maxDrawdownPct);
    }

    public static void main(String[] args) {
        System.out.println("\n=== 📊 Portfolio Weekly Summary (Next Week Forecast) ===\n");
        System.out.println("פורמט תצוגה: שורה-לפי-מניה (ללא טבלה)\n");

        Map<String, ForecastResult> results = new LinkedHashMap<>();

        int limit = (MAX_TICKERS > 0) ? Math.min(MAX_TICKERS, PORTFOLIO.size()) : PORTFOLIO.size();
        LAST_TICKERS = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String ticker = PORTFOLIO.get(i);
            LAST_TICKERS.add(ticker);
            try {
                ForecastResult r = analyzeAndForecast(ticker);
                results.put(ticker, r);

                System.out.println("------------------------------------------------------------");
                System.out.println("מניה: " + ticker);
                System.out.printf("מחיר נוכחי: $%.2f%n", r.lastClose);
                System.out.println("כיוון (Bias): " + r.directionBias);
                System.out.printf("תחזית לשבוע הבא (Low-High): $%.2f - $%.2f%n", r.nextWeekLow, r.nextWeekHigh);
                System.out.println("פסק-דין סופי: " + r.finalVerdict);
                System.out.printf("ADX: %.2f%n", r.adx);
                System.out.println("איתות טכני: " + r.technicalSignal);
                System.out.println("איתות פונדמנטלי: " + r.fundamentalSignal);
                System.out.printf("מומנטום 12-1: %.2f%%%n", r.momentum12mPct);
                System.out.printf("שיא ירידה (Max Drawdown): %.2f%%%n", r.maxDrawdownPct);
                System.out.println();

            } catch (Exception e) {
                System.out.println(String.format("%-6s | %s", ticker, "Error: " + e.getMessage()));
            }

            if (ENABLE_THROTTLE && i < limit - 1) {
                try { Thread.sleep(THROTTLE_MS); } catch (InterruptedException ignored) {}
            }
        }

        // Portfolio rollup
        System.out.println("\n--- 📈 Portfolio Rollup ---");
        long up = results.values().stream().filter(r -> r.directionBias.equals("Up")).count();
        long down = results.values().stream().filter(r -> r.directionBias.equals("Down")).count();
        long neutral = results.values().stream().filter(r -> r.directionBias.equals("Neutral")).count();
        System.out.println("Bias counts -> Up: " + up + ", Down: " + down + ", Neutral: " + neutral);

        double avgAtr = results.values().stream().mapToDouble(r -> r.atr14).average().orElse(0.0);
        double avgMom = results.values().stream().mapToDouble(r -> r.momentum12mPct).average().orElse(0.0);
        double avgDD = results.values().stream().mapToDouble(r -> r.maxDrawdownPct).average().orElse(0.0);
        System.out.printf("Average ATR(14): %.2f\n", avgAtr);
        System.out.printf("Average Momentum 12-1: %.2f%%%n", avgMom);
        System.out.printf("Average Max Drawdown: %.2f%%%n", avgDD);

        System.out.println("\nDone.\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    // Momentum 12-1: price change from ~12 months ago to ~1 month ago (skip recent month)
    private static double calculateMomentum12m(List<Double> closes) {
        if (closes == null || closes.size() < 40) {
            // fallback: use last N vs N-20 (~1 month)
            if (closes == null || closes.size() < 20) return 0.0;
            double a = closes.get(closes.size()-1);
            double b = closes.get(closes.size()-21);
            if (b == 0) return 0.0;
            return (a - b) / b * 100.0;
        }
        int n = closes.size();
        int oneMonthAgo = Math.max(0, n - 21);
        int twelveMonthsAgo = Math.max(0, n - 252);
        double end = closes.get(oneMonthAgo);
        double start = closes.get(twelveMonthsAgo);
        if (start == 0) return 0.0;
        return (end - start) / start * 100.0;
    }

    // Max Drawdown in percent over the series
    private static double calculateMaxDrawdownPct(List<Double> closes) {
        if (closes == null || closes.isEmpty()) return 0.0;
        double peak = closes.get(0);
        double maxDD = 0.0;
        for (double v : closes) {
            peak = Math.max(peak, v);
            double dd = (peak - v) / peak; // fraction
            if (dd > maxDD) maxDD = dd;
        }
        return maxDD * 100.0;
    }
}
