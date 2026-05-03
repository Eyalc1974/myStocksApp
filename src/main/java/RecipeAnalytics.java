import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;

/**
 * RecipeAnalytics — finds the EDGE: what differentiates winners from losers.
 *
 * Philosophy: "The edge is not what winners have in common —
 *             it's how winners DIFFER from losers."
 *
 * V2 additions:
 *   - Feature interaction (combo) analysis with EXPECTANCY
 *   - Discretized buckets (RSI=MID+RVOL=HIGH+AboveSMA50)
 *   - Non-linear SignalQualityScore (combo boosts + danger penalties)
 *   - Time-of-entry dimension (OPEN_30M / MIDDAY / CLOSE)
 *   - Whitelist (safe combos) + Blacklist (avoid combos)
 *   - Overfitting guard: min 30 trades per combo, expectancy hurdle
 *
 * Rules of statistical validity:
 *   < 30 trades  = NO CONCLUSIONS (noise)
 *   < 100 trades = CAUTION (weak signal)
 *   >= 100 trades = RELIABLE
 */
public class RecipeAnalytics {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path HISTORY_PATH = Paths.get("newStrategies", "agent-history.json");
    private static final Path STATE_PATH   = Paths.get("newStrategies", "agent-state.json");
    private static final Path OUTPUT_PATH  = Paths.get("newStrategies", "success-recipes.json");
    private static final int MIN_COMBO_TRADES = 30;   // iron rule: no combo under this
    private static final double EXPECTANCY_HURDLE = 1.0; // pct: combo must beat this to whitelist

    // ── Public data model ───────────────────────────────────────────────────

    public static class RecipeBook {
        public String lastUpdated;
        public int totalWins;
        public int totalLosses;
        public int totalTrades;
        public double overallWinRate;
        public double overallExpectancy;
        public Map<String, SetupAnalysis> bySetup = new LinkedHashMap<>();
        public List<RecommendedAgent> recommendedAgents = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    /** Analysis per setup type (BREAKOUT / PULLBACK / TREND). */
    public static class SetupAnalysis {
        public String setupType;
        public int winCount;
        public int lossCount;
        public int totalCount;
        public double winRate;
        public double avgWinProfit;
        public double avgLossProfit;
        public double profitFactor;
        public double expectancy; // (WR * avgWin) - (LR * |avgLoss|)
        public String sampleSizeWarning;
        public Map<String, FeatureEdge> featureEdges = new LinkedHashMap<>();
        public Map<String, ThresholdAnalysis> thresholds = new LinkedHashMap<>();
        // ── V2: combo interaction analysis ──
        public List<ComboAnalysis> combos = new ArrayList<>();
        public List<ComboAnalysis> whitelist = new ArrayList<>(); // combos with >=30T and expectancy > hurdle
        public List<ComboAnalysis> blacklist = new ArrayList<>(); // combos with >=30T and negative expectancy
        public Map<String, TimeBucketAnalysis> timeBuckets = new LinkedHashMap<>();
        public SignalQualityModel qualityModel;
        public List<TradeExample> bestWins = new ArrayList<>();
        public List<TradeExample> worstLosses = new ArrayList<>();
        public RecommendedAgent recommendedConfig;
    }

    public static class ComboAnalysis {
        public String comboKey;      // e.g. "RSI=MID|RVOL=HIGH|AboveSMA50=true"
        public int winCount;
        public int lossCount;
        public int totalCount;
        public double winRate;
        public double avgWin;
        public double avgLoss;
        public double expectancy;    // THE metric: (WR*avgWin) - (LR*|avgLoss|)
        public double profitFactor;
        public String sampleWarning; // null if N>=30
        public String insightHebrew;
    }

    public static class TimeBucketAnalysis {
        public String timeBucket;    // OPEN_30M / MIDDAY / CLOSE
        public int winCount;
        public int lossCount;
        public int totalCount;
        public double winRate;
        public double avgWin;
        public double avgLoss;
        public double expectancy;
        public String insightHebrew;
    }

    /** For a single feature (e.g. rsi, rvol), what is the edge? */
    public static class FeatureEdge {
        public String feature;
        public double avgWin;
        public double avgLoss;
        public double diff;
        public double winRateHigh;
        public double winRateLow;
        public double edgeStrength;
        public String direction;
        public String insightHebrew;
    }

    /** What happens above/below a specific threshold? */
    public static class ThresholdAnalysis {
        public String feature;
        public double threshold;
        public int aboveWin;
        public int aboveLoss;
        public double aboveWinRate;
        public int belowWin;
        public int belowLoss;
        public double belowWinRate;
        public double edge;
        public String insightHebrew;
    }

    /** How to score a new trade signal based on historical edge.
     *  V2: comboBoosts give non-linear bonuses for high-probability feature intersections. */
    public static class SignalQualityModel {
        public int maxScore;
        public Map<String, QualityRule> rules = new LinkedHashMap<>();
        public List<ComboBoost> comboBoosts = new ArrayList<>();
        public List<ComboBoost> comboPenalties = new ArrayList<>();
        public Map<Integer, ScoreStats> scoreDistribution = new LinkedHashMap<>();
        public String insightHebrew;
    }

    public static class QualityRule {
        public String feature;
        public String condition;
        public double threshold1;
        public double threshold2;
        public int points;
        public String reasonHebrew;
    }

    /** Non-linear combo boost: if ALL features match, add bonus points. */
    public static class ComboBoost {
        public String comboKey;      // human-readable, e.g. "RSI=MID+RVOL=HIGH"
        public Map<String, String> requiredBuckets = new LinkedHashMap<>(); // feature -> bucket
        public int bonusPoints;
        public double historicalWinRate; // WR of this combo in backtest
        public int sampleCount;
        public String reasonHebrew;
    }

    public static class ScoreStats {
        public int score;
        public int totalTrades;
        public int wins;
        public int losses;
        public double winRate;
        public double avgProfit;
        public double expectancy;
    }

    public static class TradeExample {
        public String ticker;
        public String agentId;
        public String entryTime;
        public String timeBucket;
        public double profitLossPct;
        public int signalQualityScore;
        public String comboKey;
        public Map<String, Object> snapshot = new LinkedHashMap<>();
    }

