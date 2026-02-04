import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DailyTradingSimulator - Automated daily trading simulation using scanner signals.
 * 
 * Runs daily scanner to find stocks using Momentum and Swing strategies,
 * simulates buying with $1000 per stock, monitors every 15 minutes,
 * and decides when to sell using stop loss / take profit logic.
 */
public class DailyTradingSimulator {

    private static final Path STORE_PATH = Paths.get("daily-trading-sim.json");
    private static final double POSITION_SIZE = 1000.0; // $1000 per stock
    private static final int STOCKS_PER_STRATEGY = 4;   // 4 stocks per strategy
    private static final double STOP_LOSS_PCT = 2.0;    // 2% stop loss
    private static final double TAKE_PROFIT_PCT = 4.0;  // 4% take profit (2:1 R/R)
    private static final double TRAILING_STOP_PCT = 1.5; // 1.5% trailing stop after 2% gain
    
    // Enhanced Momentum Entry Criteria (from user recommendations)
    private static final double CCI_BREAKOUT_LEVEL = 100.0;    // CCI must break above 100 for momentum entry
    private static final double CCI_OVERBOUGHT = 200.0;        // Avoid if CCI > 200 (too extended)
    private static final int MA_FAST_PERIOD = 9;               // Fast MA for crossover
    private static final int MA_SLOW_PERIOD = 21;              // Slow MA for crossover
    private static final double RS_MIN_RATIO = 1.0;            // Stock must be stronger than market (RS > 1.0)
    private static final double RVOL_MIN_FOR_VALID_BREAKOUT = 1.5; // Minimum RVOL to avoid bull traps

    // Market regime gate (SPY)
    private static final double MARKET_WAIT_SPY_DROP_PCT = -0.5; // If SPY is down more than -0.5%, do not open new positions
    private static final int SPY_SMA_PERIOD = 20;
    private static final long SPY_RECHECK_INTERVAL_MS = 15 * 60 * 1000; // Re-check SPY every 15 minutes when in WAIT mode
    private static volatile long lastSpyCheckTime = 0;
    
    private static ScheduledExecutorService scheduler;
    private static ScheduledExecutorService dailyScheduler;
    private static final Object lock = new Object();

    private static final AtomicLong scannerRunId = new AtomicLong(0);
    private static volatile Thread scannerThread;
    
    // Daily scheduler settings
    private static final int SCAN_HOUR_ET = 9;      // 9:45 AM ET - after market open
    private static final int SCAN_MINUTE_ET = 45;
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private static final String POLYGON_API_KEY = System.getenv("POLYGON_API_KEY");

    private static final String DISCORD_WEBHOOK_URL = System.getenv("DAILY_SIM_DISCORD_WEBHOOK_URL");

    // Exit types
    public enum ExitType {
        STOP_LOSS("Stop Loss"),
        STOP_LIMIT("Stop Limit"),
        TAKE_PROFIT("Take Profit"),
        TRAILING_STOP("Trailing Stop"),
        END_OF_DAY("End of Day"),
        MANUAL("Manual"),
        HOLDING("HOLD");

        private final String display;
        ExitType(String display) { this.display = display; }
        public String getDisplay() { return display; }
    }

    // Strategy types
    public enum Strategy {
        MOMENTUM("Momentum"),
        SWING("Swing Trader"),
        INTRADAY("Intraday VWAP");

