import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DataFetcher {

    // יש להחליף ב-API Key אמיתי!
    private static final String API_KEY = "DH6B36IUFEU8MFGY";
    private static String TICKER = "BIIB";
    // בתוך DataFetcher.java

    // משתנים סטטיים חדשים
    private static final String NEWS_API_KEY = "DH6B36IUFEU8MFGY"; // TODO: replace with real premium key or env var
    private static final String NEWS_API_URL = "https://api.premiumnews.com/news/";

    // הוספת מתודות חדשות:
    public static String fetchSentimentData(String ticker) {
        // קריאה ל-API חיצוני לנתוני סנטימנט
        // לדוגמה: /sentiment?symbol=AAPL&apikey=...
        String url = NEWS_API_URL + "sentiment?symbol=" + ticker + "&apikey=" + NEWS_API_KEY;
        // לוגיקת קריאה לרשת והחזרת JSON (אופציונלי בלבד)
        try {
            return makeApiCall(url);
        } catch (Exception e) {
            // אם ה-API הפרימיום לא זמין (למשל 302/401) נחזיר null כדי לא לשבור את הסורק
            // שומרים על שקט לוגי כדי לא לזהם את פלט ה-Finder
            return null;
        }
    }

    // Alpha Vantage NEWS_SENTIMENT for a single ticker (used only in single-symbol analysis)
    public static String fetchNewsSentiment(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            ticker = TICKER; // fallback to last-set global ticker
        }

        String url = String.format(
                "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=%s&sort=LATEST&limit=50&apikey=%s",
                ticker.toUpperCase(),
                API_KEY
        );

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
        String url = NEWS_API_URL + "latest_news?symbol=" + ticker + "&limit=10&apikey=" + NEWS_API_KEY;
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

    public static String fetchStockData() {
        // בניית כתובת ה-URL לבקשה (למשל, מחירי סגירה יומיים)
        String url = String.format(
                "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=%s&apikey=%s",
                TICKER, API_KEY
        );

        // יצירת לקוח HTTP
        HttpClient client = HttpClient.newHttpClient();

        // יצירת בקשת HTTP
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        try {
            // שליחת הבקשה וקבלת התגובה (Response)
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // בדיקה שסטטוס הקוד תקין (200)
            if (response.statusCode() == 200) {
                // התגובה היא מחרוזת JSON שצריך לנתח
                return response.body();
            } else {
                return "Error: HTTP Status Code " + response.statusCode();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Error during request: " + e.getMessage();
        }
    }

    // Fetch earnings history (quarterly/annual EPS etc.) from Alpha Vantage
    public static String fetchEarnings(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=EARNINGS&symbol=%s&apikey=%s",
                symbol, API_KEY
        );
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
                "https://www.alphavantage.co/query?function=EARNINGS_ESTIMATES&symbol=%s&apikey=%s",
                symbol, API_KEY
        );
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
                "https://www.alphavantage.co/query?function=TOP_GAINERS_LOSERS&apikey=%s",
                API_KEY
        );

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
        System.out.println(jsonData.substring(0, Math.min(jsonData.length(), 200)) + "..."); // מדפיס רק את 200 התווים הראשונים
    }

    // Fetch company overview (name, sector, etc.) from Alpha Vantage
    public static String fetchCompanyOverview(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            symbol = TICKER;
        }
        String url = String.format(
                "https://www.alphavantage.co/query?function=OVERVIEW&symbol=%s&apikey=%s",
                symbol, API_KEY
        );
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return "Error: HTTP Status Code " + response.statusCode();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Error during request: " + e.getMessage();
        }
    }
}