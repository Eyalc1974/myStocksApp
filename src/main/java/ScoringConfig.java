import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScoringConfig {

    private static final Path CONFIG_PATH = Paths.get("scoring-config.json");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Object LOCK = new Object();

    private static volatile ConfigData cachedConfig = null;

    public static class ConfigData {
        public String activeMode = "LONG_TERM_INVESTOR";
        public String activeAgentConfig = null; // AITool agent ID to use for filtering
        public String monitoredAgentForDiscord = null; // Agent ID to monitor for Discord notifications
        public double investmentPerStock = 1000.0; // $ invested per stock in simulation
        public Map<String, ModeConfig> presets = new ConcurrentHashMap<>();
        public Map<String, SavedAgentTracker> savedAgentTrackers = new ConcurrentHashMap<>(); // Cumulative tracking
    }

    public static class SavedAgentTracker {
        public String agentId;
        public String agentName;
        public String type;
        public int cumulativeWins = 0;
        public int cumulativeTrades = 0;
        public double cumulativeProfitLoss = 0.0;
        public String firstSavedDate;
        public String lastUpdatedDate;
        public boolean notifyOnTrade = false; // Discord notification for every trade
        public java.util.List<DailySnapshot> dailySnapshots = new java.util.ArrayList<>();
    }

    public static class DailySnapshot {
        public String date;
        public int wins;
        public int trades;
        public double winRate;
        public double profitLoss;
    }

    public static class ModeConfig {
        public String name;
        public String nameHe;
        public String description;
        public int fundamentalWeight = 50;
        public int technicalWeight = 30;
        public int efficiencyWeight = 20;
        public int grahamBonus = 10;
        public boolean vetoEnabled = true;
        public int greenThreshold = 60; // Minimum score to be considered GREEN
        public int strongBuyThreshold = 80; // Score for STRONG BUY recommendation
        public int buyThreshold = 60; // Score for BUY recommendation
        public int holdThreshold = 40; // Score for HOLD recommendation
        public Map<String, IndicatorConfig> indicators = new ConcurrentHashMap<>();
        public EntryFiltersConfig entryFilters = new EntryFiltersConfig(); // פילטרים קשיחים לכניסה
    }

    public static class IndicatorConfig {
        public boolean enabled = true;
        public int points = 0;
        public double threshold = 0;
        public double thresholdHigh = 0;
        public double thresholdLow = 0;
        public int pointsHigh = 0;
        public int pointsLow = 0;
        public double min = 0;
        public double max = 100;
    }

    /**
     * Entry Filters - פילטרים קשיחים לכניסה לפוזיציה
     * מניה שלא עוברת את הפילטרים לא תיכנס לרשימת הקנייה
     */
    public static class EntryFiltersConfig {
        // ======== שער 1: בטיחות (Veto) ========
        public boolean vetoMScoreEnabled = true;
        public double vetoMScoreThreshold = -1.78; // M-Score > threshold = fraud risk
        public boolean vetoZScoreEnabled = true;
        public double vetoZScoreThreshold = 1.1; // Z-Score < threshold = bankruptcy risk
        
        // ======== שער 2: כוח יחסי (RS - The Leader) ========
        public boolean rsEntryFilterEnabled = true;
        public double rsEntryThreshold = 1.05; // RS > 1.05 = חזק מ-S&P 500 ב-5%
        public double rsExitThreshold = 0.95; // RS < 0.95 = חלש מהשוק - מכור
        
        // ======== שער 3: תזמון טכני ========
        // פילטר SMA - מחיר מעל ממוצע נע
        public boolean smaFilterEnabled = true;
        public int smaPeriod = 20; // SMA 20 לכניסה
        public boolean sma200FilterEnabled = true; // SMA 200 לזיהוי מגמה כללית
        
        // פילטר RSI - במגמת עלייה אך לא בקניית יתר
        public boolean rsiFilterEnabled = true;
        public double rsiMaxThreshold = 70.0; // RSI < 70 (לא קניית יתר)
        public double rsiMinThreshold = 30.0; // RSI > 30 (לא מכירת יתר)
        public boolean rsiRisingRequired = true; // RSI במגמת עלייה
        
        // ======== ניהול סיכונים ========
        // Position Sizing
        public double riskPercentPerTrade = 1.0; // סיכון מקסימלי 1% מההון לעסקה
        public double accountEquity = 36000.0; // הון התחלתי ברירת מחדל
        
        // סטופ-לוס מבוסס ATR
        public boolean atrStopLossEnabled = true;
        public double atrStopLossMultiplier = 2.5; // פי 2.5 מה-ATR
        public int atrPeriod = 14;
        
        // Trailing Stop
        public boolean trailingStopEnabled = true;
        
        // יעד רווח (R:R Ratio)
        public double riskRewardRatio = 2.0; // Target = Entry + (StopDistance × 2)
        
        // פילטר נפח מסחר
        public boolean volumeFilterEnabled = false;
        public double volumeMinRatioToAvg = 1.5;
        public int volumeAvgPeriod = 20;
    }

    public static ConfigData load() {
        synchronized (LOCK) {
            if (cachedConfig != null) return cachedConfig;
            return forceReload();
        }
    }

    public static ConfigData forceReload() {
        synchronized (LOCK) {
            try {
                if (Files.exists(CONFIG_PATH)) {
                    cachedConfig = JSON.readValue(CONFIG_PATH.toFile(), ConfigData.class);
                    System.out.println("[ScoringConfig] Loaded config: activeMode=" + cachedConfig.activeMode);
                    ModeConfig mc = cachedConfig.presets.get(cachedConfig.activeMode);
                    if (mc != null) {
                        System.out.println("[ScoringConfig] Mode thresholds: green=" + mc.greenThreshold + 
                            ", buy=" + mc.buyThreshold + ", strongBuy=" + mc.strongBuyThreshold);
                    }
                    return cachedConfig;
                }
            } catch (Exception e) {
                System.err.println("Error loading scoring config: " + e.getMessage());
            }
            cachedConfig = createDefault();
            return cachedConfig;
        }
    }

    public static void save(ConfigData config) {
        synchronized (LOCK) {
            try {
                JSON.writeValue(CONFIG_PATH.toFile(), config);
                cachedConfig = config;
            } catch (IOException e) {
                System.err.println("Error saving scoring config: " + e.getMessage());
            }
        }
    }

    public static void setActiveMode(String mode) {
        ConfigData config = load();
        if (config.presets.containsKey(mode)) {
            config.activeMode = mode;
            save(config);
        }
    }

    public static String getActiveMode() {
        return load().activeMode;
    }

    public static String getActiveAgentConfig() {
        return load().activeAgentConfig;
    }

    public static void setActiveAgentConfig(String agentId) {
        ConfigData config = load();
        config.activeAgentConfig = (agentId != null && !agentId.isBlank()) ? agentId.trim() : null;
        save(config);
        System.out.println("[ScoringConfig] Active agent config set to: " + config.activeAgentConfig);
    }

    public static Map<String, SavedAgentTracker> getSavedAgentTrackers() {
        ConfigData config = load();
        if (config.savedAgentTrackers == null) {
            config.savedAgentTrackers = new ConcurrentHashMap<>();
        }
        return config.savedAgentTrackers;
    }

    public static SavedAgentTracker getOrCreateTracker(String agentId, String agentName, String type) {
        ConfigData config = load();
        if (config.savedAgentTrackers == null) {
            config.savedAgentTrackers = new ConcurrentHashMap<>();
        }
        SavedAgentTracker tracker = config.savedAgentTrackers.get(agentId);
        if (tracker == null) {
            tracker = new SavedAgentTracker();
            tracker.agentId = agentId;
            tracker.agentName = agentName;
            tracker.type = type;
            tracker.firstSavedDate = java.time.LocalDate.now().toString();
            config.savedAgentTrackers.put(agentId, tracker);
            save(config);
        }
        return tracker;
    }

    public static void updateTrackerWithDailyStats(String agentId, int wins, int trades, double profitLoss) {
        ConfigData config = load();
        if (config.savedAgentTrackers == null) return;
        SavedAgentTracker tracker = config.savedAgentTrackers.get(agentId);
        if (tracker == null) return;
        
        String today = java.time.LocalDate.now().toString();
        
        // Check if we already have a snapshot for today
        DailySnapshot todaySnapshot = null;
        for (DailySnapshot snap : tracker.dailySnapshots) {
            if (today.equals(snap.date)) {
                todaySnapshot = snap;
                break;
            }
        }
        
        if (todaySnapshot == null) {
            // New day - add new snapshot
            todaySnapshot = new DailySnapshot();
            todaySnapshot.date = today;
            todaySnapshot.wins = wins;
            todaySnapshot.trades = trades;
            todaySnapshot.winRate = trades > 0 ? (wins * 100.0 / trades) : 0;
            todaySnapshot.profitLoss = profitLoss;
            tracker.dailySnapshots.add(todaySnapshot);
            
            // Update cumulative
            tracker.cumulativeWins += wins;
            tracker.cumulativeTrades += trades;
            tracker.cumulativeProfitLoss += profitLoss;
        } else {
            // Update existing snapshot - calculate delta
            int deltaWins = wins - todaySnapshot.wins;
            int deltaTrades = trades - todaySnapshot.trades;
            double deltaPL = profitLoss - todaySnapshot.profitLoss;
            
            todaySnapshot.wins = wins;
            todaySnapshot.trades = trades;
            todaySnapshot.winRate = trades > 0 ? (wins * 100.0 / trades) : 0;
            todaySnapshot.profitLoss = profitLoss;
            
            tracker.cumulativeWins += deltaWins;
            tracker.cumulativeTrades += deltaTrades;
            tracker.cumulativeProfitLoss += deltaPL;
        }
        
        tracker.lastUpdatedDate = today;
        save(config);
        System.out.println("[ScoringConfig] Updated tracker for " + agentId + ": " + tracker.cumulativeWins + "/" + tracker.cumulativeTrades);
    }

    public static void deleteTracker(String agentId) {
        ConfigData config = load();
        if (config.savedAgentTrackers != null) {
            config.savedAgentTrackers.remove(agentId);
            save(config);
            System.out.println("[ScoringConfig] Deleted tracker for: " + agentId);
        }
    }

    public static void toggleTradeNotification(String agentId) {
        ConfigData config = load();
        if (config.savedAgentTrackers != null) {
            SavedAgentTracker tracker = config.savedAgentTrackers.get(agentId);
            if (tracker != null) {
                tracker.notifyOnTrade = !tracker.notifyOnTrade;
                save(config);
                System.out.println("[ScoringConfig] Trade notification for " + agentId + " set to: " + tracker.notifyOnTrade);
            }
        }
    }

    public static boolean isTradeNotificationEnabled(String agentId) {
        ConfigData config = load();
        if (config.savedAgentTrackers != null) {
            SavedAgentTracker tracker = config.savedAgentTrackers.get(agentId);
            if (tracker != null) {
                return tracker.notifyOnTrade;
            }
        }
        return false;
    }

    // Investment Per Stock methods
    public static double getInvestmentPerStock() {
        ConfigData config = load();
        return config.investmentPerStock > 0 ? config.investmentPerStock : 1000.0;
    }

    public static void setInvestmentPerStock(double amount) {
        ConfigData config = load();
        config.investmentPerStock = amount;
        save(config);
        System.out.println("[ScoringConfig] Investment per stock set to: $" + amount);
    }

    // Discord Agent Monitor methods
    public static String getMonitoredAgentForDiscord() {
        ConfigData config = load();
        return config.monitoredAgentForDiscord;
    }

    public static void setMonitoredAgentForDiscord(String agentId) {
        ConfigData config = load();
        config.monitoredAgentForDiscord = agentId;
        save(config);
        System.out.println("[ScoringConfig] Monitored agent for Discord set to: " + (agentId != null ? agentId : "none"));
    }

    public static boolean isAgentMonitoredForDiscord(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        ConfigData config = load();
        return agentId.equals(config.monitoredAgentForDiscord);
    }

    public static ModeConfig getActiveModeConfig() {
        ConfigData config = load();
        ModeConfig mode = config.presets.get(config.activeMode);
        if (mode == null) {
            mode = config.presets.get("LONG_TERM_INVESTOR");
        }
        if (mode == null) {
            mode = createDefaultMode();
        }
        // Ensure entryFilters is not null
        if (mode.entryFilters == null) {
            mode.entryFilters = new EntryFiltersConfig();
        }
        return mode;
    }

    public static EntryFiltersConfig getActiveEntryFilters() {
        ModeConfig mode = getActiveModeConfig();
        return mode.entryFilters != null ? mode.entryFilters : new EntryFiltersConfig();
    }

    public static EntryFiltersConfig getEntryFiltersForMode(String modeName) {
        ConfigData config = load();
        String modeKey = null;
        if ("longterm".equalsIgnoreCase(modeName) || "long_term_investor".equalsIgnoreCase(modeName)) {
            modeKey = "LONG_TERM_INVESTOR";
        } else if ("swing".equalsIgnoreCase(modeName) || "swing_trader".equalsIgnoreCase(modeName)) {
            modeKey = "SWING_TRADER";
        } else if ("momentum".equalsIgnoreCase(modeName) || "momentum_hunter".equalsIgnoreCase(modeName)) {
            modeKey = "MOMENTUM_HUNTER";
        } else if (config.presets.containsKey(modeName)) {
            modeKey = modeName;
        }
        if (modeKey == null || !config.presets.containsKey(modeKey)) {
            return new EntryFiltersConfig();
        }
        ModeConfig mode = config.presets.get(modeKey);
        return mode.entryFilters != null ? mode.entryFilters : new EntryFiltersConfig();
    }

    public static void updateCustomMode(ModeConfig customConfig) {
        ConfigData config = load();
        config.presets.put("CUSTOM", customConfig);
        config.activeMode = "CUSTOM";
        save(config);
    }

    public static void invalidateCache() {
        synchronized (LOCK) {
            cachedConfig = null;
        }
    }

    private static ConfigData createDefault() {
        ConfigData config = new ConfigData();
        config.activeMode = "LONG_TERM_INVESTOR";
        
        // Long-Term Investor
        ModeConfig longTerm = createDefaultMode();
        longTerm.name = "Long-Term Investor";
        longTerm.nameHe = "משקיע לטווח ארוך";
        longTerm.description = "Focus on fundamentals, value investing, Graham-style";
        config.presets.put("LONG_TERM_INVESTOR", longTerm);

        // Swing Trader
        ModeConfig swing = new ModeConfig();
        swing.name = "Swing Trader";
        swing.nameHe = "סווינג טריידר (1-5 ימים)";
        swing.description = "Focus on technical signals, momentum, short-term trades";
        swing.fundamentalWeight = 15;
        swing.technicalWeight = 65;
        swing.efficiencyWeight = 10;
        swing.grahamBonus = 0;
        swing.vetoEnabled = false;
        swing.indicators = createSwingIndicators();
        swing.entryFilters = createSwingEntryFilters();
        config.presets.put("SWING_TRADER", swing);

        // Momentum
        ModeConfig momentum = new ModeConfig();
        momentum.name = "Momentum / Day Trader";
        momentum.nameHe = "מומנטום / יומי";
        momentum.description = "Pure technical, momentum-based, intraday to 1 day";
        momentum.fundamentalWeight = 5;
        momentum.technicalWeight = 85;
        momentum.efficiencyWeight = 5;
        momentum.grahamBonus = 0;
        momentum.vetoEnabled = false;
        momentum.indicators = createMomentumIndicators();
        momentum.entryFilters = createMomentumEntryFilters();
        config.presets.put("MOMENTUM", momentum);

        // Custom (copy of Long-Term by default)
        ModeConfig custom = createDefaultMode();
        custom.name = "Custom";
        custom.nameHe = "מותאם אישית";
        custom.description = "User-defined weights and thresholds";
        config.presets.put("CUSTOM", custom);

        return config;
    }

    private static ModeConfig createDefaultMode() {
        ModeConfig mode = new ModeConfig();
        mode.fundamentalWeight = 50;
        mode.technicalWeight = 30;
        mode.efficiencyWeight = 20;
        mode.grahamBonus = 10;
        mode.vetoEnabled = true;
        mode.indicators = createDefaultIndicators();
        mode.entryFilters = createLongTermEntryFilters();
        return mode;
    }

    private static EntryFiltersConfig createLongTermEntryFilters() {
        EntryFiltersConfig filters = new EntryFiltersConfig();
        // Long-term investors: conservative filters
        filters.vetoMScoreEnabled = true;
        filters.vetoZScoreEnabled = true;
        filters.rsEntryFilterEnabled = true;
        filters.rsEntryThreshold = 1.05;
        filters.rsExitThreshold = 0.95;
        filters.smaFilterEnabled = true;
        filters.smaPeriod = 50; // SMA 50 for long-term
        filters.sma200FilterEnabled = true;
        filters.rsiFilterEnabled = true;
        filters.rsiMaxThreshold = 70.0;
        filters.rsiMinThreshold = 30.0;
        filters.rsiRisingRequired = false; // Less strict for long-term
        filters.riskPercentPerTrade = 1.0;
        filters.atrStopLossEnabled = true;
        filters.atrStopLossMultiplier = 3.0; // Wider stop for long-term
        filters.trailingStopEnabled = true;
        filters.riskRewardRatio = 3.0; // Higher R:R for long-term
        filters.volumeFilterEnabled = false;
        return filters;
    }

    private static EntryFiltersConfig createSwingEntryFilters() {
        EntryFiltersConfig filters = new EntryFiltersConfig();
        // Swing traders: balanced filters
        filters.vetoMScoreEnabled = true;
        filters.vetoZScoreEnabled = true;
        filters.rsEntryFilterEnabled = true;
        filters.rsEntryThreshold = 1.05;
        filters.rsExitThreshold = 0.95;
        filters.smaFilterEnabled = true;
        filters.smaPeriod = 20; // SMA 20 for swing
        filters.sma200FilterEnabled = true;
        filters.rsiFilterEnabled = true;
        filters.rsiMaxThreshold = 70.0;
        filters.rsiMinThreshold = 30.0;
        filters.rsiRisingRequired = true;
        filters.riskPercentPerTrade = 1.0;
        filters.atrStopLossEnabled = true;
        filters.atrStopLossMultiplier = 2.5;
        filters.trailingStopEnabled = true;
        filters.riskRewardRatio = 2.0;
        filters.volumeFilterEnabled = true;
        filters.volumeMinRatioToAvg = 1.5;
        return filters;
    }

    private static EntryFiltersConfig createMomentumEntryFilters() {
        EntryFiltersConfig filters = new EntryFiltersConfig();
        // Momentum/Day traders: strict filters, tight stops
        filters.vetoMScoreEnabled = false; // Speed over safety
        filters.vetoZScoreEnabled = false;
        filters.rsEntryFilterEnabled = true;
        filters.rsEntryThreshold = 1.10; // Stronger momentum required
        filters.rsExitThreshold = 0.98; // Quick exit
        filters.smaFilterEnabled = true;
        filters.smaPeriod = 10; // SMA 10 for momentum
        filters.sma200FilterEnabled = false;
        filters.rsiFilterEnabled = true;
        filters.rsiMaxThreshold = 80.0; // Allow higher RSI for momentum
        filters.rsiMinThreshold = 40.0;
        filters.rsiRisingRequired = true;
        filters.riskPercentPerTrade = 0.5; // Lower risk for day trading
        filters.atrStopLossEnabled = true;
        filters.atrStopLossMultiplier = 1.5; // Tight stops
        filters.trailingStopEnabled = true;
        filters.riskRewardRatio = 1.5;
        filters.volumeFilterEnabled = true;
        filters.volumeMinRatioToAvg = 2.0; // High volume required
        return filters;
    }

    private static Map<String, IndicatorConfig> createDefaultIndicators() {
        Map<String, IndicatorConfig> indicators = new ConcurrentHashMap<>();

        IndicatorConfig fScore = new IndicatorConfig();
        fScore.enabled = true;
        fScore.points = 15;
        fScore.threshold = 7;
        indicators.put("fScore", fScore);

        IndicatorConfig peg = new IndicatorConfig();
        peg.enabled = true;
        peg.points = 15;
        peg.threshold = 1.2;
        indicators.put("peg", peg);

        IndicatorConfig dcf = new IndicatorConfig();
        dcf.enabled = true;
        dcf.points = 20;
        dcf.thresholdHigh = 0.20;
        dcf.thresholdLow = 0.0;
        indicators.put("dcfMargin", dcf);

        IndicatorConfig bullish = new IndicatorConfig();
        bullish.enabled = true;
        bullish.points = 15;
        indicators.put("technicalBullish", bullish);

        IndicatorConfig rsi = new IndicatorConfig();
        rsi.enabled = true;
        rsi.points = 15;
        rsi.min = 40;
        rsi.max = 65;
        indicators.put("rsiHealthy", rsi);

        IndicatorConfig roic = new IndicatorConfig();
        roic.enabled = true;
        roic.points = 10;
        roic.threshold = 0.05;
        indicators.put("roicWacc", roic);

        IndicatorConfig ccc = new IndicatorConfig();
        ccc.enabled = true;
        ccc.points = 10;
        ccc.threshold = 40;
        indicators.put("ccc", ccc);

        IndicatorConfig graham = new IndicatorConfig();
        graham.enabled = true;
        graham.pointsHigh = 10;
        graham.pointsLow = 5;
        graham.thresholdHigh = 0.33;
        graham.thresholdLow = 0.15;
        indicators.put("grahamMoS", graham);

        return indicators;
    }

    private static Map<String, IndicatorConfig> createSwingIndicators() {
        Map<String, IndicatorConfig> indicators = new ConcurrentHashMap<>();

        IndicatorConfig fScore = new IndicatorConfig();
        fScore.enabled = false;
        fScore.points = 5;
        fScore.threshold = 5;
        indicators.put("fScore", fScore);

        IndicatorConfig peg = new IndicatorConfig();
        peg.enabled = false;
        peg.points = 5;
        peg.threshold = 2.0;
        indicators.put("peg", peg);

        IndicatorConfig dcf = new IndicatorConfig();
        dcf.enabled = false;
        dcf.points = 5;
        dcf.thresholdHigh = 0.10;
        dcf.thresholdLow = 0.0;
        indicators.put("dcfMargin", dcf);

        IndicatorConfig bullish = new IndicatorConfig();
        bullish.enabled = true;
        bullish.points = 25;
        indicators.put("technicalBullish", bullish);

        IndicatorConfig rsi = new IndicatorConfig();
        rsi.enabled = true;
        rsi.points = 20;
        rsi.min = 30;
        rsi.max = 70;
        indicators.put("rsiHealthy", rsi);

        IndicatorConfig rsiOversold = new IndicatorConfig();
        rsiOversold.enabled = true;
        rsiOversold.points = 20;
        rsiOversold.threshold = 30;
        indicators.put("rsiOversold", rsiOversold);

        IndicatorConfig roic = new IndicatorConfig();
        roic.enabled = false;
        roic.points = 5;
        roic.threshold = 0.05;
        indicators.put("roicWacc", roic);

        IndicatorConfig ccc = new IndicatorConfig();
        ccc.enabled = false;
        ccc.points = 5;
        ccc.threshold = 60;
        indicators.put("ccc", ccc);

        IndicatorConfig graham = new IndicatorConfig();
        graham.enabled = false;
        graham.pointsHigh = 0;
        graham.pointsLow = 0;
        graham.thresholdHigh = 0.33;
        graham.thresholdLow = 0.15;
        indicators.put("grahamMoS", graham);

        return indicators;
    }

    private static Map<String, IndicatorConfig> createMomentumIndicators() {
        Map<String, IndicatorConfig> indicators = new ConcurrentHashMap<>();

        IndicatorConfig fScore = new IndicatorConfig();
        fScore.enabled = false;
        fScore.points = 0;
        indicators.put("fScore", fScore);

        IndicatorConfig peg = new IndicatorConfig();
        peg.enabled = false;
        peg.points = 0;
        indicators.put("peg", peg);

        IndicatorConfig dcf = new IndicatorConfig();
        dcf.enabled = false;
        dcf.points = 5;
        indicators.put("dcfMargin", dcf);

        IndicatorConfig bullish = new IndicatorConfig();
        bullish.enabled = true;
        bullish.points = 35;
        indicators.put("technicalBullish", bullish);

        IndicatorConfig rsi = new IndicatorConfig();
        rsi.enabled = true;
        rsi.points = 25;
        rsi.min = 50;
        rsi.max = 80;
        indicators.put("rsiHealthy", rsi);

        IndicatorConfig rsiOversold = new IndicatorConfig();
        rsiOversold.enabled = true;
        rsiOversold.points = 25;
        rsiOversold.threshold = 35;
        indicators.put("rsiOversold", rsiOversold);

        IndicatorConfig roic = new IndicatorConfig();
        roic.enabled = false;
        roic.points = 0;
        indicators.put("roicWacc", roic);

        IndicatorConfig ccc = new IndicatorConfig();
        ccc.enabled = false;
        ccc.points = 5;
        indicators.put("ccc", ccc);

        IndicatorConfig graham = new IndicatorConfig();
        graham.enabled = false;
        graham.pointsHigh = 0;
        graham.pointsLow = 0;
        indicators.put("grahamMoS", graham);

        return indicators;
    }
}
