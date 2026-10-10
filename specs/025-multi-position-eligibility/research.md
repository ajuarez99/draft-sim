# Research: Multi-position eligibility (spec 025)

Phase 0 for [plan.md](plan.md). Each entry is labelled by how it was established: **[measured]** (run on 2026-10-09), **[read]** (read from the code), or **[decided]**.

## R1. Where the single position comes from [measured + read]

- **Ingest keeps Sleeper's order.** `PlayerIngestService.fantasyPositions` stores Sleeper's `fantasy_positions` in the order Sleeper sends them.
- **Sleeper's order is alphabetical.** It sorts that list alphabetically for every one of the 1,478 active multi-position NBA players. Sleeper's separately published primary (`position`) matches the first entry for only 769 of them, 52%.
- **The app takes the first entry.** `Player.primary()` is `positions.getFirst()`, and `BoardEntry.position()` comes from it.
- **One record feeds the frontend.** The frontend's `PlayerRef.position` is set from it by `SimulationResult.PlayerRef.from` and by `LeagueController.fallbackPlayerRef`, which are the **only two** construction sites. Every player on the wire uses that record: board cells, pool, picks (`RealPick.player`), mock state and targets.

## R2. The wire change [decided]

- **Backend.** Add `List<String> positions` to `SimulationResult.PlayerRef`, filled from `player.positions()` in Sleeper's stored order. Both construction sites change. Nothing in the engine reads `PlayerRef`, so **simulation inputs and numbers are untouched** (FR-009). The record is only the wire shape.
- **Frontend.** `api.ts` gets `positions?: string[]`, optional because the frontend and backend deploy separately. A single helper, `eligiblePositions(p)`, returns `p.positions` if it is present and non-empty, otherwise `[p.position]`. **Every eligibility reader goes through this helper**, so there is exactly one fallback rule.
- **Display order.** Labels use one fixed basketball order, PG, SG, SF, PF, C, not Sleeper's alphabetical order. Football players with several positions keep their stored order. That's 9 of 4,387 players.

## R3. Labels and the positional rank [measured + decided]

- **[read] The rank is the app's own.** `BoardService` numbers each position by a running count over `player.primary()`, the alphabetical-first position. **It is not Sleeper's rank.** So for Anthony Edwards, "PG3" means the third player whose alphabetically first position is PG.
- **[decided] The badge for a multi-position NBA player** shows the eligible positions in basketball order, e.g. "PG/SG" or "SF/PF", and **no rank number.** Attaching the alphabetical rank to a label that claims no primary would put a number on an ordering this spec just stopped presenting. Single-position players keep "C2" and similar, because for them the rank is unambiguous.
- **Correction to the spec.** The spec's edge case said ranks "keep their current meaning". That was based on a wrong reading, and it is amended in the spec.
- **[decided] Pill colour.** A multi-position pill is coloured by a split gradient across each position's existing colour (`--pg`, `--sg`, …). It is not painted the colour of one position, since that would be a primary claim made in colour instead of text. The compact board cell keeps a 30 px-class pill by using a narrower font for multi-position text. T-final measures that "SG/SF/PF" fits.

## R4. Scarcity counting (FR-004 = A) [read + decided]

- **Today.** `scarcity.ts:80` counts `head.filter(p => p.position === position)`.
- **The change.** Count `eligiblePositions(p).includes(position)`. A drafted player leaves every pool he was in.
- **Expected values.** On the top 36 by ADP, measured, eligibility gives PG 18, SG 8, SF 7, PF 17, C 11. The 2026 12-team draft's pool is the top 108, which needs a live measurement.
- **Copy.** Chips read e.g. "SG 8 eligible / 8". The starter-pool note adds: "a player can count at more than one position". Spec 024's hidden-0-pool note stays, as a fallback for a real 0. Its text changes from "list these first" to "no starter-pool players are eligible here".

## R5. "Your team" and "fills a need": one rule (FR-005) [read + decided]

- **[read] The two rules today:**
  - **Backend**, `BasketballRules.prepareLineup`: a matroid greedy. Players are sorted by board value descending, which is equivalent to ADP ascending because `value = exp(-adp/decay)`. Each is kept if a Kuhn augmenting-path search can seat him in the fixed nine-slot template PG, SG, G, SF, PF, F, C, UTIL, UTIL. The class javadoc says this is proven optimal.
  - **Frontend**, `teamNeeds.ts`: a two-pass greedy by the single position.
