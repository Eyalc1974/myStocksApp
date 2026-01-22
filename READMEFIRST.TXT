



# 📊 MyStocksApp - Complete Application Documentation

## 🎯 Overview

**MyStocksApp** is a comprehensive stock analysis and trading decision support system built in Java. It combines fundamental analysis, technical analysis, sentiment analysis, and risk management to provide actionable trading recommendations.

**Tech Stack:** Java 17+, HTTP Server (com.sun.net.httpserver), Alpha Vantage API, JSON processing

**Main Entry Point:** [WebServer.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/WebServer.java:0:0-0:0) (Port 8099)

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         WebServer.java                          │
│                    (HTTP Server - Port 8099)                    │
├─────────────────────────────────────────────────────────────────┤
│  Pages: /dashboard, /favorites, /monitoring, /active-positions │
│         /alpha-agent, /settings, /scanner                       │
└─────────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  Data Fetcher   │  │  Analysis       │  │  Risk Mgmt      │
│  - Alpha Vantage│  │  - Technical    │  │  - Position Size│
│  - Price Data   │  │  - Fundamental  │  │  - Trailing Stop│
│  - News/Sent.   │  │  - Sentiment    │  │  - Backtester   │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

---

## 📁 File Structure & Components

### 🌐 Core Web Server

| File | Description |
|------|-------------|
| [WebServer.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/WebServer.java:0:0-0:0) | Main HTTP server with all web pages and API endpoints |
| [Main.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/Main.java:0:0-0:0) | Original console-based analysis runner |

### 📈 Technical Analysis Indicators

| File | Description |
|------|-------------|
| [RSI.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/RSI.java:0:0-0:0) | Relative Strength Index (0-100) |
| [MACD.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MACD.java:0:0-0:0) | Moving Average Convergence Divergence |
| [BollingerBands.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/BollingerBands.java:0:0-0:0) | Volatility bands around moving average |
| [ADX.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ADX.java:0:0-0:0) | Average Directional Index (trend strength) |
| [ATR.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ATR.java:0:0-0:0) | Average True Range (volatility) |
| [Stochastic.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/Stochastic.java:0:0-0:0) | Stochastic Oscillator |
| [CCI.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/CCI.java:0:0-0:0) | Commodity Channel Index |
| [OBV.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/OBV.java:0:0-0:0) | On-Balance Volume |
| [MFI.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MFI.java:0:0-0:0) | Money Flow Index |
| [CMF.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/CMF.java:0:0-0:0) | Chaikin Money Flow |
| [ADLine.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ADLine.java:0:0-0:0) | Accumulation/Distribution Line |
| [RelativeStrength.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/RelativeStrength.java:0:0-0:0) | Stock vs S&P 500 comparison |
| [FibonacciRetracement.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/FibonacciRetracement.java:0:0-0:0) | Fibonacci support/resistance levels |
| [PivotPoints.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/PivotPoints.java:0:0-0:0) | Pivot point calculations |

### 💰 Fundamental Analysis

| File | Description |
|------|-------------|
| [PiotroskiFScore.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/PiotroskiFScore.java:0:0-0:0) | Piotroski F-Score (0-9, financial health) |
| [AltmanZScore.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/AltmanZScore.java:0:0-0:0) | Altman Z-Score (bankruptcy risk) |
| [BeneishMScore.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/BeneishMScore.java:0:0-0:0) | Beneish M-Score (fraud detection) |
| [GrahamNumber.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/GrahamNumber.java:0:0-0:0) | Benjamin Graham intrinsic value |
| [GrahamValuation.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/GrahamValuation.java:0:0-0:0) | Graham-style valuation metrics |
| [DCFModel.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/DCFModel.java:0:0-0:0) | Discounted Cash Flow valuation |
| [DuPontAnalysis.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/DuPontAnalysis.java:0:0-0:0) | DuPont ROE decomposition |
| [EVSales.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/EVSales.java:0:0-0:0) | Enterprise Value to Sales |
| [SloanAnalysis.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/SloanAnalysis.java:0:0-0:0) | Sloan Accruals ratio |
| [WorkingCapitalEfficiency.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/WorkingCapitalEfficiency.java:0:0-0:0) | Cash Conversion Cycle |
| [ValueCreationAnalysis.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ValueCreationAnalysis.java:0:0-0:0) | ROIC vs WACC spread |
| [FundamentalAnalysis.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/FundamentalAnalysis.java:0:0-0:0) | Aggregated fundamental metrics |

