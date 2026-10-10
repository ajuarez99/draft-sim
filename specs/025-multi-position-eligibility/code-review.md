# Code review: spec 025 (multi-position eligibility)

Bug-hunting review of `git diff 024-draft-room-ux` plus the untracked files, in worktree `draft-sim-025` on 2026-10-10. The review was read-only: the only file written is this report. Throwaway probe scripts lived in the session scratchpad, bundled with the repo's own esbuild and run with node against the real `web/src` modules.

**What was run:** `npx tsc -b` was clean. `npx vitest run` passed **1663/1663** (109 files). On the backend, `NbaLineupParityFixtureTest` (2/2), `Spec025SimBaselineTest` (1/1), `EngineOutputFrozenTest` (2/2) and every `LeagueController*` test (57 in total, 0 skipped) passed. All other claims below are marked *verified* (executed) or *inferred* (read, not run).

---

## BUG

### B1. "Fills X" names a slot the roster then shows as still open (verified)

**Where:**
- `web/src/lineup.ts:165-173`: `canJoin`'s slot choice, research A1.
- `web/src/pickInsight.ts:24-43`: `fillsFor` and `openAfter`.
- Indirectly `teamNeeds.ts:163-170`, `makeFitFor` and `fitSlot`.

**The mismatch.** A1 names "an open slot he is directly eligible for, dedicated first". But the lineup the UI then *shows* is `computeTeamNeeds` → `seatLineup` on the roster with the player added, and that re-runs the greedy. The greedy's Kuhn search walks the slots in template order, so it often seats the new player in a *filled* slot and shifts the occupant into a *different* open slot. The slot named and the slot actually filled disagree.

**Concrete case:**
1. The roster is a pure PG at ADP 5. You draft a PG/SG at ADP 20.
2. `fillsFor` returns **"SG"**.
3. `openAfter` returns **SG**, SF, PF, F, C, UTIL, UTIL.
4. The re-run seats the PG/SG at PG and moves the pure PG to G, so SG stays empty.

The live pick card then prints, verbatim from `summarize`: **"Fills SG. Still open: SG, SF, PF, F, C, UTIL, UTIL."**

**The mirror case:** an SF on the roster, then you draft a PF/SF. The card says "Fills PF", yet PF is still listed as open.

**Measured on real rosters.** I replayed the four real auto-drafted mock rosters from the parity fixture (3721–3724) pick by pick. **9 of 32** "Fills X" claims are contradicted by `openAfter`: the named slot is still open in the same or greater number. Examples:

| Mock | Pick | Card says | Still open after the pick |
|---|---|---|---|
| 3721 | pick 3, PG/SG | Fills SG | SG,… |
| 3721 | pick 5, PG | Fills UTIL1 | UTIL,UTIL (SG got filled) |
| 3722 | pick 8, PF/SF/SG | Fills F | F |

The same contradiction shows up between the picker's row tag ("Fills SG") and the `TeamStrip` / compact-row strip after the pick. The parity fixture can't catch this: it pins `canJoin.ok` and "never a filled slot", not agreement with the re-run seating.

**Suggested fix.** Name the slot as the difference between the open-slot lists of `seatLineup(roster)` and `seatLineup(roster + player)`. That is cheap: 9 slots, cached per player by `makeFitFor`. Then the tag, the card and the strip come from one seating rule. A test should assert, for every fixture prefix and next pick, that `fillsFor`'s slot kind has one fewer open entry in `openAfter`.

---

## RISK

### R1. A steal/reach tint wipes a multi-position cell's colour split (inferred from the CSS cascade, not rendered)

**Where:** `styles.css:957-968` vs `5200-5205`.

**Why.** `.board .cell.pos-multi` uses the `background` shorthand. That makes the split a `background-image` and leaves `background-color` transparent. `.board.value-view .cell.value-steal/.value-reach` sets `background-image` at a higher specificity (0,4,0 vs 0,3,0), so it **replaces** the split gradient instead of overlaying it.

