# C1: Simulation result equivalence (D3)

The interface being protected is `SimulationResult` as serialized to JSON: what `/api/sims`
and `/api/sims/stream` return, and what `web/src/api.ts` types.

## Cases (fixed seeds, built from in-memory `DraftContext` fixtures like `DraftSimulatorTest`'s)
1. NFL 12×15, no locked picks, 1 iteration
2. NFL 12×15, no locked picks, 5,000 iterations
3. NFL 12×15, 20 locked picks (`startState`), including one "drafted early in a minority" player (the locked-pick scrub path)
4. NBA context (BasketballRules, reversal round set), 1,000 iterations
5. Slot at the turn (my picks adjacent), 1,000 iterations

## Procedure
1. **Characterization commit**: `MonteCarloGoldenTest` serializes each result with
   `Jackson2ObjectMapperBuilder.json().build()` and compares it with
   `src/test/resources/golden/montecarlo/<case>.json` via `api/GoldenJson`
   (order-sensitive inside lists, exact number types). Green on the **old** `MonteCarloRunner`.
2. **Conversion commit**: D3. The diff touches 0 files under `golden/` or `*GoldenTest*`.

## Pass
Steps 1 and 2 are green. The full backend suite has 0 IT skips. The wall clock of case 2 is within ±10%
of the old code (best of 3, logged).
