import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class AIToolAgent {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path NEW_STRATEGIES_DIR = Paths.get("newStrategies");
    private static final Path HISTORY_FILE = Paths.get("newStrategies", "agent-history.json");
    private static final Path AGENT_STATE_FILE = Paths.get("newStrategies", "agent-state.json");
    private static final Path TRADE_LOG_FILE = Paths.get("newStrategies", "full-scan-trade-log.txt");
    private static final Path RECS_FILE       = Paths.get("newStrategies", "buy-recommendations.json");
    private static final Path DAILY_RECS_FILE = Paths.get("newStrategies", "daily-recommendations.txt");
    private static final Path SCAN_LOG_FILE  = Paths.get("newStrategies", "scan-detail.log");
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId ISRAEL = ZoneId.of("Asia/Jerusalem");
    private static final DateTimeFormatter LOG_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    
    // AlphaPoint AI recommendation: Filter to TOP 2-3 signals per run to reduce noise
    private static final int MAX_SIGNALS_PER_RUN = 3;
    
    // Discord webhook for notifications
    private static final String DISCORD_WEBHOOK_URL = System.getenv("DAILY_SIM_DISCORD_WEBHOOK_URL");
    
    // Win rate threshold for Discord notification (notify when agent reaches this)
    private static final double WIN_RATE_NOTIFICATION_THRESHOLD = 60.0;
    private static final int MIN_TRADES_FOR_NOTIFICATION = 5;

    private static final int SCORE_THRESHOLD = 10;
    private static final int CONFLUENCE_SCORE_THRESHOLD = 7;
    private static final int CONFLUENCE_BONUS = 2;
    private static final int MAX_TRADES_PER_SCAN       = 2;   // TOP 2 only - quality over quantity
    private static final int MAX_OPEN_POSITIONS_TOTAL  = 5;   // global cap: max open trades (reduced from 8 for focus)
    private static final int MAX_OPEN_PER_SECTOR        = 1;   // max simultaneous positions in the same sector (reduced from 2)
    private static final double MIN_STOP_LOSS_PCT       = 3.0; // minimum stop-loss distance (%)
    private static final double RISK_PER_TRADE = 1000.0; // $ position size per trade (changed from $500 to $1K)
    private static final double MIN_PROFIT_FOR_WIN = 20.0; // minimum profit ($) to be considered a WIN
    private static final double EARLY_EXIT_PROFIT_THRESHOLD = 30.0; // profit threshold to consider early exit

    enum RegimeLevel {
        HEALTHY,    // SPY above 20MA and change > -1%  → full position (100%)
        WEAK,       // Mixed signals                    → half position (50%)
        VERY_WEAK   // SPY below 20MA and change ≤ -2% → no trades
    }
    
    // Number of random stocks each agent analyzes per run
    // Increased to 12 for faster edge discovery (more candidates = more signals = faster data collection)
    // Note: Alpha Vantage free tier = 5 calls/minute; full scan = ~16 agents x 12 stocks = 192 calls (~40 min)
    private static final int STOCKS_PER_AGENT_RUN = 12;
    
    // Track which agents we've already notified about (to avoid spam)
    private static final Set<String> notifiedWinningAgents = ConcurrentHashMap.newKeySet();
    
    private static final Object LOCK = new Object();
    private static volatile AgentSystemState systemState = null;
    private static volatile boolean initialized = false;

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Normalize strategyType into broad setupType for recipe analysis. */
    private static String deriveSetupType(String strategyType) {
        if (strategyType == null) return "UNKNOWN";
        return switch (strategyType) {
            case "MOMENTUM_BREAKOUT", "VOLUME_BREAKOUT", "WEEK52_HIGH_MOMENTUM" -> "BREAKOUT";
            case "PULLBACK", "PULLBACK_MA20" -> "PULLBACK";
            case "TREND_CONTINUATION", "STRONG_TREND" -> "TREND";
            default -> "UNKNOWN";
        };
    }

    // Market regime state — updated by checkMarketRegime() on every scan and every 30-min standalone check
    static volatile RegimeLevel lastKnownRegime    = RegimeLevel.HEALTHY;
    static volatile String      lastRegimeDetail   = "";      // e.g. "SPY $540.00 | SMA20=$548.00 | Change=-2.3%"
    static volatile int         lastScanSignalCount = 0;      // signals found in the last scan (before regime filter)
    static volatile String      lastRegimeCheckTime = null;   // formatted HH:mm z of last check
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("aitool-agent-scheduler");
        return t;
    });
    
    private static final List<String> NASDAQ_100_TICKERS = Arrays.asList(
            "AAPL","ABNB","ALNY","AMAT","AMGN","ADBE","ADI","ADP","AMD","APP",
            "AMZN","AEP","ANET","APPS","ASML","BIDU","CDNS","COST","CRWD","DDOG",
            "EA","GILD","GOOG","GOOGL","HON","ILMN","INCY","INTC","INTU","JD",
            "KDP","KLAC","LRCX","MAR","META","MCHP","MELI","MNST","MRVL","MSFT",
            "NTES","NVDA","ODFL","ORLY","PAYX","PCAR","PDD","PEP","PYPL","QCOM",
            "REGN","ROST","SIRI","SNPS","SWKS","TCOM","TEAM","TMUS","TSLA","TXN",
            "VRSN","VRSK","WDAY","XEL","ZM","3M","A","AAL","AAPL","ABBV","ABC","ABMD","ABT","ACN","ADBE",
            "ADI","ADM","ADP","ADS","ADSK","AEE","AEP","AES","AET","AFG",
            "AFL","AGN","AIG","AIV","AKAM","ALL","ALLE","ALXN","AMAT","AME",
            "AMGN","AMP","AMT","AMZN","ANET","ANTM","AON","AOS","APA","APD",
            "APH","APTV","ARE","ATO","ATR","ATVI","AVB","AVGO","AVY","AWK",
            "AXP","AYI","AZO","BA","BAC","BAX","BBY","BDX","BEN","BHV","BIIB",
            "BK","BLK","BMY","BR","BRK.B","BSX","BWA","BX","C","CAG","CAH",
            "CAT","CB","CCI","CCL","CDNS","CF","CHD","CHRW","CI","CINF","CL",
            "CLX","CMA","CMCSA","CME","CMG","COF","COG","COO","COP","COST",
            "CPB","CRM","CSCO","CSX","CTAS","CTL","CTSH","CTVA","CVS","CVX",
            "CXO","D","DAL","DD","DE","DFS","DG","DGX","DHI","DHR","DIS","DISCA",
            "DISCK","DISH","DLR","DLTR","DOV","DRE","DRI","DTE","DUK","DVA",
            "DVN","DXC","EA","EBAY","ECL","ED","EFX","EIX","EL","EMN","ETN",
            "ETR","EVRG","EW","EXC","EXPD","EXPE","F","FAST","FCX","FDX","FE",
            "FITB","FLIR","FLS","FOXA","FOX","FRC","FTI","GD","GE","GILD","GIS",
            "GL","GLW","GM","GOOG","GOOGL","GPC","GPS","GRMN","GS","GT","GWW",
            "HAL","HAS","HBAN","HBI","HCA","HD","HES","HIG","HLT","HOG","HOLX",
            "HON","HPQ","HRL","HSIC","HST","HTZ","HUM","IBM","ICE","IFF","ILMN",
            "INCY","INTC","INTU","IP","IPG","IQV","IRM","ISRG","ITW","IVZ","J",
            "JBHT","JCI","JEF","JKHY","JNJ","JNPR","JPM","JWN","K","KEY","KHC",
            "KIM","KLAC","KMB","KMI","KMX","KO","KR","L","LB","LDOS","LEG","LEN",
            "LH","LMT","LNC","LNT","LOW","LRCX","LULU","LYB","M","MA","MAR","MAS",
            "MCD","MCHP","MCK","MCO","MDLZ","MDT","MET","MGM","MKC","MLM","MMC",
            "MMM","MNST","MO","MOS","MPC","MRK","MRO","MS","MSFT","MSI","MTB"
            );

    public static class AgentConfig {
        public String id;
        public String name;
        public String sourceFile;
        public String type; // MOMENTUM, SWING, INTRADAY, SUCCESS
        public Map<String, Object> entryFilters = new HashMap<>();
        public Map<String, Object> riskManagement = new HashMap<>();
        public Map<String, Object> scoring = new HashMap<>();
        public int generation = 0; // How many times this agent has evolved
        public String parentId; // Original agent ID if evolved
        public String lastModified;
        // Lock protection fields - prevents evolution/modification of winning agents
        public boolean locked = false;
        public String lockedAt; // ISO timestamp when locked
        public String codeVersion; // Git commit SHA when locked
        public String lockedByUser; // Who locked it
        public boolean masterStrategy = false;
        public String strategyType; // MOMENTUM_BREAKOUT, PULLBACK, TREND_CONTINUATION
        public String entryType = "AUTO"; // AUTO, EARLY_BREAKOUT, RETEST_BREAKOUT, TREND_CONTINUATION
        public boolean disabled = false; // true = skip this strategy (e.g. intraday strategies disabled for swing mode)
        public int maxOpenTrades = 5;  // max concurrent open positions for this agent (portfolio manager)
        public int maxPerSector  = 2;  // max concurrent positions in the same sector
        public int stocksPerVariant = 2;  // max stocks to select per variant
    }

    public static class Trade {
        public String id;
        public String agentId;
        public String ticker;
        public String action; // BUY, SELL
        public double entryPrice;
        public double exitPrice;
        public double stopLoss;      // Stop loss price
        public double takeProfit;    // Take profit / limit price
        public double quantity;
        public String entryTime;
        public String exitTime;
        public double profitLoss;
        public double profitLossPct;
        public String status; // OPEN, CLOSED_WIN, CLOSED_LOSS
        public String closeReason; // STOP_LOSS, TAKE_PROFIT, EOD_CLOSE, PARTIAL_1R
        public boolean partialExitDone = false; // true after 1R partial exit fires intraday
        public double partialExitPrice = 0;     // price at which partial exit was executed
        // Strategy attribution — stored for post-trade performance analysis
        public int entryScore;           // total score at entry (out of 12)
        public int entryVolumeScore;
        public int entryTrendScore;
        public int entryMomentumScore;
        public int entrySetupScore;
        public int entryConfluenceCount; // how many strategies agreed at entry
        public String strategyType;      // MOMENTUM_BREAKOUT | PULLBACK | TREND_CONTINUATION
        public String setupType;         // BREAKOUT | PULLBACK | TREND — normalized setup classification
        public String sector;            // sector label (TECHNOLOGY, FINANCIALS, etc.)
        // Expectancy metrics - calculated at trade close
        public double avgWin;             // average win percentage for this agent at time of trade close
        public double avgLoss;            // average loss percentage for this agent at time of trade close
        public double expectancy;         // (WinRate × AvgWin) - (LossRate × AvgLoss) at time of trade close
        // Full snapshot of market conditions at entry — used by SuccessRecipeEngine to find winning patterns
        public java.util.Map<String, Object> entrySnapshot = new java.util.HashMap<>();
    }

    /** A single BUY recommendation captured during a scan (mirrors the Discord message). */
    public static class ScanRecommendation {
        public String ticker;
        public String agentId;
        public double entryPrice;
        public double stopLoss;
        public double takeProfit;
        public int    score;        // 0 if not available (legacy agents)
        public String timestamp;    // human-readable NY time
        public int    runNumber;
        public String date;          // YYYY-MM-DD in NY timezone
        public long   signalTimeMs;  // epoch millis at signal creation (for expiry check)

        // ── Institutional Flow Layer scores ──
        public double finalConviction;   // 0-50 weighted composite
        public int    fundamentalScore;    // 0-10
        public int    catalystScore;       // 0-10
        public int    institutionalFlowScore; // 0-10
    }

    private static final List<ScanRecommendation> recentRecs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final int MAX_RECS = 100;
    
    // AlphaPoint AI recommendation: Track signals per run to filter to TOP 2-3
    private static volatile int signalsSentThisRun = 0;
    private static volatile int currentRunNumber = 0;
    private static volatile boolean isScheduledRun = false; // true = scheduled scan, false = manual scan

    public static List<ScanRecommendation> getRecentRecommendations() {
        String today = LocalDate.now(NY).toString();
        List<ScanRecommendation> todayRecs = new ArrayList<>();
        for (ScanRecommendation r : recentRecs) {
            if (today.equals(r.date)) todayRecs.add(r);
        }
        return todayRecs;
    }

    private static void addRecommendation(String ticker, String agentId,
                                          double entry, double sl, double tp, int score,
                                          double finalConviction, int fundamentalScore,
                                          int catalystScore, int institutionalFlowScore) {
        ScanRecommendation r = new ScanRecommendation();
        r.ticker     = ticker;
        r.agentId    = agentId;
        r.entryPrice = entry;
        r.stopLoss   = sl;
        r.takeProfit = tp;
        r.score      = score;
        r.timestamp  = ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("MM/dd HH:mm z"));
        r.runNumber  = systemState != null ? systemState.runCount : 0;
        r.date       = LocalDate.now(NY).toString();
        r.signalTimeMs = System.currentTimeMillis();
        r.finalConviction        = finalConviction;
        r.fundamentalScore       = fundamentalScore;
        r.catalystScore          = catalystScore;
        r.institutionalFlowScore = institutionalFlowScore;
        recentRecs.add(0, r); // newest first
        if (recentRecs.size() > MAX_RECS) recentRecs.remove(recentRecs.size() - 1);
        saveRecommendations();
    }

    private static void saveRecommendations() {
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            JSON.writeValue(RECS_FILE.toFile(), new ArrayList<>(recentRecs));
            saveDailyRecommendationsToTxt();
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error saving recommendations: " + e.getMessage());
        }
    }

    private static void saveDailyRecommendationsToTxt() {
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            
            String today = LocalDate.now(NY).toString();
            List<ScanRecommendation> todayRecs = getRecentRecommendations();
            
            if (todayRecs.isEmpty()) {
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("================================================================================\n");
            sb.append("DAILY STOCK RECOMMENDATIONS - ").append(today).append("\n");
            sb.append("Generated at: ").append(ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))).append("\n");
            sb.append("Position Size: $1,000 per trade\n");
            sb.append("Total Recommendations: ").append(todayRecs.size()).append("\n");
            sb.append("================================================================================\n\n");
            
            // Group by agent and sort by conviction (descending), then take top 3 per agent
            Map<String, List<ScanRecommendation>> byAgent = todayRecs.stream()
                .collect(Collectors.groupingBy(r -> r.agentId));
            
            for (Map.Entry<String, List<ScanRecommendation>> entry : byAgent.entrySet()) {
                String agentId = entry.getKey();
                List<ScanRecommendation> agentRecs = entry.getValue();
                
                // Sort by conviction (descending) and take top 3
                agentRecs.sort((a, b) -> Double.compare(b.finalConviction, a.finalConviction));
                List<ScanRecommendation> topRecs = agentRecs.stream().limit(3).collect(Collectors.toList());
                
                sb.append("AGENT: ").append(agentId).append("\n");
                sb.append("--------------------------------------------------------------------------------\n");
                
                for (ScanRecommendation rec : topRecs) {
                    // Calculate shares based on $1K position size
                    int shares = (int) (RISK_PER_TRADE / rec.entryPrice);
                    double positionValue = shares * rec.entryPrice;
                    
                    sb.append("  TICKER:         ").append(rec.ticker).append("\n");
                    sb.append("  Entry Price:    $").append(String.format("%.2f", rec.entryPrice)).append("\n");
                    sb.append("  Stop Loss:      $").append(String.format("%.2f", rec.stopLoss)).append("\n");
                    sb.append("  Take Profit:    $").append(String.format("%.2f", rec.takeProfit)).append("\n");
                    sb.append("  Quantity:       ").append(shares).append(" shares ($").append(String.format("%.0f", positionValue)).append(")\n");
                    sb.append("  Score:          ").append(rec.score).append("/100\n");
                    sb.append("  Conviction:     ").append(String.format("%.1f", rec.finalConviction)).append("/10\n");
                    sb.append("  Fundamental:    ").append(rec.fundamentalScore).append("/10\n");
                    sb.append("  Catalyst:       ").append(rec.catalystScore).append("/10\n");
                    sb.append("  Inst. Flow:     ").append(rec.institutionalFlowScore).append("/10\n");
                    sb.append("  Date:           ").append(rec.date).append("\n");
                    sb.append("\n");
                }
                
                sb.append("\n");
            }
            
            sb.append("================================================================================\n");
            sb.append("END OF DAILY RECOMMENDATIONS\n");
            sb.append("================================================================================\n");
            
            // Append to file instead of overwriting - allows tracking multiple runs per day
            Files.writeString(DAILY_RECS_FILE, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[AIToolAgent] Daily recommendations appended to: " + DAILY_RECS_FILE);
            
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error saving daily recommendations to txt: " + e.getMessage());
        }
    }

    private static void loadRecommendations() {
        try {
            if (!Files.exists(RECS_FILE)) return;
            List<ScanRecommendation> loaded = JSON.readValue(RECS_FILE.toFile(),
                JSON.getTypeFactory().constructCollectionType(List.class, ScanRecommendation.class));
            if (loaded != null) {
                recentRecs.clear();
                recentRecs.addAll(loaded);
            }
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error loading recommendations: " + e.getMessage());
        }
    }

    // Daily statistics record for historical tracking
    public static class DailyStats {
        public String date; // YYYY-MM-DD format
        public int trades = 0;
        public int wins = 0;
        public int losses = 0;
        public double profitLoss = 0.0;
        public double winRate = 0.0;
        public double avgWin = 0.0;
        public double avgLoss = 0.0;
        public double expectancy = 0.0; // (WinRate × AvgWin) - (LossRate × AvgLoss) - the REAL metric

        public DailyStats() {}
        
        public DailyStats(String date) {
            this.date = date;
        }
        
        public void updateWinRate() {
            if (trades > 0) {
                this.winRate = (wins * 100.0) / trades;
            }
        }

        public void updateExpectancy(List<Trade> dayTrades) {
            if (dayTrades.isEmpty()) return;

            double totalWin = 0.0;
            double totalLoss = 0.0;
            int winCount = 0;
            int lossCount = 0;

            for (Trade t : dayTrades) {
                if (t.profitLossPct > 0) {
                    totalWin += t.profitLossPct;
                    winCount++;
                } else if (t.profitLossPct < 0) {
                    totalLoss += Math.abs(t.profitLossPct);
                    lossCount++;
                }
            }

            this.avgWin = winCount > 0 ? totalWin / winCount : 0.0;
            this.avgLoss = lossCount > 0 ? totalLoss / lossCount : 0.0;

            double winRateDecimal = winCount / (double) dayTrades.size();
            double lossRateDecimal = lossCount / (double) dayTrades.size();

            // Expectancy formula: (WinRate × AvgWin) - (LossRate × AvgLoss)
            this.expectancy = (winRateDecimal * avgWin) - (lossRateDecimal * avgLoss);
        }
    }

    public static class AgentPerformance {
        public String agentId;
        public String agentName;
        public String type;
        public int totalTrades = 0;
        public int wins = 0;
        public int losses = 0;
        public double totalProfitLoss = 0.0;
        public double winRate = 0.0;
        public double avgWin = 0.0;
        public double avgLoss = 0.0;
        public double expectancy = 0.0; // (WinRate × AvgWin) - (LossRate × AvgLoss) - the REAL metric
        public double avgDailyReturn = 0.0;
        public double avgWeeklyReturn = 0.0;
        public double avgMonthlyReturn = 0.0;
        public List<Trade> recentTrades = new ArrayList<>();
        public boolean isWinning = false;
        public boolean configChanged = false;
        public String newConfigName;
        public int generation = 0;
        public String evolutionReason; // Why this agent evolved (if it did)
        
        // Daily history - tracks wins/losses per day
        public Map<String, DailyStats> dailyHistory = new LinkedHashMap<>(); // date -> stats
        
        // Professional trading metrics
        public double maxDrawdown = 0.0;        // Maximum peak-to-trough decline
        public double sharpeRatio = 0.0;      // Risk-adjusted return metric
        public double profitFactor = 0.0;       // Gross profit / Gross loss
        public double startingCapital = 10000.0; // Virtual starting capital
        public double currentCapital = 10000.0; // Current virtual capital after all trades
        public double peakCapital = 10000.0;    // Highest capital value reached (for drawdown calc)
        public double positionSize = 1000.0;    // Fixed position size per trade ($1,000 default)
    }

    public static class PendingSignal {
        public String id;             // short UUID for UI actions
        public String ticker;
        public String strategyId;
        public String strategyType;
        public int    score;          // total score at scan time
        public double scanPrice;      // last close price when scanned
        public double triggerPrice;   // price must break above this to enter
        public double triggerGapPct;  // (trigger - scanPrice) / scanPrice × 100
        public double suggestedStopLoss;
        public double suggestedTakeProfit;
        public String rejectReason;   // why trigger was not met
        public String scanTime;       // ISO timestamp of last scan
        public String status;         // WAITING | CONFIRMED | EXPIRED | DISMISSED
        public int    scanCount;      // number of scans seen without trigger firing
        public static final int MAX_WAIT_SCANS = 3; // expire after 3 scans if trigger never fires
    }

    public static class AgentSystemState {
        public Map<String, AgentConfig> agents = new ConcurrentHashMap<>();
        public Map<String, List<Trade>> tradeHistory = new ConcurrentHashMap<>();
        public Map<String, AgentPerformance> performance = new ConcurrentHashMap<>();
        public List<PendingSignal> pendingSignals = new CopyOnWriteArrayList<>(); // Watchlist: scored signals waiting for trigger
        public String lastRunTime;
        public boolean running = false;
        public String currentAgent;
        public int runCount = 0;
        public String lastSavedTime;
    }

    public static void initialize() {
        synchronized (LOCK) {
            if (initialized) {
                System.out.println("[AIToolAgent] Already initialized, skipping...");
                return;
            }
            initialized = true;
        }
        
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            loadState();
            if (systemState == null) {
                systemState = new AgentSystemState();
            }
            loadAllAgents();
            loadRecommendations();
            LongTermCandidateFinder.loadRSCache(); // warm up RS cache from last scan (eliminates cold-start penalty)
            saveState();
            
            System.out.println("[AIToolAgent] Initialized with " + systemState.agents.size() + " agents");
            
            // Schedule strategic scan runs (Israel time - AlphaPoint AI recommendation)
            // Run 1: 17:15-17:30 (after market open, most important - market calms down)
            // Run 2: 19:30-20:00 (mid-day - continuation setups, less noise)
            // Run 3: 21:30-22:00 (before close - best swing trades)
            scheduleStrategicScanRuns();

            // DISABLED: "Run All Agents Now" is now user-request only (manual trigger via WebServer)
            // The schedule has been moved to "Start Full Scan with Selected Agents"
            /*
            // Schedule periodic runs every 60 minutes during NASDAQ market hours (9:30 AM - 4:00 PM ET)
            // Note: Using 60-min interval due to 15-min delay in real-time price data
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isMarketHours()) {
                        System.out.println("[AIToolAgent] Scheduled 60-min periodic run starting...");
                        // 1. Scan for new trade signals
                        runAllAgents();
                        // 2. Monitor open positions after scan (stop loss / partial 1R exit)
                        monitorOpenPositions();
                    }
                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Scheduled run error: " + e.getMessage());
                }
            }, 60, 60, TimeUnit.MINUTES); // Start after 60 min, then every 60 minutes
            */
            
            // Schedule end-of-day cleanup at 4:30 PM ET (after market close)
            scheduleEndOfDayCleanup();
            
            // Schedule 48h log rotation for scan-detail.log
            rotateScanLogIfNeeded(); // check immediately on startup
            scheduleLogRotation();

            // Schedule 30-minute standalone SPY regime check (only during market hours, only when no scan is running)
            // Sends Discord notification if the regime level changes (e.g. HEALTHY → VERY_WEAK)
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!isMarketHours()) return;
                    if (systemState != null && (systemState.running || topAgentsFullScanRunning)) return;
                    System.out.println("[AIToolAgent] 30-min regime check: fetching SPY...");
                    RegimeLevel prevRegime = lastKnownRegime;
                    checkMarketRegime();
                    RegimeLevel newRegime = lastKnownRegime;
                    if (prevRegime != newRegime) {
                        String detail = lastRegimeDetail;
                        if (newRegime == RegimeLevel.VERY_WEAK) {
                            sendDiscord("⛔ **Market Regime Changed: VERY WEAK**\n" +
                                "SPY is below 20-day MA or down >2% today. **No new trades will be executed** to protect capital.\n" +
                                (detail.isEmpty() ? "" : detail));
                        } else if (newRegime == RegimeLevel.WEAK) {
                            sendDiscord("⚠️ **Market Regime Changed: WEAK**\n" +
                                "SPY shows mixed signals. Position sizes reduced to 50%.\n" +
                                (detail.isEmpty() ? "" : detail));
                        } else {
                            sendDiscord("✅ **Market Regime Recovered: HEALTHY**\n" +
                                "SPY is back above 20-day MA. Normal trading resumed.\n" +
                                (detail.isEmpty() ? "" : detail));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Standalone regime check error: " + e.getMessage());
                }
            }, 30, 30, TimeUnit.MINUTES);
            
            // AUTO-RUN ON STARTUP: If market is currently open, run immediately
            if (isMarketHours()) {
                System.out.println("[AIToolAgent] Market is OPEN - starting immediate run on startup!");
                sendDiscord("🤖 **AITool Agents Started**\nServer started during market hours. Running all " + systemState.agents.size() + " agents now...");
                scheduler.schedule(() -> {
                    try {
                        runAllAgents();
                        // Monitor open positions after startup scan
                        monitorOpenPositions();
                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Startup run error: " + e.getMessage());
                    }
                }, 10, TimeUnit.SECONDS); // Small delay to let server fully start
            } else {
                System.out.println("[AIToolAgent] Market is CLOSED - agents will run at next market open (9:30 AM ET)");
            }
            
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Init error: " + e.getMessage());
        }
    }

    /**
     * AlphaPoint AI Strategic Scan Scheduling
     * Schedules 3 optimal scan windows (Israel time) to avoid market open noise:
     * - Run 1: 17:15-17:30 (after market open - most important, market calms down)
     * - Run 2: 19:30-20:00 (mid-day - continuation setups, less noise)
     * - Run 3: 21:30-22:00 (before close - best swing trades)
     */
    private static void scheduleStrategicScanRuns() {
        scheduleScanRun(17, 15, "Post-Open (Primary)", "🟢 **Primary Scan - Post Market Open**\nMarket has calmed down. Direction starting to clear. Fewer fake moves.\nSelecting TOP 1-2 trades only.");
        scheduleScanRun(19, 30, "Mid-Day", "🟡 **Mid-Day Scan**\nContinuation & retest setups. Less noise than open.\nAdding if strong signals found.");
        scheduleScanRun(21, 30, "Pre-Close", "🔵 **Pre-Close Swing Scan**\nBest swing trades for next day entry.\nMore reliable with daily data. Swing only (not intraday).");
    }

    private static void scheduleScanRun(int hour, int minute, String runName, String discordMessage) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ZonedDateTime nowIsrael = ZonedDateTime.now(ISRAEL);
                ZonedDateTime scheduledTime = nowIsrael.withHour(hour).withMinute(minute).withSecond(0).withNano(0);

                // Only run if we're within the target window (within 15 minutes of scheduled time)
                long minutesFromScheduled = Duration.between(scheduledTime, nowIsrael).toMinutes();
                if (minutesFromScheduled < 0 || minutesFromScheduled > 15) {
                    return;
                }

                // Skip weekends
                DayOfWeek dow = nowIsrael.getDayOfWeek();
                if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                    return;
                }

                System.out.println("[AIToolAgent] " + runName + " Full Scan starting at " + nowIsrael.format(DateTimeFormatter.ofPattern("HH:mm")) + " Israel time");
                sendDiscord(discordMessage + "\n🚀 Running Full Scan with Selected Agents (75 tickers across 11 sectors)...");

                // Get filtered agents: success rate >50% + INST_SWING_V1
                List<String> filteredAgentIds = getFilteredAgentsForScheduledRun();
                if (filteredAgentIds.isEmpty()) {
                    sendDiscord("⚠️ No qualified agents found (success rate >50% or INST_SWING_V1). Skipping scan.");
                    return;
                }

                System.out.println("[AIToolAgent] Running " + filteredAgentIds.size() + " qualified agents: " + filteredAgentIds);
                sendDiscord("🤖 Qualified agents: " + String.join(", ", filteredAgentIds));

                // Run full scan with filtered agents (will pick top 2 stocks per agent)
                runFullScanWithAgentsAsync(filteredAgentIds);
                monitorOpenPositions();

            } catch (Exception e) {
                System.err.println("[AIToolAgent] " + runName + " scan error: " + e.getMessage());
            }
        }, computeInitialDelay(hour, minute), 24 * 60, TimeUnit.MINUTES);
    }

    /**
     * Get filtered agents for scheduled runs:
     * Currently only includes MASTER_7_VIX_MARKET_FILTER as the default selection.
     */
    public static List<String> getFilteredAgentsForScheduledRun() {
        List<String> filtered = new ArrayList<>();

        synchronized (LOCK) {
            // Only include MASTER_7_VIX_MARKET_FILTER by default
            AgentConfig cfg = systemState != null && systemState.agents != null ? systemState.agents.get("MASTER_7_VIX_MARKET_FILTER") : null;
            if (cfg != null && !cfg.disabled) {
                filtered.add("MASTER_7_VIX_MARKET_FILTER");
            }
        }

        return filtered;
    }

    private static long computeInitialDelay(int targetHour, int targetMinute) {
        ZonedDateTime nowIsrael = ZonedDateTime.now(ISRAEL);
        ZonedDateTime target = nowIsrael.withHour(targetHour).withMinute(targetMinute).withSecond(0).withNano(0);
        
        if (nowIsrael.isAfter(target)) {
            target = target.plusDays(1);
        }
        
        return Duration.between(nowIsrael, target).toMinutes();
    }

    private static void scheduleEndOfDayCleanup() {
        // Calculate delay until 4:30 PM ET (30 minutes after market close)
        ZonedDateTime now = ZonedDateTime.now(NY);
        ZonedDateTime nextCleanup = now.withHour(16).withMinute(30).withSecond(0).withNano(0);
        
        // If we're past 4:30 PM today, schedule for tomorrow
        if (now.isAfter(nextCleanup)) {
            nextCleanup = nextCleanup.plusDays(1);
        }
        
        // Skip weekends
        while (nextCleanup.getDayOfWeek().getValue() > 5) {
            nextCleanup = nextCleanup.plusDays(1);
        }
        
        long delayMinutes = Duration.between(now, nextCleanup).toMinutes();
        
        System.out.println("[AIToolAgent] End-of-day cleanup scheduled in " + delayMinutes + " minutes (" + nextCleanup.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " ET)");
        
        scheduler.schedule(() -> {
            try {
                System.out.println("[AIToolAgent] END OF DAY - Closing open positions and running cleanup...");
                
                // First, close all open positions
                closeOpenPositions();
                
                // Then delete underperforming agents
                deleteUnderperformingAgents();
                
                // Reschedule for next day
                scheduleEndOfDayCleanup();
            } catch (Exception e) {
                System.err.println("[AIToolAgent] End-of-day cleanup error: " + e.getMessage());
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }
    
    /**
     * Immediately closes all expired OPEN trades for the given agent (or all agents if agentId is empty/null).
     * Fetches the latest daily close price from Alpha Vantage for each expired ticker.
     * Called from the "Force Close Expired Now" button on the agent-detail page.
     */
    public static void closeExpiredPositionsNow(String filterAgentId) {
        writeScanLog("[FORCE EXPIRE] Manual force-close triggered"
            + (filterAgentId != null && !filterAgentId.isBlank() ? " for agent " + filterAgentId : " for ALL agents"));
        List<Map.Entry<String, Trade>> toUpdate = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, List<Trade>> entry : systemState.tradeHistory.entrySet()) {
                String agentId = entry.getKey();
                if (filterAgentId != null && !filterAgentId.isBlank() && !filterAgentId.equals(agentId)) continue;
                AgentConfig agentCfg = systemState.agents.get(agentId);
                int maxHold = agentCfg != null ? (int) getDoubleRisk(agentCfg, "maxHoldDays", 7.0) : 7;
                for (Trade trade : entry.getValue()) {
                    if (!"OPEN".equals(trade.status)) continue;
                    int holdDays = calcHoldDays(trade.entryTime);
                    if (holdDays < maxHold) continue;
                    // Fetch live close price
                    double exitPrice = trade.entryPrice; // fallback: neutral
                    try {
                        String json = DataFetcher.fetchStockDataForTicker(trade.ticker);
                        if (json != null) {
                            List<Double> closes = PriceJsonParser.extractClosingPrices(json);
                            if (closes != null && !closes.isEmpty()) exitPrice = closes.get(closes.size() - 1);
                        }
                    } catch (Exception ignored) {}
                    trade.exitPrice     = exitPrice;
                    trade.profitLoss    = (exitPrice - trade.entryPrice) * trade.quantity;
                    trade.profitLossPct = trade.entryPrice > 0
                        ? ((exitPrice - trade.entryPrice) / trade.entryPrice) * 100 : 0;
                    trade.status        = trade.profitLoss >= 0 ? "CLOSED_WIN" : "CLOSED_LOSS";
                    trade.closeReason   = "MAX_HOLD_DAYS";
                    trade.exitTime      = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    writeScanLog(String.format(
                        "[FORCE EXPIRE \uD83D\uDCC5] %s | %s | day %d/%d | exit $%.2f | P/L $%+.2f | %s",
                        trade.ticker, agentId, holdDays, maxHold,
                        exitPrice, trade.profitLoss, trade.status));
                    toUpdate.add(new java.util.AbstractMap.SimpleEntry<>(agentId, trade));
                }
            }
        }
        for (Map.Entry<String, Trade> e : toUpdate) {
            updatePerformance(e.getKey(), e.getValue());
        }
        if (!toUpdate.isEmpty()) {
            writeScanLog("[FORCE EXPIRE] Closed " + toUpdate.size() + " position(s)");
            saveState();
            saveHistory();
        } else {
            writeScanLog("[FORCE EXPIRE] No expired positions found");
        }
    }

    /**
     * Called at the start of every scan (after pre-fetch).
     * Closes any OPEN trade whose hold duration >= maxHoldDays using the latest close price
     * from already-downloaded prefetchedJson — no additional API calls needed.
     */
    private static void closeExpiredPositions(java.util.concurrent.ConcurrentHashMap<String, String> prefetchedJson) {
        // Pass 1: close expired trades and collect (agentId, trade) pairs for performance update
        List<Map.Entry<String, Trade>> toUpdate = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, List<Trade>> entry : systemState.tradeHistory.entrySet()) {
                String agentId = entry.getKey();
                AgentConfig agentCfg = systemState.agents.get(agentId);
                int maxHold = agentCfg != null ? (int) getDoubleRisk(agentCfg, "maxHoldDays", 7.0) : 7;
                for (Trade trade : entry.getValue()) {
                    if (!"OPEN".equals(trade.status)) continue;
                    int holdDays = calcHoldDays(trade.entryTime);
                    if (holdDays < maxHold) continue;
                    // Use latest close price from pre-fetched data; fall back to entry price (neutral)
                    double exitPrice = trade.entryPrice;
                    String json = prefetchedJson.get(trade.ticker);
                    if (json != null) {
                        try {
                            List<Double> closes = PriceJsonParser.extractClosingPrices(json);
                            if (closes != null && !closes.isEmpty()) exitPrice = closes.get(closes.size() - 1);
                        } catch (Exception ignored) {}
                    }
                    trade.exitPrice     = exitPrice;
                    trade.profitLoss    = (exitPrice - trade.entryPrice) * trade.quantity;
                    trade.profitLossPct = trade.entryPrice > 0
                        ? ((exitPrice - trade.entryPrice) / trade.entryPrice) * 100 : 0;
                    trade.status        = trade.profitLoss >= 0 ? "CLOSED_WIN" : "CLOSED_LOSS";
                    trade.closeReason   = "MAX_HOLD_DAYS";
                    trade.exitTime      = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    writeScanLog(String.format(
                        "[EXPIRE \uD83D\uDCC5] %s | %s | day %d/%d | exit $%.2f | P/L $%+.2f | %s",
                        trade.ticker, agentId, holdDays, maxHold,
                        exitPrice, trade.profitLoss, trade.status));
                    toUpdate.add(new java.util.AbstractMap.SimpleEntry<>(agentId, trade));
                }
            }
        }
        // Pass 2: update performance counters (outside LOCK to avoid re-entering on deadlock risk)
        if (!toUpdate.isEmpty()) {
            for (Map.Entry<String, Trade> e : toUpdate) {
                updatePerformance(e.getKey(), e.getValue());
            }
            writeScanLog("[EXPIRE] Closed " + toUpdate.size() + " expired position(s) before scan");
            saveState();
            saveHistory();
        }
    }

    /**
     * Close all OPEN positions at end of day.
     * Fetches current price and determines win/loss based on stop loss and take profit.
     */
    public static void closeOpenPositions() {
        System.out.println("[AIToolAgent] Closing all OPEN positions at end of day...");
        writeScanLog("════════════════════════════════════════");
        writeScanLog("[EOD CLOSE START] Closing all OPEN positions at 4:30 PM ET");
        
        int closedCount = 0;
        int winCount = 0;
        int lossCount = 0;
        double totalPL = 0;
        
        synchronized (LOCK) {
            for (Map.Entry<String, List<Trade>> entry : systemState.tradeHistory.entrySet()) {
                String agentId = entry.getKey();
                List<Trade> trades = entry.getValue();
                
                for (Trade trade : trades) {
                    if (!"OPEN".equals(trade.status)) {
                        continue; // Skip already closed trades
                    }
                    
                    try {
                        // Fetch current price for this ticker
                        DataFetcher.setTicker(trade.ticker);
                        String json = DataFetcher.fetchStockData();
                        List<Double> prices = PriceJsonParser.extractClosingPrices(json);
                        
                        if (prices == null || prices.isEmpty()) {
                            System.err.println("[AIToolAgent] Could not fetch price for " + trade.ticker + ", skipping close");
                            writeScanLog("[EOD FETCH FAIL] " + trade.ticker + " | " + agentId + " — no price data");
                            continue;
                        }
                        
                        double currentPrice = prices.get(prices.size() - 1);
                        
                        // Also fetch today's high for partial exit simulation
                        List<Double> highPricesEOD = PriceJsonParser.extractHighPrices(json);
                        double todayHigh = (highPricesEOD != null && !highPricesEOD.isEmpty())
                            ? highPricesEOD.get(highPricesEOD.size() - 1) : currentPrice;
                        
                        // Set exit price and time
                        trade.exitPrice = currentPrice;
                        trade.exitTime = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        
                        // === PARTIAL EXIT AT 1R (if agent has partialExitAt1R: true) ===
                        // Sell 50% at 1R profit, move stop to break-even, trail the rest
                        boolean partialExit1RUsed = false;
                        AgentConfig agentCfg = systemState.agents.get(agentId);
                        if (agentCfg != null && getBooleanRisk(agentCfg, "partialExitAt1R", false)
                                && currentPrice > trade.stopLoss) {
                            double stopDistance = trade.entryPrice - trade.stopLoss;
                            double oneRTarget = trade.entryPrice + stopDistance;
                            if (stopDistance > 0 && todayHigh >= oneRTarget) {
                                double partialPct = getDoubleRisk(agentCfg, "partialExitPct", 50.0) / 100.0;
                                boolean breakEven = getBooleanRisk(agentCfg, "breakEvenAfterPartial", true);
                                // First leg: 50% exits at 1R price
                                double partialQty = trade.quantity * partialPct;
                                double partialProfit = (oneRTarget - trade.entryPrice) * partialQty;
                                // Second leg: remaining 50% exits at EOD price, with break-even floor
                                double remainingQty = trade.quantity * (1 - partialPct);
                                double remainingExitPrice = currentPrice;
                                if (breakEven && currentPrice < trade.entryPrice) {
                                    remainingExitPrice = trade.entryPrice; // stop moved to break-even
                                }
                                double remainingProfit = (remainingExitPrice - trade.entryPrice) * remainingQty;
                                trade.profitLoss = partialProfit + remainingProfit;
                                trade.profitLossPct = (trade.profitLoss / (trade.entryPrice * trade.quantity)) * 100;
                                trade.status = trade.profitLoss >= 0 ? "CLOSED_WIN" : "CLOSED_LOSS";
                                trade.closeReason = "PARTIAL_1R";
                                partialExit1RUsed = true;
                                System.out.println("[AIToolAgent] PARTIAL_1R: " + trade.ticker +
                                    " 1R=$" + String.format("%.2f", oneRTarget) +
                                    " EOD=$" + String.format("%.2f", currentPrice) +
                                    " P/L=$" + String.format("%.2f", trade.profitLoss));
                                writeScanLog("[EOD 💰 PARTIAL_1R] " + trade.ticker + " | " + agentId +
                                    " | 1R=$" + String.format("%.2f", oneRTarget) +
                                    " EOD=$" + String.format("%.2f", currentPrice) +
                                    " | P/L=$" + String.format("%+.2f", trade.profitLoss));
                            }
                        }
                        
                        if (!partialExit1RUsed) {
                            AgentConfig agentCfgEod = systemState.agents.get(agentId);
                            int maxHold  = agentCfgEod != null ? (int) getDoubleRisk(agentCfgEod, "maxHoldDays", 7.0) : 7;
                            int holdDays = calcHoldDays(trade.entryTime);

                            if (currentPrice <= trade.stopLoss) {
                                trade.status    = "CLOSED_LOSS";
                                trade.exitPrice = trade.stopLoss;
                                trade.closeReason = "STOP_LOSS";
                            } else if (todayHigh >= trade.takeProfit) {
                                trade.status    = "CLOSED_WIN";
                                trade.exitPrice = trade.takeProfit;
                                trade.closeReason = "TAKE_PROFIT";
                            } else if (holdDays >= maxHold) {
                                double priceChange = currentPrice - trade.entryPrice;
                                trade.status    = priceChange >= 0 ? "CLOSED_WIN" : "CLOSED_LOSS";
                                trade.exitPrice = currentPrice;
                                trade.closeReason = "MAX_HOLD_DAYS";
                            } else {
                                // Check for early exit opportunity if profit >= $30
                                double currentProfit = (currentPrice - trade.entryPrice) * trade.quantity;
                                if (currentProfit >= EARLY_EXIT_PROFIT_THRESHOLD) {
                                    // Analyze whether to sell or hold
                                    double profitPct = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100;
                                    double distanceToTP = ((trade.takeProfit - currentPrice) / currentPrice) * 100;

                                    // Sell early if:
                                    // 1. Profit is good (>= 3%) AND
                                    // 2. Either close to take profit (< 2% away) OR showing signs of weakness (held 3+ days)
                                    boolean shouldSellEarly = (profitPct >= 3.0) &&
                                        (distanceToTP < 2.0 || holdDays >= 3);

                                    if (shouldSellEarly) {
                                        trade.status    = "CLOSED_WIN";
                                        trade.exitPrice = currentPrice;
                                        trade.closeReason = "EARLY_EXIT";
                                        writeScanLog(String.format(
                                            "[EOD EARLY EXIT 💰] %s | %s | day %d/%d | P/L=$%+.2f (%.2f%%) | dist to TP=%.2f%%",
                                            trade.ticker, agentId, holdDays, maxHold,
                                            currentProfit, profitPct, distanceToTP));
                                    } else {
                                        // Hold the runner
                                        writeScanLog(String.format(
                                            "[EOD HOLD RUNNER 🚀] %s | %s | day %d/%d | P/L=$%+.2f (%.2f%%) | SL=$%.2f | TP=$%.2f",
                                            trade.ticker, agentId, holdDays, maxHold,
                                            currentProfit, profitPct, trade.stopLoss, trade.takeProfit));
                                        Thread.sleep(12500);
                                        continue;
                                    }
                                } else {
                                    // Within hold window — carry trade over to the next trading day
                                    writeScanLog(String.format(
                                        "[EOD CARRY ⏳] %s | %s | day %d/%d | $%.2f | SL=$%.2f | TP=$%.2f",
                                        trade.ticker, agentId, holdDays, maxHold,
                                        currentPrice, trade.stopLoss, trade.takeProfit));
                                    Thread.sleep(12500);
                                    continue;
                                }
                            }
                            // Calculate P/L for closed trade
                            double priceChange = trade.exitPrice - trade.entryPrice;
                            trade.profitLoss    = priceChange * trade.quantity;
                            trade.profitLossPct = (priceChange / trade.entryPrice) * 100;
                        }
                        
                        // Log the trade close
                        logTradeClosed(trade);
                        
                        // Update performance stats now that trade is closed
                        updatePerformance(agentId, trade);
                        
                        closedCount++;
                        totalPL += trade.profitLoss;
                        if (trade.status.equals("CLOSED_WIN")) {
                            winCount++;
                        } else {
                            lossCount++;
                        }
                        
                        String eodIcon = trade.status.equals("CLOSED_WIN") ? "✅" : "❌";
                        writeScanLog("[EOD " + eodIcon + " " + trade.closeReason + "] " + trade.ticker +
                            " | " + agentId +
                            " | entry=$" + String.format("%.2f", trade.entryPrice) +
                            " exit=$" + String.format("%.2f", trade.exitPrice) +
                            " | P/L=$" + String.format("%+.2f", trade.profitLoss) +
                            " (" + String.format("%+.2f", trade.profitLossPct) + "%%)");
                        System.out.println("[AIToolAgent] CLOSED: " + trade.ticker + " @ $" + 
                            String.format("%.2f", trade.exitPrice) + " P/L: $" + 
                            String.format("%.2f", trade.profitLoss) + " (" + trade.status + ")");
                        
                        // Rate limit for API calls
                        Thread.sleep(12500);
                        
                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Error closing position for " + trade.ticker + ": " + e.getMessage());
                        writeScanLog("[EOD ERROR] " + trade.ticker + " | " + agentId + " — " + e.getMessage());
                    }
                }
            }
            
            // Save state after closing all positions
            saveState();
        }
        
        // Log summary
        writeScanLog(String.format("[EOD SUMMARY] Closed: %d | Wins: %d | Losses: %d | Total P/L: $%+.2f",
            closedCount, winCount, lossCount, totalPL));
        writeScanLog("════════════════════════════════════════");
        if (closedCount > 0) {
            String summary = String.format("EOD_SUMMARY: Closed %d positions, %d wins, %d losses, Total P/L: $%.2f",
                closedCount, winCount, lossCount, totalPL);
            logTradeEvent("EOD_CLOSE", "SYSTEM", "---", summary);
            
            // Send Discord notification
            String discordMsg = String.format(
                "🌙 **End of Day Position Close**\n" +
                "Closed: **%d** positions\n" +
                "✅ Wins: **%d** | ❌ Losses: **%d**\n" +
                "💰 Total P/L: **$%.2f**",
                closedCount, winCount, lossCount, totalPL
            );
            sendDiscord(discordMsg);
        }
        
        System.out.println("[AIToolAgent] End of day close complete: " + closedCount + " positions closed");
    }
    
    /**
     * Hourly monitor of all OPEN positions during market hours.
     * Uses GLOBAL_QUOTE (live price) to check stop loss and 1R partial exit in real-time.
     * Sends Discord SELL notifications when positions are closed or partially exited.
     */
    public static void monitorOpenPositions() {
        if (!isMarketHours()) return;

        // Collect open trades snapshot (outside lock to avoid holding lock during API calls)
        List<Trade> openTrades = new ArrayList<>();
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return;
            for (List<Trade> trades : systemState.tradeHistory.values()) {
                for (Trade t : trades) {
                    if ("OPEN".equals(t.status)) openTrades.add(t);
                }
            }
        }

        if (openTrades.isEmpty()) return;
        System.out.println("[AIToolAgent] Monitoring " + openTrades.size() + " open positions...");
        writeScanLog("────────────────────────────────────────");
        writeScanLog("[MONITOR START] Checking " + openTrades.size() + " open position(s) live");

        for (Trade trade : openTrades) {
            try {
                // Fetch live quote for current price + today's high
                String quoteJson = DataFetcher.fetchGlobalQuote(trade.ticker);
                if (quoteJson == null || quoteJson.isBlank()) {
                    Thread.sleep(12500);
                    continue;
                }

                double currentPrice = parseGlobalQuoteField(quoteJson, "05. price");
                double todayHigh    = parseGlobalQuoteField(quoteJson, "03. high");
                if (currentPrice <= 0) {
                    Thread.sleep(12500);
                    continue;
                }

                AgentConfig agentCfg = systemState.agents.get(trade.agentId);
                boolean partialAt1R  = agentCfg != null && getBooleanRisk(agentCfg, "partialExitAt1R", false);

                // === STOP LOSS HIT ===
                if (currentPrice <= trade.stopLoss) {
                    synchronized (LOCK) {
                        if (!"OPEN".equals(trade.status)) continue; // already closed by concurrent thread
                        trade.exitPrice = trade.stopLoss;
                        trade.exitTime  = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        trade.status    = "CLOSED_LOSS";
                        trade.closeReason = "STOP_LOSS";
                        double priceChange = trade.exitPrice - trade.entryPrice;
                        trade.profitLoss    = priceChange * trade.quantity;
                        trade.profitLossPct = (priceChange / trade.entryPrice) * 100;
                    }
                    logTradeClosed(trade);
                    updatePerformance(trade.agentId, trade);
                    sendSellDiscord(trade, "🛑 STOP LOSS HIT");
                    writeScanLog("[MONITOR 🛑 STOP] " + trade.ticker + " | " + trade.agentId +
                        " | live=$" + String.format("%.2f", currentPrice) +
                        " <= SL=$" + String.format("%.2f", trade.stopLoss) +
                        " | P/L=$" + String.format("%+.2f", trade.profitLoss));
                    System.out.println("[AIToolAgent] MONITOR STOP: " + trade.ticker + " @ $" + String.format("%.2f", trade.exitPrice));

                // === TAKE PROFIT HIT (intraday) ===
                } else if (todayHigh >= trade.takeProfit) {
                    synchronized (LOCK) {
                        if (!"OPEN".equals(trade.status)) continue;
                        trade.exitPrice = trade.takeProfit;
                        trade.exitTime  = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        trade.status    = "CLOSED_WIN";
                        trade.closeReason = "TAKE_PROFIT";
                        double priceChange = trade.exitPrice - trade.entryPrice;
                        trade.profitLoss    = priceChange * trade.quantity;
                        trade.profitLossPct = (priceChange / trade.entryPrice) * 100;
                    }
                    logTradeClosed(trade);
                    updatePerformance(trade.agentId, trade);
                    sendSellDiscord(trade, "🎯 TAKE PROFIT HIT");
                    writeScanLog("[MONITOR 🎯 TP] " + trade.ticker + " | " + trade.agentId +
                        " | high=$" + String.format("%.2f", todayHigh) +
                        " >= TP=$" + String.format("%.2f", trade.takeProfit) +
                        " | P/L=$" + String.format("%+.2f", trade.profitLoss));
                    System.out.println("[AIToolAgent] MONITOR TP: " + trade.ticker + " @ $" + String.format("%.2f", trade.exitPrice));

                // === PARTIAL EXIT AT 1R (not yet done) ===
                } else if (partialAt1R && !trade.partialExitDone) {
                    double stopDistance = trade.entryPrice - trade.stopLoss;
                    double oneRTarget   = trade.entryPrice + stopDistance;
                    if (stopDistance > 0 && todayHigh >= oneRTarget) {
                        double partialPct   = agentCfg != null ? getDoubleRisk(agentCfg, "partialExitPct", 50.0) / 100.0 : 0.5;
                        double partialQty   = trade.quantity * partialPct;
                        double partialProfit = (oneRTarget - trade.entryPrice) * partialQty;
                        synchronized (LOCK) {
                            trade.partialExitDone  = true;
                            trade.partialExitPrice = oneRTarget;
                        }
                        String msg = String.format(
                            "💰 **PARTIAL EXIT (1R)**\n" +
                            "━━━━━━━━━━━━━━━━━━━━\n" +
                            "**%s** — 50%% sold @ **$%.2f**\n" +
                            "🎯 1R Target hit! Locked: **$%+.2f**\n" +
                            "🔒 Stop moved to break-even: **$%.2f**\n" +
                            "⏳ Remaining 50%% riding to EOD\n" +
                            "🤖 Agent: %s",
                            trade.ticker, oneRTarget, partialProfit,
                            trade.entryPrice,
                            trade.agentId
                        );
                        sendDiscord(msg);
                        writeScanLog("[MONITOR 💰 PARTIAL 1R] " + trade.ticker + " | " + trade.agentId +
                            " | 1R target=$" + String.format("%.2f", oneRTarget) +
                            " | today high=$" + String.format("%.2f", todayHigh) +
                            " | locked profit=$" + String.format("%+.2f", partialProfit));
                        System.out.println("[AIToolAgent] MONITOR PARTIAL_1R: " + trade.ticker + " @ $" + String.format("%.2f", oneRTarget));
                    }
                } else {
                    // === TOP-AGENT SMART EXIT: lock profit if reversed after $25 gain ===
                    double unrealizedProfit = (currentPrice - trade.entryPrice) * trade.quantity;
                    if (isTopAgent(trade.agentId) && unrealizedProfit >= 25.0) {
                        // Move stop to break-even once profit >= $25 (protect the gain)
                        double newStop = trade.entryPrice;
                        if (trade.stopLoss < newStop) {
                            synchronized (LOCK) {
                                trade.stopLoss = newStop;
                            }
                            writeScanLog("[MONITOR 🏆 BREAK-EVEN] " + trade.ticker + " | " + trade.agentId +
                                " | profit=$" + String.format("%+.2f", unrealizedProfit) +
                                " >= $25 → stop raised to break-even $" + String.format("%.2f", newStop));
                        }
                    }
                    writeScanLog("[MONITOR ⏳ HOLD] " + trade.ticker + " | " + trade.agentId +
                        " | live=$" + String.format("%.2f", currentPrice) +
                        " | SL=$" + String.format("%.2f", trade.stopLoss) +
                        " | TP=$" + String.format("%.2f", trade.takeProfit));
                }

                Thread.sleep(12500); // Alpha Vantage rate limit: 5 calls/min

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[AIToolAgent] Monitor error for " + trade.ticker + ": " + e.getMessage());
            }
        }

        writeScanLog("[MONITOR END] Done checking " + openTrades.size() + " position(s)");
        writeScanLog("────────────────────────────────────────");
        saveState();
    }

    /**
     * Returns the number of calendar days since the trade was entered.
     * Uses the ISO-formatted entryTime stored on the trade.
     */
    private static int calcHoldDays(String entryTime) {
        try {
            ZonedDateTime entry = ZonedDateTime.parse(entryTime);
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                entry.toLocalDate(), ZonedDateTime.now(NY).toLocalDate());
            return (int) Math.max(0, days);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Parse a named field from GLOBAL_QUOTE JSON response.
     * e.g. field "05. price" -> current price, "03. high" -> today's high
     */
    private static double parseGlobalQuoteField(String json, String field) {
        try {
            int idx = json.indexOf("\"" + field + "\"");
            if (idx < 0) return 0;
            int colon = json.indexOf(":", idx);
            int start = json.indexOf("\"", colon) + 1;
            int end   = json.indexOf("\"", start);
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== SCAN DETAIL LOG ====================

    /**
     * Append one line to scan-detail.log, prefixed with timestamp.
     */
    // Returns Alpha Vantage call rate from env var AV_CALLS_PER_MIN.
    // Free tier = 5/min. Premium ($50/mo) = 150/min. Set env var to unlock speed.
    private static int avCallsPerMin() {
        String val = System.getenv("AV_CALLS_PER_MIN");
        if (val != null && !val.isBlank()) {
            try { return Math.max(1, Integer.parseInt(val.trim())); } catch (Exception ignored) {}
        }
        return 5; // Alpha Vantage free tier default
    }

    static void writeScanLog(String line) {
        try {
            String entry = ZonedDateTime.now(NY).format(LOG_TIMESTAMP_FMT) + "  " + line + "\n";
            java.nio.file.Files.createDirectories(NEW_STRATEGIES_DIR);
            java.nio.file.Files.write(SCAN_LOG_FILE,
                entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    /**
     * Delete and recreate scan-detail.log if it is older than 48 hours.
     */
    static void rotateScanLogIfNeeded() {
        try {
            if (java.nio.file.Files.exists(SCAN_LOG_FILE)) {
                java.nio.file.attribute.BasicFileAttributes attrs =
                    java.nio.file.Files.readAttributes(SCAN_LOG_FILE,
                        java.nio.file.attribute.BasicFileAttributes.class);
                long ageHours = java.time.Duration.between(
                    attrs.creationTime().toInstant(), java.time.Instant.now()).toHours();
                if (ageHours >= 48) {
                    java.nio.file.Files.delete(SCAN_LOG_FILE);
                    writeScanLog("=== LOG ROTATED (48h) ===");
                    System.out.println("[AIToolAgent] scan-detail.log rotated (was " + ageHours + "h old)");
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Schedule log rotation check every 24h so the file never exceeds ~48h of data.
     */
    private static void scheduleLogRotation() {
        scheduler.scheduleAtFixedRate(() -> {
            try { rotateScanLogIfNeeded(); } catch (Exception ignored) {}
        }, 24, 24, TimeUnit.HOURS);
    }

    // ==================== END SCAN DETAIL LOG ====================

    private static void sendRankedScanSummary(List<RankedSignal> all, List<RankedSignal> top,
                                               RegimeLevel regime, double posMultiplier) {
        if (all.isEmpty()) {
            sendDiscord("📊 **Swing Scan Complete** — No signals above threshold (" + SCORE_THRESHOLD + "/12) this run.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📊 **Swing Scan — Top " + top.size() + " Signal(s)**\n");
        sb.append("🔔 Check once before open & once after close\n");
        // Regime banner
        if (regime == RegimeLevel.VERY_WEAK) {
            sb.append("⛔ **Market regime: very weak** — No trades executed to protect capital\n");
        } else if (regime == RegimeLevel.WEAK) {
            sb.append("⚠️ **Market regime: weak** — Position size reduced to 50%\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
        for (int i = 0; i < top.size(); i++) {
            RankedSignal sig = top.get(i);
            String m        = (i < medals.length) ? medals[i] : (i + 1) + ".";
            String cf        = sig.confluence ? " 🔥" : "";
            double entry     = sig.decision.entryPrice;
            double trigger   = sig.decision.entryTriggerPrice > 0 ? sig.decision.entryTriggerPrice : entry;
            double sl        = sig.decision.suggestedStopLoss;
            double tp        = sig.decision.suggestedTakeProfit;
            double slPct     = trigger > 0 ? ((trigger - sl) / trigger) * 100  : 0;
            double tpPct     = trigger > 0 ? ((tp - trigger) / trigger) * 100  : 0;
            double rr        = slPct > 0 ? tpPct / slPct : 0;
            double effectiveRisk = RISK_PER_TRADE * posMultiplier;
            int    shares    = (trigger > sl && sl > 0)
                ? (int) Math.max(1, Math.round(effectiveRisk / (trigger - sl))) : 0;
            double posValue  = shares * trigger;
            String holdTime  = holdDuration(sig.strategy.strategyType);
            String setupLabel = setupTypeLabel(sig.strategy.strategyType);
            // Line 1 — signal identity
            sb.append(String.format("%s **%s** — %s | Score **%d/12**%s%n",
                m, sig.ticker, setupLabel, sig.decision.totalScore, cf));
            sb.append(String.format("   📊 V:%d T:%d M:%d S:%d%n",
                sig.decision.volumeScore, sig.decision.trendScore,
                sig.decision.momentumScore, sig.decision.setupScore));
            // Line 2 — entry trigger (the most important line)
            sb.append(String.format(
                "   ⚡ **BUY ONLY IF breaks $%.2f** (current $%.2f)%n", trigger, entry));
            // Line 3 — risk management
            sb.append(String.format(
                "   🛑 SL **$%.2f** (-%.1f%%)  🎯 TP **$%.2f** (+%.1f%%)  R:R %.1f:1%n",
                sl, slPct, tp, tpPct, rr));
            // Line 4 — position size + hold time
            sb.append(String.format(
                "   📦 ~%d shares ($%,.0f | $%.0f at risk)  ⏱ %s%n",
                shares, posValue, effectiveRisk, holdTime));
        }
        int filtered = all.size() - top.size();
        if (filtered > 0) {
            sb.append("\n❌ Filtered out: **").append(filtered).append("** lower-scored signal(s)");
        }
        sendDiscord(sb.toString());
    }

    private static String setupTypeLabel(String strategyType) {
        if (strategyType == null) return "Unknown";
        switch (strategyType) {
            case "MOMENTUM_BREAKOUT":    return "⚡ Momentum Breakout";
            case "PULLBACK":             return "↩️ Pullback to Support";
            case "TREND_CONTINUATION":  return "📈 Trend Continuation";
            case "WEEK52_HIGH_MOMENTUM": return "🏔️ 52W High Momentum";
            case "PULLBACK_MA20":        return "↩️ Pullback to MA20";
            case "VOLUME_BREAKOUT":      return "💥 Volume Breakout";
            default:                     return strategyType;
        }
    }

    private static String holdDuration(String strategyType) {
        if (strategyType == null) return "Swing";
        switch (strategyType) {
            case "MOMENTUM_BREAKOUT":    return "⚠️ Intraday (disabled)";
            case "PULLBACK":             return "Swing 2–4 days";
            case "TREND_CONTINUATION":  return "Swing 3–5 days";
            case "WEEK52_HIGH_MOMENTUM": return "Swing 2–6 weeks";
            case "PULLBACK_MA20":        return "Swing 3–10 days";
            case "VOLUME_BREAKOUT":      return "Swing 3–10 days";
            case "STRONG_TREND":          return "Swing 5–15 days";
            default:                     return "Swing";
        }
    }

    /**
     * Send a SELL / close notification to Discord for any closed trade.
     */
    private static void sendSellDiscord(Trade trade, String reason) {
        String icon  = trade.profitLoss >= 0 ? "✅" : "❌";
        String plStr = String.format("%+.2f%%  ($%+.2f)", trade.profitLossPct, trade.profitLoss);
        String scoreStr = trade.entryScore > 0
            ? String.format("Score: **%d/12** (V:%d T:%d M:%d S:%d)",
                trade.entryScore, trade.entryVolumeScore, trade.entryTrendScore,
                trade.entryMomentumScore, trade.entrySetupScore)
            : "";
        String cfStr = trade.entryConfluenceCount >= 2
            ? "  🔥 Confluence(" + trade.entryConfluenceCount + " strategies)" : "";
        String msg = String.format(
            "%s **SELL — %s**\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "**%s**  Entry: $%.2f → Exit: $%.2f\n" +
            "P/L: **%s**\n" +
            "%s%s\n" +
            "🤖 %s  |  ⏰ %s",
            icon, reason,
            trade.ticker, trade.entryPrice, trade.exitPrice,
            plStr,
            scoreStr, cfStr,
            trade.agentId,
            ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm z"))
        );
        sendDiscord(msg);
    }

    /**
     * Get count of currently OPEN positions
     */
    public static int getOpenPositionsCount() {
        int count = 0;
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return 0;
            for (List<Trade> trades : systemState.tradeHistory.values()) {
                for (Trade trade : trades) {
                    if ("OPEN".equals(trade.status)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
    
    /** Count total OPEN positions across ALL agents. */
    private static int countAllOpenPositions() {
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return 0;
            int count = 0;
            for (List<Trade> trades : systemState.tradeHistory.values())
                for (Trade t : trades)
                    if ("OPEN".equals(t.status)) count++;
            return count;
        }
    }

    /** Count OPEN positions per sector across ALL agents. */
    private static Map<String, Integer> countOpenPositionsBySector() {
        Map<String, Integer> sectorCount = new HashMap<>();
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return sectorCount;
            for (List<Trade> trades : systemState.tradeHistory.values())
                for (Trade t : trades)
                    if ("OPEN".equals(t.status) && t.sector != null)
                        sectorCount.merge(t.sector, 1, Integer::sum);
        }
        return sectorCount;
    }

    /** Count OPEN positions for a specific agent. */
    private static int countOpenPositionsForAgent(String agentId) {
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return 0;
            List<Trade> trades = systemState.tradeHistory.get(agentId);
            if (trades == null) return 0;
            int count = 0;
            for (Trade t : trades)
                if ("OPEN".equals(t.status)) count++;
            return count;
        }
    }

    /**
     * Get list of all OPEN positions
     */
    public static List<Trade> getOpenPositions() {
        List<Trade> openPositions = new ArrayList<>();
        synchronized (LOCK) {
            if (systemState == null || systemState.tradeHistory == null) return openPositions;
            for (List<Trade> trades : systemState.tradeHistory.values()) {
                for (Trade trade : trades) {
                    if ("OPEN".equals(trade.status)) {
                        openPositions.add(trade);
                    }
                }
            }
        }
        return openPositions;
    }

    /**
     * Returns top 20 open positions deduplicated by ticker.
     * For each unique ticker, picks the most recent entry; collects the agent IDs holding it.
     */
    public static class OpenPositionSummary {
        public String ticker;
        public double entryPrice;
        public double stopLoss;
        public double takeProfit;
        public double quantity;
        public String entryTime;
        public List<String> agentIds = new ArrayList<>();
    }

    public static List<OpenPositionSummary> getTop20OpenPositionsDeduped() {
        Map<String, OpenPositionSummary> byTicker = new LinkedHashMap<>();
        List<Trade> all = getOpenPositions();
        // Sort newest first
        all.sort((a, b) -> {
            if (a.entryTime == null) return 1;
            if (b.entryTime == null) return -1;
            return b.entryTime.compareTo(a.entryTime);
        });
        for (Trade t : all) {
            if (t.ticker == null) continue;
            OpenPositionSummary summary = byTicker.computeIfAbsent(t.ticker, k -> {
                OpenPositionSummary s = new OpenPositionSummary();
                s.ticker = t.ticker;
                s.entryPrice = t.entryPrice;
                s.stopLoss = t.stopLoss;
                s.takeProfit = t.takeProfit;
                s.quantity = t.quantity;
                s.entryTime = t.entryTime;
                return s;
            });
            if (t.agentId != null && !summary.agentIds.contains(t.agentId)) {
                summary.agentIds.add(t.agentId);
            }
        }
        List<OpenPositionSummary> result = new ArrayList<>(byTicker.values());
        if (result.size() > 20) result = result.subList(0, 20);
        return result;
    }

    private static void deleteUnderperformingAgents() {
        // Delete agents with win rate < 50% and at least 5 trades
        final double UNDERPERFORMING_THRESHOLD = 50.0;
        final int MIN_TRADES_FOR_DELETION = 5;
        
        List<String> agentsToDelete = new ArrayList<>();
        StringBuilder deletedReport = new StringBuilder();
        
        synchronized (LOCK) {
            for (AgentPerformance perf : systemState.performance.values()) {
                // Use expectancy instead of winRate for deletion threshold
                if (perf.totalTrades >= MIN_TRADES_FOR_DELETION && perf.expectancy < 0.0) {
                    agentsToDelete.add(perf.agentId);
                    deletedReport.append(String.format("• **%s** - %.2f%% expectancy (%d/%d trades)\n",
                        perf.agentId, perf.expectancy, perf.wins, perf.totalTrades));
                }
            }
            
            // Delete the agents
            for (String agentId : agentsToDelete) {
                // Remove from agents map
                AgentConfig removed = systemState.agents.remove(agentId);
                
                // Remove from performance map
                systemState.performance.remove(agentId);
                
                // Remove from trade history
                systemState.tradeHistory.remove(agentId);
                
                // Delete the JSON file if it exists in newStrategies folder
                try {
                    Path agentFile = NEW_STRATEGIES_DIR.resolve(agentId + ".json");
                    if (Files.exists(agentFile)) {
                        Files.delete(agentFile);
                        System.out.println("[AIToolAgent] Deleted agent file: " + agentFile);
                    }
                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Error deleting agent file for " + agentId + ": " + e.getMessage());
                }
                
                System.out.println("[AIToolAgent] DELETED underperforming agent: " + agentId);
            }
            
            if (!agentsToDelete.isEmpty()) {
                saveState();
            }
        }
        
        // Send Discord notification about deleted agents
        if (!agentsToDelete.isEmpty()) {
            String message = String.format(
                "🗑️ **End-of-Day Cleanup**\n" +
                "Deleted **%d** underperforming agents (win rate < 50%%):\n%s" +
                "Remaining agents: **%d**",
                agentsToDelete.size(),
                deletedReport.toString(),
                systemState.agents.size()
            );
            sendDiscord(message);
        } else {
            System.out.println("[AIToolAgent] No underperforming agents to delete");
        }
    }

    private static boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(NY);
        int hour = now.getHour();
        int minute = now.getMinute();
        int dayOfWeek = now.getDayOfWeek().getValue();
        
        // Market hours: Monday-Friday, 9:30 AM - 4:00 PM ET
        if (dayOfWeek < 1 || dayOfWeek > 5) return false; // Weekend
        
        int timeInMinutes = hour * 60 + minute;
        int marketOpen = 9 * 60 + 30;  // 9:30 AM
        int marketClose = 16 * 60;      // 4:00 PM
        
        return timeInMinutes >= marketOpen && timeInMinutes < marketClose;
    }

    public static void loadAllAgents() {
        synchronized (LOCK) {
            if (systemState == null) systemState = new AgentSystemState();
            
            // Load from momentum-variants.json
            loadAgentsFromFile("momentum-variants.json", "MOMENTUM", "variants");
            
            // Load from momentum-success-2026.json
            loadAgentsFromFile("momentum-success-2026.json", "SUCCESS", "variants");
            
            // Load from intraday-variants.json
            loadAgentsFromFile("intraday-variants.json", "INTRADAY", "variants");
            
            // Load from swing-variants.json
            loadAgentsFromFile("swing-variants.json", "SWING", "variants");
            
            // Also load swingVariants from momentum-variants.json if present
            loadAgentsFromFile("momentum-variants.json", "SWING", "swingVariants");
            
            // Load from institutional-swing-agent.json
            loadAgentsFromFile("institutional-swing-agent.json", "INSTITUTIONAL", "variants");
            
            // Load from fundamental-momentum-agent.json
            loadAgentsFromFile("fundamental-momentum-agent.json", "FUNDAMENTAL", "variants");
            
            // Load from quality-growth-agent.json
            loadAgentsFromFile("quality-growth-agent.json", "QUALITY", "variants");
            
            // Load any evolved agents from newStrategies folder
            loadEvolvedAgents();
        }
    }

    private static void loadAgentsFromFile(String filename, String type, String arrayKey) {
        try {
            Path path = Paths.get(filename);
            if (!Files.exists(path)) return;
            
            JsonNode root = JSON.readTree(path.toFile());
            
            // Respect top-level "enabled" flag — skip entire file if set to false
            JsonNode enabledNode = root.get("enabled");
            if (enabledNode != null && !enabledNode.asBoolean(true)) {
                System.out.println("[AIToolAgent] Skipping disabled agent file: " + filename);
                return;
            }
            
            JsonNode variants = root.get(arrayKey);
            if (variants == null || !variants.isArray()) return;
            
            for (JsonNode v : variants) {
                AgentConfig agent = new AgentConfig();
                agent.id = v.path("id").asText("");
                agent.name = v.path("name").asText(agent.id);
                agent.sourceFile = filename;
                agent.type = type;
                agent.generation = 0;
                
                JsonNode ef = v.get("entryFilters");
                if (ef != null) {
                    Iterator<String> fields = ef.fieldNames();
                    while (fields.hasNext()) {
                        String f = fields.next();
                        JsonNode val = ef.get(f);
                        if (val.isNumber()) agent.entryFilters.put(f, val.doubleValue());
                        else if (val.isBoolean()) agent.entryFilters.put(f, val.booleanValue());
                        else agent.entryFilters.put(f, val.asText());
                    }
                }
                
                JsonNode rm = v.get("riskManagement");
                if (rm != null) {
                    Iterator<String> fields = rm.fieldNames();
                    while (fields.hasNext()) {
                        String f = fields.next();
                        JsonNode val = rm.get(f);
                        if (val.isNumber()) agent.riskManagement.put(f, val.doubleValue());
                        else agent.riskManagement.put(f, val.asText());
                    }
                }
                
                JsonNode sc = v.get("scoring");
                if (sc != null) {
                    Iterator<String> fields = sc.fieldNames();
                    while (fields.hasNext()) {
                        String f = fields.next();
                        JsonNode val = sc.get(f);
                        if (val.isNumber()) agent.scoring.put(f, val.doubleValue());
                        else agent.scoring.put(f, val.asText());
                    }
                }
                
                agent.entryType = v.path("entryType").asText("AUTO");
                agent.disabled = v.path("disabled").asBoolean(false);

                if (!agent.id.isEmpty()) {
                    systemState.agents.put(agent.id, agent);
                    if (!systemState.performance.containsKey(agent.id)) {
                        AgentPerformance perf = new AgentPerformance();
                        perf.agentId = agent.id;
                        perf.agentName = agent.name;
                        perf.type = agent.type;
                        systemState.performance.put(agent.id, perf);
                    }
                    if (!systemState.tradeHistory.containsKey(agent.id)) {
                        systemState.tradeHistory.put(agent.id, new ArrayList<>());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error loading " + filename + ": " + e.getMessage());
        }
    }

    private static void loadEvolvedAgents() {
        try {
            if (!Files.exists(NEW_STRATEGIES_DIR)) return;
            
            Files.list(NEW_STRATEGIES_DIR)
                .filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().startsWith("agent-"))
                .forEach(p -> {
                    try {
                        JsonNode root = JSON.readTree(p.toFile());
                        AgentConfig agent = new AgentConfig();
                        agent.id = root.path("id").asText("");
                        agent.name = root.path("name").asText(agent.id);
                        agent.sourceFile = p.toString();
                        agent.type = root.path("type").asText("EVOLVED");
                        agent.generation = root.path("generation").asInt(1);
                        agent.parentId = root.path("parentId").asText(null);
                        agent.lastModified = root.path("lastModified").asText(null);
                        
                        // Load lock protection fields
                        agent.locked = root.path("locked").asBoolean(false);
                        agent.lockedAt = root.path("lockedAt").asText(null);
                        agent.codeVersion = root.path("codeVersion").asText(null);
                        agent.lockedByUser = root.path("lockedByUser").asText(null);
                        agent.masterStrategy = root.path("masterStrategy").asBoolean(false);
                        agent.strategyType = root.path("strategyType").asText(null);
                        
                        JsonNode ef = root.get("entryFilters");
                        if (ef != null) {
                            Iterator<String> fields = ef.fieldNames();
                            while (fields.hasNext()) {
                                String f = fields.next();
                                JsonNode val = ef.get(f);
                                if (val.isNumber()) agent.entryFilters.put(f, val.doubleValue());
                                else if (val.isBoolean()) agent.entryFilters.put(f, val.booleanValue());
                                else agent.entryFilters.put(f, val.asText());
                            }
                        }
                        
                        JsonNode rm = root.get("riskManagement");
                        if (rm != null) {
                            Iterator<String> fields = rm.fieldNames();
                            while (fields.hasNext()) {
                                String f = fields.next();
                                JsonNode val = rm.get(f);
                                if (val.isNumber()) agent.riskManagement.put(f, val.doubleValue());
                                else agent.riskManagement.put(f, val.asText());
                            }
                        }
                        
                        if (!agent.id.isEmpty()) {
                            systemState.agents.put(agent.id, agent);
                            if (!systemState.performance.containsKey(agent.id)) {
                                AgentPerformance perf = new AgentPerformance();
                                perf.agentId = agent.id;
                                perf.agentName = agent.name;
                                perf.type = agent.type;
                                perf.generation = agent.generation;
                                systemState.performance.put(agent.id, perf);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Error loading evolved agent: " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error loading evolved agents: " + e.getMessage());
        }
    }

    public static void runAllAgents() {
        boolean alreadyRunning = false;
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            if (systemState.running) {
                alreadyRunning = true;
            } else {
                systemState.running = true;
                systemState.runCount++;
                // AlphaPoint AI: Reset signal counter for new run
                signalsSentThisRun = 0;
                currentRunNumber = systemState.runCount;
            }
        }
        if (alreadyRunning) {
            System.out.println("[AIToolAgent] Already running, skipping...");
            return;
        }

        try {
            // Ticker-centric scan: fetch each ticker ONCE, then test ALL agents against it.
            // This uses 1 API call per ticker instead of (agentCount x tickerCount) calls.
            List<String> allTickers = LongTermCandidateFinder.getAllSectorTickers();
            List<AgentConfig> allAgents;
            synchronized (LOCK) {
                allAgents = new ArrayList<>(systemState.agents.values());
            }

            int total = allTickers.size();
            System.out.println("[AIToolAgent] Run #" + systemState.runCount +
                " | Scanning " + total + " tickers with " + allAgents.size() + " agents");
            writeScanLog("════════════════════════════════════════");
            writeScanLog("[SCAN START] Run #" + systemState.runCount +
                " | Tickers: " + total + " | Agents: " + allAgents.size());
            int avCpm = avCallsPerMin();
            int etaMin = (int) Math.ceil(total * (60.0 / avCpm) / 60);
            sendDiscord("🚀 **Scan Started** — Run #" + systemState.runCount
                + "\nScanning **" + total + "** tickers | AV rate: **" + avCpm + " calls/min** | ETA ~" + etaMin + " min"
                + "\n⏰ " + ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm:ss z")));
            runProgress = 0;
            runTotal = total;
            runStockProgress = 0;
            runCurrentTicker = null;

            int totalSignals = 0;

            // Comparator: top-performing agents first (expectancy desc, min 3 trades), rest at end
            Comparator<AgentConfig> byExpectancyDesc = (a, b) -> {
                AgentPerformance pa = systemState.performance.get(a.id);
                AgentPerformance pb = systemState.performance.get(b.id);
                double ea = (pa != null && pa.totalTrades >= 3) ? pa.expectancy : -1;
                double eb = (pb != null && pb.totalTrades >= 3) ? pb.expectancy : -1;
                return Double.compare(eb, ea); // descending
            };

            // Separate master strategies (scoring-based) from legacy agents (binary pass/fail)
            List<AgentConfig> masterStrategies = allAgents.stream()
                .filter(a -> a.masterStrategy && a.strategyType != null && !a.disabled)
                .sorted(byExpectancyDesc)
                .collect(Collectors.toList());
            List<AgentConfig> legacyAgents = allAgents.stream()
                .filter(a -> !a.masterStrategy)
                .sorted(byExpectancyDesc)
                .collect(Collectors.toList());
            boolean useMasters = !masterStrategies.isEmpty();
            List<RankedSignal> allSignals = new ArrayList<>();

            writeScanLog("[SCAN MODE] " + (useMasters
                ? "MASTER STRATEGY scoring — " + masterStrategies.size() + " strategies (threshold=" + SCORE_THRESHOLD + "/12)"
                : "LEGACY AGENTS binary — " + legacyAgents.size() + " agents"));

            // ── PARALLEL PRE-FETCH: all tickers fetched concurrently via Alpha Vantage ──
            // Rate is controlled by AV_CALLS_PER_MIN env var (default 5 for free tier, 150 for premium).
            // Each thread acquires a time-slot ticket so total rate never exceeds the configured limit.
            final int AV_CPM = avCallsPerMin();
            final long MS_PER_CALL = 60_000L / AV_CPM;
            final int FETCH_THREADS = Math.min(AV_CPM, 16); // no point in more threads than calls/min
            final java.util.concurrent.atomic.AtomicLong nextSlot = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            final java.util.concurrent.atomic.AtomicInteger fetchedCount = new java.util.concurrent.atomic.AtomicInteger(0);
            ExecutorService fetchPool = Executors.newFixedThreadPool(FETCH_THREADS);
            ConcurrentHashMap<String, String> prefetchedJson = new ConcurrentHashMap<>();
            long prefetchStart = System.currentTimeMillis();
            writeScanLog("[PRE-FETCH] Starting parallel AV fetch: " + total + " tickers | " + AV_CPM + " calls/min | ETA ~" + (int)Math.ceil(total / (double)AV_CPM) + " min");
            List<CompletableFuture<Void>> fetchFutures = allTickers.stream()
                .map(t -> CompletableFuture.runAsync(() -> {
                    try {
                        // Acquire a rate-limited time slot (ticket system)
                        long slot = nextSlot.getAndAdd(MS_PER_CALL);
                        long waitMs = slot - System.currentTimeMillis();
                        if (waitMs > 0) Thread.sleep(waitMs);
                        runCurrentTicker = t;
                        String data = DataFetcher.fetchStockDataForTicker(t);
                        if (data != null && !data.isBlank()) {
                            prefetchedJson.put(t, data);
                            int progress = fetchedCount.incrementAndGet();
                            // Log progress every 10 tickers or at completion
                            if (progress % 10 == 0 || progress == total) {
                                writeScanLog("[PRE-FETCH] Progress: " + progress + "/" + total + " (" + 
                                    String.format("%.1f", (progress * 100.0 / total)) + "%) - Latest: " + t);
                            }
                        } else {
                            int progress = fetchedCount.incrementAndGet();
                            // Log failed fetches
                            if (progress % 20 == 0) {
                                writeScanLog("[PRE-FETCH] Progress: " + progress + "/" + total + " - Failed: " + t);
                            }
                        }
                    } catch (Exception e) {
                        int progress = fetchedCount.incrementAndGet();
                        writeScanLog("[PRE-FETCH] Error fetching " + t + ": " + e.getMessage());
                    }
                }, fetchPool))
                .collect(Collectors.toList());
            // Start progress monitor thread
            Thread progressMonitor = new Thread(() -> {
                try {
                    while (!fetchPool.isTerminated()) {
                        Thread.sleep(30000); // Report every 30 seconds
                        int current = fetchedCount.get();
                        double elapsedSec = (System.currentTimeMillis() - prefetchStart) / 1000.0;
                        double rate = current / elapsedSec * 60; // calls per minute
                        writeScanLog("[PRE-FETCH] Status: " + current + "/" + total + " (" + 
                            String.format("%.1f", current * 100.0 / total) + "%) - Rate: " + 
                            String.format("%.1f", rate) + " calls/min - Elapsed: " + 
                            String.format("%.1f", elapsedSec / 60) + " min");
                    }
                } catch (InterruptedException e) {
                    // Normal termination
                }
            });
            progressMonitor.setDaemon(true);
            progressMonitor.start();
            
            try {
                CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.MINUTES);
                progressMonitor.interrupt(); // Stop the monitor when done
            } catch (Exception e) {
                writeScanLog("[PRE-FETCH] Warning: timeout or partial failure — " + e.getMessage());
                progressMonitor.interrupt();
            }
            fetchPool.shutdownNow();
            long prefetchMs = System.currentTimeMillis() - prefetchStart;
            writeScanLog("[PRE-FETCH] Done in " + (prefetchMs / 1000) + "s — "
                + prefetchedJson.size() + "/" + total + " tickers loaded");

            // ── CLOSE EXPIRED POSITIONS: settle any trade past maxHoldDays using pre-fetched prices ──
            closeExpiredPositions(prefetchedJson);

            for (int i = 0; i < allTickers.size(); i++) {
                String ticker = allTickers.get(i);
                runCurrentTicker = ticker;
                runProgress = i;

                try {
                    // Use pre-fetched data from parallel download phase
                    String json = prefetchedJson.get(ticker);
                    if (json == null || json.isBlank()) {
                        writeScanLog("[FETCH FAIL] " + ticker + " — not in pre-fetch cache");
                        continue;
                    }

                    if (useMasters) {
                        // ── MASTER STRATEGY PATH: score-based with confluence detection ──
                        IndicatorData data = computeIndicators(ticker, json);
                        // Institutional Flow Layer: warm cached fundamental/catalyst data (fast, no API calls)
                        InstitutionalFlowLayer.enrichCached(data);
                        // Update RS ranking cache (used to pre-filter universe on next scan)
                        double rsScore = data.momentum20d
                            + (data.priceAboveSMA50  ? 4.0 : -4.0)
                            + (data.priceAboveSMA200 ? 5.0 : -5.0)
                            + (data.rvol > 2.0       ? 2.0 :  0.0);
                        LongTermCandidateFinder.updateRSScore(ticker, data.valid ? rsScore : -99.0);
                        if (!data.valid) {
                            writeScanLog("[NULL] " + ticker + " — insufficient data for scoring");
                            continue;
                        }

                        // Score each master strategy against this ticker
                        Map<String, TradeDecision> scoreMap = new LinkedHashMap<>();
                        for (AgentConfig strategy : masterStrategies) {
                            synchronized (LOCK) { systemState.currentAgent = strategy.id; }
                            if (hasOpenPositionToday(strategy.id, ticker)) {
                                writeScanLog("[SKIP] " + ticker + " | " + strategy.id + " — already has open position today");
                                continue;
                            }
                            TradeDecision d = scoreStrategyForTicker(ticker, strategy, data);
                            scoreMap.put(strategy.id, d);
                        }

                        // Confluence: count strategies that score >= CONFLUENCE_SCORE_THRESHOLD
                        long confluenceCount = scoreMap.values().stream()
                            .filter(d -> d.totalScore >= CONFLUENCE_SCORE_THRESHOLD)
                            .count();

                        // Apply confluence bonus, then decide to trade
                        for (AgentConfig strategy : masterStrategies) {
                            TradeDecision d = scoreMap.get(strategy.id);
                            if (d == null) continue;

                            if (confluenceCount >= 2) {
                                d.confluenceBonus = CONFLUENCE_BONUS;
                                d.totalScore += CONFLUENCE_BONUS;
                            }
                            d.confluenceCount = (int) confluenceCount;

                            // Institutional Flow Layer: fetch + score (API calls only if technical score >= 7 to save quota)
                            InstitutionalFlowLayer.enrichAndScore(data, d, d.totalScore >= 7);

                            d.shouldTrade = d.totalScore >= SCORE_THRESHOLD && !d.triggerNotMet;

                            if (d.shouldTrade) {
                                String confluenceTag = confluenceCount >= 2
                                    ? " 🔥CONFLUENCE(+" + CONFLUENCE_BONUS + ")" : "";
                                String iflTag = (d.finalConviction > 0)
                                    ? " | Conviction=" + String.format("%.1f", d.finalConviction) + "/50"
                                    : "";
                                writeScanLog("[✅ SIGNAL] " + ticker + " | " + strategy.id +
                                    " | Score=" + d.totalScore + "/12" + confluenceTag +
                                    " (V=" + d.volumeScore + " T=" + d.trendScore +
                                    " M=" + d.momentumScore + " S=" + d.setupScore + ")" +
                                    iflTag +
                                    " | Entry=$" + String.format("%.2f", d.entryPrice) +
                                    " SL=$" + String.format("%.2f", d.suggestedStopLoss) +
                                    " TP=$" + String.format("%.2f", d.suggestedTakeProfit));
                                if (hasMinWinRate(strategy.id, 65.0)) {
                                    String agentLabel = (strategy.name != null && !strategy.name.isEmpty())
                                        ? strategy.name : strategy.id;
                                    double entryZoneHigh = d.entryPrice * 1.01;
                                    double cappedSL = d.entryPrice * 0.98; // max $20 risk on $1K position
                                    double cappedSLActual = Math.max(d.suggestedStopLoss, cappedSL); // use tighter of the two
                                    sendDiscord("\uD83C\uDFC6 **" + ticker + "** | " + agentLabel + " (`" + strategy.id + "`)"
                                        + " | Score=" + d.totalScore + "/12" + confluenceTag
                                        + " (V=" + d.volumeScore + " T=" + d.trendScore
                                        + " M=" + d.momentumScore + " S=" + d.setupScore + ")"
                                        + (d.finalConviction > 0
                                            ? "\n🏦 Conviction=" + String.format("%.1f", d.finalConviction) + "/50"
                                              + " (F=" + d.fundamentalScore + " C=" + d.catalystScore
                                              + " I=" + d.institutionalFlowScore + ")"
                                            : "")
                                        + "\n📥 **ENTRY ZONE:** $" + String.format("%.2f", d.entryPrice) + " – $" + String.format("%.2f", entryZoneHigh)
                                        + "  |  🛑 **SL:** $" + String.format("%.2f", cappedSLActual) + " *(max $20 risk)*"
                                        + "  |  🎯 **TP:** $" + String.format("%.2f", d.suggestedTakeProfit)
                                        + "\n⏱ **VALID FOR: 10 min**");
                                }
                                addRecommendation(ticker, strategy.id, d.entryPrice,
                                    d.suggestedStopLoss, d.suggestedTakeProfit, d.totalScore,
                                    d.finalConviction, d.fundamentalScore, d.catalystScore, d.institutionalFlowScore);
                                // If this ticker was previously on the watchlist, promote it
                                confirmPendingSignal(ticker, strategy.id);
                                allSignals.add(new RankedSignal(strategy, ticker, d, confluenceCount >= 2));
                            } else if (d.triggerNotMet) {
                                // Score passed but trigger not yet met → save to watchlist
                                // (already logged as [⏳ TRIGGER-WAIT] inside scoreStrategyForTicker)
                                if (d.totalScore >= SCORE_THRESHOLD) {
                                    upsertPendingSignal(ticker, strategy, d);
                                }
                            } else {
                                writeScanLog("[❌ SCORE] " + ticker + " | " + strategy.id +
                                    " — Score=" + d.totalScore + "/12 < " + SCORE_THRESHOLD +
                                    " (V=" + d.volumeScore + " T=" + d.trendScore +
                                    " M=" + d.momentumScore + " S=" + d.setupScore + ")");
                                // Score dropped → expire any watchlist entry for this ticker/strategy
                                expirePendingSignal(ticker, strategy.id);
                            }
                        }

                    } else {
                        // ── LEGACY AGENT PATH: binary pass/fail (unchanged) ──
                        for (AgentConfig agent : legacyAgents) {
                            try {
                                synchronized (LOCK) { systemState.currentAgent = agent.id; }
                                if (hasOpenPositionToday(agent.id, ticker)) {
                                    writeScanLog("[SKIP] " + ticker + " | " + agent.id + " — already has open position today");
                                    continue;
                                }

                                TradeDecision decision = analyzeStock(ticker, agent, json);
                                if (decision == null) {
                                    writeScanLog("[NULL] " + ticker + " | " + agent.id + " — insufficient data");
                                } else if (decision.shouldTrade) {
                                    // Promote from watchlist if it was waiting
                                    confirmPendingSignal(ticker, agent.id);
                                    writeScanLog("[✅ BUY SIGNAL] " + ticker + " | " + agent.id +
                                        " | Entry=$" + String.format("%.2f", decision.entryPrice) +
                                        " SL=$" + String.format("%.2f", decision.suggestedStopLoss) +
                                        " TP=$" + String.format("%.2f", decision.suggestedTakeProfit) +
                                        " conf=" + String.format("%.2f", decision.confidence));
                                    writeScanLog("[📊 ENTRY DIAG] " + ticker + " | " + agent.id +
                                        " | type=" + decision.diagEntryType +
                                        " | RSI=" + String.format("%.1f", decision.diagRsi) +
                                        " | RVOL=" + String.format("%.2f", decision.diagRvol) +
                                        " | distSMA20=" + String.format("%+.1f%%", decision.diagDistSMA20) +
                                        " | distSMA50=" + String.format("%+.1f%%", decision.diagDistSMA50));
                                    sendBuyAlertIfMonitored(agent, ticker, decision);
                                    Trade trade = executeTrade(agent, ticker, decision);
                                    if (trade != null) {
                                        logBuySignal(agent.id, ticker, trade.entryPrice, trade.stopLoss, trade.takeProfit);
                                        logTradeExecuted(agent.id, ticker, trade.entryPrice, trade.quantity);
                                        synchronized (LOCK) {
                                            systemState.tradeHistory
                                                .computeIfAbsent(agent.id, k -> new ArrayList<>())
                                                .add(trade);
                                        }
                                        updatePerformance(agent.id, trade);
                                        totalSignals++;
                                    }
                                } else if (decision.triggerNotMet) {
                                    // Scan gates passed but entry trigger not yet fired → watchlist
                                    upsertPendingSignal(ticker, agent, decision);
                                } else {
                                    // Scan gates failed → expire any existing watchlist entry
                                    expirePendingSignal(ticker, agent.id);
                                    writeScanLog("[❌ REJECT] " + ticker + " | " + agent.id +
                                        " — " + (decision.rejectReason != null ? decision.rejectReason : "unknown"));
                                }
                            } catch (Exception e) {
                                System.err.println("[AIToolAgent] Agent " + agent.id +
                                    " error on " + ticker + ": " + e.getMessage());
                                writeScanLog("[ERROR] " + ticker + " | " + agent.id + " — " + e.getMessage());
                            }
                        }
                    }

                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Error fetching " + ticker + ": " + e.getMessage());
                }
            }

            // ── RANK & EXECUTE: portfolio-manager mode ──
            if (useMasters && !allSignals.isEmpty()) {
                // Rank by finalConviction (META SCORE) instead of totalScore
                allSignals.sort((a, b) -> Double.compare(b.decision.finalConviction, a.decision.finalConviction));

                writeScanLog("────────────────────────────────────────");
                writeScanLog("[PORTFOLIO] ── Step 1: Dedup ─────────────────────────");
                writeScanLog("[PORTFOLIO] Raw signals before dedup: " + allSignals.size());

                // ── Step 1: Dedup same ticker across strategies — keep highest finalConviction signal ──
                Map<String, RankedSignal> dedupMap = new LinkedHashMap<>();
                for (RankedSignal sig : allSignals) {
                    RankedSignal existing = dedupMap.get(sig.ticker);
                    if (existing != null && existing.decision.finalConviction >= sig.decision.finalConviction) {
                        writeScanLog("[DEDUP] " + sig.ticker + " — dropped " + sig.strategy.id
                            + " (finalConviction=" + String.format("%.2f", sig.decision.finalConviction) + ") — kept " + existing.strategy.id
                            + " (finalConviction=" + String.format("%.2f", existing.decision.finalConviction) + ")");
                    } else {
                        if (existing != null) {
                            writeScanLog("[DEDUP] " + sig.ticker + " — replaced " + existing.strategy.id
                                + " (finalConviction=" + String.format("%.2f", existing.decision.finalConviction) + ") with " + sig.strategy.id
                                + " (finalConviction=" + String.format("%.2f", sig.decision.finalConviction) + ")");
                        }
                        dedupMap.put(sig.ticker, sig);
                    }
                }
                List<RankedSignal> dedupedSignals = new ArrayList<>(dedupMap.values());
                dedupedSignals.sort((a, b) -> Double.compare(b.decision.finalConviction, a.decision.finalConviction));
                int removedDups = allSignals.size() - dedupedSignals.size();
                writeScanLog("[DEDUP] Result: " + allSignals.size() + " → " + dedupedSignals.size()
                    + " unique signals (" + removedDups + " duplicate(s) removed)");

                // ── Step 1.5: Take TOP 2 candidates only ──
                final int TOP_CANDIDATES = 2;
                List<RankedSignal> topCandidates = dedupedSignals.stream()
                    .limit(TOP_CANDIDATES)
                    .collect(Collectors.toList());
                writeScanLog("[RANK] Taking TOP " + TOP_CANDIDATES + " candidates from " + dedupedSignals.size() + " unique signals");

                // Log ranked list
                writeScanLog("[PORTFOLIO] ── Ranked TOP candidates ──────────");
                for (int i = 0; i < topCandidates.size(); i++) {
                    RankedSignal sig = topCandidates.get(i);
                    String sector = LongTermCandidateFinder.getSectorForTicker(sig.ticker);
                    writeScanLog(String.format("[RANK #%d] %s | %s | FinalConviction=%.2f | Tech=%.1f Mom=%.1f Fund=%.1f Cat=%.1f | Sector=%s",
                        i + 1, sig.ticker, sig.strategy.id, sig.decision.finalConviction,
                        sig.decision.totalScore * 0.35, sig.decision.momentumScore * 0.20,
                        sig.decision.fundamentalScore * 0.25, sig.decision.catalystScore * 0.20, sector));
                }

                // ── Step 2: Portfolio constraints — sector cap + agent open cap + total open cap ──
                writeScanLog("[PORTFOLIO] ── Step 2: Portfolio filter ─────────────");
                int currentOpenTotal = countAllOpenPositions();
                Map<String, Integer> sectorOpenCount = countOpenPositionsBySector();
                writeScanLog("[PORTFOLIO] Current open positions: " + currentOpenTotal + "/" + MAX_OPEN_POSITIONS_TOTAL);
                if (!sectorOpenCount.isEmpty()) {
                    StringBuilder sb2 = new StringBuilder("[PORTFOLIO] Open by sector:");
                    sectorOpenCount.forEach((sec, cnt) -> sb2.append(" ").append(sec).append("=").append(cnt));
                    writeScanLog(sb2.toString());
                }

                List<RankedSignal> topSignals = new ArrayList<>();
                for (RankedSignal sig : topCandidates) {
                    String sector = LongTermCandidateFinder.getSectorForTicker(sig.ticker);
                    // Check: total open cap
                    if (topSignals.size() + currentOpenTotal >= MAX_OPEN_POSITIONS_TOTAL) {
                        writeScanLog("[PORTFOLIO] ❌ " + sig.ticker + " | Portfolio full ("
                            + (topSignals.size() + currentOpenTotal) + "/" + MAX_OPEN_POSITIONS_TOTAL + ") — skipped");
                        continue;
                    }
                    // Check: per-agent cap
                    int agentOpen = countOpenPositionsForAgent(sig.strategy.id);
                    int agentMax  = sig.strategy.maxOpenTrades > 0 ? sig.strategy.maxOpenTrades : MAX_OPEN_POSITIONS_TOTAL;
                    if (agentOpen >= agentMax) {
                        writeScanLog("[PORTFOLIO] ❌ " + sig.ticker + " | Agent " + sig.strategy.id
                            + " at max open positions (" + agentOpen + "/" + agentMax + ") — skipped");
                        continue;
                    }
                    // Check: per-sector cap
                    int sectorMax     = sig.strategy.maxPerSector > 0 ? sig.strategy.maxPerSector : MAX_OPEN_PER_SECTOR;
                    int sectorCurrent = sectorOpenCount.getOrDefault(sector, 0);
                    if (!"OTHER".equals(sector) && sectorCurrent >= sectorMax) {
                        writeScanLog("[PORTFOLIO] ❌ " + sig.ticker + " | Sector " + sector
                            + " at cap (" + sectorCurrent + "/" + sectorMax + ") — skipped");
                        continue;
                    }
                    writeScanLog("[PORTFOLIO] ✅ " + sig.ticker + " | FinalConviction=" + String.format("%.2f", sig.decision.finalConviction)
                        + " | " + sig.strategy.id + " | Sector=" + sector
                        + " (" + sectorCurrent + "/" + sectorMax + ")");
                    topSignals.add(sig);
                    sectorOpenCount.merge(sector, 1, Integer::sum); // reserve slot for this scan
                }
                writeScanLog("[PORTFOLIO] Filter result: " + topCandidates.size()
                    + " → " + topSignals.size() + " signal(s) approved for execution");

                RegimeLevel regime = checkMarketRegime();
                double posMultiplier = (regime == RegimeLevel.HEALTHY) ? 1.0
                                     : (regime == RegimeLevel.WEAK)    ? 0.5 : 0.0;
                sendRankedScanSummary(topCandidates, topSignals, regime, posMultiplier);
                writeScanLog("[PORTFOLIO] ── Step 3: Execution ──────────────────");
                if (regime == RegimeLevel.VERY_WEAK) {
                    lastScanSignalCount = topCandidates.size();
                    writeScanLog("[REGIME] ⛔ Trade execution blocked — market in crash mode (" + topCandidates.size() + " signal(s) suppressed)");
                } else {
                    String regimeNote = (regime == RegimeLevel.WEAK) ? " [WEAK regime — 50% size]" : "";
                    writeScanLog("[EXECUTE] " + topSignals.size() + " signal(s) queued for execution" + regimeNote);
                    for (RankedSignal sig : topSignals) {
                        if (hasOpenPositionToday(sig.strategy.id, sig.ticker)) {
                            writeScanLog("[EXECUTE] ⚠️ " + sig.ticker + " | " + sig.strategy.id + " — already has open position today, skipped");
                            continue;
                        }
                        Trade trade = executeTrade(sig.strategy, sig.ticker, sig.decision);
                        if (trade != null) {
                            logBuySignal(sig.strategy.id, sig.ticker, trade.entryPrice, trade.stopLoss, trade.takeProfit);
                            logTradeExecuted(sig.strategy.id, sig.ticker, trade.entryPrice, trade.quantity);
                            writeScanLog(String.format("[EXECUTE] ✅ TRADE OPENED: %s | %s | Entry=$%.2f | SL=$%.2f (%.1f%%) | TP=$%.2f (%.1f%%)",
                                sig.ticker, sig.strategy.id,
                                trade.entryPrice,
                                trade.stopLoss,  ((trade.entryPrice - trade.stopLoss)  / trade.entryPrice) * 100,
                                trade.takeProfit, ((trade.takeProfit - trade.entryPrice) / trade.entryPrice) * 100));
                            synchronized (LOCK) {
                                systemState.tradeHistory
                                    .computeIfAbsent(sig.strategy.id, k -> new ArrayList<>())
                                    .add(trade);
                            }
                            updatePerformance(sig.strategy.id, trade);
                            totalSignals++;
                        } else {
                            writeScanLog("[EXECUTE] ⚠️ " + sig.ticker + " | executeTrade returned null — skipped");
                        }
                    }
                }
                writeScanLog("────────────────────────────────────────");
            }

            runProgress = total;
            System.out.println("[AIToolAgent] Scan complete — signals: " + totalSignals +
                ", tickers: " + total + ", agents: " + allAgents.size());
            writeScanLog("[SCAN END] Run #" + systemState.runCount +
                " | Signals: " + totalSignals + " | Tickers scanned: " + total);
            writeScanLog("════════════════════════════════════════");

            autoTrackWinners();

            synchronized (LOCK) {
                systemState.lastRunTime = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                systemState.running = false;
                systemState.currentAgent = null;
            }
            runProgress = 0;
            runTotal = 0;
            runCurrentTicker = null;

            LongTermCandidateFinder.persistRSCache(); // persist RS scores so next cold start skips weak tickers
            saveState();
            saveHistory();

            System.out.println("[AIToolAgent] Run #" + systemState.runCount + " completed.");
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Run error: " + e.getMessage());
            synchronized (LOCK) {
                systemState.running = false;
            }
        }
    }

    private static void runSingleAgent(AgentConfig agent) {
        // Select random stocks from the ticker list
        List<String> selectedStocks = selectRandomStocks(STOCKS_PER_AGENT_RUN);
        System.out.println("[AIToolAgent] Agent " + agent.id + " analyzing " + selectedStocks.size() + " stocks: " + selectedStocks);
        
        for (String ticker : selectedStocks) {
            runCurrentTicker = ticker;
            try {
                // Simulate analysis and trading decision
                TradeDecision decision = analyzeStock(ticker, agent);
                
                if (decision != null && decision.shouldTrade) {
                    // Send BUY ALERT notification BEFORE executing trade
                    sendBuyAlertIfMonitored(agent, ticker, decision);
                    
                    Trade trade = executeTrade(agent, ticker, decision);
                    if (trade != null) {
                        synchronized (LOCK) {
                            systemState.tradeHistory.computeIfAbsent(agent.id, k -> new ArrayList<>()).add(trade);
                        }
                        updatePerformance(agent.id, trade);
                    }
                }
                
                // Rate limit delay - Alpha Vantage free tier = 5 calls/minute
                Thread.sleep(12500); // 12.5 seconds between calls = ~5 calls/minute
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[AIToolAgent] Error analyzing " + ticker + " for " + agent.id + ": " + e.getMessage());
            }
        }
    }

    private static List<String> selectRandomStocks(int count) {
        List<String> shuffled = new ArrayList<>(LongTermCandidateFinder.getAllSectorTickers());
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    // Returns true if this agent already has an OPEN position on the given ticker opened today.
    // Prevents the same agent from entering the same stock multiple times in one trading day.
    private static boolean hasOpenPositionToday(String agentId, String ticker) {
        String today = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_LOCAL_DATE);
        synchronized (LOCK) {
            List<Trade> trades = systemState.tradeHistory.get(agentId);
            if (trades == null) return false;
            for (Trade t : trades) {
                if (ticker.equals(t.ticker) && "OPEN".equals(t.status)
                        && t.entryTime != null && t.entryTime.startsWith(today)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Made public for testing/debugging
    public static class TradeDecision {
        public boolean shouldTrade;
        public String action; // BUY or SELL
        public double confidence;
        public double suggestedStopLoss;
        public double suggestedTakeProfit;
        public double entryPrice; // set during analysis; avoids redundant API call in executeTrade
        public String rejectReason; // set when shouldTrade=false, describes which filter rejected
        public int totalScore;
        public int volumeScore;
        public int trendScore;
        public int momentumScore;
        public int setupScore;
        public int confluenceBonus;
        public int confluenceCount; // how many strategies scored >= CONFLUENCE_SCORE_THRESHOLD
        public double entryTriggerPrice; // BUY ONLY IF price breaks above this level (swing confirmation)
        public boolean triggerNotMet = false; // Execution gate: price hasn't met the entry trigger yet
        // Entry diagnostics — captured at decision time, logged + stored on every executed trade
        public String diagEntryType   = "AUTO";
        public double diagRsi         = 0;
        public double diagRvol        = 0;
        public double diagDistSMA20   = 0; // % distance from SMA20
        public double diagDistSMA50   = 0; // % distance from SMA50

        // ── Institutional Flow Layer scores ──
        public int    fundamentalScore;        // 0-10  (Layer 2)
        public int    catalystScore;         // 0-10  (Layer 3)
        public int    institutionalFlowScore; // 0-10  (Layer 4)
        public double finalConviction;       // 0-50  (weighted composite)

        // ── META SCORE components ──
        public double metaScore;              // 0-100 total meta score
        public double metaTechnicalScore;     // 0-30
        public double metaRegimeScore;        // 0-20
        public double metaFundamentalScore;   // 0-20
        public double metaFlowScore;          // 0-15
        public double metaHistoricalScore;    // 0-15

        // Raw IFL data for snapshot persistence
        public FundamentalData fundamentalData;
        public CatalystData    catalystData;
    }

    private static class RankedSignal {
        AgentConfig strategy;
        String ticker;
        TradeDecision decision;
        boolean confluence;
        RankedSignal(AgentConfig strategy, String ticker, TradeDecision decision, boolean confluence) {
            this.strategy = strategy; this.ticker = ticker;
            this.decision = decision; this.confluence = confluence;
        }
    }

    public static class IndicatorData {
        String ticker;
        double currentPrice;
        double prevClose;
        double todayChangePct;
        double sma20;
        double sma50;
        double sma200;
        double rsi;
        double cci;
        double rvol;
        double typicalPrice;
        double vwapPct;
        double momentum20d;  // % return over last 20 trading days (RS proxy)
        double week52High;        // highest high over available data (up to 252 bars)
        double pctFromWeek52High; // how far below the 52W high (negative = below, 0 = at high)
        double resistance30d;     // highest close over last 30 trading days (excl. today) = resistance level
        double atrPct;            // ATR(14) as % of price — volatility check (0 if unavailable)
        double atr;               // ATR(14) raw dollar value — used for ATR-based stop calculation
        double prevHigh;          // yesterday's high price (for entry trigger: break above prev high)
        double high20d;           // highest high over last 20 sessions (near 20-day high check)
        boolean priceAboveSMA20;
        boolean priceAboveSMA50;
        boolean priceAboveSMA200;
        boolean maCrossoverUp;
        boolean hasSMA50;
        boolean hasSMA200;
        boolean valid = false;

        // ── Institutional Flow Layer enrichment ──
        FundamentalData fundamentalData;
        CatalystData catalystData;
    }

    /**
     * Market regime filter: SPY must be above its 20-day SMA and not down more than 2% today.
     * Fail-open: returns true (allow trading) if SPY data cannot be fetched.
     */
    private static RegimeLevel checkMarketRegime() {
        try {
            DataFetcher.setTicker("SPY");
            String json = DataFetcher.fetchStockData();
            Thread.sleep(12500);
            if (json == null || json.isBlank()) {
                writeScanLog("[REGIME] SPY data unavailable — proceeding (fail-open)");
                return RegimeLevel.HEALTHY;
            }
            IndicatorData spy = computeIndicators("SPY", json);
            if (!spy.valid) {
                writeScanLog("[REGIME] SPY indicators invalid — proceeding (fail-open)");
                return RegimeLevel.HEALTHY;
            }
            double pctBelowSMA20 = (spy.sma20 > 0 && !spy.priceAboveSMA20)
                ? ((spy.sma20 - spy.currentPrice) / spy.sma20) * 100 : 0;
            RegimeLevel level;
            if (spy.priceAboveSMA20 && spy.todayChangePct > -1.0) {
                level = RegimeLevel.HEALTHY;    // strong: above 20MA and less than -1% today
            } else if (!spy.priceAboveSMA20 && (spy.todayChangePct <= -2.0 || pctBelowSMA20 >= 2.0)) {
                level = RegimeLevel.VERY_WEAK;  // crash: below 20MA by 2%+ OR down >2% today
            } else {
                level = RegimeLevel.WEAK;       // mixed: reduced size
            }
            String label = level == RegimeLevel.HEALTHY   ? "✅ HEALTHY (full size)"
                         : level == RegimeLevel.WEAK      ? "⚠️ WEAK (50% size)"
                                                          : "⛔ VERY WEAK (no trades)";
            writeScanLog(String.format("[REGIME] SPY $%.2f | SMA20=$%.2f | Change=%.2f%% | BelowSMA20=%.1f%% | Above20=%s → %s",
                spy.currentPrice, spy.sma20, spy.todayChangePct, pctBelowSMA20,
                spy.priceAboveSMA20 ? "YES" : "NO", label));
            // Persist for UI banner
            lastKnownRegime    = level;
            lastRegimeDetail   = String.format("SPY $%.2f | SMA20=$%.2f | Change=%.2f%%",
                spy.currentPrice, spy.sma20, spy.todayChangePct);
            lastRegimeCheckTime = ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm z"));
            return level;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return RegimeLevel.HEALTHY;
        } catch (Exception e) {
            writeScanLog("[REGIME] SPY check error: " + e.getMessage() + " — proceeding (fail-open)");
            return RegimeLevel.HEALTHY;
        }
    }

    private static IndicatorData computeIndicators(String ticker, String json) {
        IndicatorData d = new IndicatorData();
        d.ticker = ticker;
        try {
            List<Double> prices = PriceJsonParser.extractClosingPrices(json);
            if (prices == null || prices.size() < 20) return d;

            List<Double> highPrices = PriceJsonParser.extractHighPrices(json);
            List<Double> lowPrices  = PriceJsonParser.extractLowPrices(json);
            List<Double> volumes    = PriceJsonParser.extractVolumes(json);

            d.currentPrice   = prices.get(prices.size() - 1);
            d.prevClose      = prices.size() >= 2 ? prices.get(prices.size() - 2) : d.currentPrice;
            d.todayChangePct = d.prevClose > 0 ? ((d.currentPrice - d.prevClose) / d.prevClose) * 100 : 0;
            d.momentum20d    = prices.size() >= 21
                ? ((d.currentPrice - prices.get(prices.size() - 21)) / prices.get(prices.size() - 21)) * 100
                : d.todayChangePct;

            List<Double> rsiList = RSI.calculateRSI(prices, 14);
            d.rsi = (rsiList != null && !rsiList.isEmpty()) ? rsiList.get(rsiList.size() - 1) : 50.0;

            List<Double> sma20List = TechnicalAnalysisModel.calculateSMA(prices, 20);
            d.sma20 = (sma20List != null && !sma20List.isEmpty()) ? sma20List.get(sma20List.size() - 1) : d.currentPrice;
            d.priceAboveSMA20 = d.currentPrice > d.sma20;

            if (prices.size() >= 50) {
                List<Double> sma50List = TechnicalAnalysisModel.calculateSMA(prices, 50);
                if (sma50List != null && !sma50List.isEmpty()) {
                    d.sma50 = sma50List.get(sma50List.size() - 1);
                    d.hasSMA50 = true;
                    d.priceAboveSMA50 = d.currentPrice > d.sma50;
                    d.maCrossoverUp   = d.sma20 > d.sma50;
                }
            }

            if (prices.size() >= 200) {
                List<Double> sma200List = TechnicalAnalysisModel.calculateSMA(prices, 200);
                if (sma200List != null && !sma200List.isEmpty()) {
                    d.sma200 = sma200List.get(sma200List.size() - 1);
                    d.hasSMA200 = true;
                    d.priceAboveSMA200 = d.currentPrice > d.sma200;
                }
            }

            if (volumes != null && volumes.size() >= 20) {
                double curVol = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                // If today's bar has 0 volume (Alpha Vantage includes partial intraday bar),
                // fall back to the previous fully-closed session's volume
                if (curVol == 0 && volumes.size() >= 2) {
                    curVol = volumes.get(volumes.size() - 2) != null ? volumes.get(volumes.size() - 2) : 0;
                }
                double avgVol = volumes.subList(Math.max(0, volumes.size() - 20), volumes.size() - 1)
                        .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                d.rvol = avgVol > 0 ? curVol / avgVol : 0;
            }

            if (highPrices != null && lowPrices != null && highPrices.size() >= 20) {
                List<Double> cciList = CCI.calculateCCI(highPrices, lowPrices, prices, 20);
                if (cciList != null && !cciList.isEmpty() && cciList.get(cciList.size() - 1) != null) {
                    d.cci = cciList.get(cciList.size() - 1);
                }
            }

            if (highPrices != null && lowPrices != null && !highPrices.isEmpty()) {
                double high = highPrices.get(highPrices.size() - 1);
                double low  = lowPrices.get(lowPrices.size() - 1);
                d.typicalPrice = (high + low + d.currentPrice) / 3.0;
                d.vwapPct = d.typicalPrice > 0 ? ((d.currentPrice - d.typicalPrice) / d.typicalPrice) * 100 : 0;

                // 52-week high: max of all available high prices (up to 252 bars)
                int lookback52 = Math.min(highPrices.size(), 252);
                d.week52High = highPrices.subList(highPrices.size() - lookback52, highPrices.size())
                    .stream().mapToDouble(v -> v != null ? v : 0).max().orElse(0);
                d.pctFromWeek52High = d.week52High > 0
                    ? ((d.currentPrice - d.week52High) / d.week52High) * 100 : 0;
            }

            // 30-day resistance: highest close over prior 30 sessions (excluding today)
            if (prices.size() >= 31) {
                int n = prices.size();
                d.resistance30d = prices.subList(n - 31, n - 1)
                    .stream().mapToDouble(v -> v != null ? v : 0).max().orElse(0);
            }

            // Yesterday's high (entry trigger for STRONG_TREND)
            if (highPrices != null && highPrices.size() >= 2) {
                d.prevHigh = highPrices.get(highPrices.size() - 2);
            }
            // 20-day high ("near 20-day high" momentum check)
            if (highPrices != null && highPrices.size() >= 20) {
                int sz = highPrices.size();
                d.high20d = highPrices.subList(sz - 20, sz)
                    .stream().mapToDouble(v -> v != null ? v : 0).max().orElse(0);
            }

            // ATR(14) as % of price — volatility filter
            if (highPrices != null && lowPrices != null && highPrices.size() >= 14) {
                List<Double> atrList = ATR.calculateATR(highPrices, lowPrices, prices, 14);
                if (atrList != null && !atrList.isEmpty()) {
                    Double atrVal = atrList.get(atrList.size() - 1);
                    if (atrVal != null && atrVal > 0 && d.currentPrice > 0) {
                        d.atrPct = (atrVal / d.currentPrice) * 100.0;
                        d.atr    = atrVal;
                    }
                }
            }

            d.valid = true;
        } catch (Exception e) {
            System.err.println("[AIToolAgent] computeIndicators error for " + ticker + ": " + e.getMessage());
        }
        return d;
    }

    private static TradeDecision scoreStrategyForTicker(String ticker, AgentConfig strategy, IndicatorData data) {
        // Universal ATR volatility filter — skips stocks that are too slow to reach TP
        double atrMinPct = getDoubleFilter(strategy, "atrMinPct", 0.0);
        if (atrMinPct > 0 && data.atrPct < atrMinPct) {
            TradeDecision skip = new TradeDecision();
            skip.rejectReason = String.format("ATR%%=%.1f%% < min=%.1f%% (too slow)", data.atrPct, atrMinPct);
            writeScanLog("[❌ ATR] " + ticker + " | " + strategy.id +
                String.format(" | ATR%%=%.1f%% < required %.1f%%", data.atrPct, atrMinPct));
            return skip;
        }
        TradeDecision dec;
        if ("MOMENTUM_BREAKOUT".equals(strategy.strategyType)) {
            dec = scoreMomentumBreakout(ticker, strategy, data);
        } else if ("PULLBACK".equals(strategy.strategyType)) {
            dec = scorePullback(ticker, strategy, data);
        } else if ("TREND_CONTINUATION".equals(strategy.strategyType)) {
            dec = scoreTrendContinuation(ticker, strategy, data);
        } else if ("WEEK52_HIGH_MOMENTUM".equals(strategy.strategyType)) {
            dec = score52WeekHighMomentum(ticker, strategy, data);
        } else if ("PULLBACK_MA20".equals(strategy.strategyType)) {
            dec = scorePullbackMA20(ticker, strategy, data);
        } else if ("VOLUME_BREAKOUT".equals(strategy.strategyType)) {
            dec = scoreVolumeBreakout(ticker, strategy, data);
        } else if ("STRONG_TREND".equals(strategy.strategyType)) {
            dec = scoreStrongTrend(ticker, strategy, data);
        } else if ("INSTITUTIONAL_SWING".equals(strategy.strategyType)) {
            dec = scoreInstitutionalSwing(ticker, strategy, data);
        } else {
            dec = new TradeDecision();
            dec.rejectReason = "Unknown strategyType: " + strategy.strategyType;
            return dec;
        }

        // ── Move Potential filter ──────────────────────────────────────────────────
        // Breakout strategies (MOMENTUM_BREAKOUT, VOLUME_BREAKOUT, WEEK52_HIGH_MOMENTUM)
        // intentionally enter near resistance, so skip the penalty for them.
        boolean isBreakoutType = "MOMENTUM_BREAKOUT".equals(strategy.strategyType)
            || "VOLUME_BREAKOUT".equals(strategy.strategyType)
            || "WEEK52_HIGH_MOMENTUM".equals(strategy.strategyType);

        if (!isBreakoutType && data.resistance30d > 0 && data.currentPrice > 0) {
            double upsideToResistance = ((data.resistance30d - data.currentPrice) / data.currentPrice) * 100;
            int movePenalty = 0;
            if      (upsideToResistance <= 0)   movePenalty = -2; // at/above resistance — reversal zone
            else if (upsideToResistance <  3.0) movePenalty = -1; // almost no room to TP
            else if (upsideToResistance >= 10.0) movePenalty =  1; // lots of room — quality entry
            if (movePenalty != 0) {
                dec.totalScore = Math.max(0, Math.min(12, dec.totalScore + movePenalty));
                writeScanLog("[MOVE-POTENTIAL] " + ticker + " | " + strategy.id
                    + String.format(" | upside=%.1f%% to R30=$%.2f → score%+d → %d/12",
                        upsideToResistance, data.resistance30d, movePenalty, dec.totalScore));
            }
        }

        // ── 52-week high proximity bonus (structure strength) ─────────────────────
        // Stocks within 10% of their 52W high are the structural leaders — strongest RS.
        // Applied to all strategy types (breakout strategies benefit too).
        // pctFromWeek52High is negative: -5.0 means 5% below the 52W high.
        if (data.week52High > 0 && data.currentPrice > 0 && data.pctFromWeek52High >= -10.0) {
            dec.totalScore = Math.min(12, dec.totalScore + 1);
            writeScanLog("[52W-STRUCTURE] " + ticker + " | " + strategy.id
                + String.format(" | %.1f%% from 52W high ($%.2f) → +1 → %d/12",
                    data.pctFromWeek52High, data.week52High, dec.totalScore));
        }

        // ── Weak market penalty ────────────────────────────────────────────────────
        // In a WEAK regime (SPY below SMA20), borderline signals score 10→8 (rejected).
        // Strong signals 12→10 still pass. VERY_WEAK is already blocked before execution.
        if (lastKnownRegime == RegimeLevel.WEAK) {
            int before = dec.totalScore;
            dec.totalScore = Math.max(0, dec.totalScore - 2);
            if (dec.totalScore != before)
                writeScanLog("[REGIME-PENALTY] " + ticker + " | WEAK market → score " + before + "→" + dec.totalScore + "/12");
        }

        // ── Execution Layer: Entry Trigger Gate ───────────────────────────────────
        // Validates that the current daily close has ALREADY satisfied the entry condition.
        // Signals that haven't triggered yet are marked triggerNotMet=true — they are scored
        // and logged, but NOT executed.  Will be re-evaluated in the next scan cycle.
        {
            String sType = strategy.strategyType;
            double price  = data.currentPrice;

            if ("VOLUME_BREAKOUT".equals(sType) || "WEEK52_HIGH_MOMENTUM".equals(sType)) {
                // Breakout confirmed only if close is above the breakout level.
                // Reject also if >6% above trigger — overextended late chase.
                // Reject also if volume is weak — breakout without volume = fake breakout.
                double trigger = dec.entryTriggerPrice;
                if (trigger > 0 && price < trigger) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "TRIGGER-WAIT: $%.2f hasn't cleared breakout level $%.2f (need +%.1f%%)",
                        price, trigger, ((trigger - price) / price) * 100);
                } else if (trigger > 0 && price > trigger * 1.06) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "OVEREXTENDED: $%.2f is >6%% above breakout $%.2f — late chase", price, trigger);
                } else if (data.rvol < 1.5) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "NO-VOL-CONFIRM: breakout at $%.2f lacks volume (rvol=%.2fx < 1.5) — potential fake breakout",
                        price, data.rvol);
                }

            } else if ("PULLBACK".equals(sType)) {
                // Bounce confirmation: price must have closed at or above the VWAP/typical-price.
                double vwapRef = data.typicalPrice > 0 ? data.typicalPrice : data.sma20;
                if (vwapRef > 0 && price < vwapRef) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "TRIGGER-WAIT: $%.2f still below VWAP $%.2f — bounce not confirmed", price, vwapRef);
                }

            } else if ("PULLBACK_MA20".equals(sType)) {
                // Bounce confirmation: price must have reclaimed SMA20.
                if (data.sma20 > 0 && price < data.sma20) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "TRIGGER-WAIT: $%.2f still below SMA20 $%.2f — not reclaimed yet", price, data.sma20);
                }

            } else if ("TREND_CONTINUATION".equals(sType)) {
                // Momentum confirmation: stock must have closed up ≥0.5% today.
                // Also reject if up >3% — that's a blow-off, not a continuation entry.
                if (data.todayChangePct < 0.5) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "TRIGGER-WAIT: today Δ%.2f%% < 0.5%% — no momentum confirmation", data.todayChangePct);
                } else if (data.todayChangePct > 3.0) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "OVEREXTENDED: today Δ%.2f%% > 3%% — chasing blow-off", data.todayChangePct);
                }

            } else if ("STRONG_TREND".equals(sType)) {
                // Breakout above yesterday's high confirms the trend continuation.
                double trigger = dec.entryTriggerPrice;
                if (trigger > 0 && price < trigger) {
                    dec.triggerNotMet = true;
                    dec.rejectReason = String.format(
                        "TRIGGER-WAIT: $%.2f hasn't broken yesterday's high $%.2f", price, trigger);
                }
            }

            if (dec.triggerNotMet) {
                writeScanLog("[⏳ TRIGGER-WAIT] " + ticker + " | " + strategy.id
                    + " | Score=" + dec.totalScore + "/12 | " + dec.rejectReason);
            }
        }

        return dec;
    }

    private static TradeDecision scoreMomentumBreakout(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Volume score (max 3): high volume confirms the breakout
        if      (d.rvol >= 3.0) dec.volumeScore = 3;
        else if (d.rvol >= 2.0) dec.volumeScore = 2;
        else if (d.rvol >= 1.5) dec.volumeScore = 1;

        // Trend score (max 3): stock must be in an established uptrend
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.hasSMA50  && d.maCrossoverUp)    dec.trendScore += 1;

        // Momentum score (max 3): RSI in breakout zone + price above VWAP
        if (d.rsi >= 55 && d.rsi <= 80) dec.momentumScore += 2;
        if (d.vwapPct >= 0.0)            dec.momentumScore += 1;

        // Setup score (max 3): today's price action confirms the breakout
        if      (d.todayChangePct >= 2.0) dec.setupScore += 2;
        else if (d.todayChangePct >= 1.0) dec.setupScore += 1;
        if      (d.cci >= 50)             dec.setupScore += 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 3.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 9.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice,
            d.currentPrice + (d.currentPrice - dec.suggestedStopLoss) * atrM > d.currentPrice
                ? d.currentPrice + (d.currentPrice - dec.suggestedStopLoss) * atrM
                : d.currentPrice * (1 + tpPct / 100));

        double upside_breakout = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;
        writeScanLog("[SCORE|BREAKOUT] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.maCrossoverUp    ? ",cross↑" : "") + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                (d.vwapPct >= 0.0 ? ",abvVWAP" : ",blwVWAP") + ")" +
            " Setup=" + dec.setupScore +
                "(chg=" + String.format("%+.1f%%", d.todayChangePct) +
                ",CCI=" + String.format("%.0f", d.cci) + ")" +
            " R30=$" + String.format("%.2f", d.resistance30d) +
                "(upside=" + String.format("%.1f%%", upside_breakout) + ")" +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scorePullback(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Trend score (max 3): uptrend must be intact for pullback to be valid
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.hasSMA50  && d.maCrossoverUp)    dec.trendScore += 1;

        // Setup score (max 3): price closeness to VWAP = quality of pullback level
        double absVwapPct = Math.abs(d.vwapPct);
        if      (absVwapPct <= 0.3) dec.setupScore = 3;
        else if (absVwapPct <= 0.8) dec.setupScore = 2;
        else if (absVwapPct <= 2.0) dec.setupScore = 1;

        // Momentum score (max 3): RSI cooling off + CCI not overextended
        if      (d.rsi >= 40 && d.rsi <= 62) dec.momentumScore += 2;
        else if (d.rsi >= 35 && d.rsi <= 68) dec.momentumScore += 1;
        if      (d.cci >= 20 && d.cci <= 120) dec.momentumScore += 1;

        // Volume score (max 3): drying volume confirms a healthy pullback (not panic selling)
        if      (d.rvol >= 0.5 && d.rvol <= 1.3) dec.volumeScore = 3;
        else if (d.rvol >= 0.3 && d.rvol <  1.8) dec.volumeScore = 2;
        else if (d.rvol >  0)                     dec.volumeScore = 1;

        // Swing tightener: penalise stocks that are NOT actually pulling back (up strongly = not a pullback)
        if (d.todayChangePct > 1.5) {
            dec.setupScore = Math.max(0, dec.setupScore - 1);
        }

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 3.5);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 10.5);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy only when price reclaims VWAP/typical-price support
        double vwapLevel = d.typicalPrice > 0 ? d.typicalPrice : d.currentPrice;
        dec.entryTriggerPrice = (d.currentPrice <= vwapLevel)
            ? vwapLevel * 1.003   // price at/below VWAP: wait for 0.3% reclaim
            : d.currentPrice * 1.002; // price near VWAP: small 0.2% confirmation buffer

        double upside_pullback = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;
        writeScanLog("[SCORE|PULLBACK] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x,drying=" + (d.rvol >= 0.5 && d.rvol <= 1.3 ? "YES" : "no") + ")" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.maCrossoverUp    ? ",cross↑" : "") + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                ",CCI=" + String.format("%.0f", d.cci) + ")" +
            " Setup=" + dec.setupScore +
                "(VWAP=" + String.format("%.2f%%", Math.abs(d.vwapPct)) + "away,chg=" + String.format("%+.1f%%", d.todayChangePct) + ")" +
            " R30=$" + String.format("%.2f", d.resistance30d) +
                "(upside=" + String.format("%.1f%%", upside_pullback) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scoreTrendContinuation(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Trend score (max 3): full SMA alignment = strongest signal
        if (d.hasSMA200 && d.priceAboveSMA200 && d.hasSMA50 && d.maCrossoverUp && d.priceAboveSMA50) {
            dec.trendScore = 3;
        } else if (d.hasSMA50 && d.maCrossoverUp && d.priceAboveSMA50) {
            dec.trendScore = 2;
        } else if (d.hasSMA200 && d.priceAboveSMA200) {
            dec.trendScore = 1;
        }

        // Momentum score (max 3): RSI in healthy trend zone, not overbought
        if      (d.rsi >= 58 && d.rsi <= 72) dec.momentumScore += 2;
        else if (d.rsi >= 50 && d.rsi <= 78) dec.momentumScore += 1;
        if      (d.cci >= 50 && d.cci <= 200) dec.momentumScore += 1;

        // Volume score (max 3): confirms trend participation
        if      (d.rvol >= 2.0) dec.volumeScore = 3;
        else if (d.rvol >= 1.5) dec.volumeScore = 2;
        else if (d.rvol >= 1.1) dec.volumeScore = 1;

        // Setup score (max 3): trend strength + moderate daily momentum (not blowoff)
        if (d.hasSMA50 && d.sma50 > 0) {
            double aboveSMA50Pct = ((d.currentPrice - d.sma50) / d.sma50) * 100;
            if      (aboveSMA50Pct >= 5.0) dec.setupScore += 2;
            else if (aboveSMA50Pct >= 2.0) dec.setupScore += 1;
        }
        if (d.todayChangePct >= 0.5 && d.todayChangePct <= 3.0) dec.setupScore += 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 3.5);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 13.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy only if price pushes 0.5% above last close — confirms momentum is real
        dec.entryTriggerPrice = d.currentPrice * 1.005;

        double abvSMA50Pct = (d.hasSMA50 && d.sma50 > 0)
            ? ((d.currentPrice - d.sma50) / d.sma50) * 100 : 0;
        double upside_trend = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;
        writeScanLog("[SCORE|TREND] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.maCrossoverUp    ? ",cross↑"  : "") +
                (d.hasSMA50  && d.currentPrice > d.sma50 ? ",abvSMA50" : "") + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                ",CCI=" + String.format("%.0f", d.cci) + ")" +
            " Setup=" + dec.setupScore +
                "(chg=" + String.format("%+.1f%%", d.todayChangePct) +
                ",above50=" + String.format("%.1f%%", abvSMA50Pct) + ")" +
            " R30=$" + String.format("%.2f", d.resistance30d) +
                "(upside=" + String.format("%.1f%%", upside_trend) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision score52WeekHighMomentum(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Trend score (max 3): price must be in an established uptrend
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.hasSMA50  && d.priceAboveSMA50)  dec.trendScore += 1;

        // Setup score (max 3): how close to the 52W high — the closer, the stronger
        // pctFromWeek52High is negative when below the high (e.g. -3.0 = 3% below)
        if      (d.week52High > 0 && d.pctFromWeek52High >= -1.0) dec.setupScore = 3;
        else if (d.week52High > 0 && d.pctFromWeek52High >= -3.0) dec.setupScore = 2;
        else if (d.week52High > 0 && d.pctFromWeek52High >= -5.0) dec.setupScore = 1;

        // Volume score (max 3): rising volume confirms institutional accumulation near highs
        if      (d.rvol >= 2.0) dec.volumeScore = 3;
        else if (d.rvol >= 1.3) dec.volumeScore = 2;
        else if (d.rvol >= 0.8) dec.volumeScore = 1;

        // Momentum score (max 3): RSI in breakout zone + positive 20-day price momentum
        if      (d.rsi >= 55 && d.rsi <= 75) dec.momentumScore += 2;
        else if (d.rsi >= 50 && d.rsi <= 78) dec.momentumScore += 1;
        if      (d.momentum20d >= 10.0)      dec.momentumScore += 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 6.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 18.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy only on confirmed break above the 52W high
        dec.entryTriggerPrice = d.week52High > 0 ? d.week52High * 1.002 : d.currentPrice * 1.005;

        writeScanLog("[SCORE|52W-HIGH] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.priceAboveSMA50  ? ",abvSMA50" : "") + ")" +
            " Setup=" + dec.setupScore +
                "(52Whi=$" + String.format("%.2f", d.week52High) +
                ",dist=" + String.format("%+.1f%%", d.pctFromWeek52High) + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                ",mom20d=" + String.format("%+.1f%%", d.momentum20d) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scorePullbackMA20(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Trend score (max 3): uptrend must be confirmed before a MA20 pullback is valid
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.hasSMA50  && d.priceAboveSMA50)  dec.trendScore += 1;

        // Setup score (max 3): closeness to MA20 — tighter pullback = better entry quality
        double absSMA20Pct = d.sma20 > 0 ? Math.abs(((d.currentPrice - d.sma20) / d.sma20) * 100) : 99;
        if      (absSMA20Pct <= 0.5) dec.setupScore = 3;
        else if (absSMA20Pct <= 1.5) dec.setupScore = 2;
        else if (absSMA20Pct <= 3.0) dec.setupScore = 1;

        // Momentum score (max 3): RSI cooling off into 40-55 zone = healthy reset
        if      (d.rsi >= 40 && d.rsi <= 55) dec.momentumScore += 2;
        else if (d.rsi >= 35 && d.rsi <= 60) dec.momentumScore += 1;
        if      (d.cci >= -50 && d.cci <= 100) dec.momentumScore += 1;

        // Volume score (max 3): drying volume on the pullback = sellers exhausted
        if      (d.rvol >= 0.5 && d.rvol <= 1.3) dec.volumeScore = 3;
        else if (d.rvol >= 0.3 && d.rvol <  1.8) dec.volumeScore = 2;
        else if (d.rvol >  0)                     dec.volumeScore = 1;

        // Penalise stocks that are up strongly today — not a real pullback to MA20
        if (d.todayChangePct > 2.0) {
            dec.setupScore = Math.max(0, dec.setupScore - 1);
        }

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 4.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 12.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy only when price closes back above MA20 with a small buffer
        dec.entryTriggerPrice = d.sma20 > 0 ? d.sma20 * 1.003 : d.currentPrice * 1.003;

        double upside_ma20 = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;
        writeScanLog("[SCORE|PULLBACK-MA20] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x,drying=" + (d.rvol >= 0.5 && d.rvol <= 1.3 ? "YES" : "no") + ")" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.priceAboveSMA50  ? ",abvSMA50" : "") + ")" +
            " Setup=" + dec.setupScore +
                "(SMA20=$" + String.format("%.2f", d.sma20) +
                ",dist=" + String.format("%.2f%%", absSMA20Pct) + ",chg=" + String.format("%+.1f%%", d.todayChangePct) + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                ",CCI=" + String.format("%.0f", d.cci) + ")" +
            " R30=$" + String.format("%.2f", d.resistance30d) +
                "(upside=" + String.format("%.1f%%", upside_ma20) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scoreVolumeBreakout(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Volume score (max 3): must be a genuine volume surge to confirm the breakout
        if      (d.rvol >= 3.0) dec.volumeScore = 3;
        else if (d.rvol >= 2.0) dec.volumeScore = 2;
        else if (d.rvol >= 1.5) dec.volumeScore = 1;

        // Breakout score (max 3): price relative to 30-day resistance level
        if (d.resistance30d > 0) {
            double breakPct = ((d.currentPrice - d.resistance30d) / d.resistance30d) * 100;
            if      (breakPct >= 0 && breakPct <= 3.0) dec.setupScore = 3; // clean break, not overextended
            else if (breakPct >= -0.5)                 dec.setupScore = 2; // right at resistance
            else if (breakPct >= -1.5)                 dec.setupScore = 1; // approaching resistance
        }

        // Trend score (max 3): base trend must support the breakout
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.hasSMA50  && d.priceAboveSMA50)  dec.trendScore += 1;

        // Momentum score (max 3): RSI with energy but not overbought + strong day
        if      (d.rsi >= 50 && d.rsi <= 70) dec.momentumScore += 2;
        else if (d.rsi >= 45 && d.rsi <= 75) dec.momentumScore += 1;
        if      (d.todayChangePct >= 1.0)    dec.momentumScore += 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 5.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 15.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy only on confirmed break above the 30-day resistance
        dec.entryTriggerPrice = d.resistance30d > 0 ? d.resistance30d * 1.005 : d.currentPrice * 1.005;

        double breakPct = d.resistance30d > 0 ? ((d.currentPrice - d.resistance30d) / d.resistance30d) * 100 : 0;
        writeScanLog("[SCORE|VOL-BREAKOUT] " + ticker + " | " + strategy.id +
            " | Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " Breakout=" + dec.setupScore +
                "(R30=$" + String.format("%.2f", d.resistance30d) +
                ",breakPct=" + String.format("%+.1f%%", breakPct) + ")" +
            " Trend=" + dec.trendScore +
                "(" + (d.hasSMA200 && d.priceAboveSMA200 ? "abvSMA200" : "blwSMA200") +
                (d.hasSMA50  && d.priceAboveSMA50  ? ",abvSMA50" : "") + ")" +
            " Momentum=" + dec.momentumScore +
                "(RSI=" + String.format("%.0f", d.rsi) +
                ",chg=" + String.format("%+.1f%%", d.todayChangePct) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scoreStrongTrend(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // Trend score (max 3): stock must already be in a confirmed uptrend above SMA50
        if (d.hasSMA50 && d.priceAboveSMA50) dec.trendScore += 2;
        if (d.maCrossoverUp)                  dec.trendScore += 1; // SMA20 > SMA50 = golden alignment

        // Volume score (max 3): must confirm trend with real volume — tightest filter
        if      (d.rvol >= 2.5) dec.volumeScore = 3;
        else if (d.rvol >= 1.5) dec.volumeScore = 2;
        else if (d.rvol >= 1.0) dec.volumeScore = 1;

        // Momentum score (max 3): price near its 20-day high + RSI in healthy zone
        if (d.high20d > 0) {
            double pctFromHigh20 = ((d.currentPrice - d.high20d) / d.high20d) * 100;
            if      (pctFromHigh20 >= -3.0) dec.momentumScore += 2; // within 3% of 20d high
            else if (pctFromHigh20 >= -7.0) dec.momentumScore += 1; // within 7%
        }
        if (d.rsi >= 50 && d.rsi <= 72) dec.momentumScore += 1;

        // Setup score (max 3): room to run before hitting resistance ceiling
        double upsideToResistance = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;
        if      (upsideToResistance >= 8.0) dec.setupScore = 3;
        else if (upsideToResistance >= 5.0) dec.setupScore = 2;
        else if (upsideToResistance >= 3.0) dec.setupScore = 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 5.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 15.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.0);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: buy ONLY if price breaks above yesterday's high (momentum confirmation)
        dec.entryTriggerPrice = d.prevHigh > 0 ? d.prevHigh * 1.001 : d.currentPrice * 1.005;

        double pctFromHigh20 = d.high20d > 0 ? ((d.currentPrice - d.high20d) / d.high20d) * 100 : 0;
        writeScanLog("[SCORE|STRONG-TREND] " + ticker + " | " + strategy.id +
            " | Trend=" + dec.trendScore +
                "(abvSMA50=" + (d.hasSMA50 && d.priceAboveSMA50 ? "Y" : "N") +
                ",SMA20>50=" + (d.maCrossoverUp ? "Y" : "N") + ")" +
            " Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " Momentum=" + dec.momentumScore +
                "(near20dHi=" + String.format("%+.1f%%", pctFromHigh20) +
                ",RSI=" + String.format("%.0f", d.rsi) + ")" +
            " Setup=" + dec.setupScore +
                "(upside=" + String.format("%.1f%%", upsideToResistance) + ")" +
            " Trigger=$" + String.format("%.2f", dec.entryTriggerPrice) +
            " total=" + dec.totalScore + "/12");
        return dec;
    }

    private static TradeDecision scoreInstitutionalSwing(String ticker, AgentConfig strategy, IndicatorData d) {
        TradeDecision dec = new TradeDecision();
        dec.action = "BUY";

        // ── Daily Recommendation Limit ─────────────────────────────────────────────────
        String today = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_LOCAL_DATE);
        synchronized (LOCK) {
            if (!today.equals(institutionalSwingLastDate)) {
                institutionalSwingLastDate = today;
                institutionalSwingDailyCount = 0;
            }
            if (institutionalSwingDailyCount >= INSTITUTIONAL_SWING_DAILY_LIMIT) {
                dec.rejectReason = String.format("Daily limit reached: %d/%d recommendations today",
                    institutionalSwingDailyCount, INSTITUTIONAL_SWING_DAILY_LIMIT);
                writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
                return dec;
            }
        }

        // ── Strict Entry Filters ─────────────────────────────────────────────────────
        // Filter 1: Must be above SMA50
        if (!d.hasSMA50 || !d.priceAboveSMA50) {
            dec.rejectReason = "SMA50 filter: price not above SMA50";
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        // Filter 2: Relative Strength vs SPY (20-day momentum as proxy)
        // Using momentum20d as RS proxy - must be positive and > 2%
        if (d.momentum20d < 2.0) {
            dec.rejectReason = String.format("RS filter: 20d momentum %.1f%% < 2.0%%", d.momentum20d);
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        // Filter 3: Volume higher than usual
        double rvolMin = getDoubleFilter(strategy, "rvolMin", 1.5);
        if (d.rvol < rvolMin) {
            dec.rejectReason = String.format("Volume filter: RVOL %.2fx < %.2fx", d.rvol, rvolMin);
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        // Filter 4: Market regime must be NORMAL (HEALTHY)
        if (lastKnownRegime != RegimeLevel.HEALTHY) {
            dec.rejectReason = "Market regime filter: not HEALTHY (current=" + lastKnownRegime + ")";
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        // ── Scoring Components ───────────────────────────────────────────────────────
        // Trend score (max 3): SMA alignment
        if (d.hasSMA200 && d.priceAboveSMA200) dec.trendScore += 2;
        if (d.maCrossoverUp) dec.trendScore += 1;

        // Volume score (max 3): institutional volume confirmation
        if      (d.rvol >= 3.0) dec.volumeScore = 3;
        else if (d.rvol >= 2.0) dec.volumeScore = 2;
        else if (d.rvol >= 1.5) dec.volumeScore = 1;

        // Relative Strength score (max 3): 20-day momentum
        if      (d.momentum20d >= 15.0) dec.momentumScore = 3;
        else if (d.momentum20d >= 10.0) dec.momentumScore = 2;
        else if (d.momentum20d >= 5.0)  dec.momentumScore = 1;

        // Setup score (max 3): RSI in healthy zone, not overbought
        if      (d.rsi >= 50 && d.rsi <= 70) dec.setupScore = 3;
        else if (d.rsi >= 45 && d.rsi <= 75) dec.setupScore = 2;
        else if (d.rsi >= 40 && d.rsi <= 80) dec.setupScore = 1;

        dec.totalScore = dec.volumeScore + dec.trendScore + dec.momentumScore + dec.setupScore;

        // ── Institutional Flow Layer: Fundamental & Catalyst Scoring ─────────────────
        InstitutionalFlowLayer.enrichAndScore(d, dec, true);

        // Apply fundamental and catalyst minimum filters
        int fundamentalMin = (int) getDoubleFilter(strategy, "fundamentalScoreMin", 5);
        int catalystMin = (int) getDoubleFilter(strategy, "catalystScoreMin", 4);

        if (dec.fundamentalScore < fundamentalMin) {
            dec.rejectReason = String.format("Fundamental filter: score %d < %d", dec.fundamentalScore, fundamentalMin);
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        if (dec.catalystScore < catalystMin) {
            dec.rejectReason = String.format("Catalyst filter: score %d < %d", dec.catalystScore, catalystMin);
            writeScanLog("[INST-SWING|REJECT] " + ticker + " | " + strategy.id + " | " + dec.rejectReason);
            return dec;
        }

        // ── Risk Management ─────────────────────────────────────────────────────────
        dec.entryPrice = d.currentPrice;
        double slPct = getDoubleRisk(strategy, "stopLossPct", 4.0);
        double tpPct = getDoubleRisk(strategy, "takeProfitPct", 12.0);
        double atrM  = getDoubleRisk(strategy, "atrMultiplier", 2.5);
        dec.suggestedStopLoss   = calculateFinalStopPrice(d.currentPrice, d.atr, atrM, slPct);
        dec.suggestedTakeProfit = applyMinTakeProfit(d.currentPrice, d.currentPrice * (1 + tpPct / 100));

        // Entry trigger: immediate entry (all filters already validated)
        dec.entryTriggerPrice = d.currentPrice;

        double upsideToResistance = (d.resistance30d > 0 && d.currentPrice > 0)
            ? ((d.resistance30d - d.currentPrice) / d.currentPrice) * 100 : 0;

        writeScanLog("[SCORE|INST-SWING] " + ticker + " | " + strategy.id +
            " | Trend=" + dec.trendScore +
                "(abvSMA50=" + (d.hasSMA50 && d.priceAboveSMA50 ? "Y" : "N") +
                ",abvSMA200=" + (d.hasSMA200 && d.priceAboveSMA200 ? "Y" : "N") + ")" +
            " Volume=" + dec.volumeScore + "(RVOL=" + String.format("%.1f", d.rvol) + "x)" +
            " RS=" + dec.momentumScore +
                "(mom20d=" + String.format("%+.1f%%", d.momentum20d) + ")" +
            " Setup=" + dec.setupScore +
                "(RSI=" + String.format("%.0f", d.rsi) + ")" +
            " Fund=" + dec.fundamentalScore +
            " Cat=" + dec.catalystScore +
            " Conv=" + String.format("%.1f", dec.finalConviction) +
            " upside=" + String.format("%.1f%%", upsideToResistance) +
            " total=" + dec.totalScore + "/12");

        // Increment daily counter on successful score
        synchronized (LOCK) {
            institutionalSwingDailyCount++;
            writeScanLog("[INST-SWING] Daily count: " + institutionalSwingDailyCount + "/" + INSTITUTIONAL_SWING_DAILY_LIMIT);
        }

        return dec;
    }

    // Public wrapper for debugging - calls the private analyzeStock
    public static TradeDecision analyzeStockPublic(String ticker, AgentConfig agent) {
        return analyzeStock(ticker, agent);
    }

    // Public wrapper for testing — accepts pre-fetched JSON (mirrors real scan loop behaviour)
    public static TradeDecision analyzeStockPublic(String ticker, AgentConfig agent, String json) {
        return analyzeStock(ticker, agent, json);
    }

    /** Public wrapper for testing — runs computeIndicators() on pre-fetched JSON */
    public static IndicatorData computeIndicatorsPublic(String ticker, String rawJson) {
        return computeIndicators(ticker, rawJson);
    }

    /** Public wrapper for testing — runs scoreStrategyForTicker() directly */
    public static TradeDecision scoreStrategyForTickerPublic(String ticker, AgentConfig strategy, IndicatorData data) {
        return scoreStrategyForTicker(ticker, strategy, data);
    }

    /**
     * Public helper for testing — loads a single AgentConfig from an absolute or relative JSON file path.
     * Does NOT register the agent into systemState; purely for inspection and testing.
     */
    public static AgentConfig loadAgentConfigFromFile(java.nio.file.Path jsonFile) {
        try {
            JsonNode root = JSON.readTree(jsonFile.toFile());
            AgentConfig agent = new AgentConfig();
            agent.id           = root.path("id").asText("");
            agent.name         = root.path("name").asText(agent.id);
            agent.sourceFile   = jsonFile.toString();
            agent.type         = root.path("type").asText("EVOLVED");
            agent.generation   = root.path("generation").asInt(0);
            agent.parentId     = root.path("parentId").asText(null);
            agent.lastModified = root.path("lastModified").asText(null);
            agent.locked       = root.path("locked").asBoolean(false);
            agent.disabled     = root.path("disabled").asBoolean(false);
            agent.masterStrategy = root.path("masterStrategy").asBoolean(false);
            agent.strategyType   = root.path("strategyType").asText(null);
            JsonNode rm = root.get("riskManagement");
            if (rm != null) {
                Iterator<String> fields = rm.fieldNames();
                while (fields.hasNext()) {
                    String f = fields.next();
                    JsonNode val = rm.get(f);
                    if (val.isNumber()) agent.riskManagement.put(f, val.doubleValue());
                    else agent.riskManagement.put(f, val.asText());
                }
            }
            JsonNode ef = root.get("entryFilters");
            if (ef != null) {
                Iterator<String> fields = ef.fieldNames();
                while (fields.hasNext()) {
                    String f = fields.next();
                    JsonNode val = ef.get(f);
                    if (val.isNumber()) agent.entryFilters.put(f, val.doubleValue());
                    else if (val.isBoolean()) agent.entryFilters.put(f, val.booleanValue());
                    else agent.entryFilters.put(f, val.asText());
                }
            }
            return agent;
        } catch (Exception e) {
            System.err.println("[loadAgentConfigFromFile] Error reading " + jsonFile + ": " + e.getMessage());
            return null;
        }
    }

    // Public wrapper for debugging - calls the private executeTrade
    public static Trade executeTradePublic(AgentConfig agent, String ticker, TradeDecision decision) {
        return executeTrade(agent, ticker, decision);
    }

    /**
     * Full analysis report for a single symbol - returns detailed breakdown of all filters
     */
    public static class AnalysisReport {
        public String ticker;
        public String agentId;
        public boolean passed;
        public String failedAt;
        
        // Price data
        public double currentPrice;
        public double sma20;
        public double sma200;
        
        // RSI
        public double rsi;
        public double rsiMin;
        public double rsiMax;
        public boolean rsiPassed;
        
        // RVOL
        public double rvol;
        public double rvolMin;
        public boolean rvolPassed;
        
        // CMF (Chaikin Money Flow)
        public double cmf;
        public boolean cmfPositiveRequired;
        public boolean cmfPassed;
        
        // VWAP
        public double vwap;
        public int vwapHoldBars;
        public int vwapHoldBarsRequired;
        public boolean vwapPassed;
        
        // SMA200
        public boolean sma200Required;
        public boolean sma200Passed;
        
        // Trade recommendation
        public String action;
        public double stopLoss;
        public double takeProfit;
        public double riskPct;
        public double rewardPct;
        public double riskReward;
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
            sb.append(String.format("║  FULL ANALYSIS REPORT: %-37s ║\n", ticker));
            sb.append(String.format("║  Agent: %-52s ║\n", agentId));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║  Current Price: $%-43.2f ║\n", currentPrice));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            
            // RSI
            String rsiStatus = rsiPassed ? "✅ PASS" : "❌ FAIL";
            sb.append(String.format("║  RSI: %.2f  (Range: %.2f - %.2f)  %s              ║\n", 
                rsi, rsiMin, rsiMax, rsiStatus));
            
            // Price vs SMA20
            String sma20Status = currentPrice > sma20 ? "✅ PASS" : "❌ FAIL";
            sb.append(String.format("║  Price > SMA20: $%.2f > $%.2f  %s               ║\n", 
                currentPrice, sma20, sma20Status));
            
            // RVOL
            String rvolStatus = rvolPassed ? "✅ PASS" : "❌ FAIL";
            sb.append(String.format("║  RVOL: %.2f  (Min: %.2f)  %s                      ║\n", 
                rvol, rvolMin, rvolStatus));
            
            // CMF
            String cmfStatus = cmfPassed ? "✅ PASS" : "❌ FAIL";
            String cmfReq = cmfPositiveRequired ? "> 0 required" : "not required";
            sb.append(String.format("║  CMF: %.3f  (%s)  %s                  ║\n", 
                cmf, cmfReq, cmfStatus));
            
            // VWAP Hold
            String vwapStatus = vwapPassed ? "✅ PASS" : "❌ FAIL";
            sb.append(String.format("║  VWAP Hold: %d bars  (Min: %d bars)  %s              ║\n", 
                vwapHoldBars, vwapHoldBarsRequired, vwapStatus));
            
            // SMA200
            if (sma200Required) {
                String sma200Status = sma200Passed ? "✅ PASS" : "❌ FAIL";
                sb.append(String.format("║  Price > SMA200: $%.2f > $%.2f  %s            ║\n", 
                    currentPrice, sma200, sma200Status));
            }
            
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            
            if (passed) {
                sb.append("║  🚀 RESULT: ALL FILTERS PASSED - TRADE SIGNAL!              ║\n");
                sb.append("╠══════════════════════════════════════════════════════════════╣\n");
                sb.append(String.format("║  📍 Entry: $%-48.2f ║\n", currentPrice));
                sb.append(String.format("║  🛑 Stop Loss: $%.2f (-%.1f%%)                              ║\n", stopLoss, riskPct));
                sb.append(String.format("║  🎯 Take Profit: $%.2f (+%.1f%%)                           ║\n", takeProfit, rewardPct));
                sb.append(String.format("║  📊 Risk/Reward: 1:%.1f                                     ║\n", riskReward));
            } else {
                sb.append(String.format("║  ❌ RESULT: FILTERED OUT at: %-31s ║\n", failedAt));
            }
            
            sb.append("╚══════════════════════════════════════════════════════════════╝\n");
            return sb.toString();
        }
    }

    /**
     * Analyze a single symbol and return full detailed report
     */
    public static AnalysisReport analyzeStockWithReport(String ticker, AgentConfig agent) {
        AnalysisReport report = new AnalysisReport();
        report.ticker = ticker;
        report.agentId = agent.id;
        report.passed = false;
        report.failedAt = "Unknown";
        
        try {
            // Fetch current price data
            DataFetcher.setTicker(ticker);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) {
                report.failedAt = "No data available";
                return report;
            }
            
            List<Double> prices = PriceJsonParser.extractClosingPrices(json);
            if (prices == null || prices.size() < 20) {
                report.failedAt = "Insufficient price data";
                return report;
            }
            
            report.currentPrice = prices.get(prices.size() - 1);
            
            // Extract high/low prices and volumes
            List<Double> highPrices = PriceJsonParser.extractHighPrices(json);
            List<Double> lowPrices = PriceJsonParser.extractLowPrices(json);
            List<Double> volumes = PriceJsonParser.extractVolumes(json);
            
            // Calculate RSI
            List<Double> rsiList = RSI.calculateRSI(prices, 14);
            report.rsi = (rsiList != null && !rsiList.isEmpty()) ? rsiList.get(rsiList.size() - 1) : 50.0;
            report.rsiMin = getDoubleFilter(agent, "rsiMin", 30);
            report.rsiMax = getDoubleFilter(agent, "rsiMax", 70);
            report.rsiPassed = report.rsi >= report.rsiMin && report.rsi <= report.rsiMax;
            
            if (!report.rsiPassed) {
                report.failedAt = "RSI out of range";
                return report;
            }
            
            // Calculate SMA20
            List<Double> sma20List = TechnicalAnalysisModel.calculateSMA(prices, 20);
            report.sma20 = (sma20List != null && !sma20List.isEmpty()) ? sma20List.get(sma20List.size() - 1) : report.currentPrice;
            
            if (report.currentPrice <= report.sma20) {
                report.failedAt = "Price below SMA20";
                return report;
            }
            
            // Calculate RVOL
            if (volumes != null && volumes.size() >= 20) {
                double avgVolume = volumes.subList(Math.max(0, volumes.size() - 20), volumes.size() - 1)
                        .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                double currentVolume = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                report.rvol = avgVolume > 0 ? currentVolume / avgVolume : 0;
            }
            report.rvolMin = getDoubleFilter(agent, "rvolMin", 0);
            report.rvolPassed = report.rvol >= report.rvolMin;
            
            if (hasFilter(agent, "rvolMin") && !report.rvolPassed) {
                report.failedAt = "RVOL too low";
                return report;
            }
            
            // Calculate CMF
            report.cmfPositiveRequired = getBooleanFilter(agent, "cmfPositiveRequired", false);
            if (highPrices != null && lowPrices != null && volumes != null && 
                highPrices.size() >= 20 && volumes.size() >= 20) {
                List<Long> volumesLong = new ArrayList<>();
                for (Double v : volumes) {
                    volumesLong.add(v != null ? v.longValue() : 0L);
                }
                List<Double> cmfList = CMF.calculateCMF(highPrices, lowPrices, prices, volumesLong, 20);
                if (cmfList != null && !cmfList.isEmpty()) {
                    Double cmfVal = cmfList.get(cmfList.size() - 1);
                    report.cmf = cmfVal != null ? cmfVal : 0;
                }
            }
            report.cmfPassed = !report.cmfPositiveRequired || report.cmf > 0;
            
            if (report.cmfPositiveRequired && !report.cmfPassed) {
                report.failedAt = "CMF negative (distribution)";
                return report;
            }
            
            // Calculate SMA200
            report.sma200Required = getBooleanFilter(agent, "sma200Required", false);
            if (report.sma200Required && prices.size() >= 200) {
                List<Double> sma200List = TechnicalAnalysisModel.calculateSMA(prices, 200);
                if (sma200List != null && !sma200List.isEmpty()) {
                    report.sma200 = sma200List.get(sma200List.size() - 1);
                }
            }
            report.sma200Passed = !report.sma200Required || report.currentPrice > report.sma200;
            
            if (report.sma200Required && !report.sma200Passed) {
                report.failedAt = "Price below SMA200";
                return report;
            }
            
            // Calculate VWAP hold bars
            report.vwapHoldBarsRequired = (int) getDoubleFilter(agent, "vwapHoldBars", 0);
            if (report.vwapHoldBarsRequired > 0 && highPrices != null && lowPrices != null) {
                int dataSize = Math.min(Math.min(highPrices.size(), lowPrices.size()), prices.size());
                report.vwapHoldBars = 0;
                for (int i = dataSize - 1; i >= 0 && i >= dataSize - 10; i--) {
                    double barHigh = highPrices.get(i);
                    double barLow = lowPrices.get(i);
                    double barClose = prices.get(i);
                    double barTypicalPrice = (barHigh + barLow + barClose) / 3.0;
                    report.vwap = barTypicalPrice; // Last VWAP
                    
                    if (barClose > barTypicalPrice) {
                        report.vwapHoldBars++;
                    } else {
                        break;
                    }
                }
            }
            report.vwapPassed = report.vwapHoldBarsRequired == 0 || report.vwapHoldBars >= report.vwapHoldBarsRequired;
            
            if (!report.vwapPassed) {
                report.failedAt = "VWAP hold too short";
                return report;
            }
            
            // ALL PASSED!
            report.passed = true;
            report.failedAt = null;
            report.action = "BUY";
            
            // Calculate stop loss and take profit
            double stopLossPct = getDoubleRisk(agent, "stopLossPct", 3.0);
            double takeProfitPct = getDoubleRisk(agent, "takeProfitPct", 6.0);
            report.stopLoss = report.currentPrice * (1 - stopLossPct / 100);
            report.takeProfit = report.currentPrice * (1 + takeProfitPct / 100);
            report.riskPct = stopLossPct;
            report.rewardPct = takeProfitPct;
            report.riskReward = takeProfitPct / stopLossPct;
            
            return report;
            
        } catch (Exception e) {
            report.failedAt = "Error: " + e.getMessage();
            return report;
        }
    }

    private static TradeDecision analyzeStock(String ticker, AgentConfig agent) {
        try {
            DataFetcher.setTicker(ticker);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) return null;
            return analyzeStock(ticker, agent, json);
        } catch (Exception e) {
            return null;
        }
    }

    // Overload that accepts pre-fetched JSON — used by runAllAgents() ticker-centric loop
    // to avoid making 65x redundant API calls for the same ticker.
    private static TradeDecision analyzeStock(String ticker, AgentConfig agent, String json) {
        try {
            List<Double> prices = PriceJsonParser.extractClosingPrices(json);
            if (prices == null || prices.size() < 20) return null;

            double currentPrice = prices.get(prices.size() - 1);
            
            // Extract high/low prices if available (for CCI, ATR calculations)
            List<Double> highPrices = PriceJsonParser.extractHighPrices(json);
            List<Double> lowPrices = PriceJsonParser.extractLowPrices(json);
            List<Double> volumes = PriceJsonParser.extractVolumes(json);
            
            // Calculate base indicators
            List<Double> rsiList = RSI.calculateRSI(prices, 14);
            double rsi = (rsiList != null && !rsiList.isEmpty()) ? rsiList.get(rsiList.size() - 1) : 50.0;
            List<Double> sma20List = TechnicalAnalysisModel.calculateSMA(prices, 20);
            double sma20Current = (sma20List != null && !sma20List.isEmpty()) ? sma20List.get(sma20List.size() - 1) : currentPrice;
            
            // Get agent filters
            double rsiMin = getDoubleFilter(agent, "rsiMin", 30);
            double rsiMax = getDoubleFilter(agent, "rsiMax", 70);
            boolean sma200Required = getBooleanFilter(agent, "sma200Required", false);
            
            TradeDecision decision = new TradeDecision();
            decision.shouldTrade = false;
            
            // === CORE RSI CHECK ===
            if (rsi < rsiMin || rsi > rsiMax) {
                decision.rejectReason = "RSI=" + String.format("%.1f", rsi) + " not in [" + rsiMin + "," + rsiMax + "]";
                return decision;
            }
            
            // === PRICE ABOVE SMA CHECK ===
            if (currentPrice <= sma20Current) {
                decision.rejectReason = "Price=$" + String.format("%.2f", currentPrice) + " <= SMA20=$" + String.format("%.2f", sma20Current);
                return decision;
            }
            
            // === OPTIONAL CCI FILTER (if present in agent config) ===
            if (hasFilter(agent, "cciMin") || hasFilter(agent, "cciMax") || hasFilter(agent, "cciStdDev")) {
                if (highPrices != null && lowPrices != null && highPrices.size() >= 20) {
                    List<Double> cciList = CCI.calculateCCI(highPrices, lowPrices, prices, 20);
                    if (cciList != null && !cciList.isEmpty()) {
                        Double cci = cciList.get(cciList.size() - 1);
                        if (cci != null) {
                            double cciMin = getDoubleFilter(agent, "cciMin", -100);
                            double cciMax = getDoubleFilter(agent, "cciMax", 100);
                            
                            // If cciStdDev is set, use it to narrow the range
                            if (hasFilter(agent, "cciStdDev")) {
                                double cciStdDev = getDoubleFilter(agent, "cciStdDev", 100);
                                cciMin = Math.max(cciMin, -cciStdDev);
                                cciMax = Math.min(cciMax, cciStdDev);
                            }
                            
                            if (cci < cciMin || cci > cciMax) {
                                decision.rejectReason = "CCI=" + String.format("%.1f", cci) + " not in [" + cciMin + "," + cciMax + "]";
                                return decision;
                            }
                        }
                    }
                }
            }
            
            // === OPTIONAL ATR FILTER (volatility check) ===
            if (hasFilter(agent, "atrMultiplier") || hasFilter(agent, "atrMin") || hasFilter(agent, "atrMax")) {
                if (highPrices != null && lowPrices != null && highPrices.size() >= 14) {
                    List<Double> atrList = ATR.calculateATR(highPrices, lowPrices, prices, 14);
                    if (atrList != null && !atrList.isEmpty()) {
                        Double atr = atrList.get(atrList.size() - 1);
                        if (atr != null && atr > 0) {
                            double atrPct = (atr / currentPrice) * 100;
                            
                            // Check ATR percentage bounds if specified
                            double atrMin = getDoubleFilter(agent, "atrMin", 0);
                            double atrMax = getDoubleFilter(agent, "atrMax", 10);
                            
                            if (atrPct < atrMin || atrPct > atrMax) {
                                decision.rejectReason = "ATR%=" + String.format("%.2f", atrPct) + " not in [" + atrMin + "," + atrMax + "]";
                                return decision;
                            }
                        }
                    }
                }
            }
            
            // === OPTIONAL RS FILTER (Relative Strength — 20-day price momentum ratio) ===
            // rsMin: 1.05 means currentPrice must be >= 5% above price 20 days ago (stock is a leader)
            if (hasFilter(agent, "rsMin") || hasFilter(agent, "rsMax")) {
                if (prices.size() >= 21) {
                    double price20dAgo = prices.get(prices.size() - 21);
                    if (price20dAgo > 0) {
                        double rs = currentPrice / price20dAgo;
                        double rsMin = getDoubleFilter(agent, "rsMin", 0);
                        double rsMax = getDoubleFilter(agent, "rsMax", 99);
                        if (rs < rsMin || rs > rsMax) {
                            decision.rejectReason = String.format("RS=%.3f not in [%.2f,%.2f] (20d momentum ratio)", rs, rsMin, rsMax);
                            return decision;
                        }
                    }
                }
            }

            // === OPTIONAL RVOL FILTER (relative volume) ===
            if (hasFilter(agent, "rvolMin") || hasFilter(agent, "rvolMax")) {
                if (volumes != null && volumes.size() >= 20) {
                    // Calculate average volume over last 20 days
                    double avgVolume = volumes.subList(Math.max(0, volumes.size() - 20), volumes.size() - 1)
                            .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                    double currentVolume = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                    
                    if (avgVolume > 0) {
                        double rvol = currentVolume / avgVolume;
                        double rvolMin = getDoubleFilter(agent, "rvolMin", 0);
                        double rvolMax = getDoubleFilter(agent, "rvolMax", 10);
                        
                        if (rvol < rvolMin || rvol > rvolMax) {
                            decision.rejectReason = "RVOL=" + String.format("%.2f", rvol) + " not in [" + rvolMin + "," + rvolMax + "]";
                            return decision;
                        }
                    }
                }
            }
            
            // === OPTIONAL VOLUME THRESHOLD ===
            // Supports both "volumeMin" and "volumeThreshold" (AI may suggest either name)
            if (hasFilter(agent, "volumeMin") || hasFilter(agent, "volumeThreshold")) {
                if (volumes != null && !volumes.isEmpty()) {
                    double currentVolume = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                    // Check both possible filter names
                    double volumeMin = getDoubleFilter(agent, "volumeMin", 0);
                    double volumeThreshold = getDoubleFilter(agent, "volumeThreshold", 0);
                    double minRequired = Math.max(volumeMin, volumeThreshold); // Use whichever is set
                    
                    if (currentVolume < minRequired) {
                        decision.rejectReason = "Volume=" + String.format("%.0f", currentVolume) + " < min=" + String.format("%.0f", minRequired);
                        return decision;
                    }
                }
            }
            
            // === CHAIKIN MONEY FLOW (CMF) FILTER - Accumulation vs Distribution ===
            // CMF > 0 = Buying pressure (accumulation) - BULLISH
            // CMF < 0 = Selling pressure (distribution) - BEARISH
            // This filter ensures high RVOL is actually bullish, not distribution
            if (hasFilter(agent, "cmfMin") || hasFilter(agent, "cmfPositiveRequired")) {
                if (highPrices != null && lowPrices != null && volumes != null && 
                    highPrices.size() >= 20 && volumes.size() >= 20) {
                    
                    // Convert volumes to Long for CMF calculation
                    List<Long> volumesLong = new ArrayList<>();
                    for (Double v : volumes) {
                        volumesLong.add(v != null ? v.longValue() : 0L);
                    }
                    
                    List<Double> cmfList = CMF.calculateCMF(highPrices, lowPrices, prices, volumesLong, 20);
                    if (cmfList != null && !cmfList.isEmpty()) {
                        Double cmf = cmfList.get(cmfList.size() - 1);
                        if (cmf != null) {
                            // Check if positive CMF is required (accumulation only)
                            boolean cmfPositiveRequired = getBooleanFilter(agent, "cmfPositiveRequired", false);
                            if (cmfPositiveRequired && cmf <= 0) {
                                decision.rejectReason = "CMF=" + String.format("%.3f", cmf) + " (distribution — negative)";
                                return decision;
                            }
                            
                            // Check minimum CMF threshold
                            double cmfMin = getDoubleFilter(agent, "cmfMin", -1.0);
                            if (cmf < cmfMin) {
                                decision.rejectReason = "CMF=" + String.format("%.3f", cmf) + " < cmfMin=" + cmfMin;
                                return decision;
                            }
                            
                            System.out.println("[AIToolAgent] " + ticker + " CMF PASSED: " + String.format("%.3f", cmf) + " (accumulation/buying pressure)");
                        }
                    }
                }
            }
            
            // === OPTIONAL SMA200 FILTER ===
            if (sma200Required && prices.size() >= 200) {
                List<Double> sma200List = TechnicalAnalysisModel.calculateSMA(prices, 200);
                if (sma200List != null && !sma200List.isEmpty()) {
                    Double sma200 = sma200List.get(sma200List.size() - 1);
                    if (sma200 != null && currentPrice <= sma200) {
                        decision.rejectReason = "Price=$" + String.format("%.2f", currentPrice) + " <= SMA200=$" + String.format("%.2f", sma200);
                        return decision;
                    }
                }
            }
            
            // === OPTIONAL SMA WINDOWS FILTER (Multi-SMA Momentum) ===
            // smaWindows: array of SMA periods, e.g. [50, 100]
            // Logic: Short SMA must be above Long SMA (bullish crossover/momentum)
            // AND price must be above both SMAs
            if (hasFilter(agent, "smaWindows")) {
                int[] smaWindows = getIntArrayFilter(agent, "smaWindows", new int[]{});
                if (smaWindows.length >= 2) {
                    // Sort windows to identify short and long periods
                    java.util.Arrays.sort(smaWindows);
                    int shortPeriod = smaWindows[0];  // e.g., 50
                    int longPeriod = smaWindows[smaWindows.length - 1];  // e.g., 100
                    
                    if (prices.size() >= longPeriod) {
                        List<Double> shortSmaList = TechnicalAnalysisModel.calculateSMA(prices, shortPeriod);
                        List<Double> longSmaList = TechnicalAnalysisModel.calculateSMA(prices, longPeriod);
                        
                        if (shortSmaList != null && !shortSmaList.isEmpty() &&
                            longSmaList != null && !longSmaList.isEmpty()) {
                            
                            Double shortSma = shortSmaList.get(shortSmaList.size() - 1);
                            Double longSma = longSmaList.get(longSmaList.size() - 1);
                            
                            if (shortSma != null && longSma != null) {
                                // Check 1: Short SMA must be above Long SMA (bullish momentum)
                                if (shortSma <= longSma) {
                                    decision.rejectReason = "SMA" + shortPeriod + "=$" + String.format("%.2f", shortSma) + " <= SMA" + longPeriod + "=$" + String.format("%.2f", longSma) + " (bearish)";
                                    return decision;
                                }
                                
                                // Check 2: Price must be above both SMAs
                                if (currentPrice <= shortSma || currentPrice <= longSma) {
                                    decision.rejectReason = "Price=$" + String.format("%.2f", currentPrice) + " below SMA" + shortPeriod + "=$" + String.format("%.2f", shortSma) + " or SMA" + longPeriod + "=$" + String.format("%.2f", longSma);
                                    return decision;
                                }
                                
                                System.out.println("[AIToolAgent] " + ticker + " SMA WINDOWS PASSED: Price=" + 
                                    String.format("%.2f", currentPrice) + " > SMA" + shortPeriod + "=" + 
                                    String.format("%.2f", shortSma) + " > SMA" + longPeriod + "=" + 
                                    String.format("%.2f", longSma));
                            }
                        }
                    }
                }
            }
            
            // === OPTIONAL PRICE ABOVE VWAP FILTER ===
            // For daily data, we approximate VWAP using typical price = (high + low + close) / 3
            if (hasFilter(agent, "priceAboveVwapPct") || hasFilter(agent, "priceAboveVwapRequired")) {
                if (highPrices != null && lowPrices != null && !highPrices.isEmpty()) {
                    // Calculate typical price (approximation of VWAP for daily data)
                    double high = highPrices.get(highPrices.size() - 1);
                    double low = lowPrices.get(lowPrices.size() - 1);
                    double typicalPrice = (high + low + currentPrice) / 3.0;
                    
                    // Check if priceAboveVwapRequired is set (boolean check)
                    boolean vwapRequired = getBooleanFilter(agent, "priceAboveVwapRequired", false);
                    if (vwapRequired && currentPrice <= typicalPrice) {
                        decision.rejectReason = "Price=$" + String.format("%.2f", currentPrice) + " <= VWAP=$" + String.format("%.2f", typicalPrice);
                        return decision;
                    }
                    
                    // Check priceAboveVwapPct (percentage threshold)
                    // Positive value = must be X% above VWAP
                    // Negative value = can be up to X% below VWAP (more lenient)
                    if (hasFilter(agent, "priceAboveVwapPct")) {
                        double vwapPctThreshold = getDoubleFilter(agent, "priceAboveVwapPct", 0);
                        double actualVwapPct = ((currentPrice - typicalPrice) / typicalPrice) * 100;
                        
                        if (actualVwapPct < vwapPctThreshold) {
                            decision.rejectReason = "VWAP%=" + String.format("%.2f", actualVwapPct) + " < required=" + vwapPctThreshold;
                            return decision;
                        }
                    }
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // LAYER 2: ENTRY TRIGGERS
            // All scan gates (1-11) have passed. These triggers are NOT hard
            // rejects — failing them defers the stock to the watchlist so it
            // can be promoted automatically when the trigger fires.
            // ═══════════════════════════════════════════════════════════════

            // Pre-calculate SL/TP using daily close so watchlist entries
            // always carry meaningful risk levels for display.
            decision.entryPrice = currentPrice;
            decision.confidence = Math.min(1.0, (rsi - rsiMin) / (rsiMax - rsiMin));

            double stopLossPct   = getDoubleRisk(agent, "stopLossPct",   3.0);
            double takeProfitPct = getDoubleRisk(agent, "takeProfitPct", 6.0);
            double atrMult       = getDoubleFilter(agent, "atrMultiplier", 2.0);
            double dailyAtr      = 0;
            if (highPrices != null && lowPrices != null && highPrices.size() >= 14) {
                List<Double> atrList = ATR.calculateATR(highPrices, lowPrices, prices, 14);
                Double atrVal = (atrList != null && !atrList.isEmpty()) ? atrList.get(atrList.size() - 1) : null;
                if (atrVal != null) dailyAtr = atrVal;
            }
            double rrRatio = getDoubleRisk(agent, "riskRewardRatio", 1.5);
            decision.suggestedStopLoss   = calculateFinalStopPrice(currentPrice, dailyAtr, atrMult, stopLossPct);
            double riskPerShare           = currentPrice - decision.suggestedStopLoss;
            decision.suggestedTakeProfit = applyMinTakeProfit(currentPrice,
                currentPrice + (riskPerShare * rrRatio > 0 ? riskPerShare * rrRatio
                    : currentPrice * takeProfitPct / 100));

            // --- Entry Trigger 1: MA Crossover (MA9 > MA21) ---
            if (getBooleanFilter(agent, "maCrossoverRequired", false)) {
                if (prices.size() >= 21) {
                    List<Double> sma9List  = TechnicalAnalysisModel.calculateSMA(prices, 9);
                    List<Double> sma21List = TechnicalAnalysisModel.calculateSMA(prices, 21);
                    if (sma9List != null && !sma9List.isEmpty() && sma21List != null && !sma21List.isEmpty()) {
                        double sma9  = sma9List.get(sma9List.size() - 1);
                        double sma21 = sma21List.get(sma21List.size() - 1);
                        if (sma9 <= sma21) {
                            decision.triggerNotMet     = true;
                            decision.entryTriggerPrice = sma21 * 1.002;
                            decision.rejectReason      = String.format("Waiting: MA9=$%.2f <= MA21=$%.2f", sma9, sma21);
                        }
                    }
                } else {
                    decision.triggerNotMet = true;
                    decision.rejectReason  = "Waiting: need 21 bars for MA crossover";
                }
            }

            // --- Entry Trigger 2: Prev-Day High Breakout (0.2% buffer blocks fake breakouts) ---
            if (!decision.triggerNotMet && getBooleanFilter(agent, "prevHighBreakoutRequired", false)) {
                if (highPrices != null && highPrices.size() >= 2) {
                    double prevDayHigh        = highPrices.get(highPrices.size() - 2);
                    double breakoutThreshold  = prevDayHigh * 1.002;
                    if (currentPrice < breakoutThreshold) {
                        decision.triggerNotMet     = true;
                        decision.entryTriggerPrice = breakoutThreshold;
                        decision.rejectReason      = String.format("Waiting: price=$%.2f < breakout=$%.2f (prevHigh=$%.2f +0.2%%)", currentPrice, breakoutThreshold, prevDayHigh);
                    }
                }
            }

            // --- Entry Trigger 3: Distance from 30-Day Resistance ---
            if (!decision.triggerNotMet && hasFilter(agent, "distanceFromResistancePct")) {
                double minDistPct = getDoubleFilter(agent, "distanceFromResistancePct", 0);
                if (minDistPct > 0 && prices.size() >= 2) {
                    int lookback = Math.min(prices.size() - 1, 30);
                    List<Double> recentCloses = prices.subList(prices.size() - 1 - lookback, prices.size() - 1);
                    double resistance = recentCloses.stream().mapToDouble(p -> p != null ? p : 0).max().orElse(0);
                    if (resistance > 0 && currentPrice > 0) {
                        double distPct = ((resistance - currentPrice) / currentPrice) * 100;
                        if (distPct < minDistPct) {
                            decision.triggerNotMet     = true;
                            decision.entryTriggerPrice = resistance * 1.005;
                            decision.rejectReason      = String.format("Waiting: R30d dist=%.1f%% < %.1f%% (R=$%.2f)", distPct, minDistPct, resistance);
                        }
                    }
                }
            }

            // --- Entry Trigger T4: Entry-Type Routing ---
            // Applied only when entryType is explicitly set (not AUTO).
            // Adds type-specific precision on top of the generic T1–T3 triggers.
            if (!decision.triggerNotMet && agent.entryType != null && !agent.entryType.equals("AUTO")) {
                String et = agent.entryType;

                if ("EARLY_BREAKOUT".equals(et)) {
                    // Confirm first break above prevHigh with high-conviction volume
                    // Requires: price >= prevHigh * 1.003  AND  rvol > 1.5
                    if (highPrices != null && highPrices.size() >= 2 && volumes != null && volumes.size() >= 21) {
                        double prevDayHigh   = highPrices.get(highPrices.size() - 2);
                        double earlyThresh   = prevDayHigh * 1.003;
                        double avgVol        = volumes.subList(volumes.size() - 21, volumes.size() - 1)
                                                       .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                        double todayVol      = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                        double rvol          = avgVol > 0 ? todayVol / avgVol : 0;
                        if (currentPrice < earlyThresh || rvol < 1.5) {
                            decision.triggerNotMet     = true;
                            decision.entryTriggerPrice = earlyThresh;
                            decision.rejectReason      = String.format(
                                "EARLY: need price=$%.2f (+0.3%%) AND rvol>1.5 (now=%.2f)", earlyThresh, rvol);
                        }
                    }

                } else if ("RETEST_BREAKOUT".equals(et)) {
                    // Enter on confirmed retest: peak 2–3 days ago → pullback → reclaim
                    // Requires: hadBreakout + reclaim + above SMA50 + rvol > 1.3
                    if (highPrices != null && highPrices.size() >= 4) {
                        int sz             = highPrices.size();
                        double prevHigh    = highPrices.get(sz - 2);
                        double high2d      = highPrices.get(sz - 3);
                        double high3d      = highPrices.get(sz - 4);
                        double recentPeak  = Math.max(high2d, high3d);
                        boolean hadBreakout = recentPeak > prevHigh * 1.01;
                        boolean reclaim     = currentPrice >= prevHigh;

                        // SMA50 structure: must be above SMA50 (trending structure)
                        List<Double> sma50r = TechnicalAnalysisModel.calculateSMA(prices, 50);
                        double sma50r_val   = (sma50r != null && !sma50r.isEmpty()) ? sma50r.get(sma50r.size() - 1) : 0;
                        boolean aboveSMA50  = sma50r_val > 0 && currentPrice > sma50r_val;

                        // Volume confirmation: retest needs volume backing
                        double retestRvol = 0;
                        if (volumes != null && volumes.size() >= 21) {
                            double avgV  = volumes.subList(volumes.size() - 21, volumes.size() - 1)
                                                   .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                            double curV  = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                            retestRvol   = avgV > 0 ? curV / avgV : 0;
                        }
                        boolean volumeOk = retestRvol >= 1.3;

                        if (!(hadBreakout && reclaim && aboveSMA50 && volumeOk)) {
                            decision.triggerNotMet     = true;
                            decision.entryTriggerPrice = prevHigh;
                            decision.rejectReason      = String.format(
                                "RETEST: breakout=%b reclaim=%b sma50=%b vol=%b (peak=$%.2f prev=$%.2f rvol=%.2f)",
                                hadBreakout, reclaim, aboveSMA50, volumeOk, recentPeak, prevHigh, retestRvol);
                        }
                    }

                } else if ("TREND_CONTINUATION".equals(et)) {
                    // Already in a healthy uptrend — just ensure RSI is in the "momentum but not overbought" zone
                    // and price is above SMA50. SMA200 is already enforced by scan gate.
                    List<Double> sma50List = TechnicalAnalysisModel.calculateSMA(prices, 50);
                    double sma50 = (sma50List != null && !sma50List.isEmpty())
                        ? sma50List.get(sma50List.size() - 1) : 0;
                    boolean aboveSMA50  = sma50 > 0 && currentPrice > sma50;
                    boolean rsiHealthy  = rsi >= 50 && rsi <= 70;
                    if (!aboveSMA50 || !rsiHealthy) {
                        decision.triggerNotMet     = true;
                        decision.entryTriggerPrice = Math.max(currentPrice, sma50) * 1.005;
                        decision.rejectReason      = String.format(
                            "TREND: aboveSMA50=%b rsiHealthy=%b (RSI=%.1f SMA50=$%.2f)",
                            aboveSMA50, rsiHealthy, rsi, sma50);
                    }
                }
            }

            // Trigger not yet met → qualified candidate, deferred to watchlist
            if (decision.triggerNotMet) {
                writeScanLog("[⏳ WATCHLIST] " + ticker + " | " + agent.id
                    + " | " + decision.rejectReason
                    + " | Trigger=$" + String.format("%.2f", decision.entryTriggerPrice));
                return decision;
            }

            // === ALL SCAN GATES + ENTRY TRIGGERS PASSED — EXECUTE ===
            // Capture entry diagnostics for post-trade analysis
            try {
                decision.diagEntryType = agent.entryType != null ? agent.entryType : "AUTO";
                List<Double> rsiDiag = RSI.calculateRSI(prices, 14);
                decision.diagRsi = (rsiDiag != null && !rsiDiag.isEmpty()) ? rsiDiag.get(rsiDiag.size() - 1) : 0;
                List<Double> s20d = TechnicalAnalysisModel.calculateSMA(prices, 20);
                double s20v = (s20d != null && !s20d.isEmpty()) ? s20d.get(s20d.size() - 1) : 0;
                List<Double> s50d = TechnicalAnalysisModel.calculateSMA(prices, 50);
                double s50v = (s50d != null && !s50d.isEmpty()) ? s50d.get(s50d.size() - 1) : 0;
                if (s20v > 0) decision.diagDistSMA20 = (currentPrice - s20v) / s20v * 100;
                if (s50v > 0) decision.diagDistSMA50 = (currentPrice - s50v) / s50v * 100;
                if (volumes != null && volumes.size() >= 21) {
                    double avgV = volumes.subList(volumes.size() - 21, volumes.size() - 1)
                                         .stream().mapToDouble(v -> v != null ? v : 0).average().orElse(0);
                    double curV = volumes.get(volumes.size() - 1) != null ? volumes.get(volumes.size() - 1) : 0;
                    decision.diagRvol = avgV > 0 ? curV / avgV : 0;
                }
            } catch (Exception ignored) {}

            decision.shouldTrade = true;
            decision.action = "BUY";

            // Live price fetch for confirmed entries only (not watchlist candidates)
            if (isMarketHours()) {
                String quoteJson = DataFetcher.fetchGlobalQuote(ticker);
                double livePrice = quoteJson != null ? parseGlobalQuoteField(quoteJson, "05. price") : 0;
                if (livePrice > 0) {
                    decision.entryPrice = livePrice;
                    System.out.println("[AIToolAgent] LIVE price for " + ticker + ": $" + String.format("%.2f", livePrice)
                        + " (daily close was $" + String.format("%.2f", currentPrice) + ")");
                    // Recalculate SL/TP with confirmed live entry price
                    double liveAtr = dailyAtr; // reuse already-computed ATR
                    decision.suggestedStopLoss   = calculateFinalStopPrice(livePrice, liveAtr, atrMult, stopLossPct);
                    double liveRisk               = livePrice - decision.suggestedStopLoss;
                    decision.suggestedTakeProfit = applyMinTakeProfit(livePrice,
                        livePrice + (liveRisk * rrRatio > 0 ? liveRisk * rrRatio
                            : livePrice * takeProfitPct / 100));
                }
            }

            return decision;
        } catch (Exception e) {
            return null;
        }
    }
    
    // Check if agent has a specific filter defined
    private static boolean hasFilter(AgentConfig agent, String key) {
        return agent.entryFilters != null && agent.entryFilters.containsKey(key);
    }

    private static double getDoubleFilter(AgentConfig agent, String key, double defaultVal) {
        Object val = agent.entryFilters.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultVal;
    }

    private static boolean getBooleanFilter(AgentConfig agent, String key, boolean defaultVal) {
        Object val = agent.entryFilters.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultVal;
    }

    /**
     * Enforce a minimum 6% take-profit floor on any take-profit price.
     * If the strategy TP% yields less than 6%, raise it to entry × 1.06.
     */
    private static double applyMinTakeProfit(double entryPrice, double rawTP) {
        double minTP = entryPrice * 1.06;
        return Math.max(rawTP, minTP);
    }

    /**
     * Calculates the final stop-loss price using the best of two methods:
     *   1. Percentage-based stop  (from agent JSON: stopLossPct)
     *   2. ATR-based stop         (entry - atr × atrMultiplier)
     *
     * Takes the LOWER/wider stop to give volatile stocks breathing room,
     * then enforces MIN_STOP_LOSS_PCT as a floor (stop must be at least
     * MIN_STOP_LOSS_PCT% below entry — prevents stop being too tight).
     *
     * Example: entry=$100, ATR=$4, mult=2.5 → atrStop=$90, pctStop=$96.5 (3.5%)
     *   → final=$90 (ATR wins, wider stop for volatile stock)
     * Example: entry=$100, ATR=$0.5, mult=2.5 → atrStop=$98.75, pctStop=$96.5
     *   → raw=$96.5 (pct wins), floor check: $97 > $96.5 so final=$97 (3% floor)
     */
    private static double calculateFinalStopPrice(double entryPrice, double atr, double atrMultiplier,
                                                   double stopLossPct) {
        double percentStop = entryPrice * (1 - stopLossPct / 100.0);
        double atrStop     = (atr > 0) ? entryPrice - (atr * atrMultiplier) : percentStop;
        double finalStop   = Math.min(percentStop, atrStop); // wider stop wins
        double floorStop   = entryPrice * (1 - MIN_STOP_LOSS_PCT / 100.0);
        if (finalStop > floorStop) finalStop = floorStop; // enforce minimum distance
        return finalStop;
    }

    private static double getDoubleRisk(AgentConfig agent, String key, double defaultVal) {
        Object val = agent.riskManagement.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultVal;
    }

    private static boolean getBooleanRisk(AgentConfig agent, String key, boolean defaultVal) {
        Object val = agent.riskManagement.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    private static int[] getIntArrayFilter(AgentConfig agent, String key, int[] defaultVal) {
        Object val = agent.entryFilters.get(key);
        if (val == null) return defaultVal;
        
        // Handle List<Integer> or List<Number> from JSON parsing
        if (val instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) val;
            int[] result = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    result[i] = ((Number) item).intValue();
                }
            }
            return result;
        }
        
        // Handle int[] directly
        if (val instanceof int[]) {
            return (int[]) val;
        }
        
        return defaultVal;
    }

    private static Trade executeTrade(AgentConfig agent, String ticker, TradeDecision decision) {
        try {
            // Reuse the entry price captured during analysis (avoids a redundant API call).
            // Falls back to fetching only when called outside the normal ticker-centric loop.
            double entryPrice;
            if (decision.entryPrice > 0) {
                entryPrice = decision.entryPrice;
            } else {
                DataFetcher.setTicker(ticker);
                String json = DataFetcher.fetchStockData();
                List<Double> prices = PriceJsonParser.extractClosingPrices(json);
                if (prices == null || prices.size() < 1) return null;
                entryPrice = prices.get(prices.size() - 1);
            }
            
            Trade trade = new Trade();
            trade.id = UUID.randomUUID().toString().substring(0, 8);
            trade.agentId = agent.id;
            trade.ticker = ticker;
            trade.action = decision.action;
            trade.entryPrice = entryPrice;
            trade.quantity = 100; // Simulated quantity
            
            // Entry time is NOW
            ZonedDateTime now = ZonedDateTime.now(NY);
            trade.entryTime = now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            
            // Exit time is NOT SET yet - position is OPEN
            trade.exitTime = null;
            trade.exitPrice = 0;
            
            // Store stop/limit in trade; enforce minimum 4% stop-loss distance
            double rawSL = decision.suggestedStopLoss;
            double minSL = entryPrice * (1.0 - MIN_STOP_LOSS_PCT / 100.0);
            trade.stopLoss  = (rawSL <= 0 || entryPrice - rawSL < entryPrice - minSL) ? minSL : rawSL;
            trade.takeProfit = decision.suggestedTakeProfit;

            // Record sector
            trade.sector = LongTermCandidateFinder.getSectorForTicker(ticker);
            
            // Position is OPEN - will be closed at end of day or when stop/limit hit
            trade.status = "OPEN";
            trade.entryScore          = decision.totalScore;
            trade.entryVolumeScore    = decision.volumeScore;
            trade.entryTrendScore     = decision.trendScore;
            trade.entryMomentumScore  = decision.momentumScore;
            trade.entrySetupScore     = decision.setupScore;
            trade.entryConfluenceCount = decision.confluenceCount;
            trade.strategyType        = agent.strategyType;
            trade.setupType           = deriveSetupType(agent.strategyType);

            // P/L is 0 until position is closed
            trade.profitLoss = 0;
            trade.profitLossPct = 0;

            // ── SUCCESS RECIPE: capture full market snapshot at entry ──
            try {
                DataFetcher.setTicker(ticker);
                String snapJson = DataFetcher.fetchStockData();
                if (snapJson != null && !snapJson.isBlank()) {
                    IndicatorData snap = computeIndicators(ticker, snapJson);
                    if (snap != null && snap.valid) {
                        java.util.Map<String, Object> m = trade.entrySnapshot;
                        m.put("currentPrice", snap.currentPrice);
                        m.put("prevClose", snap.prevClose);
                        m.put("todayChangePct", round2(snap.todayChangePct));
                        m.put("sma20", snap.sma20);
                        m.put("sma50", snap.sma50);
                        m.put("sma200", snap.sma200);
                        m.put("rsi", round2(snap.rsi));
                        m.put("cci", round2(snap.cci));
                        m.put("rvol", round2(snap.rvol));
                        m.put("vwapPct", round2(snap.vwapPct));
                        m.put("momentum20d", round2(snap.momentum20d));
                        m.put("week52High", snap.week52High);
                        m.put("pctFromWeek52High", round2(snap.pctFromWeek52High));
                        m.put("resistance30d", snap.resistance30d);
                        m.put("atrPct", round2(snap.atrPct));
                        m.put("atr", round2(snap.atr));
                        m.put("prevHigh", snap.prevHigh);
                        m.put("high20d", snap.high20d);
                        m.put("priceAboveSMA20", snap.priceAboveSMA20);
                        m.put("priceAboveSMA50", snap.priceAboveSMA50);
                        m.put("priceAboveSMA200", snap.priceAboveSMA200);
                        m.put("maCrossoverUp", snap.maCrossoverUp);
                        m.put("distSMA20", round2(decision.diagDistSMA20));
                        m.put("distSMA50", round2(decision.diagDistSMA50));
                        m.put("diagRsi", round2(decision.diagRsi));
                        m.put("diagRvol", round2(decision.diagRvol));
                        m.put("entryScore", decision.totalScore);
                        m.put("volumeScore", decision.volumeScore);
                        m.put("trendScore", decision.trendScore);
                        m.put("momentumScore", decision.momentumScore);
                        m.put("setupScore", decision.setupScore);
                        m.put("confluenceCount", decision.confluenceCount);
                        // ── Institutional Flow Layer snapshot ──
                        m.put("finalConviction", round2(decision.finalConviction));
                        m.put("fundamentalScore", decision.fundamentalScore);
                        m.put("catalystScore", decision.catalystScore);
                        m.put("institutionalFlowScore", decision.institutionalFlowScore);
                        if (decision.fundamentalData != null) {
                            FundamentalData fd = decision.fundamentalData;
                            m.put("fd_marketCap", fd.marketCap);
                            m.put("fd_revenueGrowth", round2(fd.revenueGrowth));
                            m.put("fd_epsGrowth", round2(fd.epsGrowth));
                            m.put("fd_eps", round2(fd.eps));
                            m.put("fd_profitMargin", round2(fd.profitMargin));
                            m.put("fd_operatingMargin", round2(fd.operatingMargin));
                            m.put("fd_peRatio", round2(fd.peRatio));
                            m.put("fd_analystTargetPrice", fd.analystTargetPrice);
                            m.put("fd_beta", round2(fd.beta));
                            m.put("fd_debtToEquity", round2(fd.debtToEquity));
                            m.put("fd_analystUpside", round2(fd.analystUpside));
                            m.put("fd_institutionalScore", round2(fd.institutionalScore));
                            m.put("fd_sector", fd.sector);
                            m.put("fd_industry", fd.industry);
                        }
                        if (decision.catalystData != null) {
                            CatalystData cd = decision.catalystData;
                            m.put("cd_sentimentScore", round2(cd.sentimentScore));
                            m.put("cd_relevanceScore", round2(cd.relevanceScore));
                            m.put("cd_newsCount24h", cd.newsCount24h);
                            m.put("cd_earningsBeat", cd.earningsBeat);
                            m.put("cd_guidanceRaise", cd.guidanceRaise);
                            m.put("cd_analystUpgrade", cd.analystUpgrade);
                            m.put("cd_analystDowngrade", cd.analystDowngrade);
                            m.put("cd_hasAIMention", cd.hasAIMention);
                            m.put("cd_hasMnaMention", cd.hasMnaMention);
                            m.put("cd_hasFDAStage", cd.hasFDAStage);
                            m.put("cd_hasPartnership", cd.hasPartnership);
                        }
                        m.put("agentId", agent.id);
                        m.put("strategyType", agent.strategyType);
                        // Also snapshot the active filter thresholds so we know what "recipe" was in effect
                        if (agent.entryFilters != null) {
                            agent.entryFilters.forEach((k, v) -> m.put("filter_" + k, v));
                        }
                        if (agent.riskManagement != null) {
                            agent.riskManagement.forEach((k, v) -> m.put("risk_" + k, v));
                        }
                    }
                }
            } catch (Exception snapEx) {
                System.err.println("[AIToolAgent] Snapshot error for " + ticker + ": " + snapEx.getMessage());
            }

            System.out.println("[AIToolAgent] OPEN POSITION: " + ticker + " Entry=$" + String.format("%.2f", entryPrice) +
                " SL=$" + String.format("%.2f", trade.stopLoss) + " TP=$" + String.format("%.2f", trade.takeProfit));

            return trade;
        } catch (Exception e) {
            return null;
        }
    }

    private static void updatePerformance(String agentId, Trade trade) {
        synchronized (LOCK) {
            AgentPerformance perf = systemState.performance.computeIfAbsent(agentId, k -> {
                AgentPerformance p = new AgentPerformance();
                p.agentId = agentId;
                return p;
            });
            
            // Only update win/loss stats when trade is CLOSED
            if (trade.status.equals("OPEN")) {
                // For OPEN trades, just add to recent trades for display
                perf.recentTrades.add(0, trade);
                if (perf.recentTrades.size() > 3) {
                    perf.recentTrades = new ArrayList<>(perf.recentTrades.subList(0, 3));
                }
                return; // Don't update P/L stats yet
            }
            
            perf.totalTrades++;
            perf.totalProfitLoss += trade.profitLoss;
            
            // Update virtual capital tracking
            perf.currentCapital += trade.profitLoss;
            if (perf.currentCapital > perf.peakCapital) {
                perf.peakCapital = perf.currentCapital;
            }
            // Calculate max drawdown
            double drawdown = (perf.peakCapital - perf.currentCapital) / perf.peakCapital * 100;
            if (drawdown > perf.maxDrawdown) {
                perf.maxDrawdown = drawdown;
            }
            
            if (trade.status.equals("CLOSED_WIN")) {
                perf.wins++;
            } else if (trade.status.equals("CLOSED_LOSS")) {
                perf.losses++;
            }
            
            perf.winRate = perf.totalTrades > 0 ? (double) perf.wins / perf.totalTrades * 100 : 0;
            
            // Calculate Expectancy: (WinRate × AvgWin) - (LossRate × AvgLoss)
            double totalWinPct = 0.0;
            double totalLossPct = 0.0;
            double totalWinPL = 0.0;
            double totalLossPL = 0.0;
            int winCount = 0;
            int lossCount = 0;
            List<Trade> allTrades = systemState.tradeHistory.get(agentId);
            if (allTrades != null) {
                for (Trade t : allTrades) {
                    if (t.status.equals("CLOSED_WIN")) {
                        totalWinPct += t.profitLossPct;
                        totalWinPL += t.profitLoss;
                        winCount++;
                    } else if (t.status.equals("CLOSED_LOSS")) {
                        totalLossPct += Math.abs(t.profitLossPct);
                        totalLossPL += Math.abs(t.profitLoss);
                        lossCount++;
                    }
                }
            }
            perf.avgWin = winCount > 0 ? totalWinPct / winCount : 0.0;
            perf.avgLoss = lossCount > 0 ? totalLossPct / lossCount : 0.0;
            double winRateDecimal = perf.totalTrades > 0 ? (double) perf.wins / perf.totalTrades : 0.0;
            double lossRateDecimal = perf.totalTrades > 0 ? (double) perf.losses / perf.totalTrades : 0.0;
            perf.expectancy = (winRateDecimal * perf.avgWin) - (lossRateDecimal * perf.avgLoss);
            
            // Calculate Profit Factor: Gross Profit / Gross Loss
            perf.profitFactor = totalLossPL > 0 ? totalWinPL / totalLossPL : (totalWinPL > 0 ? Double.MAX_VALUE : 0.0);
            
            // Calculate Sharpe Ratio (simplified: Return / StdDev of returns)
            if (allTrades != null && allTrades.size() >= 2) {
                double[] returns = allTrades.stream()
                    .filter(t -> t.status.equals("CLOSED_WIN") || t.status.equals("CLOSED_LOSS"))
                    .mapToDouble(t -> t.profitLossPct)
                    .toArray();
                if (returns.length > 0) {
                    double mean = java.util.Arrays.stream(returns).average().orElse(0);
                    double variance = java.util.Arrays.stream(returns)
                        .map(r -> Math.pow(r - mean, 2))
                        .average().orElse(0);
                    double stdDev = Math.sqrt(variance);
                    perf.sharpeRatio = stdDev > 0 ? (mean / stdDev) * Math.sqrt(252) : 0; // Annualized
                }
            }

            // Store expectancy metrics in the trade for historical tracking
            trade.avgWin = perf.avgWin;
            trade.avgLoss = perf.avgLoss;
            trade.expectancy = perf.expectancy;

            // Update daily history
            String today = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_LOCAL_DATE); // YYYY-MM-DD
            if (perf.dailyHistory == null) {
                perf.dailyHistory = new LinkedHashMap<>();
            }
            DailyStats dailyStats = perf.dailyHistory.computeIfAbsent(today, DailyStats::new);
            dailyStats.trades++;
            dailyStats.profitLoss += trade.profitLoss;
            if (trade.status.equals("CLOSED_WIN")) {
                dailyStats.wins++;
            } else {
                dailyStats.losses++;
            }
            dailyStats.updateWinRate();
            
            // Update daily expectancy
            List<Trade> dayTrades = new ArrayList<>();
            if (allTrades != null) {
                String todayStr = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_LOCAL_DATE);
                for (Trade t : allTrades) {
                    if (t.entryTime != null && t.entryTime.startsWith(todayStr)) {
                        dayTrades.add(t);
                    }
                }
            }
            dailyStats.updateExpectancy(dayTrades);

            // Keep only last 3 trades for display
            perf.recentTrades.add(0, trade);
            if (perf.recentTrades.size() > 3) {
                perf.recentTrades = new ArrayList<>(perf.recentTrades.subList(0, 3));
            }
            
            // Calculate averages
            if (allTrades != null && !allTrades.isEmpty()) {
                double totalReturn = allTrades.stream().mapToDouble(t -> t.profitLossPct).sum();
                int tradeDays = Math.max(1, allTrades.size());
                
                perf.avgDailyReturn = totalReturn / tradeDays;
                perf.avgWeeklyReturn = perf.avgDailyReturn * 5;
                perf.avgMonthlyReturn = perf.avgDailyReturn * 22;
            }
            
            // Determine if agent is winning (positive expectancy instead of just win rate)
            perf.isWinning = perf.expectancy > 0.5 && perf.totalProfitLoss > 0; // Positive expectancy > 0.5% per trade
            
            // Check if agent has high win rate and notify via Discord
            checkAndNotifyHighWinRate(perf);
            
            // Send Discord notification if enabled for this agent
            notifyTradeIfEnabled(agentId, trade, perf);
            
            // Save state after every trade update to ensure persistence
            saveState();
        }
    }

    private static void checkAndNotifyHighWinRate(AgentPerformance perf) {
        // Only notify if:
        // 1. Agent has enough trades
        // 2. Expectancy exceeds threshold (the REAL metric)
        // 3. We haven't already notified about this agent
        if (perf.totalTrades >= MIN_TRADES_FOR_NOTIFICATION
            && perf.expectancy >= 1.0  // Positive expectancy > 1% per trade
            && !notifiedWinningAgents.contains(perf.agentId)) {
            
            notifiedWinningAgents.add(perf.agentId);
            
            // Build winning stocks list
            StringBuilder winningStocks = new StringBuilder();
            List<Trade> agentTrades = systemState.tradeHistory.get(perf.agentId);
            if (agentTrades != null) {
                List<Trade> wins = agentTrades.stream()
                    .filter(t -> "CLOSED_WIN".equals(t.status))
                    .sorted((a, b) -> Double.compare(b.profitLossPct, a.profitLossPct)) // Best first
                    .limit(5) // Top 5 winners
                    .collect(Collectors.toList());
                
                if (!wins.isEmpty()) {
                    winningStocks.append("\n📈 **Winning Stocks:**\n");
                    for (Trade t : wins) {
                        winningStocks.append(String.format("• **%s** %+.2f%% ($%+.2f)\n", 
                            t.ticker, t.profitLossPct, t.profitLoss));
                    }
                }
            }
            
            String message = String.format(
                "🏆 **HIGH WIN RATE AGENT DETECTED!**\n" +
                "Agent: **%s** (%s)\n" +
                "Win Rate: **%.1f%%** ✅\n" +
                "Trades: %d (W: %d / L: %d)\n" +
                "Total P/L: $%.2f\n" +
                "Daily Avg: %.2f%% | Monthly Avg: %.2f%%\n" +
                "Generation: %d",
                perf.agentName != null ? perf.agentName : perf.agentId,
                perf.type != null ? perf.type : "UNKNOWN",
                perf.winRate,
                perf.totalTrades, perf.wins, perf.losses,
                perf.totalProfitLoss,
                perf.avgDailyReturn, perf.avgMonthlyReturn,
                perf.generation
            );
            
            // Append winning stocks to the message
            message = message + winningStocks.toString();
            
            sendDiscord(message);
            System.out.println("[AIToolAgent] HIGH WIN RATE notification sent for: " + perf.agentId);
        }
    }

    // ==================== TRADE LOG TRACING ====================
    
    /**
     * Log a trade event to the full-scan-trade-log.txt file
     * Format: [TIMESTAMP] | EVENT | AGENT | TICKER | DETAILS
     */
    private static void logTradeEvent(String event, String agentId, String ticker, String details) {
        try {
            String timestamp = ZonedDateTime.now(NY).format(LOG_TIMESTAMP_FMT);
            String logLine = String.format("[%s] | %-15s | %-20s | %-6s | %s%n",
                timestamp, event, agentId, ticker, details);
            
            // Ensure directory exists
            Files.createDirectories(TRADE_LOG_FILE.getParent());
            
            // Append to log file
            Files.writeString(TRADE_LOG_FILE, logLine, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (IOException e) {
            System.err.println("[AIToolAgent] Failed to write trade log: " + e.getMessage());
        }
    }
    
    /**
     * Log BUY signal detected (before Discord/trade)
     */
    public static void logBuySignal(String agentId, String ticker, double entryPrice, double stopLoss, double takeProfit) {
        String details = String.format("Entry=$%.2f, SL=$%.2f, TP=$%.2f", entryPrice, stopLoss, takeProfit);
        logTradeEvent("BUY_SIGNAL", agentId, ticker, details);
    }
    
    /**
     * Log Discord notification sent
     */
    public static void logDiscordSent(String agentId, String ticker, String alertType) {
        logTradeEvent("DISCORD_SENT", agentId, ticker, alertType);
    }
    
    /**
     * Log trade execution (BUY order placed)
     */
    public static void logTradeExecuted(String agentId, String ticker, double entryPrice, double quantity) {
        String details = String.format("BUY executed: %.0f shares @ $%.2f = $%.2f", 
            quantity, entryPrice, entryPrice * quantity);
        logTradeEvent("TRADE_EXECUTED", agentId, ticker, details);
    }
    
    /**
     * Log trade closed (SELL - position closed)
     */
    public static void logTradeClosed(Trade trade) {
        String status = trade.status.contains("WIN") ? "WIN" : "LOSS";
        String reason = trade.closeReason != null ? trade.closeReason : "EOD_CLOSE";
        String details = String.format("SELL[%s]: Entry=$%.2f, Exit=$%.2f, P/L=$%.2f (%.2f%%) [%s]",
            reason, trade.entryPrice, trade.exitPrice, trade.profitLoss, trade.profitLossPct, status);
        logTradeEvent("TRADE_CLOSED", trade.agentId, trade.ticker, details);
    }
    
    /**
     * Log scan start
     */
    public static void logScanStart(List<String> agentIds, int tickerCount) {
        String agents = String.join(", ", agentIds);
        logTradeEvent("SCAN_START", "SYSTEM", "---", 
            String.format("Agents: [%s], Tickers: %d", agents, tickerCount));
    }
    
    /**
     * Log scan complete
     */
    public static void logScanComplete(int analyzed, int trades) {
        logTradeEvent("SCAN_COMPLETE", "SYSTEM", "---", 
            String.format("Analyzed: %d, Trades: %d", analyzed, trades));
    }
    
    // ==================== END TRADE LOG TRACING ====================

    /**
     * Send BUY ALERT notification BEFORE trade execution.
     * Master strategies always notify (they are the best by design).
     * Legacy agents only notify if saved in Saved Configurations & Cumulative Tracking
     * with either notifyOnTrade=true or a proven cumulative record (≥3 trades, ≥55% win rate).
     */
    private static void sendBuyAlertIfMonitored(AgentConfig agent, String ticker, TradeDecision decision) {
        // --- Filter: only notify from master strategies or tracked/successful legacy agents ---
        ScoringConfig.SavedAgentTracker tracker = null;
        if (!agent.masterStrategy) {
            Map<String, ScoringConfig.SavedAgentTracker> trackers = ScoringConfig.getSavedAgentTrackers();
            tracker = trackers != null ? trackers.get(agent.id) : null;
            if (tracker == null) return; // Not in Saved Configurations → skip
            boolean hasGoodPerformance = tracker.cumulativeTrades >= 3
                && (double) tracker.cumulativeWins / tracker.cumulativeTrades >= 0.55;
            if (!tracker.notifyOnTrade && !hasGoodPerformance) return;
        }

        double currentPrice = decision.entryPrice > 0
            ? decision.entryPrice
            : decision.suggestedStopLoss / (1 - getDoubleRisk(agent, "stopLossPct", 3.5) / 100);
        double stopLossPrice   = decision.suggestedStopLoss;
        double takeProfitPrice = decision.suggestedTakeProfit;
        double riskPct = ((currentPrice - stopLossPrice) / currentPrice) * 100;
        double rewardPct = ((takeProfitPrice - currentPrice) / currentPrice) * 100;
        double riskReward = rewardPct / riskPct;

        // Get agent performance for context
        AgentPerformance perf = systemState.performance.get(agent.id);
        String winRateStr = perf != null ? String.format("%.1f%%", perf.winRate) : "N/A";
        int totalTrades = perf != null ? perf.totalTrades : 0;

        String scoreInfo = decision.totalScore > 0 ? String.format(
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "📊 Score: **%d/12** (V:%d T:%d M:%d S:%d%s)\n",
            decision.totalScore, decision.volumeScore, decision.trendScore,
            decision.momentumScore, decision.setupScore,
            decision.confluenceBonus > 0 ? " 🔥+" + decision.confluenceBonus + " CONFLUENCE" : ""
        ) : "";

        // Cumulative tracker stats for legacy agents saved in Saved Configurations
        String trackerInfo = "";
        if (tracker != null && tracker.cumulativeTrades > 0) {
            double cumWinRate = (double) tracker.cumulativeWins / tracker.cumulativeTrades * 100;
            trackerInfo = String.format(
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "📋 Tracker: **%d/%d** wins (%.1f%%) | Cumulative P/L: $%+.0f\n",
                tracker.cumulativeWins, tracker.cumulativeTrades, cumWinRate, tracker.cumulativeProfitLoss
            );
        }

        double triggerPrice = decision.entryTriggerPrice > 0 ? decision.entryTriggerPrice : currentPrice;
        double triggerPct   = currentPrice > 0 ? ((triggerPrice - currentPrice) / currentPrice) * 100 : 0;
        String setupLabel   = setupTypeLabel(agent.strategyType);
        if (setupLabel.equals("Unknown") && agent.name != null) setupLabel = "🤖 " + agent.name;
        String holdLabel    = holdDuration(agent.strategyType);
        String triggerLine  = String.format(
            "⚡ **BUY ONLY IF breaks $%.2f** (+%.2f%%)\n" +
            "   (Current close: $%.2f — wait for confirmation)\n",
            triggerPrice, triggerPct, currentPrice);

        String message = String.format(
            "🚨 **SWING SIGNAL** — %s\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "**%s**\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "%s" +
            "🛑 Stop Loss: **$%.2f** (-%.1f%%)\n" +
            "🎯 Take Profit: **$%.2f** (+%.1f%%)\n" +
            "📊 Risk/Reward: **1:%.1f**\n" +
            "%s" +
            "%s" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "🤖 Strategy: %s\n" +
            "⏱ Expected hold: %s\n" +
            "📈 Win Rate: %s (%d trades)\n" +
            "⏰ Signal time: %s",
            setupLabel,
            ticker,
            triggerLine,
            stopLossPrice, riskPct,
            takeProfitPrice, rewardPct,
            riskReward,
            scoreInfo,
            trackerInfo,
            agent.name != null ? agent.name : agent.id,
            holdLabel,
            winRateStr, totalTrades,
            ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm:ss z"))
        );

        sendDiscord(message);
        logDiscordSent(agent.id, ticker, "BUY_ALERT");
        System.out.println("[AIToolAgent] BUY ALERT sent for " + ticker + " via agent " + agent.id);
    }

    private static void notifyTradeIfEnabled(String agentId, Trade trade, AgentPerformance perf) {
        // Send SELL notification for every fully closed trade
        if (trade.status.equals("CLOSED_WIN") || trade.status.equals("CLOSED_LOSS")) {
            String reason = trade.closeReason != null ? trade.closeReason : "EOD_CLOSE";
            sendSellDiscord(trade, reason);
        }
    }


    private static void saveEvolvedAgent(AgentConfig agent) {
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            Path path = NEW_STRATEGIES_DIR.resolve(agent.id + ".json");
            
            ObjectNode root = JSON.createObjectNode();
            root.put("id", agent.id);
            root.put("name", agent.name);
            root.put("type", agent.type);
            root.put("generation", agent.generation);
            root.put("parentId", agent.parentId);
            root.put("lastModified", agent.lastModified);
            
            ObjectNode ef = root.putObject("entryFilters");
            for (Map.Entry<String, Object> e : agent.entryFilters.entrySet()) {
                if (e.getValue() instanceof Number) {
                    ef.put(e.getKey(), ((Number) e.getValue()).doubleValue());
                } else if (e.getValue() instanceof Boolean) {
                    ef.put(e.getKey(), (Boolean) e.getValue());
                } else {
                    ef.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            
            ObjectNode rm = root.putObject("riskManagement");
            for (Map.Entry<String, Object> e : agent.riskManagement.entrySet()) {
                if (e.getValue() instanceof Number) {
                    rm.put(e.getKey(), ((Number) e.getValue()).doubleValue());
                } else {
                    rm.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            
            // Save lock protection fields
            root.put("locked", agent.locked);
            if (agent.lockedAt != null) {
                root.put("lockedAt", agent.lockedAt);
            }
            if (agent.codeVersion != null) {
                root.put("codeVersion", agent.codeVersion);
            }
            if (agent.lockedByUser != null) {
                root.put("lockedByUser", agent.lockedByUser);
            }
            root.put("masterStrategy", agent.masterStrategy);
            if (agent.strategyType != null) {
                root.put("strategyType", agent.strategyType);
            }
            
            JSON.writeValue(path.toFile(), root);
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error saving evolved agent: " + e.getMessage());
        }
    }

    private static void saveState() {
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            JSON.writeValue(AGENT_STATE_FILE.toFile(), systemState);
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error saving state: " + e.getMessage());
        }
    }

    // Get current git commit SHA for version tracking
    public static String getCurrentCodeVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return result.isEmpty() ? "unknown" : result;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // Lock an agent to prevent evolution/modification
    public static boolean lockAgent(String agentId) {
        synchronized (LOCK) {
            AgentConfig agent = systemState.agents.get(agentId);
            if (agent == null) return false;
            
            agent.locked = true;
            agent.lockedAt = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            agent.codeVersion = getCurrentCodeVersion();
            agent.lockedByUser = "user"; // Could be extended to track actual user
            
            // Save to file if it's an evolved agent
            if (agent.sourceFile != null && agent.sourceFile.contains("newStrategies")) {
                saveEvolvedAgent(agent);
            }
            saveState();
            
            System.out.println("[AIToolAgent] LOCKED agent: " + agentId + " at code version: " + agent.codeVersion);
            return true;
        }
    }

    // Unlock an agent to allow evolution/modification
    public static boolean unlockAgent(String agentId) {
        synchronized (LOCK) {
            AgentConfig agent = systemState.agents.get(agentId);
            if (agent == null) return false;
            
            agent.locked = false;
            agent.lockedAt = null;
            agent.codeVersion = null;
            agent.lockedByUser = null;
            
            // Save to file if it's an evolved agent
            if (agent.sourceFile != null && agent.sourceFile.contains("newStrategies")) {
                saveEvolvedAgent(agent);
            }
            saveState();
            
            System.out.println("[AIToolAgent] UNLOCKED agent: " + agentId);
            return true;
        }
    }

    // Check if agent is locked
    public static boolean isAgentLocked(String agentId) {
        synchronized (LOCK) {
            AgentConfig agent = systemState.agents.get(agentId);
            return agent != null && agent.locked;
        }
    }

    // Check if code version has changed since agent was locked
    public static boolean hasCodeVersionChanged(String agentId) {
        synchronized (LOCK) {
            AgentConfig agent = systemState.agents.get(agentId);
            if (agent == null || !agent.locked || agent.codeVersion == null) return false;
            String currentVersion = getCurrentCodeVersion();
            return !agent.codeVersion.equals(currentVersion) && !"unknown".equals(currentVersion);
        }
    }

    private static void loadState() {
        try {
            if (Files.exists(AGENT_STATE_FILE)) {
                systemState = JSON.readValue(AGENT_STATE_FILE.toFile(), AgentSystemState.class);
                // Reset running status on startup - it was interrupted
                if (systemState.running) {
                    systemState.running = false;
                    systemState.currentAgent = null;
                    System.out.println("[AIToolAgent] Reset stuck RUNNING status to IDLE on startup");
                }
            }
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error loading state: " + e.getMessage());
            systemState = new AgentSystemState();
        }
    }

    private static void saveHistory() {
        try {
            Files.createDirectories(NEW_STRATEGIES_DIR);
            
            ObjectNode root = JSON.createObjectNode();
            root.put("lastUpdated", ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            
            ObjectNode history = root.putObject("tradeHistory");
            for (Map.Entry<String, List<Trade>> entry : systemState.tradeHistory.entrySet()) {
                ArrayNode trades = history.putArray(entry.getKey());
                for (Trade t : entry.getValue()) {
                    ObjectNode tn = trades.addObject();
                    tn.put("id", t.id);
                    tn.put("ticker", t.ticker);
                    tn.put("action", t.action);
                    tn.put("entryPrice", t.entryPrice);
                    tn.put("exitPrice", t.exitPrice);
                    tn.put("profitLoss", t.profitLoss);
                    tn.put("profitLossPct", t.profitLossPct);
                    tn.put("status", t.status);
                    tn.put("entryTime", t.entryTime);
                    tn.put("exitTime", t.exitTime);
                }
            }
            
            JSON.writeValue(HISTORY_FILE.toFile(), root);
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error saving history: " + e.getMessage());
        }
    }

    public static AgentSystemState getSystemState() {
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            return systemState;
        }
    }

    public static List<AgentPerformance> getAllPerformances() {
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            return new ArrayList<>(systemState.performance.values());
        }
    }

    /** Returns all WAITING pending signals (copy, safe to iterate). */
    public static List<PendingSignal> getPendingSignals() {
        if (systemState == null) return Collections.emptyList();
        return systemState.pendingSignals.stream()
            .filter(s -> "WAITING".equals(s.status))
            .collect(Collectors.toList());
    }

    /** Save or refresh a trigger-not-met signal. Ticker+strategyId is the natural key. */
    private static void upsertPendingSignal(String ticker, AgentConfig strategy, TradeDecision d) {
        if (systemState == null) return;
        synchronized (LOCK) {
            // Update if already present
            for (PendingSignal ps : systemState.pendingSignals) {
                if (ticker.equals(ps.ticker) && strategy.id.equals(ps.strategyId)
                        && "WAITING".equals(ps.status)) {
                    ps.score        = d.totalScore;
                    ps.scanPrice    = d.entryPrice;
                    ps.triggerPrice = d.entryTriggerPrice;
                    ps.triggerGapPct= d.entryTriggerPrice > 0 && d.entryPrice > 0
                        ? ((d.entryTriggerPrice - d.entryPrice) / d.entryPrice) * 100 : 0;
                    ps.suggestedStopLoss  = d.suggestedStopLoss;
                    ps.suggestedTakeProfit= d.suggestedTakeProfit;
                    ps.rejectReason = d.rejectReason;
                    ps.scanTime     = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    ps.scanCount++;
                    if (ps.scanCount >= PendingSignal.MAX_WAIT_SCANS) {
                        ps.status = "EXPIRED";
                        writeScanLog("[WATCHLIST] ⌛ EXPIRED: " + ticker + " | " + strategy.id
                            + " — waited " + ps.scanCount + " scans without trigger firing");
                    } else {
                        writeScanLog("[WATCHLIST] 🔄 UPDATED: " + ticker + " | " + strategy.id
                            + " | Score=" + ps.score + "/12"
                            + " | ScanPrice=$" + String.format("%.2f", ps.scanPrice)
                            + " | Trigger=$" + String.format("%.2f", ps.triggerPrice)
                            + " | Gap=" + String.format("%.1f%%", ps.triggerGapPct)
                            + " | Scan#" + ps.scanCount + "/" + PendingSignal.MAX_WAIT_SCANS);
                    }
                    return;
                }
            }
            // New pending signal
            PendingSignal ps = new PendingSignal();
            ps.id           = UUID.randomUUID().toString().substring(0, 8);
            ps.ticker       = ticker;
            ps.strategyId   = strategy.id;
            ps.strategyType = strategy.strategyType;
            ps.score        = d.totalScore;
            ps.scanPrice    = d.entryPrice;
            ps.triggerPrice = d.entryTriggerPrice;
            ps.triggerGapPct= d.entryTriggerPrice > 0 && d.entryPrice > 0
                ? ((d.entryTriggerPrice - d.entryPrice) / d.entryPrice) * 100 : 0;
            ps.suggestedStopLoss   = d.suggestedStopLoss;
            ps.suggestedTakeProfit = d.suggestedTakeProfit;
            ps.rejectReason = d.rejectReason;
            ps.scanTime     = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            ps.status       = "WAITING";
            ps.scanCount    = 1;
            systemState.pendingSignals.add(ps);
            writeScanLog("[WATCHLIST] ➕ NEW: " + ticker + " | " + strategy.id
                + " | Score=" + ps.score + "/12"
                + " | ScanPrice=$" + String.format("%.2f", ps.scanPrice)
                + " | Trigger=$" + String.format("%.2f", ps.triggerPrice)
                + " | Need +" + String.format("%.1f%%", ps.triggerGapPct) + " to fire");
        }
    }

    /** Mark any WAITING signal for this ticker+strategy as EXPIRED (conditions no longer valid). */
    private static void expirePendingSignal(String ticker, String strategyId) {
        if (systemState == null) return;
        for (PendingSignal ps : systemState.pendingSignals) {
            if (ticker.equals(ps.ticker) && strategyId.equals(ps.strategyId)
                    && "WAITING".equals(ps.status)) {
                ps.status = "EXPIRED";
                writeScanLog("[WATCHLIST] ❌ EXPIRED: " + ticker + " | " + strategyId
                    + " — score dropped below threshold");
            }
        }
    }

    /** Mark any WAITING signal for this ticker+strategy as CONFIRMED (trigger met, trade executed). */
    private static void confirmPendingSignal(String ticker, String strategyId) {
        if (systemState == null) return;
        for (PendingSignal ps : systemState.pendingSignals) {
            if (ticker.equals(ps.ticker) && strategyId.equals(ps.strategyId)
                    && "WAITING".equals(ps.status)) {
                ps.status = "CONFIRMED";
                writeScanLog("[WATCHLIST] ✅ CONFIRMED: " + ticker + " | " + strategyId
                    + " — trigger fired, trade executed");
            }
        }
    }

    /** Dismiss a pending signal by its short ID (called from UI). */
    public static boolean dismissPendingSignal(String id) {
        if (systemState == null) return false;
        for (PendingSignal ps : systemState.pendingSignals) {
            if (id.equals(ps.id) && "WAITING".equals(ps.status)) {
                ps.status = "DISMISSED";
                return true;
            }
        }
        return false;
    }

    public static AgentConfig getAgentConfig(String agentId) {
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            return systemState.agents.get(agentId);
        }
    }

    public static AgentPerformance getAgentPerformance(String agentId) {
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            return systemState.performance.get(agentId);
        }
    }

    /**
     * Returns all trades (open + closed) for a specific agent, newest first.
     */
    public static List<Trade> getAgentTradeHistory(String agentId) {
        synchronized (LOCK) {
            if (systemState == null) initialize();
            List<Trade> trades = systemState.tradeHistory.get(agentId);
            if (trades == null) return new ArrayList<>();
            List<Trade> copy = new ArrayList<>(trades);
            copy.sort((a, b) -> {
                String ta = a.entryTime != null ? a.entryTime : "";
                String tb = b.entryTime != null ? b.entryTime : "";
                return tb.compareTo(ta); // newest first
            });
            return copy;
        }
    }

    /**
     * Returns agents with >= minTrades trades AND winRate >= 70% ("top agents").
     * Sorted by win rate descending, capped at maxResults.
     */
    public static List<AgentPerformance> getTopPerformingAgents(int minTrades, int maxResults) {
        List<AgentPerformance> all = getAllPerformances();
        Map<String, ScoringConfig.SavedAgentTracker> trackers = ScoringConfig.getSavedAgentTrackers();
        List<AgentPerformance> result = new ArrayList<>();
        for (AgentPerformance perf : all) {
            AgentPerformance copy = new AgentPerformance();
            copy.agentId = perf.agentId;
            copy.agentName = perf.agentName;
            copy.type = perf.type;
            copy.totalTrades = perf.totalTrades;
            copy.wins = perf.wins;
            copy.losses = perf.losses;
            copy.winRate = perf.winRate;
            copy.totalProfitLoss = perf.totalProfitLoss;
            copy.recentTrades = perf.recentTrades;
            ScoringConfig.SavedAgentTracker tracker = trackers.get(perf.agentId);
            if (tracker != null && tracker.cumulativeTrades > copy.totalTrades) {
                copy.totalTrades = tracker.cumulativeTrades;
                copy.wins = tracker.cumulativeWins;
                copy.losses = tracker.cumulativeTrades - tracker.cumulativeWins;
                copy.winRate = tracker.cumulativeTrades > 0 ? (tracker.cumulativeWins * 100.0 / tracker.cumulativeTrades) : 0;
                copy.totalProfitLoss = tracker.cumulativeProfitLoss;
            }
            if (copy.totalTrades >= minTrades && copy.winRate >= 70.0) {
                result.add(copy);
            }
        }
        // Sort by professional metrics: expectancy (primary), then profit factor, then sharpe ratio
        result.sort((a, b) -> {
            // Primary: Expectancy (the REAL metric)
            int expCompare = Double.compare(b.expectancy, a.expectancy);
            if (expCompare != 0) return expCompare;
            // Secondary: Profit Factor (risk/reward ratio)
            int pfCompare = Double.compare(b.profitFactor, a.profitFactor);
            if (pfCompare != 0) return pfCompare;
            // Tertiary: Sharpe Ratio (risk-adjusted returns)
            return Double.compare(b.sharpeRatio, a.sharpeRatio);
        });
        return result.subList(0, Math.min(maxResults, result.size()));
    }

    /** Returns true if an agent qualifies as a "top agent" (>=70% win rate, >=3 trades). */
    public static boolean isTopAgent(String agentId) {
        synchronized (LOCK) {
            if (systemState == null) return false;
            AgentPerformance perf = systemState.performance.get(agentId);
            if (perf == null) return false;
            return perf.totalTrades >= 3 && perf.winRate >= 70.0;
        }
    }

    /** Returns true if an agent has at least minWinRatePct win rate AND >= 3 trades. */
    public static boolean hasMinWinRate(String agentId, double minWinRatePct) {
        synchronized (LOCK) {
            if (systemState == null) return false;
            AgentPerformance perf = systemState.performance.get(agentId);
            if (perf == null) return false;
            return perf.totalTrades >= 3 && perf.winRate > minWinRatePct;
        }
    }

    private static void autoTrackWinners() {
        try {
            // IMPORTANT: Use raw systemState.performance values directly, NOT getAllPerformances()
            // which may have been modified by getTop5Agents() with inflated tracker cumulative stats.
            // This prevents a feedback loop where tracker stats get added to themselves.
            Map<String, ScoringConfig.SavedAgentTracker> existingTrackers = ScoringConfig.getSavedAgentTrackers();
            
            synchronized (LOCK) {
                for (AgentPerformance perf : systemState.performance.values()) {
                    if (perf.totalTrades < 5) continue; // Need at least 5 trades
                    
                    // Auto-track agents with >75% win rate
                    if (perf.winRate >= 75.0) {
                        if (!existingTrackers.containsKey(perf.agentId)) {
                            AgentConfig cfg = systemState.agents.get(perf.agentId);
                            String name = cfg != null && cfg.name != null ? cfg.name : perf.agentId;
                            String type = cfg != null && cfg.type != null ? cfg.type : "UNKNOWN";
                            ScoringConfig.getOrCreateTracker(perf.agentId, name, type);
                            System.out.println("[AIToolAgent] Auto-tracked winner: " + perf.agentId + " (" + String.format("%.1f%%", perf.winRate) + ")");
                        }
                    }
                    
                    // Update existing trackers with latest stats from RAW performance (not inflated)
                    if (existingTrackers.containsKey(perf.agentId)) {
                        ScoringConfig.updateTrackerWithDailyStats(perf.agentId, perf.wins, perf.totalTrades, perf.totalProfitLoss);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error auto-tracking winners: " + e.getMessage());
        }
    }

    public static void runAgentsAsync() {
        scheduler.submit(() -> {
            try {
                runAllAgents();
            } catch (Exception e) {
                System.err.println("[AIToolAgent] Async run error: " + e.getMessage());
            }
        });
    }

    // Progress tracking for "Run All Agents Now"
    private static volatile int runProgress = 0;   // agents completed
    private static volatile int runTotal = 0;       // total agents in run
    private static volatile int runStockProgress = 0; // stocks analyzed this agent
    private static volatile String runCurrentTicker = null;

    public static int getRunProgress() { return runProgress; }
    public static int getRunTotal() { return runTotal; }
    public static int getRunStockProgress() { return runStockProgress; }
    public static String getRunCurrentTicker() { return runCurrentTicker; }

    public static RegimeLevel getLastKnownRegime()    { return lastKnownRegime; }
    public static String      getLastRegimeDetail()   { return lastRegimeDetail; }
    public static int         getLastScanSignalCount(){ return lastScanSignalCount; }
    public static String      getLastRegimeCheckTime(){ return lastRegimeCheckTime; }

    // Status tracking for top agents full scan
    private static volatile boolean topAgentsFullScanRunning = false;
    private static volatile String topAgentsFullScanStatus = "";
    private static volatile int topAgentsFullScanProgress = 0;
    private static volatile int topAgentsFullScanTotal = 0;
    private static final List<Trade> recentFullScanTrades = Collections.synchronizedList(new ArrayList<>());
    private static volatile List<String> selectedAgentsForScan = null;

    // Institutional Swing Agent daily recommendation limit
    private static volatile String institutionalSwingLastDate = "";
    private static volatile int institutionalSwingDailyCount = 0;
    private static final int INSTITUTIONAL_SWING_DAILY_LIMIT = 3;

    public static boolean isTopAgentsFullScanRunning() {
        return topAgentsFullScanRunning;
    }

    public static String getTopAgentsFullScanStatus() {
        return topAgentsFullScanStatus;
    }

    public static int getTopAgentsFullScanProgress() {
        return topAgentsFullScanProgress;
    }

    public static int getTopAgentsFullScanTotal() {
        return topAgentsFullScanTotal;
    }

    public static List<Trade> getRecentFullScanTrades() {
        synchronized (recentFullScanTrades) {
            return new ArrayList<>(recentFullScanTrades);
        }
    }

    /**
     * Get top 5 agents sorted by win rate and number of trades (combined score)
     * Uses tracked agents cumulative stats when available (more accurate than agent-state.json)
     * NOTE: Creates copies of performance objects to avoid mutating the original systemState.performance
     */
    public static List<AgentPerformance> getTop5Agents() {
        List<AgentPerformance> allPerfs = getAllPerformances();
        
        // Get tracked agents for cumulative stats (more accurate)
        Map<String, ScoringConfig.SavedAgentTracker> trackers = ScoringConfig.getSavedAgentTrackers();
        
        // Create copies with tracker stats to avoid mutating original performance objects
        // This prevents feedback loops where inflated stats get written back to trackers
        List<AgentPerformance> perfCopies = new ArrayList<>();
        for (AgentPerformance perf : allPerfs) {
            AgentPerformance copy = new AgentPerformance();
            copy.agentId = perf.agentId;
            copy.agentName = perf.agentName;
            copy.type = perf.type;
            copy.totalTrades = perf.totalTrades;
            copy.wins = perf.wins;
            copy.losses = perf.losses;
            copy.winRate = perf.winRate;
            copy.totalProfitLoss = perf.totalProfitLoss;
            copy.avgDailyReturn = perf.avgDailyReturn;
            copy.avgWeeklyReturn = perf.avgWeeklyReturn;
            copy.avgMonthlyReturn = perf.avgMonthlyReturn;
            copy.recentTrades = perf.recentTrades;
            
            // Use cumulative stats from tracker if available (more complete data)
            ScoringConfig.SavedAgentTracker tracker = trackers.get(perf.agentId);
            if (tracker != null && tracker.cumulativeTrades > copy.totalTrades) {
                copy.totalTrades = tracker.cumulativeTrades;
                copy.wins = tracker.cumulativeWins;
                copy.losses = tracker.cumulativeTrades - tracker.cumulativeWins;
                copy.winRate = tracker.cumulativeTrades > 0 ? (tracker.cumulativeWins * 100.0 / tracker.cumulativeTrades) : 0;
                copy.totalProfitLoss = tracker.cumulativeProfitLoss;
            }
            perfCopies.add(copy);
        }
        
        // Filter agents with at least 3 trades
        List<AgentPerformance> qualified = perfCopies.stream()
            .filter(p -> p.totalTrades >= 3)
            .collect(Collectors.toList());
        
        // Sort by professional metrics: expectancy (primary), then profit factor, then sharpe ratio
        qualified.sort((a, b) -> {
            // Primary: Expectancy (the REAL metric)
            int expCompare = Double.compare(b.expectancy, a.expectancy);
            if (expCompare != 0) return expCompare;
            // Secondary: Profit Factor (risk/reward ratio)
            int pfCompare = Double.compare(b.profitFactor, a.profitFactor);
            if (pfCompare != 0) return pfCompare;
            // Tertiary: Sharpe Ratio (risk-adjusted returns)
            return Double.compare(b.sharpeRatio, a.sharpeRatio);
        });
        
        // Return top 5
        return qualified.subList(0, Math.min(5, qualified.size()));
    }

    /**
     * Run selected agents against ALL tickers from LongTermCandidateFinder (500+ tickers)
     * @param agentIds List of agent IDs to run, or null to use top 5
     */
    public static void runFullScanWithAgentsAsync(List<String> agentIds) {
        if (topAgentsFullScanRunning) {
            System.out.println("[AIToolAgent] Top agents full scan already running, skipping...");
            return;
        }
        
        // Mark as manual run (no TOP 2-3 filtering)
        isScheduledRun = false;
        
        selectedAgentsForScan = agentIds;
        
        scheduler.submit(() -> {
            try {
                runTop5AgentsFullScan();
            } catch (Exception e) {
                System.err.println("[AIToolAgent] Top agents full scan error: " + e.getMessage());
                topAgentsFullScanRunning = false;
                topAgentsFullScanStatus = "Error: " + e.getMessage();
            }
        });
    }

    /**
     * Run top 5 agents against ALL tickers from LongTermCandidateFinder (500+ tickers)
     * This is a comprehensive scan that takes a long time due to API rate limits
     */
    public static void runTop5AgentsFullScanAsync() {
        runFullScanWithAgentsAsync(null);
    }

    private static void runTop5AgentsFullScan() {
        topAgentsFullScanRunning = true;
        topAgentsFullScanProgress = 0;
        
        // Clear recent trades from previous scan
        synchronized (recentFullScanTrades) {
            recentFullScanTrades.clear();
        }
        
        try {
            // Get agents to run - either selected ones or top 5
            List<AgentPerformance> agentsToRun = new ArrayList<>();
            
            if (selectedAgentsForScan != null && !selectedAgentsForScan.isEmpty()) {
                // Use selected agents — include even if no performance record yet (e.g. new MASTER agents)
                for (String agentId : selectedAgentsForScan) {
                    AgentPerformance perf = systemState.performance.get(agentId);
                    if (perf == null) {
                        // Agent exists in agents map but has no trades yet — create a stub perf so it can run
                        AgentConfig cfg = systemState.agents.get(agentId);
                        if (cfg != null && !cfg.disabled && cfg.strategyType != null) {
                            perf = new AgentPerformance();
                            perf.agentId = agentId;
                            perf.agentName = cfg.name;
                            perf.type = cfg.type;
                        }
                    }
                    if (perf != null) agentsToRun.add(perf);
                }
            } else {
                // Use top 5
                agentsToRun = getTop5Agents();
            }
            
            if (agentsToRun.isEmpty()) {
                topAgentsFullScanStatus = "No qualified agents found (need at least 3 trades)";
                topAgentsFullScanRunning = false;
                return;
            }
            
            // Get all tickers from sector banks (all sectors combined)
            List<String> allTickers = LongTermCandidateFinder.getAllSectorTickers();
            topAgentsFullScanTotal = allTickers.size() * agentsToRun.size();
            
            // Send Discord scan-start notification
            String agentListStr = agentsToRun.stream().map(p -> p.agentId).collect(Collectors.joining(", "));
            sendDiscord("\uD83D\uDE80 **Full Scan Started** — " + agentsToRun.size() + " agents | " + allTickers.size() + " tickers\n"
                + "Agents: " + agentListStr + "\n"
                + "\u23F0 " + ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm:ss z")));
            
            // Log scan start to trade log
            List<String> agentIdList = agentsToRun.stream().map(p -> p.agentId).collect(Collectors.toList());
            logScanStart(agentIdList, allTickers.size());
            
            topAgentsFullScanStatus = "Running: 0/" + topAgentsFullScanTotal + " analyzed";
            System.out.println("[AIToolAgent] Starting full scan with " + agentsToRun.size() + " agents and " + allTickers.size() + " tickers");
            
            int totalAnalyzed = 0;
            int totalTrades = 0;

            // Run each agent against all tickers
            for (AgentPerformance perfInfo : agentsToRun) {
                AgentConfig agent = systemState.agents.get(perfInfo.agentId);
                if (agent == null || agent.disabled) continue;

                System.out.println("[AIToolAgent] Full scan with agent: " + agent.id);

                // Collect all signals for this agent first, then pick top 2
                List<AgentSignal> agentSignals = new ArrayList<>();

                for (String ticker : allTickers) {
                    try {
                        topAgentsFullScanStatus = "Analyzing " + ticker + " with " + agent.id + " (" + totalAnalyzed + "/" + topAgentsFullScanTotal + ")";

                        // Analyze stock
                        TradeDecision decision = analyzeStock(ticker, agent);

                        if (decision != null && decision.shouldTrade) {
                            // Collect signal for later sorting
                            AgentSignal signal = new AgentSignal();
                            signal.ticker = ticker;
                            signal.agent = agent;
                            signal.decision = decision;
                            signal.score = decision.totalScore;
                            signal.finalConviction = decision.finalConviction;
                            agentSignals.add(signal);

                            // Log BUY signal detected
                            logBuySignal(agent.id, ticker, decision.suggestedStopLoss / (1 - 0.03),
                                decision.suggestedStopLoss, decision.suggestedTakeProfit);
                        }

                        totalAnalyzed++;
                        topAgentsFullScanProgress = totalAnalyzed;

                        // Rate limit delay - Alpha Vantage free tier = 5 calls/minute
                        Thread.sleep(12500);

                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Full scan error for " + ticker + ": " + e.getMessage());
                        totalAnalyzed++;
                        topAgentsFullScanProgress = totalAnalyzed;
                    }
                }

                // Sort signals by score (primary) and conviction (secondary), pick top 2
                agentSignals.sort((a, b) -> {
                    int scoreCompare = Double.compare(b.score, a.score);
                    if (scoreCompare != 0) return scoreCompare;
                    return Double.compare(b.finalConviction, a.finalConviction);
                });

                int topSignalsPerAgent = 2;
                List<AgentSignal> topSignals = agentSignals.stream()
                    .limit(topSignalsPerAgent)
                    .collect(Collectors.toList());

                System.out.println("[AIToolAgent] Agent " + agent.id + " found " + agentSignals.size() + " signals, selecting top " + topSignals.size());

                // Send Discord notifications only for top 2 signals
                for (AgentSignal signal : topSignals) {
                    double signalEntry = signal.decision.entryPrice > 0 ? signal.decision.entryPrice
                        : signal.decision.suggestedStopLoss / (1 - 0.03);

                    if (hasMinWinRate(agent.id, 65.0)) {
                        String agentLabel = (agent.name != null && !agent.name.isEmpty())
                            ? agent.name : agent.id;
                        double entryZoneHigh = signalEntry * 1.01;
                        double cappedSL = signalEntry * 0.98; // max $20 risk on $1K position
                        double cappedSLActual = Math.max(signal.decision.suggestedStopLoss, cappedSL); // tighter of the two

                        // AlphaPoint AI recommendation: Add confirmation guidance
                        String confirmationAdvice = getConfirmationAdvice(agent.strategyType);

                        // Visual indicator for run type
                        String runTypeIndicator = "🔄 SCHEDULED";
                        String signalCountInfo = "\n\n📊 *Top " + topSignalsPerAgent + " signal for " + agentLabel + "*";

                        sendDiscord("\uD83C\uDFC6 **" + signal.ticker + "** | " + agentLabel + " (`" + agent.id + "`) " + runTypeIndicator
                            + "\n📥 **ENTRY ZONE:** $" + String.format("%.2f", signalEntry) + " – $" + String.format("%.2f", entryZoneHigh)
                            + "  |  🛑 **SL:** $" + String.format("%.2f", cappedSLActual) + " *(max $20 risk)*"
                            + "  |  🎯 **TP:** $" + String.format("%.2f", signal.decision.suggestedTakeProfit)
                            + "\n⏱ **VALID FOR: 10 min**"
                            + "\n\n💡 **WAIT FOR CONFIRMATION:** " + confirmationAdvice
                            + signalCountInfo);

                        signalsSentThisRun++;
                        totalTrades++;
                    }
                }
            }

            // Log scan complete
            logScanComplete(totalAnalyzed, totalTrades);

            topAgentsFullScanStatus = "Complete: " + totalAnalyzed + " analyzed, " + totalTrades + " trades";
            System.out.println("[AIToolAgent] Top agents full scan complete: " + totalAnalyzed + " analyzed, " + totalTrades + " trades");

        } finally {
            topAgentsFullScanRunning = false;
        }
    }

    /** Helper class to store agent signals for sorting and filtering */
    private static class AgentSignal {
        String ticker;
        AgentConfig agent;
        TradeDecision decision;
        double score;
        double finalConviction;
    }


    public static boolean isRunning() {
        synchronized (LOCK) {
            return systemState != null && systemState.running;
        }
    }

    public static String getCurrentAgent() {
        synchronized (LOCK) {
            return systemState != null ? systemState.currentAgent : null;
        }
    }

    public static int getAgentCount() {
        synchronized (LOCK) {
            return systemState != null ? systemState.agents.size() : 0;
        }
    }


    // Discord notification - public wrapper for external calls
    public static boolean sendDiscordPublic(String text) {
        return sendDiscord(text);
    }

    // Discord notification — uses Java's built-in HttpClient for better reliability
    private static boolean sendDiscord(String text) {
        try {
            if (DISCORD_WEBHOOK_URL == null || DISCORD_WEBHOOK_URL.isBlank()) {
                System.out.println("[AIToolAgent] Discord webhook not configured (DAILY_SIM_DISCORD_WEBHOOK_URL env var)");
                writeScanLog("[DISCORD] ❌ Not configured — DAILY_SIM_DISCORD_WEBHOOK_URL env var missing");
                return false;
            }
            if (text == null || text.isBlank()) return false;

            String preview = text.length() > 60 ? text.substring(0, 60).replace('\n', ' ') + "..." : text.replace('\n', ' ');
            String jsonBody = "{\"content\": " + escapeJsonString(text) + "}";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_WEBHOOK_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            boolean ok = statusCode == 200 || statusCode == 204;
            System.out.println("[AIToolAgent][Discord] Sent notification, status: " + statusCode);
            if (ok) {
                writeScanLog("[DISCORD] ✅ Sent (HTTP " + statusCode + "): " + preview);
            } else {
                writeScanLog("[DISCORD] ❌ Failed (HTTP " + statusCode + "): " + preview);
                writeScanLog("[DISCORD] Response: " + response.body());
            }
            return ok;
        } catch (java.net.ConnectException e) {
            System.err.println("[AIToolAgent][Discord] Connection failed: " + e.getMessage());
            writeScanLog("[DISCORD] ❌ Connection failed: " + e.getMessage());
            return false;
        } catch (java.net.SocketTimeoutException e) {
            System.err.println("[AIToolAgent][Discord] Request timed out: " + e.getMessage());
            writeScanLog("[DISCORD] ❌ Timeout: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[AIToolAgent][Discord] Failed to send: " + e.getMessage());
            writeScanLog("[DISCORD] ❌ Exception: " + e.getMessage());
            return false;
        }
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "null";
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
        sb.append("\"");
        return sb.toString();
    }

    /**
     * AlphaPoint AI recommendation: Provide confirmation guidance based on strategy type
     * Instead of immediate entry, wait for small confirmation to avoid fake moves
     */
    private static String getConfirmationAdvice(String strategyType) {
        if (strategyType == null) {
            return "Wait for price to stabilize before entering";
        }
        
        switch (strategyType.toUpperCase()) {
            case "MOMENTUM_BREAKOUT":
            case "BREAKOUT":
                return "Wait for next candle to close ABOVE breakout level before entering";
            case "PULLBACK":
            case "PULLBACK_MA20":
                return "Wait for small pullback to complete, then bounce confirmation";
            case "TREND_CONTINUATION":
            case "TREND":
                return "Wait for small pullback within the trend, then continuation signal";
            case "RETEST":
                return "Wait for level to hold (support/resistance) with bounce confirmation";
            default:
                return "Wait for price action confirmation before entering (don't chase)";
        }
    }
}
