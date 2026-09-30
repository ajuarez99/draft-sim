---
description: "Task list for 010-superlatives-full-standings"
---

# Tasks: Superlatives full standings

**Input**: `specs/010-superlatives-full-standings/` (spec, plan, plan-review) plus the design
doc `claude/superlatives-full-standings.md`.

**Read `plan.md`'s amendments first.** There are 13. They override the design doc, and
amendments 6–13 come from the T000 adversarial review (`plan-review.md`).

> **Rewritten 2026-09-29 after T000.** The pre-review task list is in commit `8f89ae3`. Main
> changes:
> - a controller task (R1);
> - the roster universe (R2);
> - a seeded IT (R3);
> - the empty-state rule (R4);
> - one per-kind figure formatter (R5);
> - the highlight dropped (R6);
> - a real "See all" button, with modal state in the card (R7/R8).

**Line numbers are from `main` at `587fbc3`.** Re-find each anchor by its quoted code.

Path prefixes:
- `BE` = `backend/src/main/java/com/ballknowers/draftsim`
- `BT` = `backend/src/test/java/com/ballknowers/draftsim`
- `web` = `web/src`

**Shared definitions used below:**

- **The three maps** are `Map<Integer, String> nameByRoster`, `Map<Integer, String>
  avatarByRoster` and `Map<Integer, Long> managerByRoster`, as built by `teamMaps`.
- **`rosterIds`** is `managerByRoster.keySet()`: every `roster_season` row, orphans included
  (R2 / FR-012). **Never `nameByRoster.keySet()`.**
