# Projection tools: start/sit, waivers, trade analyzer, rate my team, player compare

Status: **design and reconciliation, not built.** 2026-10-05. Gap #9 in
[competitor-gap-research.md](competitor-gap-research.md). Like
`claude/adp-sources.md`, "build none of it" is a legitimate outcome of this doc.
The point is that the decision now rests on a correct premise.

Competitors: every commercial site. FantasyPros (Start/Sit Assistant, Waiver
Assistant, Trade Analyzer, Who Should I Start, ROS rankings), DraftSharks (Start/
Sit Analyzer, Trade Navigator, Free Agent Finder), ffwrapped (Weekly Lineup
Analyzer, Trade Finder, Team Rating, Player Comparison, Player Values),
FantasyCalc / KTC / Dynasty Daddy (trade values), FanScout and Hashtag for NBA.

## The premise was wrong. Measured 2026-10-05

`specs/004-ffwrapped-feature-parity/spec.md:266-270` put all of these out of
scope because "for basketball they need a projection source that does not
exist." `LeagueAnalysisService` says the same thing to users today
(`engine/LeagueAnalysisService.java:448-450`: "… which has no nba
equivalent"). **Both are wrong.**

    GET https://api.sleeper.app/projections/nba/2026/{week}
        ?season_type=regular&position[]=PG&position[]=SG&position[]=SF&position[]=PF&position[]=C

| week | rows | with a stat line |
|---|---|---|
| 1 | 961 | 945 |
| 2 | 1,088 | 1,072 |
| 10 | 1,187 | 1,172 |
| 20 | 1,095 | 1,091 |

Shape: one row **per player per game** (`gp: 1.0`, `game_id`, `date`,
`opponent`, `team`), `company: "rotowire"`, raw stats (`pts`, `reb`, `ast`,
`stl`, `blk`, `to`, `tpm`, `dd`, `td`, `sp`, …). Week 1 has 2–4 rows per player,
matching that week's schedule. The season endpoint `/projections/nba/2026`
returns per-game averages for 529 players (Dončić 31.88 pts).

Why it was missed: `SleeperProjectionClient.POSITIONS`
(`ingest/SleeperProjectionClient.java:31`) is `QB, RB, WR, TE, K, DEF`, so NBA
was never requested.

**Not measured:** accuracy. RotoWire projections are an industry-standard
source, but this repo has no number for how well they predict *these* leagues.
That stays unverified until someone measures it (see "Open questions").

### Follow-up regardless of whether any tool here is built

The `LeagueAnalysisService` reason string is now a false statement on a live
page. Either wire NBA projections into roster projections or reword it to "not
wired up yet". Don't leave "no equivalent" standing.

## What's there now

- `player_projection` (V15): `pts_ppr`, `pts_half_ppr`, `pts_std` columns.
  That's football-shaped. NFL rows are scored by picking the column that matches
  the league's reception points
  (`PlayerProjectionRepository.ScoringKey.forReceptionPoints`,
  `LeagueAnalysisService.java:221`). That's an approximation for any league with
  non-standard scoring, and the one `GameScoringService`'s javadoc warns about
  for NBA (`pts_std` doesn't match either NBA league).
- `GameScoringService.score(scoring, stats)`: one implementation of "what is a
  stat line worth in this league", verified for NBA.
- `SportRules.startingLineup` (`sport/SportRules.java:99`): the optimal-lineup
  rule, both sports. Already used for points-possible.
- `/leagues/:id/analysis`: football-only roster projections.

## Proposed design

### 1. One per-player weekly value, both sports (the foundation)

- Ingest: store projection **stat lines**, not points. Add a
  `stats jsonb` column (V27+ — V26 is the highest on main as of 2026-10-05; append-only) and widen `POSITIONS` per sport via
  `SportRules`. NBA rows also need `game_id`/`game_date`, since there are
  multiple rows per week.
- Value: `projectedWeek(player, league, week)` = Σ over that week's projected
  games of `GameScoringService.score(league.scoring, stats)`. For NFL this
  replaces the `pts_ppr` column pick with exact league scoring. Verify that it
  matches before switching (acceptance #1).
- Schedule-awareness comes for free in NBA. A 4-game week has 4 rows.

Every tool below is a thin view over this one function. Building them on
separate value paths is the two-implementations bug.

### 2. The tools, in build order

| Tool | Definition | Notes |
|---|---|---|
| **Start/Sit** | `startingLineup` over `projectedWeek` for your roster vs your current Sleeper lineup | Show the delta in projected points, not "start X". |
| **Rate my team** | Projected optimal-lineup ROS points, ranked in league | Extends the existing football-only analysis page to NBA. |
| **Player comparison** | Two to four players side by side: realized (last N, season) beside projected (next week, ROS) | Realized and projected in visibly separate columns, always. |
| **Waiver assistant** | Unrostered players by projected ROS lineup gain *for your roster* (`startingLineupValue` with vs without) | Lineup gain, not raw points. A great bench player adds 0. |
| **Trade analyzer** | Each side's ROS projected lineup value before vs after | Same lineup-gain math. No external "trade value". |
| **Trade finder** | Search pairs of 1-for-1 / 2-for-1 trades where both sides' lineup value rises | Last; combinatorial, and the most likely to produce confident-looking nonsense. |

"Your roster" comes from [my-team-dashboard.md](my-team-dashboard.md)'s join.

### 3. Honesty requirements (all tools)

- Every projected number says "RotoWire projection via Sleeper" and the
  `fetched_at`.
- Projected and realized never share a column or a color.
- Injured/out players: Sleeper projection rows may still carry stats for a
  player ruled out after `last_modified`. Show `injury_status` beside any
  recommendation and never recommend starting an Out player.

## Not building

- **External trade values** (KTC, FantasyCalc). They're dynasty-oriented and
  crowd-sourced, and all leagues here are redraft. Lineup-gain from projections
  is the redraft-correct measure.
- **Expert consensus.** That's FantasyPros' business.
- **Our own projection model.** RotoWire's is the input, labelled as such.

## Open questions (Allan's call)

- **Measure projection accuracy against these leagues?** `player_game` holds the
  realized games for 2025, so projected-vs-actual error is computable *if* past
  weeks' projections are still served (unchecked). This is measuring an input,
  not backtesting the draft engine. It's flagged because the no-backtesting
  decision exists, and it's not my call whether it covers this.
- Build any of section 2 at all, or only the foundation plus the
  `LeagueAnalysisService` fix?

## Acceptance criteria

1. For NFL: `GameScoringService` over projected stat lines vs the stored
   `pts_ppr` for the same rows, in a full-PPR league. Report the mismatch count.
   They should match exactly; if they don't, find out why before switching.
2. For NBA: week-1 2026 projections for "Ball Knowers" score per game with that
   league's `dd`/`td`/bonus settings. Spot-check three players by hand.
3. Preference-ordering tests per tool (lessons bug class #1): a waiver candidate
   who'd start must outrank a higher-scoring one who wouldn't.
4. Live check: `/leagues/:id/analysis` renders for the NBA league instead of the
   "no equivalent" message.
