# AI Trading System - Scan Process Documentation

## Overview

The AI Trading System performs automated scans of the stock market to identify high-probability trading opportunities. The scan process is a multi-stage pipeline that combines technical analysis, fundamental data, catalyst detection, and institutional flow analysis to generate a **META SCORE** (Final Conviction) for each candidate.

---

## Scan Stages

### Stage 1: Pre-Fetch (Data Collection)

**Purpose**: Efficiently fetch price data for all tickers in parallel.

**What it does**:
- Fetches all tickers from the sector universe (~500+ stocks)
- Uses parallel threading with rate limiting (controlled by `AV_CALLS_PER_MIN` env var)
- Default: 5 calls/min (free tier) or 150 calls/min (premium)
- Stores pre-fetched JSON data in memory for subsequent stages
- Progress monitoring every 30 seconds

**Output**: `ConcurrentHashMap<String, String>` with ticker → OHLC data

---

### Stage 2: Technical Analysis

**Purpose**: Compute technical indicators for each ticker.

**What it checks**:
- **Volume Score** (0-3): Relative volume (RVOL) vs 20-day average
- **Trend Score** (0-3): Price position relative to SMAs (20, 50, 200)
- **Momentum Score** (0-3): RSI and CCI indicators
- **Setup Score** (0-3): Chart pattern detection (pullback, breakout, etc.)

**Total Technical Score**: 0-12 points

**Threshold**: Must score ≥ 10 to proceed (configurable via `SCORE_THRESHOLD`)

---

### Stage 3: Fundamental Analysis (Layer 2)

**Purpose**: Evaluate company fundamentals using Alpha Vantage OVERVIEW API.

**What it checks**:
- Revenue growth rate
- EPS growth
- Profit margin
- Operating margin
- P/E ratio
- Debt-to-equity ratio
- Analyst upside potential
- Institutional score
- Sector and industry classification

**Scoring**: 0-10 points (computed by `FundamentalData.computeScore()`)

**Caching**: Data cached for 7 days (TTL) to minimize API calls

---

### Stage 4: Catalyst Detection (Layer 3)

**Purpose**: Identify upcoming catalysts using Alpha Vantage NEWS_SENTIMENT API.

**What it checks**:
- News sentiment score (positive/negative bias)
- News count in last 24 hours
- Earnings beat/miss
- Guidance raise/cut
- Analyst upgrades/downgrades
- M&A rumors
- Product launches

**Scoring**: 0-10 points (computed by `CatalystData.computeScore()`)

**Caching**: Data cached for 24 hours (TTL) to minimize API calls

---

### Stage 5: Institutional Flow Analysis (Layer 4)

**Purpose**: Detect institutional footprint from price/volume patterns.

**What it checks**:
- Volume spikes on up days (institutional buying)
- Price action strength
- Accumulation/distribution patterns
- Gap analysis

**Scoring**: 0-10 points (computed from existing price/volume data, no API calls)

---

### Stage 6: Final Conviction (META SCORE)

**Purpose**: Combine all scores into a single conviction metric.

**Formula**:
```
Final Conviction = (Technical × 1.0) + (Momentum × 0.75) + (Fundamentals × 1.25) + (Catalyst × 1.75) + (Institutional Flow × 1.25)
```

**Scale**: 0-60 points (each component normalized to 0-10 before weighting)

**Example**:
- Technical: 10/12 → normalized 8.33 → weighted 8.33 × 1.0 = 8.33
- Momentum: 3/3 → normalized 10 → weighted 10 × 0.75 = 7.5
- Fundamentals: 8/10 → normalized 8 → weighted 8 × 1.25 = 10.0
- Catalyst: 9/10 → normalized 9 → weighted 9 × 1.75 = 15.75
- Institutional Flow: 7/10 → normalized 7 → weighted 7 × 1.25 = 8.75
- **Final Conviction = 50.33/60**

**Rationale**: Higher weight on catalyst (35%) and institutional flow (25%) to capture stocks with real narratives and smart money footprints, not just technical setups.

---

### Stage 7: Confluence Detection

**Purpose**: Identify when multiple strategies agree on the same ticker.

**What it does**:
- Counts how many master strategies score ≥ 7 for the same ticker
- If ≥ 2 strategies agree, applies **+2 confluence bonus**
- This bonus is added to the total score

**Threshold**: `CONFLUENCE_SCORE_THRESHOLD = 7`

---

### Stage 8: Ranking & Selection

**Purpose**: Rank all candidates and select the best ones.

**Process**:
1. **Deduplication**: If multiple strategies signal the same ticker, keep the highest-scoring one
2. **Ranking**: Sort by `finalConviction` (descending)
3. **TOP 2 Selection**: Take only the top 2 candidates (quality over quantity)
4. **Logging**: Display ranked list with score breakdown

**Why TOP 2?**
- Focuses capital on highest-conviction opportunities
- Reduces over-trading
- Improves risk-adjusted returns

---

### Stage 9: Portfolio Constraints

**Purpose**: Ensure portfolio diversification and risk management.

**Constraints**:
- **Max Positions**: 5 total open positions (global cap)
- **Sector Cap**: 1 position per sector (e.g., max 1 Technology, max 1 Healthcare)
- **Semiconductor Cap**: 1 position max (special sector cap)
- **Agent Cap**: Each agent limited to `maxOpenTrades` positions

