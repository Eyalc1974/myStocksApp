import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Agent Backtester - Runs agents on historical data to evaluate performance
 * Simulates daily agent scans on historical data using Alpha Vantage API
 * Uses the same indicators and logic as the live agents
 */
public class AgentBacktester {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path STRATEGIES_DIR = Paths.get(".");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Backtest result for a single trade
     */
    public static class BacktestTrade {
        public String entryDate;
        public String exitDate;
        public String ticker;
        public String agentId;
        public String agentName;
        public double entryPrice;
        public double exitPrice;
        public double profitPct;
        public double profitAmount;
        public int daysHeld;
        public String exitReason; // "STOP_LOSS", "TAKE_PROFIT", "TIME_EXIT", "SIGNAL_EXIT"
        public String setupType;

        public BacktestTrade(String entryDate, String ticker, String agentId, String agentName, double entryPrice) {
            this.entryDate = entryDate;
            this.ticker = ticker;
            this.agentId = agentId;
            this.agentName = agentName;
            this.entryPrice = entryPrice;
        }
    }

    /**
     * Track an open position during backtest
     */
    private static class OpenPosition {
        String ticker;
        String entryDate;
        double entryPrice;
        int shares;
        double stopLoss;
        double takeProfit;
        double trailingStop;
        String setupType;

        public OpenPosition(String ticker, String entryDate, double entryPrice, int shares, 
                          double stopLoss, double takeProfit, String setupType) {
            this.ticker = ticker;
            this.entryDate = entryDate;
            this.entryPrice = entryPrice;
            this.shares = shares;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.trailingStop = stopLoss;
            this.setupType = setupType;
        }
    }

    /**
     * Overall backtest results for an agent
     */
    public static class AgentBacktestResult {
        public String agentId;
        public String agentName;
        public String startDate;
        public String endDate;
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double winRate;
        public double totalProfit;
        public double totalLoss;
        public double netProfit;
        public double profitFactor;
        public double avgWin;
        public double avgLoss;
        public double maxDrawdown;
        public double expectancy;
        public double sharpeRatio;
        public double cagr;
        public List<BacktestTrade> trades;
        public Map<String, Integer> tradesByMonth;
        public Map<String, Double> profitByTicker;
        public String error;

        public AgentBacktestResult() {
            this.trades = new ArrayList<>();
            this.tradesByMonth = new LinkedHashMap<>();
            this.profitByTicker = new LinkedHashMap<>();
        }
    }

