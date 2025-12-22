import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class MonitoringStore {
    private final Object lock = new Object();
    private final Path baseDir;
    private final Path tickersPath;
    private final Path snapshotsDir;
    private final ObjectMapper om;

    public MonitoringStore(Path baseDir, Path tickersPath, Path snapshotsDir) {
        this.baseDir = baseDir;
        this.tickersPath = tickersPath;
        this.snapshotsDir = snapshotsDir;
        this.om = new ObjectMapper();
        try {
            Files.createDirectories(this.snapshotsDir);
        } catch (Exception ignore) {
        }
    }

    public static MonitoringStore defaultStore() {
        String dir = System.getenv("MONITORING_DATA_DIR");
        Path base;
        if (dir != null && !dir.isBlank()) {
            base = Paths.get(dir.trim());
        } else {
            base = Paths.get(System.getProperty("user.home"), ".alphapoint-ai", "monitoring");
        }
        try { Files.createDirectories(base); } catch (Exception ignore) {}
        Path tickers = base.resolve("monitoring-stocks.txt");
        Path cache = base.resolve("monitoring-cache");
        try { Files.createDirectories(cache); } catch (Exception ignore) {}

        // Best-effort migration from legacy relative paths (when MONITORING_DATA_DIR is not set)
        // This avoids "lost data" after changing persistence location.
        if (dir == null || dir.isBlank()) {
            try {
                boolean hasNewTickers = Files.exists(tickers) && Files.size(tickers) > 0;
                Path legacyTickers = Paths.get("monitoring-stocks.txt");
                if (!hasNewTickers && Files.exists(legacyTickers) && Files.size(legacyTickers) > 0) {
                    Files.copy(legacyTickers, tickers, StandardCopyOption.REPLACE_EXISTING);
                }

                Path legacyCache = Paths.get("monitoring-cache");
                if (Files.exists(legacyCache) && Files.isDirectory(legacyCache)) {
                    try (var stream = Files.list(legacyCache)) {
                        stream.forEach(p -> {
                            try {
                                if (!Files.isRegularFile(p)) return;
                                String name = p.getFileName().toString();
                                if (!(name.endsWith(".json") || name.endsWith(".txt"))) return;
                                Path dest = cache.resolve(name);
                                if (Files.exists(dest)) return;
                                Files.copy(p, dest);
                            } catch (Exception ignore2) {
                            }
                        });
                    }
                }
            } catch (Exception ignore) {
            }
        }

        return new MonitoringStore(base, tickers, cache);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public List<String> loadTickers() {
        synchronized (lock) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            try {
                if (Files.exists(tickersPath)) {
                    for (String line : Files.readAllLines(tickersPath, StandardCharsets.UTF_8)) {
                        String t = line == null ? "" : line.trim().toUpperCase();
                        if (!t.isEmpty() && t.matches("[A-Z0-9.:-]{1,10}")) out.add(t);
                    }
                }
            } catch (Exception ignore) {
            }
            return new ArrayList<>(out);
        }
    }

    public boolean addTicker(String ticker) {
        String t = ticker == null ? "" : ticker.trim().toUpperCase();
        if (t.isEmpty() || !t.matches("[A-Z0-9.:-]{1,10}")) return false;
        synchronized (lock) {
            List<String> current = loadTickers();
            if (current.contains(t)) return false;
            current.add(t);
            persistTickers(current);
            return true;
        }
    }

    public boolean removeTicker(String ticker) {
        String t = ticker == null ? "" : ticker.trim().toUpperCase();
        synchronized (lock) {
            List<String> current = loadTickers();
            boolean removed = current.removeIf(x -> x.equalsIgnoreCase(t));
            if (removed) persistTickers(current);
            return removed;
        }
    }

    public void persistTickers(List<String> tickers) {
        synchronized (lock) {
            try {
                Path tmp = tickersPath.resolveSibling(tickersPath.getFileName().toString() + ".tmp");
                Files.write(tmp, (String.join("\n", tickers) + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, tickersPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignore) {
            }
        }
    }

    public void saveSnapshot(MonitoringSnapshot snapshot) {
        if (snapshot == null || snapshot.symbol() == null || snapshot.symbol().isBlank()) return;
        String sym = snapshot.symbol().trim().toUpperCase();
        synchronized (lock) {
            try {
                Files.createDirectories(snapshotsDir);
                Path p = snapshotsDir.resolve(sym + ".json");
                Path tmp = snapshotsDir.resolve(sym + ".json.tmp");
                om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                Path txt = snapshotsDir.resolve(sym + ".txt");
                Path txtTmp = snapshotsDir.resolve(sym + ".txt.tmp");
                Files.writeString(txtTmp, buildTextReport(snapshot), StandardCharsets.UTF_8);
                Files.move(txtTmp, txt, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignore) {
            }
        }
    }

    private static String buildTextReport(MonitoringSnapshot s) {
        StringBuilder out = new StringBuilder();
        out.append("Monitoring Snapshot\n");
        out.append("Symbol: ").append(s.symbol()).append("\n");
        try {
            ZonedDateTime z = Instant.ofEpochMilli(s.asOfEpochMillis()).atZone(ZoneId.systemDefault());
            out.append("As Of: ").append(DateTimeFormatter.ISO_ZONED_DATE_TIME.format(z)).append("\n");
        } catch (Exception ignore) {
            out.append("As Of (epoch): ").append(s.asOfEpochMillis()).append("\n");
        }

        out.append("\nSignals (2-10 trading days)\n");
        for (String k : new String[]{"2","3","4","5","6","7","8","9","10"}) {
            String rec = s.recommendationByDays() == null ? null : s.recommendationByDays().get(k);
            Double ret = s.returnsByDays() == null ? null : s.returnsByDays().get(k);
            Double sc = s.scoreByDays() == null ? null : s.scoreByDays().get(k);
            out.append(k).append("d: ")
                    .append(rec == null ? "N/A" : rec)
                    .append(" | return=").append(ret == null ? "N/A" : String.format("%.2f%%", ret))
                    .append(" | score=").append(sc == null ? "N/A" : String.format("%.2f", sc))
                    .append("\n");
        }

        if (s.newsSentimentScore() != null) {
            out.append("\nNews sentiment avg: ").append(String.format("%.3f", s.newsSentimentScore())).append("\n");
        }

        out.append("\nIndicators\n");
        if (s.indicatorValues() != null && !s.indicatorValues().isEmpty()) {
            List<Map.Entry<String, Double>> items = new ArrayList<>(s.indicatorValues().entrySet());
            items.sort(Comparator.comparing(Map.Entry::getKey));
            for (Map.Entry<String, Double> e : items) {
                out.append("- ").append(e.getKey()).append(": ");
                if (e.getValue() == null) out.append("N/A");
                else out.append(String.format("%.6f", e.getValue()));
                String note = s.indicatorNotes() == null ? null : s.indicatorNotes().get(e.getKey());
                if (note != null && !note.isBlank()) out.append(" | ").append(note);
                out.append("\n");
            }
        } else {
            out.append("(none)\n");
        }

        out.append("\nFundamentals (Overview)\n");
        if (s.fundamentals() != null && !s.fundamentals().isEmpty()) {
            List<Map.Entry<String, String>> f = new ArrayList<>(s.fundamentals().entrySet());
            f.sort(Comparator.comparing(Map.Entry::getKey));
            for (Map.Entry<String, String> e : f) {
                out.append("- ").append(e.getKey()).append(": ").append(e.getValue() == null ? "" : e.getValue()).append("\n");
            }
        } else {
            out.append("(none)\n");
        }

        out.append("\nTop News\n");
        if (s.newsTop() != null && !s.newsTop().isEmpty()) {
            for (Map<String, String> n : s.newsTop()) {
                String title = n.getOrDefault("title", "");
                String url = n.getOrDefault("url", "");
                String sent = n.getOrDefault("sentiment", "");
                out.append("- ").append(title);
                if (!sent.isEmpty()) out.append(" [").append(sent).append("]");
                if (!url.isEmpty()) out.append(" ").append(url);
                out.append("\n");
            }
        } else {
            out.append("(none)\n");
        }
        return out.toString();
    }

    public MonitoringSnapshot loadSnapshot(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String sym = symbol.trim().toUpperCase();
        synchronized (lock) {
            try {
                Path p = snapshotsDir.resolve(sym + ".json");
                if (!Files.exists(p)) return null;
                MonitoringSnapshot s = om.readValue(p.toFile(), MonitoringSnapshot.class);
                if (s == null) return null;
                return s;
            } catch (Exception ignore) {
                return null;
            }
        }
    }

    public Instant snapshotUpdatedAt(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        try {
            Path p = snapshotsDir.resolve(symbol.trim().toUpperCase() + ".json");
            if (!Files.exists(p)) return null;
            return Files.getLastModifiedTime(p).toInstant();
        } catch (Exception ignore) {
            return null;
        }
    }
}
