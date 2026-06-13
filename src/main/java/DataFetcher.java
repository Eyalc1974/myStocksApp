import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Map;

public class DataFetcher {

    private static final String API_KEY = readAlphaVantageApiKeyFromEnv();
    private static final String ENTITLEMENT = readAlphaVantageEntitlementFromEnv();
    private static String TICKER = "BIIB";
    // בתוך DataFetcher.java

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    // משתנים סטטיים חדשים
    private static final String NEWS_API_KEY = readAlphaVantageApiKeyFromEnv();
    private static final String NEWS_API_URL = "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=";

    // הוספת מתודות חדשות:
    public static String fetchSentimentData(String ticker) {
        // קריאה ל-API חיצוני לנתוני סנטימנט
        // לדוגמה: /sentiment?symbol=AAPL&apikey=...
        String url = NEWS_API_URL + ticker + "&sort=LATEST&limit=50" + entitlementQueryParam() + "&apikey=" + NEWS_API_KEY;
        // לוגיקת קריאה לרשת והחזרת JSON (אופציונלי בלבד)
        try {
            return makeApiCall(url);
        } catch (Exception e) {
            // אם ה-API הפרימיום לא זמין (למשל 302/401) נחזיר null כדי לא לשבור את הסורק
            // שומרים על שקט לוגי כדי לא לזהם את פלט ה-Finder
            return null;
        }
    }

    // Fetch annual/quarterly cash flow statements from Alpha Vantage
    public static String fetchCashFlow(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=CASH_FLOW&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("CASH_FLOW");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Fetch annual/quarterly income statements from Alpha Vantage
    public static String fetchIncomeStatement(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=INCOME_STATEMENT&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("INCOME_STATEMENT");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Fetch annual/quarterly balance sheet statements from Alpha Vantage
    public static String fetchBalanceSheet(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=BALANCE_SHEET&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("BALANCE_SHEET");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Alpha Vantage NEWS_SENTIMENT for a single ticker (used only in single-symbol analysis)
    public static String fetchNewsSentiment(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            ticker = TICKER; // fallback to last-set global ticker
        }

        String url = String.format(
                "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=%s&sort=LATEST&limit=50%s&apikey=%s",
                ticker.toUpperCase(),
                entitlementQueryParam(),
                API_KEY
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            ApiUsageTracker.track("NEWS_SENTIMENT");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            // non-200: treat as no data
            return null;
        } catch (Exception e) {
            // network/API failure: treat as no data so core analysis still runs
            return null;
        }
    }

    public static String fetchLatestNews(String ticker) {
        // קריאה ל-API חיצוני לנתוני חדשות
        String url = "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=" + ticker + "&sort=LATEST&limit=10" + entitlementQueryParam() + "&apikey=" + NEWS_API_KEY;
        // לוגיקת קריאה לרשת והחזרת JSON (אופציונלי בלבד)
        try {
            return makeApiCall(url);
        } catch (Exception e) {
            // גם כאן נחזיר null בשקט
            return null;
        }
    }

    public static void setTicker(String ticker) {
        TICKER = ticker;
    }

    // Fetch 2 years of daily OHLCV from Yahoo Finance (no API key required, high concurrency).
    // Response is converted to Alpha Vantage TIME_SERIES_DAILY_ADJUSTED format so
    // PriceJsonParser and all downstream code work without any changes.
    public static String fetchYahooFinanceData(String ticker) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + ticker
                + "?interval=1d&range=2y&includePrePost=false";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return convertYahooToAlphaVantageFormat(response.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static String convertYahooToAlphaVantageFormat(String yahooJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(yahooJson);
            JsonNode resultArr = root.path("chart").path("result");
            if (resultArr.isMissingNode() || resultArr.isEmpty()) return null;
            JsonNode chartData = resultArr.get(0);
            JsonNode timestamps = chartData.get("timestamp");
            if (timestamps == null || timestamps.isEmpty()) return null;
            JsonNode quote      = chartData.path("indicators").path("quote").get(0);
            JsonNode adjCloseArr = chartData.path("indicators").path("adjclose").get(0).path("adjclose");
            JsonNode opens   = quote.get("open");
            JsonNode highs   = quote.get("high");
            JsonNode lows    = quote.get("low");
            JsonNode closes  = quote.get("close");
            JsonNode volumes = quote.get("volume");
            ObjectNode timeSeries = mapper.createObjectNode();
            ZoneId etZone = ZoneId.of("America/New_York");
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes == null || closes.get(i) == null || closes.get(i).isNull()) continue;
                String date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(etZone).toLocalDate().toString();
                ObjectNode day = mapper.createObjectNode();
                day.put("1. open",  yahooSafeDouble(opens,  i));
                day.put("2. high",  yahooSafeDouble(highs,  i));
                day.put("3. low",   yahooSafeDouble(lows,   i));
                day.put("4. close", yahooSafeDouble(closes, i));
                double adj = (!adjCloseArr.isMissingNode() && i < adjCloseArr.size() && !adjCloseArr.get(i).isNull())
                        ? adjCloseArr.get(i).asDouble() : closes.get(i).asDouble();
                day.put("5. adjusted close", String.format("%.4f", adj));
                day.put("6. volume", volumes != null && !volumes.get(i).isNull()
                        ? String.valueOf(volumes.get(i).asLong()) : "0");
                timeSeries.set(date, day);
            }
            ObjectNode output = mapper.createObjectNode();
            output.set("Time Series (Daily)", timeSeries);
            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            return null;
        }
    }

