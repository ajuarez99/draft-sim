# Research: Weekly High-Score Ledger (spec 048)

All findings read from code on `main` at ec72031 (2026-10-25). Nothing here was executed
yet; where a claim needs a measurement, it is marked **to measure** and lands in
`verification.md`.

## R1. Where the weekly scores come from

- **Decision**: `RosterWeekPointsRepository.allScores(List.of(leagueId), bound)`
  (`store/RosterWeekPointsRepository.java:232`). One row per roster-week with
  `starters_points` (Sleeper's own starting-lineup total), the roster's season manager
  and avatar, LEFT JOINed so an unowned roster still appears.
- **Rationale**: the record book (`LeagueRecordService.highestWeeks`) and superlatives'
  weekly high score read the same rows. One definition of "a week's score" (FR-003,
  spec Assumption "Highest scorer").
- **Alternatives**: `league_matchup` points — only covers paired rosters and adds nothing.

## R2. Which weeks are "decided"

- **Decision**: `ScoredWeeks.of(leagueId).isFinal(week)` (`engine/ScoredWeeks.java`), the
  one finality reader built on spec 009's `WeekFinality`. A stored-but-not-final week
  (NBA scores during the week) moves no money.
- **Rationale**: FR-003 "the same decided-week rule the rest of the app uses";
  `ExpectedWinsService.java:403` filters the same way.

## R3. Regular season only

- **Decision**: `ExpectedWinsService.regularSeasonBound(league)` (`:386`), already shared
  by superlatives and career pages: `through(playoff_week_start - 1)` when the league has
  a playoff start ≥ 2.
- **Gap found**: when there is no playoff format the bound is `ALL_WEEKS`. Counting
  every week would silently include playoffs. Decision: still count every final week
  (refusing would hide the ledger for a league that may simply have no playoffs), but the
  response carries `regularSeasonKnown: false` and the page says "this league doesn't
  record when its playoffs start, so every scored week is counted". Every real Sleeper
  league stores `playoff_week_start`; **to measure** for the six real chains which, if
  any, lack it.

## R4. Ties and cents (clarified 2026-10-25)

- **Decision**: all money is integer cents (`long`). For a week with `k` co-winners and
  stake `s` cents, each non-winner pays `s` in total: every co-winner gets `s / k`, and
  the first `s % k` co-winners in ascending `roster_id` get one extra cent. Tie detection
  compares `starters_points` as stored (`numeric(8,2)`), so two scores equal to the
  hundredth tie.
- **Rationale**: integer cents makes zero-sum exact (FR-006, SC-002) with no rounding
  step to get wrong. Ascending roster id is arbitrary, and the response labels it
  (`tieCentRule`), per the project's "label a hand-set convention" rule.
- **Alternatives**: exact fractions displayed rounded — sums of displayed figures would
  stop adding to zero, the exact thing SC-002 checks.

## R5. Settle-up: pairwise and "few payments" (clarified: show both)

- **Pairwise**: `owed[payer][payee]` accumulates each week's shares; the pair's net is
  `owed[a][b] − owed[b][a]`, listed from the side that owes.
- **Few payments**: greedy — repeatedly match the most-negative balance with the
  most-positive and transfer the smaller magnitude. Produces at most (non-zero balances − 1)
  transfers. Exact minimum is NP-hard in general, so the spec's "fewest" is **amended**
  to "a short list that never uses more payments than the pairwise view": the service
  computes both and, if greedy ever uses more transfers than pairwise (possible when the
  pairwise graph splits into several components), returns the pairwise list as the
  shortcut too, with `shortcutIsPairwise: true`.
- Both lists are checked in the service's own unit tests to clear every balance (SC-003).

## R6. Who a roster belongs to

- **Decision**: roster's season manager from `roster_season` (via R1's join), team name
  from `league_member.team_name`, exactly as `ExpectedWinsService.java:411-425` builds
  `TeamRow`. Balances are keyed by **roster**, not manager.
- **Spec amendment**: the spec's edge case "a manager change mid-season: balances follow
  the owner for each week" cannot be met — the schema stores one manager per
  roster-season, not per week. The ledger follows the roster, labelled with its season
  manager, the same attribution the record book uses. Recorded in spec as amended.

## R7. Where the setting lives and who may change it

- **Decision**: new table `league_weekly_pot` (migration **V41**; highest on main is V40,
  no in-flight worktree holds a V41 — checked 2026-10-25), keyed by `league.id`, i.e.
  per league-SEASON row, like the conduct list (`SuperlativesController.java:67-74`).
  Gate: `LeagueMembership.canCommission(leagueId, sleeperUserId)` — the same honour-system
  commissioner check the conduct list and power ballots use, and the same 403 shape.
- **Consequence**: a past season shows a ledger only once its own row is turned on. The
  page offers the toggle on whichever season it is opened on (commissioner of that row —
  history ingest records `is_owner` for past rows too, `LeagueHistoryIngestService.java:164`).
- **Stake bounds**: 1..100000 cents ($0.01..$1,000). The upper cap is a hand-set sanity
  limit, labelled arbitrary.

## R8. Season resolution

- **Decision**: the URL's row exactly, via `membership.visibleLeague(sleeperId, user)` —
  no `LeagueSeasonResolver` fallback. The setting is per row, so falling back to last
  season would show a ledger the commissioner never turned on for the season in the URL.
  A season with no final regular-season week shows "no decided weeks yet".

## R9. Who's leading this week (US4)

- **Decision**: `LiveWeekSource.current(row)` (`engine/LiveWeekSource.java:172`), spec
  045's bounded live read. Shown only when its `status` is OK, its `week` is inside the
  regular-season bound, and that week is not already final. Nothing is stored. FAILED →
  `liveLeader: null` with `liveUnavailable: true` (the page says it couldn't check), never
  a silent omission.
- **Why not stored rows**: NFL's in-progress week is never stored (`last_scored_leg` is
  the last completed week), so a stored-rows approach works for NBA only.

## R10. Navigation (spec amendment)

- FR-001 said "no ledger navigation when off". The rail is declared per sport in
  `web/src/destinations.ts` and has no per-league data; and the commissioner needs a page
  to turn it on. **Amended**: the rail always shows "Weekly pot" (both sports); when off,
  the page shows no money, just one sentence about what it is and, for the commissioner,
  the turn-on control. SC-005 ("no dollar amounts anywhere when off") is unchanged.
