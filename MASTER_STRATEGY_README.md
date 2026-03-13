# Master Strategy Architecture

## Overview

The system was refactored from **30+ overlapping individual agents** into **3 focused master strategies** with a scoring model. This eliminates duplicate signals, reduces noise, and makes the system far easier to optimize.

**Current mode: Swing Trading Only** — intraday/momentum strategy is disabled. The system is tuned for 2–5 day holds, compatible with 15-minute delayed data and manual execution.

---

## Why Swing Trading

With a $3k–$7k account, manual execution, and 15-minute delayed data, intraday momentum trading is unreliable:

```
Momentum timeline (what the market does):
  10:05 breakout → 10:07 big move → 10:10 pullback → 10:15 reversal

You see the signal at 10:20 (15-min delay) — you are buying the top.
```

Swing trading works well with these constraints:
- Check the market once before open, once after close
- Wider stops absorb normal daily noise
- Entry triggers prevent chasing extended moves

---

## The New Architecture: 3 Master Strategies

Each strategy uses a **scoring model** instead of binary pass/fail. Every ticker gets scored 0–12. A trade is only executed if the score reaches the threshold.

```
Score = volumeScore(0-3) + trendScore(0-3) + momentumScore(0-3) + setupScore(0-3)

Trade fires if: totalScore >= 10
```

---

### Strategy 1 — Momentum Breakout (`MASTER_1_MOMENTUM_BREAKOUT`) — ⛔ DISABLED

**Reason disabled:** Requires real-time data. With 15-minute delay you are consistently buying the top of intraday moves. Use `PULLBACK` or `TREND_CONTINUATION` instead.

> To re-enable: set `"disabled": false` in `MASTER_1_MOMENTUM_BREAKOUT.json`

| Sub-score | What it measures | Points |
|-----------|-----------------|--------|
| Volume | RVOL ≥ 3.0 / ≥ 2.0 / ≥ 1.5 | +3 / +2 / +1 |
| Trend | Price above SMA200 (+2), SMA20 > SMA50 (+1) | max 3 |
| Momentum | RSI 55–80 (+2), price above VWAP (+1) | max 3 |
| Setup | Today's change ≥ 2% (+2) / ≥ 1% (+1), CCI > 50 (+1) | max 3 |

---

### Strategy 2 — Pullback to Support (`MASTER_2_PULLBACK`) — ✅ ACTIVE

**Idea:** Stock is in an uptrend, pulling back toward VWAP/support. Buy when volume dries up and price stabilises — the pullback setup for swing traders.

**Expected hold: 2–4 days**

| Sub-score | What it measures | Points |
|-----------|-----------------|--------|
| Trend | Price above SMA200 (+2), SMA20 > SMA50 (+1) | max 3 |
| Setup | Price within 0.3% of VWAP (+3) / 0.8% (+2) / 2% (+1); penalised if up >1.5% today | max 3 |
| Momentum | RSI 40–62 (+2) / 35–68 (+1), CCI 20–120 (+1) | max 3 |
| Volume | RVOL 0.5–1.3 = drying (+3), 0.3–1.8 (+2), > 0 (+1) | max 3 |

> Volume *drying* confirms sellers are exhausted. A stock up >1.5% today loses a setup point — that is no longer a pullback.

**Risk Management:**
- Stop Loss: **3.5%** (swing-appropriate; was 1.5% intraday)
- Take Profit: **10.5%** (3:1 R:R)
- Trailing Stop: 2.5%
- ATR Multiplier: 2.5×

**Entry Trigger (swing confirmation):**
```
Price at/below VWAP  →  BUY ONLY IF price breaks back above vwap × 1.003
Price near VWAP      →  BUY ONLY IF price breaks above current × 1.002
```
Do not buy immediately. Wait for the price to reclaim the level.

---

### Strategy 3 — Trend Continuation (`MASTER_3_TREND_CONTINUATION`) — ✅ ACTIVE

**Idea:** Strong multi-day trend continuing. Full SMA alignment, healthy RSI, and expanding volume. Wider targets for swing/multi-day holds.

**Expected hold: 3–5 days**

| Sub-score | What it measures | Points |
|-----------|-----------------|--------|
| Trend | Full SMA20 > SMA50 > SMA200 alignment (+3), partial (+2/+1) | max 3 |
| Momentum | RSI 58–72 (+2) / 50–78 (+1), CCI 50–200 (+1) | max 3 |
| Volume | RVOL ≥ 2.0 (+3) / ≥ 1.5 (+2) / ≥ 1.1 (+1) | max 3 |
| Setup | Price ≥ 5% above SMA50 (+2) / ≥ 2% (+1), daily change 0.5–3% (+1) | max 3 |

