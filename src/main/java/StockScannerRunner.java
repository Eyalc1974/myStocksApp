

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Random;

public class StockScannerRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static volatile boolean PRINT_GRAHAM_DETAILS = false;

    public static void setPrintGrahamDetails(boolean print) {
        PRINT_GRAHAM_DETAILS = print;
    }

    private static Double parseDouble(JsonNode root, String key) {
        try {
            if (root == null) return null;
            JsonNode n = root.get(key);
            if (n == null) return null;
            String s = n.asText();
            if (s == null || s.isBlank() || "None".equalsIgnoreCase(s)) return null;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Long parseLong(JsonNode root, String key) {
        try {
            Double d = parseDouble(root, key);
            if (d == null) return null;
            return d.longValue();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static class FundamentalSnapshot {
        Double fcf;
        Double sharesOutstanding;
        Double epsTtm;
        Double bookValuePerShare;
        Double peRatio;
        Double revenueTtm;
        Double marketCap;
        Double totalDebt;
        Double cash;
        Double growthRate;
        // Altman Z inputs (latest annual)
        Double totalAssets;
        Double totalShareholderEquity;
        Double totalCurrentAssets;
        Double totalCurrentLiabilities;
        Double retainedEarnings;
        Double totalLiabilities;
        Double ebit;
        Double totalRevenue;
        Double netIncome;
        String note;
    }

    private static FundamentalSnapshot fetchFundamentals(String ticker) {
        FundamentalSnapshot fs = new FundamentalSnapshot();
        fs.note = "";
        try {
            String ovJson = DataFetcher.fetchCompanyOverview(ticker);
            if (ovJson != null && !ovJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(ovJson);
                if (svc != null && !svc.isBlank()) {
                    fs.note = svc;
                    return fs;
                }
                JsonNode ov = JSON.readTree(ovJson);
                Long shares = parseLong(ov, "SharesOutstanding");
                if (shares != null && shares > 0) fs.sharesOutstanding = shares.doubleValue();
                fs.epsTtm = parseDouble(ov, "EPS");
                fs.bookValuePerShare = parseDouble(ov, "BookValue");
                fs.peRatio = parseDouble(ov, "PERatio");
                fs.marketCap = parseDouble(ov, "MarketCapitalization");
                fs.revenueTtm = parseDouble(ov, "RevenueTTM");
                fs.totalDebt = parseDouble(ov, "TotalDebt");
                fs.cash = parseDouble(ov, "CashAndCashEquivalentsAtCarryingValue");

                Double revYoY = parseDouble(ov, "QuarterlyRevenueGrowthYOY");
                Double epsYoY = parseDouble(ov, "QuarterlyEarningsGrowthYOY");
                // Prefer revenue growth; fallback to earnings growth; clamp to sane bounds
                Double g = null;
                if (revYoY != null) g = revYoY;
                else if (epsYoY != null) g = epsYoY;
                if (g != null) {
                    // Overview fields are ratios (e.g. 0.12). Convert to annual growth rate.
                    fs.growthRate = clamp(g, -0.20, 0.35);
                }
            }
        } catch (Exception ignore) {
        }

        try {
            String cfJson = DataFetcher.fetchCashFlow(ticker);
            if (cfJson != null && !cfJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(cfJson);
                if (svc != null && !svc.isBlank()) {
                    fs.note = (fs.note == null || fs.note.isBlank()) ? svc : (fs.note + "; " + svc);
                    return fs;
                }
                JsonNode root = JSON.readTree(cfJson);
                JsonNode arr = root.path("annualReports");
                if (arr != null && arr.isArray() && arr.size() > 0) {
                    JsonNode y0 = arr.get(0);
                    Double ocf = parseDouble(y0, "operatingCashflow");
                    Double capex = parseDouble(y0, "capitalExpenditures");
                    if (ocf != null) {
                        double cpx = (capex == null) ? 0.0 : capex;
                        // capex may be negative in AV payloads; FCF = OCF - CapEx
                        fs.fcf = ocf - cpx;
                    }
                }
            }
        } catch (Exception ignore) {
        }

        // Fallback: if no growthRate from OVERVIEW, estimate from last two annual revenues (income statement)
        if (fs.growthRate == null) {
            try {
                String incJson = DataFetcher.fetchIncomeStatement(ticker);
                if (incJson != null && !incJson.isBlank()) {
                    String svc = PriceJsonParser.extractServiceMessage(incJson);
                    if (svc != null && !svc.isBlank()) {
                        fs.note = (fs.note == null || fs.note.isBlank()) ? svc : (fs.note + "; " + svc);
                        return fs;
                    }
                    JsonNode root = JSON.readTree(incJson);
                    JsonNode arr = root.path("annualReports");
                    if (arr != null && arr.isArray() && arr.size() > 1) {
                        Double rev0 = parseDouble(arr.get(0), "totalRevenue");
                        Double rev1 = parseDouble(arr.get(1), "totalRevenue");
                        if (rev0 != null && rev1 != null && rev1 != 0) {
                            double g = (rev0 - rev1) / rev1;
                            fs.growthRate = clamp(g, -0.20, 0.35);
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

        // Populate Altman Z inputs (Balance Sheet + Income Statement)
        try {
            String bsJson = DataFetcher.fetchBalanceSheet(ticker);
            if (bsJson != null && !bsJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(bsJson);
                if (svc != null && !svc.isBlank()) {
                    fs.note = (fs.note == null || fs.note.isBlank()) ? svc : (fs.note + "; " + svc);
                    return fs;
                }
                JsonNode root = JSON.readTree(bsJson);
                JsonNode arr = root.path("annualReports");
                if (arr != null && arr.isArray() && arr.size() > 0) {
                    JsonNode y0 = arr.get(0);
                    fs.totalAssets = parseDouble(y0, "totalAssets");
                    fs.totalShareholderEquity = parseDouble(y0, "totalShareholderEquity");
                    fs.totalCurrentAssets = parseDouble(y0, "totalCurrentAssets");
                    fs.totalCurrentLiabilities = parseDouble(y0, "totalCurrentLiabilities");
                    fs.retainedEarnings = parseDouble(y0, "retainedEarnings");
                    fs.totalLiabilities = parseDouble(y0, "totalLiabilities");
                }
            }
        } catch (Exception ignore) {}

        try {
            String incJson = DataFetcher.fetchIncomeStatement(ticker);
            if (incJson != null && !incJson.isBlank()) {
                String svc = PriceJsonParser.extractServiceMessage(incJson);
                if (svc != null && !svc.isBlank()) {
                    fs.note = (fs.note == null || fs.note.isBlank()) ? svc : (fs.note + "; " + svc);
                    return fs;
                }
                JsonNode root = JSON.readTree(incJson);
                JsonNode arr = root.path("annualReports");
                if (arr != null && arr.isArray() && arr.size() > 0) {
                    JsonNode y0 = arr.get(0);
                    fs.ebit = parseDouble(y0, "ebit");
                    fs.totalRevenue = parseDouble(y0, "totalRevenue");
                    fs.netIncome = parseDouble(y0, "netIncome");
                }
            }
        } catch (Exception ignore) {}

        return fs;
    }

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

        // 4. ניתוח פונדמנטלי (Alpha Vantage: OVERVIEW + CASH_FLOW + INCOME_STATEMENT)
        FundamentalSnapshot fs = fetchFundamentals(ticker);
        double fairPricePerShare = Double.NaN;
        double pegRatio = Double.NaN;
        double altmanZ = Double.NaN;
        String altmanVerdict = null;
        double grahamPrice = Double.NaN;
        String grahamVerdict = null;
        try {
            // DCF inputs
            double initialFcf = (fs.fcf != null) ? fs.fcf : Double.NaN;
            double shares = (fs.sharesOutstanding != null && fs.sharesOutstanding > 0) ? fs.sharesOutstanding : Double.NaN;
            double growth = (fs.growthRate != null) ? fs.growthRate : 0.04;
            double discount = 0.12;
            int years = 5;
            double terminalGrowth = 0.02;

            if (!Double.isNaN(initialFcf) && !Double.isNaN(shares) && shares > 0) {
                double fairValue = DCFModel.calculateFairValue(initialFcf, growth, discount, years, terminalGrowth);
                fairPricePerShare = fairValue / shares;
            }

            // PEG inputs
            Double pe = fs.peRatio;
            if (pe == null && fs.epsTtm != null && fs.epsTtm != 0) {
                pe = currentPrice / fs.epsTtm;
            }
            // FundamentalAnalysis.calculatePEGRatio expects growth in percent (e.g. 20), not ratio.
            double growthPct = clamp(growth * 100.0, 0.1, 35.0);
            if (pe != null && !Double.isNaN(pe)) {
                pegRatio = FundamentalAnalysis.calculatePEGRatio(pe, growthPct);
            }
        } catch (Exception ignore) {
        }

        // Altman Z-Score (risk / survivability)
        try {
            Double marketCap = fs.marketCap;
            if (marketCap != null
                    && fs.totalAssets != null && fs.totalAssets != 0
                    && fs.totalCurrentAssets != null
                    && fs.totalCurrentLiabilities != null
                    && fs.retainedEarnings != null
                    && fs.totalLiabilities != null && fs.totalLiabilities != 0
                    && fs.ebit != null
                    && fs.totalRevenue != null) {
                double workingCap = fs.totalCurrentAssets - fs.totalCurrentLiabilities;
                altmanZ = AltmanZScore.calculateZScore(
                        workingCap,
                        fs.totalAssets,
                        fs.retainedEarnings,
                        fs.ebit,
                        marketCap,
                        fs.totalLiabilities,
                        fs.totalRevenue
                );
                altmanVerdict = AltmanZScore.getVerdict(altmanZ);
            }
        } catch (Exception ignore) {
        }

        // Graham Number (conservative value price ceiling)
        try {
            if (fs.epsTtm != null && fs.bookValuePerShare != null) {
                grahamPrice = GrahamNumber.calculateGrahamPrice(fs.epsTtm, fs.bookValuePerShare);
                grahamVerdict = GrahamNumber.getVerdict(currentPrice, grahamPrice);

                if (PRINT_GRAHAM_DETAILS) {
                    System.out.println("\n--- 📝 מודל Graham Number (תקרת מחיר השקעת ערך) ---");
                    System.out.printf("המחיר המקסימלי לפי גרהם: $%.2f%n", grahamPrice);
                    System.out.printf("מחיר שוק נוכחי: $%.2f%n", currentPrice);
                    System.out.println("פסק דין: " + grahamVerdict);
                }
            }
        } catch (Exception ignore) {
        }

        if (PRINT_GRAHAM_DETAILS) {
            try {
                if (fs.netIncome != null && fs.totalRevenue != null && fs.totalAssets != null && fs.totalShareholderEquity != null) {
                    DuPontAnalysis.analyze(fs.netIncome, fs.totalRevenue, fs.totalAssets, fs.totalShareholderEquity);
                }
            } catch (Exception ignore) {
            }

            try {
                double pegFairPrice = Double.NaN;
                double growthPct = clamp(((fs.growthRate != null) ? fs.growthRate : 0.04) * 100.0, 0.1, 35.0);
                if (fs.epsTtm != null && fs.epsTtm > 0 && growthPct > 0) {
                    // PEG fair price heuristic: fair PE ~= growth% (when PEG ~= 1)
                    pegFairPrice = fs.epsTtm * growthPct;
                }

                double dcfPrice = (!Double.isNaN(fairPricePerShare) && fairPricePerShare > 0) ? fairPricePerShare : 0.0;
                double graham = (!Double.isNaN(grahamPrice) && grahamPrice > 0) ? grahamPrice : 0.0;
                double pegPrice = (!Double.isNaN(pegFairPrice) && pegFairPrice > 0) ? pegFairPrice : 0.0;

                if (dcfPrice > 0 || graham > 0 || pegPrice > 0) {
                    double weightedFairValue = ValuationEngine.getWeightedFairValue(dcfPrice, graham, pegPrice);
                    ValuationEngine.printSafetyMargin(currentPrice, weightedFairValue);
                }
            } catch (Exception ignore) {
            }
        }

        // 5. יצירת אובייקט תוצאה
        StockAnalysisResult result = new StockAnalysisResult(ticker, currentPrice, Double.isNaN(fairPricePerShare) ? 0.0 : fairPricePerShare, latestADX);

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
        if (!Double.isNaN(fairPricePerShare) && currentPrice < fairPricePerShare && !Double.isNaN(pegRatio) && pegRatio <= 1.0) {
            result.fundamentalSignal = "STRONG BUY (Long Term)";
        } else if (!Double.isNaN(fairPricePerShare) && (currentPrice > fairPricePerShare * 1.5 || (!Double.isNaN(pegRatio) && pegRatio > 2.0))) {
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

        // Graham Number influence (small weight): undervalued adds, overpriced subtracts
        if (!Double.isNaN(grahamPrice) && grahamPrice > 0) {
            if (currentPrice < grahamPrice) fundamentalScore += 1;
            else if (currentPrice > grahamPrice * 1.30) fundamentalScore -= 1;
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

        // 8.3. החלטה סופית משולבת (ללא נתוני חדשות/סנטימנט)

        if (fundamentalScore >= 3 && technicalScore >= 0) {
            result.finalVerdict = "STRONG BUY (Long Term Entry)"; // פונדמנטלי מעולה וטכני מאפשר כניסה
        } else if (fundamentalScore >= 3 && technicalScore < 0) {
            result.finalVerdict = "HOLD/WAIT (Strong Value, but Short-Term Weakness)"; // פונדמנטלי מעולה אך טכני חלש
        } else if (fundamentalScore < 0) {
            result.finalVerdict = "AVOID/SELL (Overvalued)"; // פונדמנטלי יקר
        } else {
            result.finalVerdict = "NEUTRAL (No clear edge)";
        }

        // Risk gate: if Altman Z indicates distress, do not allow BUY verdicts
        if (!Double.isNaN(altmanZ) && altmanZ < 1.81) {
            result.fundamentalSignal = "DISTRESS (Altman Z < 1.81)";
            if (result.finalVerdict.contains("BUY") || result.finalVerdict.contains("HOLD")) {
                result.finalVerdict = "AVOID (Financial Distress)";
            }
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