        private final String display;
        Strategy(String display) { this.display = display; }
        public String getDisplay() { return display; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimulatedTrade {
        public String ticker;
        public Strategy strategy;
        public String entryDate;
        public String entryTime;
        public double entryPrice;
        public double shares;
        public double positionValue;
        public double currentPrice;
        public double highestPrice;    // Track highest price for trailing stop
        public double stopLossPrice;
        public double takeProfitPrice;
        public double trailingStopPrice;
        public String exitDate;
        public String exitTime;
        public double exitPrice;
        public ExitType exitType;
        public boolean isOpen;
        public double profitLossPct;
        public double profitLossDollars;
        public String scanSignal;      // Original scanner signal
        public double rvol;
        public double rsi;
        
        // Enhanced momentum indicators
        public double cci;              // CCI value at entry
        public boolean cciBreakout;     // CCI crossed above 100
        public double rsRatio;          // Relative Strength vs SPY
        public boolean maCrossover;     // MA9 > MA21 (fast cross)
        public double pivotPP;          // Pivot Point
        public double pivotR1;          // Resistance 1 (first target)
        public double pivotR2;          // Resistance 2 (second target)
        public double pivotS1;          // Support 1 (stop level)
        public String entryReason;      // Why we entered
        public int momentumScore;       // Combined momentum score (0-100)
        
        // Variant tracking for A/B testing
        public String variantId;        // e.g., "M1_AGGRESSIVE", "S1_STANDARD"
        public String variantName;      // Display name
        
        public SimulatedTrade() {}
        
        public SimulatedTrade(String ticker, Strategy strategy, double entryPrice, String signal, double rvol, double rsi) {
            this.ticker = ticker;
            this.strategy = strategy;
            this.entryPrice = entryPrice;
            this.shares = POSITION_SIZE / entryPrice;
            this.positionValue = POSITION_SIZE;
            this.currentPrice = entryPrice;
            this.highestPrice = entryPrice;
            this.stopLossPrice = entryPrice * (1 - STOP_LOSS_PCT / 100.0);
            this.takeProfitPrice = entryPrice * (1 + TAKE_PROFIT_PCT / 100.0);
            this.trailingStopPrice = 0; // Activated after 2% gain
            this.isOpen = true;
            this.scanSignal = signal;
            this.rvol = rvol;
            this.rsi = rsi;
            this.exitType = ExitType.HOLDING;
            
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
            this.entryDate = now.toLocalDate().toString();
            this.entryTime = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        
        public void updatePrice(double newPrice) {
            this.currentPrice = newPrice;
            if (newPrice > highestPrice) {
                highestPrice = newPrice;
                // Activate trailing stop after 2% gain
                double gainPct = ((newPrice - entryPrice) / entryPrice) * 100;
                if (gainPct >= 2.0) {
                    trailingStopPrice = newPrice * (1 - TRAILING_STOP_PCT / 100.0);
                }
            }
            // Update P/L
            profitLossPct = ((currentPrice - entryPrice) / entryPrice) * 100;
            profitLossDollars = (currentPrice - entryPrice) * shares;
        }
        
        public ExitType checkExitCondition() {
            if (!isOpen) return exitType;
            
            // Check stop loss
            if (currentPrice <= stopLossPrice) {
                return ExitType.STOP_LOSS;
            }
            
            // Check take profit
            if (currentPrice >= takeProfitPrice) {
                return ExitType.TAKE_PROFIT;
            }
            
            // Check trailing stop (if activated)
            if (trailingStopPrice > 0 && currentPrice <= trailingStopPrice) {
                return ExitType.TRAILING_STOP;
            }
            
            return ExitType.HOLDING;
        }
        
        public void closePosition(ExitType type) {
            this.isOpen = false;
            this.exitType = type;
            this.exitPrice = currentPrice;
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
            this.exitDate = now.toLocalDate().toString();
            this.exitTime = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            this.profitLossPct = ((exitPrice - entryPrice) / entryPrice) * 100;
            this.profitLossDollars = (exitPrice - entryPrice) * shares;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimulatorStore {
        public List<SimulatedTrade> trades = new ArrayList<>();
        public String lastScanDate;
        public String lastScanTime;
        public String lastUpdateTime;
        public boolean scannerRunning;
        public String scannerStatus;
        public int totalScannedToday;
        public List<String> scannedTickers = new ArrayList<>();
        public Map<String, VariantPerformance> variantPerformance = new LinkedHashMap<>();

        // Market regime (SPY)
        public String marketCheckedAtNy;
        public Double spyChangePct;
        public Double spyPrice;
        public Double spyPrevClose;
        public Double spySma20;
        public Boolean spyAbovePrevClose;
        public Boolean spyAboveSma20;
        public Boolean marketWaitMode;
        public String marketWaitReason;
    }

    private static class SpyMarketRegime {
        double price;
        double prevClose;
        double changePct;
        Double sma20;
        boolean abovePrevClose;
        Boolean aboveSma20;
        boolean waitMode;
        String reason;
    }

    private static SpyMarketRegime computeSpyMarketRegime(MonitoringAlphaVantageClient av) {
        SpyMarketRegime r = new SpyMarketRegime();
        r.reason = "";
        try {
            JsonNode spyQuote = av.globalQuote("SPY");
            if (spyQuote != null) {
                JsonNode gq = spyQuote.path("Global Quote");
                if (gq == null || gq.isMissingNode()) {
                    gq = spyQuote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                }
                r.price = parseDouble(gq.path("05. price").asText(""));
                r.prevClose = parseDouble(gq.path("08. previous close").asText(""));
                String pct = gq.path("10. change percent").asText("").replace("%", "").trim();
                if (!pct.isBlank()) r.changePct = parseDouble(pct);
            }
        } catch (Exception ignore) {}

        // Fallback if prev close missing
        if (r.prevClose <= 0) r.prevClose = r.price;
        r.abovePrevClose = r.price > r.prevClose;

        // SMA20 from daily closes
        try {
            JsonNode daily = av.timeSeriesDaily("SPY");
            if (daily != null) {
                JsonNode ts = daily.path("Time Series (Daily)");
                if (ts != null && !ts.isMissingNode() && ts.fields().hasNext()) {
                    List<String> keys = new ArrayList<>();
                    ts.fieldNames().forEachRemaining(keys::add);
                    Collections.sort(keys, Collections.reverseOrder());
                    List<Double> closes = new ArrayList<>();
                    for (String k : keys) {
                        if (closes.size() >= SPY_SMA_PERIOD) break;
                        JsonNode day = ts.path(k);
                        if (day == null || day.isMissingNode()) continue;
                        double c = parseDouble(day.path("4. close").asText(""));
                        if (c > 0) closes.add(c);
                    }
                    if (closes.size() >= SPY_SMA_PERIOD) {
                        double sum = 0.0;
                        for (int i = 0; i < SPY_SMA_PERIOD; i++) sum += closes.get(i);
                        r.sma20 = sum / SPY_SMA_PERIOD;
                        r.aboveSma20 = r.price > r.sma20;
                    }
                }
            }
        } catch (Exception ignore) {}

        boolean waitByDrop = r.changePct <= MARKET_WAIT_SPY_DROP_PCT;
        boolean waitByTrend;
        if (r.aboveSma20 != null) {
            waitByTrend = !r.aboveSma20;
        } else {
            // If we cannot compute SMA20, fall back to previous close
            waitByTrend = !r.abovePrevClose;
        }
        r.waitMode = waitByDrop || waitByTrend;
        if (r.waitMode) {
            if (waitByDrop) {
                r.reason = "WAIT: SPY down " + String.format("%.2f", r.changePct) + "%";
            } else {
                r.reason = (r.aboveSma20 != null)
                    ? "WAIT: SPY below SMA" + SPY_SMA_PERIOD
                    : "WAIT: SPY below prev close";
            }
        } else {
            r.reason = "GO: Market OK";
        }
        return r;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariantPerformance {
        public String variantId;
        public String variantName;
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double totalProfitLossPct;
        public double totalProfitLossDollars;
        public double winRate;
        public double avgWinPct;
        public double avgLossPct;
        public double profitFactor;
        
        public VariantPerformance() {}
        
        public VariantPerformance(String variantId, String variantName) {
            this.variantId = variantId;
            this.variantName = variantName;
        }
        
        public void updateFromTrades(List<SimulatedTrade> allTrades) {
            List<SimulatedTrade> variantTrades = new ArrayList<>();
            for (SimulatedTrade t : allTrades) {
                if (variantId.equals(t.variantId) && !t.isOpen) {
                    variantTrades.add(t);
                }
            }
            
            totalTrades = variantTrades.size();
            winningTrades = 0;
            losingTrades = 0;
            totalProfitLossPct = 0;
            totalProfitLossDollars = 0;
            double totalWinPct = 0;
            double totalLossPct = 0;
            double grossProfit = 0;
            double grossLoss = 0;
            
            for (SimulatedTrade t : variantTrades) {
                totalProfitLossPct += t.profitLossPct;
                totalProfitLossDollars += t.profitLossDollars;
                if (t.profitLossPct > 0) {
                    winningTrades++;
                    totalWinPct += t.profitLossPct;
                    grossProfit += t.profitLossDollars;
                } else {
                    losingTrades++;
                    totalLossPct += Math.abs(t.profitLossPct);
                    grossLoss += Math.abs(t.profitLossDollars);
                }
            }
            
            winRate = totalTrades > 0 ? (winningTrades * 100.0 / totalTrades) : 0;
            avgWinPct = winningTrades > 0 ? totalWinPct / winningTrades : 0;
            avgLossPct = losingTrades > 0 ? totalLossPct / losingTrades : 0;
            profitFactor = grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 999 : 0);
        }
    }
    
    // Momentum Variants Configuration Classes
    private static final Path VARIANTS_CONFIG_PATH = Paths.get("momentum-variants.json");
    private static final Path INTRADAY_VARIANTS_CONFIG_PATH = Paths.get("intraday-variants.json");
    private static final Path SUCCESS_2026_CONFIG_PATH = Paths.get("momentum-success-2026.json");
    private static final Path SWING_VARIANTS_CONFIG_PATH = Paths.get("swing-variants.json");
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MomentumVariantsConfig {
        public String description;
        public boolean enabled = true;
        public List<VariantConfig> variants = new ArrayList<>();
        public List<VariantConfig> swingVariants = new ArrayList<>();
        
        public static MomentumVariantsConfig load() {
            try {
                if (Files.exists(VARIANTS_CONFIG_PATH)) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(VARIANTS_CONFIG_PATH.toFile(), MomentumVariantsConfig.class);
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error loading variants config: " + e.getMessage());
            }
            return createDefaultConfig();
        }
        
        private static MomentumVariantsConfig createDefaultConfig() {
            MomentumVariantsConfig config = new MomentumVariantsConfig();
            config.enabled = true;
            // Add default momentum variant
            VariantConfig m1 = new VariantConfig();
            m1.id = "M1_DEFAULT";
            m1.name = "Default Momentum";
            m1.stocksPerVariant = 4;
            config.variants.add(m1);
            // Add default swing variant
            VariantConfig s1 = new VariantConfig();
            s1.id = "S1_DEFAULT";
            s1.name = "Default Swing";
            s1.stocksPerVariant = 4;
            config.swingVariants.add(s1);
            return config;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariantConfig {
        public String id;
        public String name;
        public String nameHe;
        public String description;
        public int stocksPerVariant = 2;
        public VariantEntryFilters entryFilters = new VariantEntryFilters();
        public VariantRiskManagement riskManagement = new VariantRiskManagement();
        public VariantScoring scoring = new VariantScoring();
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariantEntryFilters {
        public double rvolMin = 2.0;
        public double rsiMin = 50;
        public double rsiMax = 80;
        public double cciMin = 100;
        public double cciMax = 200;
        public double rsMin = 1.10;
        public boolean sma200Required = false;
        public boolean maCrossoverRequired = false;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariantRiskManagement {
        public double stopLossPct = 2.0;
        public double takeProfitPct = 4.0;
        public double trailingStopPct = 1.5;
        public double atrMultiplier = 1.5;
        public double riskRewardRatio = 2.0;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VariantScoring {
        public int cciWeight = 35;
        public int rsWeight = 40;
        public int volumeWeight = 25;
        public int maCrossoverBonus = 10;
    }
    
    // Intraday VWAP Variants Configuration Classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayVariantsConfig {
        public String description;
        public boolean enabled = true;
        public int delayMinutes = 15;
        public IntradayVwapCore vwapCore = new IntradayVwapCore();
        public List<IntradayVariantConfig> variants = new ArrayList<>();
        
        public static IntradayVariantsConfig load() {
            try {
                if (Files.exists(INTRADAY_VARIANTS_CONFIG_PATH)) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(INTRADAY_VARIANTS_CONFIG_PATH.toFile(), IntradayVariantsConfig.class);
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error loading intraday variants config: " + e.getMessage());
            }
            return createDefaultIntradayConfig();
        }
        
        private static IntradayVariantsConfig createDefaultIntradayConfig() {
            IntradayVariantsConfig config = new IntradayVariantsConfig();
            config.enabled = true;
            IntradayVariantConfig i1 = new IntradayVariantConfig();
            i1.id = "I1_DEFAULT";
            i1.name = "Default Intraday VWAP";
            i1.stocksPerVariant = 2;
            config.variants.add(i1);
            return config;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayVwapCore {
        public String description;
        public boolean priceAboveVwapRequired = true;
        public double vwapBufferPct = 0.5;
        public boolean pullbackToVwapEnabled = true;
        public double pullbackMaxDistancePct = 1.0;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayVariantConfig {
        public String id;
        public String name;
        public String nameHe;
        public String description;
        public String descriptionHe;
        public int stocksPerVariant = 2;
        public IntradayEntryFilters entryFilters = new IntradayEntryFilters();
        public IntradayRiskManagement riskManagement = new IntradayRiskManagement();
        public IntradayTiming timing = new IntradayTiming();
        public IntradayScoring scoring = new IntradayScoring();
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayEntryFilters {
        public boolean vwapRequired = true;
        public double priceAboveVwapPct = 0.5;
        public boolean pullbackToVwapRequired = false;
        public double pullbackMaxDistancePct = 0.5;
        public boolean retestVwapRequired = false;
        public int retestHoldMinutes = 15;
        public int timeAboveVwapMinutes = 0;
        public double rvolMin = 1.5;
        public double rsiMin = 50;
        public double rsiMax = 75;
        public double cciMin = 100;
        public double cciMax = 250;
        public double rsMin = 1.10;
        public boolean sma200Required = true;
        public double intradayPriceChangeMinPct = 1.0;
        public double intradayPriceChangeMaxPct = 4.0;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayRiskManagement {
        public double stopLossBelowVwapPct = 0.5;
        public double stopLossMaxPct = 2.0;
        public double takeProfitPct = 4.0;
        public double trailingStopPct = 1.5;
        public double riskRewardRatio = 2.0;
        public double maxPositionPct = 5.0;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayTiming {
        public String entryWindowStart = "09:45";
        public String entryWindowEnd = "15:30";
        public int avoidFirstMinutes = 15;
        public int avoidLastMinutes = 30;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntradayScoring {
        public int vwapWeight = 30;
        public int cciWeight = 25;
        public int rsWeight = 25;
        public int volumeWeight = 20;
    }

    // Success 2026 Configuration with Market Guard
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Success2026Config {
        public String description;
        public boolean enabled = true;
        public int delayMinutes = 15;
        public MarketGuardConfig marketGuard = new MarketGuardConfig();
        public List<VariantConfig> variants = new ArrayList<>();
        public ExitRulesConfig exitRules = new ExitRulesConfig();
        
        public static Success2026Config load() {
            try {
                if (Files.exists(SUCCESS_2026_CONFIG_PATH)) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(SUCCESS_2026_CONFIG_PATH.toFile(), Success2026Config.class);
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error loading success-2026 config: " + e.getMessage());
            }
            return new Success2026Config(); // Return default (disabled)
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketGuardConfig {
        public boolean spyFilterEnabled = true;
        public int spySmaPeriod = 20;
        public double spyMinVwapDistancePct = 0.1;
        public double maxSpyDailyDropPct = -0.7;
        public String description;
        
        public boolean isMarketSafe(double spyPrice, double spyVwap, double spySma20, double spyDailyChangePct) {
            if (!spyFilterEnabled) return true;
            if (spyVwap > 0 && spyPrice < spyVwap) return false;
            if (spySma20 > 0 && spyPrice < spySma20) return false;
            if (spyDailyChangePct < maxSpyDailyDropPct) return false;
            return true;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExitRulesConfig {
        public List<ExitRuleConfig> rules = new ArrayList<>();
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExitRuleConfig {
        public String id;
        public String name;
        public String condition;
        public String action;
    }
    
    // Swing Variants Configuration
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwingVariantsConfig {
        public String description;
        public boolean enabled = true;
        public List<SwingVariantConfig> variants = new ArrayList<>();
        public MarketGuardConfig marketGuard = new MarketGuardConfig();
        
        public static SwingVariantsConfig load() {
            try {
                if (Files.exists(SWING_VARIANTS_CONFIG_PATH)) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(SWING_VARIANTS_CONFIG_PATH.toFile(), SwingVariantsConfig.class);
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error loading swing variants config: " + e.getMessage());
            }
            return createDefaultSwingConfig();
        }
        
        private static SwingVariantsConfig createDefaultSwingConfig() {
            SwingVariantsConfig config = new SwingVariantsConfig();
            config.enabled = true;
            SwingVariantConfig s1 = new SwingVariantConfig();
            s1.id = "S1_DEFAULT";
            s1.name = "Default Swing Pullback";
            s1.stocksPerVariant = 3;
            config.variants.add(s1);
            return config;
        }
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwingVariantConfig {
        public String id;
        public String name;
        public String nameHe;
        public String description;
        public int stocksPerVariant = 3;
        public SwingEntryFilters entryFilters = new SwingEntryFilters();
        public SwingRiskManagement riskManagement = new SwingRiskManagement();
        public SwingScoring scoring = new SwingScoring();
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwingEntryFilters {
        public double rvolMin = 0.8;
        public double rsiMin = 30;
        public double rsiMax = 50;
        public double rsMin = 1.05;
        public boolean sma200Required = true;
        public boolean priceAboveVwapRequired = false;
        public boolean maCrossoverRequired = false;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwingRiskManagement {
        public double stopLossPct = 5.0;
        public double takeProfitPct = 15.0;
        public double trailingStopPct = 3.0;
        public double atrMultiplier = 3.5;
        public double riskRewardRatio = 3.0;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwingScoring {
        public int rsWeight = 60;
        public int rsiWeight = 20;
        public int volumeWeight = 20;
        public int maCrossoverBonus = 0;
    }

    private static SimulatorStore store = new SimulatorStore();

    private static final DateTimeFormatter LOG_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String logTs() {
        return ZonedDateTime.now(NY_ZONE).format(LOG_TS_FORMAT);
    }

    private static void log(String msg) {
        System.out.println("[" + logTs() + "] " + msg);
    }

    private static void logErr(String msg) {
        System.err.println("[" + logTs() + "] " + msg);
    }

    public static void loadStore() {
        synchronized (lock) {
            try {
                if (Files.exists(STORE_PATH)) {
                    ObjectMapper mapper = new ObjectMapper();
                    store = mapper.readValue(STORE_PATH.toFile(), SimulatorStore.class);
                    if (store.trades == null) store.trades = new ArrayList<>();
                    if (store.scannedTickers == null) store.scannedTickers = new ArrayList<>();
                    if (store.variantPerformance == null) store.variantPerformance = new LinkedHashMap<>();
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error loading store: " + e.getMessage());
                store = new SimulatorStore();
            }
        }
    }
    
    /**
     * Update variant performance statistics from closed trades
     */
    public static void updateVariantPerformance() {
        synchronized (lock) {
            // Get all unique variant IDs from trades
            Set<String> variantIds = new HashSet<>();
            for (SimulatedTrade t : store.trades) {
                if (t.variantId != null) variantIds.add(t.variantId);
            }
            
            // Update or create performance for each variant
            for (String variantId : variantIds) {
                VariantPerformance perf = store.variantPerformance.get(variantId);
                if (perf == null) {
                    // Find variant name from first trade
                    String name = variantId;
                    for (SimulatedTrade t : store.trades) {
                        if (variantId.equals(t.variantId) && t.variantName != null) {
                            name = t.variantName;
                            break;
                        }
                    }
                    perf = new VariantPerformance(variantId, name);
                    store.variantPerformance.put(variantId, perf);
                }
                perf.updateFromTrades(store.trades);
            }
            saveStore();
        }
    }
    
    /**
     * Get variant performance sorted by total P/L (best first)
     */
    public static List<VariantPerformance> getVariantPerformanceRanked() {
        synchronized (lock) {
            List<VariantPerformance> ranked = new ArrayList<>(store.variantPerformance.values());
            ranked.sort((a, b) -> Double.compare(b.totalProfitLossDollars, a.totalProfitLossDollars));
            return ranked;
        }
    }

    public static void saveStore() {
        synchronized (lock) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(STORE_PATH.toFile(), store);
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error saving store: " + e.getMessage());
            }
        }
    }

    public static SimulatorStore getStore() {
        synchronized (lock) {
            return store;
        }
    }

    public static List<SimulatedTrade> getOpenTrades() {
        synchronized (lock) {
            List<SimulatedTrade> open = new ArrayList<>();
            for (SimulatedTrade t : store.trades) {
                if (t.isOpen) open.add(t);
            }
            return open;
        }
    }

    public static List<SimulatedTrade> getClosedTrades() {
        synchronized (lock) {
            List<SimulatedTrade> closed = new ArrayList<>();
            for (SimulatedTrade t : store.trades) {
                if (!t.isOpen) closed.add(t);
            }
            return closed;
        }
    }

    public static List<SimulatedTrade> getTodaysTrades() {
        synchronized (lock) {
            String today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().toString();
            List<SimulatedTrade> todays = new ArrayList<>();
            for (SimulatedTrade t : store.trades) {
                if (today.equals(t.entryDate)) todays.add(t);
            }
            return todays;
        }
    }

    /**
     * Run the daily scanner to find stocks for both Momentum and Swing strategies.
     * This should be called once at market open (9:45 AM ET recommended).
     */
    public static void runDailyScanner(MonitoringAlphaVantageClient av, String interval) {
        runDailyScanner(av, interval, false);
    }
    
    /**
     * Run the daily scanner with optional force rescan.
     */
    public static void runDailyScanner(MonitoringAlphaVantageClient av, String interval, boolean forceRescan) {
        final long runId;
        synchronized (lock) {
            if (store.scannerRunning) {
                if (!forceRescan) {
                    log("[DailyTradingSimulator] Scanner already running");
                    return;
                }

                // Force rescan: cancel the current scan (best-effort) and start a new one.
                log("[DailyTradingSimulator] Force rescan while scanner running - canceling current scan");
                Thread t = scannerThread;
                if (t != null) {
                    try { t.interrupt(); } catch (Exception ignore) {}
                }
                // Invalidate any in-flight scanner thread so it won't overwrite store state.
                scannerRunId.incrementAndGet();
                store.scannerRunning = false;
            }

            runId = scannerRunId.incrementAndGet();
            store.scannerRunning = true;
            store.scannerStatus = forceRescan ? "Starting scanner (forced rescan)..." : "Starting scanner...";
            saveStore();
        }

        Thread thread = new Thread(() -> {
            log("[DailyTradingSimulator] 🚀 Scanner thread started! runId=" + runId);
            try {
                String today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate().toString();
                log("[DailyTradingSimulator] Today: " + today + ", forceRescan=" + forceRescan);

                if (Thread.currentThread().isInterrupted() || scannerRunId.get() != runId) {
                    log("[DailyTradingSimulator] Scanner canceled before start (runId=" + runId + ")");
                    return;
                }
                
                // Check if already scanned today (unless force rescan)
                if (!forceRescan && today.equals(store.lastScanDate)) {
                    log("[DailyTradingSimulator] Already scanned today");
                    synchronized (lock) {
                        if (scannerRunId.get() == runId) {
                            store.scannerRunning = false;
                            store.scannerStatus = "Already scanned today at " + store.lastScanTime;
                            saveStore();
                        }
                    }
                    return;
                }
                
                if (forceRescan) {
                    log("[DailyTradingSimulator] Force rescan requested");
                }

                // Get universe of tickers - scan ALL stocks for complete coverage
                log("[DailyTradingSimulator] Getting universe tickers...");
                List<String> allTickers = LongTermCandidateFinder.getUniverseTickers();
                log("[DailyTradingSimulator] Got " + allTickers.size() + " tickers from universe");
                List<String> toScan = new ArrayList<>(allTickers); // Scan ALL tickers
                log("[DailyTradingSimulator] Will scan ALL " + toScan.size() + " tickers");

                synchronized (lock) {
                    store.scannedTickers = new ArrayList<>(toScan);
                    store.totalScannedToday = 0;
                    store.scannerStatus = "Scanning " + toScan.size() + " stocks...";
                    saveStore();
                }

                log("[DailyTradingSimulator] Starting scan of " + toScan.size() + " stocks");

                // Get SPY for market sentiment (use array wrapper to allow reassignment in re-check)
                final SpyMarketRegime[] spyRegimeHolder = new SpyMarketRegime[] { computeSpyMarketRegime(av) };
                lastSpyCheckTime = System.currentTimeMillis();
                final double[] spyChangeHolder = new double[] { spyRegimeHolder[0].changePct };
                synchronized (lock) {
                    if (scannerRunId.get() == runId) {
                        store.marketCheckedAtNy = ZonedDateTime.now(NY_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        store.spyChangePct = spyRegimeHolder[0].changePct;
                        store.spyPrice = spyRegimeHolder[0].price;
                        store.spyPrevClose = spyRegimeHolder[0].prevClose;
                        store.spySma20 = spyRegimeHolder[0].sma20;
                        store.spyAbovePrevClose = spyRegimeHolder[0].abovePrevClose;
                        store.spyAboveSma20 = spyRegimeHolder[0].aboveSma20;
                        store.marketWaitMode = spyRegimeHolder[0].waitMode;
                        store.marketWaitReason = spyRegimeHolder[0].reason;
                        saveStore();
                    }
                }

                List<ScanCandidate> momentumCandidates = new ArrayList<>();
                List<ScanCandidate> swingCandidates = new ArrayList<>();

                for (String symbol : toScan) {
                    try {
                        Thread.sleep(13000); // API rate limit

                        if (Thread.currentThread().isInterrupted() || scannerRunId.get() != runId) {
                            log("[DailyTradingSimulator] Scanner canceled mid-run (runId=" + runId + ")");
                            return;
                        }

                        synchronized (lock) {
                            if (scannerRunId.get() == runId) {
                                store.totalScannedToday++;
                                store.scannerStatus = "Scanning " + symbol + " (" + store.totalScannedToday + "/" + toScan.size() + ")";
                                saveStore();
                            }
                        }

                        // Enhanced scan with momentum indicators
                        EnhancedScanResult enhanced = scanStockEnhanced(av, symbol, interval, spyChangeHolder[0]);
                        if (enhanced == null || enhanced.result == null) continue;
                        
                        IntradayScanner.ScanResult result = enhanced.result;

                        // Accept BUY signals only - filter out HOLD/SELL
                        String sig = result.signal;
                        boolean isBuySignal = sig != null && (sig.contains("BUY") || sig.contains("BULLISH") || sig.contains("BREAKOUT"));
                        if (!isBuySignal) {
                            log("[DailyTradingSimulator] ⏭️ " + symbol + " skipped: " + sig + " (not a BUY signal)");
                            continue;
                        }
                        
                        ScanCandidate candidate = new ScanCandidate(symbol, result);
                        
                        // Copy enhanced indicators to candidate
                        candidate.cci = enhanced.cci;
                        candidate.cciBreakout = enhanced.cciBreakout;
                        candidate.rsRatio = enhanced.rsRatio;
                        candidate.maCrossover = enhanced.maCrossover;
                        candidate.pivotPP = enhanced.pivotPP;
                        candidate.pivotR1 = enhanced.pivotR1;
                        candidate.pivotR2 = enhanced.pivotR2;
                        candidate.pivotS1 = enhanced.pivotS1;
                        
                        // Calculate momentum score
                        candidate.calculateMomentumScore();
                        
                        // Add to momentum candidates - very relaxed base criteria
                        // Variant filters will do the actual filtering
                        if (result.rvol >= 1.0 && result.rsi >= 30 && result.rsi <= 90) {
                            momentumCandidates.add(candidate);
                            log("[DailyTradingSimulator] ✅ " + symbol + 
                                " candidate: Signal=" + result.signal +
                                ", Score=" + candidate.momentumScore + 
                                ", CCI=" + String.format("%.0f", candidate.cci) +
                                ", RS=" + String.format("%.2f", candidate.rsRatio) +
                                ", RVOL=" + String.format("%.1f", result.rvol) +
                                ", RSI=" + String.format("%.0f", result.rsi));
                        }
                        
                        // Add to swing candidates too
                        if (result.rvol >= 0.8 && result.rsi >= 30 && result.rsi <= 80) {
                            swingCandidates.add(candidate);
                        }

                        log("[DailyTradingSimulator] " + symbol + ": " + result.signal + 
                            " CCI=" + String.format("%.0f", enhanced.cci) + 
                            " RS=" + String.format("%.2f", enhanced.rsRatio));

                    } catch (InterruptedException e) {
                        log("[DailyTradingSimulator] Scanner interrupted (runId=" + runId + ")");
                        return;
                    } catch (Exception e) {
                        logErr("[DailyTradingSimulator] Error scanning " + symbol + ": " + e.getMessage());
                    }
                }

                // Sort MOMENTUM by momentum score (highest first), SWING by RVOL
                momentumCandidates.sort((a, b) -> Integer.compare(b.momentumScore, a.momentumScore));
                swingCandidates.sort((a, b) -> Double.compare(b.result.rvol, a.result.rvol));
                
                // Build INTRADAY candidates (need VWAP above)
                List<ScanCandidate> intradayCandidates = new ArrayList<>();
                for (ScanCandidate c : momentumCandidates) {
                    // Intraday requires price above VWAP
                    if (c.result.aboveVwap && c.result.vwap > 0) {
                        intradayCandidates.add(c);
                    }
                }
                // Sort intraday by combined VWAP distance + momentum score
                intradayCandidates.sort((a, b) -> {
                    double aVwapPct = (a.result.currentPrice - a.result.vwap) / a.result.vwap * 100;
                    double bVwapPct = (b.result.currentPrice - b.result.vwap) / b.result.vwap * 100;
                    // Prefer closer to VWAP (pullback) with good momentum score
                    double aScore = a.momentumScore - aVwapPct * 5; // Penalize being too far from VWAP
                    double bScore = b.momentumScore - bVwapPct * 5;
                    return Double.compare(bScore, aScore);
                });

                // Load variant configs
                MomentumVariantsConfig momentumConfig = MomentumVariantsConfig.load();
                IntradayVariantsConfig intradayConfig = IntradayVariantsConfig.load();
                Success2026Config success2026Config = Success2026Config.load();

                int totalMomentumTrades = 0;
                int totalSwingTrades = 0;
                int totalIntradayTrades = 0;
                int totalSuccess2026Trades = 0;

                // Assign candidates to variants (prevent duplicates per (ticker, variantId))
                Map<String, Integer> variantTradeCount = new HashMap<>();

                // Re-check SPY if in WAIT mode and 15 minutes have passed
                if (spyRegimeHolder[0].waitMode && (System.currentTimeMillis() - lastSpyCheckTime) >= SPY_RECHECK_INTERVAL_MS) {
                    log("[DailyTradingSimulator] Re-checking SPY (15 min passed, was in WAIT mode)...");
                    spyRegimeHolder[0] = computeSpyMarketRegime(av);
                    lastSpyCheckTime = System.currentTimeMillis();
                    spyChangeHolder[0] = spyRegimeHolder[0].changePct;
                    
                    // Update store with new SPY data
                    synchronized (lock) {
                        if (scannerRunId.get() == runId) {
                            store.marketCheckedAtNy = ZonedDateTime.now(NY_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                            store.spyChangePct = spyRegimeHolder[0].changePct;
                            store.spyPrice = spyRegimeHolder[0].price;
                            store.spyPrevClose = spyRegimeHolder[0].prevClose;
                            store.spySma20 = spyRegimeHolder[0].sma20;
                            store.spyAbovePrevClose = spyRegimeHolder[0].abovePrevClose;
                            store.spyAboveSma20 = spyRegimeHolder[0].aboveSma20;
                            store.marketWaitMode = spyRegimeHolder[0].waitMode;
                            store.marketWaitReason = spyRegimeHolder[0].reason;
                            saveStore();
                        }
                    }
                    
                    if (!spyRegimeHolder[0].waitMode) {
                        log("[DailyTradingSimulator] ✅ SPY recovered! Market now in GO mode: " + spyRegimeHolder[0].reason);
                    } else {
                        log("[DailyTradingSimulator] ⏳ SPY still in WAIT mode: " + spyRegimeHolder[0].reason);
                    }
                }

                // Process each MOMENTUM variant
                if (momentumConfig.enabled) {
                    for (VariantConfig variant : momentumConfig.variants) {
                        int variantCount = 0;
                        for (ScanCandidate c : momentumCandidates) {
                            if (variantCount >= variant.stocksPerVariant) break;
                            if (!matchesVariantFilters(c, variant)) continue;
                            String key = c.ticker + "_" + variant.id;
                            if (variantTradeCount.containsKey(key)) continue;

                            if (spyRegimeHolder[0].waitMode) {
                                log("[DailyTradingSimulator] WAIT MODE (" + spyRegimeHolder[0].reason + ") - skipping new MOMENTUM position for " + c.ticker);
                                continue;
                            }

                            SimulatedTrade trade = createEnhancedTrade(c, Strategy.MOMENTUM, variant);
                            store.trades.add(trade);
                            notifyOpenPosition(trade);
                            variantTradeCount.put(key, 1);
                            variantCount++;
                            totalMomentumTrades++;
                            log("[DailyTradingSimulator] " + variant.id + " BUY: " + c.ticker +
                                " @ $" + c.result.currentPrice + " Score=" + c.momentumScore);
                        }
                    }
                }

                // Process each SWING variant
                if (momentumConfig.enabled) {
                    for (VariantConfig variant : momentumConfig.swingVariants) {
                        int variantCount = 0;
                        for (ScanCandidate c : swingCandidates) {
                            if (variantCount >= variant.stocksPerVariant) break;
                            if (!matchesSwingVariantFilters(c, variant)) continue;
                            String key = c.ticker + "_" + variant.id;
                            if (variantTradeCount.containsKey(key)) continue;

                            if (spyRegimeHolder[0].waitMode) {
                                log("[DailyTradingSimulator] WAIT MODE (" + spyRegimeHolder[0].reason + ") - skipping new SWING position for " + c.ticker);
                                continue;
                            }

                            SimulatedTrade trade = createEnhancedTrade(c, Strategy.SWING, variant);
                            store.trades.add(trade);
                            notifyOpenPosition(trade);
                            variantTradeCount.put(key, 1);
                            variantCount++;
                            totalSwingTrades++;
                            log("[DailyTradingSimulator] " + variant.id + " BUY: " + c.ticker +
                                " @ $" + c.result.currentPrice);
                        }
                    }
                }

                // Process each INTRADAY VWAP variant
                if (intradayConfig.enabled) {
                    for (IntradayVariantConfig variant : intradayConfig.variants) {
                        int variantCount = 0;
                        for (ScanCandidate c : intradayCandidates) {
                            if (variantCount >= variant.stocksPerVariant) break;
                            if (!matchesIntradayVariantFilters(c, variant, intradayConfig)) continue;
                            String key = c.ticker + "_" + variant.id;
                            if (variantTradeCount.containsKey(key)) continue;

                            if (spyRegimeHolder[0].waitMode) {
                                log("[DailyTradingSimulator] WAIT MODE (" + spyRegimeHolder[0].reason + ") - skipping new INTRADAY position for " + c.ticker);
                                continue;
                            }

                            SimulatedTrade trade = createIntradayTrade(c, variant);
                            store.trades.add(trade);
                            notifyOpenPosition(trade);
                            variantTradeCount.put(key, 1);
                            variantCount++;
                            totalIntradayTrades++;
                            double vwapPct = (c.result.currentPrice - c.result.vwap) / c.result.vwap * 100;
                            log("[DailyTradingSimulator] " + variant.id + " BUY: " + c.ticker +
                                " @ $" + c.result.currentPrice + " VWAP=$" + String.format("%.2f", c.result.vwap) +
                                " (+" + String.format("%.1f%%", vwapPct) + " above VWAP)");
                        }
                    }
                }

                // Process SUCCESS 2026 variants (with enhanced market guard)
                if (success2026Config.enabled) {
                    // Check market guard from config (uses stricter thresholds)
                    boolean marketSafe = success2026Config.marketGuard.isMarketSafe(
                        spyRegimeHolder[0].price, 
                        0, // VWAP not available from daily data
                        spyRegimeHolder[0].sma20 != null ? spyRegimeHolder[0].sma20 : 0,
                        spyRegimeHolder[0].changePct
                    );
                    
                    if (!marketSafe) {
                        log("[DailyTradingSimulator] SUCCESS_2026 Market Guard BLOCKED - SPY conditions not met");
                    } else {
                        for (VariantConfig variant : success2026Config.variants) {
                            int variantCount = 0;
                            for (ScanCandidate c : momentumCandidates) {
                                if (variantCount >= variant.stocksPerVariant) break;
                                if (!matchesVariantFilters(c, variant)) continue;
                                String key = c.ticker + "_" + variant.id;
                                if (variantTradeCount.containsKey(key)) continue;

                                if (spyRegimeHolder[0].waitMode) {
                                    log("[DailyTradingSimulator] WAIT MODE (" + spyRegimeHolder[0].reason + ") - skipping SUCCESS_2026 position for " + c.ticker);
                                    continue;
                                }

                                SimulatedTrade trade = createEnhancedTrade(c, Strategy.MOMENTUM, variant);
                                store.trades.add(trade);
                                notifyOpenPosition(trade);
                                variantTradeCount.put(key, 1);
                                variantCount++;
                                totalSuccess2026Trades++;
                                log("[DailyTradingSimulator] SUCCESS_2026 " + variant.id + " BUY: " + c.ticker +
                                    " @ $" + c.result.currentPrice + " Score=" + c.momentumScore);
                            }
                        }
                    }
                }

                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
                if (scannerRunId.get() == runId) {
                    store.lastScanDate = today;
                    store.lastScanTime = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
                    store.scannerRunning = false;
                    String waitSuffix = (store.marketWaitMode != null && store.marketWaitMode) ? (" | " + (store.marketWaitReason == null ? "WAIT" : store.marketWaitReason)) : "";
                    store.scannerStatus = "Completed. " + totalMomentumTrades + " Momentum + " +
                        totalSwingTrades + " Swing + " + totalIntradayTrades + " Intraday VWAP + " +
                        totalSuccess2026Trades + " Success2026 trades" + waitSuffix;
                    saveStore();
                }

                log("[DailyTradingSimulator] Scanner complete: " + totalMomentumTrades + 
                    " Momentum, " + totalSwingTrades + " Swing, " + totalIntradayTrades + " Intraday VWAP, " +
                    totalSuccess2026Trades + " Success2026");

            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Scanner error: " + e.getMessage());
                synchronized (lock) {
                    if (scannerRunId.get() == runId) {
                        store.scannerRunning = false;
                        store.scannerStatus = "Error: " + e.getMessage();
                        saveStore();
                    }
                }
            }
        });
        thread.setName("daily-trading-scanner-" + runId);
        thread.setDaemon(true);
        scannerThread = thread;
        thread.start();
    }

    /**
     * Enhanced scan candidate with momentum indicators
     */
    private static class ScanCandidate {
        String ticker;
        IntradayScanner.ScanResult result;
        
        // Enhanced momentum indicators
        double cci;
        boolean cciBreakout;      // CCI crossed above 100
        double rsRatio;           // Relative Strength vs SPY
        boolean maCrossover;      // MA9 > MA21
        double pivotPP;
        double pivotR1;
        double pivotR2;
        double pivotS1;
        int momentumScore;        // 0-100 combined score
        String entryReason;
        
        ScanCandidate(String ticker, IntradayScanner.ScanResult result) {
            this.ticker = ticker;
            this.result = result;
        }
        
        /**
         * Calculate combined momentum score based on key indicators.
         * Focus on 3 main indicators: CCI breakout, RS, and Volume confirmation.
         */
        void calculateMomentumScore() {
            int score = 0;
            StringBuilder reason = new StringBuilder();
            
            // CCI breakout (35 points) - Most important for momentum entry
            if (cciBreakout && cci >= CCI_BREAKOUT_LEVEL && cci < CCI_OVERBOUGHT) {
                score += 35;
                reason.append("CCI פריצה מעל 100 (").append(String.format("%.0f", cci)).append("); ");
            } else if (cci >= CCI_BREAKOUT_LEVEL) {
                score += 15; // CCI above 100 but not confirmed breakout
            }
            
            // Relative Strength (40 points) - Stock must outperform market
            if (rsRatio >= 1.20) {
                score += 40;
                reason.append("RS מנהיג שוק (").append(String.format("%.2f", rsRatio)).append("); ");
            } else if (rsRatio >= 1.10) {
                score += 30;
                reason.append("RS חזק (").append(String.format("%.2f", rsRatio)).append("); ");
            } else if (rsRatio >= 1.0) {
                score += 15;
            }
            
            // Volume confirmation (25 points) - Avoid bull traps
            if (result.rvol >= 2.5) {
                score += 25;
                reason.append("RVOL חריג (").append(String.format("%.1fx", result.rvol)).append("); ");
            } else if (result.rvol >= RVOL_MIN_FOR_VALID_BREAKOUT) {
                score += 15;
            }
            
            // Bonus: MA crossover confirmation
            if (maCrossover) {
                score += 10;
                reason.append("הצלבת MA9/21; ");
            }
            
            // Penalty: Avoid overbought
            if (cci > CCI_OVERBOUGHT || result.rsi > 80) {
                score -= 20;
                reason.append("⚠️ Overbought; ");
            }
            
            this.momentumScore = Math.max(0, Math.min(100, score));
            this.entryReason = reason.length() > 0 ? reason.toString() : "Basic signal";
        }
    }

    private static IntradayScanner.ScanResult scanStock(MonitoringAlphaVantageClient av, String symbol, 
                                                         String interval, double spyChange) throws Exception {
        JsonNode intraday = av.timeSeriesIntraday(symbol, interval);
        if (intraday == null) return null;

        JsonNode ts = intraday.path("Time Series (" + interval + ")");
        if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) {
            ts = intraday.path("Time Series (" + interval + ") - DATA DELAYED BY 15 MINUTES");
        }
        if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) return null;

        List<String> keys = new ArrayList<>();
        ts.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        if (keys.size() < 5) return null;

        List<IntradayScanner.IntradayBar> bars = new ArrayList<>();
        for (String tsKey : keys) {
            JsonNode bar = ts.path(tsKey);
            IntradayScanner.IntradayBar ib = new IntradayScanner.IntradayBar();
            ib.timestamp = tsKey;
            ib.open = parseDouble(bar.path("1. open").asText(""));
            ib.high = parseDouble(bar.path("2. high").asText(""));
            ib.low = parseDouble(bar.path("3. low").asText(""));
            ib.close = parseDouble(bar.path("4. close").asText(""));
            String volStr = bar.path("5. volume").asText("");
            ib.volume = volStr.isBlank() ? 0L : Long.parseLong(volStr);
            bars.add(ib);
        }

        long totalVol = bars.stream().mapToLong(b -> b.volume).sum();
        double avgDailyVol = totalVol > 0 ? totalVol * 0.5 : 1_000_000;

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
                        prevDayHigh = parseDouble(prevDay.path("2. high").asText(""));
                        prevDayClose = parseDouble(prevDay.path("4. close").asText(""));
                    }
                }
            }
        } catch (Exception ignore) {}

        return IntradayScanner.scan(symbol, bars, avgDailyVol, prevDayHigh, prevDayClose, spyChange);
    }

    private static double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Calculate CCI from price bars and check for breakout above 100
     */
    private static double[] calculateCCIWithBreakout(List<IntradayScanner.IntradayBar> bars, int period) {
        if (bars.size() < period + 1) return new double[]{0, 0}; // [currentCCI, wasBreakout]
        
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        
        for (IntradayScanner.IntradayBar bar : bars) {
            highs.add(bar.high);
            lows.add(bar.low);
            closes.add(bar.close);
        }
        
        List<Double> cciValues = CCI.calculateCCI(highs, lows, closes, period);
        if (cciValues.isEmpty()) return new double[]{0, 0};
        
        // Get last two valid CCI values to detect breakout
        Double currentCCI = null;
        Double prevCCI = null;
        for (int i = cciValues.size() - 1; i >= 0 && (currentCCI == null || prevCCI == null); i--) {
            if (cciValues.get(i) != null) {
                if (currentCCI == null) currentCCI = cciValues.get(i);
                else prevCCI = cciValues.get(i);
            }
        }
        
        if (currentCCI == null) return new double[]{0, 0};
        
        // Check if CCI just crossed above 100 (breakout)
        boolean breakout = (prevCCI != null && prevCCI < CCI_BREAKOUT_LEVEL && currentCCI >= CCI_BREAKOUT_LEVEL);
        
        return new double[]{currentCCI, breakout ? 1 : 0};
    }

    /**
     * Calculate Simple Moving Average
     */
    private static double calculateSMA(List<Double> prices, int period) {
        if (prices.size() < period) return 0;
        double sum = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return sum / period;
    }

    /**
     * Check if fast MA (9) crossed above slow MA (21) - "The Fast Cross"
     */
    private static boolean checkMACrossover(List<IntradayScanner.IntradayBar> bars) {
        if (bars.size() < MA_SLOW_PERIOD + 2) return false;
        
        List<Double> closes = new ArrayList<>();
        for (IntradayScanner.IntradayBar bar : bars) {
            closes.add(bar.close);
        }
        
        // Current values
        double currentMA9 = calculateSMA(closes, MA_FAST_PERIOD);
        double currentMA21 = calculateSMA(closes, MA_SLOW_PERIOD);
        
        // Previous values (remove last price)
        List<Double> prevCloses = closes.subList(0, closes.size() - 1);
        double prevMA9 = calculateSMA(prevCloses, MA_FAST_PERIOD);
        double prevMA21 = calculateSMA(prevCloses, MA_SLOW_PERIOD);
        
        // Check for crossover: MA9 was below MA21, now above
        return (prevMA9 <= prevMA21 && currentMA9 > currentMA21);
    }

    /**
     * Calculate Relative Strength ratio vs SPY
     */
    private static double calculateRSRatio(double stockChangePct, double spyChangePct) {
        // Simple RS calculation for intraday
        return (1 + stockChangePct / 100.0) / (1 + spyChangePct / 100.0);
    }

    /**
     * Enhanced scan result with additional momentum indicators
     */
    private static class EnhancedScanResult {
        IntradayScanner.ScanResult result;
        double cci;
        boolean cciBreakout;
        double rsRatio;
        boolean maCrossover;
        double pivotPP, pivotR1, pivotR2, pivotS1;
    }

    /**
     * Enhanced stock scan with CCI, MA crossover, RS, and Pivot Points
     */
    private static EnhancedScanResult scanStockEnhanced(MonitoringAlphaVantageClient av, String symbol, 
                                                         String interval, double spyChange) throws Exception {
        JsonNode intraday = av.timeSeriesIntraday(symbol, interval);
        if (intraday == null) return null;

        JsonNode ts = intraday.path("Time Series (" + interval + ")");
        if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) {
            ts = intraday.path("Time Series (" + interval + ") - DATA DELAYED BY 15 MINUTES");
        }
        if (ts == null || ts.isMissingNode() || !ts.fields().hasNext()) return null;

        List<String> keys = new ArrayList<>();
        ts.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        if (keys.size() < 5) return null;

        List<IntradayScanner.IntradayBar> bars = new ArrayList<>();
        for (String tsKey : keys) {
            JsonNode bar = ts.path(tsKey);
            IntradayScanner.IntradayBar ib = new IntradayScanner.IntradayBar();
            ib.timestamp = tsKey;
            ib.open = parseDouble(bar.path("1. open").asText(""));
            ib.high = parseDouble(bar.path("2. high").asText(""));
            ib.low = parseDouble(bar.path("3. low").asText(""));
            ib.close = parseDouble(bar.path("4. close").asText(""));
            String volStr = bar.path("5. volume").asText("");
            ib.volume = volStr.isBlank() ? 0L : Long.parseLong(volStr);
            bars.add(ib);
        }

        long totalVol = bars.stream().mapToLong(b -> b.volume).sum();
        double avgDailyVol = totalVol > 0 ? totalVol * 0.5 : 1_000_000;

        double prevDayHigh = 0, prevDayLow = 0, prevDayClose = 0;
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
                        prevDayHigh = parseDouble(prevDay.path("2. high").asText(""));
                        prevDayLow = parseDouble(prevDay.path("3. low").asText(""));
                        prevDayClose = parseDouble(prevDay.path("4. close").asText(""));
                    }
                }
            }
        } catch (Exception ignore) {}

        // Get base scan result
        IntradayScanner.ScanResult result = IntradayScanner.scan(symbol, bars, avgDailyVol, prevDayHigh, prevDayClose, spyChange);
        if (result == null) return null;

        // Create enhanced result
        EnhancedScanResult enhanced = new EnhancedScanResult();
        enhanced.result = result;

        // Calculate CCI (20 period)
        double[] cciData = calculateCCIWithBreakout(bars, 20);
        enhanced.cci = cciData[0];
        enhanced.cciBreakout = cciData[1] > 0;

        // Check MA crossover (9/21)
        enhanced.maCrossover = checkMACrossover(bars);

        // Calculate RS ratio
        enhanced.rsRatio = calculateRSRatio(result.priceChangePct, spyChange);

        // Calculate Pivot Points from previous day
        if (prevDayHigh > 0 && prevDayLow > 0 && prevDayClose > 0) {
            Map<String, Double> pivots = PivotPoints.calculatePivots(prevDayHigh, prevDayLow, prevDayClose);
            enhanced.pivotPP = pivots.getOrDefault("PP", 0.0);
            enhanced.pivotR1 = pivots.getOrDefault("R1", 0.0);
            enhanced.pivotR2 = pivots.getOrDefault("R2", 0.0);
            enhanced.pivotS1 = pivots.getOrDefault("S1", 0.0);
        }

        return enhanced;
    }

    /**
     * Create an enhanced trade with momentum indicators and pivot-based targets
     */
    private static SimulatedTrade createEnhancedTrade(ScanCandidate c, Strategy strategy) {
        return createEnhancedTrade(c, strategy, null);
    }
    
    /**
     * Create an enhanced trade with variant configuration for A/B testing
     */
    private static SimulatedTrade createEnhancedTrade(ScanCandidate c, Strategy strategy, VariantConfig variant) {
        SimulatedTrade trade = new SimulatedTrade(
            c.ticker, strategy, c.result.currentPrice,
            c.result.signal, c.result.rvol, c.result.rsi
        );
        
        // Add enhanced indicators
        trade.cci = c.cci;
        trade.cciBreakout = c.cciBreakout;
        trade.rsRatio = c.rsRatio;
        trade.maCrossover = c.maCrossover;
        trade.pivotPP = c.pivotPP;
        trade.pivotR1 = c.pivotR1;
        trade.pivotR2 = c.pivotR2;
        trade.pivotS1 = c.pivotS1;
        trade.momentumScore = c.momentumScore;
        trade.entryReason = c.entryReason;
        
        // Assign variant ID for A/B testing
        if (variant != null) {
            trade.variantId = variant.id;
            trade.variantName = variant.name;
            
            // Use variant-specific risk management
            double entryPrice = c.result.currentPrice;
            trade.stopLossPrice = entryPrice * (1 - variant.riskManagement.stopLossPct / 100.0);
            trade.takeProfitPrice = entryPrice * (1 + variant.riskManagement.takeProfitPct / 100.0);
        } else {
            trade.variantId = strategy == Strategy.MOMENTUM ? "M_DEFAULT" : "S_DEFAULT";
            trade.variantName = strategy == Strategy.MOMENTUM ? "Default Momentum" : "Default Swing";
        }
        
        // Use Pivot Points for dynamic targets (if available and reasonable)
        if (strategy == Strategy.MOMENTUM && c.pivotR1 > 0) {
            // For MOMENTUM: Use R1 as first target, tighten trailing stop at R2
            if (c.pivotR1 > c.result.currentPrice * 1.01) { // R1 at least 1% above entry
                trade.takeProfitPrice = c.pivotR1;
            }
            // Use S1 as stop if it's within 3% of entry (otherwise keep 2% default)
            if (c.pivotS1 > 0 && c.pivotS1 > c.result.currentPrice * 0.97) {
                trade.stopLossPrice = c.pivotS1;
            }
        }
        
        return trade;
    }

    private static boolean matchesVariantFilters(ScanCandidate c, VariantConfig variant) {
        VariantEntryFilters f = variant.entryFilters;
        if (f == null) return true;

        if (c.result.rvol < f.rvolMin) {
            log("[VariantFilter] " + c.ticker + " rejected by " + variant.id + ": RVOL " + String.format("%.1f", c.result.rvol) + " < " + f.rvolMin);
            return false;
        }

        if (c.result.rsi < f.rsiMin || c.result.rsi > f.rsiMax) {
            log("[VariantFilter] " + c.ticker + " rejected by " + variant.id +
                ": RSI " + String.format("%.0f", c.result.rsi) + " not in [" + f.rsiMin + "-" + f.rsiMax + "]");
            return false;
        }

        if (c.cci < f.cciMin || c.cci > f.cciMax) {
            log("[VariantFilter] " + c.ticker + " rejected by " + variant.id +
                ": CCI " + String.format("%.0f", c.cci) + " not in [" + f.cciMin + "-" + f.cciMax + "]");
            return false;
        }

        if (c.rsRatio < f.rsMin) {
            log("[VariantFilter] " + c.ticker + " rejected by " + variant.id +
                ": RS " + String.format("%.2f", c.rsRatio) + " < " + f.rsMin);
            return false;
        }

        if (f.maCrossoverRequired && !c.maCrossover) {
            log("[VariantFilter] " + c.ticker + " rejected by " + variant.id + ": MA crossover required");
            return false;
        }

        if (f.sma200Required) {
            log("[VariantFilter] " + c.ticker + " note: " + variant.id + " has sma200Required=true but SMA200 is not available in ScanCandidate; ignoring this filter");
        }

        return true;
    }

    private static boolean matchesSwingVariantFilters(ScanCandidate c, VariantConfig variant) {
        return matchesVariantFilters(c, variant);
    }
    
    /**
     * Check if a candidate matches a swing variant's entry filters (from swing-variants.json)
     */
    private static boolean matchesSwingVariantFiltersNew(ScanCandidate c, SwingVariantConfig variant) {
        SwingEntryFilters f = variant.entryFilters;
        if (f == null) return true;

        // Check RVOL (swing has lower requirements)
        if (c.result.rvol < f.rvolMin) {
            log("[SwingFilter] " + c.ticker + " rejected by " + variant.id + ": RVOL " + String.format("%.1f", c.result.rvol) + " < " + f.rvolMin);
            return false;
        }

        // Check RSI range - for swing pullback we want LOW RSI (oversold)
        if (c.result.rsi < f.rsiMin || c.result.rsi > f.rsiMax) {
            log("[SwingFilter] " + c.ticker + " rejected by " + variant.id +
                ": RSI " + String.format("%.0f", c.result.rsi) + " not in [" + f.rsiMin + "-" + f.rsiMax + "]");
            return false;
        }

        // Check RS (Relative Strength vs SPY)
        if (c.rsRatio < f.rsMin) {
            log("[SwingFilter] " + c.ticker + " rejected by " + variant.id + ": RS " + String.format("%.2f", c.rsRatio) + " < " + f.rsMin);
            return false;
        }

        // Check MA crossover if required
        if (f.maCrossoverRequired && !c.maCrossover) {
            log("[SwingFilter] " + c.ticker + " rejected by " + variant.id + ": MA crossover required but not present");
            return false;
        }

        // Check VWAP if required
        if (f.priceAboveVwapRequired && !c.result.aboveVwap) {
            log("[SwingFilter] " + c.ticker + " rejected by " + variant.id + ": Price above VWAP required but not met");
            return false;
        }

        return true;
    }
    
    /**
     * Calculate swing score based on swing-specific scoring weights
     */
    private static int calculateSwingScore(ScanCandidate c, SwingScoring scoring) {
        int score = 0;
        
        // RS Weight - most important for swing (historical strength)
        if (c.rsRatio >= 1.20) {
            score += scoring.rsWeight;
        } else if (c.rsRatio >= 1.10) {
            score += (int)(scoring.rsWeight * 0.75);
        } else if (c.rsRatio >= 1.0) {
            score += (int)(scoring.rsWeight * 0.5);
        }
        
        // RSI Weight - for pullback, lower RSI is better (more oversold)
        if (c.result.rsi <= 35) {
            score += scoring.rsiWeight; // Deep pullback - full points
        } else if (c.result.rsi <= 45) {
            score += (int)(scoring.rsiWeight * 0.75);
        } else if (c.result.rsi <= 55) {
            score += (int)(scoring.rsiWeight * 0.5);
        }
        
        // Volume Weight
        if (c.result.rvol >= 1.5) {
            score += scoring.volumeWeight;
        } else if (c.result.rvol >= 1.0) {
            score += (int)(scoring.volumeWeight * 0.6);
        } else if (c.result.rvol >= 0.8) {
            score += (int)(scoring.volumeWeight * 0.3);
        }
        
        // MA Crossover Bonus
        if (c.maCrossover) {
            score += scoring.maCrossoverBonus;
        }
        
        return Math.min(100, score);
    }

    /**
     * Check if a candidate matches an intraday VWAP variant's entry filters
     */
    private static boolean matchesIntradayVariantFilters(ScanCandidate c, IntradayVariantConfig variant, IntradayVariantsConfig config) {
        IntradayEntryFilters f = variant.entryFilters;
        if (f == null) return true;

        // VWAP is the core filter for intraday
        if (f.vwapRequired && !c.result.aboveVwap) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id + ": Not above VWAP");
            return false;
        }

        // Check distance from VWAP (price must be at least X% above VWAP)
        if (c.result.vwap > 0) {
            double vwapPct = (c.result.currentPrice - c.result.vwap) / c.result.vwap * 100;
            if (vwapPct < f.priceAboveVwapPct) {
                log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                    ": VWAP distance " + String.format("%.2f%%", vwapPct) + " < " + f.priceAboveVwapPct + "%");
                return false;
            }
            // Don't chase - reject if too far from VWAP (using intradayPriceChangeMaxPct as proxy)
            if (vwapPct > f.intradayPriceChangeMaxPct) {
                log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                    ": Too far from VWAP " + String.format("%.2f%%", vwapPct) + " > " + f.intradayPriceChangeMaxPct + "%");
                return false;
            }
        }

        // Check RVOL
        if (c.result.rvol < f.rvolMin) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id + ": RVOL " + String.format("%.1f", c.result.rvol) + " < " + f.rvolMin);
            return false;
        }

        // Check RSI range
        if (c.result.rsi < f.rsiMin || c.result.rsi > f.rsiMax) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                ": RSI " + String.format("%.0f", c.result.rsi) + " not in [" + f.rsiMin + "-" + f.rsiMax + "]");
            return false;
        }

        // Check CCI range
        if (c.cci < f.cciMin || c.cci > f.cciMax) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                ": CCI " + String.format("%.0f", c.cci) + " not in [" + f.cciMin + "-" + f.cciMax + "]");
            return false;
        }

        // Check RS ratio
        if (c.rsRatio < f.rsMin) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                ": RS " + String.format("%.2f", c.rsRatio) + " < " + f.rsMin);
            return false;
        }

        // Check intraday price change range
        double priceChangePct = c.result.priceChangePct;
        if (priceChangePct < f.intradayPriceChangeMinPct || priceChangePct > f.intradayPriceChangeMaxPct) {
            log("[IntradayFilter] " + c.ticker + " rejected by " + variant.id +
                ": Price change " + String.format("%.1f%%", priceChangePct) +
                " not in [" + f.intradayPriceChangeMinPct + "-" + f.intradayPriceChangeMaxPct + "]");
            return false;
        }

        log("[IntradayFilter] ✅ " + c.ticker + " MATCHED " + variant.id +
            " (VWAP=$" + String.format("%.2f", c.result.vwap) + ", RVOL=" + String.format("%.1f", c.result.rvol) + ")");
        return true;
    }

    /**
     * Create an intraday VWAP trade with variant-specific risk management
     */
    private static SimulatedTrade createIntradayTrade(ScanCandidate c, IntradayVariantConfig variant) {
        SimulatedTrade trade = new SimulatedTrade(
            c.ticker, Strategy.INTRADAY, c.result.currentPrice,
            c.result.signal, c.result.rvol, c.result.rsi
        );

        // Add enhanced indicators
        trade.cci = c.cci;
        trade.cciBreakout = c.cciBreakout;
        trade.rsRatio = c.rsRatio;
        trade.maCrossover = c.maCrossover;
        trade.pivotPP = c.pivotPP;
        trade.pivotR1 = c.pivotR1;
        trade.pivotR2 = c.pivotR2;
        trade.pivotS1 = c.pivotS1;
        trade.momentumScore = c.momentumScore;

        // Assign variant ID
        trade.variantId = variant.id;
        trade.variantName = variant.name;

        // VWAP-based risk management
        double entryPrice = c.result.currentPrice;
        double vwap = c.result.vwap;
        IntradayRiskManagement rm = variant.riskManagement;

        // Stop loss: below VWAP or max stop, whichever is tighter
        double stopBelowVwap = vwap * (1 - rm.stopLossBelowVwapPct / 100.0);
        double stopMaxPct = entryPrice * (1 - rm.stopLossMaxPct / 100.0);
        trade.stopLossPrice = Math.max(stopBelowVwap, stopMaxPct);

        // Take profit based on variant config
        trade.takeProfitPrice = entryPrice * (1 + rm.takeProfitPct / 100.0);

        // Entry reason
        double vwapPct = (entryPrice - vwap) / vwap * 100;
        trade.entryReason = "VWAP Trend (" + String.format("+%.1f%%", vwapPct) + " above VWAP); " + c.entryReason;

        return trade;
    }

    /**
     * Monitor open positions and update prices every 15 minutes.
     * Check exit conditions and close positions as needed.
     */
    public static void monitorPositions(MonitoringAlphaVantageClient av) {
        List<SimulatedTrade> openTrades = getOpenTrades();
        if (openTrades.isEmpty()) {
            log("[Monitor] No open positions to monitor");
            return;
        }

        log("[Monitor] 📊 Checking " + openTrades.size() + " open positions...");

        for (SimulatedTrade trade : openTrades) {
            try {
                Thread.sleep(13000); // API rate limit

                JsonNode quote = av.globalQuote(trade.ticker);
                if (quote == null) continue;

                JsonNode gq = quote.path("Global Quote");
                if (gq == null || gq.isMissingNode()) {
                    gq = quote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                }
                
                String priceStr = gq.path("05. price").asText("");
                if (priceStr.isBlank()) continue;
                
                double newPrice = Double.parseDouble(priceStr);
                double oldPrice = trade.currentPrice;
                
                synchronized (lock) {
                    trade.updatePrice(newPrice);
                    
                    // Log price update
                    String priceChange = newPrice >= oldPrice ? "📈" : "📉";
                    log("[Monitor] " + trade.ticker + " " + priceChange + 
                        " $" + String.format("%.2f", oldPrice) + " → $" + String.format("%.2f", newPrice) +
                        " (P/L: " + String.format("%+.1f%%", trade.profitLossPct) + ")" +
                        " [" + (trade.variantId != null ? trade.variantId : trade.strategy) + "]");
                    
                    ExitType exitCondition = trade.checkExitCondition();
                    if (exitCondition != ExitType.HOLDING) {
                        trade.closePosition(exitCondition);
                        log("[Monitor] 🔔 CLOSED " + trade.ticker + 
                            " via " + exitCondition.getDisplay() + 
                            " @ $" + String.format("%.2f", newPrice) +
                            " P/L: " + String.format("%.1f%%", trade.profitLossPct));
                        notifyClosedPosition(trade);
                    }
                    
                    store.lastUpdateTime = ZonedDateTime.now(ZoneId.of("America/New_York"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    saveStore();
                }

            } catch (Exception e) {
                logErr("[Monitor] ❌ Error checking " + trade.ticker + ": " + e.getMessage());
            }
        }
        log("[Monitor] ✅ Check complete\n");
    }

    /**
     * Start the 15-minute monitoring scheduler
     */
    public static void startMonitoring(MonitoringAlphaVantageClient av) {
        if (scheduler != null && !scheduler.isShutdown()) {
            log("[DailyTradingSimulator] Monitoring already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Only monitor during market hours (9:30 AM - 4:00 PM ET)
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
                int hour = now.getHour();
                int minute = now.getMinute();
                int totalMinutes = hour * 60 + minute;
                
                // Market hours: 9:30 (570) to 16:00 (960)
                if (totalMinutes >= 570 && totalMinutes <= 960) {
                    monitorPositions(av);
                }
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Monitoring error: " + e.getMessage());
            }
        }, 0, 15, TimeUnit.MINUTES);

        log("[DailyTradingSimulator] Started 15-minute monitoring");
    }

    /**
     * Stop the monitoring scheduler
     */
    public static void stopMonitoring() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
            log("[DailyTradingSimulator] Stopped monitoring");
        }
    }

    /**
     * Close all open positions (end of day)
     */
    public static void closeAllPositions(MonitoringAlphaVantageClient av) {
        List<SimulatedTrade> openTrades = getOpenTrades();
        
        for (SimulatedTrade trade : openTrades) {
            try {
                // Get latest price
                JsonNode quote = av.globalQuote(trade.ticker);
                if (quote != null) {
                    JsonNode gq = quote.path("Global Quote");
                    if (gq == null || gq.isMissingNode()) {
                        gq = quote.path("Global Quote - DATA DELAYED BY 15 MINUTES");
                    }
                    String priceStr = gq.path("05. price").asText("");
                    if (!priceStr.isBlank()) {
                        trade.updatePrice(Double.parseDouble(priceStr));
                    }
                }
                
                synchronized (lock) {
                    trade.closePosition(ExitType.END_OF_DAY);
                    saveStore();
                }
                
                log("[DailyTradingSimulator] EOD CLOSE " + trade.ticker + 
                    " @ $" + String.format("%.2f", trade.exitPrice) +
                    " P/L: " + String.format("%.1f%%", trade.profitLossPct));

                notifyClosedPosition(trade);
                    
                Thread.sleep(13000); // API rate limit
                
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Error closing " + trade.ticker + ": " + e.getMessage());
            }
        }
    }

    /**
     * Get performance summary
     */
    public static Map<String, Object> getPerformanceSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        synchronized (lock) {
            List<SimulatedTrade> closed = getClosedTrades();
            
            int totalTrades = closed.size();
            int winners = 0;
            double totalPnL = 0;
            double totalPnLPct = 0;
            
            for (SimulatedTrade t : closed) {
                if (t.profitLossPct > 0) winners++;
                totalPnL += t.profitLossDollars;
                totalPnLPct += t.profitLossPct;
            }
            
            summary.put("totalTrades", totalTrades);
            summary.put("winners", winners);
            summary.put("losers", totalTrades - winners);
            summary.put("winRate", totalTrades > 0 ? (double) winners / totalTrades * 100 : 0);
            summary.put("totalPnL", totalPnL);
            summary.put("avgPnLPct", totalTrades > 0 ? totalPnLPct / totalTrades : 0);
            summary.put("openPositions", getOpenTrades().size());

            summary.put("marketCheckedAtNy", store.marketCheckedAtNy);
            summary.put("spyChangePct", store.spyChangePct != null ? store.spyChangePct : 0.0);
            summary.put("spyPrice", store.spyPrice);
            summary.put("spyPrevClose", store.spyPrevClose);
            summary.put("spySma20", store.spySma20);
            summary.put("spyAbovePrevClose", store.spyAbovePrevClose);
            summary.put("spyAboveSma20", store.spyAboveSma20);
            summary.put("marketWaitMode", store.marketWaitMode != null && store.marketWaitMode);
            summary.put("marketWaitReason", store.marketWaitReason);
        }
        
        return summary;
    }

