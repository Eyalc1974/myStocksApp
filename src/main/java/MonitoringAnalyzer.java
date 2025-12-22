import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MonitoringAnalyzer {
    private final MonitoringAlphaVantageClient client;

    public MonitoringAnalyzer(MonitoringAlphaVantageClient client) {
        this.client = client;
    }

    public MonitoringSnapshot analyze(String symbol) throws Exception {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) throw new IllegalArgumentException("symbol required");

        JsonNode daily = client.timeSeriesDaily(sym);
        List<Double> closes = extractCloses(daily);
        if (closes.size() < 6) {
            throw new RuntimeException("insufficient daily data");
        }
        double lastClose = closes.get(closes.size() - 1);

        Map<String, Double> returns = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> recs = new LinkedHashMap<>();

        for (int d : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            String k = String.valueOf(d);
            double r = pctReturn(closes, d);
            returns.put(k, r);
            double s = 0.0;
            s += clamp(r / 2.0, -2.0, 2.0);
            scores.put(k, s);
        }

        Map<String, Double> indicatorValues = new LinkedHashMap<>();
        Map<String, String> indicatorNotes = new LinkedHashMap<>();

        double[] temaPair = latestAndPrevFromSeries(client.temaWeeklyOpen(sym, 10), "Technical Analysis: TEMA", "TEMA");
        Double tema = temaPair == null ? null : temaPair[0];
        if (tema != null) {
            indicatorValues.put("TEMA_10_weekly_open", tema);
            String bias = lastClose > tema ? "BULLISH" : "BEARISH";
            indicatorNotes.put(
                    "TEMA_10_weekly_open",
                    "TEMA (Triple EMA) is a trend-smoothing moving average that reacts faster than a simple SMA. " +
                            "Interpretation: Close > TEMA suggests upward bias; Close < TEMA suggests downward bias. " +
                            "Current: close=" + String.format("%.4f", lastClose) + ", TEMA=" + String.format("%.4f", tema) + " => " + bias + "."
            );
        }

        double[] sarPair = latestAndPrevFromSeries(client.sarWeekly(sym, 0.05, 0.25), "Technical Analysis: SAR", "SAR");
        Double sar = sarPair == null ? null : sarPair[0];
        if (sar != null) {
            indicatorValues.put("SAR_weekly", sar);
            String trend = lastClose > sar ? "UPTREND" : "DOWNTREND";
            indicatorNotes.put(
                    "SAR_weekly",
                    "Parabolic SAR is a trend-following stop-and-reverse indicator (often used to trail stops). " +
                            "Interpretation: SAR below price supports an uptrend; SAR above price supports a downtrend. " +
                            "Current: close=" + String.format("%.4f", lastClose) + ", SAR=" + String.format("%.4f", sar) + " => " + trend + "."
            );
        }

        double[] atrPair = latestAndPrevFromSeries(client.atrDaily(sym, 14), "Technical Analysis: ATR", "ATR");
        Double atr = atrPair == null ? null : atrPair[0];
        if (atr != null) {
            indicatorValues.put("ATR_14_daily", atr);
            double atrPct = lastClose > 0 ? (atr / lastClose) * 100.0 : 0.0;
            indicatorNotes.put(
                    "ATR_14_daily",
                    "ATR (Average True Range) measures volatility, not direction. Higher ATR means wider typical daily swings. " +
                            "A common risk heuristic: stops/position sizing often reference ~1–3x ATR. " +
                            "Current: ATR=" + String.format("%.4f", atr) + " (" + String.format("%.2f", atrPct) + "% of price)."
            );
        }

        JsonNode bb = client.bbandsWeeklyClose(sym, 5, 3);
        Double bbUpper = latestFromSeries(bb, "Technical Analysis: BBANDS", "Real Upper Band");
        Double bbLower = latestFromSeries(bb, "Technical Analysis: BBANDS", "Real Lower Band");
        Double bbMid = latestFromSeries(bb, "Technical Analysis: BBANDS", "Real Middle Band");
        if (bbUpper != null) indicatorValues.put("BBANDS_upper", bbUpper);
        if (bbLower != null) indicatorValues.put("BBANDS_lower", bbLower);
        if (bbMid != null) indicatorValues.put("BBANDS_mid", bbMid);
        if (bbUpper != null || bbLower != null) {
            String pos = "";
            if (bbUpper != null && lastClose >= bbUpper) pos = "Close is AT/ABOVE the upper band (strong momentum, but can be overextended).";
            else if (bbLower != null && lastClose <= bbLower) pos = "Close is AT/BELOW the lower band (weakness, but can be oversold).";
            else if (bbMid != null && lastClose >= bbMid) pos = "Close is between mid and upper band (moderate bullish bias).";
            else if (bbMid != null) pos = "Close is between lower and mid band (moderate bearish/mean-reversion zone).";

            indicatorNotes.put(
                    "BBANDS",
                    "Bollinger Bands describe a volatility envelope around a moving average (middle band). " +
                            "Interpretation: price near upper band suggests strength; near lower band suggests weakness; mid band is the mean. " +
                            "Current: close=" + String.format("%.4f", lastClose) + ". " + pos
            );
        }

        double[] adPair = latestAndPrevFromSeries(client.adDaily(sym), "Technical Analysis: Chaikin A/D", "Chaikin A/D");
        Double ad = adPair == null ? null : adPair[0];
        if (ad != null) {
            indicatorValues.put("AD_daily", ad);
            String dir = directionText(adPair);
            indicatorNotes.put(
                    "AD_daily",
                    "Chaikin Accumulation/Distribution (A/D) estimates whether volume is flowing into (accumulation) or out of (distribution) the stock. " +
                            "Look for confirmation: rising A/D alongside rising price is constructive; falling A/D can warn of weakening demand. " +
                            (dir.isEmpty() ? "" : ("Recent direction: " + dir + "."))
            );
        }

        double[] obvPair = latestAndPrevFromSeries(client.obvWeekly(sym), "Technical Analysis: OBV", "OBV");
        Double obv = obvPair == null ? null : obvPair[0];
        if (obv != null) {
            indicatorValues.put("OBV_weekly", obv);
            String dir = directionText(obvPair);
            indicatorNotes.put(
                    "OBV_weekly",
                    "On-Balance Volume (OBV) is a cumulative volume indicator. If OBV trends up, it suggests buying pressure; if it trends down, selling pressure. " +
                            "Useful as confirmation/divergence vs price trend. " +
                            (dir.isEmpty() ? "" : ("Recent direction: " + dir + "."))
            );
        }

        Map<String, String> fundamentals = new LinkedHashMap<>();
        try {
            JsonNode ov = client.overview(sym);
            putIfText(fundamentals, "Name", ov, "Name");
            putIfText(fundamentals, "Sector", ov, "Sector");
            putIfText(fundamentals, "Industry", ov, "Industry");
            putIfText(fundamentals, "MarketCapitalization", ov, "MarketCapitalization");
            putIfText(fundamentals, "PERatio", ov, "PERatio");
            putIfText(fundamentals, "PriceToBookRatio", ov, "PriceToBookRatio");
            putIfText(fundamentals, "DividendYield", ov, "DividendYield");
        } catch (Exception ignore) {
        }

        Map<String, String> risk = new LinkedHashMap<>();
        if (atr != null) {
            if (lastClose > 0) {
                double atrPct = (atr / lastClose) * 100.0;
                risk.put("ATR_pct", String.format("%.2f", atrPct));
            }
        }

        Double newsScore = null;
        List<Map<String, String>> topNews = new ArrayList<>();
        try {
            JsonNode news = client.newsSentiment(sym);
            JsonNode feed = news.get("feed");
            if (feed != null && feed.isArray()) {
                double sum = 0.0;
                int cnt = 0;
                for (JsonNode it : feed) {
                    JsonNode s = it.get("overall_sentiment_score");
                    if (s != null && s.isTextual()) {
                        try {
                            sum += Double.parseDouble(s.asText());
                            cnt++;
                        } catch (Exception ignore2) {
                        }
                    }
                }
                if (cnt > 0) newsScore = sum / cnt;

                List<JsonNode> items = new ArrayList<>();
                for (JsonNode it : feed) items.add(it);
                items.sort(Comparator.comparing((JsonNode n) -> n.path("time_published").asText("")).reversed());
                for (int i = 0; i < Math.min(5, items.size()); i++) {
                    JsonNode it = items.get(i);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("title", it.path("title").asText(""));
                    row.put("url", it.path("url").asText(""));
                    row.put("summary", it.path("summary").asText(""));
                    row.put("sentiment", it.path("overall_sentiment_label").asText(""));
                    topNews.add(row);
                }
            }
        } catch (Exception ignore) {
        }

        for (int d : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            String k = String.valueOf(d);
            double score = scores.getOrDefault(k, 0.0);
            if (newsScore != null) score += clamp(newsScore * 2.0, -2.0, 2.0);

            double last = lastClose;
            if (tema != null) {
                if (last > tema) score += 0.8;
                else score -= 0.8;
            }
            if (sar != null) {
                if (last > sar) score += 0.6;
                else score -= 0.6;
            }
            if (bbUpper != null && bbLower != null) {
                if (last >= bbUpper) score += 0.3;
                if (last <= bbLower) score -= 0.3;
            }
            scores.put(k, score);

            String rec;
            if (score >= 1.2) rec = "BUY";
            else if (score <= -1.2) rec = "DROP";
            else rec = "HOLD";
            recs.put(k, rec);
        }

        return new MonitoringSnapshot(
                sym,
                Instant.now().toEpochMilli(),
                returns,
                recs,
                scores,
                indicatorValues,
                indicatorNotes,
                fundamentals,
                risk,
                newsScore,
                topNews
        );
    }

    private static String directionText(double[] pair) {
        if (pair == null || pair.length < 2) return "";
        double latest = pair[0];
        double prev = pair[1];
        if (Double.isNaN(prev)) return "";
        if (latest > prev) return "UP";
        if (latest < prev) return "DOWN";
        return "FLAT";
    }

    private static void putIfText(Map<String, String> out, String outKey, JsonNode root, String jsonKey) {
        if (root == null) return;
        JsonNode n = root.get(jsonKey);
        if (n != null && n.isTextual()) {
            String v = n.asText();
            if (v != null && !v.isBlank() && !v.equalsIgnoreCase("None")) out.put(outKey, v);
        }
    }

    private static double pctReturn(List<Double> closes, int days) {
        int n = closes.size();
        int idx = n - 1 - days;
        if (idx < 0) idx = 0;
        double now = closes.get(n - 1);
        double prev = closes.get(idx);
        if (prev == 0) return 0.0;
        return ((now - prev) / prev) * 100.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<Double> extractCloses(JsonNode daily) {
        List<Double> closes = new ArrayList<>();
        JsonNode ts = daily == null ? null : daily.get("Time Series (Daily)");
        if (ts == null || !ts.isObject()) return closes;
        List<Map.Entry<String, JsonNode>> items = new ArrayList<>();
        ts.fields().forEachRemaining(items::add);
        items.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, JsonNode> e : items) {
            JsonNode day = e.getValue();
            JsonNode c = day.get("4. close");
            if (c != null && c.isTextual()) {
                try {
                    closes.add(Double.parseDouble(c.asText()));
                } catch (Exception ignore) {
                }
            }
        }
        return closes;
    }

    private static Double latestFromSeries(JsonNode root, String seriesKey, String valueKey) {
        if (root == null) return null;
        JsonNode series = root.get(seriesKey);
        if (series == null || !series.isObject()) return null;
        String bestDate = null;
        JsonNode best = null;
        var it = series.fields();
        while (it.hasNext()) {
            var e = it.next();
            String k = e.getKey();
            if (bestDate == null || k.compareTo(bestDate) > 0) {
                bestDate = k;
                best = e.getValue();
            }
        }
        if (best == null) return null;
        JsonNode v = best.get(valueKey);
        if (v == null) return null;
        try {
            if (v.isTextual()) return Double.parseDouble(v.asText());
            if (v.isNumber()) return v.asDouble();
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double[] latestAndPrevFromSeries(JsonNode root, String seriesKey, String valueKey) {
        if (root == null) return null;
        JsonNode series = root.get(seriesKey);
        if (series == null || !series.isObject()) return null;

        String bestDate = null;
        String prevDate = null;
        JsonNode best = null;
        JsonNode prev = null;
        var it = series.fields();
        while (it.hasNext()) {
            var e = it.next();
            String k = e.getKey();
            if (bestDate == null || k.compareTo(bestDate) > 0) {
                prevDate = bestDate;
                prev = best;
                bestDate = k;
                best = e.getValue();
            } else if (prevDate == null || k.compareTo(prevDate) > 0) {
                prevDate = k;
                prev = e.getValue();
            }
        }

        if (best == null) return null;
        Double latest = parseSeriesValue(best.get(valueKey));
        if (latest == null) return null;
        Double prevVal = prev == null ? null : parseSeriesValue(prev.get(valueKey));
        return new double[]{latest, prevVal == null ? Double.NaN : prevVal};
    }

    private static Double parseSeriesValue(JsonNode v) {
        if (v == null) return null;
        try {
            if (v.isTextual()) return Double.parseDouble(v.asText());
            if (v.isNumber()) return v.asDouble();
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }
}
