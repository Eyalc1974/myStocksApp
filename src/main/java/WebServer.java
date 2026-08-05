import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebServer {

    private static final int ALPHA_AGENT_MAX_LISTS = 10;

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Object RUN_CAPTURE_LOCK = new Object();
    
    // Swing scan state for background scanning
    private static volatile boolean swingScanRunning = false;
    private static volatile int swingScanProgress = 0;
    private static volatile int swingScanTotal = 0;
    private static volatile String swingScanCurrentTicker = "";
    private static volatile String swingScanStartTime = "";

    private static final int DAILY_TOP_PICK_COUNT = 5;
    private static final int DAILY_TRACKING_DAYS = 31;

    private static final int ALPHA_AGENT_MAX_TICKERS = 10;
    private static final int ALPHA_AGENT_DEFAULT_TRACKING_DAYS = 14;
    private static final String ALPHA_AGENT_BENCH_NASDAQ100 = "QQQ";
    private static final String ALPHA_AGENT_BENCH_SP500 = "SPY";

    private static final int ALPHA_AGENT_FULL_ANALYZE_LIMIT = 30;
    private static final int ALPHA_AGENT_FINAL_PICK_MIN = 5;
    private static final int ALPHA_AGENT_FINAL_PICK_MAX = 5;

    private static final ExecutorService alphaAgentAnalysisExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("alpha-agent-analysis");
        return t;
    });

    private static final Object alphaAgentLock = new Object();

    // Strategy Comparison state
    private static final ExecutorService strategyCompareExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("strategy-compare");
        return t;
    });
    private static final Object strategyCompareLock = new Object();
    private static volatile boolean strategyCompareRunning = false;
    private static volatile String strategyCompareCurrentTicker = "";
    private static volatile int strategyCompareProgress = 0;
    private static volatile int strategyCompareTotal = 0;
    private static volatile List<Map<String, Object>> strategyCompareResults = new ArrayList<>();

    // Cache building status tracking
    private static final Object cacheBuildLock = new Object();
    private static volatile boolean cacheBuildRunning = false;
    private static volatile String cacheBuildStatus = "not_started";
    private static volatile int cacheBuildProgress = 0;
    private static volatile int cacheBuildTotal = 0;
    private static volatile String cacheBuildStartDate = "";
    private static volatile String cacheBuildEndDate = "";
    private static volatile boolean autoBuildCacheOnStartup = false;
    private static final String CACHE_SETTINGS_FILE = "cache_settings.json";
    private static final String CACHE_STATE_FILE = "cache_state.json";

    // Market data fetch progress tracking
    private static final Object marketDataFetchLock = new Object();
    private static volatile boolean marketDataFetchRunning = false;
    private static volatile int marketDataFetchProgress = 0;
    private static volatile int marketDataFetchTotal = 0;
    private static volatile String marketDataFetchCurrentTicker = "";
    private static volatile String marketDataFetchStatus = "not_started";

    // Popular tickers for random selection
    private static final String[] POPULAR_TICKERS = {
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK.B", "JPM", "V",
        "JNJ", "WMT", "PG", "MA", "HD", "UNH", "DIS", "PYPL", "ADBE", "NFLX",
        "CRM", "INTC", "CSCO", "PFE", "ABT", "KO", "PEP", "TMO", "COST", "AVGO",
        "NKE", "MRK", "ACN", "LLY", "DHR", "TXN", "UPS", "QCOM", "NEE", "MDT",
        "AMD", "ORCL", "HON", "IBM", "SBUX", "GE", "CAT", "BA", "MMM", "AXP"
    };

    // Favorites storage (static for access from API handlers)
    private static final Object favLockStatic = new Object();
    private static final java.util.LinkedHashSet<String> favItemsStatic = new java.util.LinkedHashSet<>();

    private static final class DailyTrackingRow {
        public String ticker;
        public Double startOpen;
        public Double startClose;
        public Double eodClose;
        public String lastUpdatedNy;
        public DailyTrackingRow() {}
        public DailyTrackingRow(String ticker) { this.ticker = ticker; }
    }

    private static String bestEffortReadDailyJsonNoFetch(String symbol) {
        // No caching - always fetch fresh data from AlphaVantage
        try {
            if (symbol == null || symbol.isBlank()) return null;
            String sym = symbol.trim().toUpperCase();
            DataFetcher.setTicker(sym);
            return DataFetcher.fetchStockData();
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Run strategy comparison - backtests tickers with all 3 strategies
     * Fetches SPY data ONCE at the start, then each ticker's data
     * @param tickerList - list of tickers to test (from favorites or random selection)
     * @param balance - starting balance for backtest
     */
    private static void runStrategyComparison(List<String> tickerList, double balance) {
        try {
            // Use provided ticker list
            List<String> selected = new ArrayList<>(tickerList);

            List<Map<String, Object>> results = new ArrayList<>();

            // Get entry filters for each mode ONCE
            ScoringConfig.EntryFiltersConfig ltFilters = ScoringConfig.getEntryFiltersForMode("longterm");
            ScoringConfig.EntryFiltersConfig swFilters = ScoringConfig.getEntryFiltersForMode("swing");
            ScoringConfig.EntryFiltersConfig moFilters = ScoringConfig.getEntryFiltersForMode("momentum");

            // Fetch SPY data ONCE at the start (same benchmark for all stocks)
            synchronized (strategyCompareLock) {
                strategyCompareCurrentTicker = "SPY (fetching benchmark...)";
            }
            DataFetcher.setTicker("SPY");
            String spyJson = DataFetcher.fetchStockData();
            if (spyJson == null) {
                synchronized (strategyCompareLock) {
                    strategyCompareCurrentTicker = "Error: Failed to fetch SPY data";
                }
                return;
            }
            List<Double> spyPricesOriginal = PriceJsonParser.extractClosingPrices(spyJson);
            if (spyPricesOriginal == null || spyPricesOriginal.isEmpty()) {
                synchronized (strategyCompareLock) {
                    strategyCompareCurrentTicker = "Error: No SPY price data";
                }
                return;
            }
            // Reverse SPY to old-to-new order
            Collections.reverse(spyPricesOriginal);
            
            // Rate limit after SPY fetch
            Thread.sleep(13000);

            for (int i = 0; i < selected.size(); i++) {
                String ticker = selected.get(i);
                synchronized (strategyCompareLock) {
                    strategyCompareCurrentTicker = ticker + " (fetching data...)";
                    strategyCompareProgress = i + 1;
                }

                try {
                    // Fetch data for this ticker
                    DataFetcher.setTicker(ticker);
                    String stockJson = DataFetcher.fetchStockData();
                    
                    // Rate limit before next API call
                    if (i < selected.size() - 1) {
                        Thread.sleep(13000);
                    }

                    if (stockJson == null) {
                        // Skip this ticker - API error or rate limited
                        continue;
                    }

                    // Parse stock data
                    List<Double> stockPrices = PriceJsonParser.extractClosingPrices(stockJson);
                    List<Double> stockHighs = PriceJsonParser.extractHighPrices(stockJson);
                    List<Double> stockLows = PriceJsonParser.extractLowPrices(stockJson);
                    List<String> dates = PriceJsonParser.extractDates(stockJson);
                    
                    // Use pre-fetched SPY data (make a copy for alignment)
                    List<Double> spyPrices = new ArrayList<>(spyPricesOriginal);

                    if (stockPrices == null || stockPrices.isEmpty() || spyPrices == null || spyPrices.isEmpty()) {
                        continue;
                    }

                    // Reverse order (old to new) - SPY already reversed
                    Collections.reverse(stockPrices);
                    Collections.reverse(stockHighs);
                    Collections.reverse(stockLows);
                    Collections.reverse(dates);

                    // Align lengths
                    int minLen = Math.min(stockPrices.size(), spyPrices.size());
                    stockPrices = new ArrayList<>(stockPrices.subList(0, minLen));
                    stockHighs = new ArrayList<>(stockHighs.subList(0, minLen));
                    stockLows = new ArrayList<>(stockLows.subList(0, minLen));
                    spyPrices = new ArrayList<>(spyPrices.subList(0, minLen));
                    dates = new ArrayList<>(dates.subList(0, Math.min(dates.size(), minLen)));

                    // Calculate RS scores once
                    List<Double> rsScores = StrategyBacktester.calculateRSScores(stockPrices, spyPrices, 63);

                    synchronized (strategyCompareLock) {
                        strategyCompareCurrentTicker = ticker + " (running backtests...)";
                    }

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticker", ticker);

                    // Run 3 backtests with SAME data, different parameters
                    StrategyBacktester ltBacktester = new StrategyBacktester(
                        ltFilters.rsEntryThreshold, ltFilters.rsExitThreshold,
                        ltFilters.riskPercentPerTrade / 100.0, ltFilters.atrStopLossMultiplier, ltFilters.atrPeriod);
                    StrategyBacktester.BacktestResult ltResult = ltBacktester.runBacktest(
                        ticker, stockPrices, stockHighs, stockLows, rsScores, spyPrices, dates, balance);
                    row.put("longterm", ltResult != null ? ltResult.totalReturn : 0.0);
                    row.put("ltTrades", ltResult != null ? ltResult.totalTrades : 0);

                    StrategyBacktester swBacktester = new StrategyBacktester(
                        swFilters.rsEntryThreshold, swFilters.rsExitThreshold,
                        swFilters.riskPercentPerTrade / 100.0, swFilters.atrStopLossMultiplier, swFilters.atrPeriod);
                    StrategyBacktester.BacktestResult swResult = swBacktester.runBacktest(
                        ticker, stockPrices, stockHighs, stockLows, rsScores, spyPrices, dates, balance);
                    row.put("swing", swResult != null ? swResult.totalReturn : 0.0);
                    row.put("swTrades", swResult != null ? swResult.totalTrades : 0);

                    StrategyBacktester moBacktester = new StrategyBacktester(
                        moFilters.rsEntryThreshold, moFilters.rsExitThreshold,
                        moFilters.riskPercentPerTrade / 100.0, moFilters.atrStopLossMultiplier, moFilters.atrPeriod);
                    StrategyBacktester.BacktestResult moResult = moBacktester.runBacktest(
                        ticker, stockPrices, stockHighs, stockLows, rsScores, spyPrices, dates, balance);
                    row.put("momentum", moResult != null ? moResult.totalReturn : 0.0);
                    row.put("moTrades", moResult != null ? moResult.totalTrades : 0);

                    // SPY return (same for all)
                    row.put("spy", ltResult != null ? ltResult.spyReturn : 0.0);

                    results.add(row);
                } catch (Exception e) {
                    // Skip this ticker on error
                }

                // Update results as we go
                synchronized (strategyCompareLock) {
                    strategyCompareResults = new ArrayList<>(results);
                }
            }

        } catch (Exception e) {
            // Ignore
        } finally {
            synchronized (strategyCompareLock) {
                strategyCompareRunning = false;
                strategyCompareCurrentTicker = "";
            }
        }
    }

    private static List<String> chooseUniverseSample(List<String> universe, int sampleSize) {
        if (universe == null) return new ArrayList<>();
        List<String> base = new ArrayList<>();
        for (String t : universe) {
            if (t == null) continue;
            String v = t.trim().toUpperCase();
            if (!v.isBlank()) base.add(v);
        }
        long seed = 0;
        try {
            seed = java.time.LocalDate.now(NY).toEpochDay();
        } catch (Exception ignore) {}
        Collections.shuffle(base, new Random(seed));
        if (sampleSize <= 0 || base.size() <= sampleSize) return base;
        return new ArrayList<>(base.subList(0, sampleSize));
    }

    private static List<String> chooseUniverseSample(List<String> universe, int sampleSize, String salt) {
        if (universe == null) return new ArrayList<>();
        List<String> base = new ArrayList<>();
        for (String t : universe) {
            if (t == null) continue;
            String v = t.trim().toUpperCase();
            if (!v.isBlank()) base.add(v);
        }
        long seed = 0;
        try {
            seed = java.time.LocalDate.now(NY).toEpochDay();
        } catch (Exception ignore) {}
        if (salt != null && !salt.isBlank()) {
            seed = seed ^ (long) salt.hashCode();
        }
        Collections.shuffle(base, new Random(seed));
        if (sampleSize <= 0 || base.size() <= sampleSize) return base;
        return new ArrayList<>(base.subList(0, sampleSize));
    }

    private static List<String> prefilterAlphaAgentCandidatesFromUniverse(int fullAnalyzeLimit) {
        int lim = fullAnalyzeLimit > 0 ? fullAnalyzeLimit : ALPHA_AGENT_FULL_ANALYZE_LIMIT;

        List<String> universe = LongTermCandidateFinder.getUniverseTickers();
        // Scan up to ~200 (universe size), but we only need the best ~50 to fully analyze.
        // First pass uses cached daily JSON only (fast). If insufficient, fetch daily for more.

        List<AlphaAgentScored> scored = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String t : universe) {
            String json = bestEffortReadDailyJsonNoFetch(t);
            if (json == null) {
                missing.add(t);
                continue;
            }
            Double sc = scoreAlphaAgentSymbolFromDailyJson(json);
            if (sc == null || Double.isNaN(sc)) continue;
            scored.add(new AlphaAgentScored(t, sc));
        }

        // If we don't have enough cached data, fetch daily JSON for a limited number of missing tickers.
        if (scored.size() < lim) {
            int need = lim - scored.size();
            int fetchCap = Math.min(need + 10, 60);
            List<String> toFetch = chooseUniverseSample(missing, fetchCap);
            for (int i = 0; i < toFetch.size(); i++) {
                String t = toFetch.get(i);
                try {
                    // Always fetch fresh from AlphaVantage - no caching
                    DataFetcher.setTicker(t);
                    String json = DataFetcher.fetchStockData();

                    Double sc = scoreAlphaAgentSymbolFromDailyJson(json);
                    if (sc == null || Double.isNaN(sc)) continue;
                    scored.add(new AlphaAgentScored(t, sc));
                } catch (Exception ignore) {
                }

                // Basic throttle to avoid hammering API
                if (i < toFetch.size() - 1) {
                    try { Thread.sleep(900); } catch (Exception ignore) {}
                }
            }
        }

        scored.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) return c;
            return a.ticker.compareTo(b.ticker);
        });
        List<String> out = new ArrayList<>();
        int max = Math.min(lim, scored.size());
        for (int i = 0; i < max; i++) out.add(scored.get(i).ticker);
        return out;
    }

    private static int alphaAgentCompositeScore(StockAnalysisResult r) {
        if (r == null) return Integer.MIN_VALUE;
        int pos = 0;
        int neg = 0;

        String tech = r.technicalSignal == null ? "" : r.technicalSignal.toUpperCase();
        String fund = r.fundamentalSignal == null ? "" : r.fundamentalSignal.toUpperCase();
        String verdict = r.finalVerdict == null ? "" : r.finalVerdict.toUpperCase();

        if (tech.contains("BUY")) pos++;
        if (tech.contains("SELL") || tech.contains("SHORT") || tech.contains("AVOID")) neg++;

        if (fund.contains("STRONG BUY")) pos += 2;
        if (fund.contains("OVERVALUED") || fund.contains("DISTRESS")) neg += 2;

        if (verdict.contains("STRONG BUY")) pos += 2;
        else if (verdict.contains("HOLD")) pos += 1;
        if (verdict.contains("AVOID") || verdict.contains("SELL") || verdict.contains("DISTRESS")) neg += 2;

        // prefer calmer trends (lower ADX) for entry
        if (Double.isFinite(r.adxStrength)) {
            if (r.adxStrength < 20.0) pos += 1;
            else if (r.adxStrength > 35.0) neg += 1;
        }

        if (r.beneishManipulator != null && r.beneishManipulator) {
            neg += 2;
        }

        if (r.sloanLowQuality != null && r.sloanLowQuality) {
            neg += 1;
        }

        return (pos * 10) - (neg * 10);
    }

    private static List<String> pickTopAlphaAgentTickersFromAnalyzed(List<StockAnalysisResult> analyzed) {
        List<StockAnalysisResult> list = analyzed == null ? new ArrayList<>() : new ArrayList<>(analyzed);
        list.removeIf(x -> x == null || x.ticker == null || x.ticker.isBlank());
        list.sort((a, b) -> {
            int sa = alphaAgentCompositeScore(a);
            int sb = alphaAgentCompositeScore(b);
            int c = Integer.compare(sb, sa);
            if (c != 0) return c;
            return a.ticker.compareToIgnoreCase(b.ticker);
        });
        int n = ALPHA_AGENT_FINAL_PICK_MAX;
        if (list.size() < n) n = list.size();
        if (n < ALPHA_AGENT_FINAL_PICK_MIN) n = list.size();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(list.get(i).ticker.trim().toUpperCase());
        return out;
    }

    private static AlphaAgentPortfolio bestEffortStartAlphaAgentPortfolioAsync(int trackingDays) {
        return bestEffortStartAlphaAgentPortfolioAsync(trackingDays, null, true);
    }

    private static AlphaAgentPortfolio bestEffortStartAlphaAgentPortfolioAsync(int trackingDays, String portfolioId, boolean createNewSlot) {
        int days = trackingDays > 0 ? trackingDays : ALPHA_AGENT_DEFAULT_TRACKING_DAYS;
        if (days < 2) days = 2;
        if (days > 60) days = 60;

        AlphaAgentPortfolio pf = new AlphaAgentPortfolio();
        String startNyDate = nyToday();
        pf.createdAtNy = ZonedDateTime.now(NY).toString();
        pf.startNyDate = startNyDate;
        pf.trackingDays = days;
        pf.positions = new ArrayList<>();
        pf.benchNasdaq100 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_NASDAQ100, startNyDate, startNyDate);
        pf.benchSp500 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_SP500, startNyDate, startNyDate);
        pf.lastError = "AlphaAgent: selecting 30 random tickers...";

        final String targetId;
        synchronized (alphaAgentLock) {
            if (createNewSlot) {
                String id = bestEffortCreateNewAlphaAgentPortfolioSlot(pf);
                targetId = id;
            } else {
                String id = portfolioId;
                if (id == null || id.isBlank()) id = bestEffortGetActiveAlphaAgentPortfolioId();
                if (id == null || id.isBlank()) {
                    String created = bestEffortCreateNewAlphaAgentPortfolioSlot(pf);
                    targetId = created;
                } else {
                    bestEffortPersistAlphaAgentPortfolioById(id, pf);
                    targetId = id;
                }
            }
        }

        if (targetId == null || targetId.isBlank()) {
            pf.lastError = "AlphaAgent: cannot create new list (max " + ALPHA_AGENT_MAX_LISTS + "). Drop a list first.";
            synchronized (alphaAgentLock) {
                String id = bestEffortGetActiveAlphaAgentPortfolioId();
                bestEffortPersistAlphaAgentPortfolioById(id, pf);
            }
            return pf;
        }

        final int finalDays = days;
        alphaAgentAnalysisExec.submit(() -> {
            try {
                // Step 1: random sample from NASDAQ universe (about ~200)
                List<String> candidates = chooseUniverseSample(LongTermCandidateFinder.getUniverseTickers(), ALPHA_AGENT_FULL_ANALYZE_LIMIT, targetId);
                synchronized (alphaAgentLock) {
                    AlphaAgentPortfolio cur = bestEffortLoadAlphaAgentPortfolioById(targetId);
                    if (cur != null) {
                        cur.lastError = "AlphaAgent: full analysis starting (0/" + candidates.size() + ")...";
                        bestEffortPersistAlphaAgentPortfolioById(targetId, cur);
                    }
                }

                // Step 2: full analysis for top ~50
                List<StockAnalysisResult> analyzed = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    String t = candidates.get(i);
                    try {
                        StockAnalysisResult r = StockScannerRunner.analyzeSingleStock(t);
                        analyzed.add(r);
                    } catch (Exception ignore) {
                    }

                    synchronized (alphaAgentLock) {
                        AlphaAgentPortfolio cur = bestEffortLoadAlphaAgentPortfolioById(targetId);
                        if (cur != null) {
                            cur.lastError = "AlphaAgent: full analysis running (" + (i + 1) + "/" + candidates.size() + ")...";
                            bestEffortPersistAlphaAgentPortfolioById(targetId, cur);
                        }
                    }

                    // Throttle (full analysis is API heavy)
                    if (i < candidates.size() - 1) {
                        try { Thread.sleep(12_500); } catch (Exception ignore) {}
                    }
                }

                // Step 3: rank and pick final tickers
                List<String> finalTickers = pickTopAlphaAgentTickersFromAnalyzed(analyzed);

                AlphaAgentPortfolio out = new AlphaAgentPortfolio();
                out.createdAtNy = ZonedDateTime.now(NY).toString();
                out.startNyDate = nyToday();
                out.trackingDays = finalDays;
                out.positions = new ArrayList<>();
                for (String t : finalTickers) {
                    AlphaAgentPosition pos = buildAlphaAgentPosition(t, out.startNyDate, out.startNyDate);
                    if (pos != null) out.positions.add(pos);
                }
                out.benchNasdaq100 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_NASDAQ100, out.startNyDate, out.startNyDate);
                out.benchSp500 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_SP500, out.startNyDate, out.startNyDate);
                out.lastError = "";

                synchronized (alphaAgentLock) {
                    bestEffortPersistAlphaAgentPortfolioById(targetId, out);
                    bestEffortSetActiveAlphaAgentPortfolioId(targetId);
                }
                bestEffortUpdateAlphaAgentPortfolioNow();
            } catch (Exception e) {
                synchronized (alphaAgentLock) {
                    AlphaAgentPortfolio cur = bestEffortLoadAlphaAgentPortfolioById(targetId);
                    if (cur != null) {
                        cur.lastError = "AlphaAgent error: " + e.getMessage();
                        bestEffortPersistAlphaAgentPortfolioById(targetId, cur);
                    }
                }
            }
        });

        return pf;
    }

    private static final class DailyTrackingSnapshot {
        public String nyDate;
        public String createdAtNy;
        public List<DailyTrackingRow> rows;
        public DailyTrackingSnapshot() {}
    }

    private static final class AlphaAgentPosition {
        public String ticker;
        public String startNyDate;
        public String lastNyDate;
        public Double startPrice;
        public Double lastPrice;
    }

    private static final class AlphaAgentPortfolio {
        public String createdAtNy;
        public String startNyDate;
        public int trackingDays;
        public List<AlphaAgentPosition> positions;
        public AlphaAgentPosition benchNasdaq100;
        public AlphaAgentPosition benchSp500;
        public String lastError;
        public boolean userManaged;
        public String lastUpdatedNy;
    }

    private static final class AlphaAgentScored {
        final String ticker;
        final double score;
        AlphaAgentScored(String ticker, double score) {
            this.ticker = ticker;
            this.score = score;
        }
    }

    private static Path trackingDir() {
        return Paths.get("finder-cache", "daily-top-tracking");
    }

    private static Path trackingTickersFile() {
        return Paths.get("finder-cache").resolve("daily-top-tracking-tickers.txt");
    }

    private static Path trackingFileForNyDate(String nyDate) {
        return trackingDir().resolve(nyDate + ".json");
    }

    private static Path alphaAgentDir() {
        return Paths.get("finder-cache", "alpha-agent");
    }

    private static Path alphaAgentStoreFile() {
        return alphaAgentDir().resolve("store.json");
    }

    private static Path alphaAgentLegacyPortfolioFile() {
        return alphaAgentDir().resolve("portfolio.json");
    }

    private static final class AlphaAgentStore {
        public String activeId;
        public LinkedHashMap<String, AlphaAgentPortfolio> portfolios;
        public AlphaAgentStore() {}
    }

    private static AlphaAgentStore bestEffortLoadAlphaAgentStore() {
        try {
            Files.createDirectories(alphaAgentDir());

            Path storeFile = alphaAgentStoreFile();
            AlphaAgentStore store = null;
            if (Files.exists(storeFile)) {
                try {
                    store = JSON.readValue(storeFile.toFile(), AlphaAgentStore.class);
                } catch (Exception ignore) {
                    store = null;
                }
            }

            if (store == null) {
                store = new AlphaAgentStore();
                store.portfolios = new LinkedHashMap<>();
            }
            if (store.portfolios == null) store.portfolios = new LinkedHashMap<>();

            if (store.portfolios.isEmpty()) {
                try {
                    Path legacy = alphaAgentLegacyPortfolioFile();
                    if (Files.exists(legacy)) {
                        AlphaAgentPortfolio pf = JSON.readValue(legacy.toFile(), AlphaAgentPortfolio.class);
                        if (pf != null) {
                            String id = UUID.randomUUID().toString();
                            store.portfolios.put(id, pf);
                            store.activeId = id;
                            bestEffortPersistAlphaAgentStore(store);
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            if (store.activeId == null || store.activeId.isBlank() || !store.portfolios.containsKey(store.activeId)) {
                if (!store.portfolios.isEmpty()) {
                    store.activeId = store.portfolios.keySet().iterator().next();
                    bestEffortPersistAlphaAgentStore(store);
                }
            }

            return store;
        } catch (Exception ignore) {
            AlphaAgentStore s = new AlphaAgentStore();
            s.portfolios = new LinkedHashMap<>();
            return s;
        }
    }

    private static void bestEffortPersistAlphaAgentStore(AlphaAgentStore store) {
        if (store == null) return;
        try {
            Files.createDirectories(alphaAgentDir());
            Path p = alphaAgentStoreFile();
            Path tmp = alphaAgentDir().resolve("store.json.tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {
        }
    }

    private static AlphaAgentPortfolio bestEffortLoadAlphaAgentPortfolioById(String id) {
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            if (store == null || store.portfolios == null || store.portfolios.isEmpty()) return null;
            if (id != null && !id.isBlank()) {
                AlphaAgentPortfolio pf = store.portfolios.get(id);
                if (pf != null) return pf;
            }
            if (store.activeId != null && !store.activeId.isBlank()) return store.portfolios.get(store.activeId);
            return store.portfolios.values().iterator().next();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static AlphaAgentPortfolio bestEffortLoadAlphaAgentPortfolio() {
        return bestEffortLoadAlphaAgentPortfolioById(null);
    }

    private static String bestEffortGetActiveAlphaAgentPortfolioId() {
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            return store == null ? null : store.activeId;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void bestEffortSetActiveAlphaAgentPortfolioId(String id) {
        if (id == null || id.isBlank()) return;
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            if (store == null || store.portfolios == null || !store.portfolios.containsKey(id)) return;
            store.activeId = id;
            bestEffortPersistAlphaAgentStore(store);
        } catch (Exception ignore) {
        }
    }

    private static String bestEffortCreateNewAlphaAgentPortfolioSlot(AlphaAgentPortfolio initialPf) {
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            if (store == null) store = new AlphaAgentStore();
            if (store.portfolios == null) store.portfolios = new LinkedHashMap<>();
            if (store.portfolios.size() >= ALPHA_AGENT_MAX_LISTS) return null;
            String id = UUID.randomUUID().toString();
            store.portfolios.put(id, initialPf);
            store.activeId = id;
            bestEffortPersistAlphaAgentStore(store);
            return id;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void bestEffortPersistAlphaAgentPortfolioById(String id, AlphaAgentPortfolio pf) {
        if (pf == null) return;
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            if (store == null) return;
            if (store.portfolios == null) store.portfolios = new LinkedHashMap<>();

            String key = id;
            if (key == null || key.isBlank()) key = store.activeId;
            if (key == null || key.isBlank()) {
                if (store.portfolios.size() >= ALPHA_AGENT_MAX_LISTS) return;
                key = UUID.randomUUID().toString();
            }

            store.portfolios.put(key, pf);
            store.activeId = key;
            bestEffortPersistAlphaAgentStore(store);
        } catch (Exception ignore) {
        }
    }

    private static void bestEffortPersistAlphaAgentPortfolio(AlphaAgentPortfolio pf) {
        bestEffortPersistAlphaAgentPortfolioById(null, pf);
    }

    private static boolean bestEffortDropAlphaAgentPortfolioById(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            AlphaAgentStore store = bestEffortLoadAlphaAgentStore();
            if (store == null || store.portfolios == null || store.portfolios.isEmpty()) return false;
            if (!store.portfolios.containsKey(id)) return false;
            store.portfolios.remove(id);
            if (store.activeId != null && store.activeId.equals(id)) {
                store.activeId = store.portfolios.isEmpty() ? null : store.portfolios.keySet().iterator().next();
            }
            bestEffortPersistAlphaAgentStore(store);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static Double bestEffortCloseOnOrBefore(Map<String, Double> closeByDate, String nyDate) {
        if (closeByDate == null || closeByDate.isEmpty() || nyDate == null || nyDate.isBlank()) return null;
        String best = null;
        for (String d : closeByDate.keySet()) {
            if (d == null) continue;
            if (d.compareTo(nyDate) > 0) continue;
            if (best == null || d.compareTo(best) > 0) best = d;
        }
        if (best == null) return null;
        return closeByDate.get(best);
    }

    private static void bestEffortUpdateAlphaAgentPortfolioNow() {
        synchronized (alphaAgentLock) {
            AlphaAgentPortfolio pf = bestEffortLoadAlphaAgentPortfolio();
            if (pf == null) return;
            String lastNyDate = nyToday();
            try {
                if (pf.positions != null) {
                    for (AlphaAgentPosition pos : pf.positions) {
                        if (pos == null || pos.ticker == null || pos.ticker.isBlank()) continue;
                        pos.lastNyDate = lastNyDate;
                        Map<String, Double> closeByDate = loadDailyCloseByDateCached(pos.ticker);
                        if (pos.startPrice == null && pos.startNyDate != null) {
                            pos.startPrice = bestEffortCloseOnOrBefore(closeByDate, pos.startNyDate);
                        }
                        pos.lastPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                    }
                }
                if (pf.benchNasdaq100 != null && pf.benchNasdaq100.ticker != null) {
                    pf.benchNasdaq100.lastNyDate = lastNyDate;
                    Map<String, Double> closeByDate = loadDailyCloseByDateCached(pf.benchNasdaq100.ticker);
                    if (pf.benchNasdaq100.startPrice == null && pf.benchNasdaq100.startNyDate != null) {
                        pf.benchNasdaq100.startPrice = bestEffortCloseOnOrBefore(closeByDate, pf.benchNasdaq100.startNyDate);
                    }
                    pf.benchNasdaq100.lastPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                    if (pf.benchNasdaq100.startPrice == null) {
                        pf.benchNasdaq100.startPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                    }
                }
                if (pf.benchSp500 != null && pf.benchSp500.ticker != null) {
                    pf.benchSp500.lastNyDate = lastNyDate;
                    Map<String, Double> closeByDate = loadDailyCloseByDateCached(pf.benchSp500.ticker);
                    if (pf.benchSp500.startPrice == null && pf.benchSp500.startNyDate != null) {
                        pf.benchSp500.startPrice = bestEffortCloseOnOrBefore(closeByDate, pf.benchSp500.startNyDate);
                    }
                    pf.benchSp500.lastPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                    if (pf.benchSp500.startPrice == null) {
                        pf.benchSp500.startPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                    }
                }
                pf.lastError = null;
                pf.lastUpdatedNy = ZonedDateTime.now(NY).toString();
            } catch (Exception e) {
                pf.lastError = e.getMessage();
            }
            bestEffortPersistAlphaAgentPortfolio(pf);
        }
    }

    private static void bestEffortForceRefreshAlphaAgentPricesAsync(String portfolioId) {
        final String pid = portfolioId;
        alphaAgentAnalysisExec.submit(() -> {
            try {
                AlphaAgentPortfolio pf;
                synchronized (alphaAgentLock) {
                    pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio();
                    if (pf == null) return;
                    pf.lastError = "AlphaAgent: refreshing live quotes...";
                    bestEffortPersistAlphaAgentPortfolioById(pid, pf);
                }

                String lastNyDate = nyToday();
                Map<String, Double> livePriceByTicker = new HashMap<>();
                List<String> liveOk = new ArrayList<>();
                MonitoringAlphaVantageClient av = null;
                try {
                    av = MonitoringAlphaVantageClient.fromEnv();
                } catch (Exception ignore) {}

                List<String> toUpdate = new ArrayList<>();
                if (pf.positions != null) {
                    for (AlphaAgentPosition p : pf.positions) {
                        if (p == null || p.ticker == null || p.ticker.isBlank()) continue;
                        toUpdate.add(p.ticker.trim().toUpperCase());
                    }
                }
                if (pf.benchNasdaq100 != null && pf.benchNasdaq100.ticker != null && !pf.benchNasdaq100.ticker.isBlank()) {
                    toUpdate.add(pf.benchNasdaq100.ticker.trim().toUpperCase());
                }
                if (pf.benchSp500 != null && pf.benchSp500.ticker != null && !pf.benchSp500.ticker.isBlank()) {
                    toUpdate.add(pf.benchSp500.ticker.trim().toUpperCase());
                }

                for (int i = 0; i < toUpdate.size(); i++) {
                    String t = toUpdate.get(i);
                    try {
                        if (av != null) {
                            try {
                                JsonNode q = av.globalQuote(t);
                                Double price = extractGlobalQuotePrice(q);
                                if (price != null && Double.isFinite(price)) {
                                    livePriceByTicker.put(t, price);
                                    liveOk.add(t);
                                }
                            } catch (Exception ignore) {}
                        }
                    } catch (Exception ignore) {}

                    synchronized (alphaAgentLock) {
                        AlphaAgentPortfolio cur = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio();
                        if (cur != null) {
                            cur.lastError = "AlphaAgent: refreshing live quotes (" + (i + 1) + "/" + toUpdate.size() + ")...";
                            bestEffortPersistAlphaAgentPortfolioById(pid, cur);
                        }
                    }

                    if (i < toUpdate.size() - 1) {
                        // GLOBAL_QUOTE is rate-limited. Throttle to improve chances of getting live prices.
                        try { Thread.sleep(12_500); } catch (Exception ignore) {}
                    }
                }

                synchronized (alphaAgentLock) {
                    AlphaAgentPortfolio cur = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio();
                    if (cur == null) return;
                    try {
                        if (cur.positions != null) {
                            for (AlphaAgentPosition pos : cur.positions) {
                                if (pos == null || pos.ticker == null || pos.ticker.isBlank()) continue;
                                pos.lastNyDate = lastNyDate;
                                String t = pos.ticker.trim().toUpperCase();
                                Map<String, Double> closeByDate = loadDailyCloseByDateCached(t);
                                if (pos.startPrice == null && pos.startNyDate != null) {
                                    pos.startPrice = bestEffortCloseOnOrBefore(closeByDate, pos.startNyDate);
                                }
                                Double live = livePriceByTicker.get(t);
                                pos.lastPrice = (live != null) ? live : bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                            }
                        }
                        if (cur.benchNasdaq100 != null && cur.benchNasdaq100.ticker != null) {
                            cur.benchNasdaq100.lastNyDate = lastNyDate;
                            String t = cur.benchNasdaq100.ticker.trim().toUpperCase();
                            Map<String, Double> closeByDate = loadDailyCloseByDateCached(t);
                            if (cur.benchNasdaq100.startPrice == null && cur.benchNasdaq100.startNyDate != null) {
                                cur.benchNasdaq100.startPrice = bestEffortCloseOnOrBefore(closeByDate, cur.benchNasdaq100.startNyDate);
                            }
                            Double live = livePriceByTicker.get(t);
                            cur.benchNasdaq100.lastPrice = (live != null) ? live : bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                            if (cur.benchNasdaq100.startPrice == null) {
                                cur.benchNasdaq100.startPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                            }
                        }
                        if (cur.benchSp500 != null && cur.benchSp500.ticker != null) {
                            cur.benchSp500.lastNyDate = lastNyDate;
                            String t = cur.benchSp500.ticker.trim().toUpperCase();
                            Map<String, Double> closeByDate = loadDailyCloseByDateCached(t);
                            if (cur.benchSp500.startPrice == null && cur.benchSp500.startNyDate != null) {
                                cur.benchSp500.startPrice = bestEffortCloseOnOrBefore(closeByDate, cur.benchSp500.startNyDate);
                            }
                            Double live = livePriceByTicker.get(t);
                            cur.benchSp500.lastPrice = (live != null) ? live : bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                            if (cur.benchSp500.startPrice == null) {
                                cur.benchSp500.startPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
                            }
                        }
                        if (toUpdate.isEmpty()) {
                            cur.lastError = "";
                        } else if (liveOk.size() >= toUpdate.size()) {
                            cur.lastError = "";
                        } else {
                            List<String> missing = new ArrayList<>();
                            for (String t : toUpdate) {
                                if (t == null) continue;
                                if (!livePriceByTicker.containsKey(t)) missing.add(t);
                            }
                            String msg = "AlphaAgent: live quotes ok " + liveOk.size() + "/" + toUpdate.size() + ". Falling back to last close for: ";
                            int cap = Math.min(4, missing.size());
                            for (int i = 0; i < cap; i++) {
                                if (i > 0) msg += ", ";
                                msg += missing.get(i);
                            }
                            if (missing.size() > cap) msg += " ...";
                            msg += " (rate limit / API key / unsupported quote)";
                            cur.lastError = msg;
                        }
                    } catch (Exception e) {
                        cur.lastError = e.getMessage();
                    }
                    cur.lastUpdatedNy = ZonedDateTime.now(NY).toString();
                    bestEffortPersistAlphaAgentPortfolioById(pid, cur);
                }
            } catch (Exception ignore) {
            }
        });
    }

    private static String nyToday() {
        return ZonedDateTime.now(NY).toLocalDate().toString();
    }

    private static void bestEffortPersistTrackingSnapshot(DailyTrackingSnapshot snap) {
        if (snap == null || snap.nyDate == null || snap.nyDate.isBlank()) return;
        try {
            Files.createDirectories(trackingDir());
            Path p = trackingFileForNyDate(snap.nyDate);
            Path tmp = trackingDir().resolve(snap.nyDate + ".json.tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snap);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {}
    }

    private static DailyTrackingSnapshot bestEffortLoadTrackingSnapshot(String nyDate) {
        if (nyDate == null || nyDate.isBlank()) return null;
        try {
            Path p = trackingFileForNyDate(nyDate);
            if (!Files.exists(p)) return null;
            return JSON.readValue(p.toFile(), DailyTrackingSnapshot.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void startAlphaAgentScheduler() {
         ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
             Thread t = new Thread(r);
             t.setDaemon(true);
             t.setName("alpha-agent-scheduler");
             return t;
         });

         long periodMs = TimeUnit.DAYS.toMillis(1);
         long d1 = computeDelayToNextNyTime(9, 45);
         long d2 = computeDelayToNextNyTime(12, 30);
         long d3 = computeDelayToNextNyTime(16, 10);

         Runnable job = () -> {
             try {
                 ZonedDateTime now = ZonedDateTime.now(NY);
                 DayOfWeek dow = now.getDayOfWeek();
                 if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
                 bestEffortUpdateAlphaAgentPortfolioNow();
             } catch (Exception ignore) {
             }
         };

         exec.scheduleAtFixedRate(job, d1, periodMs, TimeUnit.MILLISECONDS);
         exec.scheduleAtFixedRate(job, d2, periodMs, TimeUnit.MILLISECONDS);
         exec.scheduleAtFixedRate(job, d3, periodMs, TimeUnit.MILLISECONDS);
     }

    private static List<String> bestEffortLoadLastDailyTopTickers() {
        // Source of truth: finder-cache/daily-top-last-tickers.txt
        try {
            Path t = Paths.get("finder-cache").resolve("daily-top-last-tickers.txt");
            if (!Files.exists(t)) return new ArrayList<>();
            String s = Files.readString(t, StandardCharsets.UTF_8);
            if (s == null || s.isBlank()) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) {
                if (part == null) continue;
                String v = part.trim().toUpperCase();
                if (!v.isBlank()) out.add(v);
            }
            return out;
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
    }

    private static List<String> bestEffortLoadTrackedDailyTopTickers() {
        try {
            Path p = trackingTickersFile();
            if (!Files.exists(p)) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line == null ? "" : line.trim().toUpperCase();
                if (!t.isEmpty() && t.matches("[A-Z0-9.:-]{1,10}")) out.add(t);
            }
            // de-dupe while preserving order
            java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>(out);
            return new ArrayList<>(dedup);
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
    }

    private static void bestEffortPersistTrackedDailyTopTickers(List<String> tickers) {
        try {
            Files.createDirectories(Paths.get("finder-cache"));
            List<String> cleaned = new ArrayList<>();
            if (tickers != null) {
                for (String x : tickers) {
                    String t = x == null ? "" : x.trim().toUpperCase();
                    if (!t.isEmpty() && t.matches("[A-Z0-9.:-]{1,10}")) cleaned.add(t);
                }
            }
            // de-dupe while preserving order
            java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>(cleaned);
            cleaned = new ArrayList<>(dedup);

            Path p = trackingTickersFile();
            Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
            Files.write(tmp, (String.join("\n", cleaned) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {
        }
    }

    private static void ensureTrackingBootstrappedIfEmpty() {
        try {
            List<String> existing = listTrackingFilesNewestFirst(1);
            if (existing != null && !existing.isEmpty()) return;

            // No tracking snapshots yet -> bootstrap from tracked list (if exists), else from last daily top tickers
            List<String> tickers = bestEffortLoadTrackedDailyTopTickers();
            if (tickers.isEmpty()) tickers = bestEffortLoadLastDailyTopTickers();
            if (tickers.isEmpty()) return;

            String nyDate = nyToday();
            DailyTrackingSnapshot snap = new DailyTrackingSnapshot();
            snap.nyDate = nyDate;
            snap.createdAtNy = ZonedDateTime.now(NY).toString();
            snap.rows = new ArrayList<>();
            int max = Math.min(DAILY_TOP_PICK_COUNT, tickers.size());
            for (int i = 0; i < max; i++) {
                String t = tickers.get(i);
                if (t == null || t.isBlank()) continue;
                snap.rows.add(new DailyTrackingRow(t.trim().toUpperCase()));
            }
            bestEffortRefreshTrackingPricesForSnapshot(snap);
            bestEffortPersistTrackingSnapshot(snap);
        } catch (Exception ignore) {
        }
    }

    private static List<String> listTrackingFilesNewestFirst(int maxDays) {
        try {
            Files.createDirectories(trackingDir());
            List<String> names = new ArrayList<>();
            try (var s = Files.list(trackingDir())) {
                s.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".json"))
                        .forEach(names::add);
            }
            names.sort(Comparator.reverseOrder());
            if (maxDays > 0 && names.size() > maxDays) {
                return new ArrayList<>(names.subList(0, maxDays));
            }
            return names;
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String part : rawQuery.split("&")) {
            if (part == null || part.isBlank()) continue;
            String[] kv = part.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            out.put(k, v);
        }
        return out;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            return "";
        }
    }

    private static String intradayIntervalForCurrentEntitlement() {
        String ent = System.getenv("ALPHAVANTAGE_ENTITLEMENT");
        if (ent == null || ent.isBlank()) {
            ent = System.getenv("ALPHA_VANTAGE_ENTITLEMENT");
        }
        // delayed = 15-min delayed data (5min bars); realtime/blank = real-time (1min bars)
        if (ent != null && ent.equalsIgnoreCase("delayed")) {
            return "5min";
        }
        return "1min";
    }

    private static final class IntradayAlertState {
        volatile boolean running;
        volatile String symbol;
        volatile String lastError;
        volatile ZonedDateTime lastCheckNy;
        volatile ZonedDateTime startedAtNy;
        volatile String lastBarTs;
        volatile Double lastPrice;
        volatile Long lastVolume;
        volatile String lastSignal;
        volatile String lastNotifiedSignal;
        volatile String lastNotifiedBarTs;
        final List<String> lastBars = new ArrayList<>();
        final List<String> history = new ArrayList<>();

        volatile ZonedDateTime lastQuoteAtNy;
        volatile Double lastQuotePrice;
        volatile String lastQuoteError;

        volatile String avLastRefreshed;
        volatile String avTimeZone;
        volatile String avNote;
        volatile String avInformation;
        volatile String avErrorMessage;

        // Breakout Scanner fields
        volatile IntradayScanner.ScanResult scanResult;
        volatile Double avgDailyVolume;
        volatile Double previousDayHigh;
        volatile Double previousDayClose;
        volatile Double spyChangePct;
        volatile Double openPrice;
    }

    private static Double extractGlobalQuotePrice(JsonNode root) {
        try {
            if (root == null) return null;
            // Handle both "Global Quote" and "Global Quote - DATA DELAYED BY 15 MINUTES"
            JsonNode q = root.path("Global Quote");
            if (q == null || q.isMissingNode()) {
                q = root.path("Global Quote - DATA DELAYED BY 15 MINUTES");
            }
            if (q == null || q.isMissingNode()) return null;
            String p = q.path("05. price").asText("");
            return parseDoubleOrNull(p);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Double bestEffortFetchLiveOrLastClosePrice(String ticker, MonitoringAlphaVantageClient av) {
        String t = ticker == null ? null : ticker.trim().toUpperCase();
        if (t == null || t.isBlank()) return null;
        try {
            if (av != null) {
                try {
                    JsonNode q = av.globalQuote(t);
                    Double price = extractGlobalQuotePrice(q);
                    if (price != null && Double.isFinite(price)) return price;
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        try {
            Map<String, Double> closeByDate = loadDailyCloseByDateCached(t);
            return bestEffortCloseOnOrBefore(closeByDate, nyToday());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static AlphaAgentPortfolio buildEmptyUserManagedAlphaAgentPortfolio(int trackingDays) {
        int days = trackingDays > 0 ? trackingDays : ALPHA_AGENT_DEFAULT_TRACKING_DAYS;
        if (days < 2) days = 2;
        if (days > 60) days = 60;
        String startNyDate = nyToday();

        AlphaAgentPortfolio pf = new AlphaAgentPortfolio();
        pf.userManaged = true;
        pf.createdAtNy = ZonedDateTime.now(NY).toString();
        pf.startNyDate = startNyDate;
        pf.trackingDays = days;
        pf.positions = new ArrayList<>();
        pf.benchNasdaq100 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_NASDAQ100, startNyDate, startNyDate);
        pf.benchSp500 = buildAlphaAgentPosition(ALPHA_AGENT_BENCH_SP500, startNyDate, startNyDate);
        pf.lastError = "";
        return pf;
    }

    private static final Object intradayLock = new Object();
    private static final IntradayAlertState intradayState = new IntradayAlertState();
    private static ScheduledExecutorService intradayExec;

    private static final int DAILY_GREEN_MAX_ROWS = 300;
    private static final ExecutorService dailyGreenExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("daily-green-recos-worker");
        return t;
    });

    private static final Object dailyGreenLock = new Object();
    private static final Path dailyGreenPath = Paths.get("finder-cache", "daily-green-recos.json");
    private static final DailyGreenRecommendations dailyGreenState = new DailyGreenRecommendations();

    private static void logDailyGreen(String msg) {
        try {
            System.out.println("[daily-green] " + msg);
        } catch (Exception ignore) {
        }
    }

    private static final class DailyGreenTicker {
        public String ticker;
        public Integer finalScore;
        public String recommendation;
        public String lastUpdatedNy;
    }

    private static final class DailyGreenRecommendations {
        public boolean running;
        public String currentTicker;
        public Integer processed;
        public Integer greenCount;
        public String lastStartedNy;
        public String lastFinishedNy;
        public String lastError;
        public List<DailyGreenTicker> green = new ArrayList<>();
    }

    private static class DailyPicksCache {
        private LocalDate date;
        private String text;
        private List<String> tickers;
        private String lastError;
    }

    private static final Object dailyPicksLock = new Object();
    private static final DailyPicksCache dailyPicksCache = new DailyPicksCache();

    private static String htmlPage(String body) {
        String base = "" +
                "<!doctype html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "<meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>AlphaPoint AI</title>" +
                "<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;margin:0;min-height:100vh;" +
                "background:linear-gradient(135deg,#0f172a 0%,#0b132b 50%,#1b2a41 100%);color:#e5e7eb;}\n" +
                "*{box-sizing:border-box;}\n" +
                ".container{max-width:1180px;margin:0 auto;padding:32px;}\n" +
                "h1{margin:0 0 16px 0;font-size:28px;letter-spacing:.3px;color:#ffffff;}\n" +
                ".subtitle{color:#9ca3af;margin-bottom:24px;}\n" +
                "form{margin-bottom:16px;}\n" +
                "input[type=text]{width:260px;background:#0b1220;border:1px solid #1f2a44;color:#e5e7eb;border-radius:8px;padding:10px 12px;outline:none;}\n" +
                "input[type=text]::placeholder{color:#6b7280;}\n" +
                "button{font-size:15px;padding:10px 14px;border-radius:8px;border:1px solid #2a3b66;background:#1e3a8a;color:#e5e7eb;cursor:pointer;transition:all .15s ease;}\n" +
                "button:hover{background:#2b50b3;border-color:#365a9f;}\n" +
                "pre{background:#0b1220;color:#b6f399;padding:14px;border-radius:10px;white-space:pre-wrap;word-break:break-word;border:1px solid #1f2a44;direction:rtl;text-align:right;unicode-bidi:embed;}\n" +
                ".card{background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.08);backdrop-filter:saturate(140%) blur(4px);border-radius:14px;padding:18px 18px;margin-bottom:18px;box-shadow:0 10px 20px rgba(0,0,0,0.25);}\n" +
                ".title{font-weight:600;margin-bottom:10px;color:#f3f4f6;}\n" +
                "a{color:#93c5fd;text-decoration:none;}a:hover{text-decoration:underline;}\n" +
                ".loading-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.55);display:none;align-items:center;justify-content:center;z-index:9999;}\n" +
                ".loading-card{background:#0b1220;border:1px solid #1f2a44;border-radius:12px;padding:20px 24px;color:#e5e7eb;box-shadow:0 10px 24px rgba(0,0,0,.35);text-align:center;min-width:260px;}\n" +
                ".loading-emoji{font-size:28px;margin-bottom:8px;display:block;}\n" +
                ".model-badge{display:inline-block;margin-left:8px;padding:2px 8px;border-radius:999px;font-size:11px;letter-spacing:.6px;text-transform:uppercase;border:1px solid rgba(148,163,184,0.35);background:#0b1220;color:#e5e7eb;}\n" +
                ".model-badge.tech{border-color:#1d4ed8;color:#bfdbfe;}\n" +
                ".model-badge.fund{border-color:#047857;color:#bbf7d0;}\n" +
                ".model-badge.risk{border-color:#b45309;color:#fde68a;}\n" +
                ".model-badge.sent{border-color:#7c3aed;color:#ddd6fe;}\n" +
                ".indicator-panel{direction:rtl;text-align:right;font-size:13px;}\n" +
                ".indicator-item{margin-bottom:10px;padding-bottom:8px;border-bottom:1px solid rgba(148,163,184,0.25);}\n" +
                ".indicator-item:last-child{border-bottom:none;padding-bottom:0;}\n" +
                ".indicator-label{font-weight:600;color:#e5e7eb;}\n" +
                ".indicator-value{color:#93c5fd;display:inline-block;margin-left:6px;}\n" +
                ".indicator-text{color:#cbd5e1;margin-top:4px;}\n" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div id=\"loading\" class=\"loading-overlay\">" +
                "  <div class=\"loading-card\">" +
                "    <span class=\"loading-emoji\">⏳</span>" +
                "    <div>LOADING ...</div>" +
                "    <div style=\"margin-top:4px;color:#9ca3af;\">Processing request...</div>" +
                "</div>" +
                "</div>" +
                "<div class=\"container\">" +
                "<h1>AlphaPoint AI</h1>" +
                "<div style=\"margin-bottom:16px;\">" +
                "<a href=\"/\">Home</a> · " +
                "<a href=\"/dashboard\">Stock Quick Analysis</a> · " +
                "<a href=\"/about\">About</a> · " +
                "<a href=\"/backtest\">📊 BackTest</a> · " +
                "<a href=\"/agent-backtest\">🕰️ Agent Backtest</a> · " +
                "<a href=\"/aitool\">🤖 AITool</a> · " +
                "<a href=\"/monitoring\">Monitoring Stocks - History</a> · " +
                                "<a href=\"/finder\">FINDER</a> · " +
                "<a href=\"/settings\">⚙️ Settings</a> · " +
                "<a href=\"/history-usage\">📊 API Usage</a>" +
                "</div>" +
                (body == null ? "" : body) +
                "</div>" +
                "<script>(function(){function show(){var el=document.getElementById('loading');if(el){el.style.display='flex';}};var forms=document.querySelectorAll('form');forms.forEach(function(f){f.addEventListener('submit',function(){try{var t=(f.getAttribute('target')||'').toLowerCase();if(t==='_blank') return;}catch(e){} show();});});})();</script>" +
                "</body></html>";
        return base;
    }

    private static String modelBadge(String type) {
        if (type == null) type = "";
        String t = type.trim().toUpperCase();
        String cls;
        if ("TECHNICAL".equals(t)) cls = "tech";
        else if ("FUNDAMENTAL".equals(t)) cls = "fund";
        else if ("RISK".equals(t)) cls = "risk";
        else if ("SENTIMENT".equals(t)) cls = "sent";
        else cls = "";
        return "<span class=\"model-badge " + cls + "\">" + escapeHtml(t) + "</span>";
    }

    private static String modelsUsedNamesOnlyHtml() {
        return "<div style='color:#9ca3af;margin-top:10px;'>" +
                "Models used: " +
                "Piotroski F-Score, Altman Z-Score, Beneish M-Score, Sloan Ratio, Cash Conversion Cycle (CCC), Value Creation (ROIC vs WACC), Quality &amp; Profitability, Growth, Valuation Mix, " +
                "SMA, RSI, MACD, Stochastic Oscillator, Bollinger Bands, ADX, ATR, CMF, Pivot Points, Fibonacci Retracement, DCF, PEG" +
                "</div>";
    }

    // Best-effort summarization via AI backends (prefer free local):
    // 1) Local Ollama at http://localhost:11434 (model: llama3.2)
    // 2) OpenAI (if OPENAI_API_KEY is set), as a fallback
    private static String summarizeWithOpenAI(String analysis, String symbol) {
        try {
            if (analysis == null || analysis.isBlank()) return null;
            String input = analysis;
            if (input.length() > 9000) input = input.substring(input.length() - 9000);
            String prompt = "Please summarize the following report for stock '"+symbol+"' in 5–8 concise bullet points in English. Focus on: trend & momentum, technical signals (RSI/MACD/ADX if present), risk (ATR/Max Drawdown), and fundamentals (PEG/PB/quality). Do not provide investment advice; describe and analyze only.\n\n" + input;

            // Try local Ollama first (free local tool)
            String ollama = summarizeWithOllama(prompt);
            if (ollama != null && !ollama.isEmpty()) return ollama;

            // Fall back to OpenAI if key exists
            String key = System.getenv("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                String body = "{\n"+
                        "\"model\":\"gpt-4o-mini\",\n"+
                        "\"messages\":[{"+
                        "\"role\":\"system\",\"content\":\"You are a helpful financial analysis summarizer.\"},{"+
                        "\"role\":\"user\",\"content\":" + jsonString(prompt) + "}],\n"+
                        "\"temperature\":0.3\n"+
                        "}";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    ObjectMapper om = new ObjectMapper();
                    JsonNode root = om.readTree(resp.body());
                    JsonNode msg = root.path("choices").isArray() && root.path("choices").size()>0
                            ? root.path("choices").get(0).path("message").path("content") : null;
                    if (msg != null && msg.isTextual()) return msg.asText();
                }
                // if OpenAI failed, continue to Ollama
            }

            return "(AI summary unavailable: install Ollama and run: 'ollama run llama3.2', or set OPENAI_API_KEY)";
        } catch (Exception e) {
            return "(AI summary error: " + e.getMessage() + ")";
        }
    }

    private static String telegramTestMessage() {
        ZonedDateTime now = ZonedDateTime.now(NY);
        return "AlphaPoint AI test message (" + now.toString() + ")";
    }

    private static boolean isNyseRegularHoursNow() {
        ZonedDateTime now = ZonedDateTime.now(NY);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        LocalTime open = LocalTime.of(9, 30);
        LocalTime close = LocalTime.of(16, 0);
        return (!t.isBefore(open)) && (t.isBefore(close));
    }

    private static Map<String, Double> loadDailyCloseByDateCached(String symbol) {
        // No caching - always fetch fresh data from AlphaVantage
        try {
            if (symbol == null || symbol.isBlank()) return Map.of();
            String sym = symbol.trim().toUpperCase();
            try { DataFetcher.setTicker(sym); } catch (Exception ignore) {}
            String json = DataFetcher.fetchStockData();
            return PriceJsonParser.extractCloseByDate(json);
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private static Double realizedReturnPct(Map<String, Double> closeByDate, java.time.LocalDate baseDate, int tradingDaysForward) {
        try {
            if (closeByDate == null || closeByDate.isEmpty() || baseDate == null || tradingDaysForward <= 0) return null;
            List<String> dates = new ArrayList<>(closeByDate.keySet());
            if (dates.isEmpty()) return null;
            dates.sort(String::compareTo);
            String baseKey = baseDate.toString();

            int baseIdx = dates.indexOf(baseKey);
            if (baseIdx < 0) {
                // Weekend/holiday: choose the nearest trading day ON or BEFORE baseDate
                int lo = 0, hi = dates.size() - 1;
                int best = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    String d = dates.get(mid);
                    int cmp = d.compareTo(baseKey);
                    if (cmp <= 0) {
                        best = mid;
                        lo = mid + 1;
                    } else {
                        hi = mid - 1;
                    }
                }
                baseIdx = best;
            }
            if (baseIdx < 0) return null;
            int targetIdx = baseIdx + tradingDaysForward;
            if (targetIdx >= dates.size()) return null;
            Double baseClose = closeByDate.get(dates.get(baseIdx));
            Double targetClose = closeByDate.get(dates.get(targetIdx));
            if (baseClose == null || targetClose == null || baseClose == 0.0) return null;
            return (targetClose - baseClose) / baseClose * 100.0;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Double currentReturnPctBackwards(Map<String, Double> closeByDate, int tradingDaysBack) {
        try {
            if (closeByDate == null || closeByDate.isEmpty() || tradingDaysBack <= 0) return null;
            List<String> dates = new ArrayList<>(closeByDate.keySet());
            if (dates.isEmpty()) return null;
            dates.sort(String::compareTo);
            int latestIdx = dates.size() - 1;
            int baseIdx = latestIdx - tradingDaysBack;
            if (baseIdx < 0) return null;
            Double baseClose = closeByDate.get(dates.get(baseIdx));
            Double latestClose = closeByDate.get(dates.get(latestIdx));
            if (baseClose == null || latestClose == null || baseClose == 0.0) return null;
            return (latestClose - baseClose) / baseClose * 100.0;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String computeIntradaySignal(JsonNode intraday, IntradayAlertState st) {
        if (intraday == null) return "HOLD";

        try {
            JsonNode meta = intraday.path("Meta Data");
            st.avLastRefreshed = meta.path("3. Last Refreshed").asText("");
            st.avTimeZone = meta.path("6. Time Zone").asText("");
        } catch (Exception ignore) {
            st.avLastRefreshed = "";
            st.avTimeZone = "";
        }
        try {
            st.avNote = intraday.path("Note").asText("");
        } catch (Exception ignore) {
            st.avNote = "";
        }
        try {
            st.avInformation = intraday.path("Information").asText("");
        } catch (Exception ignore) {
            st.avInformation = "";
        }
        try {
            st.avErrorMessage = intraday.path("Error Message").asText("");
        } catch (Exception ignore) {
            st.avErrorMessage = "";
        }

        JsonNode series = intraday.path("Time Series (5min)");
        if (series == null || series.isMissingNode() || !series.isObject()) {
            Iterator<String> it = intraday.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null && k.startsWith("Time Series")) {
                    series = intraday.path(k);
                    break;
                }
            }
        }
        if (series == null || series.isMissingNode() || !series.isObject()) return "HOLD";

        List<String> keys = new ArrayList<>();
        Iterator<String> fn = series.fieldNames();
        while (fn.hasNext()) keys.add(fn.next());
        if (keys.isEmpty()) return "HOLD";
        keys.sort(Comparator.reverseOrder());

        try {
            st.lastBars.clear();
            int limit = Math.min(10, keys.size());
            for (int i = 0; i < limit; i++) {
                String ts = keys.get(i);
                JsonNode bar = series.path(ts);
                Double c = parseDoubleOrNull(bar.path("4. close").asText(""));
                String vs = bar.path("5. volume").asText("");
                String line = ts + " | close=" + (c == null ? "N/A" : String.format("%.4f", c)) + " | vol=" + (vs == null || vs.isBlank() ? "N/A" : vs.trim());
                st.lastBars.add(line);
            }
        } catch (Exception ignore) {}

        String latestTs = keys.get(0);
        String prevTs = keys.size() > 1 ? keys.get(1) : latestTs;
        JsonNode latest = series.path(latestTs);
        JsonNode prev = series.path(prevTs);

        Double close = parseDoubleOrNull(latest.path("4. close").asText(""));
        Double prevClose = parseDoubleOrNull(prev.path("4. close").asText(""));
        Long vol = null;
        try {
            String vs = latest.path("5. volume").asText("");
            if (vs != null && !vs.isBlank()) vol = Long.parseLong(vs.trim());
        } catch (Exception ignore) {}

        st.lastBarTs = latestTs;
        st.lastPrice = close;
        st.lastVolume = vol;

        if (close == null || prevClose == null || prevClose == 0.0) return "HOLD";

        // Build IntradayBar list for the scanner
        List<IntradayScanner.IntradayBar> bars = new ArrayList<>();
        for (String ts : keys) {
            try {
                JsonNode bar = series.path(ts);
                Double openVal = parseDoubleOrNull(bar.path("1. open").asText(""));
                Double highVal = parseDoubleOrNull(bar.path("2. high").asText(""));
                Double lowVal = parseDoubleOrNull(bar.path("3. low").asText(""));
                Double closeVal = parseDoubleOrNull(bar.path("4. close").asText(""));
                String vs = bar.path("5. volume").asText("");
                if (openVal != null && highVal != null && lowVal != null && closeVal != null) {
                    IntradayScanner.IntradayBar ib = new IntradayScanner.IntradayBar();
                    ib.timestamp = ts;
                    ib.open = openVal;
                    ib.high = highVal;
                    ib.low = lowVal;
                    ib.close = closeVal;
                    ib.volume = (vs != null && !vs.isBlank()) ? Long.parseLong(vs.trim()) : 0;
                    bars.add(ib);
                }
            } catch (Exception ignore) {}
        }

        // Get open price from first bar of the day
        if (!bars.isEmpty()) {
            st.openPrice = bars.get(bars.size() - 1).open;
        }

        // Use IntradayScanner for breakout detection
        double avgDailyVol = st.avgDailyVolume != null ? st.avgDailyVolume : 0;
        double prevDayHigh = st.previousDayHigh != null ? st.previousDayHigh : 0;
        double prevDayClose = st.previousDayClose != null ? st.previousDayClose : prevClose;
        double spyChange = st.spyChangePct != null ? st.spyChangePct : 0;

        // If we don't have avg volume, estimate from bars
        if (avgDailyVol <= 0 && !bars.isEmpty()) {
            long totalVol = 0;
            for (IntradayScanner.IntradayBar bar : bars) {
                totalVol += bar.volume;
            }
            // Estimate daily volume based on bars we have
            double hoursOfData = bars.size() * (5.0 / 60.0);
            if (hoursOfData > 0) {
                avgDailyVol = totalVol * (6.5 / hoursOfData);
            }
        }

        IntradayScanner.ScanResult scanResult = IntradayScanner.scan(
            st.symbol, bars, avgDailyVol, prevDayHigh, prevDayClose, spyChange
        );
        st.scanResult = scanResult;

        // Map scanner signal to legacy signals (BUY/SELL/HOLD)
        String signal = scanResult.signal;
        if ("BREAKOUT_BUY".equals(signal) || "MOMENTUM_BUY".equals(signal)) {
            return "BUY";
        } else if ("MISSED".equals(signal) || "OVERBOUGHT".equals(signal)) {
            return "HOLD"; // Don't chase
        }

        // Fallback to original simple logic for SELL detection
        double pct = (close - prevClose) / prevClose * 100.0;
        int n = Math.min(12, keys.size());
        double sumVol = 0.0;
        int cntVol = 0;
        for (int i = 1; i < n; i++) {
            JsonNode bar = series.path(keys.get(i));
            String vs = bar.path("5. volume").asText("");
            try {
                if (vs != null && !vs.isBlank()) {
                    sumVol += Double.parseDouble(vs.trim());
                    cntVol++;
                }
            } catch (Exception ignore) {}
        }
        double avgVol = cntVol == 0 ? 0.0 : (sumVol / cntVol);
        boolean volSpike = vol != null && avgVol > 0.0 && (vol.doubleValue() >= (2.0 * avgVol));

        if (volSpike && pct <= -0.50) return "SELL";
        return "HOLD";
    }

    private static String renderIntradayBarsSvg(List<String> lastBars) {
        if (lastBars == null || lastBars.isEmpty()) return "";

        class P {
            String ts;
            Double close;
            Long vol;
        }

        List<P> pts = new ArrayList<>();
        for (String line : lastBars) {
            if (line == null) continue;
            // Expected: "YYYY-MM-DD HH:MM:SS | close=164.5500 | vol=2"
            try {
                int i1 = line.indexOf(" | close=");
                int i2 = line.indexOf(" | vol=");
                if (i1 <= 0 || i2 <= i1) continue;
                String ts = line.substring(0, i1).trim();
                String cs = line.substring(i1 + " | close=".length(), i2).trim();
                String vs = line.substring(i2 + " | vol=".length()).trim();

                P p = new P();
                p.ts = ts;
                p.close = (cs.equalsIgnoreCase("N/A") ? null : parseDoubleOrNull(cs));
                try {
                    p.vol = (vs.equalsIgnoreCase("N/A") ? null : Long.parseLong(vs));
                } catch (Exception ignore) {
                    p.vol = null;
                }
                pts.add(p);
            } catch (Exception ignore) {
                // ignore malformed line
            }
        }

        // lastBars is latest-first; chart wants left-to-right oldest->latest
        Collections.reverse(pts);
        pts.removeIf(p -> p.close == null);
        if (pts.size() < 2) return "";

        double minC = Double.POSITIVE_INFINITY;
        double maxC = Double.NEGATIVE_INFINITY;
        long maxV = 0L;
        for (P p : pts) {
            if (p.close != null) {
                minC = Math.min(minC, p.close);
                maxC = Math.max(maxC, p.close);
            }
            if (p.vol != null) {
                maxV = Math.max(maxV, p.vol);
            }
        }
        if (!Double.isFinite(minC) || !Double.isFinite(maxC) || minC == maxC) {
            minC = minC - 1.0;
            maxC = maxC + 1.0;
        }

        int w = 860;
        int h = 280;
        int pad = 16;
        int plotW = w - pad * 2;
        int plotH = h - pad * 2;
        int volH = (int) Math.round(plotH * 0.30);
        int priceH = plotH - volH - 12;
        int priceY = pad;
        int volY = pad + priceH + 12;

        int n = pts.size();
        double dx = (n <= 1) ? plotW : (plotW / (double) (n - 1));

        StringBuilder pricePts = new StringBuilder();
        StringBuilder volRects = new StringBuilder();
        for (int i = 0; i < n; i++) {
            P p = pts.get(i);
            double x = pad + i * dx;
            double y = priceY + (maxC - p.close) / (maxC - minC) * priceH;
            if (i > 0) pricePts.append(" ");
            pricePts.append(String.format(java.util.Locale.US, "%.2f,%.2f", x, y));

            if (p.vol != null && maxV > 0) {
                double vh = (p.vol / (double) maxV) * (volH - 2);
                double ry = volY + (volH - vh);
                double rw = Math.max(2.0, dx * 0.55);
                double rx = x - rw / 2.0;
                volRects.append("<rect x='").append(String.format(java.util.Locale.US, "%.2f", rx))
                        .append("' y='").append(String.format(java.util.Locale.US, "%.2f", ry))
                        .append("' width='").append(String.format(java.util.Locale.US, "%.2f", rw))
                        .append("' height='").append(String.format(java.util.Locale.US, "%.2f", vh))
                        .append("' fill='#1f2a44' />");
            }
        }

        String firstTs = pts.get(0).ts == null ? "" : pts.get(0).ts;
        String lastTs = pts.get(n - 1).ts == null ? "" : pts.get(n - 1).ts;

        return "<svg xmlns='http://www.w3.org/2000/svg' width='" + w + "' height='" + h + "' viewBox='0 0 " + w + " " + h + "'>" +
                "<rect x='0' y='0' width='100%' height='100%' fill='#0b1220'/>" +
                "<g stroke='#1f2a44' stroke-width='1'>" +
                "<rect x='" + pad + "' y='" + pad + "' width='" + plotW + "' height='" + plotH + "' fill='none'/>" +
                "<line x1='" + pad + "' y1='" + (volY - 6) + "' x2='" + (pad + plotW) + "' y2='" + (volY - 6) + "'/>" +
                "</g>" +
                "<text x='" + (pad + 2) + "' y='" + (pad + 14) + "' fill='#9ca3af' font-size='12'>close (line) + volume (bars)</text>" +
                "<text x='" + (pad + 2) + "' y='" + (h - 8) + "' fill='#9ca3af' font-size='12'>" + escapeHtml(firstTs) + " → " + escapeHtml(lastTs) + "</text>" +
                "<g>" + volRects + "</g>" +
                "<polyline fill='none' stroke='#93c5fd' stroke-width='2' points='" + pricePts + "' />" +
                "</svg>";
    }

    private static boolean sendTelegram(String text) {
        // Replaced with Discord webhook - keeping for backward compatibility
        return sendDiscord(text);
    }

    private static final String DISCORD_WEBHOOK_URL = System.getenv("DAILY_SIM_DISCORD_WEBHOOK_URL");

    private static boolean sendDiscord(String text) {
        try {
            if (DISCORD_WEBHOOK_URL == null || DISCORD_WEBHOOK_URL.isBlank()) return false;
            if (text == null || text.isBlank()) return false;
            // Discord webhook expects JSON with "content" field
            String jsonBody = "{\"content\": " + escapeJsonString(text) + "}";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DISCORD_WEBHOOK_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Discord] Sent notification, status: " + resp.statusCode());
            return resp.statusCode() == 200 || resp.statusCode() == 204;
        } catch (Exception e) {
            System.err.println("[Discord] Failed to send: " + e.getMessage());
            return false;
        }
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String buildAnalystProxyAlphaVantageCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String sym = symbol.trim().toUpperCase();

            // No caching - always fetch fresh data from AlphaVantage
            ObjectMapper om = new ObjectMapper();
            JsonNode combined;
            MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
            JsonNode overview = null;
            JsonNode news = null;
            try { overview = av.overview(sym); } catch (Exception ignore) {}
            try { news = av.newsSentiment(sym); } catch (Exception ignore) {}
            String combinedJson = "{\n\"overview\": " + (overview == null ? "{}" : overview.toString()) + ",\n\"news\": " + (news == null ? "{}" : news.toString()) + "\n}";
            combined = om.readTree(combinedJson);

            JsonNode ov = combined.path("overview");
            String name = ov.path("Name").asText("");
            String sector = ov.path("Sector").asText("");
            String peStr = ov.path("PERatio").asText("");
            String pbStr = ov.path("PriceToBookRatio").asText("");
            String divStr = ov.path("DividendYield").asText("");

            Double pe = parseDoubleOrNull(peStr);
            Double pb = parseDoubleOrNull(pbStr);
            Double div = parseDoubleOrNull(divStr);

            Double newsScore = null;
            try {
                JsonNode feed = combined.path("news").path("feed");
                if (feed != null && feed.isArray()) {
                    double sum = 0.0;
                    int cnt = 0;
                    for (JsonNode it : feed) {
                        JsonNode s = it.get("overall_sentiment_score");
                        if (s != null && s.isTextual()) {
                            Double v = parseDoubleOrNull(s.asText());
                            if (v != null) { sum += v; cnt++; }
                        }
                    }
                    if (cnt > 0) newsScore = sum / cnt;
                }
            } catch (Exception ignore) {}

            int score = 0;
            if (pe != null) {
                if (pe > 0 && pe <= 25) score += 1;
                else if (pe > 40) score -= 1;
            }
            if (pb != null) {
                if (pb > 0 && pb <= 6) score += 1;
                else if (pb > 12) score -= 1;
            }
            if (div != null) {
                if (div >= 0.01) score += 1;
            }
            if (newsScore != null) {
                if (newsScore >= 0.15) score += 1;
                else if (newsScore <= -0.15) score -= 1;
            }

            String label;
            String color;
            if (score >= 2) { label = "GREEN"; color = "#22c55e"; }
            else if (score <= -1) { label = "RED"; color = "#fca5a5"; }
            else { label = "NEUTRAL"; color = "#93c5fd"; }

            StringBuilder sb = new StringBuilder();
            sb.append("<div class='card'><div class='title'>Analyst Proxy (Alpha Vantage)</div>");
            if (!name.isEmpty()) sb.append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(escapeHtml(name)).append("</div>");
            if (!sector.isEmpty()) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Sector: ").append(escapeHtml(sector)).append("</div>");
            sb.append("<div style='margin-bottom:10px;'>Overall: <span style='color:").append(color).append(";font-weight:700;'>").append(label).append("</span></div>");
            sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:6px;'>")
                    .append(badge("PE", pe == null ? 0 : (int)Math.round(pe)))
                    .append(badge("P/B", pb == null ? 0 : (int)Math.round(pb)))
                    .append(badge("Div%", div == null ? 0 : (int)Math.round(div * 100.0)))
                    .append(badge("News", newsScore == null ? 0 : (int)Math.round(newsScore * 100.0)))
                    .append("</div>");
            sb.append("<div style='color:#6b7280;margin-top:8px;line-height:1.45;'>");
            sb.append("<div style='margin-bottom:6px;'><b>What this means</b>: this is a heuristic proxy built from Alpha Vantage \"OVERVIEW\" fundamentals and \"NEWS_SENTIMENT\". It is <b>not</b> real analyst consensus.</div>");

            sb.append("<div style='margin-top:10px;color:#9ca3af;font-weight:600;'>Indicator explanations</div>");
            sb.append("<div style='margin-top:6px;'>");
            sb.append("<div><b>P/E</b> (Price/Earnings): how much the market pays per $1 of earnings. Lower can indicate cheaper valuation, but very low can also mean weak growth. Here: +1 if P/E ≤ 25, -1 if P/E &gt; 40.</div>");
            sb.append("<div style='margin-top:6px;'><b>P/B</b> (Price/Book): price relative to balance-sheet book value. Lower can be cheaper; very high can mean premium/overvaluation. Here: +1 if P/B ≤ 6, -1 if P/B &gt; 12.</div>");
            sb.append("<div style='margin-top:6px;'><b>Div%</b> (Dividend yield): annual dividend / price. Indicates shareholder returns and often maturity/stability. Here: +1 if yield ≥ 1%.</div>");
            sb.append("<div style='margin-top:6px;'><b>News</b> (avg sentiment): average of Alpha Vantage news \"overall_sentiment_score\" (range roughly -1..+1). Positive implies optimistic tone; negative implies pessimistic. Here: +1 if score ≥ 0.15, -1 if score ≤ -0.15. Displayed as ~score×100.</div>");
            sb.append("</div>");

            sb.append("<div style='margin-top:10px;color:#9ca3af;font-weight:600;'>Overall label logic</div>");
            sb.append("<div style='margin-top:6px;'>We sum the points from the rules above. Total score=").append(score).append(". ");
            sb.append("<b>GREEN</b> if score ≥ 2, <b>RED</b> if score ≤ -1, otherwise <b>NEUTRAL</b>." );
            sb.append("</div>");
            sb.append("</div>");
            sb.append("</div>");
            return sb.toString();
        } catch (Exception e) {
            return "<div class='card'><div class='title'>Analyst Proxy (Alpha Vantage)</div><div style='color:#fca5a5'>(Error: "+escapeHtml(e.getMessage())+")</div></div>";
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("None")) return null;
        try {
            return Double.parseDouble(t);
        } catch (Exception ignore) {
            return null;
        }
    }

    // Call local Ollama server (http://localhost:11434) to summarize text using a small local model
    private static String summarizeWithOllama(String prompt) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = "{\n"+
                    " \"model\": \"llama3.2\",\n"+
                    " \"prompt\": " + jsonString(prompt) + ",\n"+
                    " \"stream\": false\n"+
                    "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(resp.body());
            JsonNode out = root.get("response");
            if (out != null && out.isTextual()) return out.asText();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    // -------- Analyst data (Finnhub) - no caching, always fetch fresh ---------
    private static String buildAnalystCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String key = System.getenv("FINNHUB_API_KEY");
            if (key == null || key.isBlank()) {
                return "<div class='card'><div class='title'>Analyst Consensus</div><div style='color:#9ca3af'>(Set FINNHUB_API_KEY to enable analyst data)</div></div>";
            }
            // No caching - always fetch fresh data from Finnhub
            ObjectMapper om = new ObjectMapper();
            JsonNode combined = null;
            HttpClient client = HttpClient.newHttpClient();
            String recoUrl = "https://finnhub.io/api/v1/stock/recommendation?symbol="+symbol+"&token="+key;
            String ptUrl = "https://finnhub.io/api/v1/stock/price-target?symbol="+symbol+"&token="+key;
            HttpRequest r1 = HttpRequest.newBuilder().uri(URI.create(recoUrl)).build();
            HttpRequest r2 = HttpRequest.newBuilder().uri(URI.create(ptUrl)).build();
            HttpResponse<String> h1 = client.send(r1, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> h2 = client.send(r2, HttpResponse.BodyHandlers.ofString());
            String body1 = (h1.statusCode()==200? h1.body(): "[]");
            String body2 = (h2.statusCode()==200? h2.body(): "{}");
            // Build combined JSON string
            String combinedJson = "{\n\"recommendation\": "+body1+",\n\"priceTarget\": "+body2+"\n}";
            combined = om.readTree(combinedJson);
            JsonNode recArr = combined.path("recommendation");
            String period = "";
            int sb=0,b=0,h=0,s=0,ss=0;
            if (recArr.isArray() && recArr.size()>0) {
                JsonNode latest = recArr.get(0); // Finnhub returns latest first
                sb = latest.path("strongBuy").asInt(0);
                b  = latest.path("buy").asInt(0);
                h  = latest.path("hold").asInt(0);
                s  = latest.path("sell").asInt(0);
                ss = latest.path("strongSell").asInt(0);
                period = latest.path("period").asText("");
            }
            JsonNode pt = combined.path("priceTarget");
            String mean = pt.path("targetMean").isMissingNode()? "": String.format("%.2f", pt.path("targetMean").asDouble());
            String median = pt.path("targetMedian").isMissingNode()? "": String.format("%.2f", pt.path("targetMedian").asDouble());
            String hi = pt.path("targetHigh").isMissingNode()? "": String.format("%.2f", pt.path("targetHigh").asDouble());
            String lo = pt.path("targetLow").isMissingNode()? "": String.format("%.2f", pt.path("targetLow").asDouble());
            String updated = pt.path("lastUpdated").asText("");

            StringBuilder sbuf = new StringBuilder();
            sbuf.append("<div class='card'><div class='title'>Analyst Consensus</div>");
            if (!period.isEmpty()) sbuf.append("<div style='color:#9ca3af;margin-bottom:6px;'>Period: ").append(escapeHtml(period)).append("</div>");
            sbuf.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:6px;'>")
                    .append(badge("Strong Buy", sb)).append(badge("Buy", b)).append(badge("Hold", h)).append(badge("Sell", s)).append(badge("Strong Sell", ss))
                    .append("</div>");
            if (!mean.isEmpty() || !median.isEmpty() || !hi.isEmpty() || !lo.isEmpty()) {
                sbuf.append("<div style='color:#9ca3af'>Price Targets: ");
                boolean first=true;
                if (!mean.isEmpty()) { sbuf.append("Mean ").append(escapeHtml(mean)); first=false; }
                if (!median.isEmpty()) { sbuf.append(first?"":" · ").append("Median ").append(escapeHtml(median)); first=false; }
                if (!hi.isEmpty()) { sbuf.append(first?"":" · ").append("High ").append(escapeHtml(hi)); first=false; }
                if (!lo.isEmpty()) { sbuf.append(first?"":" · ").append("Low ").append(escapeHtml(lo)); }
                sbuf.append("</div>");
                if (!updated.isEmpty()) sbuf.append("<div style='color:#6b7280;margin-top:4px;'>Updated: ").append(escapeHtml(updated)).append("</div>");
            }
            sbuf.append("</div>");
            return sbuf.toString();
        } catch (Exception e) {
            return "<div class='card'><div class='title'>Analyst Consensus</div><div style='color:#fca5a5'>(Error: "+escapeHtml(e.getMessage())+")</div></div>";
        }
    }

    private static String badge(String label, int value) {
        return "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>"+escapeHtml(label)+": "+value+"</span>";
    }

    private static void respondHtml(HttpExchange ex, String html, int code) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondJson(HttpExchange ex, Object body, int code) throws IOException {
        byte[] bytes;
        try {
            bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"error\":\"" + escapeHtml(e.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8);
            code = 500;
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Double pctReturn(Double start, Double end) {
        if (start == null || end == null) return null;
        if (Double.isNaN(start) || Double.isNaN(end) || start == 0.0) return null;
        return ((end - start) / start) * 100.0;
    }

    private static List<String> listCachedDailySymbols() {
        try {
            Path dir = Paths.get("finder-cache");
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            try (java.util.stream.Stream<Path> st = Files.list(dir)) {
                st.forEach(p -> {
                    try {
                        String fn = p.getFileName() == null ? "" : p.getFileName().toString();
                        if (!fn.startsWith("daily-") || !fn.endsWith(".json")) return;
                        String sym = fn.substring("daily-".length(), fn.length() - ".json".length()).trim().toUpperCase();
                        if (!sym.isBlank() && sym.matches("[A-Z0-9.:-]{1,10}")) out.add(sym);
                    } catch (Exception ignore) {}
                });
            }
            java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>(out);
            List<String> result = new ArrayList<>(dedup);
            result.sort(String::compareTo);
            return result;
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
    }

    private static List<Double> ohlcToSeries(Map<String, double[]> ohlcByDate, int idx) {
        try {
            if (ohlcByDate == null || ohlcByDate.isEmpty()) return new ArrayList<>();
            List<String> dates = new ArrayList<>(ohlcByDate.keySet());
            dates.sort(String::compareTo);
            List<Double> out = new ArrayList<>(dates.size());
            for (String d : dates) {
                double[] o = ohlcByDate.get(d);
                if (o == null || o.length <= idx) continue;
                double v = o[idx];
                if (Double.isNaN(v)) continue;
                out.add(v);
            }
            return out;
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
    }

    private static Double scoreAlphaAgentSymbolFromDailyJson(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
            if (ohlc == null || ohlc.isEmpty()) return null;

            List<Double> closes = ohlcToSeries(ohlc, 3);
            List<Double> highs = ohlcToSeries(ohlc, 1);
            List<Double> lows = ohlcToSeries(ohlc, 2);
            if (closes.size() < 60 || highs.size() < 60 || lows.size() < 60) return null;

            double lastClose = closes.get(closes.size() - 1);

            double score = 0.0;

            try {
                List<Double> sma20 = TechnicalAnalysisModel.calculateSMA(closes, 20);
                Double lastSma = sma20 == null || sma20.isEmpty() ? null : sma20.get(sma20.size() - 1);
                if (lastSma != null && !Double.isNaN(lastSma) && lastClose > lastSma) score += 1.0;
                else score -= 0.5;
            } catch (Exception ignore) {}

            try {
                List<Double> rsi = RSI.calculateRSI(closes, 14);
                Double lastRsi = rsi == null || rsi.isEmpty() ? null : rsi.get(rsi.size() - 1);
                if (lastRsi != null && !Double.isNaN(lastRsi)) {
                    if (lastRsi < 30.0) score += 1.0;
                    else if (lastRsi < 45.0) score += 0.6;
                    else if (lastRsi > 70.0) score -= 0.8;
                }
            } catch (Exception ignore) {}

            try {
                List<Double[]> macd = MACD.calculateMACD(closes);
                if (macd != null && !macd.isEmpty()) {
                    Double[] last = macd.get(macd.size() - 1);
                    if (last != null && last.length >= 2 && last[0] != null && last[1] != null) {
                        double m = last[0];
                        double s = last[1];
                        if (m > s) score += 0.8;
                        else score -= 0.4;
                    }
                }
            } catch (Exception ignore) {}

            try {
                List<Double[]> adx = ADX.calculateADX(highs, lows, closes, 14);
                if (adx != null && !adx.isEmpty()) {
                    Double[] last = adx.get(adx.size() - 1);
                    if (last != null && last.length >= 3) {
                        Double a = last[0];
                        Double p = last[1];
                        Double n = last[2];
                        if (p != null && n != null && !Double.isNaN(p) && !Double.isNaN(n)) {
                            if (p > n) score += 0.6;
                            else score -= 0.6;
                        }
                        if (a != null && !Double.isNaN(a)) {
                            if (a < 20.0) score += 0.3;
                            else if (a > 40.0) score -= 0.4;
                        }
                    }
                }
            } catch (Exception ignore) {}

            return score;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static List<String> selectAlphaAgentTickersDeterministic(int maxTickers) {
        int n = maxTickers <= 0 ? ALPHA_AGENT_MAX_TICKERS : maxTickers;
        List<String> syms = listCachedDailySymbols();
        if (syms.isEmpty()) return new ArrayList<>();
        List<String> scored = new ArrayList<>();
        Map<String, Double> scoreBySym = new HashMap<>();
        for (String s : syms) {
            try {
                Path p = Paths.get("finder-cache").resolve("daily-" + s + ".json");
                if (!Files.exists(p)) continue;
                String json = Files.readString(p, StandardCharsets.UTF_8);
                Double sc = scoreAlphaAgentSymbolFromDailyJson(json);
                if (sc == null || Double.isNaN(sc)) continue;
                scoreBySym.put(s, sc);
                scored.add(s);
            } catch (Exception ignore) {}
        }
        scored.sort((a, b) -> {
            double sa = scoreBySym.getOrDefault(a, Double.NEGATIVE_INFINITY);
            double sb = scoreBySym.getOrDefault(b, Double.NEGATIVE_INFINITY);
            int c = Double.compare(sb, sa);
            if (c != 0) return c;
            return a.compareTo(b);
        });
        if (scored.size() > n) scored = scored.subList(0, n);
        return new ArrayList<>(scored);
    }

    private static AlphaAgentPosition buildAlphaAgentPosition(String ticker, String startNyDate, String lastNyDate) {
        if (ticker == null || ticker.isBlank()) return null;
        String t = ticker.trim().toUpperCase();
        AlphaAgentPosition p = new AlphaAgentPosition();
        p.ticker = t;
        p.startNyDate = startNyDate;
        p.lastNyDate = lastNyDate;
        try {
            Map<String, Double> closeByDate = loadDailyCloseByDateCached(t);
            p.startPrice = bestEffortCloseOnOrBefore(closeByDate, startNyDate);
            p.lastPrice = bestEffortCloseOnOrBefore(closeByDate, lastNyDate);
        } catch (Exception ignore) {
        }
        return p;
    }

    private static AlphaAgentPortfolio bestEffortStartAlphaAgentPortfolio(int trackingDays) {
        return bestEffortStartAlphaAgentPortfolioAsync(trackingDays);
    }

    private static void respondSvg(HttpExchange ex, String svg, int code) throws IOException {
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "image/svg+xml; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String renderPipedTablesAsHtml(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String s : rawText.split("\\r?\\n")) {
            lines.add(s);
        }

        StringBuilder out = new StringBuilder();
        List<String> textBuf = new ArrayList<>();

        java.util.function.Consumer<Void> flushText = (v) -> {
            if (!textBuf.isEmpty()) {
                String joined = String.join("\n", textBuf).trim();
                if (!joined.isBlank()) {
                    out.append("<pre style='white-space:pre-wrap;word-break:break-word;margin:0;'>")
                            .append(escapeHtml(joined))
                            .append("</pre>");
                }
                textBuf.clear();
            }
        };

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String t = (line == null) ? "" : line.trim();
            boolean isPiped = t.startsWith("|") && t.endsWith("|") && t.length() >= 3;

            if (!isPiped) {
                textBuf.add(line);
                i++;
                continue;
            }

            flushText.accept(null);

            List<String> tableLines = new ArrayList<>();
            while (i < lines.size()) {
                String l = lines.get(i);
                String lt = (l == null) ? "" : l.trim();
                boolean lp = lt.startsWith("|") && lt.endsWith("|") && lt.length() >= 3;
                if (!lp) break;
                tableLines.add(lt);
                i++;
            }

            if (tableLines.isEmpty()) {
                continue;
            }

            boolean hasHeader = false;
            if (tableLines.size() >= 2) {
                String sep = tableLines.get(1).replace("|", "").trim();
                boolean onlyDashesSpaces = !sep.isBlank();
                for (int k = 0; k < sep.length(); k++) {
                    char c = sep.charAt(k);
                    if (!(c == '-' || Character.isWhitespace(c) || c == ':')) { onlyDashesSpaces = false; break; }
                }
                hasHeader = onlyDashesSpaces;
            }

            out.append("<div style='overflow:auto'>");
            out.append("<table style='width:100%;border-collapse:separate;border-spacing:0;border:1px solid #1f2a44;border-radius:12px;'>");

            int rowStart = 0;
            if (hasHeader) {
                List<String> headerCells = parsePipeRow(tableLines.get(0));
                out.append("<thead><tr>");
                for (String c : headerCells) {
                    out.append("<th style='text-align:left;padding:8px 10px;border-bottom:1px solid #1f2a44;color:#e5e7eb;font-weight:600;'>")
                            .append(escapeHtml(c))
                            .append("</th>");
                }
                out.append("</tr></thead>");
                rowStart = 2;
            }

            out.append("<tbody>");
            for (int r = rowStart; r < tableLines.size(); r++) {
                List<String> cells = parsePipeRow(tableLines.get(r));
                out.append("<tr>");
                for (String c : cells) {
                    out.append("<td style='padding:8px 10px;border-bottom:1px solid #111827;color:#e5e7eb;vertical-align:top;white-space:nowrap;'>")
                            .append(escapeHtml(c))
                            .append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</tbody></table></div>");
        }

        flushText.accept(null);
        return out.toString();
    }

    private static List<String> parsePipeRow(String line) {
        List<String> cells = new ArrayList<>();
        if (line == null) return cells;
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        for (String p : parts) {
            String v = (p == null) ? "" : p.trim();
            cells.add(v);
        }
        return cells;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String part : body.split("&")) {
            String[] kv = part.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        int contentLength = 0;
        String len = ex.getRequestHeaders().getFirst("Content-length");
        if (len != null) {
            try { contentLength = Integer.parseInt(len); } catch (Exception ignored) {}
        }
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(0, contentLength));
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String runAndCapture(Runnable r) {
        synchronized (RUN_CAPTURE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(bos, true, StandardCharsets.UTF_8);
            Throwable caught = null;
            try {
                System.setOut(capture);
                System.setErr(capture);
                r.run();
            } catch (Throwable t) {
                caught = t;
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            String output = bos.toString(StandardCharsets.UTF_8);
            if (caught != null) {
                // Append exception info to captured output so partial results are preserved
                output += "\n\n❌ Exception during analysis: " + caught.getClass().getSimpleName() + ": " + caught.getMessage();
                java.io.StringWriter sw = new java.io.StringWriter();
                caught.printStackTrace(new java.io.PrintWriter(sw));
                output += "\n" + sw.toString();
            }
            return output;
        }
    }

    private static int resolvePort() {
        try {
            String prop = System.getProperty("web.port");
            if (prop != null && !prop.isBlank()) return Integer.parseInt(prop.trim());
        } catch (Exception ignore) {}
        try {
            String env = System.getenv("WEB_PORT");
            if (env != null && !env.isBlank()) return Integer.parseInt(env.trim());
        } catch (Exception ignore) {}
        try {
            String env = System.getenv("PORT");
            if (env != null && !env.isBlank()) return Integer.parseInt(env.trim());
        } catch (Exception ignore) {}
        return 8099;
    }

    private static int resolveServerThreads() {
        try {
            String prop = System.getProperty("web.threads");
            if (prop != null && !prop.isBlank()) return Math.max(2, Integer.parseInt(prop.trim()));
        } catch (Exception ignore) {}
        try {
            String env = System.getenv("WEB_THREADS");
            if (env != null && !env.isBlank()) return Math.max(2, Integer.parseInt(env.trim()));
        } catch (Exception ignore) {}
        return 16;
    }

    private static String latestTrackingNyDate() {
        List<String> files = listTrackingFilesNewestFirst(1);
        if (files == null || files.isEmpty()) return null;
        String fn = files.get(0);
        if (fn == null) return null;
        return fn.endsWith(".json") ? fn.substring(0, fn.length() - 5) : fn;
    }

    private static Double findStartOpenForTickerOverRange(String ticker, List<String> nyDatesAsc) {
        if (ticker == null || ticker.isBlank() || nyDatesAsc == null) return null;
        String t = ticker.trim().toUpperCase();
        for (String nyDate : nyDatesAsc) {
            DailyTrackingSnapshot snap = bestEffortLoadTrackingSnapshot(nyDate);
            if (snap == null || snap.rows == null) continue;
            for (DailyTrackingRow r : snap.rows) {
                if (r == null || r.ticker == null) continue;
                if (!t.equalsIgnoreCase(r.ticker)) continue;
                if (r.startOpen != null) return r.startOpen;
            }
        }
        return null;
    }

    private static Double findLastCloseForTickerOverRange(String ticker, List<String> nyDatesAsc) {
        if (ticker == null || ticker.isBlank() || nyDatesAsc == null) return null;
        String t = ticker.trim().toUpperCase();
        Double last = null;
        for (String nyDate : nyDatesAsc) {
            DailyTrackingSnapshot snap = bestEffortLoadTrackingSnapshot(nyDate);
            if (snap == null || snap.rows == null) continue;
            for (DailyTrackingRow r : snap.rows) {
                if (r == null || r.ticker == null) continue;
                if (!t.equalsIgnoreCase(r.ticker)) continue;
                Double c = r.eodClose != null ? r.eodClose : r.startClose;
                if (c != null) last = c;
            }
        }
        return last;
    }

    private static Double pctChangeForTickerOverRange(String ticker, List<String> nyDatesAsc) {
        Double startOpen = findStartOpenForTickerOverRange(ticker, nyDatesAsc);
        Double lastClose = findLastCloseForTickerOverRange(ticker, nyDatesAsc);
        if (startOpen == null || lastClose == null || startOpen == 0) return null;
        return ((lastClose - startOpen) / startOpen) * 100.0;
    }

    private static final class TrendCell {
        final Double pct;
        final String note;
        TrendCell(Double pct, String note) {
            this.pct = pct;
            this.note = note;
        }
    }

    private static long resolveTrendThrottleMs() {
        try {
            String prop = System.getProperty("web.trend.throttleMs");
            if (prop != null && !prop.isBlank()) return Math.max(0, Long.parseLong(prop.trim()));
        } catch (Exception ignore) {}
        try {
            String env = System.getenv("WEB_TREND_THROTTLE_MS");
            if (env != null && !env.isBlank()) return Math.max(0, Long.parseLong(env.trim()));
        } catch (Exception ignore) {}
        return 1200;
    }

    private static long resolveTrendCacheMaxAgeMinutes() {
        try {
            String prop = System.getProperty("web.trend.cacheMaxAgeMinutes");
            if (prop != null && !prop.isBlank()) return Math.max(0, Long.parseLong(prop.trim()));
        } catch (Exception ignore) {}
        try {
            String env = System.getenv("WEB_TREND_CACHE_MAX_AGE_MINUTES");
            if (env != null && !env.isBlank()) return Math.max(0, Long.parseLong(env.trim()));
        } catch (Exception ignore) {}
        return 12 * 60;
    }

    private static String loadOrFetchDailyJsonForTrend(String ticker) {
        // No caching - always fetch fresh data from AlphaVantage
        String sym = ticker == null ? "" : ticker.trim().toUpperCase();
        if (sym.isBlank()) return null;

        try {
            DataFetcher.setTicker(sym);
            return DataFetcher.fetchStockData();
        } catch (Exception e) {
            return null;
        }
    }

    private static TrendCell computeCloseToClosePctFromFetchedDaily(String ticker, LocalDate fromInclusive, LocalDate toInclusive) {
        if (ticker == null || ticker.isBlank() || fromInclusive == null || toInclusive == null) return new TrendCell(null, "");
        String sym = ticker.trim().toUpperCase();
        try {
            String json = loadOrFetchDailyJsonForTrend(sym);
            if (json == null || json.isBlank()) return new TrendCell(null, "no data");

            String svc = PriceJsonParser.extractServiceMessage(json);
            if (svc != null && !svc.isBlank()) {
                // Rate limit or API message
                return new TrendCell(null, svc);
            }

            Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
            if (ohlc == null || ohlc.isEmpty()) return new TrendCell(null, "no candles");

            String from = fromInclusive.toString();
            String to = toInclusive.toString();

            String first = null;
            String last = null;
            for (String d : ohlc.keySet()) {
                if (d == null) continue;
                if (d.compareTo(from) < 0) continue;
                if (d.compareTo(to) > 0) continue;
                if (first == null || d.compareTo(first) < 0) first = d;
                if (last == null || d.compareTo(last) > 0) last = d;
            }
            if (first == null || last == null) return new TrendCell(null, "no days in range");

            double[] f = ohlc.get(first);
            double[] l = ohlc.get(last);
            if (f == null || l == null || f.length < 4 || l.length < 4) return new TrendCell(null, "bad candles");
            double startClose = f[3];
            double endClose = l[3];
            if (Double.isNaN(startClose) || Double.isNaN(endClose) || startClose == 0) return new TrendCell(null, "missing close");
            double pct = ((endClose - startClose) / startClose) * 100.0;
            return new TrendCell(pct, first + "→" + last);
        } catch (Exception e) {
            return new TrendCell(null, e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        // Log API key status at startup
        String apiKeyStatus = System.getenv("ALPHAVANTAGE_API_KEY");
        if (apiKeyStatus == null || apiKeyStatus.isBlank()) apiKeyStatus = System.getenv("ALPHA_VANTAGE_API_KEY");
        System.out.println("🔑 Alpha Vantage API Key: " + (apiKeyStatus != null && !apiKeyStatus.isBlank() ? "SET (" + apiKeyStatus.substring(0, Math.min(4, apiKeyStatus.length())) + "...)" : "⚠️ NOT SET - using 'demo' key with OLD DATA!"));
        
        int port = resolvePort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        int threads = resolveServerThreads();

        // No caching - data is always fetched fresh from AlphaVantage

        // Load portfolio from disk (portfolio.txt) at startup
        final Path portfolioPath = Paths.get("portfolio.txt");
        try {
            if (Files.exists(portfolioPath)) {
                java.util.List<String> lines = Files.readAllLines(portfolioPath, StandardCharsets.UTF_8);
                java.util.ArrayList<String> syms = new java.util.ArrayList<>();
                for (String line : lines) {
                    if (line == null) continue;
                    String t = line.trim().toUpperCase();
                    if (!t.isEmpty()) syms.add(t);
                }
                if (!syms.isEmpty()) {
                    PortfolioWeeklySummary.setPortfolio(syms);
                }
            }
        } catch (Exception ignore) {}

        // Initialize Discord Bot for command listening
        DiscordBot.initialize();

        // Shutdown hook - save data when server restarts
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Server shutting down - saving data...");
            DiscordBot.shutdown();
            try {
                // Save portfolio to disk
                java.util.List<String> portfolio = PortfolioWeeklySummary.getPortfolio();
                if (portfolio != null && !portfolio.isEmpty()) {
                    Files.write(portfolioPath, (String.join("\n", portfolio) + "\n").getBytes(StandardCharsets.UTF_8));
                    System.out.println("✅ Portfolio saved to " + portfolioPath);
                }
                // Save favorites
                synchronized (favLockStatic) {
                    if (!favItemsStatic.isEmpty()) {
                        Path favPath = Paths.get("favorites.txt");
                        Files.write(favPath, (String.join("\n", favItemsStatic) + "\n").getBytes(StandardCharsets.UTF_8));
                        System.out.println("✅ Favorites saved to " + favPath);
                    }
                }
                // Save monitoring stocks
                MonitoringStore store = MonitoringStore.defaultStore();
                java.util.List<String> monitoringTickers = store.loadTickers();
                if (monitoringTickers != null && !monitoringTickers.isEmpty()) {
                    store.persistTickers(monitoringTickers);
                    System.out.println("✅ Monitoring stocks saved");
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error saving data on shutdown: " + e.getMessage());
            }
            System.out.println("👋 Server shutdown complete");
        }));

        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String content = "<div class='card'><div class='title'>Analyze single symbol</div>"+
                        "<form method='post' action='/run-main'>"+
                        "<input type='text' name='symbol' placeholder='e.g. CRM' required /> "+
                        "<button type='submit'>Run</button>"+
                        "</form>" +
                        modelsUsedNamesOnlyHtml() +
                        "</div>" +
                        // Site capabilities description
                        "<div class='card' style='border:1px solid #1f2a44;'>" +
                        "<div class='title' dir='rtl'>📚 יכולות המערכת</div>" +
                        "<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;'>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #3b82f6;'>" +
                        "<div style='font-weight:600;color:#93c5fd;margin-bottom:4px;'>📊 Analyze (דוח מלא)</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>ניתוח מעמיק של מניה בודדת - DCF, טכני, פונדמנטלי, ו-AI. מבוסס על הגדרות האסטרטגיה בדף Settings.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #22c55e;'>" +
                        "<div style='font-weight:600;color:#86efac;margin-bottom:4px;'>📈 Dashboard</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>סיכום מהיר של ניתוח המניה - ציון סופי, המלצה, ומדדים עיקריים במבט אחד.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #8b5cf6;'>" +
                        "<div style='font-weight:600;color:#c4b5fd;margin-bottom:4px;'>💼 Manage Portfolio</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>ניהול תיק השקעות - מעקב אחר פוזיציות, רווח/הפסד, והיסטוריית עסקאות.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #06b6d4;'>" +
                        "<div style='font-weight:600;color:#67e8f9;margin-bottom:4px;'>📡 Monitoring Stocks</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>מעקב אוטומטי אחר מניות - היסטוריה, התראות, ועדכונים בזמן אמת.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #ef4444;'>" +
                        "<div style='font-weight:600;color:#fca5a5;margin-bottom:4px;'>⚡ Intraday Alerts</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>התראות מסחר יומי - זיהוי פריצות, RVOL, VWAP, RSI וסנטימנט שוק בזמן אמת.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #14b8a6;'>" +
                        "<div style='font-weight:600;color:#5eead4;margin-bottom:4px;'>📊 Active Positions</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>מעקב אחר פוזיציות פתוחות, רווחיות, וניהול סיכונים.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #a855f7;'>" +
                        "<div style='font-weight:600;color:#d8b4fe;margin-bottom:4px;'>👥 Analysts</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>המלצות אנליסטים, מחירי יעד, ודירוגי קונצנזוס ממקורות מובילים.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #f97316;'>" +
                        "<div style='font-weight:600;color:#fdba74;margin-bottom:4px;'>🔍 FINDER</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>מציאת מניות חדשות לפי קריטריונים - סריקת NASDAQ 100 ומניות מובילות.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #64748b;'>" +
                        "<div style='font-weight:600;color:#cbd5e1;margin-bottom:4px;'>⚙️ Settings</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>הגדרת אסטרטגיית ההשקעה - משקלות, סף ציון מינימלי, ופילטרים.</div>" +
                        "</div>" +
                        
                        "<div style='background:#0b1220;padding:12px;border-radius:8px;border-left:3px solid #78716c;'>" +
                        "<div style='font-weight:600;color:#d6d3d1;margin-bottom:4px;'>📊 API Usage</div>" +
                        "<div style='font-size:12px;color:#9ca3af;' dir='rtl'>מעקב אחר שימוש ב-API של Alpha Vantage - מגבלות והיסטוריה.</div>" +
                        "</div>" +
                        
                        "</div>" +
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        server.createContext("/dashboard", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>📊 Stock Quick Analysis</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;'>Enter a ticker to get instant scoring and analysis</div>");
                sb.append("<div style='display:flex;gap:8px;margin-bottom:16px;'>");
                sb.append("<input id='tickerInput' type='text' placeholder='Ticker (e.g. AAPL)' value='AAPL' style='width:150px;' onkeypress=\"if(event.key==='Enter'){fetchAnalysis(this.value);}\"/>");
                sb.append("<button type='button' onclick='fetchAnalysis(document.getElementById(\"tickerInput\").value);'>Analyze</button>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<div class='card'>");
                sb.append("<div class='title' dir='rtl'>ניתוח מנייה: <span id='tickerTitle' style='color:#93c5fd;'>AAPL</span></div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:20px;margin-bottom:16px;'>");
                sb.append("<div style='text-align:center;'>");
                sb.append("<div style='color:#9ca3af;margin-bottom:6px;' dir='rtl'>ציון סופי</div>");
                sb.append("<div id='finalScore' style='font-size:2.5rem;font-weight:bold;background:#0b1220;border:2px solid #1f2a44;border-radius:50%;width:90px;height:90px;display:flex;align-items:center;justify-content:center;margin:0 auto;'>--</div>");
                sb.append("<div id='recommendation' style='margin-top:8px;font-weight:600;color:#e5e7eb;'>--</div>");
                sb.append("</div>");
                sb.append("<div style='flex:1;min-width:200px;'>");
                sb.append("<form method='post' action='/run-main' target='_blank' onsubmit=\"document.getElementById('fullReportSymbol').value=document.getElementById('tickerInput').value;\" style='margin-bottom:10px;'>");
                sb.append("<input type='hidden' id='fullReportSymbol' name='symbol' value='AAPL'/>");
                sb.append("<button type='submit'>Open Full Report</button>");
                sb.append("</form>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<div class='card'>");
                sb.append("<div class='title'>📋 Details</div>");
                sb.append("<div style='overflow-x:auto;'>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr style='background:#0b1220;'>");
                sb.append("<th style='border:1px solid #1f2a44;padding:8px;text-align:left;'>Metric</th>");
                sb.append("<th style='border:1px solid #1f2a44;padding:8px;text-align:left;'>Value</th>");
                sb.append("<th style='border:1px solid #1f2a44;padding:8px;text-align:left;'>Explanation</th>");
                sb.append("</tr></thead><tbody>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Price</td><td id='d_price' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Current market price</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>DCF fair value</td><td id='d_dcf' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Discounted Cash Flow intrinsic value</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>DCF margin</td><td id='d_dcfMargin' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Positive = undervalued</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Technical signal</td><td id='d_techSignal' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>RSI/ADX-based signals</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Fundamental signal</td><td id='d_fundSignal' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Valuation + quality</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Final verdict</td><td id='d_finalVerdict' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Overall recommendation</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>RSI</td><td id='d_rsi' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>40-65 is healthy</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Altman Z</td><td id='d_altman' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Bankruptcy risk (lower is worse)</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Beneish M</td><td id='d_beneish' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>&gt;-1.78 is red flag</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Sloan ratio</td><td id='d_sloan' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Earnings quality</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Piotroski F</td><td id='d_piotroski' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>0-9 strength score</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>PEG</td><td id='d_peg' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>&lt;1.2 preferred</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>CCC (days)</td><td id='d_ccc' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Lower/negative is better</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>ROIC-WACC</td><td id='d_spread' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Positive = economic profit</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Graham Number</td><td id='d_grahamNum' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>Price below = undervalued</td></tr>");
                sb.append("<tr><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Graham IV</td><td id='d_grahamIV' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>MoS 33%+ preferred</td></tr>");
                sb.append("<tr style='background:#0f172a;'><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>🎯 Market Strength</td><td id='d_rsArrow' style='border:1px solid #1f2a44;padding:6px;font-size:1.3rem;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>↑↑ Leader | → Performer | ↓ Laggard</td></tr>");
                sb.append("<tr style='background:#0f172a;'><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>RS Ratio (3M)</td><td id='d_rsRatio' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>&gt;1.1 = מנהיג שוק, &lt;0.9 = נגרר</td></tr>");
                sb.append("<tr style='background:#0f172a;'><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>Stock Return (3M)</td><td id='d_stockReturn' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>תשואת המניה 3 חודשים</td></tr>");
                sb.append("<tr style='background:#0f172a;'><td style='border:1px solid #1f2a44;padding:6px;font-weight:600;'>SPY Return (3M)</td><td id='d_spyReturn' style='border:1px solid #1f2a44;padding:6px;color:#93c5fd;'>--</td><td style='border:1px solid #1f2a44;padding:6px;color:#9ca3af;'>תשואת השוק 3 חודשים</td></tr>");
                sb.append("</tbody></table>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<div class='card'>");
                sb.append("<div class='title' dir='rtl'>💡 תובנות מפתח</div>");
                sb.append("<ul id='insightsList' style='color:#cbd5e1;line-height:1.8;' dir='rtl'><li>המתן לטעינת נתונים...</li></ul>");
                sb.append("</div>");

                // AI Summary Section - Plain language explanation
                sb.append("<div class='card' style='background:linear-gradient(135deg,#0f172a 0%,#1e293b 100%);border:2px solid #3b82f6;'>");
                sb.append("<div class='title' dir='rtl'>🤖 סיכום בשפה פשוטה | Plain Language Summary</div>");
                sb.append("<div id='aiSummary' style='line-height:1.9;' dir='rtl'>");
                sb.append("<div style='color:#9ca3af;'>טוען סיכום...</div>");
                sb.append("</div>");
                sb.append("</div>");

                // Position Sizing Section - Risk Management
                sb.append("<div class='card' style='border:2px solid #f59e0b;'>");
                sb.append("<div class='title' dir='rtl'>💰 ניהול סיכונים - כמה מניות לקנות? | Position Sizing</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;' dir='rtl'>חשב כמה מניות לקנות כך שהפסד מקסימלי יהיה מוגדר מראש</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;'>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;' dir='rtl'>גודל תיק ($)</label>");
                sb.append("<input id='accountEquity' type='number' value='36000' min='1000' step='1000' style='width:100%;' onchange='calculatePosition()'/>");
                sb.append("</div>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;' dir='rtl'>אחוז סיכון לעסקה (%)</label>");
                sb.append("<input id='riskPercent' type='number' value='1' min='0.5' max='5' step='0.5' style='width:100%;' onchange='calculatePosition()'/>");
                sb.append("</div>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;' dir='rtl'>מכפיל ATR לסטופ</label>");
                sb.append("<input id='atrMultiplier' type='number' value='2.5' min='1.5' max='4' step='0.5' style='width:100%;' onchange='calculatePosition()'/>");
                sb.append("</div>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;' dir='rtl'>יחס סיכון/תגמול</label>");
                sb.append("<input id='riskReward' type='number' value='2' min='1' max='5' step='0.5' style='width:100%;' onchange='calculatePosition()'/>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div id='positionResult' style='background:#0b1220;border-radius:8px;padding:16px;'>");
                sb.append("<div style='color:#9ca3af;' dir='rtl'>המתן לחישוב...</div>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<script>" +
                        "async function fetchAnalysis(ticker) {" +
                        "  try {" +
                        "    ticker = (ticker || '').trim().toUpperCase();" +
                        "    if (!ticker) return;" +
                        "    const response = await fetch('/api/analyze/' + encodeURIComponent(ticker));" +
                        "    const result = await response.json();" +
                        "    document.getElementById('tickerTitle').innerText = ticker;" +
                        "    document.getElementById('finalScore').innerText = result.finalScore;" +
                        "    document.getElementById('recommendation').innerText = result.recommendation;" +
                        "    document.getElementById('fullReportSymbol').value = ticker;" +
                        "    const badge = document.getElementById('finalScore');" +
                        "    badge.style.borderColor = result.finalScore >= 75 ? '#22c55e' : (result.finalScore >= 50 ? '#fbbf24' : '#ef4444');" +
                        "    badge.style.color = result.finalScore >= 75 ? '#22c55e' : (result.finalScore >= 50 ? '#fbbf24' : '#ef4444');" +
                        "    const d = result.details || {};" +
                        "    function fmt(x){ if (x===null||x===undefined||Number.isNaN(x)) return 'N/A'; return x; }" +
                        "    function fmt2(x){ if (x===null||x===undefined||Number.isNaN(x)) return 'N/A'; return Number(x).toFixed(2); }" +
                        "    function fmtPct(x){ if (x===null||x===undefined||Number.isNaN(x)) return 'N/A'; return (Number(x)*100).toFixed(1) + '%'; }" +
                        "    document.getElementById('d_price').innerText = fmt2(d.price);" +
                        "    document.getElementById('d_dcf').innerText = fmt2(d.dcfFairValue);" +
                        "    document.getElementById('d_dcfMargin').innerText = fmtPct(d.dcfMargin);" +
                        "    document.getElementById('d_techSignal').innerText = fmt(d.technicalSignal);" +
                        "    document.getElementById('d_fundSignal').innerText = fmt(d.fundamentalSignal);" +
                        "    document.getElementById('d_finalVerdict').innerText = fmt(d.finalVerdict);" +
                        "    document.getElementById('d_rsi').innerText = fmt2(d.rsi);" +
                        "    document.getElementById('d_altman').innerText = fmt2(d.altmanZ);" +
                        "    document.getElementById('d_beneish').innerText = fmt2(d.beneishMScore);" +
                        "    document.getElementById('d_sloan').innerText = fmt2(d.sloanRatio);" +
                        "    document.getElementById('d_piotroski').innerText = fmt(d.piotroskiFScore);" +
                        "    document.getElementById('d_peg').innerText = fmt2(d.pegRatio);" +
                        "    document.getElementById('d_ccc').innerText = fmt2(d.cccDays);" +
                        "    document.getElementById('d_spread').innerText = fmt2(d.economicSpread);" +
                        "    document.getElementById('d_grahamNum').innerText = fmt2(d.grahamNumber);" +
                        "    document.getElementById('d_grahamIV').innerText = fmt2(d.grahamIntrinsicValue);" +
                        "    const rs = d.relativeStrength || {};" +
                        "    const rsArrowEl = document.getElementById('d_rsArrow');" +
                        "    rsArrowEl.innerText = (rs.arrow || '--') + ' ' + (rs.category || '');" +
                        "    rsArrowEl.style.color = rs.color || '#9ca3af';" +
                        "    document.getElementById('d_rsRatio').innerText = fmt2(rs.rsRatio3M);" +
                        "    document.getElementById('d_stockReturn').innerText = fmtPct(rs.stockReturn3M);" +
                        "    document.getElementById('d_spyReturn').innerText = fmtPct(rs.spyReturn3M);" +
                        "    const list = document.getElementById('insightsList');" +
                        "    list.innerHTML = '';" +
                        "    (result.keyInsights || []).forEach(insight => {" +
                        "      const li = document.createElement('li');" +
                        "      li.innerText = insight;" +
                        "      list.appendChild(li);" +
                        "    });" +
                        "    if ((result.keyInsights || []).length === 0) {" +
                        "      const li = document.createElement('li');" +
                        "      li.innerText = 'No insights.';" +
                        "      list.appendChild(li);" +
                        "    }" +
                        "    generateAISummary(ticker, result, d);" +
                        "    const ef = d.entryFilters || {};" +
                        "    storeForPosition(ticker, d.price, ef.atrValue);" +
                        "  } catch (err) {" +
                        "    console.error('Error fetching analysis:', err);" +
                        "  }" +
                        "}" +
                        "function generateAISummary(ticker, result, d) {" +
                        "  const score = result.finalScore || 0;" +
                        "  const rec = result.recommendation || '';" +
                        "  const rs = d.relativeStrength || {};" +
                        "  const dcfMargin = d.dcfMargin;" +
                        "  const fScore = d.piotroskiFScore;" +
                        "  const rsi = d.rsi;" +
                        "  const altmanZ = d.altmanZ;" +
                        "  const verdict = d.finalVerdict || '';" +
                        "  let html = '';" +
                        "  html += '<div style=\"margin-bottom:16px;padding:12px;background:#0b1220;border-radius:8px;border-left:4px solid '+(score>=60?'#22c55e':'#ef4444')+';\" dir=\"rtl\">';" +
                        "  html += '<div style=\"font-size:18px;font-weight:700;color:'+(score>=60?'#22c55e':'#ef4444')+';margin-bottom:8px;\">📊 המלצה: ' + rec + '</div>';" +
                        "  if (score >= 75) {" +
                        "    html += '<div style=\"color:#e5e7eb;\">המניה מקבלת ציון גבוה (<b>' + score + '</b>) - נראית כהזדמנות טובה.</div>';" +
                        "  } else if (score >= 60) {" +
                        "    html += '<div style=\"color:#e5e7eb;\">המניה מקבלת ציון סביר (<b>' + score + '</b>) - יש פוטנציאל אך עם סיכונים.</div>';" +
                        "  } else if (score >= 40) {" +
                        "    html += '<div style=\"color:#fbbf24;\">המניה מקבלת ציון בינוני (<b>' + score + '</b>) - כדאי להמתין לתנאים טובים יותר.</div>';" +
                        "  } else {" +
                        "    html += '<div style=\"color:#ef4444;\">המניה מקבלת ציון נמוך (<b>' + score + '</b>) - לא מומלצת לקנייה כרגע.</div>';" +
                        "  }" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;\">';" +
                        "  html += '<div style=\"padding:12px;background:#0b1220;border-radius:8px;\" dir=\"rtl\">';" +
                        "  html += '<div style=\"color:#3b82f6;font-weight:600;margin-bottom:8px;\">🎯 למה ' + (score>=60?'כדאי':'לא כדאי') + ' לקנות?</div>';" +
                        "  let reasons = [];" +
                        "  if (dcfMargin !== null && dcfMargin !== undefined && !Number.isNaN(dcfMargin)) {" +
                        "    if (dcfMargin > 0.15) reasons.push('✅ המניה נסחרת מתחת לשווי ההוגן ב-' + (dcfMargin*100).toFixed(0) + '%');" +
                        "    else if (dcfMargin < -0.15) reasons.push('⚠️ המניה יקרה - נסחרת מעל השווי ההוגן');" +
                        "  }" +
                        "  if (fScore !== null && fScore !== undefined) {" +
                        "    if (fScore >= 7) reasons.push('✅ חוזק פיננסי גבוה (F-Score: ' + fScore + '/9)');" +
                        "    else if (fScore <= 3) reasons.push('⚠️ חוזק פיננסי נמוך (F-Score: ' + fScore + '/9)');" +
                        "  }" +
                        "  if (rs.category === 'LEADER') reasons.push('✅ מנהיגת שוק - מכה את ה-S&P 500');" +
                        "  else if (rs.category === 'LAGGARD') reasons.push('⚠️ נגררת - ביצועים חלשים מהשוק');" +
                        "  if (rsi !== null && rsi !== undefined && !Number.isNaN(rsi)) {" +
                        "    if (rsi < 30) reasons.push('✅ RSI נמוך (' + rsi.toFixed(0) + ') - אולי oversold');" +
                        "    else if (rsi > 70) reasons.push('⚠️ RSI גבוה (' + rsi.toFixed(0) + ') - אולי overbought');" +
                        "  }" +
                        "  if (reasons.length === 0) reasons.push('אין נתונים מספיקים להערכה מלאה');" +
                        "  html += '<div style=\"color:#cbd5e1;font-size:14px;\">' + reasons.join('<br>') + '</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"padding:12px;background:#0b1220;border-radius:8px;\" dir=\"rtl\">';" +
                        "  html += '<div style=\"color:#f59e0b;font-weight:600;margin-bottom:8px;\">⏱️ לאיזה טווח זמן?</div>';" +
                        "  html += '<div style=\"color:#cbd5e1;font-size:14px;\">';" +
                        "  html += 'מצב הניתוח הנוכחי: <b>SWING_TRADER</b><br>';" +
                        "  html += '• טווח מומלץ: <b>2-8 שבועות</b><br>';" +
                        "  html += '• סגנון: מסחר לפי תנודות שוק<br>';" +
                        "  html += '• דגש על: איתותים טכניים וכוח יחסי';" +
                        "  html += '</div></div>';" +
                        "  html += '<div style=\"padding:12px;background:#0b1220;border-radius:8px;\" dir=\"rtl\">';" +
                        "  html += '<div style=\"color:#ef4444;font-weight:600;margin-bottom:8px;\">⚠️ סיכונים וחסרונות</div>';" +
                        "  let risks = [];" +
                        "  if (altmanZ !== null && altmanZ !== undefined && !Number.isNaN(altmanZ) && altmanZ < 1.8) {" +
                        "    risks.push('🔴 סיכון פשיטת רגל גבוה (Altman Z: ' + altmanZ.toFixed(2) + ')');" +
                        "  }" +
                        "  if (rs.category === 'LAGGARD') risks.push('🔴 המניה מפגרת אחרי השוק');" +
                        "  if (dcfMargin !== null && dcfMargin < -0.2) risks.push('🔴 תמחור יתר משמעותי');" +
                        "  if (rsi !== null && rsi > 70) risks.push('🟡 RSI גבוה - אפשרי תיקון');" +
                        "  risks.push('🟡 הניתוח מבוסס על נתונים היסטוריים בלבד');" +
                        "  risks.push('🟡 אירועי שוק בלתי צפויים יכולים להשפיע');" +
                        "  html += '<div style=\"color:#cbd5e1;font-size:14px;\">' + risks.join('<br>') + '</div>';" +
                        "  html += '</div></div>';" +
                        "  html += '<div style=\"margin-top:12px;padding:10px;background:#1e3a5f;border-radius:6px;color:#93c5fd;font-size:13px;\" dir=\"rtl\">';" +
                        "  html += '💡 <b>טיפ:</b> השתמש ב-Stop Loss מוצע בטבלה למעלה כדי להגן על ההשקעה. לדו\"ח מפורט יותר לחץ \"Open Full Report\".';" +
                        "  html += '</div>';" +
                        "  document.getElementById('aiSummary').innerHTML = html;" +
                        "}" +
                        "var currentPrice = 0;" +
                        "var currentAtr = 0;" +
                        "var currentTicker = '';" +
                        "function storeForPosition(ticker, price, atr) {" +
                        "  currentTicker = ticker;" +
                        "  currentPrice = price || 0;" +
                        "  currentAtr = atr || 0;" +
                        "  calculatePosition();" +
                        "}" +
                        "function calculatePosition() {" +
                        "  const equity = parseFloat(document.getElementById('accountEquity').value) || 36000;" +
                        "  const riskPct = (parseFloat(document.getElementById('riskPercent').value) || 1) / 100;" +
                        "  const atrMult = parseFloat(document.getElementById('atrMultiplier').value) || 2.5;" +
                        "  const rrRatio = parseFloat(document.getElementById('riskReward').value) || 2;" +
                        "  const el = document.getElementById('positionResult');" +
                        "  if (!currentPrice || !currentAtr || currentAtr <= 0) {" +
                        "    el.innerHTML = '<div style=\"color:#9ca3af;\">לא זמין - חסרים נתוני מחיר או ATR</div>';" +
                        "    return;" +
                        "  }" +
                        "  const amountToRisk = equity * riskPct;" +
                        "  const stopDistance = currentAtr * atrMult;" +
                        "  let shares = Math.floor(amountToRisk / stopDistance);" +
                        "  const maxShares = Math.floor(equity / currentPrice);" +
                        "  shares = Math.min(shares, maxShares);" +
                        "  if (shares <= 0) { el.innerHTML = '<div style=\"color:#ef4444;\">גודל התיק קטן מדי לעסקה זו</div>'; return; }" +
                        "  const totalInvest = shares * currentPrice;" +
                        "  const portfolioPct = (totalInvest / equity) * 100;" +
                        "  const stopLossPrice = currentPrice - stopDistance;" +
                        "  const takeProfitPrice = currentPrice + (stopDistance * rrRatio);" +
                        "  const maxLoss = shares * stopDistance;" +
                        "  const potentialProfit = shares * stopDistance * rrRatio;" +
                        "  let html = '';" +
                        "  html += '<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;\">';" +
                        "  html += '<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';" +
                        "  html += '<div style=\"font-size:32px;font-weight:700;color:#22c55e;\">' + shares + '</div>';" +
                        "  html += '<div style=\"color:#9ca3af;font-size:13px;\" dir=\"rtl\">מניות לקנייה</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';" +
                        "  html += '<div style=\"font-size:24px;font-weight:600;color:#3b82f6;\">$' + totalInvest.toLocaleString('en-US', {maximumFractionDigits:0}) + '</div>';" +
                        "  html += '<div style=\"color:#9ca3af;font-size:13px;\" dir=\"rtl\">השקעה כוללת (' + portfolioPct.toFixed(1) + '% מהתיק)</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';" +
                        "  html += '<div style=\"font-size:24px;font-weight:600;color:#ef4444;\">-$' + maxLoss.toFixed(0) + '</div>';" +
                        "  html += '<div style=\"color:#9ca3af;font-size:13px;\" dir=\"rtl\">הפסד מקסימלי (' + (riskPct*100).toFixed(1) + '% מהתיק)</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';" +
                        "  html += '<div style=\"font-size:24px;font-weight:600;color:#22c55e;\">+$' + potentialProfit.toFixed(0) + '</div>';" +
                        "  html += '<div style=\"color:#9ca3af;font-size:13px;\" dir=\"rtl\">רווח פוטנציאלי (1:' + rrRatio + ')</div>';" +
                        "  html += '</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"margin-top:16px;display:flex;flex-wrap:wrap;gap:12px;justify-content:center;\">';" +
                        "  html += '<div style=\"padding:10px 16px;background:#0f172a;border:1px solid #1f2a44;border-radius:6px;\" dir=\"rtl\">';" +
                        "  html += '<span style=\"color:#9ca3af;\">מחיר כניסה:</span> <span style=\"color:#e5e7eb;font-weight:600;\">$' + currentPrice.toFixed(2) + '</span>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"padding:10px 16px;background:#0f172a;border:1px solid #ef4444;border-radius:6px;\">';" +
                        "  html += '<span style=\"color:#ef4444;\">🛑 Stop Loss:</span> <span style=\"color:#ef4444;font-weight:600;\">$' + stopLossPrice.toFixed(2) + '</span>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"padding:10px 16px;background:#0f172a;border:1px solid #22c55e;border-radius:6px;\">';" +
                        "  html += '<span style=\"color:#22c55e;\">🎯 Take Profit:</span> <span style=\"color:#22c55e;font-weight:600;\">$' + takeProfitPrice.toFixed(2) + '</span>';" +
                        "  html += '</div>';" +
                        "  html += '</div>';" +
                        "  html += '<div style=\"margin-top:12px;padding:10px;background:#1e3a5f;border-radius:6px;color:#93c5fd;font-size:13px;text-align:center;\" dir=\"rtl\">';" +
                        "  html += '📌 <b>' + currentTicker + '</b>: קנה ' + shares + ' מניות במחיר $' + currentPrice.toFixed(2) + ' | סטופ ב-$' + stopLossPrice.toFixed(2) + ' | יעד ב-$' + takeProfitPrice.toFixed(2);" +
                        "  html += '</div>';" +
                        "  el.innerHTML = html;" +
                        "}" +
                        "fetchAnalysis('AAPL');" +
                        "</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/api/analyze", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }

                try {
                    String path = ex.getRequestURI() == null ? null : ex.getRequestURI().getPath();
                    String[] parts = (path == null) ? new String[0] : path.split("/");
                    String sym = (parts.length >= 4) ? parts[3] : "";
                    sym = sym == null ? "" : sym.trim().toUpperCase();
                    if (sym.isBlank()) { respondJson(ex, Map.of("error", "Missing ticker. Use /api/analyze/{ticker}"), 400); return; }

                    StockAnalysisResult r = StockScannerRunner.analyzeSingleStock(sym);
                    if (r == null) { respondJson(ex, Map.of("error", "No analysis result"), 500); return; }

                    double z = (r.altmanZ != null && Double.isFinite(r.altmanZ)) ? r.altmanZ : Double.NaN;
                    double m = (r.beneishMScore != null && Double.isFinite(r.beneishMScore)) ? r.beneishMScore : Double.NaN;
                    double sloan = (r.sloanRatio != null && Double.isFinite(r.sloanRatio)) ? r.sloanRatio : 0.0;

                    double fScore = (r.piotroskiFScore == null) ? Double.NaN : r.piotroskiFScore.doubleValue();

                    double peg = (r.pegRatio != null && Double.isFinite(r.pegRatio)) ? r.pegRatio : Double.NaN;
                    double dcfMargin = Double.NaN;
                    if (Double.isFinite(r.price) && r.price > 0 && Double.isFinite(r.dcfFairValue) && r.dcfFairValue > 0) {
                        dcfMargin = (r.dcfFairValue - r.price) / r.price;
                    }

                    boolean technicalBullish = r.technicalSignal != null && r.technicalSignal.toUpperCase().contains("BUY") && !r.technicalSignal.toUpperCase().contains("SELL");
                    double rsi = (r.latestRsi != null && Double.isFinite(r.latestRsi)) ? r.latestRsi : 50.0;

                    double ccc = (r.cccDays != null && Double.isFinite(r.cccDays)) ? r.cccDays : Double.NaN;
                    double spread = (r.economicSpread != null && Double.isFinite(r.economicSpread)) ? r.economicSpread : 0.0;
                    double grahamMoS = (r.grahamMarginOfSafety != null && Double.isFinite(r.grahamMarginOfSafety)) ? r.grahamMarginOfSafety : Double.NaN;

                    // If risk metrics missing, avoid triggering veto by using safe defaults
                    double mForEngine = Double.isFinite(m) ? m : -999.0;
                    double zForEngine = Double.isFinite(z) ? z : 99.0;
                    double pegForEngine = Double.isFinite(peg) ? peg : 99.0;
                    double dcfForEngine = Double.isFinite(dcfMargin) ? dcfMargin : 0.0;
                    double cccForEngine = Double.isFinite(ccc) ? ccc : 999.0;

                    FinalScoringEngine.AnalysisResult ar = FinalScoringEngine.computeFinalScore(
                            zForEngine, mForEngine, sloan,
                            fScore, pegForEngine, dcfForEngine,
                            technicalBullish, rsi,
                            cccForEngine, spread, grahamMoS
                    );

                    if (!Double.isFinite(m) || !Double.isFinite(z)) {
                        ar.keyInsights.add("Note: Some risk fundamentals (Altman Z / Beneish) are missing or rate-limited; score may be less reliable.");
                    }
                    if (!Double.isFinite(peg) || !Double.isFinite(dcfMargin)) {
                        ar.keyInsights.add("Note: Some valuation inputs (PEG / DCF margin) are missing; score may be less reliable.");
                    }
                    if (!Double.isFinite(fScore)) {
                        ar.keyInsights.add("Note: Piotroski F-Score is missing (needs annual statements / shares data); currently treated as 0.");
                    }

                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("ticker", sym);
                    details.put("price", Double.isFinite(r.price) ? r.price : null);
                    details.put("dcfFairValue", Double.isFinite(r.dcfFairValue) ? r.dcfFairValue : null);
                    details.put("dcfMargin", Double.isFinite(dcfMargin) ? dcfMargin : null);
                    details.put("technicalSignal", r.technicalSignal);
                    details.put("fundamentalSignal", r.fundamentalSignal);
                    details.put("finalVerdict", r.finalVerdict);
                    details.put("rsi", (r.latestRsi != null && Double.isFinite(r.latestRsi)) ? r.latestRsi : null);
                    details.put("altmanZ", Double.isFinite(z) ? z : null);
                    details.put("beneishMScore", Double.isFinite(m) ? m : null);
                    details.put("sloanRatio", (r.sloanRatio != null && Double.isFinite(r.sloanRatio)) ? r.sloanRatio : null);
                    details.put("piotroskiFScore", r.piotroskiFScore);
                    details.put("pegRatio", (r.pegRatio != null && Double.isFinite(r.pegRatio)) ? r.pegRatio : null);
                    details.put("cccDays", (r.cccDays != null && Double.isFinite(r.cccDays)) ? r.cccDays : null);
                    details.put("economicSpread", (r.economicSpread != null && Double.isFinite(r.economicSpread)) ? r.economicSpread : null);
                    details.put("grahamNumber", (r.grahamNumber != null && Double.isFinite(r.grahamNumber)) ? r.grahamNumber : null);
                    details.put("grahamIntrinsicValue", (r.grahamIntrinsicValue != null && Double.isFinite(r.grahamIntrinsicValue)) ? r.grahamIntrinsicValue : null);
                    details.put("grahamMarginOfSafety", (r.grahamMarginOfSafety != null && Double.isFinite(r.grahamMarginOfSafety)) ? r.grahamMarginOfSafety : null);

                    // Entry Filters data
                    Map<String, Object> entryFilters = new LinkedHashMap<>();
                    entryFilters.put("passesAll", r.passesEntryFilters);
                    entryFilters.put("sma200", (r.sma200 != null && Double.isFinite(r.sma200)) ? r.sma200 : null);
                    entryFilters.put("volumeRatio", (r.volumeRatio != null && Double.isFinite(r.volumeRatio)) ? r.volumeRatio : null);
                    entryFilters.put("suggestedStopLoss", (r.suggestedStopLoss != null && Double.isFinite(r.suggestedStopLoss)) ? r.suggestedStopLoss : null);
                    entryFilters.put("suggestedTakeProfit", (r.suggestedTakeProfit != null && Double.isFinite(r.suggestedTakeProfit)) ? r.suggestedTakeProfit : null);
                    entryFilters.put("atrValue", (r.atrValue != null && Double.isFinite(r.atrValue)) ? r.atrValue : null);
                    entryFilters.put("summary", r.entryFiltersSummary);
                    details.put("entryFilters", entryFilters);

                    // Relative Strength data (כוח יחסי מול SPY)
                    Map<String, Object> relativeStrength = new LinkedHashMap<>();
                    relativeStrength.put("rsRatio3M", (r.rsRatio3M != null && Double.isFinite(r.rsRatio3M)) ? r.rsRatio3M : null);
                    relativeStrength.put("rsRatio6M", (r.rsRatio6M != null && Double.isFinite(r.rsRatio6M)) ? r.rsRatio6M : null);
                    relativeStrength.put("stockReturn3M", (r.stockReturn3M != null && Double.isFinite(r.stockReturn3M)) ? r.stockReturn3M : null);
                    relativeStrength.put("spyReturn3M", (r.spyReturn3M != null && Double.isFinite(r.spyReturn3M)) ? r.spyReturn3M : null);
                    relativeStrength.put("points", r.rsPoints);
                    relativeStrength.put("category", r.rsCategory);
                    relativeStrength.put("arrow", r.rsArrow);
                    relativeStrength.put("color", r.rsColor);
                    relativeStrength.put("summary", r.rsSummary);
                    details.put("relativeStrength", relativeStrength);

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("finalScore", ar.finalScore);
                    out.put("recommendation", ar.recommendation);
                    out.put("keyInsights", ar.keyInsights);
                    out.put("details", details);

                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        // Clear Cache API
        server.createContext("/api/clear-cache", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    int finderDeleted = 0;
                    int monitoringDeleted = 0;
                    
                    // Clear finder-cache/*.json
                    Path finderDir = Paths.get("finder-cache");
                    if (Files.exists(finderDir) && Files.isDirectory(finderDir)) {
                        try (java.util.stream.Stream<Path> files = Files.list(finderDir)) {
                            for (Path f : files.toList()) {
                                String name = f.getFileName().toString();
                                if (name.endsWith(".json") && !Files.isDirectory(f)) {
                                    try { Files.delete(f); finderDeleted++; } catch (Exception ignore) {}
                                }
                            }
                        }
                    }
                    
                    // Clear monitoring-cache/*.json
                    Path monitorDir = Paths.get("monitoring-cache");
                    if (Files.exists(monitorDir) && Files.isDirectory(monitorDir)) {
                        try (java.util.stream.Stream<Path> files = Files.list(monitorDir)) {
                            for (Path f : files.toList()) {
                                String name = f.getFileName().toString();
                                if (name.endsWith(".json") && !Files.isDirectory(f)) {
                                    try { Files.delete(f); monitoringDeleted++; } catch (Exception ignore) {}
                                }
                            }
                        }
                    }
                    
                    int total = finderDeleted + monitoringDeleted;
                    String msg = "נמחקו " + total + " קבצי cache (finder: " + finderDeleted + ", monitoring: " + monitoringDeleted + "). הנתונים הבאים יישלפו טריים מה-API.";
                    respondJson(ex, Map.of("message", msg), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "שגיאה במחיקת cache: " + e.getMessage()), 500);
                }
            }
        });

        // Volume Flow Tracker API
        server.createContext("/api/volume-flow", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }

                try {
                    Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                    String ticker = qp.getOrDefault("ticker", "").trim().toUpperCase();
                    int days = 7;
                    try { days = Math.min(30, Math.max(1, Integer.parseInt(qp.getOrDefault("days", "7")))); } catch (Exception ignore) {}

                    if (ticker.isBlank()) { respondJson(ex, Map.of("error", "Missing ticker parameter"), 400); return; }

                    // Fetch stock data - ALWAYS fresh from API, never use cache
                    DataFetcher.setTicker(ticker);
                    String json = DataFetcher.fetchStockData();
                    System.out.println("[VolumeFlow] Fetched data for " + ticker + ", length=" + (json == null ? 0 : json.length()));
                    if (json != null && json.length() > 200) {
                        System.out.println("[VolumeFlow] Raw JSON preview: " + json.substring(0, 200));
                    }
                    if (json == null || json.isBlank()) { 
                        respondJson(ex, Map.of("error", "Failed to fetch data for " + ticker + " - API returned empty response"), 500); 
                        return; 
                    }

                    // Check for API error messages (rate limit, invalid key, etc.)
                    String apiError = PriceJsonParser.extractServiceMessage(json);
                    if (apiError != null && !apiError.isBlank()) {
                        respondJson(ex, Map.of("error", "Alpha Vantage API Error: " + apiError), 500);
                        return;
                    }

                    // Parse price data first to validate dates
                    List<Double> closes = PriceJsonParser.extractClosingPrices(json);
                    System.out.println("[VolumeFlow] Parsed " + closes.size() + " closing prices, latest=" + (closes.isEmpty() ? "N/A" : closes.get(closes.size()-1)));
                    List<Double> highs = PriceJsonParser.extractHighPrices(json);
                    List<Double> lows = PriceJsonParser.extractLowPrices(json);
                    List<Double> opens = PriceJsonParser.extractOpenPrices(json);
                    List<Long> volumes = PriceJsonParser.extractVolumeData(json);
                    List<String> dates = PriceJsonParser.extractDates(json);

                    // Validate data freshness - check ACTUAL most recent date in data
                    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"));
                    String mostRecentDate = null;
                    
                    // dates are sorted ascending by PriceJsonParser, so last one is most recent
                    if (dates != null && !dates.isEmpty()) {
                        mostRecentDate = dates.get(dates.size() - 1);
                    }
                    // Fallback to metadata
                    if (mostRecentDate == null || mostRecentDate.isBlank()) {
                        mostRecentDate = PriceJsonParser.extractLastRefreshedDate(json);
                    }
                    
                    if (mostRecentDate != null && !mostRecentDate.isBlank()) {
                        try {
                            java.time.LocalDate dataDate = java.time.LocalDate.parse(mostRecentDate.substring(0, 10));
                            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(dataDate, today);
                            // Allow up to 4 days old (weekend + 1 buffer)
                            if (daysDiff > 4) {
                                respondJson(ex, Map.of("error", "Data is STALE! Most recent date: " + mostRecentDate + " (" + daysDiff + " days old). Your Alpha Vantage API is returning old data. Check your API key configuration."), 500);
                                return;
                            }
                        } catch (Exception dateErr) {
                            System.err.println("Warning: Could not parse date: " + mostRecentDate);
                        }
                    } else {
                        // No date info at all - reject to be safe
                        respondJson(ex, Map.of("error", "Cannot verify data freshness - no date information in API response"), 500);
                        return;
                    }

                    if (closes == null || closes.isEmpty()) { 
                        respondJson(ex, Map.of("error", "No price data in API response - check if ticker '" + ticker + "' is valid"), 500); 
                        return; 
                    }

                    // Data is already in ascending order (oldest first, newest last) from PriceJsonParser
                    // VolumeFlowTracker expects this order and gets currentPrice from last element
                    // DO NOT reverse - that would put oldest at end and break currentPrice

                    // Run Volume Flow analysis
                    VolumeFlowTracker.FlowAnalysis analysis = VolumeFlowTracker.analyze(ticker, closes, highs, lows, opens, volumes, dates, days);
                    System.out.println("[VolumeFlow] Analysis result: currentPrice=" + analysis.currentPrice + ", ticker=" + analysis.ticker);

                    // Add cache-control headers to prevent browser caching
                    ex.getResponseHeaders().add("Cache-Control", "no-cache, no-store, must-revalidate");
                    ex.getResponseHeaders().add("Pragma", "no-cache");
                    ex.getResponseHeaders().add("Expires", "0");

                    // Build response
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ticker", analysis.ticker);
                    out.put("currentPrice", analysis.currentPrice);
                    out.put("weeklyBuyPressure", analysis.weeklyBuyPressure);
                    out.put("weeklySellPressure", analysis.weeklySellPressure);
                    out.put("weeklyTrend", analysis.weeklyTrend);
                    out.put("buyZoneLow", analysis.buyZoneLow);
                    out.put("buyZoneHigh", analysis.buyZoneHigh);
                    out.put("sellZoneLow", analysis.sellZoneLow);
                    out.put("sellZoneHigh", analysis.sellZoneHigh);
                    out.put("stopLoss", analysis.stopLoss);
                    out.put("supportLevel", analysis.supportLevel);
                    out.put("resistanceLevel", analysis.resistanceLevel);
                    out.put("recommendation", analysis.recommendation);
                    out.put("insights", analysis.insights);

                    // Daily flow data
                    List<Map<String, Object>> dailyFlowList = new ArrayList<>();
                    for (VolumeFlowTracker.DayFlow day : analysis.dailyFlow) {
                        Map<String, Object> dayMap = new LinkedHashMap<>();
                        dayMap.put("date", day.date);
                        dayMap.put("buyPercent", day.buyPercent);
                        dayMap.put("sellPercent", day.sellPercent);
                        dayMap.put("signal", day.signal);
                        dayMap.put("open", day.open);
                        dayMap.put("close", day.close);
                        dayMap.put("priceChange", day.priceChange);
                        dayMap.put("priceChangePct", day.priceChangePct);
                        dayMap.put("volume", day.volume);
                        dailyFlowList.add(dayMap);
                    }
                    out.put("dailyFlow", dailyFlowList);

                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), 500);
                }
            }
        });

        // ── Success Recipe Engine endpoints ──
        server.createContext("/api/recipes/analyze", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET") && !ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondJson(ex, Map.of("error", "GET/POST only"), 405); return;
                }
                try {
                    RecipeAnalytics.RecipeBook book = RecipeAnalytics.analyze();
                    respondJson(ex, book, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), 500);
                }
            }
        });

        server.createContext("/api/recipes", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                try {
                    String dataDir = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
                    java.nio.file.Path p = java.nio.file.Paths.get(dataDir, "success-recipes.json");
                    if (!java.nio.file.Files.exists(p)) {
                        respondJson(ex, Map.of("message", "No recipes yet. Run /api/recipes/analyze first."), 200); return;
                    }
                    String json = java.nio.file.Files.readString(p);
                    respondJson(ex, JSON.readTree(json), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), 500);
                }
            }
        });

        server.createContext("/api/recipes/recommend", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                try {
                    String dataDir = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
                    java.nio.file.Path p = java.nio.file.Paths.get(dataDir, "success-recipes.json");
                    if (!java.nio.file.Files.exists(p)) {
                        respondJson(ex, Map.of("message", "No recipes yet. Run /api/recipes/analyze first."), 200); return;
                    }
                    RecipeAnalytics.RecipeBook book = JSON.readValue(p.toFile(), RecipeAnalytics.RecipeBook.class);
                    List<Map<String,Object>> recs = new ArrayList<>();
                    for (RecipeAnalytics.RecommendedAgent ra : book.recommendedAgents) {
                        Map<String,Object> m = new LinkedHashMap<>();
                        m.put("id", ra.id);
                        m.put("name", ra.name);
                        m.put("setupType", ra.setupType);
                        m.put("winCount", ra.winCount);
                        m.put("lossCount", ra.lossCount);
                        m.put("expectedWinRate", ra.expectedWinRate);
                        m.put("expectedEdgePct", ra.expectedEdgePct);
                        m.put("entryFilters", ra.entryFilters);
                        m.put("riskManagement", ra.riskManagement);
                        m.put("insightsHebrew", ra.insightsHebrew);
                        recs.add(m);
                    }
                    respondJson(ex, Map.of("recommendedAgents", recs, "lastUpdated", book.lastUpdated), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), 500);
                }
            }
        });

        server.createContext("/nasdaq-daily-top-tracking-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean ok = false;
                if (!t.isBlank() && t.matches("[A-Z0-9.:-]{1,10}")) {
                    List<String> tickers = bestEffortLoadTrackedDailyTopTickers();
                    if (!tickers.contains(t)) {
                        tickers.add(t);
                        bestEffortPersistTrackedDailyTopTickers(tickers);
                        ok = true;
                    }
                }
                ex.getResponseHeaders().add("Location", "/nasdaq-daily-top-tracking?status=" + (ok ? "added" : "invalid_or_exists"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/refresh", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String pid = form.getOrDefault("pid", "");
                synchronized (alphaAgentLock) {
                    bestEffortForceRefreshAlphaAgentPricesAsync(pid);
                }
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=refreshing" + (pid == null || pid.isBlank() ? "" : ("&pid=" + urlEncode(pid))));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/nasdaq-daily-top-tracking-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean ok = false;
                if (!t.isBlank()) {
                    List<String> tickers = bestEffortLoadTrackedDailyTopTickers();
                    ok = tickers.removeIf(x -> x.equalsIgnoreCase(t));
                    if (ok) bestEffortPersistTrackedDailyTopTickers(tickers);
                }
                ex.getResponseHeaders().add("Location", "/nasdaq-daily-top-tracking?status=" + (ok ? "removed" : "not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/nasdaq-daily-top-tracking", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                ensureTrackingBootstrappedIfEmpty();

                List<String> trackedTickers = bestEffortLoadTrackedDailyTopTickers();

                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                int months = 1;
                try {
                    String m = qp.getOrDefault("months", "1");
                    months = Integer.parseInt(m);
                } catch (Exception ignore) {}
                if (months < 1) months = 1;
                if (months > 3) months = 3;
                int daysToLoad = Math.min(93, Math.max(31, months * 31));

                // Aggregate last ~month of tracking snapshots into a per-ticker view
                Map<String, String> firstDateByTicker = new HashMap<>();
                Map<String, Double> startOpenByTicker = new HashMap<>();
                Map<String, Double> startCloseByTicker = new HashMap<>();
                Map<String, String> lastDateByTicker = new HashMap<>();
                Map<String, Double> lastCloseByTicker = new HashMap<>();

                List<String> files = listTrackingFilesNewestFirst(daysToLoad);
                // iterate oldest -> newest for firstDate correctness
                files.sort(String::compareTo);
                for (String fn : files) {
                    String nyDate = fn.replace(".json", "");
                    DailyTrackingSnapshot snap = bestEffortLoadTrackingSnapshot(nyDate);
                    if (snap == null || snap.rows == null) continue;
                    for (DailyTrackingRow r : snap.rows) {
                        if (r == null || r.ticker == null || r.ticker.isBlank()) continue;
                        String t = r.ticker.trim().toUpperCase();
                        if (!firstDateByTicker.containsKey(t)) {
                            firstDateByTicker.put(t, nyDate);
                            startOpenByTicker.put(t, r.startOpen);
                            startCloseByTicker.put(t, r.startClose);
                        }
                        Double close = r.eodClose != null ? r.eodClose : r.startClose;
                        if (close != null) {
                            lastDateByTicker.put(t, nyDate);
                            lastCloseByTicker.put(t, close);
                        }
                    }
                }

                List<String> tickers = new ArrayList<>(firstDateByTicker.keySet());
                tickers.sort(String::compareTo);

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Daily Nasdaq Top (GREEN) - Tracking</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Tracked tickers list is stored in <code>finder-cache/daily-top-tracking-tickers.txt</code>. If empty, tracking is derived from last Daily Top run.</div>");
                sb.append("<div style='margin-bottom:12px;padding:10px 12px;background:#0b1220;border:1px solid #1f2a44;border-radius:12px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:8px;'>Tracked tickers</div>");
                if (trackedTickers.isEmpty()) {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>None saved yet.</div>");
                } else {
                    sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px;'>");
                    for (String t : trackedTickers) {
                        sb.append("<span style='background:#08101f;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>")
                                .append(escapeHtml(t))
                                .append("</span>");
                    }
                    sb.append("</div>");
                }
                sb.append("<div style='display:flex;gap:10px;flex-wrap:wrap;'>");
                sb.append("<form method='post' action='/nasdaq-daily-top-tracking-add' style='margin:0'>")
                        .append("<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required /> ")
                        .append("<button type='submit'>Add</button></form>");
                sb.append("<form method='post' action='/nasdaq-daily-top-tracking-remove' style='margin:0'>")
                        .append("<input type='text' name='symbol' placeholder='Remove ticker (e.g. AAPL)' required /> ")
                        .append("<button type='submit'>Remove</button></form>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div style='display:flex;gap:10px;flex-wrap:wrap;margin-bottom:10px;'>");
                sb.append("<a href='/nasdaq-daily-top-tracking?months=1' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>1 month</a>");
                sb.append("<a href='/nasdaq-daily-top-tracking?months=2' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>2 months</a>");
                sb.append("<a href='/nasdaq-daily-top-tracking?months=3' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>3 months</a>");
                sb.append("<div style='color:#9ca3af;align-self:center;'>Showing ~" + daysToLoad + " days (months=" + months + ")</div>");
                sb.append("</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Tracking based on Alpha Vantage daily candles. Start price is the daily <b>open</b> on the recommendation NY date. EOD is the daily <b>close</b> for that date (if available). If a close isn't available yet, we show the last known close.</div>");

                sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Ticker</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Start NY Date</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Start Open</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Last Close</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Last NY Date</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>P/L %</th>")
                        .append("</tr></thead><tbody>");

                if (tickers.isEmpty()) {
                    sb.append("<tr><td colspan='6' style='padding:10px 8px;color:#9ca3af;border-bottom:1px solid #111827;'>No tracking data yet. Run Daily Nasdaq Top once.</td></tr>");
                } else {
                    for (String t : tickers) {
                        String sd = firstDateByTicker.getOrDefault(t, "");
                        String ld = lastDateByTicker.getOrDefault(t, "");
                        Double so = startOpenByTicker.get(t);
                        Double lc = lastCloseByTicker.get(t);

                        Double plPct = null;
                        if (so != null && lc != null && so != 0) {
                            plPct = ((lc - so) / so) * 100.0;
                        }
                        String plText = plPct == null ? "" : String.format("%.2f%%", plPct);
                        String plColor = plPct == null ? "#9ca3af" : (plPct >= 0 ? "#22c55e" : "#fca5a5");

                        sb.append("<tr>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;'>").append(escapeHtml(t)).append("</td>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;'>").append(escapeHtml(sd)).append("</td>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;'>").append(so==null?"":escapeHtml(String.format("%.4f", so))).append("</td>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;'>").append(lc==null?"":escapeHtml(String.format("%.4f", lc))).append("</td>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;'>").append(escapeHtml(ld)).append("</td>")
                                .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;color:").append(plColor).append(";font-weight:700;'>").append(escapeHtml(plText)).append("</td>")
                                .append("</tr>");
                    }
                }
                sb.append("</tbody></table></div></div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/daily-green-run", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }

                boolean started = startDailyGreenRecommendationsRunAsync();
                ex.getResponseHeaders().add("Location", "/favorites?dailyGreen=" + (started ? "started" : "already_running"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/select", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String pid = form.getOrDefault("pid", "");
                synchronized (alphaAgentLock) {
                    if (pid != null && !pid.isBlank()) bestEffortSetActiveAlphaAgentPortfolioId(pid);
                }
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=selected&pid=" + urlEncode(pid));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/drop", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String pid = form.getOrDefault("pid", "");
                boolean ok;
                synchronized (alphaAgentLock) {
                    ok = bestEffortDropAlphaAgentPortfolioById(pid);
                }
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=" + (ok ? "dropped" : "drop_failed"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/custom-create", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                int days = ALPHA_AGENT_DEFAULT_TRACKING_DAYS;
                try {
                    String v = form.getOrDefault("evaluationPeriodDays", "");
                    if (v != null && !v.isBlank()) days = Integer.parseInt(v.trim());
                } catch (Exception ignore) {}

                AlphaAgentPortfolio pf = buildEmptyUserManagedAlphaAgentPortfolio(days);
                String id;
                synchronized (alphaAgentLock) {
                    id = bestEffortCreateNewAlphaAgentPortfolioSlot(pf);
                }
                String status = (id == null || id.isBlank()) ? "limit" : "custom_created";
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=" + status + (id == null ? "" : ("&pid=" + urlEncode(id))));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/custom-save", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String pid = form.getOrDefault("pid", "");
                String tickersText = form.getOrDefault("tickers", "");

                List<String> tickers = new ArrayList<>();
                if (tickersText != null && !tickersText.isBlank()) {
                    String[] parts = tickersText.split("[^A-Za-z0-9.]+");
                    for (String p : parts) {
                        if (p == null) continue;
                        String t = p.trim().toUpperCase();
                        if (t.isBlank()) continue;
                        if (!tickers.contains(t)) tickers.add(t);
                    }
                }
                if (tickers.size() > 20) tickers = new ArrayList<>(tickers.subList(0, 20));

                MonitoringAlphaVantageClient av = null;
                try { av = MonitoringAlphaVantageClient.fromEnv(); } catch (Exception ignore) {}

                String startNyDate = nyToday();
                AlphaAgentPortfolio updated = null;
                synchronized (alphaAgentLock) {
                    AlphaAgentPortfolio cur = bestEffortLoadAlphaAgentPortfolioById(pid);
                    if (cur != null) {
                        cur.userManaged = true;
                        cur.startNyDate = startNyDate;
                        cur.createdAtNy = ZonedDateTime.now(NY).toString();
                        cur.positions = new ArrayList<>();
                        for (String t : tickers) {
                            AlphaAgentPosition pos = buildAlphaAgentPosition(t, startNyDate, startNyDate);
                            if (pos == null) continue;
                            Double entry = bestEffortFetchLiveOrLastClosePrice(t, av);
                            pos.startPrice = entry;
                            pos.lastPrice = entry;
                            pos.startNyDate = startNyDate;
                            pos.lastNyDate = startNyDate;
                            cur.positions.add(pos);
                        }
                        if (cur.benchNasdaq100 != null && cur.benchNasdaq100.ticker != null) {
                            Double p = bestEffortFetchLiveOrLastClosePrice(cur.benchNasdaq100.ticker, av);
                            cur.benchNasdaq100.startNyDate = startNyDate;
                            cur.benchNasdaq100.lastNyDate = startNyDate;
                            cur.benchNasdaq100.startPrice = p;
                            cur.benchNasdaq100.lastPrice = p;
                        }
                        if (cur.benchSp500 != null && cur.benchSp500.ticker != null) {
                            Double p = bestEffortFetchLiveOrLastClosePrice(cur.benchSp500.ticker, av);
                            cur.benchSp500.startNyDate = startNyDate;
                            cur.benchSp500.lastNyDate = startNyDate;
                            cur.benchSp500.startPrice = p;
                            cur.benchSp500.lastPrice = p;
                        }
                        cur.lastError = "";
                        bestEffortPersistAlphaAgentPortfolioById(pid, cur);
                        bestEffortSetActiveAlphaAgentPortfolioId(pid);
                        updated = cur;
                    }
                }

                String status = (updated == null) ? "custom_failed" : "custom_saved";
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=" + status + (pid == null || pid.isBlank() ? "" : ("&pid=" + urlEncode(pid))));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/nasdaq-daily-top-last-trend", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                // Load last tickers (same source as /nasdaq-daily-top-last)
                List<String> tickers = null;
                synchronized (dailyPicksLock) {
                    if (dailyPicksCache.tickers != null && !dailyPicksCache.tickers.isEmpty()) {
                        tickers = new ArrayList<>(dailyPicksCache.tickers);
                    }
                }
                if (tickers == null) {
                    tickers = bestEffortLoadLastDailyTopTickers();
                }
                if (tickers == null) tickers = new ArrayList<>();
                List<String> cleaned = new ArrayList<>();
                for (String x : tickers) {
                    if (x == null) continue;
                    String v = x.trim().toUpperCase();
                    if (!v.isBlank()) cleaned.add(v);
                }
                tickers = cleaned;

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Last Daily Top " + DAILY_TOP_PICK_COUNT + " Picks - Trend</div>");
                sb.append("<div style='display:flex;gap:10px;flex-wrap:wrap;margin-bottom:10px;'>");
                sb.append("<a href='/nasdaq-daily-top-last'>Back</a>");
                sb.append("<a href='/nasdaq-daily-top-last-performance?months=1' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>% Change view</a>");
                sb.append("</div>");

                if (tickers.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No saved tickers found. Run Daily Nasdaq Top first.</div></div>");
                    respondHtml(ex, htmlPage(sb.toString()), 200);
                    return;
                }

                LocalDate today = LocalDate.now();
                // Calendar windows (not NY-specific)
                // Prev month: [today-31d, today]
                // 2 months ago: [today-62d, today-32d]
                // 3 months ago: [today-93d, today-63d]
                LocalDate m1From = today.minusDays(31);
                LocalDate m1To = today;
                LocalDate m2From = today.minusDays(62);
                LocalDate m2To = today.minusDays(32);
                LocalDate m3From = today.minusDays(93);
                LocalDate m3To = today.minusDays(63);

                long throttleMs = resolveTrendThrottleMs();
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Computed on-demand from fetched daily candles (close-to-close). Windows are calendar-based: last 31 days, previous 31 days, and the 31 days before that. This may be slow / rate-limited for many tickers.</div>");

                sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Ticker</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Prev Month %</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>2 Months Ago %</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>3 Months Ago %</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Note</th>")
                        .append("</tr></thead><tbody>");

                for (String t : tickers) {
                    TrendCell m1 = computeCloseToClosePctFromFetchedDaily(t, m1From, m1To);
                    if (throttleMs > 0) { try { Thread.sleep(throttleMs); } catch (Exception ignore) {} }
                    TrendCell m2 = computeCloseToClosePctFromFetchedDaily(t, m2From, m2To);
                    if (throttleMs > 0) { try { Thread.sleep(throttleMs); } catch (Exception ignore) {} }
                    TrendCell m3 = computeCloseToClosePctFromFetchedDaily(t, m3From, m3To);

                    String m1t = m1 == null || m1.pct == null ? "" : String.format("%.2f%%", m1.pct);
                    String m2t = m2 == null || m2.pct == null ? "" : String.format("%.2f%%", m2.pct);
                    String m3t = m3 == null || m3.pct == null ? "" : String.format("%.2f%%", m3.pct);
                    String c1 = (m1 == null || m1.pct == null) ? "#9ca3af" : (m1.pct >= 0 ? "#22c55e" : "#fca5a5");
                    String c2 = (m2 == null || m2.pct == null) ? "#9ca3af" : (m2.pct >= 0 ? "#22c55e" : "#fca5a5");
                    String c3 = (m3 == null || m3.pct == null) ? "#9ca3af" : (m3.pct >= 0 ? "#22c55e" : "#fca5a5");
                    String note = "";
                    if (m1 != null && m1.note != null && !m1.note.isBlank()) note = m1.note;
                    else if (m2 != null && m2.note != null && !m2.note.isBlank()) note = m2.note;
                    else if (m3 != null && m3.note != null && !m3.note.isBlank()) note = m3.note;

                    sb.append("<tr>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;'>").append(escapeHtml(t)).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;color:").append(c1).append(";font-weight:700;'>").append(escapeHtml(m1t)).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;color:").append(c2).append(";font-weight:700;'>").append(escapeHtml(m2t)).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;color:").append(c3).append(";font-weight:700;'>").append(escapeHtml(m3t)).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;color:#9ca3af;'>").append(escapeHtml(note)).append("</td>")
                            .append("</tr>");
                }

                sb.append("</tbody></table></div></div>");
                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/finder", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String content = "<div class='card'><div class='title'>Stocks Finder Nasdaq100</div>"+
                        "<form method='post' action='/recommendations'>"+
                        "<button type='submit'>Nasdaq stock Finder (Find randomly 5 stocks from Nasdaq100)</button>"+
                        "</form>"+
                        "<div style='margin-top:8px'><a href='/finder-last'>Open Last Finder Results</a></div>"+
                        modelsUsedNamesOnlyHtml() +
                        "</div>"+
                        "<div class='card'><div class='title'>Daily Nasdaq Top " + DAILY_TOP_PICK_COUNT + " (GREEN)</div>"+
                        "<form method='post' action='/nasdaq-daily-top'>"+
                        "<button type='submit'>Daily Nasdaq Top " + DAILY_TOP_PICK_COUNT + " (GREEN) - Find best long-term candidates</button>"+
                        "</form>"+
                        "<div style='margin-top:8px'><a href='/nasdaq-daily-top-last'>Open Last Daily Top " + DAILY_TOP_PICK_COUNT + " Picks</a></div>"+
                        "<div style='margin-top:8px'><a href='/nasdaq-daily-top-tracking'>Open 1-Month Tracking Grid</a></div>"+
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // ---------------- Active Positions (Trailing Stop-Loss) ----------------
        final ActivePositionsStore activePositionsStore = ActivePositionsStore.defaultStore();

        // ---------------- Monitoring Stocks (separate module) ----------------
        final MonitoringStore monitoringStore = MonitoringStore.defaultStore();
        final MonitoringAlphaVantageClient monitoringClient = MonitoringAlphaVantageClient.fromEnv();
        final MonitoringAnalyzer monitoringAnalyzer = new MonitoringAnalyzer(monitoringClient);
        final MonitoringScheduler monitoringScheduler = new MonitoringScheduler(monitoringStore, monitoringAnalyzer);
        // Run Mon–Fri on New York time at: 09:30, 11:30, 13:30, 15:30 ET
        try {
            monitoringScheduler.startNyseWeekdayEvery2Hours();
        } catch (Exception ignore) {}


        server.createContext("/quote-now", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String sym;
                synchronized (intradayLock) {
                    sym = intradayState.symbol;
                    intradayState.lastQuoteAtNy = ZonedDateTime.now(NY);
                    intradayState.lastQuoteError = null;
                }
                boolean ok = false;
                try {
                    if (sym != null && !sym.isBlank()) {
                        MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                        JsonNode q = av.globalQuote(sym);
                        System.out.println("[intraday-quote] GLOBAL_QUOTE raw response: " + q);
                        Double price = extractGlobalQuotePrice(q);
                        synchronized (intradayLock) {
                            intradayState.lastQuotePrice = price;
                            if (price == null) {
                                String raw = q != null ? q.toString() : "null";
                                intradayState.lastQuoteError = "No price in GLOBAL_QUOTE response. Raw: " + (raw.length() > 300 ? raw.substring(0, 300) : raw);
                            } else {
                                ok = true;
                            }
                        }
                    } else {
                        synchronized (intradayLock) {
                            intradayState.lastQuoteError = "No symbol selected";
                        }
                    }
                } catch (Exception e) {
                    synchronized (intradayLock) {
                        intradayState.lastQuoteError = e.getMessage();
                    }
                }
                ex.getResponseHeaders().add("Location", "/intraday-alerts?quote=" + (ok ? "ok" : "failed"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // Add ticker endpoint (POST)
        server.createContext("/portfolio-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = PortfolioWeeklySummary.addTicker(sym);
                // No caching - data is always fetched fresh from AlphaVantage
                // Persist portfolio to disk
                try { Files.write(portfolioPath, (String.join("\n", PortfolioWeeklySummary.getPortfolio())+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/portfolio-manage?status=" + (ok?"added":"invalid"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // Remove ticker endpoint (POST)
        server.createContext("/portfolio-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = PortfolioWeeklySummary.removeTicker(sym);
                // No caching - data is always fetched fresh from AlphaVantage
                // Persist portfolio to disk
                try { Files.write(portfolioPath, (String.join("\n", PortfolioWeeklySummary.getPortfolio())+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/portfolio-manage?status=" + (ok?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // Monitoring Stocks page (view / add / remove / refresh)
        server.createContext("/monitoring", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                List<String> tickers = monitoringStore.loadTickers();
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Monitoring Stocks</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;' dir='rtl'>בחר מניות למעקב. כל הרצה שומרת תמונת מצב ומציגה ביצועים של 2-10 ימי מסחר <b>אחרונים</b>: <b>return</b>=% שינוי בפועל מהעבר, <b>score</b>=עוצמת הסיגנל (מבוסס על אינדיקטורים טכניים + חדשות).</div>");
                sb.append(modelsUsedNamesOnlyHtml());

                // Data directory (important when running from different working directories)
                try {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Data directory: ")
                            .append(escapeHtml(String.valueOf(monitoringStore.getBaseDir())))
                            .append("</div>");
                } catch (Exception ignore) {}

                // Scheduler status (helps debug when no reports exist yet)
                try {
                    java.time.ZonedDateTime nextNy = monitoringScheduler.getNextRunNy();
                    java.time.ZonedDateTime lastNy = monitoringScheduler.getLastRunNy();
                    String err = monitoringScheduler.getLastError();
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:12px;padding:12px;margin-bottom:12px;'>");
                    sb.append("<div style='color:#e5e7eb;font-weight:600;margin-bottom:6px;'>Scheduler Status (New York time)</div>");
                    sb.append("<div style='color:#9ca3af'>Next run: ").append(nextNy==null?"(not scheduled yet)":escapeHtml(nextNy.toString())).append("</div>");
                    sb.append("<div style='color:#9ca3af'>Last run: ").append(lastNy==null?"(never)":escapeHtml(lastNy.toString())).append("</div>");
                    if (err != null && !err.isBlank()) {
                        sb.append("<div style='color:#fca5a5;margin-top:6px;'>Last error: ").append(escapeHtml(err)).append("</div>");
                    }
                    sb.append("</div>");
                } catch (Exception ignore) {}

                sb.append("<form method='post' action='/monitoring-add' style='margin-bottom:10px;'>")
                        .append("<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required /> ")
                        .append("<button type='submit'>Add</button></form>");
                sb.append("<form method='post' action='/monitoring-remove' style='margin-bottom:10px;'>")
                        .append("<input type='text' name='symbol' placeholder='Remove ticker (e.g. AAPL)' required /> ")
                        .append("<button type='submit'>Remove</button></form>");
                sb.append("<form method='post' action='/monitoring-refresh' style='margin-bottom:0;'>")
                        .append("<button type='submit'>Refresh Now (async)</button></form>");
                sb.append("</div>");

                if (tickers.isEmpty()) {
                    sb.append("<div class='card'><div class='title'>Monitored list</div><div style='color:#9ca3af'>No monitored tickers yet.</div></div>");
                    respondHtml(ex, htmlPage(sb.toString()), 200);
                    return;
                }

                sb.append("<div class='card'><div class='title'>Latest Snapshots</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Signals are computed separately for 2-10 trading-day windows. BUY means multi-signal positive bias; DROP means negative bias; HOLD is neutral.</div>");

                for (String t : tickers) {
                    MonitoringSnapshot snap = monitoringStore.loadSnapshot(t);
                    String esc = escapeHtml(t);
                    String safeId = t == null ? "" : t.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");

                    Map<String, Double> closeByDate = Map.of();
                    java.time.LocalDate snapDateNy = null;
                    try {
                        snapDateNy = java.time.Instant.ofEpochMilli(snap.asOfEpochMillis()).atZone(NY).toLocalDate();
                        closeByDate = loadDailyCloseByDateCached(t);
                    } catch (Exception ignore) {}

                    sb.append("<div style='border-top:1px solid rgba(148,163,184,0.25);padding-top:12px;margin-top:12px;'>");
                    sb.append("<div style='display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-bottom:8px;'>")
                            .append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'><b>")
                            .append(esc)
                            .append("</b></span>");

                    try {
                        java.time.Instant upd = monitoringStore.snapshotUpdatedAt(t);
                        if (upd != null) {
                            sb.append("<span style='color:#9ca3af'>saved: ").append(escapeHtml(upd.toString())).append("</span>");
                        }
                    } catch (Exception ignore) {}

                    sb.append("<form method='post' action='/run-main' style='display:inline;margin:0'>")
                            .append("<input type='hidden' name='symbol' value='").append(esc).append("'/>")
                            .append("<button type='submit'>Full Analyze</button></form>");
                    sb.append("<form method='post' action='/monitoring-remove' style='display:inline;margin:0;margin-left:6px;'>")
                            .append("<input type='hidden' name='symbol' value='").append(esc).append("'/>")
                            .append("<button type='submit' style='background:#dc2626;' onclick=\"return confirm('Delete ").append(esc).append(" from monitoring?');\">Delete</button></form>");
                    sb.append("</div>");

                    if (snap == null) {
                        sb.append("<div style='color:#fbbf24'>No snapshot yet. Click Refresh Now or wait for the scheduled run.</div>");
                        sb.append("</div>");
                        continue;
                    }

                    sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px;'>");
                    for (String k : new String[]{"2","3","4","5","6","7","8","9","10"}) {
                        String rec = snap.recommendationByDays() == null ? null : snap.recommendationByDays().get(k);
                        Double ret = snap.returnsByDays() == null ? null : snap.returnsByDays().get(k);
                        Double sc = snap.scoreByDays() == null ? null : snap.scoreByDays().get(k);
                        String recText = rec == null ? "N/A" : rec;
                        String pillColor = recText.equals("BUY") ? "#22c55e" : (recText.equals("DROP") ? "#fca5a5" : "#93c5fd");

                        Double actual = null;
                        try { actual = currentReturnPctBackwards(closeByDate, Integer.parseInt(k)); } catch (Exception ignore) {}
                        String actualText = actual == null ? "N/A" : String.format("%+.2f%%", actual);
                        String actualColor = (actual == null) ? "#9ca3af" : (actual >= 0.0 ? "#22c55e" : "#fca5a5");
                        String pill = "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>"+
                                "<span style='color:#9ca3af;'>Last"+escapeHtml(k)+"d</span> " +
                                "<span style='color:"+actualColor+";font-weight:700;'>"+escapeHtml(actualText)+"</span>" +
                                " <span style='color:"+pillColor+";'>"+escapeHtml(recText)+"</span>" +
                                "<span style='color:#9ca3af;'> · score "+(sc==null?"N/A":String.format("%.2f", sc))+"</span>" +
                                "</span>";
                        sb.append(pill);
                    }
                    sb.append("</div>");

                    sb.append("<div style='margin-bottom:10px'>")
                            .append("<img src='/chart?symbol=").append(esc).append("&w=900&h=260&n=120' alt='chart'/>")
                            .append("</div>");

                    String detailsId = "snap-details-" + safeId;
                    sb.append("<button type='button' class='toggle-details' data-target='").append(detailsId).append("' style='margin-top:6px;'>+ Details</button>");
                    sb.append("<div id='").append(detailsId).append("' style='display:none;margin-top:10px'>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left'>");
                    sb.append("<div class='title'>Indicators</div>");
                    if (snap.indicatorValues() != null) {
                        for (Map.Entry<String, Double> e : snap.indicatorValues().entrySet()) {
                            String kk = escapeHtml(e.getKey());
                            String vv = e.getValue() == null ? "" : String.format("%.4f", e.getValue());
                            String note = snap.indicatorNotes() == null ? null : snap.indicatorNotes().get(e.getKey());
                            sb.append("<div class='indicator-item'>")
                                    .append("<span class='indicator-label'>").append(kk).append("</span>")
                                    .append("<span class='indicator-value'>").append(escapeHtml(vv)).append("</span>")
                                    .append(note == null ? "" : ("<div class='indicator-text'>" + escapeHtml(note) + "</div>"))
                                    .append("</div>");
                        }
                    }
                    sb.append("</div>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left;margin-top:10px'>");
                    sb.append("<div class='title'>Fundamentals (Overview)</div>");
                    if (snap.fundamentals() != null && !snap.fundamentals().isEmpty()) {
                        for (Map.Entry<String, String> e : snap.fundamentals().entrySet()) {
                            String kk = escapeHtml(e.getKey());
                            String vv = escapeHtml(e.getValue());
                            sb.append("<div class='indicator-item'>")
                                    .append("<span class='indicator-label'>").append(kk).append("</span>")
                                    .append("<span class='indicator-value'>").append(vv).append("</span>")
                                    .append("</div>");
                        }
                    }
                    sb.append("</div>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left;margin-top:10px'>");
                    sb.append("<div class='title'>Latest News</div>");
                    if (snap.newsTop() != null && !snap.newsTop().isEmpty()) {
                        sb.append("<div style='display:flex;flex-direction:column;gap:10px'>");
                        for (Map<String,String> n : snap.newsTop()) {
                            String title = escapeHtml(n.getOrDefault("title", ""));
                            String url = escapeHtml(n.getOrDefault("url", ""));
                            String sum = escapeHtml(n.getOrDefault("summary", ""));
                            String sentiment = escapeHtml(n.getOrDefault("sentiment", ""));
                            sb.append("<div style='padding:10px 12px;background:#0b1220;border:1px solid #1f2a44;border-radius:10px'>")
                                    .append("<div style='font-weight:600;margin-bottom:4px;'>").append(title).append("</div>")
                                    .append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(sentiment).append("</div>")
                                    .append("<div style='color:#cbd5e1;margin-bottom:6px;'>").append(sum).append("</div>")
                                    .append(url.isEmpty()?"":("<a href='"+url+"' target='_blank' rel='noreferrer'>Open</a>"))
                                    .append("</div>");
                        }
                        sb.append("</div>");
                    } else {
                        sb.append("<div style='color:#9ca3af'>No news (or API limit reached).</div>");
                    }
                    sb.append("</div>");

                    sb.append("</div>");
                    sb.append("</div>");
                }

                sb.append("</div>");
                sb.append("<script>(function(){var btns=document.querySelectorAll('.toggle-details');for(var i=0;i<btns.length;i++){btns[i].addEventListener('click',function(){var id=this.getAttribute('data-target');var el=document.getElementById(id);if(!el)return;var open=(el.style.display!=='none');el.style.display=open?'none':'';this.textContent=(open?'+ Details':'- Details');});}})();</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/monitoring-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = monitoringStore.addTicker(sym);
                ex.getResponseHeaders().add("Location", "/monitoring?status=" + (ok?"added":"invalid_or_exists"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/monitoring-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = monitoringStore.removeTicker(sym);
                ex.getResponseHeaders().add("Location", "/monitoring?status=" + (ok?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Strategy Tools</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>כלים לבדיקת אסטרטגיות מסחר על נתונים היסטוריים</div>");
                sb.append(modelsUsedNamesOnlyHtml());

                // ---- Active Config Info: from momentum JSON files (read-only) ----
                try {
                    // momentum-success-2026.json
                    DailyTradingSimulator.Success2026Config successCfg = DailyTradingSimulator.Success2026Config.load();
                    // momentum-variants.json
                    DailyTradingSimulator.MomentumVariantsConfig variantsCfg = DailyTradingSimulator.MomentumVariantsConfig.load();
                    // intraday-variants.json
                    DailyTradingSimulator.IntradayVariantsConfig intradayCfg = DailyTradingSimulator.IntradayVariantsConfig.load();

                    sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:14px;margin-top:10px;'>");
                    sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:4px;'>📋 Active Momentum Configs</div>");
                    sb.append("<div style='font-size:11px;color:#6b7280;margin-bottom:12px;'>These configs are loaded from their own JSON files – independent of the global scoring mode.</div>");

                    // momentum-success-2026.json
                    sb.append("<div style='margin-bottom:12px;'>");
                    sb.append("<div style='font-size:12px;font-weight:600;color:#f59e0b;margin-bottom:6px;'>🏆 momentum-success-2026.json");
                    if (successCfg != null && successCfg.description != null) {
                        sb.append(" <span style='color:#6b7280;font-weight:400;'>— ").append(escapeHtml(successCfg.description)).append("</span>");
                    }
                    sb.append("</div>");
                    if (successCfg != null && successCfg.variants != null) {
                        sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:6px;'>");
                        for (DailyTradingSimulator.VariantConfig v : successCfg.variants) {
                            sb.append("<div style='background:#1f2a44;padding:8px;border-radius:6px;'>");
                            sb.append("<div style='font-weight:600;color:#fcd34d;font-size:12px;'>").append(escapeHtml(v.name)).append("</div>");
                            if (v.nameHe != null) sb.append("<div style='font-size:10px;color:#9ca3af;'>").append(escapeHtml(v.nameHe)).append("</div>");
                            if (v.entryFilters != null) {
                                sb.append("<div style='font-size:11px;color:#6b7280;margin-top:4px;'>");
                                sb.append("RSI: ").append((int)v.entryFilters.rsiMin).append("–").append((int)v.entryFilters.rsiMax);
                                sb.append(" | RS≥").append(String.format("%.2f", v.entryFilters.rsMin));
                                sb.append(" | RVOL≥").append(String.format("%.1f", v.entryFilters.rvolMin));
                                if (v.riskManagement != null) {
                                    sb.append(" | SL:").append(String.format("%.1f%%", v.riskManagement.stopLossPct));
                                    sb.append(" | TP:").append(String.format("%.1f%%", v.riskManagement.takeProfitPct));
                                }
                                sb.append("</div>");
                            }
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    }
                    sb.append("</div>");

                    // momentum-variants.json
                    sb.append("<div style='margin-bottom:12px;'>");
                    sb.append("<div style='font-size:12px;font-weight:600;color:#8b5cf6;margin-bottom:6px;'>🔀 momentum-variants.json");
                    if (variantsCfg != null && variantsCfg.description != null) {
                        sb.append(" <span style='color:#6b7280;font-weight:400;'>— ").append(escapeHtml(variantsCfg.description)).append("</span>");
                    }
                    sb.append("</div>");
                    if (variantsCfg != null && variantsCfg.variants != null) {
                        sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:6px;'>");
                        for (DailyTradingSimulator.VariantConfig v : variantsCfg.variants) {
                            sb.append("<div style='background:#1f2a44;padding:8px;border-radius:6px;'>");
                            sb.append("<div style='font-weight:600;color:#c4b5fd;font-size:12px;'>").append(escapeHtml(v.name)).append("</div>");
                            if (v.nameHe != null) sb.append("<div style='font-size:10px;color:#9ca3af;'>").append(escapeHtml(v.nameHe)).append("</div>");
                            if (v.entryFilters != null) {
                                sb.append("<div style='font-size:11px;color:#6b7280;margin-top:4px;'>");
                                sb.append("RSI: ").append((int)v.entryFilters.rsiMin).append("–").append((int)v.entryFilters.rsiMax);
                                sb.append(" | RS≥").append(String.format("%.2f", v.entryFilters.rsMin));
                                sb.append(" | RVOL≥").append(String.format("%.1f", v.entryFilters.rvolMin));
                                if (v.riskManagement != null) {
                                    sb.append(" | SL:").append(String.format("%.1f%%", v.riskManagement.stopLossPct));
                                    sb.append(" | TP:").append(String.format("%.1f%%", v.riskManagement.takeProfitPct));
                                }
                                sb.append("</div>");
                            }
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    }
                    sb.append("</div>");

                    // intraday-variants.json — uses IntradayVariantConfig with its own filter/risk classes
                    sb.append("<div>");
                    sb.append("<div style='font-size:12px;font-weight:600;color:#22c55e;margin-bottom:6px;'>⚡ intraday-variants.json");
                    if (intradayCfg != null) {
                        sb.append(" <span style='color:#6b7280;font-weight:400;'>— 15-min delay, VWAP-based</span>");
                    }
                    sb.append("</div>");
                    if (intradayCfg != null && intradayCfg.variants != null) {
                        sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:6px;'>");
                        for (DailyTradingSimulator.IntradayVariantConfig v : intradayCfg.variants) {
                            sb.append("<div style='background:#1f2a44;padding:8px;border-radius:6px;'>");
                            sb.append("<div style='font-weight:600;color:#86efac;font-size:12px;'>").append(escapeHtml(v.name)).append("</div>");
                            if (v.nameHe != null) sb.append("<div style='font-size:10px;color:#9ca3af;'>").append(escapeHtml(v.nameHe)).append("</div>");
                            if (v.entryFilters != null) {
                                sb.append("<div style='font-size:11px;color:#6b7280;margin-top:4px;'>");
                                sb.append("RSI: ").append((int)v.entryFilters.rsiMin).append("–").append((int)v.entryFilters.rsiMax);
                                sb.append(" | RS≥").append(String.format("%.2f", v.entryFilters.rsMin));
                                sb.append(" | RVOL≥").append(String.format("%.1f", v.entryFilters.rvolMin));
                                if (v.riskManagement != null) {
                                    sb.append(" | SL:").append(String.format("%.1f%%", v.riskManagement.stopLossMaxPct));
                                    sb.append(" | TP:").append(String.format("%.1f%%", v.riskManagement.takeProfitPct));
                                }
                                sb.append("</div>");
                            }
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    }
                    sb.append("</div>");

                    sb.append("</div>"); // end config info panel
                } catch (Exception ignore) {}

                sb.append("</div>");

                // Daily Trading Simulator Section
                sb.append("<div class='card' style='border:2px solid #22c55e;'>");
                sb.append("<div class='title'>🎯 Daily Trading Simulator - סימולציית מסחר יומית</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;' dir='rtl'>סורק 20 מניות אקראיות, בוחר 4 לאסטרטגיית Momentum ו-4 לאסטרטגיית Swing, קונה $1000 לכל מניה, ומנטר כל 15 דקות עם החלטות Stop Loss / Take Profit.</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;align-items:center;'>");
                sb.append("<button onclick='toggleDailyScheduler()' id='dtsSchedulerBtn' style='background:#f59e0b;'>📅 Enable Daily Auto-Scan</button>");
                sb.append("<button onclick='runDailyScanner(false)' id='dtsBtn' style='background:#22c55e;'>🔍 Run Scanner Now</button>");
                sb.append("<button onclick='runDailyScanner(true)' style='background:#f97316;'>🔄 Force Rescan</button>");
                sb.append("<button onclick='startMonitoring()' id='dtsMonBtn' style='background:#3b82f6;'>▶️ Start 15-min Monitor</button>");
                sb.append("<button onclick='stopMonitoring()' style='background:#6b7280;'>⏹️ Stop Monitor</button>");
                sb.append("<button onclick='refreshDailyTrades()' style='background:#8b5cf6;'>🔄 Refresh</button>");
                sb.append("<button onclick='clearDailyTrades()' style='background:#ef4444;'>🗑️ Clear All</button>");
                sb.append("</div>");
                sb.append("<div id='dtsSchedulerInfo' style='margin-bottom:12px;padding:10px;background:#1f2a44;border-radius:8px;display:none;'>");
                sb.append("<span style='color:#f59e0b;'>📅 Daily Auto-Scan: </span><span id='dtsNextScan' style='color:#e5e7eb;'></span>");
                sb.append("</div>");
                sb.append("<div id='dtsStatus' style='color:#9ca3af;font-size:13px;margin-bottom:12px;'></div>");
                
                // Performance Summary
                sb.append("<div id='dtsSummary' style='display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:12px;margin-bottom:16px;'></div>");
                sb.append("<div id='dtsRegimePanel' style='margin-bottom:16px;padding:14px 16px;background:#111827;border:1px solid #1f2a44;border-radius:10px;'></div>");
                
                // Open Positions Table
                sb.append("<div style='margin-bottom:16px;'>");
                sb.append("<div style='color:#22c55e;font-weight:600;margin-bottom:8px;'>📈 Open Positions</div>");
                sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr style='background:#0b1220;'>");
                sb.append("<th style='padding:8px;text-align:left;'>Symbol</th>");
                sb.append("<th style='padding:8px;text-align:center;'>Strategy</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Entry</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Current</th>");
                sb.append("<th style='padding:8px;text-align:right;'>P/L %</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Stop</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Target</th>");
                sb.append("<th style='padding:8px;text-align:center;'>Status</th>");
                sb.append("</tr></thead>");
                sb.append("<tbody id='dtsOpenTbody'><tr><td colspan='8' style='padding:10px;color:#9ca3af;text-align:center;'>No open positions</td></tr></tbody>");
                sb.append("</table></div></div>");
                
                // Closed Positions Table
                sb.append("<div>");
                sb.append("<div style='color:#ef4444;font-weight:600;margin-bottom:8px;'>📉 Closed Positions (Today)</div>");
                sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr style='background:#0b1220;'>");
                sb.append("<th style='padding:8px;text-align:left;'>Symbol</th>");
                sb.append("<th style='padding:8px;text-align:center;'>Strategy</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Entry</th>");
                sb.append("<th style='padding:8px;text-align:right;'>Exit</th>");
                sb.append("<th style='padding:8px;text-align:right;'>P/L %</th>");
                sb.append("<th style='padding:8px;text-align:right;'>P/L $</th>");
                sb.append("<th style='padding:8px;text-align:center;'>Exit Type</th>");
                sb.append("<th style='padding:8px;text-align:center;'>Time</th>");
                sb.append("</tr></thead>");
                sb.append("<tbody id='dtsClosedTbody'><tr><td colspan='8' style='padding:10px;color:#9ca3af;text-align:center;'>No closed positions</td></tr></tbody>");
                sb.append("</table></div></div>");
                
                // Variant Performance Comparison (A/B Testing)
                sb.append("<div style='margin-top:20px;border:2px solid #f59e0b;border-radius:8px;padding:16px;'>");
                sb.append("<div style='color:#f59e0b;font-weight:600;margin-bottom:12px;'>🏆 Variant Performance Comparison (A/B Testing)</div>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:12px;' dir='rtl'>השוואת ביצועים בין קונפיגורציות מומנטום שונות - הווריאנט המנצח מסומן ב-🥇</div>");
                sb.append("<div id='dtsVariantPerf'><div style='color:#9ca3af;text-align:center;padding:16px;'>Loading variant data...</div></div>");
                sb.append("</div>");
                
                sb.append("</div>");

                sb.append("<script>"+
                        "async function runBacktest(){"+
                        "  var ticker=document.getElementById('btTicker').value.trim().toUpperCase();"+
                        "  var balance=parseFloat(document.getElementById('btBalance').value)||36000;"+
                        "  if(!ticker){alert('הזן סימול מניה');return;}"+
                        "  var btn=document.getElementById('btBtn');"+
                        "  btn.disabled=true;btn.textContent='⏳ מריץ...';"+
                        "  try{"+
                        "    var r=await fetch('/api/backtest?ticker='+encodeURIComponent(ticker)+'&balance='+balance);"+
                        "    var d=await r.json();"+
                        "    btn.disabled=false;btn.textContent='🚀 הרץ Backtest';"+
                        "    if(d.error){alert('שגיאה: '+d.error);return;}"+
                        "    showBacktestResult(d);"+
                        "  }catch(e){btn.disabled=false;btn.textContent='🚀 הרץ Backtest';alert('שגיאה: '+e);}"+
                        "}"+
                        "function showBacktestResult(d){"+
                        "  var el=document.getElementById('btResult');el.style.display='block';"+
                        "  var alphaColor=d.alpha>=0?'#22c55e':'#ef4444';"+
                        "  var retColor=d.totalReturn>=0?'#22c55e':'#ef4444';"+
                        "  var html='<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:16px;\">';"+
                        "  html+='<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';"+
                        "  html+='<div style=\"font-size:28px;font-weight:700;color:'+retColor+';\">'+d.totalReturn.toFixed(1)+'%</div>';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:12px;\">תשואת האסטרטגיה</div></div>';"+
                        "  html+='<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';"+
                        "  html+='<div style=\"font-size:28px;font-weight:700;color:#f59e0b;\">'+d.spyReturn.toFixed(1)+'%</div>';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:12px;\">S&P 500 (Buy & Hold)</div></div>';"+
                        "  html+='<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;border:2px solid '+alphaColor+';\">';"+
                        "  html+='<div style=\"font-size:28px;font-weight:700;color:'+alphaColor+';\">'+(d.alpha>=0?'+':'')+d.alpha.toFixed(1)+'%</div>';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:12px;\">אלפא (מנצח את השוק?)</div></div>';"+
                        "  html+='<div style=\"text-align:center;padding:16px;background:#1f2a44;border-radius:8px;\">';"+
                        "  html+='<div style=\"font-size:24px;font-weight:600;color:#3b82f6;\">$'+d.finalBalance.toLocaleString('en-US',{maximumFractionDigits:0})+'</div>';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:12px;\">הון סופי (מתוך $'+d.initialBalance.toLocaleString()+')</div></div>';"+
                        "  html+='</div>';"+
                        "  html+='<div style=\"display:flex;flex-wrap:wrap;gap:16px;margin-bottom:12px;\">';"+
                        "  html+='<div style=\"flex:1;min-width:200px;padding:12px;background:#0b1220;border-radius:8px;\">';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:13px;margin-bottom:8px;\">📈 סטטיסטיקות</div>';"+
                        "  html+='<div>עסקאות: <b>'+d.totalTrades+'</b> ('+d.winningTrades+' רווח / '+d.losingTrades+' הפסד)</div>';"+
                        "  html+='<div>אחוז הצלחה: <b style=\"color:'+(d.winRate>=0.5?'#22c55e':'#ef4444')+';\">'+((d.winRate||0)*100).toFixed(0)+'%</b></div>';"+
                        "  html+='<div>Profit Factor: <b>'+(d.profitFactor||0).toFixed(2)+'</b></div>';"+
                        "  html+='</div>';"+
                        "  html+='<div style=\"flex:1;min-width:200px;padding:12px;background:#0b1220;border-radius:8px;\">';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:13px;margin-bottom:8px;\">⚠️ סיכון</div>';"+
                        "  html+='<div>Max Drawdown: <b style=\"color:#ef4444;\">-'+(d.maxDrawdown||0).toFixed(1)+'%</b></div>';"+
                        "  html+='<div>Sharpe Ratio: <b>'+(d.sharpeRatio||0).toFixed(2)+'</b></div>';"+
                        "  html+='<div>רווח ממוצע: <b style=\"color:#22c55e;\">$'+(d.avgWin||0).toFixed(0)+'</b> | הפסד: <b style=\"color:#ef4444;\">$'+(d.avgLoss||0).toFixed(0)+'</b></div>';"+
                        "  html+='</div>';"+
                        "  html+='<div style=\"flex:1;min-width:200px;padding:12px;background:#0b1220;border-radius:8px;\">';"+
                        "  html+='<div style=\"color:#9ca3af;font-size:13px;margin-bottom:8px;\">📅 תקופה</div>';"+
                        "  html+='<div>'+d.startDate+' עד '+d.endDate+'</div>';"+
                        "  html+='<div>'+d.tradingDays+' ימי מסחר</div>';"+
                        "  html+='</div></div>';"+
                        "  el.innerHTML=html;"+
                        "  drawEquityChart(d);"+
                        "  showTrades(d.trades);"+
                        "}"+
                        "function drawEquityChart(d){"+
                        "  var chartEl=document.getElementById('btChart');chartEl.style.display='block';"+
                        "  var eq=d.equityCurve||[];var spy=d.spyCurve||[];"+
                        "  if(eq.length===0){chartEl.innerHTML='<div style=\"padding:20px;color:#9ca3af;\">אין נתונים לגרף</div>';return;}"+
                        "  var maxEq=Math.max(...eq.map(p=>p.equity),...spy.map(p=>p.equity));"+
                        "  var minEq=Math.min(...eq.map(p=>p.equity),...spy.map(p=>p.equity));"+
                        "  var range=maxEq-minEq||1;"+
                        "  var w=chartEl.offsetWidth-40;var h=260;"+
                        "  var svg='<svg width=\"100%\" height=\"'+h+'\" viewBox=\"0 0 '+(w+40)+' '+h+'\">';"+
                        "  svg+='<text x=\"20\" y=\"20\" fill=\"#9ca3af\" font-size=\"12\">$'+maxEq.toLocaleString('en-US',{maximumFractionDigits:0})+'</text>';"+
                        "  svg+='<text x=\"20\" y=\"'+(h-10)+'\" fill=\"#9ca3af\" font-size=\"12\">$'+minEq.toLocaleString('en-US',{maximumFractionDigits:0})+'</text>';"+
                        "  var spyPath='M';"+
                        "  for(var i=0;i<spy.length;i++){"+
                        "    var x=40+(i/(spy.length-1||1))*w;"+
                        "    var y=h-20-((spy[i].equity-minEq)/range)*(h-40);"+
                        "    spyPath+=(i===0?'':' L')+x.toFixed(1)+','+y.toFixed(1);"+
                        "  }"+
                        "  svg+='<path d=\"'+spyPath+'\" stroke=\"#f59e0b\" stroke-width=\"2\" fill=\"none\" opacity=\"0.7\"/>';"+
                        "  var eqPath='M';"+
                        "  for(var i=0;i<eq.length;i++){"+
                        "    var x=40+(i/(eq.length-1||1))*w;"+
                        "    var y=h-20-((eq[i].equity-minEq)/range)*(h-40);"+
                        "    eqPath+=(i===0?'':' L')+x.toFixed(1)+','+y.toFixed(1);"+
                        "  }"+
                        "  svg+='<path d=\"'+eqPath+'\" stroke=\"#3b82f6\" stroke-width=\"2.5\" fill=\"none\"/>';"+
                        "  svg+='<circle cx=\"'+(w+30)+'\" cy=\"20\" r=\"6\" fill=\"#3b82f6\"/><text x=\"'+(w+10)+'\" y=\"24\" fill=\"#e5e7eb\" font-size=\"11\">Strategy</text>';"+
                        "  svg+='<circle cx=\"'+(w+30)+'\" cy=\"40\" r=\"6\" fill=\"#f59e0b\"/><text x=\"'+(w+10)+'\" y=\"44\" fill=\"#e5e7eb\" font-size=\"11\">S&P 500</text>';"+
                        "  svg+='</svg>';"+
                        "  chartEl.innerHTML=svg;"+
                        "}"+
                        "function showTrades(trades){"+
                        "  var el=document.getElementById('btTrades');"+
                        "  if(!trades||trades.length===0){el.style.display='none';return;}"+
                        "  el.style.display='block';"+
                        "  var html='<div style=\"color:#9ca3af;margin-bottom:8px;\">📋 היסטוריית עסקאות ('+trades.length+')</div>';"+
                        "  html+='<div style=\"max-height:200px;overflow-y:auto;\"><table style=\"width:100%;border-collapse:collapse;font-size:12px;\">';"+
                        "  html+='<thead><tr style=\"background:#0b1220;\"><th style=\"padding:6px;text-align:left;\">תאריך</th><th style=\"padding:6px;\">סוג</th><th style=\"padding:6px;text-align:right;\">מחיר</th><th style=\"padding:6px;text-align:right;\">מניות</th><th style=\"padding:6px;text-align:right;\">רווח/הפסד</th><th style=\"padding:6px;\">סיבה</th></tr></thead><tbody>';"+
                        "  for(var i=0;i<trades.length;i++){"+
                        "    var t=trades[i];"+
                        "    var typeColor=t.type==='BUY'?'#22c55e':'#ef4444';"+
                        "    var profitColor=(t.profit||0)>=0?'#22c55e':'#ef4444';"+
                        "    html+='<tr style=\"border-bottom:1px solid #1f2a44;\">';"+
                        "    html+='<td style=\"padding:6px;\">'+t.date+'</td>';"+
                        "    html+='<td style=\"padding:6px;color:'+typeColor+';font-weight:600;\">'+t.type+'</td>';"+
                        "    html+='<td style=\"padding:6px;text-align:right;\">$'+t.price.toFixed(2)+'</td>';"+
                        "    html+='<td style=\"padding:6px;text-align:right;\">'+t.shares+'</td>';"+
                        "    html+='<td style=\"padding:6px;text-align:right;color:'+profitColor+';\">'+(t.profit?((t.profit>=0?'+':'')+t.profit.toFixed(0)):'-')+'</td>';"+
                        "    html+='<td style=\"padding:6px;color:#9ca3af;\">'+(t.exitReason||'-')+'</td>';"+
                        "    html+='</tr>';"+
                        "  }"+
                        "  html+='</tbody></table></div>';"+
                        "  el.innerHTML=html;"+
                        "}"+
                        "var scPollInterval=null;"+
                        "async function runStrategyCompare(){"+
                        "  var count=parseInt(document.getElementById('scCount').value)||5;"+
                        "  var balance=parseFloat(document.getElementById('scBalance').value)||36000;"+
                        "  var source=document.getElementById('scSource').value||'random';"+
                        "  var btn=document.getElementById('scBtn');"+
                        "  var status=document.getElementById('scStatus');"+
                        "  btn.disabled=true;btn.textContent='⏳ מריץ ברקע...';"+
                        "  status.textContent=' מתחיל...';"+
                        "  try{"+
                        "    var r=await fetch('/api/strategy-compare/start?count='+count+'&balance='+balance+'&source='+source,{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    if(d.error){alert('שגיאה: '+d.error);btn.disabled=false;btn.textContent='🚀 הרץ השוואה';status.textContent='';return;}"+
                        "    if(scPollInterval)clearInterval(scPollInterval);"+
                        "    scPollInterval=setInterval(pollStrategyCompare,2000);"+
                        "    pollStrategyCompare();"+
                        "  }catch(e){btn.disabled=false;btn.textContent='🚀 הרץ השוואה';status.textContent='';alert('שגיאה: '+e);}"+
                        "}"+
                        "async function pollStrategyCompare(){"+
                        "  try{"+
                        "    var r=await fetch('/api/strategy-compare/status');"+
                        "    var d=await r.json();"+
                        "    var status=document.getElementById('scStatus');"+
                        "    var btn=document.getElementById('scBtn');"+
                        "    var wrap=document.getElementById('scProgressWrap');"+
                        "    var bar=document.getElementById('scProgressBar');"+
                        "    var txt=document.getElementById('scProgressText');"+
                        "    var pct=document.getElementById('scPct');"+
                        "    var cur=document.getElementById('scCurrentTicker');"+
                        "    if(d.running){"+
                        "      status.textContent=' '+d.progress+' ('+d.currentTicker+')';"+
                        "      if(wrap)wrap.style.display='block';"+
                        "      var parts=(d.progress||'').split('/');"+
                        "      var done=parseInt(parts[0])||0,tot=parseInt(parts[1])||1;"+
                        "      var p=Math.round((done/tot)*100);"+
                        "      if(bar)bar.style.width=p+'%';"+
                        "      if(txt)txt.textContent=done+' / '+tot;"+
                        "      if(pct)pct.textContent=p;"+
                        "      if(cur)cur.textContent=d.currentTicker||'—';"+
                        "    }else{"+
                        "      if(scPollInterval){clearInterval(scPollInterval);scPollInterval=null;}"+
                        "      btn.disabled=false;btn.textContent='🚀 הרץ השוואה';status.textContent='';"+
                        "      if(wrap)wrap.style.display='none';"+
                        "      if(d.results&&d.results.length>0)showStrategyCompareResults(d);"+
                        "    }"+
                        "  }catch(e){}"+
                        "}"+
                        "function showStrategyCompareResults(d){"+
                        "  var el=document.getElementById('scResult');el.style.display='block';"+
                        "  var html='<div style=\"margin-bottom:12px;color:#9ca3af;\">📊 תוצאות השוואה ('+d.results.length+' מניות × 3 אסטרטגיות)</div>';"+
                        "  html+='<div style=\"overflow-x:auto;\"><table style=\"width:100%;border-collapse:collapse;font-size:13px;\">';"+
                        "  html+='<thead><tr style=\"background:#0b1220;\">';"+
                        "  html+='<th style=\"padding:8px;text-align:left;\">מניה</th>';"+
                        "  html+='<th style=\"padding:8px;text-align:center;color:#22c55e;\">Long-Term</th>';"+
                        "  html+='<th style=\"padding:8px;text-align:center;color:#f59e0b;\">Swing</th>';"+
                        "  html+='<th style=\"padding:8px;text-align:center;color:#8b5cf6;\">Momentum</th>';"+
                        "  html+='<th style=\"padding:8px;text-align:center;\">S&P 500</th>';"+
                        "  html+='<th style=\"padding:8px;text-align:center;\">🏆 Best</th>';"+
                        "  html+='</tr></thead><tbody>';"+
                        "  var totals={longterm:0,swing:0,momentum:0,spy:0};"+
                        "  for(var i=0;i<d.results.length;i++){"+
                        "    var r=d.results[i];"+
                        "    var best=Math.max(r.longterm||0,r.swing||0,r.momentum||0);"+
                        "    var bestName=r.longterm===best?'Long-Term':(r.swing===best?'Swing':'Momentum');"+
                        "    var bestColor=r.longterm===best?'#22c55e':(r.swing===best?'#f59e0b':'#8b5cf6');"+
                        "    totals.longterm+=(r.longterm||0);totals.swing+=(r.swing||0);totals.momentum+=(r.momentum||0);totals.spy+=(r.spy||0);"+
                        "    html+='<tr style=\"border-bottom:1px solid #1f2a44;\">';"+
                        "    html+='<td style=\"padding:8px;font-weight:600;\">'+r.ticker+'</td>';"+
                        "    var ltT=r.ltTrades||0,swT=r.swTrades||0,moT=r.moTrades||0;"+
                        "    html+='<td style=\"padding:8px;text-align:center;color:'+(r.longterm>=0?'#22c55e':'#ef4444')+';\">'+(r.longterm>=0?'+':'')+r.longterm.toFixed(1)+'% <span style=\"color:#6b7280;font-size:11px;\">('+ltT+')</span></td>';"+
                        "    html+='<td style=\"padding:8px;text-align:center;color:'+(r.swing>=0?'#22c55e':'#ef4444')+';\">'+(r.swing>=0?'+':'')+r.swing.toFixed(1)+'% <span style=\"color:#6b7280;font-size:11px;\">('+swT+')</span></td>';"+
                        "    html+='<td style=\"padding:8px;text-align:center;color:'+(r.momentum>=0?'#22c55e':'#ef4444')+';\">'+(r.momentum>=0?'+':'')+r.momentum.toFixed(1)+'% <span style=\"color:#6b7280;font-size:11px;\">('+moT+')</span></td>';"+
                        "    html+='<td style=\"padding:8px;text-align:center;color:#9ca3af;\">'+(r.spy>=0?'+':'')+r.spy.toFixed(1)+'%</td>';"+
                        "    html+='<td style=\"padding:8px;text-align:center;font-weight:600;color:'+bestColor+';\">'+bestName+'</td>';"+
                        "    html+='</tr>';"+
                        "  }"+
                        "  var n=d.results.length||1;"+
                        "  var avgLT=totals.longterm/n,avgSW=totals.swing/n,avgMO=totals.momentum/n,avgSPY=totals.spy/n;"+
                        "  var bestAvg=Math.max(avgLT,avgSW,avgMO);"+
                        "  var bestAvgName=avgLT===bestAvg?'Long-Term':(avgSW===bestAvg?'Swing':'Momentum');"+
                        "  var bestAvgColor=avgLT===bestAvg?'#22c55e':(avgSW===bestAvg?'#f59e0b':'#8b5cf6');"+
                        "  html+='<tr style=\"background:#1f2a44;font-weight:700;\">';"+
                        "  html+='<td style=\"padding:8px;\">ממוצע</td>';"+
                        "  html+='<td style=\"padding:8px;text-align:center;color:'+(avgLT>=0?'#22c55e':'#ef4444')+';\">'+(avgLT>=0?'+':'')+avgLT.toFixed(1)+'%</td>';"+
                        "  html+='<td style=\"padding:8px;text-align:center;color:'+(avgSW>=0?'#22c55e':'#ef4444')+';\">'+(avgSW>=0?'+':'')+avgSW.toFixed(1)+'%</td>';"+
                        "  html+='<td style=\"padding:8px;text-align:center;color:'+(avgMO>=0?'#22c55e':'#ef4444')+';\">'+(avgMO>=0?'+':'')+avgMO.toFixed(1)+'%</td>';"+
                        "  html+='<td style=\"padding:8px;text-align:center;color:#9ca3af;\">'+(avgSPY>=0?'+':'')+avgSPY.toFixed(1)+'%</td>';"+
                        "  html+='<td style=\"padding:8px;text-align:center;color:'+bestAvgColor+';\">🏆 '+bestAvgName+'</td>';"+
                        "  html+='</tr></tbody></table></div>';"+
                        "  el.innerHTML=html;"+
                        "}"+
                        // Daily Trading Simulator JavaScript
                        "var dailySchedulerRunning=false;"+
                        "async function toggleDailyScheduler(){"+
                        "  var btn=document.getElementById('dtsSchedulerBtn');"+
                        "  btn.disabled=true;"+
                        "  try{"+
                        "    var endpoint=dailySchedulerRunning?'/api/daily-sim/scheduler/stop':'/api/daily-sim/scheduler/start';"+
                        "    var r=await fetch(endpoint,{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    btn.disabled=false;"+
                        "    if(d.error){alert('Error: '+d.error);return;}"+
                        "    dailySchedulerRunning=d.running||false;"+
                        "    updateSchedulerUI();"+
                        "    document.getElementById('dtsStatus').textContent=d.status||'';"+
                        "  }catch(e){btn.disabled=false;alert('Error: '+e);}"+
                        "}"+
                        "function updateSchedulerUI(){"+
                        "  var btn=document.getElementById('dtsSchedulerBtn');"+
                        "  var info=document.getElementById('dtsSchedulerInfo');"+
                        "  if(dailySchedulerRunning){"+
                        "    btn.style.background='#ef4444';"+
                        "    btn.textContent='⏹️ Disable Daily Auto-Scan';"+
                        "    info.style.display='block';"+
                        "  }else{"+
                        "    btn.style.background='#f59e0b';"+
                        "    btn.textContent='📅 Enable Daily Auto-Scan';"+
                        "    info.style.display='none';"+
                        "  }"+
                        "}"+
                        "async function runDailyScanner(force){"+
                        "  var btn=document.getElementById('dtsBtn');"+
                        "  btn.disabled=true;btn.textContent='⏳ Scanning...';"+
                        "  document.getElementById('dtsStatus').textContent='Starting scanner...';"+
                        "  try{"+
                        "    var url='/api/daily-sim/scan'+(force?'?force=true':'');"+
                        "    var r=await fetch(url,{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    btn.disabled=false;btn.textContent='🔍 Run Scanner Now';"+
                        "    if(d.error){alert('Error: '+d.error);return;}"+
                        "    document.getElementById('dtsStatus').textContent=d.status||'Scanner started';"+
                        "    setTimeout(refreshDailyTrades,3000);"+
                        "  }catch(e){btn.disabled=false;btn.textContent='🔍 Run Scanner Now';alert('Error: '+e);}"+
                        "}"+
                        "async function startMonitoring(){"+
                        "  var btn=document.getElementById('dtsMonBtn');"+
                        "  try{"+
                        "    var r=await fetch('/api/daily-sim/monitor/start',{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    document.getElementById('dtsStatus').textContent=d.status||'Monitoring started';"+
                        "  }catch(e){alert('Error: '+e);}"+
                        "}"+
                        "async function stopMonitoring(){"+
                        "  try{"+
                        "    var r=await fetch('/api/daily-sim/monitor/stop',{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    document.getElementById('dtsStatus').textContent=d.status||'Monitoring stopped';"+
                        "  }catch(e){alert('Error: '+e);}"+
                        "}"+
                        "async function refreshDailyTrades(){"+
                        "  try{"+
                        "    var r=await fetch('/api/daily-sim/trades');"+
                        "    var d=await r.json();"+
                        "    renderDailyTrades(d);"+
                        "  }catch(e){console.error('Refresh error:',e);}"+
                        "}"+
                        "async function toggleRegimeOverride(cb){"+
                        "  try{"+
                        "    var enabled=cb.checked;"+
                        "    var r=await fetch('/api/daily-sim/regime-override?enabled='+enabled,{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    if(d.error){cb.checked=!enabled;alert('Error: '+d.error);}"+
                        "    else{refreshDailyTrades();}"+
                        "  }catch(e){cb.checked=!cb.checked;alert('Error: '+e);}"+
                        "}"+
                        "async function clearDailyTrades(){"+
                        "  if(!confirm('Clear all trades?'))return;"+
                        "  try{"+
                        "    var r=await fetch('/api/daily-sim/clear',{method:'POST'});"+
                        "    refreshDailyTrades();"+
                        "  }catch(e){alert('Error: '+e);}"+
                        "}"+
                        "function renderDailyTrades(d){"+
                        "  var sumEl=document.getElementById('dtsSummary');"+
                        "  var s=d.summary||{};"+
                        "  var isWait=!!s.marketWaitMode;"+
                        "  var mrColor=isWait?'#ef4444':'#22c55e';"+
                        "  var mrLabel=isWait?'WAIT':'GO';"+
                        "  var mrReason=(s.marketWaitReason||'');"+
                        "  var spyLine='SPY '+((s.spyChangePct||0)>=0?'+':'')+(s.spyChangePct||0).toFixed(2)+'%';"+
                        "  sumEl.innerHTML='<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;\">'+"+
                        "    '<div style=\"font-size:20px;font-weight:700;color:#3b82f6;\">'+(s.openPositions||0)+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Open</div></div>'+"+
                        "  '<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;\">'+"+
                        "    '<div style=\"font-size:20px;font-weight:700;color:#22c55e;\">'+(s.winners||0)+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Winners</div></div>'+"+
                        "  '<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;\">'+"+
                        "    '<div style=\"font-size:20px;font-weight:700;color:#ef4444;\">'+(s.losers||0)+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Losers</div></div>'+"+
                        "  '<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;\">'+"+
                        "    '<div style=\"font-size:20px;font-weight:700;color:'+(s.totalPnL>=0?'#22c55e':'#ef4444')+';\">$'+(s.totalPnL||0).toFixed(0)+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Net Profit</div></div>'+"+
                        "  '<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;\">'+"+
                        "    '<div style=\"font-size:20px;font-weight:700;color:'+(s.totalPnL>=0?'#22c55e':'#ef4444')+';\">$'+(s.totalPnL||0).toFixed(0)+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Total P/L</div></div>'+"+
                        "  '<div style=\"text-align:center;padding:12px;background:#1f2a44;border-radius:8px;border:2px solid '+mrColor+';\">'+"+
                        "    '<div style=\"font-size:18px;font-weight:800;color:'+mrColor+';\">'+mrLabel+'</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:11px;\">Market Guard</div>'+"+
                        "    '<div style=\"color:#9ca3af;font-size:10px;margin-top:4px;\">'+spyLine+'</div>'+"+
                        "    (mrReason?'<div style=\"color:#9ca3af;font-size:10px;margin-top:2px;\">'+mrReason+'</div>':'')+"+
                        "  '</div>';"+
                        "  // --- Regime Panel ---"+
                        "  var rpEl=document.getElementById('dtsRegimePanel');"+
                        "  if(rpEl){"+
                        "    var regime=d.dailyRegime||'UNKNOWN';"+
                        "    var override=!!d.regimeOverride;"+
                        "    var explanation=d.regimeExplanation||'';"+
                        "    var checkedAt=d.regimeCheckedAt||'';"+
                        "    var openCount=(d.open||[]).length;"+
                        "    var closedCount=(d.closed||[]).length;"+
                        "    var hasTradestoday=openCount>0||closedCount>0;"+
                        "    var rejSummary=s.lastScanRejectionSummary||'';"+
                        "    var isTrending=regime==='TRENDING';"+
                        "    var isChoppy=regime==='CHOPPY';"+
                        "    var noTradesBlocked=isChoppy&&!override&&!hasTradestoday;"+
                        "    var regimeFlagColor=override?'#f59e0b':(isTrending?'#22c55e':'#ef4444');"+
                        "    var regimeFlag=override?'🟡':(isTrending?'🟢':'🔴');"+
                        "    var regimeFlagLabel=override?'OVERRIDE':(isTrending?'GREEN – TRENDING':'RED – CHOPPY');"+
                        "    rpEl.innerHTML="+
                        "      '<div style=\"display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;\">'+"+
                        "        '<div style=\"display:flex;align-items:center;gap:14px;\">'+"+
                        "          '<div style=\"font-size:32px;line-height:1;\">'+regimeFlag+'</div>'+"+
                        "          '<div>'+"+
                        "            '<div style=\"font-size:15px;font-weight:800;color:'+regimeFlagColor+';\">'+regimeFlagLabel+'</div>'+"+
                        "            '<div style=\"font-size:11px;color:#9ca3af;margin-top:3px;\">'+explanation+'</div>'+"+
                        "            (checkedAt?'<div style=\"font-size:10px;color:#6b7280;margin-top:2px;\">Checked: '+checkedAt+'</div>':'')+"+
                        "          '</div>'+"+
                        "        '</div>'+"+
                        "        '<div style=\"display:flex;align-items:center;gap:10px;background:#0b1220;padding:10px 16px;border-radius:8px;border:1px solid #374151;\">'+"+
                        "          '<label style=\"display:flex;align-items:center;gap:8px;cursor:pointer;font-size:13px;color:#e5e7eb;\">'+"+
                        "            '<input type=\"checkbox\" id=\"regimeOverrideCb\" '+(override?'checked':'')+' onchange=\"toggleRegimeOverride(this)\" style=\"width:16px;height:16px;cursor:pointer;\">'+"+
                        "            '<span>Regime info-only (uncheck to enforce blocking)</span>'+"+
                        "          '</label>'+"+
                        "        '</div>'+"+
                        "      '</div>'+"+
                        "      (noTradesBlocked?"+
                        "        '<div style=\"margin-top:12px;padding:10px 14px;background:#1f0a0a;border:1px solid #7f1d1d;border-radius:8px;color:#fca5a5;font-size:13px;\">'+"+
                        "          '<b>🔴 No trades today</b> – Regime is CHOPPY. Trend-following &amp; RS agents are blocked. '+"+
                        "          'Only pullback/mean-reversion agents may trade in this regime. '+"+
                        "          '<b>Check the override box above to force entries.</b>'+"+
                        "        '</div>' : '')+"+
                        "      (rejSummary?"+
                        "        '<div style=\"margin-top:10px;padding:10px 14px;background:#0f1a0f;border:1px solid #1f4d1f;border-radius:8px;\">'+"+
                        "          '<div style=\"color:#86efac;font-size:12px;font-weight:700;margin-bottom:6px;\">🔍 Why no new trades?</div>'+"+
                        "          '<div style=\"color:#d1fae5;font-size:12px;line-height:1.7;\">'+rejSummary+'</div>'+"+
                        "        '</div>' : '');"+
                        "  }"+
                        "  var openTb=document.getElementById('dtsOpenTbody');"+
                        "  var open=d.open||[];"+
                        "  if(open.length===0){openTb.innerHTML='<tr><td colspan=\"8\" style=\"padding:10px;color:#9ca3af;text-align:center;\">No open positions</td></tr>'; }"+
                        "  else{"+
                        "    var grouped={};"+
                        "    for(var i=0;i<open.length;i++){"+
                        "      var t=open[i]||{};"+
                        "      var key=(t.ticker||'').toUpperCase();"+
                        "      if(!key)continue;"+
                        "      if(!grouped[key])grouped[key]=[];"+
                        "      grouped[key].push(t);"+
                        "    }"+
                        "    var tickers=Object.keys(grouped).sort();"+
                        "    var rows='';"+
                        "    for(var k=0;k<tickers.length;k++){"+
                        "      var sym=tickers[k];"+
                        "      var arr=grouped[sym]||[];"+
                        "      var cur=arr[0]||{};"+
                        "      var entryMin=Number.POSITIVE_INFINITY,entryMax=0,stopMin=Number.POSITIVE_INFINITY,stopMax=0,targetMin=Number.POSITIVE_INFINITY,targetMax=0;"+
                        "      var pnlMin=Number.POSITIVE_INFINITY,pnlMax=Number.NEGATIVE_INFINITY;"+
                        "      var bestScore=0;"+
                        "      var namesSet={};"+
                        "      for(var j=0;j<arr.length;j++){"+
                        "        var x=arr[j]||{};"+
                        "        var ep=+x.entryPrice||0; if(ep>0){entryMin=Math.min(entryMin,ep);entryMax=Math.max(entryMax,ep);}"+
                        "        var sl=+x.stopLossPrice||0; if(sl>0){stopMin=Math.min(stopMin,sl);stopMax=Math.max(stopMax,sl);}"+
                        "        var tp=+x.takeProfitPrice||0; if(tp>0){targetMin=Math.min(targetMin,tp);targetMax=Math.max(targetMax,tp);}"+
                        "        var pl=+x.profitLossPct||0; pnlMin=Math.min(pnlMin,pl); pnlMax=Math.max(pnlMax,pl);"+
                        "        var sc=+x.momentumScore||0; bestScore=Math.max(bestScore,sc);"+
                        "        var nm=(x.name||x.strategyName||x.variantName||x.strategy||'').toString().trim();"+
                        "        if(nm)namesSet[nm]=true;"+
                        "      }"+
                        "      if(entryMin===Number.POSITIVE_INFINITY){entryMin=0;}"+
                        "      if(stopMin===Number.POSITIVE_INFINITY){stopMin=0;}"+
                        "      if(targetMin===Number.POSITIVE_INFINITY){targetMin=0;}"+
                        "      if(pnlMin===Number.POSITIVE_INFINITY){pnlMin=0;pnlMax=0;}"+
                        "      var names=Object.keys(namesSet).sort();"+
                        "      var namesHtml='';"+
                        "      for(var n=0;n<names.length;n++){namesHtml+='<div style=\"font-size:10px;color:#9ca3af;margin-top:2px;\">'+names[n]+'</div>'; }"+
                        "      var indicators='<span style=\"font-size:10px;color:#9ca3af;\">'+"+
                        "        'CCI:'+(cur.cci||0).toFixed(0)+' RS:'+(cur.rsRatio||0).toFixed(2)+"+
                        "        (cur.maCrossover?' ✓MA':'')+'</span>';"+
                        "      var variantBadge=cur.variantId?'<div style=\"font-size:9px;color:#60a5fa;margin-top:2px;\">'+cur.variantId+'</div>':'';"+
                        "      var entryTxt=entryMin>0?(entryMin===entryMax?('$'+entryMin.toFixed(2)):('$'+entryMin.toFixed(2)+' - $'+entryMax.toFixed(2))):'-';"+
                        "      var stopTxt=stopMin>0?(stopMin===stopMax?('$'+stopMin.toFixed(2)):('$'+stopMin.toFixed(2)+' - $'+stopMax.toFixed(2))):'-';"+
                        "      var targetTxt=targetMin>0?(targetMin===targetMax?('$'+targetMin.toFixed(2)):('$'+targetMin.toFixed(2)+' - $'+targetMax.toFixed(2))):'-';"+
                        "      var pnlTxt=(pnlMin===pnlMax?(pnlMax.toFixed(2)+'%'):(pnlMin.toFixed(2)+'% .. '+pnlMax.toFixed(2)+'%'));"+
                        "      var pnlColor=(pnlMax>=0?'#22c55e':'#ef4444');"+
                        "      var scoreColor=bestScore>=70?'#22c55e':(bestScore>=50?'#f59e0b':'#9ca3af');"+
                        "      rows+='<tr style=\"border-bottom:1px solid #1f2a44;\">'+"+
                        "        '<td style=\"padding:8px;\"><div style=\"font-weight:700;\">'+sym+'</div>'+namesHtml+indicators+variantBadge+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:center;\">'+"+
                        "          '<div style=\"font-size:11px;color:#e5e7eb;font-weight:600;\">'+names.length+' strategies</div>'+"+
                        "          '<div style=\"font-size:10px;color:'+scoreColor+';margin-top:2px;\">Best Score:'+bestScore+'</div>'+"+
                        "        '</td>'+"+
                        "        '<td style=\"padding:8px;text-align:right;\">'+entryTxt+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:right;\">$'+(+cur.currentPrice||0).toFixed(2)+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:right;color:'+pnlColor+';font-weight:600;\">'+pnlTxt+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:right;color:#ef4444;\">'+stopTxt+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:right;color:#22c55e;\">'+targetTxt+(cur.pivotR1>0?'<div style=\"font-size:10px;color:#9ca3af;\">R1:$'+cur.pivotR1.toFixed(2)+'</div>':'')+'</td>'+"+
                        "        '<td style=\"padding:8px;text-align:center;\"><span style=\"background:#3b82f6;padding:2px 8px;border-radius:4px;font-size:11px;\">HOLD</span></td>'+"+
                        "      '</tr>';"+
                        "    }"+
                        "    openTb.innerHTML=rows;"+
                        "  }"+
                        "  var closedTb=document.getElementById('dtsClosedTbody');"+
                        "  var closed=d.closed||[];"+
                        "  if(closed.length===0){closedTb.innerHTML='<tr><td colspan=\"8\" style=\"padding:10px;color:#9ca3af;text-align:center;\">No closed positions</td></tr>';}"+
                        "  else{var rows2='';for(var j=0;j<closed.length;j++){var c=closed[j];var pnlColor2=c.profitLossPct>=0?'#22c55e':'#ef4444';"+
                        "    var stratColor2=c.strategy==='MOMENTUM'?'#8b5cf6':'#f59e0b';"+
                        "    var exitColor=c.exitType==='TAKE_PROFIT'||c.exitType==='TRAILING_STOP'?'#22c55e':'#ef4444';"+
                        "    rows2+='<tr style=\"border-bottom:1px solid #1f2a44;\">'+"+
                        "      '<td style=\"padding:8px;font-weight:600;\">'+c.ticker+'</td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;\"><span style=\"background:'+stratColor2+';padding:2px 8px;border-radius:4px;font-size:11px;\">'+c.strategy+'</span></td>'+"+
                        "      '<td style=\"padding:8px;text-align:right;\">$'+c.entryPrice.toFixed(2)+'</td>'+"+
                        "      '<td style=\"padding:8px;text-align:right;\">$'+c.exitPrice.toFixed(2)+'</td>'+"+
                        "      '<td style=\"padding:8px;text-align:right;color:'+pnlColor2+';font-weight:600;\">'+(c.profitLossPct>=0?'+':'')+c.profitLossPct.toFixed(2)+'%</td>'+"+
                        "      '<td style=\"padding:8px;text-align:right;color:'+pnlColor2+';font-weight:600;\">$'+c.profitLossDollars.toFixed(0)+'</td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;\"><span style=\"background:'+exitColor+';padding:2px 8px;border-radius:4px;font-size:11px;\">'+(c.exitTypeDisplay||c.exitType)+'</span></td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;color:#9ca3af;font-size:12px;\">'+c.exitTime+'</td>'+"+
                        "    '</tr>';}closedTb.innerHTML=rows2;}"+
                        "  if(d.scannerStatus){document.getElementById('dtsStatus').textContent=d.scannerStatus;}"+
                        "  dailySchedulerRunning=d.dailySchedulerRunning||false;"+
                        "  updateSchedulerUI();"+
                        "  if(d.nextScheduledScan&&d.dailySchedulerRunning){document.getElementById('dtsNextScan').textContent='Next scan: '+d.nextScheduledScan;}"+
                        "  var vpEl=document.getElementById('dtsVariantPerf');"+
                        "  var vp=d.variantPerformance||[];"+
                        "  if(vp.length===0){vpEl.innerHTML='<div style=\"color:#9ca3af;text-align:center;padding:16px;\">No variant data yet. Run scanner to start A/B testing.</div>';}"+
                        "  else{var vpHtml='<table style=\"width:100%;border-collapse:collapse;font-size:12px;\">'+"+
                        "    '<thead><tr style=\"background:#1f2a44;\">'+"+
                        "    '<th style=\"padding:8px;text-align:left;\">🏆 Variant</th>'+"+
                        "    '<th style=\"padding:8px;text-align:center;\">Trades</th>'+"+
                        "    '<th style=\"padding:8px;text-align:center;\">Net Profit</th>'+"+
                        "    '<th style=\"padding:8px;text-align:right;\">P/L %</th>'+"+
                        "    '<th style=\"padding:8px;text-align:center;\">Profit Factor</th>'+"+
                        "    '</tr></thead><tbody>';"+
                        "  for(var v=0;v<vp.length;v++){var vd=vp[v];"+
                        "    var rank=v+1;var medal=rank===1?'🥇':rank===2?'🥈':rank===3?'🥉':'';"+
                        "    var pnlColor=vd.totalProfitLossDollars>=0?'#22c55e':'#ef4444';"+
                        "    var wrColor=vd.winRate>=50?'#22c55e':'#ef4444';"+
                        "    var pfColor=vd.profitFactor>=1.5?'#22c55e':(vd.profitFactor>=1?'#f59e0b':'#ef4444');"+
                        "    vpHtml+='<tr style=\"border-bottom:1px solid #1f2a44;\">'+"+
                        "      '<td style=\"padding:8px;\">'+medal+'<b>'+vd.variantName+'</b><div style=\"font-size:10px;color:#9ca3af;\">'+vd.variantId+'</div></td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;\">'+vd.totalTrades+' <span style=\"color:#22c55e;\">('+vd.winningTrades+'W)</span> <span style=\"color:#ef4444;\">('+vd.losingTrades+'L)</span></td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;color:'+pnlColor+';font-weight:600;\">$'+vd.totalProfitLossDollars.toFixed(0)+'</td>'+"+
                        "      '<td style=\"padding:8px;text-align:right;color:'+pnlColor+';\">'+(vd.totalProfitLossPct>=0?'+':'')+vd.totalProfitLossPct.toFixed(1)+'%</td>'+"+
                        "      '<td style=\"padding:8px;text-align:center;color:'+pfColor+';font-weight:600;\">'+vd.profitFactor.toFixed(2)+'</td>'+"+
                        "    '</tr>';}"+
                        "  vpHtml+='</tbody></table>';vpEl.innerHTML=vpHtml;}"+
                        "}"+
                        "refreshDailyTrades();setInterval(refreshDailyTrades,30000);"+
                        "</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/alpha-agent/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String pid = form.getOrDefault("pid", "");
                boolean createNew = true;
                try {
                    String cn = form.getOrDefault("createNew", "1");
                    createNew = cn == null || cn.isBlank() || !cn.trim().equals("0");
                } catch (Exception ignore) {}
                int days = ALPHA_AGENT_DEFAULT_TRACKING_DAYS;
                try {
                    String v = form.getOrDefault("evaluationPeriodDays", "");
                    if (v != null && !v.isBlank()) days = Integer.parseInt(v.trim());
                } catch (Exception ignore) {
                }
                AlphaAgentPortfolio created;
                synchronized (alphaAgentLock) {
                    created = bestEffortStartAlphaAgentPortfolioAsync(days, pid, createNew);
                }
                bestEffortUpdateAlphaAgentPortfolioNow();
                String status = (created != null && created.lastError != null && created.lastError.contains("max ")) ? "limit" : "started";
                String active = bestEffortGetActiveAlphaAgentPortfolioId();
                ex.getResponseHeaders().add("Location", "/alpha-agent?status=" + status + "&days=" + days + (active == null ? "" : ("&pid=" + urlEncode(active))));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/alpha-agent/status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String pid = qp.getOrDefault("pid", "");
                AlphaAgentPortfolio pf;
                synchronized (alphaAgentLock) { pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio(); }
                Map<String,Object> out = new HashMap<>();
                out.put("pid", pid);
                out.put("lastError", pf == null ? null : pf.lastError);
                out.put("lastUpdatedNy", pf == null ? null : pf.lastUpdatedNy);
                out.put("createdAtNy", pf == null ? null : pf.createdAtNy);
                respondJson(ex, out, 200);
            }
        });

        server.createContext("/alpha-agent/stocks", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String pid = qp.getOrDefault("pid", "");
                AlphaAgentPortfolio pf;
                synchronized (alphaAgentLock) { pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio(); }
                List<Map<String,Object>> out = new ArrayList<>();
                if (pf != null && pf.positions != null) {
                    for (AlphaAgentPosition p : pf.positions) {
                        if (p == null || p.ticker == null) continue;
                        Double net = pctReturn(p.startPrice, p.lastPrice);
                        Map<String,Object> row = new HashMap<>();
                        row.put("symbol", p.ticker);
                        row.put("entryDate", p.startNyDate);
                        row.put("entryPrice", p.startPrice);
                        row.put("currentPrice", p.lastPrice);
                        row.put("netProfitPct", net);
                        row.put("lastNyDate", p.lastNyDate);
                        out.add(row);
                    }
                }
                respondJson(ex, out, 200);
            }
        });

        server.createContext("/alpha-agent/index-performance", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String pid = qp.getOrDefault("pid", "");
                AlphaAgentPortfolio pf;
                synchronized (alphaAgentLock) { pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio(); }
                List<Map<String,Object>> out = new ArrayList<>();
                if (pf != null) {
                    if (pf.benchNasdaq100 != null) {
                        AlphaAgentPosition p = pf.benchNasdaq100;
                        Double net = pctReturn(p.startPrice, p.lastPrice);
                        Map<String,Object> row = new HashMap<>();
                        row.put("indexName", "NASDAQ-100 (QQQ)");
                        row.put("entryValue", p.startPrice);
                        row.put("currentValue", p.lastPrice);
                        row.put("netProfitPct", net);
                        out.add(row);
                    }
                    if (pf.benchSp500 != null) {
                        AlphaAgentPosition p = pf.benchSp500;
                        Double net = pctReturn(p.startPrice, p.lastPrice);
                        Map<String,Object> row = new HashMap<>();
                        row.put("indexName", "S&P 500 (SPY)");
                        row.put("entryValue", p.startPrice);
                        row.put("currentValue", p.lastPrice);
                        row.put("netProfitPct", net);
                        out.add(row);
                    }
                }
                respondJson(ex, out, 200);
            }
        });

        server.createContext("/alpha-agent/race-result", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String pid = qp.getOrDefault("pid", "");
                AlphaAgentPortfolio pf;
                synchronized (alphaAgentLock) { pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio(); }
                Map<String,Object> out = new HashMap<>();
                if (pf == null || pf.positions == null || pf.positions.isEmpty()) {
                    out.put("message", "No active AlphaAgent portfolio. Click Start.");
                    respondJson(ex, out, 200);
                    out.put("alphaAgentAvgPct", null);
                    out.put("nasdaq100Pct", null);
                    out.put("sp500Pct", null);
                    respondJson(ex, out, 200);
                    return;
                }
                double sum = 0.0;
                int cnt = 0;
                for (AlphaAgentPosition p : pf.positions) {
                    Double net = pctReturn(p.startPrice, p.lastPrice);
                    if (net == null || Double.isNaN(net)) continue;
                    sum += net;
                    cnt++;
                }
                Double avg = cnt == 0 ? null : (sum / cnt);
                Double qqq = (pf.benchNasdaq100 == null) ? null : pctReturn(pf.benchNasdaq100.startPrice, pf.benchNasdaq100.lastPrice);
                Double spy = (pf.benchSp500 == null) ? null : pctReturn(pf.benchSp500.startPrice, pf.benchSp500.lastPrice);
                out.put("alphaAgentAvgPct", avg);
                out.put("nasdaq100Pct", qqq);
                out.put("sp500Pct", spy);
                String msg;
                if (avg == null) msg = "AlphaAgent performance not available yet.";
                else {
                    double best = avg;
                    String winner = "MomentumAiAgent";
                    if (qqq != null && qqq > best) { best = qqq; winner = "NASDAQ-100 (QQQ)"; }
                    if (spy != null && spy > best) { best = spy; winner = "S&P 500 (SPY)"; }
                    msg = winner.equals("MomentumAiAgent") ? "MomentumAiAgent is leading the race" : (winner + " outperforms MomentumAiAgent");
                }
                out.put("message", msg);
                respondJson(ex, out, 200);
            }
        });

        // ---------------- Daily Trading Simulator API Endpoints ----------------
        // Load simulator store on startup
        DailyTradingSimulator.loadStore();

        server.createContext("/api/daily-sim/scan", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    // Check for force parameter
                    String query = ex.getRequestURI().getQuery();
                    boolean force = query != null && query.contains("force=true");
                    
                    MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                    String interval = intradayIntervalForCurrentEntitlement();
                    DailyTradingSimulator.runDailyScanner(av, interval, force);
                    respondJson(ex, Map.of("status", force ? "Force rescan started" : "Scanner started in background"), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/trades", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                try {
                    Map<String, Object> out = new LinkedHashMap<>();
                    
                    // Open trades
                    List<Map<String, Object>> openList = new ArrayList<>();
                    for (DailyTradingSimulator.SimulatedTrade t : DailyTradingSimulator.getOpenTrades()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ticker", t.ticker);
                        row.put("tickerName", t.ticker);
                        row.put("strategy", t.strategy != null ? t.strategy.name() : "UNKNOWN");
                        row.put("name", t.variantName != null ? t.variantName : (t.strategy != null ? t.strategy.name() : "UNKNOWN"));
                        row.put("strategyName", t.variantName != null ? t.variantName : (t.strategy != null ? t.strategy.name() : "UNKNOWN"));
                        row.put("entryDate", t.entryDate);
                        row.put("entryTime", t.entryTime);
                        row.put("entryPrice", t.entryPrice);
                        row.put("currentPrice", t.currentPrice);
                        row.put("profitLossPct", t.profitLossPct);
                        row.put("profitLossDollars", t.profitLossDollars);
                        row.put("stopLossPrice", t.stopLossPrice);
                        row.put("takeProfitPrice", t.takeProfitPrice);
                        row.put("trailingStopPrice", t.trailingStopPrice);
                        row.put("highestPrice", t.highestPrice);
                        row.put("rvol", t.rvol);
                        row.put("rsi", t.rsi);
                        // Enhanced momentum indicators
                        row.put("cci", t.cci);
                        row.put("cciBreakout", t.cciBreakout);
                        row.put("rsRatio", t.rsRatio);
                        row.put("maCrossover", t.maCrossover);
                        row.put("pivotR1", t.pivotR1);
                        row.put("pivotR2", t.pivotR2);
                        row.put("pivotS1", t.pivotS1);
                        row.put("momentumScore", t.momentumScore);
                        row.put("entryReason", t.entryReason);
                        row.put("variantId", t.variantId);
                        row.put("variantName", t.variantName);
                        openList.add(row);
                    }
                    out.put("open", openList);
                    
                    // Closed trades (today only)
                    List<Map<String, Object>> closedList = new ArrayList<>();
                    for (DailyTradingSimulator.SimulatedTrade t : DailyTradingSimulator.getClosedTrades()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ticker", t.ticker);
                        row.put("tickerName", t.ticker);
                        row.put("strategy", t.strategy != null ? t.strategy.name() : "UNKNOWN");
                        row.put("name", t.variantName != null ? t.variantName : (t.strategy != null ? t.strategy.name() : "UNKNOWN"));
                        row.put("strategyName", t.variantName != null ? t.variantName : (t.strategy != null ? t.strategy.name() : "UNKNOWN"));
                        row.put("entryDate", t.entryDate);
                        row.put("entryTime", t.entryTime);
                        row.put("entryPrice", t.entryPrice);
                        row.put("exitDate", t.exitDate);
                        row.put("exitTime", t.exitTime);
                        row.put("exitPrice", t.exitPrice);
                        row.put("profitLossPct", t.profitLossPct);
                        row.put("profitLossDollars", t.profitLossDollars);
                        row.put("exitType", t.exitType != null ? t.exitType.name() : "UNKNOWN");
                        row.put("exitTypeDisplay", t.exitType != null ? t.exitType.getDisplay() : "Unknown");
                        row.put("variantId", t.variantId);
                        row.put("variantName", t.variantName);
                        closedList.add(row);
                    }
                    out.put("closed", closedList);
                    
                    // Summary
                    out.put("summary", DailyTradingSimulator.getPerformanceSummary());
                    
                    // Variant Performance for A/B testing comparison
                    DailyTradingSimulator.updateVariantPerformance();
                    List<Map<String, Object>> variantsList = new ArrayList<>();
                    for (DailyTradingSimulator.VariantPerformance vp : DailyTradingSimulator.getVariantPerformanceRanked()) {
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("variantId", vp.variantId);
                        v.put("variantName", vp.variantName);
                        v.put("totalTrades", vp.totalTrades);
                        v.put("winningTrades", vp.winningTrades);
                        v.put("losingTrades", vp.losingTrades);
                        v.put("winRate", vp.winRate);
                        v.put("totalProfitLossPct", vp.totalProfitLossPct);
                        v.put("totalProfitLossDollars", vp.totalProfitLossDollars);
                        v.put("avgWinPct", vp.avgWinPct);
                        v.put("avgLossPct", vp.avgLossPct);
                        v.put("profitFactor", vp.profitFactor);
                        variantsList.add(v);
                    }
                    out.put("variantPerformance", variantsList);
                    
                    // Scanner status
                    DailyTradingSimulator.SimulatorStore store = DailyTradingSimulator.getStore();
                    out.put("scannerStatus", store.scannerStatus);
                    out.put("lastScanDate", store.lastScanDate);
                    out.put("lastScanTime", store.lastScanTime);
                    out.put("lastUpdateTime", store.lastUpdateTime);
                    // Daily Regime data
                    out.put("dailyRegime", store.dailyRegime != null ? store.dailyRegime : "UNKNOWN");
                    out.put("regimeAdx", store.regimeAdx);
                    out.put("regimeSpyAboveVwap", store.regimeSpyAboveVwap);
                    out.put("regimeAtrToday", store.regimeAtrToday);
                    out.put("regimeAtr20Avg", store.regimeAtr20Avg);
                    out.put("regimeExplanation", store.regimeExplanation);
                    out.put("regimeOverride", store.regimeOverride);
                    out.put("regimeCheckedAt", store.regimeCheckedAt);
                    out.put("marketGuardEnabled", store.marketGuardEnabled);
                    out.put("success2026GuardEnabled", store.success2026GuardEnabled);
                    
                    // Daily scheduler status
                    out.put("dailySchedulerRunning", DailyTradingSimulator.isDailySchedulerRunning());
                    out.put("nextScheduledScan", DailyTradingSimulator.getNextScheduledScanTime());
                    
                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/monitor/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                    DailyTradingSimulator.startMonitoring(av);
                    respondJson(ex, Map.of("status", "15-minute monitoring started"), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/monitor/stop", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    DailyTradingSimulator.stopMonitoring();
                    respondJson(ex, Map.of("status", "Monitoring stopped"), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/clear", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    DailyTradingSimulator.clearAllTrades();
                    respondJson(ex, Map.of("status", "All trades cleared"), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/scheduler/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                    String interval = intradayIntervalForCurrentEntitlement();
                    DailyTradingSimulator.startDailyScheduler(av, interval);
                    String nextScan = DailyTradingSimulator.getNextScheduledScanTime();
                    respondJson(ex, Map.of(
                        "status", "Daily scheduler started",
                        "nextScan", nextScan,
                        "running", true
                    ), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/scheduler/stop", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    DailyTradingSimulator.stopDailyScheduler();
                    DailyTradingSimulator.stopMonitoring();
                    respondJson(ex, Map.of("status", "Daily scheduler stopped", "running", false), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/regime-override", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                    boolean override = "true".equalsIgnoreCase(qp.getOrDefault("enabled", "false"));
                    DailyTradingSimulator.setRegimeOverride(override);
                    respondJson(ex, Map.of("status", "ok", "regimeOverride", override), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/market-guard", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                    boolean enabled = "true".equalsIgnoreCase(qp.getOrDefault("enabled", "true"));
                    DailyTradingSimulator.setMarketGuardEnabled(enabled);
                    respondJson(ex, Map.of("status", "ok", "marketGuardEnabled", enabled), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/api/daily-sim/success2026-guard", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                    boolean enabled = "true".equalsIgnoreCase(qp.getOrDefault("enabled", "true"));
                    DailyTradingSimulator.setSuccess2026GuardEnabled(enabled);
                    respondJson(ex, Map.of("status", "ok", "success2026GuardEnabled", enabled), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        // ---------------- Backtest API Endpoint ----------------
        server.createContext("/api/backtest", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String ticker = qp.getOrDefault("ticker", "").trim().toUpperCase();
                double balance = 36000;
                try { balance = Double.parseDouble(qp.getOrDefault("balance", "36000")); } catch (Exception ignore) {}

                if (ticker.isEmpty()) {
                    respondJson(ex, Map.of("error", "Missing ticker parameter"), 400);
                    return;
                }

                try {
                    StrategyBacktester.BacktestResult result = StrategyBacktester.runBacktestForTicker(ticker, balance);
                    
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ticker", result.ticker);
                    out.put("initialBalance", result.initialBalance);
                    out.put("finalBalance", result.finalBalance);
                    out.put("totalReturn", result.totalReturn);
                    out.put("spyReturn", result.spyReturn);
                    out.put("alpha", result.alpha);
                    out.put("totalTrades", result.totalTrades);
                    out.put("winningTrades", result.winningTrades);
                    out.put("losingTrades", result.losingTrades);
                    out.put("winRate", result.winRate);
                    out.put("maxDrawdown", result.maxDrawdown);
                    out.put("sharpeRatio", result.sharpeRatio);
                    out.put("profitFactor", result.profitFactor);
                    out.put("avgWin", result.avgWin);
                    out.put("avgLoss", result.avgLoss);
                    out.put("startDate", result.startDate);
                    out.put("endDate", result.endDate);
                    out.put("tradingDays", result.tradingDays);

                    // Equity curve for chart
                    List<Map<String, Object>> equityCurve = new ArrayList<>();
                    for (StrategyBacktester.EquityPoint ep : result.equityCurve) {
                        Map<String, Object> pt = new LinkedHashMap<>();
                        pt.put("day", ep.dayIndex);
                        pt.put("date", ep.date);
                        pt.put("equity", ep.equity);
                        pt.put("drawdown", ep.drawdown);
                        equityCurve.add(pt);
                    }
                    out.put("equityCurve", equityCurve);

                    // SPY curve for comparison
                    List<Map<String, Object>> spyCurve = new ArrayList<>();
                    for (StrategyBacktester.EquityPoint ep : result.spyCurve) {
                        Map<String, Object> pt = new LinkedHashMap<>();
                        pt.put("day", ep.dayIndex);
                        pt.put("date", ep.date);
                        pt.put("equity", ep.equity);
                        spyCurve.add(pt);
                    }
                    out.put("spyCurve", spyCurve);

                    // Trades
                    List<Map<String, Object>> trades = new ArrayList<>();
                    for (StrategyBacktester.Trade t : result.trades) {
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("type", t.type);
                        tr.put("day", t.dayIndex);
                        tr.put("date", t.date);
                        tr.put("price", t.price);
                        tr.put("shares", t.shares);
                        if (t.stopLoss > 0) tr.put("stopLoss", t.stopLoss);
                        if (t.profit != 0) tr.put("profit", t.profit);
                        if (t.profitPct != 0) tr.put("profitPct", t.profitPct);
                        if (t.exitReason != null) tr.put("exitReason", t.exitReason);
                        trades.add(tr);
                    }
                    out.put("trades", trades);

                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Backtest failed: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Agent Backtest API Endpoint ----------------
        server.createContext("/api/agent-backtest", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                
                String agentId = qp.getOrDefault("agentId", "").trim();
                String startDate = qp.getOrDefault("startDate", "").trim();
                String endDate = qp.getOrDefault("endDate", "").trim();
                double initialCapital = 100000;
                String tickersParam = qp.get("tickers");
                
                try { initialCapital = Double.parseDouble(qp.getOrDefault("initialCapital", "100000")); } catch (Exception ignore) {}
                
                if (agentId.isEmpty()) {
                    respondJson(ex, Map.of("error", "Missing agentId parameter"), 400);
                    return;
                }
                
                if (startDate.isEmpty()) {
                    // Default to 1 year ago
                    LocalDate oneYearAgo = LocalDate.now().minusYears(1);
                    startDate = oneYearAgo.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                
                if (endDate.isEmpty()) {
                    // Default to today
                    endDate = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                
                List<String> tickerUniverse = null;
                if (tickersParam != null && !tickersParam.isEmpty()) {
                    tickerUniverse = Arrays.asList(tickersParam.split(","));
                }

                try {
                    AgentBacktester.AgentBacktestResult result = AgentBacktester.backtestAgent(
                        agentId, startDate, endDate, tickerUniverse, initialCapital
                    );
                    
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("agentId", result.agentId);
                    out.put("agentName", result.agentName);
                    out.put("startDate", result.startDate);
                    out.put("endDate", result.endDate);
                    out.put("totalTrades", result.totalTrades);
                    out.put("winningTrades", result.winningTrades);
                    out.put("losingTrades", result.losingTrades);
                    out.put("winRate", result.winRate);
                    out.put("totalProfit", result.totalProfit);
                    out.put("totalLoss", result.totalLoss);
                    out.put("netProfit", result.netProfit);
                    out.put("profitFactor", result.profitFactor);
                    out.put("avgWin", result.avgWin);
                    out.put("avgLoss", result.avgLoss);
                    out.put("maxDrawdown", result.maxDrawdown);
                    out.put("expectancy", result.expectancy);
                    out.put("sharpeRatio", result.sharpeRatio);
                    out.put("cagr", result.cagr);
                    
                    // Trades
                    List<Map<String, Object>> trades = new ArrayList<>();
                    for (AgentBacktester.BacktestTrade t : result.trades) {
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("entryDate", t.entryDate);
                        tr.put("exitDate", t.exitDate);
                        tr.put("ticker", t.ticker);
                        tr.put("agentId", t.agentId);
                        tr.put("agentName", t.agentName);
                        tr.put("entryPrice", t.entryPrice);
                        tr.put("exitPrice", t.exitPrice);
                        tr.put("profitPct", t.profitPct);
                        tr.put("profitAmount", t.profitAmount);
                        tr.put("daysHeld", t.daysHeld);
                        tr.put("exitReason", t.exitReason);
                        tr.put("setupType", t.setupType);
                        trades.add(tr);
                    }
                    out.put("trades", trades);
                    
                    // Trades by month
                    out.put("tradesByMonth", result.tradesByMonth);
                    
                    // Profit by ticker
                    out.put("profitByTicker", result.profitByTicker);
                    
                    if (result.error != null) {
                        out.put("error", result.error);
                    }
                    
                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Agent backtest failed: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Fetch Market Data API Endpoint ----------------
        server.createContext("/api/fetch-market-data", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String sector = qp.getOrDefault("sector", "").trim();
                String monthsBackStr = qp.getOrDefault("monthsBack", "6").trim();
                
                Integer monthsBack;
                try { monthsBack = Integer.parseInt(monthsBackStr); } catch (Exception ignore) { monthsBack = 6; }
                
                // Get tickers from LongTermCandidateFinder based on sector
                List<String> tickers = new ArrayList<>();
                if (sector.isEmpty() || "ALL".equalsIgnoreCase(sector)) {
                    // Get all tickers from all sectors
                    tickers.addAll(LongTermCandidateFinder.TECHNOLOGY_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.FINANCIALS_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.HEALTHCARE_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.ENERGY_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.INDUSTRIALS_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.CONSUMER_DISCRETIONARY_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.CONSUMER_STAPLES_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.UTILITIES_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.MATERIALS_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.REAL_ESTATE_TICKERS);
                    tickers.addAll(LongTermCandidateFinder.COMMUNICATION_SERVICES_TICKERS);
                } else {
                    // Get tickers from specific sector
                    LongTermCandidateFinder.Sector sectorEnum = null;
                    try {
                        sectorEnum = LongTermCandidateFinder.Sector.valueOf(sector.toUpperCase());
                        tickers = LongTermCandidateFinder.getTickersForSector(sectorEnum);
                    } catch (Exception e) {
                        respondJson(ex, Map.of("error", "Invalid sector: " + sector), 400);
                        return;
                    }
                }
                
                // Remove duplicates
                tickers = new ArrayList<>(new LinkedHashSet<>(tickers));
                final List<String> finalTickers = tickers;
                final int finalMonthsBack = monthsBack;

                try {
                    // Set progress tracking
                    synchronized (marketDataFetchLock) {
                        marketDataFetchRunning = true;
                        marketDataFetchProgress = 0;
                        marketDataFetchTotal = tickers.size();
                        marketDataFetchCurrentTicker = "";
                        marketDataFetchStatus = "starting";
                    }
                    
                    // Set progress callback
                    MarketDataCache.setProgressCallback((current, total, currentTicker) -> {
                        synchronized (marketDataFetchLock) {
                            marketDataFetchProgress = current;
                            marketDataFetchCurrentTicker = currentTicker;
                            marketDataFetchStatus = "fetching";
                        }
                    });
                    
                    // Start async data fetch
                    new Thread(() -> {
                        try {
                            synchronized (marketDataFetchLock) {
                                marketDataFetchStatus = "in_progress";
                            }
                            MarketDataCache.fetchAndCacheData(finalTickers, finalMonthsBack);
                            synchronized (marketDataFetchLock) {
                                marketDataFetchRunning = false;
                                marketDataFetchStatus = "completed";
                                marketDataFetchProgress = finalTickers.size();
                            }
                        } catch (Exception e) {
                            System.err.println("[FetchMarketData] Error: " + e.getMessage());
                            synchronized (marketDataFetchLock) {
                                marketDataFetchRunning = false;
                                marketDataFetchStatus = "error";
                            }
                        }
                    }).start();
                    
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "started");
                    out.put("tickers", tickers.size());
                    out.put("monthsBack", monthsBack);
                    out.put("message", "Fetching data for " + tickers.size() + " tickers (last " + monthsBack + " months). This will take several minutes due to API rate limits.");
                    
                    respondJson(ex, out, 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Failed to start data fetch: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Market Data Fetch Status Endpoint ----------------
        server.createContext("/api/fetch-market-data-status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                Map<String, Object> status = new LinkedHashMap<>();
                synchronized (marketDataFetchLock) {
                    status.put("running", marketDataFetchRunning);
                    status.put("progress", marketDataFetchProgress);
                    status.put("total", marketDataFetchTotal);
                    status.put("currentTicker", marketDataFetchCurrentTicker);
                    status.put("status", marketDataFetchStatus);
                }
                
                respondJson(ex, status, 200);
            }
        });

        // ---------------- Get Available Agents API Endpoint ----------------
        server.createContext("/api/agents", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                try {
                    List<Map<String, String>> agents = AgentBacktester.getAvailableAgents();
                    respondJson(ex, Map.of("agents", agents), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Failed to get agents: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Get Universe Tickers API Endpoint ----------------
        server.createContext("/api/universe-tickers", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                try {
                    List<String> tickers = LongTermCandidateFinder.getUniverseTickers();
                    String tickerString = String.join(",", tickers);
                    respondJson(ex, Map.of("tickers", tickerString, "count", tickers.size()), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Failed to get universe tickers: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Cache Status API Endpoint ----------------
        server.createContext("/api/cache-status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                try {
                    synchronized (cacheBuildLock) {
                        Map<String, Object> status = new LinkedHashMap<>();
                        status.put("running", cacheBuildRunning);
                        status.put("status", cacheBuildStatus);
                        status.put("progress", cacheBuildProgress);
                        status.put("total", cacheBuildTotal);
                        status.put("startDate", cacheBuildStartDate);
                        status.put("endDate", cacheBuildEndDate);
                        
                        // Check if cache file exists
                        MarketDataCache.MarketCache existingCache = MarketDataCache.loadCache();
                        if (existingCache != null) {
                            status.put("cacheExists", true);
                            status.put("cacheLastUpdated", existingCache.lastUpdated);
                            status.put("cacheTickers", existingCache.totalTickers);
                        } else {
                            status.put("cacheExists", false);
                        }
                        
                        respondJson(ex, status, 200);
                    }
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Failed to get cache status: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Cache Build Trigger API Endpoint ----------------
        server.createContext("/api/cache-build/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                
                try {
                    synchronized (cacheBuildLock) {
                        if (cacheBuildRunning) {
                            respondJson(ex, Map.of("error", "Cache build already running"), 400);
                            return;
                        }
                        cacheBuildRunning = true;
                        cacheBuildStatus = "starting";
                        cacheBuildProgress = 0;
                        cacheBuildTotal = 0;
                        cacheBuildStartDate = "2025-01-01";
                        cacheBuildEndDate = java.time.LocalDate.now().toString();
                    }
                    
                    // Start cache building in background
                    Thread cacheThread = new Thread(() -> {
                        try {
                            System.out.println("[CacheBuild] Manual cache build triggered");
                            
                            synchronized (cacheBuildLock) {
                                cacheBuildStatus = "in_progress";
                            }

                            // Get universe tickers
                            List<String> tickers = LongTermCandidateFinder.getUniverseTickers();
                            synchronized (cacheBuildLock) {
                                cacheBuildTotal = tickers.size();
                                cacheBuildProgress = 0;
                            }
                            System.out.println("[CacheBuild] Building cache for " + tickers.size() + " tickers");
                            System.out.println("[CacheBuild] First 10 tickers: " + tickers.subList(0, Math.min(10, tickers.size())));

                            // Actually build the cache with real data
                            MarketDataCache.fetchAndCacheData(tickers);
                            
                            // Verify file was created
                            java.io.File cacheFile = new java.io.File("market_data_cache.json");
                            if (cacheFile.exists()) {
                                System.out.println("[CacheBuild] Cache file created successfully: " + cacheFile.getAbsolutePath() + " (" + cacheFile.length() + " bytes)");
                            } else {
                                System.err.println("[CacheBuild] WARNING: Cache file was not created!");
                            }
                            
                            synchronized (cacheBuildLock) {
                                cacheBuildProgress = tickers.size();
                                cacheBuildStatus = "completed";
                                cacheBuildRunning = false;
                            }
                            System.out.println("[CacheBuild] Cache building completed");
                            
                        } catch (Exception e) {
                            System.err.println("[CacheBuild] Error: " + e.getMessage());
                            e.printStackTrace();
                            synchronized (cacheBuildLock) {
                                cacheBuildStatus = "error";
                                cacheBuildRunning = false;
                            }
                        }
                    });
                    cacheThread.setDaemon(true);
                    cacheThread.setName("cache-build-manual");
                    cacheThread.start();
                    
                    respondJson(ex, Map.of("message", "Cache build started", "tickers", LongTermCandidateFinder.getUniverseTickers().size()), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", "Failed to start cache build: " + e.getMessage()), 500);
                }
            }
        });

        // ---------------- Strategy Comparison API Endpoints ----------------
        server.createContext("/api/strategy-compare/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                int count = 5;
                double balance = 36000;
                String source = qp.getOrDefault("source", "random"); // "random" or "favorites"
                try { count = Math.min(20, Math.max(1, Integer.parseInt(qp.getOrDefault("count", "5")))); } catch (Exception ignore) {}
                try { balance = Double.parseDouble(qp.getOrDefault("balance", "36000")); } catch (Exception ignore) {}

                // Build ticker list based on source
                List<String> tickerList;
                if ("favorites".equals(source)) {
                    synchronized (favLockStatic) {
                        tickerList = new ArrayList<>(favItemsStatic);
                    }
                    if (tickerList.isEmpty()) {
                        respondJson(ex, Map.of("error", "אין מניות במועדפים - הוסף מניות קודם"), 400);
                        return;
                    }
                    // Limit to count
                    if (tickerList.size() > count) {
                        tickerList = tickerList.subList(0, count);
                    }
                } else {
                    // Random from POPULAR_TICKERS
                    tickerList = new ArrayList<>(Arrays.asList(POPULAR_TICKERS));
                    Collections.shuffle(tickerList, new Random());
                    tickerList = tickerList.subList(0, Math.min(count, tickerList.size()));
                }

                final int actualCount = tickerList.size();
                synchronized (strategyCompareLock) {
                    if (strategyCompareRunning) {
                        respondJson(ex, Map.of("error", "Already running"), 400);
                        return;
                    }
                    strategyCompareRunning = true;
                    strategyCompareProgress = 0;
                    strategyCompareTotal = actualCount;
                    strategyCompareCurrentTicker = "";
                    strategyCompareResults = new ArrayList<>();
                }

                final List<String> fTickerList = tickerList;
                final double fBalance = balance;
                strategyCompareExec.submit(() -> runStrategyComparison(fTickerList, fBalance));
                respondJson(ex, Map.of("status", "started", "count", actualCount, "source", source), 200);
            }
        });

        server.createContext("/api/strategy-compare/status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                Map<String, Object> out = new LinkedHashMap<>();
                synchronized (strategyCompareLock) {
                    out.put("running", strategyCompareRunning);
                    out.put("progress", strategyCompareProgress + "/" + strategyCompareTotal);
                    out.put("currentTicker", strategyCompareCurrentTicker);
                    out.put("results", new ArrayList<>(strategyCompareResults));
                }
                respondJson(ex, out, 200);
            }
        });

        // ---------------- Swing Scanner API Endpoint (Background) ----------------
        // Start scan in background
        server.createContext("/api/swing-scan/start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                
                if (swingScanRunning) {
                    respondJson(ex, Map.of("status", "already_running", "progress", swingScanProgress, "total", swingScanTotal, "currentTicker", swingScanCurrentTicker), 200);
                    return;
                }
                
                // Start background scan
                swingScanRunning = true;
                swingScanProgress = 0;
                swingScanTotal = 100;
                swingScanCurrentTicker = "";
                swingScanStartTime = java.time.ZonedDateTime.now(NY).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                
                new Thread(() -> {
                    List<Map<String, Object>> candidates = new ArrayList<>();
                    int scanned = 0;
                    int matchedVariant = 0;
                    
                    try {
                        System.out.println("[SwingScan] ========== Starting Background Swing Scanner ==========");
                        
                        List<String> allTickers = LongTermCandidateFinder.getUniverseTickers();
                        System.out.println("[SwingScan] Universe size: " + allTickers.size() + " stocks");
                        
                        if (allTickers.isEmpty()) {
                            System.out.println("[SwingScan] ERROR: No stocks in universe");
                            swingScanRunning = false;
                            return;
                        }
                        
                        List<String> tickers = new ArrayList<>(allTickers);
                        Collections.shuffle(tickers);
                        int maxStocks = Math.min(100, tickers.size());
                        tickers = tickers.subList(0, maxStocks);
                        swingScanTotal = maxStocks;
                        System.out.println("[SwingScan] Scanning " + maxStocks + " random stocks...");
                        
                        DailyTradingSimulator.SwingVariantsConfig swingConfig = DailyTradingSimulator.SwingVariantsConfig.load();
                        if (!swingConfig.enabled || swingConfig.variants == null || swingConfig.variants.isEmpty()) {
                            System.out.println("[SwingScan] ERROR: Swing variants not configured");
                            swingScanRunning = false;
                            return;
                        }
                        
                        MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                        
                        for (int i = 0; i < tickers.size(); i++) {
                            String ticker = tickers.get(i);
                            swingScanProgress = i + 1;
                            swingScanCurrentTicker = ticker;
                            
                            try {
                                Thread.sleep(800);
                                
                                JsonNode quote = av.globalQuote(ticker);
                                if (quote == null) continue;
                                
                                JsonNode gq = quote.path("Global Quote");
                                if (gq == null || gq.isMissingNode()) {
                                    gq = quote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                                }
                                if (gq == null || gq.isMissingNode()) continue;
                                
                                Double priceD = parseDoubleOrNull(gq.path("05. price").asText(""));
                                Double prevCloseD = parseDoubleOrNull(gq.path("08. previous close").asText(""));
                                double price = priceD != null ? priceD : 0;
                                double prevClose = prevCloseD != null ? prevCloseD : 0;
                                if (price <= 0) continue;
                                
                                scanned++;
                                double changePct = prevClose > 0 ? ((price - prevClose) / prevClose * 100) : 0;
                                double rsi = 50 + (changePct * 10);
                                rsi = Math.max(20, Math.min(80, rsi));
                                double rs = 1.0 + (changePct / 50);
                                rs = Math.max(0.8, Math.min(1.5, rs));
                                double rvol = 0.8 + Math.abs(changePct) * 0.3;
                                rvol = Math.max(0.5, Math.min(3.0, rvol));
                                
                                System.out.println("[SwingScan] " + ticker + ": $" + String.format("%.2f", price) + 
                                    " | Change: " + String.format("%.2f%%", changePct) + 
                                    " | RSI: " + String.format("%.0f", rsi) + 
                                    " | RS: " + String.format("%.2f", rs));
                                
                                for (DailyTradingSimulator.SwingVariantConfig variant : swingConfig.variants) {
                                    DailyTradingSimulator.SwingEntryFilters f = variant.entryFilters;
                                    if (rsi < f.rsiMin || rsi > f.rsiMax) continue;
                                    if (rs < f.rsMin) continue;
                                    if (rvol < f.rvolMin) continue;
                                    
                                    int score = 0;
                                    DailyTradingSimulator.SwingScoring scoring = variant.scoring;
                                    if (rs >= 1.20) score += scoring.rsWeight;
                                    else if (rs >= 1.10) score += (int)(scoring.rsWeight * 0.75);
                                    else if (rs >= 1.0) score += (int)(scoring.rsWeight * 0.5);
                                    if (rsi <= 35) score += scoring.rsiWeight;
                                    else if (rsi <= 45) score += (int)(scoring.rsiWeight * 0.75);
                                    else if (rsi <= 55) score += (int)(scoring.rsiWeight * 0.5);
                                    if (rvol >= 1.5) score += scoring.volumeWeight;
                                    else if (rvol >= 1.0) score += (int)(scoring.volumeWeight * 0.6);
                                    
                                    double stopLoss = price * (1 - variant.riskManagement.stopLossPct / 100);
                                    double takeProfit = price * (1 + variant.riskManagement.takeProfitPct / 100);
                                    
                                    System.out.println("[SwingScan] ✅ " + ticker + " MATCHED " + variant.id + " | Score: " + score);
                                    
                                    Map<String, Object> candidate = new LinkedHashMap<>();
                                    candidate.put("ticker", ticker);
                                    candidate.put("variant", variant.id);
                                    candidate.put("variantName", variant.name);
                                    candidate.put("score", score);
                                    candidate.put("rsi", rsi);
                                    candidate.put("rs", rs);
                                    candidate.put("rvol", rvol);
                                    candidate.put("entry", price);
                                    candidate.put("stop", stopLoss);
                                    candidate.put("target", takeProfit);
                                    candidate.put("price", price);
                                    candidate.put("changePct", changePct);
                                    
                                    candidates.add(candidate);
                                    matchedVariant++;
                                    break;
                                }
                            } catch (Exception e) {
                                System.err.println("[SwingScan] Error scanning " + ticker + ": " + e.getMessage());
                            }
                        }
                        
                        candidates.sort((a, b) -> Integer.compare((Integer)b.get("score"), (Integer)a.get("score")));
                        
                        System.out.println("[SwingScan] ========== Scan Complete ==========");
                        System.out.println("[SwingScan] Scanned: " + scanned + " | Matched: " + matchedVariant);
                        
                        // Save results
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("scanned", scanned);
                        out.put("candidates", candidates);
                        out.put("scanTime", swingScanStartTime);
                        
                        try {
                            java.nio.file.Files.writeString(
                                java.nio.file.Path.of("swing-scan-results.json"),
                                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out)
                            );
                            System.out.println("[SwingScan] Results saved to swing-scan-results.json");
                        } catch (Exception saveErr) {
                            System.err.println("[SwingScan] Failed to save results: " + saveErr.getMessage());
                        }
                        
                    } catch (Exception e) {
                        System.err.println("[SwingScan] FATAL ERROR: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        swingScanRunning = false;
                        swingScanCurrentTicker = "";
                    }
                }).start();
                
                respondJson(ex, Map.of("status", "started", "total", swingScanTotal), 200);
            }
        });
        
        // Get scan status
        server.createContext("/api/swing-scan/status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("running", swingScanRunning);
                status.put("progress", swingScanProgress);
                status.put("total", swingScanTotal);
                status.put("currentTicker", swingScanCurrentTicker);
                status.put("startTime", swingScanStartTime);
                
                respondJson(ex, status, 200);
            }
        });
        
        // Get full scan status
        server.createContext("/api/full-scan/status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("running", AIToolAgent.isTopAgentsFullScanRunning());
                status.put("progress", AIToolAgent.getTopAgentsFullScanProgress());
                status.put("total", AIToolAgent.getTopAgentsFullScanTotal());
                status.put("statusMessage", AIToolAgent.getTopAgentsFullScanStatus());
                
                respondJson(ex, status, 200);
            }
        });
        
        // Get saved swing scan results
        server.createContext("/api/swing-scan/results", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondJson(ex, Map.of("error", "GET only"), 405); return; }
                try {
                    java.nio.file.Path resultsPath = java.nio.file.Path.of("swing-scan-results.json");
                    if (java.nio.file.Files.exists(resultsPath)) {
                        String json = java.nio.file.Files.readString(resultsPath);
                        Map<String, Object> results = JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        respondJson(ex, results, 200);
                    } else {
                        respondJson(ex, Map.of("candidates", List.of()), 200);
                    }
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });
        
        // Clear swing scan results
        server.createContext("/api/swing-scan/clear", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondJson(ex, Map.of("error", "POST only"), 405); return; }
                try {
                    java.nio.file.Path resultsPath = java.nio.file.Path.of("swing-scan-results.json");
                    if (java.nio.file.Files.exists(resultsPath)) {
                        java.nio.file.Files.delete(resultsPath);
                        System.out.println("[SwingScan] Results cleared");
                    }
                    respondJson(ex, Map.of("success", true), 200);
                } catch (Exception e) {
                    respondJson(ex, Map.of("error", e.getMessage()), 500);
                }
            }
        });

        server.createContext("/monitoring-refresh", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                try { monitoringScheduler.triggerNowAsync(); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/monitoring?status=refresh_started");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/sync-green-to-monitoring", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                int added = 0;
                try {
                    List<DailyGreenTicker> greenList;
                    synchronized (dailyGreenLock) {
                        greenList = dailyGreenState.green == null ? Collections.emptyList() : new ArrayList<>(dailyGreenState.green);
                    }
                    for (DailyGreenTicker t : greenList) {
                        if (t != null && t.ticker != null && !t.ticker.isBlank()) {
                            if (monitoringStore.addTicker(t.ticker)) added++;
                        }
                    }
                    if (added > 0) monitoringScheduler.triggerNowAsync();
                } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/favorites?syncStatus=added_" + added);
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/api/fetch-atr", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    byte[] resp = "{\"error\":\"Method not allowed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(405, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                    return;
                }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String symbol = qp.getOrDefault("symbol", "").trim().toUpperCase();
                
                ex.getResponseHeaders().add("Content-Type", "application/json");
                
                if (symbol.isEmpty()) {
                    byte[] resp = "{\"error\":\"Missing symbol parameter\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(400, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                    return;
                }
                
                try {
                    double atr = activePositionsStore.fetchCurrentAtr(symbol);
                    double currentPrice = activePositionsStore.fetchCurrentPrice(symbol);
                    double atrPercent = (currentPrice > 0 && atr > 0) ? (atr / currentPrice) * 100 : 0;
                    String volatility = atrPercent < 1.0 ? "נמוכה" : (atrPercent < 2.5 ? "בינונית" : (atrPercent < 5.0 ? "גבוהה" : "קיצונית"));
                    String suggestedMultiplier = atrPercent < 1.0 ? "1.5-2.0" : (atrPercent < 2.5 ? "2.0-2.5" : (atrPercent < 5.0 ? "2.5-3.0" : "3.0-3.5"));
                    
                    String json = String.format(
                        "{\"symbol\":\"%s\",\"atr\":%.4f,\"currentPrice\":%.2f,\"atrPercent\":%.2f,\"volatility\":\"%s\",\"suggestedMultiplier\":\"%s\"}",
                        symbol, atr, currentPrice, atrPercent, volatility, suggestedMultiplier
                    );
                    byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                } catch (Exception e) {
                    byte[] resp = "{\"error\":\"Failed to fetch ATR\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, resp.length);
                    ex.getResponseBody().write(resp);
                }
                ex.close();
            }
        });

        server.createContext("/intraday-alerts", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }
                StringBuilder sb = new StringBuilder();
                String interval = intradayIntervalForCurrentEntitlement();
                sb.append("<div class='card'><div class='title'>Intraday Alerts (Experimental)</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>On-demand ").append(escapeHtml(interval)).append(" polling for <b>one</b> ticker during NYSE regular hours (09:30–16:00 ET). Auto-stops after <b>40 minutes</b> to stay within API limits. Uses Alpha Vantage intraday bars and triggers BUY/SELL on volume spikes + price moves.</div>");

                IntradayAlertState st;
                synchronized (intradayLock) { st = intradayState; }

                String status = st.running ? "RUNNING" : "STOPPED";
                String statusColor = st.running ? "#22c55e" : "#fbbf24";
                sb.append("<div style='margin-bottom:10px;'>Status: <span style='color:").append(statusColor).append(";font-weight:700;'>").append(status).append("</span></div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>NYSE hours now: ").append(isNyseRegularHoursNow()?"YES":"NO").append("</div>");
                if (st.symbol != null && !st.symbol.isBlank()) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Symbol: <b>").append(escapeHtml(st.symbol)).append("</b></div>");
                if (st.startedAtNy != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Started (NY): ").append(escapeHtml(st.startedAtNy.toString())).append("</div>");
                if (st.lastCheckNy != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last check (NY): ").append(escapeHtml(st.lastCheckNy.toString())).append("</div>");
                if (st.lastBarTs != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Latest bar: ").append(escapeHtml(st.lastBarTs)).append("</div>");
                if (st.avLastRefreshed != null && !st.avLastRefreshed.isBlank()) {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Alpha Vantage last refreshed: ").append(escapeHtml(st.avLastRefreshed)).append("</div>");
                }
                if (st.avTimeZone != null && !st.avTimeZone.isBlank()) {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Alpha Vantage time zone: ").append(escapeHtml(st.avTimeZone)).append("</div>");
                }
                if (st.avNote != null && !st.avNote.isBlank()) {
                    sb.append("<div style='color:#fbbf24;margin-bottom:12px;'>Alpha Vantage note: ").append(escapeHtml(st.avNote)).append("</div>");
                }
                if (st.avInformation != null && !st.avInformation.isBlank()) {
                    sb.append("<div style='color:#fbbf24;margin-bottom:12px;'>Alpha Vantage info: ").append(escapeHtml(st.avInformation)).append("</div>");
                }
                if (st.avErrorMessage != null && !st.avErrorMessage.isBlank()) {
                    sb.append("<div style='color:#fca5a5;margin-bottom:12px;'>Alpha Vantage error: ").append(escapeHtml(st.avErrorMessage)).append("</div>");
                }
                if (st.lastPrice != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last price: ").append(escapeHtml(String.format("%.4f", st.lastPrice))).append("</div>");
                if (st.lastVolume != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last volume: ").append(escapeHtml(String.valueOf(st.lastVolume))).append("</div>");

                if (st.lastQuoteAtNy != null) {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last quote (NY): ").append(escapeHtml(st.lastQuoteAtNy.toString())).append("</div>");
                }
                if (st.lastQuotePrice != null) {
                    sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Latest quote price: <b>")
                            .append(escapeHtml(String.format("%.4f", st.lastQuotePrice)))
                            .append("</b></div>");
                }
                if (st.lastQuoteError != null && !st.lastQuoteError.isBlank()) {
                    sb.append("<div style='color:#fca5a5;margin-bottom:12px;'>Quote error: ").append(escapeHtml(st.lastQuoteError)).append("</div>");
                }

                if (st.lastSignal != null) {
                    String col = st.lastSignal.equals("BUY") ? "#22c55e" : (st.lastSignal.equals("SELL") ? "#fca5a5" : "#93c5fd");
                    sb.append("<div style='margin-bottom:12px;'>Signal: <span style='color:").append(col).append(";font-weight:700;'>").append(escapeHtml(st.lastSignal)).append("</span></div>");
                }
                if (st.lastError != null && !st.lastError.isBlank()) {
                    sb.append("<div style='color:#fca5a5;margin-bottom:12px;'>Last error: ").append(escapeHtml(st.lastError)).append("</div>");
                }

                // Breakout Scanner Results
                IntradayScanner.ScanResult scan = st.scanResult;
                if (scan != null) {
                    sb.append("</div>"); // Close main card
                    sb.append("<div class='card'><div class='title'>🚀 Breakout Scanner - מסחר תוך-יומי</div>");
                    
                    // Signal with Hebrew
                    String scanCol = "#93c5fd";
                    if ("BREAKOUT_BUY".equals(scan.signal) || "MOMENTUM_BUY".equals(scan.signal)) scanCol = "#22c55e";
                    else if ("MISSED".equals(scan.signal) || "OVERBOUGHT".equals(scan.signal)) scanCol = "#fbbf24";
                    else if ("WEAK_VOLUME".equals(scan.signal)) scanCol = "#9ca3af";
                    
                    sb.append("<div style='font-size:1.3em;margin-bottom:16px;'>")
                      .append("<span style='color:").append(scanCol).append(";font-weight:700;'>")
                      .append(escapeHtml(scan.signalHebrew != null ? scan.signalHebrew : scan.signal))
                      .append("</span></div>");
                    
                    // Key metrics grid
                    sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:16px;'>");
                    
                    // Price Change
                    String pctColor = scan.priceChangePct >= 0 ? "#22c55e" : "#fca5a5";
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;text-align:center;'>")
                      .append("<div style='color:#9ca3af;font-size:0.8em;'>שינוי יומי</div>")
                      .append("<div style='color:#6b7280;font-size:0.65em;'>שינוי המחיר מאז הפתיחה</div>")
                      .append("<div style='color:").append(pctColor).append(";font-size:1.2em;font-weight:700;'>")
                      .append(scan.priceChangePct >= 0 ? "+" : "").append(String.format("%.2f%%", scan.priceChangePct))
                      .append("</div></div>");
                    
                    // RVOL
                    String rvolColor = scan.rvol >= 2.0 ? "#22c55e" : (scan.rvol >= 1.5 ? "#fbbf24" : "#9ca3af");
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;text-align:center;'>")
                      .append("<div style='color:#9ca3af;font-size:0.8em;'>RVOL (נפח יחסי)</div>")
                      .append("<div style='color:#6b7280;font-size:0.65em;'>נפח ביחס לממוצע, מעל 2x = חזק</div>")
                      .append("<div style='color:").append(rvolColor).append(";font-size:1.2em;font-weight:700;'>")
                      .append(String.format("%.1fx", scan.rvol))
                      .append("</div></div>");
                    
                    // VWAP
                    String vwapColor = scan.aboveVwap ? "#22c55e" : "#fca5a5";
                    String vwapIcon = scan.aboveVwap ? "✅" : "❌";
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;text-align:center;'>")
                      .append("<div style='color:#9ca3af;font-size:0.8em;'>VWAP</div>")
                      .append("<div style='color:#6b7280;font-size:0.65em;'>מחיר ממוצע משוקלל, מעליו = שורי</div>")
                      .append("<div style='color:").append(vwapColor).append(";font-size:1.2em;font-weight:700;'>")
                      .append("$").append(String.format("%.2f", scan.vwap)).append(" ").append(vwapIcon)
                      .append("</div></div>");
                    
                    // RSI
                    String rsiColor = scan.rsi > 70 ? "#fca5a5" : (scan.rsi > 60 ? "#22c55e" : "#9ca3af");
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;text-align:center;'>")
                      .append("<div style='color:#9ca3af;font-size:0.8em;'>RSI (תוך-יומי)</div>")
                      .append("<div style='color:#6b7280;font-size:0.65em;'>עוצמה, 60-70 = מומנטום, מעל 80 = קניית יתר</div>")
                      .append("<div style='color:").append(rsiColor).append(";font-size:1.2em;font-weight:700;'>")
                      .append(String.format("%.0f", scan.rsi))
                      .append("</div></div>");
                    
                    // Market Support
                    String mktColor = scan.marketSupport ? "#22c55e" : "#fca5a5";
                    String mktIcon = scan.marketSupport ? "🟢" : "🔴";
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;text-align:center;'>")
                      .append("<div style='color:#9ca3af;font-size:0.8em;'>שוק (SPY)</div>")
                      .append("<div style='color:#6b7280;font-size:0.65em;'>כיוון השוק הכללי, ירוק = תומך</div>")
                      .append("<div style='color:").append(mktColor).append(";font-size:1.2em;font-weight:700;'>")
                      .append(mktIcon).append(" ").append(String.format("%.1f%%", scan.spyChangePct))
                      .append("</div></div>");
                    
                    sb.append("</div>"); // Close grid
                    
                    // Trade Parameters (if buy signal)
                    if (scan.suggestedEntry > 0 && ("BREAKOUT_BUY".equals(scan.signal) || "MOMENTUM_BUY".equals(scan.signal))) {
                        sb.append("<div style='background:#0f1a2a;border:1px solid #22c55e;border-radius:10px;padding:16px;margin-bottom:16px;'>");
                        sb.append("<div style='color:#22c55e;font-weight:700;margin-bottom:10px;'>📊 תוכנית מסחר</div>");
                        sb.append("<div style='display:grid;grid-template-columns:repeat(3,1fr);gap:8px;text-align:center;'>");
                        sb.append("<div><div style='color:#9ca3af;font-size:0.8em;'>כניסה</div><div style='color:#22c55e;font-weight:700;'>$")
                          .append(String.format("%.2f", scan.suggestedEntry)).append("</div></div>");
                        sb.append("<div><div style='color:#9ca3af;font-size:0.8em;'>סטופ לוס</div><div style='color:#fca5a5;font-weight:700;'>$")
                          .append(String.format("%.2f", scan.suggestedStopLoss)).append("</div></div>");
                        sb.append("<div><div style='color:#9ca3af;font-size:0.8em;'>יעד</div><div style='color:#22c55e;font-weight:700;'>$")
                          .append(String.format("%.2f", scan.targetPrice)).append("</div></div>");
                        sb.append("</div></div>");
                    }
                    
                    // Reasons
                    if (!scan.reasons.isEmpty()) {
                        sb.append("<div style='margin-bottom:12px;'><div style='color:#22c55e;margin-bottom:6px;'>✅ סיבות:</div>");
                        for (String reason : scan.reasons) {
                            sb.append("<div style='color:#9ca3af;margin-left:16px;'>• ").append(escapeHtml(reason)).append("</div>");
                        }
                        sb.append("</div>");
                    }
                    
                    // Warnings
                    if (!scan.warnings.isEmpty()) {
                        sb.append("<div style='margin-bottom:12px;'><div style='color:#fbbf24;margin-bottom:6px;'>⚠️ אזהרות:</div>");
                        for (String warning : scan.warnings) {
                            sb.append("<div style='color:#9ca3af;margin-left:16px;'>• ").append(escapeHtml(warning)).append("</div>");
                        }
                        sb.append("</div>");
                    }
                    
                    // Scanner explanation
                    sb.append("<div style='color:#6b7280;font-size:0.85em;margin-top:12px;border-top:1px solid #1f2a44;padding-top:12px;'>");
                    sb.append("<b>מדדי הסורק:</b> RVOL≥2 (נפח חריג), מעל VWAP, RSI>60, תמיכת שוק (SPY לא יורד מעל 0.5%). ");
                    sb.append("אם המניה כבר עלתה יותר מ-4% - אל תרדוף!");
                    sb.append("</div>");
                    
                    sb.append("<div class='card' style='margin-top:12px;background:#0b1220;'>");
                }

                if (st.running) {
                    sb.append("<script>setTimeout(function(){location.reload();},60000);</script>");
                }

                sb.append("<div style='display:flex;gap:12px;flex-wrap:wrap;margin-bottom:12px;'>");
                sb.append("<form method='post' action='/intraday-start' style='margin:0'>")
                        .append("<input type='text' name='symbol' placeholder='Ticker (e.g. AAPL)' required /> ")
                        .append("<button type='submit'>Start (").append(escapeHtml(interval)).append(" / 40min)</button></form>");
                sb.append("<form method='post' action='/intraday-stop' style='margin:0'>")
                        .append("<button type='submit'>Stop</button></form>");
                sb.append("<form method='post' action='/quote-now' style='margin:0'>")
                        .append("<button type='submit'>Fetch Latest Quote Now</button></form>");
                sb.append("<form method='post' action='/run-scanner' style='margin:0'>")
                        .append("<button type='submit' style='background:#7c3aed;'>🔍 Run Scanner (20 Random)</button></form>");
                sb.append("</div>");

                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Discord: התראות נשלחות אוטומטית לערוץ Discord.</div>");
                sb.append("</div>");

                sb.append("<div class='card'><div class='title'>Last 10 Bars</div>");
                if (st.lastBars.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No bars loaded yet. If this stays empty, Alpha Vantage may be returning a rate-limit note or stale data.</div>");
                } else {
                    String svg = renderIntradayBarsSvg(st.lastBars);
                    if (svg == null || svg.isBlank()) {
                        sb.append("<div style='color:#9ca3af'>Not enough numeric bars to draw a chart yet.</div>");
                    } else {
                        sb.append("<div style='overflow-x:auto'>").append(svg).append("</div>");
                    }
                }
                sb.append("</div>");

                sb.append("<div class='card'><div class='title'>Recent Alerts</div>");
                if (st.history.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No alerts yet.</div>");
                } else {
                    sb.append("<div style='display:flex;flex-direction:column;gap:8px;'>");
                    int start = Math.max(0, st.history.size() - 20);
                    for (int i = st.history.size() - 1; i >= start; i--) {
                        sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:10px;padding:10px 12px;'>")
                                .append(escapeHtml(st.history.get(i)))
                                .append("</div>");
                    }
                    sb.append("</div>");
                }
                sb.append("</div>");

                // Volume Flow Tracker Section
                sb.append("<div class='card' style='border:2px solid #8b5cf6;'>");
                sb.append("<div class='title'>📊 Volume Flow Tracker - ניתוח קונים מול מוכרים</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>מעקב אחר לחץ קנייה/מכירה על פני 7 ימים עם המלצות מחיר</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;'>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>סמל מניה</label>");
                sb.append("<input id='vfTicker' type='text' placeholder='AAPL' style='width:100%;'/>");
                sb.append("</div>");
                sb.append("<div style='flex:1;min-width:150px;'>");
                sb.append("<label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>ימים לניתוח</label>");
                sb.append("<select id='vfDays' style='width:100%;padding:8px;'>");
                sb.append("<option value='3'>3 ימים</option>");
                sb.append("<option value='5'>5 ימים</option>");
                sb.append("<option value='7' selected>7 ימים (שבוע)</option>");
                sb.append("<option value='14'>14 ימים</option>");
                sb.append("</select>");
                sb.append("</div>");
                sb.append("<div style='display:flex;align-items:flex-end;gap:8px;'>");
                sb.append("<button id='vfBtn' onclick='fetchVolumeFlow()' style='padding:8px 20px;background:#8b5cf6;'>🔍 נתח</button>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div id='vfResult' style='display:none;'></div>");
                sb.append("</div>");

                // Volume Flow Tracker JavaScript
                sb.append("<script>" +
                        "async function fetchVolumeFlow() {" +
                        "  var ticker = (document.getElementById('vfTicker').value || '').trim().toUpperCase();" +
                        "  var days = parseInt(document.getElementById('vfDays').value) || 7;" +
                        "  if (!ticker) { alert('הכנס סמל מניה'); return; }" +
                        "  var btn = document.getElementById('vfBtn');" +
                        "  var el = document.getElementById('vfResult');" +
                        "  btn.disabled = true; btn.textContent = '⏳ טוען...';" +
                        "  el.style.display = 'block';" +
                        "  el.innerHTML = '<div style=\"color:#9ca3af;text-align:center;padding:20px;\">טוען נתונים...</div>';" +
                        "  try {" +
                        "    var r = await fetch('/api/volume-flow?ticker=' + encodeURIComponent(ticker) + '&days=' + days);" +
                        "    var d = await r.json();" +
                        "    if (d.error) { el.innerHTML = '<div style=\"color:#ef4444;\">' + d.error + '</div>'; return; }" +
                        "    var html = '';" +
                        "    var latestDay = (d.dailyFlow && d.dailyFlow.length > 0) ? d.dailyFlow[d.dailyFlow.length-1] : null;" +
                        "    var todayChgPct = latestDay ? (latestDay.priceChangePct || 0) : 0;" +
                        "    var todayChgColor = todayChgPct >= 0 ? '#22c55e' : '#ef4444';" +
                        "    var todayChgSign = todayChgPct >= 0 ? '+' : '';" +
                        "    var todayOpen = latestDay ? (latestDay.open || 0) : 0;" +
                        "    var todayClose = latestDay ? (latestDay.close || 0) : 0;" +
                        "    html += '<div style=\"background:#0b1220;border-radius:12px;padding:16px;margin-bottom:16px;\">';" +
                        "    html += '<div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;\">';" +
                        "    html += '<div style=\"font-size:20px;font-weight:700;\">' + d.ticker + '</div>';" +
                        "    html += '<div style=\"text-align:right;\">';" +
                        "    html += '<div style=\"font-size:24px;font-weight:700;color:#e5e7eb;\">$' + (d.currentPrice||0).toFixed(2) + '</div>';" +
                        "    html += '<div style=\"font-size:18px;font-weight:600;color:' + todayChgColor + ';\">' + todayChgSign + todayChgPct.toFixed(2) + '%</div>';" +
                        "    html += '</div>';" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;background:#1f2a44;padding:12px;border-radius:8px;\">';" +
                        "    html += '<div style=\"text-align:center;\"><div style=\"font-size:11px;color:#9ca3af;\">פתיחה (Open)</div><div style=\"font-size:16px;font-weight:600;\">$' + todayOpen.toFixed(2) + '</div></div>';" +
                        "    html += '<div style=\"text-align:center;\"><div style=\"font-size:11px;color:#9ca3af;\">סגירה (Close)</div><div style=\"font-size:16px;font-weight:600;color:' + todayChgColor + ';\">$' + todayClose.toFixed(2) + '</div></div>';" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"text-align:center;padding:12px;background:linear-gradient(90deg,#22c55e ' + (d.weeklyBuyPressure||50) + '%,#ef4444 ' + (d.weeklyBuyPressure||50) + '%);border-radius:8px;margin-bottom:8px;\">';" +
                        "    html += '<span style=\"background:#0b1220;padding:4px 12px;border-radius:4px;font-weight:600;\">קונים ' + (d.weeklyBuyPressure||0).toFixed(0) + '% | מוכרים ' + (d.weeklySellPressure||0).toFixed(0) + '%</span>';" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"text-align:center;font-size:18px;font-weight:600;color:' + (d.weeklyTrend.includes('Accumulation')?'#22c55e':'#ef4444') + ';\">' + d.weeklyTrend + '</div>';" +
                        "    html += '</div>';" +
                        "    var latestDate = (d.dailyFlow && d.dailyFlow.length > 0) ? d.dailyFlow[d.dailyFlow.length-1].date : '';" +
                        "    var dataAge = latestDate ? '(נתונים מ-' + latestDate + ')' : '';" +
                        "    var today = new Date().toISOString().slice(0,10);" +
                        "    var isStale = latestDate && latestDate < today.slice(0,7);" +
                        "    html += '<div style=\"font-weight:600;margin-bottom:8px;\">📅 ניתוח יומי: <span style=\"font-size:12px;color:' + (isStale ? '#fbbf24' : '#9ca3af') + ';font-weight:400;\">' + dataAge + (isStale ? ' ⚠️ נתונים ישנים!' : '') + '</span></div>';" +
                        "    html += '<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin-bottom:16px;\">';" +
                        "    (d.dailyFlow||[]).forEach(function(day,i){" +
                        "      var bgColor = day.buyPercent > 55 ? '#14532d' : (day.sellPercent > 55 ? '#7f1d1d' : '#1f2a44');" +
                        "      var icon = day.buyPercent > 55 ? '🟢' : (day.sellPercent > 55 ? '🔴' : '🟡');" +
                        "      var chgPct = day.priceChangePct || 0;" +
                        "      var chgColor = chgPct >= 0 ? '#22c55e' : '#ef4444';" +
                        "      var chgSign = chgPct >= 0 ? '+' : '';" +
                        "      html += '<div style=\"background:'+bgColor+';padding:10px;border-radius:8px;text-align:center;\">';" +
                        "      html += '<div style=\"font-size:11px;color:#9ca3af;\">יום ' + (i+1) + '</div>';" +
                        "      html += '<div style=\"font-size:10px;color:#6b7280;margin-bottom:4px;\">' + (day.date||'') + '</div>';" +
                        "      html += '<div style=\"font-size:14px;font-weight:600;color:' + chgColor + ';margin-bottom:4px;\">' + chgSign + chgPct.toFixed(2) + '%</div>';" +
                        "      html += '<div style=\"font-size:16px;\">' + icon + '</div>';" +
                        "      html += '<div style=\"font-size:11px;color:#22c55e;\">קנייה ' + day.buyPercent.toFixed(0) + '%</div>';" +
                        "      html += '<div style=\"font-size:11px;color:#ef4444;\">מכירה ' + day.sellPercent.toFixed(0) + '%</div>';" +
                        "      html += '</div>';" +
                        "    });" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:16px;\">';" +
                        "    html += '<div style=\"background:#14532d;padding:16px;border-radius:12px;\">';" +
                        "    html += '<div style=\"font-weight:600;color:#22c55e;margin-bottom:8px;\">🟢 אזור קנייה</div>';" +
                        "    html += '<div style=\"font-size:20px;font-weight:700;\">$' + (d.buyZoneLow||0).toFixed(2) + ' - $' + (d.buyZoneHigh||0).toFixed(2) + '</div>';" +
                        "    html += '<div style=\"font-size:12px;color:#9ca3af;margin-top:4px;\">תמיכה: $' + (d.supportLevel||0).toFixed(2) + '</div>';" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"background:#7f1d1d;padding:16px;border-radius:12px;\">';" +
                        "    html += '<div style=\"font-weight:600;color:#ef4444;margin-bottom:8px;\">🔴 אזור מכירה</div>';" +
                        "    html += '<div style=\"font-size:20px;font-weight:700;\">$' + (d.sellZoneLow||0).toFixed(2) + ' - $' + (d.sellZoneHigh||0).toFixed(2) + '</div>';" +
                        "    html += '<div style=\"font-size:12px;color:#9ca3af;margin-top:4px;\">התנגדות: $' + (d.resistanceLevel||0).toFixed(2) + '</div>';" +
                        "    html += '</div>';" +
                        "    html += '</div>';" +
                        "    html += '<div style=\"background:#1e3a5f;padding:16px;border-radius:12px;margin-bottom:12px;\">';" +
                        "    html += '<div style=\"font-size:18px;font-weight:700;margin-bottom:8px;\">' + (d.recommendation||'') + '</div>';" +
                        "    html += '<div style=\"color:#ef4444;\">🛑 Stop Loss: $' + (d.stopLoss||0).toFixed(2) + '</div>';" +
                        "    html += '</div>';" +
                        "    if (d.insights && d.insights.length > 0) {" +
                        "      html += '<div style=\"background:#0b1220;padding:12px;border-radius:8px;\">';" +
                        "      html += '<div style=\"font-weight:600;margin-bottom:8px;\">💡 תובנות:</div>';" +
                        "      d.insights.forEach(function(ins){ html += '<div style=\"color:#cbd5e1;margin-bottom:4px;\">• ' + ins + '</div>'; });" +
                        "      html += '</div>';" +
                        "    }" +
                        "    el.innerHTML = html;" +
                        "  } catch(e) { el.innerHTML = '<div style=\"color:#ef4444;\">שגיאה: ' + e.message + '</div>'; }" +
                        "  finally { btn.disabled = false; btn.textContent = '🔍 נתח'; }" +
                        "}" +
                        "</script>");

                // Strategy Backtester Section (moved from alpha-agent)
                sb.append("<div class='card' style='border:2px solid #f59e0b;'>");
                sb.append("<div class='title'>📊 Strategy Backtester - בדיקת אסטרטגיה על נתוני עבר</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;'>הרץ סימולציה של הכללים שלנו (RS, Position Sizing, Trailing Stop) על נתונים היסטוריים והשווה ל-S&P 500</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;align-items:flex-end;'>");
                sb.append("<div><label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>סימול מניה</label>");
                sb.append("<input id='btTicker' type='text' placeholder='AAPL' style='width:120px;'/></div>");
                sb.append("<div><label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>הון התחלתי ($)</label>");
                sb.append("<input id='btBalance' type='number' value='36000' min='1000' step='1000' style='width:120px;'/></div>");
                sb.append("<button onclick='runBacktest()' id='btBtn'>🚀 הרץ Backtest</button>");
                sb.append("</div>");
                sb.append("<div id='btResult' style='display:none;'></div>");
                sb.append("<div id='btChart' style='height:300px;background:#0b1220;border-radius:8px;margin-top:12px;display:none;'></div>");
                sb.append("<div id='btTrades' style='display:none;margin-top:12px;'></div>");
                sb.append("</div>");

                // Strategy Comparison Section (moved from alpha-agent)
                sb.append("<div class='card' style='border:2px solid #8b5cf6;'>");
                sb.append("<div class='title'>🔬 Strategy Comparison - השוואת אסטרטגיות</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;'>בוחר מניות ומריץ backtest על 100 הימים האחרונים עם כל האסטרטגיות. רץ ברקע - לא חוסם את הדפדפן.</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;align-items:flex-end;'>");
                sb.append("<div><label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>מקור מניות</label>");
                sb.append("<select id='scSource' style='padding:6px 10px;'>");
                sb.append("<option value='random'>🎲 רנדומלי (מניות פופולריות)</option>");
                sb.append("<option value='favorites'>⭐ מועדפים</option>");
                sb.append("</select></div>");
                sb.append("<div><label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>מספר מניות לבדיקה</label>");
                sb.append("<input id='scCount' type='number' value='5' min='1' max='20' style='width:80px;'/></div>");
                sb.append("<div><label style='display:block;color:#9ca3af;font-size:12px;margin-bottom:4px;'>הון התחלתי ($)</label>");
                sb.append("<input id='scBalance' type='number' value='36000' min='1000' step='1000' style='width:120px;'/></div>");
                sb.append("<button onclick='runStrategyCompare()' id='scBtn'>🚀 הרץ השוואה</button>");
                sb.append("<span id='scStatus' style='color:#9ca3af;font-size:13px;'></span>");
                sb.append("</div>");
                // Progress bar (hidden when idle)
                sb.append("<div id='scProgressWrap' style='margin-top:8px;display:none;'>");
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;'>");
                sb.append("<span style='font-size:12px;color:#9ca3af;'>Tickers scanned: <span id='scProgressText'>0 / 0</span></span>");
                sb.append("<span style='font-size:12px;color:#8b5cf6;font-weight:600;'><span id='scPct'>0</span>%</span>");
                sb.append("</div>");
                sb.append("<div style='background:#1f2a44;border-radius:6px;height:10px;overflow:hidden;'>");
                sb.append("<div id='scProgressBar' style='background:linear-gradient(90deg,#8b5cf6,#a78bfa);height:100%;width:0%;transition:width 0.4s ease;'></div>");
                sb.append("</div>");
                sb.append("<div style='margin-top:4px;font-size:11px;color:#6b7280;'>Current: <span id='scCurrentTicker' style='color:#fcd34d;'>—</span></div>");
                sb.append("</div>");
                sb.append("<div id='scResult' style='display:none;'></div>");
                sb.append("</div>");

                // JavaScript for Backtester
                sb.append("<script>");
                sb.append("async function runBacktest() {");
                sb.append("  var ticker = (document.getElementById('btTicker').value || '').trim().toUpperCase();");
                sb.append("  var balance = parseInt(document.getElementById('btBalance').value) || 36000;");
                sb.append("  if (!ticker) { alert('הכנס סמל מניה'); return; }");
                sb.append("  var btn = document.getElementById('btBtn');");
                sb.append("  btn.disabled = true; btn.textContent = '⏳ מריץ...';");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/backtest?ticker=' + encodeURIComponent(ticker) + '&balance=' + balance);");
                sb.append("    var d = await r.json();");
                sb.append("    var el = document.getElementById('btResult');");
                sb.append("    el.style.display = 'block';");
                sb.append("    if (d.error) { el.innerHTML = '<div style=\"color:#ef4444;\">' + d.error + '</div>'; return; }");
                sb.append("    var html = '<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;\">';");
                sb.append("    html += '<div style=\"background:#0b1220;padding:12px;border-radius:8px;text-align:center;\"><div style=\"color:#9ca3af;font-size:12px;\">תשואה</div><div style=\"font-size:20px;font-weight:700;color:' + (d.totalReturnPct >= 0 ? '#22c55e' : '#ef4444') + ';\">' + (d.totalReturnPct >= 0 ? '+' : '') + d.totalReturnPct.toFixed(2) + '%</div></div>';");
                sb.append("    html += '<div style=\"background:#0b1220;padding:12px;border-radius:8px;text-align:center;\"><div style=\"color:#9ca3af;font-size:12px;\">S&P 500</div><div style=\"font-size:20px;font-weight:700;color:' + (d.spyReturnPct >= 0 ? '#22c55e' : '#ef4444') + ';\">' + (d.spyReturnPct >= 0 ? '+' : '') + d.spyReturnPct.toFixed(2) + '%</div></div>';");
                sb.append("    html += '<div style=\"background:#0b1220;padding:12px;border-radius:8px;text-align:center;\"><div style=\"color:#9ca3af;font-size:12px;\">עסקאות</div><div style=\"font-size:20px;font-weight:700;\">' + d.totalTrades + '</div></div>';");
                sb.append("    html += '<div style=\"background:#0b1220;padding:12px;border-radius:8px;text-align:center;\"><div style=\"color:#9ca3af;font-size:12px;\">Win Rate</div><div style=\"font-size:20px;font-weight:700;\">' + d.winRate.toFixed(1) + '%</div></div>';");
                sb.append("    html += '</div>';");
                sb.append("    el.innerHTML = html;");
                sb.append("  } catch(e) { document.getElementById('btResult').innerHTML = '<div style=\"color:#ef4444;\">שגיאה: ' + e.message + '</div>'; }");
                sb.append("  finally { btn.disabled = false; btn.textContent = '🚀 הרץ Backtest'; }");
                sb.append("}");
                sb.append("async function runStrategyCompare() {");
                sb.append("  var source = document.getElementById('scSource').value;");
                sb.append("  var count = parseInt(document.getElementById('scCount').value) || 5;");
                sb.append("  var balance = parseInt(document.getElementById('scBalance').value) || 36000;");
                sb.append("  var btn = document.getElementById('scBtn');");
                sb.append("  var status = document.getElementById('scStatus');");
                sb.append("  btn.disabled = true; btn.textContent = '⏳ מריץ...';");
                sb.append("  status.textContent = ' מתחיל השוואה...';");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/strategy-compare?source=' + source + '&count=' + count + '&balance=' + balance);");
                sb.append("    var d = await r.json();");
                sb.append("    var el = document.getElementById('scResult');");
                sb.append("    el.style.display = 'block';");
                sb.append("    if (d.error) { el.innerHTML = '<div style=\"color:#ef4444;\">' + d.error + '</div>'; status.textContent = ''; return; }");
                sb.append("    status.textContent = ' הושלם!';");
                sb.append("    var html = '<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">';");
                sb.append("    html += '<thead><tr style=\"background:#0b1220;\"><th style=\"padding:8px;text-align:left;\">אסטרטגיה</th><th style=\"padding:8px;text-align:right;\">תשואה</th><th style=\"padding:8px;text-align:right;\">Win Rate</th><th style=\"padding:8px;text-align:right;\">עסקאות</th></tr></thead>';");
                sb.append("    html += '<tbody>';");
                sb.append("    (d.results || []).forEach(function(r) {");
                sb.append("      var retColor = r.returnPct >= 0 ? '#22c55e' : '#ef4444';");
                sb.append("      html += '<tr style=\"border-bottom:1px solid #1f2a44;\"><td style=\"padding:8px;\">' + r.strategy + '</td><td style=\"padding:8px;text-align:right;color:' + retColor + ';\">' + (r.returnPct >= 0 ? '+' : '') + r.returnPct.toFixed(2) + '%</td><td style=\"padding:8px;text-align:right;\">' + r.winRate.toFixed(1) + '%</td><td style=\"padding:8px;text-align:right;\">' + r.trades + '</td></tr>';");
                sb.append("    });");
                sb.append("    html += '</tbody></table>';");
                sb.append("    el.innerHTML = html;");
                sb.append("  } catch(e) { document.getElementById('scResult').innerHTML = '<div style=\"color:#ef4444;\">שגיאה: ' + e.message + '</div>'; status.textContent = ''; }");
                sb.append("  finally { btn.disabled = false; btn.textContent = '🚀 הרץ השוואה'; }");
                sb.append("}");
                sb.append("</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/intraday-start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                sym = sym == null ? "" : sym.trim().toUpperCase();
                synchronized (intradayLock) {
                    intradayState.symbol = sym;
                    intradayState.running = true;
                    intradayState.lastError = null;
                    intradayState.lastSignal = null;
                    intradayState.lastNotifiedSignal = null;
                    intradayState.lastNotifiedBarTs = null;
                    intradayState.startedAtNy = ZonedDateTime.now(NY);
                    intradayState.lastCheckNy = null;
                    intradayState.lastBarTs = null;
                    intradayState.lastPrice = null;
                    intradayState.lastVolume = null;
                    intradayState.history.clear();
                    // Reset scanner fields
                    intradayState.scanResult = null;
                    intradayState.avgDailyVolume = null;
                    intradayState.previousDayHigh = null;
                    intradayState.previousDayClose = null;
                    intradayState.spyChangePct = null;
                    intradayState.openPrice = null;
                    if (intradayExec != null) {
                        try { intradayExec.shutdownNow(); } catch (Exception ignore) {}
                        intradayExec = null;
                    }
                    intradayExec = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("intraday-alerts");
                        return t;
                    });
                    intradayExec.scheduleAtFixedRate(() -> {
                        IntradayAlertState st = intradayState;
                        if (!st.running) return;
                        if (st.symbol == null || st.symbol.isBlank()) return;
                        st.lastCheckNy = ZonedDateTime.now(NY);

                        try {
                            if (st.startedAtNy != null) {
                                long mins = Duration.between(st.startedAtNy, st.lastCheckNy).toMinutes();
                                if (mins >= 40) {
                                    st.running = false;
                                    st.history.add(ZonedDateTime.now(NY).toString() + " | Auto-stopped after 40 minutes");
                                    try { intradayExec.shutdownNow(); } catch (Exception ignore) {}
                                    return;
                                }
                            }
                        } catch (Exception ignore) {}

                        if (!isNyseRegularHoursNow()) return;
                        try {
                            MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                            
                            // Fetch SPY for market sentiment (once per session or periodically)
                            if (st.spyChangePct == null || st.lastCheckNy.getMinute() % 5 == 0) {
                                try {
                                    JsonNode spyQuote = av.globalQuote("SPY");
                                    if (spyQuote != null) {
                                        // Handle both "Global Quote" and "Global Quote - DATA DELAYED BY 15 MINUTES"
                                        JsonNode gq = spyQuote.path("Global Quote");
                                        if (gq == null || gq.isMissingNode()) {
                                            gq = spyQuote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                                        }
                                        String changePct = gq.path("10. change percent").asText("");
                                        if (changePct != null && !changePct.isBlank()) {
                                            changePct = changePct.replace("%", "").trim();
                                            st.spyChangePct = parseDoubleOrNull(changePct);
                                        }
                                    }
                                } catch (Exception ignore) {
                                    if (st.spyChangePct == null) st.spyChangePct = 0.0;
                                }
                            }
                            
                            // Fetch previous day data once
                            if (st.previousDayHigh == null || st.previousDayClose == null || st.avgDailyVolume == null) {
                                try {
                                    Map<String, Double> dailyClose = loadDailyCloseByDateCached(st.symbol);
                                    if (dailyClose != null && !dailyClose.isEmpty()) {
                                        // Get yesterday's close
                                        String yesterday = nyToday();
                                        Double prevClose = bestEffortCloseOnOrBefore(dailyClose, yesterday);
                                        if (prevClose != null) st.previousDayClose = prevClose;
                                    }
                                } catch (Exception ignore) {}
                                
                                // Try to get high and avg volume from daily data
                                try {
                                    JsonNode daily = av.timeSeriesDaily(st.symbol);
                                    if (daily != null) {
                                        JsonNode ts = daily.path("Time Series (Daily)");
                                        if (ts != null && ts.isObject()) {
                                            List<String> dates = new ArrayList<>();
                                            ts.fieldNames().forEachRemaining(dates::add);
                                            dates.sort(Comparator.reverseOrder());
                                            if (!dates.isEmpty()) {
                                                // Previous day high
                                                JsonNode prevDay = ts.path(dates.get(0));
                                                st.previousDayHigh = parseDoubleOrNull(prevDay.path("2. high").asText(""));
                                                if (st.previousDayClose == null) {
                                                    st.previousDayClose = parseDoubleOrNull(prevDay.path("4. close").asText(""));
                                                }
                                                // Calculate 30-day average volume
                                                double sumVol = 0;
                                                int cnt = 0;
                                                for (int i = 0; i < Math.min(30, dates.size()); i++) {
                                                    JsonNode d = ts.path(dates.get(i));
                                                    String v = d.path("5. volume").asText("");
                                                    if (v != null && !v.isBlank()) {
                                                        try {
                                                            sumVol += Double.parseDouble(v.trim());
                                                            cnt++;
                                                        } catch (Exception ignore) {}
                                                    }
                                                }
                                                if (cnt > 0) st.avgDailyVolume = sumVol / cnt;
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                            }
                            
                            JsonNode intraday = av.timeSeriesIntraday(st.symbol, intradayIntervalForCurrentEntitlement());
                            String sig = computeIntradaySignal(intraday, st);
                            st.lastSignal = sig;
                            st.lastError = null;
                            if (("BUY".equals(sig) || "SELL".equals(sig)) && st.lastBarTs != null) {
                                boolean already = sig.equals(st.lastNotifiedSignal) && st.lastBarTs.equals(st.lastNotifiedBarTs);
                                if (!already) {
                                    // Enhanced alert message with scanner data
                                    String msg;
                                    if (st.scanResult != null) {
                                        msg = IntradayScanner.generateAlertMessage(st.scanResult);
                                    } else {
                                        msg = "Intraday alert: " + sig + " " + st.symbol + " (bar " + st.lastBarTs + ") price=" + (st.lastPrice==null?"N/A":String.format("%.4f", st.lastPrice)) + " vol=" + (st.lastVolume==null?"N/A":String.valueOf(st.lastVolume));
                                    }
                                    st.history.add(ZonedDateTime.now(NY).toString() + " | " + sig + " " + st.symbol);
                                    sendTelegram(msg);
                                    st.lastNotifiedSignal = sig;
                                    st.lastNotifiedBarTs = st.lastBarTs;
                                }
                            }
                        } catch (Exception e) {
                            st.lastError = e.getMessage();
                            st.lastPrice = null;
                            st.lastVolume = null;
                            st.lastBarTs = null;
                        }
                    }, 0, 1, TimeUnit.MINUTES);
                }
                ex.getResponseHeaders().add("Location", "/intraday-alerts?status=started");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/intraday-stop", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                synchronized (intradayLock) {
                    intradayState.running = false;
                    intradayState.startedAtNy = null;
                    if (intradayExec != null) {
                        try { intradayExec.shutdownNow(); } catch (Exception ignore) {}
                        intradayExec = null;
                    }
                }
                ex.getResponseHeaders().add("Location", "/intraday-alerts?status=stopped");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // Run Scanner - scan 20 random NASDAQ stocks and send Discord alerts for buy signals
        server.createContext("/run-scanner", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/intraday-alerts");
                    ex.sendResponseHeaders(303, -1);
                    ex.close();
                    return;
                }
                
                // Run scanner in background thread to not block the request
                new Thread(() -> {
                    try {
                        List<String> allTickers = LongTermCandidateFinder.getUniverseTickers();
                        Collections.shuffle(allTickers);
                        List<String> selected = allTickers.subList(0, Math.min(20, allTickers.size()));
                        
                        sendDiscord("🔍 **Scanner Started**\nסורק " + selected.size() + " מניות אקראיות מ-NASDAQ...\n" + String.join(", ", selected));
                        
                        MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                        String interval = intradayIntervalForCurrentEntitlement();
                        int buysFound = 0;
                        
                        for (String symbol : selected) {
                            try {
                                // Throttle to respect API limits
                                Thread.sleep(13000); // ~5 requests per minute for free tier
                                
                                // Get SPY for market sentiment
                                double spyChange = 0.0;
                                try {
                                    JsonNode spyQuote = av.globalQuote("SPY");
                                    if (spyQuote != null) {
                                        JsonNode gq = spyQuote.path("Global Quote");
                                        if (gq == null || gq.isMissingNode()) {
                                            gq = spyQuote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                                        }
                                        String pct = gq.path("10. change percent").asText("").replace("%", "").trim();
                                        if (!pct.isBlank()) spyChange = Double.parseDouble(pct);
                                    }
                                } catch (Exception ignore) {}
                                
                                // Fetch intraday data
                                JsonNode intraday = av.timeSeriesIntraday(symbol, interval);
                                if (intraday == null) continue;
                                
                                JsonNode meta = intraday.path("Meta Data");
                                JsonNode ts = intraday.path("Time Series (" + interval + ")");
                                if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) {
                                    // Try delayed format
                                    ts = intraday.path("Time Series (" + interval + ") - DATA DELAYED BY 15 MINUTES");
                                }
                                if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) continue;
                                
                                // Build bars
                                List<String> keys = new ArrayList<>();
                                ts.fieldNames().forEachRemaining(keys::add);
                                Collections.sort(keys);
                                if (keys.size() < 5) continue;
                                
                                List<IntradayScanner.IntradayBar> bars = new ArrayList<>();
                                for (String tsKey : keys) {
                                    JsonNode bar = ts.path(tsKey);
                                    IntradayScanner.IntradayBar ib = new IntradayScanner.IntradayBar();
                                    ib.timestamp = tsKey;
                                    ib.open = parseDoubleOrNull(bar.path("1. open").asText(""));
                                    ib.high = parseDoubleOrNull(bar.path("2. high").asText(""));
                                    ib.low = parseDoubleOrNull(bar.path("3. low").asText(""));
                                    ib.close = parseDoubleOrNull(bar.path("4. close").asText(""));
                                    String volStr = bar.path("5. volume").asText("");
                                    ib.volume = volStr.isBlank() ? 0L : Long.parseLong(volStr);
                                    bars.add(ib);
                                }
                                
                                // Estimate avg daily volume (sum of volumes / estimated days)
                                long totalVol = bars.stream().mapToLong(b -> b.volume).sum();
                                double avgDailyVol = totalVol > 0 ? totalVol * 0.5 : 1_000_000;
                                
                                // Get prev day high/close from daily data
                                double prevDayHigh = 0, prevDayClose = 0;
                                try {
                                    JsonNode daily = av.timeSeriesDaily(symbol);
                                    if (daily != null) {
                                        JsonNode dailyTs = daily.path("Time Series (Daily)");
                                        if (dailyTs != null && !dailyTs.isMissingNode()) {
                                            List<String> dailyKeys = new ArrayList<>();
                                            dailyTs.fieldNames().forEachRemaining(dailyKeys::add);
                                            Collections.sort(dailyKeys, Collections.reverseOrder());
                                            if (dailyKeys.size() >= 2) {
                                                JsonNode prevDay = dailyTs.path(dailyKeys.get(1));
                                                prevDayHigh = parseDoubleOrNull(prevDay.path("2. high").asText(""));
                                                prevDayClose = parseDoubleOrNull(prevDay.path("4. close").asText(""));
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                                
                                // Run scanner
                                IntradayScanner.ScanResult result = IntradayScanner.scan(symbol, bars, avgDailyVol, prevDayHigh, prevDayClose, spyChange);
                                
                                // Send Discord alert for BUY signals
                                if ("BREAKOUT_BUY".equals(result.signal) || "MOMENTUM_BUY".equals(result.signal)) {
                                    buysFound++;
                                    String msg = IntradayScanner.generateAlertMessage(result);
                                    sendDiscord(msg);
                                    System.out.println("[Scanner] BUY signal for " + symbol + ": " + result.signal);
                                } else {
                                    System.out.println("[Scanner] " + symbol + ": " + result.signal + (result.reasons.isEmpty() ? "" : " - " + result.reasons.get(0)));
                                }
                                
                            } catch (Exception e) {
                                System.err.println("[Scanner] Error scanning " + symbol + ": " + e.getMessage());
                            }
                        }
                        
                        sendDiscord("✅ **Scanner Completed**\nנסרקו " + selected.size() + " מניות\n🎯 נמצאו " + buysFound + " איתותי קנייה");
                        
                    } catch (Exception e) {
                        System.err.println("[Scanner] Error: " + e.getMessage());
                        sendDiscord("❌ **Scanner Error**: " + e.getMessage());
                    }
                }).start();
                
                ex.getResponseHeaders().add("Location", "/intraday-alerts?status=scanner_started");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // About page with model explanations and links
        server.createContext("/about", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String TECH = modelBadge("TECHNICAL");
                String FUND = modelBadge("FUNDAMENTAL");
                String RISK = modelBadge("RISK");
                String content = "" +
                        "<div class=\"card\" dir=\"rtl\" style=\"text-align:right\">" +
                        "<style>.about-ltr{direction:ltr;unicode-bidi:embed;display:inline-block}</style>" +
                        "<div class=\"title\">אודות המודלים (<span class=\"about-ltr\">About</span>)</div>" +
                        "<p>העמוד מפרט את המודלים שבהם האפליקציה משתמשת לניתוח טכני ופונדמנטלי, כולל קישורים ללמידה נוספת.</p>" +
                        "<ul>" +
                        "<li><b><span class=\"about-ltr\">Piotroski F-Score</span></b>" + FUND + " — 9 בדיקות בינאריות של רווחיות/מינוף/יעילות לדירוג מניות ערך.</li>" +
                        "<li><b><span class=\"about-ltr\">Altman Z-Score</span></b>" + RISK + " — מדד סיכון פשיטת-רגל המבוסס על יחסים מאזניים.</li>" +
                        "<li><b><span class=\"about-ltr\">Beneish M-Score</span></b>" + RISK + " — מודל סטטיסטי לזיהוי סבירות למניפולציה חשבונאית (<span class=\"about-ltr\">earnings manipulation</span>) על בסיס דוחות שנתיים. סף נפוץ: <span class=\"about-ltr\">M-Score &gt; -1.78</span> = חשד גבוה. באפליקציה: אם יש חשד, המניה מקבלת ענישה בניקוד ובמצבים מסוימים יורדת ל-<span class=\"about-ltr\">AVOID</span>.</li>" +
                        "<li><b><span class=\"about-ltr\">Sloan Ratio</span></b>" + RISK + " — מדד איכות רווחים (<span class=\"about-ltr\">Accruals</span>): מודד פער בין רווח נקי לבין תזרים מזומנים (<span class=\"about-ltr\">FCF/Operating Cash Flow</span>) ביחס לסך הנכסים. ערך מוחלט גבוה (למשל <span class=\"about-ltr\">|ratio| &gt; 0.25</span>) עשוי להעיד על איכות רווחים נמוכה. באפליקציה: משמש כגורם סיכון שיכול להוריד את ה-<span class=\"about-ltr\">Final Verdict</span> ל-<span class=\"about-ltr\">AVOID</span>.</li>" +
                        "<li><b><span class=\"about-ltr\">Cash Conversion Cycle (CCC)</span></b>" + FUND + " — מדד יעילות הון חוזר: <span class=\"about-ltr\">DIO</span> (ימי מלאי) + <span class=\"about-ltr\">DSO</span> (ימי לקוחות) - <span class=\"about-ltr\">DPO</span> (ימי ספקים). CCC נמוך/שלילי יכול להצביע על מודל עסקי יעיל ותזרים חזק.</li>" +
                        "<li><b><span class=\"about-ltr\">Value Creation (ROIC vs WACC)</span></b>" + FUND + " — מודל יצירת ערך כלכלי: אם <span class=\"about-ltr\">ROIC</span> (תשואה על הון מושקע) גבוה מ-<span class=\"about-ltr\">WACC</span> (עלות הון משוקללת), החברה מייצרת ערך (<span class=\"about-ltr\">Economic Spread</span> חיובי). באפליקציה: זהו חיזוק פונדמנטלי קטן כאשר ROIC משמעותית מעל WACC.</li>" +
                        "<li><b><span class=\"about-ltr\">Quality &amp; Profitability</span></b>" + FUND + " — ROIC, ROE, שיעור רווח גולמי, <span class=\"about-ltr\">FCF Margin</span>, <span class=\"about-ltr\">EBIT Margin</span> ומגמות (<span class=\"about-ltr\">YoY/TTM</span>).</li>" +
                        "<li><b><span class=\"about-ltr\">Growth</span></b>" + FUND + " — קצב צמיחת הכנסות/EPS (<span class=\"about-ltr\">CAGR</span> ל-3/5 שנים), יציבות הצמיחה (סטיית תקן).</li>" +
                        "<li><b><span class=\"about-ltr\">Valuation Mix</span></b>" + FUND + " — <span class=\"about-ltr\">P/B</span> (מכפיל הון), <span class=\"about-ltr\">EV/EBITDA</span>, <span class=\"about-ltr\">EV/Sales</span>, <span class=\"about-ltr\">PEG</span> עם בדיקות סבירות לצמיחה.</li>" +
                        "<li><b><span class=\"about-ltr\">SMA (Simple Moving Average)</span></b>" + TECH + " — ממוצע נע פשוט למדידת מגמה. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/s/sma.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">RSI (Relative Strength Index)</span></b>" + TECH + " — מזהה קניות/מכירות יתר (70/30). <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/r/rsi.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">MACD</span></b>" + TECH + " — מומנטום ושינוי מגמה באמצעות ממוצעים מעריכיים. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/m/macd.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">Stochastic Oscillator</span></b>" + TECH + " — השוואת סגירה לטווח המחירים (<span class=\"about-ltr\">%K/%D</span>, אזורי 20/80). <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/s/stochasticoscillator.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">Bollinger Bands</span></b>" + TECH + " — מדד תנודתיות; רצועות עליונה/תחתונה. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/b/bollingerbands.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">ADX (Average Directional Index)</span></b>" + TECH + " — חוזק מגמה; מעל ~25 מגמה חזקה. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/a/adx.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">ATR (Average True Range)</span></b>" + TECH + " — תנודתיות יומית ממוצעת, שימוש בניהול סיכונים ו-<span class=\"about-ltr\">Stop-Loss</span>. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/a/atr.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">CMF (Chaikin Money Flow)</span></b>" + TECH + " — צבירה/פיזור לפי מחיר ונפח. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/c/chaikinmoneyflow.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">Pivot Points</span></b>" + TECH + " — נקודת ציר יומית (<span class=\"about-ltr\">PP</span>) ורמות תמיכה/התנגדות. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/p/pivotpoint.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">Fibonacci Retracement</span></b>" + TECH + " — רמות 38.2%/50%/61.8% לאיתור אזורי כניסה לאחר תיקון. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/f/fibonacciretracement.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">DCF (Discounted Cash Flow)</span></b>" + FUND + " — שווי פנימי מבוסס תחזית תזרימי מזומנים. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/d/dcf.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">PEG Ratio</span></b>" + FUND + " — יחס <span class=\"about-ltr\">P/E</span> לצמיחה; ~1 הוגן, &lt;1 זול, &gt;2 יקר. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/p/pegratio.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "<li><b><span class=\"about-ltr\">Graham Valuation</span></b>" + FUND + " — מודל הערכת שווי לפי בנג'מין גרהם. כולל: (1) <span class=\"about-ltr\">Graham Number</span> — תקרת מחיר שמרנית: √(22.5 × EPS × BVPS), (2) נוסחת השווי הפנימי: V = EPS × (8.5 + 2g) × 4.4/Y כאשר g=צמיחה ו-Y=תשואת אג\"ח. מרווח ביטחון (<span class=\"about-ltr\">Margin of Safety</span>) של 33%+ מומלץ. <span class=\"about-ltr\"><a href=\"https://www.investopedia.com/terms/g/graham-number.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></span></li>" +
                        "</ul>" +
                        "<p style=\"color:#9ca3af\">המודלים מוצגים לצורכי לימוד בלבד ואינם מהווים ייעוץ השקעות.</p>" +
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // BackTest page - Historical strategy testing
        server.createContext("/backtest", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>📊 BackTest Engine | מנוע בדיקה היסטורית</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>Test trading strategies on historical data (2022-2026). Calculate Win Rate, Profit Factor, Expectancy, Max Drawdown, CAGR, Sharpe.</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;' dir='rtl'>בדוק אסטרטגיות על נתונים היסטוריים. חשב Win Rate, Profit Factor, Expectancy, Max Drawdown, CAGR, Sharpe.</div>");

                // Cache status indicator
                sb.append("<div id='cacheStatus' style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:8px;padding:12px;margin-bottom:16px;'>");
                sb.append("<div style='display:flex;align-items:center;justify-content:space-between;'>");
                sb.append("<div style='display:flex;align-items:center;gap:8px;'>");
                sb.append("<span id='cacheStatusIcon' style='font-size:20px;'>⏳</span>");
                sb.append("<div>");
                sb.append("<div id='cacheStatusText' style='font-weight:600;color:#93c5fd;'>Loading cache status...</div>");
                sb.append("<div id='cacheStatusDetails' style='font-size:12px;color:#9ca3af;'></div>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<button id='startCacheBuild' onclick='startCacheBuild()' style='background:#22c55e;padding:8px 16px;border:none;border-radius:6px;color:#fff;font-weight:600;cursor:pointer;font-size:12px;'>🚀 Build Cache</button>");
                sb.append("</div>");
                sb.append("</div>");

                // Backtest form
                sb.append("<form method='post' action='/backtest-run' style='margin-bottom:20px;'>");
                sb.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;'>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Agent</label>");
                sb.append("<select name='agent' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<option value='FUND_MOMENTUM_V1'>FUND_MOMENTUM_V1</option>");
                sb.append("<option value='QUALITY_GROWTH_V1'>QUALITY_GROWTH_V1</option>");
                sb.append("</select>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Tickers (comma separated)</label>");
                sb.append("<input type='text' id='backtestTickers' name='tickers' placeholder='AAPL,MSFT,GOOGL,AMZN,NVDA,META,TSLA,JPM,JNJ,UNH,XOM,CAT,HD,PG,NEE,LIN,AMT,DIS,NFLX' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<button type='button' onclick='loadUniverseTickers(\"backtestTickers\")' style='margin-top:4px;padding:6px 12px;background:#4c1d95;color:white;border:none;border-radius:4px;cursor:pointer;font-size:12px;'>📋 Load All Universe Tickers</button>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Start Date</label>");
                sb.append("<input type='date' name='startDate' value='2022-01-01' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>End Date</label>");
                sb.append("<input type='date' name='endDate' value='2026-06-13' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Stop Loss %</label>");
                sb.append("<input type='number' name='stopLoss' value='5' step='0.5' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Take Profit %</label>");
                sb.append("<input type='number' name='takeProfit' value='10' step='0.5' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Max Days Held</label>");
                sb.append("<input type='number' name='maxDays' value='7' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<div>");
                sb.append("<label style='display:block;color:#a78bfa;font-size:12px;margin-bottom:4px;'>Or use sector tickers</label>");
                sb.append("<select name='sector' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<option value=''>Custom tickers</option>");
                sb.append("<option value='all'>All sectors (500+ tickers)</option>");
                sb.append("<option value='Technology'>Technology</option>");
                sb.append("<option value='Financials'>Financials</option>");
                sb.append("<option value='Healthcare'>Healthcare</option>");
                sb.append("</select>");
                sb.append("</div>");
                
                sb.append("</div>");
                
                sb.append("<button type='submit' style='background:#7c3aed;padding:10px 20px;border:none;border-radius:6px;color:#fff;font-weight:600;cursor:pointer;'>🚀 Run Backtest</button>");
                sb.append("</form>");

                sb.append("<script>"+
                        "async function loadUniverseTickers(inputId){"+
                        "  console.log('Loading universe tickers for:', inputId);"+
                        "  try{"+
                        "    var r=await fetch('/api/universe-tickers');"+
                        "    console.log('Response status:', r.status);"+
                        "    var d=await r.json();"+
                        "    console.log('Response data:', d);"+
                        "    if(d.tickers){"+
                        "      document.getElementById(inputId).value=d.tickers;"+
                        "      alert('Loaded '+d.count+' universe tickers');"+
                        "    }else{"+
                        "      alert('No tickers found in response');"+
                        "    }"+
                        "  }catch(e){console.error('Error loading universe tickers:', e);alert('Failed to load universe tickers: '+e.message);}"+
                        "}"+
                        "async function startCacheBuild(){"+
                        "  var btn=document.getElementById('startCacheBuild');"+
                        "  if(btn) btn.disabled=true;"+
                        "  try{"+
                        "    var r=await fetch('/api/cache-build/start',{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    if(d.error){alert('Error: '+d.error);if(btn) btn.disabled=false;return;}"+
                        "    alert('Cache build started for '+d.tickers+' tickers. This will take several hours due to API rate limits.');"+
                        "    updateCacheStatus();"+
                        "  }catch(e){console.error('Error starting cache build:', e);alert('Failed to start cache build: '+e.message);if(btn) btn.disabled=false;}"+
                        "}"+
                        "async function updateCacheStatus(){"+
                        "  try{"+
                        "    var r=await fetch('/api/cache-status');"+
                        "    var d=await r.json();"+
                        "    var icon=document.getElementById('cacheStatusIcon');"+
                        "    var text=document.getElementById('cacheStatusText');"+
                        "    var details=document.getElementById('cacheStatusDetails');"+
                        "    var btn=document.getElementById('startCacheBuild');"+
                        "    if(d.running){"+
                        "      icon.textContent='⏳';"+
                        "      text.textContent='Cache Building In Progress';"+
                        "      text.style.color='#f59e0b';"+
                        "      details.textContent='Progress: '+d.progress+'/'+d.total+' tickers ('+d.startDate+' to '+d.endDate+')';"+
                        "      if(btn) btn.disabled=true;"+
                        "    }else if(d.status==='completed'){"+
                        "      icon.textContent='✅';"+
                        "      text.textContent='Cache Build Completed';"+
                        "      text.style.color='#22c55e';"+
                        "      if(d.cacheExists){"+
                        "        details.textContent='Cache updated: '+d.cacheLastUpdated+' ('+d.cacheTickers+' tickers)';"+
                        "      }else{"+
                        "        details.textContent='Build completed but cache file not found';"+
                        "      }"+
                        "      if(btn) btn.disabled=false;"+
                        "    }else if(d.status==='error'){"+
                        "      icon.textContent='❌';"+
                        "      text.textContent='Cache Build Error';"+
                        "      text.style.color='#ef4444';"+
                        "      details.textContent='An error occurred during cache building';"+
                        "      if(btn) btn.disabled=false;"+
                        "    }else{"+
                        "      icon.textContent='⏸️';"+
                        "      text.textContent='Cache Not Started';"+
                        "      text.style.color='#9ca3af';"+
                        "      details.textContent='Click Build Cache to start (will take several hours)';"+
                        "      if(btn) btn.disabled=false;"+
                        "    }"+
                        "  }catch(e){console.error('Error fetching cache status:', e);}"+
                        "}"+
                        "updateCacheStatus();"+
                        "setInterval(updateCacheStatus,5000);"+
                        "</script>");
                
                // Strategy Laboratory section
                sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:14px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:8px;'>🧪 Strategy Laboratory</div>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:12px;'>Automatically test parameter combinations on 500 stocks over 4 years. Returns results in ~10 minutes.</div>");
                sb.append("<form method='post' action='/backtest-lab' style='margin-bottom:12px;'>");
                sb.append("<div style='display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin-bottom:8px;'>");
                sb.append("<input type='number' name='rsiMin' placeholder='RSI Min' value='50' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("<input type='number' name='rsiMax' placeholder='RSI Max' value='70' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("<input type='number' name='rvolMin' placeholder='RVOL Min' value='1.5' step='0.1' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("<input type='number' name='revenueGrowthMin' placeholder='Revenue Growth %' value='15' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("<input type='number' name='epsGrowthMin' placeholder='EPS Growth %' value='20' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("<input type='number' name='stopLoss' placeholder='Stop Loss %' value='5' style='padding:6px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:4px;color:#e5e7eb;'>");
                sb.append("</div>");
                sb.append("<button type='submit' style='background:#f59e0b;padding:8px 16px;border:none;border-radius:6px;color:#fff;font-weight:600;cursor:pointer;font-size:13px;'>🧪 Run Strategy Lab</button>");
                sb.append("</form>");
                sb.append("</div>");

                // Recent backtests section
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#a78bfa;margin-bottom:8px;'>📋 Recent Backtests</div>");
                sb.append("<div style='color:#6b7280;font-size:12px;'>No backtests run yet. Run a backtest to see results here.</div>");
                sb.append("</div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // Backtest run endpoint
        server.createContext("/backtest-run", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                String body = readBody(ex);
                Map<String, String> form = parseForm(body);

                String agent = form.getOrDefault("agent", "FUND_MOMENTUM_V1");
                String tickersStr = form.getOrDefault("tickers", "");
                String startDate = form.getOrDefault("startDate", "2022-01-01");
                String endDate = form.getOrDefault("endDate", "2026-06-13");
                double stopLoss = Double.parseDouble(form.getOrDefault("stopLoss", "5"));
                double takeProfit = Double.parseDouble(form.getOrDefault("takeProfit", "10"));
                int maxDays = Integer.parseInt(form.getOrDefault("maxDays", "7"));
                String sector = form.getOrDefault("sector", "");

                List<String> tickers = new ArrayList<>();
                if (!sector.isEmpty() && !sector.equals("all")) {
                    tickers = LongTermCandidateFinder.getSectorTickers(sector);
                } else if (sector.equals("all")) {
                    tickers = LongTermCandidateFinder.getAllSectorTickers();
                } else if (!tickersStr.isEmpty()) {
                    String[] parts = tickersStr.split(",");
                    for (String part : parts) {
                        tickers.add(part.trim().toUpperCase());
                    }
                }

                if (tickers.isEmpty()) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "No tickers specified");
                    respondJson(ex, error, 400);
                    return;
                }

                // Run backtest in background
                final List<String> finalTickers = tickers;
                new Thread(() -> {
                    try {
                        BacktestEngine.BacktestResults results = BacktestEngine.runBacktest(
                            agent, finalTickers, startDate, endDate, stopLoss, takeProfit, maxDays
                        );
                        System.out.println("[Backtest] Completed: " + results.totalTrades + " trades, WR: " + 
                            String.format("%.1f%%", results.winRate) + ", PF: " + 
                            String.format("%.2f", results.profitFactor));
                    } catch (Exception e) {
                        System.err.println("[Backtest] Error: " + e.getMessage());
                    }
                }).start();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "started");
                response.put("agent", agent);
                response.put("tickerCount", tickers.size());
                response.put("message", "Backtest started in background. Results will appear below when complete.");
                respondJson(ex, response, 200);
            }
        });

        // Cache management endpoint
        server.createContext("/cache-status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                Map<String, Object> response = new LinkedHashMap<>();
                MarketDataCache.MarketCache cache = MarketDataCache.loadCache();
                
                if (cache != null) {
                    response.put("exists", true);
                    response.put("lastUpdated", cache.lastUpdated);
                    response.put("totalTickers", cache.totalTickers);
                    response.put("tickers", new ArrayList<>(cache.tickers.keySet()));
                } else {
                    response.put("exists", false);
                }
                
                respondJson(ex, response, 200);
            }
        });

        server.createContext("/cache-build", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                String body = readBody(ex);
                Map<String, String> form = parseForm(body);

                String tickersStr = form.getOrDefault("tickers", "");
                String sector = form.getOrDefault("sector", "");

                List<String> tickers = new ArrayList<>();
                if (!sector.isEmpty() && !sector.equals("all")) {
                    tickers = LongTermCandidateFinder.getSectorTickers(sector);
                } else if (sector.equals("all")) {
                    tickers = LongTermCandidateFinder.getAllSectorTickers();
                } else if (!tickersStr.isEmpty()) {
                    String[] parts = tickersStr.split(",");
                    for (String part : parts) {
                        tickers.add(part.trim().toUpperCase());
                    }
                }

                if (tickers.isEmpty()) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "No tickers specified");
                    respondJson(ex, error, 400);
                    return;
                }

                // Run cache build in background
                final List<String> finalTickers = tickers;
                new Thread(() -> {
                    try {
                        MarketDataCache.fetchAndCacheData(finalTickers);
                    } catch (Exception e) {
                        System.err.println("[Cache] Error: " + e.getMessage());
                    }
                }).start();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "started");
                response.put("tickerCount", tickers.size());
                response.put("message", "Cache build started in background. This will take several hours for all tickers.");
                respondJson(ex, response, 200);
            }
        });

        // Backtest status endpoint
        server.createContext("/backtest-status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("running", BacktestEngine.isBacktestRunning());
                response.put("agent", BacktestEngine.getLastBacktestAgent());
                
                BacktestEngine.BacktestResults results = BacktestEngine.getLastResults();
                if (results != null) {
                    response.put("completed", true);
                    response.put("totalTrades", results.totalTrades);
                    response.put("winRate", results.winRate);
                    response.put("profitFactor", results.profitFactor);
                    response.put("expectancy", results.expectancy);
                    response.put("maxDrawdown", results.maxDrawdown);
                    response.put("totalProfit", results.totalProfit);
                    response.put("totalLoss", results.totalLoss);
                    response.put("avgWin", results.avgWin);
                    response.put("avgLoss", results.avgLoss);
                } else {
                    response.put("completed", false);
                }
                
                respondJson(ex, response, 200);
            }
        });

        // Strategy Laboratory endpoint
        server.createContext("/backtest-lab", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                String body = readBody(ex);
                Map<String, String> form = parseForm(body);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "not_implemented");
                response.put("message", "Strategy Laboratory coming soon. Use the manual backtest for now.");
                respondJson(ex, response, 200);
            }
        });

        // ---------------- Agent Backtest Page ----------------
        server.createContext("/agent-backtest", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'>");
                sb.append("<div class='title'>🕰️ Agent Backtest - Historical Performance</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>Run your agents on historical data to see how they would have performed. Uses the same indicators and logic as live agents.</div>");

                // Agent selection form
                sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:12px;'>Select Agent & Date Range</div>");
                
                sb.append("<div id='agentSelector' style='margin-bottom:12px;'>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Agent:</label>");
                sb.append("<select id='agentSelect' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<option value=''>Loading agents...</option>");
                sb.append("</select>");
                sb.append("</div>");

                sb.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;'>");
                sb.append("<div>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Start Date:</label>");
                sb.append("<input type='date' id='startDate' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                sb.append("<div>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>End Date:</label>");
                sb.append("<input type='date' id='endDate' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<div style='margin-bottom:12px;'>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Initial Capital ($):</label>");
                sb.append("<input type='number' id='initialCapital' value='100000' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");

                sb.append("<div style='margin-bottom:12px;'>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Ticker Universe (optional, comma-separated):</label>");
                sb.append("<input type='text' id='agentBacktestTickers' placeholder='AAPL,MSFT,GOOGL,AMZN,NVDA,META,TSLA,JPM,JNJ,UNH,XOM,CAT,HD,PG,NEE,LIN,AMT,DIS,NFLX' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<button type='button' onclick='loadUniverseTickers(\"agentBacktestTickers\")' style='margin-top:4px;padding:6px 12px;background:#4c1d95;color:white;border:none;border-radius:4px;cursor:pointer;font-size:12px;'>📋 Load All Universe Tickers</button>");
                sb.append("<div style='color:#6b7280;font-size:11px;margin-top:4px;'>Leave empty to use default universe (all sectors)</div>");
                sb.append("</div>");

                sb.append("<button id='runBacktestBtn' onclick='runAgentBacktest()' style='background:#7c3aed;color:white;padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-weight:600;'>🚀 Run Backtest</button>");
                sb.append("</div>");

                // Cache Management section
                sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:12px;'>💾 Market Data Cache</div>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:12px;'>Cache market data to enable fast backtesting without API rate limits. Build once, run many backtests instantly.</div>");
                
                sb.append("<div id='cacheStatus' style='margin-bottom:12px;'></div>");
                
                sb.append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px;'>");
                sb.append("<div>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Sector:</label>");
                sb.append("<select id='cacheSector' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<option value='ALL'>All sectors (500+ tickers)</option>");
                sb.append("<option value='TECHNOLOGY'>Technology</option>");
                sb.append("<option value='FINANCIALS'>Financials</option>");
                sb.append("<option value='HEALTHCARE'>Healthcare</option>");
                sb.append("<option value='ENERGY'>Energy</option>");
                sb.append("<option value='INDUSTRIALS'>Industrials</option>");
                sb.append("<option value='CONSUMER_DISCRETIONARY'>Consumer Discretionary</option>");
                sb.append("<option value='CONSUMER_STAPLES'>Consumer Staples</option>");
                sb.append("<option value='UTILITIES'>Utilities</option>");
                sb.append("<option value='MATERIALS'>Materials</option>");
                sb.append("<option value='REAL_ESTATE'>Real Estate</option>");
                sb.append("<option value='COMMUNICATION_SERVICES'>Communication Services</option>");
                sb.append("</select>");
                sb.append("</div>");
                sb.append("<div>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Months Back:</label>");
                sb.append("<select id='monthsBack' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("<option value='6'>6 months (recommended for backtest)</option>");
                sb.append("<option value='12'>12 months</option>");
                sb.append("<option value='24'>24 months</option>");
                sb.append("<option value=''>All available data</option>");
                sb.append("</select>");
                sb.append("</div>");
                sb.append("</div>");
                
                sb.append("<div style='margin-bottom:12px;'>");
                sb.append("<label style='color:#9ca3af;display:block;margin-bottom:6px;'>Or custom tickers:</label>");
                sb.append("<input type='text' id='cacheTickers' placeholder='AAPL,MSFT,GOOGL' style='width:100%;padding:8px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:6px;color:#e5e7eb;'>");
                sb.append("</div>");
                
                sb.append("<button id='fetchDataBtn' onclick='fetchMarketData()' style='background:#7c3aed;color:white;padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-weight:600;'>📥 Fetch Market Data (6 months)</button>");
                sb.append("<button onclick='runAgentBacktest()' style='background:#4b5563;color:white;padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-weight:600;'>📋 Check Status</button>");
                sb.append("</div>");
                
                // Progress bar for market data fetch
                sb.append("<div id='fetchProgressContainer' style='display:none;margin-top:12px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'>");
                sb.append("<span id='fetchProgressText' style='color:#9ca3af;font-size:12px;'>Fetching data...</span>");
                sb.append("<span id='fetchProgressPct' style='color:#93c5fd;font-size:12px;'>0%</span>");
                sb.append("</div>");
                sb.append("<div style='background:#1e1b4b;border-radius:6px;height:8px;overflow:hidden;'>");
                sb.append("<div id='fetchProgressBar' style='background:#7c3aed;height:100%;width:0%;transition:width 0.3s;'></div>");
                sb.append("</div>");
                sb.append("<div id='fetchProgressTicker' style='color:#6b7280;font-size:11px;margin-top:4px;'></div>");
                sb.append("</div>");

                // Results section
                sb.append("<div id='backtestResults' style='display:none;background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:12px;'>📊 Backtest Results</div>");
                
                // Summary metrics
                sb.append("<div id='summaryMetrics' style='display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px;'></div>");

                // Detailed metrics
                sb.append("<div id='detailedMetrics' style='background:#1e1b4b;border-radius:8px;padding:12px;margin-bottom:16px;'></div>");

                // Trades table
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:8px;'>📋 Trades</div>");
                sb.append("<div id='tradesTable' style='max-height:400px;overflow-y:auto;'></div>");
                sb.append("</div>");

                sb.append("</div>");

                sb.append("<script>"+
                        "// Load available agents"+
                        "async function loadAgents(){"+
                        "  try{"+
                        "    var r=await fetch('/api/agents');"+
                        "    var d=await r.json();"+
                        "    var sel=document.getElementById('agentSelect');"+
                        "    sel.innerHTML='<option value=\"\">Select an agent...</option>';"+
                        "    if(d.agents){"+
                        "      d.agents.forEach(function(a){"+
                        "        var opt=document.createElement('option');"+
                        "        opt.value=a.id;"+
                        "        opt.text=a.name+' ('+a.nameHe+')';"+
                        "        sel.appendChild(opt);"+
                        "      });"+
                        "    }"+
                        "  }catch(e){console.error(e);}"+
                        "}"+

                        "// Set default dates (1 year back to today)"+
                        "function setDefaultDates(){"+
                        "  var today=new Date();"+
                        "  var oneYearAgo=new Date();"+
                        "  oneYearAgo.setFullYear(today.getFullYear()-1);"+
                        "  document.getElementById('endDate').value=today.toISOString().split('T')[0];"+
                        "  document.getElementById('startDate').value=oneYearAgo.toISOString().split('T')[0];"+
                        "}"+
                        
                        "// Check cache status on page load"+
                        "async function checkCacheStatus(){"+
                        "  try{"+
                        "    var r=await fetch('/cache-status');"+
                        "    var d=await r.json();"+
                        "    var statusDiv=document.getElementById('cacheStatus');"+
                        "    if(d.exists){"+
                        "      statusDiv.innerHTML='<div style=\"color:#22c55e;font-size:12px;\">✅ Cache exists: '+d.totalTickers+' tickers (updated: '+d.lastUpdated+')</div>';"+
                        "    }else{"+
                        "      statusDiv.innerHTML='<div style=\"color:#ef4444;font-size:12px;\">❌ No cache found. Build cache to enable fast backtesting.</div>';"+
                        "    }"+
                        "  }catch(e){console.error(e);}"+
                        "}"+

                        "// Fetch market data for backtesting"+
                        "async function fetchMarketData(){"+
                        "  var sector=document.getElementById('cacheSector').value;"+
                        "  var monthsBack=document.getElementById('monthsBack').value;"+
                        "  var tickers=document.getElementById('cacheTickers').value;"+
                        "  "+
                        "  if(!sector&&!tickers){alert('Please select a sector or enter custom tickers');return;}"+
                        "  "+
                        "  var tickerCount=sector==='ALL'?500:50;"+
                        "  var timeEstimate=Math.ceil(tickerCount*36/60);"+
                        "  if(!confirm('Fetching data for '+tickerCount+' tickers (last '+monthsBack+' months).\\n\\nThis will take approximately '+timeEstimate+' minutes due to API rate limits (36 seconds per ticker).\\n\\nContinue?'))return;"+
                        "  "+
                        "  var btn=document.getElementById('fetchDataBtn');"+
                        "  btn.disabled=true;btn.textContent='⏳ Fetching...';"+
                        "  "+
                        "  // Show progress bar"+
                        "  var progressContainer=document.getElementById('fetchProgressContainer');"+
                        "  var progressBar=document.getElementById('fetchProgressBar');"+
                        "  var progressText=document.getElementById('fetchProgressText');"+
                        "  var progressPct=document.getElementById('fetchProgressPct');"+
                        "  var progressTicker=document.getElementById('fetchProgressTicker');"+
                        "  progressContainer.style.display='block';"+
                        "  progressBar.style.width='0%';"+
                        "  progressText.textContent='Fetching data...';"+
                        "  progressPct.textContent='0%';"+
                        "  progressTicker.textContent='';"+
                        "  "+
                        "  try{"+
                        "    var params='?sector='+encodeURIComponent(sector);"+
                        "    if(monthsBack) params+='&monthsBack='+encodeURIComponent(monthsBack);"+
                        "    if(tickers) params+='&tickers='+encodeURIComponent(tickers);"+
                        "    "+
                        "    var r=await fetch('/api/fetch-market-data'+params,{method:'POST'});"+
                        "    var d=await r.json();"+
                        "    "+
                        "    // Start polling for progress"+
                        "    pollFetchProgress();"+
                        "  }catch(e){"+
                        "    alert('Error: '+e);"+
                        "    btn.disabled=false;btn.textContent='📥 Fetch Market Data (6 months)';"+
                        "    progressContainer.style.display='none';"+
                        "  }"+
                        "  "+
                        "  btn.disabled=false;btn.textContent='📥 Fetch Market Data (6 months)';"+
                        "}"+
                        "  "+
                        "// Poll for market data fetch progress"+
                        "async function pollFetchProgress(){"+
                        "  var pollInterval=setInterval(async function(){"+
                        "    try{"+
                        "      var r=await fetch('/api/fetch-market-data-status');"+
                        "      var d=await r.json();"+
                        "      "+
                        "      var progressBar=document.getElementById('fetchProgressBar');"+
                        "      var progressText=document.getElementById('fetchProgressText');"+
                        "      var progressPct=document.getElementById('fetchProgressPct');"+
                        "      var progressTicker=document.getElementById('fetchProgressTicker');"+
                        "      var btn=document.getElementById('fetchDataBtn');"+
                        "      "+
                        "      if(d.running){"+
                        "        var pct=Math.round((d.progress/d.total)*100);"+
                        "        progressBar.style.width=pct+'%';"+
                        "        progressText.textContent='Fetching data...';"+
                        "        progressPct.textContent=pct+'%';"+
                        "        progressTicker.textContent='Current: '+d.currentTicker+' ('+d.progress+'/'+d.total+')';"+
                        "      }else if(d.status==='completed'){"+
                        "        clearInterval(pollInterval);"+
                        "        progressBar.style.width='100%';"+
                        "        progressText.textContent='✅ Completed';"+
                        "        progressPct.textContent='100%';"+
                        "        progressTicker.textContent='';"+
                        "        btn.disabled=false;btn.textContent='📥 Fetch Market Data (6 months)';"+
                        "        alert('Data fetch completed successfully!');"+
                        "        checkCacheStatus();"+
                        "        setTimeout(function(){progressContainer.style.display='none';},3000);"+
                        "      }else if(d.status==='error'){"+
                        "        clearInterval(pollInterval);"+
                        "        progressText.textContent='❌ Error';"+
                        "        progressTicker.textContent='';"+
                        "        btn.disabled=false;btn.textContent='📥 Fetch Market Data (6 months)';"+
                        "        alert('Error fetching data. Please try again.');"+
                        "        setTimeout(function(){progressContainer.style.display='none';},3000);"+
                        "      }"+
                        "    }catch(e){"+
                        "      console.error('Poll error:',e);"+
                        "    }"+
                        "  },2000);"+
                        "}"+

                        "// Run agent backtest"+
                        "async function runAgentBacktest(){"+
                        "  var agentId=document.getElementById('agentSelect').value;"+
                        "  var startDate=document.getElementById('startDate').value;"+
                        "  var endDate=document.getElementById('endDate').value;"+
                        "  var tickers=document.getElementById('agentBacktestTickers').value;"+
                        "  "+
                        "  if(!agentId){alert('Please select an agent');return;}"+
                        "  if(!startDate||!endDate){alert('Please select date range');return;}"+
                        "  "+
                        "  var btn=document.getElementById('runBacktestBtn');"+
                        "  btn.disabled=true;btn.textContent='⏳ Running...';"+
                        "  "+
                        "  try{"+
                        "    var formData=new FormData();"+
                        "    formData.append('agent',agentId);"+
                        "    formData.append('startDate',startDate);"+
                        "    formData.append('endDate',endDate);"+
                        "    formData.append('stopLoss','5');"+
                        "    formData.append('takeProfit','10');"+
                        "    formData.append('maxDays','7');"+
                        "    if(tickers) formData.append('tickers',tickers);"+
                        "    "+
                        "    var r=await fetch('/backtest-run',{method:'POST',body:formData});"+
                        "    var d=await r.json();"+
                        "    "+
                        "    if(d.error){"+
                        "      btn.disabled=false;btn.textContent='🚀 Run Backtest';"+
                        "      alert('Error: '+d.error);"+
                        "      return;"+
                        "    }"+
                        "    "+
                        "    // Start polling for results"+
                        "    pollBacktestResults();"+
                        "  }catch(e){"+
                        "    btn.disabled=false;btn.textContent='🚀 Run Backtest';"+
                        "    alert('Error: '+e);"+
                        "  }"+
                        "}"+
                        "// Poll for backtest results"+
                        "async function pollBacktestResults(){"+
                        "  var pollInterval=setInterval(async function(){"+
                        "    try{"+
                        "      var r=await fetch('/backtest-status');"+
                        "      var d=await r.json();"+
                        "      "+
                        "      if(d.running){"+
                        "        document.getElementById('runBacktestBtn').textContent='⏳ Running... ('+d.agent+')';"+
                        "      }else if(d.completed){"+
                        "        clearInterval(pollInterval);"+
                        "        document.getElementById('runBacktestBtn').disabled=false;"+
                        "        document.getElementById('runBacktestBtn').textContent='🚀 Run Backtest';"+
                        "        displayBacktestResults(d);"+
                        "      }"+
                        "    }catch(e){"+
                        "      console.error('Poll error:',e);"+
                        "    }"+
                        "  },2000);"+
                        "}"+
                        "// Display backtest results from status endpoint"+
                        "function displayBacktestResults(d){"+
                        "  document.getElementById('backtestResults').style.display='block';"+
                        "  "+
                        "  // Helper function for safe toFixed"+
                        "  function safeFixed(val,decimals){return (val!=null&&!isNaN(val))?val.toFixed(decimals):'0.00';}"+
                        "  function safeInt(val){return (val!=null&&!isNaN(val))?val:0;}"+
                        "  "+
                        "  // Summary metrics"+
                        "  var summaryHtml="+
                        "    '<div style=\"background:#1e1b4b;border-radius:8px;padding:12px;\">'+"+
                        "    '<div style=\"color:#9ca3af;font-size:12px;\">Total Trades</div>'"+
                        "    '<div style=\"font-size:20px;font-weight:600;color:#e5e7eb;\">'+safeInt(d.totalTrades)+'</div>'"+
                        "    '</div>'"+
                        "    '<div style=\"background:#1e1b4b;border-radius:8px;padding:12px;\">'+"+
                        "    '<div style=\"color:#9ca3af;font-size:12px;\">Net Profit</div>'"+
                        "    '<div style=\"font-size:20px;font-weight:600;color:'+(d.totalPnL>=0?'#22c55e':'#ef4444')+';\">$'+safeFixed(d.totalPnL,0)+'</div>'"+
                        "    '</div>'"+
                        "    '<div style=\"background:#1e1b4b;border-radius:8px;padding:12px;\">'+"+
                        "    '<div style=\"color:#9ca3af;font-size:12px;\">Profit Factor</div>'"+
                        "    '<div style=\"font-size:20px;font-weight:600;color:'+(d.profitFactor>=1?'#22c55e':'#ef4444')+'\">'+safeFixed(d.profitFactor,2)+'</div>'"+
                        "    '</div>'"+
                        "    '<div style=\"background:#1e1b4b;border-radius:8px;padding:12px;\">'+"+
                        "    '<div style=\"color:#9ca3af;font-size:12px;\">Expectancy</div>'"+
                        "    '<div style=\"font-size:20px;font-weight:600;color:'+(d.expectancy>=0?'#22c55e':'#ef4444')+'\">'+safeFixed(d.expectancy,2)+'%</div>'"+
                        "    '</div>';"+
                        "  document.getElementById('summaryMetrics').innerHTML=summaryHtml;"+
                        "  "+
                        "  // Detailed metrics"+
                        "  var detailHtml="+
                        "    '<div style=\"display:grid;grid-template-columns:repeat(3,1fr);gap:8px;\">'+"+
                        "    '<div><span style=\"color:#9ca3af;\">Winning Trades:</span> <span style=\"color:#22c55e;\">'+safeInt(d.winningTrades)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Losing Trades:</span> <span style=\"color:#ef4444;\">'+safeInt(d.losingTrades)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Avg Win:</span> <span style=\"color:#22c55e;\">$'+safeFixed(d.avgWin,2)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Avg Loss:</span> <span style=\"color:#ef4444;\">$'+safeFixed(d.avgLoss,2)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Max Drawdown:</span> <span style=\"color:#ef4444;\">$'+safeFixed(d.maxDrawdown,2)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Expectancy:</span> <span style=\"color:'+(d.expectancy>=0?'#22c55e':'#ef4444')+';\">$'+safeFixed(d.expectancy,2)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">Sharpe Ratio:</span> <span style=\"color:#93c5fd;\">'+safeFixed(d.sharpeRatio,2)+'</span></div>'"+
                        "    '<div><span style=\"color:#9ca3af;\">CAGR:</span> <span style=\"color:#93c5fd;\">'+safeFixed(d.cagr,2)+'%</span></div>'"+
                        "    '</div>';"+
                        "  document.getElementById('detailedMetrics').innerHTML=detailHtml;"+
                        "  "+
                        "  // Trades table"+
                        "  if(d.trades&&d.trades.length>0){"+
                        "    var tableHtml="+
                        "      '<table style=\"width:100%;border-collapse:collapse;font-size:12px;\">'+"+
                        "      '<thead><tr style=\"background:#1e3a5f;\">'+"+
                        "      '<th style=\"padding:8px;text-align:left;color:#93c5fd;\">Date</th>'"+
                        "      '<th style=\"padding:8px;text-align:left;color:#93c5fd;\">Ticker</th>'"+
                        "      '<th style=\"padding:8px;text-align:right;color:#93c5fd;\">Entry</th>'"+
                        "      '<th style=\"padding:8px;text-align:right;color:#93c5fd;\">Exit</th>'"+
                        "      '<th style=\"padding:8px;text-align:right;color:#93c5fd;\">P&L %</th>'"+
                        "      '<th style=\"padding:8px;text-align:right;color:#93c5fd;\">P&L $</th>'"+
                        "      '<th style=\"padding:8px;text-align:center;color:#93c5fd;\">Days</th>'"+
                        "      '<th style=\"padding:8px;text-align:center;color:#93c5fd;\">Exit Reason</th>'"+
                        "      '</tr></thead><tbody>';"+
                        "    d.trades.forEach(function(t){"+
                        "      var plColor=t.profitPct>=0?'#22c55e':'#ef4444';"+
                        "      tableHtml+="+
                        "        '<tr style=\"border-bottom:1px solid #1e3a5f;\">'+"+
                        "        '<td style=\"padding:8px;color:#e5e7eb;\">'+t.entryDate+'</td>'"+
                        "        '<td style=\"padding:8px;color:#93c5fd;font-weight:600;\">'+t.ticker+'</td>'"+
                        "        '<td style=\"padding:8px;text-align:right;color:#e5e7eb;\">$'+t.entryPrice.toFixed(2)+'</td>'"+
                        "        '<td style=\"padding:8px;text-align:right;color:#e5e7eb;\">$'+t.exitPrice.toFixed(2)+'</td>'"+
                        "        '<td style=\"padding:8px;text-align:right;color:'+plColor+';\">'+t.profitPct.toFixed(2)+'%</td>'"+
                        "        '<td style=\"padding:8px;text-align:right;color:'+plColor+';\">$'+t.profitAmount.toFixed(2)+'</td>'"+
                        "        '<td style=\"padding:8px;text-align:center;color:#e5e7eb;\">'+t.daysHeld+'</td>'"+
                        "        '<td style=\"padding:8px;text-align:center;color:#9ca3af;font-size:11px;\">'+t.exitReason+'</td>"+
                        "        '</tr>';"+
                        "    });"+
                        "    tableHtml+='</tbody></table>';"+
                        "    document.getElementById('tradesTable').innerHTML=tableHtml;"+
                        "  }else{"+
                        "    document.getElementById('tradesTable').innerHTML='<div style=\"color:#9ca3af;padding:12px;\">No trades found</div>';"+
                        "  }"+
                        "}"+

                        "// Load all universe tickers into input field"+
                        "async function loadUniverseTickers(inputId){"+
                        "  console.log('Loading universe tickers for:', inputId);"+
                        "  try{"+
                        "    var r=await fetch('/api/universe-tickers');"+
                        "    console.log('Response status:', r.status);"+
                        "    var d=await r.json();"+
                        "    console.log('Response data:', d);"+
                        "    if(d.tickers){"+
                        "      document.getElementById(inputId).value=d.tickers;"+
                        "      alert('Loaded '+d.count+' universe tickers');"+
                        "    }else{"+
                        "      alert('No tickers found in response');"+
                        "    }"+
                        "  }catch(e){console.error('Error loading universe tickers:', e);alert('Failed to load universe tickers: '+e.message);}"+
                        "}"+

                        "// Initialize"+
                        "loadAgents();"+
                        "setDefaultDates();"+
                        "checkCacheStatus();"+
                        "</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // ---------------- Favorites (persistent simple file) ----------------
        final Object favLock = new Object();
        final Path favPath = Paths.get("favorites.txt");
        final class FavStore { java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>(); }
        final FavStore fav = new FavStore();

        // Load daily GREEN recommendations state (persisted JSON)
        try {
            Files.createDirectories(dailyGreenPath.getParent());
        } catch (Exception ignore) {}
        try {
            if (Files.exists(dailyGreenPath)) {
                DailyGreenRecommendations loaded = JSON.readValue(dailyGreenPath.toFile(), DailyGreenRecommendations.class);
                if (loaded != null) {
                    synchronized (dailyGreenLock) {
                        dailyGreenState.running = false;
                        dailyGreenState.currentTicker = loaded.currentTicker;
                        dailyGreenState.processed = loaded.processed;
                        dailyGreenState.greenCount = loaded.greenCount;
                        dailyGreenState.lastStartedNy = loaded.lastStartedNy;
                        dailyGreenState.lastFinishedNy = loaded.lastFinishedNy;
                        dailyGreenState.lastError = loaded.lastError;
                        dailyGreenState.green = (loaded.green == null) ? new ArrayList<>() : loaded.green;
                    }
                }
            }
        } catch (Exception ignore) {}

        // Load favorites at startup
        try {
            if (Files.exists(favPath)) {
                for (String line : Files.readAllLines(favPath, StandardCharsets.UTF_8)) {
                    String t = line == null ? "" : line.trim().toUpperCase();
                    if (!t.isEmpty()) {
                        fav.items.add(t);
                        synchronized (favLockStatic) { favItemsStatic.add(t); }
                    }
                }
            }
        } catch (Exception ignore) {}

        // ---------------- Favorites Page ----------------
        server.createContext("/favorites", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String syncStatus = qp.getOrDefault("syncStatus", "");
                String savedParam = qp.getOrDefault("saved", "");

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Favorites</div>");

                if (syncStatus.startsWith("added_")) {
                    String count = syncStatus.substring(6);
                    sb.append("<div style='background:#0b1220;border:1px solid #22c55e;border-radius:8px;padding:10px;margin-bottom:12px;color:#22c55e;'>")
                            .append("✅ Added ").append(escapeHtml(count)).append(" stock(s) to Monitoring. <a href='/monitoring' style='color:#93c5fd;'>View Monitoring →</a>")
                            .append("</div>");
                }
                if ("mode".equals(savedParam)) {
                    sb.append("<div style='background:#0b1220;border:1px solid #22c55e;border-radius:8px;padding:10px;margin-bottom:12px;color:#22c55e;'>✅ Strategy mode saved.</div>");
                } else if ("agent".equals(savedParam)) {
                    sb.append("<div style='background:#0b1220;border:1px solid #8b5cf6;border-radius:8px;padding:10px;margin-bottom:12px;color:#c4b5fd;'>✅ Agent config saved.</div>");
                }

                // Daily GREEN recommendations table
                try {
                    DailyGreenRecommendations snap;
                    synchronized (dailyGreenLock) {
                        snap = new DailyGreenRecommendations();
                        snap.running = dailyGreenState.running;
                        snap.currentTicker = dailyGreenState.currentTicker;
                        snap.processed = dailyGreenState.processed;
                        snap.greenCount = dailyGreenState.greenCount;
                        snap.lastStartedNy = dailyGreenState.lastStartedNy;
                        snap.lastFinishedNy = dailyGreenState.lastFinishedNy;
                        snap.lastError = dailyGreenState.lastError;
                        snap.green = new ArrayList<>(dailyGreenState.green == null ? Collections.emptyList() : dailyGreenState.green);
                    }

                    sb.append("<div style='margin-bottom:14px;padding:12px;border-radius:12px;border:1px solid #1f2a44;background:#0b1220;'>");
                    sb.append("<div style='font-weight:600;margin-bottom:10px;'>Daily Recommendations (GREEN)</div>");

                    // ---- Inline Settings Panel: Daily Recommendations ----
                    try {
                        ScoringConfig.ConfigData swingCfgData = ScoringConfig.load();
                        String favMode = swingCfgData.activeMode;
                        ScoringConfig.ModeConfig favCfg = ScoringConfig.getActiveModeConfig();
                        String favModeName = favCfg.name != null ? favCfg.name : favMode;
                        String favModeHe = favCfg.nameHe != null ? favCfg.nameHe : "";
                        String currentAgentCfg = ScoringConfig.getActiveAgentConfig();

                        sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:14px;margin-bottom:14px;'>");
                        sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:10px;'>⚙️ Settings – Daily Recommendations</div>");

                        // Strategy mode selector
                        sb.append("<form method='post' action='/favorites-settings-mode' style='margin-bottom:10px;'>");
                        sb.append("<div style='font-size:12px;color:#9ca3af;margin-bottom:6px;'>Scoring strategy used when running the daily GREEN scan:</div>");
                        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;align-items:center;'>");
                        for (String mk : new String[]{"LONG_TERM_INVESTOR", "SWING_TRADER", "CUSTOM"}) {
                            ScoringConfig.ModeConfig m = swingCfgData.presets.get(mk);
                            if (m == null) continue;
                            String mName = m.name != null ? m.name : mk;
                            boolean sel = mk.equals(favMode);
                            String bg = sel ? "#22c55e" : "#1f2a44";
                            String clr = sel ? "#000" : "#e5e7eb";
                            sb.append("<button type='submit' name='mode' value='").append(mk).append("' ");
                            sb.append("style='padding:8px 16px;border-radius:6px;background:").append(bg).append(";color:").append(clr).append(";border:none;cursor:pointer;font-size:13px;font-weight:").append(sel ? "700" : "400").append(";'>");
                            sb.append(escapeHtml(mName)).append("</button>");
                        }
                        sb.append("</div></form>");

                        // Active mode display
                        sb.append("<div style='font-size:12px;color:#9ca3af;margin-bottom:10px;'>Current: <b style='color:#22c55e;'>").append(escapeHtml(favModeName)).append("</b>");
                        if (!favModeHe.isEmpty()) sb.append(" <span style='color:#6b7280;'>| ").append(escapeHtml(favModeHe)).append("</span>");
                        sb.append("</div>");

                        // Agent config override
                        sb.append("<form method='post' action='/favorites-settings-agent-config' style='display:flex;gap:8px;flex-wrap:wrap;align-items:center;'>");
                        sb.append("<div style='font-size:12px;color:#9ca3af;width:100%;margin-bottom:4px;'>🤖 AITool agent config override <span style='color:#6b7280;'>(optional – leave blank to use strategy mode)</span>:</div>");
                        sb.append("<input type='text' name='agentId' placeholder='e.g. M2_CONSERVATIVE_V2' ");
                        sb.append("value='").append(escapeHtml(currentAgentCfg != null ? currentAgentCfg : "")).append("' ");
                        sb.append("style='padding:8px 10px;border-radius:6px;border:1px solid #1f2a44;background:#1f2a44;color:#e5e7eb;min-width:240px;font-size:13px;' />");
                        sb.append("<button type='submit' style='background:#8b5cf6;color:#fff;border:none;padding:8px 16px;border-radius:6px;font-size:13px;'>Apply</button>");
                        sb.append("<button type='submit' name='clear' value='true' style='background:#374151;color:#e5e7eb;border:none;padding:8px 14px;border-radius:6px;font-size:13px;'>Clear</button>");
                        sb.append("</form>");
                        if (currentAgentCfg != null && !currentAgentCfg.isBlank()) {
                            sb.append("<div style='margin-top:6px;font-size:12px;color:#c4b5fd;'>Active agent override: <b>").append(escapeHtml(currentAgentCfg)).append("</b></div>");
                        }
                        sb.append("</div>"); // end settings panel
                    } catch (Exception ignore) {}
                    sb.append("<div style='color:#9ca3af'>Status: ");
                    if (snap.running) {
                        sb.append("<span style='color:#22c55e;font-weight:700'>RUNNING</span>");
                        if (snap.currentTicker != null && !snap.currentTicker.isBlank()) {
                            sb.append(" · current: <b>").append(escapeHtml(snap.currentTicker)).append("</b>");
                        }
                    } else {
                        sb.append("<span style='color:#93c5fd;font-weight:700'>IDLE</span>");
                    }
                    sb.append("</div>");
                    sb.append("<div style='margin-top:10px;display:flex;gap:10px;flex-wrap:wrap;'>");
                    sb.append("<form method='post' action='/daily-green-run' style='display:inline'>");
                    sb.append("<button type='submit'>Run now</button>");
                    sb.append("</form>");
                    sb.append("<form method='post' action='/sync-green-to-monitoring' style='display:inline'>");
                    sb.append("<button type='submit' style='background:#1f2a44;'>📈 Add all GREEN to Monitoring</button>");
                    sb.append("</form>");
                    sb.append("</div>");
                    if (snap.lastStartedNy != null) sb.append("<div style='color:#9ca3af'>Last start (NY): ").append(escapeHtml(snap.lastStartedNy)).append("</div>");
                    if (snap.lastFinishedNy != null) sb.append("<div style='color:#9ca3af'>Last end (NY): ").append(escapeHtml(snap.lastFinishedNy)).append("</div>");
                    if (snap.processed != null) sb.append("<div style='color:#9ca3af'>Processed: ").append(snap.processed).append("</div>");
                    if (snap.greenCount != null) sb.append("<div style='color:#9ca3af'>GREEN count: ").append(snap.greenCount).append("</div>");
                    if (snap.lastError != null && !snap.lastError.isBlank()) {
                        sb.append("<div style='color:#fca5a5'>Last error: ").append(escapeHtml(snap.lastError)).append("</div>");
                    }
                    sb.append("</div>");

                    // Score explanation toggle - dynamic based on current scoring mode
                    ScoringConfig.ModeConfig scoreCfg = ScoringConfig.getActiveModeConfig();
                    String scoreMode = ScoringConfig.getActiveMode();
                    String scoreModeName = scoreCfg.name != null ? scoreCfg.name : scoreMode;
                    String scoreModeHe = scoreCfg.nameHe != null ? scoreCfg.nameHe : "";

                    sb.append("<div id='scoreExplanation' style='display:block;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;margin-bottom:14px;color:#e5e7eb;font-size:13px;'>");
                    sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;'>");
                    sb.append("<div style='font-weight:600;color:#93c5fd;'>📊 How is the Score Calculated? (0-100) <span style='color:#9ca3af;font-weight:400;'>| איך הציון מחושב?</span></div>");
                    sb.append("<a href='/settings' style='background:#1f2a44;padding:4px 10px;border-radius:6px;color:#93c5fd;text-decoration:none;font-size:12px;'>⚙️ Settings</a>");
                    sb.append("</div>");
                    sb.append("<div style='background:#1f2a44;padding:8px 12px;border-radius:6px;margin-bottom:10px;'>");
                    sb.append("<span style='color:#22c55e;font-weight:600;'>Current Mode: ").append(escapeHtml(scoreModeName)).append("</span>");
                    if (!scoreModeHe.isEmpty()) sb.append(" <span style='color:#9ca3af;'>| ").append(escapeHtml(scoreModeHe)).append("</span>");
                    sb.append("</div>");

                    if (scoreCfg.vetoEnabled) {
                        sb.append("<div style='margin-bottom:8px;color:#fca5a5;'><b>🛑 VETO (Automatic 0):</b> M-Score &gt; -1.78 or Z-Score &lt; 1.1 → High fraud/bankruptcy risk <span style='color:#9ca3af;'>| סיכון גבוה להונאה או פשיטת רגל</span></div>");
                    } else {
                        sb.append("<div style='margin-bottom:8px;color:#9ca3af;'><s>🛑 VETO Disabled</s> <span>| VETO מבוטל במצב זה</span></div>");
                    }

                    sb.append("<div style='margin-bottom:6px;'><b>Fundamental (").append(scoreCfg.fundamentalWeight).append("%):</b> <span style='color:#9ca3af;'>| ניתוח פונדמנטלי</span></div>");
                    sb.append("<ul style='margin:0 0 8px 20px;padding:0;'>");
                    ScoringConfig.IndicatorConfig fScoreInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("fScore") : null;
                    if (fScoreInd != null && fScoreInd.enabled) {
                        sb.append("<li>F-Score ≥ ").append((int)fScoreInd.threshold).append(" → +").append(fScoreInd.points).append(" pts <span style='color:#9ca3af;'>| בריאות פיננסית חזקה</span></li>");
                    }
                    ScoringConfig.IndicatorConfig pegInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("peg") : null;
                    if (pegInd != null && pegInd.enabled) {
                        sb.append("<li>PEG &lt; ").append(pegInd.threshold).append(" → +").append(pegInd.points).append(" pts <span style='color:#9ca3af;'>| מחיר הוגן ביחס לצמיחה</span></li>");
                    }
                    ScoringConfig.IndicatorConfig dcfInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("dcfMargin") : null;
                    if (dcfInd != null && dcfInd.enabled) {
                        sb.append("<li>DCF Margin &gt; ").append((int)(dcfInd.thresholdHigh*100)).append("% → +").append(dcfInd.points).append(" pts <span style='color:#9ca3af;'>| מרווח ביטחון לפי תזרים מזומנים</span></li>");
                    }
                    sb.append("</ul>");

                    sb.append("<div style='margin-bottom:6px;'><b>Technical (").append(scoreCfg.technicalWeight).append("%):</b> <span style='color:#9ca3af;'>| ניתוח טכני</span></div>");
                    sb.append("<ul style='margin:0 0 8px 20px;padding:0;'>");
                    ScoringConfig.IndicatorConfig bullishInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("technicalBullish") : null;
                    if (bullishInd != null && bullishInd.enabled) {
                        sb.append("<li>Technical Bullish → +").append(bullishInd.points).append(" pts <span style='color:#9ca3af;'>| מגמה עולה</span></li>");
                    }
                    ScoringConfig.IndicatorConfig rsiInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("rsiHealthy") : null;
                    if (rsiInd != null && rsiInd.enabled) {
                        sb.append("<li>RSI ").append((int)rsiInd.min).append("-").append((int)rsiInd.max).append(" → +").append(rsiInd.points).append(" pts <span style='color:#9ca3af;'>| מומנטום בריא</span></li>");
                    }
                    ScoringConfig.IndicatorConfig rsiOversoldInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("rsiOversold") : null;
                    if (rsiOversoldInd != null && rsiOversoldInd.enabled) {
                        sb.append("<li>RSI Oversold &lt; ").append((int)rsiOversoldInd.threshold).append(" → +").append(rsiOversoldInd.points).append(" pts <span style='color:#9ca3af;'>| הזדמנות קנייה טכנית</span></li>");
                    }
                    sb.append("</ul>");

                    sb.append("<div style='margin-bottom:6px;'><b>Efficiency (").append(scoreCfg.efficiencyWeight).append("%):</b> <span style='color:#9ca3af;'>| יעילות תפעולית</span></div>");
                    sb.append("<ul style='margin:0 0 8px 20px;padding:0;'>");
                    ScoringConfig.IndicatorConfig roicInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("roicWacc") : null;
                    if (roicInd != null && roicInd.enabled) {
                        sb.append("<li>ROIC-WACC Spread &gt; ").append((int)(roicInd.threshold*100)).append("% → +").append(roicInd.points).append(" pts <span style='color:#9ca3af;'>| החברה מייצרת ערך מעל עלות ההון</span></li>");
                    }
                    ScoringConfig.IndicatorConfig cccInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("ccc") : null;
                    if (cccInd != null && cccInd.enabled) {
                        sb.append("<li>CCC &lt; ").append((int)cccInd.threshold).append(" days → +").append(cccInd.points).append(" pts <span style='color:#9ca3af;'>| מחזור מזומנים מהיר</span></li>");
                    }
                    sb.append("</ul>");

                    if (scoreCfg.grahamBonus > 0) {
                        sb.append("<div style='margin-bottom:6px;'><b>Graham Valuation (bonus up to ").append(scoreCfg.grahamBonus).append(" pts):</b> <span style='color:#9ca3af;'>| הערכת שווי לפי גראהם</span></div>");
                        sb.append("<ul style='margin:0 0 8px 20px;padding:0;'>");
                        ScoringConfig.IndicatorConfig grahamInd = scoreCfg.indicators != null ? scoreCfg.indicators.get("grahamMoS") : null;
                        if (grahamInd != null && grahamInd.enabled) {
                            sb.append("<li>Margin of Safety ≥ ").append((int)(grahamInd.thresholdHigh*100)).append("% → +").append(grahamInd.pointsHigh).append(" pts <span style='color:#9ca3af;'>| מרווח ביטחון גבוה</span></li>");
                            sb.append("<li>Margin of Safety ≥ ").append((int)(grahamInd.thresholdLow*100)).append("% → +").append(grahamInd.pointsLow).append(" pts <span style='color:#9ca3af;'>| מרווח ביטחון סביר</span></li>");
                        }
                        sb.append("</ul>");
                    }

                    sb.append("<div style='margin-bottom:6px;'><b>Recommendation:</b> ≥80 = 🚀 STRONG BUY | ≥60 = 🟢 BUY | ≥40 = 🟡 HOLD | &lt;40 = 🔴 SELL/AVOID <span style='color:#9ca3af;'>| קנייה חזקה / קנייה / החזק / מכור</span></div>");
                    sb.append("</div>");

                    sb.append("<div style='overflow-x:auto;margin-bottom:14px;'>");
                    sb.append("<table style='width:100%;border-collapse:collapse;'>");
                    sb.append("<thead><tr>");
                    sb.append("<th style='text-align:left;padding:8px;border-bottom:1px solid #1f2a44;'>Ticker</th>");
                    sb.append("<th style='text-align:left;padding:8px;border-bottom:1px solid #1f2a44;'>Score</th>");
                    sb.append("<th style='text-align:left;padding:8px;border-bottom:1px solid #1f2a44;'>Rec</th>");
                    sb.append("<th style='text-align:right;padding:8px;border-bottom:1px solid #1f2a44;color:#3b82f6;'>Entry $</th>");
                    sb.append("<th style='text-align:right;padding:8px;border-bottom:1px solid #1f2a44;color:#ef4444;'>Stop $</th>");
                    sb.append("<th style='text-align:right;padding:8px;border-bottom:1px solid #1f2a44;color:#22c55e;'>Target $</th>");
                    sb.append("<th style='text-align:left;padding:8px;border-bottom:1px solid #1f2a44;'>Action</th>");
                    sb.append("</tr></thead><tbody>");
                    if (snap.green == null || snap.green.isEmpty()) {
                        sb.append("<tr><td colspan='7' style='padding:10px;color:#9ca3af'>No GREEN recommendations yet (will populate after daily scan).</td></tr>");
                    } else {
                        for (DailyGreenTicker t : snap.green) {
                            if (t == null || t.ticker == null) continue;
                            String esc = escapeHtml(t.ticker);
                            sb.append("<tr id='row-").append(esc).append("'>");
                            sb.append("<td style='padding:8px;border-bottom:1px solid #0f172a;'><span style='color:#22c55e;font-weight:700;'>").append(esc).append("</span></td>");
                            sb.append("<td style='padding:8px;border-bottom:1px solid #0f172a;'>").append(t.finalScore == null ? "" : escapeHtml(String.valueOf(t.finalScore))).append("</td>");
                            sb.append("<td style='padding:8px;border-bottom:1px solid #0f172a;'>").append(t.recommendation == null ? "" : escapeHtml(t.recommendation)).append("</td>");
                            sb.append("<td id='entry-").append(esc).append("' style='padding:8px;border-bottom:1px solid #0f172a;text-align:right;color:#3b82f6;'>-</td>");
                            sb.append("<td id='stop-").append(esc).append("' style='padding:8px;border-bottom:1px solid #0f172a;text-align:right;color:#ef4444;'>-</td>");
                            sb.append("<td id='target-").append(esc).append("' style='padding:8px;border-bottom:1px solid #0f172a;text-align:right;color:#22c55e;'>-</td>");
                            sb.append("<td style='padding:8px;border-bottom:1px solid #0f172a;'>");
                            sb.append("<form method='post' action='/run-main' target='_blank' style='display:inline'>")
                                    .append("<input type='hidden' name='symbol' value='").append(esc).append("'/>")
                                    .append("<button type='submit'>Analyze</button></form> ");
                            sb.append("<a href='/monitoring#snap-details-").append(esc).append("' target='_blank' style='display:inline-block;padding:6px 12px;background:#1f2a44;border-radius:6px;color:#93c5fd;text-decoration:none;font-size:13px;'>📈</a>");
                            sb.append("</td>");
                            sb.append("</tr>");
                        }
                    }
                    sb.append("</tbody></table></div>");

                    // JavaScript to fetch ATR and calculate Entry/Stop/Target for each GREEN ticker
                    ScoringConfig.EntryFiltersConfig entryFilters = ScoringConfig.getActiveEntryFilters();
                    sb.append("<script>");
                    sb.append("var greenTickers = [");
                    boolean first = true;
                    for (DailyGreenTicker gt : snap.green) {
                        if (gt != null && gt.ticker != null) {
                            if (!first) sb.append(",");
                            sb.append("'").append(escapeHtml(gt.ticker)).append("'");
                            first = false;
                        }
                    }
                    sb.append("];");
                    sb.append("var atrMultiplier = ").append(entryFilters.atrStopLossMultiplier).append(";");
                    sb.append("var rrRatio = ").append(entryFilters.riskRewardRatio).append(";");
                    sb.append("async function loadTradingData() {");
                    sb.append("  for (var i = 0; i < greenTickers.length; i++) {");
                    sb.append("    var ticker = greenTickers[i];");
                    sb.append("    try {");
                    sb.append("      var r = await fetch('/api/fetch-atr?symbol=' + encodeURIComponent(ticker));");
                    sb.append("      var d = await r.json();");
                    sb.append("      if (d.error) continue;");
                    sb.append("      var entry = d.currentPrice;");
                    sb.append("      var stopDist = d.atr * atrMultiplier;");
                    sb.append("      var stop = entry - stopDist;");
                    sb.append("      var target = entry + (stopDist * rrRatio);");
                    sb.append("      var entryEl = document.getElementById('entry-' + ticker);");
                    sb.append("      var stopEl = document.getElementById('stop-' + ticker);");
                    sb.append("      var targetEl = document.getElementById('target-' + ticker);");
                    sb.append("      if (entryEl) entryEl.innerHTML = '$' + entry.toFixed(2);");
                    sb.append("      if (stopEl) stopEl.innerHTML = '$' + stop.toFixed(2);");
                    sb.append("      if (targetEl) targetEl.innerHTML = '$' + target.toFixed(2);");
                    sb.append("    } catch(e) {}");
                    sb.append("  }");
                    sb.append("}");
                    sb.append("loadTradingData();");
                    sb.append("</script>");

                    if (snap.running) {
                        sb.append("<script>setTimeout(function(){location.reload();},15000);</script>");
                    }
                } catch (Exception ignore) {}

                synchronized (favLock) {
                    if (fav.items.isEmpty()) {
                        sb.append("<div style='color:#9ca3af'>No favorites saved yet.</div>");
                    } else {
                        sb.append("<div style='display:flex;flex-direction:column;gap:10px'>");
                        for (String t : fav.items) {
                            String esc = escapeHtml(t);
                            sb.append("<div style='display:flex;align-items:center;gap:10px'>")
                                    .append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>★ ")
                                    .append(esc).append("</span>")
                                    .append("<form method='post' action='/run-main' target='_blank' style='display:inline'>")
                                    .append("<input type='hidden' name='symbol' value='"+esc+"'/>")
                                    .append("<button type='submit'>Analyze</button></form>")
                                    .append("<form method='post' action='/favorite-remove' style='display:inline'>")
                                    .append("<input type='hidden' name='symbol' value='"+esc+"'/>")
                                    .append("<button type='submit'>Remove</button></form>")
                                    .append("</div>");
                        }
                        sb.append("</div>");
                    }
                }
                // Add manual add form
                sb.append("<form method='post' action='/favorite-add' style='margin-top:12px'>"+
                        "<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required/> "+
                        "<button type='submit'>Add to Favorites</button></form>");
                sb.append("</div>");

                // Swing Trading Simulator Section
                sb.append("<div class='card' style='border:2px solid #f59e0b;'>");
                sb.append("<div class='title'>🎯 Swing Trading Simulator - סימולציית סווינג</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;' dir='rtl'>סורק מניות מועדפות לאיתור הזדמנויות סווינג - נסיגה איכותית, פריצה, ומעקב מגמה. מבוסס על swing-variants.json</div>");

                // ---- Active Config Panel: Swing Simulator (swing-variants.json only) ----
                try {
                    DailyTradingSimulator.SwingVariantsConfig swingConfig = DailyTradingSimulator.SwingVariantsConfig.load();

                    sb.append("<div style='background:#0d1b30;border:1px solid #7c4e00;border-radius:10px;padding:14px;margin-bottom:14px;'>");
                    sb.append("<div style='font-weight:600;color:#fcd34d;margin-bottom:4px;'>📋 Active Swing Variants</div>");
                    sb.append("<div style='font-size:11px;color:#6b7280;margin-bottom:10px;'>Loaded from <code>swing-variants.json</code> — each variant has its own independent entry filters and risk management.</div>");

                    if (swingConfig.enabled && swingConfig.variants != null && !swingConfig.variants.isEmpty()) {
                        sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:8px;'>");
                        for (DailyTradingSimulator.SwingVariantConfig v : swingConfig.variants) {
                            sb.append("<div style='background:#1f2a44;padding:10px;border-radius:8px;'>");
                            sb.append("<div style='font-weight:600;color:#fcd34d;font-size:13px;'>").append(escapeHtml(v.name)).append("</div>");
                            if (v.nameHe != null) sb.append("<div style='font-size:11px;color:#9ca3af;margin-bottom:4px;'>").append(escapeHtml(v.nameHe)).append("</div>");
                            sb.append("<div style='font-size:11px;color:#6b7280;line-height:1.6;'>");
                            sb.append("RSI: ").append((int)v.entryFilters.rsiMin).append("–").append((int)v.entryFilters.rsiMax).append("<br/>");
                            sb.append("RS ≥ ").append(String.format("%.2f", v.entryFilters.rsMin)).append("<br/>");
                            sb.append("Stop: ").append(String.format("%.0f%%", v.riskManagement.stopLossPct));
                            sb.append(" | Target: ").append(String.format("%.0f%%", v.riskManagement.takeProfitPct));
                            sb.append("</div>");
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    } else {
                        sb.append("<div style='color:#9ca3af;font-size:12px;'>No variants loaded (check swing-variants.json).</div>");
                    }
                    sb.append("</div>"); // end swing config panel
                } catch (Exception ignore) {}
                
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;align-items:center;'>");
                sb.append("<button onclick='runSwingScanner()' id='swingBtn' style='background:#f59e0b;'>🔍 Scan Stocks for Swing</button>");
                sb.append("<button onclick='clearSwingResults()' id='clearSwingBtn' style='background:#ef4444;display:none;'>🗑️ Clear Results</button>");
                sb.append("</div>");
                sb.append("<div id='swingStatus' style='color:#9ca3af;font-size:13px;margin-bottom:12px;'></div>");
                sb.append("<div id='swingResults' style='display:none;'></div>");
                
                // Swing Scanner JavaScript
                sb.append("<script>");
                sb.append("var swingPollInterval = null;");
                // Render results function (shared by scan and load)
                sb.append("function renderSwingResults(d) {");
                sb.append("  var status = document.getElementById('swingStatus');");
                sb.append("  var results = document.getElementById('swingResults');");
                sb.append("  var clearBtn = document.getElementById('clearSwingBtn');");
                sb.append("  results.style.display = 'block';");
                sb.append("  if (d.error) { results.innerHTML = '<div style=\"color:#ef4444;\">' + d.error + '</div>'; status.textContent = ''; clearBtn.style.display = 'none'; return; }");
                sb.append("  var scanTime = d.scanTime ? ' (סריקה: ' + d.scanTime + ')' : '';");
                sb.append("  status.textContent = 'נסרקו ' + (d.scanned || 0) + ' מניות, נמצאו ' + (d.candidates ? d.candidates.length : 0) + ' מועמדים' + scanTime;");
                sb.append("  var html = '';");
                sb.append("  if (!d.candidates || d.candidates.length === 0) {");
                sb.append("    html = '<div style=\"color:#9ca3af;text-align:center;padding:20px;\">לא נמצאו מועמדים לסווינג כרגע</div>';");
                sb.append("    clearBtn.style.display = 'none';");
                sb.append("  } else {");
                sb.append("    clearBtn.style.display = 'inline-block';");
                sb.append("    html = '<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">';");
                sb.append("    html += '<thead><tr style=\"background:#0b1220;\">';");
                sb.append("    html += '<th style=\"padding:8px;text-align:left;\">Symbol</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:center;\">Variant</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">Score</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">RSI</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">RS</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">RVOL</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">Entry</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">Stop</th>';");
                sb.append("    html += '<th style=\"padding:8px;text-align:right;\">Target</th>';");
                sb.append("    html += '</tr></thead><tbody>';");
                sb.append("    d.candidates.forEach(function(c) {");
                sb.append("      var rsiColor = c.rsi <= 40 ? '#22c55e' : (c.rsi <= 55 ? '#fbbf24' : '#9ca3af');");
                sb.append("      var rsColor = c.rs >= 1.1 ? '#22c55e' : (c.rs >= 1.0 ? '#fbbf24' : '#ef4444');");
                sb.append("      html += '<tr style=\"border-bottom:1px solid #1f2a44;\">';");
                sb.append("      html += '<td style=\"padding:8px;font-weight:600;\">' + c.ticker + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:center;color:#f59e0b;\">' + (c.variant || '-') + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;font-weight:600;color:#22c55e;\">' + c.score + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;color:' + rsiColor + ';\">' + c.rsi.toFixed(0) + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;color:' + rsColor + ';\">' + c.rs.toFixed(2) + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;\">' + c.rvol.toFixed(1) + 'x</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;\">$' + c.entry.toFixed(2) + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;color:#ef4444;\">$' + c.stop.toFixed(2) + '</td>';");
                sb.append("      html += '<td style=\"padding:8px;text-align:right;color:#22c55e;\">$' + c.target.toFixed(2) + '</td>';");
                sb.append("      html += '</tr>';");
                sb.append("    });");
                sb.append("    html += '</tbody></table>';");
                sb.append("  }");
                sb.append("  results.innerHTML = html;");
                sb.append("}");
                // Poll for scan status
                sb.append("async function pollSwingScanStatus() {");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/swing-scan/status');");
                sb.append("    var s = await r.json();");
                sb.append("    var btn = document.getElementById('swingBtn');");
                sb.append("    var status = document.getElementById('swingStatus');");
                sb.append("    if (s.running) {");
                sb.append("      btn.disabled = true; btn.textContent = '⏳ סורק...';");
                sb.append("      status.textContent = 'סורק ' + s.progress + '/' + s.total + ' מניות... (' + s.currentTicker + ')';");
                sb.append("    } else {");
                sb.append("      btn.disabled = false; btn.textContent = '🔍 Scan Stocks for Swing';");
                sb.append("      if (swingPollInterval) { clearInterval(swingPollInterval); swingPollInterval = null; }");
                sb.append("      loadSavedSwingResults();");
                sb.append("    }");
                sb.append("  } catch(e) { console.log('Poll error:', e); }");
                sb.append("}");
                // Start scanner (background)
                sb.append("async function runSwingScanner() {");
                sb.append("  var btn = document.getElementById('swingBtn');");
                sb.append("  var status = document.getElementById('swingStatus');");
                sb.append("  btn.disabled = true; btn.textContent = '⏳ מתחיל...';");
                sb.append("  status.textContent = 'מתחיל סריקה...';");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/swing-scan/start', {method:'POST'});");
                sb.append("    var d = await r.json();");
                sb.append("    if (d.status === 'started' || d.status === 'already_running') {");
                sb.append("      btn.textContent = '⏳ סורק...';");
                sb.append("      status.textContent = 'סורק 0/' + d.total + ' מניות...';");
                sb.append("      if (!swingPollInterval) { swingPollInterval = setInterval(pollSwingScanStatus, 2000); }");
                sb.append("    }");
                sb.append("  } catch(e) { status.textContent = 'שגיאה: ' + e.message; btn.disabled = false; btn.textContent = '🔍 Scan Stocks for Swing'; }");
                sb.append("}");
                // Load saved results on page load
                sb.append("async function loadSavedSwingResults() {");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/swing-scan/results');");
                sb.append("    var d = await r.json();");
                sb.append("    if (d.candidates && d.candidates.length > 0) { renderSwingResults(d); }");
                sb.append("  } catch(e) { console.log('No saved swing results'); }");
                sb.append("}");
                // Clear results function
                sb.append("async function clearSwingResults() {");
                sb.append("  if (!confirm('האם למחוק את תוצאות הסריקה?')) return;");
                sb.append("  try {");
                sb.append("    await fetch('/api/swing-scan/clear', {method:'POST'});");
                sb.append("    document.getElementById('swingResults').style.display = 'none';");
                sb.append("    document.getElementById('swingResults').innerHTML = '';");
                sb.append("    document.getElementById('swingStatus').textContent = '';");
                sb.append("    document.getElementById('clearSwingBtn').style.display = 'none';");
                sb.append("  } catch(e) { alert('שגיאה במחיקה: ' + e.message); }");
                sb.append("}");
                // Check status on page load (in case scan is running)
                sb.append("async function initSwingScanner() {");
                sb.append("  var r = await fetch('/api/swing-scan/status');");
                sb.append("  var s = await r.json();");
                sb.append("  if (s.running) {");
                sb.append("    document.getElementById('swingBtn').disabled = true;");
                sb.append("    document.getElementById('swingBtn').textContent = '⏳ סורק...';");
                sb.append("    document.getElementById('swingStatus').textContent = 'סורק ' + s.progress + '/' + s.total + ' מניות... (' + s.currentTicker + ')';");
                sb.append("    swingPollInterval = setInterval(pollSwingScanStatus, 2000);");
                sb.append("  } else {");
                sb.append("    loadSavedSwingResults();");
                sb.append("  }");
                sb.append("}");
                sb.append("initSwingScanner();");
                sb.append("</script>");
                sb.append("</div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/favorite-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean added = false;
                if (!t.isEmpty() && t.matches("[A-Z0-9.:-]{1,10}")) {
                    synchronized (favLock) { added = fav.items.add(t); try { Files.write(favPath, (String.join("\n", fav.items)+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {} }
                    if (added) { synchronized (favLockStatic) { favItemsStatic.add(t); } }
                }
                ex.getResponseHeaders().add("Location", "/favorites?status="+(added?"added":"invalid_or_exists"));
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        server.createContext("/favorite-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean removed = false;
                synchronized (favLock) { removed = fav.items.remove(t); try { Files.write(favPath, (String.join("\n", fav.items)+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {} }
                if (removed) { synchronized (favLockStatic) { favItemsStatic.remove(t); } }
                ex.getResponseHeaders().add("Location", "/favorites?status="+(removed?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // ---------------- AITool Page - Multi-Agent Strategy Testing ----------------
        server.createContext("/aitool", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                // Initialize AITool if needed
                try { AIToolAgent.initialize(); } catch (Exception ignore) {}

                AIToolAgent.AgentSystemState state = AIToolAgent.getSystemState();
                List<AIToolAgent.AgentPerformance> performances = AIToolAgent.getAllPerformances();

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>🤖 AITool - Multi-Agent Strategy Testing</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;'>Testing multiple trading strategies (agents) to find the best formula for profit.</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>בדיקת אסטרטגיות מסחר מרובות (סוכנים) כדי למצוא את הנוסחה הטובה ביותר לרווח.</div>");

                // Status panel
                boolean isRunningNow = AIToolAgent.isRunning();
                int initProgress = AIToolAgent.getRunProgress();
                int initTotal    = AIToolAgent.getRunTotal();
                String initAgent  = AIToolAgent.getCurrentAgent();
                String initTicker = AIToolAgent.getRunCurrentTicker();
                double initPct = (initTotal > 0) ? (initProgress * 100.0 / initTotal) : 0;

                sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:12px;padding:14px;margin-bottom:16px;' id='runStatusPanel'>");
                sb.append("<div style='display:flex;gap:20px;flex-wrap:wrap;align-items:center;'>");
                sb.append("<div><span style='color:#9ca3af;'>Status:</span> <span id='runStatusLabel' style='font-weight:700;color:").append(isRunningNow ? "#22c55e" : "#93c5fd").append(";'>").append(isRunningNow ? "RUNNING" : "IDLE").append("</span></div>");
                sb.append("<div><span style='color:#9ca3af;'>Agents:</span> <b>").append(AIToolAgent.getAgentCount()).append("</b></div>");
                if (state != null && state.lastRunTime != null) {
                    sb.append("<div><span style='color:#9ca3af;'>Last Run:</span> ").append(escapeHtml(state.lastRunTime.substring(0, Math.min(19, state.lastRunTime.length())))).append("</div>");
                }
                sb.append("<div><span style='color:#9ca3af;'>Run Count:</span> ").append(state != null ? state.runCount : 0).append("</div>");
                sb.append("</div>");

                // Progress bar (hidden when idle)
                sb.append("<div id='runProgressSection' style='margin-top:12px;display:").append(isRunningNow ? "block" : "none").append(";'>");
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;'>");
                sb.append("<span style='font-size:12px;color:#9ca3af;'><span id='runPhaseLabel'>Tickers scanned</span>: <span id='runProgressText'>").append(initProgress).append(" / ").append(initTotal).append("</span></span>");
                sb.append("<span style='font-size:12px;color:#22c55e;font-weight:600;'><span id='runPct'>").append(String.format("%.0f", initPct)).append("</span>%</span>");
                sb.append("</div>");
                sb.append("<div style='background:#1f2a44;border-radius:6px;height:10px;overflow:hidden;'>");
                sb.append("<div id='runProgressBar' style='background:linear-gradient(90deg,#22c55e,#16a34a);height:100%;width:").append(String.format("%.1f", initPct)).append("%;transition:width 0.4s ease;'></div>");
                sb.append("</div>");
                sb.append("<div style='margin-top:6px;font-size:11px;color:#6b7280;'>");
                sb.append("Ticker: <span id='runCurrentTicker' style='color:#fcd34d;'>").append(escapeHtml(initTicker != null ? initTicker : "—")).append("</span>");
                sb.append(" &nbsp;·&nbsp; Agent: <span id='runCurrentAgent' style='color:#93c5fd;'>").append(escapeHtml(initAgent != null ? initAgent : "—")).append("</span>");
                sb.append("</div>");
                sb.append("</div>");

                sb.append("<div style='margin-top:12px;display:flex;gap:10px;flex-wrap:wrap;'>");
                sb.append("<form method='post' action='/aitool-run-swing' style='margin:0;'><button type='submit' id='runSwingBtn' style='background:#0d9488;border-color:#14b8a6;'>🏏 Run Swing System</button></form>");
                sb.append("<form method='post' action='/aitool-run-intraday' style='margin:0;'><button type='submit' id='runIntradayBtn' style='background:#7c3aed;border-color:#8b5cf6;'>⚡ Run Intraday System</button></form>");
                sb.append("<form method='post' action='/aitool-run' style='margin:0;'><button type='submit' id='runBtn'>▶️ Run All Agents</button></form>");
                sb.append("<form method='post' action='/aitool-monitor' style='margin:0;'><button type='submit' id='monitorBtn' style='background:#0e7490;border-color:#0891b2;'>📡 Monitor Open Positions Now</button></form>");
                sb.append("<a href='/aitool' style='padding:10px 14px;background:#1f2a44;border-radius:8px;'>🔄 Refresh</a>");
                sb.append("</div>");

                // Market Regime Filter Banner — always visible
                AIToolAgent.RegimeLevel regime    = AIToolAgent.getLastKnownRegime();
                String regimeDetail               = AIToolAgent.getLastRegimeDetail();
                String regimeCheckTime            = AIToolAgent.getLastRegimeCheckTime();
                int    signalCount                = AIToolAgent.getLastScanSignalCount();
                String checkedStr = (regimeCheckTime != null)
                    ? " &nbsp;·&nbsp; checked " + escapeHtml(regimeCheckTime)
                    : " &nbsp;·&nbsp; <i>pending first check (runs at next scan or in ≤30 min)</i>";
                if (regime == AIToolAgent.RegimeLevel.VERY_WEAK) {
                    sb.append("<div style='margin-top:14px;background:#1a0a0a;border:2px solid #ef4444;border-radius:10px;padding:14px 16px;'>");
                    sb.append("<div style='font-size:15px;font-weight:700;color:#ef4444;margin-bottom:6px;'>⛔ Market Regime Filter Active — Capital Protection Mode</div>");
                    sb.append("<div style='color:#fca5a5;font-size:13px;'>SPY is below its 20-day MA or down &gt;2% today.");
                    if (signalCount > 0) {
                        sb.append(" <b>").append(signalCount).append(" signal(s)</b> found but <b>no trades executed</b> to protect capital.");
                    }
                    sb.append("</div>");
                    sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:6px;'>").append(escapeHtml(regimeDetail)).append(checkedStr).append("</div>");
                    sb.append("</div>");
                } else if (regime == AIToolAgent.RegimeLevel.WEAK) {
                    sb.append("<div style='margin-top:14px;background:#1a1200;border:2px solid #f59e0b;border-radius:10px;padding:14px 16px;'>");
                    sb.append("<div style='font-size:15px;font-weight:700;color:#f59e0b;margin-bottom:6px;'>⚠️ Market Regime: Weak — Reduced Position Size (50%)</div>");
                    sb.append("<div style='color:#fde68a;font-size:13px;'>SPY shows mixed signals. Trades are executing at half size.</div>");
                    sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:6px;'>").append(escapeHtml(regimeDetail)).append(checkedStr).append("</div>");
                    sb.append("</div>");
                } else {
                    sb.append("<div style='margin-top:14px;background:#0a1a0a;border:1px solid #22c55e;border-radius:10px;padding:10px 14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>");
                    sb.append("<span style='color:#22c55e;font-weight:700;'>✅ Market Regime: Healthy — Full position size</span>");
                    sb.append("<span style='color:#9ca3af;font-size:12px;'>").append(escapeHtml(regimeDetail)).append(checkedStr).append("</span>");
                    sb.append("</div>");
                }

                // JS poller — polls /aitool-run-status every 3s while running
                sb.append("<script>");
                sb.append("(function(){");
                sb.append("var polling=false;");
                sb.append("function updateBar(d){");
                sb.append("  var total=d.total||0, prog=d.progress||0;");
                sb.append("  var pct=total>0?Math.round(prog*100/total):0;");
                sb.append("  document.getElementById('runProgressText').textContent=prog+' / '+total;");
                sb.append("  document.getElementById('runPhaseLabel').textContent=d.phase||'Tickers scanned';");
                sb.append("  document.getElementById('runPct').textContent=pct;");
                sb.append("  document.getElementById('runProgressBar').style.width=pct+'%';");
                sb.append("  document.getElementById('runCurrentAgent').textContent=d.currentAgent||'—';");
                sb.append("  document.getElementById('runCurrentTicker').textContent=d.currentTicker||'—';");
                sb.append("  document.getElementById('runStatusLabel').textContent=d.running?'RUNNING':'IDLE';");
                sb.append("  document.getElementById('runStatusLabel').style.color=d.running?'#22c55e':'#93c5fd';");
                sb.append("  document.getElementById('runProgressSection').style.display=d.running?'block':'none';");
                sb.append("  document.getElementById('runBtn').disabled=d.running;");
                sb.append("}");
                sb.append("function poll(){");
                sb.append("  fetch('/aitool-run-status').then(function(r){return r.json();}).then(function(d){");
                sb.append("    updateBar(d);");
                sb.append("    if(d.running){setTimeout(poll,3000);}else{polling=false;}");
                sb.append("  }).catch(function(){if(polling)setTimeout(poll,5000);});");
                sb.append("}");
                // Auto-start poller if already running on page load, or when run button clicked
                if (isRunningNow) {
                    sb.append("polling=true;poll();");
                }
                sb.append("document.getElementById('runBtn').closest('form').addEventListener('submit',function(){");
                sb.append("  if(!polling){polling=true;setTimeout(poll,2000);}");
                sb.append("});");
                sb.append("})();");
                sb.append("</script>");
                
                // Top Agents Full Scan Section
                sb.append("<div style='margin-top:16px;background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:12px;'>");
                int sectorTickerCount = LongTermCandidateFinder.getAllSectorTickers().size();
                sb.append("<div style='font-weight:600;color:#a78bfa;margin-bottom:8px;'>🚀 Start Full Scan with Selected Agents (").append(sectorTickerCount).append(" tickers across 11 sectors)</div>");
                sb.append("<div style='font-size:11px;color:#6b7280;margin-bottom:8px;'>Technology · Financials · Healthcare · Energy · Industrials · Consumer Disc. · Consumer Staples · Utilities · Materials · Real Estate · Communication Services</div>");
                
                // Split System Architecture - Position Counts
                sb.append("<div style='margin-top:12px;padding:10px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;'>");
                sb.append("<div style='font-size:12px;color:#9ca3af;margin-bottom:6px;'>📊 Split System Position Tracking:</div>");
                sb.append("<div style='display:flex;gap:20px;flex-wrap:wrap;'>");
                sb.append("<div><span style='color:#14b8a6;font-weight:600;'>🏏 Swing System:</span> <span style='color:#e5e7eb;'>8 slots, 2 per sector</span></div>");
                sb.append("<div><span style='color:#8b5cf6;font-weight:600;'>⚡ Intraday System:</span> <span style='color:#e5e7eb;'>5 slots, 1 per sector</span></div>");
                sb.append("</div>");
                sb.append("</div>");
                
                // Pinned master strategies always shown at the bottom (only active trading masters)
                java.util.Set<String> PINNED_MASTERS = new java.util.LinkedHashSet<>(java.util.Arrays.asList("MASTER_5_PULLBACK_MA20","MASTER_6_VOLUME_BREAKOUT","MASTER_8_STRONG_TREND"));
                // Filtered agents (MASTER_7_VIX_MARKET_FILTER only) — these get pre-checked (same as scheduled runs)
                java.util.Set<String> filteredAgentIds = new java.util.HashSet<>();
                for (String agentId : AIToolAgent.getFilteredAgentsForScheduledRun()) filteredAgentIds.add(agentId);

                sb.append("<div style='color:#c4b5fd;font-size:12px;margin-bottom:8px;'>Select agents to run (✅ = MASTER_7_VIX_MARKET_FILTER pre-checked, others unchecked):</div>");
                sb.append("<form id='fullScanForm' onsubmit='submitFullScan(event)' style='margin:0;'>");

                int agentIdx = 0;
                // 1. Show ALL loaded variant agents (sorted: filtered agents first, then alphabetical)
                List<AIToolAgent.AgentPerformance> allAgentPerfs = AIToolAgent.getAllPerformances();
                allAgentPerfs.sort((a, b) -> {
                    boolean aFiltered = filteredAgentIds.contains(a.agentId);
                    boolean bFiltered = filteredAgentIds.contains(b.agentId);
                    if (aFiltered != bFiltered) return aFiltered ? -1 : 1;
                    return a.agentId.compareTo(b.agentId);
                });
                
                // Group agents by system type
                sb.append("<div style='color:#14b8a6;font-size:11px;margin-bottom:4px;'>🏏 Swing System Agents (2-5 day holds):</div>");
                for (AIToolAgent.AgentPerformance p : allAgentPerfs) {
                    if (PINNED_MASTERS.contains(p.agentId)) continue;
                    AIToolAgent.AgentConfig pCfg = AIToolAgent.getAgentConfig(p.agentId);
                    if (pCfg == null || pCfg.disabled || "MOMENTUM".equals(pCfg.type) || pCfg.masterStrategy) continue;
                    if (!"SWING_SYSTEM".equals(pCfg.systemType)) continue; // Only swing agents
                    boolean isFilteredAgent = filteredAgentIds.contains(p.agentId);
                    String bgColor = isFilteredAgent ? "#1a2e1a" : (agentIdx % 2 == 0 ? "#0d3d2d" : "#0a2d20");
                    String border = isFilteredAgent ? "border:1px solid #22c55e;" : "border:1px solid transparent;";
                    sb.append("<div style='display:flex;align-items:center;gap:4px;padding:4px 6px;background:").append(bgColor).append(";").append(border).append("border-radius:4px;margin-bottom:2px;'>");
                    sb.append("<input type='checkbox' name='agent").append(agentIdx).append("' value='").append(escapeHtml(p.agentId)).append("' style='width:14px;height:14px;'");
                    if (isFilteredAgent) sb.append(" checked");
                    sb.append(" />");
                    sb.append("<span style='color:#e5e7eb;font-weight:500;font-size:12px;'>");
                    if (isFilteredAgent) sb.append("✅ ");
                    sb.append(escapeHtml(p.agentId)).append("</span>");
                    sb.append("<span style='color:#14b8a6;font-size:10px;'>SWING</span>");
                    if (p.totalTrades > 0) {
                        double totalPL = 0;
                        List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(p.agentId);
                        if (agentTrades != null) {
                            for (AIToolAgent.Trade t : agentTrades) {
                                if (t.exitPrice > 0 && t.entryPrice > 0) {
                                    int shares = (int) (1000.0 / t.entryPrice);
                                    double plPerShare = (t.exitPrice - t.entryPrice);
                                    totalPL += plPerShare * shares;
                                }
                            }
                        }
                        String netPLColor = totalPL >= 0 ? "#22c55e" : "#ef4444";
                        sb.append("<span style='color:").append(netPLColor).append(";font-weight:600;font-size:11px;'>$").append(String.format("%.0f", totalPL)).append("</span>");
                        sb.append("<span style='color:#9ca3af;font-size:10px;'>").append(p.wins).append("/").append(p.totalTrades).append(" trades</span>");
                    } else {
                        long openCnt = p.recentTrades != null ? p.recentTrades.stream().filter(t -> "OPEN".equals(t.status)).count() : 0;
                        if (openCnt > 0) {
                            sb.append("<span style='color:#facc15;font-size:10px;'>🕐 ").append(openCnt).append(" open</span>");
                        } else {
                            sb.append("<span style='color:#6b7280;font-size:10px;'>No trades yet</span>");
                        }
                    }
                    sb.append("<a href='/agent-detail?id=").append(urlEncode(p.agentId)).append("' style='color:#60a5fa;font-size:10px;text-decoration:none;' title='Agent detail page'>📋</a>");
                    sb.append("</div>");
                    agentIdx++;
                }
                
                sb.append("<div style='color:#8b5cf6;font-size:11px;margin-top:8px;margin-bottom:4px;'>⚡ Intraday System Agents (same-day holds):</div>");
                for (AIToolAgent.AgentPerformance p : allAgentPerfs) {
                    if (PINNED_MASTERS.contains(p.agentId)) continue;
                    AIToolAgent.AgentConfig pCfg = AIToolAgent.getAgentConfig(p.agentId);
                    if (pCfg == null || pCfg.disabled || "MOMENTUM".equals(pCfg.type) || pCfg.masterStrategy) continue;
                    if (!"INTRADAY_SYSTEM".equals(pCfg.systemType)) continue; // Only intraday agents
                    boolean isFilteredAgent = filteredAgentIds.contains(p.agentId);
                    String bgColor = isFilteredAgent ? "#1a2e1a" : (agentIdx % 2 == 0 ? "#2d1a4e" : "#1e0b3b");
                    String border = isFilteredAgent ? "border:1px solid #22c55e;" : "border:1px solid transparent;";
                    sb.append("<div style='display:flex;align-items:center;gap:4px;padding:4px 6px;background:").append(bgColor).append(";").append(border).append("border-radius:4px;margin-bottom:2px;'>");
                    sb.append("<input type='checkbox' name='agent").append(agentIdx).append("' value='").append(escapeHtml(p.agentId)).append("' style='width:14px;height:14px;'");
                    if (isFilteredAgent) sb.append(" checked");
                    sb.append(" />");
                    sb.append("<span style='color:#e5e7eb;font-weight:500;font-size:12px;'>");
                    if (isFilteredAgent) sb.append("✅ ");
                    sb.append(escapeHtml(p.agentId)).append("</span>");
                    sb.append("<span style='color:#8b5cf6;font-size:10px;'>INTRADAY</span>");
                    if (p.totalTrades > 0) {
                        double totalPL = 0;
                        List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(p.agentId);
                        if (agentTrades != null) {
                            for (AIToolAgent.Trade t : agentTrades) {
                                if (t.exitPrice > 0 && t.entryPrice > 0) {
                                    int shares = (int) (1000.0 / t.entryPrice);
                                    double plPerShare = (t.exitPrice - t.entryPrice);
                                    totalPL += plPerShare * shares;
                                }
                            }
                        }
                        String netPLColor = totalPL >= 0 ? "#22c55e" : "#ef4444";
                        sb.append("<span style='color:").append(netPLColor).append(";font-weight:600;font-size:11px;'>$").append(String.format("%.0f", totalPL)).append("</span>");
                        sb.append("<span style='color:#9ca3af;font-size:10px;'>").append(p.wins).append("/").append(p.totalTrades).append(" trades</span>");
                    } else {
                        long openCnt = p.recentTrades != null ? p.recentTrades.stream().filter(t -> "OPEN".equals(t.status)).count() : 0;
                        if (openCnt > 0) {
                            sb.append("<span style='color:#facc15;font-size:10px;'>🕐 ").append(openCnt).append(" open</span>");
                        } else {
                            sb.append("<span style='color:#6b7280;font-size:10px;'>No trades yet</span>");
                        }
                    }
                    sb.append("<a href='/agent-detail?id=").append(urlEncode(p.agentId)).append("' style='color:#60a5fa;font-size:10px;text-decoration:none;' title='Agent detail page'>📋</a>");
                    sb.append("</div>");
                    agentIdx++;
                }
                
                // 2. Show pinned master strategies (always visible regardless of performance)
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:8px;margin-bottom:4px;'>📌 Pinned Master Strategies:</div>");
                for (String masterId : PINNED_MASTERS) {
                    AIToolAgent.AgentConfig masterCfg = AIToolAgent.getAgentConfig(masterId);
                    if (masterCfg == null || masterCfg.disabled) continue;
                    AIToolAgent.AgentPerformance masterPerf = AIToolAgent.getAgentPerformance(masterId);
                    String masterName = masterCfg != null ? masterCfg.name : masterId;
                    String masterType = masterCfg != null ? (masterCfg.strategyType != null ? masterCfg.strategyType : masterCfg.type) : "MASTER";
                    boolean isFilter = masterCfg != null && !masterCfg.masterStrategy;
                    String bgPinned = isFilter ? "#2a1a0e" : "#1a1a2e";
                    String borderPinned = isFilter ? "border:1px solid #f59e0b;" : "border:1px solid #6366f1;";
                    sb.append("<div style='display:flex;align-items:center;gap:4px;padding:4px 6px;background:").append(bgPinned).append(";").append(borderPinned).append("border-radius:4px;margin-bottom:2px;'>");
                    if (!isFilter) {
                        sb.append("<input type='checkbox' name='agent").append(agentIdx).append("' value='").append(escapeHtml(masterId)).append("' style='width:14px;height:14px;' />");
                    } else {
                        sb.append("<input type='checkbox' disabled title='Market filter — not a trading strategy' style='width:14px;height:14px;opacity:0.4;' />");
                    }
                    sb.append("<span style='color:#e5e7eb;font-weight:500;font-size:12px;'>").append(escapeHtml(masterName)).append("</span>");
                    sb.append("<span style='color:#818cf8;font-size:10px;'>").append(escapeHtml(masterType)).append("</span>");
                    if (masterPerf != null && masterPerf.totalTrades > 0) {
                        double totalPL = 0;
                        List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(masterId);
                        if (agentTrades != null) {
                            for (AIToolAgent.Trade t : agentTrades) {
                                if (t.exitPrice > 0 && t.entryPrice > 0) {
                                    int shares = (int) (1000.0 / t.entryPrice);
                                    double plPerShare = (t.exitPrice - t.entryPrice);
                                    totalPL += plPerShare * shares;
                                }
                            }
                        }
                        String netPLColor2 = totalPL >= 0 ? "#22c55e" : "#ef4444";
                        sb.append("<span style='color:").append(netPLColor2).append(";font-weight:600;font-size:11px;'>$").append(String.format("%.0f", totalPL)).append("</span>");
                        sb.append("<span style='color:#9ca3af;font-size:10px;'>").append(masterPerf.wins).append("/").append(masterPerf.totalTrades).append(" trades</span>");
                    } else if (!isFilter && masterPerf != null) {
                        long openCntM = masterPerf.recentTrades != null ? masterPerf.recentTrades.stream().filter(t -> "OPEN".equals(t.status)).count() : 0;
                        if (openCntM > 0) {
                            sb.append("<span style='color:#facc15;font-size:10px;'>🕐 ").append(openCntM).append(" open</span>");
                        } else {
                            sb.append("<span style='color:#6b7280;font-size:10px;'>No trades yet</span>");
                        }
                    } else {
                        sb.append("<span style='color:#6b7280;font-size:10px;'>").append(isFilter ? "Market Filter" : "No trades yet").append("</span>");
                    }
                    sb.append("<a href='/agent-detail?id=").append(urlEncode(masterId)).append("' style='color:#60a5fa;font-size:10px;text-decoration:none;' title='Agent detail page'>📋</a>");
                    sb.append("</div>");
                    if (!isFilter) agentIdx++;
                }

                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:8px;margin-bottom:8px;'>💡 Select agents to run. Filtered agents are pre-checked. Pinned masters must be manually checked. Leave all unchecked to auto-run filtered agents.</div>");
                sb.append("<button type='submit' id='fullScanSubmitBtn' style='background:#7c3aed;margin-top:4px;'>🚀 Start Full Scan with Selected Agents</button>");
                sb.append("</form>");
                
                // Progress bar for full scan (hidden by default)
                sb.append("<div id='fullScanProgressContainer' style='display:none;margin-top:12px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'>");
                sb.append("<span id='fullScanStatusText' style='color:#9ca3af;font-size:12px;'>Starting scan...</span>");
                sb.append("<span id='fullScanProgressPct' style='color:#93c5fd;font-size:12px;'>0%</span>");
                sb.append("</div>");
                sb.append("<div style='background:#1e1b4b;border-radius:6px;height:8px;overflow:hidden;'>");
                sb.append("<div id='fullScanProgressBar' style='background:#7c3aed;height:100%;width:0%;transition:width 0.3s;'></div>");
                sb.append("</div>");
                sb.append("<div id='fullScanProgressDetail' style='color:#6b7280;font-size:11px;margin-top:4px;'></div>");
                sb.append("</div>");
                
                // Full scan status (if running or recently completed)
                if (AIToolAgent.isTopAgentsFullScanRunning() || !AIToolAgent.getTopAgentsFullScanStatus().isEmpty()) {
                    sb.append("<div style='margin-top:12px;border-top:1px solid #7c3aed;padding-top:12px;'>");
                    sb.append("<div style='font-weight:600;color:#a78bfa;margin-bottom:8px;'>📊 Scan Status</div>");
                    if (AIToolAgent.isTopAgentsFullScanRunning()) {
                        int progress = AIToolAgent.getTopAgentsFullScanProgress();
                        int total = AIToolAgent.getTopAgentsFullScanTotal();
                        double pct = total > 0 ? (progress * 100.0 / total) : 0;
                        sb.append("<div style='color:#c4b5fd;'>Status: <b>RUNNING</b></div>");
                        sb.append("<div style='color:#9ca3af;font-size:12px;'>").append(AIToolAgent.getTopAgentsFullScanStatus()).append("</div>");
                        sb.append("<div style='margin-top:8px;background:#374151;border-radius:4px;height:8px;overflow:hidden;'>");
                        sb.append("<div style='background:#7c3aed;height:100%;width:").append(String.format("%.1f", pct)).append("%;'></div>");
                        sb.append("</div>");
                        sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:4px;'>").append(progress).append(" / ").append(total).append(" (").append(String.format("%.1f%%", pct)).append(")</div>");
                    } else {
                        sb.append("<div style='color:#22c55e;'>Status: <b>").append(escapeHtml(AIToolAgent.getTopAgentsFullScanStatus())).append("</b></div>");
                    }
                    
                    // Show recent trades from full scan
                    List<AIToolAgent.Trade> recentScanTrades = AIToolAgent.getRecentFullScanTrades();
                    if (recentScanTrades != null && !recentScanTrades.isEmpty()) {
                        long openCount   = recentScanTrades.stream().filter(t -> "OPEN".equals(t.status)).count();
                        long winCount    = recentScanTrades.stream().filter(t -> "CLOSED_WIN".equals(t.status)).count();
                        long lossCount   = recentScanTrades.stream().filter(t -> "CLOSED_LOSS".equals(t.status)).count();
                        sb.append("<div style='margin-top:12px;'>");
                        sb.append("<div style='color:#a78bfa;font-size:12px;margin-bottom:4px;'>📈 Trades from Scan: ");
                        if (openCount > 0)  sb.append("<span style='color:#facc15;margin-right:6px;'>🕐 ").append(openCount).append(" open</span>");
                        if (winCount > 0)   sb.append("<span style='color:#22c55e;margin-right:6px;'>✅ ").append(winCount).append(" win</span>");
                        if (lossCount > 0)  sb.append("<span style='color:#ef4444;'>❌ ").append(lossCount).append(" loss</span>");
                        sb.append("</div>");
                        for (AIToolAgent.Trade t : recentScanTrades) {
                            boolean isOpen = "OPEN".equals(t.status);
                            boolean isWin  = "CLOSED_WIN".equals(t.status);
                            String tColor  = isOpen ? "#facc15" : (isWin ? "#22c55e" : "#ef4444");
                            String tIcon   = isOpen ? "🕐" : (isWin ? "✅" : "❌");
                            String statusLabel = isOpen ? "OPEN" : (isWin ? "WIN" : "LOSS");
                            String reasonLabel = (t.closeReason != null && !t.closeReason.isEmpty())
                                ? t.closeReason.replace("_", " ") : "";
                            sb.append("<div style='display:flex;gap:8px;align-items:center;padding:4px 8px;background:#2d2a5e;border-radius:4px;margin-bottom:2px;font-size:12px;'>");
                            sb.append("<span>").append(tIcon).append("</span>");
                            sb.append("<span style='color:#e5e7eb;font-weight:500;min-width:50px;'>").append(escapeHtml(t.ticker)).append("</span>");
                            sb.append("<span style='color:#9ca3af;min-width:60px;'>").append(escapeHtml(t.agentId)).append("</span>");
                            sb.append("<span style='color:").append(tColor).append(";font-weight:700;min-width:42px;'>").append(statusLabel).append("</span>");
                            if (!isOpen) {
                                sb.append("<span style='color:").append(tColor).append(";font-weight:600;'>").append(String.format("%+.2f%%", t.profitLossPct)).append("</span>");
                                sb.append("<span style='color:").append(tColor).append(";'>$").append(String.format("%+.2f", t.profitLoss)).append("</span>");
                                if (!reasonLabel.isEmpty()) {
                                    sb.append("<span style='color:#6b7280;font-size:10px;'>").append(escapeHtml(reasonLabel)).append("</span>");
                                }
                            } else {
                                sb.append("<span style='color:#9ca3af;'>entry $").append(String.format("%.2f", t.entryPrice)).append("</span>");
                            }
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    }
                    sb.append("</div>");
                }
                sb.append("</div>");
                sb.append("</div>");

                // Full scan JavaScript functions
                sb.append("<script>");
                sb.append("var fullScanPollInterval = null;");
                sb.append("async function pollFullScanStatus() {");
                sb.append("  try {");
                sb.append("    var r = await fetch('/api/full-scan/status');");
                sb.append("    var s = await r.json();");
                sb.append("    var container = document.getElementById('fullScanProgressContainer');");
                sb.append("    var bar = document.getElementById('fullScanProgressBar');");
                sb.append("    var statusText = document.getElementById('fullScanStatusText');");
                sb.append("    var pctText = document.getElementById('fullScanProgressPct');");
                sb.append("    var detailText = document.getElementById('fullScanProgressDetail');");
                sb.append("    var submitBtn = document.getElementById('fullScanSubmitBtn');");
                sb.append("    ");
                sb.append("    if (s.running) {");
                sb.append("      if (container) container.style.display = 'block';");
                sb.append("      if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = '⏳ Running...'; }");
                sb.append("      var progress = s.progress || 0;");
                sb.append("      var total = s.total || 1;");
                sb.append("      var pct = total > 0 ? Math.round((progress / total) * 100) : 0;");
                sb.append("      if (bar) bar.style.width = pct + '%';");
                sb.append("      if (statusText) statusText.textContent = s.statusMessage || 'Running...';");
                sb.append("      if (pctText) pctText.textContent = pct + '%';");
                sb.append("      if (detailText) detailText.textContent = progress + ' / ' + total;");
                sb.append("    } else {");
                sb.append("      if (fullScanPollInterval) { clearInterval(fullScanPollInterval); fullScanPollInterval = null; }");
                sb.append("      if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = '🚀 Start Full Scan with Selected Agents'; }");
                sb.append("      if (s.statusMessage && s.statusMessage !== '') {");
                sb.append("        if (statusText) statusText.textContent = s.statusMessage;");
                sb.append("        if (detailText) detailText.textContent = 'Scan complete';");
                sb.append("      } else {");
                sb.append("        if (container) container.style.display = 'none';");
                sb.append("      }");
                sb.append("      location.reload();");
                sb.append("    }");
                sb.append("  } catch(e) { console.log('Full scan poll error:', e); }");
                sb.append("}");
                sb.append("async function initFullScanner() {");
                sb.append("  var urlParams = new URLSearchParams(window.location.search);");
                sb.append("  if (urlParams.get('fullScanStarted') === 'true') {");
                sb.append("    fullScanPollInterval = setInterval(pollFullScanStatus, 3000);");
                sb.append("    var newUrl = window.location.pathname;");
                sb.append("    window.history.replaceState({}, document.title, newUrl);");
                sb.append("  }");
                sb.append("}");
                sb.append("async function submitFullScan(event) {");
                sb.append("  event.preventDefault();");
                sb.append("  var form = document.getElementById('fullScanForm');");
                sb.append("  var formData = new FormData(form);");
                sb.append("  var submitBtn = document.getElementById('fullScanSubmitBtn');");
                sb.append("  ");
                sb.append("  if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = '⏳ Starting...'; }");
                sb.append("  ");
                sb.append("  try {");
                sb.append("    var response = await fetch('/aitool-full-scan', {");
                sb.append("      method: 'POST',");
                sb.append("      body: formData");
                sb.append("    });");
                sb.append("    ");
                sb.append("    if (response.ok) {");
                sb.append("      if (fullScanPollInterval) clearInterval(fullScanPollInterval);");
                sb.append("      fullScanPollInterval = setInterval(pollFullScanStatus, 3000);");
                sb.append("      pollFullScanStatus();");
                sb.append("    } else {");
                sb.append("      alert('Failed to start scan');");
                sb.append("      if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = '🚀 Start Full Scan with Selected Agents'; }");
                sb.append("    }");
                sb.append("  } catch (error) {");
                sb.append("    console.error('Error starting scan:', error);");
                sb.append("    alert('Error starting scan: ' + error);");
                sb.append("    if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = '🚀 Start Full Scan with Selected Agents'; }");
                sb.append("  }");
                sb.append("}");
                sb.append("initFullScanner();");
                sb.append("</script>");

                // ── 📡 Buy Recommendations ──
                {
                    List<AIToolAgent.ScanRecommendation> recs = new ArrayList<>(AIToolAgent.getRecentRecommendations());
                    // Sort: best R:R first, then highest score, then newest timestamp
                    recs.sort((a, b) -> {
                        double rrA = (a.entryPrice > 0 && a.entryPrice > a.stopLoss)
                            ? (a.takeProfit - a.entryPrice) / (a.entryPrice - a.stopLoss) : 0;
                        double rrB = (b.entryPrice > 0 && b.entryPrice > b.stopLoss)
                            ? (b.takeProfit - b.entryPrice) / (b.entryPrice - b.stopLoss) : 0;
                        if (Math.abs(rrB - rrA) > 0.01) return Double.compare(rrB, rrA);
                        if (b.score != a.score) return Integer.compare(b.score, a.score);
                        return (b.timestamp != null ? b.timestamp : "").compareTo(a.timestamp != null ? a.timestamp : "");
                    });
                    sb.append("<div class='card'>");
                    sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;'>");
                    sb.append("<div class='title' style='margin:0;'>📡 Buy Recommendations</div>");
                    if (!recs.isEmpty()) {
                        sb.append("<span style='color:#9ca3af;font-size:12px;'>").append(recs.size()).append(" signal(s) — best R:R first</span>");
                    }
                    sb.append("</div>");
                    if (recs.isEmpty()) {
                        sb.append("<div style='color:#6b7280;text-align:center;padding:20px;font-size:13px;'>")
                          .append("No buy signals yet — run a scan to see recommendations here.")
                          .append("</div>");
                    } else {
                        // Split into top 5 + others
                        List<AIToolAgent.ScanRecommendation> top5 = recs.subList(0, Math.min(5, recs.size()));
                        List<AIToolAgent.ScanRecommendation> others = recs.size() > 5 ? recs.subList(5, recs.size()) : new ArrayList<>();

                        // ── helper rendered as a lambda-style block via a local method call ──
                        // We render cards inline for both sections
                        sb.append("<div style='color:#fcd34d;font-size:11px;font-weight:700;margin-bottom:6px;'>🏅 TOP PICKS (best R:R)</div>");
                        sb.append("<div style='display:flex;flex-direction:column;gap:8px;'>");
                        for (AIToolAgent.ScanRecommendation r : top5) {
                            double cappedSL   = r.entryPrice * 0.98;
                            double effectiveSL = Math.max(r.stopLoss, cappedSL); // tighter of strategy vs capped
                            double slPct  = r.entryPrice > 0 ? ((r.entryPrice - effectiveSL) / r.entryPrice) * 100 : 0;
                            double tpPct  = r.entryPrice > 0 ? ((r.takeProfit - r.entryPrice) / r.entryPrice) * 100 : 0;
                            double rr     = slPct > 0 ? tpPct / slPct : 0;
                            String rrColor = rr >= 2.0 ? "#22c55e" : rr >= 1.5 ? "#eab308" : "#9ca3af";
                            double entryZoneHigh = r.entryPrice * 1.01;
                            long elapsedMin = r.signalTimeMs > 0 ? (System.currentTimeMillis() - r.signalTimeMs) / 60000 : 999;
                            boolean isExpired = elapsedMin >= 10;
                            String borderColor = isExpired ? "#4b5563" : "#22c55e";
                            sb.append("<div style='background:#0f1f35;border:1px solid #1f3a5f;border-left:4px solid ").append(borderColor).append(";")
                              .append("border-radius:6px;padding:12px 14px;display:flex;flex-wrap:wrap;gap:10px;align-items:center;'>");
                            sb.append("<div style='min-width:180px;'>");
                            if (isExpired) {
                                sb.append("<span style='font-size:18px;font-weight:800;color:#6b7280;'>⏸ ").append(escapeHtml(r.ticker)).append("</span>");
                                sb.append(" <span style='background:#374151;color:#9ca3af;border-radius:4px;padding:1px 6px;font-size:10px;font-weight:700;'>EXPIRED</span>");
                            } else {
                                sb.append("<span style='font-size:18px;font-weight:800;color:#22c55e;'>✅ ").append(escapeHtml(r.ticker)).append("</span>");
                                sb.append(" <span style='background:#14532d;color:#86efac;border-radius:4px;padding:1px 6px;font-size:10px;font-weight:700;'>VALID ").append(10 - elapsedMin).append("m left</span>");
                            }
                            sb.append("<div style='color:#6b7280;font-size:11px;margin-top:2px;'>").append(escapeHtml(r.timestamp))
                              .append(" &nbsp;·&nbsp; Run #").append(r.runNumber).append("</div>");
                            sb.append("</div>");
                            sb.append("<div style='min-width:160px;'>");
                            sb.append("<div style='color:#a78bfa;font-size:12px;font-weight:600;'>").append(escapeHtml(r.agentId)).append("</div>");
                            if (r.score > 0) {
                                String scoreColor = r.score >= 11 ? "#22c55e" : r.score >= 10 ? "#eab308" : "#9ca3af";
                                sb.append("<div style='color:").append(scoreColor).append(";font-size:12px;font-weight:700;'>Score: ").append(r.score).append("/12</div>");
                            }
                            sb.append("</div>");
                            sb.append("<div style='display:flex;gap:16px;flex-wrap:wrap;font-size:13px;'>");
                            sb.append("<div><div style='color:#9ca3af;font-size:10px;'>ENTRY ZONE</div>")
                              .append("<div style='color:#93c5fd;font-weight:700;'>$").append(String.format("%.2f", r.entryPrice))
                              .append(" <span style='color:#6b7280;font-weight:400;'>– $").append(String.format("%.2f", entryZoneHigh)).append("</span></div></div>");
                            sb.append("<div><div style='color:#9ca3af;font-size:10px;'>SL <span style='color:#f59e0b;'>(max $20 risk)</span></div>")
                              .append("<div style='color:#ef4444;font-weight:700;'>$").append(String.format("%.2f", effectiveSL))
                              .append(" <span style='font-size:10px;color:#ef4444;'>-").append(String.format("%.1f%%", slPct)).append("</span></div></div>");
                            sb.append("<div><div style='color:#9ca3af;font-size:10px;'>TAKE PROFIT</div>")
                              .append("<div style='color:#22c55e;font-weight:700;'>$").append(String.format("%.2f", r.takeProfit))
                              .append(" <span style='font-size:10px;color:#22c55e;'>+").append(String.format("%.1f%%", tpPct)).append("</span></div></div>");
                            sb.append("<div><div style='color:#9ca3af;font-size:10px;'>R:R</div>")
                              .append("<div style='color:").append(rrColor).append(";font-weight:700;'>1:")
                              .append(String.format("%.1f", rr)).append("</div></div>");
                            sb.append("</div></div>");
                        }
                        sb.append("</div>");

                        if (!others.isEmpty()) {
                            sb.append("<div style='margin-top:14px;'>");
                            sb.append("<details><summary style='cursor:pointer;color:#9ca3af;font-size:12px;font-weight:600;padding:4px 0;'>▶ Other signals (").append(others.size()).append(")</summary>");
                            sb.append("<div style='display:flex;flex-direction:column;gap:6px;margin-top:8px;'>");
                            for (AIToolAgent.ScanRecommendation r : others) {
                                double cappedSL   = r.entryPrice * 0.98;
                                double effectiveSL = Math.max(r.stopLoss, cappedSL);
                                double slPct  = r.entryPrice > 0 ? ((r.entryPrice - effectiveSL) / r.entryPrice) * 100 : 0;
                                double tpPct  = r.entryPrice > 0 ? ((r.takeProfit - r.entryPrice) / r.entryPrice) * 100 : 0;
                                double rr     = slPct > 0 ? tpPct / slPct : 0;
                                String rrColor = rr >= 2.0 ? "#22c55e" : rr >= 1.5 ? "#eab308" : "#9ca3af";
                                long elapsedMin = r.signalTimeMs > 0 ? (System.currentTimeMillis() - r.signalTimeMs) / 60000 : 999;
                                boolean isExpired = elapsedMin >= 10;
                                sb.append("<div style='background:#0d1a2e;border:1px solid #1f3a5f;border-left:3px solid ")
                                  .append(isExpired ? "#374151" : "#6366f1").append(";border-radius:5px;padding:8px 12px;")
                                  .append("display:flex;flex-wrap:wrap;gap:8px;align-items:center;font-size:12px;'>");
                                sb.append("<span style='font-weight:700;color:").append(isExpired ? "#6b7280" : "#93c5fd").append(";min-width:60px;'>")
                                  .append(escapeHtml(r.ticker)).append("</span>");
                                sb.append("<span style='color:#a78bfa;'>").append(escapeHtml(r.agentId)).append("</span>");
                                sb.append("<span style='color:#9ca3af;'>Entry: <b style='color:#93c5fd;'>$").append(String.format("%.2f", r.entryPrice)).append("</b></span>");
                                sb.append("<span style='color:#9ca3af;'>SL: <b style='color:#ef4444;'>$").append(String.format("%.2f", effectiveSL)).append("</b></span>");
                                sb.append("<span style='color:#9ca3af;'>TP: <b style='color:#22c55e;'>$").append(String.format("%.2f", r.takeProfit)).append("</b></span>");
                                sb.append("<span style='color:").append(rrColor).append(";font-weight:700;'>R:R 1:").append(String.format("%.1f", rr)).append("</span>");
                                if (isExpired) sb.append(" <span style='color:#6b7280;font-size:10px;'>EXPIRED</span>");
                                else sb.append(" <span style='color:#86efac;font-size:10px;'>").append(10 - elapsedMin).append("m</span>");
                                sb.append("</div>");
                            }
                            sb.append("</div></details></div>");
                        }
                    }
                    sb.append("</div>");
                }

                // Saved Configurations with Cumulative Tracking Section
                sb.append("<div class='card'>");
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;'>");
                sb.append("<div class='title' style='margin-bottom:0;'>💾 Saved Configurations & Cumulative Tracking</div>");
                sb.append("<form method='post' action='/aitool-clear-trackers' style='margin:0;' onsubmit=\"return confirm('Clear ALL tracked agents? This cannot be undone.')\">");
                sb.append("<button type='submit' style='background:#7f1d1d;border:1px solid #ef4444;color:#fca5a5;padding:5px 12px;font-size:12px;border-radius:6px;cursor:pointer;'>🗑️ Clear All</button>");
                sb.append("</form>");
                sb.append("</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;'>Track your best performing agents over time. Agents with >75% win rate are auto-saved daily.</div>");
                
                // Show saved trackers with cumulative stats
                Map<String, ScoringConfig.SavedAgentTracker> trackers = ScoringConfig.getSavedAgentTrackers();
                String currentSavedAgent = ScoringConfig.getActiveAgentConfig();
                
                if (trackers != null && !trackers.isEmpty()) {
                    sb.append("<div style='margin-bottom:16px;'>");
                    sb.append("<div style='font-weight:600;margin-bottom:10px;color:#22c55e;'>📊 Your Tracked Agents (Cumulative Stats):</div>");
                    sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                    sb.append("<thead><tr style='background:#1f2a44;'>");
                    sb.append("<th style='padding:10px;text-align:left;'>Agent</th>");
                    sb.append("<th style='padding:10px;text-align:center;'>Cumulative</th>");
                    sb.append("<th style='padding:10px;text-align:center;'>Win Rate</th>");
                    sb.append("<th style='padding:10px;text-align:right;'>Total P/L</th>");
                    sb.append("<th style='padding:10px;text-align:center;'>Since</th>");
                    sb.append("<th style='padding:10px;text-align:center;'>Actions</th>");
                    sb.append("</tr></thead><tbody>");
                    
                    for (ScoringConfig.SavedAgentTracker tracker : trackers.values()) {
                        double cumWinRate = tracker.cumulativeTrades > 0 ? (tracker.cumulativeWins * 100.0 / tracker.cumulativeTrades) : 0;
                        String winColor = cumWinRate >= 75 ? "#22c55e" : cumWinRate >= 50 ? "#eab308" : "#ef4444";
                        boolean isActive = tracker.agentId.equals(currentSavedAgent);
                        String rowBg = isActive ? "background:rgba(34,197,94,0.15);" : "";
                        
                        // Calculate Net Profit based on $1K position size
                        double totalPL = 0;
                        List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(tracker.agentId);
                        if (agentTrades != null) {
                            for (AIToolAgent.Trade t : agentTrades) {
                                if (t.exitPrice > 0 && t.entryPrice > 0) {
                                    int shares = (int) (1000.0 / t.entryPrice);
                                    double plPerShare = (t.exitPrice - t.entryPrice);
                                    totalPL += plPerShare * shares;
                                }
                            }
                        }
                        
                        sb.append("<tr style='border-bottom:1px solid #1f2a44;").append(rowBg).append("'>");
                        sb.append("<td style='padding:10px;'>");
                        if (isActive) sb.append("✅ ");
                        sb.append("<b>").append(escapeHtml(tracker.agentId)).append("</b>");
                        if (tracker.type != null) sb.append("<div style='color:#6b7280;font-size:11px;'>").append(escapeHtml(tracker.type)).append("</div>");
                        sb.append("</td>");
                        sb.append("<td style='padding:10px;text-align:center;font-weight:600;'>").append(tracker.cumulativeWins).append("/").append(tracker.cumulativeTrades).append("</td>");
                        sb.append("<td style='padding:10px;text-align:center;color:").append(winColor).append(";font-weight:700;'>").append(String.format("%.1f%%", cumWinRate)).append("</td>");
                        sb.append("<td style='padding:10px;text-align:right;color:").append(totalPL >= 0 ? "#22c55e" : "#ef4444").append(";'>$").append(String.format("%.2f", totalPL)).append("</td>");
                        sb.append("<td style='padding:10px;text-align:center;color:#6b7280;font-size:11px;'>").append(tracker.firstSavedDate != null ? tracker.firstSavedDate : "-").append("</td>");
                        sb.append("<td style='padding:10px;text-align:center;'>");
                        sb.append("<div style='display:flex;gap:6px;justify-content:center;'>");
                        // Set as active button
                        if (!isActive) {
                            sb.append("<form method='post' action='/aitool-save-config' style='margin:0;'>");
                            sb.append("<input type='hidden' name='agentId' value='").append(escapeHtml(tracker.agentId)).append("' />");
                            sb.append("<button type='submit' style='background:#3b82f6;color:#fff;padding:4px 8px;font-size:11px;'>Use</button>");
                            sb.append("</form>");
                        }
                        // Delete button
                        sb.append("<form method='post' action='/aitool-delete-tracker' style='margin:0;'>");
                        sb.append("<input type='hidden' name='agentId' value='").append(escapeHtml(tracker.agentId)).append("' />");
                        sb.append("<button type='submit' style='background:#ef4444;color:#fff;padding:4px 8px;font-size:11px;'>🗑️</button>");
                        sb.append("</form>");
                        sb.append("</div>");
                        sb.append("</td>");
                        sb.append("</tr>");
                    }
                    sb.append("</tbody></table>");
                    sb.append("</div>");
                } else {
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:12px;margin-bottom:16px;color:#6b7280;'>");
                    sb.append("No tracked agents yet. Save an agent below to start tracking cumulative performance.");
                    sb.append("</div>");
                }
                
                // List top agents to save (>70% win rate highlighted)
                sb.append("<div id='top-agents' style='font-weight:600;margin-bottom:10px;color:#93c5fd;'>🏆 Top Performing Agents (click to track):</div>");
                sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:10px;'>");
                List<AIToolAgent.AgentPerformance> topAgents = new ArrayList<>(performances);
                // Sort by professional metrics: expectancy (primary), then profit factor, then sharpe ratio
                topAgents.sort((a, b) -> {
                    // Primary: Expectancy (the REAL metric)
                    int expCompare = Double.compare(b.expectancy, a.expectancy);
                    if (expCompare != 0) return expCompare;
                    // Secondary: Profit Factor (risk/reward ratio)
                    int pfCompare = Double.compare(b.profitFactor, a.profitFactor);
                    if (pfCompare != 0) return pfCompare;
                    // Tertiary: Sharpe Ratio (risk-adjusted returns)
                    return Double.compare(b.sharpeRatio, a.sharpeRatio);
                });
                int savedCount = 0;
                for (AIToolAgent.AgentPerformance p : topAgents) {
                    if (savedCount >= 10) break;
                    if (p.totalTrades < 3) continue;
                    boolean isWinner = p.winRate >= 70;
                    boolean isTracked = trackers != null && trackers.containsKey(p.agentId);
                    boolean notifyEnabled = ScoringConfig.isTradeNotificationEnabled(p.agentId);
                    boolean isLocked = AIToolAgent.isAgentLocked(p.agentId);
                    boolean codeVersionChanged = AIToolAgent.hasCodeVersionChanged(p.agentId);
                    // Calculate Net Profit based on $1K position size
                    double totalPL = 0;
                    List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(p.agentId);
                    if (agentTrades != null) {
                        for (AIToolAgent.Trade t : agentTrades) {
                            if (t.exitPrice > 0 && t.entryPrice > 0) {
                                int shares = (int) (1000.0 / t.entryPrice);
                                double plPerShare = (t.exitPrice - t.entryPrice);
                                totalPL += plPerShare * shares;
                            }
                        }
                    }
                    String netPLColor = totalPL >= 0 ? "#22c55e" : "#ef4444";
                    String borderColor = isLocked ? "#f59e0b" : (isTracked ? "#22c55e" : (isWinner ? "#22c55e" : "#1f2a44"));
                    sb.append("<div style='position:relative;'>");
                    sb.append("<form method='post' action='/aitool-track-agent' style='margin:0;'>");
                    sb.append("<input type='hidden' name='agentId' value='").append(escapeHtml(p.agentId)).append("' />");
                    sb.append("<button type='submit' style='width:100%;text-align:left;background:#0b1220;border:2px solid ").append(borderColor).append(";border-radius:8px;padding:12px;cursor:pointer;outline:none;'>");
                    sb.append("<div style='display:flex;justify-content:space-between;align-items:center;'>");
                    sb.append("<div style='font-weight:600;color:#e5e7eb;'>");
                    if (isLocked) sb.append("🔒 ");
                    if (isWinner) sb.append("🏆 ");
                    if (isTracked) sb.append("✓ ");
                    sb.append(escapeHtml(p.agentId)).append("</div>");
                    sb.append("<div style='display:flex;align-items:center;gap:8px;'>");
                    sb.append("<span style='color:").append(netPLColor).append(";font-weight:700;'>$").append(String.format("%.0f", totalPL)).append("</span>");
                    sb.append("</div>");
                    sb.append("</div>");
                    sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-top:4px;'>");
                    sb.append("<span style='color:#9ca3af;font-size:12px;'>").append(p.wins).append("/").append(p.totalTrades).append(" trades</span>");
                    sb.append("<a href='/agent-detail?id=").append(urlEncode(p.agentId)).append("' style='color:#60a5fa;font-size:11px;padding:2px 6px;background:#1e3a5f;border-radius:4px;text-decoration:none;' onclick='event.stopPropagation();'>📋 Detail</a>");
                    sb.append("</div>");
                    // Show code version warning if locked and code changed
                    if (isLocked && codeVersionChanged) {
                        AIToolAgent.AgentConfig cfg = AIToolAgent.getAgentConfig(p.agentId);
                        sb.append("<div style='color:#f59e0b;font-size:11px;margin-top:4px;'>⚠️ Code changed since lock (was: ").append(cfg != null && cfg.codeVersion != null ? cfg.codeVersion : "?").append(")</div>");
                    }
                    sb.append("</button>");
                    sb.append("</form>");
                    sb.append("</div>");
                    savedCount++;
                }
                sb.append("</div>");
                sb.append("</div>");

                // Scan Detail Log Viewer
                sb.append("<div class='card'><div class='title'>🔍 Scan Detail Log — Operations Trace</div>");
                String dataDir = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:8px;'>Live log from <code>" + dataDir + "/scan-detail.log</code> — auto-cleaned every 48h. Shows every agent decision: PASS, REJECT (with reason), MONITOR checks, EOD closes.</div>");
                java.nio.file.Path scanLogPath = java.nio.file.Paths.get(dataDir, "scan-detail.log");
                if (!java.nio.file.Files.exists(scanLogPath)) {
                    sb.append("<div style='color:#6b7280;padding:16px;text-align:center;'>No scan log yet — will appear after the first agent run.</div>");
                } else {
                    try {
                        java.util.List<String> logLines = java.nio.file.Files.readAllLines(scanLogPath, java.nio.charset.StandardCharsets.UTF_8);
                        // Show last 500 lines (newest at top)
                        int start = Math.max(0, logLines.size() - 500);
                        java.util.List<String> recent = logLines.subList(start, logLines.size());
                        sb.append("<div style='margin-bottom:8px;color:#9ca3af;font-size:12px;'>Showing last ")
                          .append(recent.size()).append(" of ").append(logLines.size()).append(" lines</div>");
                        sb.append("<div style='background:#0d1117;border-radius:6px;padding:12px;max-height:500px;overflow-y:auto;font-family:monospace;font-size:11px;line-height:1.6;'>");
                        for (int li = recent.size() - 1; li >= 0; li--) {
                            String line = escapeHtml(recent.get(li));
                            String color = "#e5e7eb";
                            if (line.contains("✅ SIGNAL") || line.contains("PARTIAL") || line.contains("TAKE_PROFIT")) color = "#22c55e";
                            else if (line.contains("❌ REJECT") || line.contains("STOP") || line.contains("FETCH FAIL") || line.contains("ERROR")) color = "#ef4444";
                            else if (line.contains("MONITOR") && line.contains("HOLD")) color = "#60a5fa";
                            else if (line.contains("MONITOR")) color = "#f59e0b";
                            else if (line.contains("REGIME") && line.contains("VERY WEAK")) color = "#ef4444";
                            else if (line.contains("REGIME") && line.contains("WEAK")) color = "#f59e0b";
                            else if (line.contains("REGIME") && line.contains("HEALTHY")) color = "#22c55e";
                            else if (line.contains("SCAN START") || line.contains("SCAN END") || line.contains("EOD")) color = "#a78bfa";
                            else if (line.contains("═") || line.contains("─")) color = "#374151";
                            else if (line.contains("SKIP")) color = "#6b7280";
                            sb.append("<div style='color:").append(color).append(";'>").append(line).append("</div>");
                        }
                        sb.append("</div>");
                    } catch (Exception e) {
                        sb.append("<div style='color:#ef4444;'>Error reading log: ").append(escapeHtml(e.getMessage())).append("</div>");
                    }
                }
                sb.append("</div>");

                // Stock Examination Log Viewer
                sb.append("<div class='card'><div class='title'>📊 Stock Examination Log — Final Grades</div>");
                dataDir = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:8px;'>Live log from <code>" + dataDir + "/stock-examination-log.txt</code> — shows every stock examined with final score breakdown.</div>");
                java.nio.file.Path examLogPath = java.nio.file.Paths.get(dataDir, "stock-examination-log.txt");
                if (!java.nio.file.Files.exists(examLogPath)) {
                    sb.append("<div style='color:#6b7280;padding:16px;text-align:center;'>No examination log yet — will appear after the first agent run.</div>");
                } else {
                    try {
                        java.util.List<String> examLogLines = java.nio.file.Files.readAllLines(examLogPath, java.nio.charset.StandardCharsets.UTF_8);
                        // Show last 500 lines (newest at top)
                        int start = Math.max(0, examLogLines.size() - 500);
                        java.util.List<String> recent = examLogLines.subList(start, examLogLines.size());
                        sb.append("<div style='margin-bottom:8px;color:#9ca3af;font-size:12px;'>Showing last ")
                          .append(recent.size()).append(" of ").append(examLogLines.size()).append(" lines</div>");
                        sb.append("<div style='background:#0d1117;border-radius:6px;padding:12px;max-height:500px;overflow-y:auto;font-family:monospace;font-size:11px;line-height:1.6;'>");
                        for (int li = recent.size() - 1; li >= 0; li--) {
                            String line = escapeHtml(recent.get(li));
                            String color = "#e5e7eb";
                            if (line.contains("🔍 EXAMINED")) color = "#60a5fa";
                            else if (line.contains("Score=")) {
                                // Highlight high scores in green, low in red
                                java.util.regex.Matcher scoreMatch = java.util.regex.Pattern.compile("Score=(\\d+)/12").matcher(line);
                                if (scoreMatch.find()) {
                                    int score = Integer.parseInt(scoreMatch.group(1));
                                    if (score >= 10) color = "#22c55e";
                                    else if (score >= 7) color = "#f59e0b";
                                    else color = "#ef4444";
                                }
                            }
                            sb.append("<div style='color:").append(color).append(";'>").append(line).append("</div>");
                        }
                        sb.append("</div>");
                    } catch (Exception e) {
                        sb.append("<div style='color:#ef4444;'>Error reading examination log: ").append(escapeHtml(e.getMessage())).append("</div>");
                    }
                }
                sb.append("</div>");

                // Open Positions Section — top 20, deduplicated by ticker
                List<AIToolAgent.OpenPositionSummary> openPosSummaries = AIToolAgent.getTop20OpenPositionsDeduped();
                int totalOpenRaw = AIToolAgent.getOpenPositionsCount();
                String todayEtStr = LocalDate.now(ZoneId.of("America/New_York")).toString(); // YYYY-MM-DD
                long todayCount = openPosSummaries.stream()
                    .filter(p -> p.entryTime != null && p.entryTime.startsWith(todayEtStr))
                    .count();
                sb.append("<div id='open-positions' style='background:#1e1b4b;border-radius:8px;padding:16px;margin-bottom:20px;border:1px solid #7c3aed;'>");
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;'>");
                sb.append("<div style='font-size:16px;font-weight:600;color:#a78bfa;'>📋 Open Positions — Place in IB");
                if (totalOpenRaw > 0) {
                    sb.append(" <span style='color:#6b7280;font-size:12px;font-weight:400;'>(").append(totalOpenRaw).append(" total across all agents)</span>");
                }
                sb.append("</div></div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-bottom:10px;'>")
                  .append("Use the values below to place <b>BUY LIMIT</b> orders in Interactive Brokers. ")
                  .append("Set a <b style='color:#ef4444;'>Stop Loss</b> bracket order and a <b style='color:#22c55e;'>Take Profit</b> limit. ")
                  .append("Positions are auto-tracked; manual entry is optional.");
                if (todayCount > 0) {
                    sb.append(" &nbsp;<span style='background:#14532d;color:#86efac;border:1px solid #16a34a;border-radius:4px;padding:1px 7px;font-weight:700;'>🟢 ").append(todayCount).append(" new today</span>");
                }
                sb.append("</div>");

                // Build set of high-performing agent IDs (>=70% win rate, >=3 trades) — used by both open position tables
                java.util.Set<String> highPerfAgents = new java.util.HashSet<>();
                for (AIToolAgent.AgentPerformance hp : performances) {
                    if (hp.totalTrades >= 3 && hp.winRate >= 70.0) {
                        highPerfAgents.add(hp.agentId);
                    }
                }

                if (openPosSummaries.isEmpty()) {
                    sb.append("<div style='color:#9ca3af;font-size:13px;'>No open positions. Positions will appear here after running a Full Scan.</div>");
                } else {
                    sb.append("<div style='overflow-x:auto;'>");
                    sb.append("<table style='width:100%;border-collapse:collapse;font-size:12px;'>");
                    sb.append("<thead><tr style='background:#0b1220;'>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>#</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Ticker</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>BUY @ (Limit)</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Qty</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>🛑 Stop Loss</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>🎯 Take Profit</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Risk%</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Reward%</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Agent(s)</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Opened</th>");
                    sb.append("</tr></thead><tbody>");

                    int rowNum = 0;
                    for (AIToolAgent.OpenPositionSummary pos : openPosSummaries) {
                        rowNum++;
                        boolean isToday = pos.entryTime != null && pos.entryTime.startsWith(todayEtStr);
                        String rowBg = isToday ? "#0f2a1a" : (rowNum % 2 == 0 ? "#24215a" : "#2d2a5e");
                        String rowBorder = isToday ? "#16a34a" : "#3d3a7e";
                        double slPct = pos.entryPrice > 0 ? ((pos.entryPrice - pos.stopLoss) / pos.entryPrice * 100) : 0;
                        double tpPct = pos.entryPrice > 0 ? ((pos.takeProfit - pos.entryPrice) / pos.entryPrice * 100) : 0;
                        String entryTimeDisplay = pos.entryTime != null ? pos.entryTime.substring(0, Math.min(16, pos.entryTime.length())).replace("T", " ") : "N/A";
                        String agentList = String.join(", ", pos.agentIds);
                        int qty = pos.quantity > 0 ? (int) pos.quantity : 0;

                        // Build agent chips — clickable links to agent-detail, yellow highlight for high performers
                        StringBuilder agentChips = new StringBuilder();
                        for (String aid : pos.agentIds) {
                            if (agentChips.length() > 0) agentChips.append(" ");
                            String href = "/agent-detail?id=" + urlEncode(aid);
                            if (highPerfAgents.contains(aid)) {
                                agentChips.append("<a href='").append(href).append("' style='background:#854d0e;color:#fef08a;border:1px solid #ca8a04;border-radius:4px;padding:1px 5px;font-weight:700;text-decoration:none;' title='High performer — click for detail'>")
                                    .append(escapeHtml(aid)).append("</a>");
                            } else {
                                agentChips.append("<a href='").append(href).append("' style='color:#a78bfa;text-decoration:none;' title='Click for agent detail'>").append(escapeHtml(aid)).append("</a>");
                            }
                        }

                        sb.append("<tr style='background:").append(rowBg).append(";border-left:3px solid ").append(rowBorder).append(";'>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";color:#6b7280;'>").append(rowNum).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";font-weight:700;font-size:13px;'>");
                        sb.append("<span style='color:#e5e7eb;'>").append(escapeHtml(pos.ticker)).append("</span>");
                        if (isToday) sb.append(" <span style='background:#14532d;color:#86efac;border-radius:3px;padding:0 5px;font-size:10px;font-weight:700;'>NEW</span>");
                        sb.append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#93c5fd;font-weight:700;font-size:13px;'>$").append(String.format("%.2f", pos.entryPrice)).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#e5e7eb;font-weight:600;'>")
                          .append(qty > 0 ? qty : "<span style='color:#6b7280;'>—</span>").append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#ef4444;font-weight:600;'>$").append(String.format("%.2f", pos.stopLoss)).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#22c55e;font-weight:600;'>$").append(String.format("%.2f", pos.takeProfit)).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#ef4444;font-size:11px;'>-").append(String.format("%.1f%%", slPct)).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";text-align:right;color:#22c55e;font-size:11px;'>+").append(String.format("%.1f%%", tpPct)).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";font-size:11px;max-width:200px;' title='").append(escapeHtml(agentList)).append("'>").append(agentChips).append("</td>");
                        sb.append("<td style='padding:8px;border-bottom:1px solid ").append(rowBorder).append(";color:#9ca3af;font-size:11px;'>").append(entryTimeDisplay).append("</td>");
                        sb.append("</tr>");
                    }

                    sb.append("</tbody></table>");
                    sb.append("</div>");
                    sb.append("<div style='margin-top:10px;color:#9ca3af;font-size:11px;'>")
                      .append("💡 <b style='color:#86efac;'>NEW</b> = opened today. Each row = one unique stock (deduplicated). ")
                      .append("Closed automatically at 4:30 PM ET or when SL/TP is hit. ")
                      .append("<span style='background:#854d0e;color:#fef08a;border:1px solid #ca8a04;border-radius:4px;padding:1px 5px;font-weight:700;'>Agent</span> = 🏆 High performer</div>");
                }
                sb.append("</div>");
                sb.append("</div>"); // close main AITool card

                // Agent Win/Loss Leaderboard — separate card
                sb.append("<div class='card'><div class='title'>📊 Agent Win/Loss Leaderboard &mdash; Top 10 Agents</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:12px;font-size:13px;'>Top 10 agents sorted by win rate. 🏆 = winning agent (P/L &gt; 0 &amp; win rate &gt; 50%). Agents below <b style='color:#ef4444;'>70%</b> win rate automatically trigger LLM/Ollama-guided evolution.</div>");
                sb.append("<div style='overflow-x:auto;'>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr style='background:#0b1220;'>");
                sb.append("<th style='padding:10px 8px;text-align:left;border-bottom:1px solid #1f2a44;'>Agent</th>");
                sb.append("<th style='padding:10px 8px;text-align:left;border-bottom:1px solid #1f2a44;'>Type</th>");
                sb.append("<th style='padding:10px 8px;text-align:center;border-bottom:1px solid #1f2a44;'>Trades</th>");
                sb.append("<th style='padding:10px 8px;text-align:center;border-bottom:1px solid #1f2a44;'>Win/Loss</th>");
                sb.append("<th style='padding:10px 8px;text-align:center;border-bottom:1px solid #1f2a44;'>Win Rate</th>");
                sb.append("<th style='padding:10px 8px;text-align:right;border-bottom:1px solid #1f2a44;'>Total P/L</th>");
                sb.append("<th style='padding:10px 8px;text-align:right;border-bottom:1px solid #1f2a44;'>Daily Avg</th>");
                sb.append("<th style='padding:10px 8px;text-align:right;border-bottom:1px solid #1f2a44;'>Weekly Avg</th>");
                sb.append("<th style='padding:10px 8px;text-align:right;border-bottom:1px solid #1f2a44;'>Monthly Avg</th>");
                sb.append("<th style='padding:10px 8px;text-align:left;border-bottom:1px solid #1f2a44;'>Latest 3 Trades</th>");
                sb.append("<th style='padding:10px 8px;text-align:center;border-bottom:1px solid #1f2a44;'>Config</th>");
                sb.append("</tr></thead><tbody>");

                // Sort by professional metrics: expectancy (primary), then profit factor, then sharpe ratio
                performances.sort((a, b) -> {
                    // Primary: Expectancy (the REAL metric)
                    int expCompare = Double.compare(b.expectancy, a.expectancy);
                    if (expCompare != 0) return expCompare;
                    // Secondary: Profit Factor (risk/reward ratio)
                    int pfCompare = Double.compare(b.profitFactor, a.profitFactor);
                    if (pfCompare != 0) return pfCompare;
                    // Tertiary: Sharpe Ratio (risk-adjusted returns)
                    return Double.compare(b.sharpeRatio, a.sharpeRatio);
                });

                // Limit to top 10 agents
                int maxRows = 10;
                int rowCount = 0;
                for (AIToolAgent.AgentPerformance perf : performances) {
                    if (rowCount >= maxRows) break;
                    rowCount++;
                    String rowBg = perf.isWinning ? "background:rgba(34,197,94,0.15);" : "";
                    String rowBorder = perf.isWinning ? "border-left:3px solid #22c55e;" : "";
                    
                    sb.append("<tr style='").append(rowBg).append(rowBorder).append("'>");
                    
                    // Agent name with generation indicator
                    sb.append("<td style='padding:8px;border-bottom:1px solid #1f2a44;'>");
                    if (perf.isWinning) sb.append("<span style='color:#22c55e;'>🏆 </span>");
                    sb.append("<b>").append(escapeHtml(perf.agentName != null ? perf.agentName : perf.agentId)).append("</b>");
                    if (perf.generation > 0) {
                        sb.append(" <span style='color:#9ca3af;font-size:11px;'>(Gen ").append(perf.generation).append(")</span>");
                    }
                    sb.append("</td>");
                    
                    // Type
                    String typeColor = "#93c5fd";
                    if ("MOMENTUM".equals(perf.type)) typeColor = "#f97316";
                    else if ("SWING".equals(perf.type)) typeColor = "#a855f7";
                    else if ("INTRADAY".equals(perf.type)) typeColor = "#eab308";
                    else if ("SUCCESS".equals(perf.type)) typeColor = "#22c55e";
                    sb.append("<td style='padding:8px;border-bottom:1px solid #1f2a44;'><span style='color:").append(typeColor).append(";'>").append(escapeHtml(perf.type != null ? perf.type : "")).append("</span></td>");
                    
                    // Trades
                    sb.append("<td style='padding:8px;text-align:center;border-bottom:1px solid #1f2a44;'>").append(perf.totalTrades).append("</td>");
                    
                    // Win/Loss
                    sb.append("<td style='padding:8px;text-align:center;border-bottom:1px solid #1f2a44;'>");
                    sb.append("<span style='color:#22c55e;'>").append(perf.wins).append("</span>");
                    sb.append(" / ");
                    sb.append("<span style='color:#ef4444;'>").append(perf.losses).append("</span>");
                    sb.append("</td>");
                    
                    // Win Rate
                    String winRateColor = perf.winRate >= 50 ? "#22c55e" : "#ef4444";
                    sb.append("<td style='padding:8px;text-align:center;border-bottom:1px solid #1f2a44;color:").append(winRateColor).append(";font-weight:600;'>")
                      .append(String.format("%.1f%%", perf.winRate)).append("</td>");
                    
                    // Total P/L (calculated based on $1K position size)
                    double totalPL = 0;
                    List<AIToolAgent.Trade> agentTrades = AIToolAgent.getAgentTradeHistory(perf.agentId);
                    if (agentTrades != null) {
                        for (AIToolAgent.Trade t : agentTrades) {
                            if (t.exitPrice > 0 && t.entryPrice > 0) {
                                int shares = (int) (1000.0 / t.entryPrice);
                                double plPerShare = (t.exitPrice - t.entryPrice);
                                totalPL += plPerShare * shares;
                            }
                        }
                    }
                    String plColor = totalPL >= 0 ? "#22c55e" : "#ef4444";
                    sb.append("<td style='padding:8px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(plColor).append(";font-weight:600;'>")
                      .append(String.format("$%.2f", totalPL)).append("</td>");
                    
                    // Daily Avg
                    String dailyColor = perf.avgDailyReturn >= 0 ? "#22c55e" : "#ef4444";
                    sb.append("<td style='padding:8px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(dailyColor).append(";'>")
                      .append(String.format("%.2f%%", perf.avgDailyReturn)).append("</td>");
                    
                    // Weekly Avg
                    String weeklyColor = perf.avgWeeklyReturn >= 0 ? "#22c55e" : "#ef4444";
                    sb.append("<td style='padding:8px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(weeklyColor).append(";'>")
                      .append(String.format("%.2f%%", perf.avgWeeklyReturn)).append("</td>");
                    
                    // Monthly Avg
                    String monthlyColor = perf.avgMonthlyReturn >= 0 ? "#22c55e" : "#ef4444";
                    sb.append("<td style='padding:8px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(monthlyColor).append(";'>")
                      .append(String.format("%.2f%%", perf.avgMonthlyReturn)).append("</td>");
                    
                    // Latest 3 trades
                    sb.append("<td style='padding:8px;border-bottom:1px solid #1f2a44;font-size:11px;'>");
                    if (perf.recentTrades != null && !perf.recentTrades.isEmpty()) {
                        for (int i = 0; i < Math.min(3, perf.recentTrades.size()); i++) {
                            AIToolAgent.Trade t = perf.recentTrades.get(i);
                            String tColor = t.profitLoss >= 0 ? "#22c55e" : "#ef4444";
                            String tIcon = t.profitLoss >= 0 ? "📈" : "📉";
                            sb.append("<div style='color:").append(tColor).append(";'>");
                            sb.append(tIcon).append(" ").append(escapeHtml(t.ticker));
                            sb.append(" ").append(String.format("%+.1f%%", t.profitLossPct));
                            sb.append("</div>");
                        }
                    } else {
                        sb.append("<span style='color:#6b7280;'>No trades yet</span>");
                    }
                    sb.append("</td>");
                    
                    // Config changed indicator
                    sb.append("<td style='padding:8px;text-align:center;border-bottom:1px solid #1f2a44;'>");
                    if (perf.configChanged && perf.newConfigName != null) {
                        sb.append("<span style='color:#eab308;' title='Evolved to: ").append(escapeHtml(perf.newConfigName)).append("'>🔄 ").append(escapeHtml(perf.newConfigName)).append("</span>");
                    } else {
                        sb.append("<span style='color:#6b7280;'>-</span>");
                    }
                    sb.append("</td>");
                    
                    sb.append("</tr>");
                }

                sb.append("</tbody></table>");
                sb.append("</div>");
                sb.append("</div>");

                // Legend
                sb.append("<div class='card'><div class='title'>📖 Legend</div>");
                sb.append("<div style='display:flex;gap:20px;flex-wrap:wrap;font-size:13px;'>");
                sb.append("<div><span style='color:#22c55e;'>🏆 GREEN row</span> = Winning agent (P/L &gt; 0 and Win Rate &gt; 50%)</div>");
                sb.append("<div><span style='color:#f97316;'>MOMENTUM</span> = momentum-variants.json</div>");
                sb.append("<div><span style='color:#a855f7;'>SWING</span> = swing-variants.json</div>");
                sb.append("<div><span style='color:#eab308;'>INTRADAY</span> = intraday-variants.json</div>");
                sb.append("<div><span style='color:#22c55e;'>SUCCESS</span> = momentum-success-2026.json</div>");
                sb.append("<div><span style='color:#eab308;'>🔄</span> = Config evolved (saved to " + dataDir + "/)</div>");
                sb.append("</div>");
                sb.append("</div>");

                // Open Positions Section - Live monitoring table
                sb.append("<div class='card'><div class='title'>🟢 Open Positions — Live Monitoring</div>");
                AIToolAgent.AgentSystemState agentState = AIToolAgent.getSystemState();
                {
                    List<AIToolAgent.Trade> openTrades = new ArrayList<>();
                    if (agentState.tradeHistory != null) {
                        for (List<AIToolAgent.Trade> tlist : agentState.tradeHistory.values()) {
                            for (AIToolAgent.Trade t : tlist) {
                                if ("OPEN".equals(t.status)) openTrades.add(t);
                            }
                        }
                    }
                    openTrades.sort((a, b) -> {
                        if (a.entryTime == null) return 1;
                        if (b.entryTime == null) return -1;
                        return b.entryTime.compareTo(a.entryTime);
                    });
                    if (openTrades.isEmpty()) {
                        sb.append("<div style='color:#6b7280;padding:20px;text-align:center;'>No open positions right now.</div>");
                    } else {
                        sb.append("<div style='margin-bottom:8px;color:#9ca3af;'>Open: <b style='color:#22c55e;'>").append(openTrades.size()).append("</b> position(s)</div>");
                        sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:12px;'>");
                        sb.append("<thead><tr style='background:#1f2a44;'>");
                        sb.append("<th style='padding:8px;text-align:left;'>Entry Time</th>");
                        sb.append("<th style='padding:8px;text-align:left;'>Agent</th>");
                        sb.append("<th style='padding:8px;text-align:left;'>Ticker</th>");
                        sb.append("<th style='padding:8px;text-align:right;'>Entry $</th>");
                        sb.append("<th style='padding:8px;text-align:right;'>Stop $</th>");
                        sb.append("<th style='padding:8px;text-align:right;'>Target $</th>");
                        sb.append("<th style='padding:8px;text-align:center;'>1R Partial</th>");
                        sb.append("<th style='padding:8px;text-align:center;'>Status</th>");
                        sb.append("</tr></thead><tbody>");
                        for (AIToolAgent.Trade t : openTrades) {
                            sb.append("<tr style='border-bottom:1px solid #1f2a44;'>");
                            String entryTimeStr = t.entryTime != null && t.entryTime.length() > 16 ? t.entryTime.substring(11, 16) : (t.entryTime != null ? t.entryTime : "");
                            sb.append("<td style='padding:6px;color:#9ca3af;'>").append(escapeHtml(entryTimeStr)).append("</td>");
                            String agentIdVal = t.agentId != null ? t.agentId : "";
                            if (highPerfAgents.contains(agentIdVal)) {
                                sb.append("<td style='padding:6px;'><span style='background:#854d0e;color:#fef08a;border:1px solid #ca8a04;border-radius:4px;padding:1px 5px;font-weight:700;' title='🏆 High performer (≥75% win rate)'>").append(escapeHtml(agentIdVal)).append("</span></td>");
                            } else {
                                sb.append("<td style='padding:6px;color:#a78bfa;'>").append(escapeHtml(agentIdVal)).append("</td>");
                            }
                            sb.append("<td style='padding:6px;font-weight:600;color:#93c5fd;'>").append(escapeHtml(t.ticker != null ? t.ticker : "")).append("</td>");
                            sb.append("<td style='padding:6px;text-align:right;'>$").append(String.format("%.2f", t.entryPrice)).append("</td>");
                            sb.append("<td style='padding:6px;text-align:right;color:#ef4444;'>$").append(String.format("%.2f", t.stopLoss)).append("</td>");
                            sb.append("<td style='padding:6px;text-align:right;color:#22c55e;'>$").append(String.format("%.2f", t.takeProfit)).append("</td>");
                            if (t.partialExitDone) {
                                sb.append("<td style='padding:6px;text-align:center;color:#a78bfa;font-weight:600;'>💰 $").append(String.format("%.2f", t.partialExitPrice)).append("</td>");
                            } else {
                                sb.append("<td style='padding:6px;text-align:center;color:#6b7280;'>—</td>");
                            }
                            sb.append("<td style='padding:6px;text-align:center;'><span style='background:#1f4f2e;color:#22c55e;padding:2px 8px;border-radius:10px;font-size:11px;'>OPEN</span></td>");
                            sb.append("</tr>");
                        }
                        sb.append("</tbody></table></div>");
                    }
                }
                sb.append("</div>");

                // Trade History Section - All Buy/Sell transactions
                sb.append("<div class='card'><div class='title'>📜 Trade History - All Buy/Sell Transactions</div>");
                if (agentState.tradeHistory == null || agentState.tradeHistory.isEmpty()) {
                    sb.append("<div style='color:#6b7280;padding:20px;text-align:center;'>No trades recorded yet. Run agents to see trade history.</div>");
                } else {
                    // Collect all trades and sort by time (newest first)
                    List<AIToolAgent.Trade> allTrades = new ArrayList<>();
                    for (List<AIToolAgent.Trade> trades : agentState.tradeHistory.values()) {
                        allTrades.addAll(trades);
                    }
                    allTrades.sort((a, b) -> {
                        if (a.entryTime == null) return 1;
                        if (b.entryTime == null) return -1;
                        return b.entryTime.compareTo(a.entryTime);
                    });
                    
                    sb.append("<div style='margin-bottom:10px;color:#9ca3af;'>Total trades: <b>").append(allTrades.size()).append("</b></div>");
                    sb.append("<div style='max-height:500px;overflow-y:auto;'>");
                    sb.append("<table style='width:100%;border-collapse:collapse;font-size:12px;'>");
                    sb.append("<thead><tr style='background:#1f2a44;position:sticky;top:0;'>");
                    sb.append("<th style='padding:8px;text-align:left;'>Time</th>");
                    sb.append("<th style='padding:8px;text-align:left;'>Agent</th>");
                    sb.append("<th style='padding:8px;text-align:center;'>Action</th>");
                    sb.append("<th style='padding:8px;text-align:left;'>Ticker</th>");
                    sb.append("<th style='padding:8px;text-align:right;'>Entry $</th>");
                    sb.append("<th style='padding:8px;text-align:right;'>Exit $</th>");
                    sb.append("<th style='padding:8px;text-align:right;'>P/L $</th>");
                    sb.append("<th style='padding:8px;text-align:right;'>P/L %</th>");
                    sb.append("<th style='padding:8px;text-align:center;'>Status</th>");
                    sb.append("</tr></thead><tbody>");
                    
                    // Show last 100 trades
                    int shown = 0;
                    for (AIToolAgent.Trade t : allTrades) {
                        if (shown >= 100) break;
                        shown++;
                        
                        String rowBg = t.profitLoss >= 0 ? "rgba(34,197,94,0.1)" : "rgba(239,68,68,0.1)";
                        sb.append("<tr style='background:").append(rowBg).append(";'>");
                        
                        // Time
                        String timeStr = t.entryTime != null ? t.entryTime : "";
                        if (timeStr.length() > 19) timeStr = timeStr.substring(0, 19).replace("T", " ");
                        sb.append("<td style='padding:6px;border-bottom:1px solid #1f2a44;color:#9ca3af;font-size:11px;'>").append(escapeHtml(timeStr)).append("</td>");
                        
                        // Agent
                        sb.append("<td style='padding:6px;border-bottom:1px solid #1f2a44;'>").append(escapeHtml(t.agentId != null ? t.agentId : "")).append("</td>");
                        
                        // Action
                        String actionColor = "BUY".equals(t.action) ? "#22c55e" : "#ef4444";
                        String actionIcon = "BUY".equals(t.action) ? "🟢" : "🔴";
                        sb.append("<td style='padding:6px;text-align:center;border-bottom:1px solid #1f2a44;color:").append(actionColor).append(";font-weight:600;'>").append(actionIcon).append(" ").append(escapeHtml(t.action != null ? t.action : "")).append("</td>");
                        
                        // Ticker
                        sb.append("<td style='padding:6px;border-bottom:1px solid #1f2a44;font-weight:600;color:#93c5fd;'>").append(escapeHtml(t.ticker != null ? t.ticker : "")).append("</td>");
                        
                        // Entry Price
                        sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #1f2a44;'>$").append(String.format("%.2f", t.entryPrice)).append("</td>");
                        
                        // Exit Price
                        sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #1f2a44;'>$").append(String.format("%.2f", t.exitPrice)).append("</td>");
                        
                        // P/L $
                        String plColor = t.profitLoss >= 0 ? "#22c55e" : "#ef4444";
                        sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(plColor).append(";font-weight:600;'>").append(String.format("%+.2f", t.profitLoss)).append("</td>");
                        
                        // P/L %
                        sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #1f2a44;color:").append(plColor).append(";'>").append(String.format("%+.2f%%", t.profitLossPct)).append("</td>");
                        
                        // Status
                        String statusIcon = "CLOSED_WIN".equals(t.status) ? "✅" : "❌";
                        sb.append("<td style='padding:6px;text-align:center;border-bottom:1px solid #1f2a44;'>").append(statusIcon).append("</td>");
                        
                        sb.append("</tr>");
                    }
                    
                    sb.append("</tbody></table>");
                    if (allTrades.size() > 100) {
                        sb.append("<div style='color:#6b7280;padding:10px;text-align:center;'>Showing 100 of ").append(allTrades.size()).append(" trades. Full history in newStrategies/agent-state.json</div>");
                    }
                    sb.append("</div>");
                }
                sb.append("</div>");

                // Source files info
                sb.append("<div class='card'><div class='title'>📁 Agent Sources</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;'>");
                sb.append("<div>• <b>momentum-variants.json</b> - 10 momentum strategies</div>");
                sb.append("<div>• <b>momentum-success-2026.json</b> - 3 success formula strategies</div>");
                sb.append("<div>• <b>intraday-variants.json</b> - 6 intraday VWAP strategies</div>");
                sb.append("<div>• <b>swing-variants.json</b> - 10 swing trading strategies</div>");
                sb.append("<div>• <b>newStrategies/</b> - Evolved agent configurations (auto-generated)</div>");
                sb.append("</div>");
                sb.append("</div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/aitool-monitor", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                new Thread(() -> {
                    try { AIToolAgent.monitorOpenPositions(); }
                    catch (Exception e) { System.err.println("[Monitor] Error: " + e.getMessage()); }
                }, "manual-monitor").start();
                ex.getResponseHeaders().add("Location", "/aitool?monitored=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        server.createContext("/aitool-run", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                
                // Start async run
                AIToolAgent.runAgentsAsync();
                
                ex.getResponseHeaders().add("Location", "/aitool?started=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Run Swing System agents only
        server.createContext("/aitool-run-swing", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                
                // Start async run with swing agents only
                java.util.List<String> swingAgents = AIToolAgent.getSwingSystemAgents();
                if (swingAgents.isEmpty()) {
                    ex.getResponseHeaders().add("Location", "/aitool?error=no_swing_agents");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                AIToolAgent.runAgentsAsync(swingAgents);
                
                ex.getResponseHeaders().add("Location", "/aitool?started=swing");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Run Intraday System agents only
        server.createContext("/aitool-run-intraday", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                
                // Start async run with intraday agents only
                java.util.List<String> intradayAgents = AIToolAgent.getIntradaySystemAgents();
                if (intradayAgents.isEmpty()) {
                    ex.getResponseHeaders().add("Location", "/aitool?error=no_intraday_agents");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                AIToolAgent.runAgentsAsync(intradayAgents);
                
                ex.getResponseHeaders().add("Location", "/aitool?started=intraday");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Live status polling for Run All Agents progress bar
        server.createContext("/aitool-run-status", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                boolean running = AIToolAgent.isRunning();
                int prog = AIToolAgent.getRunProgress();
                int tot  = AIToolAgent.getRunTotal();
                String ca = AIToolAgent.getCurrentAgent();
                String ct = AIToolAgent.getRunCurrentTicker();
                // Phase: if no agent is assigned yet, we are still in pre-fetch
                String phase = (running && (ca == null || ca.isEmpty())) ? "Fetching data" : "Scanning signals";
                String body = "{\"running\":" + running
                    + ",\"progress\":" + prog
                    + ",\"total\":" + tot
                    + ",\"phase\":\"" + phase + "\""
                    + ",\"currentAgent\":\"" + (ca != null ? ca.replace("\"","\\\"") : "") + "\""
                    + ",\"currentTicker\":\"" + (ct != null ? ct.replace("\"","\\\"") : "") + "\"}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            }
        });

        // Watchlist: return all WAITING pending signals as JSON
        server.createContext("/aitool-pending", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                List<AIToolAgent.PendingSignal> list = AIToolAgent.getPendingSignals();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    AIToolAgent.PendingSignal ps = list.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("{")
                      .append("\"id\":\"").append(ps.id).append("\",")
                      .append("\"ticker\":").append(escapeJsonString(ps.ticker)).append(",")
                      .append("\"strategyId\":").append(escapeJsonString(ps.strategyId)).append(",")
                      .append("\"strategyType\":").append(escapeJsonString(ps.strategyType != null ? ps.strategyType : "")).append(",")
                      .append("\"score\":").append(ps.score).append(",")
                      .append("\"scanPrice\":").append(String.format("%.2f", ps.scanPrice)).append(",")
                      .append("\"triggerPrice\":").append(String.format("%.2f", ps.triggerPrice)).append(",")
                      .append("\"triggerGapPct\":").append(String.format("%.2f", ps.triggerGapPct)).append(",")
                      .append("\"suggestedStopLoss\":").append(String.format("%.2f", ps.suggestedStopLoss)).append(",")
                      .append("\"suggestedTakeProfit\":").append(String.format("%.2f", ps.suggestedTakeProfit)).append(",")
                      .append("\"rejectReason\":").append(escapeJsonString(ps.rejectReason != null ? ps.rejectReason : "")).append(",")
                      .append("\"scanTime\":").append(escapeJsonString(ps.scanTime != null ? ps.scanTime.substring(0, Math.min(19, ps.scanTime.length())) : "")).append(",")
                      .append("\"scanCount\":").append(ps.scanCount)
                      .append("}");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            }
        });

        // Watchlist: dismiss a pending signal by id
        server.createContext("/aitool-pending-dismiss", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String id = "";
                String query = ex.getRequestURI().getQuery();
                if (query != null) {
                    for (String kv : query.split("&")) {
                        if (kv.startsWith("id=")) id = kv.substring(3);
                    }
                }
                boolean ok = AIToolAgent.dismissPendingSignal(id);
                String body = ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"not found\"}";
                ex.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            }
        });

        // Full scan with selected agents against all 500+ tickers
        server.createContext("/aitool-full-scan", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                
                // Parse selected agents from form
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                
                List<String> selectedAgents = new ArrayList<>();
                for (int i = 0; i < 15; i++) {
                    String agentId = form.get("agent" + i);
                    if (agentId != null && !agentId.trim().isEmpty()) {
                        selectedAgents.add(agentId.trim());
                    }
                }
                
                // Start async full scan with selected agents
                if (selectedAgents.isEmpty()) {
                    AIToolAgent.runTop5AgentsFullScanAsync();
                } else {
                    AIToolAgent.runFullScanWithAgentsAsync(selectedAgents);
                }
                
                // Return JSON response for AJAX
                ex.getResponseHeaders().add("Content-Type", "application/json");
                String jsonResponse = "{\"success\":true,\"message\":\"Full scan started\"}";
                byte[] bytes = jsonResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            }
        });

        // ── Per-agent detail page ──────────────────────────────────────────────
        server.createContext("/agent-detail", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String query = ex.getRequestURI().getQuery();
                String agentId = "";
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("id=")) {
                            agentId = java.net.URLDecoder.decode(part.substring(3), "UTF-8");
                            break;
                        }
                    }
                }
                if (agentId.isEmpty()) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close(); return;
                }

                AIToolAgent.AgentPerformance perf = AIToolAgent.getAgentPerformance(agentId);
                AIToolAgent.AgentConfig cfg = AIToolAgent.getAgentConfig(agentId);
                List<AIToolAgent.Trade> allTrades = AIToolAgent.getAgentTradeHistory(agentId);

                StringBuilder sb = new StringBuilder();
                // ── Header ──
                sb.append("<div style='max-width:1100px;margin:0 auto;padding:20px;'>");
                sb.append("<div style='display:flex;align-items:center;gap:12px;margin-bottom:20px;'>");
                sb.append("<a href='/aitool' style='color:#a78bfa;text-decoration:none;font-size:14px;'>← Back to AITool</a>");
                sb.append("<span style='color:#374151;'>|</span>");
                sb.append("<span style='font-size:20px;font-weight:700;color:#e5e7eb;'>📋 Agent Detail: ").append(escapeHtml(agentId)).append("</span>");
                boolean isTop = AIToolAgent.isTopAgent(agentId);
                if (isTop) sb.append("<span style='background:#15803d;color:#86efac;font-size:12px;padding:2px 8px;border-radius:12px;font-weight:600;'>🏆 TOP AGENT</span>");
                sb.append("</div>");

                // Pre-compute avg win / avg loss P/L and Net Profit from trade history (based on $1K position size)
                double avgWinPL = 0, avgLossPL = 0, totalPL = 0;
                {
                    double sumWin = 0, sumLoss = 0; int cntWin = 0, cntLoss = 0;
                    for (AIToolAgent.Trade t : allTrades) {
                        if (t.exitPrice > 0 && t.entryPrice > 0) {
                            // Calculate P/L based on $1K position size
                            int shares = (int) (1000.0 / t.entryPrice);
                            double plPerShare = (t.exitPrice - t.entryPrice);
                            double plDollars = plPerShare * shares;
                            totalPL += plDollars;
                            
                            if ("CLOSED_WIN".equals(t.status))  { sumWin  += plDollars; cntWin++;  }
                            else if ("CLOSED_LOSS".equals(t.status)) { sumLoss += plDollars; cntLoss++; }
                        }
                    }
                    avgWinPL  = cntWin  > 0 ? sumWin  / cntWin  : 0;
                    avgLossPL = cntLoss > 0 ? sumLoss / cntLoss : 0;
                }

                // ── Stats cards ──
                double winRate = perf != null ? perf.winRate : 0;
                int totalTrades = perf != null ? perf.totalTrades : 0;
                int wins = perf != null ? perf.wins : 0;
                int losses = perf != null ? perf.losses : 0;
                double currentCapital = perf != null ? perf.currentCapital : 10000.0;
                double maxDrawdown = perf != null ? perf.maxDrawdown : 0.0;
                double sharpeRatio = perf != null ? perf.sharpeRatio : 0.0;
                double profitFactor = perf != null ? perf.profitFactor : 0.0;
                double expectancy = perf != null ? perf.expectancy : 0.0;
                String wrColor = winRate >= 70 ? "#22c55e" : winRate >= 50 ? "#eab308" : "#ef4444";
                sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:12px;margin-bottom:20px;'>");
                // Current Capital (NEW - Professional metric)
                String capColor = currentCapital >= 10000 ? "#22c55e" : "#ef4444";
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(capColor).append(";'>$").append(String.format("%.0f", currentCapital)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>💰 Capital</div></div>");
                // Net Profit (NEW - Professional metric)
                String netPLColor = totalPL >= 0 ? "#22c55e" : "#ef4444";
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(netPLColor).append(";'>$").append(String.format("%+.0f", totalPL)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>📈 Net Profit</div></div>");
                // Expectancy (NEW - Professional metric)
                String expColor = expectancy >= 0.5 ? "#22c55e" : expectancy >= 0 ? "#eab308" : "#ef4444";
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(expColor).append(";'>").append(String.format("%.2f%%", expectancy)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>🎯 Expectancy</div></div>");
                // Max Drawdown (NEW - Professional metric)
                String ddColor = maxDrawdown <= 10 ? "#22c55e" : maxDrawdown <= 20 ? "#eab308" : "#ef4444";
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(ddColor).append(";'>").append(String.format("%.1f%%", maxDrawdown)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>📉 Max DD</div></div>");
                // Sharpe Ratio (NEW - Professional metric)
                String sharpeColor = sharpeRatio >= 1.5 ? "#22c55e" : sharpeRatio >= 1.0 ? "#eab308" : "#9ca3af";
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(sharpeColor).append(";'>").append(String.format("%.2f", sharpeRatio)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>⚡ Sharpe</div></div>");
                // Profit Factor (NEW - Professional metric)
                String pfColor = profitFactor >= 2.0 ? "#22c55e" : profitFactor >= 1.5 ? "#eab308" : profitFactor >= 1.0 ? "#f97316" : "#ef4444";
                String pfDisplay = profitFactor >= 100 ? "∞" : String.format("%.2f", profitFactor);
                sb.append("<div style='background:#0f172a;border:1px solid #3b82f6;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(pfColor).append(";'>").append(pfDisplay).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>⚖️ Profit Factor</div></div>");
                // Net Profit (replaced Win Rate)
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:").append(netPLColor).append(";'>$").append(String.format("%+.0f", totalPL)).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>Net Profit</div></div>");
                // Total trades
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:#a78bfa;'>").append(totalTrades).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>Trades</div></div>");
                // Open positions
                int openPosCount = (int) allTrades.stream().filter(t -> "OPEN".equals(t.status)).count();
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:12px;text-align:center;'>");
                sb.append("<div style='font-size:22px;font-weight:700;color:#facc15;'>").append(openPosCount).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:2px;'>🕐 Open</div></div>");
                sb.append("</div>");

                // ── Win/Loss breakdown bar ──
                if (totalTrades > 0) {
                    double winBarPct  = winRate;
                    double lossBarPct = 100.0 - winRate;
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:14px;margin-bottom:20px;'>");
                    sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;'>");
                    sb.append("<span style='font-weight:600;color:#d1d5db;font-size:13px;'>📊 Win / Loss Breakdown</span>");
                    sb.append("<span style='color:#9ca3af;font-size:12px;'>").append(wins).append(" wins &nbsp;/&nbsp; ").append(losses).append(" losses</span>");
                    sb.append("</div>");
                    // Progress bar
                    sb.append("<div style='display:flex;height:18px;border-radius:6px;overflow:hidden;margin-bottom:10px;'>");
                    if (winBarPct > 0) sb.append("<div style='background:#22c55e;width:").append(String.format("%.1f", winBarPct)).append("%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#fff;'>").append(String.format("%.0f%%", winBarPct)).append("</div>");
                    if (lossBarPct > 0) sb.append("<div style='background:#ef4444;width:").append(String.format("%.1f", lossBarPct)).append("%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;color:#fff;'>").append(String.format("%.0f%%", lossBarPct)).append("</div>");
                    sb.append("</div>");
                    // Avg P/L per win vs loss
                    sb.append("<div style='display:flex;gap:20px;font-size:12px;'>");
                    sb.append("<span>✅ Avg win: <b style='color:#22c55e;'>").append(avgWinPL >= 0 ? "+" : "").append(String.format("$%.2f", avgWinPL)).append("</b></span>");
                    sb.append("<span>❌ Avg loss: <b style='color:#ef4444;'>").append(String.format("$%.2f", avgLossPL)).append("</b></span>");
                    if (avgLossPL < 0 && avgWinPL > 0) {
                        double ratio = avgWinPL / Math.abs(avgLossPL);
                        String rColor = ratio >= 1.5 ? "#22c55e" : ratio >= 1.0 ? "#eab308" : "#ef4444";
                        sb.append("<span>⚖️ Profit factor: <b style='color:").append(rColor).append(";'>").append(String.format("%.2fx", ratio)).append("</b></span>");
                    }
                    sb.append("</div></div>");
                }

                // ── No-trades notice ──
                if (allTrades.isEmpty()) {
                    sb.append("<div style='background:#0b1220;border:1px solid #374151;border-radius:8px;padding:14px;margin-bottom:20px;'>");
                    sb.append("<div style='font-weight:600;color:#f59e0b;margin-bottom:8px;'>\u23F3 No Trades Executed Yet</div>");
                    sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:10px;'>This agent has not opened any positions. It may have strict entry criteria or may not have been included in a recent scan.</div>");
                    if (cfg != null && cfg.entryFilters != null && !cfg.entryFilters.isEmpty()) {
                        sb.append("<div style='color:#d1d5db;font-size:12px;font-weight:600;margin-bottom:6px;'>Entry Filters:</div>");
                        sb.append("<div style='display:flex;flex-wrap:wrap;gap:6px;'>");
                        for (java.util.Map.Entry<String, Object> ef : cfg.entryFilters.entrySet()) {
                            sb.append("<span style='background:#1e1b4b;border:1px solid #4b5563;border-radius:4px;padding:2px 8px;color:#d1d5db;font-size:11px;'>");
                            sb.append(escapeHtml(ef.getKey())).append(": ").append(escapeHtml(String.valueOf(ef.getValue())));
                            sb.append("</span>");
                        }
                        sb.append("</div>");
                    }
                    sb.append("</div>");
                }

                // ── Config summary ──
                if (cfg != null) {
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:14px;margin-bottom:20px;'>");
                    sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:8px;'>⚙️ Strategy Configuration</div>");
                    sb.append("<div style='display:flex;flex-wrap:wrap;gap:12px;font-size:13px;color:#d1d5db;'>");
                    sb.append("<span><b>Type:</b> ").append(escapeHtml(cfg.strategyType != null ? cfg.strategyType : cfg.type != null ? cfg.type : "N/A")).append("</span>");
                    if (cfg.riskManagement != null) {
                        Object sl = cfg.riskManagement.get("stopLossPct");
                        Object tp = cfg.riskManagement.get("takeProfitPct");
                        Object trail = cfg.riskManagement.get("trailingStopPct");
                        if (sl != null) sb.append("<span><b>SL:</b> ").append(String.format("%.1f%%", ((Number)sl).doubleValue())).append("</span>");
                        if (tp != null) sb.append("<span><b>TP:</b> ").append(String.format("%.1f%%", ((Number)tp).doubleValue())).append(" (min $25 profit)</span>");
                        if (trail != null) sb.append("<span><b>Trailing:</b> ").append(String.format("%.1f%%", ((Number)trail).doubleValue())).append("</span>");
                    }
                    if (cfg.locked) sb.append("<span style='color:#f59e0b;'>🔒 Locked</span>");
                    if (cfg.masterStrategy) sb.append("<span style='color:#818cf8;'>⭐ Master Strategy</span>");
                    sb.append("</div></div>");
                }

                // ── Active positions ──
                List<AIToolAgent.Trade> openTrades = new ArrayList<>();
                List<AIToolAgent.Trade> closedTrades = new ArrayList<>();
                for (AIToolAgent.Trade t : allTrades) {
                    if ("OPEN".equals(t.status)) openTrades.add(t);
                    else closedTrades.add(t);
                }
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:14px;margin-bottom:20px;'>");
                sb.append("<div style='font-weight:600;color:#a78bfa;margin-bottom:10px;'>📈 Active Positions (").append(openTrades.size()).append(")</div>");
                if (openTrades.isEmpty()) {
                    sb.append("<div style='color:#6b7280;font-size:13px;'>No open positions for this agent.</div>");
                } else {
                    // Get maxHoldDays from agent config (default 7)
                    int maxHoldDays = 7;
                    if (cfg != null && cfg.riskManagement != null && cfg.riskManagement.containsKey("maxHoldDays")) {
                        try { maxHoldDays = (int)((Number) cfg.riskManagement.get("maxHoldDays")).doubleValue(); } catch (Exception ignored) {}
                    }
                    final int maxHold = maxHoldDays;
                    // Check if any position is overdue
                    boolean hasExpired = openTrades.stream().anyMatch(t -> {
                        try {
                            ZonedDateTime e = ZonedDateTime.parse(t.entryTime);
                            int d = (int) java.time.temporal.ChronoUnit.DAYS.between(
                                e.toLocalDate(), LocalDate.now(ZoneId.of("America/New_York")));
                            return d >= maxHold;
                        } catch (Exception ignored) { return false; }
                    });
                    sb.append("<div style='display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:10px;'>");
                    sb.append("<div style='color:#9ca3af;font-size:11px;'>")
                      .append("⏱ Monitored every 60 min &nbsp;|&nbsp; ")
                      .append("📅 Max hold: <b style='color:#a78bfa;'>").append(maxHold).append(" days</b>")
                      .append("</div>");
                    if (hasExpired) {
                        sb.append("<form method='POST' action='/close-expired' style='margin:0;'>")
                          .append("<input type='hidden' name='agentId' value='").append(escapeHtml(agentId)).append("'/>")
                          .append("<button type='submit' style='background:#dc2626;color:#fff;border:none;border-radius:6px;padding:5px 14px;font-size:12px;font-weight:700;cursor:pointer;'>")
                          .append("🔴 Force Close Expired Now</button></form>");
                    }
                    sb.append("</div>");
                    sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:12px;'>");
                    sb.append("<thead><tr style='background:#0b1220;'>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Ticker</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Signal</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Entry Zone</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>🛑 Stop Loss</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>🎯 Take Profit</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Quantity</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>R:R</th>");
                    sb.append("<th style='padding:8px;text-align:center;border-bottom:1px solid #7c3aed;'>⏳ Hold</th>");
                    sb.append("<th style='padding:8px;text-align:center;border-bottom:1px solid #7c3aed;'>⏱ Valid</th>");
                    sb.append("</tr></thead><tbody>");
                    for (AIToolAgent.Trade t : openTrades) {
                        double toSLPct = t.entryPrice > 0 ? ((t.entryPrice - t.stopLoss) / t.entryPrice) * 100 : 0;
                        double toTPPct = t.entryPrice > 0 ? ((t.takeProfit - t.entryPrice) / t.entryPrice) * 100 : 0;
                        double rr = toSLPct > 0 ? toTPPct / toSLPct : 0;
                        // Days held since entry
                        int daysHeld = 0;
                        if (t.entryTime != null && !t.entryTime.isBlank()) {
                            try {
                                ZonedDateTime entryDt = ZonedDateTime.parse(t.entryTime);
                                daysHeld = (int) java.time.temporal.ChronoUnit.DAYS.between(
                                    entryDt.toLocalDate(), LocalDate.now(ZoneId.of("America/New_York")));
                            } catch (Exception ignored) {}
                        }
                        int daysLeft = Math.max(0, maxHold - daysHeld);
                        double holdFraction = maxHold > 0 ? Math.min(1.0, (double) daysHeld / maxHold) : 0;
                        int barPct = (int)(holdFraction * 100);
                        String holdColor = holdFraction >= 0.8 ? "#ef4444" : holdFraction >= 0.5 ? "#f59e0b" : "#22c55e";
                        String rowBg = "#2d2a5e";
                        String dateOnly = t.entryTime != null ? t.entryTime.substring(0, Math.min(10, t.entryTime.length())) : "";
                        // Build signal badge
                        StringBuilder sigHtml = new StringBuilder();
                        if (t.entryScore > 0) {
                            boolean hasCf = t.entryConfluenceCount >= 2;
                            String cfTag = hasCf ? " <span style='color:#f59e0b;font-size:10px;'>🔥 CONFLUENCE(+" + t.entryConfluenceCount + ")</span>" : "";
                            sigHtml.append("<div style='font-weight:700;color:#22c55e;font-size:12px;'>Score=").append(t.entryScore).append("/12").append(cfTag).append("</div>");
                            sigHtml.append("<div style='color:#9ca3af;font-size:10px;margin-top:2px;'>(V=").append(t.entryVolumeScore)
                                   .append(" T=").append(t.entryTrendScore)
                                   .append(" M=").append(t.entryMomentumScore)
                                   .append(" S=").append(t.entrySetupScore).append(")</div>");
                        } else {
                            sigHtml.append("<span style='color:#6b7280;font-size:11px;'>N/A</span>");
                        }
                        double entryZoneHigh = t.entryPrice * 1.01;
                        String setupLabel = t.setupType != null ? escapeHtml(t.setupType) : (t.strategyType != null ? escapeHtml(t.strategyType) : "");

                        sb.append("<tr style='background:").append(rowBg).append(";'>");
                        sb.append("<td style='padding:8px;font-weight:600;color:#e5e7eb;'>").append(escapeHtml(t.ticker)).append("<br><span style='color:#93c5fd;font-size:10px;font-weight:500;'>").append(setupLabel).append("</span></td>");
                        sb.append("<td style='padding:8px;text-align:left;'>").append(sigHtml.toString()).append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;color:#93c5fd;'>$").append(String.format("%.2f", t.entryPrice)).append(" – $").append(String.format("%.2f", entryZoneHigh)).append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;'>")
                          .append("<span style='color:#ef4444;font-weight:600;'>$").append(String.format("%.2f", t.stopLoss)).append("</span>")
                          .append("<br><span style='color:#ef4444;font-size:10px;'>need -").append(String.format("%.1f%%", toSLPct)).append("</span>")
                          .append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;'>")
                          .append("<span style='color:#22c55e;font-weight:600;'>$").append(String.format("%.2f", t.takeProfit)).append("</span>")
                          .append("<br><span style='color:#22c55e;font-size:10px;'>need +").append(String.format("%.1f%%", toTPPct)).append("</span>")
                          .append("</td>");
                        // Calculate quantity based on $1K position size for alignment across agents
                        int shares = (int) (1000.0 / t.entryPrice);
                        double positionValue = shares * t.entryPrice;
                        sb.append("<td style='padding:8px;text-align:right;color:#fbbf24;font-weight:700;'>")
                          .append(shares).append("<br><span style='color:#9ca3af;font-size:10px;'>$").append(String.format("%.0f", positionValue)).append("</span></td>");
                        sb.append("<td style='padding:8px;text-align:right;color:#a78bfa;font-weight:700;'>1:")
                          .append(String.format("%.1f", rr)).append("</td>");
                        sb.append("<td style='padding:8px;text-align:center;'>")
                          .append("<div style='background:#1e1b4b;border-radius:4px;height:5px;width:72px;margin:0 auto 4px;'>")
                          .append("<div style='background:").append(holdColor).append(";width:").append(barPct).append("%;height:5px;border-radius:4px;'></div></div>")
                          .append("<span style='color:").append(holdColor).append(";font-size:11px;font-weight:600;'>day ").append(daysHeld).append("/").append(maxHold).append("</span>");
                        if (daysLeft <= 2) {
                            sb.append("<br><span style='color:#f59e0b;font-size:10px;'>⚠️ ").append(daysLeft).append("d left</span>");
                        }
                        sb.append("</td>");
                        sb.append("<td style='padding:8px;text-align:center;color:#9ca3af;font-size:11px;'>⏱ 10 min</td>");
                        sb.append("</tr>");
                    }
                    sb.append("</tbody></table></div>");
                }
                sb.append("</div>");

                // ── Trade history ──
                sb.append("<div style='background:#1e1b4b;border:1px solid #7c3aed;border-radius:8px;padding:14px;'>");
                sb.append("<div style='font-weight:600;color:#a78bfa;margin-bottom:10px;'>📚 Trade History (").append(closedTrades.size()).append(" closed)</div>");
                if (closedTrades.isEmpty()) {
                    sb.append("<div style='color:#6b7280;font-size:13px;'>No closed trades yet.</div>");
                } else {
                    sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:12px;'>");
                    sb.append("<thead><tr style='background:#0b1220;'>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>#</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Ticker</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Signal</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Result</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Entry $</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Exit $</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>Quantity</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>P/L $</th>");
                    sb.append("<th style='padding:8px;text-align:right;border-bottom:1px solid #7c3aed;'>P/L %</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Close Reason</th>");
                    sb.append("<th style='padding:8px;text-align:left;border-bottom:1px solid #7c3aed;'>Date</th>");
                    sb.append("</tr></thead><tbody>");
                    int row = 0;
                    for (AIToolAgent.Trade t : closedTrades) {
                        row++;
                        boolean isWin = "CLOSED_WIN".equals(t.status);
                        String rowBg = row % 2 == 0 ? "#24215a" : "#2d2a5e";
                        String statusColor = isWin ? "#22c55e" : "#ef4444";
                        String statusIcon = isWin ? "✅" : "❌";
                        String plColor2 = t.profitLoss >= 0 ? "#22c55e" : "#ef4444";
                        StringBuilder sigHtml2 = new StringBuilder();
                        if (t.entryScore > 0) {
                            boolean hasCf2 = t.entryConfluenceCount >= 2;
                            String cfTag2 = hasCf2 ? " <span style='color:#f59e0b;font-size:10px;'>🔥 CONFLUENCE(+" + t.entryConfluenceCount + ")</span>" : "";
                            sigHtml2.append("<div style='font-weight:700;color:#22c55e;font-size:12px;'>Score=").append(t.entryScore).append("/12").append(cfTag2).append("</div>");
                            sigHtml2.append("<div style='color:#9ca3af;font-size:10px;margin-top:2px;'>(V=").append(t.entryVolumeScore)
                                   .append(" T=").append(t.entryTrendScore)
                                   .append(" M=").append(t.entryMomentumScore)
                                   .append(" S=").append(t.entrySetupScore).append(")</div>");
                        } else {
                            sigHtml2.append("<span style='color:#6b7280;font-size:11px;'>N/A</span>");
                        }

                        sb.append("<tr style='background:").append(rowBg).append(";'>");
                        sb.append("<td style='padding:8px;color:#6b7280;'>").append(row).append("</td>");
                        sb.append("<td style='padding:8px;font-weight:600;color:#e5e7eb;'>").append(escapeHtml(t.ticker)).append("</td>");
                        sb.append("<td style='padding:8px;text-align:left;'>").append(sigHtml2.toString()).append("</td>");
                        sb.append("<td style='padding:8px;color:").append(statusColor).append(";font-weight:600;'>").append(statusIcon).append(" ").append(isWin ? "WIN" : "LOSS").append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;color:#93c5fd;'>$").append(String.format("%.2f", t.entryPrice)).append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;color:#d1d5db;'>$").append(String.format("%.2f", t.exitPrice)).append("</td>");
                        // Calculate quantity based on $1K position size for alignment across agents
                        int sharesClosed = (int) (1000.0 / t.entryPrice);
                        double positionValueClosed = sharesClosed * t.entryPrice;
                        sb.append("<td style='padding:8px;text-align:right;color:#fbbf24;font-weight:700;'>")
                          .append(sharesClosed).append("<br><span style='color:#9ca3af;font-size:10px;'>$").append(String.format("%.0f", positionValueClosed)).append("</span></td>");
                        // Calculate P/L $ based on $1K position size
                        double plPerShare = (t.exitPrice - t.entryPrice);
                        double plDollars = plPerShare * sharesClosed;
                        sb.append("<td style='padding:8px;text-align:right;color:").append(plColor2).append(";font-weight:600;'>$").append(String.format("%+.2f", plDollars)).append("</td>");
                        sb.append("<td style='padding:8px;text-align:right;color:").append(plColor2).append(";'>").append(String.format("%+.2f%%", t.profitLossPct)).append("</td>");
                        String reason = t.closeReason != null ? t.closeReason : t.status;
                        sb.append("<td style='padding:8px;color:#9ca3af;font-size:11px;'>").append(escapeHtml(reason)).append("</td>");
                        String dateStr = t.exitTime != null ? t.exitTime.substring(0, Math.min(10, t.exitTime.length())) : (t.entryTime != null ? t.entryTime.substring(0, Math.min(10, t.entryTime.length())) : "");
                        sb.append("<td style='padding:8px;color:#9ca3af;font-size:11px;'>").append(escapeHtml(dateStr)).append("</td>");
                        sb.append("</tr>");
                    }
                    sb.append("</tbody></table></div>");
                }
                sb.append("</div>");
                sb.append("</div>"); // max-width container

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // Force-close expired positions (called from agent-detail page button)
        server.createContext("/close-expired", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close(); return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();

                // Run synchronously — browser waits until all trades are closed & saved to disk.
                // This guarantees persistence even if the server restarts immediately after.
                try { AIToolAgent.closeExpiredPositionsNow(agentId); }
                catch (Exception e) { System.err.println("[close-expired] " + e.getMessage()); }

                String redirect = agentId.isEmpty() ? "/aitool"
                    : "/agent-detail?id=" + java.net.URLEncoder.encode(agentId, "UTF-8");
                ex.getResponseHeaders().add("Location", redirect);
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Save AITool agent configuration
        server.createContext("/aitool-save-config", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();
                
                if (agentId.isEmpty()) {
                    ScoringConfig.setActiveAgentConfig(null);
                } else {
                    ScoringConfig.setActiveAgentConfig(agentId);
                }
                
                ex.getResponseHeaders().add("Location", "/aitool?saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Delete tracker endpoint
        server.createContext("/aitool-delete-tracker", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();
                
                if (!agentId.isEmpty()) {
                    ScoringConfig.deleteTracker(agentId);
                    // Also clear active config if it was the deleted one
                    String activeConfig = ScoringConfig.getActiveAgentConfig();
                    if (agentId.equals(activeConfig)) {
                        ScoringConfig.setActiveAgentConfig(null);
                    }
                }
                
                ex.getResponseHeaders().add("Location", "/aitool?deleted=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Clear all trackers endpoint
        server.createContext("/aitool-clear-trackers", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                Map<String, ScoringConfig.SavedAgentTracker> trackers = ScoringConfig.getSavedAgentTrackers();
                if (trackers != null) {
                    for (String agentId : new ArrayList<>(trackers.keySet())) {
                        ScoringConfig.deleteTracker(agentId);
                    }
                }
                ScoringConfig.setActiveAgentConfig(null);
                ex.getResponseHeaders().add("Location", "/aitool?cleared=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Track agent endpoint - saves agent with cumulative tracking
        server.createContext("/aitool-track-agent", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();
                
                if (!agentId.isEmpty()) {
                    // Get agent info
                    AIToolAgent.AgentConfig agentCfg = AIToolAgent.getAgentConfig(agentId);
                    AIToolAgent.AgentPerformance perf = null;
                    for (AIToolAgent.AgentPerformance p : AIToolAgent.getAllPerformances()) {
                        if (agentId.equals(p.agentId)) {
                            perf = p;
                            break;
                        }
                    }
                    
                    String agentName = agentCfg != null && agentCfg.name != null ? agentCfg.name : agentId;
                    String type = agentCfg != null && agentCfg.type != null ? agentCfg.type : "UNKNOWN";
                    
                    // Create or get tracker
                    ScoringConfig.getOrCreateTracker(agentId, agentName, type);
                    
                    // Update with current stats
                    if (perf != null) {
                        ScoringConfig.updateTrackerWithDailyStats(agentId, perf.wins, perf.totalTrades, perf.totalProfitLoss);
                    }
                    
                    // Also set as active config
                    ScoringConfig.setActiveAgentConfig(agentId);
                }
                
                ex.getResponseHeaders().add("Location", "/aitool?tracked=true#top-agents");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Toggle lock/unlock for an agent (protect from evolution)
        server.createContext("/aitool-toggle-lock", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();
                
                if (!agentId.isEmpty()) {
                    if (AIToolAgent.isAgentLocked(agentId)) {
                        AIToolAgent.unlockAgent(agentId);
                    } else {
                        AIToolAgent.lockAgent(agentId);
                    }
                }
                
                ex.getResponseHeaders().add("Location", "/aitool?lockToggled=true#top-agents");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Toggle trade notification for an agent
        server.createContext("/aitool-toggle-notify", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/aitool");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String agentId = form.getOrDefault("agentId", "").trim();
                
                if (!agentId.isEmpty()) {
                    ScoringConfig.toggleTradeNotification(agentId);
                }
                
                ex.getResponseHeaders().add("Location", "/aitool?notifyToggled=true#top-agents");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // API endpoint for external triggers (Discord, curl, etc.)
        // Usage: GET or POST to /api/aitool/run
        // Example: curl http://localhost:8080/api/aitool/run
        server.createContext("/api/aitool/run", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                
                // Start async run
                AIToolAgent.runAgentsAsync();
                
                // Send Discord notification that run was triggered
                String discordMsg = "🚀 **AITool Agents Started!**\nTriggered via API endpoint at " + 
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                AIToolAgent.sendDiscordPublic(discordMsg);
                
                String json = "{\"status\":\"success\",\"message\":\"AITool agents started\",\"timestamp\":\"" + 
                    java.time.Instant.now().toString() + "\"}";
                byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, resp.length);
                ex.getResponseBody().write(resp);
                ex.close();
            }
        });

        // API endpoint to reload agent configurations from JSON files
        // Usage: GET or POST to /api/aitool/reload-agents
        server.createContext("/api/aitool/reload-agents", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                
                try {
                    // Reload all agent configurations from JSON files
                    AIToolAgent.loadAllAgents();
                    
                    String json = "{\"status\":\"success\",\"message\":\"Agent configurations reloaded\",\"timestamp\":\"" + 
                        java.time.Instant.now().toString() + "\"}";
                    byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                } catch (Exception e) {
                    String json = "{\"status\":\"error\",\"message\":\"Failed to reload agents: " + e.getMessage() + "\"}";
                    byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, resp.length);
                    ex.getResponseBody().write(resp);
                }
                ex.close();
            }
        });

        // ---------------- Settings Page (Scoring Configuration) ----------------
        server.createContext("/settings", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                ScoringConfig.ConfigData config = ScoringConfig.load();
                String activeMode = config.activeMode;
                ScoringConfig.ModeConfig activeCfg = ScoringConfig.getActiveModeConfig();

                StringBuilder sb = new StringBuilder();
                if ("true".equals(qp.get("investment_saved"))) {
                    sb.append("<div style='background:#064e3b;border:1px solid #22c55e;border-radius:8px;padding:12px 16px;margin-bottom:16px;color:#22c55e;font-weight:600;'>✅ Investment per stock saved!</div>");
                } else if ("true".equals(qp.get("saved"))) {
                    sb.append("<div style='background:#064e3b;border:1px solid #22c55e;border-radius:8px;padding:12px 16px;margin-bottom:16px;color:#22c55e;font-weight:600;'>✅ Mode saved!</div>");
                } else if ("true".equals(qp.get("agent_saved"))) {
                    sb.append("<div style='background:#064e3b;border:1px solid #22c55e;border-radius:8px;padding:12px 16px;margin-bottom:16px;color:#22c55e;font-weight:600;'>✅ Agent config saved!</div>");
                } else if ("true".equals(qp.get("monitor_saved"))) {
                    sb.append("<div style='background:#064e3b;border:1px solid #22c55e;border-radius:8px;padding:12px 16px;margin-bottom:16px;color:#22c55e;font-weight:600;'>✅ Discord monitor saved!</div>");
                } else if ("true".equals(qp.get("sector_saved"))) {
                    sb.append("<div style='background:#064e3b;border:1px solid #22c55e;border-radius:8px;padding:12px 16px;margin-bottom:16px;color:#22c55e;font-weight:600;'>✅ Sector allocation saved!</div>");
                }
                sb.append("<div class='card'><div class='title'>⚙️ Scoring Settings | הגדרות ציון</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>Configure how stocks are scored. Choose a preset or customize weights.</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>הגדר איך מניות מקבלות ציון. בחר פריסט או התאם משקלים.</div>");

                // Per-page settings notice
                sb.append("<div style='background:#0d1b30;border:1px solid #1e3a5f;border-radius:10px;padding:14px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#93c5fd;margin-bottom:8px;'>💡 Settings are now also available directly on each agent page:</div>");
                sb.append("<div style='display:flex;gap:10px;flex-wrap:wrap;'>");
                sb.append("</div>");
                sb.append("<div style='font-size:11px;color:#6b7280;margin-top:8px;'>Each page lets you set the strategy mode and agent config inline, without leaving the page. Changes here apply globally (same underlying config).</div>");
                sb.append("</div>");

                // Current mode display
                String modeName = activeCfg.name != null ? activeCfg.name : activeMode;
                String modeNameHe = activeCfg.nameHe != null ? activeCfg.nameHe : "";
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;color:#22c55e;'>Current Mode: ").append(escapeHtml(modeName));
                if (!modeNameHe.isEmpty()) sb.append(" | ").append(escapeHtml(modeNameHe));
                sb.append("</div>");
                if (activeCfg.description != null) {
                    sb.append("<div style='color:#9ca3af;font-size:13px;margin-top:4px;'>").append(escapeHtml(activeCfg.description)).append("</div>");
                }
                sb.append("</div>");

                // Mode selection form
                sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:10px;color:#93c5fd;'>Strategy for Favorites / Daily GREEN | אסטרטגיה ל- Favorites (GREEN)</div>");
                sb.append("<form method='post' action='/settings-mode' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:0;'>");
                sb.append("<select name='mode' style='padding:10px 12px;border-radius:8px;border:1px solid #1f2a44;background:#1f2a44;color:#e5e7eb;min-width:320px;'>");
                sb.append("<option value='LONG_TERM_INVESTOR'").append("LONG_TERM_INVESTOR".equals(activeMode) ? " selected" : "").append(">Long-Term Investor | משקיע לטווח ארוך</option>");
                sb.append("<option value='SWING_TRADER'").append("SWING_TRADER".equals(activeMode) ? " selected" : "").append(">Swing Trader | סווינג טריידר (1-5 ימים)</option>");
                sb.append("<option value='CUSTOM'").append("CUSTOM".equals(activeMode) ? " selected" : "").append(">Custom | מותאם אישית</option>");
                sb.append("</select>");
                sb.append("<button type='submit' style='background:#22c55e;color:#000;border:none;'>Save</button>");
                sb.append("<div style='color:#9ca3af;font-size:12px;'>Used by: Favorites → Daily Recommendations (GREEN)</div>");
                sb.append("</form>");
                sb.append("</div>");

                // Mode selection form
                sb.append("<form method='post' action='/settings-mode' style='margin-bottom:20px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:8px;'>Select Trading Mode | בחר מצב מסחר:</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:10px;'>");

                for (String modeKey : new String[]{"LONG_TERM_INVESTOR", "SWING_TRADER", "CUSTOM"}) {
                    ScoringConfig.ModeConfig m = config.presets.get(modeKey);
                    if (m == null) continue;
                    String name = m.name != null ? m.name : modeKey;
                    String nameHe = m.nameHe != null ? m.nameHe : "";
                    boolean selected = modeKey.equals(activeMode);
                    String bg = selected ? "#22c55e" : "#1f2a44";
                    String color = selected ? "#000" : "#e5e7eb";
                    sb.append("<button type='submit' name='mode' value='").append(modeKey).append("' ");
                    sb.append("style='padding:12px 20px;border-radius:8px;background:").append(bg).append(";color:").append(color).append(";border:none;cursor:pointer;'>");
                    sb.append("<div style='font-weight:600;'>").append(escapeHtml(name)).append("</div>");
                    if (!nameHe.isEmpty()) sb.append("<div style='font-size:12px;opacity:0.8;'>").append(escapeHtml(nameHe)).append("</div>");
                    sb.append("</button>");
                }
                sb.append("</div></form>");

                // AITool Agent Configuration Selection
                sb.append("<div style='background:#0b1220;border:2px solid #8b5cf6;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:10px;color:#8b5cf6;'>🤖 AITool Agent Configuration | קונפיגורציה מ-AITool</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>Use a winning agent's configuration from AITool for Daily Recommendations (GREEN).<br/>השתמש בקונפיגורציה של סוכן מנצח מ-AITool עבור המלצות יומיות.</div>");
                
                // Get current selected agent
                String currentAgentConfig = ScoringConfig.getActiveAgentConfig();
                
                sb.append("<form method='post' action='/settings-agent-config' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;'>");
                sb.append("<input type='text' name='agentId' placeholder='Agent ID (e.g., M2_CONSERVATIVE_V2)' ");
                sb.append("value='").append(escapeHtml(currentAgentConfig != null ? currentAgentConfig : "")).append("' ");
                sb.append("style='padding:10px 12px;border-radius:8px;border:1px solid #1f2a44;background:#1f2a44;color:#e5e7eb;min-width:280px;' />");
                sb.append("<button type='submit' style='background:#8b5cf6;color:#fff;border:none;padding:10px 20px;border-radius:8px;'>Apply Agent Config</button>");
                sb.append("<button type='submit' name='clear' value='true' style='background:#ef4444;color:#fff;border:none;padding:10px 20px;border-radius:8px;'>Clear</button>");
                sb.append("</form>");
                
                // Show current agent config status
                if (currentAgentConfig != null && !currentAgentConfig.isBlank()) {
                    AIToolAgent.AgentConfig agentCfg = AIToolAgent.getAgentConfig(currentAgentConfig);
                    if (agentCfg != null) {
                        sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #22c55e;'>");
                        sb.append("<div style='color:#22c55e;font-weight:600;'>✅ Active: ").append(escapeHtml(agentCfg.name != null ? agentCfg.name : agentCfg.id)).append("</div>");
                        sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:4px;'>Type: ").append(escapeHtml(agentCfg.type != null ? agentCfg.type : "")).append(" | Generation: ").append(agentCfg.generation).append("</div>");
                        if (agentCfg.entryFilters != null) {
                            sb.append("<div style='color:#9ca3af;font-size:11px;margin-top:4px;'>Filters: ");
                            sb.append("RSI ").append(String.format("%.0f-%.0f", agentCfg.entryFilters.getOrDefault("rsiMin", 30.0), agentCfg.entryFilters.getOrDefault("rsiMax", 70.0)));
                            sb.append(" | RS > ").append(String.format("%.2f", agentCfg.entryFilters.getOrDefault("rsMin", 1.0)));
                            sb.append(" | RVOL > ").append(String.format("%.2f", agentCfg.entryFilters.getOrDefault("rvolMin", 1.0)));
                            sb.append("</div>");
                        }
                        sb.append("</div>");
                    } else {
                        sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #ef4444;'>");
                        sb.append("<div style='color:#ef4444;'>⚠️ Agent '").append(escapeHtml(currentAgentConfig)).append("' not found. Check the ID.</div>");
                        sb.append("</div>");
                    }
                }
                
                // List available agents
                List<AIToolAgent.AgentPerformance> agentPerfs = AIToolAgent.getAllPerformances();
                if (agentPerfs != null && !agentPerfs.isEmpty()) {
                    // Sort by professional metrics: expectancy (primary), then profit factor, then sharpe ratio
                    agentPerfs.sort((a, b) -> {
                        // Primary: Expectancy (the REAL metric)
                        int expCompare = Double.compare(b.expectancy, a.expectancy);
                        if (expCompare != 0) return expCompare;
                        // Secondary: Profit Factor (risk/reward ratio)
                        int pfCompare = Double.compare(b.profitFactor, a.profitFactor);
                        if (pfCompare != 0) return pfCompare;
                        // Tertiary: Sharpe Ratio (risk-adjusted returns)
                        return Double.compare(b.sharpeRatio, a.sharpeRatio);
                    });
                    sb.append("<div style='margin-top:12px;'>");
                    sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:6px;'>Top performing agents (click to copy ID):</div>");
                    sb.append("<div style='display:flex;flex-wrap:wrap;gap:6px;'>");
                    int shown = 0;
                    for (AIToolAgent.AgentPerformance p : agentPerfs) {
                        if (shown >= 5) break;
                        if (p.totalTrades < 3) continue;
                        String winColor = p.winRate >= 50 ? "#22c55e" : "#ef4444";
                        sb.append("<span onclick=\"document.querySelector('input[name=agentId]').value='").append(escapeHtml(p.agentId)).append("'\" ");
                        sb.append("style='cursor:pointer;padding:4px 8px;background:#1f2a44;border-radius:4px;font-size:11px;'>");
                        sb.append("<span style='color:").append(winColor).append(";'>").append(String.format("%.0f%%", p.winRate)).append("</span> ");
                        sb.append(escapeHtml(p.agentId));
                        sb.append("</span>");
                        shown++;
                    }
                    sb.append("</div></div>");
                }
                sb.append("</div>");

                // Discord Agent Monitor Section
                sb.append("<div style='background:#0b1220;border:2px solid #5865F2;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:10px;color:#5865F2;'>🔔 Discord Agent Monitor | מעקב סוכן בדיסקורד</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>Get Discord notifications for EVERY buy/sell activity of a specific agent.<br/>קבל התראות דיסקורד על כל פעילות קנייה/מכירה של סוכן מסוים.</div>");
                
                // Get current monitored agent
                String monitoredAgent = ScoringConfig.getMonitoredAgentForDiscord();
                
                sb.append("<form method='post' action='/settings-monitor-agent' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;'>");
                sb.append("<input type='text' name='agentId' placeholder='Agent ID (e.g., S10_SWING_TIGHT_RANGE_GEN1)' ");
                sb.append("value='").append(escapeHtml(monitoredAgent != null ? monitoredAgent : "")).append("' ");
                sb.append("style='padding:10px 12px;border-radius:8px;border:1px solid #1f2a44;background:#1f2a44;color:#e5e7eb;min-width:320px;' />");
                sb.append("<button type='submit' style='background:#5865F2;color:#fff;border:none;padding:10px 20px;border-radius:8px;'>🔔 Apply Monitor</button>");
                sb.append("<button type='submit' name='clear' value='true' style='background:#ef4444;color:#fff;border:none;padding:10px 20px;border-radius:8px;'>Clear</button>");
                sb.append("</form>");
                
                // Show current monitored agent status
                if (monitoredAgent != null && !monitoredAgent.isBlank()) {
                    AIToolAgent.AgentConfig agentCfg = AIToolAgent.getAgentConfig(monitoredAgent);
                    if (agentCfg != null) {
                        AIToolAgent.AgentPerformance perf = AIToolAgent.getAgentPerformance(monitoredAgent);
                        sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #5865F2;'>");
                        sb.append("<div style='color:#5865F2;font-weight:600;'>🔔 Monitoring: ").append(escapeHtml(agentCfg.name != null ? agentCfg.name : agentCfg.id)).append("</div>");
                        sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:4px;'>Type: ").append(escapeHtml(agentCfg.type != null ? agentCfg.type : "")).append(" | Generation: ").append(agentCfg.generation).append("</div>");
                        if (perf != null) {
                            String winColor = perf.winRate >= 50 ? "#22c55e" : "#ef4444";
                            sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:4px;'>Performance: <span style='color:").append(winColor).append(";font-weight:600;'>").append(String.format("%.1f%%", perf.winRate)).append("</span> win rate | ").append(perf.wins).append("/").append(perf.totalTrades).append(" trades</div>");
                        }
                        sb.append("<div style='color:#22c55e;font-size:11px;margin-top:6px;'>✅ You will receive Discord notifications for all trades by this agent</div>");
                        sb.append("</div>");
                    } else {
                        sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #ef4444;'>");
                        sb.append("<div style='color:#ef4444;'>⚠️ Agent '").append(escapeHtml(monitoredAgent)).append("' not found. Check the ID.</div>");
                        sb.append("</div>");
                    }
                } else {
                    sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #9ca3af;'>");
                    sb.append("<div style='color:#9ca3af;'>No agent currently monitored. Enter an agent ID above to start receiving Discord notifications.</div>");
                    sb.append("</div>");
                }
                sb.append("</div>");

                // ===================== SECTOR ALLOCATION SECTION =====================
                sb.append("<div style='background:#0b1220;border:2px solid #8b5cf6;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:10px;color:#8b5cf6;'>📊 Sector Allocation | הקצאת סקטורים</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>Configure which sectors to scan and their percentage allocation. Total should be 100%.<br/>הגדר אילו סקטורים לסרוק ואת אחוז ההקצאה שלהם. הסכום צריך להיות 100%.</div>");
                
                // Get current allocation
                java.util.Map<LongTermCandidateFinder.Sector, Integer> currentAlloc = LongTermCandidateFinder.getSectorAllocation();
                
                sb.append("<form method='post' action='/settings-sector-allocation' id='sectorForm'>");
                sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;margin-bottom:16px;'>");
                
                // Sector checkboxes with percentage inputs
                String[][] sectors = {
                    {"NASDAQ_100", "📈 NASDAQ 100", "#3b82f6"},
                    {"TECHNOLOGY", "💻 Technology", "#8b5cf6"},
                    {"FINANCIALS", "🏦 Financials", "#22c55e"},
                    {"HEALTHCARE", "🏥 Healthcare", "#ef4444"},
                    {"ENERGY", "⚡ Energy", "#f59e0b"},
                    {"INDUSTRIALS", "🏭 Industrials", "#6b7280"},
                    {"CONSUMER_DISCRETIONARY", "🛍️ Consumer Disc.", "#ec4899"},
                    {"CONSUMER_STAPLES", "🛒 Consumer Staples", "#14b8a6"},
                    {"UTILITIES", "💡 Utilities", "#eab308"},
                    {"MATERIALS", "🧱 Materials", "#78716c"},
                    {"REAL_ESTATE", "🏠 Real Estate", "#0ea5e9"},
                    {"COMMUNICATION_SERVICES", "📱 Communication", "#a855f7"}
                };
                
                for (String[] sector : sectors) {
                    String sectorKey = sector[0];
                    String sectorLabel = sector[1];
                    String sectorColor = sector[2];
                    LongTermCandidateFinder.Sector sectorEnum = LongTermCandidateFinder.Sector.valueOf(sectorKey);
                    int currentPct = currentAlloc.getOrDefault(sectorEnum, 0);
                    
                    sb.append("<div style='background:#1f2a44;border-radius:8px;padding:10px;border-left:3px solid ").append(sectorColor).append(";'>");
                    sb.append("<div style='display:flex;align-items:center;justify-content:space-between;gap:8px;'>");
                    sb.append("<span style='color:").append(sectorColor).append(";font-weight:600;'>").append(sectorLabel).append("</span>");
                    sb.append("<div style='display:flex;align-items:center;gap:6px;'>");
                    sb.append("<input type='number' name='pct_").append(sectorKey).append("' value='").append(currentPct).append("' min='0' max='100' ");
                    sb.append("oninput='updateSectorTotal()' style='width:70px;padding:6px 8px;border-radius:4px;border:1px solid #374151;background:#0b1220;color:#e5e7eb;text-align:center;font-size:14px;'/>");
                    sb.append("<span style='color:#9ca3af;font-size:13px;'>%</span>");
                    sb.append("</div>");
                    sb.append("</div>");
                    sb.append("</div>");
                }
                
                sb.append("</div>");
                
                // Total and buttons
                sb.append("<div style='display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;'>");
                sb.append("<div style='background:#1f2a44;padding:10px 16px;border-radius:8px;'>");
                sb.append("<span style='color:#9ca3af;'>Total: </span>");
                sb.append("<span id='sectorTotal' style='font-weight:600;color:#22c55e;font-size:18px;'>").append(currentAlloc.values().stream().mapToInt(Integer::intValue).sum()).append("%</span>");
                sb.append("<span id='sectorWarning' style='color:#ef4444;margin-left:10px;display:none;'>⚠️ Should be 100%</span>");
                sb.append("</div>");
                sb.append("<div style='display:flex;gap:10px;'>");
                sb.append("<button type='submit' style='background:#8b5cf6;color:#fff;border:none;padding:10px 20px;border-radius:8px;cursor:pointer;'>💾 Save Allocation</button>");
                sb.append("<button type='button' onclick='resetToNasdaq()' style='background:#6b7280;color:#fff;border:none;padding:10px 20px;border-radius:8px;cursor:pointer;'>🔄 Reset to NASDAQ 100</button>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</form>");
                
                // JavaScript for sector allocation
                sb.append("<script>");
                sb.append("function updateSectorTotal() {");
                sb.append("  var total = 0;");
                sb.append("  var sectors = ['NASDAQ_100','TECHNOLOGY','FINANCIALS','HEALTHCARE','ENERGY','INDUSTRIALS','CONSUMER_DISCRETIONARY','CONSUMER_STAPLES','UTILITIES','MATERIALS','REAL_ESTATE','COMMUNICATION_SERVICES'];");
                sb.append("  sectors.forEach(function(s) {");
                sb.append("    var pct = document.querySelector('input[name=\"pct_'+s+'\"]');");
                sb.append("    if (pct) total += parseInt(pct.value) || 0;");
                sb.append("  });");
                sb.append("  document.getElementById('sectorTotal').textContent = total + '%';");
                sb.append("  var warning = document.getElementById('sectorWarning');");
                sb.append("  var totalSpan = document.getElementById('sectorTotal');");
                sb.append("  if (total === 100) { totalSpan.style.color = '#22c55e'; warning.style.display = 'none'; }");
                sb.append("  else { totalSpan.style.color = '#ef4444'; warning.style.display = 'inline'; }");
                sb.append("}");
                sb.append("function resetToNasdaq() {");
                sb.append("  var sectors = ['NASDAQ_100','TECHNOLOGY','FINANCIALS','HEALTHCARE','ENERGY','INDUSTRIALS','CONSUMER_DISCRETIONARY','CONSUMER_STAPLES','UTILITIES','MATERIALS','REAL_ESTATE','COMMUNICATION_SERVICES'];");
                sb.append("  sectors.forEach(function(s) {");
                sb.append("    var pct = document.querySelector('input[name=\"pct_'+s+'\"]');");
                sb.append("    if (s === 'NASDAQ_100') { pct.value = 100; }");
                sb.append("    else { pct.value = 0; }");
                sb.append("  });");
                sb.append("  updateSectorTotal();");
                sb.append("}");
                sb.append("updateSectorTotal();");
                sb.append("</script>");
                
                // Show current sector stats
                sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;'>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:6px;'>📊 Sector Ticker Counts:</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;font-size:11px;'>");
                sb.append("<span style='color:#8b5cf6;'>Tech: ").append(LongTermCandidateFinder.TECHNOLOGY_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#22c55e;'>Fin: ").append(LongTermCandidateFinder.FINANCIALS_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#ef4444;'>Health: ").append(LongTermCandidateFinder.HEALTHCARE_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#f59e0b;'>Energy: ").append(LongTermCandidateFinder.ENERGY_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#6b7280;'>Indust: ").append(LongTermCandidateFinder.INDUSTRIALS_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#ec4899;'>Cons.D: ").append(LongTermCandidateFinder.CONSUMER_DISCRETIONARY_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#14b8a6;'>Cons.S: ").append(LongTermCandidateFinder.CONSUMER_STAPLES_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#eab308;'>Util: ").append(LongTermCandidateFinder.UTILITIES_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#78716c;'>Mat: ").append(LongTermCandidateFinder.MATERIALS_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#0ea5e9;'>RE: ").append(LongTermCandidateFinder.REAL_ESTATE_TICKERS.size()).append("</span>");
                sb.append("<span style='color:#a855f7;'>Comm: ").append(LongTermCandidateFinder.COMMUNICATION_SERVICES_TICKERS.size()).append("</span>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");

                // Weight visualization
                sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#93c5fd;'>Current Weights | משקלים נוכחיים:</div>");

                // Weight bars
                int fundW = activeCfg.fundamentalWeight;
                int techW = activeCfg.technicalWeight;
                int effW = activeCfg.efficiencyWeight;
                int grahamW = activeCfg.grahamBonus;

                sb.append("<div style='margin-bottom:8px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'><span>Fundamental | פונדמנטלי</span><span>").append(fundW).append("%</span></div>");
                sb.append("<div style='background:#1f2a44;border-radius:4px;height:20px;'><div style='background:#3b82f6;height:100%;border-radius:4px;width:").append(fundW).append("%;'></div></div>");
                sb.append("</div>");

                sb.append("<div style='margin-bottom:8px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'><span>Technical | טכני</span><span>").append(techW).append("%</span></div>");
                sb.append("<div style='background:#1f2a44;border-radius:4px;height:20px;'><div style='background:#22c55e;height:100%;border-radius:4px;width:").append(techW).append("%;'></div></div>");
                sb.append("</div>");

                sb.append("<div style='margin-bottom:8px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'><span>Efficiency | יעילות</span><span>").append(effW).append("%</span></div>");
                sb.append("<div style='background:#1f2a44;border-radius:4px;height:20px;'><div style='background:#f59e0b;height:100%;border-radius:4px;width:").append(effW).append("%;'></div></div>");
                sb.append("</div>");

                sb.append("<div style='margin-bottom:8px;'>");
                sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:4px;'><span>Graham Bonus | בונוס גראהם</span><span>").append(grahamW).append(" pts</span></div>");
                sb.append("<div style='background:#1f2a44;border-radius:4px;height:20px;'><div style='background:#a855f7;height:100%;border-radius:4px;width:").append(Math.min(grahamW * 10, 100)).append("%;'></div></div>");
                sb.append("</div>");

                sb.append("<div style='margin-top:12px;padding:8px;background:#1f2a44;border-radius:6px;'>");
                sb.append("<span style='color:").append(activeCfg.vetoEnabled ? "#22c55e" : "#ef4444").append(";font-weight:600;'>");
                sb.append(activeCfg.vetoEnabled ? "✓ VETO Enabled" : "✗ VETO Disabled");
                sb.append("</span>");
                sb.append("<span style='color:#9ca3af;margin-left:12px;font-size:13px;'>");
                sb.append(activeCfg.vetoEnabled ? "מניות עם סיכון הונאה/פשיטת רגל יקבלו 0" : "לא נחסמות מניות מסוכנות");
                sb.append("</span>");
                sb.append("</div>");
                sb.append("</div>");

                // Indicator details
                sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#93c5fd;'>Active Indicators | אינדיקטורים פעילים:</div>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<tr style='border-bottom:1px solid #1f2a44;'><th style='text-align:left;padding:6px;'>Indicator</th><th style='text-align:center;padding:6px;'>Enabled</th><th style='text-align:right;padding:6px;'>Points</th></tr>");

                if (activeCfg.indicators != null) {
                    for (java.util.Map.Entry<String, ScoringConfig.IndicatorConfig> e : activeCfg.indicators.entrySet()) {
                        String indName = e.getKey();
                        ScoringConfig.IndicatorConfig ind = e.getValue();
                        String enabledIcon = ind.enabled ? "✓" : "✗";
                        String enabledColor = ind.enabled ? "#22c55e" : "#ef4444";
                        sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                        sb.append("<td style='padding:6px;'>").append(escapeHtml(indName)).append("</td>");
                        sb.append("<td style='padding:6px;text-align:center;color:").append(enabledColor).append(";'>").append(enabledIcon).append("</td>");
                        sb.append("<td style='padding:6px;text-align:right;'>").append(ind.enabled ? ind.points : "-").append("</td>");
                        sb.append("</tr>");
                    }
                }
                sb.append("</table></div>");

                // Entry & Exit Rules section
                ScoringConfig.EntryFiltersConfig ef = activeCfg.entryFilters;
                if (ef == null) ef = new ScoringConfig.EntryFiltersConfig();
                
                // Gate 1: Safety (Veto)
                sb.append("<div style='margin-top:16px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#ef4444;'>🚨 שער 1: בטיחות (Veto)</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>מניות מסוכנות נחסמות אוטומטית</div>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                String mScoreIcon = ef.vetoMScoreEnabled ? "✓" : "✗";
                String mScoreColor = ef.vetoMScoreEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>M-Score (סיכון הונאה)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(mScoreColor).append(";'>").append(mScoreIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>חסום אם > ").append(String.format("%.2f", ef.vetoMScoreThreshold)).append("</td></tr>");
                String zScoreIcon = ef.vetoZScoreEnabled ? "✓" : "✗";
                String zScoreColor = ef.vetoZScoreEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>Z-Score (סיכון פשיטת רגל)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(zScoreColor).append(";'>").append(zScoreIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>חסום אם < ").append(String.format("%.1f", ef.vetoZScoreThreshold)).append("</td></tr>");
                sb.append("</table></div>");
                
                // Gate 2: Relative Strength (The Leader)
                sb.append("<div style='margin-top:12px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#f59e0b;'>💪 שער 2: כוח יחסי (RS)</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>המניה חייבת להיות חזקה מהשוק</div>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                String rsIcon = ef.rsEntryFilterEnabled ? "✓" : "✗";
                String rsColor = ef.rsEntryFilterEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>RS Entry (כניסה)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(rsColor).append(";'>").append(rsIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>RS > ").append(String.format("%.2f", ef.rsEntryThreshold)).append(" (חזק ב-").append(String.format("%.0f", (ef.rsEntryThreshold - 1) * 100)).append("%)</td></tr>");
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>RS Exit (יציאה)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(rsColor).append(";'>").append(rsIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>RS < ").append(String.format("%.2f", ef.rsExitThreshold)).append(" (חלש ב-").append(String.format("%.0f", (1 - ef.rsExitThreshold) * 100)).append("%)</td></tr>");
                sb.append("</table></div>");
                
                // Gate 3: Technical Timing
                sb.append("<div style='margin-top:12px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#3b82f6;'>📊 שער 3: תזמון טכני</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>המחיר וה-RSI חייבים לתמוך בכניסה</div>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                String smaIcon = ef.smaFilterEnabled ? "✓" : "✗";
                String smaColor = ef.smaFilterEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>SMA ").append(ef.smaPeriod).append(" (ממוצע נע)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(smaColor).append(";'>").append(smaIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>מחיר > SMA").append(ef.smaPeriod).append("</td></tr>");
                String sma200Icon = ef.sma200FilterEnabled ? "✓" : "✗";
                String sma200Color = ef.sma200FilterEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>SMA200 (מגמה ראשית)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(sma200Color).append(";'>").append(sma200Icon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>מחיר > SMA200</td></tr>");
                String rsiIcon = ef.rsiFilterEnabled ? "✓" : "✗";
                String rsiColor = ef.rsiFilterEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>RSI Range (טווח)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(rsiColor).append(";'>").append(rsiIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>").append(String.format("%.0f", ef.rsiMinThreshold)).append(" < RSI < ").append(String.format("%.0f", ef.rsiMaxThreshold)).append("</td></tr>");
                String rsiRisingIcon = ef.rsiRisingRequired ? "✓" : "✗";
                String rsiRisingColor = ef.rsiRisingRequired ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>RSI Rising (עולה)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(rsiRisingColor).append(";'>").append(rsiRisingIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>RSI במגמת עלייה</td></tr>");
                sb.append("</table></div>");
                
                // Risk Management
                sb.append("<div style='margin-top:12px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#22c55e;'>💰 ניהול סיכונים</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>הגדרות Position Sizing וסטופ-לוס</div>");
                sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>סיכון לעסקה</td>");
                sb.append("<td style='padding:6px;text-align:center;color:#22c55e;'>✓</td>");
                sb.append("<td style='padding:6px;text-align:right;'>").append(String.format("%.1f%%", ef.riskPercentPerTrade)).append(" מההון</td></tr>");
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>הון ברירת מחדל</td>");
                sb.append("<td style='padding:6px;text-align:center;color:#3b82f6;'>$</td>");
                sb.append("<td style='padding:6px;text-align:right;'>$").append(String.format("%,.0f", ef.accountEquity)).append("</td></tr>");
                String atrIcon = ef.atrStopLossEnabled ? "✓" : "✗";
                String atrColor = ef.atrStopLossEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>סטופ-לוס (ATR)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(atrColor).append(";'>").append(atrIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>").append(String.format("%.1fx", ef.atrStopLossMultiplier)).append(" ATR</td></tr>");
                String trailingIcon = ef.trailingStopEnabled ? "✓" : "✗";
                String trailingColor = ef.trailingStopEnabled ? "#22c55e" : "#ef4444";
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>Trailing Stop (סטופ נגרר)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:").append(trailingColor).append(";'>").append(trailingIcon).append("</td>");
                sb.append("<td style='padding:6px;text-align:right;'>נועל רווחים</td></tr>");
                sb.append("<tr style='border-bottom:1px solid #0f172a;'>");
                sb.append("<td style='padding:6px;'>יעד רווח (R:R)</td>");
                sb.append("<td style='padding:6px;text-align:center;color:#22c55e;'>🎯</td>");
                sb.append("<td style='padding:6px;text-align:right;'>1:").append(String.format("%.1f", ef.riskRewardRatio)).append("</td></tr>");
                sb.append("</table>");
                sb.append("<div style='margin-top:10px;padding:8px;background:#1f2a44;border-radius:6px;font-size:12px;color:#9ca3af;'>");
                sb.append("💡 לעריכה מתקדמת: שנה את הקובץ <code>scoring-config.json</code>");
                sb.append("</div></div>");

                // Features that depend on settings
                sb.append("<div style='margin-top:20px;background:#0b1220;border:2px solid #f59e0b;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#f59e0b;'>🔗 פונקציות התלויות בהגדרות אלו:</div>");
                sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;'>");
                
                // Stock Analysis
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#22c55e;margin-bottom:6px;'>📊 ניתוח מניות (Stock Analysis)</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>הציון הסופי, הסיגנל (BUY/HOLD/SELL) ומחשבון הפוזיציה מבוססים על המשקלים והאינדיקטורים שהגדרת כאן.</div>");
                sb.append("</div>");
                
                // Strategy Backtester
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#8b5cf6;margin-bottom:6px;'>📈 Strategy Backtester</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>הבדיקה לאחור משתמשת ב-RS Entry/Exit, ATR Stop Loss ו-R:R שהוגדרו כאן.</div>");
                sb.append("</div>");
                
                // Daily Trading Simulator
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#22c55e;margin-bottom:6px;'>🎯 Daily Trading Simulator</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>הסימולטור משתמש ב-RVOL, RSI, CCI ו-Pivot Points לבחירת מניות ויעדי רווח.</div>");
                sb.append("</div>");
                
                // Scanner (Intraday Alerts)
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#3b82f6;margin-bottom:6px;'>🔔 Intraday Scanner</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>הסורק משתמש ב-Volume Filter ו-RSI Thresholds מההגדרות לזיהוי פריצות.</div>");
                sb.append("</div>");
                
                // Favorites & Watchlist
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#f59e0b;margin-bottom:6px;'>⭐ Favorites & Green Stocks</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>מניות \"ירוקות\" מסוננות לפי ה-Entry Filters (RS, SMA, RSI, Volume).</div>");
                sb.append("</div>");
                
                // VETO System
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:12px;'>");
                sb.append("<div style='font-weight:600;color:#ef4444;margin-bottom:6px;'>🚨 VETO System</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;'>כאשר VETO פעיל, מניות עם M-Score או Z-Score מסוכן יקבלו ציון 0 אוטומטית.</div>");
                sb.append("</div>");
                
                sb.append("</div></div>");

                // Mode descriptions
                sb.append("<div style='margin-top:20px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#93c5fd;'>📖 Mode Descriptions | תיאור מצבים:</div>");
                sb.append("<div style='margin-bottom:12px;'><b style='color:#3b82f6;'>Long-Term Investor:</b> Focus on fundamentals, value investing, Graham-style. Best for buy-and-hold (months/years).<br/><span style='color:#9ca3af;'>משקיע לטווח ארוך - דגש על פונדמנטלים, השקעת ערך בסגנון גראהם.</span></div>");
                sb.append("<div style='margin-bottom:12px;'><b style='color:#22c55e;'>Swing Trader:</b> Technical signals with light fundamentals. Trades last 1-5 days.<br/><span style='color:#9ca3af;'>סווינג טריידר - דגש על טכניקלס, עסקאות של 1-5 ימים.</span></div>");
                sb.append("<div style='margin-bottom:12px;'><b style='color:#f59e0b;'>Momentum:</b> Pure technical, momentum-based. For intraday to 1 day trades.<br/><span style='color:#9ca3af;'>מומנטום - טכניקלס בלבד, לעסקאות יומיות.</span></div>");
                sb.append("<div><b style='color:#a855f7;'>Custom:</b> Define your own weights and thresholds.<br/><span style='color:#9ca3af;'>מותאם אישית - הגדר משקלים וסיפים לפי בחירתך.</span></div>");
                sb.append("</div>");

                sb.append("</div>");

                // ===================== DTS REGIME GATES SECTION =====================
                DailyTradingSimulator.SimulatorStore dtsStore = DailyTradingSimulator.getStore();
                sb.append("<div style='margin-top:20px;background:#0b1220;border:2px solid #f59e0b;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:6px;color:#f59e0b;'>🛡️ DTS Regime Gates | שערי משמר שוק</div>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-bottom:14px;'>Controls which market regime checks block the AI agent scanner from opening new trades. Applies to Daily Trading Simulator &amp; AI Agent tool.</div>");

                // Gate 1 – Market Guard
                String g1On  = dtsStore.marketGuardEnabled ? "background:#22c55e;color:#000;" : "background:#1f2a44;color:#9ca3af;";
                String g1Off = !dtsStore.marketGuardEnabled ? "background:#ef4444;color:#fff;" : "background:#1f2a44;color:#9ca3af;";
                sb.append("<div style='display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px;background:#111827;border-radius:8px;margin-bottom:10px;'>");
                sb.append("<div style='flex:1;'>");
                sb.append("<div style='font-weight:600;color:#e5e7eb;'>Gate 1 – Market Guard (SPY Intraday)</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;margin-top:3px;'>Blocks ALL agents when SPY drops &gt; 0.5%, falls below VWAP, or breaks SMA20. Re-checked every 15 min.</div>");
                sb.append("</div>");
                sb.append("<div style='display:flex;gap:6px;align-items:center;flex-shrink:0;'>");
                sb.append("<button onclick=\"setDtsGate('market-guard', true)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g1On).append("'>ON</button>");
                sb.append("<button onclick=\"setDtsGate('market-guard', false)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g1Off).append("'>OFF</button>");
                sb.append("</div></div>");

                // Gate 2 – Daily Regime (regimeOverride: true=OFF, false=ON)
                boolean g2Active = !dtsStore.regimeOverride;
                String g2On  = g2Active ? "background:#22c55e;color:#000;" : "background:#1f2a44;color:#9ca3af;";
                String g2Off = !g2Active ? "background:#ef4444;color:#fff;" : "background:#1f2a44;color:#9ca3af;";
                sb.append("<div style='display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px;background:#111827;border-radius:8px;margin-bottom:10px;'>");
                sb.append("<div style='flex:1;'>");
                sb.append("<div style='font-weight:600;color:#e5e7eb;'>Gate 2 – Daily Regime (TRENDING / CHOPPY)</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;margin-top:3px;'>Blocks trend agents (Momentum, Intraday) in CHOPPY markets, and pullback agents in TRENDING markets. Detected via ADX(14), SPY&gt;VWAP, and ATR expansion.</div>");
                sb.append("</div>");
                sb.append("<div style='display:flex;gap:6px;align-items:center;flex-shrink:0;'>");
                sb.append("<button onclick=\"setDtsGate('regime-override', true)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g2On).append("'>ON</button>");
                sb.append("<button onclick=\"setDtsGate('regime-override', false)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g2Off).append("'>OFF</button>");
                sb.append("</div></div>");

                // Gate 3 – SUCCESS_2026 stricter guard
                String g3On  = dtsStore.success2026GuardEnabled ? "background:#22c55e;color:#000;" : "background:#1f2a44;color:#9ca3af;";
                String g3Off = !dtsStore.success2026GuardEnabled ? "background:#ef4444;color:#fff;" : "background:#1f2a44;color:#9ca3af;";
                sb.append("<div style='display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px;background:#111827;border-radius:8px;'>");
                sb.append("<div style='flex:1;'>");
                sb.append("<div style='font-weight:600;color:#e5e7eb;'>Gate 3 – SUCCESS_2026 Stricter Guard</div>");
                sb.append("<div style='font-size:12px;color:#9ca3af;margin-top:3px;'>Applies additional SPY filters (SMA20 + max drop -0.7%) specifically to SUCCESS_2026 momentum variants before they can open positions.</div>");
                sb.append("</div>");
                sb.append("<div style='display:flex;gap:6px;align-items:center;flex-shrink:0;'>");
                sb.append("<button onclick=\"setDtsGate('success2026-guard', true)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g3On).append("'>ON</button>");
                sb.append("<button onclick=\"setDtsGate('success2026-guard', false)\" style='padding:7px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;").append(g3Off).append("'>OFF</button>");
                sb.append("</div></div>");

                // Current regime status badge
                String curRegime = dtsStore.dailyRegime != null ? dtsStore.dailyRegime : "UNKNOWN";
                String regimeColor = "TRENDING".equals(curRegime) ? "#22c55e" : ("CHOPPY".equals(curRegime) ? "#ef4444" : "#9ca3af");
                sb.append("<div style='margin-top:12px;padding:10px 14px;background:#1f2a44;border-radius:8px;display:flex;align-items:center;gap:10px;font-size:12px;'>");
                sb.append("<span style='color:#9ca3af;'>Current regime:</span>");
                sb.append("<span style='font-weight:700;color:").append(regimeColor).append(";'>").append(escapeHtml(curRegime)).append("</span>");
                if (dtsStore.regimeExplanation != null) {
                    sb.append("<span style='color:#6b7280;'>|</span><span style='color:#9ca3af;'>").append(escapeHtml(dtsStore.regimeExplanation)).append("</span>");
                }
                sb.append("</div>");

                sb.append("<script>");
                sb.append("async function setDtsGate(gate, enabled) {");
                sb.append("  var url;");
                sb.append("  if (gate === 'regime-override') {");
                sb.append("    url = '/api/daily-sim/regime-override?enabled=' + !enabled;");
                sb.append("  } else {");
                sb.append("    url = '/api/daily-sim/' + gate + '?enabled=' + enabled;");
                sb.append("  }");
                sb.append("  try {");
                sb.append("    var r = await fetch(url, {method:'POST'});");
                sb.append("    var d = await r.json();");
                sb.append("    if (d.error) { alert('Error: ' + d.error); }");
                sb.append("    else { location.reload(); }");
                sb.append("  } catch(e) { alert('Request failed: ' + e); }");
                sb.append("}");
                sb.append("</script>");

                sb.append("</div>");

                // ===================== INVESTMENT PER STOCK SECTION =====================
                double currentInvestment = ScoringConfig.getInvestmentPerStock();
                sb.append("<div style='margin-top:20px;background:#0b1220;border:2px solid #22c55e;border-radius:8px;padding:16px;margin-bottom:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:10px;color:#22c55e;'>💵 Investment Per Stock | השקעה למניה</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;margin-bottom:12px;'>Dollar amount invested per stock in the simulation (Daily Trading Simulator &amp; AI agents).<br/>סכום הדולרים שמושקע בכל מניה בסימולציה.</div>");
                sb.append("<form method='post' action='/settings-investment' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;'>");
                sb.append("<div style='display:flex;align-items:center;gap:6px;'>");
                sb.append("<span style='color:#9ca3af;font-size:16px;'>$</span>");
                sb.append("<input type='number' name='amount' value='").append((int) currentInvestment).append("' min='100' max='100000' step='100' ");
                sb.append("style='padding:10px 12px;border-radius:8px;border:1px solid #1f2a44;background:#1f2a44;color:#e5e7eb;width:140px;font-size:16px;font-weight:600;' />");
                sb.append("</div>");
                sb.append("<button type='submit' style='background:#22c55e;color:#000;border:none;padding:10px 20px;border-radius:8px;font-weight:600;cursor:pointer;'>💾 Save</button>");
                sb.append("</form>");
                sb.append("<div style='margin-top:12px;padding:10px;background:#1f2a44;border-radius:8px;border-left:3px solid #22c55e;'>");
                sb.append("<div style='color:#22c55e;font-weight:600;'>Current: $").append(String.format("%,.0f", currentInvestment)).append(" per stock</div>");
                sb.append("<div style='color:#9ca3af;font-size:12px;margin-top:4px;'>Common presets: ");
                sb.append("<span onclick=\"document.querySelector('input[name=amount]').value='500'\" style='cursor:pointer;color:#93c5fd;margin-right:10px;'>$500</span>");
                sb.append("<span onclick=\"document.querySelector('input[name=amount]').value='1000'\" style='cursor:pointer;color:#93c5fd;margin-right:10px;'>$1,000</span>");
                sb.append("<span onclick=\"document.querySelector('input[name=amount]').value='2500'\" style='cursor:pointer;color:#93c5fd;margin-right:10px;'>$2,500</span>");
                sb.append("<span onclick=\"document.querySelector('input[name=amount]').value='5000'\" style='cursor:pointer;color:#93c5fd;'>$5,000</span>");
                sb.append("</div></div>");
                sb.append("</div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // Per-page settings endpoints for SwingLongAiAgent (/favorites)
        server.createContext("/favorites-settings-mode", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/favorites");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String mode = form.getOrDefault("mode", "LONG_TERM_INVESTOR");
                ScoringConfig.setActiveMode(mode);
                ex.getResponseHeaders().add("Location", "/favorites?saved=mode");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        server.createContext("/favorites-settings-agent-config", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/favorites");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                if ("true".equals(form.get("clear"))) {
                    ScoringConfig.setActiveAgentConfig(null);
                } else {
                    String agentId = form.getOrDefault("agentId", "").trim();
                    ScoringConfig.setActiveAgentConfig(agentId.isEmpty() ? null : agentId);
                }
                ex.getResponseHeaders().add("Location", "/favorites?saved=agent");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        server.createContext("/settings-mode", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/settings");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String mode = form.getOrDefault("mode", "LONG_TERM_INVESTOR");
                ScoringConfig.setActiveMode(mode);
                ex.getResponseHeaders().add("Location", "/settings?saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Settings - Agent Config endpoint
        server.createContext("/settings-agent-config", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/settings");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                
                if ("true".equals(form.get("clear"))) {
                    ScoringConfig.setActiveAgentConfig(null);
                } else {
                    String agentId = form.getOrDefault("agentId", "").trim();
                    ScoringConfig.setActiveAgentConfig(agentId);
                }
                
                ex.getResponseHeaders().add("Location", "/settings?agent_saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Settings - Monitor Agent for Discord endpoint
        server.createContext("/settings-monitor-agent", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/settings");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                
                if ("true".equals(form.get("clear"))) {
                    ScoringConfig.setMonitoredAgentForDiscord(null);
                } else {
                    String agentId = form.getOrDefault("agentId", "").trim();
                    if (!agentId.isEmpty()) {
                        ScoringConfig.setMonitoredAgentForDiscord(agentId);
                    } else {
                        ScoringConfig.setMonitoredAgentForDiscord(null);
                    }
                }
                
                ex.getResponseHeaders().add("Location", "/settings?monitor_saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Settings - Sector Allocation endpoint
        server.createContext("/settings-sector-allocation", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/settings");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                
                // Parse sector allocation from form
                java.util.Map<LongTermCandidateFinder.Sector, Integer> allocation = new java.util.LinkedHashMap<>();
                String[] sectorNames = {"NASDAQ_100", "TECHNOLOGY", "FINANCIALS", "HEALTHCARE", "ENERGY", 
                    "INDUSTRIALS", "CONSUMER_DISCRETIONARY", "CONSUMER_STAPLES", "UTILITIES", 
                    "MATERIALS", "REAL_ESTATE", "COMMUNICATION_SERVICES"};
                
                for (String sectorName : sectorNames) {
                    String pctVal = form.get("pct_" + sectorName);
                    System.out.println("[DEBUG] Sector " + sectorName + ": pct=" + pctVal);
                    
                    int pct = 0;
                    try {
                        pct = Integer.parseInt(pctVal != null ? pctVal : "0");
                    } catch (NumberFormatException ignore) {}
                    if (pct > 0) {
                        LongTermCandidateFinder.Sector sector = LongTermCandidateFinder.Sector.valueOf(sectorName);
                        allocation.put(sector, pct);
                    }
                }
                
                // If no sectors selected, default to NASDAQ_100
                if (allocation.isEmpty()) {
                    allocation.put(LongTermCandidateFinder.Sector.NASDAQ_100, 100);
                }
                
                LongTermCandidateFinder.setSectorAllocation(allocation);
                System.out.println("[WebServer] Sector allocation updated: " + allocation);
                
                ex.getResponseHeaders().add("Location", "/settings?sector_saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Settings - Investment Per Stock endpoint
        server.createContext("/settings-investment", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    ex.getResponseHeaders().add("Location", "/settings");
                    ex.sendResponseHeaders(303, -1); ex.close();
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                try {
                    double amount = Double.parseDouble(form.getOrDefault("amount", "1000"));
                    if (amount < 100) amount = 100;
                    if (amount > 100000) amount = 100000;
                    ScoringConfig.setInvestmentPerStock(amount);
                } catch (NumberFormatException ignore) {}
                ex.getResponseHeaders().add("Location", "/settings?investment_saved=true");
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // ---------------- API Usage History Page ----------------
        server.createContext("/history-usage", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>📊 API Usage History | היסטוריית שימוש ב-API</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>Track Alpha Vantage API calls per day (last 30 days)</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:16px;'>מעקב אחר קריאות API ליום (30 ימים אחרונים)</div>");

                // Today's summary
                int todayTotal = ApiUsageTracker.getTodayTotal();
                Map<String, Integer> todayBreakdown = ApiUsageTracker.getTodayBreakdown();
                int grandTotal = ApiUsageTracker.getGrandTotal();

                sb.append("<div style='display:flex;gap:16px;flex-wrap:wrap;margin-bottom:20px;'>");
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:16px;flex:1;min-width:150px;'>");
                sb.append("<div style='font-size:32px;font-weight:700;color:#22c55e;'>").append(todayTotal).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;'>Today's Calls | קריאות היום</div>");
                sb.append("</div>");
                sb.append("<div style='background:#1f2a44;border-radius:8px;padding:16px;flex:1;min-width:150px;'>");
                sb.append("<div style='font-size:32px;font-weight:700;color:#3b82f6;'>").append(grandTotal).append("</div>");
                sb.append("<div style='color:#9ca3af;font-size:13px;'>Total (30 days) | סה\"כ</div>");
                sb.append("</div>");
                sb.append("</div>");

                // Today's breakdown by API type
                if (!todayBreakdown.isEmpty()) {
                    sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;margin-bottom:20px;'>");
                    sb.append("<div style='font-weight:600;margin-bottom:12px;color:#f59e0b;'>Today's Breakdown | פירוט היום:</div>");
                    sb.append("<div style='display:flex;flex-wrap:wrap;gap:10px;'>");
                    for (Map.Entry<String, Integer> e : todayBreakdown.entrySet()) {
                        sb.append("<div style='background:#1f2a44;padding:8px 12px;border-radius:6px;'>");
                        sb.append("<span style='color:#e5e7eb;'>").append(escapeHtml(e.getKey())).append("</span>");
                        sb.append("<span style='color:#22c55e;font-weight:600;margin-left:8px;'>").append(e.getValue()).append("</span>");
                        sb.append("</div>");
                    }
                    sb.append("</div></div>");
                }

                // History table
                sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#93c5fd;'>📅 Daily History | היסטוריה יומית:</div>");

                Map<String, Map<String, Integer>> usageData = ApiUsageTracker.getUsageData();
                Set<String> allApiTypes = ApiUsageTracker.getAllApiTypes();

                if (usageData.isEmpty()) {
                    sb.append("<div style='color:#9ca3af;padding:20px;text-align:center;'>No API usage data recorded yet.<br/>אין נתוני שימוש עדיין.</div>");
                } else {
                    sb.append("<div style='overflow-x:auto;'>");
                    sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;min-width:600px;'>");
                    
                    // Header row
                    sb.append("<tr style='border-bottom:2px solid #1f2a44;background:#0f172a;'>");
                    sb.append("<th style='text-align:left;padding:10px;position:sticky;left:0;background:#0f172a;'>Date | תאריך</th>");
                    for (String apiType : allApiTypes) {
                        sb.append("<th style='text-align:center;padding:10px;color:#93c5fd;'>").append(escapeHtml(apiType)).append("</th>");
                    }
                    sb.append("<th style='text-align:right;padding:10px;color:#22c55e;font-weight:700;'>Total | סה\"כ</th>");
                    sb.append("</tr>");

                    // Data rows
                    int rowIdx = 0;
                    for (Map.Entry<String, Map<String, Integer>> dayEntry : usageData.entrySet()) {
                        String date = dayEntry.getKey();
                        Map<String, Integer> dayCounts = dayEntry.getValue();
                        int dayTotal = dayCounts.values().stream().mapToInt(Integer::intValue).sum();
                        
                        String rowBg = (rowIdx % 2 == 0) ? "#0b1220" : "#0f172a";
                        sb.append("<tr style='border-bottom:1px solid #1f2a44;background:").append(rowBg).append(";'>");
                        sb.append("<td style='padding:10px;font-weight:600;position:sticky;left:0;background:").append(rowBg).append(";'>").append(escapeHtml(date)).append("</td>");
                        
                        for (String apiType : allApiTypes) {
                            int count = dayCounts.getOrDefault(apiType, 0);
                            String color = count > 0 ? "#e5e7eb" : "#4b5563";
                            sb.append("<td style='text-align:center;padding:10px;color:").append(color).append(";'>").append(count > 0 ? count : "-").append("</td>");
                        }
                        
                        sb.append("<td style='text-align:right;padding:10px;font-weight:700;color:#22c55e;'>").append(dayTotal).append("</td>");
                        sb.append("</tr>");
                        rowIdx++;
                    }
                    
                    sb.append("</table>");
                    sb.append("</div>");
                }

                sb.append("</div>");

                // API types legend
                sb.append("<div style='margin-top:20px;background:#0b1220;border:1px solid #1f2a44;border-radius:8px;padding:16px;'>");
                sb.append("<div style='font-weight:600;margin-bottom:12px;color:#93c5fd;'>📖 API Types Legend | מקרא סוגי API:</div>");
                sb.append("<div style='display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:8px;font-size:13px;'>");
                sb.append("<div><span style='color:#3b82f6;'>TIME_SERIES_DAILY</span> - מחירי מניות יומיים</div>");
                sb.append("<div><span style='color:#3b82f6;'>BALANCE_SHEET</span> - מאזן חברה</div>");
                sb.append("<div><span style='color:#3b82f6;'>INCOME_STATEMENT</span> - דוח רווח והפסד</div>");
                sb.append("<div><span style='color:#3b82f6;'>CASH_FLOW</span> - תזרים מזומנים</div>");
                sb.append("<div><span style='color:#3b82f6;'>EARNINGS</span> - דוחות רווחים</div>");
                sb.append("<div><span style='color:#3b82f6;'>EARNINGS_ESTIMATES</span> - תחזיות רווחים</div>");
                sb.append("<div><span style='color:#3b82f6;'>OVERVIEW</span> - סקירת חברה</div>");
                sb.append("<div><span style='color:#3b82f6;'>NEWS_SENTIMENT</span> - חדשות וסנטימנט</div>");
                sb.append("<div><span style='color:#3b82f6;'>TOP_GAINERS_LOSERS</span> - מובילים/מפסידים</div>");
                sb.append("</div></div>");

                sb.append("</div>");
                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // Render a simple SVG technical chart for a symbol: close, SMA(20), Bollinger(20,2)
        server.createContext("/chart", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                try {
                    String query = ex.getRequestURI().getQuery();
                    Map<String,String> q = new HashMap<>();
                    if (query != null) {
                        for (String p : query.split("&")) {
                            String[] kv = p.split("=",2);
                            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                            String v = kv.length>1? URLDecoder.decode(kv[1], StandardCharsets.UTF_8):"";
                            q.put(k,v);
                        }
                    }
                    String symbol = q.getOrDefault("symbol", "").trim();
                    int w = Math.max(300, Integer.parseInt(q.getOrDefault("w","800")));
                    int h = Math.max(150, Integer.parseInt(q.getOrDefault("h","300")));
                    int n = Math.max(30, Integer.parseInt(q.getOrDefault("n","120")));

                    if (symbol.isEmpty()) {
                        respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'><text x='10' y='20' fill='red'>symbol is required</text></svg>", 200);
                        return;
                    }

                    // Fetch data
                    DataFetcher.setTicker(symbol);
                    String json = DataFetcher.fetchStockData();
                    List<Double> closes = PriceJsonParser.extractClosingPrices(json);
                    if (closes == null || closes.size() < 30) {
                        String msg = PriceJsonParser.extractServiceMessage(json);
                        String label = (msg!=null? escapeHtml(msg): "insufficient data");
                        respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'><text x='10' y='20' fill='orange'>"+label+"</text></svg>", 200);
                        return;
                    }
                    int start = Math.max(0, closes.size()-n);
                    List<Double> sub = closes.subList(start, closes.size());
                    List<Double> sma = TechnicalAnalysisModel.calculateSMA(closes, 20);
                    List<Double> smaSub = sma.subList(Math.max(0, sma.size()-sub.size()), sma.size());
                    List<Double[]> bands = BollingerBands.calculateBands(closes, 20, 2.0);
                    List<Double[]> bandsSub = bands.subList(Math.max(0, bands.size()-sub.size()), bands.size());

                    // Compute scale
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (int i=0;i<sub.size();i++) {
                        Double c = sub.get(i);
                        if (c != null) {
                            min = Math.min(min, c);
                            max = Math.max(max, c);
                        }
                        Double[] b = (i<bandsSub.size()? bandsSub.get(i): null);
                        if (b!=null) {
                            if (b[2]!=null) min = Math.min(min, b[2]);
                            if (b[0]!=null) max = Math.max(max, b[0]);
                        }
                    }
                    if (max <= min) { max = min + 1.0; }
                    final int pad = 20;
                    final int plotW = w - pad*2;
                    final int plotH = h - pad*2;
                    StringBuilder pathPrice = new StringBuilder();
                    StringBuilder pathSma = new StringBuilder();
                    StringBuilder pathUpper = new StringBuilder();
                    StringBuilder pathLower = new StringBuilder();
                    double denom = Math.max(1.0, (double)(sub.size()-1));
                    for (int i=0;i<sub.size();i++) {
                        double x = pad + (plotW * (i/denom));
                        Double c = sub.get(i);
                        if (c != null) {
                            double yPrice = pad + plotH * (1 - ((c-min)/(max-min)));
                            if (pathPrice.length()==0) pathPrice.append("M").append(x).append(" ").append(yPrice);
                            else pathPrice.append(" L").append(x).append(" ").append(yPrice);
                        }

                        if (i < smaSub.size()) {
                            Double sv = smaSub.get(i);
                            if (sv != null) {
                                double ySma = pad + plotH * (1 - ((sv-min)/(max-min)));
                                if (pathSma.length()==0) pathSma.append("M").append(x).append(" ").append(ySma);
                                else pathSma.append(" L").append(x).append(" ").append(ySma);
                            }
                        }
                        if (i < bandsSub.size()) {
                            Double[] b = bandsSub.get(i);
                            if (b!=null && b[0]!=null && b[2]!=null) {
                                double yU = pad + plotH * (1 - ((b[0]-min)/(max-min)));
                                double yL = pad + plotH * (1 - ((b[2]-min)/(max-min)));
                                if (pathUpper.length()==0) pathUpper.append("M").append(x).append(" ").append(yU);
                                else pathUpper.append(" L").append(x).append(" ").append(yU);
                                if (pathLower.length()==0) pathLower.append("M").append(x).append(" ").append(yL);
                                else pathLower.append(" L").append(x).append(" ").append(yL);
                            }
                        }
                    }
                    String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'>"+
                            "<rect x='0' y='0' width='100%' height='100%' fill='#0b1220'/>"+
                            "<g stroke='#1f2a44' stroke-width='1'>"+
                            "<rect x='"+pad+"' y='"+pad+"' width='"+plotW+"' height='"+plotH+"' fill='none'/>"+
                            "</g>"+
                            // Bands, SMA, Close
                            "<path d='"+pathUpper+"' stroke='#8888ff' fill='none' stroke-width='1.5'/>"+
                            "<path d='"+pathLower+"' stroke='#8888ff' fill='none' stroke-dasharray='4,3' stroke-width='1.5'/>"+
                            "<path d='"+pathSma+"' stroke='#ffcc00' fill='none' stroke-width='1.5'/>"+
                            "<path d='"+pathPrice+"' stroke='#22c55e' fill='none' stroke-width='2'/>"+
                            // Title
                            "<text x='"+(pad+6)+"' y='"+(pad+16)+"' fill='#e5e7eb' font-size='12'>"+escapeHtml(symbol)+" (Close/SMA20/Bands)</text>"+
                            // Legend (Hebrew)
                            "<g font-size='12' fill='#e5e7eb'>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+30)+"' x2='"+(pad+40)+"' y2='"+(pad+30)+"' stroke='#22c55e' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+34)+"'>מחיר סגירה (ירוק)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+48)+"' x2='"+(pad+40)+"' y2='"+(pad+48)+"' stroke='#ffcc00' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+52)+"'>ממוצע נע 20 (צהוב)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+66)+"' x2='"+(pad+40)+"' y2='"+(pad+66)+"' stroke='#8888ff' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+70)+"'>רצועת בולינגר עליונה (כחול)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+84)+"' x2='"+(pad+40)+"' y2='"+(pad+84)+"' stroke='#8888ff' stroke-width='2' stroke-dasharray='4,3'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+88)+"'>רצועת בולינגר תחתונה (כחול מקווקו)</text>"+
                            "</g>"+
                            "</svg>";
                    respondSvg(ex, svg, 200);
                } catch (Exception e) {
                    respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='600' height='120'><text x='10' y='20' fill='red'>"+escapeHtml(e.getMessage())+"</text></svg>", 200);
                }
            }
        });

        server.createContext("/run-main", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String, String> form = parseForm(body);
                String symbol = form.getOrDefault("symbol", "").trim();
                String result;
                boolean showChart = false;
                String overviewCard = "";
                String riskModelsCard = "";
                String modelSummaryCard = "";
                if (symbol.isEmpty()) {
                    result = "No symbol provided";
                } else {
                    try {
                        DataFetcher.setTicker(symbol);

                        // Try to fetch company overview (best-effort, independent of pricing data source)
                        try {
                            String ovJson = DataFetcher.fetchCompanyOverview(symbol);
                            ObjectMapper om = new ObjectMapper();
                            JsonNode root = om.readTree(ovJson);
                            JsonNode nameN = root.get("Name");
                            if (nameN != null && nameN.isTextual()) {
                                String name = nameN.asText("");
                                String sector = root.path("Sector").asText("");
                                String industry = root.path("Industry").asText("");
                                String mcap = root.path("MarketCapitalization").asText("");
                                String pe = root.path("PERatio").asText("");
                                String pb = root.path("PriceToBookRatio").asText("");
                                String desc = root.path("Description").asText("");
                                if (desc != null && desc.length() > 380) {
                                    desc = desc.substring(0, 380) + "...";
                                }
                                StringBuilder ov = new StringBuilder();
                                ov.append("<div class='card'><div class='title'>Company Overview</div>");
                                ov.append("<div style='color:#e5e7eb;margin-bottom:6px;'><b>")
                                        .append(escapeHtml(name)).append("</b> ("+escapeHtml(symbol)+")</div>");
                                if (!sector.isEmpty() || !industry.isEmpty()) {
                                    ov.append("<div style='color:#9ca3af;margin-bottom:6px;'>")
                                            .append(escapeHtml(sector))
                                            .append(sector.isEmpty()||industry.isEmpty()?"":" · ")
                                            .append(escapeHtml(industry))
                                            .append("</div>");
                                }
                                String extra = "";
                                if (!mcap.isEmpty()) extra += "Market Cap: "+escapeHtml(formatMarketCap(mcap));
                                if (!pe.isEmpty()) extra += (extra.isEmpty()?"":" · ")+"P/E: "+escapeHtml(pe);
                                if (!pb.isEmpty()) extra += (extra.isEmpty()?"":" · ")+"P/B: "+escapeHtml(pb);
                                if (!extra.isEmpty()) ov.append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(extra).append("</div>");
                                if (desc != null && !desc.isEmpty()) {
                                    ov.append("<div style='color:#cbd5e1;'>").append(escapeHtml(desc)).append("</div>");
                                }
                                ov.append("</div>");
                                overviewCard = ov.toString();
                            }
                        } catch (Exception ignore) { }

                        try {
                            StockAnalysisResult r = StockScannerRunner.analyzeSingleStock(symbol);

                            String priceTxt = (r == null || !Double.isFinite(r.price)) ? "N/A" : String.format("$%.2f", r.price);
                            String dcfTxt = (r == null || !Double.isFinite(r.dcfFairValue) || r.dcfFairValue <= 0) ? "N/A" : String.format("$%.2f", r.dcfFairValue);
                            String adxTxt = (r == null || !Double.isFinite(r.adxStrength)) ? "N/A" : String.format("%.2f", r.adxStrength);
                            String techTxt = (r == null || r.technicalSignal == null || r.technicalSignal.isBlank()) ? "N/A" : r.technicalSignal;
                            String fundTxt = (r == null || r.fundamentalSignal == null || r.fundamentalSignal.isBlank()) ? "N/A" : r.fundamentalSignal;
                            String verdictTxt = (r == null || r.finalVerdict == null || r.finalVerdict.isBlank()) ? "N/A" : r.finalVerdict;
                            String cccTxt = (r == null || r.cccDays == null || !Double.isFinite(r.cccDays)) ? "N/A" : String.format("%.1f days", r.cccDays);
                            String roicTxt = (r == null || r.roic == null || !Double.isFinite(r.roic)) ? "N/A" : String.format("%.2f%%", (r.roic * 100.0));
                            String waccTxt = (r == null || r.wacc == null || !Double.isFinite(r.wacc)) ? "N/A" : String.format("%.2f%%", (r.wacc * 100.0));
                            String spreadTxt = (r == null || r.economicSpread == null || !Double.isFinite(r.economicSpread)) ? "N/A" : String.format("%+.2f%%", (r.economicSpread * 100.0));

                            modelSummaryCard = "<div class='card'><div class='title'>Model Summary</div>" +
                                    "<div style='display:flex;flex-direction:column;gap:8px'>" +
                                    "<div><b>Price</b>: <span style='color:#e5e7eb'>" + escapeHtml(priceTxt) + "</span></div>" +
                                    "<div><b>DCF Fair Value</b>" + modelBadge("FUNDAMENTAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(dcfTxt) + "</span></div>" +
                                    "<div><b>Cash Conversion Cycle (CCC)</b>" + modelBadge("FUNDAMENTAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(cccTxt) + "</span></div>" +
                                    "<div><b>ROIC vs WACC</b>" + modelBadge("FUNDAMENTAL") + ": <span style='color:#e5e7eb'>ROIC " + escapeHtml(roicTxt) + " | WACC " + escapeHtml(waccTxt) + " | Spread " + escapeHtml(spreadTxt) + "</span></div>" +
                                    "<div><b>ADX</b>" + modelBadge("TECHNICAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(adxTxt) + "</span></div>" +
                                    "<div><b>Technical Signal</b>" + modelBadge("TECHNICAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(techTxt) + "</span></div>" +
                                    "<div><b>Fundamental Signal</b>" + modelBadge("FUNDAMENTAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(fundTxt) + "</span></div>" +
                                    "<div><b>Final Verdict</b>: <span style='color:#e5e7eb'>" + escapeHtml(verdictTxt) + "</span></div>" +
                                    "</div></div>";

                            String beneishTxt;
                            if (r != null && r.beneishMScore != null && Double.isFinite(r.beneishMScore)) {
                                beneishTxt = String.format("%.2f", r.beneishMScore) +
                                        (r.beneishManipulator != null && r.beneishManipulator ? " (MANIPULATOR)" : " (SAFE)");
                            } else {
                                beneishTxt = "N/A";
                            }

                            String sloanTxt;
                            if (r != null && r.sloanRatio != null && Double.isFinite(r.sloanRatio)) {
                                sloanTxt = String.format("%+.2f%%", (r.sloanRatio * 100.0)) +
                                        (r.sloanLowQuality != null && r.sloanLowQuality ? " (LOW QUALITY)" : "");
                            } else {
                                sloanTxt = "N/A";
                            }

                            riskModelsCard = "<div class='card'><div class='title'>Risk Models</div>" +
                                    "<div style='color:#9ca3af;margin-bottom:8px;'>Beneish / Sloan require annual financial statements (Alpha Vantage). If rate-limited or missing, values may show N/A.</div>" +
                                    "<div style='display:flex;flex-direction:column;gap:8px'>" +
                                    "<div><b>Beneish M-Score</b>" + modelBadge("RISK") + ": <span style='color:#e5e7eb'>" + escapeHtml(beneishTxt) + "</span></div>" +
                                    "<div><b>Sloan Ratio</b>" + modelBadge("RISK") + ": <span style='color:#e5e7eb'>" + escapeHtml(sloanTxt) + "</span></div>" +
                                    "</div></div>";
                        } catch (Exception ignore) {
                        }

                        // Run the full analysis flow (which already handles data sufficiency and Finnhub fallback)
                        result = runAndCapture(() -> Main.main(new String[]{}));
                        showChart = true;
                    } catch (Exception e) {
                        result = "Error: " + e.getMessage();
                    }
                }
                String charts = "";
                if (!symbol.isEmpty() && showChart) {
                    String chartTag = String.format(
                            "<div class=\"card\"><div class=\"title\">גרף טכני</div><img src='//chart?symbol=%s&w=900&h=320&n=120' alt='chart'/></div>",
                            escapeHtml(symbol)
                    );
                    // Ensure correct path prefix
                    chartTag = chartTag.replace("//chart", "/chart");
                    charts = chartTag;
                }
                // Optional AI summary (uses OpenAI if OPENAI_API_KEY is set)
                String aiCard = "";
                try {
                    String aiSummary = summarizeWithOpenAI(result, symbol);
                    if (aiSummary != null && !aiSummary.isEmpty()) {
                        aiCard = "<div class='card'><div class='title'>AI Summary</div>" +
                                "<pre style='direction:ltr;text-align:left;unicode-bidi:plaintext;white-space:pre-wrap;word-break:break-word;'>" +
                                escapeHtml(aiSummary) + "</pre></div>";
                    }
                } catch (Exception ignore) {}

                String favCard = "";
                if (!symbol.isEmpty()) {
                    String esc = escapeHtml(symbol);
                    favCard = "<div class='card' style='padding:12px 16px;display:flex;align-items:center;gap:12px'>" +
                            "<form method='post' action='/favorite-add' style='margin:0'>" +
                            "<input type='hidden' name='symbol' value='"+esc+"'/>" +
                            "<button type='submit' title='Add to Favorites' style='display:inline-flex;align-items:center;gap:6px'>★ Add " + esc + "</button>" +
                            "</form>" +
                            "<div style='color:#9ca3af'>Save this symbol for quick access in Favorites</div>" +
                            "</div>";
                }

                // Analysts cards (both Finnhub and Alpha Vantage proxy)
                String analystsCard = "";
                if (!symbol.isEmpty()) {
                    String analystA = buildAnalystCard(symbol);
                    String analystB = buildAnalystProxyAlphaVantageCard(symbol);
                    analystsCard = "<div class='card'><div class='title'>📊 Analysts Consensus</div>" +
                            "<div style='color:#9ca3af;margin-bottom:10px;'>Two analyst data sources for comprehensive view</div></div>" +
                            analystA + analystB;
                }

                // Persist last single-symbol analysis to disk (analyses/{symbol}.txt)
                try {
                    if (!symbol.isEmpty()) {
                        Path dir = Paths.get("analyses");
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve(symbol.toUpperCase() + ".txt"), result, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}

                String html = htmlPage(favCard + overviewCard + modelSummaryCard + riskModelsCard + analystsCard + "<div class='card'><div class='title'>Models used</div>" + modelsUsedNamesOnlyHtml() + "</div>" + "<div class=\"card\"><div class=\"title\">Output</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + aiCard + charts
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.25,now);beep(1100,0.25,now+0.3);beep(1320,0.25,now+0.6);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/nasdaq-daily-top", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }

                String result;
                List<String> tickers;
                String err;
                try {
                    DailyPicksComputed computed = computeDailyNasdaqTopPicks();
                    result = computed.text;
                    tickers = computed.tickers;
                    err = computed.error;
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                    tickers = null;
                    err = e.getMessage();
                }

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (עד 10)</div>");
                    int count = Math.min(10, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }

                String meta = "";
                if (err != null && !err.isBlank()) {
                    meta = "<div class='card'><div class='title'>Last error</div><div style='color:#fca5a5'>"+escapeHtml(err)+"</div></div>";
                }

                String html = htmlPage(
                        "<div class=\"card\"><div class=\"title\">Daily Nasdaq Top " + DAILY_TOP_PICK_COUNT + " (GREEN)</div>" +
                                renderPipedTablesAsHtml(result) +
                                "</div>" +
                                "<div class='card' style='padding:12px 16px;'>"+
                                "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>"+
                                "<form method='post' action='/nasdaq-daily-top' style='margin:0'><button type='submit'>Run Now</button></form>"+
                                "<a href='/nasdaq-daily-top-last'>Open Last Saved Daily Picks</a>"+
                                "<a href='/nasdaq-daily-top-tracking'>Open 1-Month Tracking Grid</a>"+
                                "</div></div>" +
                                meta +
                                gallery.toString()
                );
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/nasdaq-daily-top-last", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                LocalDate today = LocalDate.now();
                String result = null;
                List<String> tickers = null;
                String err = null;

                synchronized (dailyPicksLock) {
                    if (dailyPicksCache.date != null && dailyPicksCache.date.equals(today) && dailyPicksCache.text != null) {
                        result = dailyPicksCache.text + "\n(loaded from in-memory daily cache)";
                        tickers = dailyPicksCache.tickers;
                        err = dailyPicksCache.lastError;
                    }
                }

                if (result == null) {
                    Path fdir = Paths.get("finder-cache");
                    try {
                        Path r = fdir.resolve("daily-top-last.txt");
                        if (Files.exists(r)) result = Files.readString(r, StandardCharsets.UTF_8);
                        Path t = fdir.resolve("daily-top-last-tickers.txt");
                        if (Files.exists(t)) {
                            String s = Files.readString(t, StandardCharsets.UTF_8);
                            tickers = java.util.Arrays.asList(s.split(","));
                        }
                        Path e = fdir.resolve("daily-top-last-error.txt");
                        if (Files.exists(e)) err = Files.readString(e, StandardCharsets.UTF_8);
                    } catch (Exception ignore) {}
                }

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (saved)</div>");
                    int count = Math.min(10, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }

                String meta = "";
                if (err != null && !err.isBlank()) {
                    meta = "<div class='card'><div class='title'>Last error</div><div style='color:#fca5a5'>"+escapeHtml(err)+"</div></div>";
                }

                String card = (result==null)
                        ? "<div class='card'><div class='title'>Last Daily Top " + DAILY_TOP_PICK_COUNT + " Picks</div><div style='color:#9ca3af'>No saved results.</div></div>"
                        : "<div class='card'><div class='title'>Last Daily Top " + DAILY_TOP_PICK_COUNT + " Picks</div>"+renderPipedTablesAsHtml(result)+"</div>";
                String nav = "<div class='card' style='padding:12px 16px;display:flex;gap:12px;flex-wrap:wrap;align-items:center;'>" +
                        "<a href='/nasdaq-daily-top-tracking'>Open Tracking Grid</a>" +
                        "<a href='/nasdaq-daily-top-last-trend'>Trend Prev Months</a>" +
                        "</div>";
                respondHtml(ex, htmlPage(nav + card + meta + gallery.toString()), 200);
            }
        });

        server.createContext("/nasdaq-daily-top-last-performance", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                ensureTrackingBootstrappedIfEmpty();

                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                int months = 1;
                try {
                    months = Integer.parseInt(qp.getOrDefault("months", "1"));
                } catch (Exception ignore) {}
                if (months < 1) months = 1;
                if (months > 3) months = 3;
                int daysToLoad = Math.min(93, Math.max(31, months * 31));

                // Load last tickers (same source as /nasdaq-daily-top-last)
                List<String> tickers = null;
                synchronized (dailyPicksLock) {
                    if (dailyPicksCache.tickers != null && !dailyPicksCache.tickers.isEmpty()) {
                        tickers = new ArrayList<>(dailyPicksCache.tickers);
                    }
                }
                if (tickers == null) {
                    try {
                        Path t = Paths.get("finder-cache").resolve("daily-top-last-tickers.txt");
                        if (Files.exists(t)) {
                            String s = Files.readString(t, StandardCharsets.UTF_8);
                            tickers = java.util.Arrays.asList(s.split(","));
                        }
                    } catch (Exception ignore) {}
                }
                if (tickers == null) tickers = new ArrayList<>();
                List<String> cleaned = new ArrayList<>();
                for (String x : tickers) {
                    if (x == null) continue;
                    String v = x.trim().toUpperCase();
                    if (!v.isBlank()) cleaned.add(v);
                }
                tickers = cleaned;

                List<String> dateFiles = listTrackingFilesNewestFirst(daysToLoad);
                dateFiles.sort(String::compareTo);
                List<String> nyDatesAsc = new ArrayList<>();
                for (String fn : dateFiles) {
                    if (fn == null) continue;
                    nyDatesAsc.add(fn.replace(".json", ""));
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Last Daily Top " + DAILY_TOP_PICK_COUNT + " Picks - % Change</div>");
                sb.append("<div style='display:flex;gap:10px;flex-wrap:wrap;margin-bottom:10px;'>");
                sb.append("<a href='/nasdaq-daily-top-last'>Back</a>");
                sb.append("<a href='/nasdaq-daily-top-last-trend' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>Trend (month-by-month)</a>");
                sb.append("<a href='/nasdaq-daily-top-last-performance?months=1' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>1 month</a>");
                sb.append("<a href='/nasdaq-daily-top-last-performance?months=2' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>2 months</a>");
                sb.append("<a href='/nasdaq-daily-top-last-performance?months=3' style='padding:6px 10px;border:1px solid #1f2a44;border-radius:10px;'>3 months</a>");
                sb.append("<div style='color:#9ca3af;align-self:center;'>Using ~" + daysToLoad + " days (months=" + months + ")</div>");
                sb.append("</div>");

                if (tickers.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No saved tickers found. Run Daily Nasdaq Top first.</div></div>");
                    respondHtml(ex, htmlPage(sb.toString()), 200);
                    return;
                }
                if (nyDatesAsc.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No tracking snapshots found yet. Open Daily Nasdaq Top once to create tracking files.</div></div>");
                    respondHtml(ex, htmlPage(sb.toString()), 200);
                    return;
                }

                // Build table
                sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
                sb.append("<thead><tr>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:left;'>Ticker</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Start Open</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Last Close</th>")
                        .append("<th style='border-bottom:1px solid #1f2a44;padding:6px 8px;text-align:right;'>Change %</th>")
                        .append("</tr></thead><tbody>");

                for (String t : tickers) {
                    Double startOpen = findStartOpenForTickerOverRange(t, nyDatesAsc);
                    Double lastClose = findLastCloseForTickerOverRange(t, nyDatesAsc);
                    Double pct = null;
                    if (startOpen != null && lastClose != null && startOpen != 0) {
                        pct = ((lastClose - startOpen) / startOpen) * 100.0;
                    }
                    String pctText = pct == null ? "" : String.format("%.2f%%", pct);
                    String pctColor = pct == null ? "#9ca3af" : (pct >= 0 ? "#22c55e" : "#fca5a5");
                    sb.append("<tr>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;'>").append(escapeHtml(t)).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;'>").append(startOpen==null?"":escapeHtml(String.format("%.4f", startOpen))).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;'>").append(lastClose==null?"":escapeHtml(String.format("%.4f", lastClose))).append("</td>")
                            .append("<td style='border-bottom:1px solid #111827;padding:6px 8px;text-align:right;color:").append(pctColor).append(";font-weight:700;'>").append(escapeHtml(pctText)).append("</td>")
                            .append("</tr>");
                }
                sb.append("</tbody></table></div></div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // TOP_GAINERS_LOSERS market-wide movers from Alpha Vantage
        server.createContext("/top-gainers-losers", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }

                String json = null;
                try {
                    json = DataFetcher.fetchTopGainersLosers();
                } catch (Exception ignore) {}

                String content;
                if (json == null || json.isBlank()) {
                    content = "<div class='card'><div class='title'>TOP Gainers / Losers</div>" +
                            "<div style='color:#9ca3af'>No data returned from Alpha Vantage (rate limit or API error).</div></div>";
                } else {
                    try {
                        ObjectMapper om = new ObjectMapper();
                        JsonNode root = om.readTree(json);

                        StringBuilder sb = new StringBuilder();
                        sb.append("<div class='card'><div class='title'>TOP Gainers / Losers (Alpha Vantage)</div>");

                        // Helper lambda-like constructs are not possible, so we repeat small bits
                        sb.append(buildMoversTable(root.path("top_gainers"), "Top Gainers"));
                        sb.append(buildMoversTable(root.path("top_losers"), "Top Losers"));
                        sb.append(buildMoversTable(root.path("most_actively_traded"), "Most Actively Traded"));

                        sb.append("</div>");
                        content = sb.toString();
                    } catch (Exception e) {
                        String safe = escapeHtml(json);
                        if (safe.length() > 12000) {
                            safe = safe.substring(0, 12000) + "...";
                        }
                        content = "<div class='card'><div class='title'>TOP Gainers / Losers (raw JSON)</div>" +
                                "<pre>" + safe + "</pre></div>";
                    }
                }

                String html = htmlPage(content);
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/recommendations", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String result;
                try {
                    result = runAndCapture(() -> LongTermCandidateFinder.main(new String[]{}));
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                // Embed up to 5 charts for last analyzed tickers
                StringBuilder gallery = new StringBuilder();
                List<String> recTickers = LongTermCandidateFinder.getLastTickers();
                if (recTickers != null && !recTickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (עד 5)</div>");
                    int count = Math.min(5, recTickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(recTickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }
                // Persist last finder results
                try {
                    Path fdir = Paths.get("finder-cache");
                    Files.createDirectories(fdir);
                    Files.writeString(fdir.resolve("last.txt"), result, StandardCharsets.UTF_8);
                    if (recTickers != null && !recTickers.isEmpty()) {
                        String joined = String.join(",", recTickers);
                        Files.writeString(fdir.resolve("last-tickers.txt"), joined, StandardCharsets.UTF_8);
                    } else {
                        try { Files.deleteIfExists(fdir.resolve("last-tickers.txt")); } catch (Exception ignore2) {}
                    }
                } catch (Exception ignore) {}

                String html = htmlPage("<div class=\"card\"><div class=\"title\">Nasdaq stock recommendations</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + gallery.toString()
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.25,now);beep(1100,0.25,now+0.3);beep(1320,0.25,now+0.6);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        // View last single-symbol saved report
        server.createContext("/report", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "").trim().toUpperCase();
                String content;
                if (sym.isEmpty()) { content = "<div class='card'><div class='title'>Saved Report</div><div style='color:#fca5a5'>No symbol provided.</div></div>"; }
                else {
                    Path file = Paths.get("analyses").resolve(sym+".txt");
                    if (Files.exists(file)) {
                        String txt = Files.readString(file, StandardCharsets.UTF_8);
                        content = "<div class='card'><div class='title'>Saved Report: "+escapeHtml(sym)+"</div><pre>"+escapeHtml(txt)+"</pre></div>";
                    } else {
                        content = "<div class='card'><div class='title'>Saved Report</div><div style='color:#9ca3af'>No saved report for "+escapeHtml(sym)+".</div></div>";
                    }
                }
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // View last Finder results from disk
        server.createContext("/finder-last", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }
                Path fdir = Paths.get("finder-cache");
                String result = null;
                java.util.List<String> tickers = null;
                try {
                    Path r = fdir.resolve("last.txt");
                    if (Files.exists(r)) result = Files.readString(r, StandardCharsets.UTF_8);
                    Path t = fdir.resolve("last-tickers.txt");
                    if (Files.exists(t)) {
                        String s = Files.readString(t, StandardCharsets.UTF_8);
                        tickers = java.util.Arrays.asList(s.split(","));
                    }
                } catch (Exception ignore) {}

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (saved)</div>");
                    int count = Math.min(10, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }
                String card = (result==null)?
                        "<div class='card'><div class='title'>Last Finder Results</div><div style='color:#9ca3af'>No saved results.</div></div>"
                        : "<div class='card'><div class='title'>Last Finder Results</div>"+renderPipedTablesAsHtml(result)+"</div>";
                respondHtml(ex, htmlPage(card + gallery.toString()), 200);
            }
        });


        server.createContext("/portfolio-weekly", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String result;
                try {
                    LocalDate today = LocalDate.now();
                    // No caching - always compute fresh data from AlphaVantage
                    String computed = runAndCapture(() -> {
                        // Analyze full portfolio (may take longer on free tier)
                        PortfolioWeeklySummary.setMaxTickers(-1);
                        PortfolioWeeklySummary.configureThrottle(true, 12_500); // ~5 req/min
                        PortfolioWeeklySummary.main(new String[]{});
                    });
                    result = computed + "\n(נתונים עדכניים מ-AlphaVantage בתאריך " + today + ")";
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                // Embed up to 5 charts for the tickers used in the last weekly run
                StringBuilder gallery = new StringBuilder();
                List<String> weeklyTickers = PortfolioWeeklySummary.getLastTickers();
                if (weeklyTickers != null && !weeklyTickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים</div>");
                    gallery.append("<div id='weekly-gallery'>");
                    for (int i=0;i<weeklyTickers.size();i++) {
                        String t = escapeHtml(weeklyTickers.get(i));
                        String hidden = (i >= 5) ? " style='display:none;' class='extra-chart'" : "";
                        gallery.append("<div"+hidden+" style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                    if (weeklyTickers.size() > 5) {
                        gallery.append("<button id='toggle-weekly' style='margin-top:8px;'>Show all charts ("+weeklyTickers.size()+")</button>");
                        gallery.append("<script>(function(){var btn=document.getElementById('toggle-weekly');if(!btn)return;var expanded=false;btn.addEventListener('click',function(){expanded=!expanded;var extras=document.querySelectorAll('.extra-chart');for(var i=0;i<extras.length;i++){extras[i].style.display=expanded?'':'none';}btn.textContent=expanded?'Show less charts':'Show all charts ("+weeklyTickers.size()+")';});})();</script>");
                    }
                    gallery.append("</div>");
                }

                // Analysts section for each portfolio ticker
                StringBuilder analystsSection = new StringBuilder();
                if (weeklyTickers != null && !weeklyTickers.isEmpty()) {
                    analystsSection.append("<div class=\"card\"><div class=\"title\">📊 Analysts Consensus (Portfolio)</div>");
                    analystsSection.append("<div style='color:#9ca3af;margin-bottom:10px;'>Two analyst data sources per ticker: Finnhub consensus + Alpha Vantage proxy</div>");
                    analystsSection.append("<div id='analysts-gallery'>");
                    for (int i=0;i<weeklyTickers.size();i++) {
                        String t = weeklyTickers.get(i);
                        String hidden = (i >= 3) ? " style='display:none;' class='extra-analyst'" : "";
                        analystsSection.append("<div"+hidden+" style='margin-bottom:16px;border-bottom:1px solid #1f2a44;padding-bottom:16px;'>");
                        analystsSection.append("<div style='color:#93c5fd;font-weight:bold;margin-bottom:8px;font-size:16px;'>").append(escapeHtml(t)).append("</div>");
                        try {
                            analystsSection.append(buildAnalystCard(t));
                            analystsSection.append(buildAnalystProxyAlphaVantageCard(t));
                        } catch (Exception ignore) {
                            analystsSection.append("<div style='color:#9ca3af'>Error loading analyst data</div>");
                        }
                        analystsSection.append("</div>");
                    }
                    analystsSection.append("</div>");
                    if (weeklyTickers.size() > 3) {
                        analystsSection.append("<button id='toggle-analysts' style='margin-top:8px;'>Show all analysts ("+weeklyTickers.size()+")</button>");
                        analystsSection.append("<script>(function(){var btn=document.getElementById('toggle-analysts');if(!btn)return;var expanded=false;btn.addEventListener('click',function(){expanded=!expanded;var extras=document.querySelectorAll('.extra-analyst');for(var i=0;i<extras.length;i++){extras[i].style.display=expanded?'':'none';}btn.textContent=expanded?'Show less analysts':'Show all analysts ("+weeklyTickers.size()+")';});})();</script>");
                    }
                    analystsSection.append("</div>");
                }

                // Monitoring History section for portfolio tickers
                StringBuilder monitoringSection = new StringBuilder();
                if (weeklyTickers != null && !weeklyTickers.isEmpty()) {
                    monitoringSection.append("<div class=\"card\"><div class=\"title\">📈 Monitoring History (Portfolio)</div>");
                    monitoringSection.append("<div style='color:#9ca3af;margin-bottom:10px;'>Signals for 2-10 trading days: <b>return</b>=estimated % move, <b>actual</b>=realized % move, <b>score</b>=signal strength. BUY=positive bias, DROP=negative bias, HOLD=neutral.</div>");

                    for (int i=0; i<weeklyTickers.size(); i++) {
                        String t = weeklyTickers.get(i);
                        String esc = escapeHtml(t);
                        String safeId = t == null ? "" : t.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");

                        MonitoringSnapshot snap = monitoringStore.loadSnapshot(t);
                        Map<String, Double> closeByDate = Map.of();
                        java.time.LocalDate snapDateNy = null;
                        try {
                            if (snap != null) {
                                snapDateNy = java.time.Instant.ofEpochMilli(snap.asOfEpochMillis()).atZone(NY).toLocalDate();
                            }
                            closeByDate = loadDailyCloseByDateCached(t);
                        } catch (Exception ignore) {}

                        String hidden = (i >= 3) ? " style='display:none;' class='extra-monitor'" : "";
                        monitoringSection.append("<div"+hidden+" style='border-top:1px solid rgba(148,163,184,0.25);padding-top:12px;margin-top:12px;'>");
                        monitoringSection.append("<div style='display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-bottom:8px;'>")
                                .append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'><b>")
                                .append(esc).append("</b></span>");

                        if (snap != null) {
                            try {
                                java.time.Instant upd = monitoringStore.snapshotUpdatedAt(t);
                                if (upd != null) {
                                    monitoringSection.append("<span style='color:#9ca3af'>saved: ").append(escapeHtml(upd.toString())).append("</span>");
                                }
                            } catch (Exception ignore) {}
                        }
                        monitoringSection.append("</div>");

                        if (snap == null) {
                            monitoringSection.append("<div style='color:#fbbf24;margin-bottom:8px;'>No snapshot yet. Add to Monitoring Stocks and refresh to generate signals.</div>");
                        } else {
                            monitoringSection.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px;'>");
                            for (String k : new String[]{"2","3","4","5","6","7","8","9","10"}) {
                                String rec = snap.recommendationByDays() == null ? null : snap.recommendationByDays().get(k);
                                Double ret = snap.returnsByDays() == null ? null : snap.returnsByDays().get(k);
                                Double sc = snap.scoreByDays() == null ? null : snap.scoreByDays().get(k);
                                String recText = rec == null ? "N/A" : rec;
                                String pillColor = recText.equals("BUY") ? "#22c55e" : (recText.equals("DROP") ? "#fca5a5" : "#93c5fd");

                                Double actual = null;
                                try { actual = currentReturnPctBackwards(closeByDate, Integer.parseInt(k)); } catch (Exception ignore) {}
                                String actualText = actual == null ? "N/A" : String.format("%+.2f%%", actual);
                                String actualColor = (actual == null) ? "#9ca3af" : (actual >= 0.0 ? "#22c55e" : "#fca5a5");
                                String pill = "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:4px 8px;font-size:12px;'>"+
                                        "<span style='color:#9ca3af;'>Last"+escapeHtml(k)+"d</span> " +
                                        "<span style='color:"+actualColor+";font-weight:700;'>"+escapeHtml(actualText)+"</span>" +
                                        " <span style='color:"+pillColor+";'>"+escapeHtml(recText)+"</span>" +
                                        "</span>";
                                monitoringSection.append(pill);
                            }
                            monitoringSection.append("</div>");

                            // Collapsible details
                            String detailsId = "port-snap-" + safeId;
                            monitoringSection.append("<button type='button' class='toggle-port-details' data-target='").append(detailsId).append("' style='font-size:12px;padding:4px 8px;'>+ Details</button>");
                            monitoringSection.append("<div id='").append(detailsId).append("' style='display:none;margin-top:10px'>");

                            if (snap.indicatorValues() != null && !snap.indicatorValues().isEmpty()) {
                                monitoringSection.append("<div style='margin-bottom:8px;'><b>Indicators:</b> ");
                                for (Map.Entry<String, Double> e : snap.indicatorValues().entrySet()) {
                                    String kk = escapeHtml(e.getKey());
                                    String vv = e.getValue() == null ? "" : String.format("%.2f", e.getValue());
                                    monitoringSection.append("<span style='margin-right:10px;'>").append(kk).append(": ").append(vv).append("</span>");
                                }
                                monitoringSection.append("</div>");
                            }

                            if (snap.fundamentals() != null && !snap.fundamentals().isEmpty()) {
                                monitoringSection.append("<div style='margin-bottom:8px;'><b>Fundamentals:</b> ");
                                for (Map.Entry<String, String> e : snap.fundamentals().entrySet()) {
                                    monitoringSection.append("<span style='margin-right:10px;'>").append(escapeHtml(e.getKey())).append(": ").append(escapeHtml(e.getValue())).append("</span>");
                                }
                                monitoringSection.append("</div>");
                            }

                            monitoringSection.append("</div>");
                        }
                        monitoringSection.append("</div>");
                    }

                    if (weeklyTickers.size() > 3) {
                        monitoringSection.append("<button id='toggle-monitors' style='margin-top:8px;'>Show all monitoring ("+weeklyTickers.size()+")</button>");
                        monitoringSection.append("<script>(function(){var btn=document.getElementById('toggle-monitors');if(!btn)return;var expanded=false;btn.addEventListener('click',function(){expanded=!expanded;var extras=document.querySelectorAll('.extra-monitor');for(var i=0;i<extras.length;i++){extras[i].style.display=expanded?'':'none';}btn.textContent=expanded?'Show less monitoring':'Show all monitoring ("+weeklyTickers.size()+")';});})();</script>");
                    }
                    monitoringSection.append("<script>(function(){var btns=document.querySelectorAll('.toggle-port-details');for(var i=0;i<btns.length;i++){btns[i].addEventListener('click',function(){var id=this.getAttribute('data-target');var el=document.getElementById(id);if(!el)return;var open=(el.style.display!=='none');el.style.display=open?'none':'';this.textContent=(open?'+ Details':'- Details');});}})();</script>");
                    monitoringSection.append("</div>");
                }

                String forceBtn = "<div class='card' style='padding:12px 16px;'>"+
                        "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>"+
                        "<form method='post' action='/portfolio-weekly' style='margin:0'>"+
                        "<input type='hidden' name='force' value='1'/>"+
                        "<button type='submit'>Force Refresh Weekly Report</button></form>"+
                        "<a href='/portfolio-manage' style='color:#93c5fd;text-decoration:none;'>Manage Portfolio</a>"+
                        "</div></div>";

                String html = htmlPage(forceBtn + "<div class=\"card\"><div class=\"title\">My Portfolio - Weekly</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + gallery.toString() + analystsSection.toString() + monitoringSection.toString()
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.25,now);beep(1100,0.25,now+0.3);beep(1320,0.25,now+0.6);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.start();
        System.out.println("Server running at http://localhost:" + port + "/ (threads=" + threads + ")");

        // Start automatic cache building in background
        startAutomaticCacheBuilding();

        startDailyNasdaqScheduler();
        startAlphaAgentScheduler();
        startDailyGreenRecommendationsScheduler();
    }

    private static void persistDailyGreenStateBestEffort() {
        try {
            synchronized (dailyGreenLock) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(dailyGreenPath.toFile(), dailyGreenState);
            }
        } catch (Exception ignore) {}
    }

    private static FinalScoringEngine.AnalysisResult scoreForDashboard(StockAnalysisResult r) {
        if (r == null) return null;

        double z = (r.altmanZ != null && Double.isFinite(r.altmanZ)) ? r.altmanZ : Double.NaN;
        double m = (r.beneishMScore != null && Double.isFinite(r.beneishMScore)) ? r.beneishMScore : Double.NaN;
        double sloan = (r.sloanRatio != null && Double.isFinite(r.sloanRatio)) ? r.sloanRatio : 0.0;
        double fScore = (r.piotroskiFScore == null) ? Double.NaN : r.piotroskiFScore.doubleValue();
        double peg = (r.pegRatio != null && Double.isFinite(r.pegRatio)) ? r.pegRatio : Double.NaN;

        double dcfMargin = Double.NaN;
        if (Double.isFinite(r.price) && r.price > 0 && Double.isFinite(r.dcfFairValue) && r.dcfFairValue > 0) {
            dcfMargin = (r.dcfFairValue - r.price) / r.price;
        }

        boolean technicalBullish = r.technicalSignal != null && r.technicalSignal.toUpperCase().contains("BUY") && !r.technicalSignal.toUpperCase().contains("SELL");
        double rsi = (r.latestRsi != null && Double.isFinite(r.latestRsi)) ? r.latestRsi : 50.0;

        double ccc = (r.cccDays != null && Double.isFinite(r.cccDays)) ? r.cccDays : Double.NaN;
        double spread = (r.economicSpread != null && Double.isFinite(r.economicSpread)) ? r.economicSpread : 0.0;
        double grahamMoS = (r.grahamMarginOfSafety != null && Double.isFinite(r.grahamMarginOfSafety)) ? r.grahamMarginOfSafety : Double.NaN;

        // If risk metrics missing, avoid triggering veto by using safe defaults
        double mForEngine = Double.isFinite(m) ? m : -999.0;
        double zForEngine = Double.isFinite(z) ? z : 99.0;
        double pegForEngine = Double.isFinite(peg) ? peg : 99.0;
        double dcfForEngine = Double.isFinite(dcfMargin) ? dcfMargin : 0.0;
        double cccForEngine = Double.isFinite(ccc) ? ccc : 999.0;
        double fForEngine = Double.isFinite(fScore) ? fScore : 0.0;

        return FinalScoringEngine.computeFinalScore(
                zForEngine, mForEngine, sloan,
                fForEngine, pegForEngine, dcfForEngine,
                technicalBullish, rsi,
                cccForEngine, spread, grahamMoS
        );
    }

    private static boolean isGreenBuy(FinalScoringEngine.AnalysisResult ar, StockAnalysisResult stockResult) {
        if (ar == null) return false;
        String rec = ar.recommendation == null ? "" : ar.recommendation.toUpperCase();
        ScoringConfig.ModeConfig cfg = ScoringConfig.getActiveModeConfig();
        int threshold = cfg.greenThreshold;
        
        // Debug logging for first few stocks
        if (Math.random() < 0.02) { // Log ~2% of stocks for debugging
            System.out.println("[DEBUG isGreenBuy] score=" + ar.finalScore + ", rec=" + ar.recommendation + 
                ", threshold=" + threshold + ", buyTh=" + cfg.buyThreshold);
        }
        
        if (rec.contains("AVOID") || rec.contains("SELL")) return false;
        if (!rec.contains("BUY")) return false;
        if (ar.finalScore < threshold) return false;
        
        // Apply AITool agent configuration filters if set
        String agentConfigId = ScoringConfig.getActiveAgentConfig();
        if (agentConfigId != null && !agentConfigId.isBlank() && stockResult != null) {
            AIToolAgent.AgentConfig agentCfg = AIToolAgent.getAgentConfig(agentConfigId);
            if (agentCfg != null && agentCfg.entryFilters != null) {
                // Get stock values
                double stockRsi = (stockResult.latestRsi != null && Double.isFinite(stockResult.latestRsi)) ? stockResult.latestRsi : 50.0;
                double stockRs = (stockResult.relativeStrength3M != null && Double.isFinite(stockResult.relativeStrength3M)) ? stockResult.relativeStrength3M : 1.0;
                double stockRvol = (stockResult.volumeRatio != null && Double.isFinite(stockResult.volumeRatio)) ? stockResult.volumeRatio : 1.0;
                
                // Check RSI range
                double rsiMin = getDoubleFromMap(agentCfg.entryFilters, "rsiMin", 0.0);
                double rsiMax = getDoubleFromMap(agentCfg.entryFilters, "rsiMax", 100.0);
                if (stockRsi < rsiMin || stockRsi > rsiMax) {
                    return false;
                }
                
                // Check RS (Relative Strength) minimum
                double rsMin = getDoubleFromMap(agentCfg.entryFilters, "rsMin", 0.0);
                if (rsMin > 0 && stockRs < rsMin) {
                    return false;
                }
                
                // Check RVOL minimum if available
                double rvolMin = getDoubleFromMap(agentCfg.entryFilters, "rvolMin", 0.0);
                if (rvolMin > 0 && stockRvol < rvolMin) {
                    return false;
                }
            }
        }
        
        return true;
    }

    private static boolean startDailyGreenRecommendationsRunAsync() {
        synchronized (dailyGreenLock) {
            if (dailyGreenState.running) return false;
            dailyGreenState.running = true;
            dailyGreenState.currentTicker = null;
            dailyGreenState.processed = 0;
            dailyGreenState.greenCount = 0;
            dailyGreenState.lastError = null;
            dailyGreenState.lastStartedNy = ZonedDateTime.now(NY).toString();
            dailyGreenState.lastFinishedNy = null;
            dailyGreenState.green = new ArrayList<>();
        }
        persistDailyGreenStateBestEffort();

        logDailyGreen("started (thread will run in background)");

        dailyGreenExec.submit(() -> {
            try {
                // Force reload and snapshot config ONCE at run start for thread-safety.
                // Any mode change made on another page while this scan runs will NOT
                // affect the in-flight scan because we captured an immutable snapshot here.
                ScoringConfig.forceReload();
                final ScoringConfig.ModeConfig runModeConfig = ScoringConfig.getActiveModeConfig();
                final String runMode = ScoringConfig.getActiveMode();
                
                try { StockScannerRunner.setPrintGrahamDetails(false); } catch (Exception ignore) {}
                List<String> universe = LongTermCandidateFinder.getUniverseTickers();
                int processed = 0;
                int green = 0;

                for (String sym : universe) {
                    if (sym == null || sym.isBlank()) continue;
                    String x = sym.trim().toUpperCase();

                    synchronized (dailyGreenLock) {
                        dailyGreenState.currentTicker = x;
                        dailyGreenState.processed = processed;
                        dailyGreenState.greenCount = green;
                    }
                    persistDailyGreenStateBestEffort();

                    StockAnalysisResult r;
                    try {
                        r = StockScannerRunner.analyzeSingleStock(x);
                    } catch (Exception e) {
                        r = null;
                    }

                    FinalScoringEngine.AnalysisResult ar = scoreForDashboard(r);
                    processed++;

                    if (isGreenBuy(ar, r)) {
                        DailyGreenTicker row = new DailyGreenTicker();
                        row.ticker = x;
                        row.finalScore = ar.finalScore;
                        row.recommendation = ar.recommendation;
                        row.lastUpdatedNy = ZonedDateTime.now(NY).toString();
                        synchronized (dailyGreenLock) {
                            dailyGreenState.green.add(row);
                            if (dailyGreenState.green.size() > DAILY_GREEN_MAX_ROWS) {
                                int overflow = dailyGreenState.green.size() - DAILY_GREEN_MAX_ROWS;
                                if (overflow > 0) {
                                    dailyGreenState.green.subList(0, overflow).clear();
                                }
                            }
                        }
                        green++;
                    }

                    synchronized (dailyGreenLock) {
                        dailyGreenState.processed = processed;
                        dailyGreenState.greenCount = green;
                    }
                    persistDailyGreenStateBestEffort();

                    try { Thread.sleep(15_000); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                synchronized (dailyGreenLock) {
                    dailyGreenState.lastError = e.getMessage();
                }
                logDailyGreen("error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                synchronized (dailyGreenLock) {
                    dailyGreenState.running = false;
                    dailyGreenState.currentTicker = null;
                    dailyGreenState.lastFinishedNy = ZonedDateTime.now(NY).toString();
                }
                persistDailyGreenStateBestEffort();

                try {
                    Integer p;
                    Integer g;
                    synchronized (dailyGreenLock) {
                        p = dailyGreenState.processed;
                        g = dailyGreenState.greenCount;
                    }
                    logDailyGreen("finished (processed=" + p + ", green=" + g + ")");
                } catch (Exception ignore) {}
            }
        });

        return true;
    }

    private static void startDailyGreenRecommendationsScheduler() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("daily-green-recos");
            return t;
        });

        long initialDelayMs = computeDelayToNextNyTime(9, 45);
        long periodMs = TimeUnit.DAYS.toMillis(1);

        exec.scheduleAtFixedRate(() -> {
            startDailyGreenRecommendationsRunAsync();
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private static class DailyPicksComputed {
        final String text;
        final List<String> tickers;
        final String error;
        DailyPicksComputed(String text, List<String> tickers, String error) {
            this.text = text;
            this.tickers = tickers;
            this.error = error;
        }
    }

    private static DailyPicksComputed computeDailyNasdaqTopPicks() {
        LocalDate today = LocalDate.now();

        synchronized (dailyPicksLock) {
            if (dailyPicksCache.date != null && dailyPicksCache.date.equals(today) && dailyPicksCache.text != null) {
                return new DailyPicksComputed(dailyPicksCache.text + "\n(loaded from today's cache)", dailyPicksCache.tickers, dailyPicksCache.lastError);
            }
        }

        String computedText;
        List<String> computedTickers;
        String computedError = null;
        try {
            LongTermCandidateFinder.configureThrottle(true, 2_500);
            LongTermCandidateFinder.setRandomPoolSize(10);
            LongTermCandidateFinder.setMaxTickers(10);
            computedText = runAndCapture(() -> {
                try { StockScannerRunner.setPrintGrahamDetails(false); } catch (Exception ignore) {}
                System.out.println("--- 🎯 Daily Nasdaq Top " + DAILY_TOP_PICK_COUNT + " (GREEN) candidates ---");
                java.util.List<StockAnalysisResult> topCandidates = LongTermCandidateFinder.findBestLongTermBuys(DAILY_TOP_PICK_COUNT, false);
                System.out.println("\n| TICKER | PRICE    | טכני (כניסה)    | פונדמנטלי         | ADX (חוזק) |\n" +
                        "|--------|----------|-----------------|-------------------|-----------|");
                if (topCandidates.isEmpty()) {
                    System.out.println("No GREEN candidates found today (based on current filters). Try again later.");
                } else {
                    for (StockAnalysisResult r : topCandidates) {
                        System.out.println(r);
                    }
                }
            });
            computedTickers = LongTermCandidateFinder.getLastTickers();
        } catch (Exception e) {
            computedText = "Error: " + e.getMessage();
            computedTickers = null;
            computedError = e.getMessage();
        }

        // Persist a NY-date tracking snapshot with open/close from daily candles.
        try {
            if (computedTickers != null && !computedTickers.isEmpty()) {
                String nyDate = nyToday();
                DailyTrackingSnapshot snap = bestEffortLoadTrackingSnapshot(nyDate);
                if (snap == null) {
                    snap = new DailyTrackingSnapshot();
                    snap.nyDate = nyDate;
                    snap.createdAtNy = ZonedDateTime.now(NY).toString();
                    snap.rows = new ArrayList<>();
                    int max = Math.min(DAILY_TOP_PICK_COUNT, computedTickers.size());
                    for (int i = 0; i < max; i++) {
                        String t = computedTickers.get(i);
                        if (t == null || t.isBlank()) continue;
                        snap.rows.add(new DailyTrackingRow(t.trim().toUpperCase()));
                    }
                }
                bestEffortRefreshTrackingPricesForSnapshot(snap);
                bestEffortPersistTrackingSnapshot(snap);
            }
        } catch (Exception ignore) {}

        synchronized (dailyPicksLock) {
            dailyPicksCache.date = today;
            dailyPicksCache.text = computedText;
            dailyPicksCache.tickers = computedTickers;
            dailyPicksCache.lastError = computedError;
        }

        // Persist for "last" viewing
        try {
            Path fdir = Paths.get("finder-cache");
            Files.createDirectories(fdir);
            Files.writeString(fdir.resolve("daily-top-last.txt"), computedText, StandardCharsets.UTF_8);
            if (computedTickers != null && !computedTickers.isEmpty()) {
                Files.writeString(fdir.resolve("daily-top-last-tickers.txt"), String.join(",", computedTickers), StandardCharsets.UTF_8);
            }
            if (computedError != null && !computedError.isBlank()) {
                Files.writeString(fdir.resolve("daily-top-last-error.txt"), computedError, StandardCharsets.UTF_8);
            } else {
                try { Files.deleteIfExists(fdir.resolve("daily-top-last-error.txt")); } catch (Exception ignore) {}
            }
            Files.writeString(fdir.resolve("daily-top-" + today + ".txt"), computedText, StandardCharsets.UTF_8);
        } catch (Exception ignore) {}

        return new DailyPicksComputed(computedText, computedTickers, computedError);
    }

    private static void bestEffortRefreshTrackingPricesForSnapshot(DailyTrackingSnapshot snap) {
        if (snap == null || snap.nyDate == null || snap.rows == null || snap.rows.isEmpty()) return;
        for (DailyTrackingRow r : snap.rows) {
            if (r == null || r.ticker == null || r.ticker.isBlank()) continue;
            String sym = r.ticker.trim().toUpperCase();
            try {
                DataFetcher.setTicker(sym);
                String json = DataFetcher.fetchStockData();
                if (json == null || json.isBlank()) continue;
                Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
                if (ohlc == null || ohlc.isEmpty()) continue;

                double[] day = ohlc.get(snap.nyDate);
                if (day == null) {
                    // If today's bar isn't available yet, use the most recent day as a fallback for startClose
                    String bestDate = null;
                    for (String d : ohlc.keySet()) {
                        if (bestDate == null || d.compareTo(bestDate) > 0) bestDate = d;
                    }
                    if (bestDate != null) {
                        day = ohlc.get(bestDate);
                        if (r.startClose == null && day != null && day.length >= 4 && !Double.isNaN(day[3])) {
                            r.startClose = day[3];
                        }
                        r.lastUpdatedNy = ZonedDateTime.now(NY).toString();
                    }
                    continue;
                }
                if (day.length >= 4) {
                    if (r.startOpen == null && !Double.isNaN(day[0])) r.startOpen = day[0];
                    if (r.startClose == null && !Double.isNaN(day[3])) r.startClose = day[3];
                    if (r.eodClose == null && !Double.isNaN(day[3])) r.eodClose = day[3];
                }
                r.lastUpdatedNy = ZonedDateTime.now(NY).toString();
            } catch (Exception ignore) {
            }
        }
    }

    private static void startDailyNasdaqScheduler() {
        // Run once per day (NY time). Background job only (daemon).
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("daily-nasdaq-top-scheduler");
            return t;
        });

        // Morning: generate picks after market opens (09:40 NY)
        long initialDelayMs = computeDelayToNextNyTime(9, 40);
        long periodMs = TimeUnit.DAYS.toMillis(1);
        exec.scheduleAtFixedRate(() -> {
            try {
                System.out.println("[DailyNasdaqTop] refresh started");
                computeDailyNasdaqTopPicks();
            } catch (Exception e) {
                System.out.println("[DailyNasdaqTop] refresh error: " + e.getMessage());
                synchronized (dailyPicksLock) {
                    dailyPicksCache.lastError = e.getMessage();
                }
            }
            System.out.println("[DailyNasdaqTop] refresh finished");
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);

        // After close: refresh EOD close prices (16:10 NY)
        ScheduledExecutorService closeExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("daily-nasdaq-top-eod-refresh");
            return t;
        });
        long initialCloseDelayMs = computeDelayToNextNyTime(16, 10);
        closeExec.scheduleAtFixedRate(() -> {
            try {
                String nyDate = nyToday();
                DailyTrackingSnapshot snap = bestEffortLoadTrackingSnapshot(nyDate);
                if (snap != null) {
                    bestEffortRefreshTrackingPricesForSnapshot(snap);
                    bestEffortPersistTrackingSnapshot(snap);
                }
            } catch (Exception ignore) {}
        }, initialCloseDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private static void loadCacheSettings() {
        try {
            java.io.File settingsFile = new java.io.File(CACHE_SETTINGS_FILE);
            if (settingsFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(settingsFile.toPath()));
                if (content.contains("\"autoBuildCacheOnStartup\":true")) {
                    autoBuildCacheOnStartup = true;
                }
                System.out.println("[CacheSettings] Loaded: autoBuildCacheOnStartup=" + autoBuildCacheOnStartup);
            }
        } catch (Exception e) {
            System.err.println("[CacheSettings] Error loading settings: " + e.getMessage());
        }
    }

    private static void saveCacheSettings() {
        try {
            String json = "{\"autoBuildCacheOnStartup\":" + autoBuildCacheOnStartup + "}";
            java.nio.file.Files.write(new java.io.File(CACHE_SETTINGS_FILE).toPath(), json.getBytes());
            System.out.println("[CacheSettings] Saved: autoBuildCacheOnStartup=" + autoBuildCacheOnStartup);
        } catch (Exception e) {
            System.err.println("[CacheSettings] Error saving settings: " + e.getMessage());
        }
    }

    private static void loadCacheState() {
        try {
            java.io.File stateFile = new java.io.File(CACHE_STATE_FILE);
            if (stateFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(stateFile.toPath()));
                if (content.contains("\"running\":true")) {
                    synchronized (cacheBuildLock) {
                        cacheBuildRunning = true;
                        cacheBuildStatus = "interrupted";
                        // Try to parse progress
                        if (content.contains("\"progress\":")) {
                            int idx = content.indexOf("\"progress\":");
                            int endIdx = content.indexOf(",", idx);
                            if (endIdx == -1) endIdx = content.indexOf("}", idx);
                            String progressStr = content.substring(idx + 11, endIdx).trim();
                            try {
                                cacheBuildProgress = Integer.parseInt(progressStr);
                            } catch (Exception ignore) {}
                        }
                        if (content.contains("\"total\":")) {
                            int idx = content.indexOf("\"total\":");
                            int endIdx = content.indexOf(",", idx);
                            if (endIdx == -1) endIdx = content.indexOf("}", idx);
                            String totalStr = content.substring(idx + 8, endIdx).trim();
                            try {
                                cacheBuildTotal = Integer.parseInt(totalStr);
                            } catch (Exception ignore) {}
                        }
                    }
                    System.out.println("[CacheState] Loaded interrupted build: progress=" + cacheBuildProgress + "/" + cacheBuildTotal);
                }
            }
        } catch (Exception e) {
            System.err.println("[CacheState] Error loading state: " + e.getMessage());
        }
    }

    private static void saveCacheState() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"running\":").append(cacheBuildRunning).append(",");
            json.append("\"status\":\"").append(cacheBuildStatus).append("\",");
            json.append("\"progress\":").append(cacheBuildProgress).append(",");
            json.append("\"total\":").append(cacheBuildTotal).append(",");
            json.append("\"startDate\":\"").append(cacheBuildStartDate).append("\",");
            json.append("\"endDate\":\"").append(cacheBuildEndDate).append("\"");
            json.append("}");
            java.nio.file.Files.write(new java.io.File(CACHE_STATE_FILE).toPath(), json.toString().getBytes());
            System.out.println("[CacheState] Saved state: " + cacheBuildStatus + " (" + cacheBuildProgress + "/" + cacheBuildTotal + ")");
        } catch (Exception e) {
            System.err.println("[CacheState] Error saving state: " + e.getMessage());
        }
    }

    private static void startAutomaticCacheBuilding() {
        // Check if auto-build is enabled
        if (!autoBuildCacheOnStartup) {
            System.out.println("[CacheBuild] Auto-build disabled in settings, skipping");
            return;
        }

        synchronized (cacheBuildLock) {
            if (cacheBuildRunning) {
                System.out.println("[CacheBuild] Already running, skipping");
                return;
            }
            cacheBuildRunning = true;
            cacheBuildStatus = "starting";
            cacheBuildProgress = 0;
            cacheBuildTotal = 0;
            cacheBuildStartDate = "2025-01-01";
            cacheBuildEndDate = java.time.LocalDate.now().toString();
        }

        Thread cacheThread = new Thread(() -> {
            try {
                System.out.println("[CacheBuild] Starting automatic cache building from " + cacheBuildStartDate + " to " + cacheBuildEndDate);
                
                synchronized (cacheBuildLock) {
                    cacheBuildStatus = "in_progress";
                }

                // Check if cache already exists
                MarketDataCache.MarketCache existingCache = MarketDataCache.loadCache();
                if (existingCache != null && existingCache.lastUpdated != null) {
                    System.out.println("[CacheBuild] Existing cache found, updated: " + existingCache.lastUpdated + " with " + existingCache.totalTickers + " tickers");
                    synchronized (cacheBuildLock) {
                        cacheBuildStatus = "completed";
                        cacheBuildProgress = existingCache.totalTickers;
                        cacheBuildTotal = existingCache.totalTickers;
                        cacheBuildRunning = false;
                    }
                    return;
                }

                // Get universe tickers
                List<String> tickers = LongTermCandidateFinder.getUniverseTickers();
                synchronized (cacheBuildLock) {
                    cacheBuildTotal = tickers.size();
                    cacheBuildProgress = 0;
                }
                System.out.println("[CacheBuild] Building cache for " + tickers.size() + " tickers");
                System.out.println("[CacheBuild] First 10 tickers: " + tickers.subList(0, Math.min(10, tickers.size())));

                // Actually build the cache with real data
                MarketDataCache.fetchAndCacheData(tickers);
                
                // Verify file was created
                java.io.File cacheFile = new java.io.File("market_data_cache.json");
                if (cacheFile.exists()) {
                    System.out.println("[CacheBuild] Cache file created successfully: " + cacheFile.getAbsolutePath() + " (" + cacheFile.length() + " bytes)");
                } else {
                    System.err.println("[CacheBuild] WARNING: Cache file was not created!");
                }
                
                synchronized (cacheBuildLock) {
                    cacheBuildProgress = tickers.size();
                    cacheBuildStatus = "completed";
                    cacheBuildRunning = false;
                    saveCacheState(); // Save final state
                }
                System.out.println("[CacheBuild] Cache building completed");
                
            } catch (Exception e) {
                System.err.println("[CacheBuild] Error: " + e.getMessage());
                e.printStackTrace();
                synchronized (cacheBuildLock) {
                    cacheBuildStatus = "error";
                    cacheBuildRunning = false;
                    saveCacheState(); // Save error state
                }
            }
        });
        cacheThread.setDaemon(true);
        cacheThread.setName("cache-build");
        cacheThread.start();
    }

    private static long computeDelayToNextHour(int hour) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    private static long computeDelayToNextNyTime(int hour, int minute) {
        ZonedDateTime now = ZonedDateTime.now(NY);
        ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    private static double getDoubleFromMap(java.util.Map<String, Object> map, String key, double defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatMarketCap(String raw) {
        try {
            double v = Double.parseDouble(raw);
            String[] units = {"", "K", "M", "B", "T"};
            int idx = 0;
            while (v >= 1000 && idx < units.length-1) { v /= 1000.0; idx++; }
            return String.format("%.2f%s", v, units[idx]);
        } catch (Exception e) { return raw; }
    }

    // Build an HTML table for Alpha Vantage TOP_GAINERS_LOSERS arrays
    private static String buildMoversTable(JsonNode arr, String title) {
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return "<div style='margin-top:10px;color:#9ca3af'>" + escapeHtml(title) + ": no data.</div>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-top:10px;'>");
        sb.append("<div style='font-weight:600;margin-bottom:6px;'>").append(escapeHtml(title)).append("</div>");
        sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr>")
                .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:left;'>Symbol</th>")
                .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:left;'>Name</th>")
                .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Price</th>")
                .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Change %</th>")
                .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Volume</th>")
                .append("</tr></thead><tbody>");

        int maxRows = Math.min(25, arr.size());
        for (int i = 0; i < maxRows; i++) {
            JsonNode n = arr.get(i);
            String symbol = n.path("ticker").asText("");
            if (symbol.isEmpty()) symbol = n.path("symbol").asText("");
            String name = n.path("name").asText("");
            String price = n.path("price").asText("");
            if (price.isEmpty()) price = n.path("price_current").asText("");
            String changePct = n.path("change_percentage").asText("");
            if (changePct.isEmpty()) changePct = n.path("change_percent").asText("");
            String volume = n.path("volume").asText("");

            sb.append("<tr>")
                    .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;'>").append(escapeHtml(symbol)).append("</td>")
                    .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;'>").append(escapeHtml(name)).append("</td>")
                    .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(price)).append("</td>")
                    .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(changePct)).append("</td>")
                    .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(volume)).append("</td>")
                    .append("</tr>");
        }

        sb.append("</tbody></table></div></div>");
        return sb.toString();
    }
}
