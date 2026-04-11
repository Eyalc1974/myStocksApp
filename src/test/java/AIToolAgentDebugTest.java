import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

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
        "DELL"    // Single ticker
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
        String agentId = "M5_MA_CROSS";
        
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
        String agentId = "M5_MA_CROSS";
        
        System.out.println("========================================");
        System.out.println("Single Stock Test: " + ticker);
        System.out.println("Agent: " + agentId);
        System.out.println("========================================\n");
        
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(agentId);
        if (agent == null) {
            System.err.println("ERROR: Agent not found!");
            return;
        }
        
        // Print expected filter values (M5_MA_CROSS)
        System.out.println("Expected Filter Values:");
        System.out.println("  RSI must be between 45 and 70 (rsiMin/rsiMax)");
        System.out.println("  RVOL must be >= 1.3 (rvolMin)");
        System.out.println("  Price must be > SMA200 (sma200Required)");
        System.out.println("  MA9 must be > MA21 (maCrossoverRequired)");
        System.out.println("  entryType = TREND_CONTINUATION (T4: price>SMA50, RSI 50-70)");
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
        String agentId = "M5_MA_CROSS";
        
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
        String agentId = "M5_MA_CROSS";
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
        String agentId = "M5_MA_CROSS";
        
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
     * Full scoring flow for all MATERIALS sector tickers against one master strategy.
     *
     * Flow demonstrated per ticker:
     *   1. Load MASTER_7_VIX_MARKET_FILTER.json config (from JSON file, no system state needed)
     *   2. Read current market regime (HEALTHY / WEAK / VERY_WEAK)
     *   3. Fetch daily OHLCV data  →  DataFetcher.fetchStockData()
     *   4. Compute indicators      →  AIToolAgent.computeIndicatorsPublic()
     *      (price, SMA20/50/200, RSI, RVOL, ATR%, resistance30d, week52High, …)
     *   5. Score against MASTER_6_VOLUME_BREAKOUT  →  AIToolAgent.scoreStrategyForTickerPublic()
     *      Volume (0–3) + Trend (0–3) + Momentum (0–3) + Setup (0–3) = Total (0–12)
     *      + Move-potential penalty/bonus  + WEAK regime penalty  + Execution trigger gate
     *   6. Decide: SIGNAL (≥10 + trigger met) | TRIGGER_WAIT (≥10, trigger pending) | SCORE_LOW
     *   7. Print summary table sorted by score descending
     *
     * NOTE: MASTER_7 is a global Market Filter — it does NOT score individual stocks.
     *       It is loaded here to illustrate the JSON-based config loading step.
     *       Per-stock scoring requires a trading strategyType (VOLUME_BREAKOUT, etc.).
     *       MASTER_6_VOLUME_BREAKOUT is used as the scoring agent.
     *
     * Runtime: ~20 × 13 s = ~4 min  (Alpha Vantage free-tier rate limit)
     */
    @Test
    void testMaterialsTickersScoringFlow() {
        final List<String> MATERIALS_TICKERS = Arrays.asList(
            "LIN","APD","SHW","FCX","NEM","DD","DOW","PPG","ECL","ALB",
            "VMC","MLM","NUE","STLD","X","CF","MOS","FMC","IFF","CE"
        );
        final int SCORE_THRESHOLD = 10;
        final String SCORING_AGENT_ID = "MASTER_6_VOLUME_BREAKOUT";

        System.out.println("\n╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║         MATERIALS SECTOR — FULL SCORING FLOW TEST                    ║");
        System.out.println("║   20 tickers  |  1 agent  |  entire pipeline  |  ~4 min              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");

        // ── Phase 1: Load MASTER_7 from its JSON file ─────────────────────────────
        System.out.println("\n── Phase 1: Load MASTER_7_VIX_MARKET_FILTER.json from disk ────────────");
        AIToolAgent.AgentConfig master7 = AIToolAgent.loadAgentConfigFromFile(
            Paths.get("newStrategies/MASTER_7_VIX_MARKET_FILTER.json"));
        if (master7 == null) {
            System.err.println("  ❌ ERROR: could not load MASTER_7 JSON file");
        } else {
            System.out.println("  ✓ Loaded:  " + master7.id);
            System.out.println("  Name:      " + master7.name);
            System.out.println("  Type:      " + master7.type);
            System.out.println("  StratType: " + master7.strategyType);
            System.out.println("  Locked:    " + master7.locked);
            System.out.println("  ⚠ This is a global Market Filter — no per-stock scoring.");
            System.out.println("    Per-stock scoring is done by: " + SCORING_AGENT_ID);
        }

        // ── Phase 2: Current market regime ────────────────────────────────────────
        System.out.println("\n── Phase 2: Current Market Regime ─────────────────────────────────────");
        AIToolAgent.RegimeLevel regime = AIToolAgent.getLastKnownRegime();
        System.out.println("  Regime:  " + regime);
        System.out.println("  Detail:  " + AIToolAgent.getLastRegimeDetail());
        System.out.println("  Rules:");
        System.out.println("    HEALTHY   → full position size, no score penalty");
        System.out.println("    WEAK      → score −2 per ticker, 50% size if traded");
        System.out.println("    VERY_WEAK → no new trades at all (execution blocked)");

        // ── Phase 3: Load trading agent for scoring ───────────────────────────────
        System.out.println("\n── Phase 3: Load scoring agent ─────────────────────────────────────────");
        AIToolAgent.AgentConfig scoringAgent = AIToolAgent.getAgentConfig(SCORING_AGENT_ID);
        if (scoringAgent == null) {
            System.err.println("  ❌ ERROR: " + SCORING_AGENT_ID + " not found in systemState — aborting");
            return;
        }
        System.out.println("  ✓ Loaded:    " + scoringAgent.id);
        System.out.println("  StratType:   " + scoringAgent.strategyType);
        System.out.println("  SL%:         " + scoringAgent.riskManagement.getOrDefault("stopLossPct",  "?"));
        System.out.println("  TP%:         " + scoringAgent.riskManagement.getOrDefault("takeProfitPct","?"));
        System.out.println("  MaxOpen:     " + scoringAgent.maxOpenTrades);
        System.out.println("  ScoreThresh: " + SCORE_THRESHOLD + "/12");

        // ── Phase 4: Score each ticker ────────────────────────────────────────────
        System.out.println("\n── Phase 4: Scanning " + MATERIALS_TICKERS.size() + " MATERIALS tickers ──");
        System.out.println("  (13 s sleep between tickers — Alpha Vantage free tier)\n");

        // result row: ticker | status | total | V | T | M | S | price | triggerPrice
        List<String[]> results = new ArrayList<>();

        for (String ticker : MATERIALS_TICKERS) {
            System.out.println("┌─── " + ticker + " " + "─".repeat(Math.max(0, 55 - ticker.length())));

            // Step A — Fetch
            System.out.println("│  [A] Fetch: DataFetcher.setTicker(\"" + ticker + "\") + fetchStockData()");
            DataFetcher.setTicker(ticker);
            String rawJson = DataFetcher.fetchStockData();
            if (rawJson == null || rawJson.isBlank()) {
                System.out.println("│      ❌ FETCH FAIL — no data returned");
                System.out.println("└" + "─".repeat(57));
                results.add(new String[]{ticker, "FETCH FAIL", "-", "-", "-", "-", "-", "-", "-"});
                try { Thread.sleep(13000); } catch (InterruptedException ignored) {}
                continue;
            }
            System.out.println("│      ✓ Data received (" + rawJson.length() + " bytes)");

            // Step B — Compute indicators
            System.out.println("│  [B] Compute indicators: computeIndicatorsPublic()");
            AIToolAgent.IndicatorData ind = AIToolAgent.computeIndicatorsPublic(ticker, rawJson);
            if (ind == null || !ind.valid) {
                System.out.println("│      ❌ INDICATOR FAIL — insufficient history");
                System.out.println("└" + "─".repeat(57));
                results.add(new String[]{ticker, "NULL", "-", "-", "-", "-", "-", "-", "-"});
                try { Thread.sleep(13000); } catch (InterruptedException ignored) {}
                continue;
            }
            System.out.printf("│      Price:       $%.2f  (prev $%.2f, today %+.2f%%)%n",
                ind.currentPrice, ind.prevClose, ind.todayChangePct);
            System.out.printf("│      SMA20/50/200: $%.2f / $%.2f / $%.2f%n",
                ind.sma20, ind.sma50, ind.sma200);
            System.out.printf("│      Above SMA:   20=%b  50=%b  200=%b  maCross=%b%n",
                ind.priceAboveSMA20, ind.priceAboveSMA50, ind.priceAboveSMA200, ind.maCrossoverUp);
            System.out.printf("│      RSI: %.1f   RVOL: %.2fx   ATR%%: %.2f%%%n",
                ind.rsi, ind.rvol, ind.atrPct);
            System.out.printf("│      Resistance30d: $%.2f   Week52High: $%.2f (%.1f%% from high)%n",
                ind.resistance30d, ind.week52High, ind.pctFromWeek52High);
            System.out.printf("│      VWAP%%: %+.2f%%   Momentum20d: %+.2f%%%n",
                ind.vwapPct, ind.momentum20d);

            // Step C — Score
            System.out.println("│  [C] Score: scoreStrategyForTickerPublic(ticker, " + SCORING_AGENT_ID + ", indicators)");
            AIToolAgent.TradeDecision dec =
                AIToolAgent.scoreStrategyForTickerPublic(ticker, scoringAgent, ind);

            if (dec == null) {
                System.out.println("│      ❌ SKIPPED — ATR too low (hard filter, no score computed)");
                System.out.println("└" + "─".repeat(57));
                results.add(new String[]{ticker, "ATR SKIP", "-", "-", "-", "-", "-",
                    String.format("$%.2f", ind.currentPrice), "-"});
                try { Thread.sleep(13000); } catch (InterruptedException ignored) {}
                continue;
            }

            // Step D — Print breakdown
            System.out.println("│  [D] Score breakdown:");
            System.out.printf("│      Volume   (0-3): %d%n", dec.volumeScore);
            System.out.printf("│      Trend    (0-3): %d%n", dec.trendScore);
            System.out.printf("│      Momentum (0-3): %d%n", dec.momentumScore);
            System.out.printf("│      Setup    (0-3): %d%n", dec.setupScore);
            System.out.printf("│      ─────────────────────%n");
            System.out.printf("│      TOTAL:   %d/12  (threshold=%d)%n", dec.totalScore, SCORE_THRESHOLD);
            if (dec.confluenceBonus > 0) {
                System.out.printf("│      🔥 Confluence bonus: +%d (included above)%n", dec.confluenceBonus);
            }
            if (dec.entryTriggerPrice > 0) {
                double gapPct = ((dec.entryTriggerPrice - ind.currentPrice) / ind.currentPrice) * 100;
                System.out.printf("│      Trigger Level: $%.2f  (need %+.1f%% from $%.2f)%n",
                    dec.entryTriggerPrice, gapPct, ind.currentPrice);
            }

            // Step E — Decision
            System.out.println("│  [E] Decision:");
            String status;
            if (dec.totalScore >= SCORE_THRESHOLD && !dec.triggerNotMet) {
                status = "✅ SIGNAL";
                System.out.printf("│      ✅ SIGNAL — score %d/12 ≥ %d AND trigger met%n",
                    dec.totalScore, SCORE_THRESHOLD);
                System.out.printf("│         Entry=$%.2f  SL=$%.2f  TP=$%.2f%n",
                    dec.entryPrice, dec.suggestedStopLoss, dec.suggestedTakeProfit);
            } else if (dec.totalScore >= SCORE_THRESHOLD && dec.triggerNotMet) {
                status = "⏳ TRIGGER_WAIT";
                System.out.printf("│      ⏳ WATCHLIST — score %d/12 OK, but trigger not met:%n",
                    dec.totalScore);
                System.out.println("│         " + dec.rejectReason);
                System.out.println("│         → Saved to pending watchlist, re-evaluated next scan");
            } else {
                status = "❌ SCORE_LOW";
                System.out.printf("│      ❌ SCORE TOO LOW — %d/12 < %d (no signal)%n",
                    dec.totalScore, SCORE_THRESHOLD);
            }
            System.out.println("└" + "─".repeat(57));

            results.add(new String[]{
                ticker,
                status,
                String.valueOf(dec.totalScore),
                String.valueOf(dec.volumeScore),
                String.valueOf(dec.trendScore),
                String.valueOf(dec.momentumScore),
                String.valueOf(dec.setupScore),
                String.format("$%.2f", ind.currentPrice),
                dec.entryTriggerPrice > 0 ? String.format("$%.2f", dec.entryTriggerPrice) : "-"
            });

            try { Thread.sleep(13000); } catch (InterruptedException ignored) {}
        }

        // ── Phase 5: Summary table sorted by score ────────────────────────────────
        results.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(b[2]), Integer.parseInt(a[2])); }
            catch (NumberFormatException e) { return 0; }
        });

        System.out.println("\n╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              SUMMARY — MATERIALS SECTOR SCORING                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.printf("%-6s  %-17s  %5s  %2s  %2s  %2s  %2s  %9s  %9s%n",
            "TICKER","STATUS","SCORE","V","T","M","S","PRICE","TRIGGER");
        System.out.println("─".repeat(72));
        for (String[] r : results) {
            System.out.printf("%-6s  %-17s  %5s  %2s  %2s  %2s  %2s  %9s  %9s%n",
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
        }
        System.out.println("─".repeat(72));

        long signals  = results.stream().filter(r -> r[1].contains("SIGNAL") && !r[1].contains("WAIT")).count();
        long waiting  = results.stream().filter(r -> r[1].contains("WAIT")).count();
        long rejected = results.stream().filter(r -> r[1].contains("SCORE_LOW")).count();
        long skipped  = results.stream().filter(r -> r[1].contains("FAIL") || r[1].contains("SKIP") || r[1].contains("NULL")).count();
        System.out.printf("✅ Signals: %d  |  ⏳ Trigger-Wait: %d  |  ❌ Score-Low: %d  |  ⚠ Skipped: %d%n",
            signals, waiting, rejected, skipped);
        System.out.printf("Regime: %s  |  Agent: %s  |  Threshold: %d/12%n",
            regime, SCORING_AGENT_ID, SCORE_THRESHOLD);
        System.out.println("═".repeat(72));
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

    /**
     * Full Scan Flow Integration Test — DELL + M5_MA_CROSS
     *
     * Walks through every section of SCAN_FLOW.md in order:
     *   §1  Agent Loading
     *   §7  Market Regime Guard  (SPY check)
     *   §2  Scan Loop            (one API call for DELL, shared)
     *   §3a Indicator Computation (computeIndicators)
     *   §4  Variant Agent Path   (Layer 1 gates + Layer 2 triggers)
     *   §6  Trade Execution / SL-TP
     *   §8  Key Constants
     *
     * Assertions (marked ✔) verify correctness; display lines are informational.
     * One real Alpha Vantage API call is made for DELL data.
     */
    @Test
    void testFullScanFlowDellM5() throws Exception {
        final String TICKER      = "DELL";
        final String AGENT_ID    = "M5_MA_CROSS";
        final int    SCORE_THRESHOLD = 10;

        System.out.println("\n╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║      FULL SCAN FLOW  —  DELL  ×  M5_MA_CROSS  (SCAN_FLOW.md)        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");

        // ── §1  Agent Loading ─────────────────────────────────────────────────
        System.out.println("\n━━━ §1  AGENT LOADING ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        AIToolAgent.AgentConfig agent = AIToolAgent.getAgentConfig(AGENT_ID);
        assertNotNull(agent, "✘ " + AGENT_ID + " must be loaded by initialize()");
        System.out.println("  ✔ Agent loaded: " + agent.id + " — " + agent.name);
        System.out.println("  ✔ Entry type:   " + agent.entryType);
        System.out.println("  ✔ Strategy type: " + agent.strategyType);
        System.out.println("  Filters:");
        agent.entryFilters.forEach((k, v) -> System.out.printf("     %-30s = %s%n", k, v));
        System.out.println("  Risk:");
        agent.riskManagement.forEach((k, v) -> System.out.printf("     %-30s = %s%n", k, v));

        // ── §8  Key Constants ─────────────────────────────────────────────────
        System.out.println("\n━━━ §8  KEY CONSTANTS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  SCORE_THRESHOLD        = " + SCORE_THRESHOLD + " / 12");
        System.out.println("  MIN_STOP_LOSS_PCT       = 3.0%");
        System.out.println("  MAX_TRADES_PER_SCAN     = 5");
        System.out.println("  MAX_OPEN_POSITIONS      = 5");
        System.out.println("  MAX_OPEN_PER_SECTOR     = 2");
        System.out.println("  API sleep between ticks = 12 500 ms");

        // ── §7  Market Regime Guard ───────────────────────────────────────────
        System.out.println("\n━━━ §7  MARKET REGIME GUARD ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        AIToolAgent.RegimeLevel regime = AIToolAgent.getLastKnownRegime();
        System.out.println("  Last known regime: " + regime + "  (" + AIToolAgent.getLastRegimeDetail() + ")");
        System.out.println("  Rules: HEALTHY=full size | WEAK=50% size | VERY_WEAK=no trades");
        if (regime == AIToolAgent.RegimeLevel.VERY_WEAK) {
            System.out.println("  ⚠ VERY_WEAK — new trades would be BLOCKED in live scan");
        } else {
            System.out.println("  ✔ Regime allows new trades");
        }

        // ── §2  Scan Loop — one shared API call for DELL ──────────────────────
        System.out.println("\n━━━ §2  SCAN LOOP — fetch DELL (1 API call, shared) ━━━━━━━━━━━━━━━━━");
        DataFetcher.setTicker(TICKER);
        String json = DataFetcher.fetchStockData();
        assertNotNull(json,      "✘ fetchStockData() returned null for " + TICKER);
        assertFalse(json.isBlank(), "✘ fetchStockData() returned empty JSON for " + TICKER);
        System.out.printf("  ✔ JSON received: %,d bytes%n", json.length());

        // ── §3a  Indicator Computation ────────────────────────────────────────
        System.out.println("\n━━━ §3a  INDICATOR COMPUTATION  (computeIndicators) ━━━━━━━━━━━━━━━━━");
        AIToolAgent.IndicatorData ind = AIToolAgent.computeIndicatorsPublic(TICKER, json);
        assertNotNull(ind,     "✘ computeIndicators() returned null");
        assertTrue(ind.valid,  "✘ IndicatorData.valid=false — insufficient price history");

        System.out.printf("  ✔ currentPrice     = $%.2f%n",           ind.currentPrice);
        System.out.printf("     prevClose        = $%.2f  (%+.2f%%)%n", ind.prevClose, ind.todayChangePct);
        System.out.printf("     SMA20 / 50 / 200 = $%.2f / $%.2f / $%.2f%n", ind.sma20, ind.sma50, ind.sma200);
        System.out.printf("     abvSMA20=%b  abvSMA50=%b  abvSMA200=%b  maCross↑=%b%n",
            ind.priceAboveSMA20, ind.priceAboveSMA50, ind.priceAboveSMA200, ind.maCrossoverUp);
        System.out.printf("     RSI-14           = %.2f%n",  ind.rsi);
        System.out.printf("     CCI-20           = %.2f%n",  ind.cci);
        System.out.printf("     RVOL             = %.2fx%n", ind.rvol);
        System.out.printf("     ATR (raw)        = $%.2f   ATR%%=%.2f%%%n", ind.atr, ind.atrPct);
        System.out.printf("     Momentum-20d     = %+.2f%%  (RS proxy)%n", ind.momentum20d);
        System.out.printf("     Resistance-30d   = $%.2f%n", ind.resistance30d);
        System.out.printf("     Week-52 High     = $%.2f  (%.1f%% from high)%n",
            ind.week52High, ind.pctFromWeek52High);
        System.out.printf("     prevHigh         = $%.2f%n", ind.prevHigh);
        System.out.printf("     VWAP%%            = %+.2f%%%n", ind.vwapPct);

        // ── §4  Variant Agent Path — Layer 1 Gate-by-Gate Evaluation ─────────
        System.out.println("\n━━━ §4  VARIANT PATH — LAYER 1: SCAN GATES ━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("  %-5s  %-30s  %-22s  %s%n", "Gate", "Filter", "Threshold", "Result");
        System.out.println("  " + "─".repeat(75));

        double rsiMin = 45, rsiMax = 70;
        boolean g1 = ind.rsi >= rsiMin && ind.rsi <= rsiMax;
        printGate("G1", "rsiMin / rsiMax",
            String.format("%.0f–%.0f", rsiMin, rsiMax),
            String.format("RSI=%.1f", ind.rsi), g1);

        boolean g2 = ind.priceAboveSMA20;
        printGate("G2", "price > SMA20 (always)",
            String.format("$%.2f", ind.sma20),
            String.format("price=$%.2f", ind.currentPrice), g2);

        double cciMin = 50, cciMax = 350;
        boolean g3 = ind.cci >= cciMin && ind.cci <= cciMax;
        printGate("G3", "cciMin / cciMax",
            String.format("%.0f–%.0f", cciMin, cciMax),
            String.format("CCI=%.1f", ind.cci), g3);

        // Gate 4: no atrMin/atrMax in M5_MA_CROSS → always pass
        printGate("G4", "atrMin / atrMax", "(not set)", "—", true);

        double rsMin = 1.02;
        double rs = (ind.momentum20d / 100.0) + 1.0;
        boolean g5 = rs >= rsMin;
        printGate("G5", "rsMin (20d momentum ratio)",
            String.format("≥ %.2f", rsMin),
            String.format("RS=%.3f", rs), g5);

        double rvolMin = 1.3;
        boolean g6 = ind.rvol >= rvolMin;
        printGate("G6", "rvolMin",
            String.format("≥ %.1fx", rvolMin),
            String.format("RVOL=%.2fx", ind.rvol), g6);

        // Gates 7, 8: not configured in M5_MA_CROSS
        printGate("G7", "volumeThreshold", "(not set)", "—", true);
        printGate("G8", "cmfMin",          "(not set)", "—", true);

        boolean g9 = ind.priceAboveSMA200;
        printGate("G9", "sma200Required",
            String.format("price > SMA200 ($%.2f)", ind.sma200),
            String.format("price=$%.2f", ind.currentPrice), g9);

        // Gates 10, 11: not configured in M5_MA_CROSS
        printGate("G10", "smaWindows",       "(not set)", "—", true);
        printGate("G11", "priceAboveVwapPct","(not set)", "—", true);

        boolean allGatesPass = g1 && g2 && g3 && g5 && g6 && g9;
        System.out.println("  " + "─".repeat(75));
        System.out.printf("  Layer 1 overall: %s%n",
            allGatesPass ? "✅ ALL GATES PASS" : "❌ REJECTED at one or more gates");

        // ── §4  Layer 2: Entry Triggers — via analyzeStockPublic ─────────────
        System.out.println("\n━━━ §4  VARIANT PATH — LAYER 2: ENTRY TRIGGERS ━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Running analyzeStockPublic(DELL, M5_MA_CROSS, json) ...");

        AIToolAgent.TradeDecision decision = AIToolAgent.analyzeStockPublic(TICKER, agent, json);
        assertNotNull(decision, "✘ analyzeStockPublic() returned null — check gate logic");

        System.out.println();
        System.out.printf("  T1 maCrossoverRequired   : MA9>MA21 — ");
        System.out.println("(see triggerNotMet/rejectReason below)");

        System.out.printf("  T2 prevHighBreakout      : not configured in M5_MA_CROSS%n");

        double distFromResistance = (ind.resistance30d > 0 && ind.currentPrice > 0)
            ? ((ind.resistance30d - ind.currentPrice) / ind.currentPrice) * 100 : 0;
        System.out.printf("  T3 distanceFromResistance: (R30d - price) / price = %.1f%%  (need ≥ 5.0%%)  → %s%n",
            distFromResistance,
            distFromResistance >= 5.0 ? "✔ pass" : "⚠ deferred");

        System.out.printf("  T4 TREND_CONTINUATION    : price>SMA50=%b  RSI∈[50,70]=%b%n",
            ind.priceAboveSMA50,
            ind.rsi >= 50 && ind.rsi <= 70);
        System.out.printf("     trigger price would be: max(price, SMA50) × 1.005 = $%.2f%n",
            Math.max(ind.currentPrice, ind.sma50) * 1.005);

        System.out.println();
        System.out.println("  ── Decision from analyzeStock ──");
        System.out.printf("  shouldTrade    = %b%n",  decision.shouldTrade);
        System.out.printf("  triggerNotMet  = %b%n",  decision.triggerNotMet);
        System.out.printf("  rejectReason   = %s%n",
            decision.rejectReason != null ? decision.rejectReason : "(none)");
        System.out.printf("  entryTriggerPx = $%.2f%n", decision.entryTriggerPrice);

        // ── §6  SL / TP ───────────────────────────────────────────────────────
        System.out.println("\n━━━ §6  TRADE EXECUTION — SL / TP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        double entryPrice = decision.entryPrice > 0 ? decision.entryPrice : ind.currentPrice;
        System.out.printf("  Entry price      = $%.2f%n", entryPrice);
        System.out.printf("  suggestedSL      = $%.2f  (%.2f%% below entry)%n",
            decision.suggestedStopLoss,
            entryPrice > 0 ? (entryPrice - decision.suggestedStopLoss) / entryPrice * 100 : 0);
        System.out.printf("  suggestedTP      = $%.2f  (%.2f%% above entry)%n",
            decision.suggestedTakeProfit,
            entryPrice > 0 ? (decision.suggestedTakeProfit - entryPrice) / entryPrice * 100 : 0);
        System.out.printf("  R/R ratio        = %.2fx%n",
            (decision.suggestedStopLoss > 0 && entryPrice > decision.suggestedStopLoss)
                ? (decision.suggestedTakeProfit - entryPrice) / (entryPrice - decision.suggestedStopLoss)
                : 0);
        System.out.printf("  ATR-based SL     = entry - (ATR × 2.0) = $%.2f - $%.2f = $%.2f%n",
            entryPrice, ind.atr * 2.0, entryPrice - ind.atr * 2.0);
        System.out.printf("  PCT-based SL     = entry × (1 - 3.5%%) = $%.2f%n",
            entryPrice * (1 - 3.5 / 100));
        System.out.printf("  Floor SL (3%%)    = entry × 0.97 = $%.2f%n",
            entryPrice * 0.97);
        System.out.println("  calculateFinalStopPrice picks the wider of ATR/PCT, floored at 3%");

        if (decision.suggestedStopLoss > 0) {
            assertTrue(decision.suggestedStopLoss < entryPrice,
                "✘ SL must be below entry price");
            double slPct = (entryPrice - decision.suggestedStopLoss) / entryPrice * 100;
            assertTrue(slPct >= 3.0,
                String.format("✘ SL %.2f%% violates MIN_STOP_LOSS_PCT=3.0%%", slPct));
            System.out.printf("  ✔ SL assertion: %.2f%% ≥ 3.0%% (MIN_STOP_LOSS_PCT)%n", slPct);
        }
        if (decision.suggestedTakeProfit > 0) {
            assertTrue(decision.suggestedTakeProfit > entryPrice,
                "✘ TP must be above entry price");
            double tpDollar = decision.suggestedTakeProfit - entryPrice;
            assertTrue(tpDollar >= 25.0,
                String.format("✘ TP $%.2f profit < $25 min TP floor", tpDollar));
            System.out.printf("  ✔ TP assertion: $%.2f profit ≥ $25 (min TP floor)%n", tpDollar);
        }

        // ── Final Outcome ─────────────────────────────────────────────────────
        System.out.println("\n━━━ FINAL OUTCOME ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        if (decision.shouldTrade) {
            System.out.println("  ✅  BUY SIGNAL — all gates + triggers passed");
            System.out.printf("      Entry=$%.2f  SL=$%.2f  TP=$%.2f%n",
                decision.entryPrice, decision.suggestedStopLoss, decision.suggestedTakeProfit);
            assertFalse(decision.triggerNotMet,
                "✘ shouldTrade=true but triggerNotMet=true — contradiction");
        } else if (decision.triggerNotMet) {
            System.out.println("  ⏳  WATCHLIST — gates passed, waiting for entry trigger");
            System.out.printf("      Trigger price: $%.2f%n", decision.entryTriggerPrice);
            System.out.printf("      Reason: %s%n", decision.rejectReason);
            assertTrue(decision.entryTriggerPrice > 0,
                "✘ triggerNotMet=true but entryTriggerPrice not set");
        } else {
            System.out.println("  ❌  REJECTED at Layer 1 (scan gate failed)");
            System.out.printf("      Reason: %s%n",
                decision.rejectReason != null ? decision.rejectReason : "(no reason set)");
            assertNotNull(decision.rejectReason,
                "✘ rejected but rejectReason is null — gate should set rejectReason");
        }
        System.out.println("═".repeat(72));
    }

    /** Helper: prints a single Layer 1 gate row */
    private static void printGate(String gate, String name, String threshold, String actual, boolean pass) {
        System.out.printf("  %-5s  %-30s  %-22s  %s  %s%n",
            gate, name, threshold, actual, pass ? "✔" : "✘ FAIL");
    }
}
