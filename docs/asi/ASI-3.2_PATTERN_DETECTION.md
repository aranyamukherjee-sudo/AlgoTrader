# ASI-3.2 — Pattern Detection

Package `com.algotrader.intelligence.pattern` inside `:core:intelligence` (no new module, no UI/app/backtest/FYERS/FNO/execution
coupling, no signals). Entry point: `PatternDetector.detect(candles, PatternConfig)` or
`PatternDetector.detect(structureAnalysis, PatternConfig)` (reuse an existing ASI-3.1 analysis). Breakout/retest logic is ASI-3.3.

## Contract
- Consumes ONLY the confirmed pivots of `PriceActionStructure.analyze` (swing detection is not duplicated). Validation and
  insufficient-data handling are inherited: `INVALID_INPUT` / `INSUFFICIENT_DATA` with no patterns; enough candles but no
  pattern = `OK` + empty.
- A pattern is a fixed shape over consecutive pivots, reported when its LAST pivot is confirmed:
  `confirmedIndex = max(member.confirmedIndex)` (`confirmedAt` likewise). `detect(prefix)` == `detect(full)` filtered by
  `confirmedIndex <= k`; later candles never alter or remove a reported pattern. Do not use a pattern before `confirmedIndex`.
- Order: `confirmedIndex`, then `startIndex`, then `PatternType` declaration order.
- Output carries `pivots` (chronological members), `startIndex/endIndex`, `high/low` envelope, and `neckline` (double/M/W).

## Families (all tolerances are fractions; defaults untuned)
- Double top/bottom: two CONSECUTIVE same-type pivots with >= 1 opposite pivot between; neckline = most extreme opposite pivot
  between (earliest on ties); depth must be > 0 and >= `minDepth` of the neckline. Same level = within `levelTolerance` (inclusive).
- M top / W bottom: second extreme not "same level" but on the expected side (lower high / higher low) and within
  `maxSecondExtremeRatio` (inclusive) of the first-extreme-to-neckline distance. Opposite-side second extreme = no pattern.
- Rectangle: 4 alternating pivots, flat highs AND flat lows, height >= `minDepth`.
- Triangles (4 alternating pivots, height >= `minDepth`): ascending = flat highs + rising lows; descending = flat lows + falling
  highs; symmetrical = falling highs + rising lows. Broadening, rising/falling wedge and channel shapes are not reported.
- Flags (4 alternating pivots `[pole start, pole end, pullback, counter]`): pole >= `poleMinMove`; pullback <= `maxFlagRetrace`
  of the pole (inclusive) and beyond the pole start; counter pivot strictly inside the pole's extreme; flag duration
  (pole end -> counter pivot, in bars) <= pole duration.

## Limits
- Families are independent and may overlap on the same pivots (e.g. a flag's pivots can also be a symmetrical triangle; a long
  range yields one rectangle per qualifying window plus the double tops/bottoms in it). No de-duplication, by design (a
  de-dup would depend on future pivots and break prefix consistency).
- Minimum shapes only (3 or 4 pivots); no multi-touch/extended channels, apex or volume. Geometry labels, not predictions.
- Mechanical rules with untuned defaults; nothing here is validated on real market data.
