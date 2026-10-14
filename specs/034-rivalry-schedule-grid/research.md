# Research: Rivalry schedule grid and rivalry week (spec 034)

All measurements were taken 2026-10-14 against the local DB (`localhost:5433`, ingested
2026-10-12/13) and the live Sleeper API. Anything not measured is labelled *inferred*.

## R1 — Where the playoff start week comes from

**Finding (corrects the spec):** it **is** stored. `league.settings_json->>'playoff_week_start'`
is read by `LeagueRepository.playoffFormat(leagueId)` → `PlayoffFormat.playoffWeekStart()`.
`ExpectedWinsService.regularSeasonBound`, `ScheduleGridService`, `NextMatchupService` and
`SeasonSuperlativesService` already use it. The spec's Background said "not stored in any
migration". That was a grep for a column, which missed the JSON. The spec now carries an
amended note.

Measured values: "(Foot) Ball Knowers" 15 (2025, 2026), West Coast 15, NBA "Ball Knowers"
22 / 19 / 20 (2024 / 2025 / 2026). All have `playoff_teams` 6. Round type is 0 for every
league except West Coast's, which is 1.

**Decision:** reuse `playoffFormat`. A value `< 2` means no playoffs, and the split is then
refused with a reason. That's the same `>= 2` rule every other caller applies.

## R2 — A playoff week is not a playoff game

**Measured:** "(Foot) Ball Knowers" 2025 weeks 15–17 hold **14** scored pairings. With 6
playoff teams, the winners bracket is only 7 games. The other 7 are consolation games, and
Sleeper pairs those too. The same holds for NBA 2024 and 2025 (14 each) and West Coast 2025
(18 over four weeks).

`roster_season.final_placement` only stores the champion: `1` for one roster per season, null
for the other 11. So stored data can't tell a winners-bracket game from a consolation game.
`ManagerCareerService:128` already says as much ("the bracket is not parsed").

**Live check:** `GET /league/1254190892974084096/winners_bracket` returned 7 matches. Their
roster pairs are exactly the 7 stored pairings that aren't consolation: week 15 (8, 9) and
(3, 4); week 16 (1, 9), (3, 11) and (4, 8); week 17 (1, 3) with `p: 1` (the final) and
(9, 11) with `p: 3`. The other 7 stored pairs are all teams outside the 6 that made the
bracket. The NBA 2024/2025 and West Coast 2025 brackets came back in the same shape
(`m, r, t1, t2, w, l, p, t1_from, t2_from`).

`SleeperClient.winnersBracket()` exists (`ingest/SleeperClient.java:85`) and **nothing calls
it**.

**Decision:** store the winners bracket in a new table. Each scored game in a playoff week is
then **PLAYOFF** if its roster pair appears in that season's winners bracket, and
**CONSOLATION** otherwise. A season whose bracket isn't stored (it's still in progress, or it
hasn't been backfilled) classifies its playoff-week games as **POSTSEASON_UNSPLIT** and says
so. The split is three-way plus "unsplit", and the parts always add up to the total
(FR-004 / SC-002).

**Alternatives rejected:**
- *Treat every game in a playoff week as a playoff game.* That calls 7 of 14 consolation games
  playoff meetings, so "met in the playoffs" would be false half the time.
- *Infer from `final_placement`.* Only the champion is stored.
- *Compute the week from the round (`start + r - 1`).* Unnecessary. Matching on the roster
  pair within playoff weeks is enough, and it also covers round type 1.
- *Also store `losers_bracket`.* Nothing in the spec needs to tell the toilet bowl from other
  consolation games. Left out.

**Round type 1 (West Coast):** the final runs two weeks, and `league_matchup` pairs the two
finalists in both. The versus page already counts that as two meetings. The matrix keeps the
same count so FR-003's parity holds, and the playoff list labels both weeks as one final.
*Inferred from Sleeper's documented round types, not measured on a played two-week final.*

## R3 — One rule for "a meeting" and "who won"

