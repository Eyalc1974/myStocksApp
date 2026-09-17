import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Pre-Market Indicator Cache
 * 
 * Pre-calculates indicators that don't require today's live price.
 * Runs before market open (e.g., 8:00 AM ET) to prepare data for fast scanning.
 * 
 * At market open, only fetch today's price/volume and incrementally update
 * indicators that need live data.
 */
public class PreMarketIndicatorCache {
    
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String CACHE_FILE = "pre-market-indicators.json";
    private static final int API_SLEEP_MS = 100; // Premium tier: 600 calls/min
    
    /**
     * Cached indicator data for a single ticker (pre-calculated using yesterday's data)
     */
    public static class CachedIndicators {
        public String symbol;
        public String lastMarketDate; // The last trading day (e.g., "2026-09-08")
        
        // Pre-calculated indicators (using data up to lastMarketDate)
        public Double sma20;
        public Double sma50;
        public Double sma200;
        public Double rsi;
        public Double rvol;
        public Double cci;
        public Double atr;
        public Double atrPct;
        public Double week52High;
        public Double resistance30d;
        public Double high20d;
        public Double prevHigh;
        
        // Historical data needed for incremental updates
        public List<Double> last20Prices; // Last 20 closes (for SMA20, RSI updates)
        public List<Double> last50Prices; // Last 50 closes (for SMA50 updates)
        public List<Double> last200Prices; // Last 200 closes (for SMA200 updates)
        public List<Double> last20Volumes; // Last 20 volumes (for RVOL updates)
        public List<Double> last20Highs; // Last 20 highs (for CCI, ATR updates)
        public List<Double> last20Lows; // Last 20 lows (for CCI, ATR updates)
        
        public String lastUpdated;
        
        public CachedIndicators() {
            this.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        }
    }
    
    /**
     * Complete cache structure
     */
    public static class PreMarketCache {
        public Map<String, CachedIndicators> tickers = new LinkedHashMap<>();
        public String lastMarketDate;
        public String lastUpdated;
        public int totalTickers;
        
        public PreMarketCache() {
            this.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        }
    }
    
    private static PreMarketCache cache = null;
    
    /**
     * Progress callback interface
     */
    public interface ProgressCallback {
        void onProgress(int current, int total, String currentTicker);
    }
    
    /**
     * Fetch and pre-calculate indicators for all tickers
     * Run this before market open (e.g., 8:00 AM ET)
     */
    public static void buildPreMarketCache(List<String> tickers) {
        buildPreMarketCache(tickers, null);
    }
    
    public static void buildPreMarketCache(List<String> tickers, ProgressCallback callback) {
        System.out.println("[PreMarketCache] Building pre-market cache for " + tickers.size() + " tickers");
        
        PreMarketCache newCache = new PreMarketCache();
        int processed = 0;
        
        // Determine last market date (yesterday if today is weekend/holiday)
        LocalDate lastDate = getLastMarketDate();
        newCache.lastMarketDate = lastDate.format(DateTimeFormatter.ISO_DATE);
        
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            try {
                CachedIndicators indicators = calculatePreMarketIndicators(ticker, lastDate);
                if (indicators != null) {
                    newCache.tickers.put(ticker, indicators);
                    processed++;
                    
                    if (processed % 50 == 0) {
                        System.out.println("[PreMarketCache] Processed " + processed + "/" + tickers.size() + " tickers");
                    }
                }
                
                if (callback != null) {
                    callback.onProgress(i + 1, tickers.size(), ticker);
                }
                
                Thread.sleep(API_SLEEP_MS);
                
            } catch (Exception e) {
                System.err.println("[PreMarketCache] Error processing " + ticker + ": " + e.getMessage());
            }
        }
        
        newCache.totalTickers = processed;
        newCache.lastUpdated = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        
        saveCache(newCache);
        cache = newCache;
        
