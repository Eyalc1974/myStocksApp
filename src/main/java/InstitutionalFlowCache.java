import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent TTL-based cache for Institutional Flow Layer data.
 *
 * Fundamental data changes rarely (quarterly) → 7-day TTL.
 * Catalyst (news/sentiment) changes daily → 24-hour TTL.
 *
 * File layout: {DATA_DIR}/institutional-flow-cache/{ticker}_fundamental.json
 *              {DATA_DIR}/institutional-flow-cache/{ticker}_catalyst.json
 */
public class InstitutionalFlowCache {

    // Base directory for cache files - configurable via environment variable
    // Default: "newStrategies" for local Windows development
    // Docker/Railway: Set DATA_DIR environment variable to appropriate path (e.g., "/app/data")
    private static final String DATA_DIR = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
    private static final Path CACHE_DIR = Paths.get(DATA_DIR, "institutional-flow-cache");
    private static final long FUNDAMENTAL_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days
    private static final long CATALYST_TTL_MS    = 24L * 60 * 60 * 1000;     // 24 hours

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // In-memory hot cache (survives within one JVM run)
    private static final ConcurrentHashMap<String, FundamentalData> fundamentalHot = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CatalystData>    catalystHot    = new ConcurrentHashMap<>();

    // ── Fundamental ─────────────────────────────────────────────────────────

    public static FundamentalData getFundamental(String ticker) {
        String t = ticker.toUpperCase();
        FundamentalData mem = fundamentalHot.get(t);
        if (mem != null && !isStale(mem.lastUpdatedEpochMs, FUNDAMENTAL_TTL_MS)) {
            return mem;
        }
        FundamentalData disk = loadFundamentalFromDisk(t);
        if (disk != null) {
            fundamentalHot.put(t, disk);
            return disk;
        }
        return null;
    }

    public static void putFundamental(String ticker, FundamentalData data) {
        String t = ticker.toUpperCase();
        data.lastUpdatedEpochMs = System.currentTimeMillis();
        fundamentalHot.put(t, data);
        saveFundamentalToDisk(t, data);
    }

    // ── Catalyst ────────────────────────────────────────────────────────────

    public static CatalystData getCatalyst(String ticker) {
        String t = ticker.toUpperCase();
        CatalystData mem = catalystHot.get(t);
        if (mem != null && !isStale(mem.lastUpdatedEpochMs, CATALYST_TTL_MS)) {
            return mem;
        }
        CatalystData disk = loadCatalystFromDisk(t);
        if (disk != null) {
            catalystHot.put(t, disk);
            return disk;
        }
        return null;
    }

    public static void putCatalyst(String ticker, CatalystData data) {
        String t = ticker.toUpperCase();
        data.lastUpdatedEpochMs = System.currentTimeMillis();
        catalystHot.put(t, data);
        saveCatalystToDisk(t, data);
    }

    // ── Disk I/O ────────────────────────────────────────────────────────────

    private static void saveFundamentalToDisk(String ticker, FundamentalData data) {
        try {
            Files.createDirectories(CACHE_DIR);
            ObjectNode node = JSON.createObjectNode();
            node.put("marketCap",              data.marketCap);
            node.put("revenueGrowth",          data.revenueGrowth);
            node.put("epsGrowth",              data.epsGrowth);
            node.put("eps",                    data.eps);
            node.put("profitMargin",           data.profitMargin);
            node.put("operatingMargin",        data.operatingMargin);
            node.put("peRatio",                data.peRatio);
            node.put("analystTargetPrice",     data.analystTargetPrice);
            node.put("beta",                   data.beta);
            node.put("debtToEquity",           data.debtToEquity);
            node.put("analystUpside",          data.analystUpside);
            node.put("institutionalScore",     data.institutionalScore);
            node.put("sector",                 data.sector);
            node.put("industry",               data.industry);
            node.put("lastUpdatedEpochMs",     data.lastUpdatedEpochMs);
            Files.writeString(fundamentalPath(ticker), JSON.writeValueAsString(node));
        } catch (Exception e) {
            // silent — cache is best-effort
        }
    }

