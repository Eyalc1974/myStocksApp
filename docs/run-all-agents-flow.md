# Run All Agents — Complete Flow

> Triggered by: `http://localhost:8099/aitool` → **▶️ Run All Agents Now**

---

## 1. Browser → HTTP Layer

```
User clicks ▶️ Run All Agents Now
    └── POST /aitool-run  (WebServer.java)
            └── AIToolAgent.runAgentsAsync()
                    └── scheduler.submit( runAllAgents() )  ← background thread
            └── HTTP 303 redirect → /aitool?started=true

Browser polls GET /aitool-run-status every 3s
    └── returns JSON: { running, progress, total, currentAgent, currentTicker }
    └── Updates progress bar + status label in UI
```

---

## 2. Guard & Initialization (`runAllAgents`)

```
Is systemState null?
    YES → initialize()
            ├── Load all agent JSON files from newStrategies/
            ├── Populate systemState.agents  (Map<id, AgentConfig>)
            └── Start 30-min regime check scheduler

Is systemState.running == true?
    YES → log "Already running, skip" + Discord alert → RETURN
    NO  → set running=true, increment runCount
```

---

## 3. Setup

```
allTickers  = LongTermCandidateFinder.getAllSectorTickers()
             (~100+ tickers across TECHNOLOGY, FINANCE, HEALTHCARE,
              ENERGY, INDUSTRIALS, CONSUMER_DISC, CONSUMER_STAPLES,
              UTILITIES, MATERIALS, REAL_ESTATE, COMMUNICATION)

allAgents   = systemState.agents.values()

masterStrategies = agents WHERE masterStrategy=true AND strategyType≠null AND disabled=false
                   sorted by winRate DESC (agents with ≥3 trades ranked first)

legacyAgents     = agents WHERE masterStrategy=false
                   sorted by winRate DESC

useMasters = masterStrategies is not empty  (always true in current setup)

Discord: "🔍 Scan Started — Run #N | Scanning X tickers | ETA ~Y min"
```

### Active Master Strategies (as of March 2026)

| ID | strategyType | SL% | TP% | scoreThreshold |
|----|---|---|---|---|
| MASTER_2 | PULLBACK | 3.5% | 10.5% | 10 |
| MASTER_3 | TREND_CONTINUATION | 3.5% | 13% | 10 |
| MASTER_4 | WEEK52_HIGH_MOMENTUM | 6% | 18% | 10 |
| MASTER_5 | PULLBACK_MA20 | 4% | 12% | 10 |
| MASTER_6 | VOLUME_BREAKOUT | 5% | 15% | 10 |
| MASTER_8 | STRONG_TREND | 5% | 15% | **11** |

> MASTER_1 (MOMENTUM_BREAKOUT) is `disabled=true` and never runs.  
> MASTER_7 is a market filter only — no trades.

---

## 4. Per-Ticker Loop

Repeat for every ticker (12.5 s sleep between each — Alpha Vantage rate limit):

```
┌─────────────────────────────────────────────────────────┐
│  DataFetcher.fetchStockData(ticker)                      │
│      └── Alpha Vantage TIME_SERIES_DAILY (OHLCV)        │
│      └── FAIL? → log [FETCH FAIL], sleep 12.5s, skip   │
│                                                          │
│  computeIndicators(ticker, json)  →  IndicatorData       │
│      ├── currentPrice, prevClose, todayChangePct         │
│      ├── sma20, sma50, sma200                            │
│      ├── priceAboveSMA20/50/200, maCrossoverUp           │
│      ├── rsi, cci, atrPct                                │
│      ├── rvol  (today vol / 20-day avg vol)              │
│      ├── typicalPrice (VWAP proxy)                       │
│      ├── vwapPct  (% above/below VWAP)                  │
│      ├── resistance30d  (highest close, last 30 days)    │
│      ├── week52High, pctFromWeek52High                   │
│      ├── momentum20d, high20d, prevHigh                  │
│      └── valid = true/false                              │
│                                                          │
│  Update RS score cache in LongTermCandidateFinder        │
│      rsScore = momentum20d                               │
│              + (aboveSMA50  ? +4 : -4)                   │
│              + (aboveSMA200 ? +5 : -5)                   │
│              + (rvol > 2.0  ? +2 :  0)                   │
│                                                          │
│  data.valid == false?  → log [NULL], sleep, skip         │
└─────────────────────────────────────────────────────────┘
```

---

## 5. Score Each Master Strategy → `scoreStrategyForTicker()`

For **each** master strategy, independently:

