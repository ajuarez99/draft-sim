# Plan review: spec 025 (adversarial, before any code)

Reviewed cold on 2026-10-09 against `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/api.md`, `quickstart.md` and `tasks.md`, with every claim checked against the code at `f638445` and the local DB on 5433. Each finding is labelled **verified** (I ran it or read the code line) or **inferred** (reasoning or estimate, not run). Scratch scripts used for the measurements are not committed.

Summary: 1 BLOCKER, 10 SHOULD-FIX, 11 NOTE. The core plan (wire field + one helper + ported seating pinned by a fixture) holds up. The blocker is in what "Fills X" names. The largest unforced risk is the pick-run detector turning into noise.

---

## 1. BLOCKER: `canJoin().slot` ("the slot the candidate himself occupies") names an already-filled slot in most cases

**Evidence (verified, measured).** `BasketballRules.tryAssign` (`BasketballRules.java:201-212`) walks slots in template order and takes the **first** successful augmenting path, not the shortest. A candidate is therefore often seated in a filled slot whose occupant gets bumped into the open one.

I replayed every roster prefix of the three completed 12-team NBA mocks (sessions 3721-3723, 36 rosters × 14 prefixes) and probed all 31 masks with a Python port of `prepareLineup`:
- 10,142 joinable probes;
- **6,541 (64%)** seat the candidate in a slot that is currently **filled**;
- 194 (1.9%) can join only through a shift, with no directly eligible open slot.

**Example.** The roster holds one C, in the C slot. A second C is a candidate. Kuhn bumps the first C to UTIL and seats the candidate at C. Under T017/R5 the tag reads **"Fills C"** while "Your team" shows C filled and UTIL open.

The same thing breaks US3 AS-2. Take a PG/SG candidate when PG holds a pure PG and SG/G are open. Kuhn moves the pure PG to G and seats the candidate at PG, so the tag says "Fills PG", while AS-2 requires "Fills SG".

The parity fixture pins only the boolean `canJoinByMask`, so nothing would catch this.

**Amendment.**
- `canJoin` returns `ok` (pinned by parity), plus a **display slot** with its own rule:
  - (a) If an open slot directly accepts the candidate, name it, walking dedicated first, then G/F, then UTIL. That is today's `openSlotFor` walk, run over the mask.
  - (b) Otherwise (the ~2% shift-only case), name the **open slot that the augmenting path fills**, i.e. the slot that goes from empty to occupied, never the candidate's own seat.
- Add `teamNeeds` tests for both examples above, red under "candidate's own slot".
- Amend R5's sentence "after a shift is still accurate". It isn't: the strip would contradict the tag.

## 2. SHOULD-FIX: pick runs by eligibility fire in two thirds of windows. Decide the threshold and wording before T021.

**Evidence (verified, measured)** on the completed 2025 NBA draft `1229352720230514688` (168 picks, 163 six-pick windows, threshold 4):

| Counting rule | Windows with a "run" |
|---|---|
| alphabetical-first | 43 / 163 (26%) |
| eligibility (FR-006) | **108 / 163 (66%)** |

Of the 108 eligibility runs, 17 are tied at the top (e.g. four PF/SF picks give PF 4 and SF 4 from the *same* picks).

**Consequences.**
- At 66% the signal is mostly noise: it will almost always say "4 of the last 6 were PG".
- T022's tie rule, "ties go to the most recent", is undefined when the tied counts come from the same picks.

**Amendment.** Record the measurement in research R6, then pick one:
- (a) raise the NBA threshold, e.g. 5 of 6, re-measured on the same draft and stated;
- (b) count a pick toward a run only when **all** his eligible positions are within one family (G or F), and word it "G run";
- (c) keep 4/6 and accept the noise explicitly.

In every case, make the tie-break deterministic (fixed PG, SG, SF, PF, C order) and test it. Also see #11 on copy.

## 3. SHOULD-FIX: research R3 is wrong that single-position ranks are "unambiguous"

**Evidence (verified, DB).** The rank is a running count over `player.primary()` (`BoardService.java:128-131`). PF is never first when C is also listed, so a pure PF's rank skips every C/PF player. Latest blend board:

| Player | Shown | Rank among PF-eligible |
|---|---|---|
| Paolo Banchero | PF4 | 9th |
| Julius Randle | PF15 | 25th |
| Miles Bridges | PF19 | 29th |

PG drifts by one (Murray PG14 = 15th PG-eligible). C is exact, because C is always first alphabetically. Under the plan, "PF4" would sit next to rank-less "PF/SF" pills: the alphabetical artifact the spec removes from multi-position players would stay on single-position ones.

