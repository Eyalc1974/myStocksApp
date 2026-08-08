import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Test Alpha Vantage API to debug response format
 */
public class TestAlphaVantageAPI {
    
    private static final String API_KEY = "DH6B36IUFEU8MFGY";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    
    public static void main(String[] args) {
        testStockData("AAPL");
    }
    
    public static void testStockData(String ticker) {
        try {
            String url = String.format(
                    "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=%s&outputsize=full&apikey=%s",
                    ticker, API_KEY
            );
            
            System.out.println("Testing API for: " + ticker);
            System.out.println("URL: " + url);
            System.out.println();
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Response Length: " + response.body().length());
            System.out.println();
            System.out.println("First 1000 characters of response:");
            System.out.println(response.body().substring(0, Math.min(1000, response.body().length())));
            System.out.println();
            System.out.println("...");
            System.out.println();
            
            // Parse JSON to check structure
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            
            System.out.println("JSON Structure:");
            System.out.println("Has 'Time Series (Daily)': " + root.has("Time Series (Daily)"));
            System.out.println("Has 'Error Message': " + root.has("Error Message"));
            System.out.println("Has 'Information': " + root.has("Information"));
            
            if (root.has("Error Message")) {
                System.out.println("Error Message: " + root.path("Error Message").asText());
            }
            
            if (root.has("Information")) {
                System.out.println("Information: " + root.path("Information").asText());
            }
            
            if (root.has("Time Series (Daily)")) {
                JsonNode timeSeries = root.path("Time Series (Daily)");
                System.out.println("Time Series is Object: " + timeSeries.isObject());
                System.out.println("Time Series size: " + timeSeries.size());
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
