import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class PriceJsonParser {

    // Parses Alpha Vantage TIME_SERIES_DAILY JSON and returns closing prices ordered by date ascending
    public static List<Double> extractClosingPrices(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        // Alpha Vantage key typically: "Time Series (Daily)"
        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            // Sometimes error payloads or different keys; try a couple of alternatives
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyList();
        }

        // Use TreeMap to sort dates ascending
        Map<String, Double> byDate = new TreeMap<>();

        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day != null && day.isObject()) {
                // Alpha Vantage uses keys like "4. close"
                JsonNode closeNode = day.get("4. close");
                if (closeNode == null) {
                    // Some endpoints for intraday can use "4. close" as well; keep as null if missing
                    // Try a fallback commonly seen
                    closeNode = day.get("4a. close (USD)");
                }
                if (closeNode != null && closeNode.isTextual()) {
                    try {
                        double close = Double.parseDouble(closeNode.asText());
                        byDate.put(date, close);
                    } catch (NumberFormatException ignore) {
                        // skip malformed values
                    }
                }
            }
        }

        List<Double> prices = new ArrayList<>(byDate.size());
        for (Double v : byDate.values()) {
            prices.add(v);
        }
        return prices;
    }

    public static Map<String, Double> extractCloseByDate(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, Double> byDate = new TreeMap<>();
        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day != null && day.isObject()) {
                JsonNode closeNode = day.get("4. close");
                if (closeNode == null) {
                    closeNode = day.get("4a. close (USD)");
                }
                if (closeNode != null && closeNode.isTextual()) {
                    try {
                        double close = Double.parseDouble(closeNode.asText());
                        byDate.put(date, close);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }
        return byDate;
    }

    public static Map<String, double[]> extractDailyOhlcByDate(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyMap();
        }

        Map<String, double[]> byDate = new TreeMap<>();
        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day == null || !day.isObject()) continue;

            Double open = parseTextDouble(day.get("1. open"));
            Double high = parseTextDouble(day.get("2. high"));
            Double low = parseTextDouble(day.get("3. low"));
            Double close = parseTextDouble(day.get("4. close"));

            if (open == null) open = parseTextDouble(day.get("1a. open (USD)"));
            if (high == null) high = parseTextDouble(day.get("2a. high (USD)"));
            if (low == null) low = parseTextDouble(day.get("3a. low (USD)"));
            if (close == null) close = parseTextDouble(day.get("4a. close (USD)"));

            if (open == null && high == null && low == null && close == null) continue;
            byDate.put(date, new double[]{
                    open == null ? Double.NaN : open,
                    high == null ? Double.NaN : high,
                    low == null ? Double.NaN : low,
                    close == null ? Double.NaN : close
            });
        }
        return byDate;
    }

    private static Double parseTextDouble(JsonNode n) {
        if (n == null) return null;
        try {
            if (n.isTextual()) return Double.parseDouble(n.asText());
            if (n.isNumber()) return n.asDouble();
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    // Parses Alpha Vantage TIME_SERIES_DAILY JSON and returns high prices ordered by date ascending
    public static List<Double> extractHighPrices(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyList();
        }

        Map<String, Double> byDate = new TreeMap<>();
        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day != null && day.isObject()) {
                JsonNode highNode = day.get("2. high");
                if (highNode == null) {
                    highNode = day.get("2a. high (USD)");
                }
                if (highNode != null && highNode.isTextual()) {
                    try {
                        double high = Double.parseDouble(highNode.asText());
                        byDate.put(date, high);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        List<Double> prices = new ArrayList<>(byDate.size());
        for (Double v : byDate.values()) {
            prices.add(v);
        }
        return prices;
    }

    // Parses Alpha Vantage TIME_SERIES_DAILY JSON and returns low prices ordered by date ascending
    public static List<Double> extractLowPrices(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyList();
        }

        Map<String, Double> byDate = new TreeMap<>();
        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day != null && day.isObject()) {
                JsonNode lowNode = day.get("3. low");
                if (lowNode == null) {
                    lowNode = day.get("3a. low (USD)");
                }
                if (lowNode != null && lowNode.isTextual()) {
                    try {
                        double low = Double.parseDouble(lowNode.asText());
                        byDate.put(date, low);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        List<Double> prices = new ArrayList<>(byDate.size());
        for (Double v : byDate.values()) {
            prices.add(v);
        }
        return prices;
    }

    // Parses Alpha Vantage TIME_SERIES_DAILY JSON and returns volumes ordered by date ascending
    public static List<Long> extractVolumeData(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        JsonNode series = root.get("Time Series (Daily)");
        if (series == null || !series.isObject()) {
            JsonNode alt1 = root.get("Time Series (Digital Currency Daily)");
            JsonNode alt2 = root.get("Time Series (5min)");
            series = series != null ? series : (alt1 != null ? alt1 : alt2);
        }
        if (series == null || !series.isObject()) {
            return Collections.emptyList();
        }

        Map<String, Long> byDate = new TreeMap<>();
        Iterator<String> dates = series.fieldNames();
        while (dates.hasNext()) {
            String date = dates.next();
            JsonNode day = series.get(date);
            if (day != null && day.isObject()) {
                // Alpha Vantage uses keys like "5. volume"
                JsonNode volNode = day.get("5. volume");
                if (volNode == null) {
                    // Some crypto endpoints use different keys; try a common alternative
                    volNode = day.get("5. volume");
                }
                if (volNode != null && volNode.isTextual()) {
                    try {
                        long vol = Long.parseLong(volNode.asText().replace(",", ""));
                        byDate.put(date, vol);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }

        List<Long> volumes = new ArrayList<>(byDate.size());
        for (Long v : byDate.values()) {
            volumes.add(v);
        }
        return volumes;
    }

    // בתוך JsonParser.java
    public static List<Long> extractVolumeDataLng(String jsonData) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonData);

        // ניגש לאזור המכיל את נתוני סדרת הזמן (Time Series)
        JsonNode timeSeriesNode = rootNode.get("Time Series (Daily)");

        if (timeSeriesNode == null) {
            // אם ה-API החזיר שגיאה או שהמפתח השתנה
            System.err.println("שגיאה: לא נמצא 'Time Series (Daily)' ב-JSON.");
            return Collections.emptyList();
        }

        // משתמשים במפה כדי לאחסן את הנתונים ולמיין אותם לפי תאריך (כדי שהישן יופיע ראשון)
        Map<String, JsonNode> unsortedVolumes = new java.util.TreeMap<>();

        Iterator<Map.Entry<String, JsonNode>> fields = timeSeriesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            unsortedVolumes.put(entry.getKey(), entry.getValue());
        }

        List<Long> volumeValues = new ArrayList<>();

        // עובר על התאריכים הממוינים לפי סדר כרונולוגי
        for (Map.Entry<String, JsonNode> entry : unsortedVolumes.entrySet()) {
            JsonNode dayData = entry.getValue();

            // *** חילוץ הערך של "5. volume" ***
            JsonNode volumeNode = dayData.get("5. volume");

            if (volumeNode != null) {
                String volumeString = volumeNode.asText();

                // ממיר את המחרוזת למספר שלם ארוך (Long)
                volumeValues.add(Long.parseLong(volumeString));
            }
        }

        return volumeValues;
    }

    private static List<Double> extractFinnhubDoubleSeries(String json, String field) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode status = root.get("s");
        if (status == null || !status.isTextual() || !"ok".equalsIgnoreCase(status.asText())) {
            return Collections.emptyList();
        }
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return Collections.emptyList();
        }
        List<Double> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode v = arr.get(i);
            if (v != null && v.isNumber()) {
                out.add(v.asDouble());
            }
        }
        return out;
    }

    public static List<Double> extractClosingPricesFromFinnhub(String json) throws Exception {
        return extractFinnhubDoubleSeries(json, "c");
    }

    public static List<Double> extractHighPricesFromFinnhub(String json) throws Exception {
        return extractFinnhubDoubleSeries(json, "h");
    }

    public static List<Double> extractLowPricesFromFinnhub(String json) throws Exception {
        return extractFinnhubDoubleSeries(json, "l");
    }

    public static List<Long> extractVolumeFromFinnhub(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode status = root.get("s");
        if (status == null || !status.isTextual() || !"ok".equalsIgnoreCase(status.asText())) {
            return Collections.emptyList();
        }
        JsonNode arr = root.get("v");
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonNode v = arr.get(i);
            if (v != null && v.isNumber()) {
                out.add(v.asLong());
            }
        }
        return out;
    }

    // Extracts service-level messages like rate-limit notes or error messages from Alpha Vantage JSON
    public static String extractServiceMessage(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            String[] keys = new String[]{"Note", "Information", "Error Message", "message"};
            for (String k : keys) {
                JsonNode n = root.get(k);
                if (n != null && n.isTextual()) {
                    return n.asText();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