**Amendment.** Drop the rank from **all** NBA badges in the draft rooms; football is unchanged. Amend R3 and the spec's edge-case bullet visibly. Test: Banchero `['PF']` gives "PF", not "PF4".

## 4. SHOULD-FIX: T016's fallback (package-private accessor in `BasketballRules`) contradicts T025's SC-004 check. Use the public API instead.

**Evidence (verified).** T025 asserts the `git diff` under `{engine,profile,sport}` shows only `SimulationResult.java`, but T016 allows editing `sport/BasketballRules.java`. The accessor isn't needed: `Lineup` is private (`:165`), but `rosterNeed` (`:301-319`) exposes `maskCanJoin` exactly.
- If the candidate can join, `delta = own`, so `captured = 1` and `rosterNeed = 1.0`.
- If he can't, and he is given a tiny value (say ADP 10,000, value ≈ e^-167 > 0), then `own - evict < 0`, so `delta = 0` and `rosterNeed = benchFloor`.

That makes a binary probe per mask through the public interface. Construction follows `BasketballRulesTest.java:25`.

**Amendment.** T016 derives `canJoinByMask[m]` from `rosterNeed(probe(m, adp=10000), prepareLineup(...)) > benchFloor + 1e-9`, with the mask-0 entry always false. Remove the "add an accessor" clause.

## 5. SHOULD-FIX: comparing the fixture byte for byte will fail on this machine's checkout. Also pin per-slot seating.

**Evidence (verified).** `git config core.autocrlf` is `true`, there is no `.gitattributes`, and `.ts`/`.java` files are checked out with CRLF (`file web/src/api.ts`). A generator writing LF and comparing strings fails on every fresh checkout. `filledByKind` built from a `HashMap` (`:274`) has no stable key order either.

Separately, "Your team" renders **which player sits in which slot**. The fixture records only seated ids and kind counts, so a TS port that seats the right set in different slots passes parity and still shows a different strip from the backend.

**Amendment.**
- Compare parsed JSON trees (Jackson `readTree` equality), not strings, and use `TreeMap`/sorted output when writing.
- Add a `.gitattributes` line `web/src/__fixtures__/*.json text eol=lf`, or rely on the tree compare.
- Record `assignment`: the 9-entry list from `startingLineup` (`:374-386`), in `SLOTS` order with UTIL_1/UTIL_2 kept positional, as `[kind, id|null]`. Assert it in vitest.
- The TS port must iterate slots in **template order** (PG, SG, G, SF, PF, F, C, UTIL, UTIL), not in `SLOT_ELIGIBILITY`'s object-key order (`teamNeeds.ts:39-48` is PG, SG, SF, PF, C, G, F, UTIL). Otherwise both kind counts and assignments diverge.

## 6. SHOULD-FIX: `AvailabilityPanel`'s `openSlots: Set<string>` prop can't answer augmentation. T019 misses the prop chain.

**Evidence (verified).**
- `AvailabilityPanel.tsx:139` takes `openSlots?: Set<string>`, and `:286`/`:446` call `needLabel(sport, position, openSlots)`.
- The live room passes `openSlots={slotKnown ? myOpenSlots : undefined}` (`LiveDraftView.tsx:978`), which comes from `openPositions(sport, myNeeds)` (`:693`).

A set of open slot names can't say whether a PG joins by bumping a PG/SG to SG. T019 lists the call sites, but not the prop type or the LiveDraftView wiring.

Also, `startersPerTeam = computeTeamNeeds(sport, rosterPositions, []).length` (`LiveDraftView.tsx:591-594`) defines the scarcity S. The NBA path through `seatLineup` must still return one `SlotStatus` per starter slot for an empty roster.

**Amendment.**
- T019 changes the prop to the lineup/needs object, or to a `needFor(player)` callback memoized per roster.
- It updates `LiveDraftView.tsx:693/978`, plus any equivalent in DraftView/MockDraftView. `tsc` finds them once the type changes.
- Add the test `computeTeamNeeds('nba', template, []).length === 9`.

## 7. SHOULD-FIX: three colour-by-first-position sites are not specified. Colour is a primary claim too (FR-002).

**Evidence (verified).**
- The board cell tint `cell … pos-${shown.position}` (`DraftBoard.tsx:251`; CSS `styles.css:923-938`) fills the whole cell in the alphabetical-first colour.
- `PickFeed.tsx:122` and `PickInsightCard.tsx:81` set `--lead-hue: var(--${position})`.

