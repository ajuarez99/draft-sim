# Research: Season superlatives, so far

Phase 0 for `specs/008-season-superlatives/plan.md`. Each entry says how it was established:
**measured** (run against live Sleeper on 2026-09-22), **read** (code, not executed), or **decided**
(a judgement call, with its alternatives). The app's own database was not running on 2026-09-22 (only
the unrelated Postgres on 5432, left alone), so nothing here was measured against stored rows.

---

## R1 — Where the section lives

**Decision**: its own page, `/leagues/:sleeperLeagueId/superlatives`, with a rail entry "Superlatives"
directly after "Weekly report" in `web/src/destinations.ts`.

**Rationale** (read): the spec assumed "League analysis page, near the Weekly Report", but the Weekly
Report is not a section of that page. It is its own route (`web/src/App.tsx:117`) and rail entry
(`destinations.ts:214`), and so are Expected wins, Roster management and Season forecast. A section
bolted onto `LeagueAnalysis.tsx` (1,216 lines) would be the one analytic that doesn't follow the
pattern.

**Alternatives**: a section inside `LeagueAnalysis.tsx` (rejected, above), or a tab on the Weekly
Report (rejected: it would mix "this week" and "this season", the overlap FR-007 exists to prevent).

**Spec amended** (see spec.md "Amended after planning").

## R2 — What "fully scored" and "regular season" mean

**Decision**:
- **Fully scored** = the week has rows in `roster_week_points`.
- **Regular season** = week < the league's `playoff_week_start`. A league with none (`< 2`) counts
  every stored week, and the page says the league has no playoff boundary set.

**Rationale** (read): `LeagueHistoryIngestService.ingestWeeklyPoints` only writes weeks
`1..settings.last_scored_leg`, and `LeagueAnalysisService.analyse` already treats stored weeks as the
scored set ("reporting the stored count is reporting what the page is showing"). A partly-played
week is never stored, so FR-001's in-progress case falls out of the storage rule. That's the same
answer every other page gives.

**Caveat, amended after analysis (2026-09-23):** "stored" is slightly stronger than "final". The
same ingest always re-fetches the most recent stored week, because it "may have been ingested while
Sleeper was still finalizing that week's scores" (`LeagueHistoryIngestService.ingestWeeklyPoints`
javadoc). So the newest week's figures can move by a stat correction until the next ingest. Every
page that reads stored weeks shares this. Superlatives don't claim more: the page says "through
week N", not "final".

Measured: (Foot) Ball Knowers 2026 is `in_season`, `last_scored_leg: 2`, `playoff_week_start: 15`,
and Sleeper's `/state/nfl` reports week 3. So the page would read "through week 2" today.

## R3 — Expected wins currently includes playoff games (bound approved 2026-09-23)

**Finding**:
- Measured on Sleeper: playoff weeks carry pairings. NFL 2025 weeks 15 and 17, and NBA 2025 week
  19, each have 8 of 12 rosters with a `matchup_id`.
- Read in code: ingest stores those pairings through `last_scored_leg`, and
  `ExpectedWinsService.forLeague` takes every paired game in the season
  (`ExpectedWinsService.java:204-210`) with no `playoff_week_start` bound.
- So for a finished season, the Expected wins page almost certainly counts playoff and consolation
  games as schedule luck. That's inferred from the two facts above; the stored rows haven't been
  checked.

**Decision (approved by Allan 2026-09-23)**: bound `ExpectedWinsService` to the regular season
**for both callers**, the existing page and the superlatives, through one explicit parameter. FR-004
requires luckiest/unluckiest to match the table exactly, so a second, bounded computation isn't an
option.

**Consequence (accepted)**: the existing Expected wins page's numbers change for any
season that has reached its playoffs (every complete season). During the regular season nothing
changes, because no playoff week is stored yet.

**Alternatives**:
- Leave the table unbounded and make the superlatives include playoffs too. Rejected: it
  contradicts FR-001, and playoff "luck" isn't the league's schedule.
- Let superlatives compute their own bounded figure. Rejected: FR-004, and the "two implementations
  of one rule" landmine memory.

## R4 — Season extremes reuse the record book, bounded

**Decision**: US1's highest/lowest week, biggest blowout and closest game come from
`LeagueRecordService`, called with the single league-season's id and an explicit week ceiling.

