# ASI-2 — Automatic Strategy Discovery (first increment)

Module `:discovery` (pure JVM, package `com.algotrader.discovery`). Depends on `:core:domain`,
`:core:strategy`, `:core:intelligence` (ASI-1) and `:backtest`. No app/UI changes.

## Pipeline

generate candidates -> TRAIN filter -> VALIDATION filter -> untouched HOLDOUT -> evidence -> confidence
-> ASI-1 `StrategyRecord` at PROMISING (never further) | rejected research result

- Candidates are ordinary ASI-1 `StrategyDna` (`CandidateGenerator` returns DNA; any generator can plug in).
- `RuleStrategy` presents a DNA to the EXISTING `BacktestEngine` as a `Strategy`. All trade/P&L math and
  `PerformanceMetrics` are reused; nothing is duplicated.
- `FeatureRegistry` maps `FeatureRef.type` -> evaluator. New feature = new registration; the engine is unchanged.
  Starter features: `ma_relation`, `range_breakout`, `range_contraction`, `range_expansion`, `rejection_candle`.

## Overfitting guards
- `DataSplit`: three chronological, disjoint segments with an embargo gap; invariants enforced at construction.
- Only train survivors are evaluated on validation; only validation survivors are evaluated on the holdout,
  once each. Later segments never influence earlier ones. Every evaluation is recorded in `phaseLog`.
- Feature evaluators only see candles up to the current bar; the engine fills at the next open.
- Hard gates (trades, net profit, profit factor, drawdown, period consistency, train->validation retention) come
  first; the 0..1 score only orders survivors and is the plain mean of five visible components. Not P&L-ranked.
- Candidate cap (`maxCandidates`) limits the multiple-testing problem. It does not remove it.

## Confidence (ASI-1 scale, 0.0..1.0)
Confidence means: **how strongly the available evidence supports this strategy being promising.**
It does **not** mean probability of profit, and nothing here implies a guaranteed or certain result.
It equals the validation score, capped at `maxConfidence` (default 0.85). One representation only
(`ConfidenceReading`).

## Reproducibility
Candidate ids are hashes of the rules (`DnaCanonical`); `RunInfo.runKey` hashes the dataset fingerprint,
policy, backtest config and candidate set. Time is injected (`DiscoveryRequest.at`). No randomness.

## Known limits (intentional, this increment)
- LONG only. The engine has no stop-loss/take-profit/time exits, so exits are rule-based and `RiskSpec` is empty.
- No transaction costs or slippage (engine has none). No multiple-testing correction. Thresholds in
  `DiscoveryPolicy` are documented policy choices, not statistical constants.
- Single instrument per run; multi-contract / regime evidence is supported only as separate runs.

## Deferred
Short side, richer structure (swings, W/M, failed breakout, levels, OI/volume), regime and cross-contract
robustness, walk-forward folds, persistence, any UI, live detection, execution.


## Robustness: deterministic research costs

The first robustness increment adds an optional generic `ResearchCostModel` to
the backtest engine. It models percentage commission, deterministic slippage
friction in basis points, and a fixed round-trip cost.

All defaults are zero, so existing backtests retain their historical results.
ASI-2 can configure non-zero research friction through `DiscoveryPolicy` and
uses the resulting cost-adjusted net profit for economic viability gates.

In this first increment, the historical fill prices, equity curve, drawdown,
profit factor, win rate, and trade-level quality statistics remain the
legacy gross metrics. Only the economic net-profit viability gate is
cost-adjusted. This keeps the increment deliberately scoped and preserves
the existing backtest statistics while making friction sensitivity explicit.

This model is deliberately broker- and instrument-agnostic. It is not a
replacement for the authoritative F&O charge model; that remains a separate
domain concern.

Cost sensitivity is a robustness test, not proof of future profitability.