T014/T015 cover only `.pos` pills ("DraftBoard ~251 … read each" leaves the cell open). Research R3 itself says a single colour would be "a primary claim made in colour".

**Amendment.** Add the following to T014/T015 explicitly:
- a `cell pos-multi` class with a neutral tint (or the same gradient at 22%);
- for `--lead-hue`, a neutral hue for multi-position players;
- tests that a PG/SG cell carries neither `pos-PG` nor `pos-SG`.

## 8. SHOULD-FIX: requiring `sport` in `posRank` means threading props that don't exist. A fourth page changes too.

**Evidence (verified).**
- The callers: `posRank` at 8 sites in AvailabilityPanel, DraftBoard (×2), DraftStatsTable, PickFeed and PlayerCard (×3); `posRankOrAdp` at 2 sites (OnTheClockPickInput, PlayerPicker).
- `PlayerCard.tsx` and `PickInsightCard.tsx` have **no `sport` prop** (0 references).
- `DraftBoard` and `PlayerCard` are also used by `pages/CompletedDraftBoard.tsx` (`/drafts/:id/board`). That page isn't one of the spec's "three draft rooms", but its labels will change.
- No non-draft page imports `posRank`, so the out-of-scope pages are safe.

**Amendment.**
- Either thread `sport` into PlayerCard (from DraftView, LiveDraftView and CompletedDraftBoard) and into PickInsightCard, and list those edits in T013/T014; or derive the order from the position codes, since NBA and NFL codes are disjoint.
- Add CompletedDraftBoard to the spec's scope and to the T024 live checks.
- For `posRankOrAdp` with a multi-position player, return the ADP form ("ADP 6"), not a label that repeats the pill beside it.

## 9. SHOULD-FIX: the pill won't fit the compact cell without a decided fallback

**Evidence.**
- *Verified:* `.pos` is 10px bold with 7px horizontal padding and `min-width: 30px` (`styles.css:796`). Compact cells are `padding: 0 7px; gap: 6px`, with the pill set to `flex: 0 0 auto` and the name set to `min-width: 0` (`:5744-5746`). Columns have a 96px floor (`:5270`).
- *Inferred estimate, not measured:** at ~6.5 px per bold capital, "PG12" is about 38px, "PG/SG" about 44px and "SG/SF/PF" about 61px. In a 96px cell, 14 + 12 + ~27 (pickno) leaves about 43px for the pill and name together. The pill can't shrink, so a three-position pill leaves the name **zero** width. That would undo spec 013's measured "J. Gibbs fits".
- *Verified:* 12 of the top 108 are three-position players (PF/SF/SG 11, plus 1 PF/PG/SF and 1 PG/SF/SG = 13 by the DB query).

**Amendment.** Decide the fallback before build, e.g. compact cells render the pill with no slashes in 9px. Give T024 a pass criterion: in a compact cell at 1440 for 12 teams, the abbreviated name keeps ≥ 6 visible characters with a three-position pill. Measure it, don't eyeball it.

## 10. SHOULD-FIX: SC-001's baseline and the contract's example numbers are wrong for the 12-team draft

**Evidence (verified, DB).** In the 12-team pool (S = 108, `/drafts/{id}/pool` = `currentBoard`, latest blend capture 2026-09-28), first-listed counts are PG 37, **SG 0, SF 10**, PF 30, C 31. Today the 12-team room hides **only SG**, so it shows 4 of 5, not "3 of 5 today".

Spec 024's "SG, SF hidden" came from a **4-team** draft (`024/verification.md:177-179`, S = 36). That matches the spec's top-36 table, not the 2026 room.

By eligibility on top 108, the counts are **PG 38, SG 41, SF 37, PF 45, C 31** (sum 192). **No chip can be 0.** The four blend captures (09-08 … 09-28) give identical counts.

`contracts/api.md`'s "PG 18 eligible / 18" uses top-36 numbers. The top-108 shapes also differ slightly from spec §3:

| Shape | Spec §3 | Now |
|---|---|---|
| PG/SG | 20 | 19 |
| C/PF | 14 | 15 |

Plus PF 4, PF/PG/SF 1, PG/SF/SG 1.

**Amendment.**
- Correct SC-001's baseline (4 of 5 for 12 teams; 3 of 5 for 4 teams) and the contract's example.
- Pin the SC-002 and T011 fixture to a named capture date.
- Expected live values for T024: 38/41/37/45/31, if the board isn't rebuilt first.

## 11. SHOULD-FIX: run copy and the "eligible" disclaimer live in places the tasks don't touch

