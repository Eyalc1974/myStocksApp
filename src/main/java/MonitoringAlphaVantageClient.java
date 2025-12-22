import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class MonitoringAlphaVantageClient {
    private final HttpClient http;
    private final ObjectMapper om;
    private final String apiKey;
    private final String entitlement;

    public MonitoringAlphaVantageClient(String apiKey) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.om = new ObjectMapper();
        this.apiKey = apiKey;
        this.entitlement = readEntitlementFromEnv();
    }

    public static MonitoringAlphaVantageClient fromEnv() {
        String key = System.getenv("ALPHAVANTAGE_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("ALPHA_VANTAGE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = "demo";
        }
        return new MonitoringAlphaVantageClient(key);
    }

    public JsonNode query(Map<String, String> params) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing AlphaVantage api key");
        }
        Map<String, String> p = new LinkedHashMap<>(params);
        if (!p.containsKey("entitlement") && entitlement != null && !entitlement.isBlank()) {
            p.put("entitlement", entitlement);
        }
        p.put("apikey", apiKey);
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (qs.length() > 0) qs.append("&");
            qs.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
        }
        String url = "https://www.alphavantage.co/query?" + qs;
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("AlphaVantage http " + resp.statusCode());
        }
        return om.readTree(resp.body());
    }

    public JsonNode newsSentiment(String symbol) throws Exception {
        return query(Map.of("function", "NEWS_SENTIMENT", "tickers", symbol.toUpperCase(), "sort", "LATEST", "limit", "50"));
    }

    public JsonNode overview(String symbol) throws Exception {
        return query(Map.of("function", "OVERVIEW", "symbol", symbol.toUpperCase()));
    }

    public JsonNode dividends(String symbol) throws Exception {
        return query(Map.of("function", "DIVIDENDS", "symbol", symbol.toUpperCase()));
    }

    public JsonNode incomeStatement(String symbol) throws Exception {
        return query(Map.of("function", "INCOME_STATEMENT", "symbol", symbol.toUpperCase()));
    }

    public JsonNode cashFlow(String symbol) throws Exception {
        return query(Map.of("function", "CASH_FLOW", "symbol", symbol.toUpperCase()));
    }

    public JsonNode timeSeriesDaily(String symbol) throws Exception {
        return query(Map.of("function", "TIME_SERIES_DAILY", "symbol", symbol.toUpperCase()));
    }

    public JsonNode timeSeriesIntraday(String symbol, String interval) throws Exception {
        String iv = (interval == null || interval.isBlank()) ? "5min" : interval;
        return query(Map.of(
                "function", "TIME_SERIES_INTRADAY",
                "symbol", symbol.toUpperCase(),
                "interval", iv,
                "outputsize", "compact"
        ));
    }

    public JsonNode globalQuote(String symbol) throws Exception {
        return query(Map.of("function", "GLOBAL_QUOTE", "symbol", symbol.toUpperCase()));
    }

    public JsonNode temaWeeklyOpen(String symbol, int timePeriod) throws Exception {
        return query(Map.of("function", "TEMA", "symbol", symbol.toUpperCase(), "interval", "weekly", "time_period", String.valueOf(timePeriod), "series_type", "open"));
    }

    public JsonNode vwap15Min(String symbol) throws Exception {
        return query(Map.of("function", "VWAP", "symbol", symbol.toUpperCase(), "interval", "15min"));
    }

    public JsonNode sarWeekly(String symbol, double acceleration, double maximum) throws Exception {
        return query(Map.of("function", "SAR", "symbol", symbol.toUpperCase(), "interval", "weekly", "acceleration", String.valueOf(acceleration), "maximum", String.valueOf(maximum)));
    }

    public JsonNode bbandsWeeklyClose(String symbol, int timePeriod, int nbdev) throws Exception {
        return query(Map.of("function", "BBANDS", "symbol", symbol.toUpperCase(), "interval", "weekly", "time_period", String.valueOf(timePeriod), "series_type", "close", "nbdevup", String.valueOf(nbdev), "nbdevdn", String.valueOf(nbdev)));
    }

    public JsonNode atrDaily(String symbol, int timePeriod) throws Exception {
        return query(Map.of("function", "ATR", "symbol", symbol.toUpperCase(), "interval", "daily", "time_period", String.valueOf(timePeriod)));
    }

    public JsonNode adDaily(String symbol) throws Exception {
        return query(Map.of("function", "AD", "symbol", symbol.toUpperCase(), "interval", "daily"));
    }

    public JsonNode obvWeekly(String symbol) throws Exception {
        return query(Map.of("function", "OBV", "symbol", symbol.toUpperCase(), "interval", "weekly"));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String readEntitlementFromEnv() {
        String ent = System.getenv("ALPHAVANTAGE_ENTITLEMENT");
        if (ent == null || ent.isBlank()) {
            ent = System.getenv("ALPHA_VANTAGE_ENTITLEMENT");
        }
        return ent;
    }
}
