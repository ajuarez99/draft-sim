# Tasks: The Trending Tony Award (spec 051)

**Input**: `specs/051-trending-tony-award/` — spec.md (amended A1–A4), plan.md (amended after review), research.md
R1–R8, data-model.md, contracts/superlatives-api.md, quickstart.md, plan-review.md F1–F13.

**Tests**: included. This repo's convention (AGENTS.md bug classes 1–3) requires a preference-ordering test for any
scoring rule, and an executed IT for any new SQL or array bind.

**Paths**: `B` = `backend/src/main/java/com/ballknowers/draftsim`, `BT` = `backend/src/test/java/com/ballknowers/draftsim`,
`W` = `web/src`.

## Phase 1: Setup

- [X] T001 Re-check the migration number before writing it: `git fetch` then confirm no `V42__*` exists on `origin/main`, any `origin/*` branch, or any `.claude/worktrees/*/backend/src/main/resources/db/migration/` (lesson #43). If one does, take the next free number and update data-model.md with a note.

## Phase 2: Foundational (blocks every story)

- [X] T002 Create `backend/src/main/resources/db/migration/V42__sport_trending_snapshot.sql` exactly per data-model.md:
  - `sport_trending_snapshot(id bigserial pk, sport text not null, fetched_at timestamptz not null, lookback_hours int not null, entry_count int not null)` with an index on `(sport, fetched_at)`.
  - `sport_trending_snapshot_entry(snapshot_id bigint not null references sport_trending_snapshot(id) on delete cascade, rank int not null, sleeper_player_id text not null, add_count int not null, primary key (snapshot_id, rank))`.
  - No FK to `sport_trending_fetch`.
  - A header comment in the style of V26: why the table exists, the fact that no backfill is possible, and that the file must never be edited once applied.
- [X] T003 In `B/store/SportTrendingRepository.java`, `replace(...)`: inside the existing `@Transactional` method, insert one `sport_trending_snapshot` row and then its entries.
  - The snapshot row takes the same `fetchedAt` and `lookbackHours`, with `entry_count = entries.size()`.
  - Entries are written as `rank = i+1`, the same as `sport_trending`.
  - Bind `fetchedAt` as `OffsetDateTime` via `setObject`, never `Timestamp`.
  - `recordFailure` and `read` stay unchanged.
- [X] T004 In `B/store/SportTrendingRepository.java`, add the read methods and records. Use new names: the existing `Snapshot` record must not be reused (F11).
  - `record TrendingSnapshotHeader(long id, OffsetDateTime fetchedAt, int lookbackHours, int entryCount)`
  - `record TrendingSnapshotRank(long snapshotId, String playerId, int rank, int addCount)`
  - `List<TrendingSnapshotHeader> snapshotHeaders(String sport, OffsetDateTime from, OffsetDateTime to)`, ordered by `fetched_at`.
  - `List<TrendingSnapshotRank> snapshotRanks(List<Long> snapshotIds, int topN, Collection<String> playerIds)`
    - SQL: `where snapshot_id = any(?) and rank <= ? and sleeper_player_id = any(?)`
    - Bind via `connection.createArrayOf("bigint", ...)` and `createArrayOf("text", ...)`.
    - Return `List.of()` without querying when either list is empty.
  - `Optional<OffsetDateTime> earliestSnapshotAt(String sport)`
- [X] T005 [P] Extend `BT/store/SportTrendingRepositoryIT.java`, executed against Postgres. Before calling it green, check the skipped-test count.
  - (a) `replace` writes exactly one snapshot with entries matching `sport_trending`, and the same `fetched_at` as `sport_trending_fetch`.
  - (b) `recordFailure` writes no snapshot.
  - (c) Two `replace` calls produce two snapshots, while the current list holds only the second.
  - (d) An empty list produces a snapshot with `entry_count = 0`.
  - (e) `snapshotHeaders` respects inclusive bounds and sport.
  - (f) `snapshotRanks` filters by rank ≤ topN and by player ids, with real array binds.
  - (g) `earliestSnapshotAt` returns empty when there is no row.
- [X] T006 [P] Create `B/engine/PickupRules.java` with `static boolean isCountedPickup(WaiverPickupAttribution.CompletedAdd a)`, meaning `type ∈ {"WAIVER","FREE_AGENT"} && week >= 1` (F1).
  - Refactor `B/engine/MostAddedPlayers.java` (lines ~102-103) to call it, with no behaviour change.
  - The existing `MostAddedPlayers` tests must pass unchanged.
- [X] T007 Create the pure class `B/engine/TrendingPickups.java` (no Spring, no Postgres).
  - Constants, labelled hand-set in a comment: `TOP_N = 15`, `MIN_TO_NAME = 2`.
  - `enum Verdict { TRENDING, NOT_TRENDING, NOT_JUDGED }`
  - `record Judged(CompletedAdd add, Verdict verdict, Integer trendingRank, Integer addCount, OffsetDateTime snapshotAt)`
  - `static List<Judged> judge(List<CompletedAdd> adds, List<TrendingSnapshotHeader> headers, Map<Long, Map<String, TrendingSnapshotRank>> ranks, int throughWeek, Predicate<String> eligible)`, implementing data-model.md's rules 1–4 exactly:
    - Drop adds that fail `PickupRules.isCountedPickup`, have `week > throughWeek`, or fail `eligible`.
    - A null `createdAt` gives NOT_JUDGED.
    - Choose the snapshot **after-first**: the earliest header with `P ≤ fetchedAt ≤ P+lookback`, else the latest with `P−lookback ≤ fetchedAt < P`. Skip headers with `entryCount == 0`.
    - If the player has a rank in that snapshot's map, the verdict is TRENDING; otherwise NOT_TRENDING.
  - Add a static helper `record Tally(int rosterId, int trending, int judged)` and `static Map<Integer, Tally> tally(List<Judged>)`.
- [X] T008 [P] Create `BT/engine/TrendingPickupsTest.java` (pure). Each case is a separate test:
  - (1) Rank 15 is TRENDING and rank 16 is NOT_TRENDING (TOP_N boundary).
  - (2) Pins the after-first rule: snapshots exist 23h before and 5 min after the pickup, and the after one judges it (F3).
  - (3) Only a before-snapshot exists, 23h59m before, so it judges. At 24h01m before, the pickup is NOT_JUDGED.
  - (4) Only an after-snapshot exists, 24h01m after, giving NOT_JUDGED.
  - (5) The `entryCount == 0` snapshot is skipped and the next usable one is chosen. With none usable, NOT_JUDGED (F13).
  - (6) A null `createdAt` gives NOT_JUDGED.
  - (7) A trending player acquired by TRADE and by COMMISSIONER is in no bucket (F1).
  - (8) A football DEF is excluded through `eligible`.
  - (9) `week > throughWeek` and `week 0` are excluded.
  - (10) SC-002: the buckets sum to the number of counted pickups.
  - (11) Preference ordering, via `tally`: a manager with 3 trending pickups outranks one with 2, even with more total pickups (bug class 1).
- [X] T009 In `B/engine/SeasonSuperlativesService.java`, make the record and shape changes. The tests in T010 come later and need these.
  - Append `TRENDING_TONY` **last** to `Kind`. It is not added to `EARLY_ELIGIBLE`.
  - Add `record TrendingPickupDetail(String playerId, String playerName, String position, int rosterId, int week, String addType, int trendingRank, int addCount, OffsetDateTime snapshotAt) implements DetailRow` to the sealed `permits` list.
  - Add `record TrendingCoverage(OffsetDateTime judgingSinceAt, int judged, int notJudged, int totalPickups)`.
  - Add a trailing component `TrendingCoverage trendingCoverage` to `Superlative`.
  - Add a **14-arg overload** defaulting it to null, and keep the existing 13/11/10-arg overloads chained to it.
  - Switch `withUsernames` (~:1831) and `withStandings` (~:555) to the **15-arg canonical** constructor, passing `s.trendingCoverage()` through (F12).
- [X] T010 [P] Create `BT/engine/SuperlativeRebuildersTest.java`. It builds a TRENDING_TONY `Superlative` with a non-null `trendingCoverage` and asserts the coverage survives **both** `withUsernames` and `withStandings` (F12).
- [X] T011 In `B/api/SuperlativesController.java`:
  - Add a `"TRENDING_PICKUP"` branch to `detailRow` with every field of `TrendingPickupDetail`. `snapshotAt` is serialized as ISO instant, and `position` may be null, so use `LinkedHashMap`, never `Map.of`.
  - Emit `"trendingCoverage"` on every superlative: null, or a map of `judgingSinceAt, judged, notJudged, totalPickups`.
- [X] T012 In `W/api.ts`, in the same change as T009 and T011 (constitution rule 2):
  - Add `'TRENDING_TONY'` to `SuperlativeKind`.
  - Add `'PICKUPS'` to the superlative `unit` union.
  - Add `export type TrendingCoverage = { judgingSinceAt: string; judged: number; notJudged: number; totalPickups: number }`.
  - Add `trendingCoverage: TrendingCoverage | null` to the superlative type.
  - Add a `TRENDING_PICKUP` member to the detail union with fields mirroring T011.
- [X] T013 Add `trendingCoverage: null` to every `Superlative` fixture so `npx tsc -b` passes. Prefer a shared fixture factory if one exists; otherwise edit each fixture in:
  - `W/components/AwardWinners.test.tsx`
  - `W/components/SuperlativeStandings.test.tsx`
  - `W/pages/Superlatives.test.tsx`
  - `W/components/TrophyCase.test.tsx`
  - `W/pages/LeagueHome.test.tsx`

  The reviewer counted 47 fixtures (F7). Run `cd web && npx tsc -b` to confirm.

**Checkpoint**: backend compiles; `./gradlew test` passes with 0 unexpected skips; `tsc -b` is clean; no award has
changed yet.

## Phase 3: User Story 1 — See who chases the trending list (P1) 🎯 MVP

**Goal**: The Awards page shows a Trending Tony card that names the top manager(s) by trending-pickup count.
**Independent test**: Seeded snapshots plus real pickups put the right manager and the right count on the card (quickstart V4).

- [X] T014 [US1] In `B/engine/SeasonSuperlativesService.java`, inject `SportTrendingRepository` through the constructor, and update every test/mocking site that constructs the service.
  Then add `trendingTonySuperlative(league, txRows, throughWeek, playersBySleeperId, rosterIds, nameByRoster, avatarByRoster, managerByRoster)`:
  1. Return unavailable "Trending data comes from Sleeper; this league is on ESPN." when `league.platform() == ESPN`.
  2. Return unavailable "Transactions for this season haven't loaded yet." when `txRows` is empty.
  3. Build counted adds with `completedAdds` + `PickupRules` + `throughWeek` + a DEF-excluding `eligible` (the same predicate as `mostAddedSuperlative` ~:824).
  4. Compute the range `[min createdAt − 24h, max createdAt + 24h]` over non-null times.
  5. Load `snapshotHeaders(league.sport().code(), …)`, then `snapshotRanks(ids, TOP_N, pickedUpPlayerIds)`, then `TrendingPickups.judge`.
  6. Handle the US3 unavailable states (T020).
  7. Compute holders: the rosters tied at the max TRENDING count. When max < `MIN_TO_NAME`, return available with no holders, `emptyReason` "nobody has chased the trending list twice yet", and the coverage set.
  8. `value` is the max as a double, `unit` is "PICKUPS", and `early = false`.
  9. `detail` holds the holders' TRENDING rows as `TrendingPickupDetail`, with the player name and position from `playersBySleeperId` ("Unknown player" and null when absent).
  10. Build `trendingCoverage`. `judgingSinceAt` is the earliest `snapshotAt` among judged pickups.

  Wire it with `built.put(Kind.TRENDING_TONY, …)` in `forLeague` after `JABARI_SMITH_JR`, reusing the already-fetched `txRows`.
- [X] T015 [P] [US1] Create `BT/engine/SeasonSuperlativesTrendingTonyTest.java`. Call the static/package-private helper with stub inputs (no Postgres, as with the Jabari tests) and cover:
  - A winner with count 4 over a manager with 2.
  - A two-way tie, where both managers are holders.
  - Top count 1, which names nobody and gives the exact emptyReason.
  - A rank-22 pickup that doesn't count.
  - The ESPN reason.
  - The no-transactions reason.

  If the helper needs the repository, extract the pure part so that it doesn't.
- [X] T016 [P] [US1] In `W/awardTitles.ts`, add `TRENDING_TONY: { title: 'The Trending Tony' }`.
- [X] T017 [US1] In `W/pages/Superlatives.tsx`, give `formatValue` a `PICKUPS` case: "N trending pickup(s)". Add the kind to whichever kind sets need it so the generic value line isn't duplicated; follow the WAIVER/CLOSE_WINS handling. Also give `W/pages/LeagueHome.tsx`'s `unitLabel` (~:127-140) a `PICKUPS` → "trending pickups" case (F7).
- [X] T018 [US1] In `W/components/AwardWinners.tsx`, render the `TRENDING_PICKUP` detail lines under each holder: "{player} · #{rank} trending · week {w} · {Waiver|Free agent}", with the player name linked through `PlayerLink`, as the other pickup lines do.
- [X] T019 [P] [US1] Add tests:
  - `W/components/AwardWinners.test.tsx`: the TRENDING_PICKUP line text.
  - `W/pages/Superlatives.test.tsx`: the card renders last, with the title "The Trending Tony" and "4 trending pickups".
  - `W/pages/LeagueHome.test.tsx`: the PICKUPS unit label.

**Checkpoint**: US1 works with seeded snapshots.

## Phase 4: User Story 3 — Be honest about what couldn't be judged (P1)

**Goal**: A coverage line on the card; an unavailable state for seasons with nothing judged; "How this works" text.
**Independent test**: With history starting mid-season, the card shows the start date and the count not judged; a past season shows the award unavailable with a reason (quickstart V2, V3).

- [X] T020 [US3] In `trendingTonySuperlative` (`B/engine/SeasonSuperlativesService.java`), when the judged count is 0, return unavailable (F2).
  - If `earliestSnapshotAt(sport)` is empty, or later than the last counted pickup + 24h, the reason is "Trending history wasn't being recorded yet for this season."
  - Otherwise the reason is "No pickups since trending history began on <YYYY-MM-DD> (UTC)." using that earliest snapshot's UTC date.

  Extend T015's test with both reasons. Also test that an available card always has non-null coverage, with `judged + notJudged == totalPickups`.
- [X] T021 [US3] In `W/components/AwardWinners.tsx`, render `s.trendingCoverage` when it's non-null. The line must name its exclusions (FR-007, F9):
  "Judged since {local date of judgingSinceAt} · {judged} of {totalPickups} pickups judged, {notJudged} not (before trending history began or no list within a day) · pickups through week {throughWeek}, team defenses excluded"
  - Drop the defenses clause for NBA.
  - Format the date with `toLocaleDateString` (F13).
  - Use `throughWeek` and `sport`, which the page already passes down.
- [X] T022 [US3] In `W/pages/Superlatives.tsx`, add a `HowThisWorks` paragraph for the Trending Tony (FR-011, FR-012). It must cover:
  - It uses Sleeper's platform-wide trending list (adds across all Sleeper leagues in the last 24 hours), not this league's.
  - The top-15 threshold.
  - Each pickup is judged by the list nearest to it within a day, with a list taken after it preferred.
  - "not judged" is not the same as "not trending".
  - History exists only from the day recording began, and earlier pickups can never be judged.
  - Waiver claims are timed when they process.
  - The credit: "Trending data from Sleeper."
- [X] T023 [US3] In `W/components/TrophyCase.tsx`, filter out `TRENDING_TONY` when `available === false` (F8). The Awards page itself is unchanged. Test it in `W/components/TrophyCase.test.tsx`.
- [X] T024 [P] [US3] Add tests in `W/components/AwardWinners.test.tsx`: the coverage line text for NFL and for NBA, and the unavailable reason rendering for both reasons.

## Phase 5: User Story 2 — Pickups behind the award, and everyone's count (P2)

**Goal**: "See all" ranks every manager, and a Players view lists each counted pickup with its trending rank.
**Independent test**: "See all" lists every roster, including zeros, and the Players view reads "#N trending" (quickstart V4, V7).

- [X] T025 [US2] In `trendingTonySuperlative`, build `standings` over **every** roster in `rosterIds`.
  - `value` is the TRENDING count, using competition ranks as in `closeGameStandings`.
  - `note` is "N of M judged pickups", or "no judged pickups" with value 0. Every row has `hasValue = true`.
  - Build `playerContributions` from every TRENDING pickup across all rosters, ordered by trendingRank, then week, then rosterId.
    `rank` is 1..n by competition rank on trendingRank, `value` is trendingRank, and `note` is "week W · waiver" or "week W · free agent".
  - Extend T015's test to cover the standings including a 0 row, and the contributions' order and notes.
- [X] T026 [US2] In `W/components/SuperlativeStandings.tsx`, add an optional prop `contributionFigure?: (v: number) => string`, defaulting to `figure`, and use it in `contributionRow` (F6, a structural change).
  In `W/pages/Superlatives.tsx`, pass `v => '#' + v + ' trending'` for `TRENDING_TONY` only.
- [X] T027 [P] [US2] In `W/components/SuperlativeStandings.test.tsx`, test that for TRENDING_TONY the Teams view reads "4 trending pickups" and the Players view reads "#2 trending", never "2 trending pickups". Also test that another award's contributions still use `figure`.

## Phase 6: Polish and verification

- [X] T028 Run the full backend suite with `cd backend && ./gradlew test` with Postgres on 5433 up. Report the pass, fail and **skipped** counts.
- [X] T029 [P] Run `cd web && npx tsc -b && npm run build && npx vitest run` and report the results.
- [X] T030 Run a bug-hunting code review, recorded in `specs/051-trending-tony-award/code-review.md`, and fix what it finds.
- [X] T031 Do live verification per quickstart V1–V7, recorded in `specs/051-trending-tony-award/verification.md`:
  - Label real-data and seeded checks separately.
  - Delete the seeded rows afterwards.
  - Report SC-005 timing and the SC-006 snapshot count as measured.
- [X] T032 Update `HANDOFF.md` with the post-deploy note: history starts at deploy, nothing is backfilled, and the snapshot count is to be checked after one day in prod (SC-006).

## Dependencies

- T001 → T002 → T003 → T004 → T005.
- T006 → T007 → T008.
- T009 → T010, T011 → T012 → T013.
- Phase 2 must be complete before Phase 3.
- US1 (T014) is needed before US3's T020 and US2's T025, because they are the same method. The frontend tasks for US3 (T021–T024) and US2 (T026–T027) can run in parallel after T012/T013.
- Phase 6 comes last.

## Parallel examples

- Phase 2: T005 (IT) can run alongside T006–T008 (pure rule), since they touch different files.
- US1: T016 (`awardTitles.ts`) can run alongside T015 (backend test) once T014 is drafted.
- After T014: T021–T024 (US3 web) can run alongside T026–T027 (US2 web), since they touch different components. The only exception is that `Superlatives.tsx` is touched by T017, T022 and T026, so those three run sequentially.

## Implementation strategy

The MVP is Phases 1–3 plus T020. The card must never show a past season as "nobody qualified", so the unavailable states ship with the MVP.
Then US3's UI, then US2.
Everything goes in one PR, because the snapshot history has to start recording as early as possible: every day it isn't deployed is a day of pickups that can never be judged.