**Evidence (verified).** Run text "N of the last 6 were X" appears in:
- `ScarcityMeter.tsx:53`;
- `PickFeed.tsx:83`;
- `AvailabilityPanel.tsx:593`;
- `LiveDraftView.tsx:669`.

Under FR-006 these overstate ("were PF" for PF/SF players). Also, `.scarcity-meter .scarcity-note` is `nowrap` plus ellipsis, and `.compact-scarcity … .scarcity-note` is capped at `max-width: 34ch` (`styles.css:5670`, `:5760`). The definition note is already about 57 characters, so T009's added "a player can count at more than one position" will be truncated out of sight in the compact row.

**Amendment.**
- NBA copy reads "were PF-eligible", or "were forwards" if #2(b) is chosen.
- Add the four sites to T022.
- T009 puts "eligible" in the chip text itself, which FR-004 requires anyway, and verifies the disclaimer is visible (or in `title`) in compact mode.

## 12. NOTE: the spec promises ingest logging, but no task builds it

**Evidence (verified).** The spec's edge case says a Sleeper primary missing from the stored list "is logged at ingest". `nba.json` has **6** active multi-position players whose `position` isn't in `fantasy_positions`. The Assumptions section says the primary "is not stored and is not needed". No task touches `PlayerIngestService`.

**Amendment.** Either add a small ingest-log task outside `engine/profile/sport`, or strike the clause visibly.

## 13. NOTE: the "no stored positions" edge case can't happen, and the fallback would say "WR"

**Evidence (verified).**
- Ingest skips empty lists (`PlayerIngestService.java:91`), and `currentBoard` skips them (`BoardService.java:335`). The DB has 0 empty-position players in either sport.
- If one did appear, `Player.primary()` returns `WR` (`Player.java:19`), and `eligiblePositions` would fall back to `['WR']` for an NBA player. That contradicts the spec ("never silently given a default").

**Amendment.** In `eligiblePositions`, `positions === undefined` means an old backend, so return `[position]`. A **present but empty** array means no positions, so return `[]`. Note that the case is unreachable today.

## 14. NOTE: value order and roster order, checked (attack point 1)

**Verified.**
- `value = exp(-adp/60)` (`weights.yml:69`; `BasketballRules.java:121`). It is strictly decreasing and doesn't underflow until adp ≈ 44,700. The comparator (`:217`) sorts value descending, and `List.sort` is stable. **ADP ascending with stable draft order is exactly equivalent.**
- Board ADPs are distinct integers `i+1` (`BoardService.java:121-133`), so ties occur only among `adp = 999` fallback refs (`LeagueController.java:333-334`). `adp` is a primitive, so it can't be NaN on the wire.
- `RosterState.picks()` is insertion (pick) order (`RosterState.java:22-23,44`).
- The greedy doesn't stop at the first failure but stops at 9 kept (`:232-233`). The TS port must do both.
- Bench players matter only through the greedy's choice of who is kept, and that falls out of the port.
- The matroid rank argument means "can join" doesn't depend on which optimal basis was chosen, so "fills a need" = "starter count rises by one" is a correct user-facing meaning. Only the slot *name* (#1) needs a rule.

**Divergence (verified).** The backend's lineup includes only on-board players: `chooseAuto` skips anyone `ctx.byId()` lacks (`MockDraftService.java:541-543`). The room includes off-board picks at adp 999. That is rare (live picks of off-board players only), but state in R5 that "Your team" deliberately counts them.

**Also verified, the US3 bug is real.** I compared the old two-pass greedy with Kuhn on all 36 rosters of completed mocks 3721-3723. **34 of 36 differ**: Kuhn seats 9 every time, the old code seats 7 or 8. These are good hard-coded sources for T016's "auto-mock rosters" cases.

## 15. NOTE: the parity fixture under `web/` from a backend test is acceptable (attack point 2)

**Verified.**
- Gradle runs tests from `backend/`, and there is precedent: `NoSportNameInPlayerSpotlightTest.java:63-74` reads `../web/src/...`.
- The backend Dockerfile runs only `gradle bootJar` (no tests), and Railway builds from Dockerfiles (`railway.toml`, `web/railway.toml`).
- The only GitHub workflow is `daily-refresh.yml`, which runs no tests. So a stale fixture can't break a deploy; it fails only the local suite.
- `web/Dockerfile` copies `src` (fixture included), and `tsconfig.json` has `resolveJsonModule: true`.

Caveat: since no CI runs either suite, the "drift breaks the build" guarantee holds only when someone runs `./gradlew test` locally. Say so in R5.

