import java.util.ArrayList;
import java.util.List;

/**
 * IntradayScanner - Real-time momentum breakout detection for intraday trading.
 * 
 * This scanner identifies potential breakout opportunities by analyzing:
 * - RVOL (Relative Volume) - Most important indicator for institutional activity
 * - VWAP (Volume Weighted Average Price) - Critical for intraday trading
 * - RSI (Relative Strength Index) - Momentum confirmation on 15-min timeframe
 * - Price Action - Breakout of previous day high or psychological levels
 * - Market Sentiment - S&P 500 support check
 * 
 * The scanner is designed to work with 15-minute delayed data from Alpha Vantage.
 */
public class IntradayScanner {

    // Configurable thresholds
    public static final double RVOL_THRESHOLD = 2.0;           // Volume must be 2x normal
    public static final double MIN_PRICE_CHANGE_PCT = 1.5;     // Minimum 1.5% move
    public static final double MAX_CHASE_PCT = 4.0;            // Don't chase if already up more than 4%
    public static final double RSI_BREAKOUT_THRESHOLD = 60.0;  // RSI must cross above 60
    public static final double RSI_OVERBOUGHT = 80.0;          // Don't buy if RSI > 80
    public static final double VWAP_BUFFER_PCT = 0.5;          // Price must be at least 0.5% above VWAP

    /**
     * Result of the intraday scan
     */
    public static class ScanResult {
        public String ticker;
        public String signal;              // "BREAKOUT_BUY", "MOMENTUM_BUY", "HOLD", "MISSED", "OVERBOUGHT", "WEAK_VOLUME"
        public String signalHebrew;        // Hebrew translation
        public double currentPrice;
        public double openPrice;
        public double priceChangePct;
        public double rvol;                // Relative volume ratio
        public double vwap;                // Volume Weighted Average Price
        public double rsi;                 // RSI on intraday timeframe
        public boolean aboveVwap;
        public boolean volumeSpike;
        public boolean breakoutConfirmed;
        public double previousDayHigh;
        public double breakoutLevel;
        public double suggestedEntry;
        public double suggestedStopLoss;
        public double targetPrice;
        public List<String> reasons = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public long timestamp;
        
        // Market context
        public double spyChangePct;        // S&P 500 change for market sentiment
        public boolean marketSupport;      // True if market is supportive
        
        // ===== DELAY-AWARE DATA (15-minute safe bullish) =====
        public int minutesAboveVwap = 0;       // How long price has been above VWAP
        public double vwapSlope = 0;           // VWAP slope (positive = rising, negative = falling)
        public int consecutiveVolumeBars = 0; // Consecutive bars with volume above average
        public int rsiRisingBars = 0;          // Consecutive bars with rising RSI
        public boolean higherLowFormed = false; // Higher low detected after first push
        public double candleClosePosition = 0; // 0-100, where 100 = close at high
        public int durabilityScore = 0;        // 0-100 combined delay-safe score
    }

    /**
     * Bar data for intraday analysis
     */
    public static class IntradayBar {
        public String timestamp;
        public double open;
        public double high;
        public double low;
        public double close;
        public long volume;
    }

    /**
     * Main breakout detection method
     * 
     * @param currentPrice Current price of the stock
     * @param openPrice Today's opening price
     * @param avgVolume Average daily volume (30-day average)
     * @param currentVolume Current day's volume so far
     * @param hoursIntoSession Hours since market open (for RVOL calculation)
     * @return true if momentum breakout conditions are met
     */
    public static boolean isMomentumBreakout(double currentPrice, double openPrice, 
                                              double avgVolume, double currentVolume,
                                              double hoursIntoSession) {
        if (openPrice <= 0 || avgVolume <= 0 || hoursIntoSession <= 0) return false;
        
        double priceChange = (currentPrice - openPrice) / openPrice;
        // Calculate expected volume for this time of day (assuming 6.5 hour trading day)
        double expectedVolumeNow = avgVolume * (hoursIntoSession / 6.5);
        double volumeRatio = currentVolume / expectedVolumeNow;

        // Basic breakout conditions: price up > 1.5% with RVOL > 2.0
        return priceChange > (MIN_PRICE_CHANGE_PCT / 100.0) && volumeRatio > RVOL_THRESHOLD;
    }

