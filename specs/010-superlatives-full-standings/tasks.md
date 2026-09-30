---
description: "Task list for 010-superlatives-full-standings"
---

# Tasks: Superlatives full standings

**Input**: Design documents from `specs/010-superlatives-full-standings/`, and the design itself in
`claude/superlatives-full-standings.md`.

**Prerequisites**:
- `plan.md` is required. Read its "Amended after checking the code" section first; it overrides the
  design doc in five places. Amendment 1 is itself a visible correction.
- `spec.md` is required.

**Tests are included.** The design doc's acceptance criteria require ordering tests per kind. This
repo's recurring bug class #1 is a sign or direction inversion that structural tests don't catch.

**Line numbers are from `main` at `77151f6`** (the same code as `04c2043`, which the plan and design doc cite; 77151f6 only added docs). Re-find each anchor by its quoted code before editing.

Path prefixes:
- `BE` = `backend/src/main/java/com/ballknowers/draftsim`
- `BT` = `backend/src/test/java/com/ballknowers/draftsim`
- `web` = `web/src`

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [ ] T000 Run the adversarial plan review before any code (the AGENTS.md pipeline).
  - A separate pass, not the author: read `specs/010-superlatives-full-standings/spec.md`, `plan.md` and this `tasks.md` cold, against `BE/engine/SeasonSuperlativesService.java` on `main`.
  - Hunt for gaps the same way `/speckit-analyze` found F1 and U1: claims about the code that were never run, and "absent means 0" rules that hide missing data.
  - Write the findings to `specs/010-superlatives-full-standings/plan-review.md`. Fold any fixes into these docs as visible amendments before T002.
- [X] T001 Record the baseline.
  - In `backend`, run `./gradlew test`, then note the pass, fail and **skip** counts from
    `backend/build/reports/tests/test/index.html`. A high skip count means Postgres on 5433 is
    down and the ITs never ran; see memory "Backend suite skips ITs silently".
  - In `web`, run `npx tsc -b && npx vitest run src/pages/Superlatives.test.tsx`.
  - Write both results at the top of `specs/010-superlatives-full-standings/verification.md` under
    "Baseline".

---

## Phase 2: Foundational (blocks both stories)

- [ ] T002 Add the two new records and the two new fields in `BE/engine/SeasonSuperlativesService.java`.
  - Add these records next to `Holder` (`:95`):
    - `public record Standing(Integer rank, Holder team, Double value, String note, boolean hasValue, String missingReason) {}`
    - `public record PlayerStanding(int rank, String playerId, String playerName, String position, String team, int adds, int distinctTeams) {}`
  - Add `List<Standing> standings, List<PlayerStanding> playerStandings` as the last two components
    of `Superlative` (`:161`).
  - Keep the existing 10-arg and 11-arg constructors, defaulting both new lists to `List.of()`.
    Those constructors are used by `unavailable`, `notBuiltYet` and the empty-state returns, where
    empty is correct.
  - Invariants to document on the record: `rank == null` iff `!hasValue`; `missingReason != null`
    iff `!hasValue`.
- [ ] T003 Update `withUsernames` (`:1197` in `BE/engine/SeasonSuperlativesService.java`).
  - Map each `standings` row's `team` through the same username fill as `holders`.
  - Pass `standings` and `playerStandings` through to the rebuilt `Superlative`.
  - Plan amendment 3: without this, every row except rank 1 renders with no username.
- [ ] T004 [P] Create the pure ranking helper `BE/engine/SuperlativeStandings.java`, a final class
  with static methods only and no Spring (the same style as `MostAddedPlayers`).
  - Signature: `static List<Standing> rank(List<Entry> entries, boolean ascending)`, where
    `record Entry(Holder team, Double value, String note, String missingReason)`.
  - A null `value` means no value. That row sorts last, has `rank=null` and `hasValue=false`, and
    requires a non-null `missingReason`.
  - Rows with a value are sorted by value in the requested direction, then by `team.rosterId()`
    ascending for determinism.
  - Ranks are competition ranks (1, 1, 3), comparing values with `Double.compare`. Rounding is
    left to callers, so they must round the same way the winner's value is rounded (e.g.
    `round2`).