## 16. NOTE: SC-004's fixed-seed replay is feasible, but only proves anything if the baseline is captured first

**Verified.** `MockDraftEngine.advanceUntilUserOrEnd(ctx, seats, rngSeed)` is deterministic per seed (`MockDraftEngine.java:116`). `MockDraftEngineTest.java:44-53` shows a synthetic `DraftContext`; `MonteCarloRunner.run(..., seed, ...)` is likewise seedable (`:35-85`).

R8's version (record a live mock's seed, then "re-run through MockDraftEngine in a test") needs the DB-built `DraftContextFactory` context, so it's an IT and not hermetic. Because the change is wire-only, a replay written *after* T003 is tautological.

**Amendment.** Write a hermetic golden test before T003 on `f638445`:
- a synthetic NBA board with multi-position players and all-bot seats;
- a fixed seed for both MockDraftEngine and MonteCarloRunner;
- record the bot picks and survival, then re-run after.

Keep the `git diff` check as the primary proof.

## 17. NOTE: FR-008 has zero visible effect on football today (attack point 7)

**Verified.** The 9 multi-position NFL players are B.J. Daniels, Will Johnson, Tyler Thigpen, James Casey, Kelvin Benjamin, Richie Brockel, David Johnson, Vince Mayle and Marcus Thigpen. All are teamless, none is on the NFL blend board, and none appears in any `draft_pick` or `mock_draft_pick` row. SC-005 can't regress through them. State that in the spec rather than "9 edge cases".

## 18. NOTE: `onBrand.ts` on `.position` is consistent with the fit, but its UI prints a primary (attack point 5)

**Verified.** `ProfileService.fit` maps every player to `primary()` (`ProfileService.java:135`) and counts early picks by it (`:186-192`). `onBrand.ts:47,53` counts by `PlayerRef.position`, which is also `primary()`, so T023's comparison is like for like.

However, `OnBrandPanel.tsx:45` renders "leans PF (…)", and `mixText(read.mix)` renders per-position counts in the live room. Those are user-visible labels built from the alphabetical first, which FR-002 forbids in the draft rooms. SG can never appear in a mix unless the player is a pure SG.

**Amendment.** In T023, also qualify the copy (e.g. "leans PF (by listed-first position)", with the follow-up named in `title`), or record it in the spec as an explicit FR-002 exception.

## 19. NOTE: contract and task inventory corrections (attack points 4 and 9)

**Verified.**
- **Every wire `PlayerRef` goes through the two constructors:**
  - `LeagueController.java:119` (pool) and `:357-359` (RealPick);
  - `TargetController.java:144`;
  - `MonteCarloRunner.java:240`;
  - `MockDraftService.java:704,709`.
- Mock picks whose player is off the board send `player: null` (`:704`), so they carry no `positions`, which is fine.
- `SimulationResult` is never persisted, so there's no stale stored JSON.
- **`TargetStrip.tsx:108` renders no position pill**, only `PlayerFace`. T014's TargetStrip entry is a no-op unless the spec wants a new pill, and FR-007 lists "target chips". Decide which.
- **`DraftStatsTable` has no position filter.** The stats-mode filter is `AvailabilityPanel.tsx:439`, which T012 already lists. Drop the DraftStatsTable line.
- React keys are per player id (`AvailabilityPanel.tsx:676`) or per position row (`ScarcityMeter.tsx:41`), so nothing double-renders.
- No other draft-room module (`tiers.ts`, `draftRoomStats`, `CompactRow`, MockDraftView, DraftView) reads `.position`.

## 20. NOTE: type `positions` as the position union

`positions?: PlayerRef['position'][]`, not `string[]`, so a typo in `eligiblePositions(...).includes('SF ')` is caught. *Inferred; trivial.*

## 21. NOTE: the rosterPositions-shape claim holds

**Verified.** The only NBA mock template stored is `{PG,SG,G,SF,PF,F,C,UTIL,UTIL,BN×5}`, which matches `BasketballRules.SLOTS` (`:63`). I didn't re-query the four league rows' `roster_positions` column contents beyond the plan's statement.

## 22. NOTE: the spec's Sleeper facts reproduce exactly

**Verified** from `nba.json`:
- 1,478 active multi-position players, all with alphabetically sorted `fantasy_positions`;
- 769 whose `position` equals the first entry;
- Edwards, Booker, Tatum, Durant and LeBron exactly as tabled;
- in the DB, NBA 1,679 / 2,091 and NFL 9 / 4,387 multi-position, with 0 empty.
