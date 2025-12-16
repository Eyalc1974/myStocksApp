import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebServer {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static final class IntradayAlertState {
        volatile boolean running;
        volatile String symbol;
        volatile String lastError;
        volatile ZonedDateTime lastCheckNy;
        volatile String lastBarTs;
        volatile Double lastPrice;
        volatile Long lastVolume;
        volatile String lastSignal;
        volatile String lastNotifiedSignal;
        volatile String lastNotifiedBarTs;
        final List<String> history = new ArrayList<>();
    }

    private static final Object intradayLock = new Object();
    private static final IntradayAlertState intradayState = new IntradayAlertState();
    private static ScheduledExecutorService intradayExec;

    private static class DailyPicksCache {
        private LocalDate date;
        private String text;
        private List<String> tickers;
        private String lastError;
    }

    private static final Object dailyPicksLock = new Object();
    private static final DailyPicksCache dailyPicksCache = new DailyPicksCache();

    private static String htmlPage(String body) {
        String base = "" +
                "<!doctype html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "<meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>AlphaPoint AI</title>" +
                "<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;margin:0;min-height:100vh;" +
                "background:linear-gradient(135deg,#0f172a 0%,#0b132b 50%,#1b2a41 100%);color:#e5e7eb;}\n" +
                "*{box-sizing:border-box;}\n" +
                ".container{max-width:1180px;margin:0 auto;padding:32px;}\n" +
                "h1{margin:0 0 16px 0;font-size:28px;letter-spacing:.3px;color:#ffffff;}\n" +
                ".subtitle{color:#9ca3af;margin-bottom:24px;}\n" +
                "form{margin-bottom:16px;}\n" +
                "input[type=text]{width:260px;background:#0b1220;border:1px solid #1f2a44;color:#e5e7eb;border-radius:8px;padding:10px 12px;outline:none;}\n" +
                "input[type=text]::placeholder{color:#6b7280;}\n" +
                "button{font-size:15px;padding:10px 14px;border-radius:8px;border:1px solid #2a3b66;background:#1e3a8a;color:#e5e7eb;cursor:pointer;transition:all .15s ease;}\n" +
                "button:hover{background:#2b50b3;border-color:#365a9f;}\n" +
                "pre{background:#0b1220;color:#b6f399;padding:14px;border-radius:10px;white-space:pre-wrap;word-break:break-word;border:1px solid #1f2a44;direction:rtl;text-align:right;unicode-bidi:embed;}\n" +
                ".card{background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.08);backdrop-filter:saturate(140%) blur(4px);border-radius:14px;padding:18px 18px;margin-bottom:18px;box-shadow:0 10px 20px rgba(0,0,0,0.25);}\n" +
                ".title{font-weight:600;margin-bottom:10px;color:#f3f4f6;}\n" +
                "a{color:#93c5fd;text-decoration:none;}a:hover{text-decoration:underline;}\n" +
                ".loading-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.55);display:none;align-items:center;justify-content:center;z-index:9999;}\n" +
                ".loading-card{background:#0b1220;border:1px solid #1f2a44;border-radius:12px;padding:20px 24px;color:#e5e7eb;box-shadow:0 10px 24px rgba(0,0,0,.35);text-align:center;min-width:260px;}\n" +
                ".loading-emoji{font-size:28px;margin-bottom:8px;display:block;}\n" +
                ".indicator-panel{direction:rtl;text-align:right;font-size:13px;}\n" +
                ".indicator-item{margin-bottom:10px;padding-bottom:8px;border-bottom:1px solid rgba(148,163,184,0.25);}\n" +
                ".indicator-item:last-child{border-bottom:none;padding-bottom:0;}\n" +
                ".indicator-label{font-weight:600;color:#e5e7eb;}\n" +
                ".indicator-value{color:#93c5fd;display:inline-block;margin-left:6px;}\n" +
                ".indicator-text{color:#cbd5e1;margin-top:4px;}\n" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div id=\"loading\" class=\"loading-overlay\">" +
                "  <div class=\"loading-card\">" +
                "    <span class=\"loading-emoji\">⏳</span>" +
                "    <div>LOADING ...</div>" +
                "    <div style=\"margin-top:4px;color:#9ca3af;\">מבצע חישוב, ייתכן שיימשך עד דקה ⏳</div>" +
                "</div>" +
                "</div>" +
                "<div class=\"container\">" +
                "<h1>AlphaPoint AI</h1>" +
                "<div style=\"margin-bottom:16px;\">"+
                "<a href=\"/\">Home</a> · "+
                "<a href=\"/about\">About</a> · "+
                "<a href=\"/favorites\">Favorites</a> · "+
                "<a href=\"/portfolio-manage\">Manage Portfolio</a> · "+
                "<a href=\"/monitoring\">Monitoring Stocks</a> · "+
                "<a href=\"/intraday-alerts\">Intraday Alerts</a> · "+
                "<a href=\"/analysts\">Analysts</a> · "+
                "<a href=\"/finder\">FINDER</a>"+
                "</div>" +
                (body == null ? "" : body) +
                "</div>" +
                "<script>(function(){function show(){var el=document.getElementById('loading');if(el){el.style.display='flex';}};var forms=document.querySelectorAll('form');forms.forEach(function(f){f.addEventListener('submit',function(){show();});});})();</script>" +
                "</body></html>";
        return base;
    }

    // Best-effort summarization via AI backends (prefer free local):
    // 1) Local Ollama at http://localhost:11434 (model: llama3.2)
    // 2) OpenAI (if OPENAI_API_KEY is set), as a fallback
    private static String summarizeWithOpenAI(String analysis, String symbol) {
        try {
            if (analysis == null || analysis.isBlank()) return null;
            String input = analysis;
            if (input.length() > 9000) input = input.substring(input.length() - 9000);
            String prompt = "Please summarize the following report for stock '"+symbol+"' in 5–8 concise bullet points in English. Focus on: trend & momentum, technical signals (RSI/MACD/ADX if present), risk (ATR/Max Drawdown), and fundamentals (PEG/PB/quality). Do not provide investment advice; describe and analyze only.\n\n" + input;

            // Try local Ollama first (free local tool)
            String ollama = summarizeWithOllama(prompt);
            if (ollama != null && !ollama.isEmpty()) return ollama;

            // Fall back to OpenAI if key exists
            String key = System.getenv("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                String body = "{\n"+
                        "\"model\":\"gpt-4o-mini\",\n"+
                        "\"messages\":[{"+
                        "\"role\":\"system\",\"content\":\"You are a helpful financial analysis summarizer.\"},{"+
                        "\"role\":\"user\",\"content\":" + jsonString(prompt) + "}],\n"+
                        "\"temperature\":0.3\n"+
                        "}";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    ObjectMapper om = new ObjectMapper();
                    JsonNode root = om.readTree(resp.body());
                    JsonNode msg = root.path("choices").isArray() && root.path("choices").size()>0
                            ? root.path("choices").get(0).path("message").path("content") : null;
                    if (msg != null && msg.isTextual()) return msg.asText();
                }
                // if OpenAI failed, continue to Ollama
            }

            return "(AI summary unavailable: install Ollama and run: 'ollama run llama3.2', or set OPENAI_API_KEY)";
        } catch (Exception e) {
            return "(AI summary error: " + e.getMessage() + ")";
        }
    }

    private static String telegramTestMessage() {
        ZonedDateTime now = ZonedDateTime.now(NY);
        return "AlphaPoint AI test message (" + now.toString() + ")";
    }

    private static boolean isNyseRegularHoursNow() {
        ZonedDateTime now = ZonedDateTime.now(NY);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        LocalTime open = LocalTime.of(9, 30);
        LocalTime close = LocalTime.of(16, 0);
        return (!t.isBefore(open)) && (t.isBefore(close));
    }

    private static Map<String, Double> loadDailyCloseByDateCached(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return Map.of();
            String sym = symbol.trim().toUpperCase();
            Path dir = Paths.get("finder-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve("daily-" + sym + ".json");
            String json = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 6L * 60 * 60 * 1000) {
                        json = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }
            if (json == null) {
                DataFetcher.setTicker(sym);
                json = DataFetcher.fetchStockData();
                try { Files.writeString(cacheFile, json == null ? "" : json, StandardCharsets.UTF_8); } catch (Exception ignore) {}
            }
            return PriceJsonParser.extractCloseByDate(json);
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private static Double realizedReturnPct(Map<String, Double> closeByDate, java.time.LocalDate baseDate, int tradingDaysForward) {
        try {
            if (closeByDate == null || closeByDate.isEmpty() || baseDate == null || tradingDaysForward <= 0) return null;
            List<String> dates = new ArrayList<>(closeByDate.keySet());
            if (dates.isEmpty()) return null;
            String baseKey = baseDate.toString();
            int baseIdx = dates.indexOf(baseKey);
            if (baseIdx < 0) {
                baseIdx = -1;
                for (int i = 0; i < dates.size(); i++) {
                    if (dates.get(i).compareTo(baseKey) <= 0) baseIdx = i;
                    else break;
                }
            }
            if (baseIdx < 0) return null;
            int targetIdx = baseIdx + tradingDaysForward;
            if (targetIdx >= dates.size()) return null;
            Double baseClose = closeByDate.get(dates.get(baseIdx));
            Double targetClose = closeByDate.get(dates.get(targetIdx));
            if (baseClose == null || targetClose == null || baseClose == 0.0) return null;
            return (targetClose - baseClose) / baseClose * 100.0;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String computeIntradaySignal(JsonNode intraday, IntradayAlertState st) {
        if (intraday == null) return "HOLD";
        JsonNode series = intraday.path("Time Series (5min)");
        if (series == null || series.isMissingNode() || !series.isObject()) {
            Iterator<String> it = intraday.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null && k.startsWith("Time Series")) {
                    series = intraday.path(k);
                    break;
                }
            }
        }
        if (series == null || series.isMissingNode() || !series.isObject()) return "HOLD";

        List<String> keys = new ArrayList<>();
        Iterator<String> fn = series.fieldNames();
        while (fn.hasNext()) keys.add(fn.next());
        if (keys.isEmpty()) return "HOLD";
        keys.sort(Comparator.reverseOrder());

        String latestTs = keys.get(0);
        String prevTs = keys.size() > 1 ? keys.get(1) : latestTs;
        JsonNode latest = series.path(latestTs);
        JsonNode prev = series.path(prevTs);

        Double close = parseDoubleOrNull(latest.path("4. close").asText(""));
        Double prevClose = parseDoubleOrNull(prev.path("4. close").asText(""));
        Long vol = null;
        try {
            String vs = latest.path("5. volume").asText("");
            if (vs != null && !vs.isBlank()) vol = Long.parseLong(vs.trim());
        } catch (Exception ignore) {}

        st.lastBarTs = latestTs;
        st.lastPrice = close;
        st.lastVolume = vol;

        if (close == null || prevClose == null || prevClose == 0.0) return "HOLD";
        double pct = (close - prevClose) / prevClose * 100.0;

        int n = Math.min(12, keys.size());
        double sumVol = 0.0;
        int cntVol = 0;
        for (int i = 1; i < n; i++) {
            JsonNode bar = series.path(keys.get(i));
            String vs = bar.path("5. volume").asText("");
            try {
                if (vs != null && !vs.isBlank()) {
                    sumVol += Double.parseDouble(vs.trim());
                    cntVol++;
                }
            } catch (Exception ignore) {}
        }
        double avgVol = cntVol == 0 ? 0.0 : (sumVol / cntVol);
        boolean volSpike = vol != null && avgVol > 0.0 && (vol.doubleValue() >= (2.0 * avgVol));

        if (volSpike && pct >= 0.50) return "BUY";
        if (volSpike && pct <= -0.50) return "SELL";
        return "HOLD";
    }

    private static boolean sendTelegram(String text) {
        try {
            String token = System.getenv("TELEGRAM_BOT_TOKEN");
            String chat = System.getenv("TELEGRAM_CHAT_ID");
            if (token == null || token.isBlank() || chat == null || chat.isBlank()) return false;
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "chat_id=" + java.net.URLEncoder.encode(chat, StandardCharsets.UTF_8) +
                    "&text=" + java.net.URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8) +
                    "&disable_web_page_preview=true";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static String buildAnalystProxyAlphaVantageCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String sym = symbol.trim().toUpperCase();

            Path dir = Paths.get("analysts-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(sym + "-alphavantage.json");
            String cachedJson = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 24L * 60 * 60 * 1000) {
                        cachedJson = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }

            ObjectMapper om = new ObjectMapper();
            JsonNode combined;
            if (cachedJson == null) {
                MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                JsonNode overview = null;
                JsonNode news = null;
                try { overview = av.overview(sym); } catch (Exception ignore) {}
                try { news = av.newsSentiment(sym); } catch (Exception ignore) {}
                String combinedJson = "{\n\"overview\": " + (overview == null ? "{}" : overview.toString()) + ",\n\"news\": " + (news == null ? "{}" : news.toString()) + "\n}";
                try { Files.writeString(cacheFile, combinedJson, StandardCharsets.UTF_8); } catch (Exception ignore) {}
                combined = om.readTree(combinedJson);
            } else {
                combined = om.readTree(cachedJson);
            }

            JsonNode ov = combined.path("overview");
            String name = ov.path("Name").asText("");
            String sector = ov.path("Sector").asText("");
            String peStr = ov.path("PERatio").asText("");
            String pbStr = ov.path("PriceToBookRatio").asText("");
            String divStr = ov.path("DividendYield").asText("");

            Double pe = parseDoubleOrNull(peStr);
            Double pb = parseDoubleOrNull(pbStr);
            Double div = parseDoubleOrNull(divStr);

            Double newsScore = null;
            try {
                JsonNode feed = combined.path("news").path("feed");
                if (feed != null && feed.isArray()) {
                    double sum = 0.0;
                    int cnt = 0;
                    for (JsonNode it : feed) {
                        JsonNode s = it.get("overall_sentiment_score");
                        if (s != null && s.isTextual()) {
                            Double v = parseDoubleOrNull(s.asText());
                            if (v != null) { sum += v; cnt++; }
                        }
                    }
                    if (cnt > 0) newsScore = sum / cnt;
                }
            } catch (Exception ignore) {}

            int score = 0;
            if (pe != null) {
                if (pe > 0 && pe <= 25) score += 1;
                else if (pe > 40) score -= 1;
            }
            if (pb != null) {
                if (pb > 0 && pb <= 6) score += 1;
                else if (pb > 12) score -= 1;
            }
            if (div != null) {
                if (div >= 0.01) score += 1;
            }
            if (newsScore != null) {
                if (newsScore >= 0.15) score += 1;
                else if (newsScore <= -0.15) score -= 1;
            }

            String label;
            String color;
            if (score >= 2) { label = "GREEN"; color = "#22c55e"; }
            else if (score <= -1) { label = "RED"; color = "#fca5a5"; }
            else { label = "NEUTRAL"; color = "#93c5fd"; }

            StringBuilder sb = new StringBuilder();
            sb.append("<div class='card'><div class='title'>Analyst Proxy (Alpha Vantage)</div>");
            if (!name.isEmpty()) sb.append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(escapeHtml(name)).append("</div>");
            if (!sector.isEmpty()) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Sector: ").append(escapeHtml(sector)).append("</div>");
            sb.append("<div style='margin-bottom:10px;'>Overall: <span style='color:").append(color).append(";font-weight:700;'>").append(label).append("</span></div>");
            sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:6px;'>")
              .append(badge("PE", pe == null ? 0 : (int)Math.round(pe)))
              .append(badge("P/B", pb == null ? 0 : (int)Math.round(pb)))
              .append(badge("Div%", div == null ? 0 : (int)Math.round(div * 100.0)))
              .append(badge("News", newsScore == null ? 0 : (int)Math.round(newsScore * 100.0)))
              .append("</div>");
            sb.append("<div style='color:#6b7280;margin-top:8px;line-height:1.45;'>");
            sb.append("<div style='margin-bottom:6px;'><b>What this means</b>: this is a heuristic proxy built from Alpha Vantage \"OVERVIEW\" fundamentals and \"NEWS_SENTIMENT\". It is <b>not</b> real analyst consensus.</div>");

            sb.append("<div style='margin-top:10px;color:#9ca3af;font-weight:600;'>Indicator explanations</div>");
            sb.append("<div style='margin-top:6px;'>");
            sb.append("<div><b>P/E</b> (Price/Earnings): how much the market pays per $1 of earnings. Lower can indicate cheaper valuation, but very low can also mean weak growth. Here: +1 if P/E ≤ 25, -1 if P/E &gt; 40.</div>");
            sb.append("<div style='margin-top:6px;'><b>P/B</b> (Price/Book): price relative to balance-sheet book value. Lower can be cheaper; very high can mean premium/overvaluation. Here: +1 if P/B ≤ 6, -1 if P/B &gt; 12.</div>");
            sb.append("<div style='margin-top:6px;'><b>Div%</b> (Dividend yield): annual dividend / price. Indicates shareholder returns and often maturity/stability. Here: +1 if yield ≥ 1%.</div>");
            sb.append("<div style='margin-top:6px;'><b>News</b> (avg sentiment): average of Alpha Vantage news \"overall_sentiment_score\" (range roughly -1..+1). Positive implies optimistic tone; negative implies pessimistic. Here: +1 if score ≥ 0.15, -1 if score ≤ -0.15. Displayed as ~score×100.</div>");
            sb.append("</div>");

            sb.append("<div style='margin-top:10px;color:#9ca3af;font-weight:600;'>Overall label logic</div>");
            sb.append("<div style='margin-top:6px;'>We sum the points from the rules above. Total score=").append(score).append(". ");
            sb.append("<b>GREEN</b> if score ≥ 2, <b>RED</b> if score ≤ -1, otherwise <b>NEUTRAL</b>." );
            sb.append("</div>");
            sb.append("</div>");
            sb.append("</div>");
            return sb.toString();
        } catch (Exception e) {
            return "<div class='card'><div class='title'>Analyst Proxy (Alpha Vantage)</div><div style='color:#fca5a5'>(Error: "+escapeHtml(e.getMessage())+")</div></div>";
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("None")) return null;
        try {
            return Double.parseDouble(t);
        } catch (Exception ignore) {
            return null;
        }
    }

    // Call local Ollama server (http://localhost:11434) to summarize text using a small local model
    private static String summarizeWithOllama(String prompt) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = "{\n"+
                    " \"model\": \"llama3.2\",\n"+
                    " \"prompt\": " + jsonString(prompt) + ",\n"+
                    " \"stream\": false\n"+
                    "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(resp.body());
            JsonNode out = root.get("response");
            if (out != null && out.isTextual()) return out.asText();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    // -------- Analyst data (Finnhub) with 24h cache ---------
    private static String buildAnalystCard(String symbol) {
        try {
            if (symbol == null || symbol.isBlank()) return "";
            String key = System.getenv("FINNHUB_API_KEY");
            if (key == null || key.isBlank()) {
                return "<div class='card'><div class='title'>Analyst Consensus</div><div style='color:#9ca3af'>(Set FINNHUB_API_KEY to enable analyst data)</div></div>";
            }
            Path dir = Paths.get("analysts-cache");
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(symbol.toUpperCase()+".json");
            String combinedJson = null;
            long now = System.currentTimeMillis();
            if (Files.exists(cacheFile)) {
                try {
                    long age = now - Files.getLastModifiedTime(cacheFile).toMillis();
                    if (age < 24L*60*60*1000) {
                        combinedJson = Files.readString(cacheFile, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode combined = null;
            if (combinedJson == null) {
                HttpClient client = HttpClient.newHttpClient();
                String recoUrl = "https://finnhub.io/api/v1/stock/recommendation?symbol="+symbol+"&token="+key;
                String ptUrl = "https://finnhub.io/api/v1/stock/price-target?symbol="+symbol+"&token="+key;
                HttpRequest r1 = HttpRequest.newBuilder().uri(URI.create(recoUrl)).build();
                HttpRequest r2 = HttpRequest.newBuilder().uri(URI.create(ptUrl)).build();
                HttpResponse<String> h1 = client.send(r1, HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> h2 = client.send(r2, HttpResponse.BodyHandlers.ofString());
                String body1 = (h1.statusCode()==200? h1.body(): "[]");
                String body2 = (h2.statusCode()==200? h2.body(): "{}");
                // Build combined JSON string
                combinedJson = "{\n\"recommendation\": "+body1+",\n\"priceTarget\": "+body2+"\n}";
                try { Files.writeString(cacheFile, combinedJson, StandardCharsets.UTF_8); } catch (Exception ignore) {}
            }
            combined = om.readTree(combinedJson);
            JsonNode recArr = combined.path("recommendation");
            String period = "";
            int sb=0,b=0,h=0,s=0,ss=0;
            if (recArr.isArray() && recArr.size()>0) {
                JsonNode latest = recArr.get(0); // Finnhub returns latest first
                sb = latest.path("strongBuy").asInt(0);
                b  = latest.path("buy").asInt(0);
                h  = latest.path("hold").asInt(0);
                s  = latest.path("sell").asInt(0);
                ss = latest.path("strongSell").asInt(0);
                period = latest.path("period").asText("");
            }
            JsonNode pt = combined.path("priceTarget");
            String mean = pt.path("targetMean").isMissingNode()? "": String.format("%.2f", pt.path("targetMean").asDouble());
            String median = pt.path("targetMedian").isMissingNode()? "": String.format("%.2f", pt.path("targetMedian").asDouble());
            String hi = pt.path("targetHigh").isMissingNode()? "": String.format("%.2f", pt.path("targetHigh").asDouble());
            String lo = pt.path("targetLow").isMissingNode()? "": String.format("%.2f", pt.path("targetLow").asDouble());
            String updated = pt.path("lastUpdated").asText("");

            StringBuilder sbuf = new StringBuilder();
            sbuf.append("<div class='card'><div class='title'>Analyst Consensus</div>");
            if (!period.isEmpty()) sbuf.append("<div style='color:#9ca3af;margin-bottom:6px;'>Period: ").append(escapeHtml(period)).append("</div>");
            sbuf.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:6px;'>")
                .append(badge("Strong Buy", sb)).append(badge("Buy", b)).append(badge("Hold", h)).append(badge("Sell", s)).append(badge("Strong Sell", ss))
                .append("</div>");
            if (!mean.isEmpty() || !median.isEmpty() || !hi.isEmpty() || !lo.isEmpty()) {
                sbuf.append("<div style='color:#9ca3af'>Price Targets: ");
                boolean first=true;
                if (!mean.isEmpty()) { sbuf.append("Mean ").append(escapeHtml(mean)); first=false; }
                if (!median.isEmpty()) { sbuf.append(first?"":" · ").append("Median ").append(escapeHtml(median)); first=false; }
                if (!hi.isEmpty()) { sbuf.append(first?"":" · ").append("High ").append(escapeHtml(hi)); first=false; }
                if (!lo.isEmpty()) { sbuf.append(first?"":" · ").append("Low ").append(escapeHtml(lo)); }
                sbuf.append("</div>");
                if (!updated.isEmpty()) sbuf.append("<div style='color:#6b7280;margin-top:4px;'>Updated: ").append(escapeHtml(updated)).append("</div>");
            }
            sbuf.append("</div>");
            return sbuf.toString();
        } catch (Exception e) {
            return "<div class='card'><div class='title'>Analyst Consensus</div><div style='color:#fca5a5'>(Error: "+escapeHtml(e.getMessage())+")</div></div>";
        }
    }

    private static String badge(String label, int value) {
        return "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>"+escapeHtml(label)+": "+value+"</span>";
    }

    private static void respondHtml(HttpExchange ex, String html, int code) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondSvg(HttpExchange ex, String svg, int code) throws IOException {
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "image/svg+xml; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String part : body.split("&")) {
            String[] kv = part.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        int contentLength = 0;
        String len = ex.getRequestHeaders().getFirst("Content-length");
        if (len != null) {
            try { contentLength = Integer.parseInt(len); } catch (Exception ignored) {}
        }
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(0, contentLength));
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String runAndCapture(Runnable r) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(bos, true, StandardCharsets.UTF_8);
        try {
            System.setOut(capture);
            System.setErr(capture);
            r.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8099), 0);

        // Simple once-a-day cache for Portfolio Weekly output
        final Object portfolioCacheLock = new Object();
        final class PortfolioCache {
            String text;
            LocalDate date;
        }
        final PortfolioCache portfolioCache = new PortfolioCache();

        // Disk persistence for today's weekly report text
        final Path weeklyCacheDir = Paths.get("weekly-cache");
        try { Files.createDirectories(weeklyCacheDir); } catch (Exception ignore) {}
        try {
            LocalDate today = LocalDate.now();
            Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
            if (Files.exists(file)) {
                String cached = Files.readString(file, StandardCharsets.UTF_8);
                portfolioCache.text = cached;
                portfolioCache.date = today;
            }
        } catch (Exception ignore) {}

        // Load portfolio from disk (portfolio.txt) at startup
        final Path portfolioPath = Paths.get("portfolio.txt");
        try {
            if (Files.exists(portfolioPath)) {
                java.util.List<String> lines = Files.readAllLines(portfolioPath, StandardCharsets.UTF_8);
                java.util.ArrayList<String> syms = new java.util.ArrayList<>();
                for (String line : lines) {
                    if (line == null) continue;
                    String t = line.trim().toUpperCase();
                    if (!t.isEmpty()) syms.add(t);
                }
                if (!syms.isEmpty()) {
                    PortfolioWeeklySummary.setPortfolio(syms);
                }
            }
        } catch (Exception ignore) {}

        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String content = "<div class='card'><div class='title'>Analyze single symbol</div>"+
                        "<form method='post' action='/run-main'>"+
                        "<input type='text' name='symbol' placeholder='e.g. CRM' required /> "+
                        "<button type='submit'>Run</button>"+
                        "</form></div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        server.createContext("/finder", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String content = "<div class='card'><div class='title'>Stocks Finder Nasdaq100</div>"+
                        "<form method='post' action='/recommendations'>"+
                        "<button type='submit'>Nasdaq stock Finder (Find randomly 5 stocks from Nasdaq100)</button>"+
                        "</form>"+
                        "<div style='margin-top:8px'><a href='/finder-last'>Open Last Finder Results</a></div>"+
                        "</div>"+
                        "<div class='card'><div class='title'>Daily Nasdaq Top 5 (GREEN)</div>"+
                        "<form method='post' action='/nasdaq-daily-top'>"+
                        "<button type='submit'>Daily Nasdaq Top 5 (GREEN) - Find best long-term candidates</button>"+
                        "</form>"+
                        "<div style='margin-top:8px'><a href='/nasdaq-daily-top-last'>Open Last Daily Top 5 Picks</a></div>"+
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // Manage portfolio page (view / add / remove)
        server.createContext("/portfolio-manage", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }
                List<String> items = PortfolioWeeklySummary.getPortfolio();
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Portfolio Actions</div>");
                sb.append("<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>");
                sb.append("<form method='post' action='/portfolio-weekly' style='margin:0'><button type='submit'>My Portfolio - Weekly</button></form>");
                sb.append("<form method='post' action='/portfolio-weekly' style='margin:0'><input type='hidden' name='force' value='1'/><button type='submit'>My Portfolio - Weekly (Force Refresh)</button></form>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div class='card'><div class='title'>Manage Portfolio</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:8px;'>Current tickers ("+items.size()+"):</div>");
                sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;'>");
                for (String t : items) {
                    sb.append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>")
                      .append(escapeHtml(t)).append("</span>");
                }
                sb.append("</div>");
                sb.append("<form method='post' action='/portfolio-add' style='margin-bottom:10px;'>" +
                          "<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required /> " +
                          "<button type='submit'>Add</button></form>");
                sb.append("<form method='post' action='/portfolio-remove'>" +
                          "<input type='text' name='symbol' placeholder='Remove ticker (e.g. AAPL)' required /> " +
                          "<button type='submit'>Remove</button></form>");
                sb.append("<div style='color:#9ca3af;margin-top:8px;'>Note: Changes reset today's cache for the weekly report.</div>");
                sb.append("</div>");
                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        // Add ticker endpoint (POST)
        server.createContext("/portfolio-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = PortfolioWeeklySummary.addTicker(sym);
                synchronized (portfolioCacheLock) { // invalidate daily cache
                    try {
                        java.lang.reflect.Field fText = portfolioCache.getClass().getDeclaredField("text");
                        java.lang.reflect.Field fDate = portfolioCache.getClass().getDeclaredField("date");
                        fText.setAccessible(true);
                        fDate.setAccessible(true);
                        fText.set(portfolioCache, null);
                        fDate.set(portfolioCache, null);
                    } catch (Exception ignore) {}
                }
                // Persist portfolio to disk
                try { Files.write(portfolioPath, (String.join("\n", PortfolioWeeklySummary.getPortfolio())+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/portfolio-manage?status=" + (ok?"added":"invalid"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // Remove ticker endpoint (POST)
        server.createContext("/portfolio-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = PortfolioWeeklySummary.removeTicker(sym);
                synchronized (portfolioCacheLock) { // invalidate daily cache
                    try {
                        java.lang.reflect.Field fText = portfolioCache.getClass().getDeclaredField("text");
                        java.lang.reflect.Field fDate = portfolioCache.getClass().getDeclaredField("date");
                        fText.setAccessible(true);
                        fDate.setAccessible(true);
                        fText.set(portfolioCache, null);
                        fDate.set(portfolioCache, null);
                    } catch (Exception ignore) {}
                }
                // Persist portfolio to disk
                try { Files.write(portfolioPath, (String.join("\n", PortfolioWeeklySummary.getPortfolio())+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/portfolio-manage?status=" + (ok?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // ---------------- Monitoring Stocks (separate module) ----------------
        final MonitoringStore monitoringStore = MonitoringStore.defaultStore();
        final MonitoringAlphaVantageClient monitoringClient = MonitoringAlphaVantageClient.fromEnv();
        final MonitoringAnalyzer monitoringAnalyzer = new MonitoringAnalyzer(monitoringClient);
        final MonitoringScheduler monitoringScheduler = new MonitoringScheduler(monitoringStore, monitoringAnalyzer);
        // Run Mon–Fri on New York time at: 09:30, 11:30, 13:30, 15:30 ET
        try {
            monitoringScheduler.startNyseWeekdayEvery2Hours();
        } catch (Exception ignore) {}

        // Monitoring Stocks page (view / add / remove / refresh)
         server.createContext("/monitoring", new HttpHandler() {
             @Override public void handle(HttpExchange ex) throws IOException {
                 if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                     respondHtml(ex, htmlPage(""), 200);
                     return;
                 }
                 List<String> tickers = monitoringStore.loadTickers();
                 StringBuilder sb = new StringBuilder();
                 sb.append("<div class='card'><div class='title'>Monitoring Stocks</div>");
                 sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Pick stocks to monitor. A background job runs twice a day and stores a snapshot per ticker (2/3/4/5 day trend + technical/fundamental/news signals).</div>");

                 // Data directory (important when running from different working directories)
                 try {
                     sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Data directory: ")
                       .append(escapeHtml(String.valueOf(monitoringStore.getBaseDir())))
                       .append("</div>");
                 } catch (Exception ignore) {}

                 // Scheduler status (helps debug when no reports exist yet)
                 try {
                     java.time.ZonedDateTime nextNy = monitoringScheduler.getNextRunNy();
                     java.time.ZonedDateTime lastNy = monitoringScheduler.getLastRunNy();
                     String err = monitoringScheduler.getLastError();
                     sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:12px;padding:12px;margin-bottom:12px;'>");
                     sb.append("<div style='color:#e5e7eb;font-weight:600;margin-bottom:6px;'>Scheduler Status (New York time)</div>");
                     sb.append("<div style='color:#9ca3af'>Next run: ").append(nextNy==null?"(not scheduled yet)":escapeHtml(nextNy.toString())).append("</div>");
                     sb.append("<div style='color:#9ca3af'>Last run: ").append(lastNy==null?"(never)":escapeHtml(lastNy.toString())).append("</div>");
                     if (err != null && !err.isBlank()) {
                         sb.append("<div style='color:#fca5a5;margin-top:6px;'>Last error: ").append(escapeHtml(err)).append("</div>");
                     }
                     sb.append("</div>");
                 } catch (Exception ignore) {}

                 sb.append("<form method='post' action='/monitoring-add' style='margin-bottom:10px;'>")
                   .append("<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required /> ")
                   .append("<button type='submit'>Add</button></form>");
                 sb.append("<form method='post' action='/monitoring-remove' style='margin-bottom:10px;'>")
                  .append("<input type='text' name='symbol' placeholder='Remove ticker (e.g. AAPL)' required /> ")
                  .append("<button type='submit'>Remove</button></form>");
                sb.append("<form method='post' action='/monitoring-refresh' style='margin-bottom:0;'>")
                  .append("<button type='submit'>Refresh Now (async)</button></form>");
                sb.append("</div>");

                if (tickers.isEmpty()) {
                    sb.append("<div class='card'><div class='title'>Monitored list</div><div style='color:#9ca3af'>No monitored tickers yet.</div></div>");
                    respondHtml(ex, htmlPage(sb.toString()), 200);
                    return;
                }

                sb.append("<div class='card'><div class='title'>Latest Snapshots</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Signals are computed separately for 2-10 trading-day windows. BUY means multi-signal positive bias; DROP means negative bias; HOLD is neutral.</div>");

                for (String t : tickers) {
                    MonitoringSnapshot snap = monitoringStore.loadSnapshot(t);
                    String esc = escapeHtml(t);
                    String safeId = t == null ? "" : t.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "_");

                    Map<String, Double> closeByDate = Map.of();
                    java.time.LocalDate snapDateNy = null;
                    try {
                        snapDateNy = java.time.Instant.ofEpochMilli(snap.asOfEpochMillis()).atZone(NY).toLocalDate();
                        closeByDate = loadDailyCloseByDateCached(t);
                    } catch (Exception ignore) {}

                    sb.append("<div style='border-top:1px solid rgba(148,163,184,0.25);padding-top:12px;margin-top:12px;'>");
                    sb.append("<div style='display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin-bottom:8px;'>")
                      .append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'><b>")
                      .append(esc)
                      .append("</b></span>");

                    try {
                        java.time.Instant upd = monitoringStore.snapshotUpdatedAt(t);
                        if (upd != null) {
                            sb.append("<span style='color:#9ca3af'>saved: ").append(escapeHtml(upd.toString())).append("</span>");
                        }
                    } catch (Exception ignore) {}

                    sb.append("<form method='post' action='/run-main' style='display:inline;margin:0'>")
                      .append("<input type='hidden' name='symbol' value='").append(esc).append("'/>")
                      .append("<button type='submit'>Full Analyze</button></form>");
                    sb.append("</div>");

                    if (snap == null) {
                        sb.append("<div style='color:#fbbf24'>No snapshot yet. Click Refresh Now or wait for the scheduled run.</div>");
                        sb.append("</div>");
                        continue;
                    }

                    sb.append("<div style='display:flex;flex-wrap:wrap;gap:8px;margin-bottom:10px;'>");
                    for (String k : new String[]{"2","3","4","5","6","7","8","9","10"}) {
                        String rec = snap.recommendationByDays() == null ? null : snap.recommendationByDays().get(k);
                        Double ret = snap.returnsByDays() == null ? null : snap.returnsByDays().get(k);
                        Double sc = snap.scoreByDays() == null ? null : snap.scoreByDays().get(k);
                        String recText = rec == null ? "N/A" : rec;
                        String pillColor = recText.equals("BUY") ? "#22c55e" : (recText.equals("DROP") ? "#fca5a5" : "#93c5fd");

                        Double actual = null;
                        try { actual = realizedReturnPct(closeByDate, snapDateNy, Integer.parseInt(k)); } catch (Exception ignore) {}
                        String actualText = actual == null ? "N/A" : String.format("%+.2f%%", actual);
                        String actualColor = (actual == null) ? "#9ca3af" : (actual >= 0.0 ? "#22c55e" : "#fca5a5");
                        String pill = "<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>"+
                                "<span style='color:#9ca3af;'>"+escapeHtml(k)+"d</span> " +
                                "<span style='color:"+pillColor+";font-weight:700;'>"+escapeHtml(recText)+"</span>" +
                                "<span style='color:#9ca3af;'> · return "+(ret==null?"N/A":String.format("%.2f%%", ret))+"</span>" +
                                "<span style='color:#9ca3af;'> · actual <span style='color:"+actualColor+";font-weight:700;'>"+escapeHtml(actualText)+"</span></span>" +
                                "<span style='color:#9ca3af;'> · score "+(sc==null?"N/A":String.format("%.2f", sc))+"</span>" +
                                "</span>";
                        sb.append(pill);
                    }
                    sb.append("</div>");

                    sb.append("<div style='margin-bottom:10px'>")
                      .append("<img src='/chart?symbol=").append(esc).append("&w=900&h=260&n=120' alt='chart'/>")
                      .append("</div>");

                    String detailsId = "snap-details-" + safeId;
                    sb.append("<button type='button' class='toggle-details' data-target='").append(detailsId).append("' style='margin-top:6px;'>+ Details</button>");
                    sb.append("<div id='").append(detailsId).append("' style='display:none;margin-top:10px'>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left'>");
                    sb.append("<div class='title'>Indicators</div>");
                    if (snap.indicatorValues() != null) {
                        for (Map.Entry<String, Double> e : snap.indicatorValues().entrySet()) {
                            String kk = escapeHtml(e.getKey());
                            String vv = e.getValue() == null ? "" : String.format("%.4f", e.getValue());
                            String note = snap.indicatorNotes() == null ? null : snap.indicatorNotes().get(e.getKey());
                            sb.append("<div class='indicator-item'>")
                              .append("<span class='indicator-label'>").append(kk).append("</span>")
                              .append("<span class='indicator-value'>").append(escapeHtml(vv)).append("</span>")
                              .append(note == null ? "" : ("<div class='indicator-text'>" + escapeHtml(note) + "</div>"))
                              .append("</div>");
                        }
                    }
                    sb.append("</div>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left;margin-top:10px'>");
                    sb.append("<div class='title'>Fundamentals (Overview)</div>");
                    if (snap.fundamentals() != null && !snap.fundamentals().isEmpty()) {
                        for (Map.Entry<String, String> e : snap.fundamentals().entrySet()) {
                            String kk = escapeHtml(e.getKey());
                            String vv = escapeHtml(e.getValue());
                            sb.append("<div class='indicator-item'>")
                              .append("<span class='indicator-label'>").append(kk).append("</span>")
                              .append("<span class='indicator-value'>").append(vv).append("</span>")
                              .append("</div>");
                        }
                    }
                    sb.append("</div>");

                    sb.append("<div class='indicator-panel' style='direction:ltr;text-align:left;margin-top:10px'>");
                    sb.append("<div class='title'>Latest News</div>");
                    if (snap.newsTop() != null && !snap.newsTop().isEmpty()) {
                        sb.append("<div style='display:flex;flex-direction:column;gap:10px'>");
                        for (Map<String,String> n : snap.newsTop()) {
                            String title = escapeHtml(n.getOrDefault("title", ""));
                            String url = escapeHtml(n.getOrDefault("url", ""));
                            String sum = escapeHtml(n.getOrDefault("summary", ""));
                            String sentiment = escapeHtml(n.getOrDefault("sentiment", ""));
                            sb.append("<div style='padding:10px 12px;background:#0b1220;border:1px solid #1f2a44;border-radius:10px'>")
                              .append("<div style='font-weight:600;margin-bottom:4px;'>").append(title).append("</div>")
                              .append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(sentiment).append("</div>")
                              .append("<div style='color:#cbd5e1;margin-bottom:6px;'>").append(sum).append("</div>")
                              .append(url.isEmpty()?"":("<a href='"+url+"' target='_blank' rel='noreferrer'>Open</a>"))
                              .append("</div>");
                        }
                        sb.append("</div>");
                    } else {
                        sb.append("<div style='color:#9ca3af'>No news (or API limit reached).</div>");
                    }
                    sb.append("</div>");

                    sb.append("</div>");
                    sb.append("</div>");
                }

                sb.append("</div>");
                sb.append("<script>(function(){var btns=document.querySelectorAll('.toggle-details');for(var i=0;i<btns.length;i++){btns[i].addEventListener('click',function(){var id=this.getAttribute('data-target');var el=document.getElementById(id);if(!el)return;var open=(el.style.display!=='none');el.style.display=open?'none':'';this.textContent=(open?'+ Details':'- Details');});}})();</script>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/monitoring-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = monitoringStore.addTicker(sym);
                ex.getResponseHeaders().add("Location", "/monitoring?status=" + (ok?"added":"invalid_or_exists"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/monitoring-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                boolean ok = monitoringStore.removeTicker(sym);
                ex.getResponseHeaders().add("Location", "/monitoring?status=" + (ok?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/monitoring-refresh", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                try { monitoringScheduler.triggerNowAsync(); } catch (Exception ignore) {}
                ex.getResponseHeaders().add("Location", "/monitoring?status=refresh_started");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/intraday-alerts", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Intraday Alerts (Experimental)</div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>On-demand 5-minute polling for <b>one</b> ticker during NYSE regular hours (09:30–16:00 ET). Uses Alpha Vantage intraday bars and triggers BUY/SELL on volume spikes + price moves.</div>");

                IntradayAlertState st;
                synchronized (intradayLock) { st = intradayState; }

                String status = st.running ? "RUNNING" : "STOPPED";
                String statusColor = st.running ? "#22c55e" : "#fbbf24";
                sb.append("<div style='margin-bottom:10px;'>Status: <span style='color:").append(statusColor).append(";font-weight:700;'>").append(status).append("</span></div>");
                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>NYSE hours now: ").append(isNyseRegularHoursNow()?"YES":"NO").append("</div>");
                if (st.symbol != null && !st.symbol.isBlank()) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Symbol: <b>").append(escapeHtml(st.symbol)).append("</b></div>");
                if (st.lastCheckNy != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last check (NY): ").append(escapeHtml(st.lastCheckNy.toString())).append("</div>");
                if (st.lastBarTs != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Latest bar: ").append(escapeHtml(st.lastBarTs)).append("</div>");
                if (st.lastPrice != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last price: ").append(escapeHtml(String.format("%.4f", st.lastPrice))).append("</div>");
                if (st.lastVolume != null) sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Last volume: ").append(escapeHtml(String.valueOf(st.lastVolume))).append("</div>");

                if (st.lastSignal != null) {
                    String col = st.lastSignal.equals("BUY") ? "#22c55e" : (st.lastSignal.equals("SELL") ? "#fca5a5" : "#93c5fd");
                    sb.append("<div style='margin-bottom:12px;'>Signal: <span style='color:").append(col).append(";font-weight:700;'>").append(escapeHtml(st.lastSignal)).append("</span></div>");
                }
                if (st.lastError != null && !st.lastError.isBlank()) {
                    sb.append("<div style='color:#fca5a5;margin-bottom:12px;'>Last error: ").append(escapeHtml(st.lastError)).append("</div>");
                }

                sb.append("<div style='display:flex;gap:12px;flex-wrap:wrap;margin-bottom:12px;'>");
                sb.append("<form method='post' action='/intraday-start' style='margin:0'>")
                  .append("<input type='text' name='symbol' placeholder='Ticker (e.g. AAPL)' required /> ")
                  .append("<button type='submit'>Start (5min)</button></form>");
                sb.append("<form method='post' action='/intraday-stop' style='margin:0'>")
                  .append("<button type='submit'>Stop</button></form>");
                sb.append("</div>");

                sb.append("<div style='color:#9ca3af;margin-bottom:10px;'>Telegram: set <code>TELEGRAM_BOT_TOKEN</code> and <code>TELEGRAM_CHAT_ID</code> to receive alerts.</div>");
                sb.append("</div>");

                sb.append("<div class='card'><div class='title'>Recent Alerts</div>");
                if (st.history.isEmpty()) {
                    sb.append("<div style='color:#9ca3af'>No alerts yet.</div>");
                } else {
                    sb.append("<div style='display:flex;flex-direction:column;gap:8px;'>");
                    int start = Math.max(0, st.history.size() - 20);
                    for (int i = st.history.size() - 1; i >= start; i--) {
                        sb.append("<div style='background:#0b1220;border:1px solid #1f2a44;border-radius:10px;padding:10px 12px;'>")
                          .append(escapeHtml(st.history.get(i)))
                          .append("</div>");
                    }
                    sb.append("</div>");
                }
                sb.append("</div>");

                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/intraday-start", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                sym = sym == null ? "" : sym.trim().toUpperCase();
                synchronized (intradayLock) {
                    intradayState.symbol = sym;
                    intradayState.running = true;
                    intradayState.lastError = null;
                    intradayState.lastSignal = null;
                    intradayState.lastNotifiedSignal = null;
                    intradayState.lastNotifiedBarTs = null;
                    intradayState.lastCheckNy = null;
                    intradayState.lastBarTs = null;
                    intradayState.lastPrice = null;
                    intradayState.lastVolume = null;
                    intradayState.history.clear();
                    if (intradayExec != null) {
                        try { intradayExec.shutdownNow(); } catch (Exception ignore) {}
                        intradayExec = null;
                    }
                    intradayExec = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("intraday-alerts");
                        return t;
                    });
                    intradayExec.scheduleAtFixedRate(() -> {
                        IntradayAlertState st = intradayState;
                        if (!st.running) return;
                        if (st.symbol == null || st.symbol.isBlank()) return;
                        st.lastCheckNy = ZonedDateTime.now(NY);
                        if (!isNyseRegularHoursNow()) return;
                        try {
                            MonitoringAlphaVantageClient av = MonitoringAlphaVantageClient.fromEnv();
                            JsonNode intraday = av.timeSeriesIntraday(st.symbol, "5min");
                            String sig = computeIntradaySignal(intraday, st);
                            st.lastSignal = sig;
                            st.lastError = null;
                            if (("BUY".equals(sig) || "SELL".equals(sig)) && st.lastBarTs != null) {
                                boolean already = sig.equals(st.lastNotifiedSignal) && st.lastBarTs.equals(st.lastNotifiedBarTs);
                                if (!already) {
                                    String msg = "Intraday alert: " + sig + " " + st.symbol + " (bar " + st.lastBarTs + ") price=" + (st.lastPrice==null?"N/A":String.format("%.4f", st.lastPrice)) + " vol=" + (st.lastVolume==null?"N/A":String.valueOf(st.lastVolume));
                                    st.history.add(ZonedDateTime.now(NY).toString() + " | " + msg);
                                    sendTelegram(msg);
                                    st.lastNotifiedSignal = sig;
                                    st.lastNotifiedBarTs = st.lastBarTs;
                                }
                            }
                        } catch (Exception e) {
                            st.lastError = e.getMessage();
                        }
                    }, 0, 5, TimeUnit.MINUTES);
                }
                ex.getResponseHeaders().add("Location", "/intraday-alerts?status=started");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        server.createContext("/intraday-stop", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                synchronized (intradayLock) {
                    intradayState.running = false;
                    if (intradayExec != null) {
                        try { intradayExec.shutdownNow(); } catch (Exception ignore) {}
                        intradayExec = null;
                    }
                }
                ex.getResponseHeaders().add("Location", "/intraday-alerts?status=stopped");
                ex.sendResponseHeaders(303, -1);
                ex.close();
            }
        });

        // About page with model explanations and links
        server.createContext("/about", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String content = "" +
                        "<div class=\"card\"><div class=\"title\">אודות המודלים (About)</div>" +
                        "<p>העמוד מפרט את המודלים שבהם האפליקציה משתמשת לניתוח טכני ופונדמנטלי, כולל קישורים ללמידה נוספת.</p>" +
                        "<ul>" +
                        "<li><b>Piotroski F-Score</b> — 9 בדיקות בינאריות של רווחיות/מינוף/יעילות לדירוג מניות ערך.</li>" +
                        "<li><b>Altman Z-Score</b> — מדד סיכון פשיטת-רגל המבוסס על יחסים מאזניים.</li>" +
                        "<li><b>Quality & Profitability</b> — ROIC, ROE, שיעור רווח גולמי, FCF Margin, EBIT Margin ומגמות (YoY/TTM).</li>" +
                        "<li><b>Growth</b> — קצב צמיחת הכנסות/EPS (CAGR ל-3/5 שנים), יציבות הצמיחה (סטיית תקן).</li>" +
                        "<li><b>Valuation Mix</b> — P/B (מכפיל הון), EV/EBITDA, EV/Sales, PEG עם בדיקות סבירות לצמיחה.</li>" +
                        "<li><b>SMA (Simple Moving Average)</b> — ממוצע נע פשוט למדידת מגמה. <a href=\"https://www.investopedia.com/terms/s/sma.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>RSI (Relative Strength Index)</b> — מזהה קניות/מכירות יתר (70/30). <a href=\"https://www.investopedia.com/terms/r/rsi.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>MACD</b> — מומנטום ושינוי מגמה באמצעות ממוצעים מעריכיים. <a href=\"https://www.investopedia.com/terms/m/macd.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Stochastic Oscillator</b> — השוואת סגירה לטווח המחירים (%K/%D, אזורי 20/80). <a href=\"https://www.investopedia.com/terms/s/stochasticoscillator.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Bollinger Bands</b> — מדד תנודתיות; רצועות עליונה/תחתונה. <a href=\"https://www.investopedia.com/terms/b/bollingerbands.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>ADX (Average Directional Index)</b> — חוזק מגמה; מעל ~25 מגמה חזקה. <a href=\"https://www.investopedia.com/terms/a/adx.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>ATR (Average True Range)</b> — תנודתיות יומית ממוצעת, שימוש בניהול סיכונים ו-Stop-Loss. <a href=\"https://www.investopedia.com/terms/a/atr.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>CMF (Chaikin Money Flow)</b> — צבירה/פיזור לפי מחיר ונפח. <a href=\"https://www.investopedia.com/terms/c/chaikinmoneyflow.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Pivot Points</b> — נקודת ציר יומית (PP) ורמות תמיכה/התנגדות. <a href=\"https://www.investopedia.com/terms/p/pivotpoint.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>Fibonacci Retracement</b> — רמות 38.2%/50%/61.8% לאיתור אזורי כניסה לאחר תיקון. <a href=\"https://www.investopedia.com/terms/f/fibonacciretracement.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>DCF (Discounted Cash Flow)</b> — שווי פנימי מבוסס תחזית תזרימי מזומנים. <a href=\"https://www.investopedia.com/terms/d/dcf.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "<li><b>PEG Ratio</b> — יחס P/E לצמיחה; ~1 הוגן, <1 זול, >2 יקר. <a href=\"https://www.investopedia.com/terms/p/pegratio.asp\" target=\"_blank\" rel=\"noreferrer\">Investopedia</a></li>" +
                        "</ul>" +
                        "<p style=\"color:#9ca3af\">המודלים מוצגים לצורכי לימוד בלבד ואינם מהווים ייעוץ השקעות.</p>" +
                        "</div>";
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // ---------------- Favorites (persistent simple file) ----------------
        final Object favLock = new Object();
        final Path favPath = Paths.get("favorites.txt");
        final class FavStore { java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>(); }
        final FavStore fav = new FavStore();

        // Load favorites at startup
        try {
            if (Files.exists(favPath)) {
                for (String line : Files.readAllLines(favPath, StandardCharsets.UTF_8)) {
                    String t = line == null ? "" : line.trim().toUpperCase();
                    if (!t.isEmpty()) fav.items.add(t);
                }
            }
        } catch (Exception ignore) {}

        server.createContext("/favorites", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    respondHtml(ex, htmlPage(""), 200); return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("<div class='card'><div class='title'>Favorites</div>");
                synchronized (favLock) {
                    if (fav.items.isEmpty()) {
                        sb.append("<div style='color:#9ca3af'>No favorites saved yet.</div>");
                    } else {
                        sb.append("<div style='display:flex;flex-direction:column;gap:10px'>");
                        for (String t : fav.items) {
                            String esc = escapeHtml(t);
                            sb.append("<div style='display:flex;align-items:center;gap:10px'>")
                              .append("<span style='background:#0b1220;border:1px solid #1f2a44;border-radius:999px;padding:6px 10px;'>★ ")
                              .append(esc).append("</span>")
                              .append("<form method='post' action='/run-main' style='display:inline'>")
                              .append("<input type='hidden' name='symbol' value='"+esc+"'/>")
                              .append("<button type='submit'>Analyze</button></form>")
                              .append("<form method='post' action='/favorite-remove' style='display:inline'>")
                              .append("<input type='hidden' name='symbol' value='"+esc+"'/>")
                              .append("<button type='submit'>Remove</button></form>")
                              .append("</div>");
                        }
                        sb.append("</div>");
                    }
                }
                // Add manual add form
                sb.append("<form method='post' action='/favorite-add' style='margin-top:12px'>"+
                        "<input type='text' name='symbol' placeholder='Add ticker (e.g. AAPL)' required/> "+
                        "<button type='submit'>Add to Favorites</button></form>");
                sb.append("</div>");
                respondHtml(ex, htmlPage(sb.toString()), 200);
            }
        });

        server.createContext("/favorite-add", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean added = false;
                if (!t.isEmpty() && t.matches("[A-Z0-9.:-]{1,10}")) {
                    synchronized (favLock) { added = fav.items.add(t); try { Files.write(favPath, (String.join("\n", fav.items)+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {} }
                }
                ex.getResponseHeaders().add("Location", "/favorites?status="+(added?"added":"invalid_or_exists"));
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        server.createContext("/favorite-remove", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "");
                String t = sym == null ? "" : sym.trim().toUpperCase();
                boolean removed = false;
                synchronized (favLock) { removed = fav.items.remove(t); try { Files.write(favPath, (String.join("\n", fav.items)+"\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) {} }
                ex.getResponseHeaders().add("Location", "/favorites?status="+(removed?"removed":"not_found"));
                ex.sendResponseHeaders(303, -1); ex.close();
            }
        });

        // Render a simple SVG technical chart for a symbol: close, SMA(20), Bollinger(20,2)
        server.createContext("/chart", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                try {
                    String query = ex.getRequestURI().getQuery();
                    Map<String,String> q = new HashMap<>();
                    if (query != null) {
                        for (String p : query.split("&")) {
                            String[] kv = p.split("=",2);
                            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                            String v = kv.length>1? URLDecoder.decode(kv[1], StandardCharsets.UTF_8):"";
                            q.put(k,v);
                        }
                    }
                    String symbol = q.getOrDefault("symbol", "").trim();
                    int w = Math.max(300, Integer.parseInt(q.getOrDefault("w","800")));
                    int h = Math.max(150, Integer.parseInt(q.getOrDefault("h","300")));
                    int n = Math.max(30, Integer.parseInt(q.getOrDefault("n","120")));

                    if (symbol.isEmpty()) {
                        respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'><text x='10' y='20' fill='red'>symbol is required</text></svg>", 200);
                        return;
                    }

                    // Fetch data
                    DataFetcher.setTicker(symbol);
                    String json = DataFetcher.fetchStockData();
                    List<Double> closes = PriceJsonParser.extractClosingPrices(json);
                    if (closes == null || closes.size() < 30) {
                        String msg = PriceJsonParser.extractServiceMessage(json);
                        String label = (msg!=null? escapeHtml(msg): "insufficient data");
                        respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'><text x='10' y='20' fill='orange'>"+label+"</text></svg>", 200);
                        return;
                    }
                    int start = Math.max(0, closes.size()-n);
                    List<Double> sub = closes.subList(start, closes.size());
                    List<Double> sma = TechnicalAnalysisModel.calculateSMA(closes, 20);
                    List<Double> smaSub = sma.subList(Math.max(0, sma.size()-sub.size()), sma.size());
                    List<Double[]> bands = BollingerBands.calculateBands(closes, 20, 2.0);
                    List<Double[]> bandsSub = bands.subList(Math.max(0, bands.size()-sub.size()), bands.size());

                    // Compute scale
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (int i=0;i<sub.size();i++) {
                        Double c = sub.get(i);
                        if (c != null) {
                            min = Math.min(min, c);
                            max = Math.max(max, c);
                        }
                        Double[] b = (i<bandsSub.size()? bandsSub.get(i): null);
                        if (b!=null) {
                            if (b[2]!=null) min = Math.min(min, b[2]);
                            if (b[0]!=null) max = Math.max(max, b[0]);
                        }
                    }
                    if (max <= min) { max = min + 1.0; }
                    final int pad = 20;
                    final int plotW = w - pad*2;
                    final int plotH = h - pad*2;
                    StringBuilder pathPrice = new StringBuilder();
                    StringBuilder pathSma = new StringBuilder();
                    StringBuilder pathUpper = new StringBuilder();
                    StringBuilder pathLower = new StringBuilder();
                    double denom = Math.max(1.0, (double)(sub.size()-1));
                    for (int i=0;i<sub.size();i++) {
                        double x = pad + (plotW * (i/denom));
                        Double c = sub.get(i);
                        if (c != null) {
                            double yPrice = pad + plotH * (1 - ((c-min)/(max-min)));
                            if (pathPrice.length()==0) pathPrice.append("M").append(x).append(" ").append(yPrice);
                            else pathPrice.append(" L").append(x).append(" ").append(yPrice);
                        }

                        if (i < smaSub.size()) {
                            Double sv = smaSub.get(i);
                            if (sv != null) {
                                double ySma = pad + plotH * (1 - ((sv-min)/(max-min)));
                                if (pathSma.length()==0) pathSma.append("M").append(x).append(" ").append(ySma);
                                else pathSma.append(" L").append(x).append(" ").append(ySma);
                            }
                        }
                        if (i < bandsSub.size()) {
                            Double[] b = bandsSub.get(i);
                            if (b!=null && b[0]!=null && b[2]!=null) {
                                double yU = pad + plotH * (1 - ((b[0]-min)/(max-min)));
                                double yL = pad + plotH * (1 - ((b[2]-min)/(max-min)));
                                if (pathUpper.length()==0) pathUpper.append("M").append(x).append(" ").append(yU);
                                else pathUpper.append(" L").append(x).append(" ").append(yU);
                                if (pathLower.length()==0) pathLower.append("M").append(x).append(" ").append(yL);
                                else pathLower.append(" L").append(x).append(" ").append(yL);
                            }
                        }
                    }
                    String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='"+w+"' height='"+h+"'>"+
                            "<rect x='0' y='0' width='100%' height='100%' fill='#0b1220'/>"+
                            "<g stroke='#1f2a44' stroke-width='1'>"+
                            "<rect x='"+pad+"' y='"+pad+"' width='"+plotW+"' height='"+plotH+"' fill='none'/>"+
                            "</g>"+
                            // Bands, SMA, Close
                            "<path d='"+pathUpper+"' stroke='#8888ff' fill='none' stroke-width='1.5'/>"+
                            "<path d='"+pathLower+"' stroke='#8888ff' fill='none' stroke-dasharray='4,3' stroke-width='1.5'/>"+
                            "<path d='"+pathSma+"' stroke='#ffcc00' fill='none' stroke-width='1.5'/>"+
                            "<path d='"+pathPrice+"' stroke='#22c55e' fill='none' stroke-width='2'/>"+
                            // Title
                            "<text x='"+(pad+6)+"' y='"+(pad+16)+"' fill='#e5e7eb' font-size='12'>"+escapeHtml(symbol)+" (Close/SMA20/Bands)</text>"+
                            // Legend (Hebrew)
                            "<g font-size='12' fill='#e5e7eb'>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+30)+"' x2='"+(pad+40)+"' y2='"+(pad+30)+"' stroke='#22c55e' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+34)+"'>מחיר סגירה (ירוק)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+48)+"' x2='"+(pad+40)+"' y2='"+(pad+48)+"' stroke='#ffcc00' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+52)+"'>ממוצע נע 20 (צהוב)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+66)+"' x2='"+(pad+40)+"' y2='"+(pad+66)+"' stroke='#8888ff' stroke-width='2'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+70)+"'>רצועת בולינגר עליונה (כחול)</text>"+
                            "<line x1='"+(pad+10)+"' y1='"+(pad+84)+"' x2='"+(pad+40)+"' y2='"+(pad+84)+"' stroke='#8888ff' stroke-width='2' stroke-dasharray='4,3'/>"+
                            "<text x='"+(pad+46)+"' y='"+(pad+88)+"'>רצועת בולינגר תחתונה (כחול מקווקו)</text>"+
                            "</g>"+
                            "</svg>";
                    respondSvg(ex, svg, 200);
                } catch (Exception e) {
                    respondSvg(ex, "<svg xmlns='http://www.w3.org/2000/svg' width='600' height='120'><text x='10' y='20' fill='red'>"+escapeHtml(e.getMessage())+"</text></svg>", 200);
                }
            }
        });

        server.createContext("/run-main", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String, String> form = parseForm(body);
                String symbol = form.getOrDefault("symbol", "").trim();
                String result;
                boolean showChart = false;
                String overviewCard = "";
                if (symbol.isEmpty()) {
                    result = "No symbol provided";
                } else {
                    try {
                        DataFetcher.setTicker(symbol);

                        // Try to fetch company overview (best-effort, independent of pricing data source)
                        try {
                            String ovJson = DataFetcher.fetchCompanyOverview(symbol);
                            ObjectMapper om = new ObjectMapper();
                            JsonNode root = om.readTree(ovJson);
                            JsonNode nameN = root.get("Name");
                            if (nameN != null && nameN.isTextual()) {
                                String name = nameN.asText("");
                                String sector = root.path("Sector").asText("");
                                String industry = root.path("Industry").asText("");
                                String mcap = root.path("MarketCapitalization").asText("");
                                String pe = root.path("PERatio").asText("");
                                String pb = root.path("PriceToBookRatio").asText("");
                                String desc = root.path("Description").asText("");
                                if (desc != null && desc.length() > 380) {
                                    desc = desc.substring(0, 380) + "...";
                                }
                                StringBuilder ov = new StringBuilder();
                                ov.append("<div class='card'><div class='title'>Company Overview</div>");
                                ov.append("<div style='color:#e5e7eb;margin-bottom:6px;'><b>")
                                        .append(escapeHtml(name)).append("</b> ("+escapeHtml(symbol)+")</div>");
                                if (!sector.isEmpty() || !industry.isEmpty()) {
                                    ov.append("<div style='color:#9ca3af;margin-bottom:6px;'>")
                                      .append(escapeHtml(sector))
                                      .append(sector.isEmpty()||industry.isEmpty()?"":" · ")
                                      .append(escapeHtml(industry))
                                      .append("</div>");
                                }
                                String extra = "";
                                if (!mcap.isEmpty()) extra += "Market Cap: "+escapeHtml(formatMarketCap(mcap));
                                if (!pe.isEmpty()) extra += (extra.isEmpty()?"":" · ")+"P/E: "+escapeHtml(pe);
                                if (!pb.isEmpty()) extra += (extra.isEmpty()?"":" · ")+"P/B: "+escapeHtml(pb);
                                if (!extra.isEmpty()) ov.append("<div style='color:#9ca3af;margin-bottom:6px;'>").append(extra).append("</div>");
                                if (desc != null && !desc.isEmpty()) {
                                    ov.append("<div style='color:#cbd5e1;'>").append(escapeHtml(desc)).append("</div>");
                                }
                                ov.append("</div>");
                                overviewCard = ov.toString();
                            }
                        } catch (Exception ignore) { }
                        // Run the full analysis flow (which already handles data sufficiency and Finnhub fallback)
                        result = runAndCapture(() -> Main.main(new String[]{}));
                        showChart = true;
                    } catch (Exception e) {
                        result = "Error: " + e.getMessage();
                    }
                }
                String charts = "";
                if (!symbol.isEmpty() && showChart) {
                    String chartTag = String.format(
                            "<div class=\"card\"><div class=\"title\">גרף טכני</div><img src='//chart?symbol=%s&w=900&h=320&n=120' alt='chart'/></div>",
                            escapeHtml(symbol)
                    );
                    // Ensure correct path prefix
                    chartTag = chartTag.replace("//chart", "/chart");
                    charts = chartTag;
                }
                // Optional AI summary (uses OpenAI if OPENAI_API_KEY is set)
                String aiCard = "";
                try {
                    String aiSummary = summarizeWithOpenAI(result, symbol);
                    if (aiSummary != null && !aiSummary.isEmpty()) {
                        aiCard = "<div class='card'><div class='title'>AI Summary</div>" +
                                "<pre style='direction:ltr;text-align:left;unicode-bidi:plaintext;white-space:pre-wrap;word-break:break-word;'>" +
                                escapeHtml(aiSummary) + "</pre></div>";
                    }
                } catch (Exception ignore) {}

                String favCard = "";
                if (!symbol.isEmpty()) {
                    String esc = escapeHtml(symbol);
                    favCard = "<div class='card' style='padding:12px 16px;display:flex;align-items:center;gap:12px'>" +
                              "<form method='post' action='/favorite-add' style='margin:0'>" +
                              "<input type='hidden' name='symbol' value='"+esc+"'/>" +
                              "<button type='submit' title='Add to Favorites' style='display:inline-flex;align-items:center;gap:6px'>★ Add " + esc + "</button>" +
                              "</form>" +
                              "<div style='color:#9ca3af'>Save this symbol for quick access in Favorites</div>" +
                              "</div>";
                }

                // Persist last single-symbol analysis to disk (analyses/{symbol}.txt)
                try {
                    if (!symbol.isEmpty()) {
                        Path dir = Paths.get("analyses");
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve(symbol.toUpperCase() + ".txt"), result, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}

                String html = htmlPage(favCard + overviewCard + "<div class=\"card\"><div class=\"title\">Output</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + aiCard + charts
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/nasdaq-daily-top", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }

                String result;
                List<String> tickers;
                String err;
                try {
                    DailyPicksComputed computed = computeDailyNasdaqTopPicks();
                    result = computed.text;
                    tickers = computed.tickers;
                    err = computed.error;
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                    tickers = null;
                    err = e.getMessage();
                }

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (עד 5)</div>");
                    int count = Math.min(5, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }

                String meta = "";
                if (err != null && !err.isBlank()) {
                    meta = "<div class='card'><div class='title'>Last error</div><div style='color:#fca5a5'>"+escapeHtml(err)+"</div></div>";
                }

                String html = htmlPage(
                        "<div class=\"card\"><div class=\"title\">Daily Nasdaq Top 5 (GREEN)</div><pre>" +
                                escapeHtml(result) +
                                "</pre></div>" +
                                "<div class='card' style='padding:12px 16px;'>"+
                                "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>"+
                                "<form method='post' action='/nasdaq-daily-top' style='margin:0'><button type='submit'>Run Now</button></form>"+
                                "<a href='/nasdaq-daily-top-last'>Open Last Saved Daily Picks</a>"+
                                "</div></div>" +
                                meta +
                                gallery.toString()
                );
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/nasdaq-daily-top-last", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }

                LocalDate today = LocalDate.now();
                String result = null;
                List<String> tickers = null;
                String err = null;

                synchronized (dailyPicksLock) {
                    if (dailyPicksCache.date != null && dailyPicksCache.date.equals(today) && dailyPicksCache.text != null) {
                        result = dailyPicksCache.text + "\n(loaded from in-memory daily cache)";
                        tickers = dailyPicksCache.tickers;
                        err = dailyPicksCache.lastError;
                    }
                }

                if (result == null) {
                    Path fdir = Paths.get("finder-cache");
                    try {
                        Path r = fdir.resolve("daily-top-last.txt");
                        if (Files.exists(r)) result = Files.readString(r, StandardCharsets.UTF_8);
                        Path t = fdir.resolve("daily-top-last-tickers.txt");
                        if (Files.exists(t)) {
                            String s = Files.readString(t, StandardCharsets.UTF_8);
                            tickers = java.util.Arrays.asList(s.split(","));
                        }
                        Path e = fdir.resolve("daily-top-last-error.txt");
                        if (Files.exists(e)) err = Files.readString(e, StandardCharsets.UTF_8);
                    } catch (Exception ignore) {}
                }

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (saved)</div>");
                    int count = Math.min(10, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }

                String meta = "";
                if (err != null && !err.isBlank()) {
                    meta = "<div class='card'><div class='title'>Last error</div><div style='color:#fca5a5'>"+escapeHtml(err)+"</div></div>";
                }

                String card = (result==null)
                        ? "<div class='card'><div class='title'>Last Daily Top 5 Picks</div><div style='color:#9ca3af'>No saved results.</div></div>"
                        : "<div class='card'><div class='title'>Last Daily Top 5 Picks</div><pre>"+escapeHtml(result)+"</pre></div>";
                respondHtml(ex, htmlPage(card + meta + gallery.toString()), 200);
            }
        });

        // TOP_GAINERS_LOSERS market-wide movers from Alpha Vantage
        server.createContext("/top-gainers-losers", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }

                String json = null;
                try {
                    json = DataFetcher.fetchTopGainersLosers();
                } catch (Exception ignore) {}

                String content;
                if (json == null || json.isBlank()) {
                    content = "<div class='card'><div class='title'>TOP Gainers / Losers</div>" +
                              "<div style='color:#9ca3af'>No data returned from Alpha Vantage (rate limit or API error).</div></div>";
                } else {
                    try {
                        ObjectMapper om = new ObjectMapper();
                        JsonNode root = om.readTree(json);

                        StringBuilder sb = new StringBuilder();
                        sb.append("<div class='card'><div class='title'>TOP Gainers / Losers (Alpha Vantage)</div>");

                        // Helper lambda-like constructs are not possible, so we repeat small bits
                        sb.append(buildMoversTable(root.path("top_gainers"), "Top Gainers"));
                        sb.append(buildMoversTable(root.path("top_losers"), "Top Losers"));
                        sb.append(buildMoversTable(root.path("most_actively_traded"), "Most Actively Traded"));

                        sb.append("</div>");
                        content = sb.toString();
                    } catch (Exception e) {
                        String safe = escapeHtml(json);
                        if (safe.length() > 12000) {
                            safe = safe.substring(0, 12000) + "...";
                        }
                        content = "<div class='card'><div class='title'>TOP Gainers / Losers (raw JSON)</div>" +
                                  "<pre>" + safe + "</pre></div>";
                    }
                }

                String html = htmlPage(content);
                respondHtml(ex, html, 200);
            }
        });

        server.createContext("/recommendations", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String result;
                try {
                    result = runAndCapture(() -> LongTermCandidateFinder.main(new String[]{}));
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                // Embed up to 5 charts for last analyzed tickers
                StringBuilder gallery = new StringBuilder();
                List<String> recTickers = LongTermCandidateFinder.getLastTickers();
                if (recTickers != null && !recTickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (עד 5)</div>");
                    int count = Math.min(5, recTickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(recTickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }
                // Persist last finder results
                try {
                    Path fdir = Paths.get("finder-cache");
                    Files.createDirectories(fdir);
                    Files.writeString(fdir.resolve("last.txt"), result, StandardCharsets.UTF_8);
                    if (recTickers != null && !recTickers.isEmpty()) {
                        String joined = String.join(",", recTickers);
                        Files.writeString(fdir.resolve("last-tickers.txt"), joined, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignore) {}

                String html = htmlPage("<div class=\"card\"><div class=\"title\">Nasdaq stock recommendations</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + gallery.toString()
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        // View last single-symbol saved report
        server.createContext("/report", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { respondHtml(ex, htmlPage(""), 200); return; }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "").trim().toUpperCase();
                String content;
                if (sym.isEmpty()) { content = "<div class='card'><div class='title'>Saved Report</div><div style='color:#fca5a5'>No symbol provided.</div></div>"; }
                else {
                    Path file = Paths.get("analyses").resolve(sym+".txt");
                    if (Files.exists(file)) {
                        String txt = Files.readString(file, StandardCharsets.UTF_8);
                        content = "<div class='card'><div class='title'>Saved Report: "+escapeHtml(sym)+"</div><pre>"+escapeHtml(txt)+"</pre></div>";
                    } else {
                        content = "<div class='card'><div class='title'>Saved Report</div><div style='color:#9ca3af'>No saved report for "+escapeHtml(sym)+".</div></div>";
                    }
                }
                respondHtml(ex, htmlPage(content), 200);
            }
        });

        // View last Finder results from disk
        server.createContext("/finder-last", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { respondHtml(ex, htmlPage(""), 200); return; }
                Path fdir = Paths.get("finder-cache");
                String result = null;
                java.util.List<String> tickers = null;
                try {
                    Path r = fdir.resolve("last.txt");
                    if (Files.exists(r)) result = Files.readString(r, StandardCharsets.UTF_8);
                    Path t = fdir.resolve("last-tickers.txt");
                    if (Files.exists(t)) {
                        String s = Files.readString(t, StandardCharsets.UTF_8);
                        tickers = java.util.Arrays.asList(s.split(","));
                    }
                } catch (Exception ignore) {}

                StringBuilder gallery = new StringBuilder();
                if (tickers != null && !tickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים (saved)</div>");
                    int count = Math.min(10, tickers.size());
                    for (int i=0;i<count;i++) {
                        String t = escapeHtml(tickers.get(i));
                        gallery.append("<div style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                }
                String card = (result==null)?
                        "<div class='card'><div class='title'>Last Finder Results</div><div style='color:#9ca3af'>No saved results.</div></div>"
                        : "<div class='card'><div class='title'>Last Finder Results</div><pre>"+escapeHtml(result)+"</pre></div>";
                respondHtml(ex, htmlPage(card + gallery.toString()), 200);
            }
        });

        // Analysts page: form + results using Finnhub (cached 24h)
        server.createContext("/analysts", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
                    String content = "<div class='card'><div class='title'>Analysts</div>"+
                            "<form method='post' action='/analysts'>"+
                            "<input type='text' name='symbol' placeholder='e.g. AAPL' required /> "+
                            "<div style='margin-top:10px;'>"+
                            "<label style='margin-right:10px;'><input type='radio' name='source' value='A' checked /> A (Finnhub consensus)</label>"+
                            "<label><input type='radio' name='source' value='B' /> B (Alpha Vantage proxy)</label>"+
                            "</div>"+
                            "<button type='submit' style='margin-top:10px;'>Show</button></form>"+
                            "<div style='color:#9ca3af;margin-top:6px;'>A: Finnhub analyst consensus with 24h cache. B: heuristic proxy from Alpha Vantage (no real analyst consensus endpoint).</div>"+
                            "</div>";
                    respondHtml(ex, htmlPage(content), 200);
                    return;
                }
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String body = readBody(ex);
                Map<String,String> form = parseForm(body);
                String sym = form.getOrDefault("symbol", "").trim().toUpperCase();
                String source = form.getOrDefault("source", "A").trim().toUpperCase();
                if (!source.equals("A") && !source.equals("B")) source = "A";

                String aChecked = source.equals("A") ? "checked" : "";
                String bChecked = source.equals("B") ? "checked" : "";
                String formHtml = "<div class='card'><div class='title'>Analysts</div>"+
                        "<form method='post' action='/analysts'>"+
                        "<input type='text' name='symbol' value='"+escapeHtml(sym)+"' placeholder='e.g. AAPL' required /> "+
                        "<div style='margin-top:10px;'>"+
                        "<label style='margin-right:10px;'><input type='radio' name='source' value='A' "+aChecked+" /> A (Finnhub consensus)</label>"+
                        "<label><input type='radio' name='source' value='B' "+bChecked+" /> B (Alpha Vantage proxy)</label>"+
                        "</div>"+
                        "<button type='submit' style='margin-top:10px;'>Show</button></form>"+
                        "</div>";

                String card;
                if (sym.isEmpty()) {
                    card = "<div class='card'><div class='title'>Analysts</div><div style='color:#9ca3af'>Enter a symbol to view.</div></div>";
                } else if (source.equals("B")) {
                    card = buildAnalystProxyAlphaVantageCard(sym);
                } else {
                    card = buildAnalystCard(sym);
                }
                respondHtml(ex, htmlPage(formHtml + card), 200);
            }
        });

        server.createContext("/portfolio-weekly", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                    respondHtml(ex, htmlPage(""), 200);
                    return;
                }
                String result;
                try {
                    String body = readBody(ex);
                    Map<String,String> form = parseForm(body);
                    boolean force = "1".equals(form.getOrDefault("force", "")) || "true".equalsIgnoreCase(form.getOrDefault("force", ""));
                    LocalDate today = LocalDate.now();
                    synchronized (portfolioCacheLock) {
                        if (force) {
                            portfolioCache.text = null;
                            portfolioCache.date = null;
                            try {
                                Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
                                try { Files.deleteIfExists(file); } catch (Exception ignore) {}
                            } catch (Exception ignore) {}
                        }

                        if (!force && portfolioCache.date != null && portfolioCache.date.equals(today) && portfolioCache.text != null) {
                            result = portfolioCache.text + "\n(הוצג מהמטמון היומי)";
                        } else {
                            // Try disk cache for today
                            Path file = weeklyCacheDir.resolve("weekly-" + today + ".txt");
                            if (!force && Files.exists(file)) {
                                String cached = Files.readString(file, StandardCharsets.UTF_8);
                                portfolioCache.text = cached;
                                portfolioCache.date = today;
                                result = cached + "\n(הוצג ממטמון קובץ יומי)";
                            } else {
                                // Compute fresh
                                String computed = runAndCapture(() -> {
                                    // Analyze full portfolio (may take longer on free tier)
                                    PortfolioWeeklySummary.setMaxTickers(-1);
                                    PortfolioWeeklySummary.configureThrottle(true, 12_500); // ~5 req/min
                                    PortfolioWeeklySummary.main(new String[]{});
                                });
                                portfolioCache.text = computed;
                                portfolioCache.date = today;
                                result = computed + (force ? "\n(רענון כפוי בוצע בתאריך " + today + ")" : "\n(עודכן במטמון היומי בתאריך " + today + ")");
                                // Write to disk for persistence
                                try { Files.writeString(file, computed, StandardCharsets.UTF_8); } catch (Exception ignore) {}
                            }
                        }
                    }
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                // Embed up to 5 charts for the tickers used in the last weekly run
                StringBuilder gallery = new StringBuilder();
                List<String> weeklyTickers = PortfolioWeeklySummary.getLastTickers();
                if (weeklyTickers != null && !weeklyTickers.isEmpty()) {
                    gallery.append("<div class=\"card\"><div class=\"title\">גרפים טכניים</div>");
                    gallery.append("<div id='weekly-gallery'>");
                    for (int i=0;i<weeklyTickers.size();i++) {
                        String t = escapeHtml(weeklyTickers.get(i));
                        String hidden = (i >= 5) ? " style='display:none;' class='extra-chart'" : "";
                        gallery.append("<div"+hidden+" style='margin-bottom:12px;'><div style='color:#9ca3af;margin:4px 0;'>"+t+"</div>");
                        gallery.append(String.format("<img src='/chart?symbol=%s&w=720&h=220&n=120' alt='chart'/></div>", t));
                    }
                    gallery.append("</div>");
                    if (weeklyTickers.size() > 5) {
                        gallery.append("<button id='toggle-weekly' style='margin-top:8px;'>Show all charts ("+weeklyTickers.size()+")</button>");
                        gallery.append("<script>(function(){var btn=document.getElementById('toggle-weekly');if(!btn)return;var expanded=false;btn.addEventListener('click',function(){expanded=!expanded;var extras=document.querySelectorAll('.extra-chart');for(var i=0;i<extras.length;i++){extras[i].style.display=expanded?'':'none';}btn.textContent=expanded?'Show less charts':'Show all charts ("+weeklyTickers.size()+")';});})();</script>");
                    }
                    gallery.append("</div>");
                }
                String forceBtn = "<div class='card' style='padding:12px 16px;'>"+
                        "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap;'>"+
                        "<form method='post' action='/portfolio-weekly' style='margin:0'>"+
                        "<input type='hidden' name='force' value='1'/>"+
                        "<button type='submit'>Force Refresh Weekly Report</button></form>"+
                        "<a href='/portfolio-manage' style='color:#93c5fd;text-decoration:none;'>Manage Portfolio</a>"+
                        "</div></div>";

                String html = htmlPage(forceBtn + "<div class=\"card\"><div class=\"title\">My Portfolio - Weekly</div><pre>" +
                        escapeHtml(result) + "</pre></div>" + gallery.toString()
                        + "<script>(function(){try{var AC=window.AudioContext||window.webkitAudioContext;var ctx=new AC();function beep(f,d,t){var o=ctx.createOscillator();var g=ctx.createGain();o.type='sine';o.frequency.value=f;o.connect(g);g.connect(ctx.destination);g.gain.setValueAtTime(0.0001,ctx.currentTime);g.gain.exponentialRampToValueAtTime(0.12,ctx.currentTime+0.02);o.start(t);g.gain.exponentialRampToValueAtTime(0.0001,t+d-0.05);o.stop(t+d);}var now=ctx.currentTime+0.05;beep(880,0.35,now);beep(1320,0.35,now+0.4);}catch(e){}})();</script>");
                respondHtml(ex, html, 200);
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Server running at http://localhost:8099/");

        startDailyNasdaqScheduler();
    }

    private static class DailyPicksComputed {
        final String text;
        final List<String> tickers;
        final String error;
        DailyPicksComputed(String text, List<String> tickers, String error) {
            this.text = text;
            this.tickers = tickers;
            this.error = error;
        }
    }

    private static DailyPicksComputed computeDailyNasdaqTopPicks() {
        LocalDate today = LocalDate.now();

        synchronized (dailyPicksLock) {
            if (dailyPicksCache.date != null && dailyPicksCache.date.equals(today) && dailyPicksCache.text != null) {
                return new DailyPicksComputed(dailyPicksCache.text + "\n(loaded from today's cache)", dailyPicksCache.tickers, dailyPicksCache.lastError);
            }
        }

        String computedText;
        List<String> computedTickers;
        String computedError = null;
        try {
            LongTermCandidateFinder.configureThrottle(true, 12_500);
            LongTermCandidateFinder.setRandomPoolSize(30);
            LongTermCandidateFinder.setMaxTickers(30);
            computedText = runAndCapture(() -> {
                System.out.println("--- 🎯 Daily Nasdaq Top 5 (GREEN) candidates ---");
                java.util.List<StockAnalysisResult> topCandidates = LongTermCandidateFinder.findBestLongTermBuys(5);
                System.out.println("\n| TICKER | PRICE    | טכני (כניסה)    | פונדמנטלי         | ADX (חוזק) |\n" +
                        "|--------|----------|-----------------|-------------------|-----------|");
                if (topCandidates.isEmpty()) {
                    System.out.println("No GREEN candidates found today (based on current filters). Try again later.");
                } else {
                    for (StockAnalysisResult r : topCandidates) {
                        System.out.println(r);
                    }
                }
            });
            computedTickers = LongTermCandidateFinder.getLastTickers();
        } catch (Exception e) {
            computedText = "Error: " + e.getMessage();
            computedTickers = null;
            computedError = e.getMessage();
        }

        synchronized (dailyPicksLock) {
            dailyPicksCache.date = today;
            dailyPicksCache.text = computedText;
            dailyPicksCache.tickers = computedTickers;
            dailyPicksCache.lastError = computedError;
        }

        // Persist for "last" viewing
        try {
            Path fdir = Paths.get("finder-cache");
            Files.createDirectories(fdir);
            Files.writeString(fdir.resolve("daily-top-last.txt"), computedText, StandardCharsets.UTF_8);
            if (computedTickers != null && !computedTickers.isEmpty()) {
                Files.writeString(fdir.resolve("daily-top-last-tickers.txt"), String.join(",", computedTickers), StandardCharsets.UTF_8);
            }
            if (computedError != null && !computedError.isBlank()) {
                Files.writeString(fdir.resolve("daily-top-last-error.txt"), computedError, StandardCharsets.UTF_8);
            } else {
                try { Files.deleteIfExists(fdir.resolve("daily-top-last-error.txt")); } catch (Exception ignore) {}
            }
            Files.writeString(fdir.resolve("daily-top-" + today + ".txt"), computedText, StandardCharsets.UTF_8);
        } catch (Exception ignore) {}

        return new DailyPicksComputed(computedText, computedTickers, computedError);
    }

    private static void startDailyNasdaqScheduler() {
        // Run once per day at 07:00 local time. Background job only (daemon).
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("daily-nasdaq-top-scheduler");
            return t;
        });

        long initialDelayMs = computeDelayToNextHour(7);
        long periodMs = TimeUnit.DAYS.toMillis(1);
        exec.scheduleAtFixedRate(() -> {
            try {
                System.out.println("[DailyNasdaqTop] refresh started");
                computeDailyNasdaqTopPicks();
            } catch (Exception e) {
                System.out.println("[DailyNasdaqTop] refresh error: " + e.getMessage());
                synchronized (dailyPicksLock) {
                    dailyPicksCache.lastError = e.getMessage();
                }
            }
            System.out.println("[DailyNasdaqTop] refresh finished");
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private static long computeDelayToNextHour(int hour) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next).toMillis();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatMarketCap(String raw) {
        try {
            double v = Double.parseDouble(raw);
            String[] units = {"", "K", "M", "B", "T"};
            int idx = 0;
            while (v >= 1000 && idx < units.length-1) { v /= 1000.0; idx++; }
            return String.format("%.2f%s", v, units[idx]);
        } catch (Exception e) { return raw; }
    }

    // Build an HTML table for Alpha Vantage TOP_GAINERS_LOSERS arrays
    private static String buildMoversTable(JsonNode arr, String title) {
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return "<div style='margin-top:10px;color:#9ca3af'>" + escapeHtml(title) + ": no data.</div>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-top:10px;'>");
        sb.append("<div style='font-weight:600;margin-bottom:6px;'>").append(escapeHtml(title)).append("</div>");
        sb.append("<div style='overflow-x:auto;'><table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr>")
          .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:left;'>Symbol</th>")
          .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:left;'>Name</th>")
          .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Price</th>")
          .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Change %</th>")
          .append("<th style='border-bottom:1px solid #1f2a44;padding:4px 6px;text-align:right;'>Volume</th>")
          .append("</tr></thead><tbody>");

        int maxRows = Math.min(25, arr.size());
        for (int i = 0; i < maxRows; i++) {
            JsonNode n = arr.get(i);
            String symbol = n.path("ticker").asText("");
            if (symbol.isEmpty()) symbol = n.path("symbol").asText("");
            String name = n.path("name").asText("");
            String price = n.path("price").asText("");
            if (price.isEmpty()) price = n.path("price_current").asText("");
            String changePct = n.path("change_percentage").asText("");
            if (changePct.isEmpty()) changePct = n.path("change_percent").asText("");
            String volume = n.path("volume").asText("");

            sb.append("<tr>")
              .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;'>").append(escapeHtml(symbol)).append("</td>")
              .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;'>").append(escapeHtml(name)).append("</td>")
              .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(price)).append("</td>")
              .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(changePct)).append("</td>")
              .append("<td style='border-bottom:1px solid #111827;padding:4px 6px;text-align:right;'>").append(escapeHtml(volume)).append("</td>")
              .append("</tr>");
        }

        sb.append("</tbody></table></div></div>");
        return sb.toString();
    }
}