- [ ] T005 [P] Write `BT/engine/SuperlativeStandingsTest.java` for T004. It asserts:
  - descending order;
  - ascending order;
  - a 2-way tie gives 1, 1, 3;
  - a 3-way tie at the top gives 1, 1, 1, 4;
  - null-value rows come last with `rank == null` and `hasValue == false`;
  - a null value without a `missingReason` throws `IllegalArgumentException`.
- [ ] T006 [P] Mirror the types in `web/api.ts`, next to `SuperlativePlayerHolder` (`:1939`).
  - Add `export type SuperlativeStanding = { rank: number | null; team: SuperlativeHolder; value: number | null; note: string | null; hasValue: boolean; missingReason: string | null }`.
  - Add `export type SuperlativePlayerStanding = { rank: number; playerId: string; playerName: string; position: string | null; team: string | null; adds: number; distinctTeams: number }`.
  - Add `standings: SuperlativeStanding[]` and `playerStandings: SuperlativePlayerStanding[]` to
    `Superlative` (`:2045`).
  - Update every `Superlative` fixture in `web/pages/Superlatives.test.tsx` and anywhere else
    `tsc -b` flags, adding `standings: []` and `playerStandings: []`. This is the hard rule on
    `api.ts` from AGENTS.md.

**Checkpoint**: The backend compiles, `SuperlativeStandingsTest` is green, and `tsc -b` is clean.

---

## Phase 3: User Story 1, where every team finished for a team award (P1) 🎯 MVP

**Goal**: Every available team-headed award carries `standings`, and clicking its card opens a
modal that ranks every roster.

**Independent Test**: See spec US1. On "(Foot) Ball Knowers" 2025, Highest week lists 12 teams from
high to low, and rank 1 is the card's winner.

### Tests for User Story 1 (write first, and watch them fail)

- [ ] T007 [P] [US1] Write the week-score tests in `BT/engine/SeasonSuperlativesStandingsWeekTest.java`
  for the T010 functions.
  - Build `RosterWeekPointsRepository.WeekBreakdown` lists by hand, with null `playersPointsJson` and `starters`; the function reads only `week`, `rosterId` and `startersPoints`.
  - HIGHEST_WEEK uses each roster's **max** week, ordered high to low. Its note is `"week N"` for
    that week; when a roster ties its own max across weeks, the note uses the earliest.
  - LOWEST_WEEK uses each roster's **min**, ordered **low to high**.
  - A roster in `rosterIds` with no rows at all gets `hasValue=false` and
    `missingReason="no scored weeks"`.
  - Rank-1 roster ids equal the roster ids `weekScoreSuperlative` would pick as holders from the
    same rows.
- [ ] T008 [P] [US1] Write the margin tests in `BT/engine/SeasonSuperlativesStandingsMarginTest.java`
  for the T011 functions.
  - Build `LeagueMatchupRepository.PairedGame` lists by hand.
  - BIGGEST_BLOWOUT uses each roster's largest **winning** margin, high to low. A roster with no
    wins gets `hasValue=false` and `"no wins yet"`.
  - CLOSEST_GAME uses each roster's smallest margin in **any** game, won or lost, ordered low to
    high. Both teams of the closest game have rank 1.
  - For CLOSEST_GAME, the holders are a subset of the rank-1 rows (plan amendment 2). For
    BIGGEST_BLOWOUT, rank-1 rows equal the holders.
  - A 0-margin tied game counts for CLOSEST_GAME, matching `LeagueRecordService.margin`, where
    `aWon` is `>=`.
- [ ] T009 [P] [US1] Write the tests for the remaining kinds in
  `BT/engine/SeasonSuperlativesStandingsTotalsTest.java`, calling the static functions whose
  signatures T012–T016 give: `closeGameStandings`, `luckStandings`, `benchStandings`,
  `waiverStandings`, `absenceStandings` and `conductStandings`.
  - CLOSE_WINS / CLOSE_LOSSES: a roster absent from the `closeGames` map is a real `0` with
    `hasValue=true`, ordered high to low.
  - LUCKIEST is high to low on `winsAboveExpected`; UNLUCKIEST is **low to high** on the same
    field.
  - MOST_BENCH_POINTS: a roster absent from `byRoster` gets `hasValue=false` and
    `"no usable lineup breakdown"`.
  - WAIVER_WIRE_WARRIOR, JOEL_EMBIID and UNETHICAL are high to low, and an absent roster is a real
    `0`.
  - JOEL_EMBIID (U1): a roster **absent** from `byRoster` but with 2 unclassified weeks is
    `hasValue=true`, `value=0`, note `"2 weeks couldn't be classified as a bye or a missed game"`, and 1 week gives `"1 week …"`. A non-winner that's
    **present** with unclassified weeks carries the same note. A roster with none has no such
    note.
  - CLOSE_WINS note: `"weeks 3, 7"` in ascending order; a single week is `"week 3"`; a 0 count
    has `note == null`.
  - Every function returns each roster in the given `rosterIds` exactly once.

