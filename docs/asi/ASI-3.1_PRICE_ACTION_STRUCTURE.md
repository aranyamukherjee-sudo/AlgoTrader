# ASI-3.1 — Price-Action Structure Foundation

Package `com.algotrader.intelligence.structure` inside `:core:intelligence` (pure JVM). Reuses `domain.Candle`;
no new module, no new dependency, no UI. Pattern detection (ASI-3.2) and breakout intelligence (ASI-3.3) are NOT here.

Entry point: `PriceActionStructure.analyze(candles, StructureConfig) -> StructureAnalysis` (pure, stateless).

## Semantics
- **Input**: one instrument, one timeframe, timestamps STRICTLY increasing (duplicate/decreasing -> `INVALID_INPUT`,
  never sorted or de-duplicated), finite OHLC, high >= low. Invalid input wins over insufficient data. Bad data is
  reported in `status`/`message`; only bad *configuration* throws.
- **Insufficient data**: fewer than `left + right + 1` candles -> `INSUFFICIENT_DATA`, empty result.
- **Pivot**: bar `i` is a pivot HIGH if `high[i] >` every high of the `left` bars before it and `>=` every high of the
  `right` bars after it (lows mirror). Full windows only, so the first `left` / last `right` bars are never pivots.
- **Equal prices**: a plateau of equal highs/lows yields ONE pivot, the earliest.  A bar can be both a high and a low
  (outside bar); the HIGH is listed first.
- **Confirmation / no look-ahead**: a pivot is emitted only once candle `i + right` exists (`confirmedIndex`).
  `analyze(prefix)` equals `analyze(full)` filtered by `confirmedIndex <= k`; later candles never alter a confirmed
  pivot (strength and prominence use only data up to confirmation).
- **Strength** = strictly dominated bars to the left (>= `left`, capped by `maxStrength`). **Prominence** = swing depth
  inside `[i-left, i+right]`.
- **Labels**: each pivot vs the previous pivot of the same type: `HH/LH/EQH`, `HL/LL/EQL`; the first of a type is
  `FIRST` (nothing comparable). `equalityTolerance` (fraction, default 0 = exact) widens "equal".
- **State**: `UPTREND` (latest labels HH + HL), `DOWNTREND` (LH + LL), `RANGE` (any other comparable mix),
  `UNDEFINED` (a side has no labelled pivot yet).
- **Zones**: pivot highs -> RESISTANCE, lows -> SUPPORT, clustered separately; clusters are anchored on the lowest
  price (no chaining) within `zoneTolerance` (fraction); `minZoneTouches` (>= 2) pivots needed. Each zone carries
  `lastConfirmedIndex`. Output sorted by low, high, kind, independent of input order.
- **Volatility**: Wilder ATR. `null` for the first `period - 1` bars, seeded at `period - 1` with the mean of the first
  `period` true ranges (TR of bar 0 = high - low), then Wilder smoothing. Earlier values never change as candles are added.

## Limits
- Mechanical definitions with untuned defaults; nothing here is validated on real market data or implies performance.
- Candles are assumed to be complete bars; intraday session gaps and corporate actions are not handled.
- Only OHLC consistency of high/low is checked (not that open/close lie inside the range).
- Pivot-based zones ignore role reversal, recency decay and volume (later sprints).
- O(n * (left + right)) per call; recomputed from scratch (no incremental state) by design.