    /**
     * Load all agent configurations from JSON files
     */
    private static List<AIToolAgent.AgentConfig> loadAllAgents() {
        List<AIToolAgent.AgentConfig> agents = new ArrayList<>();
        
        String[] agentFiles = {
            "quality-growth-agent.json",
            "fundamental-momentum-agent.json",
            "institutional-swing-agent.json"
        };

        // Try to find files in project root or current directory
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        
        for (String fileName : agentFiles) {
            try {
                Path filePath = projectRoot.resolve(fileName);
                if (!Files.exists(filePath)) {
                    // Fallback to current directory
                    filePath = Paths.get(fileName);
                }
                
                if (Files.exists(filePath)) {
                    String content = Files.readString(filePath);
                    JsonNode root = mapper.readTree(content);
                    JsonNode variants = root.path("variants");
                    
                    if (variants.isArray()) {
                        for (JsonNode variant : variants) {
                            if (variant.path("enabled").asBoolean(true)) {
                                AIToolAgent.AgentConfig config = parseAgentConfig(variant);
                                if (config != null) {
                                    agents.add(config);
                                }
                            }
                        }
                    }
                } else {
                    System.err.println("Agent file not found: " + filePath);
                }
            } catch (Exception e) {
                System.err.println("Error loading agent from " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return agents;
    }

    /**
     * Helper method to get double value from risk management map
     */
    private static double getDoubleRisk(AIToolAgent.AgentConfig agent, String key, double defaultValue) {
        Object value = agent.riskManagement.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Parse agent configuration from JSON
     */
    private static AIToolAgent.AgentConfig parseAgentConfig(JsonNode variant) {
        try {
            AIToolAgent.AgentConfig config = new AIToolAgent.AgentConfig();
            config.id = variant.path("id").asText();
            config.name = variant.path("name").asText();
            config.stocksPerVariant = variant.path("stocksPerVariant").asInt(2);
            config.entryType = variant.path("entryType").asText();
            
            // Parse entry filters
            JsonNode entryFilters = variant.path("entryFilters");
            config.entryFilters = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = entryFilters.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isBoolean()) {
                    config.entryFilters.put(key, value.asBoolean());
                } else if (value.isDouble()) {
                    config.entryFilters.put(key, value.asDouble());
                } else if (value.isInt()) {
                    config.entryFilters.put(key, value.asInt());
                } else if (value.isTextual()) {
                    config.entryFilters.put(key, value.asText());
                }
            }
            
            // Parse risk management
            JsonNode riskMgmt = variant.path("riskManagement");
            config.riskManagement.put("stopLossPct", riskMgmt.path("stopLossPct").asDouble(5.0));
            config.riskManagement.put("takeProfitPct", riskMgmt.path("takeProfitPct").asDouble(15.0));
            config.riskManagement.put("trailingStopPct", riskMgmt.path("trailingStopPct").asDouble(3.0));
            config.riskManagement.put("atrMultiplier", riskMgmt.path("atrMultiplier").asDouble(2.0));
            
            return config;
        } catch (Exception e) {
            System.err.println("Error parsing agent config: " + e.getMessage());
            return null;
        }
    }

    /**
     * Run backtest for a single agent
     */
    public static AgentBacktestResult backtestAgent(
            String agentId,
            String startDate,
            String endDate,
            List<String> tickerUniverse,
            double initialCapital
    ) {
        AgentBacktestResult result = new AgentBacktestResult();
        result.agentId = agentId;
        result.startDate = startDate;
        result.endDate = endDate;

        try {
            // Load agent configuration
            List<AIToolAgent.AgentConfig> agents = loadAllAgents();
            AIToolAgent.AgentConfig targetAgent = agents.stream()
                .filter(a -> a.id.equals(agentId))
                .findFirst()
                .orElse(null);

            if (targetAgent == null) {
                result.error = "Agent not found: " + agentId;
                return result;
            }

            result.agentName = targetAgent.name;

            // Use provided ticker universe or default to NASDAQ 100
            List<String> tickers = tickerUniverse != null && !tickerUniverse.isEmpty() 
                ? tickerUniverse 
                : getNASDAQ100Tickers();

            // Limit to reasonable number for backtesting
            tickers = tickers.subList(0, Math.min(tickers.size(), 20));

            // Calculate date range in days - need extra buffer for indicators
            LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER).minusDays(300); // 300 day buffer for indicators
            LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);
            List<LocalDate> tradingDays = getTradingDays(start, end);

            System.out.println("[AgentBacktester] Backtesting " + agentId + " from " + startDate + " to " + endDate);
            System.out.println("[AgentBacktester] Analyzing " + tickers.size() + " tickers over " + tradingDays.size() + " trading days");

            // Fetch full historical data for all tickers ONCE
            Map<String, JsonNode> allTickerData = new HashMap<>();
            for (String ticker : tickers) {
                try {
                    String fullData = DataFetcher.fetchStockDataForTicker(ticker);
                    if (fullData != null) {
                        JsonNode root = mapper.readTree(fullData);
                        JsonNode timeSeries = root.path("Time Series (Daily)");
                        if (timeSeries.isObject()) {
                            allTickerData.put(ticker, timeSeries);
                        }
                    }
                    System.out.println("[AgentBacktester] Fetched data for " + ticker);
                    Thread.sleep(1000); // Rate limiting
                } catch (Exception e) {
                    System.err.println("[AgentBacktester] Error fetching data for " + ticker + ": " + e.getMessage());
                }
            }

            // Track open positions
            Map<String, OpenPosition> openPositions = new HashMap<>();
            double currentCapital = initialCapital;

            // For each trading day, simulate agent scan and position management
            for (LocalDate currentDate : tradingDays) {
                String dateStr = currentDate.format(DATE_FORMATTER);
                
                // Skip dates before actual start date (buffer period)
                if (currentDate.isBefore(LocalDate.parse(startDate, DATE_FORMATTER))) {
                    // Still manage existing positions
                    manageOpenPositions(openPositions, allTickerData, dateStr, targetAgent, result);
                    continue;
                }

                // Skip if weekend
                if (currentDate.getDayOfWeek() == DayOfWeek.SATURDAY || 
                    currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }

                // First, manage existing positions (check stop loss, take profit, etc.)
                manageOpenPositions(openPositions, allTickerData, dateStr, targetAgent, result);

                // Then, run agent scan for new entries (if we have capital available)
                if (openPositions.size() < 5) { // Max 5 open positions
                    List<SignalWithTicker> signals = scanForSignals(
                        targetAgent, 
                        tickers, 
                        allTickerData, 
                        dateStr
                    );

                    // Sort by score and take top signals
                    signals.sort((a, b) -> Integer.compare(b.decision.totalScore, a.decision.totalScore));
                    int maxNewSignals = Math.min(
                        targetAgent.stocksPerVariant > 0 ? targetAgent.stocksPerVariant : 2,
                        5 - openPositions.size()
                    );
                    
                    for (int i = 0; i < Math.min(maxNewSignals, signals.size()); i++) {
                        SignalWithTicker signal = signals.get(i);
                        if (!openPositions.containsKey(signal.ticker)) {
                            openPosition(signal, targetAgent, dateStr, currentCapital, openPositions);
                        }
                    }
                }
            }

            // Close any remaining positions at end date
            closeAllPositions(openPositions, allTickerData, endDate, targetAgent, result);

            // Calculate metrics
            calculateMetrics(result, initialCapital);

        } catch (Exception e) {
            result.error = "Backtest failed: " + e.getMessage();
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Simple wrapper to associate ticker with TradeDecision
     */
    private static class SignalWithTicker {
        String ticker;
        AIToolAgent.TradeDecision decision;
        
        SignalWithTicker(String ticker, AIToolAgent.TradeDecision decision) {
            this.ticker = ticker;
            this.decision = decision;
        }
    }

    /**
     * Scan for new entry signals using historical data up to the specified date
     */
    private static List<SignalWithTicker> scanForSignals(
            AIToolAgent.AgentConfig agent,
            List<String> tickers,
            Map<String, JsonNode> allTickerData,
            String dateStr
    ) {
        List<SignalWithTicker> signals = new ArrayList<>();

        for (String ticker : tickers) {
            try {
                JsonNode timeSeries = allTickerData.get(ticker);
                if (timeSeries == null) continue;

                // Create filtered data with only dates up to the current date
                ObjectNode filteredData = mapper.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> it = timeSeries.fields();
                
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String dataDate = entry.getKey();
                    // Only include data up to current date (no future data leakage)
                    if (dataDate.compareTo(dateStr) <= 0) {
                        filteredData.set(dataDate, entry.getValue());
                    }
                }

                // Create full JSON structure
                ObjectNode root = mapper.createObjectNode();
                ObjectNode metaData = mapper.createObjectNode();
                metaData.put("2. Symbol", ticker);
                root.set("Meta Data", metaData);
                root.set("Time Series (Daily)", filteredData);

                String historicalJson = mapper.writeValueAsString(root);

                // Use AIToolAgent's analyzeStock logic
                AIToolAgent.TradeDecision decision = AIToolAgent.analyzeStockPublic(ticker, agent, historicalJson);

                if (decision != null && decision.shouldTrade) {
                    signals.add(new SignalWithTicker(ticker, decision));
                }

            } catch (Exception e) {
                System.err.println("[AgentBacktester] Error analyzing " + ticker + " on " + dateStr + ": " + e.getMessage());
            }
        }

        return signals;
    }

    /**
     * Open a new position based on a signal
     */
    private static void openPosition(
            SignalWithTicker signal,
            AIToolAgent.AgentConfig agent,
            String dateStr,
            double capital,
            Map<String, OpenPosition> openPositions
    ) {
        double entryPrice = signal.decision.entryPrice;
        double positionSize = capital * 0.2; // 20% of capital per trade
        int shares = (int) (positionSize / entryPrice);
        
        if (shares <= 0) return;

        double stopLoss = entryPrice * (1 - getDoubleRisk(agent, "stopLossPct", 5.0) / 100.0);
        double takeProfit = entryPrice * (1 + getDoubleRisk(agent, "takeProfitPct", 15.0) / 100.0);

        OpenPosition pos = new OpenPosition(
            signal.ticker,
            dateStr,
            entryPrice,
            shares,
            stopLoss,
            takeProfit,
            agent.name
        );

        openPositions.put(signal.ticker, pos);
        System.out.println("[AgentBacktester] Opened position: " + signal.ticker + " @ $" + entryPrice + " on " + dateStr);
    }

    /**
     * Manage existing positions - check stop loss, take profit, trailing stops
     */
    private static void manageOpenPositions(
            Map<String, OpenPosition> openPositions,
            Map<String, JsonNode> allTickerData,
            String dateStr,
            AIToolAgent.AgentConfig agent,
            AgentBacktestResult result
    ) {
        Iterator<Map.Entry<String, OpenPosition>> it = openPositions.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, OpenPosition> entry = it.next();
            OpenPosition pos = entry.getValue();

            try {
                JsonNode timeSeries = allTickerData.get(pos.ticker);
                if (timeSeries == null || !timeSeries.has(dateStr)) {
                    continue; // No data for this date
                }

                JsonNode dayData = timeSeries.get(dateStr);
                double close = dayData.path("4. close").asDouble();
                double high = dayData.path("2. high").asDouble();
                double low = dayData.path("3. low").asDouble();

                String exitReason = null;
                double exitPrice = close;

                // Check stop loss
                if (low <= pos.stopLoss) {
                    exitReason = "STOP_LOSS";
                    exitPrice = Math.max(close, pos.stopLoss);
                }
                // Check take profit
                else if (high >= pos.takeProfit) {
                    exitReason = "TAKE_PROFIT";
                    exitPrice = Math.min(close, pos.takeProfit);
                }
                // Check trailing stop
                else if (getDoubleRisk(agent, "trailingStopPct", 3.0) > 0) {
                    double newTrailingStop = close * (1 - getDoubleRisk(agent, "trailingStopPct", 3.0) / 100.0);
                    pos.trailingStop = Math.max(pos.trailingStop, newTrailingStop);
                    if (low <= pos.trailingStop) {
                        exitReason = "TRAILING_STOP";
                        exitPrice = Math.max(close, pos.trailingStop);
                    }
                }

                // If position should be closed
                if (exitReason != null) {
                    closePosition(pos, dateStr, exitPrice, exitReason, agent, result);
                    it.remove();
                }

            } catch (Exception e) {
                System.err.println("[AgentBacktester] Error managing position " + pos.ticker + ": " + e.getMessage());
            }
        }
    }

