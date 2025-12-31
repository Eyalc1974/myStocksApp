/**
 * Event Analysis - ניתוח אירועים משמעותיים מחדשות
 * מזהה אירועים חיוביים ושליליים שעשויים להשפיע על מחיר המניה
 */
public class EventAnalysis {

    // אירועים שליליים - סיכון גבוה
    private static final String[] NEGATIVE_LEGAL = {
        "lawsuit", "litigation", "sued", "class action", "investigation",
        "fraud", "sec probe", "doj", "indictment", "criminal", "תביעה", "חקירה"
    };
    
    private static final String[] NEGATIVE_FINANCIAL = {
        "bankruptcy", "default", "restructuring", "layoffs", "downsizing",
        "profit warning", "guidance cut", "missed earnings", "debt crisis",
        "credit downgrade", "delisting", "פשיטת רגל", "פיטורים"
    };
    
    private static final String[] NEGATIVE_OPERATIONAL = {
        "recall", "safety issue", "data breach", "hack", "cyberattack",
        "supply chain", "production halt", "plant closure", "strike"
    };

    // אירועים חיוביים - קטליסטים לצמיחה
    private static final String[] POSITIVE_REGULATORY = {
        "fda approval", "fda cleared", "regulatory approval", "patent granted",
        "license granted", "certification", "אישור fda", "פטנט"
    };
    
    private static final String[] POSITIVE_BUSINESS = {
        "major contract", "acquisition", "merger", "partnership", "joint venture",
        "strategic alliance", "billion deal", "million deal", "won contract",
        "new customer", "expanded partnership", "עסקה", "רכישה", "מיזוג"
    };
    
    private static final String[] POSITIVE_FINANCIAL = {
        "beat earnings", "raised guidance", "dividend increase", "buyback",
        "share repurchase", "upgrade", "outperform", "strong quarter",
        "record revenue", "record profit", "דיבידנד", "רווח שיא"
    };
    
    private static final String[] POSITIVE_GROWTH = {
        "ipo", "expansion", "new market", "new product", "launch",
        "breakthrough", "innovation", "growth", "הנפקה", "השקה"
    };

    public static class EventResult {
        public String riskLevel;      // HIGH_RISK, MODERATE_RISK, LOW_RISK, POSITIVE_CATALYST
        public String verdict;
        public int positiveCount;
        public int negativeCount;
        public double eventScore;     // -100 to +100
    }

    /**
     * מנתח טקסט חדשות ומזהה אירועים משמעותיים
     */
    public static EventResult analyzeEvents(String newsText) {
        EventResult result = new EventResult();
        
        if (newsText == null || newsText.isBlank()) {
            result.riskLevel = "UNKNOWN";
            result.verdict = "⚪️ No news data available";
            result.eventScore = 0;
            return result;
        }
        
        String lower = newsText.toLowerCase();
        
        // ספירת אירועים שליליים
        int negLegal = countMatches(lower, NEGATIVE_LEGAL);
        int negFinancial = countMatches(lower, NEGATIVE_FINANCIAL);
        int negOperational = countMatches(lower, NEGATIVE_OPERATIONAL);
        result.negativeCount = negLegal + negFinancial + negOperational;
        
        // ספירת אירועים חיוביים
        int posRegulatory = countMatches(lower, POSITIVE_REGULATORY);
        int posBusiness = countMatches(lower, POSITIVE_BUSINESS);
        int posFinancial = countMatches(lower, POSITIVE_FINANCIAL);
        int posGrowth = countMatches(lower, POSITIVE_GROWTH);
        result.positiveCount = posRegulatory + posBusiness + posFinancial + posGrowth;
        
        // חישוב ציון משוקלל (אירועים משפטיים חמורים יותר)
        double negativeScore = (negLegal * 3.0) + (negFinancial * 2.0) + (negOperational * 1.5);
        double positiveScore = (posRegulatory * 2.5) + (posBusiness * 2.0) + (posFinancial * 1.5) + (posGrowth * 1.0);
        
        result.eventScore = Math.max(-100, Math.min(100, (positiveScore - negativeScore) * 10));
        
        // קביעת רמת הסיכון
        if (negLegal > 0 || negativeScore > 5) {
            result.riskLevel = "HIGH_RISK";
            result.verdict = String.format("🔴 HIGH RISK: זוהו %d אירועים שליליים (משפטיים/פיננסיים). יש לבדוק לעומק!", 
                result.negativeCount);
        } else if (negativeScore > 2) {
            result.riskLevel = "MODERATE_RISK";
            result.verdict = String.format("🟠 MODERATE RISK: זוהו %d אירועים שליליים. מומלץ זהירות.", 
                result.negativeCount);
        } else if (positiveScore > 5) {
            result.riskLevel = "POSITIVE_CATALYST";
            result.verdict = String.format("🟢 POSITIVE CATALYST: זוהו %d אירועים חיוביים (רגולטוריים/עסקיים)!", 
                result.positiveCount);
        } else if (positiveScore > 2) {
            result.riskLevel = "MILD_POSITIVE";
            result.verdict = String.format("🟡 Mild Positive: זוהו %d אירועים חיוביים קלים.", 
                result.positiveCount);
        } else {
            result.riskLevel = "LOW_RISK";
            result.verdict = "⚪️ LOW RISK: חדשות שגרתיות ללא אירועים משמעותיים.";
        }
        
        return result;
    }

    /**
     * גרסה פשוטה לתאימות אחורה
     */
    public static String getEventRisk(String latestNewsJson) {
        EventResult result = analyzeEvents(latestNewsJson);
        return result.verdict;
    }
    
    /**
     * מחזיר ציון אירועים (-100 עד +100)
     */
    public static double getEventScore(String newsText) {
        return analyzeEvents(newsText).eventScore;
    }

    private static int countMatches(String text, String[] keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }
}