> Daily change capped at 3% — avoids buying blowoff tops on day 1 of a spike.

**Risk Management:**
- Stop Loss: 3.5%
- Take Profit: 13%
- Trailing Stop: 3%
- Risk/Reward: 3.5:1

**Entry Trigger (swing confirmation):**
```
BUY ONLY IF price breaks above current close × 1.005  (+0.5%)
```
This confirms momentum is real, not just a data artefact.

---

## Entry Trigger System

Every signal includes an `entryTriggerPrice` — the level the stock must **break and close above** before you buy.

**Why this matters:**
```
Without trigger:  pullback → you buy → stock drops more → STOP
With trigger:     pullback → price stabilises → breakout → you buy → trend resumes
```

Discord signals display:
```
⚡ BUY ONLY IF breaks $200.30  (current $200.05)
```

Never buy at the scan price. Wait for next-day confirmation above the trigger.

---

## Confluence Detection

When **2 active strategies** each score ≥ 7 on the **same ticker** in the same scan, a confluence bonus is applied:

```
confluenceBonus = +2 added to every strategy's score for that ticker
```

A stock that qualifies as both a Pullback AND a Trend Continuation simultaneously is a high-conviction setup.

**Discord alerts show:**
```
📊 Score: 12/12 (V:3 T:3 M:3 S:3 🔥+2 CONFLUENCE)
```

---

## How the Scan Works

```
For each ticker (fetched once, shared by all active strategies):
  1. Compute all indicators once: RSI, SMA20/50/200, RVOL, CCI, VWAP%, daily change, momentum20d
  2. Update RS ranking cache (momentum20d + SMA position + RVOL bonus)
  3. Score each active (non-disabled) master strategy independently (0–12)
  4. Count how many strategies scored >= 7 (confluence check)
  5. If 2+ strategies scored >= 7 → add +2 bonus to all, record confluenceCount
  6. If final score >= 10 → add to allSignals list (NOT traded yet)

After all tickers scanned:
  7. Sort allSignals by totalScore descending
  8. Take top MAX_TRADES_PER_SCAN (2) signals → topSignals
  9. Send ranked Discord swing summary (always, even if no trades execute)
  10. Check market regime (SPY above SMA20 AND not down > 2%)
  11. If regime OK → execute topSignals; if not → send ⛔ alert, skip execution
```

No redundant API calls. No duplicated indicator computation. One ticker fetch per scan cycle.

---

## Signal Ranking & Trade Cap

- All qualifying signals (score ≥ 10) are **collected** during the scan, never traded immediately
- After the full scan, signals are **sorted by totalScore descending**
- Only the **top `MAX_TRADES_PER_SCAN` (2)** signals are executed — focus on quality, not quantity
- A ranked Discord swing summary fires after every scan:

```
📊 Swing Scan — Top 2 Signal(s)
🔔 Check once before open & once after close
━━━━━━━━━━━━━━━━━━━━
🥇 DFS — 📈 Trend Continuation | Score 12/12 🔥
   📊 V:3 T:3 M:3 S:3
   ⚡ BUY ONLY IF breaks $200.30  (current $200.05)
   🛑 SL $186.05 (-3.5%)  🎯 TP $226.05 (+13%)  R:R 3.7:1
   📦 ~35 shares ($7,011 | $500 at risk)  ⏱ Swing 3–5 days

🥈 OXY — ↩️ Pullback to Support | Score 10/12
   📊 V:3 T:3 M:2 S:2
   ⚡ BUY ONLY IF breaks $53.20  (current $52.80)
   🛑 SL $50.95 (-3.5%)  🎯 TP $58.65 (+10.5%)  R:R 3.0:1
   📦 ~217 shares ($11,544 | $500 at risk)  ⏱ Swing 2–4 days

❌ Filtered out: 6 lower-scored signal(s)
```

---

## Market Regime Filter

Before executing any trade, the system checks if SPY is in a healthy regime:

```
Condition: SPY.price > SPY.SMA20  AND  SPY.todayChangePct > -2.0%
```

- **Healthy** → proceed with executing top signals
- **Crash mode** → send ⛔ Discord alert, skip all trade execution (signals still summarized)
- **Fail-open** → if SPY data unavailable (API error), trading proceeds normally

Implemented in `checkMarketRegime()` in `AIToolAgent.java`. Called once per scan after the ranked summary.

---

## Strategy Attribution

Every `Trade` object stores attribution data at entry for post-trade analysis:

| Field | Description |
|-------|-------------|
| `entryScore` | Total score at entry (out of 12) |
| `entryVolumeScore` | Volume sub-score at entry |
| `entryTrendScore` | Trend sub-score at entry |
| `entryMomentumScore` | Momentum sub-score at entry |
| `entrySetupScore` | Setup sub-score at entry |
| `entryConfluenceCount` | How many strategies agreed at entry |
| `strategyType` | `PULLBACK` / `TREND_CONTINUATION` |