`HeadToHeadService` (spec 006) defines a meeting with
`LeagueMatchupRepository.pairedWithScores` (both sides scored, `matchup_id` not null, no
mirrored double). It defines the outcome inline by comparing `starters_points` (with an
explicit tie). A second implementation in a rivalry service would be the
two-implementations-of-one-rule bug, which is memory's "class to remember".

**Decision:**
- The rivalry service reads meetings **only** through `pairedWithScores` over the chain's
  league ids.
- The win/loss/tie rule moves to one package-visible static helper, used by both
  `HeadToHeadService` and the new service.
- A parity test asserts every matrix cell equals `HeadToHeadService.compute(a, b)` restricted
  to this chain's league ids (SC-001).

`pairedWithScores` returns no `league_id`, but each league in one chain has its own season,
so `(season, week)` is unique within a chain. That's why HeadToHeadService queries one league
at a time and this service can query the whole chain at once. A test pins the assumption.

## R4 — What "the league's history" is

**Decision:** `LeagueRepository.chainBySleeperId(sleeperId)`: the requested season and its
stored predecessors, walked locally. Matrix rows are the managers with a roster in the
**requested** season. Opening the page on 2025 shows history through 2025 and the 2025
schedule. That matches the "one season" rail rule (`destinations.ts`, spec 011), so the
rail's year links keep you on this page.

A chain that wasn't fully ingested gives a prefix, which `chainBySleeperId`'s own javadoc
already warns about. The response lists the seasons it covered.

## R5 — Meeting counts are thin, and much thinner in football

**Measured** (scored meetings per pair across each chain, current and former managers):

| Chain | 1 meeting | 2 | 3 | 4 | 5 | 6 | pairs ≥ 3 |
|---|---|---|---|---|---|---|---|
| "(Foot) Ball Knowers" (2025 + 4 weeks of 2026) | 28 | 33 | 8 | 1 | – | – | **9 of 70** |
| West Coast (2025 + 3 weeks of 2026) | 43 | 27 | 6 | 2 | – | – | **8 of 78** |
| NBA "Ball Knowers" (2024 + 2025) | 4 | 16 | 15 | 30 | 11 | 1 | **57 of 77** |

So a football rivalry week will be mostly thin-history pairings for at least this season,
and the page must say so rather than dress 1-1 records up as rivalries.

**Decision:** the minimum-meetings threshold `N = 3` is hand-set in
`config/weights.yml` (`draftsim.rivalry.min-meetings`) and labelled ARBITRARY. It isn't tuned
to make football look better. The distribution above is why 3 was picked: it's the smallest
count where a record can't be decided by a single game's swing between 1-1 and 2-0.

## R6 — Choosing the pairings

League sizes are 12 and 14 (measured), and Sleeper allows more (*inferred*).

**Decision: exact maximum-weight matching by bitmask DP** over the current managers. At 14
that's 2^14 = 16,384 states, so it's trivial. Leagues are capped at **16 managers**. Above
that the section is refused with a reason, not approximated with a greedy matching. An odd
count adds one "unpaired" slot. The DP chooses who sits out, and the page names them and
says why.

Determinism (FR-010): managers are ordered by `manager.id`, the DP keeps the first-found
optimum, and scores are compared after rounding to 1e-9, so float noise can't flip a tie.

**Pair score**, hand-set and labelled ARBITRARY in `draftsim.rivalry`:

    closeness  = m × (1 − |w − l| / (m + 1))         m = meetings (ties count to m, not to w or l)
    score      = closeness + playoffBonus × playoffMeetings + championshipBonus × [met in a final]

The `+1` shrinks small samples: 1-0 scores 0.5, 1-1 scores 2, 2-0 scores 0.67, 5-5 scores 10,
and 0 meetings scores 0. Starting values: `playoffBonus 1.5`, `championshipBonus 2.5`.

**Preference-ordering tests are required** (AGENTS.md bug class 1): 5-5 beats 1-0; 4-4 beats
7-1; a pair that met in a final beats an otherwise equal pair; 0 meetings is the minimum; and
a full matching never pairs A–B and C–D when A–C plus B–D scores strictly higher.