    /**
     * Clear all trades (reset)
     */
    public static void clearAllTrades() {
        synchronized (lock) {
            store.trades.clear();
            store.lastScanDate = null;
            store.lastScanTime = null;
            store.scannerStatus = null;
            store.scannerRunning = false;  // Reset stuck scanner
            store.variantPerformance.clear();
            saveStore();
        }
        log("[DailyTradingSimulator] All trades cleared, scanner reset");
    }

    /**
     * Check if today is a trading day (Monday-Friday)
     */
    private static boolean isTradingDay(ZonedDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static boolean isUsMarketOpenToday(ZonedDateTime nowNy) {
        if (POLYGON_API_KEY == null || POLYGON_API_KEY.isBlank()) {
            return isTradingDay(nowNy);
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.polygon.io/v1/marketstatus/now?apiKey=" + POLYGON_API_KEY))
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return isTradingDay(nowNy);
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(resp.body());
            String market = root.path("market").asText("");
            if (!market.isBlank()) {
                return "open".equalsIgnoreCase(market);
            }
            String nasdaq = root.path("exchanges").path("nasdaq").asText("");
            if (!nasdaq.isBlank()) {
                return "open".equalsIgnoreCase(nasdaq);
            }
            return isTradingDay(nowNy);
        } catch (Exception ignore) {
            return isTradingDay(nowNy);
        }
    }

    /**
     * Calculate delay until next scheduled scan time (9:45 AM ET on weekdays)
     */
    private static long calculateDelayToNextScan() {
        ZonedDateTime now = ZonedDateTime.now(NY_ZONE);
        ZonedDateTime nextRun = now.withHour(SCAN_HOUR_ET).withMinute(SCAN_MINUTE_ET).withSecond(0).withNano(0);
        
        // If we've passed today's scan time, schedule for tomorrow
        if (now.isAfter(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }
        
        // Skip weekends
        while (!isTradingDay(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }
        
        return Duration.between(now, nextRun).toMillis();
    }

    /**
     * Start the daily auto-scan scheduler.
     * Runs scanner automatically at 9:45 AM ET every trading day (Mon-Fri).
     * Also starts the 15-minute position monitoring.
     */
    public static void startDailyScheduler(MonitoringAlphaVantageClient av, String interval) {
        if (dailyScheduler != null && !dailyScheduler.isShutdown()) {
            log("[DailyTradingSimulator] Daily scheduler already running");
            return;
        }

        dailyScheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Schedule daily scanner
        Runnable dailyScanTask = () -> {
            try {
                ZonedDateTime now = ZonedDateTime.now(NY_ZONE);
                if (!isUsMarketOpenToday(now)) {
                    log("[DailyTradingSimulator] Skipping - US market is closed");
                    return;
                }
                
                log("[DailyTradingSimulator] ⏰ Daily scan triggered at " + 
                    now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ET");
                
                runDailyScanner(av, interval);
                
                // Also start monitoring if not already running
                startMonitoring(av);
                
            } catch (Exception e) {
                logErr("[DailyTradingSimulator] Daily scan error: " + e.getMessage());
            }
        };

        // Calculate initial delay and schedule
        long initialDelay = calculateDelayToNextScan();
        long oneDayMs = TimeUnit.DAYS.toMillis(1);
        
        dailyScheduler.scheduleAtFixedRate(dailyScanTask, initialDelay, oneDayMs, TimeUnit.MILLISECONDS);
        
        ZonedDateTime nextRun = ZonedDateTime.now(NY_ZONE).plus(Duration.ofMillis(initialDelay));
        log("[DailyTradingSimulator] 📅 Daily scheduler started");
        log("[DailyTradingSimulator] 📅 Next scan scheduled at: " + 
            nextRun.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ET");
        
        synchronized (lock) {
            store.scannerStatus = "Daily scheduler active. Next scan: " + 
                nextRun.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")) + " ET";
            saveStore();
        }
    }

    /**
     * Stop the daily auto-scan scheduler
     */
    public static void stopDailyScheduler() {
        if (dailyScheduler != null) {
            dailyScheduler.shutdown();
            dailyScheduler = null;
            log("[DailyTradingSimulator] Daily scheduler stopped");
            
            synchronized (lock) {
                store.scannerStatus = "Daily scheduler stopped";
                saveStore();
            }
        }
    }

    /**
     * Check if daily scheduler is running
     */
    public static boolean isDailySchedulerRunning() {
        return dailyScheduler != null && !dailyScheduler.isShutdown();
    }

    /**
     * Get next scheduled scan time
     */
    public static String getNextScheduledScanTime() {
        if (!isDailySchedulerRunning()) {
            return "Not scheduled";
        }
        long delayMs = calculateDelayToNextScan();
        ZonedDateTime nextRun = ZonedDateTime.now(NY_ZONE).plus(Duration.ofMillis(delayMs));
        return nextRun.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ET";
    }

    private static void notifyOpenPosition(SimulatedTrade trade) {
        String msg = "🎯 Daily Trading Simulator - סימולציית מסחר יומית\n" +
            "📈 Open Positions\n" +
            "Opened: **" + trade.ticker + "** (" + (trade.variantId != null ? trade.variantId : trade.strategy.getDisplay()) + ")\n" +
            "Entry: $" + String.format("%.2f", trade.entryPrice) + " | Shares: " + String.format("%.2f", trade.shares) + "\n" +
            "Signal: " + (trade.scanSignal == null ? "" : trade.scanSignal) + "\n";
        sendDiscord(msg);
    }

    private static void notifyClosedPosition(SimulatedTrade trade) {
        if (trade == null || trade.isOpen) return;
        String msg = "🎯 Daily Trading Simulator - סימולציית מסחר יומית\n" +
            "📉 Closed Positions (Today)\n" +
            "Closed: **" + trade.ticker + "** (" + (trade.variantId != null ? trade.variantId : trade.strategy.getDisplay()) + ")\n" +
            "Exit: $" + String.format("%.2f", trade.exitPrice) + " via " + (trade.exitType == null ? "" : trade.exitType.getDisplay()) + "\n" +
            "P/L: " + String.format("%+.1f%%", trade.profitLossPct) + " (" + String.format("%+.2f", trade.profitLossDollars) + ")\n";
        sendDiscord(msg);
    }

    private static boolean sendDiscord(String text) {
        try {
            if (DISCORD_WEBHOOK_URL == null || DISCORD_WEBHOOK_URL.isBlank()) return false;
            if (text == null || text.isBlank()) return false;
            String jsonBody = "{\"content\": " + escapeJsonString(text) + "}";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_WEBHOOK_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            log("[DailyTradingSimulator][Discord] Sent notification, status: " + resp.statusCode());
            return resp.statusCode() == 200 || resp.statusCode() == 204;
        } catch (Exception e) {
            logErr("[DailyTradingSimulator][Discord] Failed to send: " + e.getMessage());
            return false;
        }
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "\"\"";
        String escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}