    /**
     * Full scan with detailed analysis
     */
    public static ScanResult scan(String ticker, List<IntradayBar> bars, 
                                   double avgDailyVolume, double previousDayHigh,
                                   double previousDayClose, double spyChangePct) {
        ScanResult result = new ScanResult();
        result.ticker = ticker;
        result.timestamp = System.currentTimeMillis();
        result.spyChangePct = spyChangePct;
        result.previousDayHigh = previousDayHigh;
        
        if (bars == null || bars.isEmpty()) {
            result.signal = "NO_DATA";
            result.signalHebrew = "אין נתונים";
            return result;
        }

        // Get latest bar and calculate metrics
        IntradayBar latest = bars.get(0);
        result.currentPrice = latest.close;
        result.openPrice = bars.get(bars.size() - 1).open; // First bar of the day
        result.priceChangePct = ((result.currentPrice - result.openPrice) / result.openPrice) * 100.0;

        // Calculate VWAP
        result.vwap = calculateVWAP(bars);
        result.aboveVwap = result.currentPrice > result.vwap;

        // Calculate RVOL
        long totalVolume = 0;
        for (IntradayBar bar : bars) {
            totalVolume += bar.volume;
        }
        double hoursIntoSession = Math.min(6.5, bars.size() * (5.0 / 60.0)); // Assuming 5-min bars
        double expectedVolume = avgDailyVolume * (hoursIntoSession / 6.5);
        result.rvol = expectedVolume > 0 ? totalVolume / expectedVolume : 0;
        result.volumeSpike = result.rvol >= RVOL_THRESHOLD;

        // Calculate RSI on intraday data
        result.rsi = calculateIntradayRSI(bars, 14);

        // Check if breaking previous day high
        result.breakoutLevel = previousDayHigh;
        result.breakoutConfirmed = result.currentPrice > previousDayHigh && 
                                   previousDayHigh > 0 &&
                                   previousDayClose > 0;

        // Market sentiment check
        result.marketSupport = spyChangePct > -0.5; // Market not down more than 0.5%

        // Determine signal
        result.signal = determineSignal(result);
        result.signalHebrew = translateSignal(result.signal);

        // Calculate entry, stop loss, and target
        if (result.signal.equals("BREAKOUT_BUY") || result.signal.equals("MOMENTUM_BUY")) {
            calculateTradeParameters(result, previousDayClose);
        }

        return result;
    }

    /**
     * Calculate VWAP (Volume Weighted Average Price)
     */
    public static double calculateVWAP(List<IntradayBar> bars) {
        if (bars == null || bars.isEmpty()) return 0;
        
        double cumulativeTPV = 0; // Typical Price * Volume
        long cumulativeVolume = 0;
        
        // Process bars from oldest to newest
        for (int i = bars.size() - 1; i >= 0; i--) {
            IntradayBar bar = bars.get(i);
            double typicalPrice = (bar.high + bar.low + bar.close) / 3.0;
            cumulativeTPV += typicalPrice * bar.volume;
            cumulativeVolume += bar.volume;
        }
        
        return cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : 0;
    }

