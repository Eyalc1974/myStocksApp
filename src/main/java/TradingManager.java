import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradingManager - Unified trading manager supporting both fake/test and real trading modes
 * 
 * Features:
 * - Switch between fake (simulation) and real (Alpaca API) trading modes
 * - Configurable position sizes, stop-loss, take-profit
 * - Per-stock max loss limit ($30 default)
 * - Total capital limit ($5K default)
 * - Discord notifications for real trades
 */
public class TradingManager {
    
    private static Path CONFIG_PATH;
    private static TradingConfig config;
    private static AlpacaTradingClient alpacaClient;
    private static JDA discordJda;
    private static boolean initialized = false;
    
    static {
        // Try to find config file in multiple locations
        String[] possiblePaths = {
            "trading-config.json",
            "./trading-config.json",
            "../trading-config.json",
            "../../trading-config.json"
        };
        
        for (String path : possiblePaths) {
            Path testPath = Paths.get(path);
            if (Files.exists(testPath)) {
                CONFIG_PATH = testPath.toAbsolutePath();
                System.out.println("[TradingManager] Found config at: " + CONFIG_PATH);
                break;
            }
        }
        
        if (CONFIG_PATH == null) {
            CONFIG_PATH = Paths.get("trading-config.json");
            System.err.println("[TradingManager] Config file not found, will use default path: " + CONFIG_PATH);
        }
    }
    
    /**
     * Set config path manually (useful for testing)
     */
    public static void setConfigPath(String path) {
        CONFIG_PATH = Paths.get(path);
        System.out.println("[TradingManager] Config path set to: " + CONFIG_PATH);
    }
    
    // Trading mode enum
    public enum TradingMode {
        FAKE,
        REAL
    }
    
    // Trade record for tracking
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TradeRecord {
        public String id;
        public String symbol;
        public TradingMode mode;
        public String side; // BUY or SELL
        public double quantity;
        public double price;
        public double totalValue;
        public String timestamp;
        public String status; // FILLED, PENDING, CANCELLED, FAILED
        public String orderId; // Alpaca order ID for real trades
        public String strategy;
        public double stopLossPrice;
        public double takeProfitPrice;
        public String exitReason;
        public double profitLoss;
        public double profitLossPct;
        
        public TradeRecord() {}
        
        public TradeRecord(String symbol, TradingMode mode, String side, double quantity, double price, String strategy) {
            this.id = java.util.UUID.randomUUID().toString();
            this.symbol = symbol;
            this.mode = mode;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
            this.totalValue = quantity * price;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            this.status = "PENDING";
            this.strategy = strategy;
        }
    }
    
