import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Market Data Cache - Fetches and stores all required market data for backtesting
 * Eliminates need for repeated API calls during backtesting
 */
public class MarketDataCache {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String CACHE_FILE = "market_data_cache.json";
    
    /**
     * Cached data structure for a single ticker
     */
    public static class TickerData {
        public String symbol;
        public Map<String, DailyData> dailyData = new TreeMap<>(); // date -> daily data
        public Double revenueGrowth;
        public Double epsGrowth;
        public String lastUpdated;
        
        public TickerData() {
        }
        
        public TickerData(String symbol) {
            this.symbol = symbol;
            this.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        }
    }
    
    /**
     * Daily OHLCV data with pre-calculated indicators
     */
    public static class DailyData {
        public String date;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
        public Double sma200;
        public Double sma50;
        public Double rsi;
        public Double rvol;
    }
    
    /**
     * Complete cache structure
     */
    public static class MarketCache {
        public Map<String, TickerData> tickers = new LinkedHashMap<>();
        public String lastUpdated;
        public int totalTickers;
        
        public MarketCache() {
            this.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        }
    }
    
    private static MarketCache cache = null;
    
    /**
     * Progress callback interface for market data fetch
     */
    public interface ProgressCallback {
        void onProgress(int current, int total, String currentTicker);
    }
    
    private static ProgressCallback progressCallback = null;
    
    /**
     * Set progress callback for market data fetch
     */
    public static void setProgressCallback(ProgressCallback callback) {
        progressCallback = callback;
    }
    
    /**
     * Fetch all market data for given tickers and cache to JSON
     */
    public static void fetchAndCacheData(List<String> tickers) {
        fetchAndCacheData(tickers, null);
    }
    
    /**
     * Fetch market data for given tickers with optional date range (last 6 months)
     * @param tickers List of ticker symbols
     * @param monthsBack Number of months to fetch (null for all available data)
     */
    public static void fetchAndCacheData(List<String> tickers, Integer monthsBack) {
        System.out.println("[MarketDataCache] Starting data fetch for " + tickers.size() + " tickers" + 
            (monthsBack != null ? " (last " + monthsBack + " months)" : ""));
        
        MarketCache newCache = new MarketCache();
        int processed = 0;
        
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            try {
                TickerData tickerData = fetchTickerData(ticker, monthsBack);
                if (tickerData != null) {
                    newCache.tickers.put(ticker, tickerData);
                    processed++;
                    
                    if (processed % 10 == 0) {
                        System.out.println("[MarketDataCache] Processed " + processed + "/" + tickers.size() + " tickers");
                    }
                }
                
                // Report progress
                if (progressCallback != null) {
                    progressCallback.onProgress(i + 1, tickers.size(), ticker);
                }
                
                // Rate limiting: 3 API calls per ticker (stock data + income + earnings)
                // Alpha Vantage free tier = 5 calls/min = 12 seconds per call
                // Sleep 36 seconds between tickers to respect limits
                Thread.sleep(36000);
                
            } catch (Exception e) {
                System.err.println("[MarketDataCache] Error fetching " + ticker + ": " + e.getMessage());
            }
        }
        
        newCache.totalTickers = processed;
        newCache.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        
        // Save to file
        saveCache(newCache);
        cache = newCache;
        
