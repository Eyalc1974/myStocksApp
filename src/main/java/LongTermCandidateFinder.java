
import java.util.*;
import java.util.stream.Collectors;

public class LongTermCandidateFinder {

    private static volatile boolean VERBOSE = false;

    public static void setVerbose(boolean verbose) {
        VERBOSE = verbose;
    }

    // רשימת מניות גדולה יותר לבחירה אקראית (מומלץ להגדיר רשימה משלך)
// רשימת ה-100 סימולים של נאסדא"ק לשימוש ב-TickerListFetcher.java
    private static final List<String> NASDAQ_100_TICKERS = Arrays.asList(
            "AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "TSLA", "AVGO", "COST", "PEP",
            "ADBE", "CSCO", "NFLX", "INTC", "AMGN", "QCOM", "TXN", "GILD", "CMCSA", "INTU",
            "AMD", "BKNG", "SBUX", "ISRG", "MDLZ", "ATVI", "FISV", "ADI", "VRTX", "CHTR",
            "MU", "LRCX", "SNPS", "REGN", "MNST", "KHC", "WBA", "BIDU", "CDNS", "EA",
            "MAR", "DLTR", "ASML", "BIIB", "MCHP", "JD", "IDXX", "WDC", "FAST", "PCAR",
            "ANSS", "ODFL", "XEL", "CEG", "SGEN", "EXC", "PAYX", "TCOM", "NXPI", "AEP",
            "BKR", "CPRT", "MRNA", "ROST", "CTAS", "ZS", "GPN", "FTNT", "CZR", "DOCU",
            "WDAY", "DXCM", "TEAM", "KLAC", "ILMN", "ALGN", "ZM", "LULU", "AZN", "CRWD",
            "PTON", "VRSK", "OKTA", "ZLAB", "LCID", "DDOG", "PENN", "ENPH", "RIVN",
            "MTCH", "SIRI", "ZM", "HBAN", "CTSH", "WMT", "MELI", "EBAY", "SIRI", "EXPE",
            "ADSK","AMAT","HON","TMUS","MRVL","AXON","ORCL","PANW","WDAY","OKTA","PYPL",
            "SNPS","DXCM","ILMN","ALGN","CRWD","LULU","AZN","PTON","ENPH","RIVN","MTCH",
            "SIRI","ZM","FTNT","TGTX","CSGP","CERN","SPLK","TTD","TEAM","FTNT","DLTR","CDNS",
            "MCHP","SGEN","XEL","AEP","BKR","CPRT","MRNA","ROST","CTAS","ZS","GPN","CZR",
            "DOCU","KLAC","CHTR","TTEK","EXAS","TRMB","FMC","FANG","WDC","PAYX","KDP","DLB",
            "ROP","FOXA","TCOM","BIIB","EXPE","ODFL","ASML","MU","WMT","HBAN","EBAY","LCID",
            "PENN","ZLAB","MAR","INTU","MTOR","CTRE","DLR","ORLY","HOLX","CEG","CDW","DDOG",
            "FSLY","MSTR","HUBS","SFIX","VRSN","TSCO","VRSK","CCEP","ADBE","MELI","CSCO","GILD",
            "CTSH","ANSS","QCOM","TXN","AMD"
    );
    private static final List<String> ALL_NASDAQ_TICKERS = NASDAQ_100_TICKERS;

