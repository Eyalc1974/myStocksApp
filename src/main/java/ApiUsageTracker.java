import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Alpha Vantage API usage per day and API type.
 * Persists data to JSON file and keeps last 30 days of history.
 */
public class ApiUsageTracker {

    private static final String DATA_FILE = "api-usage-history.json";
    private static final int MAX_DAYS = 30;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // Structure: Map<date, Map<apiType, count>>
    private static Map<String, Map<String, Integer>> usageData = new ConcurrentHashMap<>();

    static {
        load();
    }

    /**
     * Record an API call for tracking
     * @param apiType The type of API call (e.g., "TIME_SERIES_DAILY", "BALANCE_SHEET", etc.)
     */
    public static synchronized void track(String apiType) {
        String today = LocalDate.now().format(DATE_FMT);
        
        usageData.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        Map<String, Integer> dayData = usageData.get(today);
        dayData.merge(apiType, 1, Integer::sum);
        
        // Clean old data (keep only last 30 days)
        cleanOldData();
        
        // Persist
        save();
    }

    /**
     * Get usage data for display
     * @return Map of date -> Map of apiType -> count, sorted by date descending
     */
    public static Map<String, Map<String, Integer>> getUsageData() {
        // Return sorted by date descending
        Map<String, Map<String, Integer>> sorted = new LinkedHashMap<>();
        usageData.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .forEach(date -> sorted.put(date, new LinkedHashMap<>(usageData.get(date))));
        return sorted;
    }

    /**
     * Get total calls for a specific date
     */
    public static int getTotalForDate(String date) {
        Map<String, Integer> dayData = usageData.get(date);
        if (dayData == null) return 0;
        return dayData.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Get all unique API types ever recorded
     */
    public static Set<String> getAllApiTypes() {
        Set<String> types = new TreeSet<>();
        for (Map<String, Integer> dayData : usageData.values()) {
            types.addAll(dayData.keySet());
        }
        return types;
    }

    /**
     * Get total calls across all days
     */
    public static int getGrandTotal() {
        int total = 0;
        for (Map<String, Integer> dayData : usageData.values()) {
            for (Integer count : dayData.values()) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Get total calls for today
     */
    public static int getTodayTotal() {
        return getTotalForDate(LocalDate.now().format(DATE_FMT));
    }

    /**
     * Get breakdown for today
     */
    public static Map<String, Integer> getTodayBreakdown() {
        String today = LocalDate.now().format(DATE_FMT);
        Map<String, Integer> dayData = usageData.get(today);
        return dayData == null ? Collections.emptyMap() : new LinkedHashMap<>(dayData);
    }

    private static void cleanOldData() {
        LocalDate cutoff = LocalDate.now().minusDays(MAX_DAYS);
        usageData.keySet().removeIf(dateStr -> {
            try {
                LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                return date.isBefore(cutoff);
            } catch (Exception e) {
                return true; // Remove invalid dates
            }
        });
    }

    private static synchronized void load() {
        try {
            File file = new File(DATA_FILE);
            if (file.exists()) {
                usageData = MAPPER.readValue(file, new TypeReference<ConcurrentHashMap<String, Map<String, Integer>>>() {});
                // Convert inner maps to ConcurrentHashMap
                for (String key : usageData.keySet()) {
                    usageData.put(key, new ConcurrentHashMap<>(usageData.get(key)));
                }
                cleanOldData();
            }
        } catch (Exception e) {
            System.err.println("Failed to load API usage history: " + e.getMessage());
            usageData = new ConcurrentHashMap<>();
        }
    }

    private static synchronized void save() {
        try {
            MAPPER.writeValue(new File(DATA_FILE), usageData);
        } catch (Exception e) {
            System.err.println("Failed to save API usage history: " + e.getMessage());
        }
    }

    /**
     * Force reload from file (useful after external edits)
     */
    public static void forceReload() {
        load();
    }

    /**
     * Clear all data (for testing)
     */
    public static void clearAll() {
        usageData.clear();
        save();
    }
}
