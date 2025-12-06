
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

public class Main {

    // *** הערה: לצורך הרצה, יש להוסיף לקלאס Main מתודות עזר חסרות (High/Low) ***
    // מאחר שמשיכת נתוני High/Low דורשת שינויים ב-JsonParser, אנו משתמשים כאן ב-Close prices כנתוני ברירת מחדל (לא מדויק!)
    public static List<Double> fetchHighPrices(String jsonData) throws Exception {
        return PriceJsonParser.extractHighPrices(jsonData);
    }
    public static List<Double> fetchLowPrices(String jsonData) throws Exception {
        return PriceJsonParser.extractLowPrices(jsonData);
    }
    // *** ודא ש-JsonParser עודכן עם המתודות extractHighPrices ו-extractLowPrices ***


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

        } catch (Exception e) {
            System.err.println("❌ שגיאה קריטית במשיכת נתונים: " + e.getMessage());
            return;
        }

        if (historicalPrices == null || historicalPrices.size() < 30) {
            System.err.println("❌ לא נמשכו מספיק נתונים (נדרש לפחות 30 לחישובים מורכבים).");
            return;
        }

        // אם לא הצלחנו למשוך High/Low, נשתמש ב-Close (זה לא נכון מבחינה פיננסית, אבל מאפשר לקוד לרוץ)
        if (highPrices == null || highPrices.isEmpty()) {
            highPrices = historicalPrices;
            lowPrices = historicalPrices;
        }

        Double currentPrice = historicalPrices.get(historicalPrices.size() - 1);
        System.out.println("✅ נתונים נמשכו בהצלחה. מחיר סגירה עדכני: " + String.format("$%.2f", currentPrice));
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
        // נתונים אלו עדיין מוגדרים כאן ידנית (יש למשוך אותם מ-API פונדמנטלי)
        double initialFCF = 500_000_000.0;
        double sharesOutstanding = 10_000_000;
        double growthRate = 0.04;
        double discountRate = 0.12;
        double terminalGrowthRate = 0.02;
        int forecastYears = 5;

        double fairValue = DCFModel.calculateFairValue(initialFCF, growthRate, discountRate, forecastYears, terminalGrowthRate);
        double fairPricePerShare = fairValue / sharesOutstanding;

        System.out.println("\n--- 📝 מודל DCF מעריך את השווי הפנימי האמיתי של החברה באמצעות תזרימי מזומנים עתידיים. ---"); // הסבר קצר
        System.out.println("--- 💰 ניתוח פונדמנטלי: DCF ---");
        System.out.printf("שווי הוגן למניה (Fair Value): $%.2f%n", fairPricePerShare);

        if (currentPrice < fairPricePerShare) {
            System.out.printf("🟢 אות DCF: קנייה - מחיר השוק נמוך מהשווי ההוגן ($%.2f).", fairPricePerShare);
        } else {
            System.out.printf("🔴 אות DCF: מכירה/ניטרלי - מחיר השוק גבוה מהשווי ההוגן ($%.2f).", fairPricePerShare);
        }

        // --- 8. PEG Ratio (Price/Earnings to Growth) ---
        // נתונים פונדמנטליים נדרשים (עדיין דמיוניים):
        double latestEPS = 15.20;
        double expectedGrowthRate = 20.0;

        double peRatio = FundamentalAnalysis.calculatePERatio(currentPrice, latestEPS);
        double pegRatio = FundamentalAnalysis.calculatePEGRatio(peRatio, expectedGrowthRate);

        System.out.println("\n--- 📝 מודל PEG משווה את מכפיל הרווח (P/E) לצמיחה הצפויה ברווחים. ---"); // הסבר קצר
        System.out.println("--- 📈 ניתוח פונדמנטלי: PEG Ratio ---");
        System.out.printf("יחס P/E: %.2f%n", peRatio);
        System.out.printf("יחס צמיחה-רווח (PEG): %.2f%n", pegRatio);

        if (!Double.isNaN(pegRatio) && pegRatio <= 1.0) {
            System.out.println("🌟 אות PEG: קנייה חזקה (Undervalued ביחס לצמיחה)");
        } else if (!Double.isNaN(pegRatio) && pegRatio > 2.0) {
            System.out.println("🔴 אות PEG: מכירה (Overvalued ביחס לצמיחה)");
        } else {
            System.out.println("⚪️ אות PEG: ניטרלי");
        }

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
        }

    }
}