        System.out.println("[PreMarketCache] Completed. Cached " + processed + " tickers to " + CACHE_FILE);
    }
    
    /**
     * Calculate pre-market indicators for a single ticker
     */
    private static CachedIndicators calculatePreMarketIndicators(String ticker, LocalDate lastDate) {
        try {
            CachedIndicators indicators = new CachedIndicators();
            indicators.symbol = ticker;
            indicators.lastMarketDate = lastDate.format(DateTimeFormatter.ISO_DATE);
            
            // Fetch full historical data
            String json = DataFetcher.fetchStockDataForTicker(ticker);
            if (json == null) {
                return null;
            }
            
            JsonNode root = mapper.readTree(json);
            JsonNode timeSeries = root.path("Time Series (Daily)");
            
            if (!timeSeries.isObject()) {
                return null;
            }
            
            // Extract price/volume data
            List<Double> prices = new ArrayList<>();
            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();
            List<Double> volumes = new ArrayList<>();
            
            Iterator<Map.Entry<String, JsonNode>> it = timeSeries.fields();
            while (it.hasNext() && prices.size() < 200) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode dayData = entry.getValue();
                
                double close = dayData.path("4. close").asDouble();
                double high = dayData.path("2. high").asDouble();
                double low = dayData.path("3. low").asDouble();
                double volume = dayData.path("5. volume").asDouble();
                
                if (close > 0) {
                    prices.add(close);
                    highs.add(high);
                    lows.add(low);
                    volumes.add(volume);
                }
            }
            
            if (prices.size() < 20) {
                return null; // Not enough data
            }
            
            // Store historical data for incremental updates
            indicators.last20Prices = new ArrayList<>(prices.subList(Math.max(0, prices.size() - 20), prices.size()));
            indicators.last50Prices = prices.size() >= 50 ? new ArrayList<>(prices.subList(prices.size() - 50, prices.size())) : new ArrayList<>(prices);
            indicators.last200Prices = new ArrayList<>(prices);
            indicators.last20Volumes = new ArrayList<>(volumes.subList(Math.max(0, volumes.size() - 20), volumes.size()));
            indicators.last20Highs = new ArrayList<>(highs.subList(Math.max(0, highs.size() - 20), highs.size()));
            indicators.last20Lows = new ArrayList<>(lows.subList(Math.max(0, lows.size() - 20), lows.size()));
            
            // Calculate pre-market indicators
            indicators.sma20 = calculateSMA(prices, 20);
            if (prices.size() >= 50) indicators.sma50 = calculateSMA(prices, 50);
            if (prices.size() >= 200) indicators.sma200 = calculateSMA(prices, 200);
            
            indicators.rsi = calculateRSI(prices, 14);
            indicators.rvol = calculateRVOL(volumes, 20);
            
            if (highs.size() >= 20 && lows.size() >= 20) {
                indicators.cci = calculateCCI(highs, lows, prices, 20);
                indicators.atr = calculateATR(highs, lows, prices, 14);
                if (indicators.atr != null && prices.get(prices.size() - 1) > 0) {
                    indicators.atrPct = (indicators.atr / prices.get(prices.size() - 1)) * 100;
                }
            }
            
            // 52-week high
            int lookback52 = Math.min(highs.size(), 252);
            indicators.week52High = highs.subList(highs.size() - lookback52, highs.size())
                .stream().mapToDouble(v -> v).max().orElse(0);
            
            // 30-day resistance (highest close over prior 30 sessions)
            if (prices.size() >= 30) {
                indicators.resistance30d = prices.subList(prices.size() - 30, prices.size())
                    .stream().mapToDouble(v -> v).max().orElse(0);
            }
            
            // 20-day high
            indicators.high20d = highs.subList(highs.size() - 20, highs.size())
                .stream().mapToDouble(v -> v).max().orElse(0);
            
            // Yesterday's high
            if (highs.size() >= 2) {
                indicators.prevHigh = highs.get(highs.size() - 2);
            }
            
            return indicators;
            
        } catch (Exception e) {
            System.err.println("[PreMarketCache] Error calculating indicators for " + ticker + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get cached indicators for a ticker
     */
    public static CachedIndicators getCachedIndicators(String ticker) {
        if (cache == null) {
            cache = loadCache();
        }
        return cache != null ? cache.tickers.get(ticker) : null;
    }
    
    /**
     * Check if cache is stale (needs refresh)
     */
    public static boolean isCacheStale() {
        if (cache == null) {
            cache = loadCache();
        }
        if (cache == null) return true;
        
        LocalDate lastUpdate = LocalDate.parse(cache.lastUpdated);
        LocalDate today = LocalDate.now();
        
        // Cache is stale if not updated today
        return !lastUpdate.equals(today);
    }
    
    /**
     * Load cache from file
     */
    private static PreMarketCache loadCache() {
        try {
            File file = new File(CACHE_FILE);
            if (!file.exists()) {
                return null;
            }
            
            PreMarketCache loaded = mapper.readValue(file, PreMarketCache.class);
            System.out.println("[PreMarketCache] Loaded cache with " + loaded.tickers.size() + 
                " tickers (updated: " + loaded.lastUpdated + ")");
            return loaded;
            
        } catch (Exception e) {
            System.err.println("[PreMarketCache] Error loading cache: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Save cache to file
     */
    private static void saveCache(PreMarketCache cacheToSave) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CACHE_FILE), cacheToSave);
            System.out.println("[PreMarketCache] Saved cache to " + CACHE_FILE);
        } catch (Exception e) {
            System.err.println("[PreMarketCache] Error saving cache: " + e.getMessage());
        }
    }
    
    /**
     * Incrementally update cached indicators with today's live price/volume
     * Call this during market hours after fetching today's data
     * 
     * @param ticker Stock symbol
     * @param todayPrice Today's current price
     * @param todayVolume Today's current volume
     * @param todayHigh Today's current high
     * @param todayLow Today's current low
     * @return Updated IndicatorData with cached + live data
     */
    public static AIToolAgent.IndicatorData updateWithLiveData(String ticker, double todayPrice, 
            double todayVolume, double todayHigh, double todayLow) {
        
        CachedIndicators cached = getCachedIndicators(ticker);
        if (cached == null) {
            // Fallback: return null to force full recalculation
            return null;
        }
        
        AIToolAgent.IndicatorData data = new AIToolAgent.IndicatorData();
        data.ticker = ticker;
        
        // Use cached pre-calculated indicators (handle null values)
        data.sma20 = cached.sma20 != null ? cached.sma20 : 0;
        data.sma50 = cached.sma50 != null ? cached.sma50 : 0;
        data.sma200 = cached.sma200 != null ? cached.sma200 : 0;
        data.rsi = cached.rsi != null ? cached.rsi : 50.0;
        data.rvol = cached.rvol != null ? cached.rvol : 1.0;
        data.cci = cached.cci != null ? cached.cci : 0;
        data.atr = cached.atr != null ? cached.atr : 0;
        data.atrPct = cached.atrPct != null ? cached.atrPct : 0;
        data.week52High = cached.week52High != null ? cached.week52High : 0;
        data.resistance30d = cached.resistance30d != null ? cached.resistance30d : 0;
        data.high20d = cached.high20d != null ? cached.high20d : 0;
        data.prevHigh = cached.prevHigh != null ? cached.prevHigh : 0;
        
        // Set live data
        data.currentPrice = todayPrice;
        
        // Calculate today's change using last cached price
        if (cached.last20Prices != null && !cached.last20Prices.isEmpty()) {
            double lastClose = cached.last20Prices.get(cached.last20Prices.size() - 1);
            data.prevClose = lastClose;
            data.todayChangePct = lastClose > 0 ? ((todayPrice - lastClose) / lastClose) * 100 : 0;
            
            // Calculate 20-day momentum
            if (cached.last20Prices.size() >= 20) {
                double price20dAgo = cached.last20Prices.get(0);
                data.momentum20d = price20dAgo > 0 ? ((todayPrice - price20dAgo) / price20dAgo) * 100 : data.todayChangePct;
            }
        }
        
        // Update price above SMA flags (check cached values before comparison)
        data.priceAboveSMA20 = cached.sma20 != null && todayPrice > cached.sma20;
        data.priceAboveSMA50 = cached.sma50 != null && todayPrice > cached.sma50;
        data.priceAboveSMA200 = cached.sma200 != null && todayPrice > cached.sma200;
        data.maCrossoverUp = cached.sma20 != null && cached.sma50 != null && cached.sma20 > cached.sma50;
        
        // Update RVOL with today's volume
        if (cached.last20Volumes != null && cached.last20Volumes.size() >= 20) {
            double avgVol = cached.last20Volumes.subList(0, 19).stream().mapToDouble(v -> v).average().orElse(0);
            data.rvol = avgVol > 0 ? todayVolume / avgVol : 0;
        }
        
        // Calculate typical price and VWAP%
        data.typicalPrice = (todayHigh + todayLow + todayPrice) / 3.0;
        data.vwapPct = data.typicalPrice > 0 ? ((todayPrice - data.typicalPrice) / data.typicalPrice) * 100 : 0;
        
        // Calculate percentage from 52-week high
        data.pctFromWeek52High = data.week52High > 0 ? ((todayPrice - data.week52High) / data.week52High) * 100 : 0;
        
        // Set flags (check cached values)
        data.hasSMA50 = cached.sma50 != null;
        data.hasSMA200 = cached.sma200 != null;
        data.valid = true;
        
        return data;
    }
    
    /**
     * Get last market date (yesterday if today is weekend/holiday)
     */
    private static LocalDate getLastMarketDate() {
        LocalDate today = LocalDate.now();
        LocalDate date = today.minusDays(1);
        
        // Skip weekends
        while (date.getDayOfWeek().getValue() >= 6) {
            date = date.minusDays(1);
        }
        
        return date;
    }
    
    // --- Simple indicator calculation methods (can be replaced with existing classes) ---
    
    private static Double calculateSMA(List<Double> data, int period) {
        if (data.size() < period) return null;
        
        double sum = 0;
        for (int i = data.size() - period; i < data.size(); i++) {
            sum += data.get(i);
        }
        return sum / period;
    }
    
    private static Double calculateRSI(List<Double> prices, int period) {
        if (prices.size() < period + 1) return 50.0;
        
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
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
        
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
    
    private static Double calculateRVOL(List<Double> volumes, int period) {
        if (volumes.size() < period + 1) return 1.0;
        
        double currentVol = volumes.get(volumes.size() - 1);
        double avgVol = 0;
        
        for (int i = volumes.size() - period - 1; i < volumes.size() - 1; i++) {
            avgVol += volumes.get(i);
        }
        avgVol /= period;
        
        return avgVol > 0 ? currentVol / avgVol : 1.0;
    }
    
    private static Double calculateCCI(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < period || lows.size() < period || closes.size() < period) return null;
        
        double sumTP = 0;
        for (int i = 0; i < period; i++) {
            int idx = highs.size() - period + i;
            double tp = (highs.get(idx) + lows.get(idx) + closes.get(idx)) / 3.0;
            sumTP += tp;
        }
        double smaTP = sumTP / period;
        
        double sumDev = 0;
        for (int i = 0; i < period; i++) {
            int idx = highs.size() - period + i;
            double tp = (highs.get(idx) + lows.get(idx) + closes.get(idx)) / 3.0;
            sumDev += Math.abs(tp - smaTP);
        }
        double meanDev = sumDev / period;
        
        if (meanDev == 0) return 0.0;
        
        int lastIdx = highs.size() - 1;
        double lastTP = (highs.get(lastIdx) + lows.get(lastIdx) + closes.get(lastIdx)) / 3.0;
        
        return (lastTP - smaTP) / (0.015 * meanDev);
    }
    
    private static Double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < period + 1) return null;
        
        List<Double> trValues = new ArrayList<>();
        for (int i = highs.size() - period; i < highs.size(); i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = closes.get(i - 1);
            
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trValues.add(tr);
        }
        
        return trValues.stream().mapToDouble(d -> d).average().orElse(0);
    }
}
