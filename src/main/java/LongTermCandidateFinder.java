
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
            "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "AVGO", "COST", "PEP",
            "ADBE", "CSCO", "NFLX", "INTC", "AMD", "CMCSA", "TMUS", "AMGN", "QCOM", "TXN",
            "INTU", "HON", "ISRG", "AMAT", "BKNG", "VRTX", "ADI", "MDLZ", "GILD", "LRCX",
            "ADP", "REGN", "PANW", "SNPS", "MU", "KLAC", "CDNS", "MELI", "CSGP", "MAR",
            "PYPL", "ASML", "ORLY", "MNST", "MNST", "LULU", "CTAS", "NXPI", "ADSK", "PDD",
            "MAR", "WDAY", "MCHP", "KDP", "CPRT", "MRVL", "AEP", "BKR", "KHC", "DXCM",
            "IDXX", "PAYX", "ROST", "EXC", "LRCX", "TEAM", "AZN", "CTSH", "EA", "FTNT",
            "ODFL", "PCAR", "XEL", "ANSS", "WBD", "DLTR", "FAST", "EBAY", "MSTR", "SIRI",
            "ENPH", "ALGN", "JD", "ZM", "ZS", "DDOG", "LCID", "RIVN", "MRNA", "ILMN",
            "OKTA", "WDC", "DOCU", "MTCH", "SPLK", "GMAB", "TCOM", "BIIB", "ZLAB", "SGEN",
            "GPN", "VRSK", "VRSN", "CDW", "DLR", "SWKS", "CHTR", "CEG", "AKAM", "TER",
            "ZBRA", "WBA", "HST", "APP", "PENN", "DKNG", "HOOD", "AFRM", "UPST", "COIN",
            "PLTR", "U", "NET", "SNOW", "CRWD", "MDB", "DDOG", "FSLY", "MSTR", "TGTX",
            "RKLB", "IONQ", "QS", "SOFI", "LCID", "NKLA", "CHPT", "RUN", "FSLR", "SEDG",
            "FUTU", "TME", "LI", "NIO", "XPEV", "BZ", "GDS", "IQ", "VIPS", "LU",
            "ARM", "CART", "KVUE", "MNY", "BIRK", "RDDT", "ALAB", "CRNC", "BOX", "DBX",
            "DOCN", "PAGER", "PATH", "UI", "LOGI", "GRMN", "FIVN", "ESTC", "ZEN", "NEWR",
            "MNDY", "FROG", "JFrog", "ASAN", "SMAR", "DT", "QLYS", "TENB", "CYBR", "RPAY",
            "EXAS", "GH", "NTRA", "PACB", "BEAM", "EDIT", "CRSP", "FATE", "CLLS", "SANA",
            "NVAX", "BNTX", "PFE", "JNJ", "PFE", "WMT", "TGT", "HD", "LOW", "TJX",
            "DG", "DLTR", "FIVE", "OLLI", "ORLY", "AZO", "TSCO", "POOL", "RH", "W",
            "ABNB", "BKNG", "EXPE", "TRIP", "MAR", "HLT", "H", "CHH", "WYNN", "LVS",
            "MGM", "CZNC", "PENN", "DKNG", "BETZ", "AAL", "UAL", "DAL", "LUV", "ALK",
            "JBLU", "SAVE", "CSX", "NSC", "UNP", "FDX", "UPS", "JBHT", "KNX", "CHRW",
            "XPO", "GXO", "HUBG", "MAT", "HAS", "NTDOY", "SEGA", "EA", "TTWO", "RBLX",
            "MTCH", "BMBL", "GRPN", "YELP", "IAC", "ANGI", "Z", "ZG", "RDFN", "OPEN",
            "PATH", "AAOI", "CIEN", "LITE", "VIAV", "EXTR", "FFIV", "JNPR", "ANET", "ARISTA",
            "DELL", "HPQ", "STX", "WDC", "PSTG", "NTAP", "LNVGY", "TER", "ONT", "COHR",
            "JPM", "GS", "V", "BRK-B", "XOM", "CVX", "NEE", "LLY", "UNH", "PFE", "PG", "KO",
            "EL", "CAT", "BA", "GE", "SPY", "IWM", "GLD", "TLT","TSM", "NVO", "ASML", "SHOP", "UBER", "ABNB", "CRWD",
            "PLTR", "SNOW", "FSLR", "ENPH", "RIVN", "NIO", "BABA", "PDD", "CP", "DE", "FCX", "NEM", "BITO",
            "LVMUY", "NKE", "SBUX", "RTX", "LMT", "GD", "UPS", "FDX", "T", "VZ", "DIS", "NFLX", "SPOT", "SQ",
            "MSTR", "MARA", "CL", "KMB", "TGT", "DE", "CAT", "BA", "RTX", "LMT", "GD", "DIS", "SPOT", "SQ", "PYPL",
            "T", "VZ", "UPS", "FDX", "NKE", "PYPL", "MSTR", "MARA", "CL", "KMB", "TGT",
            "AAON","A","AA","AAL","AAMC","AAT","ABB","ABC","ABG","ABM",
            "ABR","ABT","ACET","ACI","ACM","ACN","ACT","ACY","ADAP","ADI",
            "ADM","ADP","ADS","ADSK","ADT","ADTN","ADUS","AE","AEE","AEIS",
            "AEL","AEO","AES","AET","AFG","AFL","AGCO","AGM","AGN","AGO",
            "AHT","AI","AIG","AIV","AIZ","AJG","AJRD","AKAM","AKR","AL",
            "ALB","ALC","ALCO","ALE","ALEX","ALG","ALK","ALL","ALLE","ALLG",
            "ALLEGI","ALLY","ALNY","ALP","ALSN","ALV","AMCR","AMD","AME","AMG",
            "AMN","AMP","AMR","AMRX","AMT","AMX","AN","ANCX","ANDV","ANET",
            "ANF","ANGI","ANH","ANIK","ANSS","ANTM","AON","AOS","AP","APA",
            "APD","APH","APLE","APO","APTV","ARE","ARG","ARI","ARLO","ARMK",
            "AROC","ARW","ASBC","ASH","ASIX","ASP","ASR","ASUR","ATEC","ATGE",
            "ATKR","ATO","ATR","ATRI","ATRO","ATTU","ATUS","AU","AUB","AUO",
            "AUS","AVA","AVAV","AVB","AVD","AVGO","AVLR","AVNS","AVT","AVY",
            "AWI","AWK","AXL","AXP","AXR","AXTA","AZO","AZZ","AACG","AADI","AAN","AAPL","AAT","AAU","ABCB","ABCL","ABEO","ABG",
            "ABIL","ABMD","ABR","ABSI","ABT","ABTX","ACAD","ACER","ACI","ACIW",
            "ACLS","ACM","ACN","ACOR","ACRX","ACST","ACT","ACTG","ACU","ACVA",
            "ACXP","ADAP","ADES","ADIL","ADM","ADMA","ADNT","ADP","ADPT","ADRE",
            "ADRO","ADS","ADSEY","ADSW","ADUS","ADVM","ADX","AE","AEE","AEF",
            "AEGN","AEHR","AEL","AEO","AEP","AERI","AES","AESE","AEY","AFBI",
            "AFFC","AFG","AFI","AFIN","AFL","AFMD","AFRM","AFSI","AGBA","AGCB",
            "AGCO","AGEN","AGFS","AGI","AGIO","AGM","AGM.A","AGNC","AGO","AGR",
            "AGRI","AGRO","AGS","AGTC","AGTI","AGX","AHC","AHH","AHH.A","AHPI",
            "AHT","AI","AIII","AIKI","AIM","AIN","AINC","AIQ","AIR","AIRC",
            "AIRG","AIRS","AIT","AIU","AIV","AIZ","AJG","AJRD","AJX","AKAM",
            "AKRO","AKT","AKTS","AL","ALAC","ALB","ALBO","ALC","ALCO","ALEC",
            "ALG","ALGN","ALGT","ALHC","ALIT","ALK","ALL","ALLE","ALLY","ALNA",
            "ALOT","ALPA","ALP","ALRM","ALSN","ALT","ALTI","ALTO","ALTR","ALV",
            "ALVO","AMBC","AMCX","AMD","AME","AMED","AMG","AMH","AMK","AMKR",
            "AMN","AMR","AMRC","AMRH","AMRWW","AMRX","AMSC","AMSF","AMSWA","AMT"

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

                // קריטריון Market Regime: עוברת פילטר שוק (לא שוק דובי עם מניה חלשה)
                .filter(r -> r.passesMarketFilter == null || r.passesMarketFilter)

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

        // 3. דירוג (Ranking) - נדרג לפי Market Regime + פוטנציאל כניסה
        Collections.sort(longCandidates, (a, b) -> {
            // דירוג עדיפות 1: Relative Strength גבוה יותר (מנצחת את השוק)
            Double rsA = a.relativeStrength3M != null ? a.relativeStrength3M : 1.0;
            Double rsB = b.relativeStrength3M != null ? b.relativeStrength3M : 1.0;
            int rsComparison = Double.compare(rsB, rsA); // יורד - גבוה יותר עדיף
            if (rsComparison != 0) return rsComparison;

            // דירוג עדיפות 2: Market Regime Bonus גבוה יותר
            Integer bonusA = a.marketRegimeBonus != null ? a.marketRegimeBonus : 0;
            Integer bonusB = b.marketRegimeBonus != null ? b.marketRegimeBonus : 0;
            int bonusComparison = Integer.compare(bonusB, bonusA); // יורד - גבוה יותר עדיף
            if (bonusComparison != 0) return bonusComparison;

            // דירוג עדיפות 3: נמוך ב-ADX (מנוחה, כדי לקנות לפני הזינוק)
            int adxComparison = Double.compare(a.adxStrength, b.adxStrength);
            if (adxComparison != 0) return adxComparison;

            // דירוג עדיפות 4: קרוב יותר לשווי הוגן (יותר בטוח)
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

        // Market Regime bonus - מניות שמנצחות את השוק מקבלות עדיפות
        if (r.relativeStrength3M != null && r.relativeStrength3M > 1.0) s += 2;
        if (r.relativeStrength3M != null && r.relativeStrength3M > 1.15) s += 2; // בונוס נוסף למובילות
        if (r.highGrowth != null && r.highGrowth) s += 2; // בונוס צמיחה
        if (r.passesMarketFilter != null && !r.passesMarketFilter) s -= 3; // קנס למי שלא עוברת פילטר

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