    /**
     * Calculate RSI on intraday bars
     */
    public static double calculateIntradayRSI(List<IntradayBar> bars, int period) {
        if (bars == null || bars.size() < period + 1) return 50; // Default neutral
        
        double gainSum = 0;
        double lossSum = 0;
        
        // Bars are in reverse chronological order (newest first)
        for (int i = 0; i < period && i < bars.size() - 1; i++) {
            double change = bars.get(i).close - bars.get(i + 1).close;
            if (change > 0) {
                gainSum += change;
            } else {
                lossSum += Math.abs(change);
            }
        }
        
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * Determine the trading signal based on all indicators
     */
    private static String determineSignal(ScanResult result) {
        List<String> reasons = result.reasons;
        List<String> warnings = result.warnings;

        // Check for "missed" opportunity - already moved too much
        if (result.priceChangePct > MAX_CHASE_PCT) {
            warnings.add("מחיר כבר עלה " + String.format("%.1f%%", result.priceChangePct) + " - אל תרדוף אחרי המחיר");
            return "MISSED";
        }

        // Check for overbought
        if (result.rsi > RSI_OVERBOUGHT) {
            warnings.add("RSI גבוה מדי (" + String.format("%.0f", result.rsi) + ") - סיכון לתיקון");
            return "OVERBOUGHT";
        }

        // Check for weak volume
        if (!result.volumeSpike) {
            warnings.add("RVOL נמוך (" + String.format("%.1f", result.rvol) + ") - העלייה עלולה לדעוך");
            return "WEAK_VOLUME";
        }

        // Check market support
        if (!result.marketSupport) {
            warnings.add("השוק יורד (" + String.format("%.1f%%", result.spyChangePct) + ") - סיכון גבוה יותר");
        }

        // BREAKOUT_BUY: Breaking previous day high with volume
        if (result.breakoutConfirmed && result.volumeSpike && result.aboveVwap) {
            reasons.add("פריצת שיא יום קודם עם נפח חריג");
            reasons.add("RVOL: " + String.format("%.1fx", result.rvol));
            reasons.add("מעל VWAP ב-" + String.format("%.2f%%", ((result.currentPrice / result.vwap) - 1) * 100));
            if (result.rsi > RSI_BREAKOUT_THRESHOLD) {
                reasons.add("RSI חזק: " + String.format("%.0f", result.rsi));
            }
            return "BREAKOUT_BUY";
        }

        // MOMENTUM_BUY: Strong momentum without breaking high
        if (result.priceChangePct >= MIN_PRICE_CHANGE_PCT && 
            result.volumeSpike && 
            result.aboveVwap &&
            result.rsi > RSI_BREAKOUT_THRESHOLD) {
            reasons.add("מומנטום חזק עם נפח");
            reasons.add("עלייה של " + String.format("%.1f%%", result.priceChangePct));
            reasons.add("RVOL: " + String.format("%.1fx", result.rvol));
            reasons.add("מעל VWAP");
            return "MOMENTUM_BUY";
        }

        // Default: HOLD
        if (result.priceChangePct > 0) {
            warnings.add("עלייה קלה אבל חסרים תנאי כניסה");
        }
        return "HOLD";
    }

    /**
     * Calculate suggested trade parameters
     */
    private static void calculateTradeParameters(ScanResult result, double previousDayClose) {
        // Entry: Current price or slightly below for limit order
        result.suggestedEntry = result.currentPrice;
        
        // Stop loss: Below VWAP or 2% below entry, whichever is closer
        double stopBelowVwap = result.vwap * 0.995;
        double stop2Percent = result.currentPrice * 0.98;
        result.suggestedStopLoss = Math.max(stopBelowVwap, stop2Percent);
        
        // Target: 2:1 risk/reward ratio
        double risk = result.suggestedEntry - result.suggestedStopLoss;
        result.targetPrice = result.suggestedEntry + (risk * 2);
        
        // Add trade plan to reasons
        result.reasons.add(String.format("כניסה: $%.2f", result.suggestedEntry));
        result.reasons.add(String.format("סטופ: $%.2f (%.1f%%)", 
            result.suggestedStopLoss, 
            ((result.suggestedEntry - result.suggestedStopLoss) / result.suggestedEntry) * 100));
        result.reasons.add(String.format("יעד: $%.2f (%.1f%%)", 
            result.targetPrice,
            ((result.targetPrice - result.suggestedEntry) / result.suggestedEntry) * 100));
    }

    /**
     * Translate signal to Hebrew
     */
    private static String translateSignal(String signal) {
        switch (signal) {
            case "BREAKOUT_BUY": return "🚀 פריצה - קנייה";
            case "MOMENTUM_BUY": return "📈 מומנטום - קנייה";
            case "HOLD": return "⏸️ המתנה";
            case "MISSED": return "❌ פספסנו - אל תרדוף";
            case "OVERBOUGHT": return "⚠️ קניית יתר";
            case "WEAK_VOLUME": return "📉 נפח חלש";
            case "NO_DATA": return "אין נתונים";
            default: return signal;
        }
    }

    /**
     * Quick check if a stock passes the basic momentum filter
     * Used for scanning multiple stocks quickly
     */
    public static boolean passesQuickMomentumFilter(double priceChangePct, double rvol, 
                                                     boolean aboveVwap, double rsi) {
        return priceChangePct >= MIN_PRICE_CHANGE_PCT &&
               rvol >= RVOL_THRESHOLD &&
               aboveVwap &&
               rsi >= RSI_BREAKOUT_THRESHOLD &&
               rsi <= RSI_OVERBOUGHT;
    }

    /**
     * Calculate the "chase risk" - how much of the move has already happened
     * Returns 0-100 where higher means more risk of chasing
     */
    public static int calculateChaseRisk(double priceChangePct, double rsi) {
        int risk = 0;
        
        // Price already moved significantly
        if (priceChangePct > 3.0) risk += 40;
        else if (priceChangePct > 2.0) risk += 20;
        
        // RSI getting extended
        if (rsi > 75) risk += 40;
        else if (rsi > 70) risk += 20;
        
        // Base risk for any chase
        if (priceChangePct > MIN_PRICE_CHANGE_PCT) risk += 20;
        
        return Math.min(100, risk);
    }

    // ===== DELAY-AWARE INDICATOR CALCULATIONS =====
    
    /**
     * Calculate how many minutes price has been above VWAP
     * @param bars Intraday bars (newest first)
     * @param barIntervalMinutes Minutes per bar (e.g., 5 for 5-min bars)
     * @return Minutes above VWAP (0 if currently below)
     */
    public static int calculateMinutesAboveVwap(List<IntradayBar> bars, int barIntervalMinutes) {
        if (bars == null || bars.size() < 2) return 0;
        
        int consecutiveBars = 0;
        double runningVwap = 0;
        double runningTPV = 0;
        long runningVolume = 0;
        
        // Calculate VWAP progressively from oldest to newest, then count from newest
        List<Double> vwapAtBar = new ArrayList<>();
        for (int i = bars.size() - 1; i >= 0; i--) {
            IntradayBar bar = bars.get(i);
            double typicalPrice = (bar.high + bar.low + bar.close) / 3.0;
            runningTPV += typicalPrice * bar.volume;
            runningVolume += bar.volume;
            runningVwap = runningVolume > 0 ? runningTPV / runningVolume : 0;
            vwapAtBar.add(0, runningVwap); // Insert at beginning to maintain order
        }
        
        // Count consecutive bars above VWAP from newest
        for (int i = 0; i < bars.size() && i < vwapAtBar.size(); i++) {
            if (bars.get(i).close > vwapAtBar.get(i)) {
                consecutiveBars++;
            } else {
                break;
            }
        }
        
        return consecutiveBars * barIntervalMinutes;
    }
    
    /**
     * Calculate VWAP slope (rate of change over last N bars)
     * @return Positive = rising, negative = falling, 0 = flat
     */
    public static double calculateVwapSlope(List<IntradayBar> bars, int lookbackBars) {
        if (bars == null || bars.size() < lookbackBars + 1) return 0;
        
        // Calculate VWAP at current bar and N bars ago
        double currentVwap = calculateVWAP(bars.subList(0, Math.min(bars.size(), bars.size())));
        double pastVwap = calculateVWAP(bars.subList(lookbackBars, bars.size()));
        
        if (pastVwap <= 0) return 0;
        return (currentVwap - pastVwap) / pastVwap * 100; // Percentage change
    }
    
    /**
     * Count consecutive bars with volume above average
     * @param bars Intraday bars (newest first)
     * @param avgVolume Average volume per bar
     * @return Number of consecutive bars with volume above average
     */
    public static int calculateConsecutiveVolumeBars(List<IntradayBar> bars, double avgVolume) {
        if (bars == null || bars.isEmpty() || avgVolume <= 0) return 0;
        
        int consecutive = 0;
        for (IntradayBar bar : bars) {
            if (bar.volume > avgVolume) {
                consecutive++;
            } else {
                break;
            }
        }
        return consecutive;
    }
    
    /**
     * Count consecutive bars with rising RSI
     * @param bars Intraday bars (newest first)
     * @param period RSI period
     * @return Number of consecutive bars where RSI increased
     */
    public static int calculateRsiRisingBars(List<IntradayBar> bars, int period) {
        if (bars == null || bars.size() < period + 3) return 0;
        
        int risingCount = 0;
        double prevRsi = -1;
        
        // Calculate RSI for each bar position and check if rising
        for (int i = 0; i < Math.min(10, bars.size() - period - 1); i++) {
            List<IntradayBar> subBars = bars.subList(i, bars.size());
            double rsi = calculateIntradayRSI(subBars, period);
            
            if (prevRsi >= 0) {
                if (rsi > prevRsi) {
                    risingCount++;
                } else {
                    break;
                }
            }
            prevRsi = rsi;
        }
        
        return risingCount;
    }
    
    /**
     * Detect if a higher low has formed after the first push
     * A higher low means buyers defended price - the move is no longer fragile
     * @param bars Intraday bars (newest first)
     * @return true if higher low pattern detected
     */
    public static boolean detectHigherLow(List<IntradayBar> bars) {
        if (bars == null || bars.size() < 6) return false;
        
        // Find local lows in the recent bars
        List<Double> lows = new ArrayList<>();
        List<Integer> lowIndices = new ArrayList<>();
        
        for (int i = 1; i < bars.size() - 1 && i < 20; i++) {
            IntradayBar prev = bars.get(i + 1);
            IntradayBar curr = bars.get(i);
            IntradayBar next = bars.get(i - 1);
            
            // Local low: current low is lower than both neighbors
            if (curr.low < prev.low && curr.low < next.low) {
                lows.add(curr.low);
                lowIndices.add(i);
            }
        }
        
        // Need at least 2 lows to compare
        if (lows.size() < 2) return false;
        
        // Check if most recent low is higher than previous low
        // lows[0] is most recent, lows[1] is previous
        return lows.get(0) > lows.get(1);
    }
    
    /**
     * Calculate candle close position (0-100)
     * 100 = close at high, 0 = close at low
     * @param bar The candle bar
     * @return Position 0-100
     */
    public static double calculateCandleClosePosition(IntradayBar bar) {
        if (bar == null) return 50;
        double range = bar.high - bar.low;
        if (range <= 0) return 50;
        return ((bar.close - bar.low) / range) * 100;
    }
    
    /**
     * Calculate durability score (0-100) for delay-safe trading
     * Higher score = more durable move, safer for delayed data
     * 
     * Components:
     * - VWAP hold duration (25 pts)
     * - VWAP slope (15 pts)
     * - Volume consistency (20 pts)
     * - RSI momentum persistence (15 pts)
     * - Higher low formation (15 pts)
     * - Candle close position (10 pts)
     */
    public static int calculateDurabilityScore(ScanResult result) {
        int score = 0;
        
        // VWAP hold duration (25 pts) - 20+ minutes = full points
        if (result.minutesAboveVwap >= 30) {
            score += 25;
        } else if (result.minutesAboveVwap >= 20) {
            score += 20;
        } else if (result.minutesAboveVwap >= 10) {
            score += 10;
        }
        
        // VWAP slope (15 pts) - flat or rising
        if (result.vwapSlope > 0.1) {
            score += 15; // Rising VWAP
        } else if (result.vwapSlope >= -0.05) {
            score += 10; // Flat VWAP
        }
        
        // Volume consistency (20 pts) - 3+ consecutive bars
        if (result.consecutiveVolumeBars >= 4) {
            score += 20;
        } else if (result.consecutiveVolumeBars >= 3) {
            score += 15;
        } else if (result.consecutiveVolumeBars >= 2) {
            score += 8;
        }
        
        // RSI momentum persistence (15 pts) - 3+ rising bars
        if (result.rsiRisingBars >= 4) {
            score += 15;
        } else if (result.rsiRisingBars >= 3) {
            score += 12;
        } else if (result.rsiRisingBars >= 2) {
            score += 6;
        }
        
        // Higher low formation (15 pts)
        if (result.higherLowFormed) {
            score += 15;
        }
        
        // Candle close position (10 pts) - upper 30%
        if (result.candleClosePosition >= 70) {
            score += 10;
        } else if (result.candleClosePosition >= 50) {
            score += 5;
        }
        
        return Math.min(100, score);
    }
    
    /**
     * Populate delay-aware data in ScanResult
     * Call this after basic scan to add durability metrics
     */
    public static void populateDelayAwareData(ScanResult result, List<IntradayBar> bars, 
                                               double avgVolumePerBar, int barIntervalMinutes) {
        if (bars == null || bars.isEmpty()) return;
        
        result.minutesAboveVwap = calculateMinutesAboveVwap(bars, barIntervalMinutes);
        result.vwapSlope = calculateVwapSlope(bars, 6); // 6 bars lookback
        result.consecutiveVolumeBars = calculateConsecutiveVolumeBars(bars, avgVolumePerBar);
        result.rsiRisingBars = calculateRsiRisingBars(bars, 14);
        result.higherLowFormed = detectHigherLow(bars);
        
        if (!bars.isEmpty()) {
            result.candleClosePosition = calculateCandleClosePosition(bars.get(0));
        }
        
        result.durabilityScore = calculateDurabilityScore(result);
    }

    /**
     * Generate a concise alert message for Telegram
     */
    public static String generateAlertMessage(ScanResult result) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(result.signalHebrew).append("\n");
        sb.append("📊 ").append(result.ticker).append("\n");
        sb.append("💰 מחיר: $").append(String.format("%.2f", result.currentPrice));
        sb.append(" (").append(result.priceChangePct >= 0 ? "+" : "")
          .append(String.format("%.1f%%", result.priceChangePct)).append(")\n");
        
        sb.append("📈 RVOL: ").append(String.format("%.1fx", result.rvol)).append("\n");
        sb.append("📊 RSI: ").append(String.format("%.0f", result.rsi)).append("\n");
        sb.append("📍 VWAP: $").append(String.format("%.2f", result.vwap));
        sb.append(result.aboveVwap ? " ✅" : " ❌").append("\n");
        
        if (result.marketSupport) {
            sb.append("🌍 שוק תומך (SPY: ").append(String.format("%.1f%%", result.spyChangePct)).append(")\n");
        } else {
            sb.append("⚠️ שוק חלש (SPY: ").append(String.format("%.1f%%", result.spyChangePct)).append(")\n");
        }
        
        if (!result.reasons.isEmpty()) {
            sb.append("\n✅ סיבות:\n");
            for (String reason : result.reasons) {
                sb.append("• ").append(reason).append("\n");
            }
        }
        
        if (!result.warnings.isEmpty()) {
            sb.append("\n⚠️ אזהרות:\n");
            for (String warning : result.warnings) {
                sb.append("• ").append(warning).append("\n");
            }
        }
        
        return sb.toString();
    }
}
