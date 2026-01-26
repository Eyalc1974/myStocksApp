import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Strategy Backtester - בודק אסטרטגיות על נתוני עבר
 * מריץ סימולציה של כללי המסחר שלנו (RS, Position Sizing, Stop Loss) על נתונים היסטוריים
 * ומשווה את הביצועים ל-S&P 500
 */
public class StrategyBacktester {

    // פרמטרים של האסטרטגיה - נטענים מההגדרות
    private double rsEntryThreshold;
    private double rsExitThreshold;
    private double riskPerTrade;
    private double atrMultiplier;
    private int atrPeriod;

    public StrategyBacktester() {
        // Load settings from ScoringConfig
        ScoringConfig.EntryFiltersConfig ef = ScoringConfig.getActiveEntryFilters();
        this.rsEntryThreshold = ef.rsEntryThreshold;
        this.rsExitThreshold = ef.rsExitThreshold;
        this.riskPerTrade = ef.riskPercentPerTrade / 100.0; // Convert from % to decimal
        this.atrMultiplier = ef.atrStopLossMultiplier;
        this.atrPeriod = ef.atrPeriod;
    }

    public StrategyBacktester(double rsEntry, double rsExit, double risk, double atrMult, int atrPer) {
        this.rsEntryThreshold = rsEntry;
        this.rsExitThreshold = rsExit;
        this.riskPerTrade = risk;
        this.atrMultiplier = atrMult;
        this.atrPeriod = atrPer;
    }

    /**
     * תוצאות הבקטסט
     */
    public static class BacktestResult {
        public String ticker;
        public double initialBalance;
        public double finalBalance;
        public double totalReturn;              // תשואה כוללת באחוזים
        public double spyReturn;                // תשואת S&P 500 לתקופה
        public double alpha;                    // אלפא = תשואה שלנו - תשואת השוק
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public double winRate;                  // אחוז הצלחה
        public double maxDrawdown;              // ירידה מקסימלית מהשיא
        public double sharpeRatio;              // יחס שארפ (תשואה/סיכון)
        public double profitFactor;             // רווח גולמי / הפסד גולמי
        public double avgWin;                   // רווח ממוצע
        public double avgLoss;                  // הפסד ממוצע
        public List<Trade> trades = new ArrayList<>();
        public List<EquityPoint> equityCurve = new ArrayList<>();
        public List<EquityPoint> spyCurve = new ArrayList<>();
        public String startDate;
        public String endDate;
        public int tradingDays;

        public String getSummaryHebrew() {
            String alphaColor = alpha >= 0 ? "ירוק" : "אדום";
            return String.format(
                "תשואה: %.1f%% | S&P 500: %.1f%% | אלפא: %.1f%% (%s)\n" +
                "עסקאות: %d (%.0f%% הצלחה) | Drawdown: %.1f%% | Sharpe: %.2f",
                totalReturn, spyReturn, alpha, alphaColor,
                totalTrades, winRate * 100, maxDrawdown, sharpeRatio
            );
        }
    }

    /**
     * עסקה בודדת
     */
    public static class Trade {
        public String type;          // BUY / SELL
        public int dayIndex;
        public String date;
        public double price;
        public int shares;
        public double stopLoss;
        public double profit;        // רווח/הפסד בעסקה (רק ל-SELL)
        public double profitPct;     // אחוז רווח/הפסד
        public String exitReason;    // STOP_LOSS / RS_WEAK / END_OF_DATA
    }

    /**
     * נקודה על גרף ההון
     */
    public static class EquityPoint {
        public int dayIndex;
        public String date;
        public double equity;
        public double drawdown;

        public EquityPoint(int dayIndex, String date, double equity, double drawdown) {
            this.dayIndex = dayIndex;
            this.date = date;
            this.equity = equity;
            this.drawdown = drawdown;
        }
    }

