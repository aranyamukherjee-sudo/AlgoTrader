# ASI-3.3A.5 Android Device Verification

## Status

**COMPLETE — 19/19 tests passed on a physical Android device**

## Verification Result

- Test: `ASI-3.3A.5 Qualification Test`
- Status: `OK`
- Passed: `19/19`
- Deterministic repeat: `true`
- Message: `ASI-3.3A.5 breakout qualification device verification`

## Verified Behaviors

1. All five qualification criteria pass
2. Trend misalignment fails
3. Insufficient break distance fails
4. Poor close location fails
5. Insufficient range expansion fails
6. Insufficient volume confirmation fails
7. ATR unavailable is reported
8. Volume history unavailable is reported
9. Zero prior volume is unavailable
10. Optional criteria do not block qualification
11. Required unavailable criterion makes the result unqualified
12. Break-distance exact threshold passes
13. Close-location exact threshold passes
14. Range-expansion threshold can pass
15. Volume threshold can pass
16. Trend alignment can be optional
17. Future confirmed pivot does not affect as-of trend
18. ATR period is validated
19. Repeated evaluation is deterministic

## Device Verification Significance

The future-pivot test confirms that breakout qualification uses structure information available as of the breakout confirmation index and does not allow a later-confirmed pivot to influence the result.

The deterministic repeated-evaluation test confirms stable qualification output for identical inputs.

## Repository Baseline

- Commit: `4837669`
- Commit message: `ASI-3.3A.5 add breakout qualification tests`
- Device verification APK SHA-256:
  `b46015a619588a6e04051dc92d98b75bf5b60aedf1de4da78e37a3e0d19bde3e`

## Verification Scope

This record documents physical-device verification of the ASI-3.3A.5 test suite. The temporary Android verification harness used during testing was removed after verification and is not part of the production implementation.
