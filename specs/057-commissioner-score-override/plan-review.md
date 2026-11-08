# Adversarial plan review

This review read `spec.md` and `plan.md` cold on 2026-11-05 and checked them against the worktree
`claude/peaceful-panini-70c6a7` @ `5a47471`, the local Postgres on 5433, and the public Sleeper API. It ran
before any code was written.

Every item is labelled **measured** (run today, output quoted) or **read** (from code, not executed).

## Claims confirmed against the code

| Claim | Where checked | Result |
| --- | --- | --- |
| V45 is free | every local/remote ref and every worktree under `.claude/worktrees/` and `source/*` (measured) | ✅ no `V4[5-9]__` anywhere. Highest is `V44__roster_fpts_snapshot.sql` |
| `starters_points` is `numeric(8,2) not null` | `V5__league_history_and_power_rankings.sql:35` (read) | ✅ (`default 0`) |
| Resolver can live in `store` with its own `JdbcClient`, no cycle | `LeagueMatchupRepository:22-26`, `RosterWeekPointsRepository:20-24` take only `JdbcClient` (read) | ✅ |
| No test builds either repository with `new` | `grep "new RosterWeekPointsRepository(\|new LeagueMatchupRepository("` over `src/test` (measured) | ✅ 0 sites. Unit tests **mock** them instead: 24 `@Mock`/`mock(...)` sites. ITs `@Autowired` them (`LeagueMatchupPairingIT:47`, `LeagueRecordIT:36`) |
| Services whose constructors change are `new`'d in few tests | (measured) | `WeeklyReportService` 1 (`WeeklyReportServiceLiveWeekTest`), `WeeklyPotService` 1 (`WeeklyPotServiceTest`), `SeasonSuperlativesService` 0 |
| Record shapes, if a `source` field is added | (measured) | `new PairedGame(` 9 sites, `new ScoreRow(` 5, `new WeekPoint(` 10 |
| PUT/DELETE pass CORS from a browser (lessons #6) | `WebConfig:51` (read) | ✅ `GET, POST, PUT, DELETE, OPTIONS` |
| Commissioner gate exists and is shared | `LeagueMembership.canCommission:205-211` (read) | ✅ app owner, or `league_member.is_commissioner` |
| `entered_by` null for the app owner, as V41 | `V41__league_weekly_pot.sql` (read) | ✅ same pattern |
| Snapshots only exist for completed regular-season weeks | 055 design §2, capture keyed by `last_scored_leg` (`LeagueHistoryIngestService:238-244`, read) | ✅ so the OFFICIAL tier can never land on a live week |
| Nothing deletes `league` / `roster_week_points` / `league_matchup` rows | grep `delete from league…` (measured) | ✅ `on delete cascade` won't silently drop overrides |
| The weekly report's W-L reads `pairedWithScores` only, median included | `WeeklyReportService.recordsThrough:762-782`, called at `:255-258` and live path `:516-518` (read) | ✅ one source; resolving `pairedWithScores` fixes records and median together |
| Luck / schedule swap / median what-if read `pairedWithScores` | `ExpectedWinsService.finalGames:417-428`, `MedianWhatIfService:74` (read) | ✅ |
| Record book margins/streaks/points leaders | `LeagueRecordService:136,140,163,234,289` (read) | ✅ `extremes`, `allScores`, `pairedWithScores` |
| Hall of fame last place / finals | `HallOfFameService:218` (`through`) + `:223` (`between` for pairing only); finals via `RivalryService.chainGames:438` (read) | ✅ both covered |

### Consumers that do NOT go through the four reads

| Consumer | Reads | Matters? |
| --- | --- | --- |
| `roster_season.wins/losses/ties/points_for` (LeagueAnalysis records `:445`, PlayoffOdds `:105-107`, Rivalry standings sort `:477-478`, HallOfFame PF leader `:196`, MedianWhatIf `OFFICIAL` `:145,162`) | Sleeper's **official** standings | No change needed. These already agree with the app, so today they **disagree** with every feed-derived page. The override makes them agree. That also gives a free check (H1) |
| `RosterManagementService:193` season actual-points total | `breakdownsFor` feed | FR-012 territory (efficiency). It will now disagree with record-book / power PPG totals for NBA 2026. LOW, see L4 |
| Weekly report `gotAwayWithIt`, `selfInflictedWound`, `onePlayerCarry` | mix `pointsByRoster`/`games` with per-player feed | **M1** |
| `LockInService`, `DraftGradesService`, `TradeVerdictService`, `PlayerTrends/Stats`, `SpotlightOwnership`, `TransactionAnalysis` | per-player `breakdownsFor` | No team score or game result. Correctly left on the feed |
| `LiveWeekSource` (report live path, pot live line) | Sleeper live rows | Only for an **unstored** week, which can never be final, so never overridable. Correct |
| AI recap (`RecapInputBuilder`) | `WeeklyReportService.Result` | Picks up resolved scores. Cache key is an input hash (`RecapService:132-139`), so a saved override regenerates it. It never learns the scores are hand-entered (L5) |

No `@Cacheable` exists. The two fingerprint caches (`LockInService:354-364` on `weekPoints.digest`, and
`ProjectionAccuracyService:131`) cover per-player outputs only, so an override doesn't need to invalidate
them. **read**

## Findings

### HIGH

**H1 (high). "Validating entries against Sleeper: impossible for weeks 1–2" is wrong. Two exact checks
already sit in the DB, and the plan throws them away for 24 hand-typed numbers that move pot money.**
- `roster_fpts_snapshot` at `through_week = 2` is Sleeper's official weeks 1+2 total per roster. 055 says that
  row was the first capture (`claude/official-week-scores.md:42-43`). So `override(1) + override(2)` must equal
  `fpts(2)` per roster, to the cent.
- `roster_season.wins/losses/ties` is Sleeper's official record. While `last_scored_leg = 2`, the h2h + median
  record recomputed from the resolved weeks 1–2 (`ExpectedWinsService.headToHead` + `median`, the rule
  `recordsThrough` already uses) must equal it for all 12 rosters.
- Measured today: `/rosters` vs the sum of `/matchups/1` + `/matchups/2`. The feed is far off, for example
  roster 3 is 387.5 vs `fpts` 466.0 and roster 7 is 380.0 vs 461.5. The official records are clean
  (6, 8, 12 are 4-0; 3, 4, 11 are 0-4). So the check can separate a correct entry from a typo.

**Fix:** the GET body carries `checks: { fptsThroughWeek, perRosterDelta[], recordMatches }` whenever a snapshot
or `last_scored_leg` covers the overridden weeks. The panel shows any mismatch in words. Allan decides whether
a mismatch blocks the save or only warns. A warning is enough to keep the "entered by hand" label honest.
Optional: week 2 could be derived as `fpts(2) − override(1)`, which halves the typing. Don't build that
unless Allan wants it. Amend "Not built" either way.

**H2 (high). Live verification SC-001 / Acceptance 4 is vacuous on today's data.**
- Measured: both local databases (`draftsim`, `draftsim_057` on 5433) hold **0** `league` rows, so the live
  check needs a fresh ingest first.
- Measured: today's `/matchups/1` has roster 1 at **236.5** and roster 5 at **231.5**, so popsharky already
  wins week 1 on the feed. The spec's "219.5 to 239.0" (spec.md "Concrete bug") is stale. That value may be
  what prod stored, but nobody has re-checked it.
- A fresh local ingest will therefore show "no week-1 close win for jstrobe" (a 5.0 margin; check it against
  `closeGameMargin`) **without the feature**. The check passes before any code exists. Lessons class 1/8:
  this is a structural pass, not a preference test.

**Fix:** (a) re-measure prod's stored week-1 rows for rosters 1 and 5 and correct the spec in place.
(b) Write the live acceptance so the override **flips** a result the stored feed decides the other way. Either
seed `roster_week_points` locally to prod's stored values, or enter override values that reverse the feed's
winner of some game. Then assert the flip on every FR-007 page (SC-003).

**H3 (high). Provenance can't come out of the repositories as designed, and moving the pot's overlay deletes
the only preference tests for the money path.**
- D3 resolves inside `allScores`/`pairedWithScores` and returns plain `ScoreRow`/`PairedGame`. But D5 needs
  the source per week in the pot, superlatives and report. Each service would have to call
  `resolver.resolve(...)` a second time. Two problems follow:
  - The numbers and the label come from two separate reads. A save landing between them labels FEED numbers
    COMMISSIONER, or the other way round.
  - Superlatives alone makes ~7 resolutions per request (M5).
- `WeeklyPotServiceTest:137-187` (four tests: `anOfficialWeekPaysTheOfficialTopScorerNotTheFeedOne`,
  `aWeekWithoutTheEarlierLeg…`, `aSnapshotTakenALegLate…`, `aNegativeOfficialWeek…`) **mock** `allScores` and
  `fptsSnapshots.byWeek`. Once the overlay lives in the repository, a mocked `allScores` never resolves. The
  tests then either fail or get rewritten to assert nothing about official scores. The plan says the tests "that
  call `plausibleOfficial` move". None of these call it.
- The same holds for every one of the 24 repository mocks. No unit test of any consumer can see the resolver.
  The only end-to-end coverage is a Postgres IT, and those **skip silently** when 5433 is down (memory:
  "BUILD SUCCESSFUL with 52 skipped").

**Fix:** pick one of these.
- (a) Add `source` to `ScoreRow`, `PairedGame` and `WeekPoint` (24 construction sites, all mechanical).
  Services then read numbers and label from the same row.
- (b) Make `WeekScoreResolver.resolve(ids)` public. Give each repository read an overload that takes a
  `Resolution`. Services resolve once per request and pass it down.

Re-home the four pot tests as resolver unit tests plus one pot test with a fake `Resolution` that still asserts
the official winner gets paid. Name in tasks.md that the IT skip count must be 0 at verification.

### MEDIUM

**M1. The weekly report awards mix resolved team totals with feed per-player data, and one of them breaks
FR-012.**
- `gotAwayWithIt:620-635` computes `actual/optimal`, where the plan makes `actual` (`pointsByRoster`)
  resolved while `optimal` is feed `players_points`. For NBA 2026, where official is ~8–20% above the feed,
  efficiency can exceed 100%, or the award fires or misses on noise. FR-012 says lineup efficiency stays on
  the feed.
- `selfInflictedWound:700-745` compares a feed bench-swap gain against a resolved margin. "Enough to flip the
  matchup" then states a mixed-source claim.
- `onePlayerCarry:661-685` prints "X of Y" with the feed `Y` beside a matchup that now shows a different total.

**Fix:**
- Use resolved totals only to decide winners and losers (`games`) and for `deservedBetter`'s rank.
- Keep a separate feed `pointsByRoster` for the efficiency ratio and for the carry's denominator.
- Document the self-inflicted mix, or omit that award on non-FEED weeks with a stated reason, the same as
  `STARTERS_NOT_STORED`.

**M2. SC-005 ("NFL payloads unchanged / byte-identical") can't hold as written.**
- Every payload gains new fields (`scoreSources`, `scoreSource`).
- The NFL league has snapshots (055 captures every Sleeper league), so its weeks resolve to **OFFICIAL**. The
  Awards page and the report would then print "Sleeper's official totals" for NFL weeks.
- 055 measured only **cumulative** equality (`fpts` = sum of feed through week 8). A stat correction that
  lands in a later leg (055's "What this cannot do") moves points between weeks. That is now visible on every
  page, not only the pot.

**Fix:** restate SC-005 as "same winners, scores and ranks; new provenance fields excepted". Before the switch,
measure NFL per-week `fpts(N) − fpts(N−1)` vs feed for every snapshot week. Decide whether OFFICIAL weeks
equal to the feed get labelled at all. Suggestion: label only when a value differs.

**M3. The plan switches every consumer to 055's OFFICIAL tier before 055's own decisive check, which is owed
after 2026-11-10.** 055 said "this change should be shown to work on one page first"
(`claude/official-week-scores.md:36-38`). The spec's Assumptions accept this because "today it changes nothing
measurable". But around 11-10, week 3 becomes the first OFFICIAL week on every page at once, before anyone has
compared it with the app. This is a call about **what the feature is**, so it needs Allan. Options:
(a) proceed as planned; (b) keep the middle tier pot-only behind one constant until the 11-10 check passes.

**M4. The panel's season can differ from the season the report shows.** `WeeklyReportService.forWeek:213-216`
and `SeasonSuperlativesService.forLeague:291` go through `LeagueSeasonResolver`, which can walk back a season
(`requestedSeason != null`). D4 addresses `membership.visibleLeague`, the URL's row, as the pot does.

**Fix:** the panel calls the endpoint with `Result.sleeperLeagueId`, the season actually shown, never the
route param. tasks.md needs a test for a walked-back season.

**M5. Query volume.**
- One Awards load resolves ~7 times: `pairedWithScores` `:348`, `highestWeeks`/`lowestWeeks`/`biggestBlowouts`/
  `closestMatchups` via `LeagueRecordService`, `expectedWins.forLeague` → `finalGames`, and the plan's
  standings/scoreSources resolution.
- `RivalryService.chainGames:434-438` and `HeadToHeadService:143` call `pairedWithScores` once **per
  league-season** in a loop. `HallOfFameService:135,305` calls `chainGames` too.
- The `isEmpty()` fast path doesn't fire for any league with snapshots, which is every Sleeper league since 055
  (NFL included). So "2–4 small queries per read" turns into roughly 15–30 extra queries per Awards or
  Rivalries load.

**Fix:** make the existence probe one `union all` query over both tables for the id set. Resolve once per
request (H3 option b), and for chains resolve once for the whole chain (`resolve(chainIds)`) rather than once
per league.

### LOW

- **L1. A partial COMMISSIONER week falls back silently.** `Resolution.points` falls back to the feed for a
  roster missing from a non-FEED week (D2). Whole-week saves prevent that at save time, but nothing asserts it
  at read time. Log it, or treat the week as FEED, so a week never mixes sources quietly. That is the 055 rule.
- **L2. `extremes` moved into Java must reproduce the SQL tiebreak exactly.** Points compare as BigDecimal,
  then season desc, week asc, roster asc (`RosterWeekPointsRepository:208`). `LeagueRecordIT` covers the
  ordering. Make sure it's in the run, not skipped.
- **L3. `MAX_OFFICIAL_DRIFT` moves into `store`.** Keep its "hand-set and arbitrary" javadoc word for word
  (`WeeklyPotService:43-49`). It's the label the project rule requires.
- **L4. `RosterManagementService:193` season actual totals stay on the feed.** For NBA 2026 they will visibly
  disagree with power-ranking PPG and record-book totals. Per FR-012 that's correct, but say so in the spec's
  edge cases so nobody files it as a bug.
- **L5. The AI recap gets resolved numbers but no `scoreSource`.** A generated recap could present
  hand-entered scores as measured. Prod answers FEATURE_OFF, so this is low. Add the field to `RecapInput`
  when recap ships.
- **L6. `WeeklyPot.tsx` doesn't subscribe to `leagueDataVersion`.** It's a separate route that refetches on
  mount, so this is only a problem if the panel is ever embedded there.
- **L7. ScoredWeeks finality for NBA weeks 1–2 couldn't be measured locally (empty DB).** Sleeper reports
  `leg 3, last_scored_leg 2` (measured), so 009's rule makes weeks 1–2 final once ingested. Re-check
  `ScoredWeeks.of(id).finalWeeks()` after the local ingest, before testing the 400-on-not-final path.
