import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DataFetcher {

    // יש להחליף ב-API Key אמיתי!
    private static final String API_KEY = "KJ06EC5AYPGSOP0Z";
    private static String TICKER = "META";

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

    public static void main(String[] args) {
        String jsonData = fetchStockData();
        System.out.println(jsonData.substring(0, Math.min(jsonData.length(), 200)) + "..."); // מדפיס רק את 200 התווים הראשונים
    }
}