```
┌─────────────────────────────────────────────────────────────────────┐
│  STEP A — ATR Filter (hard gate)                                     │
│      strategy.entryFilters["atrMinPct"] > 0                         │
│      AND data.atrPct < atrMinPct  →  [❌ ATR] reject, no score      │
│                                                                      │
│  STEP B — Dispatch to strategy scoring function                      │
│      PULLBACK           → scorePullback()                            │
│      TREND_CONTINUATION → scoreTrendContinuation()                   │
│      WEEK52_HIGH_MOMENTUM→ score52WeekHighMomentum()                 │
│      PULLBACK_MA20      → scorePullbackMA20()                        │
│      VOLUME_BREAKOUT    → scoreVolumeBreakout()                      │
│      STRONG_TREND       → scoreStrongTrend()                         │
│                                                                      │
│  Each function scores 4 groups (max 3 pts each = 12 total):         │
│      Volume   score  0–3  (RVOL vs threshold)                        │
│      Trend    score  0–3  (SMA200/50 alignment)                      │
│      Momentum score  0–3  (RSI zone, VWAP, CCI)                     │
│      Setup    score  0–3  (strategy-specific quality)                │
│                                                                      │
│  Each function also sets:                                            │
│      dec.entryPrice         (current price)                          │
│      dec.suggestedStopLoss  (entry × (1 - SL%))                     │
│      dec.suggestedTakeProfit= max(entry × (1 + TP%), entry + $25)   │
│      dec.entryTriggerPrice  (level price must break to enter)        │
│                                                                      │
│  STEP C — Move Potential (non-breakout strategies only)              │
│      upsideToResistance = (resistance30d - price) / price × 100     │
│      ≤  0%  →  totalScore -= 2  (at or above resistance)            │
│       < 3%  →  totalScore -= 1  (almost no room)                    │
│      ≥ 10%  →  totalScore += 1  (lots of room to run)               │
│      Logged as [MOVE-POTENTIAL]                                      │
│                                                                      │
│  STEP D — Market Regime Penalty                                      │
│      lastKnownRegime == WEAK  →  totalScore -= 2                     │
│      (VERY_WEAK blocks execution later; HEALTHY = no change)         │
│      Logged as [REGIME-PENALTY]                                      │
│                                                                      │
│  STEP E — Execution Layer (Trigger Gate)                             │
│      VOLUME_BREAKOUT / WEEK52_HIGH_MOMENTUM:                         │
│          price < entryTriggerPrice   → triggerNotMet (wait)          │
│          price > trigger × 1.06     → triggerNotMet (overextended)  │
│      PULLBACK:                                                       │
│          price < typicalPrice (VWAP) → triggerNotMet (bounce unconfirmed)│
│      PULLBACK_MA20:                                                  │
│          price < sma20               → triggerNotMet (SMA20 not reclaimed)│
│      TREND_CONTINUATION:                                             │
│          todayChangePct < +0.5%      → triggerNotMet (no momentum)  │
│          todayChangePct > +3.0%      → triggerNotMet (blow-off)     │
│      STRONG_TREND:                                                   │
│          price < prevHigh × 1.001    → triggerNotMet (no breakout)  │
│      Logged as [⏳ TRIGGER-WAIT]                                     │
│                                                                      │
│  Returns:  TradeDecision { totalScore, volumeScore, trendScore,      │
│                            momentumScore, setupScore,                │
│                            entryPrice, suggestedSL, suggestedTP,     │
│                            entryTriggerPrice, triggerNotMet,         │
│                            rejectReason }                            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. Confluence Detection

After all strategies score the same ticker:

```
confluenceCount = count of strategies where totalScore >= 7

confluenceCount >= 2 ?
    YES → each qualifying strategy gets  totalScore += 2  (CONFLUENCE bonus)
          logged as 🔥CONFLUENCE(+2)
```

---

## 7. shouldTrade Decision

```
For each strategy decision on this ticker:

    shouldTrade = (totalScore >= 10) AND (triggerNotMet == false)

    shouldTrade == true   → log [✅ SIGNAL], add to allSignals list
    triggerNotMet == true → log [⏳ TRIGGER-WAIT]  (already logged inside step E)
    score too low         → log [❌ SCORE]
```

---

## 8. Portfolio Manager (runs after all tickers scanned)

### Step 1 — Dedup
```
allSignals sorted by totalScore DESC

Same ticker scored by multiple strategies?
    Keep the one with the highest score
    Drop the rest → log [DEDUP] dropped/replaced

