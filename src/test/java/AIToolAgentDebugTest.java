import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.Arrays;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Debug test for S10_SWING_TIGHT_RANGE_GEN1 agent.
 * 
 * HOW TO DEBUG:
 * 1. Open this file in IntelliJ
 * 2. Set breakpoints at the lines indicated below
 * 3. Right-click on the test method and select "Debug 'testS10Agent...'"
 * 
 * RECOMMENDED BREAKPOINTS in AIToolAgent.java:
 * - Line 637: analyzeStock() method start - see ticker being analyzed
 * - Line 656: After RSI calculation - check RSI value
 * - Line 669: RSI filter check - see if RSI passes (rsiMin=48, rsiMax=61)
 * - Line 733: RVOL calculation - check relative volume (rvolMin=1.35)
 * - Line 737: RVOL filter check - see if RVOL passes
 * - Line 765: SMA200 filter check - see if price > SMA200
 * - Line 779: VWAP filter check - see if price > VWAP
 * - Line 863: executeTrade() - see trade execution with real prices
 */
public class AIToolAgentDebugTest {

    // Test stocks - single ticker for faster testing
    private static final List<String> TEST_TICKERS = Arrays.asList(
        "AAPL"    // Single ticker
    );

    @BeforeAll
    static void setup() {
        // Initialize the AIToolAgent system
        System.out.println("=== Initializing AIToolAgent ===");
        AIToolAgent.initialize();
        System.out.println("=== Initialization complete ===\n");
    }

    @Test
    void testS10AgentWithMultipleStocks() {
        String agentId = "S10_SWING_TIGHT_RANGE_GEN1";
        
        System.out.println("========================================");
        System.out.println("Testing Agent: " + agentId);
        System.out.println("========================================");
        
        // Get agent config
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found: " + agentId);
            return;
        }
        
        // Print agent filters
        System.out.println("\n=== Agent Entry Filters ===");
        if (agent.entryFilters != null) {
            agent.entryFilters.forEach((key, value) -> 
                System.out.println("  " + key + " = " + value));
        }
        System.out.println("\n=== Agent Risk Management ===");
        if (agent.riskManagement != null) {
            agent.riskManagement.forEach((key, value) -> 
                System.out.println("  " + key + " = " + value));
        }
        
        System.out.println("\n=== Testing Stocks ===\n");
        
        int passedCount = 0;
        int failedCount = 0;
        
        for (String ticker : TEST_TICKERS) {
            System.out.println("--- Analyzing: " + ticker + " ---");
            
            // PUT BREAKPOINT HERE to step into analyzeStock
            AIToolAgent.TradeDecision decision = AIToolAgent.analyzeStockPublic(ticker, agent);
            
            if (decision != null && decision.shouldTrade) {
                System.out.println("  ✅ PASSED ALL FILTERS - Would trade!");
                System.out.println("     Action: " + decision.action);
                System.out.println("     Confidence: " + String.format("%.2f", decision.confidence));
                System.out.println("     Stop Loss: $" + String.format("%.2f", decision.suggestedStopLoss));
                System.out.println("     Take Profit: $" + String.format("%.2f", decision.suggestedTakeProfit));
                passedCount++;
            } else {
                System.out.println("  ❌ FILTERED OUT - No trade");
                failedCount++;
            }
            System.out.println();
            
            // Small delay to avoid API rate limiting
            try { Thread.sleep(1000); } catch (InterruptedException e) { }
        }
        
