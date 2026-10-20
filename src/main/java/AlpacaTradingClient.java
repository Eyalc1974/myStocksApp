import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AlpacaTradingClient - Handles real trading operations via Alpaca API using HTTP requests
 * 
 * Supports:
 * - Buy/Sell orders with market and limit types
 * - Bracket orders with take-profit and stop-loss
 * - Position management
 * - Account information retrieval
 */
public class AlpacaTradingClient {
    
    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private boolean usePaperTrading;
    private boolean initialized = false;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    
    public AlpacaTradingClient(String apiKey, String apiSecret, boolean usePaperTrading) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.usePaperTrading = usePaperTrading;
        this.baseUrl = usePaperTrading ? "https://paper-api.alpaca.markets" : "https://api.alpaca.markets";
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }
    
    public boolean initialize() {
        try {
            // Test connection by getting account info
            JsonNode account = getAccount();
            if (account != null) {
                initialized = true;
                System.out.println("[AlpacaTradingClient] Successfully connected to Alpaca " + 
                    (usePaperTrading ? "Paper Trading" : "Live Trading"));
                System.out.println("[AlpacaTradingClient] Account ID: " + account.path("id").asText());
                System.out.println("[AlpacaTradingClient] Buying Power: $" + account.path("buying_power").asText());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Failed to initialize: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get account information
     */
    public JsonNode getAccount() throws Exception {
        return get("/v2/account");
    }
    
    /**
     * Get current buying power
     */
    public double getBuyingPower() {
        try {
            JsonNode account = getAccount();
            String buyingPower = account.path("buying_power").asText();
            return buyingPower != null && !buyingPower.isEmpty() ? Double.parseDouble(buyingPower) : 0.0;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting buying power: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get current cash balance
     */
    public double getCashBalance() {
        try {
            JsonNode account = getAccount();
            String cash = account.path("cash").asText();
            return cash != null && !cash.isEmpty() ? Double.parseDouble(cash) : 0.0;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting cash balance: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get portfolio equity (total account value including positions)
     */
    public double getPortfolioValue() {
        try {
            JsonNode account = getAccount();
            String equity = account.path("equity").asText();
            return equity != null && !equity.isEmpty() ? Double.parseDouble(equity) : 0.0;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting portfolio value: " + e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get account summary (cash, equity, buying power)
     */
    public String getAccountSummary() {
        try {
            JsonNode account = getAccount();
            double cash = account.path("cash").asDouble();
            double equity = account.path("equity").asDouble();
            double buyingPower = account.path("buying_power").asDouble();
            double longMarketValue = account.path("long_market_value").asDouble();
            
            return String.format(
                "Account Summary:\n" +
                "  Cash: $%.2f\n" +
                "  Equity: $%.2f\n" +
                "  Buying Power: $%.2f\n" +
                "  Long Market Value: $%.2f\n" +
                "  P/L: $%.2f",
                cash, equity, buyingPower, longMarketValue, (equity - cash)
            );
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting account summary: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Get all open positions
     */
    public List<JsonNode> getOpenPositions() {
        try {
            JsonNode response = get("/v2/positions");
            List<JsonNode> positions = new ArrayList<>();
            if (response.isArray()) {
                for (JsonNode node : response) {
                    positions.add(node);
                }
            }
            return positions;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting positions: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Get all orders
     */
    public List<JsonNode> getAllOrders() {
        try {
            JsonNode response = get("/v2/orders");
            List<JsonNode> orders = new ArrayList<>();
            if (response.isArray()) {
                for (JsonNode node : response) {
                    orders.add(node);
                }
            }
            return orders;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting orders: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Place a market buy order
     */
    public JsonNode buyMarket(String symbol, double quantity) throws Exception {
        return buyMarket(symbol, quantity, "day");
    }
    
    /**
     * Place a market buy order with specified time in force
     */
    public JsonNode buyMarket(String symbol, double quantity, String timeInForce) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Alpaca client not initialized");
        }
        
        System.out.println("[AlpacaTradingClient] Placing market BUY order: " + symbol + " qty=" + quantity);
        
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("symbol", symbol);
        orderData.put("qty", quantity);
        orderData.put("side", "buy");
        orderData.put("type", "market");
        orderData.put("time_in_force", timeInForce);
        
        return post("/v2/orders", orderData);
    }
    
    /**
     * Place a limit buy order
     */
    public JsonNode buyLimit(String symbol, double quantity, double limitPrice) throws Exception {
        return buyLimit(symbol, quantity, limitPrice, "day");
    }
    
    /**
     * Place a limit buy order with specified time in force
     */
    public JsonNode buyLimit(String symbol, double quantity, double limitPrice, String timeInForce) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Alpaca client not initialized");
        }
        
        System.out.println("[AlpacaTradingClient] Placing limit BUY order: " + symbol + " qty=" + quantity + 
            " limit=" + limitPrice);
        
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("symbol", symbol);
        orderData.put("qty", quantity);
        orderData.put("side", "buy");
        orderData.put("type", "limit");
        orderData.put("time_in_force", timeInForce);
        orderData.put("limit_price", limitPrice);
        
        return post("/v2/orders", orderData);
    }
    
    /**
     * Place a market sell order
     */
    public JsonNode sellMarket(String symbol, double quantity) throws Exception {
        return sellMarket(symbol, quantity, "day");
    }
    
    /**
     * Place a market sell order with specified time in force
     */
    public JsonNode sellMarket(String symbol, double quantity, String timeInForce) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Alpaca client not initialized");
        }
        
        System.out.println("[AlpacaTradingClient] Placing market SELL order: " + symbol + " qty=" + quantity);
        
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("symbol", symbol);
        orderData.put("qty", quantity);
        orderData.put("side", "sell");
        orderData.put("type", "market");
        orderData.put("time_in_force", timeInForce);
        
        return post("/v2/orders", orderData);
    }
    
    /**
     * Place a limit sell order
     */
    public JsonNode sellLimit(String symbol, double quantity, double limitPrice) throws Exception {
        return sellLimit(symbol, quantity, limitPrice, "day");
    }
    
    /**
     * Place a limit sell order with specified time in force
     */
    public JsonNode sellLimit(String symbol, double quantity, double limitPrice, String timeInForce) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Alpaca client not initialized");
        }
        
        System.out.println("[AlpacaTradingClient] Placing limit SELL order: " + symbol + " qty=" + quantity + 
            " limit=" + limitPrice);
        
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("symbol", symbol);
        orderData.put("qty", quantity);
        orderData.put("side", "sell");
        orderData.put("type", "limit");
        orderData.put("time_in_force", timeInForce);
        orderData.put("limit_price", limitPrice);
        
        return post("/v2/orders", orderData);
    }
    
    /**
     * Place a bracket order with take-profit and stop-loss
     */
    public JsonNode placeBracketOrder(String symbol, double quantity, String side, 
            double takeProfitPrice, double stopLossPrice, Double stopLossLimitPrice) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Alpaca client not initialized");
        }
        
        System.out.println("[AlpacaTradingClient] Placing BRACKET order: " + symbol + " qty=" + quantity + 
            " side=" + side + " TP=" + takeProfitPrice + " SL=" + stopLossPrice);
        
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("symbol", symbol);
        orderData.put("qty", quantity);
        orderData.put("side", side);
        orderData.put("type", "market");
        // Use "day" for fractional orders, "gtc" for whole shares
        orderData.put("time_in_force", quantity % 1 != 0 ? "day" : "gtc");
        orderData.put("order_class", "bracket");
        
        Map<String, Object> takeProfit = new HashMap<>();
        takeProfit.put("limit_price", takeProfitPrice);
        orderData.put("take_profit", takeProfit);
        
        Map<String, Object> stopLoss = new HashMap<>();
        stopLoss.put("stop_price", stopLossPrice);
        if (stopLossLimitPrice != null) {
            stopLoss.put("limit_price", stopLossLimitPrice);
        }
        orderData.put("stop_loss", stopLoss);
        
        return post("/v2/orders", orderData);
    }
    
    /**
     * Place a bracket buy order (market entry with TP and SL)
     */
    public JsonNode buyBracket(String symbol, double quantity, double takeProfitPrice, 
            double stopLossPrice, Double stopLossLimitPrice) throws Exception {
        return placeBracketOrder(symbol, quantity, "buy", takeProfitPrice, stopLossPrice, stopLossLimitPrice);
    }
    
    /**
     * Cancel an order by ID
     */
    public boolean cancelOrder(String orderId) {
        try {
            delete("/v2/orders/" + orderId);
            System.out.println("[AlpacaTradingClient] Cancelled order: " + orderId);
            return true;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Failed to cancel order " + orderId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Cancel all open orders
     */
    public int cancelAllOrders() {
        try {
            delete("/v2/orders");
            System.out.println("[AlpacaTradingClient] Cancelled all orders");
            return 1;
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Failed to cancel all orders: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Close a position (sell all shares)
     */
    public JsonNode closePosition(String symbol) throws Exception {
        System.out.println("[AlpacaTradingClient] Closing position: " + symbol);
        return delete("/v2/positions/" + symbol);
    }
    
    /**
     * Get current price for a symbol (using latest bar)
     */
    public double getCurrentPrice(String symbol) {
        try {
            // Try snapshot endpoint first
            JsonNode snapshot = get("/v2/stocks/" + symbol + "/snapshot");
            if (snapshot != null) {
                JsonNode latestTrade = snapshot.path("latestTrade");
                if (latestTrade != null && !latestTrade.isMissingNode()) {
                    return latestTrade.path("p").asDouble();
                }
            }
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Snapshot endpoint failed for " + symbol + ", trying bars...");
        }
        
        try {
            // Fallback to bars endpoint
            JsonNode bars = get("/v2/stocks/" + symbol + "/bars?timeframe=1Min&limit=1");
            if (bars != null && bars.has("bars") && bars.get("bars").isArray()) {
                JsonNode barArray = bars.get("bars");
                if (barArray.size() > 0) {
                    JsonNode latestBar = barArray.get(0);
                    return latestBar.path("c").asDouble(); // Close price
                }
            }
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting current price for " + symbol + ": " + e.getMessage());
        }
        return 0.0;
    }
    
    /**
     * Get position for a specific symbol
     */
    public JsonNode getPosition(String symbol) {
        try {
            return get("/v2/positions/" + symbol);
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting position for " + symbol + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if we have an open position for a symbol
     */
    public boolean hasPosition(String symbol) {
        return getPosition(symbol) != null;
    }
    
    /**
     * Get order by ID
     */
    public JsonNode getOrder(String orderId) {
        try {
            return get("/v2/orders/" + orderId);
        } catch (Exception e) {
            System.err.println("[AlpacaTradingClient] Error getting order " + orderId + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Shutdown the client
     */
    public void shutdown() {
        httpClient = null;
        initialized = false;
        System.out.println("[AlpacaTradingClient] Shutdown complete");
    }
    
    // HTTP Helper Methods
    
    private JsonNode get(String endpoint) throws Exception {
        System.out.println("[AlpacaTradingClient] GET: " + baseUrl + endpoint);
        System.out.println("[AlpacaTradingClient] API Key: " + apiKey);
        System.out.println("[AlpacaTradingClient] API Secret: " + apiSecret.substring(0, Math.min(10, apiSecret.length())) + "...");
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .header("APCA-API-KEY-ID", apiKey)
            .header("APCA-API-SECRET-KEY", apiSecret)
            .header("Accept", "application/json")
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        System.out.println("[AlpacaTradingClient] Response status: " + response.statusCode());
        
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
    
    private JsonNode post(String endpoint, Map<String, Object> data) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(data);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .header("APCA-API-KEY-ID", apiKey)
            .header("APCA-API-SECRET-KEY", apiSecret)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
    
    private JsonNode delete(String endpoint) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .header("APCA-API-KEY-ID", apiKey)
            .header("APCA-API-SECRET-KEY", apiSecret)
            .header("Accept", "application/json")
            .DELETE()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
    
    /**
     * Configuration class for Alpaca settings
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaConfig {
        public String apiKey;
        public String apiSecret;
        public String baseUrl;
        public boolean usePaperTrading = true;
    }
}