    /**
     * Close a position and record the trade
     */
    private static void closePosition(
            OpenPosition pos,
            String exitDate,
            double exitPrice,
            String exitReason,
            AIToolAgent.AgentConfig agent,
            AgentBacktestResult result
    ) {
        double profitPct = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0;
        double profitAmount = (exitPrice - pos.entryPrice) * pos.shares;

        BacktestTrade trade = new BacktestTrade(pos.entryDate, pos.ticker, agent.id, agent.name, pos.entryPrice);
        trade.exitDate = exitDate;
        trade.exitPrice = exitPrice;
        trade.profitPct = profitPct;
        trade.profitAmount = profitAmount;
        trade.daysHeld = (int) java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.parse(pos.entryDate, DATE_FORMATTER),
            LocalDate.parse(exitDate, DATE_FORMATTER)
        );
        trade.exitReason = exitReason;
        trade.setupType = pos.setupType;

        result.trades.add(trade);
        System.out.println("[AgentBacktester] Closed position: " + pos.ticker + " P&L: $" + profitAmount + " (" + exitReason + ")");
    }

    /**
     * Close all remaining positions at the end of the backtest
     */
    private static void closeAllPositions(
            Map<String, OpenPosition> openPositions,
            Map<String, JsonNode> allTickerData,
            String endDate,
            AIToolAgent.AgentConfig agent,
            AgentBacktestResult result
    ) {
        Iterator<Map.Entry<String, OpenPosition>> it = openPositions.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, OpenPosition> entry = it.next();
            OpenPosition pos = entry.getValue();

            try {
                JsonNode timeSeries = allTickerData.get(pos.ticker);
                double exitPrice = pos.entryPrice; // Default to entry price if no data

                if (timeSeries != null && timeSeries.has(endDate)) {
                    JsonNode dayData = timeSeries.get(endDate);
                    exitPrice = dayData.path("4. close").asDouble();
                } else if (timeSeries != null) {
                    // Get the last available price
                    Iterator<Map.Entry<String, JsonNode>> reverseIt = timeSeries.fields();
                    while (reverseIt.hasNext()) {
                        Map.Entry<String, JsonNode> dataEntry = reverseIt.next();
                        exitPrice = dataEntry.getValue().path("4. close").asDouble();
                    }
                }

                closePosition(pos, endDate, exitPrice, "END_OF_BACKTEST", agent, result);
                it.remove();

            } catch (Exception e) {
                System.err.println("[AgentBacktester] Error closing position " + pos.ticker + ": " + e.getMessage());
            }
        }
    }

    /**
     * Calculate performance metrics
     */
    private static void calculateMetrics(AgentBacktestResult result, double initialCapital) {
        result.totalTrades = result.trades.size();
        result.winningTrades = (int) result.trades.stream().filter(t -> t.profitPct > 0).count();
        result.losingTrades = result.totalTrades - result.winningTrades;

        if (result.totalTrades > 0) {
            result.winRate = (double) result.winningTrades / result.totalTrades * 100.0;
        }

        result.totalProfit = result.trades.stream()
            .filter(t -> t.profitPct > 0)
            .mapToDouble(t -> t.profitAmount)
            .sum();

        result.totalLoss = Math.abs(result.trades.stream()
            .filter(t -> t.profitPct < 0)
            .mapToDouble(t -> t.profitAmount)
            .sum());

        result.netProfit = result.totalProfit - result.totalLoss;

        if (result.totalLoss > 0) {
            result.profitFactor = result.totalProfit / result.totalLoss;
        }

        if (result.totalTrades > 0) {
            result.expectancy = result.netProfit / result.totalTrades;
        }

        if (result.winningTrades > 0) {
            result.avgWin = result.totalProfit / result.winningTrades;
        }

        if (result.losingTrades > 0) {
            result.avgLoss = result.totalLoss / result.losingTrades;
        }

        // Calculate max drawdown
        double peak = initialCapital;
        double maxDD = 0.0;
        double cumulative = initialCapital;

        for (BacktestTrade trade : result.trades) {
            cumulative += trade.profitAmount;
            if (cumulative > peak) peak = cumulative;
            double dd = peak - cumulative;
            if (dd > maxDD) maxDD = dd;
        }
        result.maxDrawdown = maxDD;

        // Calculate trades by month
        for (BacktestTrade trade : result.trades) {
            String month = trade.entryDate.substring(0, 7); // YYYY-MM
            result.tradesByMonth.put(month, result.tradesByMonth.getOrDefault(month, 0) + 1);
        }

        // Calculate profit by ticker
        for (BacktestTrade trade : result.trades) {
            result.profitByTicker.put(trade.ticker, 
                result.profitByTicker.getOrDefault(trade.ticker, 0.0) + trade.profitAmount);
        }

        // Simplified CAGR and Sharpe
        LocalDate start = LocalDate.parse(result.startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(result.endDate, DATE_FORMATTER);
        long years = java.time.temporal.ChronoUnit.YEARS.between(start, end);
        if (years == 0) years = 1;

        double totalReturn = (result.netProfit / initialCapital) * 100.0;
        result.cagr = totalReturn / years;

        result.sharpeRatio = result.expectancy > 0 ? result.expectancy / 2.0 : 0.0; // Simplified
    }

    /**
     * Get trading days between two dates (excluding weekends)
     */
    private static List<LocalDate> getTradingDays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate current = start;

        while (!current.isAfter(end)) {
            if (current.getDayOfWeek() != DayOfWeek.SATURDAY && 
                current.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(current);
            }
            current = current.plusDays(1);
        }

        return days;
    }

    /**
     * Get NASDAQ 100 tickers
     */
    private static List<String> getNASDAQ100Tickers() {
        return Arrays.asList(
            "AAPL","MSFT","GOOGL","AMZN","META","TSLA","NVDA","PEP","COST","AVGO",
            "ADBE","CSCO","NFLX","AMD","INTC","CMCSA","QCOM","INTU","SBUX","AMGN",
            "TXN","AMD","GILD","MDLZ","ISRG","REGN","ADI","CSX","ATVI","MRNA",
            "ILMN","BKNG","MAR","EA","SQ","SNPS","ADP","ORLY","KLAC","MELI",
            "CDNS","WDAY","NXPI","LRCX","FTNT","ASML","MNST","ABNB","CRWD","DDOG"
        );
    }

    /**
     * Get list of available agents
     */
    public static List<Map<String, String>> getAvailableAgents() {
        List<AIToolAgent.AgentConfig> agents = loadAllAgents();
        return agents.stream()
            .map(a -> {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("id", a.id);
                info.put("name", a.name);
                return info;
            })
            .collect(Collectors.toList());
    }
}