**Alternatives rejected:**
- *Greedy (take the best pair, repeat).* It's not optimal and it's order-sensitive.
- *Blossom algorithm.* Correct but heavy for n ≤ 16.
- *Random tie-breaking.* It breaks FR-010.

## R7 — The reason line

Reasons are generated as a **code + numbers**, and the engine turns them into the sentence,
the same split `PlayoffFormat.playoffWindowRefusal` uses. One primary reason per pair, by
priority:

1. `CHAMPIONSHIP_REMATCH`: met in a final (`p = 1`). "Met in the 2025 final (A won 141–122)".
2. `PLAYOFF_HISTORY`: ≥ 1 PLAYOFF meeting.
3. `CLOSEST`: m ≥ N and |w − l| ≤ 1.
4. `MOST_MEETINGS`: the chain's highest m among current pairs.
5. `STREAK`: one side won the last ≥ 3 meetings.
6. `LOPSIDED`: m ≥ N and one side leads by ≥ 2.
7. `THIN`: 0 < m < N. The real record is printed ("met once: A won 120–98").
8. `NEVER_MET`: "never played each other in this league".

Every pair with m < N also gets `thin = true` (FR-011), and the page prints N with
"(hand-set)".

## R8 — Cell states and former managers

**Decision:** each cell has a `status`:

- `MET`: m ≥ 1.
- `NEVER_MET`: both owned a roster in at least one scored season of the chain, but they were
  never paired.
- `NO_SHARED_HISTORY`: there's no scored season both were in, for example a first-year
  manager.

Games against **former** managers are real history but have no column. Each row gets an
`allOpponents` total (every scored meeting in the chain) and a `formerOpponents` count, so
the spec's edge case ("their history still counts") shows up as a number instead of
vanishing. History follows `roster_season.manager_id`, never the roster slot (spec 006 T049),
so a new owner of roster 5 starts at zero.

## R9 — Season grid

- Opponents come from `scheduledPairs(leagueId)` for the requested season. Results come from
  `pairedWithScores` for that league.
- "Record before this week" is the running tally of meetings strictly earlier in
  `(season, week)` order (SC-003). It's computed from one chronological pass, not one query
  per cell.
- Byes: a week with pairings where this manager has none is `BYE`.
- Playoff columns run from `playoffWeekStart` to `lastPlayoffWeek()` (present only for round
  type 0). Weeks in that range with no pairing yet are `TBD`. For round type 1 and other
  types, only stored weeks are shown, and a note says the window length is unknown. That's
  the same refusal `ScheduleGridService` makes.
- `scheduledPairs` empty for the season → `scheduleStatus = NOT_PUBLISHED` (FR-013). Measured:
  NBA 2026 already has 228 paired rows, so it renders.

## R10 — Backfilling brackets without touching scores

`LeagueHistoryIngestService.ingestChain` is where brackets are fetched going forward: one
extra call per season, beside `ingestWeeklyPoints`. The background refresh skips seasons
marked `loaded_complete`, so **existing finished seasons never get a bracket** from the
refresh.

**Don't backfill with `POST /api/ingest/league-history/{id}`.** That reruns the whole chain,
and memory `project_sleeper_nba_2025_matchup_drift` records that Sleeper's live `/matchups`
for finished NBA seasons is degraded ("never re-ingest it").

**Decision:** add a bracket-only endpoint, `POST /api/ingest/brackets/{sleeperLeagueId}`. It
walks the chain and fetches **only** `winners_bracket`, so it can't touch scores. An empty
bracket (pre-playoffs) stores nothing. A re-run replaces that season's rows, which makes it
idempotent.

## R11 — Where it lives

**Decision:**
- Page: `/leagues/:id/rivalries`.
- Rail destination `rivalries`: group `history`, both sports, one-season href. Not on
  `/schedule`, which is the NBA team-games grid and is basketball-only on purpose
  (`destinations.ts:169`).
- One endpoint, `GET /api/leagues/{sleeperId}/rivalries`, scoped through
  `LeagueMembership.visibleLeague` like every league route (FR-014).
- The payload is small (≤ 16 × 16 cells + ≤ 16 × ~25 week cells) and computed per request
  from two queries. No cache, unless the live check measures it slow.