**What it checks**:
- Current open positions count
- Open positions by sector
- Per-agent open positions
- Rejects candidates that would violate constraints

---

### Stage 10: Market Regime Filter

**Purpose**: Avoid trading during adverse market conditions.

**What it checks**:
- SPY price relative to SMA20 and SMA50
- Recent price change percentage
- VIX level (implied volatility)

**Regime Levels**:
- **HEALTHY**: Normal trading (100% position size)
- **WEAK**: Reduced trading (50% position size)
- **VERY_WEAK**: No trading (0% position size - crash mode)

**Impact**: If regime is VERY_WEAK, all signals are suppressed.

---

### Stage 11: Trade Execution

**Purpose**: Execute trades for the final selected candidates.

**What it does**:
- Checks if position already opened today (prevents duplicates)
- Calculates entry price, stop loss, and take profit
- Executes trade via broker API
- Logs trade details to `full-scan-trade-log.txt`
- Updates agent performance metrics
- Sends Discord notification

**Risk Management**:
- Stop loss: Typically 3-5% below entry (based on ATR)
- Take profit: Typically 8-12% above entry (2-3R reward)
- Max risk: $20 per $1,000 position (2% risk)

---

## Performance Metrics

### Expectancy (The Real Metric)

**Definition**: `(WinRate × AvgWin) - (LossRate × AvgLoss)`

**Why it matters**:
- Win rate alone is misleading (can have high win rate but small wins, large losses)
- Expectancy captures both win rate and reward/risk ratio
- Used for agent ranking and deletion decisions

**Thresholds**:
- Agent deletion: Expectancy < 0.0
- Winning agent notification: Expectancy ≥ 1.0%

---

## Scheduled Scans

### Strategic Scans (Israel Time)
1. **17:15-17:30**: After market open (most important)
2. **19:30-20:00**: Mid-day continuation setups
3. **21:30-22:00**: Before close (best swing trades)

### Periodic Scans
- Every 60 minutes during NASDAQ market hours (9:30 AM - 4:00 PM ET)
- Due to 15-minute data delay, 60-min interval is optimal

### Regime Checks
- Every 30 minutes during market hours
- Standalone SPY analysis (no full scan)
- Discord notification if regime changes

---

## Log Files

### scan-detail.log
- Detailed scan progress and results
- Rotated every 48 hours
- Location: `newStrategies/scan-detail.log`

### full-scan-trade-log.txt
- All trade executions
- Entry/exit prices, P/L, reasons
- Location: `newStrategies/full-scan-trade-log.txt`

### buy-recommendations.json
- Recent buy recommendations
- Includes score breakdown and conviction
- Location: `newStrategies/buy-recommendations.json`

---

## Configuration

### Environment Variables
- `AV_CALLS_PER_MIN`: Alpha Vantage API rate limit (default: 5)
- `AV_API_KEY`: Alpha Vantage API key

### Constants (in AIToolAgent.java)
```java
SCORE_THRESHOLD = 10              // Minimum score to trade
CONFLUENCE_SCORE_THRESHOLD = 7    // Minimum score for confluence
CONFLUENCE_BONUS = 2              // Bonus when ≥2 strategies agree
MAX_TRADES_PER_SCAN = 2           // TOP 2 only
MAX_OPEN_POSITIONS_TOTAL = 5      // Global position cap
MAX_OPEN_PER_SECTOR = 1           // Sector cap
MIN_STOP_LOSS_PCT = 3.0           // Minimum stop loss distance
STOCKS_PER_AGENT_RUN = 12         // Stocks per agent per run
```

---

## Key Classes

### AIToolAgent
- Main scan orchestration
- Technical indicator computation
- Trade execution
- Performance tracking

### InstitutionalFlowLayer
- Fundamental data fetching
- Catalyst detection
- Institutional flow analysis
- Final conviction calculation

### FundamentalData
- Fundamental metrics storage
- Score computation
- JSON parsing from Alpha Vantage

### CatalystData
- Catalyst metrics storage
- Score computation
- News sentiment parsing

### ActivePositionsStore
- Position tracking
- Position limit enforcement
- Trailing stop-loss management

---

## Recent Improvements (2026-05)

1. **Expectancy-Based Ranking**: Replaced win rate with expectancy for agent ranking
2. **Position Limits**: Max 5 positions, sector cap=1, semiconductor cap=1
3. **TOP 2 Selection**: Only trade the 2 highest-conviction candidates
4. **META SCORE**: Final conviction combining technical, momentum, fundamentals, catalyst
5. **Parallel Pre-Fetch**: Optimized data fetching with rate limiting
6. **Confluence Detection**: Bonus when multiple strategies agree

---

## Troubleshooting

### Scan Not Running
- Check if market hours (9:30 AM - 4:00 PM ET)
- Verify `AV_API_KEY` is set
- Check `scan-detail.log` for errors

### No Signals Generated
- Market regime may be VERY_WEAK
- No candidates scored ≥ 10
- Portfolio constraints blocking (max 5 positions)
- Check `scan-detail.log` for rejection reasons

### API Rate Limit Errors
- Reduce `AV_CALLS_PER_MIN` environment variable
- Upgrade to Alpha Vantage Premium tier
- Check for stuck threads in logs

---

## Contact & Support

For issues or questions, check:
1. `scan-detail.log` - Detailed scan logs
2. `full-scan-trade-log.txt` - Trade history
3. Discord notifications - Real-time alerts

---

*Last Updated: May 23, 2026*
