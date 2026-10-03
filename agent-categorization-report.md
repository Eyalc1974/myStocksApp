# Agent Categorization & Consolidation Report

## Current Active Agents (12 total)

### 📊 MARKET FILTER (1 agent)
- **MASTER_7_VIX_MARKET_FILTER** - 63.1% WR (82/130 trades)
  - Purpose: Market regime filter, not a trading agent
  - Status: ✅ KEEP - Essential for market timing

---

### 🎯 MASTER STRATEGIES (3 agents) - Pinned
- **Pullback to MA20 (Master)** - 37.2% WR (16/43 trades)
  - Type: PULLBACK_MA20
  - Status: ⚠️ REVIEW - Low win rate, consider disabling

- **Volume Breakout (Master)** - 66.7% WR (2/3 trades)
  - Type: VOLUME_BREAKOUT
  - Status: ⚠️ INSUFFICIENT DATA - Only 3 trades, need more data

- **Strong Trend (Master)** - 50.0% WR (1/2 trades)
  - Type: STRONG_TREND
  - Status: ⚠️ INSUFFICIENT DATA - Only 2 trades, need more data

---

### ⚡ INTRADAY (6 agents) - HIGH DUPLICATION
- **I1_VWAP_BREAKOUT** - 100% WR (1/1 trades)
  - Strategy: VWAP breakout with CCI>100, high RVOL
  - Status: ⚠️ INSUFFICIENT DATA - Only 1 trade

- **I2_VWAP_PULLBACK** - No trades yet
  - Strategy: Pullback to VWAP after breakout
  - Status: ❌ DISABLE - No trades, duplicates I1

- **I3_TREND_RIDER** - 50% WR (1/2 trades)
  - Strategy: Conservative trend following
  - Status: ⚠️ INSUFFICIENT DATA - Only 2 trades

- **I4_VOLUME_SURGE** - 100% WR (2/2 trades)
  - Strategy: High volume breakout with VWAP
  - Status: ⚠️ INSUFFICIENT DATA - Only 2 trades

- **I5_VWAP_RETEST** - 50% WR (5/10 trades)
  - Strategy: VWAP retest with bull trap filter
  - Status: ✅ KEEP - Most data (10 trades), moderate performance

- **I6_AGGRESSIVE_INTRADAY** - 60% WR (3/5 trades)
  - Strategy: Looser filters for more trades
  - Status: ⚠️ LOW PRIORITY - High risk, limited data

**INTRAday DUPLICATION ANALYSIS:**
- I1, I2, I5 all use VWAP as core signal
- I4 is similar to I1 but focuses on volume
- RECOMMENDATION: Keep only I1 (VWAP Breakout) and I5 (VWAP Retest), disable others

---

### 🔄 NEW STRATEGIES (2 agents)
- **FUND_MOMENTUM_V1** - FUNDAMENTAL (No trades yet)
  - Strategy: Earnings revisions, relative strength, catalysts
  - Status: ✅ KEEP - New edge, needs testing

- **INST_SWING_V1** - INSTITUTIONAL (No trades yet)
  - Strategy: Institutional flow, fundamental score, catalysts
  - Status: ✅ KEEP - New edge, needs testing

---

## Disabled/Inactive Agents

### 📈 MOMENTUM (5 agents) - NOT RUNNING
- M1_AGGRESSIVE, M2_CONSERVATIVE, M2_CONSERVATIVE_V2, M4_PULLBACK, M5_MA_CROSS, M5_MA_CROSS_V2
- Status: These exist in momentum-variants.json but don't appear in active list
- RECOMMENDATION: Review if these should be activated

### 🌊 SWING (10 agents) - DISABLED
- S1-S10 swing variants
- Status: `"enabled": false` in swing-variants.json
- RECOMMENDATION: Consider enabling top 2-3 for diversification

---

## Consolidation Recommendations

### Immediate Actions:
1. **Disable I2_VWAP_PULLBACK** - Duplicates I1, no trades
2. **Disable I3_TREND_RIDER** - Insufficient data, similar to I1
3. **Disable I4_VOLUME_SURGE** - Similar to I1, only 2 trades
4. **Disable I6_AGGRESSIVE_INTRADAY** - High risk, low priority
5. **Review Pullback to MA20 (Master)** - 37.2% WR is concerning, consider disabling
6. **Keep I1_VWAP_BREAKOUT** - Best intraday performer (100% WR, needs more data)
7. **Keep I5_VWAP_RETEST** - Most intraday data (10 trades), unique retest logic

### Target State (8 agents):
1. MASTER_7_VIX_MARKET_FILTER (Market Filter)
2. I1_VWAP_BREAKOUT (Intraday - Primary)
3. I5_VWAP_RETEST (Intraday - Retest)
4. FUND_MOMENTUM_V1 (Fundamental - New)
5. INST_SWING_V1 (Institutional - New)
6. Volume Breakout (Master) - Monitor for more data
7. Strong Trend (Master) - Monitor for more data
8. Enable 1-2 top Swing agents for diversification

### Performance Ranking (by data quality):
1. **MASTER_7_VIX_MARKET_FILTER** - 63.1% WR (130 trades) ✅
2. **I5_VWAP_RETEST** - 50% WR (10 trades) - Most intraday data
3. **Pullback to MA20** - 37.2% WR (43 trades) - Low WR but good data
4. **I6_AGGRESSIVE_INTRADAY** - 60% WR (5 trades) - Limited data
5. **I1_VWAP_BREAKOUT** - 100% WR (1 trade) - Insufficient data
6. **I4_VOLUME_SURGE** - 100% WR (2 trades) - Insufficient data
7. **Volume Breakout** - 66.7% WR (3 trades) - Insufficient data
8. **Strong Trend** - 50% WR (2 trades) - Insufficient data

---

## Category Summary

| Category | Current | Recommended | Reduction |
|----------|---------|-------------|------------|
| Market Filter | 1 | 1 | 0 |
| Master Strategies | 3 | 2 | 1 |
| Intraday | 6 | 2 | 4 |
| Fundamental | 1 | 1 | 0 |
| Institutional | 1 | 1 | 0 |
| Swing | 0 | 2 | +2 |
| **TOTAL** | **12** | **9** | **-3** |

---

## Key Insights

1. **High Intraday Duplication**: 6 intraday agents all using VWAP with minor variations
2. **Insufficient Data**: Most agents have <10 trades, making statistics unreliable
3. **New Edges Needed**: FUND_MOMENTUM_V1 and INST_SWING_V1 add non-technical edges
4. **Swing Underutilized**: 10 swing agents available but disabled
5. **Market Filter Essential**: MASTER_7_VIX_MARKET_FILTER has proven value (130 trades)
