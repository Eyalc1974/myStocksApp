
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