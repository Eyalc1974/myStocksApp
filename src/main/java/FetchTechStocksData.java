import java.util.Arrays;
import java.util.List;

/**
 * Fetch technology stock data from Alpha Vantage API for the last 6 months
 * This data will be cached to market_data_cache.json for backtesting
 */
public class FetchTechStocksData {
    
    public static void main(String[] args) {
        // Focused list of major technology stocks
        List<String> techStocks = Arrays.asList(
            "AAPL", "MSFT", "GOOGL", "NVDA", "AMD", "INTC", "CSCO", "AVGO",
            "ADI", "TXN", "QCOM", "AMAT", "LRCX", "MU", "MRVL", "SNPS", "CDNS",
            "FTNT", "PANW", "CRWD", "NET", "DDOG", "SNAP", "META", "TSLA", "ORCL",
            "IBM", "CRM", "ADSK", "INTU", "NOW", "TEAM", "OKTA", "ZM", "SHOP",
            "SQ", "PLTR", "UBER", "LYFT", "RBLX", "DOCU", "COIN", "ABNB", "SPOT",
            "TWLO", "WDC", "STX", "HPE", "HPQ", "DELL", "SWKS", "QRVO", "MCHP",
            "ON", "NXPI", "TER", "KLAC", "ASML", "TSM", "SMCI", "ARM", "ANET",
            "GFS", "ENTG", "COHR", "IIIV", "LSCC", "MPWR", "POWI", "SLAB", "SYNA",
            "SIMO", "MXL", "DIOD", "AOSL", "VEEV", "WDAY", "SNOW", "DBX", "BOX",
            "ZS", "S", "CYBR", "AUTH", "PATH", "UPST", "AFRM", "LCID", "RIVN",
            "NIO", "XPEV", "LI", "ET", "FSLR", "ENPH", "SEDG", "RUN", "NOVA",
            "SPWR", "MAXN", "JKS", "CSIQ", "FSLY", "AKAM", "VRSN", "CHKP", "BAND",
            "VOC", "RING", "MGNI", "TTD", "APPN", "ADP", "PAYX", "GPN", "FIS",
            "FISV", "JKHY", "NCR", "BR", "BL", "AYX", "MDB", "ESTC", "CFLT",
            "DT", "NEWR", "ALTR", "ANSS", "KEYS", "GH", "ILMN", "PACB", "NTRA",
            "EXAS", "NVCR", "ARWR", "NTLA", "EDIT", "CRSP", "BEAM", "RGNX", "BLUE",
            "SGMO", "PGEN", "DNA", "TWST", "ARCT", "IMCR", "IMVT", "ARVN", "NXTC",
            "CABA", "RARE", "ACAD", "NBIX", "INCY"
        );
        
        System.out.println("Starting data fetch for " + techStocks.size() + " technology stocks");
        System.out.println("Fetching data for the last 6 months from Alpha Vantage API");
        System.out.println("This will take approximately " + (techStocks.size() * 36 / 60) + " minutes due to rate limiting");
        System.out.println();
        
        // Fetch data with monthsBack = 6 (last 6 months)
        MarketDataCache.fetchAndCacheData(techStocks, 6);
        
        System.out.println();
        System.out.println("Data collection complete!");
        System.out.println("Data saved to: market_data_cache.json");
        System.out.println("You can now run your agents across this cached data.");
    }
}
