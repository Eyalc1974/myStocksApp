# Trading System Configuration

This trading system supports both **fake/test trading** (simulation) and **real trading** (via Alpaca API) with configurable parameters.

## Files Created

1. **trading-config.json** - Main configuration file
2. **AlpacaTradingClient.java** - Alpaca API integration for real trading
3. **TradingManager.java** - Unified trading manager supporting both modes

## Configuration (trading-config.json)

### Trading Mode
- `tradingMode`: Set to `"FAKE"` for simulation or `"REAL"` for live trading

### Fake Trading Configuration
```json
"fakeTrading": {
  "enabled": true,
  "startingCapital": 5000.0,      // Starting capital for simulation
  "positionSize": 1000.0,         // Amount per trade
  "maxPositions": 10,            // Maximum concurrent positions
  "stopLossPct": 2.0,            // Stop loss percentage
  "takeProfitPct": 4.0,          // Take profit percentage
  "trailingStopPct": 1.5,        // Trailing stop percentage
  "maxLossPerStock": 30.0,       // Max dollar loss per stock
  "storePath": "daily-trading-sim.json"
}
```

### Real Trading Configuration
```json
"realTrading": {
  "enabled": false,
  "alpaca": {
    "apiKey": "",                // Your Alpaca API Key
    "apiSecret": "",             // Your Alpaca API Secret
    "baseUrl": "https://paper-api.alpaca.markets",
    "usePaperTrading": true      // Set to false for live trading
  },
  "startingCapital": 5000.0,
  "positionSize": 1000.0,
  "maxPositions": 10,
  "stopLossPct": 2.0,
  "takeProfitPct": 4.0,
  "trailingStopPct": 1.5,
  "maxLossPerStock": 30.0,       // Max dollar loss per stock
  "useBracketOrders": true,      // Use bracket orders with TP/SL
  "storePath": "real-trading.json"
}
```

### Discord Notifications
```json
"discord": {
  "enabled": true,
  "webhookUrl": "",              // Your Discord webhook URL
  "channelId": "",
  "notifyOnBuy": true,
  "notifyOnSell": true,
  "notifyOnStopLoss": true,
  "notifyOnTakeProfit": true,
  "notifyOnError": true
}
```

### Risk Management
```json
"riskManagement": {
  "maxDailyLossPct": 5.0,        // Max daily loss percentage
  "maxTotalLossPct": 10.0,       // Max total loss percentage
  "maxPositionSizePct": 20.0,   // Max position size percentage
  "enableCircuitBreaker": true
}
```

## Setup Instructions

### 1. Alpaca Account Setup

1. Create an account at https://alpaca.markets/
2. Go to the dashboard and generate API keys
3. Copy your API Key and API Secret
4. For testing, use Paper Trading (simulated) first
5. When ready for live trading, switch to live account

### 2. Configure trading-config.json

For **Fake Trading** (default):
- Set `"tradingMode": "FAKE"`
- Adjust `fakeTrading` parameters as needed
- No API keys required

For **Real Trading**:
- Set `"tradingMode": "REAL"`
- Set `"realTrading.enabled": true`
- Fill in `alpaca.apiKey` and `alpaca.apiSecret`
- Set `alpaca.usePaperTrading` to `true` for testing, `false` for live
- Adjust `realTrading` parameters as needed

### 3. Discord Setup (Optional)

1. Create a Discord server or use existing one
2. Go to Server Settings → Integrations → Webhooks
3. Create a new webhook
4. Copy the webhook URL
5. Paste into `discord.webhookUrl` in trading-config.json

## Usage

### Initialize TradingManager

```java
// Initialize the trading manager
boolean success = TradingManager.initialize();

if (success) {
    System.out.println("TradingManager initialized successfully");
    System.out.println("Mode: " + TradingManager.getTradingMode());
    System.out.println("Capital: $" + TradingManager.getCurrentCapital());
}
```

### Execute a Buy Order

```java
// Buy with default position size
TradingManager.TradeRecord trade = TradingManager.buy(
    "AAPL", 
    150.0, 
    "MOMENTUM"
);

// Buy with custom quantity
TradingManager.TradeRecord trade = TradingManager.buy(
    "AAPL", 
    150.0, 
    "MOMENTUM",
    10.0  // Custom quantity
);
```

