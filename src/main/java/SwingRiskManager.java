/**
 * SwingRiskManager - Advanced risk management for swing trading (2-5 day holds)
 * 
 * Implements:
 * - ATR-based stop loss using daily ATR (not intraday)
 * - Structural stop anchoring below recent swing lows
 * - Volatility-adjusted position sizing with fixed dollar risk
 * - Position notional hard cap to prevent over-leverage
 * - Risk percentage filtering (3-6% range)
 */
public class SwingRiskManager {

    // Risk parameters
    private static final double MAX_RISK_PER_TRADE_USD = 30.0;  // Maximum dollar risk per trade
    private static final double MAX_POSITION_NOTIONAL_USD = 1050.0;  // Hard cap for position size
    private static final double ATR_MULTIPLIER = 2.0;  // ATR multiplier for swing trades (2-5 days)
    private static final double MIN_RISK_PERCENT = 3.0;  // Minimum risk percentage (3%)
    private static final double MAX_RISK_PERCENT = 6.0;  // Maximum risk percentage (6%)
    private static final double RISK_REWARD_RATIO = 2.5;  // Target risk/reward ratio
    
    /**
     * Order plan result class
     */
    public static class OrderPlan {
        public final int shares;
        public final double stopLossPrice;
        public final double takeProfitPrice;
        public final double riskPerShare;
        public final double riskPercent;
        public final double totalInvestment;
        public final double maxLoss;
        public final String rejectionReason;
        
        public OrderPlan(int shares, double stopLossPrice, double takeProfitPrice, 
                        double riskPerShare, double riskPercent, double totalInvestment, 
                        double maxLoss, String rejectionReason) {
            this.shares = shares;
            this.stopLossPrice = stopLossPrice;
            this.takeProfitPrice = takeProfitPrice;
            this.riskPerShare = riskPerShare;
            this.riskPercent = riskPercent;
            this.totalInvestment = totalInvestment;
            this.maxLoss = maxLoss;
            this.rejectionReason = rejectionReason;
        }
        
        public boolean isAccepted() {
            return rejectionReason == null && shares > 0;
        }
        
        public String getSummary() {
            if (!isAccepted()) {
                return String.format("REJECTED: %s (Risk: %.2f%%)", rejectionReason, riskPercent);
            }
            return String.format(
                "Shares: %d | Entry: $%.2f | Stop: $%.2f | Target: $%.2f | Risk: %.2f%% | Max Loss: $%.2f | Investment: $%.2f",
                shares, (stopLossPrice + riskPerShare), stopLossPrice, takeProfitPrice, 
                riskPercent, maxLoss, totalInvestment
            );
        }
    }
    
    /**
     * Calculate swing trading order plan with ATR-based risk management
     * 
     * @param ticker Stock symbol
     * @param currentPrice Current market price
     * @param dailyAtr14 Daily ATR (14-period) - MUST be from daily bars, not intraday
     * @param lowestLow3Days Lowest price in the last 3 trading days
     * @return OrderPlan with calculated position size, stop loss, and take profit
     */
    public static OrderPlan calculateSwingExit(String ticker, double currentPrice, 
                                               double dailyAtr14, double lowestLow3Days) {
        // Validate inputs
        if (currentPrice <= 0 || dailyAtr14 <= 0 || lowestLow3Days <= 0) {
            return new OrderPlan(0, 0, 0, 0, 0, 0, 0, "Invalid input parameters");
        }
        
        // 1. Calculate structural stop (anchored below recent swing low)
        double structuralStop = lowestLow3Days - (0.5 * dailyAtr14);
        
        // 2. Calculate pure ATR stop (simple multiple below entry)
        double pureAtrStop = currentPrice - (ATR_MULTIPLIER * dailyAtr14);
        
        // 3. Use the more conservative (lower) stop price
        double stopLossPrice = Math.min(structuralStop, pureAtrStop);
        
        // 4. Calculate risk per share
        double riskPerShare = currentPrice - stopLossPrice;
        
        if (riskPerShare <= 0) {
            return new OrderPlan(0, 0, 0, 0, 0, 0, 0, "Invalid risk calculation (stop above entry)");
        }
        
        // 5. Calculate risk percentage
        double riskPercent = (riskPerShare / currentPrice) * 100.0;
        
        // 6. Filter trades with risk outside acceptable range (3-6%)
        if (riskPercent < MIN_RISK_PERCENT) {
            return new OrderPlan(0, stopLossPrice, 0, riskPerShare, riskPercent, 0, 0, 
                String.format("Risk too low (%.2f%% < %.2f%%)", riskPercent, MIN_RISK_PERCENT));
        }
        
        if (riskPercent > MAX_RISK_PERCENT) {
            return new OrderPlan(0, stopLossPrice, 0, riskPerShare, riskPercent, 0, 0, 
                String.format("Risk too high (%.2f%% > %.2f%%)", riskPercent, MAX_RISK_PERCENT));
        }
        
        // 7. Calculate shares based on fixed dollar risk
        int sharesByRisk = (int) (MAX_RISK_PER_TRADE_USD / riskPerShare);
        
        // 8. Enforce hard cap on position notional value
        int sharesByCapital = (int) (MAX_POSITION_NOTIONAL_USD / currentPrice);
        int finalShares = Math.min(sharesByRisk, sharesByCapital);
        
        if (finalShares <= 0) {
            return new OrderPlan(0, stopLossPrice, 0, riskPerShare, riskPercent, 0, 0, 
                "Position size too small (risk or capital constraint)");
        }
        
        // 9. Calculate take profit (2.5x risk with 6% floor)
        double targetGainDollars = Math.max(riskPerShare * RISK_REWARD_RATIO, currentPrice * 0.06);
        double takeProfitPrice = currentPrice + targetGainDollars;
        
        // 10. Calculate final metrics
        double totalInvestment = finalShares * currentPrice;
        double maxLoss = finalShares * riskPerShare;
        
        return new OrderPlan(finalShares, stopLossPrice, takeProfitPrice, 
                            riskPerShare, riskPercent, totalInvestment, maxLoss, null);
    }
    
    /**
     * Calculate limit order price for entry
     * Uses the closing price of the signal candle or slight pullback
     * 
     * @param signalCandleClose Closing price of the candle that generated the signal
     * @param pullbackPct Optional pullback percentage (e.g., 0.5% for slight pullback)
     * @return Limit price for buy order
     */
    public static double calculateLimitBuyPrice(double signalCandleClose, double pullbackPct) {
        if (pullbackPct > 0) {
            return signalCandleClose * (1 - pullbackPct / 100.0);
        }
        return signalCandleClose;
    }
    
    /**
     * Get the maximum position notional cap
     */
    public static double getMaxPositionNotional() {
        return MAX_POSITION_NOTIONAL_USD;
    }
    
    /**
     * Get the maximum risk per trade in dollars
     */
    public static double getMaxRiskPerTrade() {
        return MAX_RISK_PER_TRADE_USD;
    }
    
    /**
     * Get the ATR multiplier used for swing trades
     */
    public static double getAtrMultiplier() {
        return ATR_MULTIPLIER;
    }
}
