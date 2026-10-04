# Phase 3 — F&O capital, margin and transaction-cost design (backlog)

Status: **design notes, except FNO-1.** A generic FNO-1 domain implementation
of the §8 contract exists (`core/domain`, package `fno`; Patch 1 plus Patch 2A),
uncommitted at the time of writing. The repository owner reports 38/38 domain
tests passing and a successful debug build for it. FNO-1 is not production-
integrated or approved: it has no UI, Backtest or execution integration.
Nothing else below (FNO-2 to FNO-8) is implemented.

## 1. Where ALTRIXA is today (and what it must not claim)

- Backtests run on **index-level candles** (NIFTY 50 / BANK NIFTY / SENSEX index
  values), not on futures contract prices.
- The engine is **lot-aware**: quantities are whole lots (1 lot = N units from the
  instrument registry). Trade P&L uses the real quantity; notional exposure
  (entry price × quantity) is shown separately.
- Lot size is currently a **single current value per instrument**, not a
  date-dependent timeline, so a window crossing an exchange lot-size revision
  would be wrong.
- The displayed "Net P&L" is **P&L before any transaction costs** (the trade
  model carries only `grossPnl`). It must be relabelled once costs exist.
- **Not implemented:** margin/SPAN, capital blocking, transaction costs, futures
  contract data (settlement prices, expiries, rollover).

Policy: until FNO-7 below is done, the UI and docs describe this as an
**"index-level simulation with lot sizing"**, never as "futures backtesting".

## 2. Four concepts that must stay separate

| Concept | Meaning | Affects P&L? |
|---|---|---|
| Notional exposure | fill price × quantity (contract value) | No |
| Margin / capital requirement | capital the exchange/broker blocks (SPAN + ELM, plus any broker add-ons) | No (blocks capital only) |
| Realized / unrealized P&L | price-move result of the position | Yes |
| Transaction costs | brokerage, exchange, STT, GST, SEBI, IPFT, clearing, stamp duty | Yes (reduces net result) |

Rules: never label notional as "capital invested" or "margin"; margin never
changes equity; Net P&L (after costs) = P&L before costs − transaction costs.
Each concept gets its own field and its own "not modelled" state (null, never 0
and never a guessed number).

## 3. Direction-aware transaction-cost model (design)

- A trade has an **opening leg** and a **closing leg**:
  - LONG: open = BUY, close = SELL
  - SHORT: open = SELL, close = BUY
