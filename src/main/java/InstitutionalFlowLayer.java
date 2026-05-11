import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 🏦 Institutional Flow Layer — bridges technical triggers with WHY money is flowing.
 *
 * Architecture:
 *   Layer 1 — Technical Trigger   (already exists in AIToolAgent)
 *   Layer 2 — Fundamental Strength (Alpha Vantage OVERVIEW → FundamentalData)
 *   Layer 3 — Catalyst Engine      (Alpha Vantage NEWS_SENTIMENT → CatalystData)
 *   Layer 4 — Institutional Footprint (computed from existing price/volume data)
 *
 * Final Conviction = technical * 0.35 + momentum * 0.20 + fundamentals * 0.25 + catalyst * 0.20
 * (scores are normalised to 0-10 before weighting, producing a 0-50 scale)
 */
public class InstitutionalFlowLayer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MonitoringAlphaVantageClient AV = MonitoringAlphaVantageClient.fromEnv();

    /** Maximum API calls we allow the layer to make per ticker in one pass. */
    private static final int MAX_CALLS_PER_TICKER = 2; // OVERVIEW + NEWS_SENTIMENT

    /**
     * Enrich the given IndicatorData with cached fundamental / catalyst data.
     * This is the FAST path — no network calls. Call for every ticker in the scan loop.
     */
    public static void enrichCached(AIToolAgent.IndicatorData data) {
        if (data == null || data.ticker == null) return;

        FundamentalData f = InstitutionalFlowCache.getFundamental(data.ticker);
        CatalystData    c = InstitutionalFlowCache.getCatalyst(data.ticker);

        data.fundamentalData = f;
        data.catalystData    = c;
    }

    /**
     * Fetch missing data from Alpha Vantage APIs and compute final scores.
     * This is the SLOW path — call ONLY for tickers that passed the technical threshold
     * so we don't burn API quota on garbage stocks.
     */
    public static void enrichAndScore(AIToolAgent.IndicatorData data,
                                      AIToolAgent.TradeDecision decision,
                                      boolean fetchIfMissing) {
        if (data == null || data.ticker == null || decision == null) return;

        // ── Layer 2: Fundamental Strength ──
        FundamentalData f = data.fundamentalData;
        if (f == null && fetchIfMissing) {
            f = fetchFundamental(data.ticker, data.currentPrice);
        }
        data.fundamentalData = f;
        decision.fundamentalScore = (f != null) ? f.computeScore(data.currentPrice) : 0;

        // ── Layer 3: Catalyst Engine ──
        CatalystData c = data.catalystData;
        if (c == null && fetchIfMissing) {
            c = fetchCatalyst(data.ticker);
        }
        data.catalystData = c;
        decision.catalystScore = (c != null) ? c.computeScore() : 0;

        // ── Layer 4: Institutional Footprint (computed from existing price/volume) ──
        decision.institutionalFlowScore = computeInstitutionalFootprint(data);

        // Pass raw data objects downstream for snapshot persistence
        decision.fundamentalData = data.fundamentalData;
        decision.catalystData    = data.catalystData;

        // ── Final Conviction (0-50) ──
        double technical   = normalize(decision.totalScore, 12);          // existing 0-12 score
        double momentum    = normalize(decision.momentumScore, 3);          // RSI/CCI component
        double fundamental = normalize(decision.fundamentalScore, 10);      // Layer 2
        double catalyst    = normalize(decision.catalystScore, 10);          // Layer 3

        decision.finalConviction =
              technical   * 1.75
            + momentum    * 1.0
            + fundamental * 1.25
            + catalyst    * 1.0;

        // Hard veto: if fundamental score is 0 or 1 and catalyst is also weak,
        // this is likely a random pump — slash conviction
        if (decision.fundamentalScore <= 1 && decision.catalystScore <= 2 && data.rvol < 2.0) {
            decision.finalConviction *= 0.5;
            decision.rejectReason = (decision.rejectReason == null ? "" : decision.rejectReason + " | ")
                + "IFL_VETO: garbage fundamentals + no catalyst";
        }
    }

    /** Compute Layer 4 — Institutional Footprint — purely from existing technical data. */
    private static int computeInstitutionalFootprint(AIToolAgent.IndicatorData d) {
        int score = 0;
        if (d.rvol >= 3.0 && d.todayChangePct > 1.5) score += 3; // unusual volume + strong move
        else if (d.rvol >= 2.0 && d.todayChangePct > 0.5) score += 2;
        else if (d.rvol >= 1.5) score += 1;

        // Trend intact + strong close = accumulation
        if (d.priceAboveSMA20 && d.priceAboveSMA50 && d.todayChangePct > 1.0) score += 2;
        else if (d.priceAboveSMA20 && d.todayChangePct > 0.5) score += 1;

        // Multi-day momentum (institutions don't buy in one spike)
        if (d.momentum20d > 10.0) score += 2;
        else if (d.momentum20d > 5.0) score += 1;

        // Avoid blow-off: if RSI > 80 it's likely retail FOMO, not institutional
        if (d.rsi > 80) score -= 2;
        else if (d.rsi > 75) score -= 1;

        return Math.max(0, Math.min(10, score));
    }

    // ── API fetch helpers ───────────────────────────────────────────────────

    private static FundamentalData fetchFundamental(String ticker, double currentPrice) {
        try {
            JsonNode overview = AV.overview(ticker);
            if (overview == null || overview.has("Information")) return null;
            FundamentalData d = FundamentalData.fromOverview(overview, currentPrice);
            InstitutionalFlowCache.putFundamental(ticker, d);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static CatalystData fetchCatalyst(String ticker) {
        try {
            JsonNode news = AV.newsSentiment(ticker);
            if (news == null || news.has("Information")) return null;
            CatalystData d = CatalystData.fromNewsSentiment(news, ticker);
            InstitutionalFlowCache.putCatalyst(ticker, d);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static double normalize(int value, int max) {
        if (max <= 0) return 0;
        return Math.min(1.0, Math.max(0, value / (double) max)) * 10.0;
    }

    /** Convenience: full pipeline for a single ticker (used by WebServer diagnostics). */
    public static AIToolAgent.TradeDecision scoreTicker(String ticker,
                                                         AIToolAgent.IndicatorData data,
                                                         AIToolAgent.TradeDecision decision) {
        enrichAndScore(data, decision, true);
        return decision;
    }
}
