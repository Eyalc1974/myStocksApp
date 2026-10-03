public class TestTickerAPI {
    public static void main(String[] args) {
        System.out.println("=== Testing Alpha Vantage LISTING_STATUS API ===\n");
        
        // Force refresh to test API call
        LongTermCandidateFinder.forceRefreshTickerCache();
        
        // Get the cached tickers
        var tickers = LongTermCandidateFinder.getApiTickerCache();
        
        System.out.println("\n=== Results ===");
        System.out.println("Total tickers fetched: " + tickers.size());
        
        if (tickers.isEmpty()) {
            System.out.println("ERROR: No tickers fetched. Check API key and connection.");
        } else {
            System.out.println("\nFirst 20 tickers:");
            for (int i = 0; i < Math.min(20, tickers.size()); i++) {
                System.out.println("  " + (i + 1) + ". " + tickers.get(i));
            }
            
            // Verify filtering is working
            boolean hasInvalid = false;
            for (String ticker : tickers) {
                if (ticker.contains("-") || ticker.contains(".")) {
                    System.out.println("WARNING: Found invalid ticker: " + ticker);
                    hasInvalid = true;
                }
            }
            
            if (!hasInvalid) {
                System.out.println("\n✓ Filtering appears correct (no special characters)");
            }
        }
        
        // Test getUniverseTickers
        System.out.println("\n=== Testing getUniverseTickers() ===");
        var universe = LongTermCandidateFinder.getUniverseTickers();
        System.out.println("Universe size: " + universe.size());
    }
}