**Effect.**
- A single-position cell keeps its position colour, because that sits in `background-color`, under the tint.
- A multi-position steal or reach cell renders as a bare up/down tint over the board background, with no position colour.
- The `:hover` split is lost the same way.

This affects any NBA board with value-view on: the CompletedDraftBoard, and value view in the rooms. The inline `--vt` merge in `DraftBoard.tsx:379-385` is fine. **Fix:** give the multi rule a `background-color` base, and make the value rules layer both images, or put the split on a pseudo-element.

### R2. Football is unchanged only because no active NFL player has two positions (verified on 5433)

**What changes for a football player who does have two.** `eligiblePositions` is used for football too, in these places:
- `scarcity.ts:89`: counted in both pools.
- The filters in `AvailabilityPanel.tsx:342,440`, `PlayerPicker.tsx:66` and `OnTheClockPickInput.tsx:54`: listed under both.
- `posPill` / `multiVars`: split pill, no `pos-QB` class.
- `DraftBoard.tsx:250-306`: compact `familyCode` instead of "TE4".
- `leadHueStyle`: no hue.

**Local data:**
- 9 NFL players have more than one position (`{TE,WR}`, `{RB,TE}`, `{QB,RB}`, …).
- **None is Active, and none has an ADP row.**

So SC-005 holds on today's data, not by construction. If Sleeper lists a fantasy-relevant football player at two positions, every football screen changes without warning, and the ScarcityMeter's eligibility wording (NBA-only) wouldn't explain the double count. **Suggested fix:** decide explicitly. Either gate the multi behaviour on `sport === 'nba'`, or accept it and say so in the spec.

### R3. `lineup.ts` generalizes to any template, but `BasketballRules` hard-codes nine slots (inferred)

**Where:** `lineup.ts:95-96` reads `rosterPositions`. `BasketballRules.SLOTS` is fixed at PG,SG,G,SF,PF,F,C,UTIL,UTIL and ignores `rosterPositions`.

**Effect.** For any other template, the client's lineup and "Fills" diverge from what the simulation scores:
- an extra UTIL;
- no G/F;
- a different slot order, which changes which slot the Kuhn walk fills, and so which slot the UI names.

The parity fixture covers only the real template, plus BN and no IR.

**Local data (verified):** all 4 NBA leagues on 5433 use exactly the real template, so this is latent. At minimum, put a comment on `seatLineup` saying that parity only holds for that template.

### R4. Two implementations of the "no position" rule (verified by reading)

**Where:** `pickRun.ts:60` (`nbaFamily`) re-implements `positions.ts:38-42`, including the NBA "WR means no position" exception, inline.

This is the repo's recurring "two implementations of one rule" class. If the fallback changes in one place, run counting drifts from labels and lineups. **Fix:** call `eligiblePositions(p, 'nba')`. The `Positioned` type only needs `positions?: string[] | null`, which it already has.

---

## NIT