### Implementation for User Story 1 (backend)

All T010–T016 edit `BE/engine/SeasonSuperlativesService.java`. They run sequentially, not in
parallel. Each adds a package-private static `…Standings(...)` function that returns
`List<Standing>` via `SuperlativeStandings.rank`, and passes the result into that kind's
`new Superlative(...)`. The **roster universe** is `nameByRoster.keySet()`, built by `teamMaps` in
`forLeague`: every roster in the league-season.

- [ ] T010 [US1] Add the week-score standings (plan amendment 1).
  - Build from the already-loaded `leagueBreakdowns` (`RosterWeekPointsRepository.WeekBreakdown`: `week`, `rosterId`, `startersPoints`). No new query.
  - Plan amendment 1 explains why this is the same population as the winner's `extremes` query. It holds by construction, and T017 guards it on real data.
  - Add `static List<Standing> weekScoreStandings(List<RosterWeekPointsRepository.WeekBreakdown> rows, Set<Integer> rosterIds, boolean highest, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster)`.
  - Pass the result into `weekScoreSuperlative` (`:398`) for HIGHEST_WEEK and LOWEST_WEEK.
  - Value: `startersPoints` rounded with `round2`. Check that `extremes`' `BigDecimal` winner value, as a double, equals `round2` of the same row; if it doesn't, compare unrounded and round only for display.
- [ ] T011 [US1] Add the margin standings (plan amendment 1).
  - Build from the already-loaded `games` (`:251`). It is the same `pairedWithScores(…, bound)` call the winner's `records.biggestBlowouts` / `closestMatchups` make; see plan amendment 1.
  - Add `static List<Standing> marginStandings(List<LeagueMatchupRepository.PairedGame> games, Set<Integer> rosterIds, boolean closest, Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster)`.
  - Decide the winner with `aPoints >= bPoints`, exactly as `LeagueRecordService.margin` does, so ties resolve the same way as for the card.
  - `{opponent}` is the opponent's **team name** from `nameByRoster`, with the `"Roster N"` fallback, as `withOpponentName` does. It is not `PairedGame`'s manager display name.
  - Note format:
    - BIGGEST_BLOWOUT: `"vs {opponent} · week {N}"`;
    - CLOSEST_GAME: `"won vs {opponent} · week {N}"` or `"lost to {opponent} · week {N}"`.
  - Pass the result into `marginSuperlative` (`:420`).
- [ ] T012 [US1] Add the close-game standings in `closeGameSuperlative` (`:373`).
  - Signature: `static List<Standing> closeGameStandings(Map<Integer, List<GameDetail>> byRoster, Set<Integer> rosterIds, …the three maps)`.
  - Value: `byRoster.getOrDefault(id, List.of()).size()`. Absent means a real 0.
  - Note: `"weeks 3, 7, 11"`, from the `GameDetail.week` values in ascending order, or `null` for a 0 count. A single week is `"week 3"`.
- [ ] T013 [US1] Add the luck standings in `addLuckSuperlatives` (`:457`).
  - Signature: `static List<Standing> luckStandings(List<ExpectedWinsService.TeamRow> teams, Set<Integer> rosterIds, boolean ascending, …the three maps)`.
  - A roster in `rosterIds` with no `TeamRow` gets `missingReason="no expected-wins row"`.
  - Use one `List<ExpectedWinsService.TeamRow>` for both kinds, with `ascending=false` for LUCKIEST
    and `ascending=true` for UNLUCKIEST.
  - Value: `winsAboveExpected`, rounded the same way the winner's `extreme` is.
  - Note: `"{actualWins} actual vs {expectedWins} expected"`.