    /**
     * מריץ סימולציה מלאה על מניה בודדת
     */
    public BacktestResult runBacktest(String ticker, List<Double> prices, List<Double> highPrices,
                                      List<Double> lowPrices, List<Double> rsScores,
                                      List<Double> spyPrices, List<String> dates,
                                      double initialBalance) {
        
        BacktestResult result = new BacktestResult();
        result.ticker = ticker;
        result.initialBalance = initialBalance;
        result.tradingDays = prices.size();
        result.startDate = dates.isEmpty() ? "N/A" : dates.get(0);
        result.endDate = dates.isEmpty() ? "N/A" : dates.get(dates.size() - 1);

        double currentBalance = initialBalance;
        double peakBalance = initialBalance;
        double maxDrawdown = 0;
        int currentShares = 0;
        double entryPrice = 0;
        double stopLoss = 0;
        int entryDay = 0;

        List<Double> dailyReturns = new ArrayList<>();
        double grossProfit = 0;
        double grossLoss = 0;
        double totalWins = 0;
        double totalLosses = 0;

        // חישוב תשואת SPY
        double spyStartPrice = spyPrices.isEmpty() ? 1 : spyPrices.get(Math.min(20, spyPrices.size() - 1));
        double spyEndPrice = spyPrices.isEmpty() ? 1 : spyPrices.get(spyPrices.size() - 1);
        result.spyReturn = ((spyEndPrice - spyStartPrice) / spyStartPrice) * 100;

        // בניית גרף SPY
        for (int i = 20; i < spyPrices.size(); i++) {
            double spyEquity = initialBalance * (spyPrices.get(i) / spyStartPrice);
            String date = i < dates.size() ? dates.get(i) : "Day " + i;
            result.spyCurve.add(new EquityPoint(i, date, spyEquity, 0));
        }

        // לולאה על כל הימים
        for (int i = 20; i < prices.size(); i++) {
            double price = prices.get(i);
            double rs = (rsScores != null && i < rsScores.size()) ? rsScores.get(i) : 1.0;
            String date = i < dates.size() ? dates.get(i) : "Day " + i;

            // חישוב ערך התיק הנוכחי
            double portfolioValue = currentBalance + (currentShares * price);
            
            // עדכון Drawdown
            if (portfolioValue > peakBalance) {
                peakBalance = portfolioValue;
            }
            double drawdown = ((peakBalance - portfolioValue) / peakBalance) * 100;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }

            // הוספה לגרף ההון
            result.equityCurve.add(new EquityPoint(i, date, portfolioValue, drawdown));

            // --- כלל כניסה (BUY) ---
            if (currentShares == 0 && rs > rsEntryThreshold) {
                double atr = calculateATR(highPrices, lowPrices, prices, i, atrPeriod);
                if (atr <= 0) atr = price * 0.03; // fallback
                
                int sharesToBuy = RiskManager.calculatePositionSize(currentBalance, riskPerTrade, price, atr, atrMultiplier);
                
                if (sharesToBuy > 0 && currentBalance >= sharesToBuy * price) {
                    currentShares = sharesToBuy;
                    entryPrice = price;
                    stopLoss = price - (atr * atrMultiplier);
                    entryDay = i;
                    currentBalance -= (currentShares * price);

                    Trade trade = new Trade();
                    trade.type = "BUY";
                    trade.dayIndex = i;
                    trade.date = date;
                    trade.price = price;
                    trade.shares = currentShares;
                    trade.stopLoss = stopLoss;
                    result.trades.add(trade);
                }
            }

            // --- כלל יציאה (SELL) ---
            if (currentShares > 0) {
                String exitReason = null;
                
                if (price <= stopLoss) {
                    exitReason = "STOP_LOSS";
                } else if (rs < rsExitThreshold) {
                    exitReason = "RS_WEAK";
                } else if (i == prices.size() - 1) {
                    exitReason = "END_OF_DATA";
                }

                if (exitReason != null) {
                    double proceeds = currentShares * price;
                    double profit = (price - entryPrice) * currentShares;
                    double profitPct = ((price - entryPrice) / entryPrice) * 100;
                    currentBalance += proceeds;

                    Trade trade = new Trade();
                    trade.type = "SELL";
                    trade.dayIndex = i;
                    trade.date = date;
                    trade.price = price;
                    trade.shares = currentShares;
                    trade.profit = profit;
                    trade.profitPct = profitPct;
                    trade.exitReason = exitReason;
                    result.trades.add(trade);

                    if (profit >= 0) {
                        result.winningTrades++;
                        grossProfit += profit;
                        totalWins += profit;
                    } else {
                        result.losingTrades++;
                        grossLoss += Math.abs(profit);
                        totalLosses += Math.abs(profit);
                    }
                    result.totalTrades++;

                    currentShares = 0;
                    entryPrice = 0;
                } else {
                    // עדכון Trailing Stop
                    double atr = calculateATR(highPrices, lowPrices, prices, i, atrPeriod);
                    if (atr <= 0) atr = price * 0.03;
                    double newStop = price - (atr * atrMultiplier);
                    stopLoss = Math.max(stopLoss, newStop);
                }
            }
        }

        // חישוב תוצאות סופיות
        result.finalBalance = currentBalance + (currentShares * prices.get(prices.size() - 1));
        result.totalReturn = ((result.finalBalance - initialBalance) / initialBalance) * 100;
        result.alpha = result.totalReturn - result.spyReturn;
        result.maxDrawdown = maxDrawdown;
        result.winRate = result.totalTrades > 0 ? (double) result.winningTrades / result.totalTrades : 0;
        result.profitFactor = grossLoss > 0 ? grossProfit / grossLoss : grossProfit > 0 ? Double.POSITIVE_INFINITY : 0;
        result.avgWin = result.winningTrades > 0 ? totalWins / result.winningTrades : 0;
        result.avgLoss = result.losingTrades > 0 ? totalLosses / result.losingTrades : 0;

