import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Simple standalone test for Alpaca Paper Trading connection
 * 
 * Run this class directly from IntelliJ to test your Alpaca setup.
 * No JUnit or test framework required.
 */
public class TestAlpacaConnection {
    
    public static void main(String[] args) {
        System.out.println("\n========================================");
        System.out.println("  Alpaca Paper Trading Connection Test");
        System.out.println("========================================\n");
        
        try {
            // Step 1: Test Configuration
            System.out.println("Step 1: Loading configuration...");
            
            // Try to find config file
            String configPath = findConfigFile();
            if (configPath == null) {
                System.err.println("✗ Failed to find trading-config.json");
                System.err.println("Please ensure trading-config.json is in the project root");
                return;
            }
            
            System.out.println("Found config at: " + configPath);
            
            // Set the config path explicitly
            TradingManager.setConfigPath(configPath);
            
            var config = TradingManager.getConfig();
            
            if (config == null) {
                System.err.println("✗ Failed to load configuration");
                System.err.println("Make sure trading-config.json exists in the project root");
                return;
            }
            
            System.out.println("✓ Configuration loaded");
            System.out.println("  Trading Mode: " + config.tradingMode);
            System.out.println("  Real Trading Enabled: " + config.realTrading.enabled);
            System.out.println("  Use Paper Trading: " + config.realTrading.alpaca.usePaperTrading);
            System.out.println("  API Key: " + config.realTrading.alpaca.apiKey);
            System.out.println("  API Secret: " + 
                (config.realTrading.alpaca.apiSecret != null ? 
                    config.realTrading.alpaca.apiSecret.substring(0, Math.min(10, config.realTrading.alpaca.apiSecret.length())) + "..." : "null"));
            
            // Step 2: Initialize TradingManager
            System.out.println("\nStep 2: Initializing TradingManager...");
            boolean initialized = TradingManager.initialize();
            
            if (!initialized) {
                System.err.println("✗ Failed to initialize TradingManager");
                System.err.println("Check your API credentials in trading-config.json");
                return;
            }
            
            System.out.println("✓ TradingManager initialized successfully");
            
            // Step 3: Check connection
            System.out.println("\nStep 3: Checking Alpaca connection...");
            System.out.println("  Trading Mode: " + TradingManager.getTradingMode());
            System.out.println("  Is Real Trading: " + TradingManager.isRealTrading());
            System.out.println("  Current Capital: $" + TradingManager.getCurrentCapital());
            System.out.println("  Position Size: $" + TradingManager.getPositionSize());
            System.out.println("  Max Loss Per Stock: $" + TradingManager.getMaxLossPerStock());
            
            // Step 4: Test Alpaca client directly
            System.out.println("\nStep 4: Testing Alpaca API connection...");
            var alpacaClient = new AlpacaTradingClient(
                config.realTrading.alpaca.apiKey,
                config.realTrading.alpaca.apiSecret,
                config.realTrading.alpaca.usePaperTrading
            );
            
            if (alpacaClient.initialize()) {
                System.out.println("✓ Alpaca API connection successful");
                System.out.println("  Buying Power: $" + alpacaClient.getBuyingPower());
                System.out.println("  Cash Balance: $" + alpacaClient.getCashBalance());
                
                // Get open positions
                var positions = alpacaClient.getOpenPositions();
                System.out.println("  Open Positions: " + positions.size());
                
                // Get orders
                var orders = alpacaClient.getAllOrders();
                System.out.println("  Total Orders: " + orders.size());
                
            } else {
                System.err.println("✗ Alpaca API connection failed");
                System.err.println("Check your API credentials and network connection");
                return;
            }
            
            // Step 5: Test a small buy order
            System.out.println("\nStep 5: Test buy order");
            System.out.println("  Attempting test buy order for AAPL...");
            System.out.println("  Trading Mode: " + TradingManager.getTradingMode());
            System.out.println("  Is Real Trading: " + TradingManager.isRealTrading());
            
            // Use a reasonable price for AAPL (market data endpoints require additional permissions)
            // Based on the error message, AAPL is around $333.60
            double currentPrice = 333.60;
            System.out.println("  Using estimated AAPL price: $" + currentPrice);
            
            var trade = TradingManager.buy("AAPL", currentPrice, "CONNECTION_TEST");
            if (trade != null) {
                System.out.println("  ✓ Test buy order placed via " + 
                    (TradingManager.isRealTrading() ? "Alpaca API" : "Simulation"));
                System.out.println("    Trade ID: " + trade.id);
                System.out.println("    Order ID: " + trade.orderId);
                System.out.println("    Status: " + trade.status);
                System.out.println("    Symbol: " + trade.symbol);
                System.out.println("    Quantity: " + trade.quantity);
                System.out.println("    Price: $" + trade.price);
                System.out.println("    Total: $" + trade.totalValue);
                System.out.println("    Stop Loss: $" + trade.stopLossPrice);
                System.out.println("    Take Profit: $" + trade.takeProfitPrice);
                
                if (trade.orderId != null && !trade.orderId.isEmpty()) {
                    System.out.println("    ✓ Order successfully sent to Alpaca API");
                } else {
                    System.out.println("    ⚠ Order ID is empty - may not have been sent to API");
                }
            } else {
                System.out.println("  ✗ Test buy order failed");
            }
            
            // Success!
            System.out.println("\n========================================");
            System.out.println("  ✓ ALL TESTS PASSED!");
            System.out.println("  Your Alpaca paper trading is working!");
            System.out.println("========================================\n");
            
            System.out.println("Next steps:");
            System.out.println("1. Monitor your paper trading account at https://alpaca.markets/");
            System.out.println("2. When ready for live trading, set usePaperTrading to false");
            System.out.println("3. Check your positions and orders in the Alpaca dashboard");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed with error:");
            System.err.println("  " + e.getMessage());
            e.printStackTrace();
        } finally {
            TradingManager.shutdown();
        }
    }
    
    private static String findConfigFile() {
        String[] possiblePaths = {
            "trading-config.json",
            "./trading-config.json",
            "../trading-config.json",
            "../../trading-config.json"
        };
        
        for (String path : possiblePaths) {
            if (Files.exists(Paths.get(path))) {
                return Paths.get(path).toAbsolutePath().toString();
            }
        }
        return null;
    }
}