- [ ] T014 [US1] Add the bench standings in `benchSuperlative` (`:546`).
  - Signature: `static List<Standing> benchStandings(Map<Integer, BenchAgg> byRoster, Set<Integer> rosterIds, …the three maps)`.
  - Value: `round2(BenchAgg.pointsLeft)`.
  - Note: `"{weeksCounted} weeks counted"`.
  - A roster absent from `byRoster` gets `missingReason="no usable lineup breakdown"`.
- [ ] T015 [US1] Add the waiver and absence standings.
  - **Waiver:** in `waiverSuperlative` (`:607`), add `static List<Standing> waiverStandings(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Set<Integer> rosterIds, …the three maps)`.
    - Value: `round2(totalPoints)`. An absent roster is a real 0: no pickups were started.
  - **Absence:** in `absenceSuperlative` (`:761`), add `static List<Standing> absenceStandings(Map<Integer, AbsenceCost.RosterCost> byRoster, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster, Set<Integer> rosterIds, …the three maps)` (plan amendment 4, FR-010).
    - Value: `round2(totalPointsLost)`. An absent roster is 0.
    - **Every** row whose `unclassifiedWeeksByRoster` entry is non-empty gets the note `"N weeks couldn't be classified as a bye or a missed game"` (`"1 week …"` when N is 1, matching `unclassifiedCoverage` at `:914-921`), winners included. Word it like the existing `unclassifiedCoverage` reason so the modal and the card agree.
    - Pass `unclassifiedWeeksByRoster` (`:851`) in; it's already built there.
- [ ] T016 [US1] Add the conduct standings in `unethicalSuperlative` (`:1078`).
  - Signature: `static List<Standing> conductStandings(Map<Integer, Integer> totalByRoster, Set<Integer> rosterIds, …the three maps)`.
  - Games per roster from `totalByRoster`. An unlisted roster is a real 0.