    public static class RecommendedAgent {
        public String id;
        public String name;
        public String setupType;
        public String basedOn;
        public int winCount;
        public int lossCount;
        public double expectedWinRate;
        public double expectedExpectancy; // V2: expectancy, not just WR
        public double expectedEdgePct;
        public Map<String, Object> entryFilters = new LinkedHashMap<>();
        public Map<String, Object> riskManagement = new LinkedHashMap<>();
        public Map<String, Object> scoring = new LinkedHashMap<>();
        public List<String> insightsHebrew = new ArrayList<>();
        public List<String> whitelistCombosHebrew = new ArrayList<>();
        public List<String> blacklistCombosHebrew = new ArrayList<>();
    }

    // ── Core analysis entry point ───────────────────────────────────────────

    public static RecipeBook analyze() {
        RecipeBook book = new RecipeBook();
        book.lastUpdated = ZonedDateTime.now(ZoneId.of("America/New_York"))
            .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);

        JsonNode historyRoot = readJson(HISTORY_PATH);
        JsonNode stateRoot   = readJson(STATE_PATH);

        if (historyRoot == null || !historyRoot.has("tradeHistory")) {
            book.warnings.add("No tradeHistory found in " + HISTORY_PATH);
            return book;
        }

        JsonNode tradeHistory = historyRoot.get("tradeHistory");
        JsonNode agents = (stateRoot != null && stateRoot.has("agents")) ? stateRoot.get("agents") : null;

        // Collect ALL closed trades (wins + losses) with full context
        List<TradeContext> all = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> agentEntries = tradeHistory.fields();
        while (agentEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = agentEntries.next();
            String agentId = entry.getKey();
            JsonNode trades = entry.getValue();
            if (!trades.isArray()) continue;

            JsonNode agentNode = (agents != null && agents.has(agentId)) ? agents.get(agentId) : null;

            for (JsonNode t : trades) {
                String status = t.path("status").asText("");
                if (!"CLOSED_WIN".equals(status) && !"CLOSED_LOSS".equals(status)) continue;

                TradeContext tc = new TradeContext();
                tc.agentId = agentId;
                tc.ticker = t.path("ticker").asText("");
                tc.profitLossPct = t.path("profitLossPct").asDouble(0);
                tc.entryTime = t.path("entryTime").asText("");
                tc.isWin = "CLOSED_WIN".equals(status);
                tc.agentNode = agentNode;
                tc.tradeNode = t;
                all.add(tc);
            }
        }