    private static FundamentalData loadFundamentalFromDisk(String ticker) {
        try {
            Path p = fundamentalPath(ticker);
            if (!Files.exists(p)) return null;
            JsonNode node = JSON.readTree(Files.readString(p));
            FundamentalData d = new FundamentalData();
            d.marketCap            = node.path("marketCap").asDouble(0);
            d.revenueGrowth        = node.path("revenueGrowth").asDouble(0);
            d.epsGrowth            = node.path("epsGrowth").asDouble(0);
            d.eps                  = node.path("eps").asDouble(0);
            d.profitMargin         = node.path("profitMargin").asDouble(0);
            d.operatingMargin      = node.path("operatingMargin").asDouble(0);
            d.peRatio              = node.path("peRatio").asDouble(0);
            d.analystTargetPrice   = node.path("analystTargetPrice").asDouble(0);
            d.beta                 = node.path("beta").asDouble(0);
            d.debtToEquity         = node.path("debtToEquity").asDouble(0);
            d.analystUpside        = node.path("analystUpside").asDouble(0);
            d.institutionalScore   = node.path("institutionalScore").asDouble(0);
            d.sector               = node.path("sector").asText("");
            d.industry             = node.path("industry").asText("");
            d.lastUpdatedEpochMs   = node.path("lastUpdatedEpochMs").asLong(0);

            if (isStale(d.lastUpdatedEpochMs, FUNDAMENTAL_TTL_MS)) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCatalystToDisk(String ticker, CatalystData data) {
        try {
            Files.createDirectories(CACHE_DIR);
            ObjectNode node = JSON.createObjectNode();
            node.put("sentimentScore",        data.sentimentScore);
            node.put("relevanceScore",        data.relevanceScore);
            node.put("newsCount24h",          data.newsCount24h);
            node.put("earningsBeat",          data.earningsBeat);
            node.put("guidanceRaise",         data.guidanceRaise);
            node.put("analystUpgrade",        data.analystUpgrade);
            node.put("analystDowngrade",      data.analystDowngrade);
            node.put("hasEarningsMention",    data.hasEarningsMention);
            node.put("hasAIMention",          data.hasAIMention);
            node.put("hasMnaMention",         data.hasMnaMention);
            node.put("hasFDAStage",           data.hasFDAStage);
            node.put("hasPartnership",        data.hasPartnership);
            node.put("lastUpdatedEpochMs",    data.lastUpdatedEpochMs);
            Files.writeString(catalystPath(ticker), JSON.writeValueAsString(node));
        } catch (Exception e) {
            // silent
        }
    }

    private static CatalystData loadCatalystFromDisk(String ticker) {
        try {
            Path p = catalystPath(ticker);
            if (!Files.exists(p)) return null;
            JsonNode node = JSON.readTree(Files.readString(p));
            CatalystData d = new CatalystData();
            d.sentimentScore       = node.path("sentimentScore").asDouble(0);
            d.relevanceScore       = node.path("relevanceScore").asDouble(0);
            d.newsCount24h         = node.path("newsCount24h").asInt(0);
            d.earningsBeat         = node.path("earningsBeat").asBoolean(false);
            d.guidanceRaise        = node.path("guidanceRaise").asBoolean(false);
            d.analystUpgrade       = node.path("analystUpgrade").asBoolean(false);
            d.analystDowngrade     = node.path("analystDowngrade").asBoolean(false);
            d.hasEarningsMention   = node.path("hasEarningsMention").asBoolean(false);
            d.hasAIMention         = node.path("hasAIMention").asBoolean(false);
            d.hasMnaMention        = node.path("hasMnaMention").asBoolean(false);
            d.hasFDAStage          = node.path("hasFDAStage").asBoolean(false);
            d.hasPartnership       = node.path("hasPartnership").asBoolean(false);
            d.lastUpdatedEpochMs   = node.path("lastUpdatedEpochMs").asLong(0);

            if (isStale(d.lastUpdatedEpochMs, CATALYST_TTL_MS)) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path fundamentalPath(String ticker) {
        return CACHE_DIR.resolve(ticker.toUpperCase() + "_fundamental.json");
    }

    private static Path catalystPath(String ticker) {
        return CACHE_DIR.resolve(ticker.toUpperCase() + "_catalyst.json");
    }

    private static boolean isStale(long epochMs, long ttlMs) {
        if (epochMs <= 0) return true;
        return (System.currentTimeMillis() - epochMs) > ttlMs;
    }

    /** Clear hot cache (useful for testing). */
    public static void clearHot() {
        fundamentalHot.clear();
        catalystHot.clear();
    }

    /** Warm the hot cache from disk for a list of tickers (call at startup). */
    public static void warm(java.util.Collection<String> tickers) {
        for (String t : tickers) {
            getFundamental(t);
            getCatalyst(t);
        }
    }

    /** Stats for debugging. */
    public static String stats() {
        return String.format("IFL Cache: %d fundamental hot, %d catalyst hot",
            fundamentalHot.size(), catalystHot.size());
    }
}