### 🎯 Scoring & Decision Engine

| File | Description |
|------|-------------|
| [FinalScoringEngine.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/FinalScoringEngine.java:0:0-0:0) | Combines all metrics into 0-100 score |
| [ScoringConfig.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ScoringConfig.java:0:0-0:0) | Strategy modes: SWING_TRADER, VALUE, GROWTH, MOMENTUM |
| [EntryFilterValidator.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/EntryFilterValidator.java:0:0-0:0) | Validates entry conditions |
| [MarketRegimeFilter.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MarketRegimeFilter.java:0:0-0:0) | Bull/Bear market detection |

### 📊 Sentiment & Events

| File | Description |
|------|-------------|
| [SentimentModel.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/SentimentModel.java:0:0-0:0) | News sentiment analysis |
| [EventAnalysis.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/EventAnalysis.java:0:0-0:0) | Corporate events (earnings, dividends) |

### 🔍 Stock Scanning

| File | Description |
|------|-------------|
| [StockScannerRunner.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/StockScannerRunner.java:0:0-0:0) | Scans S&P 500/NASDAQ for opportunities |
| [LongTermCandidateFinder.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/LongTermCandidateFinder.java:0:0-0:0) | Finds long-term investment candidates |
| [StockAnalysisResult.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/StockAnalysisResult.java:0:0-0:0) | Data class for analysis results |

### 💼 Portfolio & Risk Management

| File | Description |
|------|-------------|
| [RiskManager.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/RiskManager.java:0:0-0:0) | Position sizing based on risk % |
| [TrailingStopLoss.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/TrailingStopLoss.java:0:0-0:0) | ATR-based trailing stop calculation |
| [ActivePositionsStore.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ActivePositionsStore.java:0:0-0:0) | Manages open positions with stops |
| [StrategyBacktester.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/StrategyBacktester.java:0:0-0:0) | Historical strategy simulation |
| [PortfolioWeeklySummary.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/PortfolioWeeklySummary.java:0:0-0:0) | Weekly performance reports |

### 📡 Data & Monitoring

| File | Description |
|------|-------------|
| [DataFetcher.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/DataFetcher.java:0:0-0:0) | Alpha Vantage API calls |
| [PriceJsonParser.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/PriceJsonParser.java:0:0-0:0) | JSON parsing for price data |
| [MonitoringStore.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MonitoringStore.java:0:0-0:0) | Stores monitored stocks |
| [MonitoringScheduler.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MonitoringScheduler.java:0:0-0:0) | Background monitoring jobs |
| [MonitoringAnalyzer.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MonitoringAnalyzer.java:0:0-0:0) | Analyzes monitored stocks |
| [MonitoringSnapshot.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MonitoringSnapshot.java:0:0-0:0) | Point-in-time monitoring data |
| [MonitoringAlphaVantageClient.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/MonitoringAlphaVantageClient.java:0:0-0:0) | API client for monitoring |
| [ApiUsageTracker.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ApiUsageTracker.java:0:0-0:0) | Tracks API call limits |

### 📐 Supporting Models