- **The failure, verified by reasoning:** a PG/SG drafted first takes the PG slot by its first position. A later pure-PG then can't be seated, even though the backend would put PG/SG at SG and the pure PG at PG.
- **[decided] Port the backend's algorithm to TypeScript.** Matroid greedy by ADP ascending, ties broken by drafted order (Java's `List.sort` is stable), plus Kuhn augmentation. It's about 50 lines and replaces both passes. It generalizes to the league's actual `rosterPositions`, using the slot-eligibility table that `teamNeeds.ts` already mirrors from `BasketballRules.isEligible`.
- **[decided] Pinned by a cross-language parity fixture, not by trust.**
  - A backend test builds at least 20 rosters and writes `web/src/__fixtures__/nba-lineup-parity.json`, holding the rosters plus `BasketballRules`' output (filled slot kinds, seated ids, and `maskCanJoin` for every 32 masks). The rosters cover every shape seen in the top 108, the US3 counterexample, and the rosters from a completed auto-mock.
  - The backend test **fails if the committed file differs** from what it would generate, so drift breaks the build, with a message saying how to regenerate it.
  - A vitest test runs the TS port over the same file and asserts identical answers.

  This is the "share one source or pin with a parity test" clause of FR-005. A shared runtime source would mean a new endpoint called on every pick, which isn't worth the cost.
- **Football.** `computeTeamNeeds` keeps its current path for football. Its FLEX logic is proven and single-position, so it is unchanged (FR-008).
- **"Fills a need".** `openSlotFor` takes the player, not a position. He "fills a need" if he can be seated via augmentation (the TS version of `maskCanJoin`). The label names the slot he would land in, directly if eligible, otherwise "Fills SG" after a shift is still accurate, since the shift is invisible to the user. The fit clause on pick cards (`fitSlot`) reads the same function.

## R6. Filters and the pick-run detector [read + decided]

- **Filters.** Every draft-room filter compares `p.position === filter`: `AvailabilityPanel`, `PlayerPicker`, `OnTheClockPickInput`, `DraftStatsTable`. Each becomes `eligiblePositions(p).includes(filter)`. ALL is unchanged, so each player still appears once.
- **The pick-run detector** (`pickRun.ts`) counts a pick toward every position he is eligible for, under the same rule as R4. A PF/SF pick adds to both the PF run and the SF run.

## R7. What stays single-position [decided]

- **Other pages** (StatLeaderboard, Superlatives, WeeklyReport, PlayerPage, RosterManagement, LeagueAnalysis, ManagerHistory) keep `.position`. They're out of scope, and they still compile because `.position` is unchanged.
- **The engine,** including profile tilt and `BoardEntry.position`, is unchanged.
- **The separate, measured follow-up** is the chip "Use Sleeper's real primary position in the NBA sim".

## R8. Verifying "no simulation change" (SC-004) [decided]

The only backend change is a new field on a wire record. To verify SC-004:

1. `git diff` shows no file under `engine/`, `profile/` or `sport/` changed apart from tests.
2. The existing engine tests pass unchanged.
3. A fixed-seed mock replays identically. Create one NBA mock before the change and record its seed (`mock_draft_session.rng_seed`) and bot picks. After the change, re-run the same seed through `MockDraftEngine` in a test, and compare the bot picks.


---

## Amendments after plan review (2026-10-09)

Source: [plan-review.md](plan-review.md). It found 1 blocker, 10 should-fixes and 11 notes. The entries below supersede R3, R5 and R6 where they conflict. The originals are kept above so the error stays visible.

