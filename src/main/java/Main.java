
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {

    // *** הערה: לצורך הרצה, יש להוסיף לקלאס Main מתודות עזר חסרות (High/Low) ***
    // מאחר שמשיכת נתוני High/Low דורשת שינויים ב-JsonParser, אנו משתמשים כאן ב-Close prices כנתוני ברירת מחדל (לא מדויק!)
    public static List<Double> fetchHighPrices(String jsonData) throws Exception {
        return PriceJsonParser.extractHighPrices(jsonData);
    }

    private static Double parseDouble(JsonNode root, String key) {
        try {
            JsonNode n = root.get(key);
            if (n == null || !n.isTextual()) return null;
            String s = n.asText();
            if (s == null || s.isEmpty() || s.equals("None")) return null;
            return Double.parseDouble(s);
        } catch (Exception e) { return null; }
    }

    private static String verdict(Double v) {
        if (v == null) return "N/A";
        return v > 0 ? "PASS" : "FAIL";
    }

    private static double calculateMomentum12m(List<Double> closes) {
        if (closes == null || closes.size() < 40) {
            if (closes == null || closes.size() < 20) return 0.0;
            double a = closes.get(closes.size()-1);
            double b = closes.get(closes.size()-21);
            if (b == 0) return 0.0;
            return (a - b) / b * 100.0;
        }
        int n = closes.size();
        int oneMonthAgo = Math.max(0, n - 21);
        int twelveMonthsAgo = Math.max(0, n - 252);
        double end = closes.get(oneMonthAgo);
        double start = closes.get(twelveMonthsAgo);
        if (start == 0) return 0.0;
        return (end - start) / start * 100.0;
    }

    private static double calculateMaxDrawdownPct(List<Double> closes) {
        if (closes == null || closes.isEmpty()) return 0.0;
        double peak = closes.get(0);
        double maxDD = 0.0;
        for (double v : closes) {
            peak = Math.max(peak, v);
            double dd = (peak - v) / peak;
            if (dd > maxDD) maxDD = dd;
        }
        return maxDD * 100.0;
    }
    public static List<Double> fetchLowPrices(String jsonData) throws Exception {
        return PriceJsonParser.extractLowPrices(jsonData);
    }
    // *** ודא ש-JsonParser עודכן עם המתודות extractHighPrices ו-extractLowPrices ***


    private static final String RLM = "\u200F"; // Right-to-Left Mark to enforce RTL rendering

    public static void main(String[] args) {

        // --- 1. משיכת וניתוח נתונים (שלב קריטי) ---
        List<Double> historicalPrices = null;
        List<Double> highPrices = null;
        List<Double> lowPrices = null;
        List<Long> volumeData = null;

        try {
            String jsonData = DataFetcher.fetchStockData();
            // מנתח את ה-JSON ומחלץ את המחירים (המתודה שסיפקתי ב-JsonParser)
            historicalPrices = PriceJsonParser.extractClosingPrices(jsonData);
            // מחלץ גם High/Low אם קיימים
            highPrices = PriceJsonParser.extractHighPrices(jsonData);
            lowPrices = PriceJsonParser.extractLowPrices(jsonData);
            volumeData = PriceJsonParser.extractVolumeData(jsonData);

            int avCount = (historicalPrices == null ? 0 : historicalPrices.size());
            System.out.println("[DEBUG] AlphaVantage closes count=" + avCount);
            String avSvc = PriceJsonParser.extractServiceMessage(jsonData);
            if (avSvc != null && !avSvc.isEmpty()) {
                System.out.println("[DEBUG] AlphaVantage service message=" + avSvc);
            }

        } catch (Exception e) {
            System.err.println("❌ שגיאה קריטית במשיכת נתונים: " + e.getMessage());
            return;
        }

        if (historicalPrices == null || historicalPrices.size() < 10) {
            try {
                String fhJson = DataFetcher.fetchDailyCandlesFromFinnhub();
                if (fhJson != null) {
                    List<Double> fhCloses = PriceJsonParser.extractClosingPricesFromFinnhub(fhJson);
                    int fhCount = (fhCloses == null ? 0 : fhCloses.size());
                    System.out.println("[DEBUG] Finnhub closes count=" + fhCount);
                    if (fhCloses != null && fhCloses.size() >= 10) {
                        historicalPrices = fhCloses;
                        // Attempt to also populate High/Low/Volume from Finnhub if available
                        List<Double> fhHigh = PriceJsonParser.extractHighPricesFromFinnhub(fhJson);
                        List<Double> fhLow = PriceJsonParser.extractLowPricesFromFinnhub(fhJson);
                        List<Long> fhVol = PriceJsonParser.extractVolumeFromFinnhub(fhJson);
                        if (fhHigh != null && !fhHigh.isEmpty()) highPrices = fhHigh;
                        if (fhLow != null && !fhLow.isEmpty()) lowPrices = fhLow;
                        if (fhVol != null && !fhVol.isEmpty()) volumeData = fhVol;
                    }
                }
            } catch (Exception e) {
                // if Finnhub fallback fails, log it and keep the original lists
                System.err.println("[DEBUG] Finnhub fallback failed: " + e.getMessage());
            }

            if (historicalPrices == null || historicalPrices.size() < 10) {
                System.err.println("❌ לא נמשכו מספיק נתונים (נדרש לפחות 10 לחישובים מורכבים).");
                return;
            }
        }

        // אם לא הצלחנו למשוך High/Low, נשתמש ב-Close (זה לא נכון מבחינה פיננסית, אבל מאפשר לקוד לרוץ)
        if (highPrices == null || highPrices.isEmpty()) {
            highPrices = historicalPrices;
            lowPrices = historicalPrices;
        }

        Double currentPrice = historicalPrices.get(historicalPrices.size() - 1);
        System.out.println("✅ נתונים נמשכו בהצלחה. מחיר סגירה עדכני: " + String.format("$%.2f", currentPrice));

        // Momentum 12-1 and Max Drawdown
        double momentum12mPct = calculateMomentum12m(historicalPrices);
        double maxDrawdownPct = calculateMaxDrawdownPct(historicalPrices);
        System.out.printf("\nמומנטום 12-1: %.2f%%%n", momentum12mPct);
        System.out.printf("שיא ירידה (Max Drawdown): %.2f%%%n", maxDrawdownPct);

        // --- News & Market Sentiment (Alpha Vantage NEWS_SENTIMENT) ---
        try {
            // TICKER כבר מוגדר ב-DataFetcher דרך DataFetcher.setTicker(...) לפני הקריאה ל-Main.main
            String newsJson = DataFetcher.fetchNewsSentiment("");

            System.out.println("\n--- 📰 News & Market Sentiment ---");
            if (newsJson != null && !newsJson.isBlank()) {
                String sentimentVerdict = SentimentModel.getSentimentVerdict(newsJson);
                String eventRisk = EventAnalysis.getEventRisk(newsJson);

                System.out.println("פסק-דין סנטימנט: " + sentimentVerdict);
                System.out.println("סיכון/קאטליסט מאירועים: " + eventRisk);
            } else {
                System.out.println("לא נמצאו נתוני חדשות/סנטימנט זמינים כרגע (או כשל ב-API).");
            }
        } catch (Exception e) {
            System.out.println("\n--- 📰 News & Market Sentiment ---");
            System.out.println("שגיאה בניתוח חדשות/סנטימנט (התעלמות): " + e.getMessage());
        }
        // ===================================================================
        // ======================== ניתוח טכני ===============================
        // ===================================================================

        // --- 2. SMA (ממוצע נע פשוט) ---
        int smaWindow = 20;
        List<Double> smaResults = TechnicalAnalysisModel.calculateSMA(historicalPrices, smaWindow);
        Double latestSMA = smaResults.get(smaResults.size() - 1);

        System.out.println("\n--- 📝 מודל SMA מודד את המגמה הממוצעת של המחיר בטווח הקרוב. ---"); // הסבר קצר
        System.out.println("--- 📈 ניתוח טכני: SMA ---");
        System.out.printf("SMA-%d אחרון: $%.2f%n", smaWindow, latestSMA);
        System.out.printf(RLM + "הסבר: אם המחיר הנוכחי ($%.2f) מעל הממוצע (SMA), המגמה נחשבת חיובית (Bullish). אם מתחתיו – שלילית/חלשה.%n", currentPrice);

        if (currentPrice > latestSMA) {
            System.out.println("🟢 אות SMA: קנייה (Bullish)");
        } else {
            System.out.println("🔴 אות SMA: מכירה/ניטרלי");
        }

        // --- 3. RSI (מדד חוזק יחסי) ---
        int rsiPeriod = 14;
        List<Double> rsiResults = RSI.calculateRSI(historicalPrices, rsiPeriod);
        Double latestRSI = rsiResults.get(rsiResults.size() - 1);

        System.out.println("\n--- 📝 מודל RSI מודד את עוצמת השינויים במחיר ומזהה מצבי קיצון. ---"); // הסבר קצר
        System.out.println("--- 📊 ניתוח טכני: RSI ---");
        System.out.printf("RSI-%d אחרון: %.2f%n", rsiPeriod, latestRSI);
        System.out.println(RLM + "הסבר: RSI נע בין 0 ל-100. מעל 70 = אזור קניות יתר (Overbought), מתחת ל-30 = אזור מכירות יתר (Oversold). ערכים באמצע = ניטרלי.");
        if (latestRSI > 70) {
            System.out.println("🚨 אות RSI: סיכון/מכירה - Overbought");
        } else if (latestRSI < 30) {
            System.out.println("🌟 אות RSI: קנייה - Oversold");
        } else {
            System.out.println("⚪️ אות RSI: ניטרלי");
        }

        // --- 4. MACD ---
        List<Double[]> macdResults = MACD.calculateMACD(historicalPrices);
        if (!macdResults.isEmpty()) {
            Double[] latestMacd = macdResults.get(macdResults.size() - 1);
            Double macdLine = latestMacd[0];
            Double signalLine = latestMacd[1];

            System.out.println("\n--- 📝 מודל MACD מודד את המומנטום ואת השינוי במגמה על ידי השוואת ממוצעים מעריכיים. ---"); // הסבר קצר
            System.out.println("--- 📈 ניתוח טכני: MACD ---");
            System.out.printf("קו MACD אחרון: %.4f%n", macdLine);
            System.out.printf("קו אות אחרון: %.4f%n", signalLine);
            System.out.println(RLM + "הסבר: כאשר קו ה-MACD מעל קו האות (Signal) מתפתח מומנטום חיובי; מתחת – מומנטום שלילי. הצלבות מסמנות שינוי מגמה אפשרי.");

            if (macdLine != null && signalLine != null && macdLine > signalLine) {
                System.out.println("🟢 אות MACD: קנייה (חצייה כלפי מעלה)");
            } else {
                System.out.println("🔴 אות MACD: מכירה/ניטרלי");
            }
        }

        // --- 5. Stochastic Oscillator ---
        List<Double[]> stochasticResults = Stochastic.calculateStochastic(
                historicalPrices, highPrices, lowPrices, 14, 3
        );
        if (!stochasticResults.isEmpty()) {
            Double[] latestStochastic = stochasticResults.get(stochasticResults.size() - 1);
            Double kLine = latestStochastic[0];
            Double dLine = latestStochastic[1];

            System.out.println("\n--- 📝 מודל סטוקסטיק משווה את מחיר הסגירה לטווח המחירים לאורך זמן. ---"); // הסבר קצר
            System.out.println("--- 📈 ניתוח טכני: Stochastic Oscillator ---");
            System.out.printf("%%K אחרון: %.2f%n", kLine);
            System.out.printf("%%D אחרון: %.2f%n", dLine);
            System.out.println(RLM + "הסבר: %K/%D נעים בין 0 ל-100. מעל 80 = אזור קניות יתר; מתחת 20 = אזור מכירות יתר. חצייה של %K את %D עשויה לרמוז היפוך.");

            if (kLine != null && dLine != null && kLine < 20 && kLine > dLine) {
                System.out.println("🌟 אות סטוקסטיק: קנייה (Oversold וחוצה למעלה)");
            }
        }

        // --- 6. Bollinger Bands ---
        List<Double[]> bandsResults = BollingerBands.calculateBands(historicalPrices, 20, 2.0);
        if (!bandsResults.isEmpty()) {
            Double[] latestBands = bandsResults.get(bandsResults.size() - 1);
            Double upperBand = latestBands[0];
            Double lowerBand = latestBands[2];

            System.out.println("\n--- 📝 מודל בולינגר מודד תנודתיות (Volatility) ומזהה מחירים קיצוניים. ---"); // הסבר קצר
            System.out.println("--- 📊 ניתוח טכני: Bollinger Bands ---");
            System.out.printf("רצועה עליונה (Upper): $%.2f%n", upperBand);
            System.out.printf("רצועה תחתונה (Lower): $%.2f%n", lowerBand);
            System.out.printf(RLM + "הסבר: מחיר נוכחי $%.2f ביחס לרצועות – מתחת ל-Lower עשוי לרמוז על קנייה ערכית; מעל Upper עשוי לרמוז על סיכון/מימוש.%n", currentPrice);

            if (currentPrice < lowerBand) {
                System.out.println("🌟 אות בולינגר: קנייה (מתחת לרצועה התחתונה)");
            } else if (currentPrice > upperBand) {
                System.out.println("🚨 אות בולינגר: מכירה (מעל לרצועה העליונה)");
            }
        }

        // ===================================================================
        // ======================= ניתוח פונדמנטלי ===========================
        // ===================================================================

        // --- 7. DCF (Discounted Cash Flow) ---
        // Pull real fundamentals from Alpha Vantage (OVERVIEW + CASH_FLOW + INCOME_STATEMENT)
        double initialFCF = Double.NaN;
        double sharesOutstanding = Double.NaN;
        double growthRate = Double.NaN;
        double discountRate = 0.12;
        double terminalGrowthRate = 0.02;
        int forecastYears = 5;

        // OVERVIEW
        try {
            String ovJson = DataFetcher.fetchCompanyOverview("");
            String svc = PriceJsonParser.extractServiceMessage(ovJson);
            if (svc != null && !svc.isEmpty()) {
                System.out.println("[Fundamentals] AlphaVantage: " + svc);
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode ov = om.readTree(ovJson);
            Double sh = parseDouble(ov, "SharesOutstanding");
            if (sh != null && sh > 0) sharesOutstanding = sh;

            Double revYoY = parseDouble(ov, "QuarterlyRevenueGrowthYOY");
            Double epsYoY = parseDouble(ov, "QuarterlyEarningsGrowthYOY");
            Double g = (revYoY != null) ? revYoY : epsYoY;
            if (g != null) {
                // clamp to sane bounds
                if (g < -0.20) g = -0.20;
                if (g > 0.35) g = 0.35;
                growthRate = g;
            }
        } catch (Exception ignore) {}

        // CASH_FLOW -> FCF = OCF - CapEx
        try {
            String cfJson = DataFetcher.fetchCashFlow("");
            String svc = PriceJsonParser.extractServiceMessage(cfJson);
            if (svc != null && !svc.isEmpty()) {
                System.out.println("[Fundamentals] AlphaVantage: " + svc);
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(cfJson);
            JsonNode arr = root.path("annualReports");
            if (arr.isArray() && arr.size() > 0) {
                JsonNode y0 = arr.get(0);
                Double ocf = parseDouble(y0, "operatingCashflow");
                Double capex = parseDouble(y0, "capitalExpenditures");
                if (ocf != null) {
                    double cpx = (capex == null) ? 0.0 : capex;
                    initialFCF = ocf - cpx;
                }
            }
        } catch (Exception ignore) {}

        // INCOME_STATEMENT fallback -> estimate growth from last 2 annual revenues
        if (Double.isNaN(growthRate)) {
            try {
                String incJson = DataFetcher.fetchIncomeStatement("");
                String svc = PriceJsonParser.extractServiceMessage(incJson);
                if (svc != null && !svc.isEmpty()) {
                    System.out.println("[Fundamentals] AlphaVantage: " + svc);
                }
                ObjectMapper om = new ObjectMapper();
                JsonNode root = om.readTree(incJson);
                JsonNode arr = root.path("annualReports");
                if (arr.isArray() && arr.size() > 1) {
                    Double rev0 = parseDouble(arr.get(0), "totalRevenue");
                    Double rev1 = parseDouble(arr.get(1), "totalRevenue");
                    if (rev0 != null && rev1 != null && rev1 != 0) {
                        double g = (rev0 - rev1) / rev1;
                        if (g < -0.20) g = -0.20;
                        if (g > 0.35) g = 0.35;
                        growthRate = g;
                    }
                }
            } catch (Exception ignore) {}
        }

        if (Double.isNaN(growthRate)) growthRate = 0.04;
        if (Double.isNaN(initialFCF)) initialFCF = 500_000_000.0;
        if (Double.isNaN(sharesOutstanding) || sharesOutstanding <= 0) sharesOutstanding = 10_000_000.0;

        double fairValue = DCFModel.calculateFairValue(initialFCF, growthRate, discountRate, forecastYears, terminalGrowthRate);
        double fairPricePerShare = fairValue / sharesOutstanding;

        System.out.println("\n--- 📝 מודל DCF מעריך את השווי הפנימי האמיתי של החברה באמצעות תזרימי מזומנים עתידיים. ---"); // הסבר קצר
        System.out.println("--- 💰 ניתוח פונדמנטלי: DCF ---");
        System.out.printf("שווי הוגן למניה (Fair Value): $%.2f%n", fairPricePerShare);
        System.out.printf(RLM + "הסבר: אם מחיר השוק ($%.2f) נמוך מהשווי ההוגן – המניה נראית זולה (Undervalued); אם גבוה – יקרה (Overvalued).%n", currentPrice);

        if (currentPrice < fairPricePerShare) {
            System.out.printf("🟢 אות DCF: קנייה - מחיר השוק נמוך מהשווי ההוגן ($%.2f).%n", fairPricePerShare);
        } else {
            System.out.printf("🔴 אות DCF: מכירה/ניטרלי - מחיר השוק גבוה מהשווי ההוגן ($%.2f).%n", fairPricePerShare);
        }

        // FCF Yield (using the same placeholder FCF and sharesOutstanding)
        double fcfPerShare = initialFCF / sharesOutstanding;
        double fcfYieldPct = (currentPrice > 0) ? (fcfPerShare / currentPrice) * 100.0 : 0.0;

        System.out.println("\n--- 💧 ניתוח פונדמנטלי: FCF Yield ---");
        System.out.printf("FCF למניה (שנתי, משוער): $%.2f%n", fcfPerShare);
        System.out.printf("FCF Yield: %.2f%%%n", fcfYieldPct);

        // --- 8. PEG Ratio (Price/Earnings to Growth) ---
        // Use real EPS if available; fallback remains if API missing
        double latestEPS = 15.20;
        try {
            String ovJson = DataFetcher.fetchCompanyOverview("");
            ObjectMapper om = new ObjectMapper();
            JsonNode ov = om.readTree(ovJson);
            Double eps = parseDouble(ov, "EPS");
            if (eps != null && eps != 0) latestEPS = eps;
        } catch (Exception ignore) {}
        double expectedGrowthRate = Math.max(0.1, Math.min(35.0, growthRate * 100.0));

        double peRatio = FundamentalAnalysis.calculatePERatio(currentPrice, latestEPS);
        double pegRatio = FundamentalAnalysis.calculatePEGRatio(peRatio, expectedGrowthRate);

        System.out.println("\n--- 📝 מודל PEG משווה את מכפיל הרווח (P/E) לצמיחה הצפויה ברווחים. ---"); // הסבר קצר
        System.out.println("--- 📈 ניתוח פונדמנטלי: PEG Ratio ---");
        System.out.printf("יחס P/E: %.2f%n", peRatio);
        System.out.printf("יחס צמיחה-רווח (PEG): %.2f%n", pegRatio);
        System.out.println(RLM + "הסבר: PEG≈1 מצביע על תמחור הוגן יחסית לצמיחה; מתחת ל-1 לרוב נתפס כזול; מעל 2 – עלול להיות יקר מדי.");

        if (!Double.isNaN(pegRatio) && pegRatio <= 1.0) {
            System.out.println("🌟 אות PEG: קנייה חזקה (Undervalued ביחס לצמיחה)");
        } else if (!Double.isNaN(pegRatio) && pegRatio > 2.0) {
            System.out.println("🔴 אות PEG: מכירה (Overvalued ביחס לצמיחה)");
        } else {
            System.out.println("⚪️ אות PEG: ניטרלי");
        }

        // --- Earnings history summary (Alpha Vantage EARNINGS) ---
        try {
            String earnJson = DataFetcher.fetchEarnings(""); // symbol already set in DataFetcher
            if (earnJson != null && !earnJson.isBlank()) {
                ObjectMapper om = new ObjectMapper();
                JsonNode root = om.readTree(earnJson);
                JsonNode qArr = root.path("quarterlyEarnings");
                if (qArr.isArray() && qArr.size() > 0) {
                    System.out.println("\n--- 💹 Earnings History (Last Quarters) ---");
                    int max = Math.min(4, qArr.size());
                    for (int i = 0; i < max; i++) {
                        JsonNode n = qArr.get(i);
                        String date = n.path("fiscalDateEnding").asText("");
                        String rep = n.path("reportedEPS").asText("");
                        String est = n.path("estimatedEPS").asText("");
                        String surprisePct = n.path("surprisePercentage").asText("");
                        System.out.printf("Quarter %s: reported EPS=%s, estimate=%s, surprise= %s%%%n",
                                date, rep, est, surprisePct);
                    }
                }
            }
        } catch (Exception ignore) {}

        // --- Earnings estimates summary (Alpha Vantage EARNINGS_ESTIMATES) ---
        try {
            String estJson = DataFetcher.fetchEarningsEstimates("");
            if (estJson != null && !estJson.isBlank()) {
                ObjectMapper om = new ObjectMapper();
                JsonNode root = om.readTree(estJson);
                JsonNode qArr = root.path("quarterlyEarningsEstimates");
                JsonNode yArr = root.path("annualEarningsEstimates");

                if ((qArr.isArray() && qArr.size() > 0) || (yArr.isArray() && yArr.size() > 0)) {
                    System.out.println("\n--- 📊 Earnings Estimates (Forward) ---");
                }

                if (qArr.isArray() && qArr.size() > 0) {
                    int maxQ = Math.min(4, qArr.size());
                    for (int i = 0; i < maxQ; i++) {
                        JsonNode n = qArr.get(i);
                        String period = n.path("fiscalDateEnding").asText("");
                        String mean = n.path("mean").asText("");
                        String high = n.path("high").asText("");
                        String low = n.path("low").asText("");
                        String numAnalysts = n.path("numberOfAnalysts").asText("");
                        System.out.printf("Quarter %s: EPS est. mean=%s (high=%s, low=%s), analysts=%s%n",
                                period, mean, high, low, numAnalysts);
                    }
                }

                if (yArr.isArray() && yArr.size() > 0) {
                    int maxY = Math.min(3, yArr.size());
                    for (int i = 0; i < maxY; i++) {
                        JsonNode n = yArr.get(i);
                        String year = n.path("fiscalYear").asText("");
                        String mean = n.path("mean").asText("");
                        String high = n.path("high").asText("");
                        String low = n.path("low").asText("");
                        String numAnalysts = n.path("numberOfAnalysts").asText("");
                        System.out.printf("Year %s: EPS est. mean=%s (high=%s, low=%s), analysts=%s%n",
                                year, mean, high, low, numAnalysts);
                    }
                }
            }
        } catch (Exception ignore) {}

        // Piotroski F-Score (partial, based on OVERVIEW fields)
        try {
            String ovJson = DataFetcher.fetchCompanyOverview(""); // symbol is already set in DataFetcher
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(ovJson);
            Double roa = parseDouble(root, "ReturnOnAssetsTTM");
            Double pm = parseDouble(root, "ProfitMargin");
            Double roe = parseDouble(root, "ReturnOnEquityTTM");
            Double opm = parseDouble(root, "OperatingMarginTTM");

            int score = 0; int total = 0;
            if (roa != null) { total++; if (roa > 0) score++; }
            if (pm != null)  { total++; if (pm > 0)  score++; }
            if (roe != null) { total++; if (roe > 0) score++; }
            if (opm != null) { total++; if (opm > 0) score++; }

            System.out.println("\n--- 📊 Piotroski F-Score (partial) ---");
            System.out.println(RLM + "הסבר: ציון פיאוטרוסקי (0–9) מסכם 9 בדיקות של רווחיות/מינוף/יעילות כדי לדרג מניות ערך. ציון גבוה מצביע על איכות פיננסית טובה. (חלקי – לפי שדות OVERVIEW זמינים)");
            System.out.println("ROA>0: " + verdict(roa));
            System.out.println("ProfitMargin>0: " + verdict(pm));
            System.out.println("ROE>0: " + verdict(roe));
            System.out.println("OperatingMargin>0: " + verdict(opm));
            System.out.printf("Total (partial): %d/%d%n", score, total);
        } catch (Exception ignore) {}

        // --- 9 .
        int adxPeriod = 14;
        List<Double[]> adxResults = ADX.calculateADX(highPrices, lowPrices, historicalPrices, adxPeriod);

        if (!adxResults.isEmpty()) {
            Double[] latestADX = adxResults.get(adxResults.size() - 1);
            Double adx = latestADX[0];
            Double plusDI = latestADX[1];
            Double minusDI = latestADX[2];

            System.out.println("\n--- 📈 ניתוח טכני: ADX (חוזק מגמה) ---");
            System.out.printf("ADX אחרון: %.2f (חוזק) | +DI: %.2f | -DI: %.2f%n", adx, plusDI, minusDI);
            System.out.println(RLM + "הסבר: ADX מעל ~25 מעיד על מגמה חזקה. +DI > -DI = נטייה לעלייה; -DI > +DI = נטייה לירידה.");

            // לוגיקה לבחינת שורטים/הרמות:
            if (adx > 25) {
                System.out.print("🚨 אות מגמה: המגמה חזקה. ");
                if (plusDI > minusDI) {
                    System.out.println("קנייה (Long) חזקה מומלצת (הרמה).");
                } else {
                    System.out.println("מכירה (Short) חזקה מומלצת.");
                }
            } else {
                System.out.println("⚪️ אות מגמה: המגמה חלשה/ניטרלית (מתאים למסחר ריינג').");
            }
        }

        // -------------------------------------------------------------------
        // --- 10. ניתוח טכני חדש: ATR (תנודתיות וניהול סיכונים) ---
        int atrPeriod = 14;
        List<Double> atrResults = ATR.calculateATR(highPrices, lowPrices, historicalPrices, atrPeriod);

        if (!atrResults.isEmpty()) {
            Double latestATR = atrResults.get(atrResults.size() - 1);

            System.out.println("\n--- 📊 ניתוח טכני: ATR (תנודתיות) ---");
            System.out.printf("ATR-%d אחרון: $%.2f%n", atrPeriod, latestATR);

            // לוגיקה לניהול סיכונים:
            double riskLimit = 2.0; // סכום הסיכון המומלץ
            double stopLossLevel = currentPrice - (latestATR * riskLimit);

            System.out.printf("הערכת סיכון: המניה זזה כ-%.2f$ ביום. %n", latestATR);
            System.out.printf("המלצת Stop-Loss (אם נכנסים Long): $%.2f%n", stopLossLevel);
            System.out.println(RLM + "הסבר: ATR מודד את הטווח הממוצע של תנודת המחיר. Stop-Loss מחושב בקירוב כ-מחיר נוכחי פחות 2×ATR כדי לתת מרחב ‘נשימה’.\n");
        }

        // --- 11. ניתוח טכני חדש: Chaikin Money Flow (CMF) ---
        int cmfPeriod = 20;
        if (volumeData != null && !volumeData.isEmpty()) {
            List<Double> cmfResults = CMF.calculateCMF(highPrices, lowPrices, historicalPrices, volumeData, cmfPeriod);
            Double latestCMF = cmfResults.get(cmfResults.size() - 1);

            System.out.println("\n--- 📝 מודל CMF מודד את זרימת הכסף הממוצעת כדי לזהות צבירה או פיזור. ---");
            System.out.println("--- 📊 ניתוח טכני: Chaikin Money Flow (CMF) ---");
            System.out.printf("CMF-%d אחרון: %.4f%n", cmfPeriod, latestCMF);

            // CMF נע בין 1- ל-1+.
            if (latestCMF > 0.0) {
                System.out.println("🟢 אות CMF: קנייה (צבירה - לחץ קנייה חיובי).");
            } else if (latestCMF < 0.0) {
                System.out.println("🔴 אות CMF: מכירה (פיזור - לחץ מכירה שלילי).");
            } else {
                System.out.println("⚪️ אות CMF: ניטרלי.");
            }
            System.out.println(RLM + "הסבר: ערך חיובי מצביע על צבירה (כסף ‘נשאר’ במניה), ערך שלילי על פיזור. ככל שהערך קיצוני יותר – האיתות חזק יותר.");
        }

        // --- 12. ניתוח טכני חדש: Pivot Points ---

// נשתמש בנתוני היום הקודם (הנתון לפני האחרון ברשימה)
        int lastIndex = historicalPrices.size() - 1;
        double closePrev = historicalPrices.get(lastIndex - 1); // סגירה יום קודם
        double highPrev = highPrices.get(lastIndex - 1);
        double lowPrev = lowPrices.get(lastIndex - 1);

        Map<String, Double> pivotLevels = PivotPoints.calculatePivots(highPrev, lowPrev, closePrev);
        Double pp = pivotLevels.get("PP");
        Double s1 = pivotLevels.get("S1");
        Double r1 = pivotLevels.get("R1");

        System.out.println("\n--- 📝 מודל Pivot Points (מחיר הוגן טכני) ---");
        System.out.println("--- 📊 ניתוח טכני: Pivot Points ---");
        System.out.printf("נקודת ציר (PP, הוגן): $%.2f%n", pp);
        System.out.printf("תמיכה 1 (S1): $%.2f%n", s1);
        System.out.printf(RLM + "הסבר: PP משמשת ‘מחיר הוגן’ יומי טכני. נסחר מתחת ל-PP = נטייה לירידות/תמחור זול; מעל = נטייה לעליות/תמחור יקר. השוואה למחיר נוכחי: $%.2f.%n", currentPrice);

// לוגיקת המלצה: מחיר זול לקנייה
        if (currentPrice < pp) {
            System.out.printf("🟢 אות קנייה: המחיר ($%.2f) נסחר מתחת לנקודת הציר. יעד קנייה אופטימלי: $%.2f (S1).", currentPrice, s1);
        } else if (currentPrice > r1) {
            System.out.printf("🔴 אות מכירה: המחיר נסחר מעל התנגדות 1. סיכון גבוה לכניסה.", currentPrice);
        } else {
            System.out.println("⚪️ אות ניטרלי: המחיר נסחר בין PP ל-R1.");
        }

        // --- 13. ניתוח טכני חדש: Fibonacci Retracement ---

// אנו נשתמש במחירי High/Low שנמשכו
        if (highPrices.size() > 50) { // נדרש טווח נתונים גדול יותר
            // לצורך הדוגמה, נזהה את ה-High וה-Low הגדולים ב-50 הימים האחרונים
            List<Double> last50Highs = highPrices.subList(highPrices.size() - 50, highPrices.size());
            List<Double> last50Lows = lowPrices.subList(lowPrices.size() - 50, lowPrices.size());

            // מציאת ה-High וה-Low הקיצוניים
            double recentHigh = Collections.max(last50Highs);
            double recentLow = Collections.min(last50Lows);

            Map<String, Double> fibLevels = FibonacciRetracement.calculateLevels(recentHigh, recentLow);
            Double r50 = fibLevels.get("R50");
            Double r61 = fibLevels.get("R61");

            System.out.println("\n--- 📝 מודל Fibonacci Retracement (רמות כניסה) ---");
            System.out.println("--- 📊 ניתוח טכני: Fibonacci Retracement ---");
            System.out.printf("רמת קנייה אופטימלית (R50): $%.2f%n", r50);
            System.out.printf("רמת קנייה חזקה (R61): $%.2f%n", r61);

            // לוגיקת המלצה: קנייה כאשר המחיר נוגע ברמות הנסיגה
            if (currentPrice > r61 && currentPrice < r50) {
                System.out.println("🟢 אות קנייה: המחיר נסוג לאזור ה-50%-61.8% (Deep Dip). כניסה מומלצת!");
            } else {
                System.out.println("⚪️ אות ניטרלי: המחיר לא נמצא כרגע באזור קנייה פיבונאצ'י.");
            }
            System.out.printf(RLM + "הסבר: רמות %s ו-%s משמשות אזורי כניסה פופולריים לאחר תיקון. מחיר נוכחי: $%.2f.%n", "50%", "61.8%", currentPrice);
        }

        // -------------------------------------------------------------------
        // --- 14. ניתוח טכני חדש: Accumulation/Distribution Line (A/D Line) ---

        if (volumeData != null && !volumeData.isEmpty()) {
            // 1. חישוב קו A/D Line היומי המצטבר
            List<Double> adLineResults = ADLine.calculateADLine(highPrices, lowPrices, historicalPrices, volumeData);

            // דרושות מספיק נקודות כדי לחשב ממוצעים נעים
            if (adLineResults != null && adLineResults.size() >= 60) {
                // 2. חישוב ממוצע נע על ה-A/D Line לטווחים שונים
                List<Double> adl_sma20 = TechnicalAnalysisModel.calculateSMA(adLineResults, 20); // חודש
                List<Double> adl_sma60 = TechnicalAnalysisModel.calculateSMA(adLineResults, 60); // 3 חודשים

                if (!adl_sma20.isEmpty() && !adl_sma60.isEmpty()) {
                    Double latestADLSMA20 = adl_sma20.get(adl_sma20.size() - 1);
                    Double latestADLSMA60 = adl_sma60.get(adl_sma60.size() - 1);

                    System.out.println("\n--- 📝 מודל A/D Line (לחץ קנייה מצטבר) ---");
                    System.out.println("--- 📊 ניתוח טכני: A/D Line (צבירה/פיזור) ---");
                    System.out.printf("A/D Line (ממוצע 20 יום): %.2f%n", latestADLSMA20);
                    System.out.printf("A/D Line (ממוצע 60 יום): %.2f%n", latestADLSMA60);

                    // לוגיקת המלצה: הממוצע של A/D Line צריך להיות חיובי או עולה.
                    if (latestADLSMA20 > latestADLSMA60) {
                        System.out.println("🟢 אות A/D: קנייה (לחץ הצבירה הקצר טווח מתגבר על הארוך).");
                    } else if (latestADLSMA20 < 0 && latestADLSMA60 < 0) {
                        System.out.println("🔴 אות A/D: מכירה (פיזור מתמשך בטווח הבינוני והארוך).");
                    } else {
                        System.out.println("⚪️ אות A/D: ניטרלי.");
                    }
                }
            }
        }

        // בתוך Main.java או analyzeSingleStock ב-StockScannerRunner:

// ... לאחר ניתוח DCF ו-PEG ...

// -------------------------------------------------------------------
// --- 15. ניתוח פונדמנטלי: Piotroski F-Score ---

        // *** נתונים דמיוניים/שנתיים (יש למשוך נתונים אמיתיים מ-API פיננסי) ***
        double dummyNI = 500000000.0;
        double dummyROA = 0.05;
        double dummyCFO = 600000000.0;
        double dummyROAPrev = 0.04;
        double dummyNIPRev = 450000000.0;
        double dummyDebtAssets = 0.3;
        double dummyDebtAssetsPrev = 0.4;
        double dummyCurrentRatio = 1.5;
        double dummyCurrentRatioPrev = 1.4;
        long dummyShares = 100000000L;
        long dummySharesPrev = 105000000L; // הפחתת מניות = קנייה חוזרת (Buyback) חיובית

        int fScore = PiotroskiFScore.calculateFScore(
                dummyNI, dummyROA, dummyCFO, dummyROAPrev, dummyNIPRev,
                dummyDebtAssets, dummyDebtAssetsPrev, dummyCurrentRatio, dummyCurrentRatioPrev,
                dummyShares, dummySharesPrev);

        System.out.println("\n--- 📝 מודל Piotroski F-Score (איכות ובריאות פיננסית) ---");
        System.out.println("--- 💰 ניתוח פונדמנטלי: Piotroski F-Score ---");
        System.out.printf("ציון Piotroski אחרון: %d / 9%n", fScore);

        if (fScore >= 8) {
            System.out.println("🌟 אות F-Score: קנייה חזקה (בריאות פיננסית מעולה).");
        } else if (fScore >= 6) {
            System.out.println("🟢 אות F-Score: קנייה (יסודות טובים).");
        } else if (fScore <= 3) {
            System.out.println("🔴 אות F-Score: מכירה/הימנעות (איכות ירודה ומינוף גבוה).");
        } else {
            System.out.println("⚪️ אות F-Score: ניטרלי.");
        }

        // בתוך Main.java (כניתוח טכני חדש):

// ... ודא שהנתונים highPrices, lowPrices ו-closingPrices זמינים ...

// -------------------------------------------------------------------
// --- 16. ניתוח טכני חדש: Commodity Channel Index (CCI) ---
        int cciPeriod = 20; // תקופה נפוצה

        List<Double> cciResults = CCI.calculateCCI(highPrices, lowPrices, historicalPrices, cciPeriod);
        Double latestCCI = cciResults.get(cciResults.size() - 1);

        System.out.println("\n--- 📝 מודל CCI מודד מומנטום קיצוני והיפוך במגמה חזקה. ---");
        System.out.println("--- 📊 ניתוח טכני: CCI ---");
        System.out.printf("CCI-%d אחרון: %.2f%n", cciPeriod, latestCCI);

        if (latestCCI > 100.0) {
            System.out.println("🚨 אות CCI: מכירה (Overbought קיצוני – כניסה למומנטום מעבר לממוצע).");
        } else if (latestCCI < -100.0) {
            System.out.println("🌟 אות CCI: קנייה (Oversold קיצוני – לחץ מכירה חזק מדי).");
        } else {
            System.out.println("⚪️ אות CCI: ניטרלי.");
        }

        // בתוך Main.java או analyzeSingleStock ב-StockScannerRunner:

// ... לאחר ניתוח DCF ו-PEG ...

// -------------------------------------------------------------------
// --- 17. ניתוח פונדמנטלי: EV/Sales Ratio ---

        // *** נתונים דמיוניים הנדרשים (יש למשוך נתונים אמיתיים מ-API) ***
        double dummyMarketCap = currentPrice * 10000000; // שימוש בנתון דמיוני משווי שוק
        double dummyTotalDebt = 20000000.0;
        double dummyCash = 50000000.0;
        double dummyRevenue = 100000000.0; // הכנסות שנתיות

        double evSalesRatio = EVSales.calculateEVSalesRatio(
                dummyMarketCap, dummyTotalDebt, dummyCash, dummyRevenue);

        System.out.println("\n--- 📝 מודל EV/Sales (שווי חברות צמיחה/הפסדיות) ---");
        System.out.println("--- 💰 ניתוח פונדמנטלי: EV/Sales Ratio ---");
        System.out.printf("יחס EV/Sales אחרון: %.2f%n", evSalesRatio);

        // פרשנות: יחס נמוך יותר הוא טוב יותר (יחסית לתעשייה).
        // קריטריון גס: יחס מתחת 3.0 נחשב זול מאוד לחברות טכנולוגיה צומחות.
        if (evSalesRatio < 3.0) {
            System.out.println("🌟 אות EV/Sales: קנייה חזקה (זול ביחס להכנסות).");
        } else if (evSalesRatio > 8.0) {
            System.out.println("🔴 אות EV/Sales: מכירה/הימנעות (יקר ביחס להכנסות).");
        } else {
            System.out.println("⚪️ אות EV/Sales: ניטרלי.");
        }



// ... המשך הניתוח ...

    }
}