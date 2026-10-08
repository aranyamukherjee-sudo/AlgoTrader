# ASI-3.4.8 — Setup Evidence & Confidence Verification

## Scope

ASI-3.4 implements deterministic live setup evidence and confidence
assessment on top of the existing ASI-3.3 setup architecture.

## Verification status

| Step | Status | Verification |
|---|---|---|
| ASI-3.4.1 | PASS | Contract tests |
| ASI-3.4.2 | PASS | Live-evidence extraction tests |
| ASI-3.4.3 | PASS | Confidence calculation tests |
| ASI-3.4.4 | PASS | Contradiction/degradation tests |
| ASI-3.4.5 | PASS | Determinism, boundary and no-lookahead tests |
| ASI-3.4.6 | PASS | Setup assessment engine tests |
| ASI-3.4.7 | PASS | Physical Android device verification |
| ASI-3.4.8 | PASS | Documentation/checkpoint |

## Automated verification

ASI-3.4.1 through ASI-3.4.6 were verified through the existing
`core:intelligence` test suite.

The final ASI-3.4.5 focused verification covered 32 tests with zero
failures.

ASI-3.4.6 added the deterministic `SetupEvidenceAssessmentEngine` composition
service and six focused tests. The full `core:intelligence` test suite passed
with zero failures.

## Physical-device verification

A temporary Android verification harness was used to exercise the actual
ASI-3.4.6 assessment engine through the Android application.

The first device run exposed an invalid test fixture lifecycle transition:

`BROKEN -> RETEST_HELD`

The fixture was corrected to use the existing ASI-3.3 lifecycle:

`BROKEN -> RETEST_TOUCHED -> RETEST_HELD`

The corrected physical-device run reported:

- Evidence: 4
- Confidence: 1.0
- Condition: SUPPORTING
- Change: INITIAL
- Actionable: true
- Degraded: true
- Contradicting: true
- Insufficient assessment null: true
- Future evidence excluded: true
- Deterministic repeat: true
- ALL CHECKS: true

Individual device checks all passed:

- actionable=true
- confidencePresent=true
- liveEvidence=true
- retestEvidence=true
- degraded=true
- contradicting=true
- insufficientNoReading=true
- insufficientNoAssessment=true
- futureRetestExcluded=true
- deterministic=true

The temporary Android harness and its temporary Strategies screen entry were
removed after successful device verification.

## Final Android verification

After removing the temporary harness, the application passed:

`./gradlew :app:compileDebugKotlin --no-daemon`

Result: `BUILD SUCCESSFUL`

The final repository contains no temporary ASI-3.4.7 Android verification
code.

## Architecture boundary

ASI-3.4 does not duplicate the existing `Evidence`, `Confidence`,
`Opportunity`, or ASI-3.3 setup lifecycle architecture.

The production integration added by ASI-3.4.6 is the narrow
`SetupEvidenceAssessmentEngine` composition service around an existing
`BreakoutSetup`.

No speculative Android runtime integration was added solely to satisfy
device verification.

## Checkpoint

ASI-3.4 is complete through physical-device verification and documentation.

Next planned work: ASI-3.5.
