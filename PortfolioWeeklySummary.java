import java.util.*;

public class PortfolioWeeklySummary {

    // User portfolio tickers
    private static final List<String> PORTFOLIO = Arrays.asList(
            "GOOG", "SPY", "C", "JPM", "XOM", "PLTR", "EBAY", "AMZN", "UNH", "IGV"
    );

    // Alpha Vantage free tier is 5 requests/min. Throttle between tickers.
    private static final boolean ENABLE_THROTTLE = true;
    private static final long THROTTLE_MS = 12_500; // ~5 req/min

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

        ForecastResult(double lastClose,
                       double atr14,
                       double nextWeekLow,
                       double nextWeekHigh,
                       String directionBias,
                       String technicalSignal,
                       String fundamentalSignal,
                       String finalVerdict,
                       double adx) {
            this.lastClose = lastClose;
            this.atr14 = atr14;
            this.nextWeekLow = nextWeekLow;
            this.nextWeekHigh = nextWeekHigh;
            this.directionBias = directionBias;
            this.technicalSignal = technicalSignal;
            this.fundamentalSignal = fundamentalSignal;
            this.finalVerdict = finalVerdict;
            this.adx = adx;
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

        // Fundamental (same placeholders used in your code)
        double fairValue = DCFModel.calculateFairValue(500_000_000.0, 0.04, 0.12, 5, 0.02);
        double fairPricePerShare = fairValue / 10_000_000.0;
        double peRatio = 20.0; // placeholder
        double growthRate = 20.0; // placeholder
        double peg = FundamentalAnalysis.calculatePEGRatio(peRatio, growthRate);

        String fundamentalSignal = "NEUTRAL";
        if (lastClose < fairPricePerShare && peg <= 1.0) {
            fundamentalSignal = "STRONG BUY (Long Term)";
        } else if (lastClose > fairPricePerShare * 1.5 || peg > 2.0) {
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

        return new ForecastResult(lastClose, atr14, weekLow, weekHigh, bias, technicalSignal, fundamentalSignal, finalVerdict, adx);
    }

    public static void main(String[] args) {
        System.out.println("\n=== 📊 Portfolio Weekly Summary (Next Week Forecast) ===\n");
        System.out.println(String.format("%-6s | %-8s | %-9s | %-23s | %-20s | %-6s | %-10s | %-10s",
                "TICKER", "PRICE", "BIAS", "FORECAST (LOW-HIGH)", "FINAL VERDICT", "ADX", "TECH", "FUND"));
        System.out.println("------ | -------- | --------- | ------------------------- | -------------------- | ------ | ---------- | ----------");

        Map<String, ForecastResult> results = new LinkedHashMap<>();

        for (int i = 0; i < PORTFOLIO.size(); i++) {
            String ticker = PORTFOLIO.get(i);
            try {
                ForecastResult r = analyzeAndForecast(ticker);
                results.put(ticker, r);

                System.out.println(String.format(
                        "%-6s | $%-7.2f | %-9s | $%-10.2f - $%-10.2f | %-20s | %-6.2f | %-10s | %-10s",
                        ticker,
                        r.lastClose,
                        r.directionBias,
                        r.nextWeekLow,
                        r.nextWeekHigh,
                        truncate(r.finalVerdict, 20),
                        r.adx,
                        truncate(r.technicalSignal, 10),
                        truncate(r.fundamentalSignal, 10)
                ));

            } catch (Exception e) {
                System.out.println(String.format("%-6s | %s", ticker, "Error: " + e.getMessage()));
            }

            if (ENABLE_THROTTLE && i < PORTFOLIO.size() - 1) {
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
        System.out.printf("Average ATR(14): %.2f\n", avgAtr);

        System.out.println("\nDone.\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
