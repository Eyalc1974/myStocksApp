# MyStocksApp — Scan Flow & Filter Reference

## Overview

Every scan iterates over the full ticker universe **once**, sharing a single API call per ticker across all active agents. Agents are split into two independent pipelines:

- **Master Strategies** (MASTER_5/6/7/8) — score-based, require `masterStrategy: true`
- **Variant Agents** (M1/M2/M2V2/M4/M5/M5V2 + Intraday I1–I6) — binary pass/fail gate chain

---

## 1. Agent Loading (startup)

| File | Agents Loaded | Notes |
|---|---|---|
| `momentum-variants.json` | M1, M2, M2V2, M4, M5, M5_V2 | 6 MOMENTUM agents |
| `intraday-variants.json` | I1–I6 | 6 INTRADAY agents |
| `momentum-success-2026.json` | — | `enabled: false` → skipped |
| `swing-variants.json` | — | `enabled: false` → skipped |
| `newStrategies/MASTER_*.json` | MASTER_5, 6, 7, 8 | 4 master strategies |

**Total active: 16 agents**

> The `enabled: false` top-level flag in a variant file causes the entire file to be skipped at load time.

---

## 2. Scan Loop

```
For each ticker (1 API call, shared by all agents):
  │
  ├─ MASTER PATH  ──→  computeIndicators()  ──→  scoreStrategyForTicker()
  │
  └─ VARIANT PATH ──→  analyzeStock()  ──→  sequential gate chain
```

Rate limit: **12,500 ms sleep** between tickers (Alpha Vantage free tier = 5 calls/min).

---

## 3. Master Strategy Path (Score-Based)

### 3a. Indicator Computation (`computeIndicators`)

Computed once per ticker, shared across all master strategies:

| Indicator | Description |
|---|---|
| `currentPrice` | Latest closing price |
| `rsi` | RSI-14 |
| `sma20 / sma50 / sma200` | Simple moving averages |
| `rvol` | Today's volume / 20-day avg volume |
| `cci` | CCI-20 |
| `atr` | ATR-14 |
| `momentum20d` | `(currentPrice / price20dAgo - 1) × 100` (%) |
| `maCrossoverUp` | `sma20 > sma50` |
| `priceAboveSMA50/200` | Boolean flags |
| `resistance30d` | Highest close over prior 30 sessions (excl. today) |
| `week52High` | Highest high over prior 252 sessions |

### 3b. Scoring (`scoreStrategyForTicker`)

Each master strategy produces a score composed of four sub-scores (each 0–3):

```
totalScore = trendScore + volumeScore + momentumScore + setupScore
             (max = 12)
```

**Confluence Bonus**: If 2+ strategies score ≥ 7 on the same ticker → each gets **+2** added to its total.

**Trade triggered** when: `totalScore ≥ SCORE_THRESHOLD (10)`

**Global caps**:
- `MAX_TRADES_PER_SCAN = 5`
- `MAX_OPEN_POSITIONS_TOTAL = 5`
- `MAX_OPEN_PER_SECTOR = 2`

---

## 4. Variant Agent Path — 3-Layer Architecture

`analyzeStock(ticker, agent, json)` is split into two layers with distinct outcomes:

```
Layer 1: SCAN GATES (1–11)  →  hard reject if any fails  →  [❌ REJECT]
                ↓ all pass
Layer 2: ENTRY TRIGGERS (T1–T3)  →  defer if any fails  →  [⏳ WATCHLIST]
                ↓ all pass
                              →  confirmed trade          →  [✅ BUY SIGNAL]
```

**Key difference**: Scan gates eliminate bad candidates. Entry triggers only *delay* good candidates until market conditions confirm the entry. A stock on the watchlist is automatically promoted to a trade on the next scan when its trigger fires.

---

### Layer 1 — Scan Gates (Hard Reject)

| Gate | Filter Key | Formula / Logic |
|---|---|---|
| 1 | `rsiMin` / `rsiMax` | RSI-14 must be in range |
| 2 | *(always)* | `currentPrice > SMA20` |
| 3 | `cciMin` / `cciMax` | CCI-20 must be in range (if configured) |
| 4 | `atrMin` / `atrMax` | `ATR / price × 100` must be in range (if configured) |
| 5 | `rsMin` / `rsMax` | `currentPrice / price20DaysAgo` (20d momentum ratio) |
| 6 | `rvolMin` / `rvolMax` | `todayVolume / avg20dVolume` |
| 7 | `volumeThreshold` | Raw volume ≥ threshold (if configured) |
| 8 | `cmfMin` | Chaikin Money Flow ≥ value (if configured) |
| 9 | `sma200Required` | `currentPrice > SMA200` |
| 10 | `smaWindows` | Price above each SMA in window list (if configured) |
| 11 | `priceAboveVwapPct` | `price / VWAP ≥ 1 + pct` (if configured) |

> **Gate 5 — RS**: `RS = currentPrice / price20DaysAgo`. Value `1.05` means stock must be ≥5% above 20 days ago.

---

### Layer 2 — Entry Triggers (Defer to Watchlist)

SL/TP are **pre-calculated using the daily close** before trigger checks so watchlist entries always have meaningful risk levels. The live price fetch is deferred until all triggers pass.

| Trigger | Config Key | Logic | Trigger Price Set To |
|---|---|---|---|
| T1 | `maCrossoverRequired` | `SMA9 > SMA21` (bullish crossover) | `SMA21 × 1.002` |
| T2 | `prevHighBreakoutRequired` | `currentPrice ≥ prevDayHigh × 1.002` | `prevDayHigh × 1.002` |
| T3 | `distanceFromResistancePct` | `(R30d − price) / price × 100 ≥ minPct` | `R30d × 1.005` |