    // Configuration class
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TradingConfig {
        public String description;
        public String tradingMode = "FAKE";
        
        public FakeTradingConfig fakeTrading = new FakeTradingConfig();
        public RealTradingConfig realTrading = new RealTradingConfig();
        public DiscordConfig discord = new DiscordConfig();
        public RiskManagementConfig riskManagement = new RiskManagementConfig();
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FakeTradingConfig {
        public boolean enabled = true;
        public double startingCapital = 5000.0;
        public double positionSize = 1000.0;
        public int maxPositions = 10;
        public double stopLossPct = 2.0;
        public double takeProfitPct = 4.0;
        public double trailingStopPct = 1.5;
        public double maxLossPerStock = 30.0;
        public String storePath = "daily-trading-sim.json";
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RealTradingConfig {
        public boolean enabled = false;
        public AlpacaConfig alpaca = new AlpacaConfig();
        public double startingCapital = 5000.0;
        public double positionSize = 1000.0;
        public int maxPositions = 10;
        public double stopLossPct = 2.0;
        public double takeProfitPct = 4.0;
        public double trailingStopPct = 1.5;
        public double maxLossPerStock = 30.0;
        public boolean useBracketOrders = true;
        public String storePath = "real-trading.json";
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaConfig {
        public String apiKey;
        public String apiSecret;
        public String baseUrl = "https://paper-api.alpaca.markets";
        public boolean usePaperTrading = true;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiscordConfig {
        public boolean enabled = true;
        public String webhookUrl;
        public String channelId;
        public boolean notifyOnBuy = true;
        public boolean notifyOnSell = true;
        public boolean notifyOnStopLoss = true;
        public boolean notifyOnTakeProfit = true;
        public boolean notifyOnError = true;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RiskManagementConfig {
        public double maxDailyLossPct = 5.0;
        public double maxTotalLossPct = 10.0;
        public double maxPositionSizePct = 20.0;
        public boolean enableCircuitBreaker = true;
    }
    
    // Store for tracking trades
    private static final Map<String, TradeRecord> activeTrades = new ConcurrentHashMap<>();
    private static final List<TradeRecord> tradeHistory = new ArrayList<>();
    private static double currentCapital = 5000.0;
    private static double dailyLoss = 0.0;
    
    /**
     * Initialize the TradingManager
     */
    public static boolean initialize() {
        try {
            // Load configuration
            config = loadConfig();
            
            // Set current capital based on mode
            if (config.tradingMode.equals("REAL")) {
                currentCapital = config.realTrading.startingCapital;
                
                // Initialize Alpaca client if real trading is enabled
                String apiKey = config.realTrading.alpaca.apiKey;
                String apiSecret = config.realTrading.alpaca.apiSecret;
                boolean usePaperTrading = config.realTrading.alpaca.usePaperTrading;
                
                // Fallback to environment variables if config doesn't have credentials
                if ((apiKey == null || apiKey.isEmpty()) && System.getenv("ALPACA_API_KEY") != null) {
                    apiKey = System.getenv("ALPACA_API_KEY");
                    apiSecret = System.getenv("ALPACA_API_SECRET");
                    String paperTradingEnv = System.getenv("ALPACA_PAPER_TRADING");
                    if (paperTradingEnv != null) {
                        usePaperTrading = Boolean.parseBoolean(paperTradingEnv);
                    }
                    System.out.println("[TradingManager] Using Alpaca credentials from environment variables");
                }
                
                if (config.realTrading.enabled && apiKey != null && !apiKey.isEmpty()) {
                    alpacaClient = new AlpacaTradingClient(
                        apiKey,
                        apiSecret,
                        usePaperTrading
                    );
                    
                    if (!alpacaClient.initialize()) {
                        System.err.println("[TradingManager] Failed to initialize Alpaca client");
                        return false;
                    }
                    
                    // Get actual buying power from Alpaca
                    double alpacaBuyingPower = alpacaClient.getBuyingPower();
                    if (alpacaBuyingPower > 0) {
                        currentCapital = Math.min(currentCapital, alpacaBuyingPower);
                        System.out.println("[TradingManager] Using Alpaca buying power: $" + currentCapital);
                    }
                }
            } else {
                currentCapital = config.fakeTrading.startingCapital;
            }
            
            // Initialize Discord webhook if enabled
            if (config.discord.enabled && config.discord.webhookUrl != null 
                && !config.discord.webhookUrl.isEmpty()) {
                initializeDiscordWebhook();
            }
            
            initialized = true;
            System.out.println("[TradingManager] Initialized successfully in " + config.tradingMode + " mode");
            System.out.println("[TradingManager] Starting capital: $" + currentCapital);
            return true;
            
        } catch (Exception e) {
            System.err.println("[TradingManager] Initialization failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Load configuration from file
     */
    private static TradingConfig loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(CONFIG_PATH.toFile(), TradingConfig.class);
            }
        } catch (Exception e) {
            System.err.println("[TradingManager] Error loading config: " + e.getMessage());
        }
        return new TradingConfig(); // Return default config
    }
    
    /**
     * Save configuration to file
     */
    public static boolean saveConfig() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), config);
            return true;
        } catch (Exception e) {
            System.err.println("[TradingManager] Error saving config: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get current trading mode
     */
    public static TradingMode getTradingMode() {
        return TradingMode.valueOf(config.tradingMode);
    }
    
    /**
     * Check if in real trading mode
     */
    public static boolean isRealTrading() {
        return getTradingMode() == TradingMode.REAL && config.realTrading.enabled;
    }
    
    /**
     * Check if in fake trading mode
     */
    public static boolean isFakeTrading() {
        return getTradingMode() == TradingMode.FAKE && config.fakeTrading.enabled;
    }
    
    /**
     * Get current capital
     */
    public static double getCurrentCapital() {
        if (isRealTrading() && alpacaClient != null) {
            return alpacaClient.getBuyingPower();
        }
        return currentCapital;
    }
    
    /**
     * Get position size for a trade
     */
    public static double getPositionSize() {
        if (isRealTrading()) {
            return config.realTrading.positionSize;
        }
        return config.fakeTrading.positionSize;
    }
    
    /**
     * Get max loss per stock
     */
    public static double getMaxLossPerStock() {
        if (isRealTrading()) {
            return config.realTrading.maxLossPerStock;
        }
        return config.fakeTrading.maxLossPerStock;
    }
    
    /**
     * Calculate stop-loss price based on entry price and max loss per stock
     */
    public static double calculateStopLossPrice(double entryPrice, double shares) {
        double maxLossPerStock = getMaxLossPerStock();
        double maxTotalLoss = maxLossPerStock * shares;
        double stopLossPct = (maxTotalLoss / (entryPrice * shares)) * 100;
        
        // Use the more restrictive of percentage-based or dollar-based stop loss
        double configStopLossPct = isRealTrading() ? config.realTrading.stopLossPct : config.fakeTrading.stopLossPct;
        double finalStopLossPct = Math.min(stopLossPct, configStopLossPct);
        
        double stopLossPrice = entryPrice * (1 - finalStopLossPct / 100.0);
        // Round to 2 decimal places for Alpaca
        return Math.round(stopLossPrice * 100.0) / 100.0;
    }
    
    /**
     * Calculate take-profit price
     */
    public static double calculateTakeProfitPrice(double entryPrice) {
        double takeProfitPct = isRealTrading() ? config.realTrading.takeProfitPct : config.fakeTrading.takeProfitPct;
        double takeProfitPrice = entryPrice * (1 + takeProfitPct / 100.0);
        // Round to 2 decimal places for Alpaca
        return Math.round(takeProfitPrice * 100.0) / 100.0;
    }
    
    /**
     * Execute a buy order
     */
    public static TradeRecord buy(String symbol, double price, String strategy) {
        return buy(symbol, price, strategy, null);
    }
    
    /**
     * Execute a buy order with custom quantity
     */
    public static TradeRecord buy(String symbol, double price, String strategy, Double customQuantity) {
        if (!initialized) {
            System.err.println("[TradingManager] Not initialized");
            return null;
        }
        
        // Calculate quantity based on position size
        double positionSize = customQuantity != null ? customQuantity : getPositionSize();
        double quantity = positionSize / price;
        
        // Round to whole number for bracket orders (fractional orders have limitations with GTC)
        if (isRealTrading() && config.realTrading.useBracketOrders) {
            quantity = Math.floor(quantity);
            if (quantity < 1) {
                System.err.println("[TradingManager] Position size too small for at least 1 share");
                return null;
            }
        }
        
        // Check capital limit
        double currentCapital = getCurrentCapital();
        if (positionSize > currentCapital) {
            System.err.println("[TradingManager] Insufficient capital: need $" + positionSize + 
                ", have $" + currentCapital);
            sendDiscordNotification("❌ **BUY FAILED** - Insufficient capital for " + symbol);
            return null;
        }
        
        TradeRecord trade = new TradeRecord(symbol, getTradingMode(), "BUY", quantity, price, strategy);
        trade.stopLossPrice = calculateStopLossPrice(price, quantity);
        trade.takeProfitPrice = calculateTakeProfitPrice(price);
        
        if (isRealTrading() && alpacaClient != null) {
            // Execute real trade via Alpaca
            try {
                JsonNode alpacaOrder;
                
                if (config.realTrading.useBracketOrders) {
                    // Use bracket order with TP and SL
                    double stopLossLimitPrice = Math.round((trade.stopLossPrice * 0.99) * 100.0) / 100.0;
                    alpacaOrder = alpacaClient.buyBracket(
                        symbol,
                        quantity,
                        trade.takeProfitPrice,
                        trade.stopLossPrice,
                        stopLossLimitPrice // Stop-limit price slightly below stop price
                    );
                } else {
                    // Simple market order
                    alpacaOrder = alpacaClient.buyMarket(symbol, quantity);
                }
                
                if (alpacaOrder != null) {
                    trade.orderId = alpacaOrder.path("id").asText();
                    trade.status = alpacaOrder.path("status").asText();
                    System.out.println("[TradingManager] Real BUY order placed: " + trade.orderId);
                    
                    // Send Discord notification
                    sendDiscordNotification(buildBuyNotification(trade));
                } else {
                    trade.status = "FAILED";
                    sendDiscordNotification("❌ **BUY FAILED** - Alpaca returned null for " + symbol);
                }
            } catch (Exception e) {
                trade.status = "FAILED";
                System.err.println("[TradingManager] Real BUY failed: " + e.getMessage());
                sendDiscordNotification("❌ **BUY FAILED** - " + symbol + ": " + e.getMessage());
                return null;
            }
        } else {
            // Fake trading - just record the trade
            trade.status = "FILLED";
            currentCapital -= positionSize;
            System.out.println("[TradingManager] Fake BUY: " + symbol + " qty=" + quantity + " price=" + price);
        }
        
        // Track the trade
        activeTrades.put(trade.id, trade);
        tradeHistory.add(trade);
        
        return trade;
    }
    
    /**
     * Execute a sell order
     */
    public static TradeRecord sell(String symbol, double quantity, double price, String reason) {
        if (!initialized) {
            System.err.println("[TradingManager] Not initialized");
            return null;
        }
        
        TradeRecord trade = new TradeRecord(symbol, getTradingMode(), "SELL", quantity, price, "MANUAL");
        trade.exitReason = reason;
        
        if (isRealTrading() && alpacaClient != null) {
            // Execute real trade via Alpaca
            try {
                JsonNode alpacaOrder = alpacaClient.sellMarket(symbol, quantity);
                
                if (alpacaOrder != null) {
                    trade.orderId = alpacaOrder.path("id").asText();
                    trade.status = alpacaOrder.path("status").asText();
                    System.out.println("[TradingManager] Real SELL order placed: " + trade.orderId);
                    
                    // Send Discord notification
                    sendDiscordNotification(buildSellNotification(trade));
                } else {
                    trade.status = "FAILED";
                    sendDiscordNotification("❌ **SELL FAILED** - Alpaca returned null for " + symbol);
                }
            } catch (Exception e) {
                trade.status = "FAILED";
                System.err.println("[TradingManager] Real SELL failed: " + e.getMessage());
                sendDiscordNotification("❌ **SELL FAILED** - " + symbol + ": " + e.getMessage());
                return null;
            }
        } else {
            // Fake trading
            trade.status = "FILLED";
            currentCapital += quantity * price;
            System.out.println("[TradingManager] Fake SELL: " + symbol + " qty=" + quantity + " price=" + price);
        }
        
        tradeHistory.add(trade);
        return trade;
    }
    
    /**
     * Close a position by trade ID
     */
    public static boolean closePosition(String tradeId, String reason) {
        TradeRecord trade = activeTrades.get(tradeId);
        if (trade == null) {
            System.err.println("[TradingManager] Trade not found: " + tradeId);
            return false;
        }
        
        if (isRealTrading() && alpacaClient != null) {
            try {
                alpacaClient.closePosition(trade.symbol);
                sendDiscordNotification(buildCloseNotification(trade, reason));
            } catch (Exception e) {
                System.err.println("[TradingManager] Failed to close position: " + e.getMessage());
                return false;
            }
        }
        
        activeTrades.remove(tradeId);
        return true;
    }
    
    /**
     * Get all active trades
     */
    public static List<TradeRecord> getActiveTrades() {
        return new ArrayList<>(activeTrades.values());
    }
    
    /**
     * Get trade history
     */
    public static List<TradeRecord> getTradeHistory() {
        return new ArrayList<>(tradeHistory);
    }
    
    /**
     * Initialize Discord webhook
     */
    private static void initializeDiscordWebhook() {
        // Webhook is handled via HTTP, not JDA bot
        System.out.println("[TradingManager] Discord webhook configured");
    }
    
    /**
     * Send Discord notification via webhook
     */
    private static void sendDiscordNotification(String message) {
        if (!config.discord.enabled || config.discord.webhookUrl == null 
            || config.discord.webhookUrl.isEmpty()) {
            return;
        }
        
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.discord.webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"content\": \"" + message.replace("\"", "\\\"") + "\"}"
                ))
                .build();
            
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[TradingManager] Discord notification failed: " + e.getMessage());
        }
    }
    
    /**
     * Build buy notification message
     */
    private static String buildBuyNotification(TradeRecord trade) {
        return String.format(
            "🟢 **BUY ORDER PLACED**\n" +
            "Symbol: %s\n" +
            "Quantity: %.2f\n" +
            "Price: $%.2f\n" +
            "Total: $%.2f\n" +
            "Stop Loss: $%.2f\n" +
            "Take Profit: $%.2f\n" +
            "Strategy: %s\n" +
            "Order ID: %s",
            trade.symbol, trade.quantity, trade.price, trade.totalValue,
            trade.stopLossPrice, trade.takeProfitPrice, trade.strategy, trade.orderId
        );
    }
    
    /**
     * Build sell notification message
     */
    private static String buildSellNotification(TradeRecord trade) {
        return String.format(
            "🔴 **SELL ORDER PLACED**\n" +
            "Symbol: %s\n" +
            "Quantity: %.2f\n" +
            "Price: $%.2f\n" +
            "Total: $%.2f\n" +
            "Reason: %s\n" +
            "Order ID: %s",
            trade.symbol, trade.quantity, trade.price, trade.totalValue,
            trade.exitReason, trade.orderId
        );
    }
    
    /**
     * Build close notification message
     */
    private static String buildCloseNotification(TradeRecord trade, String reason) {
        return String.format(
            "⚠️ **POSITION CLOSED**\n" +
            "Symbol: %s\n" +
            "Reason: %s\n" +
            "Entry Price: $%.2f\n" +
            "Exit Price: $%.2f\n" +
            "P/L: $%.2f (%.2f%%)",
            trade.symbol, reason, trade.price, trade.price,
            trade.profitLoss, trade.profitLossPct
        );
    }
    
    /**
     * Shutdown the TradingManager
     */
    public static void shutdown() {
        if (alpacaClient != null) {
            alpacaClient.shutdown();
        }
        if (discordJda != null) {
            discordJda.shutdown();
        }
        initialized = false;
        System.out.println("[TradingManager] Shutdown complete");
    }
    
    /**
     * Get configuration
     */
    public static TradingConfig getConfig() {
        if (config == null) {
            config = loadConfig();
        }
        return config;
    }
}