- Every standings row's `team` is built with the existing `holder(id, nameByRoster,
  avatarByRoster, managerByRoster)`, which falls back to `"Roster N"`.
- A source row whose roster isn't in `rosterIds` is skipped (R11). Add a one-line comment
  saying so.

---

## Phase 1: Setup

- [X] T000 Run the adversarial plan review. Done; see `plan-review.md` and plan amendments 6–13.
- [X] T001 Record the baseline. Done; see `verification.md`: 880 tests, 0 failed, 0 skipped, and
  web 43/43.

---

## Phase 2: Foundational

- [X] T002 Add the new records and fields in `BE/engine/SeasonSuperlativesService.java`.
  - Next to `Holder`:
    - `public record Standing(Integer rank, Holder team, Double value, String note, boolean hasValue, String missingReason) {}`
    - `public record PlayerStanding(int rank, String playerId, String playerName, String position, String team, int adds, int distinctTeams) {}`
  - Append `List<Standing> standings, List<PlayerStanding> playerStandings` to `Superlative`.
    Keep the existing shorter constructors, defaulting both lists to `List.of()`.
  - Document the invariants on the record: `rank == null` iff `!hasValue`; `missingReason != null`
    iff `!hasValue`.
- [X] T003 Update `withUsernames` in `BE/engine/SeasonSuperlativesService.java`.
  - Fill `username` on each `standings` row's `team`, exactly as for `holders`.
  - Carry `standings` and `playerStandings` through to the rebuilt `Superlative`.
- [X] T004 Serialize the new fields in `BE/api/SuperlativesController.java` (R1).
  - In `superlativeRow` (`:206`), add `m.put("standings", …)` and `m.put("playerStandings", …)`.
  - Add `standingRow(Standing)`, built as a `LinkedHashMap` because nullable fields make `Map.of`
    throw. It reuses `holderRow(s.team())` for `team`.
  - Add `playerStandingRow(PlayerStanding)`, also a `LinkedHashMap`, because `position` and `team`
    are nullable.
  - JSON field names: `rank`, `team`, `value`, `note`, `hasValue`, `missingReason`; and `rank`,
    `playerId`, `playerName`, `position`, `team`, `adds`, `distinctTeams`.
- [X] T005 [P] Create `BE/engine/SuperlativeStandings.java`: a final class with static methods only
  and no Spring.
  - `record Entry(Holder team, Double value, String note, String missingReason)`
  - `static List<Standing> rank(List<Entry> entries, boolean ascending)`
    - A null `value` sorts last, with `rank=null` and `hasValue=false`. A null value without a
      `missingReason` throws `IllegalArgumentException`.
    - Rows with a value sort by value in the requested direction, then by `team.rosterId()`
      ascending.
    - Ranks are competition ranks (1, 1, 3), comparing with `Double.compare`. Callers pass values
      already rounded exactly as the winner's value is.
- [X] T006 [P] Write `BT/engine/SuperlativeStandingsTest.java` for T005. It asserts:
  - descending and ascending order;
  - ties 1, 1, 3 and 1, 1, 1, 4;
  - null-value rows last, with `rank == null` and `hasValue == false`;
  - a null value without a reason throws.
- [X] T007 [P] Mirror the types in `web/api.ts`, next to `SuperlativePlayerHolder`.
  - Add `SuperlativeStanding = { rank: number | null; team: SuperlativeHolder; value: number | null; note: string | null; hasValue: boolean; missingReason: string | null }`.
  - Add `SuperlativePlayerStanding = { rank: number; playerId: string; playerName: string; position: string | null; team: string | null; adds: number; distinctTeams: number }`.
  - Add `standings: SuperlativeStanding[]` and `playerStandings: SuperlativePlayerStanding[]` to
    `Superlative`.
  - Add `standings: [], playerStandings: []` to every `Superlative` fixture `tsc -b` flags,
    including `web/pages/Superlatives.test.tsx`.

**Checkpoint:** `./gradlew compileJava compileTestJava` and `SuperlativeStandingsTest` pass, and
`tsc -b` is clean.

---

## Phase 3: User Story 1, team awards (P1) 🎯 MVP

### Backend: tests first

Every function below is a package-private static in `SeasonSuperlativesService` with the
signature given, where `…maps` means the three maps. Tests call them directly, without
Postgres.

- [X] T008 [P] [US1] Write the week-score and margin tests in
  `BT/engine/SeasonSuperlativesStandingsRecordsTest.java`.
  - Build `RosterWeekPointsRepository.WeekBreakdown` rows by hand, with null JSON fields, and
    `LeagueMatchupRepository.PairedGame` rows by hand.
  - HIGHEST_WEEK uses each roster's max, high to low. The note is `"week N"`, using the earliest
    week when a roster ties its own max.
  - LOWEST_WEEK uses the min, **low to high**.
  - A roster in `rosterIds` with no rows gets `hasValue=false` and `"no scored weeks"`.
  - A breakdown for a roster not in `rosterIds` is ignored.
  - BIGGEST_BLOWOUT uses the largest winning margin, high to low. A roster with no wins gets
    `"no wins yet"`.
  - CLOSEST_GAME uses the smallest margin in any game, **low to high**, and both teams of the
    closest game are rank 1.
  - A 0-margin game has the note `"tied with {opponent} · week N"` for both sides.
  - Margins come from `BigDecimal` subtraction (R9): 101.10 − 100.00 and 50.20 − 49.10 tie
    exactly.
  - The opponent name comes from `nameByRoster`, with the `"Roster N"` fallback.
  - An orphaned roster, absent from `nameByRoster` but in `rosterIds`, appears as `"Roster N"`.
- [X] T009 [P] [US1] Write the tests for the remaining kinds in
  `BT/engine/SeasonSuperlativesStandingsTotalsTest.java`.
  - `closeGameStandings`:
    - A roster that is in a paired game but absent from the map is a real 0.
    - A roster in **no** paired game gets `hasValue=false`, `"no games yet"` (R12).
    - The note is `"weeks 3, 7"`, `"week 3"`, or null for 0.
    - Order is high to low.
  - `luckStandings`: LUCKIEST is high to low on `winsAboveExpected`, UNLUCKIEST **low to high**.
    The note is `"{actual} actual vs {expected} expected"`, with expected to 2 decimals. A roster
    with no TeamRow gets `"no expected-wins row"`.
  - `benchStandings`: an absent roster gets `hasValue=false`, `"no usable lineup breakdown"`.
    The note is `"{n} weeks counted"`.
  - `waiverStandings` and `conductStandings`: an absent roster is a real 0, ordered high to low.
  - `absenceStandings`:
    - An absent roster is 0.
    - **Every** roster with unclassified weeks, winners included, gets the note
      `"N weeks couldn't be classified as a bye or a missed game"` (`"1 week …"` for 1).
    - A roster with none has a null note.
  - Every function returns each id in `rosterIds` exactly once.
- [X] T010 [P] [US1] Write the empty-state tests (R4 / FR-011) in
  `BT/engine/SeasonSuperlativesStandingsTotalsTest.java`, or in the existing
  `SeasonSuperlativesWaiverTest` and `SeasonSuperlativesAbsenceCoverageTest` if their fixtures
  fit better.
  - When every waiver `RosterTotal.totalPoints` is ≤ 0, WAIVER_WIRE_WARRIOR has empty holders and
    `emptyReason = "no started pickup has scored yet"`.
  - When every Embiid `totalPointsLost` is ≤ 0, JOEL_EMBIID has
    `emptyReason = "no absence has cost anyone points yet"`.
  - If the builders can't be reached without Postgres, extract the "pick winners from the map"
    step into a static helper and test that helper.

### Backend: implementation, in sequence (one file: `BE/engine/SeasonSuperlativesService.java`)

- [X] T011 [US1] Add the roster universe and the week and margin standings.
  - In `forLeague`, after `teamMaps`, add `Set<Integer> rosterIds = Set.copyOf(managerByRoster.keySet())`.
  - `static List<Standing> weekScoreStandings(List<RosterWeekPointsRepository.WeekBreakdown> rows, Set<Integer> rosterIds, boolean highest, …maps)`:
    - built from the loaded `leagueBreakdowns` (amendment 1);
    - value is `round2(startersPoints)`. Confirm it matches the winner's value on T016's seeded
      data.
  - `static List<Standing> marginStandings(List<LeagueMatchupRepository.PairedGame> games, Set<Integer> rosterIds, boolean closest, …maps)`:
    - built from the loaded `games`;
    - the winner is decided by `aPoints.compareTo(bPoints) >= 0`, as in `LeagueRecordService.margin`;
    - the margin is a `BigDecimal` subtraction, then `.doubleValue()`.
    - Notes:
      - BIGGEST_BLOWOUT: `"vs {opp} · week N"`;
      - CLOSEST_GAME: `"won vs {opp} · week N"`, `"lost to {opp} · week N"` or
        `"tied with {opp} · week N"`.
  - Pass each result into `weekScoreSuperlative` and `marginSuperlative`, adding a parameter, and
    from there into the `Superlative` constructor.
- [X] T012 [US1] Add the close-game and luck standings.
  - `static List<Standing> closeGameStandings(Map<Integer, List<GameDetail>> byRoster, Set<Integer> rostersWithGames, Set<Integer> rosterIds, …maps)`:
    - `rostersWithGames` is every roster id appearing in `games`;
    - wire it into `closeGameSuperlative`.
  - `static List<Standing> luckStandings(List<ExpectedWinsService.TeamRow> teams, Set<Integer> rosterIds, boolean ascending, …maps)`:
    - pass the maps and `rosterIds` into `addLuckSuperlatives`, adding parameters (R14);
    - round the value exactly as the winner's `extreme` is.
- [X] T013 [US1] Add the bench, waiver, absence and conduct standings.
  - `benchStandings(Map<Integer, BenchAgg> byRoster, Set<Integer> rosterIds, …maps)`
  - `waiverStandings(Map<Integer, WaiverPickupAttribution.RosterTotal> byRoster, Set<Integer> rosterIds, …maps)`
  - `absenceStandings(Map<Integer, AbsenceCost.RosterCost> byRoster, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster, Set<Integer> rosterIds, …maps)`
  - `conductStandings(Map<Integer, Integer> totalByRoster, Set<Integer> rosterIds, …maps)`
  - Round values with `round2`, as each winner's value is. Wire each into its builder, adding
    `rosterIds` parameters.
- [X] T014 [US1] Add the R4 empty state (FR-011) in `waiverSuperlative` and `absenceSuperlative`.
  - After computing `max`, if `max <= 0`, return the kind's existing empty-state shape (as when
    `byRoster.isEmpty()`) with the new `emptyReason` from T010.
  - Add a comment citing plan amendment 9.

### Backend: the controller-level guard

- [X] T015 [US1] Keep the default construction honest, and run T008–T010 green.
  - Grep every `new Superlative(` in `SeasonSuperlativesService`.
  - Every **available** return that has holders must pass `standings`, or `playerStandings` for
    Jabari.
  - Every empty or unavailable return uses the shorter constructor.
- [X] T016 [US1] Create `BT/api/SuperlativesStandingsIT.java` (R3), modelled on
  `SuperlativesControllerIT`: the same `requiresLocalPostgres` guard, the same setup and teardown
  style, and unique `sleeper_id`s so it can't collide with real rows.
  - Read the schema before writing inserts. Look in `backend/src/main/resources/db/migration`
    for the `roster_season`, `roster_week_points`, `league_matchup` and `league_member` columns.
  - **Seed:** one NFL league-season with a league format whose `playoffWeekStart` is 15, and 4
    rosters.
    - Rosters 1–3 have managers; **roster 4 is orphaned**, with `manager_id` null.
    - 3 weeks of `roster_week_points`, with one tied top score between rosters 1 and 2.
    - Pairings for each week.
    - Include one 0-margin game.
  - Call the controller's GET superlatives method, as the existing IT calls controller methods.
  - Assert on the **returned body** (the `Map`/`List` structure) for every superlative with
    `available` and non-empty `holders`:
    - `standings.size() == 4`;
    - unique `team.rosterId`s;
    - roster 4 is present, named `"Roster 4"`;
    - the set of rank-1 roster ids equals the `holders` roster ids, or is a superset of them for
      CLOSEST_GAME;
    - HIGHEST_WEEK's ranks are `[1, 1, 3, 4]`.
  - Jabari: seed nothing. Assert `playerStandings == []` and that Jabari is not in the loop above.
  - Clean up in `@AfterEach`.
  - Run it with Postgres up. **Confirm in the XML report that it ran, not skipped.**

### Frontend

- [X] T017 [P] [US1] Add `export function standingFigure(kind: SuperlativeKind, unit: Superlative['unit'], value: number): string`
  to `web/pages/Superlatives.tsx` (amendment 10). It returns:
  - `formatCloseGameCount(kind, value)` for CLOSE_WINS / CLOSE_LOSSES;
  - `${value >= 0 ? '+' : '−'}${Math.abs(value).toFixed(2)} wins vs expected` for LUCKIEST /
    UNLUCKIEST;
  - `formatValue(value, unit)` otherwise.
- [X] T018 [P] [US1] Create `web/components/SuperlativeStandingsModal.tsx`.
  - Props: `{ s: Superlative; title: string; hue: number; figure: (v: number) => string; onClose: () => void }`.
  - Follow `web/components/StartMockModal.tsx`:
    - a `modal-backdrop` with `onClick={onClose}`;
    - an inner `modal-card wide sl-standings-modal` with `onClick={e => e.stopPropagation()}`
      (R8), `role="dialog"`, `aria-modal="true"`, and `aria-labelledby` pointing at an `<h3 id>`
      holding the title;
    - an Escape `keydown` listener;
    - a `modal-close` button.
  - On mount:
    - store `document.activeElement` and restore focus to it on unmount;
    - focus the close button;
    - add `bk-modal-fullscreen` to `document.body`, and remove it on unmount (the PowerRankings
      pattern).
  - Show above the list, worded like the card:
    - the early line when `s.early`;
    - the coverage line when `s.coverage`.
  - Render `s.standings` as `<ol className="sl-standings">` in payload order, **never re-sorted**.
    Each `<li>` holds:
    - the rank (or `—`);
    - `Avatar` with the card's props;
    - the team name, with the username as a muted second line when present;
    - `figure(value)` when `hasValue`, otherwise the `missingReason` in muted text;
    - the `note` in muted text.
  - Rank-1 rows get the class `sl-standing-top` and the card's `--sl-hue`.
- [X] T019 [P] [US1] Add the styles to `web/styles.css`, beside the `.sl-card` rules.
  - `.sl-see-all`: a small text button in the card header.
  - `.sl-card-openable`: a cursor pointer and a hover lift consistent with the other card hovers.
  - `.sl-standings`: rows as a grid of `rank | avatar | name+note | figure`, with `tabular-nums`
    and the figure right-aligned.
  - At `@media (max-width: 700px)`, `.sl-standings-modal` is full height with no side margin,
    the list scrolls inside it, and there's no horizontal overflow.
- [X] T020 [US1] Wire up the cards in `web/pages/Superlatives.tsx`, in `SuperlativeCard`.
  - `const openable = s.available && !isEmpty && (s.standings.length > 0 || s.playerStandings.length > 0)`.
  - `const [open, setOpen] = useState(false)`.
  - When `openable`:
    - the `<article>` gets `className` `sl-card-openable` and `onClick={() => setOpen(true)}`,
      for the mouse only; there's no role and no tabIndex (R7);
    - the header gets `<button type="button" className="sl-see-all" onClick={e => { e.stopPropagation(); setOpen(true) }}>See all</button>`.
  - In `DetailList`, put `onClick={e => e.stopPropagation()}` on `<details>`. Also stop
    propagation on `onKeyDown`, for safety.
  - Render
    `{open && <SuperlativeStandingsModal s={s} title={meta.title} hue={hue} figure={v => standingFigure(s.kind, s.unit, v)} onClose={() => setOpen(false)} />}`
    inside the card component. Clicks inside the modal don't bubble to the article, because the
    modal stops propagation. It still renders inside `<article>`, so check the backdrop's
    `position: fixed` covers the page.
- [X] T021 [US1] Add tests to `web/pages/Superlatives.test.tsx`:
  - A HIGHEST_WEEK card with 3 standings has a "See all" button. Clicking it opens a `dialog` with
    3 `listitem`s in payload order.
  - Escape closes the dialog.
  - Clicking inside the dialog does **not** close it.
  - Clicking the "Games" summary opens no dialog.
  - Unavailable and empty cards have no "See all".
  - A `hasValue=false` row shows `—` and its `missingReason`.
  - A LOWEST_WEEK fixture renders in the given order.
  - UNLUCKIEST figures render as `−1.30 wins vs expected`, and a CLOSE_WINS figure matches
    `formatCloseGameCount`.
  - Existing tests keep passing: 43 at baseline.

**Checkpoint:** US1 is shippable. Run T027's checks for US1 alone if you're stopping here.

---

## Phase 4: User Story 2, Jabari Smith Jr. (P2)

- [X] T022 [P] [US2] Write `BT/engine/MostAddedPlayersTopTest.java`.
  - `top()` uses the same filters as `rank()`: WAIVER/FREE_AGENT only, the week window, and the
    eligibility predicate. Write one test per filter.
  - `top()` itself excludes players below `MIN_ADDS_TO_NAME`.
  - Ordering is adds (high to low), then `playerId`.
  - The cut at `limit` includes everyone tied with the last player kept, e.g. 12 when 9th–12th
    tie.
  - The players tied for most adds in `top()` equal `rank()`'s players.
- [X] T023 [US2] Add `public static List<Ranked> top(List<CompletedAdd> adds, int throughWeek, Predicate<String> eligiblePlayerId, int limit)`
  to `BE/engine/MostAddedPlayers.java`.
  - First extract `rank`'s filter loop into one private method that both call. There must be a
    single definition of "which adds count".
  - `Ranked.counted` is sorted the same way in both methods.
- [X] T024 [US2] Build `playerStandings` in `mostAddedSuperlative` (`BE/engine/SeasonSuperlativesService.java`).
  - Call `MostAddedPlayers.top(..., 10)` and assign competition ranks by `adds`.
  - Map each to `PlayerStanding` using the same `Player` lookup and "Unknown player" fallback as
    the holders loop.
  - Pass it into the final constructor. Leave `standings` as `List.of()`.
  - Extend T016's IT: seed 3 WAIVER adds of one player (by two rosters) and 2 of another. Assert
    Jabari's `playerStandings` ranks are `[1, 2]`, and that rank 1 equals `playerHolders`.
- [X] T025 [US2] Add the player branch to `web/components/SuperlativeStandingsModal.tsx`.
  - When `s.kind === 'JABARI_SMITH_JR'`, render `s.playerStandings`. Each row shows:
    - the rank;
    - the name and `(position)`;
    - the team, if any;
    - `"{adds} add(s) by {distinctTeams} team(s)"`, with the card's singular and plural wording.
  - Subtitle: `"This award ranks players, not teams."`
- [X] T026 [US2] Add a test to `web/pages/Superlatives.test.tsx`: the Jabari card's "See all"
  opens a dialog with the `playerStandings` in order and the players subtitle.

---

## Phase 5: Polish & verification

- [X] T027 Run the full checks.
  - `cd backend && ./gradlew test`: compare with the baseline of 880, 0 failed, 0 skipped.
    `SuperlativesStandingsIT` must show as run in the XML.
  - `cd web && npx tsc -b && npm run build && npx vitest run`.
  - Record the numbers in `verification.md`.
- [X] T028 [P] Add an "Amended 2026-09-29 (spec 010)" section to
  `specs/008-season-superlatives/contracts/superlatives-api.md`. It covers:
  - the `standings` and `playerStandings` shapes and invariants;
  - the CLOSEST_GAME exception;
  - the R4 empty-state change to WAIVER_WIRE_WARRIOR and JOEL_EMBIID.
- [X] T029 [P] Add a visible "Amended after review" section to
  `claude/superlatives-full-standings.md`. It points at plan amendments 1–13 and says plainly
  that:
  - amendment 1's first version was an unrun claim;
  - the design missed the controller (R1) and the orphan roster (R2).
- [ ] T030 Do the bug-hunting code review of the full diff. This is a separate pass from the
  builder, per AGENTS.md. Fix what it confirms, then re-run T027.
- [ ] T031 Verify it live on this worktree's own servers.
  - Use the ports free at the time; don't disturb another session's 8080 or 5173.
  - On the 2025 season of "(Foot) Ball Knowers" and of "Ball Knowers" (NBA):
    - open Highest week, Lowest week, Closest game, Unluckiest and Jabari Smith Jr.;
    - check that every roster appears once and that rank 1 matches the card;
    - hand-check one Highest week figure against the Weekly report, and one Unluckiest figure
      against Expected wins.
  - Fallback: "Ball Knowers" (NBA) 2026. Record whether it fell back.
  - Take screenshots at 1400×900 and 375×812.
  - Write everything to `verification.md`, with verified and assumed kept apart.

---

## Dependencies

- T002 → T003 → T004 must run in order: T003 and T004 both depend on T002's record, and T002
  and T003 share a file. T005, T006 and T007 can run in parallel.
- US1 backend:
  - The tests T008–T010 need T002 and T005.
  - T011 → T014 run in sequence, because they share a file. Then T015, then T016.
- US1 frontend:
  - T017, T018 and T019 need T007. T018 must exist before T020 can import it.
  - T020 → T021.
- US2:
  - T022 and T023 are independent of US1.
  - T024 needs T002 and T023, plus T016 for its IT extension.
  - T025 needs T018. T026 needs T020.
- Polish comes after both stories. T030 comes before T031.

## Implementation Strategy

- **Build.** It goes to Sonnet subagents (AGENTS.md), in two streams:
  - backend (T002–T016, T022–T024);
  - frontend (T017–T021, T025–T026), starting once T007's types exist.
- **Review.** The parent session reads every diff. T030 is a separate bug-hunting pass.
- **Verify.** T031 is live verification. A green suite is not this repo's bar for "verified".
