import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Backtest Engine for historical strategy testing
 * Tests agents on historical data to calculate performance metrics
 */
public class BacktestEngine {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile BacktestResults lastResults = null;
    private static volatile String lastBacktestAgent = null;
    private static volatile boolean backtestRunning = false;
    
    /**
     * Backtest result for a single trade
     */
    public static class BacktestTrade {
        public String entryDate;
        public String exitDate;
        public String ticker;
        public String agent;
        public String setup;
        public double entryPrice;
        public double exitPrice;
        public double profitPct;
        public int daysHeld;
        public String exitReason; // "STOP", "TAKE_PROFIT", "TIME", "MANUAL"
        
        public BacktestTrade(String entryDate, String ticker, String agent, double entryPrice) {
            this.entryDate = entryDate;
            this.ticker = ticker;
            this.agent = agent;
            this.entryPrice = entryPrice;
        }
    }
    
    /**
     * Overall backtest results
     */
    public static class BacktestResults {
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double winRate;
        public double profitFactor;
        public double expectancy;
        public double maxDrawdown;
        public double cagr;
        public double sharpe;
        public double totalProfit;
        public double totalLoss;
        public double avgWin;
        public double avgLoss;
        public List<BacktestTrade> trades;
        public Map<String, Integer> tradesByMonth;
        
        public BacktestResults() {
            this.trades = new ArrayList<>();
            this.tradesByMonth = new LinkedHashMap<>();
        }
    }
    
    /**
     * Run backtest on a single agent
     */
    public static BacktestResults runBacktest(
            String agentId,
            List<String> tickers,
            String startDate,
            String endDate,
            double stopLossPct,
            double takeProfitPct,
            int maxDaysHeld
    ) {
        backtestRunning = true;
        lastBacktestAgent = agentId;
        BacktestResults results = new BacktestResults();
        
        for (String ticker : tickers) {
            try {
                List<BacktestTrade> tickerTrades = backtestTicker(
                    ticker, agentId, startDate, endDate, stopLossPct, takeProfitPct, maxDaysHeld
                );
                results.trades.addAll(tickerTrades);
                
                // No rate limiting needed when using cached data
                // Only applies when falling back to live API calls
                
            } catch (Exception e) {
                System.err.println("[Backtest] Error backtesting " + ticker + ": " + e.getMessage());
            }
        }
        
        calculateMetrics(results);
        lastResults = results;
        backtestRunning = false;
        return results;
    }
    
    public static BacktestResults getLastResults() {
        return lastResults;
    }
    
    public static String getLastBacktestAgent() {
        return lastBacktestAgent;
    }
    
    public static boolean isBacktestRunning() {
        return backtestRunning;
    }
    
    /**
     * Backtest a single ticker
     */
    private static List<BacktestTrade> backtestTicker(
            String ticker,
            String agentId,
            String startDate,
            String endDate,
            double stopLossPct,
            double takeProfitPct,
            int maxDaysHeld
    ) {
        List<BacktestTrade> trades = new ArrayList<>();
        
        try {
            // Try to use cached data first
            MarketDataCache.TickerData cachedData = MarketDataCache.getTickerData(ticker);
            
            if (cachedData != null) {
                // Use cached data - much faster!
                return backtestWithCachedData(cachedData, agentId, startDate, endDate, stopLossPct, takeProfitPct, maxDaysHeld);
            }
            
            // Fallback to live API calls if no cache
            System.out.println("[Backtest] No cache for " + ticker + ", using live API");
            return backtestWithLiveAPI(ticker, agentId, startDate, endDate, stopLossPct, takeProfitPct, maxDaysHeld);
            
        } catch (Exception e) {
            System.err.println("[Backtest] Error processing " + ticker + ": " + e.getMessage());
        }
        
        return trades;
    }
    
