# AlgoTrader — Phase 1, Parts 2 & 3 (Indicator library + Strategy layer)

Extract this zip at the root of your repo, overwriting existing files. All
paths inside are relative to the repo root and land entirely inside
`core/strategy/` — no other module is touched.

## New files
- `core/strategy/src/main/kotlin/com/algotrader/strategy/indicator/Indicators.kt`
  — SMA, EMA, RSI, MACD, Bollinger Bands, Donchian Channel (pure math, null
  until each indicator has enough history).
- `core/strategy/src/main/kotlin/com/algotrader/strategy/PositionDirection.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/StrategyParameter.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/StrategyMetadata.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/RsiStrategy.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/MacdStrategy.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/BollingerBandsStrategy.kt`
- `core/strategy/src/main/kotlin/com/algotrader/strategy/DonchianChannelStrategy.kt`
- `core/strategy/src/test/kotlin/com/algotrader/strategy/TestCandles.kt`
  (shared test helper — deterministic synthetic candles)
- `core/strategy/src/test/kotlin/com/algotrader/strategy/*Test.kt`
  (5 test files, 16 test cases total, one per strategy)

## Modified files
- `core/strategy/src/main/kotlin/com/algotrader/strategy/Strategy.kt`
  — added a `metadata: StrategyMetadata` property to the interface.
- `core/strategy/src/main/kotlin/com/algotrader/strategy/MovingAverageCrossoverStrategy.kt`
  — now uses the shared `sma()` indicator instead of a hand-rolled average
  (identical math/defaults), plus implements `metadata`.
- `core/strategy/build.gradle.kts` — added `kotlin-test-junit5` +
  `useJUnitPlatform()` so `./gradlew :core:strategy:test` runs.

## Not touched
`MainActivity.kt`, the chart implementation, the backend, `strategy-engine`,
`backtest`, and every other module — verified unchanged (by hash) before
packaging this zip.

## Verification status
This was built and reasoned about without a working Gradle/Android/Kotlin
toolchain available in the authoring environment (no network, no Android
SDK, no `kotlinc`). The five strategies' test assertions were checked by
hand-tracing the indicator arithmetic against the implementation, but **this
has not been compiled or run**. Please run:

```
./gradlew :core:strategy:test
```

as the first verification step before building on top of this.