    private static String yahooSafeDouble(JsonNode arr, int i) {
        if (arr == null || arr.get(i) == null || arr.get(i).isNull()) return "0.0000";
        return String.format("%.4f", arr.get(i).asDouble());
    }

    // Thread-safe version of fetchStockData() — takes ticker as a parameter
    // so multiple threads can call it concurrently without touching the global TICKER field.
    public static String fetchStockDataForTicker(String ticker) {
        String url = String.format(
                "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=%s%s&outputsize=full&apikey=%s",
                ticker, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("TIME_SERIES_DAILY");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // Fetch live current price + today's high/low/open via GLOBAL_QUOTE endpoint.
    // Use this during market hours for real-time price monitoring.
    public static String fetchGlobalQuote(String ticker) {
        String url = String.format(
                "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=%s%s&apikey=%s",
                ticker, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("GLOBAL_QUOTE");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fetchStockData() {
        // בניית כתובת ה-URL לבקשה (למשל, מחירי סגירה יומיים)
        String url = String.format(
                "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=%s%s&outputsize=full&apikey=%s",
                TICKER, entitlementQueryParam(), API_KEY
        );

        // יצירת בקשת HTTP
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            ApiUsageTracker.track("TIME_SERIES_DAILY");
            // שליחת הבקשה וקבלת התגובה (Response)
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            // בדיקה שסטטוס הקוד תקין (200)
            if (response.statusCode() == 200) {
                // התגובה היא מחרוזת JSON שצריך לנתח
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Fetch earnings history (quarterly/annual EPS etc.) from Alpha Vantage
    public static String fetchEarnings(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=EARNINGS&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("EARNINGS");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Fetch earnings estimates (forward EPS expectations) from Alpha Vantage
    public static String fetchEarningsEstimates(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=EARNINGS_ESTIMATES&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("EARNINGS_ESTIMATES");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Alpha Vantage TOP_GAINERS_LOSERS endpoint (market-wide movers)
    public static String fetchTopGainersLosers() {
        String url = String.format(
                "https://www.alphavantage.co/query?function=TOP_GAINERS_LOSERS%s&apikey=%s",
                entitlementQueryParam(), API_KEY
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            ApiUsageTracker.track("TOP_GAINERS_LOSERS");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // Generic helper for simple GET requests returning JSON/string body
    private static String makeApiCall(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        throw new Exception("HTTP error " + response.statusCode() + " for URL: " + url);
    }

    public static String fetchDailyCandlesFromFinnhub() {
        String token = System.getenv("d4q3h41r01qha6q0laogd4q3h41r01qha6q0lap0");
        if (token == null || token.isBlank()) {
            return null;
        }

        String url = String.format(
                "https://finnhub.io/api/v1/stock/candle?symbol=%s&resolution=D&count=300&token=%s",
                TICKER, token
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        String jsonData = fetchStockData();
        System.out.println(jsonData.substring(0, Math.min(jsonData.length(), 200)) + "...");
    }

    // Fetch company overview (name, sector, etc.) from Alpha Vantage
    public static String fetchCompanyOverview(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=OVERVIEW&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("OVERVIEW");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String entitlementQueryParam() {
        if (ENTITLEMENT == null || ENTITLEMENT.isBlank()) return "";
        return "&entitlement=" + ENTITLEMENT;
    }

    private static String readAlphaVantageApiKeyFromEnv() {
        String key = System.getenv("ALPHAVANTAGE_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("ALPHA_VANTAGE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = "demo";
        }
        return key;
    }

    private static String readAlphaVantageEntitlementFromEnv() {
        String ent = System.getenv("ALPHAVANTAGE_ENTITLEMENT");
        if (ent == null || ent.isBlank()) {
            ent = System.getenv("ALPHA_VANTAGE_ENTITLEMENT");
        }
        // Validate entitlement - must be "delayed" or "realtime", otherwise default to "delayed"
        if (ent == null || ent.isBlank() || (!"delayed".equalsIgnoreCase(ent) && !"realtime".equalsIgnoreCase(ent))) {
            ent = "delayed";
        }
        return ent.toLowerCase();
    }

    // Fetch earnings revisions data from Alpha Vantage
    public static String fetchEarningsRevisions(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=EARNINGS&symbol=%s%s&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("EARNINGS");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Calculate revenue growth from income statement data
    public static double calculateRevenueGrowth(String incomeStatementJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(incomeStatementJson);
            JsonNode quarterlyReports = root.path("quarterlyReports");
            
            if (quarterlyReports.isArray() && quarterlyReports.size() >= 2) {
                double currentRevenue = quarterlyReports.get(0).path("totalRevenue").asDouble();
                double previousRevenue = quarterlyReports.get(1).path("totalRevenue").asDouble();
                
                if (previousRevenue > 0) {
                    return ((currentRevenue - previousRevenue) / previousRevenue) * 100.0;
                }
            }
        } catch (Exception e) {
            return 0.0;
        }
        return 0.0;
    }

    // Calculate EPS growth from earnings data
    public static double calculateEPSGrowth(String earningsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(earningsJson);
            JsonNode quarterlyEarnings = root.path("quarterlyEarnings");
            
            if (quarterlyEarnings.isArray() && quarterlyEarnings.size() >= 2) {
                double currentEPS = quarterlyEarnings.get(0).path("reportedEPS").asDouble();
                double previousEPS = quarterlyEarnings.get(1).path("reportedEPS").asDouble();
                
                if (previousEPS > 0) {
                    return ((currentEPS - previousEPS) / Math.abs(previousEPS)) * 100.0;
                }
            }
        } catch (Exception e) {
            return 0.0;
        }
        return 0.0;
    }

    // Check for positive EPS revisions
    public static boolean hasPositiveEPSRevision(String earningsEstimatesJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(earningsEstimatesJson);
            JsonNode estimates = root.path("estimates");
            
            if (estimates.isArray() && !estimates.isEmpty()) {
                double estimatedEPS = estimates.get(0).path("estimatedEPS").asDouble();
                double previousEstimate = estimates.size() > 1 ? estimates.get(1).path("estimatedEPS").asDouble() : estimatedEPS;
                
                return estimatedEPS > previousEstimate;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // Check for earnings surprise
    public static double getEarningsSurprise(String earningsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(earningsJson);
            JsonNode quarterlyEarnings = root.path("quarterlyEarnings");
            
            if (quarterlyEarnings.isArray() && !quarterlyEarnings.isEmpty()) {
                double reportedEPS = quarterlyEarnings.get(0).path("reportedEPS").asDouble();
                double estimatedEPS = quarterlyEarnings.get(0).path("estimatedEPS").asDouble();
                
                if (estimatedEPS > 0) {
                    return ((reportedEPS - estimatedEPS) / Math.abs(estimatedEPS)) * 100.0;
                }
            }
        } catch (Exception e) {
            return 0.0;
        }
        return 0.0;
    }

    // Calculate ROE from income statement and balance sheet
    public static double calculateROE(String incomeStatementJson, String balanceSheetJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode incomeRoot = mapper.readTree(incomeStatementJson);
            JsonNode balanceRoot = mapper.readTree(balanceSheetJson);
            
            JsonNode quarterlyReports = incomeRoot.path("quarterlyReports");
            JsonNode quarterlyBalance = balanceRoot.path("quarterlyReports");
            
            if (quarterlyReports.isArray() && !quarterlyReports.isEmpty() && 
                quarterlyBalance.isArray() && !quarterlyBalance.isEmpty()) {
                
                double netIncome = quarterlyReports.get(0).path("netIncome").asDouble();
                double totalShareholderEquity = quarterlyBalance.get(0).path("totalShareholderEquity").asDouble();
                
                if (totalShareholderEquity > 0) {
                    return (netIncome / totalShareholderEquity) * 100.0;
                }
            }
        } catch (Exception e) {
            return 0.0;
        }
        return 0.0;
    }

    // Calculate Debt/Equity ratio from balance sheet
    public static double calculateDebtToEquity(String balanceSheetJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(balanceSheetJson);
            JsonNode quarterlyReports = root.path("quarterlyReports");
            
            if (quarterlyReports.isArray() && !quarterlyReports.isEmpty()) {
                double totalDebt = quarterlyReports.get(0).path("totalDebt").asDouble();
                double totalShareholderEquity = quarterlyReports.get(0).path("totalShareholderEquity").asDouble();
                
                if (totalShareholderEquity > 0) {
                    return totalDebt / totalShareholderEquity;
                }
            }
        } catch (Exception e) {
            return 999.0; // High debt ratio if calculation fails
        }
        return 999.0;
    }

    // Check for analyst upgrades from news sentiment
    public static boolean hasAnalystUpgrade(String newsSentimentJson) {
        if (newsSentimentJson == null) return false;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(newsSentimentJson);
            JsonNode feed = root.path("feed");

            if (feed.isArray()) {
                for (JsonNode article : feed) {
                    String title = article.path("title").asText().toLowerCase();
                    String summary = article.path("summary").asText().toLowerCase();
                    String text = title + " " + summary;

                    if (text.contains("upgrade") || text.contains("upgraded") || 
                        text.contains("rating upgrade") || text.contains("buy rating")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // Check for guidance raise from news sentiment
    public static boolean hasGuidanceRaise(String newsSentimentJson) {
        if (newsSentimentJson == null) return false;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(newsSentimentJson);
            JsonNode feed = root.path("feed");

            if (feed.isArray()) {
                for (JsonNode article : feed) {
                    String title = article.path("title").asText().toLowerCase();
                    String summary = article.path("summary").asText().toLowerCase();
                    String text = title + " " + summary;

                    if (text.contains("guidance raise") || text.contains("raised guidance") || 
                        text.contains("outlook raise") || text.contains("raised outlook")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // Fetch historical data for specific date range (for backtesting)
    public static String fetchHistoricalDataForDateRange(String symbol, String startDate, String endDate) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        // Use full outputsize and filter by date range
        String url = String.format(
                "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=%s%s&outputsize=full&apikey=%s",
                symbol, entitlementQueryParam(), API_KEY
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            ApiUsageTracker.track("TIME_SERIES_DAILY");
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String fullData = response.body();
                return filterDataByDateRange(fullData, startDate, endDate);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Filter Alpha Vantage time series data by date range
    private static String filterDataByDateRange(String jsonData, String startDate, String endDate) {
        if (jsonData == null) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonData);
            JsonNode timeSeries = root.path("Time Series (Daily)");
            
            if (!timeSeries.isObject()) return jsonData;
            
            ObjectNode filtered = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = timeSeries.fields();
            
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String date = entry.getKey();
                
                // Check if date is within range
                if ((startDate == null || date.compareTo(startDate) >= 0) &&
                    (endDate == null || date.compareTo(endDate) <= 0)) {
                    filtered.set(date, entry.getValue());
                }
            }
            
            ObjectNode result = mapper.createObjectNode();
            result.set("Meta Data", root.path("Meta Data"));
            result.set("Time Series (Daily)", filtered);
            
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return jsonData; // Return original if filtering fails
        }
    }

    // Check for earnings beat from earnings data
    public static boolean hasEarningsBeat(String earningsJson) {
        double surprise = getEarningsSurprise(earningsJson);
        return surprise > 0.0;
    }
}