        System.out.println("[MarketDataCache] Completed. Cached " + processed + " tickers to " + CACHE_FILE);
    }
    
    /**
     * Fetch all data for a single ticker
     */
    private static TickerData fetchTickerData(String ticker, Integer monthsBack) {
        try {
            TickerData tickerData = new TickerData(ticker);
            
            // Fetch daily price data
            String dailyDataJson = DataFetcher.fetchStockDataForTicker(ticker);
            if (dailyDataJson == null) {
                System.err.println("[MarketDataCache] No daily data for " + ticker);
                return null;
            }
            
            JsonNode root = mapper.readTree(dailyDataJson);
            JsonNode timeSeries = root.path("Time Series (Daily)");
            
            if (!timeSeries.isObject()) {
                System.err.println("[MarketDataCache] Invalid time series for " + ticker);
                return null;
            }
            
            // Parse daily data
            Map<LocalDate, JsonNode> dataByDate = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = timeSeries.fields();
            LocalDate cutoffDate = monthsBack != null ? LocalDate.now().minusMonths(monthsBack) : null;
            
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                LocalDate date = LocalDate.parse(entry.getKey());
                
                // Filter by date if monthsBack is specified
                if (monthsBack != null && date.isBefore(cutoffDate)) {
                    continue;
                }
                
                dataByDate.put(date, entry.getValue());
            }
            
            // Calculate indicators
            Map<LocalDate, Double> sma200 = calculateSMA(dataByDate, 200);
            Map<LocalDate, Double> sma50 = calculateSMA(dataByDate, 50);
            Map<LocalDate, Double> rsi = calculateRSI(dataByDate, 14);
            Map<LocalDate, Double> rvol = calculateRVOL(dataByDate, 20);
            
            // Build daily data with indicators
            for (Map.Entry<LocalDate, JsonNode> entry : dataByDate.entrySet()) {
                LocalDate date = entry.getKey();
                JsonNode dayData = entry.getValue();
                
                DailyData daily = new DailyData();
                daily.date = date.toString();
                daily.open = dayData.path("1. open").asDouble();
                daily.high = dayData.path("2. high").asDouble();
                daily.low = dayData.path("3. low").asDouble();
                daily.close = dayData.path("4. close").asDouble();
                daily.volume = dayData.path("5. volume").asDouble();
                daily.sma200 = sma200.get(date);
                daily.sma50 = sma50.get(date);
                daily.rsi = rsi.get(date);
                daily.rvol = rvol.get(date);
                
                tickerData.dailyData.put(date.toString(), daily);
            }
            
            // Fetch fundamental data
            String incomeStatement = DataFetcher.fetchIncomeStatement(ticker);
            String earnings = DataFetcher.fetchEarnings(ticker);
            
            tickerData.revenueGrowth = DataFetcher.calculateRevenueGrowth(incomeStatement);
            tickerData.epsGrowth = DataFetcher.calculateEPSGrowth(earnings);
            
            System.out.println("[MarketDataCache] " + ticker + " - " + tickerData.dailyData.size() + 
                " days" + (monthsBack != null ? " (last " + monthsBack + " months)" : "") + 
                ", RevGrowth: " + tickerData.revenueGrowth + "%, EPSGrowth: " + tickerData.epsGrowth + "%");
            
            return tickerData;
            
        } catch (Exception e) {
            System.err.println("[MarketDataCache] Error processing " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Load cache from file
     */
    public static MarketCache loadCache() {
        try {
            File file = new File(CACHE_FILE);
            if (!file.exists()) {
                System.out.println("[MarketDataCache] No cache file found");
                return null;
            }
            
            cache = mapper.readValue(file, MarketCache.class);
            System.out.println("[MarketDataCache] Loaded cache with " + cache.tickers.size() + 
                " tickers (updated: " + cache.lastUpdated + ")");
            return cache;
            
        } catch (Exception e) {
            System.err.println("[MarketDataCache] Error loading cache: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Save cache to file
     */
    private static void saveCache(MarketCache cacheToSave) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CACHE_FILE), cacheToSave);
            System.out.println("[MarketDataCache] Saved cache to " + CACHE_FILE);
        } catch (Exception e) {
            System.err.println("[MarketDataCache] Error saving cache: " + e.getMessage());
        }
    }
    
    /**
     * Get cached data for a ticker
     */
    public static TickerData getTickerData(String ticker) {
        if (cache == null) {
            cache = loadCache();
        }
        return cache != null ? cache.tickers.get(ticker) : null;
    }
    
    /**
     * Get all cached ticker symbols
     */
    public static Set<String> getCachedTickers() {
        if (cache == null) {
            cache = loadCache();
        }
        return cache != null ? cache.tickers.keySet() : Collections.emptySet();
    }
    
    // --- Indicator calculation methods (copied from BacktestEngine) ---
    
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
    
    private static Map<LocalDate, Double> calculateRSI(Map<LocalDate, JsonNode> dataByDate, int period) {
        Map<LocalDate, Double> rsi = new LinkedHashMap<>();
        List<LocalDate> dates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(dates);
        
        if (dates.size() < period + 1) return rsi;
        
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
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
}