        book.totalWins = (int) all.stream().filter(t -> t.isWin).count();
        book.totalLosses = (int) all.stream().filter(t -> !t.isWin).count();
        book.totalTrades = all.size();
        book.overallWinRate = book.totalTrades > 0 ? round2(100.0 * book.totalWins / book.totalTrades) : 0;
        double grossWins = all.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).sum();
        double grossLosses = Math.abs(all.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).sum());
        double wr = book.totalTrades > 0 ? 1.0 * book.totalWins / book.totalTrades : 0;
        double lr = 1.0 - wr;
        double avgWin = all.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);
        double avgLoss = Math.abs(all.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0));
        book.overallExpectancy = round2((wr * avgWin) - (lr * avgLoss));

        System.out.println("[RecipeAnalytics] Analyzed " + book.totalWins + " wins + " + book.totalLosses +
            " losses = " + book.totalTrades + " total trades | Expectancy=" + book.overallExpectancy + "%");

        if (book.totalTrades < 30) {
            book.warnings.add("⚠️ סך הכל " + book.totalTrades + " טריידים סגורים — זה רעש סטטיסטי. אין מסקנות עדיין.");
        } else if (book.totalTrades < 100) {
            book.warnings.add("⚠️ " + book.totalTrades + " טריידים — זהירות. המסקנות חלשות עדיין.");
        }

        // Tag every trade with timeBucket (for time-dimension analysis)
        for (TradeContext tc : all) {
            tc.timeBucket = deriveTimeBucket(tc.entryTime);
        }

        // Group by setupType (BREAKOUT / PULLBACK / TREND)
        Map<String, List<TradeContext>> bySetup = all.stream()
            .collect(Collectors.groupingBy(TradeContext::getSetupType));

        for (Map.Entry<String, List<TradeContext>> setupEntry : bySetup.entrySet()) {
            String setupType = setupEntry.getKey();
            List<TradeContext> list = setupEntry.getValue();
            SetupAnalysis analysis = analyzeSetup(setupType, list);
            book.bySetup.put(setupType, analysis);
            if (analysis.recommendedConfig != null) {
                book.recommendedAgents.add(analysis.recommendedConfig);
            }
        }

        save(book);
        return book;
    }

    // ── Per-setup analysis: the heart of the engine ─────────────────────────

    private static SetupAnalysis analyzeSetup(String setupType, List<TradeContext> trades) {
        SetupAnalysis a = new SetupAnalysis();
        a.setupType = setupType;
        a.winCount = (int) trades.stream().filter(t -> t.isWin).count();
        a.lossCount = (int) trades.stream().filter(t -> !t.isWin).count();
        a.totalCount = trades.size();
        a.winRate = a.totalCount > 0 ? round2(100.0 * a.winCount / a.totalCount) : 0;

        double grossWins = trades.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).sum();
        double grossLosses = Math.abs(trades.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).sum());
        a.profitFactor = grossLosses > 0 ? round2(grossWins / grossLosses) : 0;
        a.avgWinProfit = trades.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);
        a.avgLossProfit = trades.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);

        double wr = a.totalCount > 0 ? 1.0 * a.winCount / a.totalCount : 0;
        double lr = a.totalCount > 0 ? 1.0 * a.lossCount / a.totalCount : 0;
        a.expectancy = round2((wr * a.avgWinProfit) - (lr * Math.abs(a.avgLossProfit)));

        // Sample size warning per setup
        if (a.totalCount < 30) {
            a.sampleSizeWarning = "רעש סטטיסטי — רק " + a.totalCount + " טריידים. אין מסקנות.";
        } else if (a.totalCount < 100) {
            a.sampleSizeWarning = "זהירות — " + a.totalCount + " טריידים בלבד.";
        }

        // Discover all numerical snapshot keys
        Set<String> numKeys = new LinkedHashSet<>();
        Set<String> boolKeys = new LinkedHashSet<>();
        for (TradeContext tc : trades) {
            JsonNode snap = tc.tradeNode.path("entrySnapshot");
            if (snap.isObject()) {
                snap.fields().forEachRemaining(f -> {
                    if (f.getValue().isNumber()) numKeys.add(f.getKey());
                    else if (f.getValue().isBoolean()) boolKeys.add(f.getKey());
                });
            }
        }

        // ── Feature Edge Analysis ─────────────────────────────────────────
        for (String key : numKeys) {
            FeatureEdge edge = computeFeatureEdge(key, trades);
            if (edge != null) a.featureEdges.put(key, edge);
        }
        for (String key : boolKeys) {
            FeatureEdge edge = computeBooleanEdge(key, trades);
            if (edge != null) a.featureEdges.put(key, edge);
        }

        // ── Threshold Analysis for key features ───────────────────────────
        String[] keyFeatures = {"rsi", "rvol", "atrPct", "cci", "vwapPct",
                                "momentum20d", "pctFromWeek52High", "distSMA20"};
        for (String key : keyFeatures) {
            if (!a.featureEdges.containsKey(key)) continue;
            ThresholdAnalysis ta = computeThreshold(key, trades);
            if (ta != null) a.thresholds.put(key, ta);
        }

        // ── V2: Feature Discretization + Combo Analysis ───────────────────
        // Tag each trade with feature buckets
        for (TradeContext tc : trades) {
            tc.featureBuckets = discretizeSnapshot(tc.tradeNode.path("entrySnapshot"));
        }
        a.combos = analyzeCombos(trades);

        // ── V2: Time-of-Entry Dimension ───────────────────────────────────
        a.timeBuckets = analyzeTimeBuckets(trades);

        // ── V2: Whitelist / Blacklist ( combos with >=MIN_COMBO_TRADES ) ──
        for (ComboAnalysis ca : a.combos) {
            if (ca.totalCount >= MIN_COMBO_TRADES) {
                if (ca.expectancy >= EXPECTANCY_HURDLE) {
                    a.whitelist.add(ca);
                } else if (ca.expectancy < 0) {
                    a.blacklist.add(ca);
                }
            }
        }

        // ── Signal Quality Model (V2: combo-aware) ────────────────────────
        a.qualityModel = buildQualityModelV2(a.featureEdges, a.whitelist, a.blacklist, trades);

        // ── Tag every trade with quality score + combo key ──────────────────
        for (TradeContext tc : trades) {
            tc.qualityScore = computeSignalQualityV2(tc.tradeNode.path("entrySnapshot"),
                tc.featureBuckets, a.qualityModel);
            tc.comboKey = buildComboKey(tc.featureBuckets);
        }

        // ── Score distribution: does the score predict wins? ──────────────
        a.qualityModel.scoreDistribution = computeScoreDistribution(trades);

        // ── Best wins / worst losses for manual review ────────────────────
        trades.stream().filter(t -> t.isWin)
            .sorted((a1, b1) -> Double.compare(b1.profitLossPct, a1.profitLossPct))
            .limit(5)
            .forEach(tc -> a.bestWins.add(toExample(tc)));

        trades.stream().filter(t -> !t.isWin)
            .sorted((a1, b1) -> Double.compare(a1.profitLossPct, b1.profitLossPct))
            .limit(5)
            .forEach(tc -> a.worstLosses.add(toExample(tc)));

        // ── Recommended Agent ─────────────────────────────────────────────
        a.recommendedConfig = buildRecommendedAgent(setupType, a, trades);

        return a;
    }

    // ── Feature Edge: how does this feature differentiate wins from losses? ─

    private static FeatureEdge computeFeatureEdge(String feature, List<TradeContext> trades) {
        double[] winVals = trades.stream().filter(t -> t.isWin)
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();
        double[] lossVals = trades.stream().filter(t -> !t.isWin)
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();

        if (winVals.length < 2 || lossVals.length < 2) return null;

        double avgWin = Arrays.stream(winVals).average().orElse(0);
        double avgLoss = Arrays.stream(lossVals).average().orElse(0);
        double diff = avgWin - avgLoss;

        // Median split analysis
        double[] allVals = trades.stream()
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();
        if (allVals.length < 4) return null;
        Arrays.sort(allVals);
        double median = percentile(allVals, 0.5);

        long highWins = trades.stream().filter(t -> {
            double v = t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN);
            return !Double.isNaN(v) && v >= median && t.isWin;
        }).count();
        long highTotal = trades.stream().filter(t -> {
            double v = t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN);
            return !Double.isNaN(v) && v >= median;
        }).count();

        long lowWins = trades.stream().filter(t -> {
            double v = t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN);
            return !Double.isNaN(v) && v < median && t.isWin;
        }).count();
        long lowTotal = trades.stream().filter(t -> {
            double v = t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN);
            return !Double.isNaN(v) && v < median;
        }).count();

        double wrHigh = highTotal > 0 ? 100.0 * highWins / highTotal : 0;
        double wrLow  = lowTotal > 0 ? 100.0 * lowWins / lowTotal : 0;
        double edgeStr = Math.abs(wrHigh - wrLow) / 100.0;

        FeatureEdge fe = new FeatureEdge();
        fe.feature = feature;
        fe.avgWin = round2(avgWin);
        fe.avgLoss = round2(avgLoss);
        fe.diff = round2(diff);
        fe.winRateHigh = round2(wrHigh);
        fe.winRateLow = round2(wrLow);
        fe.edgeStrength = round2(edgeStr);

        if (wrHigh > wrLow + 5) {
            fe.direction = "HIGHER_IS_BETTER";
            fe.insightHebrew = feature + " גבוה = יותר טוב ✅ (WR " + round2(wrHigh) + "% vs " + round2(wrLow) + "%)";
        } else if (wrLow > wrHigh + 5) {
            fe.direction = "LOWER_IS_BETTER";
            fe.insightHebrew = feature + " נמוך = יותר טוב ✅ (WR " + round2(wrLow) + "% vs " + round2(wrHigh) + "%)";
        } else {
            fe.direction = "NEUTRAL";
            fe.insightHebrew = feature + " — לא מבדיל בין ניצחונות להפסדים (WR " + round2(wrHigh) + "% vs " + round2(wrLow) + "%)";
        }
        return fe;
    }

    private static FeatureEdge computeBooleanEdge(String feature, List<TradeContext> trades) {
        long trueWins = trades.stream().filter(t -> t.isWin &&
            t.tradeNode.path("entrySnapshot").path(feature).asBoolean(false)).count();
        long trueTotal = trades.stream().filter(t ->
            t.tradeNode.path("entrySnapshot").path(feature).asBoolean(false)).count();
        long falseWins = trades.stream().filter(t -> t.isWin &&
            !t.tradeNode.path("entrySnapshot").path(feature).asBoolean(false)).count();
        long falseTotal = trades.stream().filter(t ->
            !t.tradeNode.path("entrySnapshot").path(feature).asBoolean(false)).count();

        if (trueTotal < 3 || falseTotal < 3) return null;

        double wrTrue = trueTotal > 0 ? 100.0 * trueWins / trueTotal : 0;
        double wrFalse = falseTotal > 0 ? 100.0 * falseWins / falseTotal : 0;

        FeatureEdge fe = new FeatureEdge();
        fe.feature = feature;
        fe.avgWin = round2(wrTrue);
        fe.avgLoss = round2(wrFalse);
        fe.diff = round2(wrTrue - wrFalse);
        fe.winRateHigh = round2(Math.max(wrTrue, wrFalse));
        fe.winRateLow = round2(Math.min(wrTrue, wrFalse));
        fe.edgeStrength = round2(Math.abs(wrTrue - wrFalse) / 100.0);

        if (wrTrue > wrFalse + 5) {
            fe.direction = "TRUE_IS_BETTER";
            fe.insightHebrew = feature + "=true = יותר טוב ✅ (WR " + round2(wrTrue) + "% vs " + round2(wrFalse) + "%)";
        } else if (wrFalse > wrTrue + 5) {
            fe.direction = "FALSE_IS_BETTER";
            fe.insightHebrew = feature + "=false = יותר טוב ✅ (WR " + round2(wrFalse) + "% vs " + round2(wrTrue) + "%)";
        } else {
            fe.direction = "NEUTRAL";
            fe.insightHebrew = feature + " — לא מבדיל (WR true=" + round2(wrTrue) + "% vs false=" + round2(wrFalse) + "%)";
        }
        return fe;
    }

    // ── Threshold Analysis: find the exact cutoff that matters ──────────────

    private static ThresholdAnalysis computeThreshold(String feature, List<TradeContext> trades) {
        double[] vals = trades.stream()
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();
        if (vals.length < 8) return null;
        Arrays.sort(vals);
        double threshold = percentile(vals, 0.5); // median

        int aboveWin = 0, aboveLoss = 0, belowWin = 0, belowLoss = 0;
        for (TradeContext tc : trades) {
            double v = tc.tradeNode.path("entrySnapshot").path(feature).asDouble(Double.NaN);
            if (Double.isNaN(v)) continue;
            if (v >= threshold) {
                if (tc.isWin) aboveWin++; else aboveLoss++;
            } else {
                if (tc.isWin) belowWin++; else belowLoss++;
            }
        }

        double aboveWR = (aboveWin + aboveLoss) > 0 ? 100.0 * aboveWin / (aboveWin + aboveLoss) : 0;
        double belowWR = (belowWin + belowLoss) > 0 ? 100.0 * belowWin / (belowWin + belowLoss) : 0;

        ThresholdAnalysis ta = new ThresholdAnalysis();
        ta.feature = feature;
        ta.threshold = round2(threshold);
        ta.aboveWin = aboveWin;
        ta.aboveLoss = aboveLoss;
        ta.aboveWinRate = round2(aboveWR);
        ta.belowWin = belowWin;
        ta.belowLoss = belowLoss;
        ta.belowWinRate = round2(belowWR);
        ta.edge = round2(aboveWR - belowWR);

        if (aboveWR > belowWR + 5) {
            ta.insightHebrew = "כאשר " + feature + " >= " + round2(threshold) + " → WR " + round2(aboveWR) +
                "% (לעומת " + round2(belowWR) + "% מתחת)";
        } else if (belowWR > aboveWR + 5) {
            ta.insightHebrew = "כאשר " + feature + " < " + round2(threshold) + " → WR " + round2(belowWR) +
                "% (לעומת " + round2(aboveWR) + "% מעל)";
        } else {
            ta.insightHebrew = feature + " — אין הבדל משמעותי מעל/מתחת " + round2(threshold);
        }
        return ta;
    }

    // ── V2: Feature Discretization ──────────────────────────────────────────

    private static Map<String, String> discretizeSnapshot(JsonNode snapshot) {
        Map<String, String> buckets = new LinkedHashMap<>();
        if (!snapshot.isObject()) return buckets;

        // RSI buckets
        double rsi = snapshot.path("rsi").asDouble(Double.NaN);
        if (!Double.isNaN(rsi)) {
            if (rsi < 50) buckets.put("rsi", "LOW");
            else if (rsi <= 65) buckets.put("rsi", "MID");
            else buckets.put("rsi", "HIGH");
        }

        // RVOL buckets
        double rvol = snapshot.path("rvol").asDouble(Double.NaN);
        if (!Double.isNaN(rvol)) {
            if (rvol < 1.3) buckets.put("rvol", "LOW");
            else buckets.put("rvol", "HIGH");
        }

        // ATR%
        double atrPct = snapshot.path("atrPct").asDouble(Double.NaN);
        if (!Double.isNaN(atrPct)) {
            if (atrPct < 1.5) buckets.put("atrPct", "LOW");
            else buckets.put("atrPct", "HIGH");
        }

        // Distance from 52W high (negative = below)
        double pct52 = snapshot.path("pctFromWeek52High").asDouble(Double.NaN);
        if (!Double.isNaN(pct52)) {
            if (pct52 >= -5) buckets.put("pctFromWeek52High", "CLOSE");
            else if (pct52 >= -15) buckets.put("pctFromWeek52High", "MID");
            else buckets.put("pctFromWeek52High", "FAR");
        }

        // Distance from SMA20 (negative = below)
        double distSMA20 = snapshot.path("distSMA20").asDouble(Double.NaN);
        if (!Double.isNaN(distSMA20)) {
            if (distSMA20 < -2) buckets.put("distSMA20", "BELOW");
            else if (distSMA20 <= 2) buckets.put("distSMA20", "NEAR");
            else buckets.put("distSMA20", "ABOVE");
        }

        // CCI
        double cci = snapshot.path("cci").asDouble(Double.NaN);
        if (!Double.isNaN(cci)) {
            if (cci < -50) buckets.put("cci", "LOW");
            else if (cci <= 100) buckets.put("cci", "MID");
            else buckets.put("cci", "HIGH");
        }

        // Booleans
        boolean aboveSMA50 = snapshot.path("priceAboveSMA50").asBoolean(false);
        buckets.put("priceAboveSMA50", aboveSMA50 ? "true" : "false");

        boolean aboveSMA200 = snapshot.path("priceAboveSMA200").asBoolean(false);
        buckets.put("priceAboveSMA200", aboveSMA200 ? "true" : "false");

        return buckets;
    }

    private static String buildComboKey(Map<String, String> buckets) {
        return buckets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("|"));
    }

    // ── V2: Combo Analysis ────────────────────────────────────────────────────

    private static List<ComboAnalysis> analyzeCombos(List<TradeContext> trades) {
        Map<String, List<TradeContext>> byCombo = trades.stream()
            .filter(t -> t.featureBuckets != null && !t.featureBuckets.isEmpty())
            .collect(Collectors.groupingBy(t -> buildComboKey(t.featureBuckets)));

        List<ComboAnalysis> results = new ArrayList<>();
        for (Map.Entry<String, List<TradeContext>> e : byCombo.entrySet()) {
            List<TradeContext> list = e.getValue();
            int wins = (int) list.stream().filter(t -> t.isWin).count();
            int losses = list.size() - wins;
            double wr = list.size() > 0 ? 100.0 * wins / list.size() : 0;
            double avgWin = list.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);
            double avgLoss = Math.abs(list.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0));
            double wRate = list.size() > 0 ? 1.0 * wins / list.size() : 0;
            double lRate = 1.0 - wRate;
            double expectancy = round2((wRate * avgWin) - (lRate * avgLoss));

            ComboAnalysis ca = new ComboAnalysis();
            ca.comboKey = e.getKey();
            ca.winCount = wins;
            ca.lossCount = losses;
            ca.totalCount = list.size();
            ca.winRate = round2(wr);
            ca.avgWin = round2(avgWin);
            ca.avgLoss = round2(avgLoss);
            ca.expectancy = expectancy;
            ca.profitFactor = avgLoss > 0 ? round2((wins * avgWin) / (losses * avgLoss)) : 0;

            if (list.size() < MIN_COMBO_TRADES) {
                ca.sampleWarning = "רק " + list.size() + " טריידים — זהירות";
            }

            // Hebrew insight
            ca.insightHebrew = ca.comboKey + " | " + wins + "W/" + losses + "L | WR " + round2(wr) +
                "% | Expectancy " + expectancy + "%";
            if (ca.sampleWarning != null) ca.insightHebrew += " ⚠️ " + ca.sampleWarning;
            results.add(ca);
        }
        // Sort by expectancy descending
        results.sort((a1, b1) -> Double.compare(b1.expectancy, a1.expectancy));
        return results;
    }

    // ── V2: Time-of-Entry Analysis ──────────────────────────────────────────

    private static String deriveTimeBucket(String entryTime) {
        if (entryTime == null || entryTime.isBlank()) return "UNKNOWN";
        try {
            ZonedDateTime dt = ZonedDateTime.parse(entryTime);
            int hour = dt.getHour();
            int minute = dt.getMinute();
            int totalMinutes = hour * 60 + minute;
            // Market open in ET: 9:30 = 570 minutes
            int marketOpen = 9 * 60 + 30;
            int midday = 12 * 60;      // 12:00
            int afternoon = 14 * 60;   // 14:00 (2pm)
            int preClose = 15 * 60 + 30; // 15:30 (3:30pm)

            int minutesSinceOpen = totalMinutes - marketOpen;
            if (minutesSinceOpen >= 0 && minutesSinceOpen < 30) return "OPEN_30M";
            else if (minutesSinceOpen >= 30 && minutesSinceOpen < 150) return "MIDDAY";
            else if (minutesSinceOpen >= 150 && minutesSinceOpen < 360) return "AFTERNOON";
            else if (minutesSinceOpen >= 360) return "PRE_CLOSE";
            else return "PRE_MARKET";
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private static Map<String, TimeBucketAnalysis> analyzeTimeBuckets(List<TradeContext> trades) {
        Map<String, List<TradeContext>> byTime = trades.stream()
            .collect(Collectors.groupingBy(t -> t.timeBucket));

        Map<String, TimeBucketAnalysis> result = new LinkedHashMap<>();
        String[] order = {"OPEN_30M", "MIDDAY", "AFTERNOON", "PRE_CLOSE", "PRE_MARKET", "UNKNOWN"};
        for (String bucket : order) {
            List<TradeContext> list = byTime.get(bucket);
            if (list == null || list.isEmpty()) continue;

            int wins = (int) list.stream().filter(t -> t.isWin).count();
            int losses = list.size() - wins;
            double wr = list.size() > 0 ? 100.0 * wins / list.size() : 0;
            double avgWin = list.stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);
            double avgLoss = Math.abs(list.stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0));
            double wRate = list.size() > 0 ? 1.0 * wins / list.size() : 0;
            double lRate = 1.0 - wRate;
            double expectancy = round2((wRate * avgWin) - (lRate * avgLoss));

            TimeBucketAnalysis ta = new TimeBucketAnalysis();
            ta.timeBucket = bucket;
            ta.winCount = wins;
            ta.lossCount = losses;
            ta.totalCount = list.size();
            ta.winRate = round2(wr);
            ta.avgWin = round2(avgWin);
            ta.avgLoss = round2(avgLoss);
            ta.expectancy = expectancy;
            ta.insightHebrew = bucket + " | " + wins + "W/" + losses + "L | WR " + round2(wr) +
                "% | Expectancy " + expectancy + "%";
            result.put(bucket, ta);
        }
        return result;
    }

    // ── V2: Combo-aware Signal Quality Model ────────────────────────────────

    private static SignalQualityModel buildQualityModelV2(Map<String, FeatureEdge> edges,
                                                          List<ComboAnalysis> whitelist,
                                                          List<ComboAnalysis> blacklist,
                                                          List<TradeContext> trades) {
        SignalQualityModel model = new SignalQualityModel();
        model.maxScore = 0;

        // Base rules from individual feature edges (keep the strong ones)
        for (FeatureEdge edge : edges.values()) {
            if (edge.edgeStrength < 0.05) continue;
            QualityRule rule = new QualityRule();
            rule.feature = edge.feature;
            rule.points = Math.max(1, (int) Math.round(edge.edgeStrength * 5));
            model.maxScore += rule.points;

            if ("HIGHER_IS_BETTER".equals(edge.direction) || "TRUE_IS_BETTER".equals(edge.direction)) {
                rule.condition = ">";
                double[] winVals = trades.stream().filter(t -> t.isWin)
                    .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(edge.feature).asDouble(Double.NaN))
                    .filter(v -> !Double.isNaN(v)).toArray();
                rule.threshold1 = winVals.length > 0 ? round2(Arrays.stream(winVals).average().orElse(0)) : 0;
                rule.reasonHebrew = edge.feature + " גבוה = +" + rule.points + " (WR " + edge.winRateHigh + "%)";
            } else if ("LOWER_IS_BETTER".equals(edge.direction) || "FALSE_IS_BETTER".equals(edge.direction)) {
                rule.condition = "<";
                double[] winVals = trades.stream().filter(t -> t.isWin)
                    .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(edge.feature).asDouble(Double.NaN))
                    .filter(v -> !Double.isNaN(v)).toArray();
                rule.threshold1 = winVals.length > 0 ? round2(Arrays.stream(winVals).average().orElse(0)) : 0;
                rule.reasonHebrew = edge.feature + " נמוך = +" + rule.points + " (WR " + edge.winRateLow + "%)";
            } else {
                rule.condition = "between";
                double[] winVals = trades.stream().filter(t -> t.isWin)
                    .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path(edge.feature).asDouble(Double.NaN))
                    .filter(v -> !Double.isNaN(v)).toArray();
                if (winVals.length >= 2) {
                    Arrays.sort(winVals);
                    rule.threshold1 = round2(percentile(winVals, 0.25));
                    rule.threshold2 = round2(percentile(winVals, 0.75));
                }
                rule.reasonHebrew = edge.feature + " בטווח הניצחונות = +" + rule.points;
            }
            model.rules.put(edge.feature, rule);
        }

        // ── Combo BOOSTS: whitelist combos get bonus points ──
        for (ComboAnalysis ca : whitelist) {
            if (ca.totalCount < MIN_COMBO_TRADES) continue;
            ComboBoost boost = new ComboBoost();
            boost.comboKey = ca.comboKey;
            // Parse comboKey into required buckets
            for (String part : ca.comboKey.split("\\|")) {
                String[] kv = part.split("=");
                if (kv.length == 2) boost.requiredBuckets.put(kv[0], kv[1]);
            }
            boost.bonusPoints = Math.max(2, (int) Math.round(ca.expectancy));
            boost.historicalWinRate = ca.winRate;
            boost.sampleCount = ca.totalCount;
            boost.reasonHebrew = ca.comboKey + " → +" + boost.bonusPoints +
                " (WR " + ca.winRate + "%, N=" + ca.totalCount + ")";
            model.comboBoosts.add(boost);
            model.maxScore += boost.bonusPoints;
        }

        // ── Combo PENALTIES: blacklist combos get negative points ──
        for (ComboAnalysis ca : blacklist) {
            if (ca.totalCount < MIN_COMBO_TRADES) continue;
            ComboBoost penalty = new ComboBoost();
            penalty.comboKey = ca.comboKey;
            for (String part : ca.comboKey.split("\\|")) {
                String[] kv = part.split("=");
                if (kv.length == 2) penalty.requiredBuckets.put(kv[0], kv[1]);
            }
            penalty.bonusPoints = Math.min(-1, (int) Math.round(ca.expectancy));
            penalty.historicalWinRate = ca.winRate;
            penalty.sampleCount = ca.totalCount;
            penalty.reasonHebrew = ca.comboKey + " → " + penalty.bonusPoints +
                " (WR " + ca.winRate + "%, N=" + ca.totalCount + ")";
            model.comboPenalties.add(penalty);
        }

        model.insightHebrew = "כלל איכות V2: " + model.rules.size() + " base rules + " +
            model.comboBoosts.size() + " combo boosts + " + model.comboPenalties.size() +
            " penalties. מקסימום " + model.maxScore + " נקודות";
        return model;
    }

    private static int computeSignalQualityV2(JsonNode snapshot, Map<String, String> buckets,
                                               SignalQualityModel model) {
        int score = 0;
        // Base feature rules
        for (QualityRule rule : model.rules.values()) {
            double v = snapshot.path(rule.feature).asDouble(Double.NaN);
            if (Double.isNaN(v)) continue;
            switch (rule.condition) {
                case ">": if (v > rule.threshold1) score += rule.points; break;
                case "<": if (v < rule.threshold1) score += rule.points; break;
                case "between": if (v >= rule.threshold1 && v <= rule.threshold2) score += rule.points; break;
            }
        }
        // Combo boosts
        for (ComboBoost boost : model.comboBoosts) {
            boolean match = true;
            for (Map.Entry<String, String> req : boost.requiredBuckets.entrySet()) {
                String actual = buckets.getOrDefault(req.getKey(), "");
                if (!req.getValue().equals(actual)) { match = false; break; }
            }
            if (match) score += boost.bonusPoints;
        }
        // Combo penalties
        for (ComboBoost pen : model.comboPenalties) {
            boolean match = true;
            for (Map.Entry<String, String> req : pen.requiredBuckets.entrySet()) {
                String actual = buckets.getOrDefault(req.getKey(), "");
                if (!req.getValue().equals(actual)) { match = false; break; }
            }
            if (match) score += pen.bonusPoints; // negative
        }
        return score;
    }

    private static Map<Integer, ScoreStats> computeScoreDistribution(List<TradeContext> trades) {
        Map<Integer, List<TradeContext>> byScore = trades.stream()
            .collect(Collectors.groupingBy(t -> t.qualityScore));
        Map<Integer, ScoreStats> result = new TreeMap<>();
        for (Map.Entry<Integer, List<TradeContext>> e : byScore.entrySet()) {
            ScoreStats s = new ScoreStats();
            s.score = e.getKey();
            s.totalTrades = e.getValue().size();
            s.wins = (int) e.getValue().stream().filter(t -> t.isWin).count();
            s.losses = s.totalTrades - s.wins;
            s.winRate = s.totalTrades > 0 ? round2(100.0 * s.wins / s.totalTrades) : 0;
            s.avgProfit = e.getValue().stream().mapToDouble(t -> t.profitLossPct).average().orElse(0);
            double wRate = s.totalTrades > 0 ? 1.0 * s.wins / s.totalTrades : 0;
            double lRate = 1.0 - wRate;
            double avgWin = e.getValue().stream().filter(t -> t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0);
            double avgLoss = Math.abs(e.getValue().stream().filter(t -> !t.isWin).mapToDouble(t -> t.profitLossPct).average().orElse(0));
            s.expectancy = round2((wRate * avgWin) - (lRate * avgLoss));
            result.put(s.score, s);
        }
        return result;
    }

    // ── Build recommended agent from the EDGE, not just winners ───────────────

    private static RecommendedAgent buildRecommendedAgent(String setupType, SetupAnalysis a, List<TradeContext> trades) {
        RecommendedAgent rec = new RecommendedAgent();
        rec.id = "RECIPE_" + setupType + "_" + a.totalCount + "T";
        rec.name = "Recipe: " + setupType + " (" + a.winCount + "W/" + a.lossCount + "L)";
        rec.setupType = setupType;
        rec.basedOn = a.totalCount + " trades (" + a.winCount + " wins, " + a.lossCount + " losses)";
        rec.winCount = a.winCount;
        rec.lossCount = a.lossCount;
        rec.expectedWinRate = a.winRate;
        rec.expectedExpectancy = a.expectancy;
        rec.expectedEdgePct = round2(a.avgWinProfit);

        // V2: Whitelist / Blacklist insights
        rec.whitelistCombosHebrew.clear();
        rec.blacklistCombosHebrew.clear();
        if (!a.whitelist.isEmpty()) {
            rec.whitelistCombosHebrew.add("🔥 " + a.whitelist.size() + " קומבינציות בטוחות (expectancy >= " + EXPECTANCY_HURDLE + "%):");
            for (ComboAnalysis ca : a.whitelist.stream().limit(5).collect(Collectors.toList())) {
                rec.whitelistCombosHebrew.add(ca.insightHebrew);
            }
        }
        if (!a.blacklist.isEmpty()) {
            rec.blacklistCombosHebrew.add("❌ " + a.blacklist.size() + " קומבינציות להימנע (expectancy < 0):");
            for (ComboAnalysis ca : a.blacklist.stream().limit(5).collect(Collectors.toList())) {
                rec.blacklistCombosHebrew.add(ca.insightHebrew);
            }
        }

        // Time dimension insights
        for (TimeBucketAnalysis tba : a.timeBuckets.values()) {
            if (tba.totalCount >= 10) {
                rec.insightsHebrew.add(tba.insightHebrew);
            }
        }

        // For each numerical feature that showed edge, set a filter threshold
        // Use the BOUNDARY between wins and losses, not just the winner average
        for (FeatureEdge edge : a.featureEdges.values()) {
            if (edge.edgeStrength < 0.05) continue; // too weak

            // Determine threshold: use average of wins if higher-is-better,
            // or average of wins if lower-is-better (both point toward winner territory)
            double threshold = edge.avgWin;

            switch (edge.feature) {
                case "rsi":
                    if ("LOWER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("rsiMax", round2(threshold + 3));
                    } else if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("rsiMin", round2(threshold - 3));
                    } else {
                        rec.entryFilters.put("rsiMin", round2(Math.min(edge.avgWin, edge.avgLoss) - 2));
                        rec.entryFilters.put("rsiMax", round2(Math.max(edge.avgWin, edge.avgLoss) + 2));
                    }
                    rec.insightsHebrew.add("RSI — מנצחים בממוצע " + edge.avgWin + " vs מפסידים " + edge.avgLoss);
                    break;
                case "rvol":
                    if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("rvolMin", round2(Math.max(0.5, threshold - 0.2)));
                        rec.insightsHebrew.add("RVOL גבוה = טוב ✅ (מנצחים " + edge.avgWin + " vs מפסידים " + edge.avgLoss + ")");
                    } else {
                        rec.insightsHebrew.add("RVOL — לא מבדיל במובהק (" + edge.avgWin + " vs " + edge.avgLoss + ")");
                    }
                    break;
                case "atrPct":
                    if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("atrMinPct", round2(Math.max(0.5, threshold - 0.3)));
                        rec.insightsHebrew.add("ATR% גבוה = טוב ✅ (מנצחים " + edge.avgWin + "% vs מפסידים " + edge.avgLoss + "%")");
                    }
                    break;
                case "cci":
                    if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("cciMin", round2(threshold - 5));
                        rec.insightsHebrew.add("CCI גבוה = טוב ✅");
                    }
                    break;
                case "pctFromWeek52High":
                    // Winners are closer to 52W high (less negative = closer)
                    if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.entryFilters.put("maxPctFromWeek52High", round2(Math.min(0, threshold + 1)));
                        rec.insightsHebrew.add("קרוב לשיא 52 שבועות = טוב ✅ (מנצחים " + edge.avgWin + "% מתחת)");
                    }
                    break;
                case "distSMA20":
                    if ("HIGHER_IS_BETTER".equals(edge.direction)) {
                        rec.insightsHebrew.add("מעל SMA20 = טוב ✅");
                    } else if ("LOWER_IS_BETTER".equals(edge.direction)) {
                        rec.insightsHebrew.add("מתחת ל-SMA20 = טוב ✅ (pullback setup)");
                    }
                    break;
            }
        }

        // Boolean features
        for (FeatureEdge edge : a.featureEdges.values()) {
            if (!edge.feature.startsWith("priceAbove")) continue;
            if (edge.edgeStrength < 0.05) continue;
            if ("TRUE_IS_BETTER".equals(edge.direction)) {
                switch (edge.feature) {
                    case "priceAboveSMA200":
                        rec.entryFilters.put("sma200Required", true);
                        rec.insightsHebrew.add("מעל SMA200 = חובה ✅");
                        break;
                    case "priceAboveSMA50":
                        rec.entryFilters.put("sma50Required", true);
                        rec.insightsHebrew.add("מעל SMA50 = חובה ✅");
                        break;
                    case "maCrossoverUp":
                        rec.insightsHebrew.add("SMA20 > SMA50 = טוב ✅");
                        break;
                }
            }
        }

        // Risk management: use what actually worked
        double[] winTPs = trades.stream().filter(t -> t.isWin)
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path("risk_takeProfitPct").asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();
        if (winTPs.length > 0) {
            rec.riskManagement.put("takeProfitPct", round2(Arrays.stream(winTPs).average().orElse(0)));
        }
        double[] winSLs = trades.stream().filter(t -> t.isWin)
            .mapToDouble(t -> t.tradeNode.path("entrySnapshot").path("risk_stopLossPct").asDouble(Double.NaN))
            .filter(v -> !Double.isNaN(v)).toArray();
        if (winSLs.length > 0) {
            rec.riskManagement.put("stopLossPct", round2(Arrays.stream(winSLs).average().orElse(0)));
        }

        return rec;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static TradeExample toExample(TradeContext tc) {
        TradeExample ex = new TradeExample();
        ex.ticker = tc.ticker;
        ex.agentId = tc.agentId;
        ex.entryTime = tc.entryTime;
        ex.timeBucket = tc.timeBucket;
        ex.comboKey = tc.comboKey;
        ex.profitLossPct = round2(tc.profitLossPct);
        ex.signalQualityScore = tc.qualityScore;
        JsonNode snap = tc.tradeNode.path("entrySnapshot");
        if (snap.isObject()) {
            snap.fields().forEachRemaining(f -> {
                if (f.getValue().isNumber()) ex.snapshot.put(f.getKey(), round2(f.getValue().asDouble()));
                else if (f.getValue().isBoolean()) ex.snapshot.put(f.getKey(), f.getValue().asBoolean());
                else ex.snapshot.put(f.getKey(), f.getValue().asText());
            });
        }
        return ex;
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double idx = p * (sorted.length - 1);
        int lower = (int) Math.floor(idx);
        int upper = (int) Math.ceil(idx);
        if (lower == upper) return sorted[lower];
        double weight = idx - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static JsonNode readJson(Path path) {
        try {
            if (!Files.exists(path)) return null;
            return JSON.readTree(path.toFile());
        } catch (Exception e) {
            System.err.println("[RecipeAnalytics] Failed to read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static void save(RecipeBook book) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), book);
            System.out.println("[RecipeAnalytics] Saved " + OUTPUT_PATH);
        } catch (Exception e) {
            System.err.println("[RecipeAnalytics] Failed to save: " + e.getMessage());
        }
    }

    // Lightweight trade holder
    private static class TradeContext {
        String agentId;
        String ticker;
        double profitLossPct;
        String entryTime;
        boolean isWin;
        int qualityScore = 0;
        String timeBucket;                     // V2: OPEN_30M / MIDDAY / etc.
        Map<String, String> featureBuckets;    // V2: discretized feature values
        JsonNode agentNode;
        JsonNode tradeNode;

        String getSetupType() {
            // 1. explicit setupType on trade
            String s = tradeNode.path("setupType").asText("");
            if (!s.isBlank()) return s;
            // 2. derive from strategyType on trade
            s = tradeNode.path("strategyType").asText("");
            if (!s.isBlank()) return deriveSetupType(s);
            // 3. from agent node
            if (agentNode != null) {
                s = agentNode.path("strategyType").asText("");
                if (!s.isBlank()) return deriveSetupType(s);
                s = agentNode.path("type").asText("");
                if (!s.isBlank()) return s;
            }
            return "UNKNOWN";
        }
    }

    private static String deriveSetupType(String strategyType) {
        if (strategyType == null) return "UNKNOWN";
        return switch (strategyType) {
            case "MOMENTUM_BREAKOUT", "VOLUME_BREAKOUT", "WEEK52_HIGH_MOMENTUM" -> "BREAKOUT";
            case "PULLBACK", "PULLBACK_MA20" -> "PULLBACK";
            case "TREND_CONTINUATION", "STRONG_TREND" -> "TREND";
            default -> "UNKNOWN";
        };
    }

    // ── CLI / quick test ─────────────────────────────────────────────────────

    public static void main(String[] args) {
        RecipeBook book = analyze();
        System.out.println("\n=== RecipeAnalytics V2 Summary ===");
        System.out.println("Total: " + book.totalWins + " wins / " + book.totalLosses + " losses = " + book.totalTrades + " trades");
        System.out.println("Overall WR: " + book.overallWinRate + "% | Expectancy: " + book.overallExpectancy + "%");
        for (String w : book.warnings) System.out.println("WARN: " + w);

        for (Map.Entry<String, SetupAnalysis> e : book.bySetup.entrySet()) {
            SetupAnalysis a = e.getValue();
            System.out.println("\n--- " + e.getKey() + " ---");
            System.out.println(a.winCount + "W / " + a.lossCount + "L | WR " + a.winRate +
                "% | PF " + a.profitFactor + " | Expectancy " + a.expectancy + "%");
            if (a.sampleSizeWarning != null) System.out.println("⚠️ " + a.sampleSizeWarning);

            System.out.println("Feature Edges:");
            for (FeatureEdge fe : a.featureEdges.values()) {
                System.out.println("  " + fe.feature + ": " + fe.direction + " | diff=" + fe.diff +
                    " | WR-high=" + fe.winRateHigh + "% WR-low=" + fe.winRateLow + "% | strength=" + fe.edgeStrength);
            }

            System.out.println("Time-of-Entry Analysis:");
            for (TimeBucketAnalysis tba : a.timeBuckets.values()) {
                System.out.println("  " + tba.insightHebrew);
            }

            System.out.println("Top 5 Combos (by Expectancy):");
            for (int i = 0; i < Math.min(5, a.combos.size()); i++) {
                ComboAnalysis ca = a.combos.get(i);
                System.out.println("  #" + (i+1) + " " + ca.insightHebrew);
            }

            System.out.println("Whitelist (safe combos, N>=" + MIN_COMBO_TRADES + "):");
            for (ComboAnalysis ca : a.whitelist.stream().limit(3).collect(Collectors.toList())) {
                System.out.println("  🔥 " + ca.comboKey + " | WR " + ca.winRate + "% | Exp " + ca.expectancy + "%");
            }

            System.out.println("Blacklist (avoid combos, N>=" + MIN_COMBO_TRADES + "):");
            for (ComboAnalysis ca : a.blacklist.stream().limit(3).collect(Collectors.toList())) {
                System.out.println("  ❌ " + ca.comboKey + " | WR " + ca.winRate + "% | Exp " + ca.expectancy + "%");
            }

            System.out.println("Quality Score Distribution:");
            for (ScoreStats s : a.qualityModel.scoreDistribution.values()) {
                System.out.println("  Score " + s.score + ": " + s.wins + "W/" + s.losses + "L = " +
                    s.winRate + "% WR | Exp " + s.expectancy + "%");
            }

            if (a.qualityModel.comboBoosts.size() > 0) {
                System.out.println("Combo Boosts:");
                for (ComboBoost cb : a.qualityModel.comboBoosts) {
                    System.out.println("  " + cb.reasonHebrew);
                }
            }
            if (a.qualityModel.comboPenalties.size() > 0) {
                System.out.println("Combo Penalties:");
                for (ComboBoost cb : a.qualityModel.comboPenalties) {
                    System.out.println("  " + cb.reasonHebrew);
                }
            }
        }
    }
}