**Rationale** (read): `LeagueRecordService.forChain(Collection<Long> leagueIds, int limit)` already
takes league ids, and a `league` row is one season of a chain. So passing one id *is*
single-season extremes, computed by the same code the all-time record book uses (FR-004, SC-007).
The only missing piece is the week ceiling (R2/R3), which `RosterWeekPointsRepository.extremes` and
the margin query don't take today.

**Rule for the new parameter** (from the memory "optional params that encode rules"): it is **not
defaulted**. The record book passes an explicit "all weeks" bound, and superlatives pass
`playoff_week_start - 1`. A defaulted bound would silently mean "all weeks" to a caller who forgot
it, and that bug shape has shipped three times here.

## R5 — Close-game margin (FR-011)

**Measured** (2025 regular seasons, all paired games, from Sleeper matchups):

| League | Games | Median team score | Score SD | Margin p10 | p20 | **p25** | Median |
|---|---|---|---|---|---|---|---|
| (Foot) Ball Knowers NFL 2025 | 84 | 124.1 | 26.6 | 5.2 | 8.2 | **9.8** | 20.9 |
| Ball Knowers NBA 2025 | 108 | 241.8 | 27.4 | 5.0 | 12.5 | **14.0** | 27.2 |

**Decision**: a close game is decided by **under 10 points in football** and **under 15 in
basketball**. That's each sport's 2025 p25 margin, rounded to a number a reader can quote. Both
values are hand-set and labelled arbitrary where they're declared.

**Where it's declared**: a non-defaulted `SportRules.closeGameMargin()`, so the value is written once
per sport and no call site compares sport names. That mirrors how `playsMultipleGamesPerScoringPeriod`
is declared.

