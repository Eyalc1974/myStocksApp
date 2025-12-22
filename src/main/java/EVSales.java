
import java.util.HashMap;
import java.util.Map;

public class EVSales {

    /**
     * מחשב את יחס Enterprise Value to Sales (EV/Sales).
     * @param marketCapitalization שווי שוק כולל.
     * @param totalDebt סך ההתחייבויות (חוב).
     * @param cashAndEquivalents מזומנים ושווי מזומנים.
     * @param annualRevenue הכנסות שנתיות.
     * @return יחס EV/Sales.
     */
    public static double calculateEVSalesRatio(
            double marketCapitalization, double totalDebt, double cashAndEquivalents, double annualRevenue) {

        if (annualRevenue <= 0) {
            return Double.NaN; // לא ניתן לחשב אם אין הכנסות או שהן שליליות
        }

        // 1. חישוב Enterprise Value (EV)
        // EV = שווי שוק + חוב - מזומנים
        double enterpriseValue = marketCapitalization + totalDebt - cashAndEquivalents;

        // 2. חישוב היחס
        double evSalesRatio = enterpriseValue / annualRevenue;

        return evSalesRatio;
    }
}