        System.out.println("========================================");
        System.out.println("SUMMARY: " + passedCount + " passed, " + failedCount + " filtered out");
        System.out.println("========================================");
    }

    @Test
    void testSingleStock() {
        // Change this ticker to test a specific stock
        String ticker = "AAPL";
        String agentId = "S10_SWING_TIGHT_RANGE_GEN1";
        
        System.out.println("========================================");
        System.out.println("Single Stock Test: " + ticker);
        System.out.println("Agent: " + agentId);
        System.out.println("========================================\n");
        
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found!");
            return;
        }
        
        // Print expected filter values
        System.out.println("Expected Filter Values:");
        System.out.println("  RSI must be between 47.93 and 61.04");
        System.out.println("  RVOL must be >= 1.35");
        System.out.println("  Price must be > SMA200");
        System.out.println("  Price must be > VWAP");
        System.out.println();
        
        // PUT BREAKPOINT ON THE NEXT LINE
        AIToolAgent.TradeDecision decision = AIToolAgent.analyzeStockPublic(ticker, agent);
        
        if (decision != null && decision.shouldTrade) {
            System.out.println("✅ TRADE SIGNAL GENERATED!");
        } else {
            System.out.println("❌ NO TRADE - Stock filtered out");
        }
    }
    
    @Test
    void testExecuteTradeWithRealPrices() {
        String ticker = "AAPL";
        String agentId = "S10_SWING_TIGHT_RANGE_GEN1";
        
        System.out.println("========================================");
        System.out.println("Execute Trade Test (Real Prices)");
        System.out.println("Ticker: " + ticker + " | Agent: " + agentId);
        System.out.println("========================================\n");
        
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found!");
            return;
        }
        
        // First analyze
        AIToolAgent.TradeDecision decision = AIToolAgent.analyzeStockPublic(ticker, agent);
        
        if (decision == null) {
            System.out.println("No decision returned - creating mock decision for trade test");
            decision = new AIToolAgent.TradeDecision();
            decision.shouldTrade = true;
            decision.action = "BUY";
            decision.confidence = 0.8;
            decision.suggestedStopLoss = 170.0;
            decision.suggestedTakeProfit = 200.0;
        }
        
        // PUT BREAKPOINT ON THE NEXT LINE to see real price execution
        AIToolAgent.Trade trade = AIToolAgent.executeTradePublic(agent, ticker, decision);
        
        if (trade != null) {
            System.out.println("\n=== Trade Executed ===");
            System.out.println("  Ticker: " + trade.ticker);
            System.out.println("  Action: " + trade.action);
            System.out.println("  Entry Price: $" + String.format("%.2f", trade.entryPrice));
            System.out.println("  Exit Price: $" + String.format("%.2f", trade.exitPrice));
            System.out.println("  P/L: $" + String.format("%.2f", trade.profitLoss) + 
                             " (" + String.format("%.2f%%", trade.profitLossPct) + ")");
            System.out.println("  Status: " + trade.status);
            System.out.println("  Entry Time: " + trade.entryTime);
            System.out.println("  Exit Time: " + trade.exitTime);
        } else {
            System.out.println("Trade execution failed!");
        }
    }
    
    /**
     * Full Analysis Report for a single symbol
     * Shows all filter values, thresholds, and pass/fail status
     */
    @Test
    void testSingleSymbolFullReport() {
        // ========== CONFIGURE HERE ==========
        String ticker = "NVDA";  // Change to any ticker you want to analyze
        String agentId = "S10_SWING_TIGHT_RANGE_GEN1";
        // ====================================
        
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           SINGLE SYMBOL FULL ANALYSIS REPORT                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found: " + agentId);
            return;
        }
        
        // Print agent configuration first
        System.out.println("\n=== Agent Configuration ===");
        System.out.println("Agent ID: " + agent.id);
        System.out.println("Agent Name: " + agent.name);
        System.out.println("Type: " + agent.type);
        
        System.out.println("\n--- Entry Filters ---");
        if (agent.entryFilters != null) {
            agent.entryFilters.forEach((key, value) -> 
                System.out.println("  • " + key + " = " + value));
        }
        
        System.out.println("\n--- Risk Management ---");
        if (agent.riskManagement != null) {
            agent.riskManagement.forEach((key, value) -> 
                System.out.println("  • " + key + " = " + value));
        }
        
        // Run full analysis with report
        System.out.println("\n=== Fetching Data & Analyzing... ===");
        AIToolAgent.AnalysisReport report = AIToolAgent.analyzeStockWithReport(ticker, agent);
        
        // Print the full report
        System.out.println(report.toString());
        
        // Additional raw data for debugging
        System.out.println("\n=== Raw Values for Debugging ===");
        System.out.println("  currentPrice = " + report.currentPrice);
        System.out.println("  rsi = " + report.rsi);
        System.out.println("  sma20 = " + report.sma20);
        System.out.println("  sma200 = " + report.sma200);
        System.out.println("  rvol = " + report.rvol);
        System.out.println("  cmf = " + report.cmf);
        System.out.println("  vwapHoldBars = " + report.vwapHoldBars);
        System.out.println("  passed = " + report.passed);
        System.out.println("  failedAt = " + report.failedAt);
    }
    
    /**
     * Analyze multiple symbols with full reports
     */
    @Test
    void testMultipleSymbolsFullReport() {
        String agentId = "S10_SWING_TIGHT_RANGE_GEN1";
        
        // Symbols to analyze
        List<String> symbols = Arrays.asList("AAPL", "NVDA", "TSLA", "META", "MSFT");
        
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         MULTIPLE SYMBOLS FULL ANALYSIS REPORT                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found: " + agentId);
            return;
        }
        
        int passed = 0;
        int failed = 0;
        
        for (String ticker : symbols) {
            System.out.println("\n>>> Analyzing " + ticker + "...");
            
            AIToolAgent.AnalysisReport report = AIToolAgent.analyzeStockWithReport(ticker, agent);
            System.out.println(report.toString());
            
            if (report.passed) {
                passed++;
            } else {
                failed++;
            }
            
            // Rate limit delay
            try { Thread.sleep(13000); } catch (InterruptedException e) { }
        }
        
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println(String.format("║  SUMMARY: %d PASSED  |  %d FAILED                             ║", passed, failed));
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
    
    /**
     * Full Technical & Fundamental Analysis Report for a single symbol
     * This is the same analysis that runs on /run-main page
     * Includes: SMA, RSI, MACD, Stochastic, Bollinger, DCF, PEG, CMF, ADX, ATR, CCI, etc.
     */
    @Test
    void testFullAnalysisReport() {
        // ========== CONFIGURE HERE ==========
        String ticker = "AAPL";  // Change to any ticker you want to analyze
        // ====================================
        
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    FULL TECHNICAL & FUNDAMENTAL ANALYSIS                     ║");
        System.out.println("║                         (Same as /run-main page)                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println("Ticker: " + ticker);
        System.out.println("================================================================================\n");
        
        try {
            // Set the ticker for DataFetcher
            DataFetcher.setTicker(ticker);
            
            // Run Main.main() which generates the full analysis
            // This is exactly what /run-main does
            Main.main(new String[]{});
            
            System.out.println("\n================================================================================");
            System.out.println("                              END OF ANALYSIS");
            System.out.println("================================================================================");
            
        } catch (Exception e) {
            System.err.println("Error running analysis: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends a test message to Discord via the configured webhook.
     * Requires env var: DAILY_SIM_DISCORD_WEBHOOK_URL
     */
    @Test
    void testSendDiscordMessage() {
        System.out.println("========================================");
        System.out.println("Discord Notification Test");
        System.out.println("========================================");

        String webhookUrl = System.getenv("DAILY_SIM_DISCORD_WEBHOOK_URL");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            System.out.println("⚠️  DAILY_SIM_DISCORD_WEBHOOK_URL is not set — skipping send.");
            System.out.println("   Set the env var and re-run to actually post to Discord.");
            return;
        }

        String message = "🧪 **Test Message from AIToolAgentDebugTest**\n"
                + "Sent at: " + java.time.LocalDateTime.now()
                + "\nThis is an automated test — you can ignore it.";

        System.out.println("Sending message:\n" + message);

        boolean success = AIToolAgent.sendDiscordPublic(message);

        System.out.println(success ? "✅ Discord message sent successfully!" : "❌ Failed to send Discord message.");
        System.out.println("========================================");
    }

    /**
     * Full Analysis Report with StockScannerRunner (Model Summary)
     * Includes DCF, CCC, ROIC vs WACC, ADX, Technical/Fundamental signals
     */
    @Test
    void testModelSummaryReport() {
        // ========== CONFIGURE HERE ==========
        String ticker = "AAPL";  // Change to any ticker you want to analyze
        // ====================================
        
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         MODEL SUMMARY REPORT                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println("Ticker: " + ticker);
        System.out.println("================================================================================\n");
        
        try {
            DataFetcher.setTicker(ticker);
            StockAnalysisResult r = StockScannerRunner.analyzeSingleStock(ticker);
            
            if (r == null) {
                System.out.println("❌ No analysis result returned");
                return;
            }
            
            System.out.println("=== PRICE DATA ===");
            System.out.printf("  Current Price: $%.2f%n", r.price);
            
            System.out.println("\n=== FUNDAMENTAL ANALYSIS ===");
            System.out.printf("  DCF Fair Value: $%.2f%n", r.dcfFairValue);
            System.out.printf("  Cash Conversion Cycle (CCC): %.1f days%n", r.cccDays != null ? r.cccDays : 0);
            System.out.printf("  ROIC: %.2f%%%n", r.roic != null ? r.roic * 100 : 0);
            System.out.printf("  WACC: %.2f%%%n", r.wacc != null ? r.wacc * 100 : 0);
            System.out.printf("  Economic Spread (ROIC-WACC): %+.2f%%%n", r.economicSpread != null ? r.economicSpread * 100 : 0);
            
            System.out.println("\n=== TECHNICAL ANALYSIS ===");
            System.out.printf("  ADX Strength: %.2f%n", r.adxStrength);
            System.out.printf("  Technical Signal: %s%n", r.technicalSignal);
            
            System.out.println("\n=== RISK MODELS ===");
            if (r.beneishMScore != null) {
                System.out.printf("  Beneish M-Score: %.2f %s%n", r.beneishMScore, 
                    r.beneishManipulator != null && r.beneishManipulator ? "(MANIPULATOR)" : "(SAFE)");
            }
            if (r.sloanRatio != null) {
                System.out.printf("  Sloan Ratio: %+.2f%% %s%n", r.sloanRatio * 100,
                    r.sloanLowQuality != null && r.sloanLowQuality ? "(LOW QUALITY)" : "");
            }
            
            System.out.println("\n=== SIGNALS ===");
            System.out.printf("  Fundamental Signal: %s%n", r.fundamentalSignal);
            System.out.printf("  Technical Signal: %s%n", r.technicalSignal);
            
            System.out.println("\n╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  FINAL VERDICT: %-60s ║%n", r.finalVerdict != null ? r.finalVerdict : "N/A");
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            System.err.println("Error running analysis: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
