// בתוך EventAnalysis.java

public class EventAnalysis {
    public static String getEventRisk(String latestNewsJson) {
        // (ביישום אמיתי: יש לנתח את הכותרות לזיהוי מילים כמו "תביעה", "חקירה", "אישור FDA", "פיצול מניות")

        if (latestNewsJson.contains("lawsuit") || latestNewsJson.contains("investigation")) {
            return "HIGH RISK (Negative Legal Event)";
        }
        if (latestNewsJson.contains("FDA approval") || latestNewsJson.contains("major contract")) {
            return "POSITIVE CATALYST (Upcoming Growth Event)";
        }
        return "LOW RISK (Routine news)";
    }
}