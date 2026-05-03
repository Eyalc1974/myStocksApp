import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;

/**
 * SuccessRecipeEngine — finds the small repeating edges that produce winning trades.
 *
 * Philosophy: "Don't build a system that always wins. Find a small edge that repeats."
 *
 * This engine reads every CLOSED_WIN trade from agent-history.json, extracts the
 * exact market conditions and agent parameters that were in force at entry time,
 * and builds statistical "recipes" per strategy type.
 *
 * A recipe tells you: "When you see RSI between X-Y, RVOL above Z, and price
 * within W% of SMA20, you have an edge that won N times with an average return of R%."
 *
 * It also generates a recommended agent configuration based on the tightest
 * common ranges among winners (25th–75th percentile), so you can spawn a new
 * agent that targets only the highest-probability setups.
 */
public class SuccessRecipeEngine {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path HISTORY_PATH = Paths.get("newStrategies", "agent-history.json");
    private static final Path STATE_PATH   = Paths.get("newStrategies", "agent-state.json");
    private static final Path OUTPUT_PATH  = Paths.get("newStrategies", "success-recipes.json");

    // ── Public data model ───────────────────────────────────────────────────

    public static class RecipeBook {
        public String lastUpdated;
        public int totalWinsAnalyzed;
        public int totalTradesAnalyzed;
        public Map<String, StrategyRecipe> recipesByStrategy = new LinkedHashMap<>();
        public List<RecommendedAgent> recommendedAgents = new ArrayList<>();
    }

    public static class StrategyRecipe {
        public String strategyType;
        public int winCount;
        public double avgProfitPct;
        public double medianProfitPct;
        public double maxProfitPct;
        public double minProfitPct;
        public Map<String, NumStat> conditionStats = new LinkedHashMap<>(); // e.g. rsi, rvol
        public Map<String, NumStat> filterStats   = new LinkedHashMap<>(); // e.g. filter_rsiMin
        public Map<String, BooleanFreq> booleanFreq = new LinkedHashMap<>();
        public List<WinSnapshot> winSnapshots = new ArrayList<>();
        public RecommendedAgent recommendedConfig;
    }

    public static class NumStat {
        public double min;
        public double max;
        public double avg;
        public double median;
        public double p25;
        public double p75;
        public int count;
    }

    public static class BooleanFreq {
        public int trueCount;
        public int falseCount;
        public double truePct;
    }

    public static class WinSnapshot {
        public String ticker;
        public String agentId;
        public String entryTime;
        public double profitLossPct;
        public Map<String, Object> snapshot = new LinkedHashMap<>();
    }

    public static class RecommendedAgent {
        public String id;
        public String name;
        public String strategyType;
        public String basedOn;
        public int winCount;
        public double expectedEdgePct;
        public Map<String, Object> entryFilters = new LinkedHashMap<>();
        public Map<String, Object> riskManagement = new LinkedHashMap<>();
        public Map<String, Object> scoring = new LinkedHashMap<>();
        public String recipeSummaryHebrew;
    }

    // ── Core analysis entry point ───────────────────────────────────────────

    public static RecipeBook analyze() {
        RecipeBook book = new RecipeBook();
        book.lastUpdated = ZonedDateTime.now(ZoneId.of("America/New_York"))
            .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);

        // Load history & state
        JsonNode historyRoot = readJson(HISTORY_PATH);
        JsonNode stateRoot   = readJson(STATE_PATH);

        if (historyRoot == null || !historyRoot.has("tradeHistory")) {
            System.out.println("[SuccessRecipe] No tradeHistory found in " + HISTORY_PATH);
            return book;
        }

        JsonNode tradeHistory = historyRoot.get("tradeHistory");
        JsonNode agents = (stateRoot != null && stateRoot.has("agents")) ? stateRoot.get("agents") : null;

        // Collect all winning trades with full context
        List<WinContext> wins = new ArrayList<>();
        int totalTrades = 0;