    public static List<String> getUniverseTickers() {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String t : ALL_NASDAQ_TICKERS) {
            if (t == null) continue;
            String v = t.trim().toUpperCase();
            if (!v.isBlank()) uniq.add(v);
        }
        return new ArrayList<>(uniq);
    }

    // Throttling and batch size controls to respect Alpha Vantage free-tier limits
    private static boolean ENABLE_THROTTLE = true;
    private static long THROTTLE_MS = 12_500; // ~5 req/min
    private static int MAX_TICKERS = 5; // default analyze 5
    private static int RANDOM_POOL_SIZE = 5; // default random pool size

    public static void configureThrottle(boolean enable, long throttleMs) {
        ENABLE_THROTTLE = enable;
        THROTTLE_MS = Math.max(0, throttleMs);
    }

    public static void setMaxTickers(int max) {
        MAX_TICKERS = max;
    }

    public static void setRandomPoolSize(int poolSize) {
        RANDOM_POOL_SIZE = Math.max(1, poolSize);
    }

    // Keep last analyzed tickers for chart embedding
    private static List<String> LAST_TICKERS = new ArrayList<>();
    public static List<String> getLastTickers() {
        return new ArrayList<>(LAST_TICKERS);
    }

    /**
     * בוחר 5 מניות רנדומלית מתוך הרשימה הנתונה.
     */
    private static List<String> selectRandomTickers(int count) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String t : ALL_NASDAQ_TICKERS) {
            if (t == null) continue;
            String v = t.trim().toUpperCase();
            if (!v.isBlank()) uniq.add(v);
        }
        List<String> base = new ArrayList<>(uniq);
        if (base.size() <= count) {
            return base;
        }

        List<String> shuffled = new ArrayList<>(base);
        Collections.shuffle(shuffled, new Random());
        return shuffled.subList(0, count);
    }

    /**
     * מריץ את הסורק על מניות שנבחרו ומסנן את מועמדי הקנייה הטובים ביותר.
     * @param numCandidates מספר המועמדים הסופיים להצגה.
     * @return רשימה של StockAnalysisResult למניות המומלצות.
     */
    public static List<StockAnalysisResult> findBestLongTermBuys(int numCandidates) {
        return findBestLongTermBuys(numCandidates, true);
    }

    public static List<StockAnalysisResult> findBestLongTermBuys(int numCandidates, boolean allowFallback) {

        List<String> tickersToAnalyze = selectRandomTickers(RANDOM_POOL_SIZE);
        if (MAX_TICKERS > 0 && MAX_TICKERS < tickersToAnalyze.size()) {
            tickersToAnalyze = new ArrayList<>(tickersToAnalyze.subList(0, MAX_TICKERS));
        }
        List<StockAnalysisResult> allAnalyzedResults = new ArrayList<>();

        if (VERBOSE) {
            System.out.println("--- ⏳ מריץ ניתוח על " + tickersToAnalyze.size() + " מניות שנבחרו אקראית: " + tickersToAnalyze + " ---");
        }

        // 1. ריצת הניתוח המלא על כל מניה
        for (int i = 0; i < tickersToAnalyze.size(); i++) {
            String ticker = tickersToAnalyze.get(i);
            try {
                // קורא למתודה הקיימת של הסורק (הנחת עבודה שהועברה מ-StockScannerRunner)
                StockAnalysisResult result = StockScannerRunner.analyzeSingleStock(ticker);
                allAnalyzedResults.add(result);

                if (VERBOSE) {
                    System.out.printf("| %-6s | $%-8.2f | FV $%-8.2f | %-28s | %-18s | %-24s | ADX %.2f |%n",
                            result.ticker,
                            result.price,
                            result.dcfFairValue,
                            result.finalVerdict == null ? "" : result.finalVerdict,
                            result.technicalSignal == null ? "" : result.technicalSignal,
                            result.fundamentalSignal == null ? "" : result.fundamentalSignal,
                            result.adxStrength
                    );
                }
            } catch (Exception e) {
                System.err.println("שגיאה בניתוח " + ticker + ": " + e.getMessage());
                // נסה להציג הודעת שירות מ-Alpha Vantage (לרוב Rate Limit)
                try {
                    DataFetcher.setTicker(ticker);
                    String json = DataFetcher.fetchStockData();
                    String svc = PriceJsonParser.extractServiceMessage(json);
                    if (svc != null && !svc.isEmpty()) {
                        System.err.println("Alpha Vantage: " + svc);
                    }
                } catch (Exception ignore) { }
            }

            if (ENABLE_THROTTLE && i < tickersToAnalyze.size() - 1) {
                try { Thread.sleep(THROTTLE_MS); } catch (InterruptedException ignored) {}
            }
        }

        // 2. סינון קריטריונים מחמירים לטווח ארוך (החלטה משולבת)
        List<StockAnalysisResult> longCandidates = allAnalyzedResults.stream()
                // קריטריון פונדמנטלי: חייבת להיות מוערכת בחסר
                .filter(r -> r.fundamentalSignal.contains("STRONG BUY"))

                // קריטריון טכני (הימנעות מנפילה חדה): לא מועמדת לשורט או מכירה חזקה
                .filter(r -> !r.technicalSignal.contains("AVOID/STRONG SELL"))

                // קריטריון מומנטום: מחפש מניות שנותנות אות קנייה (היפוך/חוזק) או ניטרלי (מנוחה)
                .filter(r -> r.technicalSignal.contains("BUY") || r.technicalSignal.contains("NEUTRAL"))

                .collect(Collectors.toList());

        if (allAnalyzedResults.isEmpty()) {
            LAST_TICKERS = new ArrayList<>();
            return new ArrayList<>();
        }

        if (longCandidates.isEmpty()) {
            if (!allowFallback) {
                LAST_TICKERS = new ArrayList<>();
                return new ArrayList<>();
            }
            if (VERBOSE) {
                System.out.println("\nלא נמצאו מועמדים העומדים בקריטריונים המחמירים. מציג את הטובים ביותר לפי דירוג פנימי:");
            }
            // Fallback ranking: prefer BUY-ish final verdicts, then stronger fundamentals, then lower ADX.
            List<StockAnalysisResult> ranked = new ArrayList<>(allAnalyzedResults);
            ranked.sort((a, b) -> {
                int sa = scoreForFallback(a);
                int sb = scoreForFallback(b);
                int c = Integer.compare(sb, sa);
                if (c != 0) return c;
                return Double.compare(a.adxStrength, b.adxStrength);
            });
            List<StockAnalysisResult> top = ranked.stream().limit(Math.max(1, numCandidates)).collect(Collectors.toList());
            LAST_TICKERS = top.stream().map(r -> r.ticker).collect(Collectors.toList());
            return top;
        }

        // 3. דירוג (Ranking) - נדרג לפי פוטנציאל כניסה (נמוך ב-ADX, או קרוב ל-DIP)
        Collections.sort(longCandidates, (a, b) -> {
            // דירוג עדיפות 1: נמוך ב-ADX (מנוחה, כדי לקנות לפני הזינוק)
            int adxComparison = Double.compare(a.adxStrength, b.adxStrength);
            if (adxComparison != 0) return adxComparison;

            // דירוג עדיפות 2: קרוב יותר לשווי הוגן (יותר בטוח)
            return Double.compare(a.dcfFairValue, b.dcfFairValue);
        });

        // 4. החזרת חמשת המועמדים המובילים
        List<StockAnalysisResult> topCandidates = longCandidates.stream()
                .limit(numCandidates)
                .collect(Collectors.toList());

        // נעדכן את LAST_TICKERS כך שישקף רק את המועמדים הסופיים (ליישור עם הגרפים ב-WebServer)
        LAST_TICKERS = topCandidates.stream()
                .map(r -> r.ticker)
                .collect(Collectors.toList());

        return topCandidates;
    }

    private static int scoreForFallback(StockAnalysisResult r) {
        if (r == null) return Integer.MIN_VALUE;
        int s = 0;
        String fv = r.finalVerdict == null ? "" : r.finalVerdict;
        String fs = r.fundamentalSignal == null ? "" : r.fundamentalSignal;
        String ts = r.technicalSignal == null ? "" : r.technicalSignal;

        if (fv.contains("STRONG BUY")) s += 6;
        else if (fv.contains("BUY")) s += 4;
        else if (fv.contains("HOLD")) s += 2;
        else if (fv.contains("AVOID")) s -= 4;

        if (fs.contains("STRONG BUY")) s += 3;
        if (fs.contains("OVERVALUED") || fs.contains("DISTRESS")) s -= 3;

        if (ts.contains("BUY")) s += 2;
        if (ts.contains("STRONG SELL") || ts.contains("SELL")) s -= 2;

        if (r.dcfFairValue > 0 && r.price > 0 && r.price < r.dcfFairValue) s += 1;
        return s;
    }

    // ----------------------------------------------------------------------------------
    // *** מתודת main להרצה והצגת התוצאות ***
    // ----------------------------------------------------------------------------------
    public static void main(String[] args) {
        setVerbose(true);
        System.out.println("--- 🎯 מציאת 5 מועמדי Long Term Buy מובילים ---");

        // Finder output should stay compact: do not spam per-ticker Graham print blocks
        try { StockScannerRunner.setPrintGrahamDetails(false); } catch (Exception ignore) {}

        System.out.println("| TICKER | PRICE     | DCF FV     | FINAL VERDICT                 | TECHNICAL          | FUNDAMENTAL               | TREND | ");
        System.out.println("|--------|-----------|------------|------------------------------|--------------------|--------------------------|-------|");

        List<StockAnalysisResult> topCandidates = findBestLongTermBuys(5);

        if (topCandidates != null && !topCandidates.isEmpty()) {
            System.out.println("\nSelected tickers:");
            for (StockAnalysisResult r : topCandidates) {
                if (r == null) continue;
                System.out.println("- " + r.ticker);
            }
        }
    }
}