| File | Description |
|------|-------------|
| [TechnicalAnalysisModel.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/TechnicalAnalysisModel.java:0:0-0:0) | Technical analysis data model |
| [ValuationEngine.java](cci:7://file:///Users/eyal.cohen/IdeaProjects/MyStocksApp-dev/src/main/java/ValuationEngine.java:0:0-0:0) | Valuation calculations |

---

## 🌐 Web Pages & Endpoints

### Main Pages

| URL | Description |
|-----|-------------|
| `/dashboard` | Single stock analysis with all metrics |
| `/favorites` | GREEN stock recommendations with Entry/Stop/Target |
| `/monitoring` | Track multiple stocks over time |
| `/active-positions` | Manage open trades with trailing stops |
| `/alpha-agent` | AI-powered portfolio tracking |
| `/settings` | Configure scoring modes and thresholds |
| `/scanner` | Scan market for opportunities |

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/fetch-atr` | GET | Get ATR and current price for symbol |
| `/api/backtest` | GET | Run strategy backtest |
| `/run-main` | POST | Run full analysis on ticker |
| `/daily-green-run` | POST | Start daily GREEN scan |

---

## 📊 Scoring System

### Strategy Modes (configurable in /settings)

| Mode | Focus | Best For |
|------|-------|----------|
| `SWING_TRADER` | Technical + Momentum | Short-term trades (days-weeks) |
| `VALUE` | Fundamentals + Valuation | Long-term value investing |
| `GROWTH` | Growth metrics | High-growth companies |
| `MOMENTUM` | Price momentum + RS | Trend following |

### Score Calculation (0-100)

```
Final Score =
  Fundamental Score × fundamentalWeight +
  Technical Score × technicalWeight +
  Efficiency Score × efficiencyWeight +
  Graham Bonus (if applicable)

VETO (Score = 0) if:
  - M-Score > -1.78 (fraud risk)
  - Z-Score < 1.1 (bankruptcy risk)
```

### Recommendations

| Score | Recommendation |
|-------|----------------|
| ≥80 | 🚀 STRONG BUY |
| ≥60 | 🟢 BUY |
| ≥40 | 🟡 HOLD |
| <40 | 🔴 SELL/AVOID |

---

## 💰 Risk Management System

### Position Sizing Formula

```
Shares to Buy = (Account × Risk%) / (ATR × Multiplier)

Example:
  Account = $36,000
  Risk% = 1% → $360 max loss per trade
  ATR = $5
  Multiplier = 2.5 → Stop distance = $12.50

  Shares = $360 / $12.50 = 28 shares
```

### Trailing Stop-Loss

```
Initial Stop = Entry Price - (ATR × Multiplier)
Updated Stop = MAX(Current Stop, High Price - ATR × Multiplier)

The stop ONLY goes UP, never down → locks in profits
```

---

## 📊 Strategy Backtester

### Rules

| Parameter | Value | Description |
|-----------|-------|-------------|
| RS Entry | > 1.05 | Buy when stock outperforms S&P 500 by 5% |
| RS Exit | < 0.95 | Sell when stock underperforms by 5% |
| Stop Loss | ATR × 2.5 | Trailing stop distance |
| Risk/Trade | 1% | Max loss per trade |

### Metrics Calculated

- **Total Return** - Strategy profit/loss %
- **S&P 500 Return** - Benchmark comparison
- **Alpha** - Excess return vs market
- **Win Rate** - % of profitable trades
- **Max Drawdown** - Largest peak-to-trough decline
- **Sharpe Ratio** - Risk-adjusted return
- **Profit Factor** - Gross profit / Gross loss

---

## 🔄 Data Flow

```
1. User enters ticker on /dashboard
              ↓
2. DataFetcher calls Alpha Vantage API
   - Daily prices (TIME_SERIES_DAILY)
   - Income statement
   - Balance sheet
   - Cash flow
   - Earnings
   - News sentiment
              ↓
3. Technical indicators calculated
   - RSI, MACD, Bollinger, ATR, etc.
              ↓
4. Fundamental metrics calculated
   - F-Score, Z-Score, M-Score, DCF, etc.
              ↓
5. FinalScoringEngine combines all
   - Applies weights based on active mode
   - Checks VETO conditions
   - Generates 0-100 score
              ↓
6. Display on dashboard with:
   - Score and recommendation
   - Entry/Stop/Target prices
   - AI summary explanation
```

---

## 📁 Data Storage

| File | Location | Purpose |
|------|----------|---------|
| `favorites.txt` | Project root | Saved favorite tickers |
| `monitoring-stocks.txt` | Project root | Monitored ticker list |
| `active-positions.json` | Project root | Open positions with stops |
| `scoring-config.json` | Project root | Scoring mode settings |
| `monitoring-snapshots/` | Directory | Historical monitoring data |

---

## 🚀 Key Features Summary

1. **Multi-factor Stock Analysis** - Technical + Fundamental + Sentiment
2. **Configurable Strategy Modes** - SWING_TRADER, VALUE, GROWTH, MOMENTUM
3. **Risk Management** - Position sizing, trailing stops, max risk limits
4. **Strategy Backtesting** - Test rules on historical data
5. **Real-time Monitoring** - Track portfolio with alerts
6. **AI-powered Recommendations** - GREEN/YELLOW/RED with explanations
7. **Entry/Stop/Target Prices** - Actionable trading levels
8. **Market Regime Detection** - Bull/Bear market awareness
9. **Fraud/Bankruptcy Screening** - M-Score and Z-Score vetoes
10. **Weekly Portfolio Reports** - Performance summaries

---

## 🔧 Running the Application

```bash
# Start the server
java WebServer

# Access at:
http://localhost:8099/dashboard      # Main analysis
http://localhost:8099/favorites      # GREEN recommendations
http://localhost:8099/settings       # Configuration
```

---

**Version:** January 2026
**Author:** MyStocksApp Development Team