import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pre-Market Cache Scheduler
 * 
 * Schedules automatic refresh of pre-market indicator cache before market open.
 * Default schedule: 8:00 AM ET (1.5 hours before market open at 9:30 AM ET)
 * 
 * Usage:
 * - Start scheduler: PreMarketCacheScheduler.start()
 * - Manual refresh: PreMarketCacheScheduler.refreshCache()
 * - Stop scheduler: PreMarketCacheScheduler.stop()
 */
public class PreMarketCacheScheduler {
    
    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;
    
    // Default refresh time: 8:00 AM ET
    private static final int DEFAULT_REFRESH_HOUR = 8; // 8 AM ET
    private static final int DEFAULT_REFRESH_MINUTE = 0;
    
    // Ticker universe file path
    private static final String TICKER_UNIVERSE_FILE = "ticker-universe.txt";
    
    /**
     * Start the scheduler to refresh cache daily at 8:00 AM ET
     */
    public static void start() {
        start(DEFAULT_REFRESH_HOUR, DEFAULT_REFRESH_MINUTE);
    }
    
    /**
     * Start the scheduler with custom refresh time
     * @param hour Hour in ET (0-23)
     * @param minute Minute (0-59)
     */
    public static void start(int hour, int minute) {
        if (isRunning) {
            System.out.println("[PreMarketScheduler] Already running");
            return;
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor();
        isRunning = true;
        
        // Calculate initial delay to next scheduled time
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        ZonedDateTime nextRun = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        
        if (now.isAfter(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }
        
        long initialDelay = ChronoUnit.MILLIS.between(now, nextRun);
        long period = TimeUnit.DAYS.toMillis(1); // Run daily
        
        System.out.println("[PreMarketScheduler] Starting scheduler");
        System.out.println("[PreMarketScheduler] Next refresh at: " + nextRun);
        System.out.println("[PreMarketScheduler] Initial delay: " + (initialDelay / 1000 / 60) + " minutes");
        
        scheduler.scheduleAtFixedRate(
            PreMarketCacheScheduler::refreshCache,
            initialDelay,
            period,
            TimeUnit.MILLISECONDS
        );
    }
    
    /**
     * Stop the scheduler
     */
    public static void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            isRunning = false;
            System.out.println("[PreMarketScheduler] Stopped");
        }
    }
    
    /**
     * Manually refresh the pre-market cache
     * This can be called independently of the scheduler
     */
    public static void refreshCache() {
        System.out.println("========================================");
        System.out.println("[PreMarketScheduler] Starting cache refresh at " + 
            ZonedDateTime.now(ZoneId.of("America/New_York")));
        System.out.println("========================================");
        
        try {
            // Check if cache is already fresh
            if (!PreMarketIndicatorCache.isCacheStale()) {
                System.out.println("[PreMarketScheduler] Cache is already fresh, skipping refresh");
                return;
            }
            
            // Load ticker universe
            List<String> tickers = loadTickerUniverse();
            if (tickers == null || tickers.isEmpty()) {
                System.err.println("[PreMarketScheduler] No tickers found, aborting refresh");
                return;
            }
            
            System.out.println("[PreMarketScheduler] Loaded " + tickers.size() + " tickers");
            
            // Build cache with progress callback
            PreMarketIndicatorCache.buildPreMarketCache(tickers, (current, total, ticker) -> {
                if (current % 100 == 0 || current == total) {
                    double pct = (current * 100.0) / total;
                    System.out.printf("[PreMarketScheduler] Progress: %d/%d (%.1f%%) - %s%n", 
                        current, total, pct, ticker);
                }
            });
            
            System.out.println("========================================");
            System.out.println("[PreMarketScheduler] Cache refresh completed at " + 
                ZonedDateTime.now(ZoneId.of("America/New_York")));
            System.out.println("========================================");
            
        } catch (Exception e) {
            System.err.println("[PreMarketScheduler] Cache refresh failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Load ticker universe from file
     * Expected format: one ticker per line (e.g., AAPL, MSFT, GOOGL)
     */
    private static List<String> loadTickerUniverse() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(TICKER_UNIVERSE_FILE);
            if (!java.nio.file.Files.exists(path)) {
                System.err.println("[PreMarketScheduler] Ticker universe file not found: " + TICKER_UNIVERSE_FILE);
                System.out.println("[PreMarketScheduler] Creating sample ticker universe file...");
                createSampleTickerUniverse();
                return loadTickerUniverse(); // Retry after creating
            }
            
            List<String> tickers = new ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(path)) {
                String ticker = line.trim().toUpperCase();
                if (!ticker.isEmpty() && !ticker.startsWith("#")) {
                    tickers.add(ticker);
                }
            }
            
            return tickers;
            
        } catch (Exception e) {
            System.err.println("[PreMarketScheduler] Error loading ticker universe: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Create a sample ticker universe file for testing
     */
    private static void createSampleTickerUniverse() {
        try {
            // Sample tech stocks for testing
            List<String> sampleTickers = Arrays.asList(
                "AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA", "AMD",
                "INTC", "CSCO", "ADBE", "CRM", "ORCL", "IBM", "QCOM"
            );
            
            java.nio.file.Files.write(
                java.nio.file.Paths.get(TICKER_UNIVERSE_FILE),
                String.join("\n", sampleTickers).getBytes()
            );
            
            System.out.println("[PreMarketScheduler] Created sample ticker universe with " + sampleTickers.size() + " tickers");
            
        } catch (Exception e) {
            System.err.println("[PreMarketScheduler] Error creating sample ticker universe: " + e.getMessage());
        }
    }
    
    /**
     * Main method for manual testing
     */
    public static void main(String[] args) {
        System.out.println("[PreMarketScheduler] Manual cache refresh");
        refreshCache();
        
        // Uncomment below to start scheduler instead
        // System.out.println("[PreMarketScheduler] Starting scheduler (runs daily at 8:00 AM ET)");
        // start();
        // 
        // // Keep running
        // try {
        //     Thread.sleep(Long.MAX_VALUE);
        // } catch (InterruptedException e) {
        //     stop();
        // }
    }
}