**This corrects the spec's premise.** FR-011 said basketball needs its own margin "because basketball
weekly totals run far higher". Totals are about twice as high, but the spread is nearly identical
(SD 27.4 vs 26.6). So a *share-of-score* margin (the spec's other suggestion) would be wrong: 4% of
the score is 5 points in football and 10 in basketball, a much tighter cut in football. Per-sport
absolute margins stand. The reason is the measured margin distributions, not the totals.

**Alternatives**: one margin for both (10 would make basketball "close" only ~16% of games vs ~26% in
football); share of score (rejected above).

## R6 — Early-season caveat (FR-006)

**Decision**: luck, bench, waiver, Embiid and Unethical superlatives carry the caveat while fewer than
**4** regular-season weeks are scored. That's a constant in the new service, javadoc'd as arbitrary.

**Rationale**: the spec's placeholder, unchanged. `LeagueAnalysisService.MIN_SCORED_WEEKS = 3` exists
but gates a *refusal* (rankings withheld), not a caveat, so reusing it would couple two unrelated
decisions. This is a guess, not a measurement, and is labelled that way.

## R7 — Most points left on the bench (US2)

**Decision**: per team, sum over regular-season weeks of (best possible lineup − actual score).
"Best possible lineup" comes from `RealizedLineupService.bestLineup`, the same call the Weekly
Report's *Got away with it* uses (`WeeklyReportService.java:215-219`). Weeks where `valid()` is false
don't count, and the count of covered weeks is reported per team (US2 scenario 3).

**Rationale** (read): this uses only `players_points`, not starters, so it doesn't depend on V18
starters coverage. Not run.

## R8 — Waiver Wire Warrior (US3, FR-012)

**Decision**: for each regular-season week *w*, each team *T*, and each player *p* in *T*'s stored
starters that week:

1. Find the most recent `league_transaction` row (by week, then `created_at`) in this league-season,
   with week ≤ *w* and status `complete`, whose `adds` put *p* on *T*'s roster.
2. Count *p*'s `players_points` for *T* in week *w* only if that row's type is `WAIVER` or
   `FREE_AGENT`.
3. A player *T* drafted (no add row) doesn't count. A player *T* last acquired by trade or
   commissioner move doesn't count (scenarios 3 and 5). Re-adds are handled by "most recent"
   (scenario 2).

**Rationale** (read):
- Every case in the spec reduces to "how did this starter most recently arrive on this roster".
  That needs no tenure table and can't double-count, because each (week, team, starter) is visited
  once.
- `league_transaction.type` already distinguishes all four types (V19 check constraint), and
  `adds` is `{player_id: roster_id}`.
- Starters are stored for every week since V18's gate change. Measured on Sleeper: all 168
  NFL 2025, 216 NBA 2025 and 24 NFL 2026 matchup rows carry `starters`. Whether *this DB* has them
  depends on a re-ingest having run; the page reports covered weeks either way.

**Coverage gaps it must report**: weeks with no stored starters, and a season with no stored
transactions at all. In that case the superlative is unavailable, with the reason "transactions not
ingested — run POST /api/ingest/transactions/{id}", the same shape as `PowerRankingService.realizedGap`.

**Not verified**: whether a waiver processed mid-week has `leg` = the week it can first be started.
The rule counts only weeks where the player was actually *started*, so an off-by-one in `leg` can
only matter if a player was started in the same week the add was recorded. Check one real case in
the quickstart.

## R9 — Missed games: what Sleeper actually reports (US4)

**Measured** on `GET https://api.sleeper.app/stats/{sport}/player/{id}?season_type=regular&season=S&grouping=week`
(the endpoint `SleeperPlayerStatsClient` already uses):

**Basketball**: every scheduled team game appears, played or not. Joel Embiid, 2025 season (id 1511):
82 entries, one per PHI game. A game he missed carries `stats: {}`. A game he played carries a full
box score. `gp` is absent in both cases, so it isn't the played signal in basketball.

**Football**: one entry per week.

> **Amended after build (2026-09-23), found by live ingest.** "One entry per week" was true, but the
> *shape* was misread. A football week's value is a **bare JSON object**, where a basketball week's
> is a list of game objects. The measurement script that produced this entry quietly handled both
> (`e = v if isinstance(v, dict) …`), so the difference never made it into this text. The ingest,
> written from this text, cast every football week to a list:
> - The first live run threw `ClassCastException` on all 315 NFL players.
> - It stored 0 games and reported 5,670 unclassified weeks.
>
> It's now normalised to accept both shapes, with a regression test. After the fix, NFL 2025 had
> 315 players walked, 4,421 games stored, 936 absences, 0 failed and 0 unclassified.
- Christian McCaffrey 2024 (id 4034), weeks 2–8 (on IR): entry present with team, opponent and date,
  `gms_active: 1.0`, but **no `gp` and no `pts_ppr`**.
- Week 9 (SF's bye): `None`.
- Weeks 10–13 (played): `gp: 1.0` and points.
- Patrick Mahomes 2024 (id 4046): week 6 (KC's bye) is `None`, but **week 18, when he was rested,
  is also `None`**.
- So in football `None` means "bye *or* didn't dress", and `gms_active` is 1 even when the player
  didn't play.

**Finding: the current ingest throws away missed games.** `PlayerGameIngestService.toRow` returns
null when `stats` is empty (`PlayerGameIngestService.java:128`). That's exactly how basketball marks
a missed game. The data the Embiid award needs is fetched today and discarded. Football has the
mirror-image problem: a DNP entry has non-empty stats (`gms_active`), so if football player-games
were ever ingested, DNP weeks would be **stored as games played**. That's harmless today only
because nothing reads football player-games (`WeeklyReportService` gates on
`playsMultipleGamesPerScoringPeriod`).

**Decision**:
- **"Did this entry's player play?"** becomes one non-defaulted `SportRules.playedIn(entry stats)`.
  - Basketball: stats non-empty.
  - Football: `gp > 0`.
  - `PlayerGameIngestService.toRow` uses it for played games, which fixes the football latent bug in
    the same change.
- **Missed games go to a new table** (`player_absence`, data-model.md) rather than a `played` flag
  on `player_game`. Spec 005's two readers of `player_game` then keep meaning "games played" with no
  edit, and can't start counting DNPs because someone forgot a filter.
- **Football `None` weeks** are classified during the same ingest walk:
  - The walk sees every rostered player's entries, each carrying `team` and `week`, so it builds a
    set of (team, week) pairs in which that NFL team played.
  - A `None` week for a player whose team is in that set is an absence (Mahomes' week 18).
  - A `None` week for a team *not* in the set is a bye.
  - A `None` week whose team can't be determined (no neighbouring entry) is **unclassified**. It's
    not counted, and it's reported in coverage.
  - Rostered defences (team-code "players") and ~180+ rostered players cover most of the 32 NFL
    teams in a 12-team league. Coverage is measured in the quickstart, not assumed.
- **Football ingest cost** (estimated, not measured): the same one-call-per-rostered-player walk as
  basketball. At the ~300 ms per call measured for basketball in spec 005, a 12-team NFL
  league-season is ~250–330 calls, so roughly 1.5 minutes. It stays on the existing manual endpoint
  `POST /api/ingest/player-games/{id}`, not in `ingest/all`, and the page says when it hasn't run.

**This reverses a spec assumption.** The spec guessed football might have to ship US4 as
unavailable. Measured: it doesn't, apart from the unclassified weeks above.

## R10 — How an absence costs points (FR-014)

> **Amended 2026-09-23 on Allan's call.** The first version of this entry costed absences per
> *fantasy week*, and counted a week only when the player played no game in it. The argument was
> that this NBA league's weekly score counts one game per player, so a player who missed three
> nights but played a fourth still scored. Allan overruled it: "as a player you want every single
> game anyways and not just that one game." The award measures lost availability, not the fantasy
> score it happened to cost that week. The week-level rule is kept below as the rejected
> alternative, not deleted.

**Decision**: the cost is measured **per missed game**, in both sports.

- An absence for team *T* is a missed game (a `player_absence` row) in a regular-season week in
  which *p*:
  - was on *T*'s roster (a key in *T*'s `players_points` that week), and
  - was a regular contributor.
- Each missed game costs *p*'s mean points per game played this season, labelled "estimated". Each
  game played is scored under this league's settings by `GameScoringService.score` (spec 005: one
  implementation of "what is this game worth"), over his `player_game` rows.
- Football has one game per week, so there "games missed" and "weeks missed" are the same number.
  One rule serves both sports, with no sport branch.
- The award shows `gamesMissed` as the headline count. `weeksAffected` rides alongside for context.

**Regular contributor** (hand-set, labelled arbitrary): *p* was in *T*'s stored starters in at least
half of the weeks *T* rostered him *and he played at least one game*, with at least 2 such weeks.
A deep stash never qualifies (US4 scenario 4).

**Dependency this creates**: the mean needs football `player_game` rows to hold only games actually
played. That's what the `playedIn` fix in R9 guarantees; without it, football DNP weeks would be
averaged in as games.

**Alternatives**:
- Cost per fantasy week with no game played (the first decision). Rejected by Allan: it forgives
  every partial absence, and Embiid-style seasons are exactly strings of partial absences.
- Using the Sleeper injury tag. Rejected by FR-013, since only today's tag exists.

## R11 — Suspensions: what Sleeper tags, and when to capture (US6)

**Measured** on 2026-09-22 (`/v1/players/{sport}`):
- **Football**: `injury_status = "Sus"` on 10 players (e.g. James Pearce, ATL). `status` stays
  `Active` or `Inactive`.
- **Basketball**: `status = "SUS"` on 1 player (Jontay Porter). `injury_status` is unused for
  suspension.
- So **the two sports put the tag in different fields**.
- Rostered right now: **zero** suspended players across the three 2026 NFL leagues in README's table.

**Decision**:
- **One rule.** "Is this player suspended?" is one non-defaulted `SportRules.isSuspended(Player)`,
  implemented per sport against the fields above. There are no string comparisons at call sites.
- **Where it's captured.** Suspension tags are captured inside `PlayerIngestService.ingest(sport)`,
  the only place the tags arrive, stamped with Sleeper's `/state/{sport}` season and week at capture
  time. Each run writes one `status_capture` row (so "we looked in week *w*" is recorded even when
  nobody was suspended) plus a `player_suspension` row per suspended player.
- **What counts.** A suspended player counts for team *T* in week *w* when a capture exists for *w*,
  he was flagged in it, and he's a key in *T*'s `players_points` for *w* (FR-018).

**What this can't do, stated on the page** (FR-019): there's no scheduler in this codebase (no
`@Scheduled` anywhere; ingest runs only when called). A week counts as "observed" only if a player
ingest ran during it. The page lists observed weeks, so gaps show instead of reading as "no
suspensions".

**Expectation to set with Allan**: given zero rostered suspended players today, the automatic half
will usually be empty for football, and the commissioner's list will carry the award. That's a
measured snapshot of one day, not a season rate.

**Not verified**: whether a player in a Sleeper IR/reserve slot still appears in `players_points`.
If he doesn't, he'd be missed. Check in the quickstart.

**Amended after analysis (2026-09-23): this matters for US4 too, not only US6.** Both awards decide
roster membership from `players_points` keys (R10, R11). IR is where injured players sit, so if
reserve-slot players aren't keys, the Embiid award would miss most long absences. The tasks now have
an explicit decision task after the measurement:
- **If reserve players are keys**: record it as verified; no change.
- **If they aren't**: membership for those two awards comes from a roster-tenure reconstruction
  (draft picks, plus `league_transaction` adds/drops/trades, in order). Its result is compared
  against `players_points` keys for players *not* on reserve, and the two must agree before it's
  trusted.
- **If even that can't be made to agree**: both awards report "players in an IR slot can't be seen
  in Sleeper's weekly data" as a coverage reason, not a silent undercount.

**Alternatives**:
- A new `@Scheduled` daily capture. Deferred: it adds scheduling infrastructure to a Railway
  deployment for one field, and it's a separable follow-up if the gaps prove real.
- A `player_status_history` of every tag. Rejected: ~12k NFL rows per capture to answer a question
  about ~10.

## R12 — The commissioner's list (US6, FR-017)

**Decision**:
- **Storage.** A table `league_conduct_entry` scoped to the `league` row (one league-season), with
  player, a reason (≤ 140 chars) and **the week it applies from** (the spec said "date"; week is
  what FR-018's rule compares against, so storing a date would need a conversion nobody asked for).
- **Deleting.** Removing an entry deletes its row. It's the commissioner's own data, entered by
  hand, with nothing downstream.
- **Who can edit.** `canCommission` moves from a private method in `LeagueHistoryController` to
  `LeagueMembership`, next to `canSee`, and both the ballot and the conduct list call it. That's the
  move `LeagueHistoryController.visibleLeague`'s own javadoc prescribes "the moment something
  does" need it outside that controller. `visibleLeague` moves with it.
- **No commissioner.** When `anyCommissioner` is false, writes are refused with the ballot's
  existing message shape, and the page says the list isn't available (US6 scenario 6).

**Rationale**: reusing the one commissioner gate keeps "who is the commissioner" a single rule; the
ballot's javadoc already argues for exactly this ("so the two can never disagree").

**Reason text** is shown as plain text (React escapes it). It's a person's free-text claim about a
real athlete, and the UI attributes it: "Commissioner's call".

## R13 — Weekly award trophy case (US5): not built

**Decision**: dropped from this feature, as the spec allowed.

**Rationale** (read): the awards are computed inside `WeeklyReportService.forWeek`, per week, with
private static functions. A tally would call `forWeek` once per scored week (each call reloads
players, lineups and per-game rows). It's the one story Allan didn't ask for and the spec itself
calls misleading. It can come back as its own change.

## R14 — Endpoint shape and scoping

**Decision**:
- `GET /api/leagues/{sleeperId}/superlatives` (a season is chosen by its own league id; no `?season=`, see the contract) returns every superlative in one payload,
  each with its own availability and coverage.
- `GET|POST /api/leagues/{sleeperId}/conduct-list` and `DELETE /api/leagues/{sleeperId}/conduct-list/{entryId}`
  handle the commissioner's list.
- All are scoped through `LeagueMembership` (`visibleLeague`) and `X-Sleeper-User`. See
  contracts/superlatives-api.md.

**Noticed in passing, out of scope**: `ExpectedWinsController` takes no `X-Sleeper-User` and doesn't
call `LeagueMembership` (read; the global `ApiTokenFilter` was not checked). If nothing upstream
scopes it, any signed-in user can read any ingested league's expected wins. That's worth checking on
its own, not folded into this feature.

## R15 — Types mirror

**Decision**: `web/src/api.ts` gets hand-written types for the superlatives and conduct-list
payloads in the same change as the Java records (AGENTS.md hard rule). A component test asserts that
every superlative renders its coverage note, so a dropped field fails loudly instead of rendering as
absent.