Result: dedupedSignals (unique tickers, best strategy each)
Log ranked list: [RANK #1] TICKER | STRATEGY | Score=N/12 | Sector=X
```

### Step 2 — Portfolio Constraints Filter
```
currentOpenTotal = count of all open positions across all agents
sectorOpenCount  = Map<sector, openCount>

For each signal in rank order:
    ❌ totalOpen + newSignals >= MAX_OPEN_POSITIONS_TOTAL (5)  → skip
    ❌ agent already at maxOpenTrades (default 5 per agent)    → skip
    ❌ sector already at MAX_OPEN_PER_SECTOR (2)               → skip
    ✅ otherwise → add to topSignals, reserve sector slot

Log:
    [PORTFOLIO] ❌ TICKER | reason
    [PORTFOLIO] ✅ TICKER | Score=N/12 | STRATEGY | Sector=X (current/max)
    [PORTFOLIO] Filter result: X → Y signal(s) approved
```

### Step 3 — Market Regime Gate
```
checkMarketRegime()  (fetches SPY, computes SMA20)

    SPY above SMA20 AND today > -1%  →  HEALTHY  (full size, 100%)
    SPY below SMA20 AND today ≤ -2%  →  VERY_WEAK (no trades)
    anything else                    →  WEAK (50% size)

VERY_WEAK  →  ALL signals suppressed, log [REGIME] ⛔
WEAK       →  trades execute at 50% position size, log [WEAK regime]
HEALTHY    →  trades execute at full size
```

### Step 4 — Execution
```
For each signal in topSignals:

    hasOpenPositionToday(strategy, ticker)?
        YES → log ⚠️ already open today, skip

    sendBuyAlertIfMonitored()   (Discord alert if ticker is watched)

    executeTrade(strategy, ticker, decision)
        ├── entryPrice  = decision.entryPrice  (close from scan)
        ├── stopLoss    = max(suggestedSL,  entry × (1 - 4%))   ← min 4% floor
        ├── takeProfit  = max(suggestedTP,  entry + $25)         ← min $25 floor
        ├── sector      = LongTermCandidateFinder.getSectorForTicker()
        ├── status      = "OPEN"
        └── scores recorded (V/T/M/S, totalScore, confluenceCount)

    log [EXECUTE] ✅ TRADE OPENED: TICKER | STRATEGY | Entry=$X | SL=$Y (Z%) | TP=$W (V%)
    logBuySignal(), logTradeExecuted()
    updatePerformance()
    totalSignals++

Discord: sendRankedScanSummary()  (summary of all signals + which were executed)
```

---

## 9. Scan Complete

```
runProgress = total
systemState.running = false
systemState.lastRunTime = now

log [SCAN END] Run #N | Signals: X | Tickers scanned: Y
log ════════════════════════════════════════

evolveUnderperformingAgents()   (auto-tune losing agents)
autoTrackWinners()              (start monitoring top-performing tickers)

UI poll detects running=false → progress bar stops, Run button re-enabled
```

---

## Key Constants

| Constant | Value | Meaning |
|---|---|---|
| `SCORE_THRESHOLD` | 10 | Minimum score to generate a signal |
| `CONFLUENCE_SCORE_THRESHOLD` | 7 | Score needed to count toward confluence |
| `CONFLUENCE_BONUS` | +2 | Points added when 2+ strategies agree |
| `MAX_OPEN_POSITIONS_TOTAL` | 5 | Global cap: open trades across all agents |
| `MAX_OPEN_PER_SECTOR` | 2 | Max simultaneous trades per sector |
| `MIN_STOP_LOSS_PCT` | 4% | Minimum SL distance (enforced in executeTrade) |
| Min TP floor | $25 | Minimum take profit in dollar terms |
| API sleep | 12,500 ms | Delay between tickers (Alpha Vantage free tier) |

---

## Scan Log Prefix Reference

| Prefix | Meaning |
|---|---|
| `[SCAN START]` | Scan begun, tickers + agents count |
| `[SCAN MODE]` | MASTER STRATEGY or LEGACY AGENTS mode |
| `[FETCH FAIL]` | Alpha Vantage returned no data for ticker |
| `[NULL]` | Insufficient data to compute indicators |
| `[❌ ATR]` | Stock too slow (ATR below minimum) |
| `[❌ SCORE]` | Score below threshold (with breakdown) |
| `[⏳ TRIGGER-WAIT]` | Score passed but price hasn't hit entry trigger |
| `[MOVE-POTENTIAL]` | Upside-to-resistance bonus or penalty applied |
| `[REGIME-PENALTY]` | -2 pts applied due to WEAK market |
| `[✅ SIGNAL]` | Signal passed all gates, queued for portfolio check |
| `[DEDUP]` | Duplicate ticker removed across strategies |
| `[RANK #N]` | Ranked signal list after dedup |
| `[PORTFOLIO] ✅` | Signal passed all portfolio constraints |
| `[PORTFOLIO] ❌` | Signal blocked by portfolio constraint |
| `[REGIME]` | SPY market regime check result |
| `[EXECUTE] ✅` | Trade successfully opened |
| `[EXECUTE] ⚠️` | Trade skipped (already open or null result) |
| `[SCAN END]` | Scan complete summary |
| `[SKIP]` | Ticker/strategy already has open position today |
