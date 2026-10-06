# ASI-1 — Intelligence Foundation

Module: `:core:intelligence` (pure JVM, package `com.algotrader.intelligence`).
Depends on `:core:domain` and `:core:strategy` only. No app/UI/backtest changes.

## Layers (kept separate on purpose)

Research -> Strategy -> Opportunity -> Entry/Exit signal -> Execution

| Layer | Types | Package |
|---|---|---|
| Strategy (reusable rule set, one version) | `StrategyDna`, `StrategyRef` | `dna` |
| Research (history about a strategy version) | `StrategyRecord`, `StrategyLifecycle`, `StrategyHealth`, research `EvidenceItem`s | `lifecycle`, `evidence` |
| Live opportunity (a current occurrence) | `Opportunity`, live `EvidenceItem`s | `opportunity` |
| Entry / exit state | `OpportunityState` (+ derived `EntryState`, `ExitState`) | `opportunity` |
| Execution | `ExecutionLink` (a record only; no order placement) | `opportunity` |

## Rules encoded
- DNA is immutable per version; a new version is a new `StrategyDna` (`nextVersion()`), a new `StrategyRecord`. Evidence and opportunities pin an exact `StrategyRef(id, version)`.
- DNA is not indicator-bound: indicators, price-action structures and generic `Condition` trees (`AllOf`/`AnyOf` of `FeatureRef` leaves).
- Indexes may be a signal source, never a trade target. MONITORING/ACTIVE need a tradable trade target.
- Confidence = the existing `Signal.confidence` scale (0.0..1.0). Not a probability of profit.
- Strategy health is separate from opportunity confidence.
- Cancelling/expiring an opportunity never touches the strategy (no link from `Opportunity` to `StrategyRecord`).
- Exit alerts and cancellation notices are never suppressed by the minimum-confidence threshold.

## Out of scope (later ASI sprints)
Discovery, pattern detection, opportunity/entry/exit detection engines, confidence/evidence scoring, ranking, generation, live monitoring, notifications UI, persistence, order placement.
