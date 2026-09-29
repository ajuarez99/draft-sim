# 07 — Expected wins luck card blames the schedule by the sign of luck

**Severity: Medium (copy states a cause it never measured). Verified locally** on NFL 2026
"(Foot) Ball Knowers" (1346366555759341568). The server was down when this plan was written,
so the counterexample below comes from the audit pass. It was not re-fetched.

## What's there now

- `web/src/pages/ExpectedWins.tsx:199-203`: when `luckSource` is not `SWING_WEEKS`, the card
  says the team "consistently faced lower-scoring opponents" when `winsAboveExpected >= 0`,
  and "higher-scoring" otherwise. The direction comes from the sign of luck, not the schedule.
- Counterexample: Master Bates has luck **+0.45** and `strengthOfSchedule` **+7.17**. The
  page's own column defines positive as a *harder* schedule, yet the card says lower-scoring.
- `ExpectedWinsService.java:285`: `CONSISTENT_OPPONENT_SCORING` means only `swings.isEmpty()`.
  Nothing about opponents is measured. The enum name (`:57`) claims a cause the code never checks.
- With no swing weeks, all of a team's wins came from top-half weeks and all of its losses
  from bottom-half weeks. Luck is then what's left of all-play (`:82`): winning in 6th of 12
  earns 1 win against 6/11 expected. That comes from narrow margins near the median, not
  from the season's mean opponent PPG. So the signs of luck and SOS can legitimately disagree.
- This is recurring bug class #5: each number is correct, but the sentence joining them isn't.

## Fix

1. **Drive the direction from `strengthOfSchedule`**, not from `winsAboveExpected`:
   - Signs agree (luck > 0 with SOS < 0, or luck < 0 with SOS > 0): keep the sentence, but
     take "lower/higher-scoring" from the SOS sign and print the value ("opponents averaged
     7.2 fewer points than the league").
   - Signs disagree (luck > 0 with SOS > 0, or luck < 0 with SOS < 0): don't blame the
     schedule. Say something true, e.g. "No single week did this, and the schedule ran the
     other way (+7.2, harder). The edge came from winning (or losing) close weeks near the
     middle of the league's scores."
   - SOS near 0 (|SOS| < 1, a hand-set threshold, labelled as such): name neither direction.
2. **Backend: no third `LuckSource` value.** The discriminator's job is "swing weeks or not",
   and both signs the copy needs are already on the wire. A third value would add a second
   implementation of one rule, the landmine class in memory. Instead, fix the lie at its
   source:
   - Rename the Javadoc at `:56` to "no swing weeks; cause not attributed".
   - Leave the wire string alone. Renaming it would ripple into `SeasonSuperlativesLuckTest`,
     the contract test and specs/004. If Allan wants the rename (`NO_SWING_WEEKS`), do it in
     one change across `ExpectedWinsService`, `api.ts:1617`, both test files and the contract doc.
3. If the enum or the payload changes, mirror it in `web/src/api.ts` in the same commit.

## Not in scope

- Changing how luck, SOS or swing weeks are computed.
- The swing-week branch, which reports measured weeks and is fine.

## Acceptance criteria

- [ ] `ExpectedWins.test.tsx` covers four cases: luck + / SOS + and luck − / SOS −
      (the disagreeing pair, which must not say "lower/higher-scoring opponents"), plus both
      agreeing pairs (the direction word must match the SOS sign). The current fixture has
      SOS −12.4, so add rows that don't lean on it.
- [ ] `npx tsc -b`, `npm run build` and the web tests pass.
- [ ] In the browser against a real `bootRun`, on this league: for **every** luck card, the
      text's direction matches that team's SOS cell in the table. Master Bates must not read
      "lower-scoring". List each card against its row in the verification write-up.
