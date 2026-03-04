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
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter LOG_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    
    // Discord webhook for notifications
    private static final String DISCORD_WEBHOOK_URL = System.getenv("DAILY_SIM_DISCORD_WEBHOOK_URL");
    
    // Win rate threshold for Discord notification (notify when agent reaches this)
    private static final double WIN_RATE_NOTIFICATION_THRESHOLD = 60.0;
    private static final int MIN_TRADES_FOR_NOTIFICATION = 5;
    
    // Number of random stocks each agent analyzes per run
    // Note: Alpha Vantage free tier = 5 calls/minute, so keep this low
    private static final int STOCKS_PER_AGENT_RUN = 5;
    
    // Track which agents we've already notified about (to avoid spam)
    private static final Set<String> notifiedWinningAgents = ConcurrentHashMap.newKeySet();
    
    private static final Object LOCK = new Object();
    private static volatile AgentSystemState systemState = null;
    private static volatile boolean initialized = false;
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
        public String aiSuggestion; // AI improvement suggestion from Ollama/ChatGPT
        // Lock protection fields - prevents evolution/modification of winning agents
        public boolean locked = false;
        public String lockedAt; // ISO timestamp when locked
        public String codeVersion; // Git commit SHA when locked
        public String lockedByUser; // Who locked it
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
        public String closeReason; // STOP_LOSS, TAKE_PROFIT, EOD_CLOSE
    }

    // Daily statistics record for historical tracking
    public static class DailyStats {
        public String date; // YYYY-MM-DD format
        public int trades = 0;
        public int wins = 0;
        public int losses = 0;
        public double profitLoss = 0.0;
        public double winRate = 0.0;
        
        public DailyStats() {}
        
        public DailyStats(String date) {
            this.date = date;
        }
        
        public void updateWinRate() {
            if (trades > 0) {
                this.winRate = (wins * 100.0) / trades;
            }
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
    }

    public static class EvolutionEvent {
        public String timestamp;
        public String originalAgentId;
        public String newAgentId;
        public String reason;
        public double oldWinRate;
        public int oldTrades;
        public double oldProfitLoss;
        public Map<String, String> parameterChanges = new HashMap<>(); // "rsiMin: 30 -> 25"
        public String aiSuggestion; // ChatGPT improvement suggestion
    }

    public static class AgentSystemState {
        public Map<String, AgentConfig> agents = new ConcurrentHashMap<>();
        public Map<String, List<Trade>> tradeHistory = new ConcurrentHashMap<>();
        public Map<String, AgentPerformance> performance = new ConcurrentHashMap<>();
        public List<EvolutionEvent> evolutionLog = new ArrayList<>(); // Track all evolution decisions
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
            saveState();
            
            System.out.println("[AIToolAgent] Initialized with " + systemState.agents.size() + " agents");
            
            // Schedule market open run (9:30 AM ET)
            scheduleMarketOpenRun();
            
            // Schedule periodic runs every 60 minutes during NASDAQ market hours (9:30 AM - 4:00 PM ET)
            // Note: Using 60-min interval due to 15-min delay in real-time price data
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isMarketHours()) {
                        System.out.println("[AIToolAgent] Scheduled 60-min periodic run starting...");
                        runAllAgents();
                    }
                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Scheduled run error: " + e.getMessage());
                }
            }, 60, 60, TimeUnit.MINUTES); // Start after 60 min, then every 60 minutes
            
            // Schedule end-of-day cleanup at 4:30 PM ET (after market close)
            scheduleEndOfDayCleanup();
            
            // AUTO-RUN ON STARTUP: If market is currently open, run immediately
            if (isMarketHours()) {
                System.out.println("[AIToolAgent] Market is OPEN - starting immediate run on startup!");
                sendDiscord("🤖 **AITool Agents Started**\nServer started during market hours. Running all " + systemState.agents.size() + " agents now...");
                scheduler.schedule(() -> {
                    try {
                        runAllAgents();
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

    private static void scheduleMarketOpenRun() {
        // Calculate delay until next 9:30 AM ET
        ZonedDateTime now = ZonedDateTime.now(NY);
        ZonedDateTime nextMarketOpen = now.withHour(9).withMinute(30).withSecond(0).withNano(0);
        
        // If we're past 9:30 today, schedule for tomorrow
        if (now.isAfter(nextMarketOpen)) {
            nextMarketOpen = nextMarketOpen.plusDays(1);
        }
        
        // Skip weekends
        while (nextMarketOpen.getDayOfWeek().getValue() > 5) {
            nextMarketOpen = nextMarketOpen.plusDays(1);
        }
        
        long delayMinutes = Duration.between(now, nextMarketOpen).toMinutes();
        
        System.out.println("[AIToolAgent] Next market open run scheduled in " + delayMinutes + " minutes (" + nextMarketOpen.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " ET)");
        
        // Schedule the market open run
        scheduler.schedule(() -> {
            try {
                System.out.println("[AIToolAgent] MARKET OPEN - Starting all agents!");
                sendDiscord("🔔 **NASDAQ Market Open**\n🤖 AITool starting all " + getAgentCount() + " agents for daily trading simulation...");
                runAllAgents();
                
                // Reschedule for next market open
                scheduleMarketOpenRun();
            } catch (Exception e) {
                System.err.println("[AIToolAgent] Market open run error: " + e.getMessage());
            }
        }, delayMinutes, TimeUnit.MINUTES);
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
     * Close all OPEN positions at end of day.
     * Fetches current price and determines win/loss based on stop loss and take profit.
     */
    public static void closeOpenPositions() {
        System.out.println("[AIToolAgent] Closing all OPEN positions at end of day...");
        
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
                            continue;
                        }
                        
                        double currentPrice = prices.get(prices.size() - 1);
                        
                        // Set exit price and time
                        trade.exitPrice = currentPrice;
                        trade.exitTime = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        
                        // Determine win/loss based on stop loss and take profit
                        if (currentPrice <= trade.stopLoss) {
                            trade.status = "CLOSED_LOSS";
                            trade.exitPrice = trade.stopLoss; // Stopped out at stop loss
                            trade.closeReason = "STOP_LOSS";
                        } else if (currentPrice >= trade.takeProfit) {
                            trade.status = "CLOSED_WIN";
                            trade.exitPrice = trade.takeProfit; // Hit take profit
                            trade.closeReason = "TAKE_PROFIT";
                        } else {
                            // Neither stop nor limit hit - close at current price
                            double priceChange = currentPrice - trade.entryPrice;
                            trade.status = priceChange > 0 ? "CLOSED_WIN" : "CLOSED_LOSS";
                            trade.closeReason = "EOD_CLOSE";
                        }
                        
                        // Calculate P/L
                        double priceChange = trade.exitPrice - trade.entryPrice;
                        trade.profitLoss = priceChange * trade.quantity;
                        trade.profitLossPct = (priceChange / trade.entryPrice) * 100;
                        
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
                        
                        System.out.println("[AIToolAgent] CLOSED: " + trade.ticker + " @ $" + 
                            String.format("%.2f", trade.exitPrice) + " P/L: $" + 
                            String.format("%.2f", trade.profitLoss) + " (" + trade.status + ")");
                        
                        // Rate limit for API calls
                        Thread.sleep(12500);
                        
                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Error closing position for " + trade.ticker + ": " + e.getMessage());
                    }
                }
            }
            
            // Save state after closing all positions
            saveState();
        }
        
        // Log summary
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
                if (perf.totalTrades >= MIN_TRADES_FOR_DELETION && perf.winRate < UNDERPERFORMING_THRESHOLD) {
                    agentsToDelete.add(perf.agentId);
                    deletedReport.append(String.format("• **%s** - %.1f%% win rate (%d/%d trades)\n", 
                        perf.agentId, perf.winRate, perf.wins, perf.totalTrades));
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
            
            // Load any evolved agents from newStrategies folder
            loadEvolvedAgents();
        }
    }

    private static void loadAgentsFromFile(String filename, String type, String arrayKey) {
        try {
            Path path = Paths.get(filename);
            if (!Files.exists(path)) return;
            
            JsonNode root = JSON.readTree(path.toFile());
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
        synchronized (LOCK) {
            if (systemState == null) {
                initialize();
            }
            if (systemState.running) {
                System.out.println("[AIToolAgent] Already running, skipping...");
                return;
            }
            systemState.running = true;
            systemState.runCount++;
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
            runProgress = 0;
            runTotal = total;
            runStockProgress = 0;
            runCurrentTicker = null;

            int totalSignals = 0;

            for (int i = 0; i < allTickers.size(); i++) {
                String ticker = allTickers.get(i);
                runCurrentTicker = ticker;
                runProgress = i;

                try {
                    // Fetch ticker data ONCE — reused by every agent (no redundant API calls)
                    DataFetcher.setTicker(ticker);
                    String json = DataFetcher.fetchStockData();
                    if (json == null || json.isBlank()) {
                        Thread.sleep(12500);
                        continue;
                    }

                    // Test ALL agents against this ticker (zero extra API calls per agent)
                    for (AgentConfig agent : allAgents) {
                        try {
                            synchronized (LOCK) { systemState.currentAgent = agent.id; }
                            // Do not re-enter a position already open today for this agent+ticker
                            if (hasOpenPositionToday(agent.id, ticker)) continue;

                            TradeDecision decision = analyzeStock(ticker, agent, json);
                            if (decision != null && decision.shouldTrade) {
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
                            }
                        } catch (Exception e) {
                            System.err.println("[AIToolAgent] Agent " + agent.id +
                                " error on " + ticker + ": " + e.getMessage());
                        }
                    }

                    // Rate limit: 1 API call per ticker (Alpha Vantage free tier = 5 calls/min)
                    Thread.sleep(12500);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[AIToolAgent] Error fetching " + ticker + ": " + e.getMessage());
                }
            }

            runProgress = total;
            System.out.println("[AIToolAgent] Scan complete — signals: " + totalSignals +
                ", tickers: " + total + ", agents: " + allAgents.size());

            evolveUnderperformingAgents();
            autoTrackWinners();

            synchronized (LOCK) {
                systemState.lastRunTime = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                systemState.running = false;
                systemState.currentAgent = null;
            }
            runProgress = 0;
            runTotal = 0;
            runCurrentTicker = null;

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
    }

    // Public wrapper for debugging - calls the private analyzeStock
    public static TradeDecision analyzeStockPublic(String ticker, AgentConfig agent) {
        return analyzeStock(ticker, agent);
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
                return decision; // RSI out of range, no trade
            }
            
            // === PRICE ABOVE SMA CHECK ===
            if (currentPrice <= sma20Current) {
                return decision; // Price below SMA, no trade
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
                                return decision; // CCI out of range, no trade
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
                                return decision; // ATR out of range, no trade
                            }
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
                            return decision; // RVOL out of range, no trade
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
                        return decision; // Volume too low, no trade
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
                                System.out.println("[AIToolAgent] " + ticker + " FILTERED: CMF=" + String.format("%.3f", cmf) + " (distribution/selling pressure)");
                                return decision; // CMF negative = distribution, no trade
                            }
                            
                            // Check minimum CMF threshold
                            double cmfMin = getDoubleFilter(agent, "cmfMin", -1.0);
                            if (cmf < cmfMin) {
                                System.out.println("[AIToolAgent] " + ticker + " FILTERED: CMF=" + String.format("%.3f", cmf) + " < cmfMin=" + cmfMin);
                                return decision; // CMF below threshold
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
                        return decision; // Price below SMA200, no trade
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
                                    return decision; // Bearish alignment, no trade
                                }
                                
                                // Check 2: Price must be above both SMAs
                                if (currentPrice <= shortSma || currentPrice <= longSma) {
                                    return decision; // Price below SMAs, no trade
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
                        return decision; // Price not above VWAP/typical price
                    }
                    
                    // Check priceAboveVwapPct (percentage threshold)
                    // Positive value = must be X% above VWAP
                    // Negative value = can be up to X% below VWAP (more lenient)
                    if (hasFilter(agent, "priceAboveVwapPct")) {
                        double vwapPctThreshold = getDoubleFilter(agent, "priceAboveVwapPct", 0);
                        double actualVwapPct = ((currentPrice - typicalPrice) / typicalPrice) * 100;
                        
                        if (actualVwapPct < vwapPctThreshold) {
                            return decision; // Price not meeting VWAP percentage threshold
                        }
                    }
                }
            }
            
            // === ALL FILTERS PASSED - TRADE SIGNAL ===
            decision.shouldTrade = true;
            decision.action = "BUY";
            decision.entryPrice = currentPrice; // cache for executeTrade — no 2nd API call needed
            decision.confidence = Math.min(1.0, (rsi - rsiMin) / (rsiMax - rsiMin));
            
            // Calculate stop loss and take profit from risk management
            double stopLossPct = getDoubleRisk(agent, "stopLossPct", 3.0);
            double takeProfitPct = getDoubleRisk(agent, "takeProfitPct", 6.0);
            
            // Use ATR-based stop loss if atrMultiplier is set
            if (hasFilter(agent, "atrMultiplier") && highPrices != null && lowPrices != null) {
                List<Double> atrList = ATR.calculateATR(highPrices, lowPrices, prices, 14);
                if (atrList != null && !atrList.isEmpty()) {
                    Double atr = atrList.get(atrList.size() - 1);
                    if (atr != null && atr > 0) {
                        double atrMultiplier = getDoubleFilter(agent, "atrMultiplier", 2.0);
                        // ATR-based stop loss: price - (ATR * multiplier)
                        decision.suggestedStopLoss = currentPrice - (atr * atrMultiplier);
                        // Take profit at 1.5x the stop distance (risk/reward)
                        double riskRewardRatio = getDoubleRisk(agent, "riskRewardRatio", 1.5);
                        decision.suggestedTakeProfit = currentPrice + (atr * atrMultiplier * riskRewardRatio);
                    } else {
                        decision.suggestedStopLoss = currentPrice * (1 - stopLossPct / 100);
                        decision.suggestedTakeProfit = currentPrice * (1 + takeProfitPct / 100);
                    }
                } else {
                    decision.suggestedStopLoss = currentPrice * (1 - stopLossPct / 100);
                    decision.suggestedTakeProfit = currentPrice * (1 + takeProfitPct / 100);
                }
            } else {
                decision.suggestedStopLoss = currentPrice * (1 - stopLossPct / 100);
                decision.suggestedTakeProfit = currentPrice * (1 + takeProfitPct / 100);
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

    private static double getDoubleRisk(AgentConfig agent, String key, double defaultVal) {
        Object val = agent.riskManagement.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
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
            
            // Store stop/limit in trade
            trade.stopLoss = decision.suggestedStopLoss;
            trade.takeProfit = decision.suggestedTakeProfit;
            
            // Position is OPEN - will be closed at end of day or when stop/limit hit
            trade.status = "OPEN";
            
            // P/L is 0 until position is closed
            trade.profitLoss = 0;
            trade.profitLossPct = 0;
            
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
            
            if (trade.status.equals("CLOSED_WIN")) {
                perf.wins++;
            } else if (trade.status.equals("CLOSED_LOSS")) {
                perf.losses++;
            }
            
            perf.winRate = perf.totalTrades > 0 ? (double) perf.wins / perf.totalTrades * 100 : 0;
            
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
            
            // Keep only last 3 trades for display
            perf.recentTrades.add(0, trade);
            if (perf.recentTrades.size() > 3) {
                perf.recentTrades = new ArrayList<>(perf.recentTrades.subList(0, 3));
            }
            
            // Calculate averages
            List<Trade> allTrades = systemState.tradeHistory.get(agentId);
            if (allTrades != null && !allTrades.isEmpty()) {
                double totalReturn = allTrades.stream().mapToDouble(t -> t.profitLossPct).sum();
                int tradeDays = Math.max(1, allTrades.size());
                
                perf.avgDailyReturn = totalReturn / tradeDays;
                perf.avgWeeklyReturn = perf.avgDailyReturn * 5;
                perf.avgMonthlyReturn = perf.avgDailyReturn * 22;
            }
            
            // Determine if agent is winning (positive total P/L and win rate > 50%)
            perf.isWinning = perf.totalProfitLoss > 0 && perf.winRate > 50;
            
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
        // 2. Win rate exceeds threshold
        // 3. We haven't already notified about this agent
        if (perf.totalTrades >= MIN_TRADES_FOR_NOTIFICATION 
            && perf.winRate >= WIN_RATE_NOTIFICATION_THRESHOLD
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
     * Send BUY ALERT notification BEFORE trade execution
     * This alerts the user to a trading opportunity so they can act on it
     */
    private static void sendBuyAlertIfMonitored(AgentConfig agent, String ticker, TradeDecision decision) {
        // Send if this agent is monitored OR tracked (both get immediate BUY alerts)
        boolean isMonitored = ScoringConfig.isAgentMonitoredForDiscord(agent.id);
        boolean isTracked = ScoringConfig.isTradeNotificationEnabled(agent.id);
        
        if (!isMonitored && !isTracked) {
            return;
        }
        
        // Get current price for the alert
        double currentPrice = decision.suggestedStopLoss / (1 - getDoubleRisk(agent, "stopLossPct", 3.0) / 100);
        // Recalculate from decision values
        double stopLossPrice = decision.suggestedStopLoss;
        double takeProfitPrice = decision.suggestedTakeProfit;
        double riskPct = ((currentPrice - stopLossPrice) / currentPrice) * 100;
        double rewardPct = ((takeProfitPrice - currentPrice) / currentPrice) * 100;
        double riskReward = rewardPct / riskPct;
        
        // Get agent performance for context
        AgentPerformance perf = systemState.performance.get(agent.id);
        String winRateStr = perf != null ? String.format("%.1f%%", perf.winRate) : "N/A";
        int totalTrades = perf != null ? perf.totalTrades : 0;
        
        String message = String.format(
            "🚨 **BUY ALERT** 🚨\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "**Ticker: %s**\n" +
            "**Action: %s NOW**\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "📍 Entry Price: **$%.2f**\n" +
            "🛑 Stop Loss: **$%.2f** (-%.1f%%)\n" +
            "🎯 Take Profit: **$%.2f** (+%.1f%%)\n" +
            "📊 Risk/Reward: **1:%.1f**\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "🤖 Agent: %s\n" +
            "📈 Win Rate: %s (%d trades)\n" +
            "⏰ Time: %s",
            ticker,
            decision.action,
            currentPrice,
            stopLossPrice, riskPct,
            takeProfitPrice, rewardPct,
            riskReward,
            agent.name != null ? agent.name : agent.id,
            winRateStr, totalTrades,
            ZonedDateTime.now(NY).format(DateTimeFormatter.ofPattern("HH:mm:ss z"))
        );
        
        sendDiscord(message);
        logDiscordSent(agent.id, ticker, "BUY_ALERT");
        System.out.println("[AIToolAgent] BUY ALERT sent for " + ticker + " via agent " + agent.id);
    }

    private static void notifyTradeIfEnabled(String agentId, Trade trade, AgentPerformance perf) {
        // POST-TRADE notifications are now DISABLED
        // Both monitored and tracked agents get IMMEDIATE BUY ALERT via sendBuyAlertIfMonitored()
        // This function is kept for potential future use (e.g., trade result summaries)
        
        // Uncomment below if you want post-trade result notifications in addition to BUY alerts:
        /*
        boolean trackedNotify = ScoringConfig.isTradeNotificationEnabled(agentId);
        if (!trackedNotify) return;
        
        String winLoss = trade.status.equals("CLOSED_WIN") ? "✅ WIN" : "❌ LOSS";
        String message = String.format(
            "%s **Trade Closed**: %s | %s\n" +
            "Entry: $%.2f → Exit: $%.2f | P/L: %+.2f%%",
            winLoss, trade.ticker, perf.agentName,
            trade.entryPrice, trade.exitPrice, trade.profitLossPct
        );
        sendDiscord(message);
        */
    }

    private static void evolveUnderperformingAgents() {
        synchronized (LOCK) {
            for (AgentPerformance perf : systemState.performance.values()) {
                // EVOLUTION DECISION LOGIC:
                // Agent will ABANDON its strategy and create a new evolved version if:
                // 1. Has at least 5 trades (enough data to judge)
                // 2. Win rate is below 70% (underperforming)
                // 3. Has not already evolved in this session
                // 4. Agent is NOT locked (locked agents are protected from evolution)
                
                AgentConfig original = systemState.agents.get(perf.agentId);
                if (original == null) continue;
                
                // Skip locked agents - they are protected from evolution
                if (original.locked) {
                    continue;
                }
                
                boolean hasEnoughTrades = perf.totalTrades >= 5;
                boolean isUnderperforming = perf.winRate < 70;
                boolean hasNotEvolvedYet = !perf.configChanged;
                
                if (hasEnoughTrades && isUnderperforming && hasNotEvolvedYet) {
                    
                    // Build evolution reason
                    String reason = String.format(
                        "Win rate %.1f%% < 70%% threshold after %d trades. Total P/L: $%.2f",
                        perf.winRate, perf.totalTrades, perf.totalProfitLoss
                    );
                    
                    System.out.println("[AIToolAgent] EVOLUTION TRIGGERED for " + original.id);
                    System.out.println("[AIToolAgent] Reason: " + reason);
                    
                    // STEP 1: Get AI suggestion FIRST (synchronously) so we can apply it
                    System.out.println("[AIToolAgent] Requesting AI improvement suggestions for " + original.id + "...");
                    AIParameterSuggestion aiSuggestion = getStructuredAISuggestion(original, perf);
                    
                    // STEP 2: Create evolved version with AI-guided parameters (or random if AI unavailable)
                    EvolutionResult evolveResult = evolveAgentWithAI(original, perf, aiSuggestion);
                    if (evolveResult != null && evolveResult.evolved != null) {
                        AgentConfig evolved = evolveResult.evolved;
                        
                        // Store AI suggestion in evolved agent
                        if (aiSuggestion != null && aiSuggestion.rawSuggestion != null) {
                            evolved.aiSuggestion = aiSuggestion.rawSuggestion;
                        }
                        
                        systemState.agents.put(evolved.id, evolved);
                        
                        AgentPerformance newPerf = new AgentPerformance();
                        newPerf.agentId = evolved.id;
                        newPerf.agentName = evolved.name;
                        newPerf.type = evolved.type;
                        newPerf.generation = evolved.generation;
                        newPerf.configChanged = false; // New agent starts fresh
                        systemState.performance.put(evolved.id, newPerf);
                        systemState.tradeHistory.put(evolved.id, new ArrayList<>());
                        
                        // Mark original as having spawned evolution
                        perf.configChanged = true;
                        perf.newConfigName = evolved.id;
                        perf.evolutionReason = reason;
                        
                        // Log the evolution event
                        EvolutionEvent event = new EvolutionEvent();
                        event.timestamp = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        event.originalAgentId = original.id;
                        event.newAgentId = evolved.id;
                        event.reason = reason;
                        event.oldWinRate = perf.winRate;
                        event.oldTrades = perf.totalTrades;
                        event.oldProfitLoss = perf.totalProfitLoss;
                        event.parameterChanges = evolveResult.changes;
                        if (aiSuggestion != null && aiSuggestion.rawSuggestion != null) {
                            event.aiSuggestion = aiSuggestion.rawSuggestion;
                        }
                        
                        systemState.evolutionLog.add(event);
                        
                        // Save evolved agent to file
                        saveEvolvedAgent(evolved);
                        
                        // Save state immediately after evolution
                        saveState();
                        
                        System.out.println("[AIToolAgent] Created evolved agent: " + evolved.id);
                        System.out.println("[AIToolAgent] Parameter changes: " + evolveResult.changes);
                        if (aiSuggestion != null && aiSuggestion.parsed) {
                            System.out.println("[AIToolAgent] AI suggestions APPLIED: " + 
                                aiSuggestion.entryFilterChanges.size() + " entry filters, " +
                                aiSuggestion.riskManagementChanges.size() + " risk mgmt, " +
                                aiSuggestion.newFilters.size() + " new filters");
                        } else {
                            System.out.println("[AIToolAgent] AI unavailable - used random mutations");
                        }
                    }
                }
            }
        }
    }

    private static class EvolutionResult {
        AgentConfig evolved;
        Map<String, String> changes = new HashMap<>();
    }

    // Evolve agent using AI suggestions (or fall back to random mutations)
    private static EvolutionResult evolveAgentWithAI(AgentConfig original, AgentPerformance perf, AIParameterSuggestion aiSuggestion) {
        try {
            EvolutionResult result = new EvolutionResult();
            AgentConfig evolved = new AgentConfig();
            evolved.id = original.id + "_GEN" + (original.generation + 1);
            evolved.name = original.name + " (Gen " + (original.generation + 1) + ")";
            evolved.type = original.type;
            evolved.sourceFile = NEW_STRATEGIES_DIR.resolve(evolved.id + ".json").toString();
            evolved.generation = original.generation + 1;
            evolved.parentId = original.id;
            evolved.lastModified = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            
            // Copy original filters
            evolved.entryFilters = new HashMap<>(original.entryFilters);
            evolved.riskManagement = new HashMap<>(original.riskManagement);
            evolved.scoring = new HashMap<>(original.scoring);
            
            // Check if we have valid AI suggestions to apply
            if (aiSuggestion != null && aiSuggestion.parsed) {
                System.out.println("[AIToolAgent] Applying AI-suggested parameters...");
                
                // Apply entry filter changes from AI
                for (Map.Entry<String, Double> change : aiSuggestion.entryFilterChanges.entrySet()) {
                    String key = change.getKey();
                    double newVal = change.getValue();
                    Object oldVal = evolved.entryFilters.get(key);
                    
                    // Validate the value is within reasonable bounds
                    newVal = validateParameterValue(key, newVal);
                    
                    if (oldVal != null) {
                        result.changes.put(key, String.format("%.2f -> %.2f (AI)", ((Number) oldVal).doubleValue(), newVal));
                    } else {
                        result.changes.put(key, String.format("(new) -> %.2f (AI)", newVal));
                    }
                    evolved.entryFilters.put(key, newVal);
                }
                
                // Apply risk management changes from AI
                for (Map.Entry<String, Double> change : aiSuggestion.riskManagementChanges.entrySet()) {
                    String key = change.getKey();
                    double newVal = change.getValue();
                    Object oldVal = evolved.riskManagement.get(key);
                    
                    // Validate the value is within reasonable bounds
                    newVal = validateParameterValue(key, newVal);
                    
                    if (oldVal != null) {
                        result.changes.put(key, String.format("%.2f -> %.2f (AI)", ((Number) oldVal).doubleValue(), newVal));
                    } else {
                        result.changes.put(key, String.format("(new) -> %.2f (AI)", newVal));
                    }
                    evolved.riskManagement.put(key, newVal);
                }
                
                // Add NEW filters suggested by AI
                for (Map.Entry<String, Double> newFilter : aiSuggestion.newFilters.entrySet()) {
                    String key = newFilter.getKey();
                    double newVal = newFilter.getValue();
                    
                    // Validate the value
                    newVal = validateParameterValue(key, newVal);
                    
                    // Add to entry filters (most new filters are entry filters)
                    evolved.entryFilters.put(key, newVal);
                    result.changes.put(key, String.format("NEW FILTER: %.2f (AI)", newVal));
                    System.out.println("[AIToolAgent] Added new filter from AI: " + key + " = " + newVal);
                }
                
                // Apply boolean entry filter changes from AI
                for (Map.Entry<String, Boolean> change : aiSuggestion.booleanFilterChanges.entrySet()) {
                    String key = change.getKey();
                    boolean newVal = change.getValue();
                    Object oldVal = evolved.entryFilters.get(key);
                    result.changes.put(key, String.format("%s -> %s (AI)", oldVal != null ? oldVal.toString() : "null", newVal));
                    evolved.entryFilters.put(key, newVal);
                    System.out.println("[AIToolAgent] Applied boolean filter from AI: " + key + " = " + newVal);
                }
                
            } else {
                // Fall back to random mutations if AI is unavailable
                System.out.println("[AIToolAgent] AI unavailable, using random mutations...");
                return evolveAgentWithTracking(original, perf);
            }
            
            result.evolved = evolved;
            return result;
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error evolving agent with AI: " + e.getMessage());
            // Fall back to random mutations
            return evolveAgentWithTracking(original, perf);
        }
    }

    // Validate parameter values to ensure they're within reasonable bounds
    private static double validateParameterValue(String paramName, double value) {
        String lowerName = paramName.toLowerCase();
        
        // RSI bounds (0-100)
        if (lowerName.contains("rsi")) {
            return Math.max(0, Math.min(100, value));
        }
        // Percentage bounds (0-100)
        if (lowerName.contains("pct") || lowerName.contains("percent")) {
            return Math.max(0.1, Math.min(50, value));
        }
        // Multipliers (0.1-10)
        if (lowerName.contains("multiplier")) {
            return Math.max(0.1, Math.min(10, value));
        }
        // Min thresholds (0-10)
        if (lowerName.contains("min") && !lowerName.contains("rsi")) {
            return Math.max(0, Math.min(10, value));
        }
        // Volume thresholds (allow large values up to 100M)
        if (lowerName.contains("volume")) {
            return Math.max(0, Math.min(100_000_000, value));
        }
        // CCI bounds (-200 to 200)
        if (lowerName.contains("cci")) {
            return Math.max(-200, Math.min(200, value));
        }
        // Max thresholds (allow negative for indicators like CCI-based max)
        if (lowerName.contains("max") && !lowerName.contains("rsi")) {
            return Math.max(-1000, Math.min(1000, value));
        }
        // Default: allow reasonable range
        return Math.max(-1000, Math.min(1000, value));
    }

    private static EvolutionResult evolveAgentWithTracking(AgentConfig original, AgentPerformance perf) {
        try {
            EvolutionResult result = new EvolutionResult();
            AgentConfig evolved = new AgentConfig();
            evolved.id = original.id + "_GEN" + (original.generation + 1);
            evolved.name = original.name + " (Gen " + (original.generation + 1) + ")";
            evolved.type = original.type;
            evolved.sourceFile = NEW_STRATEGIES_DIR.resolve(evolved.id + ".json").toString();
            evolved.generation = original.generation + 1;
            evolved.parentId = original.id;
            evolved.lastModified = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            
            // Copy and mutate entry filters with tracking
            evolved.entryFilters = new HashMap<>(original.entryFilters);
            Random rand = new Random();
            
            // Mutate RSI range
            if (evolved.entryFilters.containsKey("rsiMin")) {
                double oldVal = ((Number) evolved.entryFilters.get("rsiMin")).doubleValue();
                double newVal = Math.max(10, oldVal + (rand.nextDouble() - 0.5) * 20);
                evolved.entryFilters.put("rsiMin", newVal);
                result.changes.put("rsiMin", String.format("%.1f -> %.1f", oldVal, newVal));
            }
            if (evolved.entryFilters.containsKey("rsiMax")) {
                double oldVal = ((Number) evolved.entryFilters.get("rsiMax")).doubleValue();
                double newVal = Math.min(95, oldVal + (rand.nextDouble() - 0.5) * 20);
                evolved.entryFilters.put("rsiMax", newVal);
                result.changes.put("rsiMax", String.format("%.1f -> %.1f", oldVal, newVal));
            }
            
            // Mutate RS threshold
            if (evolved.entryFilters.containsKey("rsMin")) {
                double oldVal = ((Number) evolved.entryFilters.get("rsMin")).doubleValue();
                double newVal = Math.max(0.9, Math.min(1.3, oldVal + (rand.nextDouble() - 0.5) * 0.1));
                evolved.entryFilters.put("rsMin", newVal);
                result.changes.put("rsMin", String.format("%.2f -> %.2f", oldVal, newVal));
            }
            
            // Mutate RVOL
            if (evolved.entryFilters.containsKey("rvolMin")) {
                double oldVal = ((Number) evolved.entryFilters.get("rvolMin")).doubleValue();
                double newVal = Math.max(0.5, Math.min(3.0, oldVal + (rand.nextDouble() - 0.5) * 0.5));
                evolved.entryFilters.put("rvolMin", newVal);
                result.changes.put("rvolMin", String.format("%.2f -> %.2f", oldVal, newVal));
            }
            
            // Copy and mutate risk management
            evolved.riskManagement = new HashMap<>(original.riskManagement);
            if (evolved.riskManagement.containsKey("stopLossPct")) {
                double oldVal = ((Number) evolved.riskManagement.get("stopLossPct")).doubleValue();
                double newVal = Math.max(1.0, Math.min(8.0, oldVal + (rand.nextDouble() - 0.5) * 2));
                evolved.riskManagement.put("stopLossPct", newVal);
                result.changes.put("stopLossPct", String.format("%.1f%% -> %.1f%%", oldVal, newVal));
            }
            if (evolved.riskManagement.containsKey("takeProfitPct")) {
                double oldVal = ((Number) evolved.riskManagement.get("takeProfitPct")).doubleValue();
                double newVal = Math.max(2.0, Math.min(20.0, oldVal + (rand.nextDouble() - 0.5) * 4));
                evolved.riskManagement.put("takeProfitPct", newVal);
                result.changes.put("takeProfitPct", String.format("%.1f%% -> %.1f%%", oldVal, newVal));
            }
            
            evolved.scoring = new HashMap<>(original.scoring);
            
            result.evolved = evolved;
            return result;
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error evolving agent: " + e.getMessage());
            return null;
        }
    }

    private static AgentConfig evolveAgent(AgentConfig original) {
        try {
            AgentConfig evolved = new AgentConfig();
            evolved.id = original.id + "_GEN" + (original.generation + 1);
            evolved.name = original.name + " (Gen " + (original.generation + 1) + ")";
            evolved.type = original.type;
            evolved.sourceFile = NEW_STRATEGIES_DIR.resolve(evolved.id + ".json").toString();
            evolved.generation = original.generation + 1;
            evolved.parentId = original.id;
            evolved.lastModified = ZonedDateTime.now(NY).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
            
            // Copy and mutate entry filters
            evolved.entryFilters = new HashMap<>(original.entryFilters);
            Random rand = new Random();
            
            // Mutate RSI range
            if (evolved.entryFilters.containsKey("rsiMin")) {
                double val = ((Number) evolved.entryFilters.get("rsiMin")).doubleValue();
                val = Math.max(10, val + (rand.nextDouble() - 0.5) * 20); // +/- 10
                evolved.entryFilters.put("rsiMin", val);
            }
            if (evolved.entryFilters.containsKey("rsiMax")) {
                double val = ((Number) evolved.entryFilters.get("rsiMax")).doubleValue();
                val = Math.min(95, val + (rand.nextDouble() - 0.5) * 20);
                evolved.entryFilters.put("rsiMax", val);
            }
            
            // Mutate RS threshold
            if (evolved.entryFilters.containsKey("rsMin")) {
                double val = ((Number) evolved.entryFilters.get("rsMin")).doubleValue();
                val = Math.max(0.9, Math.min(1.3, val + (rand.nextDouble() - 0.5) * 0.1));
                evolved.entryFilters.put("rsMin", val);
            }
            
            // Mutate RVOL
            if (evolved.entryFilters.containsKey("rvolMin")) {
                double val = ((Number) evolved.entryFilters.get("rvolMin")).doubleValue();
                val = Math.max(0.5, Math.min(3.0, val + (rand.nextDouble() - 0.5) * 0.5));
                evolved.entryFilters.put("rvolMin", val);
            }
            
            // Copy and mutate risk management
            evolved.riskManagement = new HashMap<>(original.riskManagement);
            if (evolved.riskManagement.containsKey("stopLossPct")) {
                double val = ((Number) evolved.riskManagement.get("stopLossPct")).doubleValue();
                val = Math.max(1.0, Math.min(8.0, val + (rand.nextDouble() - 0.5) * 2));
                evolved.riskManagement.put("stopLossPct", val);
            }
            if (evolved.riskManagement.containsKey("takeProfitPct")) {
                double val = ((Number) evolved.riskManagement.get("takeProfitPct")).doubleValue();
                val = Math.max(2.0, Math.min(20.0, val + (rand.nextDouble() - 0.5) * 4));
                evolved.riskManagement.put("takeProfitPct", val);
            }
            
            evolved.scoring = new HashMap<>(original.scoring);
            
            return evolved;
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error evolving agent: " + e.getMessage());
            return null;
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
            
            // Save AI suggestion if available
            if (agent.aiSuggestion != null && !agent.aiSuggestion.isEmpty()) {
                root.put("aiSuggestion", agent.aiSuggestion);
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

    // Status tracking for top agents full scan
    private static volatile boolean topAgentsFullScanRunning = false;
    private static volatile String topAgentsFullScanStatus = "";
    private static volatile int topAgentsFullScanProgress = 0;
    private static volatile int topAgentsFullScanTotal = 0;
    private static final List<Trade> recentFullScanTrades = Collections.synchronizedList(new ArrayList<>());
    private static volatile List<String> selectedAgentsForScan = null;

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
        
        // Sort by combined score: winRate * log(trades+1) to balance both factors
        qualified.sort((a, b) -> {
            double scoreA = a.winRate * Math.log(a.totalTrades + 1);
            double scoreB = b.winRate * Math.log(b.totalTrades + 1);
            return Double.compare(scoreB, scoreA);
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
                // Use selected agents
                for (String agentId : selectedAgentsForScan) {
                    AgentPerformance perf = systemState.performance.get(agentId);
                    if (perf != null) {
                        agentsToRun.add(perf);
                    }
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
            
            // Build agent names for notification
            StringBuilder agentNames = new StringBuilder();
            for (AgentPerformance p : agentsToRun) {
                agentNames.append("• **").append(p.agentId).append("** (").append(String.format("%.1f%%", p.winRate)).append(" win rate, ").append(p.totalTrades).append(" trades)\n");
            }
            
            // Send Discord notification
            String startMsg = String.format(
                "🚀 **Full Scan Started**\n" +
                "Scanning **%d tickers** with %d agents:\n%s" +
                "Estimated time: ~%d minutes (API rate limited)",
                allTickers.size(),
                agentsToRun.size(),
                agentNames.toString(),
                (allTickers.size() * agentsToRun.size() * 13) / 60 // ~13 seconds per ticker
            );
            sendDiscord(startMsg);
            
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
                if (agent == null) continue;
                
                System.out.println("[AIToolAgent] Full scan with agent: " + agent.id);
                
                for (String ticker : allTickers) {
                    try {
                        topAgentsFullScanStatus = "Analyzing " + ticker + " with " + agent.id + " (" + totalAnalyzed + "/" + topAgentsFullScanTotal + ")";
                        
                        // Analyze stock
                        TradeDecision decision = analyzeStock(ticker, agent);
                        
                        if (decision != null && decision.shouldTrade) {
                            // Log BUY signal detected
                            logBuySignal(agent.id, ticker, decision.suggestedStopLoss / (1 - 0.03), 
                                decision.suggestedStopLoss, decision.suggestedTakeProfit);
                            
                            // Send BUY ALERT notification BEFORE executing trade
                            sendBuyAlertIfMonitored(agent, ticker, decision);
                            
                            Trade trade = executeTrade(agent, ticker, decision);
                            if (trade != null) {
                                // Log trade execution (position is now OPEN)
                                logTradeExecuted(agent.id, ticker, trade.entryPrice, trade.quantity);
                                
                                synchronized (LOCK) {
                                    systemState.tradeHistory.computeIfAbsent(agent.id, k -> new ArrayList<>()).add(trade);
                                }
                                updatePerformance(agent.id, trade);
                                totalTrades++;
                                
                                // Position is OPEN - will be closed at end of day by closeOpenPositions()
                                // DO NOT log TRADE_CLOSED here - it happens at EOD
                                
                                // Add to recent full scan trades (keep last 20)
                                synchronized (recentFullScanTrades) {
                                    recentFullScanTrades.add(0, trade);
                                    while (recentFullScanTrades.size() > 20) {
                                        recentFullScanTrades.remove(recentFullScanTrades.size() - 1);
                                    }
                                }
                                
                                System.out.println("[AIToolAgent] OPEN POSITION: " + agent.id + " " + trade.action + " " + ticker + 
                                    " Entry=$" + String.format("%.2f", trade.entryPrice) + 
                                    " SL=$" + String.format("%.2f", trade.stopLoss) + 
                                    " TP=$" + String.format("%.2f", trade.takeProfit));
                            }
                        }
                        
                        totalAnalyzed++;
                        topAgentsFullScanProgress = totalAnalyzed;
                        
                        // Rate limit delay - Alpha Vantage free tier = 5 calls/minute
                        Thread.sleep(12500);
                        
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("[AIToolAgent] Full scan error for " + ticker + ": " + e.getMessage());
                        totalAnalyzed++;
                        topAgentsFullScanProgress = totalAnalyzed;
                    }
                }
            }
            
            // Save state
            saveState();
            
            // Send completion notification
            String completeMsg = String.format(
                "✅ **Top 5 Agents Full Scan Complete**\n" +
                "Analyzed: **%d** ticker-agent combinations\n" +
                "Trades executed: **%d**\n" +
                "Check /aitool for updated performance stats.",
                totalAnalyzed,
                totalTrades
            );
            sendDiscord(completeMsg);
            
            // Log scan complete
            logScanComplete(totalAnalyzed, totalTrades);
            
            topAgentsFullScanStatus = "Complete: " + totalAnalyzed + " analyzed, " + totalTrades + " trades";
            System.out.println("[AIToolAgent] Top 5 agents full scan complete: " + totalAnalyzed + " analyzed, " + totalTrades + " trades");
            
        } finally {
            topAgentsFullScanRunning = false;
        }
    }

    // Result class for structured AI suggestions
    public static class AIParameterSuggestion {
        public Map<String, Double> entryFilterChanges = new HashMap<>();
        public Map<String, Double> riskManagementChanges = new HashMap<>();
        public Map<String, Double> newFilters = new HashMap<>(); // New filters to add
        public Map<String, Boolean> booleanFilterChanges = new HashMap<>(); // Boolean entry filter changes
        public String marketConditions;
        public String rawSuggestion; // Original text for display
        public boolean parsed = false;
    }

    private static String getAIImprovementSuggestion(AgentConfig agent, AgentPerformance perf) {
        AIParameterSuggestion suggestion = getStructuredAISuggestion(agent, perf);
        return suggestion != null ? suggestion.rawSuggestion : null;
    }

    // Get structured AI suggestion that can be parsed and applied
    private static AIParameterSuggestion getStructuredAISuggestion(AgentConfig agent, AgentPerformance perf) {
        try {
            // Build detailed prompt requesting JSON output
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a quantitative trading strategy advisor. Analyze this underperforming trading agent and suggest specific parameter improvements.\n\n");
            prompt.append("AGENT CONFIGURATION:\n");
            prompt.append("- ID: ").append(agent.id).append("\n");
            prompt.append("- Type: ").append(agent.type != null ? agent.type : "UNKNOWN").append("\n");
            prompt.append("- Generation: ").append(agent.generation).append("\n");
            
            if (agent.entryFilters != null) {
                prompt.append("- Entry Filters:\n");
                for (Map.Entry<String, Object> entry : agent.entryFilters.entrySet()) {
                    prompt.append("  * ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            if (agent.riskManagement != null) {
                prompt.append("- Risk Management:\n");
                for (Map.Entry<String, Object> entry : agent.riskManagement.entrySet()) {
                    prompt.append("  * ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            
            prompt.append("\nPERFORMANCE (POOR - NEEDS IMPROVEMENT):\n");
            prompt.append("- Win Rate: ").append(String.format("%.1f%%", perf.winRate)).append(" (threshold: 70%)\n");
            prompt.append("- Total Trades: ").append(perf.totalTrades).append("\n");
            prompt.append("- Wins: ").append(perf.wins).append(", Losses: ").append(perf.losses).append("\n");
            prompt.append("- Total P/L: $").append(String.format("%.2f", perf.totalProfitLoss)).append("\n");
            
            prompt.append("\nIMPORTANT: You MUST respond with a JSON block containing your parameter suggestions.\n");
            prompt.append("The JSON must be wrapped in ```json and ``` markers.\n");
            prompt.append("\nJSON FORMAT:\n");
            prompt.append("```json\n");
            prompt.append("{\n");
            prompt.append("  \"entryFilters\": { \"paramName\": newValue, ... },\n");
            prompt.append("  \"riskManagement\": { \"paramName\": newValue, ... },\n");
            prompt.append("  \"newFilters\": { \"newParamName\": value, ... },\n");
            prompt.append("  \"marketConditions\": \"description of ideal market conditions\"\n");
            prompt.append("}\n");
            prompt.append("```\n");
            prompt.append("\nAvailable entry filter parameters: rsiMin, rsiMax, rsMin, rvolMin, cciMin, cciMax, atrMultiplier, sma200Required, smaWindows (array e.g. [50,100] for multi-SMA momentum)\n");
            prompt.append("Available risk management parameters: stopLossPct, takeProfitPct, maxPositionPct, trailingStopPct\n");
            prompt.append("You can suggest NEW filters in 'newFilters' that don't exist yet (e.g., cciStdDev, volumeThreshold, etc.)\n");
            prompt.append("\nAfter the JSON, briefly explain your reasoning (2-3 sentences).");

            // Try Ollama first, then OpenAI
            String result = callOllamaAPI(prompt.toString());
            if (result == null || result.isEmpty() || result.startsWith("(")) {
                result = callOpenAIAPI(prompt.toString());
            }
            
            if (result == null || result.isEmpty()) {
                return null;
            }
            
            // Parse the result
            AIParameterSuggestion suggestion = parseAISuggestion(result, agent);
            suggestion.rawSuggestion = result;
            return suggestion;
            
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error getting AI suggestion: " + e.getMessage());
            return null;
        }
    }

    // Parse AI response to extract JSON parameter suggestions
    private static AIParameterSuggestion parseAISuggestion(String response, AgentConfig agent) {
        AIParameterSuggestion suggestion = new AIParameterSuggestion();
        
        try {
            // Extract JSON block from response
            String jsonStr = null;
            int jsonStart = response.indexOf("```json");
            int jsonEnd = response.indexOf("```", jsonStart + 7);
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = response.substring(jsonStart + 7, jsonEnd).trim();
            } else {
                // Try to find raw JSON object
                int braceStart = response.indexOf("{");
                int braceEnd = response.lastIndexOf("}");
                if (braceStart >= 0 && braceEnd > braceStart) {
                    jsonStr = response.substring(braceStart, braceEnd + 1);
                }
            }
            
            if (jsonStr == null || jsonStr.isEmpty()) {
                System.out.println("[AIToolAgent] No JSON found in AI response, using fallback parsing");
                return parseAISuggestionFallback(response, agent);
            }
            
            // Parse JSON
            JsonNode root = JSON.readTree(jsonStr);
            
            // Parse entryFilters changes
            JsonNode entryFilters = root.get("entryFilters");
            if (entryFilters != null && entryFilters.isObject()) {
                Iterator<String> fields = entryFilters.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    JsonNode val = entryFilters.get(field);
                    if (val.isNumber()) {
                        suggestion.entryFilterChanges.put(field, val.doubleValue());
                    } else if (val.isBoolean()) {
                        suggestion.booleanFilterChanges.put(field, val.booleanValue());
                    }
                }
            }
            
            // Parse riskManagement changes
            JsonNode riskMgmt = root.get("riskManagement");
            if (riskMgmt != null && riskMgmt.isObject()) {
                Iterator<String> fields = riskMgmt.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    JsonNode val = riskMgmt.get(field);
                    if (val.isNumber()) {
                        suggestion.riskManagementChanges.put(field, val.doubleValue());
                    }
                }
            }
            
            // Parse new filters
            JsonNode newFilters = root.get("newFilters");
            if (newFilters != null && newFilters.isObject()) {
                Iterator<String> fields = newFilters.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    JsonNode val = newFilters.get(field);
                    if (val.isNumber()) {
                        suggestion.newFilters.put(field, val.doubleValue());
                    }
                }
            }
            
            // Parse market conditions
            JsonNode marketCond = root.get("marketConditions");
            if (marketCond != null && marketCond.isTextual()) {
                suggestion.marketConditions = marketCond.asText();
            }
            
            suggestion.parsed = true;
            System.out.println("[AIToolAgent] Parsed AI suggestion: " + 
                suggestion.entryFilterChanges.size() + " entry filter changes, " +
                suggestion.riskManagementChanges.size() + " risk mgmt changes, " +
                suggestion.newFilters.size() + " new filters");
            
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Error parsing AI JSON: " + e.getMessage());
            return parseAISuggestionFallback(response, agent);
        }
        
        return suggestion;
    }

    // Fallback parser for non-JSON responses (regex-based)
    private static AIParameterSuggestion parseAISuggestionFallback(String response, AgentConfig agent) {
        AIParameterSuggestion suggestion = new AIParameterSuggestion();
        
        try {
            Set<String> entryFilterKeys = agent.entryFilters != null ? agent.entryFilters.keySet() : new HashSet<>();
            Set<String> riskMgmtKeys = agent.riskManagement != null ? agent.riskManagement.keySet() : new HashSet<>();
            
            // Pattern 1: "Increase/Decrease/Reduce `paramName` to VALUE (from OLD)"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)(?:increase|decrease|reduce|set)\\s+[`'\"]?(\\w+)[`'\"]?\\s+to\\s+([\\d.]+)\\s*\\(from"
            );
            java.util.regex.Matcher matcher = pattern.matcher(response);
            
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String newValueStr = matcher.group(2);
                addParsedParam(suggestion, paramName, newValueStr, entryFilterKeys, riskMgmtKeys);
            }
            
            // Pattern 2: "`paramName` filter... set to VALUE" (for new filters)
            pattern = java.util.regex.Pattern.compile(
                "(?i)[`'\"]?(\\w+)[`'\"]?\\s+filter[^:]*:\\s*set\\s+to\\s+([\\d.]+)"
            );
            matcher = pattern.matcher(response);
            
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String newValueStr = matcher.group(2);
                // These are typically new filters
                if (!entryFilterKeys.contains(paramName) && !riskMgmtKeys.contains(paramName)) {
                    try {
                        suggestion.newFilters.put(paramName, Double.parseDouble(newValueStr));
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            // Pattern 3: "short-term `paramName` filter... set to VALUE (from OLD)"
            pattern = java.util.regex.Pattern.compile(
                "(?i)short-term\\s+[`'\"]?(\\w+)[`'\"]?\\s+filter[^:]*:\\s*set\\s+to\\s+([\\d.]+)"
            );
            matcher = pattern.matcher(response);
            
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String newValueStr = matcher.group(2);
                addParsedParam(suggestion, paramName, newValueStr, entryFilterKeys, riskMgmtKeys);
            }
            
            // Pattern 4: "paramName: oldValue -> newValue"
            pattern = java.util.regex.Pattern.compile(
                "(?i)[`'\"]?(\\w+)[`'\"]?\\s*:\\s*[\\d.]+\\s*(?:->|→)\\s*([\\d.]+)"
            );
            matcher = pattern.matcher(response);
            
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String newValueStr = matcher.group(2);
                addParsedParam(suggestion, paramName, newValueStr, entryFilterKeys, riskMgmtKeys);
            }
            
            // Pattern 5: "paramName to VALUE" for known params ending in Min/Max/Pct/Multiplier
            pattern = java.util.regex.Pattern.compile(
                "(?i)[`'\"]?(\\w+(?:Min|Max|Pct|Multiplier))[`'\"]?\\s+to\\s+([\\d.]+)"
            );
            matcher = pattern.matcher(response);
            
            while (matcher.find()) {
                String paramName = matcher.group(1);
                String newValueStr = matcher.group(2);
                addParsedParam(suggestion, paramName, newValueStr, entryFilterKeys, riskMgmtKeys);
            }
            
            if (!suggestion.entryFilterChanges.isEmpty() || !suggestion.riskManagementChanges.isEmpty() || !suggestion.newFilters.isEmpty()) {
                suggestion.parsed = true;
                System.out.println("[AIToolAgent] Fallback parsed: " + 
                    suggestion.entryFilterChanges.size() + " entry changes, " +
                    suggestion.riskManagementChanges.size() + " risk changes, " +
                    suggestion.newFilters.size() + " new filters");
            }
            
        } catch (Exception e) {
            System.err.println("[AIToolAgent] Fallback parsing error: " + e.getMessage());
        }
        
        return suggestion;
    }
    
    // Helper to add parsed parameter to the right category
    private static void addParsedParam(AIParameterSuggestion suggestion, String paramName, String valueStr, 
                                        Set<String> entryFilterKeys, Set<String> riskMgmtKeys) {
        try {
            double newValue = Double.parseDouble(valueStr);
            
            if (entryFilterKeys.contains(paramName)) {
                suggestion.entryFilterChanges.put(paramName, newValue);
            } else if (riskMgmtKeys.contains(paramName)) {
                suggestion.riskManagementChanges.put(paramName, newValue);
            } else if (isValidParamName(paramName)) {
                // New filter - but only if it looks like a valid param name
                suggestion.newFilters.put(paramName, newValue);
            }
        } catch (NumberFormatException ignored) {}
    }
    
    // Check if a parameter name looks valid (not a common word)
    private static boolean isValidParamName(String name) {
        if (name == null || name.length() < 3) return false;
        String lower = name.toLowerCase();
        // Exclude common words that might be picked up by regex
        Set<String> excluded = new HashSet<>(Arrays.asList(
            "set", "to", "from", "the", "and", "for", "with", "this", "that", "add", "use"
        ));
        return !excluded.contains(lower);
    }

    private static String callOllamaAPI(String prompt) {
        try {
            String body = "{\"model\":\"llama3.2\",\"prompt\":" + jsonEscape(prompt) + ",\"stream\":false}";
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();
            
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(resp.body());
                if (root.has("response")) {
                    return root.get("response").asText();
                }
            }
        } catch (Exception e) {
            // Ollama not available, will try OpenAI
        }
        return null;
    }

    private static String callOpenAIAPI(String prompt) {
        try {
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) return null;
            
            String body = "{\n" +
                    "\"model\":\"gpt-4o-mini\",\n" +
                    "\"messages\":[{" +
                    "\"role\":\"system\",\"content\":\"You are a quantitative trading strategy advisor. Be concise and specific.\"}," +
                    "{\"role\":\"user\",\"content\":" + jsonEscape(prompt) + "}],\n" +
                    "\"temperature\":0.3,\n" +
                    "\"max_tokens\":300\n" +
                    "}";
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(resp.body());
                com.fasterxml.jackson.databind.JsonNode msg = root.path("choices").isArray() && root.path("choices").size() > 0
                        ? root.path("choices").get(0).path("message").path("content") : null;
                if (msg != null && msg.isTextual()) {
                    return msg.asText();
                }
            }
        } catch (Exception e) {
            System.err.println("[AIToolAgent] OpenAI API error: " + e.getMessage());
        }
        return null;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
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

    public static List<EvolutionEvent> getEvolutionLog() {
        synchronized (LOCK) {
            if (systemState == null || systemState.evolutionLog == null) {
                return new ArrayList<>();
            }
            // Filter for last 2 days only
            ZonedDateTime twoDaysAgo = ZonedDateTime.now(NY).minusDays(2).toLocalDate().atStartOfDay(NY);
            List<EvolutionEvent> filtered = new ArrayList<>();
            for (EvolutionEvent evt : systemState.evolutionLog) {
                try {
                    ZonedDateTime evtTime = ZonedDateTime.parse(evt.timestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    if (evtTime.isAfter(twoDaysAgo)) {
                        filtered.add(evt);
                    }
                } catch (Exception e) {
                    // If parsing fails, include the event anyway
                    filtered.add(evt);
                }
            }
            // Return in reverse order (newest first)
            Collections.reverse(filtered);
            return filtered;
        }
    }

    // Discord notification - public wrapper for external calls
    public static boolean sendDiscordPublic(String text) {
        return sendDiscord(text);
    }

    // Discord notification
    private static boolean sendDiscord(String text) {
        try {
            if (DISCORD_WEBHOOK_URL == null || DISCORD_WEBHOOK_URL.isBlank()) {
                System.out.println("[AIToolAgent] Discord webhook not configured (DAILY_SIM_DISCORD_WEBHOOK_URL env var)");
                return false;
            }
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
            System.out.println("[AIToolAgent][Discord] Sent notification, status: " + resp.statusCode());
            return resp.statusCode() == 200 || resp.statusCode() == 204;
        } catch (Exception e) {
            System.err.println("[AIToolAgent][Discord] Failed to send: " + e.getMessage());
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
}
