# AlgoTrader — Part 4 (Backtest engine) + Donchian/CPR follow-up

Extract at repo root, overwriting existing files. Nothing outside
`backtest/` and `core/strategy/` is touched. `MainActivity.kt` and
`backend/server.py` were not modified (verified by hash before packaging).

## New module: backtest/ (entirely new this session)
- `TradeDirection.kt` — LONG/SHORT enum for actual positions (distinct from
  `core.strategy.PositionDirection`, which describes what a *strategy*
  supports).
- `PositionSizing.kt` — `FixedQuantity` or `PercentOfEquity` sizing models.
- `BacktestConfig.kt` — initial capital + position sizing.
- `BacktestTrade.kt` — entry/exit index+timestamp+price, quantity, grossPnl,
  returnPercent, holdingPeriodBars.
- `EquityPoint.kt` — one per candle: initial capital + realized P&L +
  unrealized P&L of any open position, mark-to-market.
- `PerformanceMetrics.kt` — win rate, gross profit/loss, net profit, total
  return %, max drawdown (computed off the full equity curve, not just
  trade closes), profit factor, avg win/loss. Exposed as a standalone pure
  function (`computePerformanceMetrics`) so it's testable without running
  the engine.
- `BacktestResult.kt` — bundles the above.
- `SignalResolution.kt` — the pure, directly-tested policy for turning a
  signal + current position into a target position (no pyramiding, and how
  reversals work). See its KDoc for the exact rules.
- `BacktestEngine.kt` — the engine itself. **Execution convention: signal
  evaluated on data through bar i's close; executed at bar i+1's open.**
  Any open position left at the end of the data is force-closed at the
  final bar's close. Full rationale is in its class-level KDoc.
- 5 test files, 22 test cases: engine scenarios (no signals, profitable/
  losing long, short, no-pyramid, reversal, end-of-data close, look-ahead
  check, position sizing), signal-resolution policy, and performance-metric
  math.

## core/strategy additions
- `Indicators.kt` — added `CentralPivotRange`, `centralPivotRange(...)`
  (pure formula), and `dailyCentralPivotRange(candles, zone)` (groups
  candles by calendar day, assigns each day the CPR from the *previous*
  day's H/L/C). This is the first indicator function that takes `Candle`
  directly rather than a price list, since it needs timestamps.
- `CprEmaTrendStrategy.kt` — new: long/short only when an EMA trend filter
  and the prior day's CPR level agree.
- `DonchianEmaTrendStrategy.kt` — new (optional, as offered): Donchian
  breakout confirmed by an EMA filter, reusing the same anti-look-ahead
  "prior bar's channel" approach as the existing `DonchianChannelStrategy`.
- `IndicatorsTest.kt` — didn't exist before; added focused on Donchian +
  the new CPR functions (Donchian/SMA/EMA/RSI/MACD/Bollinger already have
  indirect coverage via the Part 3 strategy tests).
- Tests for both new strategies.

## Already satisfied from Parts 2–3 (not re-done)
- RSI mean reversion → `RsiStrategy` (already existed).
- Donchian breakout → `DonchianChannelStrategy` (already existed, already
  handles look-ahead via prior-bar channel).
- Configurable parameters → already via `StrategyParameter`/
  `StrategyMetadata`, reused as-is for every new class.

## Not done / explicitly deferred (see chat report for detail)
VWAP, Supertrend, volume/breakout conditions, the backend-side strategy
*discovery* system, transaction-cost/slippage modeling, and
train/validation/test splitting utilities. `BacktestEngine.run(strategy,
candles)` takes an arbitrary candle list, so a caller can already feed it
disjoint date ranges for train/val/test without engine changes — no
splitting utility exists yet, but nothing blocks adding one later.

## Verification status — please read this
**No Gradle, Android SDK, or Kotlin compiler is available in the sandbox
this was authored in** (confirmed again this session: the egress proxy
returns 403 on Gradle's distribution, Maven Central, and even `apt-get`).
Every file was reviewed by hand and every test's arithmetic was traced by
hand against the implementation, but none of this has actually been
compiled or run. Please run, in order:

```
./gradlew :core:strategy:test
./gradlew :backtest:test
./gradlew :backtest:build
./gradlew build
```

and fix forward from whatever the compiler actually says — treat my "traced
by hand" checks as a first pass, not a substitute for the real build.