> **Watchlist flow**: Stocks with `triggerNotMet = true` are added to `pendingSignals` (status=WAITING). Each subsequent scan refreshes score, price, and trigger gap. After `MAX_WAIT_SCANS` without firing, status becomes EXPIRED.

> **T2 — Prev High Breakout**: Real breakout, not a guess. The stock must have already traded above yesterday's high.

> **T3 — 30d Resistance**: Uses the highest **close** over the prior 30 sessions (not 52-week high). Ensures at least X% upside room before next resistance.

**T4 — Entry Type Routing** (only active when `entryType ≠ AUTO`):

| Type | Execute when | Trigger price |
|---|---|---|
| `EARLY_BREAKOUT` | `price ≥ prevHigh × 1.003` AND `rvol > 1.5` | `prevHigh × 1.003` |
| `RETEST_BREAKOUT` | `peak(2–3d ago) > prevHigh × 1.01` AND `price ≥ prevHigh` | `prevHigh` |
| `TREND_CONTINUATION` | `price > SMA50` AND `RSI ∈ [50, 70]` | `max(price, SMA50) × 1.005` |

> **RETEST logic**: `peak = max(high[2d], high[3d])`. Stock broke out 2–3 days ago, pulled back yesterday, reclaiming today = best entry quality.

---

### Outcome States

```
shouldTrade = true           → [✅ BUY SIGNAL]  confirmPendingSignal() + executeTrade()
triggerNotMet = true         → [⏳ WATCHLIST]   upsertPendingSignal()
shouldTrade = false (scan)   → [❌ REJECT]      expirePendingSignal()
```

---

## 5. Active Agents — Filter Comparison

### Momentum Variants

| Filter | M1_AGGRESSIVE | M2_CONSERVATIVE | M2_CONSERVATIVE_V2 | M4_PULLBACK | M5_MA_CROSS | M5_MA_CROSS_V2 |
|---|---|---|---|---|---|---|
| RSI range | 30–90 | 30–85 | 40–75 | 35–65 | **45–70** | **45–70** |
| CCI range | 50–400 | 50–350 | 50–300 | 50–200 | 50–350 | **80–300** |
| RS min | 1.02 | 1.02 | 1.02 | 1.02 | 1.02 | **1.05** |
| RVOL min | 1.3× | 1.3× | 1.3× | 1.3× | 1.3× | **1.5×** |
| SMA200 required | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MA Crossover (9>21) | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |
| Prev High Breakout | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |
| Dist from R30d | — | — | — | — | ≥ 5% | — |

### Risk Management

| Parameter | M1 | M2 | M2V2 | M4 | M5 | M5_V2 |
|---|---|---|---|---|---|---|
| Stop Loss | 2.0% | 3.0% | 2.5% | 2.8% | **3.5%** | **3.5%** |
| Take Profit | 4.0% | 6.0% | 5.0% | 6.5% | **7.0%** | **7.0%** |
| Trailing Stop | 1.5% | 2.0% | 1.8% | 2.0% | 2.0% | 2.0% |
| R/R Ratio | 2.0 | 2.0 | 2.0 | 2.3 | 2.8 | 2.5 |

> **SL Floor**: `MIN_STOP_LOSS_PCT = 3.0%` — no agent can use a stop tighter than 3% from entry.

### Scoring Weights

| Weight | M1 | M2 | M2V2 | M4 | M5 | M5_V2 |
|---|---|---|---|---|---|---|
| CCI | 35 | 25 | 20 | 35 | 20 | 20 |
| RS | 40 | 50 | 55 | 40 | **45** | **45** |
| Volume | 25 | 25 | 25 | 25 | **25** | **25** |
| MA Cross Bonus | 10 | 5 | 3 | 4 | **10** | **10** |

---

## 6. Trade Execution & Exit

```
Signal passes all gates
  └→ executeTrade()
       ├─ SL  = entry × (1 - stopLossPct / 100)
       ├─ TP  = entry × (1 + takeProfitPct / 100)
       └─ ATR override: if atrMultiplier set → SL = entry - (ATR × multiplier)

Position monitoring (intraday + EOD):
  ├─ Price ≤ SL          → close at loss
  ├─ Price ≥ TP          → close at profit
  ├─ Top agent* + P/L ≥ $25 → auto-move SL to break-even
  └─ End of day          → close all remaining open positions
```

> **Top agent** = `winRate ≥ 70%` AND `totalTrades ≥ 3`

> **TP minimum floor**: `max(rawTP, entry + $25)` — at least $25 profit per trade regardless of % TP.

---

## 7. Market Regime Guard

Before any trade is executed, the system checks the current market regime using SPY:

| Regime | Condition | Position Size |
|---|---|---|
| `HEALTHY` | SPY above SMA20, change > −1% | 100% |
| `WEAK` | Mixed signals | 50% |
| `VERY_WEAK` | SPY below SMA20, change ≤ −2% | No trades |

---

## 8. Key Constants

| Constant | Value | Purpose |
|---|---|---|
| `SCORE_THRESHOLD` | 10 / 12 | Master strategy trade trigger |
| `CONFLUENCE_SCORE_THRESHOLD` | 7 | Threshold for confluence bonus |
| `CONFLUENCE_BONUS` | +2 | Score bonus when ≥2 strategies agree |
| `MAX_TRADES_PER_SCAN` | 5 | Max new trades per scan run |
| `MAX_OPEN_POSITIONS_TOTAL` | 5 | Global open position cap |
| `MAX_OPEN_PER_SECTOR` | 2 | Max simultaneous positions per sector |
| `MIN_STOP_LOSS_PCT` | 3.0% | Floor — no stop tighter than this |
| `RISK_PER_TRADE` | $500 | $ risk used for position sizing |
| API sleep | 12,500 ms | Between tickers (Alpha Vantage rate limit) |
