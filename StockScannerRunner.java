

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Random;

public class StockScannerRunner {

    // רשימת מניות לדוגמה מהנסדא"ק לסריקה
    private static final List<String> NASDAQ_TICKERS = Arrays.asList(
            "AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "TSLA", "AVGO", "COST", "PEP",
            "ADBE", "CSCO", "NFLX", "INTC", "AMGN", "QCOM", "TXN", "GILD", "CMCSA", "INTU",
            "AMD", "BKNG", "SBUX", "ISRG", "MDLZ", "ATVI", "FISV", "ADI", "VRTX", "CHTR",
            "MU", "LRCX", "SNPS", "REGN", "MNST", "KHC", "WBA", "BIDU", "CDNS", "EA",
            "MAR", "DLTR", "ASML", "BIIB", "MCHP", "JD", "IDXX", "WDC", "FAST", "PCAR",
            "ANSS", "ODFL", "XEL", "CEG", "SGEN", "EXC", "PAYX", "TCOM", "NXPI", "AEP",
            "BKR", "CPRT", "MRNA", "ROST", "CTAS", "ZS", "GPN", "FTNT", "CZR", "DOCU",
            "WDAY", "DXCM", "TEAM", "KLAC", "ILMN", "ALGN", "ZM", "LULU", "AZN", "CRWD",
            "PTON", "VRSK", "OKTA", "ZLAB", "LCID", "DDOG", "PENN", "ENPH", "RIVN",
            "MTCH", "SIRI", "ZM", "HBAN", "CTSH", "WMT", "MELI", "EBAY", "SIRI", "EXPE"
    );

    // בוחר תת-קבוצה רנדומלית של Tickers מתוך הרשימה לעיל
    private static List<String> selectRandomTickers(int count) {
        if (NASDAQ_TICKERS.size() <= count) {
            return NASDAQ_TICKERS;
        }
        List<String> shuffled = new ArrayList<>(NASDAQ_TICKERS);
        Collections.shuffle(shuffled, new Random());
        return shuffled.subList(0, count);
    }

    // ----------------------------------------------------------------------------------
    // *** פונקציית העזר העיקרית: מבצעת ניתוח מלא עבור מניה בודדת ***
    // (הלוגיקה הזו הועברה ממתודת main המקורית של הקלאס Main)
    // ----------------------------------------------------------------------------------
    public static StockAnalysisResult analyzeSingleStock(String ticker) throws Exception {
        // *** הערה: יש לוודא שהקלאס DataFetcher מכיל מתודה סטטית setTicker(String) ***
        // *** או שתשנה את הקריאה לדרך שבה אתה מגדיר את ה-Ticker ב-DataFetcher. ***
        DataFetcher.setTicker(ticker);

        // 1. משיכת נתונים
        String jsonData = DataFetcher.fetchStockData();
        List<Double> historicalPrices = PriceJsonParser.extractClosingPrices(jsonData);
        List<Double> highPrices = PriceJsonParser.extractHighPrices(jsonData);
        List<Double> lowPrices = PriceJsonParser.extractLowPrices(jsonData);

        if (historicalPrices.size() < 30) {
            throw new Exception("חסר נתונים לחישובים מורכבים עבור " + ticker);
        }

        Double currentPrice = historicalPrices.get(historicalPrices.size() - 1);

        // 2. חישוב אינדיקטורים עיקריים
        List<Double> smaResults = TechnicalAnalysisModel.calculateSMA(historicalPrices, 20);
        List<Double> rsiResults = RSI.calculateRSI(historicalPrices, 14);
        List<Double[]> macdResults = MACD.calculateMACD(historicalPrices);
        List<Double[]> adxResults = ADX.calculateADX(highPrices, lowPrices, historicalPrices, 14);

        // 3. מיצוי הנתונים העיקריים (האחרונים)
        Double latestSMA = smaResults.get(smaResults.size() - 1);
        Double latestRSI = rsiResults.get(rsiResults.size() - 1);
        Double latestMACD = macdResults.get(macdResults.size() - 1)[0];
        Double latestSignalLine = macdResults.get(macdResults.size() - 1)[1];
        Double latestADX = adxResults.get(adxResults.size() - 1)[0];
        Double latestPlusDI = adxResults.get(adxResults.size() - 1)[1];
        Double latestMinusDI = adxResults.get(adxResults.size() - 1)[2];

        // 4. ניתוח פונדמנטלי (דורש נתונים דינמיים! כאן נתונים דמיוניים לבדיקה)
        double fairValue = DCFModel.calculateFairValue(500000000.0, 0.04, 0.12, 5, 0.02);
        double fairPricePerShare = fairValue / 10000000;
        double peRatio = 20.0;
        double growthRate = 20.0;
        double pegRatio = FundamentalAnalysis.calculatePEGRatio(peRatio, growthRate);

        // 5. יצירת אובייקט תוצאה
        StockAnalysisResult result = new StockAnalysisResult(ticker, currentPrice, fairPricePerShare, latestADX);

        // 6. לוגיקת החלטה ראשית לטווח הקצר (שורט / קנייה / מכירה)
        // ** שורט (מגמת ירידה חזקה): ** ADX חזק, ירידה ב-MACD, ומגמה יורדת (-DI > +DI)
        if (latestADX > 25 && latestMinusDI > latestPlusDI && latestMACD < latestSignalLine && currentPrice < latestSMA) {
            result.technicalSignal = "STRONG SELL/SHORT";
            // ** קנייה (היפוך ממצב Oversold): **
        } else if (latestRSI < 30 && latestMACD > latestSignalLine && currentPrice > latestSMA) {
            result.technicalSignal = "BUY on DIP";
            // ** מכירה (Overbought): **
        } else if (latestRSI > 70) {
            result.technicalSignal = "SELL/PROFIT TAKE";
        }

        // 7. לוגיקת החלטה לטווח הארוך
        if (currentPrice < fairPricePerShare && pegRatio <= 1.0) {
            result.fundamentalSignal = "STRONG BUY (Long Term)";
        } else if (currentPrice > fairPricePerShare * 1.5 || pegRatio > 2.0) {
            result.fundamentalSignal = "OVERVALUED (Avoid)";
        }

        // בתוך analyzeSingleStock:
// ... (לאחר חישוב כל המודלים והאינדיקטורים האחרונים) ...

// ===================================================================
// === שלב 8: לוגיקת ציון משולב (Final Verdict) ===
// ===================================================================

        // 8.1. ניקוד פונדמנטלי (המוקד שלך הוא Long, ניתן ציון גבוה)
        int fundamentalScore = 0;
        if (result.fundamentalSignal.contains("STRONG BUY")) {
            fundamentalScore += 3; // ערך גבוה מאוד
        } else if (result.fundamentalSignal.contains("OVERVALUED")) {
            fundamentalScore -= 3;
        }

        // 8.2. ניקוד טכני (קצר טווח - להימנע מנפילה מיידית)
        int technicalScore = 0;

        // אותות BUY חזקים (היפוך/כניסה)
        if (result.technicalSignal.contains("BUY on DIP") || result.technicalSignal.contains("BUY on STRENGTH")) {
            technicalScore += 2;
        }
        // אותות SELL חזקים (סיכון/שורט)
        else if (result.technicalSignal.contains("STRONG SELL") || result.technicalSignal.contains("SELL/PROFIT TAKE")) {
            technicalScore -= 2;
        }

        // 8.3. החלטה סופית משולבת

        if (fundamentalScore >= 3 && technicalScore >= 0) {
            result.finalVerdict = "STRONG BUY (Long Term Entry)"; // פונדמנטלי מעולה וטכני מאפשר כניסה
        } else if (fundamentalScore >= 3 && technicalScore < 0) {
            result.finalVerdict = "HOLD/WAIT (Strong Value, but Short-Term Weakness)"; // פונדמנטלי מעולה אך טכני חלש
        } else if (fundamentalScore < 0) {
            result.finalVerdict = "AVOID/SELL (Overvalued)"; // פונדמנטלי יקר
        } else {
            result.finalVerdict = "NEUTRAL (No clear edge)";
        }

        // ... (הקוד ממשיך ל-return result) ...

        return result;
    }