    /**
     * Run backtest using cached data (fast)
     */
    private static List<BacktestTrade> backtestWithCachedData(
            MarketDataCache.TickerData cachedData,
            String agentId,
            String startDate,
            String endDate,
            double stopLossPct,
            double takeProfitPct,
            int maxDaysHeld
    ) {
        List<BacktestTrade> trades = new ArrayList<>();
        
        try {
            // Filter dates within range
            List<LocalDate> dates = new ArrayList<>();
            Map<LocalDate, MarketDataCache.DailyData> dataByDate = new TreeMap<>();
            
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            
            for (Map.Entry<String, MarketDataCache.DailyData> entry : cachedData.dailyData.entrySet()) {
                LocalDate date = LocalDate.parse(entry.getKey());
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    dates.add(date);
                    dataByDate.put(date, entry.getValue());
                }
            }
            Collections.sort(dates);
            
            double revenueGrowth = cachedData.revenueGrowth != null ? cachedData.revenueGrowth : 0.0;
            double epsGrowth = cachedData.epsGrowth != null ? cachedData.epsGrowth : 0.0;
            
            System.out.println("[Backtest] " + cachedData.symbol + " (CACHED) - Revenue Growth: " + revenueGrowth + 
                "%, EPS Growth: " + epsGrowth + "%, Data points: " + dates.size());
            
            // Walk through dates and check entry conditions
            for (int i = 200; i < dates.size(); i++) {
                LocalDate date = dates.get(i);
                MarketDataCache.DailyData dayData = dataByDate.get(date);
                
                if (dayData.sma200 == null || dayData.rsi == null || dayData.rvol == null) {
                    continue; // Skip if indicators not available
                }
                
                // Check entry conditions based on agent
                if (shouldEnter(agentId, date, dayData.close, dayData.volume, dayData.sma200, dayData.sma50, 
                                  dayData.rsi, dayData.rvol, revenueGrowth, epsGrowth)) {
                    
                    BacktestTrade trade = new BacktestTrade(date.toString(), cachedData.symbol, agentId, dayData.close);
                    
                    // Simulate trade forward
                    for (int j = i + 1; j < dates.size() && j < i + maxDaysHeld; j++) {
                        LocalDate futureDate = dates.get(j);
                        MarketDataCache.DailyData futureData = dataByDate.get(futureDate);
                        
                        double profitPct = ((futureData.close - dayData.close) / dayData.close) * 100.0;
                        
                        // Check exit conditions
                        if (profitPct <= -stopLossPct) {
                            trade.exitDate = futureDate.toString();
                            trade.exitPrice = futureData.close;
                            trade.profitPct = profitPct;
                            trade.daysHeld = j - i;
                            trade.exitReason = "STOP";
                            trades.add(trade);
                            i = j; // Skip ahead
                            break;
                        } else if (profitPct >= takeProfitPct) {
                            trade.exitDate = futureDate.toString();
                            trade.exitPrice = futureData.close;
                            trade.profitPct = profitPct;
                            trade.daysHeld = j - i;
                            trade.exitReason = "TAKE_PROFIT";
                            trades.add(trade);
                            i = j; // Skip ahead
                            break;
                        }
                    }
                    
                    // If still open after maxDaysHeld, close it
                    if (trade.exitDate == null) {
                        int endIndex = Math.min(i + maxDaysHeld, dates.size() - 1);
                        LocalDate exitDate = dates.get(endIndex);
                        MarketDataCache.DailyData exitData = dataByDate.get(exitDate);
                        
                        trade.exitDate = exitDate.toString();
                        trade.exitPrice = exitData.close;
                        trade.profitPct = ((exitData.close - dayData.close) / dayData.close) * 100.0;
                        trade.daysHeld = endIndex - i;
                        trade.exitReason = "TIME";
                        trades.add(trade);
                        i = endIndex;
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("[Backtest] Error processing cached data for " + cachedData.symbol + ": " + e.getMessage());
        }
        
        return trades;
    }
    
    /**
     * Run backtest using live API calls (slow, rate-limited)
     */
    private static List<BacktestTrade> backtestWithLiveAPI(
            String ticker,
            String agentId,
            String startDate,
            String endDate,
            double stopLossPct,
            double takeProfitPct,
            int maxDaysHeld
    ) {
        List<BacktestTrade> trades = new ArrayList<>();
        
        try {
            // Fetch historical daily data
            String dailyData = DataFetcher.fetchStockDataForTicker(ticker);
            if (dailyData == null) return trades;
            
            JsonNode root = mapper.readTree(dailyData);
            JsonNode timeSeries = root.path("Time Series (Daily)");
            
            if (!timeSeries.isObject()) return trades;
            
            // Parse dates and sort
            List<LocalDate> dates = new ArrayList<>();
            Map<LocalDate, JsonNode> dataByDate = new TreeMap<>();
            
            Iterator<Map.Entry<String, JsonNode>> it = timeSeries.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                LocalDate date = LocalDate.parse(entry.getKey());
                if (!date.isBefore(LocalDate.parse(startDate)) && !date.isAfter(LocalDate.parse(endDate))) {
                    dates.add(date);
                    dataByDate.put(date, entry.getValue());
                }
            }
            Collections.sort(dates);
            
            // Calculate indicators
            Map<LocalDate, Double> sma200 = calculateSMA(dataByDate, 200);
            Map<LocalDate, Double> sma50 = calculateSMA(dataByDate, 50);
            Map<LocalDate, Double> rsi = calculateRSI(dataByDate, 14);
            Map<LocalDate, Double> rvol = calculateRVOL(dataByDate, 20);
            
            // Fetch fundamental data (once per ticker)
            String incomeStatement = DataFetcher.fetchIncomeStatement(ticker);
            String earnings = DataFetcher.fetchEarnings(ticker);
            
            double revenueGrowth = DataFetcher.calculateRevenueGrowth(incomeStatement);
            double epsGrowth = DataFetcher.calculateEPSGrowth(earnings);
            
            System.out.println("[Backtest] " + ticker + " (LIVE API) - Revenue Growth: " + revenueGrowth + "%, EPS Growth: " + epsGrowth + "%, Data points: " + dates.size());
            
            // Walk through dates and check entry conditions
            for (int i = 200; i < dates.size(); i++) {
                LocalDate date = dates.get(i);
                JsonNode dayData = dataByDate.get(date);
                
                double close = dayData.path("4. close").asDouble();
                double volume = dayData.path("5. volume").asDouble();
                
                // Check entry conditions based on agent
                if (shouldEnter(agentId, date, close, volume, sma200.get(date), sma50.get(date), 
                                  rsi.get(date), rvol.get(date), revenueGrowth, epsGrowth)) {
                    
                    BacktestTrade trade = new BacktestTrade(date.toString(), ticker, agentId, close);
                    
                    // Simulate trade forward
                    for (int j = i + 1; j < dates.size() && j < i + maxDaysHeld; j++) {
                        LocalDate futureDate = dates.get(j);
                        JsonNode futureData = dataByDate.get(futureDate);
                        double futureClose = futureData.path("4. close").asDouble();
                        
                        double profitPct = ((futureClose - close) / close) * 100.0;
                        
                        // Check exit conditions
                        if (profitPct <= -stopLossPct) {
                            trade.exitDate = futureDate.toString();
                            trade.exitPrice = futureClose;
                            trade.profitPct = profitPct;
                            trade.daysHeld = j - i;
                            trade.exitReason = "STOP";
                            trades.add(trade);
                            i = j; // Skip ahead
                            break;
                        } else if (profitPct >= takeProfitPct) {
                            trade.exitDate = futureDate.toString();
                            trade.exitPrice = futureClose;
                            trade.profitPct = profitPct;
                            trade.daysHeld = j - i;
                            trade.exitReason = "TAKE_PROFIT";
                            trades.add(trade);
                            i = j; // Skip ahead
                            break;
                        }
                    }
                    
                    // If still open after maxDaysHeld, close it
                    if (trade.exitDate == null) {
                        int endIndex = Math.min(i + maxDaysHeld, dates.size() - 1);
                        LocalDate exitDate = dates.get(endIndex);
                        JsonNode exitData = dataByDate.get(exitDate);
                        double exitPrice = exitData.path("4. close").asDouble();
                        
                        trade.exitDate = exitDate.toString();
                        trade.exitPrice = exitPrice;
                        trade.profitPct = ((exitPrice - close) / close) * 100.0;
                        trade.daysHeld = endIndex - i;
                        trade.exitReason = "TIME";
                        trades.add(trade);
                        i = endIndex;
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("[Backtest] Error processing " + ticker + ": " + e.getMessage());
        }
        
        return trades;
    }
    
    /**
     * Check if entry conditions are met for a specific agent
     */
    private static boolean shouldEnter(
            String agentId,
            LocalDate date,
            double close,
            double volume,
            Double sma200,
            Double sma50,
            Double rsi,
            Double rvol,
            double revenueGrowth,
            double epsGrowth
    ) {
        switch (agentId) {
            case "FUND_MOMENTUM_V1":
                return revenueGrowth > 15.0 &&
                       epsGrowth > 20.0 &&
                       rsi != null && rsi >= 50 && rsi <= 70 &&
                       rvol != null && rvol > 1.5 &&
                       sma200 != null && close > sma200;
            
            case "QUALITY_GROWTH_V1":
                return revenueGrowth > 15.0 &&
                       epsGrowth > 20.0 &&
                       rsi != null && rsi >= 40 && rsi <= 75 &&
                       rvol != null && rvol > 1.5 &&
                       sma200 != null && close > sma200 &&
                       sma50 != null && close > sma50;
            
            default:
                return false;
        }
    }
    
    /**
     * Calculate SMA for given period
     */
    private static Map<LocalDate, Double> calculateSMA(Map<LocalDate, JsonNode> dataByDate, int period) {
        Map<LocalDate, Double> sma = new LinkedHashMap<>();
        List<LocalDate> dates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(dates);
        
        for (int i = period; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            double sum = 0.0;
            for (int j = i - period; j < i; j++) {
                sum += dataByDate.get(dates.get(j)).path("4. close").asDouble();
            }
            sma.put(date, sum / period);
        }
        
        return sma;
    }
    
    /**
     * Calculate RSI
     */
    private static Map<LocalDate, Double> calculateRSI(Map<LocalDate, JsonNode> dataByDate, int period) {
        Map<LocalDate, Double> rsi = new LinkedHashMap<>();
        List<LocalDate> dates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(dates);
        
        if (dates.size() < period + 1) return rsi;
        
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        // Calculate initial gains/losses
        for (int i = 1; i <= period; i++) {
            double change = dataByDate.get(dates.get(i)).path("4. close").asDouble() - 
                           dataByDate.get(dates.get(i - 1)).path("4. close").asDouble();
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }
        
        double avgGain = gains.stream().mapToDouble(d -> d).average().orElse(0);
        double avgLoss = losses.stream().mapToDouble(d -> d).average().orElse(0);
        
        // Calculate RSI for remaining dates
        for (int i = period; i < dates.size(); i++) {
            double change = dataByDate.get(dates.get(i)).path("4. close").asDouble() - 
                           dataByDate.get(dates.get(i - 1)).path("4. close").asDouble();
            
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
            
            if (avgLoss == 0) {
                rsi.put(dates.get(i), 100.0);
            } else {
                double rs = avgGain / avgLoss;
                rsi.put(dates.get(i), 100.0 - (100.0 / (1.0 + rs)));
            }
        }
        
        return rsi;
    }
    
    /**
     * Calculate RVOL (Relative Volume)
     */
    private static Map<LocalDate, Double> calculateRVOL(Map<LocalDate, JsonNode> dataByDate, int period) {
        Map<LocalDate, Double> rvol = new LinkedHashMap<>();
        List<LocalDate> dates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(dates);
        
        for (int i = period; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            double currentVolume = dataByDate.get(date).path("5. volume").asDouble();
            
            double avgVolume = 0.0;
            for (int j = i - period; j < i; j++) {
                avgVolume += dataByDate.get(dates.get(j)).path("5. volume").asDouble();
            }
            avgVolume /= period;
            
            if (avgVolume > 0) {
                rvol.put(date, currentVolume / avgVolume);
            }
        }
        
        return rvol;
    }
    
    /**
     * Calculate performance metrics
     */
    private static void calculateMetrics(BacktestResults results) {
        results.totalTrades = results.trades.size();
        results.winningTrades = (int) results.trades.stream().filter(t -> t.profitPct > 0).count();
        results.losingTrades = results.totalTrades - results.winningTrades;
        
        if (results.totalTrades > 0) {
            results.winRate = (double) results.winningTrades / results.totalTrades * 100.0;
        }
        
        results.totalProfit = results.trades.stream().filter(t -> t.profitPct > 0).mapToDouble(t -> t.profitPct).sum();
        results.totalLoss = Math.abs(results.trades.stream().filter(t -> t.profitPct < 0).mapToDouble(t -> t.profitPct).sum());
        
        if (results.totalLoss > 0) {
            results.profitFactor = results.totalProfit / results.totalLoss;
        }
        
        if (results.totalTrades > 0) {
            results.expectancy = (results.totalProfit - results.totalLoss) / results.totalTrades;
        }
        
        if (results.winningTrades > 0) {
            results.avgWin = results.totalProfit / results.winningTrades;
        }
        
        if (results.losingTrades > 0) {
            results.avgLoss = results.totalLoss / results.losingTrades;
        }
        
        // Calculate max drawdown
        double peak = 0.0;
        double maxDD = 0.0;
        double cumulative = 0.0;
        
        for (BacktestTrade trade : results.trades) {
            cumulative += trade.profitPct;
            if (cumulative > peak) peak = cumulative;
            double dd = peak - cumulative;
            if (dd > maxDD) maxDD = dd;
        }
        results.maxDrawdown = maxDD;
        
        // Calculate trades by month
        for (BacktestTrade trade : results.trades) {
            String month = trade.entryDate.substring(0, 7); // YYYY-MM
            results.tradesByMonth.put(month, results.tradesByMonth.getOrDefault(month, 0) + 1);
        }
        
        // CAGR and Sharpe would require more complex calculations with initial capital
        // Simplified versions for now
        results.cagr = results.expectancy * 252 / 100.0; // Assume 252 trading days per year
        results.sharpe = results.expectancy > 0 ? results.expectancy / 2.0 : 0.0; // Simplified
    }
}