        Iterator<Map.Entry<String, JsonNode>> agentEntries = tradeHistory.fields();
        while (agentEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = agentEntries.next();
            String agentId = entry.getKey();
            JsonNode trades = entry.getValue();
            if (!trades.isArray()) continue;

            JsonNode agentNode = (agents != null && agents.has(agentId)) ? agents.get(agentId) : null;

            for (JsonNode t : trades) {
                totalTrades++;
                String status = t.path("status").asText("");
                if (!"CLOSED_WIN".equals(status)) continue;

                WinContext wc = new WinContext();
                wc.agentId = agentId;
                wc.ticker = t.path("ticker").asText("");
                wc.profitLossPct = t.path("profitLossPct").asDouble(0);
                wc.entryTime = t.path("entryTime").asText("");
                wc.agentNode = agentNode;
                wc.tradeNode = t;
                wins.add(wc);
            }
        }

        book.totalWinsAnalyzed = wins.size();
        book.totalTradesAnalyzed = totalTrades;
        System.out.println("[SuccessRecipe] Analyzed " + wins.size() + " wins out of " + totalTrades + " total trades");

        // Group by strategyType
        Map<String, List<WinContext>> byStrategy = wins.stream()
            .collect(Collectors.groupingBy(wc -> wc.getStrategyType()));

        for (Map.Entry<String, List<WinContext>> stratEntry : byStrategy.entrySet()) {
            String strategyType = stratEntry.getKey();
            List<WinContext> list = stratEntry.getValue();
            StrategyRecipe recipe = buildRecipe(strategyType, list);
            book.recipesByStrategy.put(strategyType, recipe);
            if (recipe.recommendedConfig != null) {
                book.recommendedAgents.add(recipe.recommendedConfig);
            }
        }