- **N1. Numbered vs bare slot names** (`teamNeeds.ts:241-247`). `fitSlot` numbers duplicate slot names ("UTIL2") while `makeFitFor` / `needLabel` say "Fills UTIL". The feed or card and the picker therefore word the same fit differently. This matches football's existing "RB2" vs "Fills RB" convention, so it is not new. Verified: two Cs on the roster, a third C gives `fitSlot` "UTIL2" and the tag "Fills UTIL".
- **N2. Only three colours** (`positions.ts:75`). `multiVars` stops at `--pos-a/b/c`. Three local NBA players are `{PF,PG,SF,SG}`; their pill and cell drop the PF colour while the label still says "PG/SG/SF/PF".
- **N3. Football colours for an NBA player with no position.** Such a player (the WR fallback, positions `[]`) still gets `pos WR` (`positions.ts:86`), `var(--wr)` hue (`:96`) and the `pos-WR` cell tint (`DraftBoard.tsx:254`), all football colours on a basketball board. Note #13 says this can't occur on the board; it is cheap to guard anyway.
- **N4. Dead code** (`teamNeeds.ts:51`). `POOLED_SLOTS.nba` is now dead, because the NBA path returns before reading it.
- **N5. The compact-density CSS change applies to football too** (`styles.css` ~5782). Verification records it as a fix for a spec-024 defect. Worth a line in SC-005's "no football screen changes" claim, which is otherwise contradicted.
- **N6. Fixture coverage gaps.** The fixture has no ADP ties, no 999 sentinels, no mask-0 players and no IR slot.
  - Probe, verified: two players at ADP 999 (PG/SG drafted first, then a pure PG) seat PG/SG at SG and the PG at PG. That matches the Java logic as read: a stable sort over `roster.picks()`, and equal ADP gives equal value.
  - Mask-0 players are skipped on both sides (`lineup.ts:111`, `BasketballRules.java:~200`).
  - Adding one tie case and one mask-0 case would pin both.
- **N7. The baseline test can't fail on this diff.** `Spec025SimBaselineTest` cannot fail here, because no engine, profile or sport code changed. It guards future changes; on its own it is no stronger evidence for SC-004 than the `git diff --stat`. That is fine, as long as verification doesn't present it as independent proof. `EngineOutputFrozenTest`'s regex strip (`, positions=\[[^\]]*\]`) is safe: positions never contain `]`, and the golden still matches.
- **N8. The run sentence can disappear.** In `ScarcityMeter.tsx:46`, `runChip` is the first *shown* running chip. If every chip in the running family is hidden (`poolSize` 0), the sentence is never printed. This is an edge case.

---

## Checked and clean

- **Memoization:**
  - `DraftView`, `LiveDraftView` and `MockDraftView` rebuild `myNeeds` and `myFitFor` whenever the roster's inputs change (`landedPicks`, `decidedThrough`, `userPicks`, `state`).
  - No stale lineup after a pick.
  - `PlayerPicker` and `OnTheClockPickInput` memoize on `needs`.
- **`makeFitFor`'s per-id cache:** safe. The cache lives per `needs` instance, and every `PlayerRef` on the wire comes from `PlayerRef.from(BoardEntry)` or `fallbackPlayerRef` (grepped). No `SimulationResult` is deserialized from storage, so one id cannot carry two eligibility lists within one payload.
- **`canJoin` (A1):**
  - It never names a filled slot. Asserted for all 31 masks × 37 cases, and the test passes.
  - `filledByShift` is correct: a successful augmenting path fills exactly one previously empty slot.
- **Football `fitterFor`:** `eligiblePositions(p, 'nfl')[0]` always equals `p.position`, because `Player.primary()` is `positions.getFirst()`. Football's `computeTeamNeeds` still reads `.position`.
- **`sport` is passed correctly at every call site:**
  - `DraftStatsTable` hard-codes `'nba'`, which is correct (it is an NBA-only stats view).
  - `posRank` / `posRankOrAdp` require `sport` at every call site. No NBA badge prints a rank.
  - `familyCode` no longer has a default.
- **`.position` reads still left:** only `PlayerFace` (a DEF logo check), `onBrand` (deliberate, per R9) and `teamNeeds`' football path. All correct.
- **Gradle:**
  - `regenFixture` / `regenBaseline` default to `""`, so they never equal `"true"` in a normal run.
  - The fixture is declared as a Test input.
  - `fixturePath()` resolves correctly from `backend/`.
  - The configuration cache is not enabled, so reading `System.getProperty` at configuration time is fine.
- **Compact CSS:** an empty cell keeps `pickno-open` in flow at the bottom-right, and the on-the-clock tag sits top-left with round.pick at top-right. CompletedDraftBoard renders `density="full"`, so the compact rules don't touch its value or grade tints. R1 is the exception.