    // ----------------------------------------------------------------------------------
    // *** המתודה הראשית main: מריצה את הסורק ***
    // ----------------------------------------------------------------------------------
    public static void main(String[] args) {
        List<StockAnalysisResult> allResults = new ArrayList<>();

        // בוחרים 5 מניות באופן רנדומלי מתוך הרשימה
        List<String> tickersToScan = selectRandomTickers(5);

        System.out.println("--- 🔎 מודל סריקת נאסדא\"ק אלגוריתמי (5 מניות רנדומליות): " + tickersToScan + " ---");

        for (String ticker : tickersToScan) {
            try {
                StockAnalysisResult result = analyzeSingleStock(ticker);
                allResults.add(result);
            } catch (Exception e) {
                // הדפסת שגיאות רק למה שאינו קריטי (כדי לא לעצור את כל הסריקה)
                System.err.println("שגיאה בניתוח " + ticker + ": " + e.getMessage());
            }
        }

        // 9. הדפסת התוצאות המסכמות
        System.out.println("\n--- סיכום החלטות סורק אלגוריתמי ---");
        System.out.println("| TICKER | PRICE    | טכני (קצר/שורט) | פונדמנטלי (ארוך) | חוזק מגמה |");
        System.out.println("|--------|----------|------------------|------------------|-----------|");

        for (StockAnalysisResult result : allResults) {
            System.out.println(result);
        }

        // 10. פילטר למציאת מועמדים לשורט
        System.out.println("\n--- 🔴 מועמדים לשורט (Short Targets) ---");
        allResults.stream()
                .filter(r -> r.technicalSignal.equals("STRONG SELL/SHORT") && r.adxStrength > 25)
                .forEach(r -> System.out.println(r.ticker + ": " + r.technicalSignal + " (ADX: " + String.format("%.2f", r.adxStrength) + ")"));

        // 11. פילטר למציאת מועמדים לקנייה ארוכת טווח (Long Term Value)
        System.out.println("\n--- 🌟 מועמדים לקנייה (Long Term Value) ---");
        allResults.stream()
                .filter(r -> r.fundamentalSignal.equals("STRONG BUY (Long Term)") && r.technicalSignal.contains("BUY"))
                .forEach(r -> System.out.println(r.ticker + ": " + r.fundamentalSignal + " (Price: " + String.format("$%.2f", r.price) + ")"));

        // ...
// 9. הדפסת התוצאות המסכמות (מבנה טבלה חדש)
        System.out.println("\n--- 🎯 סיכום הניתוח המיידי (VERDICT) ---");
        System.out.println("| TICKER | PRICE | Verdict Finali | טכני | פונדמנטלי | ADX |");
        System.out.println("|--------|-------|------------------|-------|-------------|-----|");

        for (StockAnalysisResult result : allResults) {
            System.out.printf("| %-6s | $%-6.2f | %-16s | %-5s | %-11s | %.2f |%n",
                    result.ticker,
                    result.price,
                    result.finalVerdict,
                    result.technicalSignal,
                    result.fundamentalSignal,
                    result.adxStrength
            );
        }

// ... (אתה יכול לשמור על קטעי הפילטרים הקודמים) ...
    }
}