- [ ] T017 [US1] Add the regression guard for the defaulted field to `BT/api/SuperlativesControllerIT.java`.
  - For every superlative with `available && !holders.isEmpty()` and kind ≠ `JABARI_SMITH_JR`:
    - `standings.size()` equals the league-season's roster count, from the IT's own
      `select count(*) from roster_season where league_id = ?`, run against the **resolved** league
      row (the payload's `leagueSleeperId`) rather than the URL id, since the resolver can walk
      back;
    - roster ids are unique;
    - the set of `rank==1` roster ids equals the `holders` roster ids, or is a superset of them
      for `CLOSEST_GAME`.
  - Run the IT with Postgres up, and confirm in the report that it **ran** rather than skipped.

### Implementation for User Story 1 (frontend)

- [ ] T018 [P] [US1] Create `web/components/SuperlativeStandingsModal.tsx`.
  - Follow `web/components/StartMockModal.tsx`:
    - wrapper `modal-backdrop` with `onClick={onClose}`;
    - inner `modal-card wide` with `role="dialog"`, `aria-modal="true"` and an `aria-labelledby`
      pointing at the title;
    - Escape closes it, via the same `keydown` listener pattern as `StartMockModal.tsx:71`;
    - a `modal-close` button.
  - Props: `{ s: Superlative; title: string; hue: number; formatValue: (v: number) => string; currentUsername: string | null; onClose: () => void }`.
  - `formatValue` is supplied by the card (T020). The modal never picks a formatter itself.
  - Render `s.standings` as an `<ol>` in **payload order**. Never re-sort on the client; the
    backend owns the direction. Each row shows:
    - the rank, or `—` when `rank == null`;
    - `Avatar`, the same props as the card's holder avatar;
    - the team name, with the username as a second muted line (the one naming rule from `aa59b73`);
    - the exact value via `formatValue`;
    - the `note` as muted text.
  - Rank-1 rows get the card's hue. `hasValue=false` rows are muted and show `missingReason` in
    place of a value.
  - Show `s.early`'s warning and the `s.coverage` line above the list, worded exactly as the card
    words them.
  - On close, return focus to the element that opened the modal: store `document.activeElement`
    on mount.
- [ ] T019 [P] [US1] Add the modal styles to `web/styles.css`, next to the `.modal-card` rules
  (`:1405`).
  - Add `.sl-standings` list rows as a grid: `rank | avatar | name | value`, with the note under
    the name.
  - Right-align the values with `tabular-nums`.
  - In `@media (max-width: 700px)`, the modal becomes a full-height sheet (`max-height: 100dvh`,
    no side margin), and the list scrolls inside it with no horizontal scroll.
- [ ] T020 [US1] Make the cards open the modal, in `web/pages/Superlatives.tsx`.
  - In `Superlatives`, keep `const [openKind, setOpenKind] = useState<SuperlativeKind | null>(null)`,
    and render `SuperlativeStandingsModal` for the matching superlative.
  - In `SuperlativeCard` (`:170`), when `s.available && !isEmpty && s.standings.length > 0`:
    - the `<article>` gets `role="button"`, `tabIndex={0}` and `aria-haspopup="dialog"`;
    - `onClick` and Enter/Space open the modal;
    - add a hover affordance class `sl-card-clickable`.
  - Otherwise, render no button semantics at all.
  - In `DetailList` (`:493`), put `onClick={e => e.stopPropagation()}` on the `<details>`, so
    "Games" doesn't open the modal.
  - Pass the modal a `formatValue` that uses the **same branch as the card's own value line**
    (FR-008, plan amendment 5):
    - CLOSE_WINS / CLOSE_LOSSES: `v => formatCloseGameCount(s.kind, v)`;
    - everything else: `v => formatValue(v, s.unit)` (`:471`).

    Build it once in `SuperlativeCard`, beside the existing `isCloseGameKind` check, so the card
    and the modal can't drift. Also pass the title from `TITLES`.
  - Signed-in user's row (FR-009): `useUser()` (`web/user.ts:67`) exposes `username`. Pass it as
    `currentUsername`, and highlight the row whose `team.username` matches. With no match,
    nothing is highlighted.
- [ ] T021 [US1] Add frontend tests to `web/pages/Superlatives.test.tsx`:
  - Clicking a HIGHEST_WEEK card with 3 `standings` opens a `dialog` with 3 list items, in payload
    order.
  - Escape closes it.
  - Clicking the "Games" summary inside a card does not open a dialog.
  - An unavailable card and an empty card have no `role="button"`.
  - A `hasValue=false` row shows its `missingReason` and `—`.
  - LOWEST_WEEK rows render in the given ascending order, which guards against a client re-sort.
  - A CLOSE_WINS row's value renders exactly as the card's own value line does (I1).
  - With `useUser` mocked to a username that matches one row, exactly that row has the highlight
    class. With no match, no row does (FR-009).

**Checkpoint**: US1 is shippable on its own. Every team award opens a full ranking.

---

## Phase 4: User Story 2, the most-added players (P2)

**Goal**: JABARI_SMITH_JR carries `playerStandings`, and its modal ranks players.

**Independent Test**: See spec US2.

- [ ] T022 [P] [US2] Write `BT/engine/MostAddedPlayersTopTest.java` for T023.
  - Adds are counted with the **same** filters as `rank`: WAIVER/FREE_AGENT only, the week bound,
    and the eligibility predicate. Include one test per filter.
  - Players below `MIN_ADDS_TO_NAME` (2) are excluded (FR-006).
  - Ordering is adds (high to low), then `playerId`, with competition ranks.
  - The cutoff at 10 **includes every player tied with the 10th**, so a tie is never cut silently
    (e.g. 12 rows when 9th–12th are tied).
  - `top(...)`'s rank-1 players equal `rank(...)`'s players.
- [ ] T023 [US2] Add `static List<Ranked> top(List<CompletedAdd> adds, int throughWeek, Predicate<String> eligiblePlayerId, int limit)`
  in `BE/engine/MostAddedPlayers.java`.
  - First extract the filter loop at the start of `rank` (`:53-58`) into a private method that
    both methods call. There must be one definition of "which adds count"; a second copy is the
    "two implementations of one rule" landmine.
  - Ranks aren't on `Ranked`; the caller assigns them.
- [ ] T024 [US2] Build `playerStandings` in `mostAddedSuperlative` (`BE/engine/SeasonSuperlativesService.java:699`).
  - Call `MostAddedPlayers.top(..., 10)`, assign competition ranks by `adds`, and map each to
    `PlayerStanding`, reusing the `Player` lookup the loop already does.
  - Pass the result into the final `new Superlative(...)`. `standings` stays `List.of()`.
- [ ] T025 [US2] Render the player variant of the modal in `web/components/SuperlativeStandingsModal.tsx`.
  - When `s.kind === 'JABARI_SMITH_JR'`, render `s.playerStandings` in its place. Each row shows:
    - the rank;
    - the player name, with `(position)`;
    - the NBA/NFL team, if non-null;
    - `"{adds} adds by {distinctTeams} teams"`, with the singular/plural rules the card uses.
  - Subtitle: `"This award ranks players, not teams."`
  - In `web/pages/Superlatives.tsx`, the Jabari card is clickable when
    `playerStandings.length > 0`.
- [ ] T026 [US2] Add a test to `web/pages/Superlatives.test.tsx`: the Jabari card opens a dialog
  listing `playerStandings` in order, with the players subtitle.

**Checkpoint**: All 13 kinds are clickable where they have data.

---

## Phase 5: Polish & verification

- [ ] T027 [P] Add an "Amended 2026-09-29 (spec 010)" section to
  `specs/008-season-superlatives/contracts/superlatives-api.md`. It documents `standings` /
  `playerStandings`, their invariants from `plan.md`, and the CLOSEST_GAME `holders ⊆ rank-1`
  exception.
- [ ] T028 [P] Add a visible "Amended after review" note to `claude/superlatives-full-standings.md`
  pointing at `plan.md`'s five amendments. Say plainly that amendment 1's first version was an unrun claim, since corrected. Don't silently rewrite the doc (repo convention).
- [ ] T029 Run the full checks:
  - `cd backend && ./gradlew test`: compare with the T001 baseline. The skip count must not rise,
    and `SuperlativesControllerIT` must show as run.
  - `cd web && npx tsc -b && npm run build && npx vitest run`.
  - Record the numbers in `verification.md`.
- [ ] T030 Verify it live, on the real stack.
  - Restart `bootRun`, because it doesn't hot-reload. Hard-refresh the tab after any vite restart.
  - On the **2025** season of "(Foot) Ball Knowers" and of "Ball Knowers" (NBA):
    - Open Highest week, Lowest week, Closest game, Unluckiest and Jabari Smith Jr.
    - Confirm every roster appears once and that rank 1 matches the card.
    - Check one Highest week figure against the Weekly report, and one Unluckiest figure against
      Expected wins, **by hand**.
  - Fallback season (spec Edge Cases): open "Ball Knowers" (NBA) **2026** Superlatives. If it walks
    back to 2025, confirm the modal shows 2025's standings under the same fallback note as the
    cards. If 2026 is scored by then, record "fallback not exercised".
  - Screenshot at 1400×900 and at 375×812.
  - Write it all to `verification.md`, keeping verified and assumed separate.

---

## Dependencies & Execution Order

- **T000** (adversarial review) comes first, then **Setup (T001)**, before everything else.
- **Foundational (T002–T006)**: T002 comes before T003. T004–T006 are [P] with each other; T004
  and T005 need nothing else, and T006 is web-only.
- **US1**:
  - Tests T007–T009 are [P] and need T002 and T004.
  - T010–T016 run in sequence, because they touch the same file.
  - T017 comes after T010–T016.
  - T018 and T019 are [P] and need only T006.
  - T020 needs T018. T021 needs T020.
- **US2** needs Foundational only, not US1's backend. T025 extends the modal from T018, so it
  comes after T018. T022 and T023 are independent of US1 entirely.
- **Polish** comes after the stories it describes.

## Parallel Example: User Story 1

```text
Together: T007, T008, T009 (backend tests), T018, T019 (modal + CSS)
Then:     T010 → T016 in sequence (one file), T017
Then:     T020 → T021
```

## Implementation Strategy

1. **MVP = Phases 1–3.** It ships 12 of 13 awards with full standings. Stop after it and run T029
   and T030 on US1 alone; that is a complete, useful increment.
2. **Then US2 (T022–T026).** It's small, and it's the only kind that ranks players.
3. **Pipeline (AGENTS.md).** The build tasks go to a Sonnet subagent. They're followed by a
   bug-hunting review of the diff, **before** T030's live verification. A green suite is not this
   repo's bar for "verified".
