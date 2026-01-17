
public class StockAnalysisResult {
    public final String ticker;
    public final double price;
    public final double dcfFairValue;

    // מודלי החלטה
    public String technicalSignal = "NEUTRAL"; // קצר: BUY, SELL, SHORT
    public String fundamentalSignal = "NEUTRAL"; // ארוך: BUY, SELL
    public final double adxStrength;
    // בתוך StockAnalysisResult.java
    public String finalVerdict = "NEUTRAL"; // שדה חדש

    public Double beneishMScore;
    public String beneishVerdict;
    public Boolean beneishManipulator;

    public Double sloanRatio;
    public String sloanVerdict;
    public Boolean sloanLowQuality;

    public Double cccDays;
    public String cccVerdict;

    public Double roic;
    public Double wacc;
    public Double economicSpread;
    public String valueCreationVerdict;
    public Boolean strongValueCreator;

    public Double altmanZ;
    public Double pegRatio;
    public Double latestRsi;

    public Integer piotroskiFScore;

    public Double grahamNumber;
    public Double grahamIntrinsicValue;
    public Double grahamMarginOfSafety;
    public String grahamValuationVerdict;

    // Market Regime Filter fields
    public Boolean marketBullish;              // האם השוק בולי (SPY > SMA200)
    public Double relativeStrength3M;          // חוזק יחסי 3 חודשים מול SPY
    public Boolean outperformsSpy;             // האם מנצחת את SPY
    public Double revenueGrowthRate;           // שיעור צמיחת הכנסות
    public Boolean highGrowth;                 // צמיחה מעל 20%
    public Integer marketRegimeBonus;          // בונוס מפילטר השוק
    public Boolean passesMarketFilter;         // האם עוברת פילטר שוק
    public String marketRegimeVerdict;         // תיאור מצב השוק

    // Entry Filters fields (פילטרים קשיחים לכניסה)
    public Boolean passesEntryFilters;         // האם עוברת את כל פילטרי הכניסה
    public Double sma200;                      // ממוצע נע 200
    public Double volumeRatio;                 // יחס נפח לממוצע
    public Double suggestedStopLoss;           // סטופ-לוס מומלץ (ATR)
    public Double suggestedTakeProfit;         // Take-Profit מומלץ (ATR)
    public Double atrValue;                    // ערך ATR נוכחי
    public String entryFiltersSummary;         // סיכום פילטרי כניסה

    // Relative Strength fields (כוח יחסי מול SPY)
    public Double rsRatio3M;                   // יחס RS ל-3 חודשים
    public Double rsRatio6M;                   // יחס RS ל-6 חודשים
    public Double stockReturn3M;               // תשואת מניה 3 חודשים
    public Double spyReturn3M;                 // תשואת SPY 3 חודשים
    public Integer rsPoints;                   // ניקוד RS (0-30)
    public String rsCategory;                  // קטגוריה: LEADER/PERFORMER/LAGGARD
    public String rsArrow;                     // חץ לתצוגה: ↑↑/→/↓
    public String rsColor;                     // צבע לתצוגה
    public String rsSummary;                   // סיכום RS

    public StockAnalysisResult(String ticker, double price, double dcfFairValue, double adxStrength) {
        this.ticker = ticker;
        this.price = price;
        this.dcfFairValue = dcfFairValue;
        this.adxStrength = adxStrength;
    }

    // מתודה להדפסת שורה מסכמת
    @Override
    public String toString() {
        return String.format("| %-5s | $%-8.2f | %-15s | %-15s | ADX: %.2f |",
                ticker, price, technicalSignal, fundamentalSignal, adxStrength);
    }
}