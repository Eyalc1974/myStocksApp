import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages active trading positions with trailing stop-loss support.
 * Each position tracks: symbol, entry price, current stop, ATR multiplier, etc.
 */
public class ActivePositionsStore {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Position {
        public String symbol;
        public double entryPrice;
        public double currentStop;
        public double atrMultiplier;
        public double lastAtr;
        public double highestPrice;
        public String entryDateNy;
        public String lastUpdatedNy;
        public String notes;
        public String sector;  // Added for position limits

        public Position() {}

        public Position(String symbol, double entryPrice, double atrMultiplier) {
            this.symbol = symbol;
            this.entryPrice = entryPrice;
            this.atrMultiplier = atrMultiplier;
            this.highestPrice = entryPrice;
            this.entryDateNy = ZonedDateTime.now(NY).toLocalDate().toString();
            this.lastUpdatedNy = ZonedDateTime.now(NY).toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PositionsData {
        public List<Position> positions = new ArrayList<>();
        public String lastUpdatedNy;
    }

    private final Object lock = new Object();
    private final Path dataFile;
    private final ObjectMapper om;

    // Position limits
    private static final int MAX_POSITIONS = 5;
    private static final int MAX_PER_SECTOR = 1;

    public ActivePositionsStore(Path dataFile) {
        this.dataFile = dataFile;
        this.om = new ObjectMapper();
        try {
            Files.createDirectories(dataFile.getParent());
        } catch (Exception ignore) {}
    }

    public static ActivePositionsStore defaultStore() {
        Path base = Paths.get("finder-cache");
        try { Files.createDirectories(base); } catch (Exception ignore) {}
        return new ActivePositionsStore(base.resolve("active-positions.json"));
    }

    public PositionsData load() {
        synchronized (lock) {
            try {
                if (Files.exists(dataFile)) {
                    return om.readValue(dataFile.toFile(), PositionsData.class);
                }
            } catch (Exception ignore) {}
            return new PositionsData();
        }
    }

    private void persist(PositionsData data) {
        synchronized (lock) {
            try {
                data.lastUpdatedNy = ZonedDateTime.now(NY).toString();
                Path tmp = dataFile.resolveSibling(dataFile.getFileName().toString() + ".tmp");
                om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignore) {}
        }
    }

    public boolean addPosition(String symbol, double entryPrice, double atrMultiplier) {
        if (symbol == null || symbol.isBlank()) return false;
        String sym = symbol.trim().toUpperCase();
        if (entryPrice <= 0) return false;
        if (atrMultiplier <= 0) atrMultiplier = 2.0;

        synchronized (lock) {
            PositionsData data = load();

            // Check if already exists
            for (Position p : data.positions) {
                if (p.symbol != null && p.symbol.equalsIgnoreCase(sym)) {
                    return false; // Already have position in this symbol
                }
            }

            // Check position limit
            if (data.positions.size() >= MAX_POSITIONS) {
                return false; // Max positions reached
            }

            // Get sector for the new position
            String newSector = getSector(sym);

            // Check sector cap
            int sectorCount = 0;
            for (Position p : data.positions) {
                if (p.sector != null && p.sector.equalsIgnoreCase(newSector)) {
                    sectorCount++;
                }
            }
            if (sectorCount >= MAX_PER_SECTOR) {
                return false; // Sector cap reached
            }

            Position pos = new Position(sym, entryPrice, atrMultiplier);
            pos.sector = newSector;

            // Compute initial stop using ATR
            try {
                double atr = fetchCurrentAtr(sym);
                if (atr > 0) {
                    pos.lastAtr = atr;
                    pos.currentStop = entryPrice - (atr * atrMultiplier);
                } else {
                    pos.lastAtr = 0;
                    pos.currentStop = entryPrice * 0.95; // Default 5% below entry
                }
            } catch (Exception e) {
                pos.lastAtr = 0;
                pos.currentStop = entryPrice * 0.95;
            }
            data.positions.add(pos);
            persist(data);
            return true;
        }
    }

    private String getSector(String symbol) {
        try {
            FundamentalData fd = InstitutionalFlowCache.getFundamental(symbol);
            if (fd != null && fd.sector != null && !fd.sector.isBlank()) {
                return fd.sector.toUpperCase();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "UNKNOWN";
    }

    public boolean removePosition(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String sym = symbol.trim().toUpperCase();
        synchronized (lock) {
            PositionsData data = load();
            boolean removed = data.positions.removeIf(p -> p.symbol != null && p.symbol.equalsIgnoreCase(sym));
            if (removed) persist(data);
            return removed;
        }
    }

    public void updateAllPositions() {
        synchronized (lock) {
            PositionsData data = load();
            for (Position pos : data.positions) {
                try {
                    updatePositionPrices(pos);
                } catch (Exception ignore) {}
            }
            persist(data);
        }
    }

    public Position updatePositionPrices(Position pos) {
        if (pos == null || pos.symbol == null) return pos;
        try {
            DataFetcher.setTicker(pos.symbol);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) return pos;

            Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
            if (ohlc == null || ohlc.isEmpty()) return pos;

            // Get last 14 days for ATR calculation
            List<String> dates = new ArrayList<>(ohlc.keySet());
            dates.sort(String::compareTo);
            if (dates.isEmpty()) return pos;

            // Extract OHLC for ATR calculation
            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();
            List<Double> closes = new ArrayList<>();

            int start = Math.max(0, dates.size() - 20);
            for (int i = start; i < dates.size(); i++) {
                double[] bar = ohlc.get(dates.get(i));
                if (bar != null && bar.length >= 4) {
                    highs.add(bar[1]);  // high
                    lows.add(bar[2]);   // low
                    closes.add(bar[3]); // close
                }
            }

            // Get current price (latest close)
            String latestDate = dates.get(dates.size() - 1);
            double[] latestBar = ohlc.get(latestDate);
            double currentPrice = latestBar != null && latestBar.length >= 4 ? latestBar[3] : 0;

            if (currentPrice <= 0) return pos;

            // Calculate ATR
            List<Double> atrValues = ATR.calculateATR(highs, lows, closes, 14);
            double currentAtr = 0;
            if (atrValues != null && !atrValues.isEmpty()) {
                for (int i = atrValues.size() - 1; i >= 0; i--) {
                    if (atrValues.get(i) != null) {
                        currentAtr = atrValues.get(i);
                        break;
                    }
                }
            }

            // Update highest price seen
            if (currentPrice > pos.highestPrice) {
                pos.highestPrice = currentPrice;
            }

            // Update ATR
            if (currentAtr > 0) {
                pos.lastAtr = currentAtr;
            }

            // Update trailing stop using TrailingStopLoss
            if (pos.lastAtr > 0) {
                pos.currentStop = TrailingStopLoss.updateStopLoss(
                    pos.highestPrice, 
                    pos.currentStop, 
                    pos.lastAtr, 
                    pos.atrMultiplier
                );
            }

            pos.lastUpdatedNy = ZonedDateTime.now(NY).toString();

        } catch (Exception ignore) {}
        return pos;
    }

    public double fetchCurrentPrice(String symbol) {
        try {
            DataFetcher.setTicker(symbol);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) return 0;

            Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
            if (ohlc == null || ohlc.isEmpty()) return 0;

            List<String> dates = new ArrayList<>(ohlc.keySet());
            dates.sort(String::compareTo);
            if (dates.isEmpty()) return 0;

            String latestDate = dates.get(dates.size() - 1);
            double[] bar = ohlc.get(latestDate);
            return (bar != null && bar.length >= 4) ? bar[3] : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public double fetchCurrentAtr(String symbol) {
        try {
            DataFetcher.setTicker(symbol);
            String json = DataFetcher.fetchStockData();
            if (json == null || json.isBlank()) return 0;

            Map<String, double[]> ohlc = PriceJsonParser.extractDailyOhlcByDate(json);
            if (ohlc == null || ohlc.isEmpty()) return 0;

            List<String> dates = new ArrayList<>(ohlc.keySet());
            dates.sort(String::compareTo);

            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();
            List<Double> closes = new ArrayList<>();

            int start = Math.max(0, dates.size() - 20);
            for (int i = start; i < dates.size(); i++) {
                double[] bar = ohlc.get(dates.get(i));
                if (bar != null && bar.length >= 4) {
                    highs.add(bar[1]);
                    lows.add(bar[2]);
                    closes.add(bar[3]);
                }
            }

            List<Double> atrValues = ATR.calculateATR(highs, lows, closes, 14);
            if (atrValues == null || atrValues.isEmpty()) return 0;

            for (int i = atrValues.size() - 1; i >= 0; i--) {
                if (atrValues.get(i) != null) return atrValues.get(i);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static class PositionView {
        public String symbol;
        public double entryPrice;
        public double currentPrice;
        public double currentStop;
        public double profitBuffer;
        public double profitBufferPct;
        public double pnlPct;
        public double atr;
        public double atrMultiplier;
        public double highestPrice;
        public boolean shouldSell;
        public String entryDateNy;
        public String lastUpdatedNy;
        public String volatilityLevel;
    }

    public List<PositionView> getPositionViews() {
        List<PositionView> views = new ArrayList<>();
        synchronized (lock) {
            PositionsData data = load();
            for (Position pos : data.positions) {
                PositionView view = new PositionView();
                view.symbol = pos.symbol;
                view.entryPrice = pos.entryPrice;
                view.currentStop = pos.currentStop;
                view.atr = pos.lastAtr;
                view.atrMultiplier = pos.atrMultiplier;
                view.highestPrice = pos.highestPrice;
                view.entryDateNy = pos.entryDateNy;
                view.lastUpdatedNy = pos.lastUpdatedNy;

                // Fetch current price
                double currentPrice = fetchCurrentPrice(pos.symbol);
                view.currentPrice = currentPrice;

                // Calculate profit buffer (how much price can drop before hitting stop)
                view.profitBuffer = currentPrice - pos.currentStop;
                view.profitBufferPct = currentPrice > 0 ? (view.profitBuffer / currentPrice) * 100 : 0;

                // P&L from entry
                view.pnlPct = pos.entryPrice > 0 ? ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100 : 0;

                // Should sell? Only if we have a valid current price!
                // If price fetch failed (currentPrice = 0), don't trigger false sell signal
                view.shouldSell = currentPrice > 0 && TrailingStopLoss.shouldSell(currentPrice, pos.currentStop);

                // Volatility level based on ATR %
                if (pos.lastAtr > 0 && currentPrice > 0) {
                    double atrPct = (pos.lastAtr / currentPrice) * 100;
                    if (atrPct < 1.0) view.volatilityLevel = "נמוכה";
                    else if (atrPct < 2.5) view.volatilityLevel = "בינונית";
                    else if (atrPct < 5.0) view.volatilityLevel = "גבוהה";
                    else view.volatilityLevel = "קיצונית";
                } else {
                    view.volatilityLevel = "לא ידוע";
                }

                views.add(view);
            }
        }
        return views;
    }
}