Discord SELL alerts include attribution:
```
✅ SELL — TAKE PROFIT
DFS  Entry: $200.30 → Exit: $226.05  |  P/L: +9.00%
Score: 12/12 (V:3 T:3 M:3 S:3)  🔥 Confluence(2 strategies)
🤖 MASTER_3_TREND_CONTINUATION  |  ⏰ 15:30 ET
```

---

## RS Universe Pre-Filter

**Problem solved:** Scanning all ~500 tickers wastes time on weak, downtrending stocks that will never score high enough to trade.

**Solution:** After the first full scan, `getAllSectorTickers()` automatically returns only the **top 200 tickers by Relative Strength score**.

**RS Score formula (computed from existing `IndicatorData`, no extra API calls):**
```
rsScore = momentum20d (% return over last 20 trading days)
        + above SMA50?  +4 / -4
        + above SMA200? +5 / -5
        + RVOL > 2x?    +2
```

| Example | RS Score |
|---------|----------|
| Above both SMAs, +10% 20d momentum, RVOL > 2x | +21 |
| Above SMA200 only, flat | +1 |
| Below both SMAs, -8% 20d | -17 |

- **First scan:** full universe (~500 tickers) — builds the RS cache
- **All subsequent scans:** top 200 only (~42 min vs ~105 min)
- Cache is updated live during every scan (no separate step needed)

Implemented in `LongTermCandidateFinder.updateRSScore()` / `getAllSectorTickers()` and `IndicatorData.momentum20d`.

---

## Backward Compatibility

The system auto-detects master strategies at runtime. Strategies with `"disabled": true` are silently skipped. If no active master strategies exist, the system falls back to the legacy binary pass/fail agents unchanged.

---

## Files

| File | Role |
|------|------|
| `newStrategies/MASTER_1_MOMENTUM_BREAKOUT.json` | ⛔ Disabled — intraday, not for delayed data |
| `newStrategies/MASTER_2_PULLBACK.json` | ✅ Active — swing pullback config + risk params |
| `newStrategies/MASTER_3_TREND_CONTINUATION.json` | ✅ Active — swing trend config + risk params |
| `src/main/java/AIToolAgent.java` | Scoring logic, entry triggers, `checkMarketRegime()`, Discord output |
| `src/main/java/LongTermCandidateFinder.java` | RS cache, `updateRSScore()`, top-200 universe filter |

---

## Where Old Agents Were Mapped

| Old agents | Now handled by |
|------------|---------------|
| `I4_VOLUME_SURGE`, `M5_BREAKOUT_FILTERED`, `I6_AGGRESSIVE_INTRADAY`, `V1_THE_SNIPER`, `S2_SWING_BREAKOUT`, `M1_AGGRESSIVE`, `V3_VOLUME_EXPLOSION` | `MASTER_1_MOMENTUM_BREAKOUT` (disabled) |
| `I2_VWAP_PULLBACK`, `M4_PULLBACK`, `S1_SWING_PULLBACK` | `MASTER_2_PULLBACK` |
| `S3_SWING_TREND_FOLLOW`, `M3_TREND_LEADER`, `M5_MA_CROSS`, `I3_TREND_RIDER` | `MASTER_3_TREND_CONTINUATION` |
| `M2_CONSERVATIVE`, `M6_LOW_NOISE`, `S7_SWING_CONSERVATIVE` | Absorbed into score thresholds |

---

## Key Constants (in `AIToolAgent.java`)

```java
SCORE_THRESHOLD            = 10   // minimum score to fire a trade (raised from 8 → reduces ~189 signals to ~5-20)
CONFLUENCE_SCORE_THRESHOLD = 7    // score required to count toward confluence (raised from 6)
CONFLUENCE_BONUS           = 2    // bonus added when 2+ strategies align
MAX_TRADES_PER_SCAN        = 2    // max trades executed per scan — top 2 only (reduced from 5)
```

```java
// LongTermCandidateFinder.java
TOP_RS_COUNT               = 200  // tickers kept after RS pre-filter
```

**Tuning guide:**

| Goal | Change |
|------|--------|
| Fewer signals (higher quality) | Increase `SCORE_THRESHOLD` |
| More signals | Decrease `SCORE_THRESHOLD` (minimum 8) |
| Re-enable momentum breakout | Set `"disabled": false` in `MASTER_1_MOMENTUM_BREAKOUT.json` |
| Take more trades per scan | Increase `MAX_TRADES_PER_SCAN` |