- **A1 (review #1, BLOCKER): which slot "Fills X" names.** R5's "the slot the candidate himself occupies after augmentation" is **wrong**. The backend's augmenting path usually seats the candidate in an *already-filled* slot and moves that slot's occupant into the open one. The review measured 64% of joinable checks on real mock rosters. **Corrected rule:**
  1. If the candidate can join, name an **open slot he is directly eligible for**: dedicated slot first, then pooled slots from most to least specific (G/F, then UTIL).
  2. Otherwise, name **the open slot the augmentation fills**.
  3. Never name a filled slot.

  Test: a second C, with C filled and UTIL open, reads "Fills UTIL", not "Fills C".
- **A2 (review #2, the user's decision): family runs.** Measured on the 2025 NBA draft's 163 six-pick windows at threshold 4:

  | Counting rule | Windows with a run | Ties |
  |---|---|---|
  | alphabetical-first (today) | 43 | 0 |
  | per-position eligibility | 108 | 17 |
  | **family, chosen** | **26** | **0** |

  For NBA, runs are counted by **family**: Guard (PG/SG), Forward (SF/PF), Center (C). A pick counts only when all his positions sit in one family. 113 of the 168 picks do; a player spanning families, such as SG/SF, counts toward none. The copy reads "4 of the last 6 were guards". Football runs are unchanged. **This supersedes R6's per-position run rule.**
- **A3 (review #3): no rank on any NBA badge.** R3 said single-position ranks are "unambiguous". **That is wrong.** Paolo Banchero shows "PF4", but he is 9th among PF-*eligible* players, because the rank counts only alphabetical-first PFs. NBA badges show positions only: "C", "PF", "PG/SG". Football keeps "RB4".
- **A4 (review #4): no change to `BasketballRules`.** `canJoinByMask` is derived through the public API: `rosterNeed(probe, lineup) > benchFloor`, for a probe player with each of the 32 masks and a value below every real player's. **T016 must not add an accessor**, which keeps T025's "nothing under `sport/` changed" check honest.
- **A5 (review #5): compare the fixture as parsed JSON.** On this machine `core.autocrlf=true` checks the fixture out with CRLF line endings, so it is compared as parsed JSON, not as bytes. It also records **per-slot seating**: template order, mapping slot to player id.
- **A6 (review #6): `AvailabilityPanel` takes a fit function.** Its `openSlots: Set<string>` prop is replaced by a `fitFor(player) => string | null` prop, built in each room from the room's lineup. The same function feeds `PlayerPicker`, `OnTheClockPickInput` and `pickInsight`.
- **A7 (review #7): colour.** The board-cell position tint and the pick feed's `--lead-hue` use the split colour for multi-position players, or a neutral colour where a gradient can't apply. Neither may use the first position's colour (FR-002).
- **A8 (review #8): threading `sport`.** `posRank(p, sport)` means passing `sport` through `PlayerCard` and `PickInsightCard`, which don't receive it today. **`CompletedDraftBoard` (`/drafts/:id/board`) also changes,** since it renders `DraftBoard`. That is accepted as in scope: the same component should show the same labels.
- **A9 (review #9, the user's decision): family code in compact cells.** Compact cells show a family code for multi-position players: PG/SG → "G", SF/PF → "F", C/PF → "F/C", a player spanning G and F → "G/F". Full cells, list rows and cards keep "PG/SG", and the compact pill's `title` gives the full list. The cell keeps at least 6 visible name characters at 1440 for 12 teams, measured.
- **A10 (review #10): correcting SC-001's baseline.** The 12-team 2026 room shows **4 of 5** chips today: only SG is hidden, and SF is 10/10. The "SG and SF hidden" observation came from the **4-team** draft. Measured top-108 eligibility for the 2026 draft: **PG 38, SG 41, SF 37, PF 45, C 31.** The contract's "PG 18 eligible" example used top-36 numbers, and is corrected.
- **Notes folded in:**
  - **#11:** the run copy lives in `AvailabilityPanel` (~593) and `CompactRow`/`PickFeed`, and the T009 disclaimer must cover them.
  - **#12:** the spec's "log at ingest" promise is dropped. No task built it, and it isn't needed for this feature.
  - **#13:** a player with no stored positions can't occur on the board. The fallback would say "WR" (the `Player.primary()` default), so the client treats a `position` of WR on an NBA player as "no position".
  - **#16:** SC-004's baseline must be captured **at `f638445` before T003** (new task T002b).
  - **#18:** `onBrand`'s "leans PF" prints an alphabetical-first position. It stays as a consistent display of the fitted tilt, but its text says "by listed position" until the simulation follow-up lands.

## R9. `onBrand.ts` stays on the first listed position (T023) [decided]

The room read's positional lean compares a manager's picks against his fitted tilt. `ProfileService` fits that tilt from `Player.primary()`, the alphabetical-first position. Counting the picks by eligibility would compare two different definitions, so `onBrand.ts` keeps `.position`. Both move together in the follow-up that adopts Sleeper's real primary. The lean text carries a `title` saying it is "by each player's first listed position", so the display doesn't present an alphabetical position as a real one without saying so.

## A11 (code review B1, 2026-10-10): how "Fills X" names its slot

A1's "directly eligible open slot first" was itself wrong. The team strip re-runs the seating, which can seat the newcomer in a filled slot and shift that slot's occupant into a different open one. On the real mocks, 9 of 32 claims were contradicted. `canJoin.slot` is now **defined** as the slot that goes from open to filled when the lineup is re-seated with the player added, so the tag and the strip agree by construction. A property test pins this over every fixture case × all 32 masks.