        save(book);
        return book;
    }

    private static StrategyRecipe buildRecipe(String strategyType, List<WinContext> wins) {
        StrategyRecipe r = new StrategyRecipe();
        r.strategyType = strategyType;
        r.winCount = wins.size();

        double[] profits = wins.stream().mapToDouble(w -> w.profitLossPct).toArray();
        r.avgProfitPct   = round2(Arrays.stream(profits).average().orElse(0));
        r.medianProfitPct= round2(median(profits));
        r.maxProfitPct   = round2(Arrays.stream(profits).max().orElse(0));
        r.minProfitPct   = round2(Arrays.stream(profits).min().orElse(0));

        // Collect all numerical snapshot keys across wins
        Set<String> numKeys = new LinkedHashSet<>();
        Set<String> boolKeys = new LinkedHashSet<>();
        for (WinContext wc : wins) {
            JsonNode snap = wc.tradeNode.path("entrySnapshot");
            if (snap.isObject()) {
                snap.fields().forEachRemaining(f -> {
                    if (f.getValue().isNumber()) numKeys.add(f.getKey());
                    else if (f.getValue().isBoolean()) boolKeys.add(f.getKey());
                });
            }
        }

        // Compute numerical stats per key
        for (String key : numKeys) {
            double[] vals = wins.stream()
                .mapToDouble(w -> w.tradeNode.path("entrySnapshot").path(key).asDouble(Double.NaN))
                .filter(v -> !Double.isNaN(v))
                .toArray();
            if (vals.length >= 2) {
                r.conditionStats.put(key, computeStats(vals));
            }
        }

        // Compute boolean frequencies per key
        for (String key : boolKeys) {
            int tc = 0, fc = 0;
            for (WinContext wc : wins) {
                boolean b = wc.tradeNode.path("entrySnapshot").path(key).asBoolean(false);
                if (b) tc++; else fc++;
            }
            BooleanFreq bf = new BooleanFreq();
            bf.trueCount = tc;
            bf.falseCount = fc;
            bf.truePct = round2(100.0 * tc / (tc + fc));
            r.booleanFreq.put(key, bf);
        }

        // Also analyze the agent filter thresholds that were in effect (legacy + new)
        Set<String> filterKeys = new LinkedHashSet<>();
        for (WinContext wc : wins) {
            if (wc.agentNode != null && wc.agentNode.has("entryFilters")) {
                wc.agentNode.get("entryFilters").fields().forEachRemaining(f -> {
                    if (f.getValue().isNumber()) filterKeys.add("filter_" + f.getKey());
                });
            }
            JsonNode snap = wc.tradeNode.path("entrySnapshot");
            snap.fields().forEachRemaining(f -> {
                if (f.getKey().startsWith("filter_") && f.getValue().isNumber()) filterKeys.add(f.getKey());
            });
        }

        for (String key : filterKeys) {
            String rawKey = key.replace("filter_", "");
            double[] vals = wins.stream()
                .mapToDouble(w -> {
                    // Prefer snapshot (exact at entry), fallback to agent node
                    double v = w.tradeNode.path("entrySnapshot").path(key).asDouble(Double.NaN);
                    if (!Double.isNaN(v)) return v;
                    if (w.agentNode != null && w.agentNode.has("entryFilters")) {
                        return w.agentNode.get("entryFilters").path(rawKey).asDouble(Double.NaN);
                    }
                    return Double.NaN;
                })
                .filter(v -> !Double.isNaN(v))
                .toArray();
            if (vals.length >= 2) {
                r.filterStats.put(key, computeStats(vals));
            }
        }

        // Save representative snapshots (top 10 by profit %)
        wins.stream()
            .sorted((a, b) -> Double.compare(b.profitLossPct, a.profitLossPct))
            .limit(10)
            .forEach(wc -> {
                WinSnapshot ws = new WinSnapshot();
                ws.ticker = wc.ticker;
                ws.agentId = wc.agentId;
                ws.entryTime = wc.entryTime;
                ws.profitLossPct = round2(wc.profitLossPct);
                JsonNode snap = wc.tradeNode.path("entrySnapshot");
                if (snap.isObject()) {
                    snap.fields().forEachRemaining(f -> {
                        if (f.getValue().isNumber()) ws.snapshot.put(f.getKey(), round2(f.getValue().asDouble()));
                        else if (f.getValue().isBoolean()) ws.snapshot.put(f.getKey(), f.getValue().asBoolean());
                        else ws.snapshot.put(f.getKey(), f.getValue().asText());
                    });
                }
                r.winSnapshots.add(ws);
            });

        // Build recommended agent configuration from the winning envelope (p25-p75)
        r.recommendedConfig = buildRecommendedAgent(strategyType, r, wins);

        return r;
    }

    private static RecommendedAgent buildRecommendedAgent(String strategyType, StrategyRecipe recipe, List<WinContext> wins) {
        RecommendedAgent rec = new RecommendedAgent();
        rec.id = "RECIPE_" + strategyType + "_" + wins.size() + "W";
        rec.name = "Recipe: " + strategyType + " (" + wins.size() + " wins)";
        rec.strategyType = strategyType;
        rec.basedOn = wins.size() + " winning trades";
        rec.winCount = wins.size();
        rec.expectedEdgePct = recipe.avgProfitPct;

        // Derive filters from the condition stats (market conditions at entry that won)
        // and from the filter stats (agent thresholds that were in effect during wins)
        Map<String, NumStat> stats = new LinkedHashMap<>(recipe.conditionStats);
        stats.putAll(recipe.filterStats);

        for (Map.Entry<String, NumStat> e : stats.entrySet()) {
            String key = e.getKey();
            NumStat s = e.getValue();

            if (key.startsWith("filter_")) {
                String filterKey = key.replace("filter_", "");
                // Use p25 as stricter lower bound if it increases the filter
                double recommended = round2(Math.max(s.min, s.p25));
                // But don't tighten beyond what was actually tested
                rec.entryFilters.put(filterKey, recommended);
            } else if (key.startsWith("risk_")) {
                String riskKey = key.replace("risk_", "");
                rec.riskManagement.put(riskKey, round2(s.median));
            } else {
                // Market condition insight — store as insight, not as agent config
                // (agent configs can't directly set "rsi" — they set rsiMin / rsiMax)
            }
        }

        // Special hardcoded mappings from observed conditions → filter recommendations
        // These are the "aha!" moments the user is looking for
        if (recipe.conditionStats.containsKey("rsi")) {
            NumStat rsi = recipe.conditionStats.get("rsi");
            rec.entryFilters.put("rsiMin", round2(Math.max(0, rsi.p25 - 2)));
            rec.entryFilters.put("rsiMax", round2(Math.min(100, rsi.p75 + 2)));
        }
        if (recipe.conditionStats.containsKey("rvol")) {
            NumStat rvol = recipe.conditionStats.get("rvol");
            rec.entryFilters.put("rvolMin", round2(Math.max(0.1, rvol.p25 - 0.1)));
        }
        if (recipe.conditionStats.containsKey("atrPct")) {
            NumStat atr = recipe.conditionStats.get("atrPct");
            rec.entryFilters.put("atrMinPct", round2(Math.max(0.5, atr.p25 - 0.3)));
        }
        if (recipe.conditionStats.containsKey("cci")) {
            NumStat cci = recipe.conditionStats.get("cci");
            rec.entryFilters.put("cciMin", round2(Math.max(-100, cci.p25 - 5)));
        }
        if (recipe.conditionStats.containsKey("momentum20d")) {
            NumStat mom = recipe.conditionStats.get("momentum20d");
            rec.entryFilters.put("rsMin", round2(Math.max(1.0, mom.p25 - 0.02)));
        }
        if (recipe.conditionStats.containsKey("pctFromWeek52High")) {
            NumStat p52 = recipe.conditionStats.get("pctFromWeek52High");
            // Winners were within X% of 52W high (negative = below)
            rec.entryFilters.put("maxPctFromWeek52High", round2(Math.min(0, p52.p75 + 1)));
        }
        if (recipe.booleanFreq.containsKey("priceAboveSMA200")) {
            BooleanFreq bf = recipe.booleanFreq.get("priceAboveSMA200");
            if (bf.truePct >= 80) {
                rec.entryFilters.put("sma200Required", true);
            }
        }
        if (recipe.booleanFreq.containsKey("maCrossoverUp")) {
            BooleanFreq bf = recipe.booleanFreq.get("maCrossoverUp");
            if (bf.truePct >= 80) {
                rec.entryFilters.put("sma50Required", true); // proxy
            }
        }

        // Risk management: use median of what worked
        if (recipe.filterStats.containsKey("risk_takeProfitPct")) {
            rec.riskManagement.put("takeProfitPct", round2(recipe.filterStats.get("risk_takeProfitPct").median));
        }
        if (recipe.filterStats.containsKey("risk_stopLossPct")) {
            rec.riskManagement.put("stopLossPct", round2(recipe.filterStats.get("risk_stopLossPct").median));
        }
        if (recipe.filterStats.containsKey("risk_atrMultiplier")) {
            rec.riskManagement.put("atrMultiplier", round2(recipe.filterStats.get("risk_atrMultiplier").median));
        }
        if (recipe.filterStats.containsKey("risk_trailingStopPct")) {
            rec.riskManagement.put("trailingStopPct", round2(recipe.filterStats.get("risk_trailingStopPct").median));
        }

        // Build Hebrew summary
        StringBuilder he = new StringBuilder();
        he.append("מתכון: ").append(strategyType).append(" | ")
          .append(wins.size()).append(" ניצחונות | תשואה ממוצעת ").append(recipe.avgProfitPct).append("%\n");
        if (recipe.conditionStats.containsKey("rsi")) {
            NumStat rsi = recipe.conditionStats.get("rsi");
            he.append("• RSI בניצחונות: ").append(rsi.min).append(" - ").append(rsi.max)
              .append(" (חציון ").append(rsi.median).append(")\n");
        }
        if (recipe.conditionStats.containsKey("rvol")) {
            NumStat rvol = recipe.conditionStats.get("rvol");
            he.append("• RVOL בניצחונות: ").append(rvol.min).append(" - ").append(rvol.max)
              .append(" (חציון ").append(rvol.median).append(")\n");
        }
        if (recipe.conditionStats.containsKey("atrPct")) {
            NumStat atr = recipe.conditionStats.get("atrPct");
            he.append("• ATR% בניצחונות: ").append(atr.min).append(" - ").append(atr.max)
              .append(" (חציון ").append(atr.median).append(")\n");
        }
        he.append("→ המלצה: בנה סוכן עם הפרמטרים המומלצים");
        rec.recipeSummaryHebrew = he.toString();

        return rec;
    }

    private static NumStat computeStats(double[] values) {
        NumStat s = new NumStat();
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        s.min = round2(sorted[0]);
        s.max = round2(sorted[sorted.length - 1]);
        s.avg = round2(Arrays.stream(sorted).average().orElse(0));
        s.median = round2(median(sorted));
        s.p25 = round2(percentile(sorted, 0.25));
        s.p75 = round2(percentile(sorted, 0.75));
        s.count = sorted.length;
        return s;
    }

    private static double median(double[] sorted) {
        return percentile(sorted, 0.5);
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
            System.err.println("[SuccessRecipe] Failed to read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static void save(RecipeBook book) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), book);
            System.out.println("[SuccessRecipe] Saved " + OUTPUT_PATH);
        } catch (Exception e) {
            System.err.println("[SuccessRecipe] Failed to save: " + e.getMessage());
        }
    }

    // Lightweight holder while analyzing
    private static class WinContext {
        String agentId;
        String ticker;
        double profitLossPct;
        String entryTime;
        JsonNode agentNode;
        JsonNode tradeNode;

        String getStrategyType() {
            // 1. trade snapshot
            String s = tradeNode.path("entrySnapshot").path("strategyType").asText("");
            if (!s.isBlank()) return s;
            // 2. trade direct field
            s = tradeNode.path("strategyType").asText("");
            if (!s.isBlank()) return s;
            // 3. agent node
            if (agentNode != null) {
                s = agentNode.path("strategyType").asText("");
                if (!s.isBlank()) return s;
                s = agentNode.path("type").asText("");
                if (!s.isBlank()) return s;
            }
            return "UNKNOWN";
        }
    }

    // ── CLI / quick test ─────────────────────────────────────────────────────

    public static void main(String[] args) {
        RecipeBook book = analyze();
        System.out.println("\n=== Success Recipe Summary ===");
        System.out.println("Wins analyzed: " + book.totalWinsAnalyzed + " / " + book.totalTradesAnalyzed);
        for (Map.Entry<String, StrategyRecipe> e : book.recipesByStrategy.entrySet()) {
            StrategyRecipe r = e.getValue();
            System.out.println("\n--- " + e.getKey() + " ---");
            System.out.println("Wins: " + r.winCount + " | Avg: " + r.avgProfitPct + "% | Median: " + r.medianProfitPct + "%");
            System.out.println("Top conditions:");
            r.conditionStats.forEach((k, v) ->
                System.out.println("  " + k + ": min=" + v.min + " max=" + v.max + " median=" + v.median + " p25=" + v.p25 + " p75=" + v.p75));
            if (r.recommendedConfig != null) {
                System.out.println("Recommended filters: " + r.recommendedConfig.entryFilters);
                System.out.println("Recommended risk:    " + r.recommendedConfig.riskManagement);
            }
        }
    }
}
