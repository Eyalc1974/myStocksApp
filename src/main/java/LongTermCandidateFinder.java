
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class LongTermCandidateFinder {

    private static volatile boolean VERBOSE = false;

    public static void setVerbose(boolean verbose) {
        VERBOSE = verbose;
    }

    // ======================= SECTOR-BASED TICKERS =======================
    
    public static final List<String> TECHNOLOGY_TICKERS = Arrays.asList(
        "AAPL","MSFT","NVDA","GOOGL","GOOG","META","AVGO","ADBE","CRM","ORCL",
        "AMD","INTC","QCOM","CSCO","IBM","NOW","SNOW","PANW","AMAT","MU",
        "LRCX","KLAC","TXN","ADI","NXPI","ASML","MRVL","WDAY","FTNT","ANET",
        "TEAM","ZS","OKTA","DDOG","MDB","NET","HPE","DELL","HPQ","STX",
        "WDC","ON","MPWR","MCHP","SWKS","QRVO","TER","CDNS","SNPS","CRWD",
        // Additional semiconductor stocks
        "ARM","TSM","COHR","TSEM","WOLF","STM","UMC","ASX","MX","ENT",
        // Additional AI/Cloud stocks
        "PLTR","AI","PATH","SOUN","UPST","ZM","DOCN","GH","NLOK"
    );

    public static final List<String> FINANCIALS_TICKERS = Arrays.asList(
        "JPM","BAC","WFC","C","GS","MS","V","MA","AXP","BLK",
        "SCHW","USB","PNC","TFC","COF","BK","STT","AIG","MET","PRU",
        "CB","TRV","ALL","AON","MMC","SPGI","MCO","ICE","CME","NDAQ",
        "MSCI","AMP","TROW","BKNG","DFS","SYF","FITB","HBAN","RF","CFG",
        "KEY","ZION","CINF","WRB","RJF","LPLA","PGR","BRO","AFL","UNM"
    );

    public static final List<String> HEALTHCARE_TICKERS = Arrays.asList(
        "JNJ","UNH","PFE","MRK","ABBV","LLY","AMGN","TMO","ABT","MDT",
        "BMY","GILD","CVS","CI","HUM","ZTS","ISRG","SYK","VRTX","REGN",
        "BSX","EW","BDX","IDXX","DXCM","BIIB","MRNA","ILMN","ALGN","RMD",
        "COO","HCA","IQV","WAT","STE","HOLX","TECH","CRL","INCY","UTHR",
        "NBIX","EXAS","VTRS","PODD","TFX","XRAY","DVA","DGX","LH","CTLT",
        "DHR","BAX","RGEN","MD","GH","ALXO","SGEN","ARGX","SRPT","PTCT"
    );

    public static final List<String> ENERGY_TICKERS = Arrays.asList(
        "XOM","CVX","COP","SLB","EOG","PXD","VLO","PSX","MPC","KMI",
        "OXY","HES","DVN","FANG","APA","HAL","BKR","NOV","OKE","WMB",
        "ET","EPD","MPLX","PAA","ENB","TRP","CNQ","SU","IMO","CVE",
        "AR","RRC","SWN","CHK","SM","MTDR","CTRA","EQT","MRO","OVV"
    );

    public static final List<String> INDUSTRIALS_TICKERS = Arrays.asList(
        "CAT","DE","HON","GE","BA","LMT","RTX","NOC","GD","EMR",
        "UPS","FDX","ETN","PH","ITW","CSX","NSC","UNP","WM","RSG",
        "ROK","DOV","XYL","PCAR","FAST","URI","ODFL","EXPD","CHRW","PAYX",
        "AME","GWW","CARR","IR","JCI","TT","MAS","AOS","PNR","ALLE",
        "MMM","TXT","COL","PHM","DHI","LEN","NVR","TOL","KBH","MDC"
    );

    public static final List<String> CONSUMER_DISCRETIONARY_TICKERS = Arrays.asList(
        "AMZN","TSLA","HD","LOW","MCD","NKE","SBUX","BKNG","TGT","TJX",
        "ROST","MAR","HLT","YUM","CMG","EBAY","ETSY","EXPE","LULU","DG",
        "DHI","LEN","PHM","NVR","POOL","TSCO","ULTA","BBY","DPZ","AZO",
        "F","GM","RCL","CCL","NCLH","WYNN","LVS","MGM","CZR","BYD"
    );

    public static final List<String> CONSUMER_STAPLES_TICKERS = Arrays.asList(
        "PG","KO","PEP","WMT","CL","KMB","MO","PM","EL","MDLZ",
        "COST","GIS","K","HSY","SJM","MKC","CHD","CLX","CPB","TSN",
        "KR","WBA","TAP","BF.B","STZ","KDP","CAG","LW","HRL","BG",
        "SYY","ADM","COTY","ELV","SPTN","FR","FLO","HAIN","POST","CL"
    );

    public static final List<String> UTILITIES_TICKERS = Arrays.asList(
        "NEE","DUK","SO","AEP","EXC","XEL","PEG","ED","D","PCG",
        "SRE","WEC","EIX","ES","AWK","AEE","CMS","CNP","EVRG","NRG"
    );

    public static final List<String> MATERIALS_TICKERS = Arrays.asList(
        "LIN","APD","SHW","FCX","NEM","DD","DOW","PPG","ECL","ALB",
        "VMC","MLM","NUE","STLD","X","CF","MOS","FMC","IFF","CE",
        "BHP","RIO","AA","GOLD","EMN","LYB","IP","PKG","AVY","SEE"
    );

    public static final List<String> REAL_ESTATE_TICKERS = Arrays.asList(
        "AMT","PLD","CCI","EQIX","SPG","O","PSA","WELL","DLR","VTR",
        "AVB","EQR","ESS","MAA","UDR","CPT","BXP","ARE","HST","PEAK",
        "VNO","SLG","KIM","FRT","REG","ADC","CBRE","PRO","EXR","VICI"
    );

    public static final List<String> COMMUNICATION_SERVICES_TICKERS = Arrays.asList(
        "DIS","NFLX","CMCSA","TMUS","VZ","T","CHTR","EA","TTWO","ROKU",
        "GOOGL","META","SNAP","PINS","MTCH","SPOT","WBD","PARA","FOX","FOXA",
        "LYV","OMC","IPG","DISH","S","LUMN","ATUS","CCOI","WIN","GLUU"
    );

    // Sector enum for configuration
    public enum Sector {
        TECHNOLOGY, FINANCIALS, HEALTHCARE, ENERGY, INDUSTRIALS,
        CONSUMER_DISCRETIONARY, CONSUMER_STAPLES, UTILITIES, MATERIALS,
        REAL_ESTATE, COMMUNICATION_SERVICES, NASDAQ_100
    }

    // Sector configuration with percentages (default: 100% NASDAQ_100)
    private static Map<Sector, Integer> sectorAllocation = new LinkedHashMap<>();
    static {
        sectorAllocation.put(Sector.NASDAQ_100, 100); // Default: use original NASDAQ_100
    }

    // Get tickers for a specific sector
    public static List<String> getTickersForSector(Sector sector) {
        switch (sector) {
            case TECHNOLOGY: return TECHNOLOGY_TICKERS;
            case FINANCIALS: return FINANCIALS_TICKERS;
            case HEALTHCARE: return HEALTHCARE_TICKERS;
            case ENERGY: return ENERGY_TICKERS;
            case INDUSTRIALS: return INDUSTRIALS_TICKERS;
            case CONSUMER_DISCRETIONARY: return CONSUMER_DISCRETIONARY_TICKERS;
            case CONSUMER_STAPLES: return CONSUMER_STAPLES_TICKERS;
            case UTILITIES: return UTILITIES_TICKERS;
            case MATERIALS: return MATERIALS_TICKERS;
            case REAL_ESTATE: return REAL_ESTATE_TICKERS;
            case COMMUNICATION_SERVICES: return COMMUNICATION_SERVICES_TICKERS;
            case NASDAQ_100:
            default: return NASDAQ_100_TICKERS;
        }
    }

    // Get tickers for a specific sector by string name (for WebServer compatibility)
    public static List<String> getSectorTickers(String sectorName) {
        if (sectorName == null || sectorName.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Sector sector = Sector.valueOf(sectorName.toUpperCase().replace(" ", "_"));
            return getTickersForSector(sector);
        } catch (IllegalArgumentException e) {
            // If sector name doesn't match enum, try case-insensitive matching
            String upperName = sectorName.toUpperCase();
            if (upperName.contains("TECH")) return TECHNOLOGY_TICKERS;
            if (upperName.contains("FINANCIAL")) return FINANCIALS_TICKERS;
            if (upperName.contains("HEALTH")) return HEALTHCARE_TICKERS;
            if (upperName.contains("ENERGY")) return ENERGY_TICKERS;
            if (upperName.contains("INDUSTRIAL")) return INDUSTRIALS_TICKERS;
            if (upperName.contains("DISCRETIONARY") || upperName.contains("CONSUMER DISCRETIONARY")) return CONSUMER_DISCRETIONARY_TICKERS;
            if (upperName.contains("STAPLES") || upperName.contains("CONSUMER STAPLES")) return CONSUMER_STAPLES_TICKERS;
            if (upperName.contains("UTILITY")) return UTILITIES_TICKERS;
            if (upperName.contains("MATERIAL")) return MATERIALS_TICKERS;
            if (upperName.contains("REAL ESTATE")) return REAL_ESTATE_TICKERS;
            if (upperName.contains("COMMUNICATION")) return COMMUNICATION_SERVICES_TICKERS;
            if (upperName.contains("NASDAQ")) return NASDAQ_100_TICKERS;
            return new ArrayList<>();
        }
    }

    // Configure sector allocation (percentages should sum to 100)
    public static void setSectorAllocation(Map<Sector, Integer> allocation) {
        sectorAllocation = new LinkedHashMap<>(allocation);
        System.out.println("[LongTermCandidateFinder] Sector allocation updated: " + sectorAllocation);
    }

    // Get current sector allocation
    public static Map<Sector, Integer> getSectorAllocation() {
        return new LinkedHashMap<>(sectorAllocation);
    }

    // Reset to default (100% NASDAQ_100)
    public static void resetSectorAllocation() {
        sectorAllocation.clear();
        sectorAllocation.put(Sector.NASDAQ_100, 100);
        System.out.println("[LongTermCandidateFinder] Sector allocation reset to default (100% NASDAQ_100)");
    }

    // Select tickers based on sector allocation and total count
    public static List<String> selectTickersBySectorAllocation(int totalCount) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        Random rand = new Random();
        
        int totalPct = sectorAllocation.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPct == 0) totalPct = 100;
        
        for (Map.Entry<Sector, Integer> entry : sectorAllocation.entrySet()) {
            Sector sector = entry.getKey();
            int pct = entry.getValue();
            if (pct <= 0) continue;
            
            int countForSector = Math.max(1, (totalCount * pct) / totalPct);
            List<String> sectorTickers = new ArrayList<>(getTickersForSector(sector));
            Collections.shuffle(sectorTickers, rand);
            
            int added = 0;
            for (String ticker : sectorTickers) {
                if (added >= countForSector) break;
                if (selected.add(ticker)) added++;
            }
            
            if (VERBOSE) {
                System.out.println("[Sector] " + sector + " (" + pct + "%) -> " + added + " tickers");
            }
        }
        
        return new ArrayList<>(selected);
    }

    /**
     * Reverse-lookup: returns the sector name for a given ticker.
     * Returns "OTHER" if the ticker is not found in any named sector list.
     */
    public static String getSectorForTicker(String ticker) {
        if (TECHNOLOGY_TICKERS.contains(ticker))             return "TECHNOLOGY";
        if (FINANCIALS_TICKERS.contains(ticker))             return "FINANCIALS";
        if (HEALTHCARE_TICKERS.contains(ticker))             return "HEALTHCARE";
        if (ENERGY_TICKERS.contains(ticker))                 return "ENERGY";
        if (INDUSTRIALS_TICKERS.contains(ticker))            return "INDUSTRIALS";
        if (CONSUMER_DISCRETIONARY_TICKERS.contains(ticker)) return "CONSUMER_DISCRETIONARY";
        if (CONSUMER_STAPLES_TICKERS.contains(ticker))       return "CONSUMER_STAPLES";
        if (UTILITIES_TICKERS.contains(ticker))              return "UTILITIES";
        if (MATERIALS_TICKERS.contains(ticker))              return "MATERIALS";
        if (REAL_ESTATE_TICKERS.contains(ticker))            return "REAL_ESTATE";
        if (COMMUNICATION_SERVICES_TICKERS.contains(ticker)) return "COMMUNICATION_SERVICES";
        return "OTHER";
    }

    // ======================= RS RANKING CACHE =======================
    // Max tickers to scan after the first run builds the RS cache.
    // Lower = faster scan. Raise if you want broader coverage.
    // With AV free tier (5/min): 120 tickers = ~24 min, 200 = ~40 min.
    static final int TOP_RS_COUNT = 120;

    // Skip tickers whose RS score from the last scan is below this threshold.
    // Stocks well below both MAs with negative momentum rarely trigger BUY signals.
    // Range: valid scores ~-14 to +18. Sentinel for no-data = -99.
    private static final double RS_MIN_THRESHOLD = -2.0;
    private static final java.util.concurrent.ConcurrentHashMap<String, Double> rsScoreCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Called by AIToolAgent after computing IndicatorData for each ticker.
     * Builds a Relative Strength ranking used to pre-filter the scan universe.
     * RS score = 20-day momentum + SMA position bonus + RVOL bonus.
     */
    public static void updateRSScore(String ticker, double score) {
        rsScoreCache.put(ticker, score);
    }

    /** Returns the number of tickers currently in the RS cache. */
    public static int getRSCacheSize() {
        return rsScoreCache.size();
    }

    /** Full universe — all sectors combined, no RS filter. */
    private static List<String> getAllSectorTickersRaw() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.addAll(TECHNOLOGY_TICKERS);
        all.addAll(FINANCIALS_TICKERS);
        all.addAll(HEALTHCARE_TICKERS);
        all.addAll(ENERGY_TICKERS);
        all.addAll(INDUSTRIALS_TICKERS);
        all.addAll(CONSUMER_DISCRETIONARY_TICKERS);
        all.addAll(CONSUMER_STAPLES_TICKERS);
        all.addAll(UTILITIES_TICKERS);
        all.addAll(MATERIALS_TICKERS);
        all.addAll(REAL_ESTATE_TICKERS);
        all.addAll(COMMUNICATION_SERVICES_TICKERS);
        return new ArrayList<>(all);
    }

    /**
     * Get scan universe.
     * - Cold cache (< 50 entries): returns full universe to build the RS ranking.
     * - Warm cache: returns top TOP_RS_COUNT tickers by RS score, filtered by RS_MIN_THRESHOLD.
     *   This skips stocks well below their MAs that rarely generate BUY signals.
     */
    public static List<String> getAllSectorTickers() {
        List<String> all = getAllSectorTickersRaw();
        if (rsScoreCache.size() < 50) {
            return all; // first scan — process everything to warm up the cache
        }
        return all.stream()
            .filter(t -> rsScoreCache.getOrDefault(t, -99.0) >= RS_MIN_THRESHOLD)
            .sorted((a, b) -> Double.compare(
                rsScoreCache.getOrDefault(b, -99.0),
                rsScoreCache.getOrDefault(a, -99.0)))
            .limit(TOP_RS_COUNT)
            .collect(Collectors.toList());
    }

    // Base directory for strategy files - configurable via environment variable
    // Default: "newStrategies" for local Windows development
    // Docker/Railway: Set DATA_DIR environment variable to appropriate path (e.g., "/app/data")
    private static final String DATA_DIR = System.getenv().getOrDefault("DATA_DIR", "newStrategies");
    private static final Path RS_CACHE_FILE = Paths.get(DATA_DIR, "rs-cache.json");

    /** Persist RS scores to disk so the next cold start also benefits from the filtered universe. */
    public static void persistRSCache() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            rsScoreCache.forEach((ticker, score) -> node.put(ticker, score));
            Files.createDirectories(RS_CACHE_FILE.getParent());
            Files.writeString(RS_CACHE_FILE, mapper.writeValueAsString(node));
        } catch (Exception ignored) {}
    }

    /** Load persisted RS scores from disk on startup (eliminates cold-start penalty after restarts). */
    public static void loadRSCache() {
        try {
            if (!Files.exists(RS_CACHE_FILE)) return;
            ObjectMapper mapper = new ObjectMapper();
            mapper.readTree(Files.readString(RS_CACHE_FILE))
                  .fields()
                  .forEachRemaining(e -> rsScoreCache.put(e.getKey(), e.getValue().asDouble()));
        } catch (Exception ignored) {}
    }

    // ======================= ORIGINAL NASDAQ_100 TICKERS =======================
    
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
        // Check if custom sector allocation is set (not just NASDAQ_100 at 100%)
        boolean useCustomSectors = !(sectorAllocation.size() == 1 && 
            sectorAllocation.containsKey(Sector.NASDAQ_100) && 
            sectorAllocation.get(Sector.NASDAQ_100) == 100);
        
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        
        if (useCustomSectors) {
            // Use only tickers from selected sectors
            for (Sector sector : sectorAllocation.keySet()) {
                List<String> sectorTickers = getTickersForSector(sector);
                for (String t : sectorTickers) {
                    if (t == null) continue;
                    String v = t.trim().toUpperCase();
                    if (!v.isBlank()) uniq.add(v);
                }
            }
            System.out.println("[LongTermCandidateFinder] Using custom sector allocation: " + sectorAllocation.keySet() + " -> " + uniq.size() + " tickers");
        } else {
            // Default: use NASDAQ_100
            for (String t : ALL_NASDAQ_TICKERS) {
                if (t == null) continue;
                String v = t.trim().toUpperCase();
                if (!v.isBlank()) uniq.add(v);
            }
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
        // Check if custom sector allocation is set (not just NASDAQ_100 at 100%)
        boolean useCustomSectors = !(sectorAllocation.size() == 1 && 
            sectorAllocation.containsKey(Sector.NASDAQ_100) && 
            sectorAllocation.get(Sector.NASDAQ_100) == 100);
        
        if (useCustomSectors) {
            return selectTickersBySectorAllocation(count);
        }
        
        // Original logic for NASDAQ_100
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