        // חישוב Sharpe Ratio (פשוט)
        if (!result.equityCurve.isEmpty()) {
            double avgReturn = result.totalReturn / result.tradingDays * 252; // תשואה שנתית
            double riskFreeRate = 4.0; // ריבית חסרת סיכון
            // פישוט - נניח volatility של 20%
            result.sharpeRatio = (avgReturn - riskFreeRate) / 20.0;
        }

        return result;
    }

    /**
     * חישוב ATR
     */
    private double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int index, int period) {
        if (index < period || highs.size() <= index || lows.size() <= index || closes.size() <= index) {
            return 0;
        }

        double sum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = i > 0 ? closes.get(i - 1) : closes.get(i);
            
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
        }
        return sum / period;
    }

    /**
     * חישוב Relative Strength לתקופה מסוימת
     */
    public static List<Double> calculateRSScores(List<Double> stockPrices, List<Double> spyPrices, int lookbackPeriod) {
        List<Double> rsScores = new ArrayList<>();
        
        for (int i = 0; i < stockPrices.size(); i++) {
            if (i < lookbackPeriod) {
                rsScores.add(1.0); // ברירת מחדל
            } else {
                double stockReturn = (stockPrices.get(i) - stockPrices.get(i - lookbackPeriod)) / stockPrices.get(i - lookbackPeriod);
                double spyReturn = (spyPrices.get(i) - spyPrices.get(i - lookbackPeriod)) / spyPrices.get(i - lookbackPeriod);
                double rs = (1 + stockReturn) / (1 + spyReturn);
                rsScores.add(rs);
            }
        }
        return rsScores;
    }

    // Setters לפרמטרים
    public void setRsEntryThreshold(double threshold) { this.rsEntryThreshold = threshold; }
    public void setRsExitThreshold(double threshold) { this.rsExitThreshold = threshold; }
    public void setRiskPerTrade(double risk) { this.riskPerTrade = risk; }
    public void setAtrMultiplier(double multiplier) { this.atrMultiplier = multiplier; }
    public void setAtrPeriod(int period) { this.atrPeriod = period; }

    /**
     * הרצת בקטסט עם נתונים מה-API - static version uses default config
     */
    public static BacktestResult runBacktestForTicker(String ticker, double initialBalance) throws Exception {
        StrategyBacktester backtester = new StrategyBacktester();
        return backtester.runBacktestForTickerInstance(ticker, initialBalance);
    }

    /**
     * הרצת בקטסט עם נתונים מה-API - instance version uses this instance's parameters
     */
    public BacktestResult runBacktestForTickerInstance(String ticker, double initialBalance) throws Exception {
        // טעינת נתונים היסטוריים
        DataFetcher.setTicker(ticker);
        String stockJson = DataFetcher.fetchStockData();
        DataFetcher.setTicker("SPY");
        String spyJson = DataFetcher.fetchStockData();

        if (stockJson == null || spyJson == null) {
            throw new Exception("Failed to fetch historical data");
        }

        List<Double> stockPrices = PriceJsonParser.extractClosingPrices(stockJson);
        List<Double> stockHighs = PriceJsonParser.extractHighPrices(stockJson);
        List<Double> stockLows = PriceJsonParser.extractLowPrices(stockJson);
        List<String> dates = PriceJsonParser.extractDates(stockJson);
        List<Double> spyPrices = PriceJsonParser.extractClosingPrices(spyJson);

        // הפיכת הסדר (מהישן לחדש)
        Collections.reverse(stockPrices);
        Collections.reverse(stockHighs);
        Collections.reverse(stockLows);
        Collections.reverse(dates);
        Collections.reverse(spyPrices);

        // יישור אורכים
        int minLen = Math.min(stockPrices.size(), spyPrices.size());
        stockPrices = stockPrices.subList(0, minLen);
        stockHighs = stockHighs.subList(0, minLen);
        stockLows = stockLows.subList(0, minLen);
        spyPrices = spyPrices.subList(0, minLen);
        dates = dates.subList(0, Math.min(dates.size(), minLen));

        // חישוב RS scores
        List<Double> rsScores = calculateRSScores(stockPrices, spyPrices, 63); // 3 חודשים

        return this.runBacktest(ticker, stockPrices, stockHighs, stockLows, rsScores, spyPrices, dates, initialBalance);
    }
}
