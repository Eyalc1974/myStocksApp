
import java.util.HashMap;
import java.util.Map;

public class PiotroskiFScore {

    /**
     * מחשב את ציון Piotroski F-Score (0-9) על בסיס נתונים שנתיים.
     * @param netIncome רווח נקי (שנה אחרונה).
     * @param roa תשואה על נכסים (Return on Assets - ROA, שנה אחרונה).
     * @param cfo תזרים מזומנים מפעילות (Cash Flow from Operations - CFO, שנה אחרונה).
     * @param roaPrev ROA של השנה הקודמת.
     * @param niPrev רווח נקי של השנה הקודמת.
     * @param debtAssets יחס חוב לנכסים (שנה אחרונה).
     * @param debtAssetsPrev יחס חוב לנכסים (שנה קודמת).
     * @param currentRatio יחס שוטף (נכסים שוטפים/התחייבויות שוטפות, שנה אחרונה).
     * @param currentRatioPrev יחס שוטף של השנה הקודמת.
     * @param sharesOutstandingShares מניות בולטות (שנה אחרונה).
     * @param sharesOutstandingSharesPrev מניות בולטות (שנה קודמת).
     * @return הציון הכולל (0-9).
     */
    public static int calculateFScore(
            double netIncome, double roa, double cfo, double roaPrev, double niPrev,
            double debtAssets, double debtAssetsPrev, double currentRatio, double currentRatioPrev,
            long sharesOutstandingShares, long sharesOutstandingSharesPrev) {

        int score = 0;

        // --- I. רווחיות (Profitability) ---

        // 1. תשואה חיובית על נכסים (ROA > 0)
        if (roa > 0) {
            score++;
        }

        // 2. תזרים מזומנים מפעילות חיובי (CFO > 0)
        if (cfo > 0) {
            score++;
        }

        // 3. CFO > Net Income (איכות הרווחים)
        if (cfo > netIncome) {
            score++;
        }

        // 4. שיפור ב-ROA לעומת שנה קודמת
        if (roa > roaPrev) {
            score++;
        }

        // --- II. מינוף, נזילות ומקורות (Leverage, Liquidity, and Source of Funds) ---

        // 5. הפחתת יחס החוב לנכסים (Debt/Assets) לעומת שנה קודמת
        if (debtAssets < debtAssetsPrev) {
            score++;
        }

        // 6. שיפור ביחס השוטף (Current Ratio) לעומת שנה קודמת
        if (currentRatio > currentRatioPrev) {
            score++;
        }

        // 7. לא גדל מספר המניות הבולטות (Unlikely Dilution)
        if (sharesOutstandingShares <= sharesOutstandingSharesPrev) {
            score++;
        }

        // --- III. יעילות תפעולית (Operating Efficiency) ---

        // 8. שיפור ביחס הרווח הגולמי (Gross Margin) לעומת שנה קודמת
        // (דרוש חישוב קצת יותר מורכב, כאן נשתמש ברווח נקי כפרוקסי)
        if (netIncome > niPrev) {
            score++;
        }

        // 9. שיפור בשיעור תחלופת הנכסים (Asset Turnover) לעומת שנה קודמת
        // (מדד זה נמדד על ידי שינוי במכירות מול שינוי בנכסים, כאן נשתמש ב-ROA/ROA-Prev כפרוקסי)
        if (roa / roaPrev > 1.0) { // שיפור ביעילות השימוש בנכסים
            score++;
        }

        return score;
    }
}