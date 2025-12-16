
import java.util.*;
import java.util.stream.Collectors;

public class LongTermCandidateFinder {

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

    // Throttling and batch size controls to respect Alpha Vantage free-tier limits
    private static boolean ENABLE_THROTTLE = true;
    private static long THROTTLE_MS = 12_500; // ~5 req/min
    private static int MAX_TICKERS = 10; // default analyze 10
    private static int RANDOM_POOL_SIZE = 10; // default random pool size

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
        if (ALL_NASDAQ_TICKERS.size() <= count) {
            return ALL_NASDAQ_TICKERS;
        }

        List<String> shuffled = new ArrayList<>(ALL_NASDAQ_TICKERS);
        Collections.shuffle(shuffled, new Random());
        return shuffled.subList(0, count);
    }

    /**
     * מריץ את הסורק על מניות שנבחרו ומסנן את מועמדי הקנייה הטובים ביותר.
     * @param numCandidates מספר המועמדים הסופיים להצגה.
     * @return רשימה של StockAnalysisResult למניות המומלצות.
     */
    public static List<StockAnalysisResult> findBestLongTermBuys(int numCandidates) {

        List<String> tickersToAnalyze = selectRandomTickers(RANDOM_POOL_SIZE);
        if (MAX_TICKERS > 0 && MAX_TICKERS < tickersToAnalyze.size()) {
            tickersToAnalyze = new ArrayList<>(tickersToAnalyze.subList(0, MAX_TICKERS));
        }
        List<StockAnalysisResult> allAnalyzedResults = new ArrayList<>();

        System.out.println("--- ⏳ מריץ ניתוח על " + tickersToAnalyze.size() + " מניות שנבחרו אקראית: " + tickersToAnalyze + " ---");

        // 1. ריצת הניתוח המלא על כל מניה
        for (int i = 0; i < tickersToAnalyze.size(); i++) {
            String ticker = tickersToAnalyze.get(i);
            try {
                // קורא למתודה הקיימת של הסורק (הנחת עבודה שהועברה מ-StockScannerRunner)
                StockAnalysisResult result = StockScannerRunner.analyzeSingleStock(ticker);
                allAnalyzedResults.add(result);
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

    // ----------------------------------------------------------------------------------
    // *** מתודת main להרצה והצגת התוצאות ***
    // ----------------------------------------------------------------------------------
    public static void main(String[] args) {
        System.out.println("--- 🎯 מציאת 5 מועמדי Long Term Buy מובילים ---");

        List<StockAnalysisResult> topCandidates = findBestLongTermBuys(5);

        System.out.println("\n| TICKER | PRICE    | טכני (כניסה)    | פונדמנטלי         | ADX (חוזק) |");
        System.out.println("|--------|----------|-----------------|-------------------|-----------|");

        if (topCandidates.isEmpty()) {
            System.out.println("לא נמצאו מועמדים העומדים בקריטריונים המחמירים.");
        } else {
            for (StockAnalysisResult result : topCandidates) {
                System.out.println(result);
            }
        }
    }
}