- Charges are computed **per leg from that leg's own side and own turnover**
  (quantity × that leg's fill price). Never assume both legs carry the same
  charges, and never reuse the entry price for the exit leg.
- Components are kept **individually** (brokerage, exchange, STT, GST, SEBI,
  IPFT, clearing, stamp duty) so each can be shown and audited.
- Side applicability (e.g. which components apply to buy vs sell) and every rate
  come from a **dated, sourced rate table** (effective-from date, source/circular
  reference), selected by trade date. Rates are time-dependent, so **no rate or
  side rule may be a constant in code**.
- Brokerage is a **broker profile** (separate from statutory/exchange
  components), so another broker can be modelled without touching the rest.
- GST base (which components are taxed) is configuration, not hard-coded.
- **Rounding policy:** internal arithmetic uses unrounded values; display
  rounding is presentation only; a validated charge schedule may define
  rounding for its own charge calculation (§8.5); broker-displayed rounding is
  never a source of rounding rules (see §8.1 and the fixture reconciliation
  below).
- **Break-even** is informational: reference price ± round-trip costs ÷
  quantity (up for LONG, down for SHORT). It is not used by the engine.

## 4. Margin and capital model

See the architecture agreed earlier: a `MarginModel` interface returning SPAN
and ELM components with an as-of date and source; NotModelled /
UserSuppliedMarginPerLot / SpanMargin implementations; per-exchange adapters
(NSE, later BSE/ICCL); a capital ledger separate from P&L; skipped-entry and
shortfall events. Key rules: no fixed margin percentage; insufficient capital
at entry → skip and record; margin shortfall on an open position → flag
(forced square-off is an optional later setting); daily margin is revalued from
that day's risk parameter file; MTM changes available capital, margin does not
change P&L.

## 5. Validation fixture FX-NIFTY-FUT-001 (broker transaction estimator)

**Purpose:** a reference to validate ALTRIXA's cost, notional and (later)
margin outputs against a real broker estimate. **It is test data only. It is
not a rate source and nothing in production code may reference or copy it.**
When implemented, store it as a data file under test resources.

Inputs / expected values (as reported by the broker):

| Item | Value | Status |
|---|---|---|
| Instrument | NIFTY OCT FUT (FUTIDX, NSE), 1 lot | Verified (Dhan screenshots) |
| Displayed reference price | ₹22,520 | Verified as displayed; price type unknown (see below) |
| Quantity | 65 (1 lot) | 1 lot verified; 65 units previously supplied |
| Exchange turnover / notional | ₹14,63,800 (= 22,520 × 65, arithmetic checked) | Verified (shown as exchange turnover) |
| Margin requirement | ₹1,65,842.15 | Previously supplied estimator information; its exact underlying calculation is unverified (not shown in the FNO-0 screenshots; add-on / exposure-margin content unknown) |
| Implied leverage | 8.83× (= notional ÷ margin, arithmetic checked) | Previously supplied estimator information; the ratio is simple arithmetic, but it depends on the margin above, whose exact underlying calculation is unverified |

| Component | BUY leg | SELL leg |
|---|---|---|
| Brokerage | 20.00 | 20.00 |
| Exchange charges | 26.79 | 26.79 |
| STT | 0 | 732.00 |
| GST | 8.42 | 8.42 |
| SEBI | 1.46 | 1.46 |
| IPFT | 0 | 0 |
| Clearing | 0 | 0 |
| Stamp duty | 29.00 | 0 |
| **Total (as stated)** | **85.95** | **788.57** |

Round-trip stated: ₹874.52 (= 85.95 + 788.57). Break-even: BUY-side ₹22,533.45,
SELL-side ₹22,506.55 (both equal reference ± 874.52 ÷ 65, arithmetic checked).

Provenance of these figures (FNO-0 update):

- **Verified from the Dhan screenshots:** BUY total ₹85.95; SELL total ₹788.57
  (taken from the earlier successful estimator screen); BUY-side break-even
  ₹22,533.45.
- **Previously supplied, not re-verified in this update:** the individual
  charge lines in the table above (they are not part of the verified list),
  the SELL-side break-even ₹22,506.55, and the round-trip total (derived from
  the two totals).
- **Invalid screenshot:** the SELL-side screenshot shows ₹0 for every field
  because it displays "Poor Internet connection!". Those zeros are a failed
  fetch, **not** real sell charges, and must never be used as expected values
  or as evidence that sell charges are zero.

Expected direction behaviour: LONG applies the BUY column on open and the SELL
column on close; SHORT applies the SELL column on open and the BUY column on
close.

### Reconciliation findings (checked, not assumed)

- The **listed lines do not sum to the stated totals**: BUY lines sum to 85.67
  (stated 85.95); SELL lines sum to 788.67 (stated 788.57).
- The totals **do reconcile exactly** if STT and stamp duty are unrounded and the
  display rounds them to whole rupees: SELL STT 731.90 (shown 732) gives 788.57;
  BUY stamp 29.28 (shown 29) gives 85.95. This is an inference consistent with
  this one example and must be confirmed against the broker's own statement.
- Implied rates from this single example (for sanity checking only, **not
  constants**): STT ≈ 0.05% of sell turnover; stamp ≈ 0.002% of buy turnover;
  SEBI ≈ ₹10 per crore; exchange ≈ 0.00183% of turnover; GST 18% on
  (brokerage + exchange charges), SEBI not in the base here. The implied STT
  differs from earlier published futures STT rates, which reinforces that rates
  must come from a dated, sourced table.
- Margin cross-check: if the broker figure is SPAN + ELM only, then
  SPAN part ≈ 1,65,842.15 − 2% × 14,63,800 = ₹1,36,566.15. This is a **check
  against the dated NSE SPAN file**, not a model. A mismatch means broker
  add-ons or a different reference price/date (add-on content is currently
  UNKNOWN, see FNO-0 status). Do **not** derive a margin
  percentage from this example (165,842.15 ÷ 14,63,800 is not a rate).

### Tolerances for the future test

- Totals (per leg, round trip), notional, leverage, break-evens: ±₹0.01 (break-evens
  from stated round-trip total).
- STT and stamp duty line items: ±₹0.50 (broker displays whole rupees).
- Other line items: ±₹0.01.
- Margin: compare against SPAN + ELM from the dated file; report the difference
  rather than force a match.

### Fixture metadata — FNO-0 status

Verified from Dhan Transaction Estimator screenshots:

| Field | Value |
|---|---|
| Broker | Dhan |
| Instrument | NIFTY OCT FUT |
| Exchange / segment | FUTIDX / NSE |
| Quantity | 1 lot |
| Displayed reference price | ₹22,520 |
| Order type | Market |
| Product / margin mode as displayed | "Margin" (label only; see NRML/MIS below) |
| Sides as displayed | B / Buy and S / Sell |
| Exchange turnover | ₹14,63,800 |
| NSE status shown | CLOSED from NSE 15:39:59 |
| BUY estimated charges | ₹85.95 |
| SELL estimated charges | ₹788.57 (from the earlier successful estimator screen) |
| BUY-side break-even | ₹22,533.45 |

**Unknown — not verified, and not to be inferred:**

| Field | Status |
|---|---|
| Exact calendar date of the estimate | UNKNOWN |
| Product type NRML vs MIS | UNKNOWN (the screen shows only "Margin") |
| Whether ₹1,65,842.15 includes broker add-ons / exposure margin | UNKNOWN |
| Whether ₹22,520 is LTP or another reference price | UNKNOWN (the "CLOSED" status is recorded as shown; it does not establish the price type) |
| Exact futures expiry date | UNKNOWN ("NIFTY OCT FUT" identifies the October contract but does **not** establish the exact expiry date) |

Consequences until these are resolved:

- Without the **date**, the dated rate-table version to validate against cannot
  be chosen. Cost validation (FNO-3) and margin validation (FNO-6) stay blocked.
- Without the **expiry date**, the contract cannot be matched to a specific
  futures series or SPAN file entry.
- Without knowing whether the **margin** includes add-ons, the margin
  cross-check above cannot conclude anything; a difference from SPAN + ELM would
  be uninterpretable.
- Without the **product type** and **price type**, margin and cost comparisons
  must be recorded as "not like-for-like" rather than pass/fail.

## 6. Backlog (in dependency order)

| ID | Item | Blocked by |
|---|---|---|
| FNO-0 | Capture fixture metadata for FX-NIFTY-FUT-001. **Partially complete:** broker, instrument, segment, quantity, order type, turnover, totals and BUY break-even verified. **Still open:** estimate date, NRML/MIS, margin add-on content, reference-price type, exact expiry date. | owner input for the five unknown fields |
| FNO-1 | F&O calculation contract (§8): inputs, derived calculations, outputs, broker-specific values, validation rules, built on FX-NIFTY-FUT-001. The data-model separation (notional / margin / costs / P&L, "not modelled" states, relabelling "Net P&L" as before-costs) is the implementation of this contract. **Contract design is documented. A generic domain implementation (Patch 1 plus Patch 2A) exists; the repository owner reports 38/38 domain tests and a successful debug build. It is not production-integrated or approved, and has no UI integration.** | — |
| FNO-2 | Date-dependent lot-size timeline from exchange circulars (replace single current value) | authoritative lot-size history |
| FNO-3 | Direction-aware cost engine with dated rate tables and broker profile; validate against FX-NIFTY-FUT-001 | FNO-0, sourced rate tables |
| FNO-4 | Net P&L (after costs), per-trade costs and break-even in results | FNO-1, FNO-3 |
| FNO-5 | Capital ledger with user-supplied margin per lot (labelled as an assumption); skipped-entry and shortfall reporting | FNO-1 |
| FNO-6 | NSE SPAN ingestion → `SpanMargin`; validate against fixture margin | confirmed SPAN archive depth, file-format semantics, ELM price basis |
| FNO-7 | Futures contract data: settlement prices, expiries, rollover → true futures backtesting | futures/contract historical data |
| FNO-8 | BSE/ICCL adapter for SENSEX (separate parameters, not NSE's) | ICCL file semantics, BSE ELM rule, SENSEX lot timeline |

## 7. Claims policy

- No hard-coded margin percentages, charge rates or fixture values in
  production code.
- No "true futures", "margin-accurate" or "net of charges" claims until the
  corresponding items (FNO-7, FNO-6, FNO-3/4) are done and validated.
- SENSEX lot size (20) is from secondary sources only; verify against the BSE
  circular.

## 8. FNO-1 — F&O calculation contract (NSE index futures)

Status: **FNO-1 implementation exists (uncommitted at the time of writing): a
generic domain implementation of this contract in `core/domain`, package `fno`
(Patch 1 plus Patch 2A). The repository owner reports 38/38 domain tests
passing and a successful debug build. FNO-1 is not production-integrated or
approved, and it has no UI integration.** Patch 2A aligned the implementation
with the margin-availability and turnover-validity rules in this section. The
remaining places where the implementation is narrower than this section are
listed under "Implementation status" at the end of §8.7. This section
defines what ALTRIXA will eventually calculate for NSE index futures capital, margin,
transaction costs and break-evens, and what it must be given rather than
compute. It builds on the FNO-0 fixture in §5 and does not change it.

### 8.1 Principles

- **Three provenance classes.** Every input, calculation and output belongs to
  exactly one:
  - **DERIVED (D):** pure arithmetic on other fields. ALTRIXA can compute it.
  - **SUPPLIED (S):** must come from the exchange, an instrument master or the
    broker. ALTRIXA does not compute it.
  - **UNVERIFIED (U):** the value or the rule is not evidenced. It must not be
    used to produce an output presented as exact.
- **Fixture statuses** (separate from the classes above): VERIFIED (shown on a
  valid Dhan screenshot), SUPPLIED-EARLIER (previously supplied, not
  independently verified), UNKNOWN, INVALID (failed capture).
- **Weakest-link propagation.** An output's evidence/provenance follows the
  weakest evidence among all the inputs on which it depends, directly or
  through intermediate results. It never follows only a single principal input
  (for example only the lot size for quantity, or only the margin for
  leverage). A derived output that depends on an unverified or unknown input
  is labelled accordingly.
- **No reverse-engineering.** A broker formula, rate or rounding rule is never
  adopted because fixture numbers look compatible with it. The fixture is
  validation data, not a source of rules.
- **Missing is null.** An unknown or unavailable value is null with a status.
  It is never 0, never a default and never a fixture-derived guess. A charge of
  0 is valid only when captured as 0 on a VALID capture. A value that is
  present but fails a validity rule (§8.2a) is INVALID_INPUT, which is distinct
  from missing.
- **Arithmetic precision and rounding (three separate layers).**
  1. *ALTRIXA internal arithmetic* uses unrounded decimal values. Where a
     division has no finite decimal result, a fixed working precision is used;
     that is not rounding of a reported result.
  2. *Validated-schedule calculation semantics.* A validated charge schedule
     (§8.5) may define rounding for its own charge calculation and defines the
     order of operations. ALTRIXA applies exactly what the schedule states and
     nothing else.
  3. *Broker-displayed / display-rounded values.* That a displayed value is
     rounded is not evidence of a rounding rule. Rounding is never applied, and
     never inferred, merely because a fixture value is displayed rounded. Which
     rounding a given broker applies is broker-specific and UNVERIFIED (see §5
     reconciliation).

### 8.2 Inputs

| ID | Input | Class / supplier | Needed for | FX-NIFTY-FUT-001 value and status |
|---|---|---|---|---|
| I1 | Broker | S (user/config) | charges, margin | Dhan — VERIFIED |
| I2 | Exchange | S (instrument master) | all | NSE — VERIFIED |
| I3 | Segment | S (instrument master) | charges, margin | FUTIDX — VERIFIED |
| I4 | Underlying / instrument | S (instrument master) | all | NIFTY — VERIFIED |
| I5 | Contract month | S (instrument master) | contract match | OCT — VERIFIED (displayed "NIFTY OCT FUT") |
| I6 | Contract expiry date | S (instrument master) | contract match, margin/SPAN lookup | **UNKNOWN** ("OCT" does not establish the exact expiry date) |
| I7 | Contract identifier (exchange symbol/token) | S (instrument master) | contract match | **UNKNOWN** (not captured) |
| I8 | Lot size (units per lot), the value effective on the position's opening date | S (instrument master, date-dependent, FNO-2) | quantity | 65 — SUPPLIED-EARLIER; no instrument-master evidence captured |
| I9 | Number of lots | S (user) | quantity | 1 — VERIFIED |
| I10 | Entry price | S (user or market data) | entry value, P&L | ₹22,520 is the displayed / reference price — VERIFIED as displayed. That it is the entry price, and its price type, are **UNKNOWN**: it is not assumed to be the entry price, the LTP or any other price type |
| I11 | Exit price. For an open position a supplied mark price takes its place and the P&L is labelled unrealized | S (user or market data) | exit value, P&L, break-even check | **Not provided** in the fixture |
| I12 | Direction | S (user) | opening/closing sides | Screens show B / Buy and S / Sell — VERIFIED; no position direction stated |
| I13 | Order type | S (user) | price source only | Market — VERIFIED. Whether it affects any charge is not claimed |
| I14 | Product / margin mode | S (user/broker) | margin, possibly charges | "Margin" displayed — VERIFIED as a label; **NRML vs MIS UNKNOWN** |
| I15 | Margin requirement, supplied together with its scenario (quantity, side, product, date, price basis); any scenario element not supplied is UNKNOWN | S (broker quote now; SPAN + ELM later, FNO-6) | capital, leverage | ₹1,65,842.15 — SUPPLIED-EARLIER; calculation unverified; add-on / exposure content **UNKNOWN**. A supplied quote is not validated by being numerically plausible |
| I16 | Brokerage inputs | S (broker profile) | charges | ₹20.00 appears in the earlier line items — SUPPLIED-EARLIER; the rule (flat, percentage, cap, per order) is **UNKNOWN** |
| I17 | Statutory / exchange charge rates (exchange transaction, STT, SEBI, stamp duty, IPFT, clearing) | S (dated, sourced rate table) | charges | **UNKNOWN** — rates implied by the fixture are deliberately not adopted |
| I18 | Other broker-specific charges and rules (GST base, rounding, any account-specific fee) | S (broker profile) | exact broker estimate | **UNKNOWN** |
| I19 | Estimate date | S | rate-table and margin-parameter version | **UNKNOWN** |
| I20 | Reference-price type (LTP, settlement, close, other) | S | interpreting I10 | **UNKNOWN** |
| I21 | Market status at capture | informational | none | CLOSED from NSE 15:39:59 — VERIFIED as displayed; does not establish I20 |
| I22 | Valuation price for notional (C5): the price the caller states the notional is valued at (entry, exit or mark price) | S (caller, stated explicitly) | notional | Not a fixture field. The displayed ₹22,520 is used as a valuation price only where §8.7 says so explicitly; that does not make it the entry price |

Nothing is assumed because it appears in a screenshot: each row above records
what was shown, and a displayed value is not promoted to a rule.

### 8.2a Handling when an input is unavailable

Unavailable means null/UNKNOWN. No input is ever defaulted (for example lot
size is never assumed to be 1). Effects are limited to what depends on the
missing input:

| Missing input | Effect |
|---|---|
| I1–I5, I7 (identity) | Contract cannot be identified: schedule and instrument-master lookups are NOT_MODELLED. A supplied margin quote is not a lookup and stays available (see I15), but leverage is null: without contract identity the quote cannot be tied to this contract (C9). Pure arithmetic on supplied numbers is unaffected |
| I6 expiry | Contract-specific lookups (SPAN or instrument-master margin lookup, instrument match) are NOT_MODELLED. A supplied margin quote stays available, but leverage is null (the quote cannot be tied to a specific expiry). C2–C6 are unaffected |
| I8 lot size | C2 and everything downstream are null |
| I9 lots | Null if missing; INVALID_INPUT if not a whole number ≥ 1. Never rounded |
| I10, I11, I22 | Only the values that use that price are null (C3, C4, C5, C6 and what follows) |
| I12 direction | C1 is null, so the sign of C6 and the sides used by charges are null |
| I13 order type | No arithmetic effect. Charges are NOT_MODELLED only if the validated schedule declares order type as a key |
| I14 product | Any product-keyed charge lookup is NOT_MODELLED, and leverage is null (the scenario product cannot be matched). A supplied margin quote stays available |
| I15 margin | Margin and capital used are null (NOT_MODELLED), and so is leverage. A supplied margin quote is never erased by gaps elsewhere in the inputs; scenario completeness, contract identity and expiry are required only for leverage (C9). Free capital is outside FNO-1 (§8.4) |
| I16–I18 | Charges, net P&L and break-even are null (NOT_MODELLED) |
| I19 date | Date-keyed lookups (lot-size timeline, rate-table version, margin parameters) are NOT_MODELLED. The calculation estimate date must be explicitly supplied for date-keyed application; a schedule's or value's own effective-from date does not substitute for it. Once supplied, a schedule applies only if its effective-from date is on or before the estimate date |
| I20 price type | No arithmetic effect. The result must be labelled "price type unknown" |

**Validity rules.** These are ALTRIXA domain-input validity rules. They are not
broker rules, and none is inferred from the fixture. A value that is present
but fails its rule is INVALID_INPUT: it is never rounded, clamped, defaulted or
silently replaced. A value that is absent is UNKNOWN, never 0.

- Lots (I9): a whole number ≥ 1.
- Lot size (I8): a whole number ≥ 1.
- Price inputs used for valuation (I10, I11 including a mark price, I22):
  strictly > 0.
- Supplied margin amount (I15): strictly > 0.
- Displayed exchange turnover amount: carries an explicit VALID/INVALID capture
  classification (§8.7 rule 3). Missing is UNKNOWN. A positive amount is valid
  supplied data. A zero or negative amount is INVALID_INPUT. Zero is never
  treated as missing, and missing is never treated as zero.
- Charge-schedule rates, flat amounts, minimums and caps: not negative (§8.5).

### 8.3 Derived calculations (mathematical)

All are class D given their inputs. Where an input is S, the output inherits
that input's evidence level (§8.4a).

**Three different quantities, never interchangeable:** (1) *leg value* (C3,
C4) is ALTRIXA's computed price × quantity for one leg; (2) *contract
notional* (C5) is computed exposure at a stated valuation price (I22); (3)
*displayed exchange turnover* is a value shown by an estimator, supplied (S),
whose leg and price basis may be UNKNOWN. Two of them being numerically equal
in the fixture is an observation, never a definition. The turnover-leg
association is UNKNOWN unless explicitly supplied; it is never inferred from
numerical equality, the reference price or any other value.

| ID | Calculation | Definition | Needs |
|---|---|---|---|
| C1 | Opening / closing side | LONG: open BUY, close SELL. SHORT: open SELL, close BUY | I12 |
| C2 | Quantity | lots × lot size. Lots must be a whole number ≥ 1; anything else is INVALID_INPUT and is never rounded | I8, I9 |
| C3 | Entry value (opening-leg value) | entry price × quantity | I10, C2 |
| C4 | Exit value (closing-leg value) | exit price × quantity | I11, C2 |
| C5 | Contract notional / exposure | valuation price (I22) × quantity. Exposure only: not margin, not invested capital, not by definition the entry value or the displayed turnover. The valuation price is stated by the caller and never assumed to be the entry price or the displayed/reference price | I22, C2 |
| C6 | Gross P&L (before costs) | LONG: (exit − entry) × quantity. SHORT: (entry − exit) × quantity | I10, I11, C1, C2 |
| C7 | Net P&L | gross P&L − round-trip charges | C6, C8 |
| C9 | Effective leverage | notional ÷ margin requirement, only when the margin quote's scenario (I15) is fully supplied and matches the notional's quantity, side, product, date and price basis, as defined in the C9 matching note below; otherwise null (NOT_MODELLED). Any UNKNOWN scenario dimension prevents leverage | C5, I15 |

Calculations that combine ALTRIXA arithmetic with supplied rules:

| ID | Calculation | Contract | Status rule |
|---|---|---|---|
| C8 | Round-trip charges | opening-leg charges + closing-leg charges. Each leg is computed from that leg's own side (C1) and own leg value (C3 or C4), using a broker profile and dated rate table. The calculation base of each component (leg value, another component such as brokerage, or a flat amount), the evaluation order, the order of operations among percentage, base, minimum, cap and rounding, whether a dependent component's base is the unrounded component amount or another defined base, and where rounding occurs are all defined by the validated schedule (§8.5), not chosen by ALTRIXA. Components are kept individually | Summing components is arithmetic, but every component value is S. If no validated schedule exists for (broker, exchange, segment, date), charges are **NOT_MODELLED (null)**, never estimated |
| C10 | Break-even price | the real-valued exit price at which net P&L is 0: gross P&L(exit) − opening-leg charges − closing-leg charges(exit) = 0, solved in unrounded arithmetic. Tick or display rounding is presentation only. The solve is exact only when the validated schedule makes net P&L a deterministic, continuous function of the exit price over the relevant domain, with a unique solution. If rounding, a minimum, a cap or any other discontinuity prevents a unique exact solution, or the schedule leaves none, the result is NOT_MODELLED; ALTRIXA never guesses or approximates. For LONG it lies above entry, for SHORT below | Needs a validated charge function (C8). Charges that depend on exit value make this an exact solve, not a shortcut |

Contract notes:

- **Capital / margin requirement is not derivable.** It is I15 (supplied). It
  is never computed or reverse-engineered from notional, price, lot size,
  leverage or a percentage. A supplied broker margin quote is SUPPLIED data: it
  is not derived, and it does not become validated by being numerically
  plausible. It stays available as a "broker estimate" even when some contract
  identity (expiry, contract identifier, product) is UNKNOWN.
- **Effective leverage (C9)** requires the margin's scenario to be known and to
  match the notional's. **For FX-NIFTY-FUT-001 ALTRIXA's own leverage output is
  therefore null**: the margin's date and product are UNKNOWN. The 8.83× figure
  is retained only as a previously supplied reference value (calculation
  unverified), for comparison. It is supplied evidence only, is never
  reverse-engineered, and is never promoted to a calculated leverage output by
  numerical agreement.
- **C9 matching (definition).** The scenario *quantity* is C2. The scenario
  *side* is the opening (trade) side, C1. The scenario *product* is I14. The
  scenario *date* is the estimate date I19 supplied for the calculation. The
  scenario *price basis* is the valuation basis stated with I22, drawn from the
  enumerated set ENTRY_PRICE, EXIT_PRICE, MARK_PRICE, REFERENCE_PRICE
  (REFERENCE_PRICE: a displayed or reference price whose type is UNKNOWN or
  not otherwise classified). Leverage is produced only when the supplied margin
  quote carries every one of these dimensions and each equals the notional's.
  Any UNKNOWN or mismatching dimension gives null (NOT_MODELLED). Leverage
  also requires the contract identity (I1–I5, I7) and expiry (I6), so that the
  quote is tied to this contract; these are preconditions, not dimensions of
  the quote's scenario. Their absence leaves the supplied margin available but
  makes leverage NOT_MODELLED.
- **Broker-displayed break-even** is a supplied value kept only for comparison.
  ALTRIXA does not assume the broker uses ALTRIXA's C10 method. The fixture's
  break-even figures are numerically compatible with reference price ±
  round-trip charges ÷ quantity at display precision. That is recorded as an
  observation and is **not adopted as a rule**.
- **ALTRIXA break-even is named by position direction** (LONG opens with BUY,
  SHORT opens with SELL). That the broker's "buy-side" and "sell-side"
  break-even labels correspond to LONG and SHORT is an interpretation and is
  UNVERIFIED for the fixture; the displayed broker values are kept under the
  broker's own labels.

### 8.4 Outputs

Every output carries: value (nullable), kind and evidence (§8.4a), the list of
input IDs it depended on, and warnings.

| Output | Provenance rule |
|---|---|
| Quantity, lots | D from I8, I9 (evidence: the weakest of I8 and I9) |
| Entry value, exit value, contract notional | D |
| Gross P&L | D |
| Per-leg charge components and totals | S-dependent. NOT_MODELLED until a validated schedule exists |
| Round-trip charges, net P&L | Follow C8 |
| Margin requirement (labelled "broker estimate") | S, as supplied; never computed or reverse-engineered. Available whenever a valid quote is supplied, even if identity, expiry or product are UNKNOWN; scenario completeness, contract identity and expiry are required only for leverage |
| Capital used | The supplied margin requirement, unchanged (S). Free capital and any other account-capital-dependent value are outside FNO-1: they are deferred to the later capital/account model (FNO-5 and §4). FNO-1 takes no account-capital input and assumes none |
| Effective leverage | D arithmetic on S margin, only with a matching margin scenario (C9); otherwise null. Evidence: the weakest of the notional's inputs and I15 |
| ALTRIXA break-even (LONG, SHORT) | Follows C8/C10; NOT_MODELLED if charges are |
| Broker-displayed break-even | S, comparison only |

Result kind and evidence are defined in §8.4a. Display labels must keep the four concepts in §2 separate and must not call
notional "capital invested" or "margin".

### 8.4a Null and evidence propagation

1. **Null propagation.** If a required input is null/UNKNOWN, the result is
   null with kind NOT_MODELLED and a `missingInputs` list of input IDs. If a
   required input is INVALID, the result is null with kind INVALID_INPUT. A
   missing input never produces 0, a default or a fallback.
2. **Kind.** A non-null result is DERIVED (ALTRIXA arithmetic) or SUPPLIED
   (passed through unchanged from a supplier, such as the margin quote).
3. **Evidence (non-null results only).** Ordered strongest to weakest:
   VERIFIED (authoritative or valid-capture evidence), then SUPPLIED
   (provided, not independently verified; includes the fixture's
   SUPPLIED-EARLIER). A result carries the weakest evidence among all the inputs
   it depends on, directly or through intermediate results.
4. **Unverified rules.** A value or rule of class UNVERIFIED (§8.1) never feeds
   a result presented as exact; the result is NOT_MODELLED instead.
5. **State vocabulary.** These are kept distinct and never merged:
   - **DERIVED:** ALTRIXA arithmetic on available, valid inputs.
   - **SUPPLIED:** passed through unchanged from a supplier.
   - **UNKNOWN / UNVERIFIED:** UNKNOWN means the input is absent (null).
     UNVERIFIED (§8.1) means a value or rule is not evidenced; it may be
     present, but it never feeds a result presented as exact.
   - **INVALID_INPUT:** the input is present but fails a capture-validity or
     validity rule (§8.2a); the result is null.
   - **NOT_MODELLED:** the result is null because a required input is UNKNOWN
     or a required rule or validated schedule is absent. It is not a failure.

### 8.5 Broker-specific values (never derived from the fixture)

Brokerage rule; which charge lines exist and their rates; GST base; rounding
behaviour per line; the margin amount and any add-on or exposure margin; the
product-type effect on margin; and how the broker computes its displayed
break-even. These live in a **broker profile** and a dated rate table. The Dhan
profile remains empty/UNVERIFIED until formula evidence (for example Dhan's
published charge schedule, dated) is added.

**Validated charge schedule.** A schedule counts as validated only through
documentary evidence: it is dated (effective-from), cites an authoritative
source (exchange or regulator circular, or the broker's published schedule),
and is complete for every component, covering rate, applicable side, calculation
base, rounding, and any cap or minimum. A fixture that is numerically
compatible with a schedule is consistency evidence only. It never validates the
schedule.

**Schedule calculation semantics.** A validated schedule defines, for every
component, its calculation base, rate, applicable side(s), minimum, cap and
rounding, and the order of operations among them. A dependent component must
state explicitly whether its base is the unrounded amount of another component
or some other defined base. A component whose mathematical order of operations
is not defined is not valid, and a schedule containing one is not validated.
Rounding in a schedule applies to that schedule's own charge calculation only.
ALTRIXA applies exactly what the schedule states, never adds a rounding step,
and never infers one from fixture numbers.

**Supplied margin.** A margin quote is not validated by being numerically
plausible, by agreeing with a notional ratio, or by a charge schedule.

### 8.6 FX-NIFTY-FUT-001 mapped to the contract

| Contract item | Fixture value | Status |
|---|---|---|
| Quantity (C2) | 65 | Arithmetic is meaningful only given lot size 65 (SUPPLIED-EARLIER) |
| Displayed / reference price | ₹22,520 | VERIFIED as displayed. A displayed/reference price from the fixture; it is not automatically an entry price or LTP, and its price type is UNKNOWN |
| Exchange turnover | ₹14,63,800 | VERIFIED as displayed exchange turnover. **Turnover-leg association (buy leg, sell leg or other): UNKNOWN** — the screenshot evidence does not establish it, and it is not inferred from the reference price or any other fixture value; it stays UNKNOWN unless explicitly supplied |
| BUY-leg charges total | ₹85.95 | VERIFIED displayed estimate. Component values and formula are not verified |
| SELL-leg charges total | ₹788.57 | From the earlier successful estimator screen; the sell estimate's own price, turnover and date were not captured. Qualified, not exact |
| SELL screenshot with ₹0 fields and "Poor Internet connection!" | — | **INVALID capture.** Never used as sell charges, an expected value or a calculation input; never a valid zero-charge observation |
| Round-trip charges | ₹874.52 | Arithmetic sum of the two displayed totals; inherits the qualification on the SELL total |
| Margin requirement | ₹1,65,842.15 | SUPPLIED-EARLIER; calculation unverified; add-ons UNKNOWN |
| Effective leverage | 8.83× | SUPPLIED-EARLIER reference value; calculation unverified. ALTRIXA's own leverage output for the fixture is null (margin scenario date/product UNKNOWN) |
| BUY-side break-even | ₹22,533.45 | VERIFIED displayed broker value; the broker's method is unverified |
| SELL-side break-even | ₹22,506.55 | SUPPLIED-EARLIER; not verified |
| Gross P&L, net P&L | — | Not applicable: no exit price in the fixture |
| Date, expiry, NRML/MIS, reference-price type, margin add-ons | — | **UNKNOWN** (see §5 FNO-0 status) |

The fixture's ₹85.95 BUY-leg charges, ₹788.57 SELL-leg charges, ₹874.52
combined charges, ₹1,65,842.15 margin and 8.83× leverage are supplied evidence
values only. They validate no broker formula, rate or rounding rule. FNO-1
remains broker-neutral and generic: Dhan appears only as fixture data, never as
a rule.

### 8.7 Validation and acceptance rules

**Comparison record.** Each comparison names the computed output, the expected
value, the fixture field it came from, the tolerance, and the input IDs it
depends on.

**Result states.**

- **NOT-COMPARABLE:** any input the comparison depends on is UNKNOWN, or an
  input or the expected value comes from an INVALID capture. It is not a failure.
- **PASS:** the comparison is comparable and the computed value equals the
  expected value within the tolerance.
- **FAIL:** the comparison is comparable and the computed value is outside the
  tolerance.
- **INVALID_INPUT:** a supplied value being compared fails its validity rule
  (§8.2a), for example a displayed turnover amount that is zero or negative. No
  comparison is made: it is neither PASS nor FAIL, and the value is never
  treated as missing. Whether the turnover leg is known does not change this.
- **Tolerance:** an explicit per-comparison tolerance. Without one, exact
  decimal equality applies to values stated exactly (sums and products of
  stated figures), and equality to within half a unit of the last displayed
  digit applies to values the source displays rounded. The §5 tolerances stay
  provisional (rule 6 below).
- **Loading:** a fixture loader loads UNKNOWN as null with status UNKNOWN. It
  never defaults it. It raises an error only for a malformed fixture, for
  example an INVALID capture offered as an input or expected value. A
  validation that needs an UNKNOWN field reports NOT-COMPARABLE, not an error.
- **No promotion.** A PASS on an arithmetic check never upgrades any broker
  formula, rate, rounding rule, margin or break-even method from UNVERIFIED to
  VERIFIED. Only the documentary evidence in §8.5 can.

**Assertable now (arithmetic only, no broker rules):**

- With lot size 65 (supplied): 1 lot gives quantity 65.
- Using the displayed ₹22,520 explicitly as the valuation price (I22): 22,520 ×
  65 = 14,63,800, numerically equal to the displayed exchange turnover. This
  checks the arithmetic and the consistency of the supplied lot size. It does
  not make the notional and the turnover the same thing, does not verify that
  ₹22,520 is the entry price or any particular price type, and does not
  establish which leg the turnover belongs to.
- 14,63,800 ÷ 1,65,842.15 ≈ 8.83. This checks the internal consistency of two
  supplied figures. It is not an ALTRIXA leverage output (C9 is null for the
  fixture) and it does **not** validate the margin.
- 85.95 + 788.57 = 874.52. This checks the sum of the stated totals only.

**Blocked (NOT-COMPARABLE until the unknowns are resolved):** any charge
component, rate or rounding behaviour; the SELL-leg total as an exact value;
margin correctness against SPAN + ELM or against a broker; any comparison
of the displayed turnover with a specific leg's entry value (C3) or exit value
(C4), because the turnover-leg association is UNKNOWN; SELL-side
break-even; ALTRIXA's own break-even; any expectation that depends on date,
expiry, NRML/MIS, price type or add-on content.

**Structural rules for any future implementation:**

1. **Purity.** Same inputs always give the same outputs. No hidden defaults.
2. **Per-leg charges.** Each leg uses its own side and its own leg value. LONG
   and SHORT must be tested with the sides swapped and no charge shared by
   assumption.
3. **Capture validity.** Fixture captures carry a status (VALID or INVALID). An
   INVALID capture cannot supply an input or an expected value. Zero is not
   treated as missing and missing is not treated as zero. Every captured
   value, including a displayed turnover amount, carries an explicit VALID or
   INVALID classification. A zero or negative amount never silently stands in
   for missing data (missing is UNKNOWN), and the invalid ₹0 SELL screenshot is
   never a valid zero-charge observation.
4. **No fixture leakage.** Production code does not contain or reference any
   fixture value, rate or margin.
5. **Provenance on every output** per §8.4, with weakest-link propagation.
6. **Tolerances.** Those in §5 remain provisional and apply only once the
   relevant rounding behaviour is verified. Margin differences are reported,
   not forced to match.

**Implementation status (Patch 1 plus Patch 2A).** These are factual notes on
where the current implementation is narrower than, or does not yet cover, this
section. None of them changes the contract above.

- Break-even (C10) is solved exactly only when no schedule component declares
  rounding, a minimum or a cap; otherwise it returns NOT_MODELLED. Schedules
  that are continuous but piecewise (a minimum or cap without rounding) are not
  yet solved, although this section would permit an exact solution.
- A schedule has no way to declare an order of operations or whether a
  dependent component uses a rounded or unrounded base. The implementation
  therefore rejects a schedule that combines rounding with a minimum or cap, or
  that uses a rounded component as another component's base. A dependent
  component has a single base, a broker profile and its dated rate table are one
  object, and a schedule has no effective-to date.
- No fixture loader or full comparison record exists. Only the displayed-
  turnover comparison is implemented (states PASS, FAIL, NOT_COMPARABLE,
  INVALID_INPUT). The capture wrapper turns an INVALID capture into an invalid
  input, so the result is NOT_COMPARABLE or INVALID_INPUT; it does not raise an
  error.
- Not implemented: free capital (deferred, §8.4), the broker-displayed
  break-even as a supplied comparison value, and market status (I21).

### 8.8 What would unblock exact validation

A valid capture of the SELL estimate with its own price, turnover and date;
evidence of which leg the displayed ₹14,63,800 turnover belongs to;
the estimate date; NRML vs MIS; the exact expiry date and contract identifier;
whether the margin includes broker add-ons or exposure margin; the reference
price type; an authoritative lot-size source; and dated evidence of Dhan's
charge formula. Until then, charge and margin comparisons stay NOT-COMPARABLE.
