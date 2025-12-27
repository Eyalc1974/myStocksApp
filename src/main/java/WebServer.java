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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebServer {

    private static final int ALPHA_AGENT_MAX_LISTS = 4;

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Object RUN_CAPTURE_LOCK = new Object();

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
        try {
            if (symbol == null || symbol.isBlank()) return null;
            String sym = symbol.trim().toUpperCase();
            Path p = Paths.get("finder-cache").resolve("daily-" + sym + ".json");
            if (!Files.exists(p)) return null;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            return (s == null || s.isBlank()) ? null : s;
        } catch (Exception ignore) {
            return null;
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
                    // This will fetch + write finder-cache/daily-{sym}.json if cache is missing/old.
                    DataFetcher.setTicker(t);
                    String json = DataFetcher.fetchStockData();
                    try {
                        Path cacheFile = Paths.get("finder-cache").resolve("daily-" + t + ".json");
                        Files.createDirectories(cacheFile.getParent());
                        Files.writeString(cacheFile, json == null ? "" : json, StandardCharsets.UTF_8);
                    } catch (Exception ignore) {}

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
    }

    private static Double extractGlobalQuotePrice(JsonNode root) {
        try {
            if (root == null) return null;
            JsonNode q = root.path("Global Quote");
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
                "    <div style=\"margin-top:4px;color:#9ca3af;\">מבצע חישוב, ייתכן שיימשך עד דקה ⏳</div>" +
                "</div>" +
                "</div>" +
                "<div class=\"container\">" +
                "<h1>AlphaPoint AI</h1>" +
                "<div style=\"margin-bottom:16px;\">"+
                "<a href=\"/\">Home</a> · "+
                "<a href=\"/about\">About</a> · "+
                "<a href=\"/favorites\">Favorites</a> · "+
                "<a href=\"/portfolio-manage\">Manage Portfolio</a> · "+
                "<a href=\"/alpha-agent\">AlphaAgent AI</a> · "+
                "<a href=\"/monitoring\">Monitoring Stocks - History</a> · "+
                "<a href=\"/intraday-alerts\">Intraday Alerts</a> · "+
                "<a href=\"/analysts\">Analysts</a> · "+
                "<a href=\"/finder\">FINDER</a>"+
                "</div>" +
                (body == null ? "" : body) +
                "</div>" +
                "<script>(function(){function show(){var el=document.getElementById('loading');if(el){el.style.display='flex';}};var forms=document.querySelectorAll('form');forms.forEach(function(f){f.addEventListener('submit',function(){show();});});})();</script>" +
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
                "Piotroski F-Score, Altman Z-Score, Beneish M-Score, Sloan Ratio, Quality &amp; Profitability, Growth, Valuation Mix, " +
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
        try {
            if (symbol == null || symbol.isBlank()) return Map.of();
            String sym = symbol.trim().toUpperCase();
            Path dir = Paths.get("finder-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve("daily-" + sym + ".json");
            String json = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 6L * 60 * 60 * 1000) {
                        json = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }
            if (json == null) {
                try { DataFetcher.setTicker(sym); } catch (Exception ignore) {}
                json = DataFetcher.fetchStockData();
                try { Files.writeString(cacheFile, json == null ? "" : json, StandardCharsets.UTF_8); } catch (Exception ignore) {}
            }
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

        if (volSpike && pct >= 0.50) return "BUY";
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
        try {
            String token = System.getenv("TELEGRAM_BOT_TOKEN");
            String chat = System.getenv("TELEGRAM_CHAT_ID");
            if (token == null || token.isBlank() || chat == null || chat.isBlank()) return false;
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "chat_id=" + java.net.URLEncoder.encode(chat, StandardCharsets.UTF_8) +
                    "&text=" + java.net.URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8) +
                    "&disable_web_page_preview=true";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static String buildAnalystProxyAlphaVantageCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String sym = symbol.trim().toUpperCase();

            Path dir = Paths.get("analysts-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(sym + "-alphavantage.json");
            String cachedJson = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 24L * 60 * 60 * 1000) {
                        cachedJson = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }

            ObjectMapper om = new ObjectMapper();
            JsonNode combined;
            if (cachedJson == null) {
                MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                JsonNode overview = null;
                JsonNode news = null;
                try { overview = av.overview(sym); } catch (Exception ignore) {}
                try { news = av.newsSentiment(sym); } catch (Exception ignore) {}
                String combinedJson = "{\n\"overview\": " + (overview == null ? "{}" : overview.toString()) + ",\n\"news\": " + (news == null ? "{}" : news.toString()) + "\n}";
                try { Files.writeString(cacheFile, combinedJson, StandardCharsets.UTF_8); } catch (Exception ignore) {}
                combined = om.readTree(combinedJson);
            } else {
                combined = om.readTree(cachedJson);
            }

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

    // -------- Analyst data (Finnhub) with 24h cache ---------
    private static String buildAnalystCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String key = System.getenv("FINNHUB_API_KEY");
            if (key == null || key.isBlank()) {
                return "<div class='card'><div class='title'>Analyst Consensus</div><div style='color:#9ca3af'>(Set FINNHUB_API_KEY to enable analyst data)</div></div>";
            }
            Path dir = Paths.get("analysts-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(symbol.toUpperCase()+".json");
            String combinedJson = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 24L*60*60*1000) {
                        combinedJson = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode combined = null;
            if (combinedJson == null) {
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
                combinedJson = "{\n\"recommendation\": "+body1+",\n\"priceTarget\": "+body2+"\n}";
                try { Files.writeString(cacheFile, combinedJson, StandardCharsets.UTF_8); } catch (Exception ignore) {}
            }
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
            try {
                System.setOut(capture);
                System.setErr(capture);
                r.run();
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            return bos.toString(StandardCharsets.UTF_8);
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
        String sym = ticker == null ? "" : ticker.trim().toUpperCase();
        if (sym.isBlank()) return null;

        Path cacheDir = Paths.get("finder-cache");
        Path cacheFile = cacheDir.resolve("daily-" + sym + ".json");
        long maxAgeMin = resolveTrendCacheMaxAgeMinutes();
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception ignore) {}

        try {
            if (Files.exists(cacheFile)) {
                long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis();
                long maxAgeMs = maxAgeMin * 60_000L;
                if (maxAgeMs <= 0 || ageMs <= maxAgeMs) {
                    return Files.readString(cacheFile, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignore) {}

        try {
            DataFetcher.setTicker(sym);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) return json;
            try {
                Path tmp = cacheDir.resolve("daily-" + sym + ".json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignore2) {}
            return json;
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

    public static void main(String[] args) throws Exception {
        int port = resolvePort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        int threads = resolveServerThreads();

        // Simple once-a-day cache for Portfolio Weekly output
        final Object portfolioCacheLock = new Object();
        final class PortfolioCache {
            String text;
            LocalDate date;
        }
        final PortfolioCache portfolioCache = new PortfolioCache();

        // Disk persistence for today's weekly report text
        final Path weeklyCacheDir = Paths.get("weekly-cache");
        try { Files.createDirectories(weeklyCacheDir); } catch (Exception ignore) {}
        try {
            LocalDate today = LocalDate.now();
            Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
            if (Files.exists(file)) {
                String cached = Files.readString(file, StandardCharsets.UTF_8);
                portfolioCache.text = cached;
                portfolioCache.date = today;
            }
        } catch (Exception ignore) {}

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
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
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

        // Manage portfolio page (view / add / remove)
        server.createContext("/portfolio-manage", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }
                List<String> items = PortfolioWeeklySummary.getPortfolio();
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Portfolio Actions</div>");
                sb.append(modelsUsedNamesOnlyHtml());
                sb.append("<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>");
                sb.append("<form method='post' action='/portfolio-weekly' style='margin:0'><button type='submit'>My Portfolio - Weekly</button></form>");
                sb.append("<form method='post' action='/portfolio-weekly' style='margin:0'><input type='hidden' name='force' value='1'/><button type='submit'>My Portfolio - Weekly (Force Refresh)</button></form>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div class='card'><div class='title'>Manage Portfolio</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:8px;'>Current tickers ("+items.size()+"):</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;'>");
                for (String t : items) {
                    sb.append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>")
                            .append(escapeHtml(t)).append("</span>");
                }
                sb.append("</div>");
                sb.append("<form method='post' action='/portfolio-add' style='margin-bottom:10px;'>" +
                        "<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required /> " +
                        "<button type='submit'>Add</button></form>");
                sb.append("<form method='post' action='/portfolio-remove'>" +
                        "<input type='text' name='symbol' placeholder='Remove ticker (e.g. AAPL)' required /> " +
                        "<button type='submit'>Remove</button></form>");
                sb.append("<div style='color:#9ca3af;margin-top:8px;'>Note: Changes reset today's cache for the weekly report.</div>");
                sb.append("</div>");
                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

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
                        Double price = extractGlobalQuotePrice(q);
                        synchronized (intradayLock) {
                            intradayState.lastQuotePrice = price;
                            if (price == null) {
                                intradayState.lastQuoteError = "No price in GLOBAL_QUOTE response (rate limit or symbol unsupported)";
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
                synchronized (portfolioCacheLock) { // invalidate daily cache
                    try {
                        java.lang.reflect.Field fText = portfolioCache.getClass().getDeclaredField("text");
                        java.lang.reflect.Field fDate = portfolioCache.getClass().getDeclaredField("date");
                        fText.setAccessible(true);
                        fDate.setAccessible(true);
                        fText.set(portfolioCache, null);
                        fDate.set(portfolioCache, null);
                    } catch (Exception ignore) {}
                }
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
                synchronized (portfolioCacheLock) { // invalidate daily cache
                    try {
                        java.lang.reflect.Field fText = portfolioCache.getClass().getDeclaredField("text");
                        java.lang.reflect.Field fDate = portfolioCache.getClass().getDeclaredField("date");
                        fText.setAccessible(true);
                        fDate.setAccessible(true);
                        fText.set(portfolioCache, null);
                        fDate.set(portfolioCache, null);
                    } catch (Exception ignore) {}
                }
                // Persist portfolio to disk
                try { Files.write(portfolioPath, (String.join("\n", PortfolioWeeklySummary.getPortfolio())+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/portfolio-manage?status=" + (ok?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // ---------------- Monitoring Stocks (separate module) ----------------
        final MonitoringStore monitoringStore = MonitoringStore.defaultStore();
        final MonitoringAlphaVantageClient monitoringClient = MonitoringAlphaVantageClient.fromEnv();
        final MonitoringAnalyzer monitoringAnalyzer = new MonitoringAnalyzer(monitoringClient);
        final MonitoringScheduler monitoringScheduler = new MonitoringScheduler(monitoringStore, monitoringAnalyzer);
        // Run Mon–Fri on New York time at: 09:30, 11:30, 13:30, 15:30 ET
        try {
            monitoringScheduler.startNyseWeekdayEvery2Hours();
        } catch (Exception ignore) {}

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
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Pick stocks to monitor. Each run saves a snapshot and shows signals for the next 2–10 <b>trading</b> days: <b>return</b>=estimated % move, <b>actual</b>=realized % move once enough days passed, <b>score</b>=signal strength.</div>");
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
                        try { actual = realizedReturnPct(closeByDate, snapDateNy, Integer.parseInt(k)); } catch (Exception ignore) {}
                        if (actual == null && ret != null) actual = ret;
                        String actualText = actual == null ? "N/A" : String.format("%+.2f%%", actual);
                        String actualColor = (actual == null) ? "#9ca3af" : (actual >= 0.0 ? "#22c55e" : "#fca5a5");
                        String pill = "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>"+
                                "<span style='color:#9ca3af;'>"+escapeHtml(k)+"d</span> " +
                                "<span style='color:"+pillColor+";font-weight:700;'>"+escapeHtml(recText)+"</span>" +
                                "<span style='color:#9ca3af;'> · return "+(ret==null?"N/A":String.format("%.2f%%", ret))+"</span>" +
                                "<span style='color:#9ca3af;'> · actual <span style='color:"+actualColor+";font-weight:700;'>"+escapeHtml(actualText)+"</span></span>" +
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
                Map<String, String> qp = parseQueryParams(ex.getRequestURI() == null ? null : ex.getRequestURI().getRawQuery());
                String status = qp.getOrDefault("status", "");
                String days = qp.getOrDefault("days", String.valueOf(ALPHA_AGENT_DEFAULT_TRACKING_DAYS));

                String pid = qp.getOrDefault("pid", "");

                AlphaAgentPortfolio pf;
                AlphaAgentStore store;
                String activeId;
                synchronized (alphaAgentLock) {
                    store = bestEffortLoadAlphaAgentStore();
                    if (pid != null && !pid.isBlank()) bestEffortSetActiveAlphaAgentPortfolioId(pid);
                    activeId = bestEffortGetActiveAlphaAgentPortfolioId();
                    pf = (pid != null && !pid.isBlank()) ? bestEffortLoadAlphaAgentPortfolioById(pid) : bestEffortLoadAlphaAgentPortfolio();
                }

                String effectivePid = (pid != null && !pid.isBlank()) ? pid : (activeId == null ? "" : activeId);

                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>AlphaAgent AI</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Real-time means scheduled refresh (~3x/day NY). Portfolio is fixed for the evaluation period (no rebalancing).</div>");
                sb.append(modelsUsedNamesOnlyHtml());
                if (!status.isEmpty()) sb.append("<div style='margin-bottom:10px;color:#93c5fd;'>Status: ").append(escapeHtml(status)).append("</div>");
                sb.append("<div id='aa-last-error' style='margin-bottom:10px;color:#fca5a5'></div>");

                int listCount = store == null || store.portfolios == null ? 0 : store.portfolios.size();
                StringBuilder controls = new StringBuilder();
                controls.append("<div class='card'><div class='title'>AlphaAgent Lists</div>");
                controls.append("<div style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:0'>");
                controls.append("<form method='post' action='/alpha-agent/select' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:0'>");
                controls.append("<label style='color:#9ca3af'>List</label>");
                controls.append("<select name='pid'>");
                if (store != null && store.portfolios != null && !store.portfolios.isEmpty()) {
                    int i = 1;
                    for (Map.Entry<String, AlphaAgentPortfolio> e : store.portfolios.entrySet()) {
                        String id = e.getKey();
                        boolean sel = id != null && id.equals(effectivePid);
                        AlphaAgentPortfolio p = e.getValue();
                        boolean custom = p != null && p.userManaged;
                        String label = custom ? ("Custom") : ("List " + i);
                        controls.append("<option value='").append(escapeHtml(id)).append("'").append(sel ? " selected" : "").append(">").append(escapeHtml(label)).append("</option>");
                        i++;
                    }
                }
                controls.append("</select>");
                controls.append("<button type='submit'>Switch</button>");
                controls.append("</form>");

                controls.append("<form method='post' action='/alpha-agent/drop' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:0'>");
                controls.append("<input type='hidden' name='pid' value='").append(escapeHtml(effectivePid)).append("'/>");
                controls.append("<button type='submit'>Drop List</button>");
                controls.append("</form>");
                controls.append("</div>");

                controls.append("<form method='post' action='/alpha-agent/start' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:10px'>");
                controls.append("<input type='hidden' name='pid' value='").append(escapeHtml(effectivePid)).append("'/>");
                controls.append("<input type='hidden' name='createNew' value='1'/>");
                controls.append("<input type='number' name='evaluationPeriodDays' min='2' max='60' value='").append(escapeHtml(days)).append("' />");
                controls.append("<button type='submit'>Start New AlphaAgent List</button>");
                controls.append("<div style='color:#9ca3af'>Saved lists: ").append(listCount).append("/").append(ALPHA_AGENT_MAX_LISTS).append("</div>");
                controls.append("</form>");

                boolean isCustom = pf != null && pf.userManaged;
                if (!isCustom) {
                    controls.append("<form method='post' action='/alpha-agent/custom-create' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:10px'>");
                    controls.append("<input type='number' name='evaluationPeriodDays' min='2' max='60' value='").append(escapeHtml(days)).append("' />");
                    controls.append("<button type='submit'>Create Custom List</button>");
                    controls.append("<div style='color:#9ca3af'>Create an empty list you can fill with tickers</div>");
                    controls.append("</form>");
                }

                if (isCustom) {
                    controls.append("<form method='post' action='/alpha-agent/custom-save' style='display:flex;flex-direction:column;gap:10px;margin-top:10px'>");
                    controls.append("<input type='hidden' name='pid' value='").append(escapeHtml(effectivePid)).append("'/>");
                    controls.append("<div style='color:#9ca3af'>Custom tickers (comma / space / newline separated)</div>");
                    controls.append("<textarea name='tickers' rows='3' style='width:100%;max-width:900px'>");
                    if (pf != null && pf.positions != null && !pf.positions.isEmpty()) {
                        StringBuilder t = new StringBuilder();
                        for (AlphaAgentPosition p : pf.positions) {
                            if (p == null || p.ticker == null || p.ticker.isBlank()) continue;
                            if (t.length() > 0) t.append(", ");
                            t.append(p.ticker.trim().toUpperCase());
                        }
                        controls.append(escapeHtml(t.toString()));
                    }
                    controls.append("</textarea>");
                    controls.append("<button type='submit'>Save Custom Tickers (Set Entry Date + Entry Price)</button>");
                    controls.append("</form>");
                }

                controls.append("<form method='post' action='/alpha-agent/refresh' style='display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:10px'>");
                controls.append("<input type='hidden' name='pid' value='").append(escapeHtml(effectivePid)).append("'/>");
                controls.append("<button type='submit'>Refresh Prices Now</button>");
                controls.append("<div style='color:#9ca3af'>Fetch latest quote for this list and update % Net P/L (falls back to last close if rate-limited)</div>");
                controls.append("</form>");
                controls.append("</div>");

                sb.append("<div class='card'><div class='title'>AlphaAgent Stock Table</div><div style='overflow:auto'><table style='width:100%;border-collapse:collapse'>");
                sb.append("<thead><tr><th style='text-align:left;padding:8px 10px;border-bottom:1px solid #1f2a44'>Symbol</th><th style='text-align:left;padding:8px 10px;border-bottom:1px solid #1f2a44'>Entry Date</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>Entry</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>Current</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>% Net P/L</th></tr></thead>");
                sb.append("<tbody id='aa-stocks'><tr><td colspan='5' style='padding:10px;color:#9ca3af'>Loading...</td></tr></tbody></table></div></div>");

                sb.append("<div class='card'><div class='title'>Index Comparison</div><div style='overflow:auto'><table style='width:100%;border-collapse:collapse'>");
                sb.append("<thead><tr><th style='text-align:left;padding:8px 10px;border-bottom:1px solid #1f2a44'>Index</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>Entry</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>Current</th><th style='text-align:right;padding:8px 10px;border-bottom:1px solid #1f2a44'>% Net P/L</th></tr></thead>");
                sb.append("<tbody id='aa-index'><tr><td colspan='4' style='padding:10px;color:#9ca3af'>Loading...</td></tr></tbody></table></div></div>");

                sb.append("<div class='card'><div class='title'>Race Result Summary</div><div id='aa-race' style='color:#9ca3af'>Loading...</div></div>");

                sb.append(controls);

                sb.append("<script>(function(){"+
                        "function fmt(x){if(x===null||x===undefined||isNaN(x))return 'N/A';return (x>=0?'+':'')+x.toFixed(2)+'%';}"+
                        "function money(x){if(x===null||x===undefined||isNaN(x))return 'N/A';return '$'+x.toFixed(2);}"+
                        "var pid='" + escapeHtml(effectivePid) + "';"+
                        "async function load(){try{"+
                        "var st=await fetch('/alpha-agent/status?pid='+encodeURIComponent(pid));var stj=await st.json();"+
                        "var le=document.getElementById('aa-last-error');"+
                        "if(stj&&stj.lastError){le.textContent='Last error: '+stj.lastError;}else{le.textContent='';}"+
                        "var s=await fetch('/alpha-agent/stocks?pid='+encodeURIComponent(pid));var stocks=await s.json();"+
                        "var tb=document.getElementById('aa-stocks');"+
                        "if(!stocks||!stocks.length){tb.innerHTML=`<tr><td colspan='5' style='padding:10px;color:#9ca3af'>No active portfolio. Click Start.</td></tr>`;}"+
                        "else{var rows='';for(var i=0;i<stocks.length;i++){var r=stocks[i];var p=r.netProfitPct;var c=(p===null||p===undefined||isNaN(p))?'#9ca3af':(p>=0?'#22c55e':'#fca5a5');"+
                        "rows+=`<tr><td style='padding:8px 10px;border-bottom:1px solid #111827'>${r.symbol||''}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;color:#9ca3af'>${r.entryDate||''}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right'>${money(r.entryPrice)}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right'>${money(r.currentPrice)}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right;color:${c};font-weight:700'>${fmt(p)}</td></tr>`;}tb.innerHTML=rows;}"+
                        "var ix=await fetch('/alpha-agent/index-performance?pid='+encodeURIComponent(pid));var idx=await ix.json();"+
                        "var itb=document.getElementById('aa-index');"+
                        "if(!idx||!idx.length){itb.innerHTML=`<tr><td colspan='4' style='padding:10px;color:#9ca3af'>N/A</td></tr>`;}"+
                        "else{var rows2='';for(var j=0;j<idx.length;j++){var r2=idx[j];var p2=r2.netProfitPct;var c2=(p2===null||p2===undefined||isNaN(p2))?'#9ca3af':(p2>=0?'#22c55e':'#fca5a5');"+
                        "rows2+=`<tr><td style='padding:8px 10px;border-bottom:1px solid #111827'>${r2.indexName||''}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right'>${money(r2.entryValue)}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right'>${money(r2.currentValue)}</td>`+"+
                        "`<td style='padding:8px 10px;border-bottom:1px solid #111827;text-align:right;color:${c2};font-weight:700'>${fmt(p2)}</td></tr>`;}itb.innerHTML=rows2;}"+
                        "var rr=await fetch('/alpha-agent/race-result?pid='+encodeURIComponent(pid));var race=await rr.json();"+
                        "var el=document.getElementById('aa-race');"+
                        "if(race&&race.message){el.innerHTML=`<div style='font-weight:700;color:#e5e7eb'>${race.message}</div>`+"+
                        "`<div style='margin-top:6px'>AlphaAgent Avg: <span style='color:#e5e7eb'>${fmt(race.alphaAgentAvgPct)}</span></div>`+"+
                        "`<div>NASDAQ-100 (QQQ): <span style='color:#e5e7eb'>${fmt(race.nasdaq100Pct)}</span></div>`+"+
                        "`<div>S&P 500 (SPY): <span style='color:#e5e7eb'>${fmt(race.sp500Pct)}</span></div>`;}"+
                        "else{el.textContent='N/A';}"+
                        "}catch(e){try{document.getElementById('aa-race').textContent='Error: '+e;}catch(_){}}}"+
                        "load();setInterval(load,15000);})();</script>");

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
                    String winner = "AlphaAgent AI";
                    if (qqq != null && qqq > best) { best = qqq; winner = "NASDAQ-100 (QQQ)"; }
                    if (spy != null && spy > best) { best = spy; winner = "S&P 500 (SPY)"; }
                    msg = winner.equals("AlphaAgent AI") ? "AlphaAgent AI is leading the race" : (winner + " outperforms AlphaAgent");
                }
                out.put("message", msg);
                respondJson(ex, out, 200);
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
                sb.append("</div>");

                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Telegram: set <code>TELEGRAM_BOT_TOKEN</code> and <code>TELEGRAM_CHAT_ID</code> to receive alerts.</div>");
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
                            JsonNode intraday = av.timeSeriesIntraday(st.symbol, intradayIntervalForCurrentEntitlement());
                            String sig = computeIntradaySignal(intraday, st);
                            st.lastSignal = sig;
                            st.lastError = null;
                            if (("BUY".equals(sig) || "SELL".equals(sig)) && st.lastBarTs != null) {
                                boolean already = sig.equals(st.lastNotifiedSignal) && st.lastBarTs.equals(st.lastNotifiedBarTs);
                                if (!already) {
                                    String msg = "Intraday alert: " + sig + " " + st.symbol + " (bar " + st.lastBarTs + ") price=" + (st.lastPrice==null?"N/A":String.format("%.4f", st.lastPrice)) + " vol=" + (st.lastVolume==null?"N/A":String.valueOf(st.lastVolume));
                                    st.history.add(ZonedDateTime.now(NY).toString() + " | " + msg);
                                    sendTelegram(msg);
                                    st.lastNotifiedSignal = sig;
                                    st.lastNotifiedBarTs = st.lastBarTs;
                                }
                            }
                        } catch (Exception e) {
                            st.lastError = e.getMessage();
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

        // About page with model explanations and links
        server.createContext("/about", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String TECH = modelBadge("TECHNICAL");
                String FUND = modelBadge("FUNDAMENTAL");
                String RISK = modelBadge("RISK");
                String content = "" +
                        "<div class=\"card\"><div class=\"title\">אודות המודלים (About)</div>" +
                        "<p>העמוד מפרט את המודלים שבהם האפליקציה משתמשת לניתוח טכני ופונדמנטלי, כולל קישורים ללמידה נוספת.</p>" +
                        "<ul>" +
                        "<li><b>Piotroski F-Score</b>" + FUND + " — 9 בדיקות בינאריות של רווחיות/מינוף/יעילות לדירוג מניות ערך.</li>" +
                        "<li><b>Altman Z-Score</b>" + RISK + " — מדד סיכון פשיטת-רגל המבוסס על יחסים מאזניים.</li>" +
                        "<li><b>Beneish M-Score</b>" + RISK + " — מודל סטטיסטי לזיהוי סבירות למניפולציה חשבונאית (earnings manipulation) על בסיס דוחות שנתיים. סף נפוץ: M-Score > -1.78 = חשד גבוה. באפליקציה: אם יש חשד, המניה מקבלת ענישה בניקוד ובמצבים מסוימים יורדת ל-AVOID.</li>" +
                        "<li><b>Sloan Ratio</b>" + RISK + " — מדד איכות רווחים (Accruals): מודד פער בין רווח נקי לבין תזרים מזומנים (FCF/Operating Cash Flow) ביחס לסך הנכסים. ערך מוחלט גבוה (למשל |ratio| > 0.25) עשוי להעיד על איכות רווחים נמוכה. באפליקציה: משמש כגורם סיכון שיכול להוריד את ה-Final Verdict ל-AVOID.</li>" +
                        "<li><b>Quality & Profitability</b>" + FUND + " — ROIC, ROE, שיעור רווח גולמי, FCF Margin, EBIT Margin ומגמות (YoY/TTM).</li>" +
                        "<li><b>Growth</b>" + FUND + " — קצב צמיחת הכנסות/EPS (CAGR ל-3/5 שנים), יציבות הצמיחה (סטיית תקן).</li>" +
                        "<li><b>Valuation Mix</b>" + FUND + " — P/B (מכפיל הון), EV/EBITDA, EV/Sales, PEG עם בדיקות סבירות לצמיחה.</li>" +
                        "<li><b>SMA (Simple Moving Average)</b>" + TECH + " — ממוצע נע פשוט למדידת מגמה. <a href=\"https://www.investopedia.com/terms/s/sma.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>RSI (Relative Strength Index)</b>" + TECH + " — מזהה קניות/מכירות יתר (70/30). <a href=\"https://www.investopedia.com/terms/r/rsi.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>MACD</b>" + TECH + " — מומנטום ושינוי מגמה באמצעות ממוצעים מעריכיים. <a href=\"https://www.investopedia.com/terms/m/macd.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Stochastic Oscillator</b>" + TECH + " — השוואת סגירה לטווח המחירים (%K/%D, אזורי 20/80). <a href=\"https://www.investopedia.com/terms/s/stochasticoscillator.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Bollinger Bands</b>" + TECH + " — מדד תנודתיות; רצועות עליונה/תחתונה. <a href=\"https://www.investopedia.com/terms/b/bollingerbands.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>ADX (Average Directional Index)</b>" + TECH + " — חוזק מגמה; מעל ~25 מגמה חזקה. <a href=\"https://www.investopedia.com/terms/a/adx.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>ATR (Average True Range)</b>" + TECH + " — תנודתיות יומית ממוצעת, שימוש בניהול סיכונים ו-Stop-Loss. <a href=\"https://www.investopedia.com/terms/a/atr.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>CMF (Chaikin Money Flow)</b>" + TECH + " — צבירה/פיזור לפי מחיר ונפח. <a href=\"https://www.investopedia.com/terms/c/chaikinmoneyflow.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Pivot Points</b>" + TECH + " — נקודת ציר יומית (PP) ורמות תמיכה/התנגדות. <a href=\"https://www.investopedia.com/terms/p/pivotpoint.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Fibonacci Retracement</b>" + TECH + " — רמות 38.2%/50%/61.8% לאיתור אזורי כניסה לאחר תיקון. <a href=\"https://www.investopedia.com/terms/f/fibonacciretracement.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>DCF (Discounted Cash Flow)</b>" + FUND + " — שווי פנימי מבוסס תחזית תזרימי מזומנים. <a href=\"https://www.investopedia.com/terms/d/dcf.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>PEG Ratio</b>" + FUND + " — יחס P/E לצמיחה; ~1 הוגן, <1 זול, >2 יקר. <a href=\"https://www.investopedia.com/terms/p/pegratio.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "</ul>" +
                        "<p style=\"color:#9ca3af\">המודלים מוצגים לצורכי לימוד בלבד ואינם מהווים ייעוץ השקעות.</p>" +
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // ---------------- Favorites (persistent simple file) ----------------
        final Object favLock = new Object();
        final Path favPath = Paths.get("favorites.txt");
        final class FavStore { java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>(); }
        final FavStore fav = new FavStore();

        // Load favorites at startup
        try {
            if (Files.exists(favPath)) {
                for (String line : Files.readAllLines(favPath, StandardCharsets.UTF_8)) {
                    String t = line == null ? "" : line.trim().toUpperCase();
                    if (!t.isEmpty()) fav.items.add(t);
                }
            }
        } catch (Exception ignore) {}

        server.createContext("/favorites", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Favorites</div>");
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
                                    .append("<form method='post' action='/run-main' style='display:inline'>")
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
                ex.getResponseHeaders().add("Location", "/favorites?status="+(removed?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1); ex.close();
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

                            modelSummaryCard = "<div class='card'><div class='title'>Model Summary</div>" +
                                    "<div style='display:flex;flex-direction:column;gap:8px'>" +
                                    "<div><b>Price</b>: <span style='color:#e5e7eb'>" + escapeHtml(priceTxt) + "</span></div>" +
                                    "<div><b>DCF Fair Value</b>" + modelBadge("FUNDAMENTAL") + ": <span style='color:#e5e7eb'>" + escapeHtml(dcfTxt) + "</span></div>" +
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

                // Persist last single-symbol analysis to disk (analyses/{symbol}.txt)
                try {
                    if (!symbol.isEmpty()) {
                        Path dir = Paths.get("analyses");
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve(symbol.toUpperCase() + ".txt"), result, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}

                String html = htmlPage(favCard + overviewCard + modelSummaryCard + riskModelsCard + "<div class='card'><div class='title'>Models used</div>" + modelsUsedNamesOnlyHtml() + "</div>" + "<div class=\"card\"><div class=\"title\">Output</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + aiCard + charts
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
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
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
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

        // Analysts page: form + results using Finnhub (cached 24h)
        server.createContext("/analysts", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    String content = "<div class='card'><div class='title'>Analysts</div>"+
                            "<form method='post' action='/analysts'>"+
                            "<input type='text' name='symbol' placeholder='e.g. AAPL' required /> "+
                            "<div style='margin-top:10px;'>"+
                            "<label style='margin-right:10px;'><input type='radio' name='source' value='A' checked /> A (Finnhub consensus)</label>"+
                            "<label><input type='radio' name='source' value='B' /> B (Alpha Vantage proxy)</label>"+
                            "</div>"+
                            "<button type='submit' style='margin-top:10px;'>Show</button></form>"+
                            "<div style='color:#9ca3af;margin-top:6px;'>A: Finnhub analyst consensus with 24h cache. B: heuristic proxy from Alpha Vantage (no real analyst consensus endpoint).</div>"+
                            "</div>";
                    respondHtml(ex, htmlPage(content), 200);
                    return;
                }
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "").trim().toUpperCase();
                String source = form.getOrDefault("source", "A").trim().toUpperCase();
                if (!source.equals("A") && !source.equals("B")) source = "A";

                String aChecked = source.equals("A") ? "checked" : "";
                String bChecked = source.equals("B") ? "checked" : "";
                String formHtml = "<div class='card'><div class='title'>Analysts</div>"+
                        "<form method='post' action='/analysts'>"+
                        "<input type='text' name='symbol' value='"+escapeHtml(sym)+"' placeholder='e.g. AAPL' required /> "+
                        "<div style='margin-top:10px;'>"+
                        "<label style='margin-right:10px;'><input type='radio' name='source' value='A' "+aChecked+" /> A (Finnhub consensus)</label>"+
                        "<label><input type='radio' name='source' value='B' "+bChecked+" /> B (Alpha Vantage proxy)</label>"+
                        "</div>"+
                        "<button type='submit' style='margin-top:10px;'>Show</button></form>"+
                        "</div>";

                String card;
                if (sym.isEmpty()) {
                    card = "<div class='card'><div class='title'>Analysts</div><div style='color:#9ca3af'>Enter a symbol to view.</div></div>";
                } else if (source.equals("B")) {
                    card = buildAnalystProxyAlphaVantageCard(sym);
                } else {
                    card = buildAnalystCard(sym);
                }
                respondHtml(ex, htmlPage(formHtml + card), 200);
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
                    String body = readBody(ex);
                    Map<String,String> form = parseForm(body);
                    boolean force = "1".equals(form.getOrDefault("force", "")) || "true".equalsIgnoreCase(form.getOrDefault("force", ""));
                    LocalDate today = LocalDate.now();
                    synchronized (portfolioCacheLock) {
                        if (force) {
                            portfolioCache.text = null;
                            portfolioCache.date = null;
                            try {
                                Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
                                try { Files.deleteIfExists(file); } catch (Exception ignore) {}
                            } catch (Exception ignore) {}
                        }

                        if (!force && portfolioCache.date != null && portfolioCache.date.equals(today) && portfolioCache.text != null) {
                            result = portfolioCache.text + "\n(הוצג מהמטמון היומי)";
                        } else {
                            // Try disk cache for today
                            Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
                            if (!force && Files.exists(file)) {
                                String cached = Files.readString(file, StandardCharsets.UTF_8);
                                portfolioCache.text = cached;
                                portfolioCache.date = today;
                                result = cached + "\n(הוצג ממטמון קובץ יומי)";
                            } else {
                                // Compute fresh
                                String computed = runAndCapture(() -> {
                                    // Analyze full portfolio (may take longer on free tier)
                                    PortfolioWeeklySummary.setMaxTickers(-1);
                                    PortfolioWeeklySummary.configureThrottle(true, 12_500); // ~5 req/min
                                    PortfolioWeeklySummary.main(new String[]{});
                                });
                                portfolioCache.text = computed;
                                portfolioCache.date = today;
                                result = computed + (force ? "\n(רענון כפוי בוצע בתאריך " + today + ")" : "\n(עודכן במטמון היומי בתאריך " + today + ")");
                                // Write to disk for persistence
                                try { Files.writeString(file, computed, StandardCharsets.UTF_8); } catch (Exception ignore) {}
                            }
                        }
                    }
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
                String forceBtn = "<div class='card' style='padding:12px 16px;'>"+
                        "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>"+
                        "<form method='post' action='/portfolio-weekly' style='margin:0'>"+
                        "<input type='hidden' name='force' value='1'/>"+
                        "<button type='submit'>Force Refresh Weekly Report</button></form>"+
                        "<a href='/portfolio-manage' style='color:#93c5fd;text-decoration:none;'>Manage Portfolio</a>"+
                        "</div></div>";

                String html = htmlPage(forceBtn + "<div class=\"card\"><div class=\"title\">My Portfolio - Weekly</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + gallery.toString()
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.start();
        System.out.println("Server running at http://localhost:" + port + "/ (threads=" + threads + ")");

        startDailyNasdaqScheduler();
        startAlphaAgentScheduler();
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