### Execute a Sell Order

```java
TradingManager.TradeRecord trade = TradingManager.sell(
    "AAPL",
    10.0,      // Quantity
    155.0,     // Price
    "TAKE_PROFIT"  // Reason
);
```

### Close a Position

```java
boolean closed = TradingManager.closePosition(
    tradeId, 
    "STOP_LOSS"
);
```

### Get Active Trades

```java
List<TradingManager.TradeRecord> activeTrades = TradingManager.getActiveTrades();
for (TradingManager.TradeRecord trade : activeTrades) {
    System.out.println(trade.symbol + " - " + trade.status);
}
```

### Get Trade History

```java
List<TradingManager.TradeRecord> history = TradingManager.getTradeHistory();
```

## Key Features

### Stop-Loss Mechanism ($30 per stock limit)

The system calculates stop-loss based on two criteria:
1. **Percentage-based**: Configurable percentage (default 2%)
2. **Dollar-based**: Max loss per stock (default $30)

The more restrictive of the two is used automatically.

Example:
- Entry price: $100
- Shares: 10
- Max loss per stock: $30
- Max total loss: $300
- Stop loss %: 2% ($200)
- **Final stop loss**: 2% (more restrictive)

### Money Limit ($5K starting capital)

- Configurable starting capital (default $5,000)
- Position size limit (default $1,000 per trade)
- Max positions limit (default 10 concurrent)
- Real-time capital tracking

### Bracket Orders (Real Trading)

When enabled, real trading uses bracket orders that include:
- **Entry order**: Market or limit order
- **Take-profit order**: Limit order at target price
- **Stop-loss order**: Stop or stop-limit order

This ensures both exit conditions are set automatically when entering a position.

### Discord Notifications

Real-time notifications for:
- ✅ Buy orders placed
- 🔴 Sell orders placed
- ⚠️ Stop-loss triggered
- 🎯 Take-profit hit
- ❌ Order failures

## Risk Management

### Circuit Breaker
- Stops trading if daily loss exceeds threshold (default 5%)
- Stops trading if total loss exceeds threshold (default 10%)
- Can be disabled in config

### Position Size Limits
- Maximum position size as percentage of capital (default 20%)
- Prevents over-concentration in single positions

## Switching Between Modes

To switch from fake to real trading:

1. Update `trading-config.json`:
   ```json
   "tradingMode": "REAL"
   ```

2. Fill in Alpaca credentials:
   ```json
   "realTrading": {
     "enabled": true,
     "alpaca": {
       "apiKey": "your-key",
       "apiSecret": "your-secret",
       "usePaperTrading": true  // Start with paper trading!
     }
   }
   ```

3. Restart the application

## Important Notes

⚠️ **Always test with Paper Trading first!**
- Set `usePaperTrading: true` when starting
- Verify orders execute correctly
- Test stop-loss and take-profit
- Monitor for a few days before going live

⚠️ **Never commit API keys to git!**
- Add `trading-config.json` to `.gitignore`
- Use environment variables for sensitive data in production

⚠️ **Monitor your positions regularly**
- Even with stop-loss, gaps can occur
- Check positions at least daily
- Be aware of after-hours and pre-market risks

## Integration with Existing System

The TradingManager can be integrated with your existing `DailyTradingSimulator`:

```java
// In DailyTradingSimulator, replace direct simulation with TradingManager
if (TradingManager.isFakeTrading()) {
    // Use existing simulation logic
} else if (TradingManager.isRealTrading()) {
    // Use TradingManager for real trades
    TradingManager.buy(ticker, entryPrice, strategy);
}
```

## Troubleshooting

### Alpaca Connection Failed
- Verify API key and secret are correct
- Check if paper trading is enabled for test account
- Ensure network connectivity to Alpaca API

### Discord Notifications Not Working
- Verify webhook URL is correct
- Check webhook has permissions in the channel
- Ensure Discord webhook is not rate-limited

### Insufficient Capital Error
- Check position size vs available capital
- Reduce position size in config
- Close existing positions to free up capital
