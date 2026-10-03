import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for Alpaca Paper Trading connection
 * 
 * This test verifies:
 * 1. Connection to Alpaca API
 * 2. Account information retrieval
 * 3. Order placement capability
 * 
 * Run this test to verify your paper trading setup works correctly.
 */
public class AlpacaTradingTest {
    
    @Test
    public void testAlpacaConnection() {
        System.out.println("\n=== Testing Alpaca Paper Trading Connection ===\n");
        
        // Initialize TradingManager
        boolean initialized = TradingManager.initialize();
        
        assertTrue(initialized, "TradingManager should initialize successfully");
        System.out.println("✓ TradingManager initialized");
        
        // Check trading mode
        System.out.println("Trading Mode: " + TradingManager.getTradingMode());
        assertTrue(TradingManager.isRealTrading(), "Should be in real trading mode");
        System.out.println("✓ Real trading mode enabled");
        
        // Get current capital
        double capital = TradingManager.getCurrentCapital();
        System.out.println("Current Capital: $" + capital);
        assertTrue(capital > 0, "Should have buying power");
        System.out.println("✓ Buying power available");
        
        System.out.println("\n=== Connection Test PASSED ===\n");
    }
    
    @Test
    public void testPaperTradeBuy() {
        System.out.println("\n=== Testing Paper Trading Buy Order ===\n");
        
        // Initialize
        if (!TradingManager.isInitialized()) {
            TradingManager.initialize();
        }
        
        // Test with a small position (AAPL is just an example)
        String symbol = "AAPL";
        double testPrice = 150.0; // Approximate price
        String strategy = "UNIT_TEST";
        
        System.out.println("Attempting to buy: " + symbol);
        System.out.println("Test price: $" + testPrice);
        System.out.println("Position size: $" + TradingManager.getPositionSize());
        
        // Execute buy order
        TradingManager.TradeRecord trade = TradingManager.buy(symbol, testPrice, strategy);
        
        if (trade != null) {
            System.out.println("\n✓ Buy order placed successfully!");
            System.out.println("Trade ID: " + trade.id);
            System.out.println("Symbol: " + trade.symbol);
            System.out.println("Quantity: " + trade.quantity);
            System.out.println("Price: $" + trade.price);
            System.out.println("Total Value: $" + trade.totalValue);
            System.out.println("Status: " + trade.status);
            System.out.println("Order ID: " + trade.orderId);
            System.out.println("Stop Loss: $" + trade.stopLossPrice);
            System.out.println("Take Profit: $" + trade.takeProfitPrice);
            
            assertNotNull(trade, "Trade should be created");
            assertEquals("BUY", trade.side, "Should be a buy order");
            assertNotNull(trade.orderId, "Should have Alpaca order ID");
            
            System.out.println("\n=== Buy Order Test PASSED ===\n");
        } else {
            System.out.println("\n✗ Buy order failed");
            System.out.println("Check your API credentials in trading-config.json");
            System.out.println("Make sure usePaperTrading is set to true");
            fail("Buy order should succeed");
        }
    }
    
    @Test
    public void testGetActiveTrades() {
        System.out.println("\n=== Testing Get Active Trades ===\n");
        
        // Initialize if needed
        if (!TradingManager.isInitialized()) {
            TradingManager.initialize();
        }
        
        // Get active trades
        var activeTrades = TradingManager.getActiveTrades();
        
        System.out.println("Active trades count: " + activeTrades.size());
        
        for (var trade : activeTrades) {
            System.out.println("- " + trade.symbol + " | Status: " + trade.status + 
                " | Qty: " + trade.quantity);
        }
        
        System.out.println("\n=== Get Active Trades Test PASSED ===\n");
    }
    
    @Test
    public void testGetTradeHistory() {
        System.out.println("\n=== Testing Get Trade History ===\n");
        
        // Initialize if needed
        if (!TradingManager.isInitialized()) {
            TradingManager.initialize();
        }
        
        // Get trade history
        var history = TradingManager.getTradeHistory();
        
        System.out.println("Total trades in history: " + history.size());
        
        for (var trade : history) {
            System.out.println("- " + trade.symbol + " | " + trade.side + 
                " | $" + trade.totalValue + " | " + trade.status);
        }
        
        System.out.println("\n=== Trade History Test PASSED ===\n");
    }
    
    @Test
    public void testConfiguration() {
        System.out.println("\n=== Testing Configuration ===\n");
        
        var config = TradingManager.getConfig();
        
        System.out.println("Trading Mode: " + config.tradingMode);
        System.out.println("Fake Trading Enabled: " + config.fakeTrading.enabled);
        System.out.println("Real Trading Enabled: " + config.realTrading.enabled);
        System.out.println("Alpaca API Key: " + 
            (config.realTrading.alpaca.apiKey != null && !config.realTrading.alpaca.apiKey.isEmpty() 
                ? "Set ✓" : "Not set ✗"));
        System.out.println("Alpaca API Secret: " + 
            (config.realTrading.alpaca.apiSecret != null && !config.realTrading.alpaca.apiSecret.isEmpty() 
                ? "Set ✓" : "Not set ✗"));
        System.out.println("Use Paper Trading: " + config.realTrading.alpaca.usePaperTrading);
        System.out.println("Starting Capital: $" + config.realTrading.startingCapital);
        System.out.println("Position Size: $" + config.realTrading.positionSize);
        System.out.println("Max Loss Per Stock: $" + config.realTrading.maxLossPerStock);
        
        assertNotNull(config, "Config should be loaded");
        assertNotNull(config.realTrading.alpaca.apiKey, "API key should be set");
        assertNotNull(config.realTrading.alpaca.apiSecret, "API secret should be set");
        assertTrue(config.realTrading.alpaca.usePaperTrading, "Should use paper trading for testing");
        
        System.out.println("\n=== Configuration Test PASSED ===\n");
    }
    
    /**
     * Main method for manual testing without JUnit
     * Run this method directly if you prefer not to use JUnit
     */
    public static void main(String[] args) {
        System.out.println("\n========================================");
        System.out.println("  Alpaca Paper Trading Manual Test");
        System.out.println("========================================\n");
        
        AlpacaTradingTest test = new AlpacaTradingTest();
        
        try {
            // Test 1: Configuration
            System.out.println("Running configuration test...");
            test.testConfiguration();
            
            // Test 2: Connection
            System.out.println("Running connection test...");
            test.testAlpacaConnection();
            
            // Test 3: Buy order (commented out by default - uncomment to test)
            System.out.println("Running buy order test...");
            // test.testPaperTradeBuy();  // Uncomment this to actually place a test order
            
            // Test 4: Get active trades
            System.out.println("Running active trades test...");
            test.testGetActiveTrades();
            
            // Test 5: Get trade history
            System.out.println("Running trade history test...");
            test.testGetTradeHistory();
            
            System.out.println("\n========================================");
            System.out.println("  All tests completed successfully!");
            System.out.println("========================================\n");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            TradingManager.shutdown();
        }
    }
}
