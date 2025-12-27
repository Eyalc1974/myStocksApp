

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

        // Beneish (2-year annual inputs)
        Double beneishSales0;
        Double beneishSales1;
        Double beneishGrossProfit0;
        Double beneishGrossProfit1;
        Double beneishReceivables0;
        Double beneishReceivables1;
        Double beneishCurrentAssets0;
        Double beneishCurrentAssets1;
        Double beneishPpe0;
        Double beneishPpe1;
        Double beneishCashEq0;
        Double beneishCashEq1;
        Double beneishSga0;
        Double beneishSga1;
        Double beneishDep0;
        Double beneishDep1;
        Double beneishTotalAssets0;
        Double beneishTotalAssets1;
        Double beneishTotalLiabilities0;
        Double beneishTotalLiabilities1;
        Double beneishNetIncome0;
        Double beneishNetIncome1;
        Double beneishCfo0;
        String note;
    }

    private static Double firstNonNull(Double... values) {
        if (values == null) return null;
        for (Double v : values) {
            if (v != null && Double.isFinite(v)) return v;
        }
        return null;
    }

    private static Double safeDiv(Double a, Double b) {
        if (a == null || b == null) return null;
        if (!Double.isFinite(a) || !Double.isFinite(b)) return null;
        if (b == 0.0) return null;
        return a / b;
    }

    private static Double safeOneMinus(Double x) {
        if (x == null) return null;
        if (!Double.isFinite(x)) return null;
        return 1.0 - x;
    }

    private static Double computeBeneishMScoreFromFundamentals(FundamentalSnapshot fs) {
        if (fs == null) return null;

        Double sales0 = fs.beneishSales0;
        Double sales1 = fs.beneishSales1;
        Double recv0 = fs.beneishReceivables0;
        Double recv1 = fs.beneishReceivables1;
        Double gp0 = fs.beneishGrossProfit0;
        Double gp1 = fs.beneishGrossProfit1;
        Double ca0 = fs.beneishCurrentAssets0;
        Double ca1 = fs.beneishCurrentAssets1;
        Double ppe0 = fs.beneishPpe0;
        Double ppe1 = fs.beneishPpe1;
        Double ce0 = fs.beneishCashEq0;
        Double ce1 = fs.beneishCashEq1;
        Double sga0 = fs.beneishSga0;
        Double sga1 = fs.beneishSga1;
        Double dep0 = fs.beneishDep0;
        Double dep1 = fs.beneishDep1;
        Double ta0 = fs.beneishTotalAssets0;
        Double ta1 = fs.beneishTotalAssets1;
        Double tl0 = fs.beneishTotalLiabilities0;
        Double tl1 = fs.beneishTotalLiabilities1;
        Double ni0 = fs.beneishNetIncome0;
        Double cfo0 = fs.beneishCfo0;

        // Require minimum set of 2-year data
        if (sales0 == null || sales1 == null || sales0 == 0.0 || sales1 == 0.0) return null;
        if (recv0 == null || recv1 == null) return null;
        if (gp0 == null || gp1 == null) return null;
        if (ta0 == null || ta1 == null || ta0 == 0.0 || ta1 == 0.0) return null;
        if (tl0 == null || tl1 == null) return null;
        if (ni0 == null || cfo0 == null) return null;
        if (ca0 == null || ca1 == null) return null;
        if (ppe0 == null || ppe1 == null) return null;
        if (sga0 == null || sga1 == null) return null;
        if (dep0 == null || dep1 == null) return null;

        // DSRI
        Double dsri = safeDiv(safeDiv(recv0, sales0), safeDiv(recv1, sales1));

        // GMI: (GrossMargin_{t-1}/Sales_{t-1}) / (GrossMargin_t/Sales_t)
        Double gm0 = safeDiv(gp0, sales0);
        Double gm1 = safeDiv(gp1, sales1);
        Double gmi = safeDiv(gm1, gm0);

        // AQI
        Double sec0 = ce0 == null ? 0.0 : ce0;
        Double sec1 = ce1 == null ? 0.0 : ce1;
        Double aqiNum0 = safeOneMinus(safeDiv((ca0 + ppe0 + sec0), ta0));
        Double aqiNum1 = safeOneMinus(safeDiv((ca1 + ppe1 + sec1), ta1));
        Double aqi = safeDiv(aqiNum0, aqiNum1);

        // SGI
        Double sgi = safeDiv(sales0, sales1);

        // DEPI
        Double depRate0 = safeDiv(dep0, (dep0 + ppe0));
        Double depRate1 = safeDiv(dep1, (dep1 + ppe1));
        Double depi = safeDiv(depRate1, depRate0);

        // SGAI
        Double sgai = safeDiv(safeDiv(sga0, sales0), safeDiv(sga1, sales1));

        // LVGI
        Double lvgi = safeDiv(safeDiv(tl0, ta0), safeDiv(tl1, ta1));

        // TATA
        Double tata = safeDiv((ni0 - cfo0), ta0);

        if (dsri == null || gmi == null || aqi == null || sgi == null || depi == null || sgai == null || lvgi == null || tata == null) return null;
        if (!Double.isFinite(dsri) || !Double.isFinite(gmi) || !Double.isFinite(aqi) || !Double.isFinite(sgi)
                || !Double.isFinite(depi) || !Double.isFinite(sgai) || !Double.isFinite(lvgi) || !Double.isFinite(tata)) return null;

        return BeneishMScore.calculateMScore(dsri, gmi, aqi, sgi, depi, sgai, lvgi, tata);
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
                    JsonNode y1 = (arr.size() > 1) ? arr.get(1) : null;

                    fs.totalAssets = parseDouble(y0, "totalAssets");
                    fs.totalShareholderEquity = parseDouble(y0, "totalShareholderEquity");
                    fs.totalCurrentAssets = parseDouble(y0, "totalCurrentAssets");
                    fs.totalCurrentLiabilities = parseDouble(y0, "totalCurrentLiabilities");
                    fs.retainedEarnings = parseDouble(y0, "retainedEarnings");
                    fs.totalLiabilities = parseDouble(y0, "totalLiabilities");

                    fs.beneishTotalAssets0 = fs.totalAssets;
                    fs.beneishTotalLiabilities0 = fs.totalLiabilities;
                    fs.beneishCurrentAssets0 = fs.totalCurrentAssets;
                    fs.beneishReceivables0 = parseDouble(y0, "currentNetReceivables");
                    fs.beneishPpe0 = parseDouble(y0, "propertyPlantEquipment");
                    fs.beneishCashEq0 = firstNonNull(
                            parseDouble(y0, "cashAndCashEquivalentsAtCarryingValue"),
                            parseDouble(y0, "cashAndShortTermInvestments"),
                            parseDouble(y0, "cashAndCashEquivalents")
                    );

                    if (y1 != null) {
                        fs.beneishTotalAssets1 = parseDouble(y1, "totalAssets");
                        fs.beneishTotalLiabilities1 = parseDouble(y1, "totalLiabilities");
                        fs.beneishCurrentAssets1 = parseDouble(y1, "totalCurrentAssets");
                        fs.beneishReceivables1 = parseDouble(y1, "currentNetReceivables");
                        fs.beneishPpe1 = parseDouble(y1, "propertyPlantEquipment");
                        fs.beneishCashEq1 = firstNonNull(
                                parseDouble(y1, "cashAndCashEquivalentsAtCarryingValue"),
                                parseDouble(y1, "cashAndShortTermInvestments"),
                                parseDouble(y1, "cashAndCashEquivalents")
                        );
                    }
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
                    JsonNode y1 = (arr.size() > 1) ? arr.get(1) : null;

                    fs.ebit = parseDouble(y0, "ebit");
                    fs.totalRevenue = parseDouble(y0, "totalRevenue");
                    fs.netIncome = parseDouble(y0, "netIncome");

                    fs.beneishSales0 = fs.totalRevenue;
                    fs.beneishGrossProfit0 = parseDouble(y0, "grossProfit");
                    fs.beneishSga0 = firstNonNull(
                            parseDouble(y0, "sellingGeneralAndAdministrative"),
                            parseDouble(y0, "sellingGeneralAdministrative")
                    );
                    fs.beneishDep0 = firstNonNull(
                            parseDouble(y0, "depreciationAndAmortization"),
                            parseDouble(y0, "depreciationDepletionAndAmortization")
                    );
                    fs.beneishNetIncome0 = fs.netIncome;

                    if (y1 != null) {
                        fs.beneishSales1 = parseDouble(y1, "totalRevenue");
                        fs.beneishGrossProfit1 = parseDouble(y1, "grossProfit");
                        fs.beneishSga1 = firstNonNull(
                                parseDouble(y1, "sellingGeneralAndAdministrative"),
                                parseDouble(y1, "sellingGeneralAdministrative")
                        );
                        fs.beneishDep1 = firstNonNull(
                                parseDouble(y1, "depreciationAndAmortization"),
                                parseDouble(y1, "depreciationDepletionAndAmortization")
                        );
                        fs.beneishNetIncome1 = parseDouble(y1, "netIncome");
                    }
                }
            }
        } catch (Exception ignore) {}

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
                    fs.beneishCfo0 = parseDouble(y0, "operatingCashflow");
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

        try {
            Double mScore = computeBeneishMScoreFromFundamentals(fs);
            if (mScore != null && Double.isFinite(mScore)) {
                result.beneishMScore = mScore;
                result.beneishVerdict = BeneishMScore.getVerdict(mScore);
                result.beneishManipulator = mScore > -1.78;
            }
        } catch (Exception ignore) {
        }

        try {
            double netIncome = (fs != null && fs.netIncome != null) ? fs.netIncome : Double.NaN;
            double cashFlow = Double.NaN;
            if (fs != null) {
                if (fs.fcf != null) cashFlow = fs.fcf;
                else if (fs.beneishCfo0 != null) cashFlow = fs.beneishCfo0;
            }
            double totalAssets = (fs != null && fs.totalAssets != null) ? fs.totalAssets : Double.NaN;
            if (!Double.isNaN(netIncome) && !Double.isNaN(cashFlow) && !Double.isNaN(totalAssets) && totalAssets > 0) {
                double sloan = SloanAnalysis.calculateSloanRatio(netIncome, cashFlow, totalAssets);
                if (Double.isFinite(sloan)) {
                    result.sloanRatio = sloan;
                    result.sloanVerdict = SloanAnalysis.getVerdict(sloan);
                    result.sloanLowQuality = Math.abs(sloan) > 0.25;
                }
            }
        } catch (Exception ignore) {
        }

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

        // Risk gate: Beneish M-Score indicates possible manipulation
        if (result.beneishManipulator != null && result.beneishManipulator) {
            if (result.finalVerdict.contains("BUY") || result.finalVerdict.contains("HOLD")) {
                result.finalVerdict = "AVOID (Beneish M-Score)";
            }
        }

        // Risk gate: Sloan Ratio indicates low earnings quality (accruals)
        if (result.sloanLowQuality != null && result.sloanLowQuality) {
            if (result.finalVerdict.contains("BUY") || result.finalVerdict.contains("HOLD")) {
                result.finalVerdict = "AVOID (Sloan Ratio)";
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