# League analysis — the ffwrapped dashboard, on our own data

A new page, `/leagues/:sleeperLeagueId/analysis`, holding the pieces of
[ffwrapped's power-rankings view](https://ffwrapped.com/?leagueId=1346366555759341568&view=power-rankings)
this app does not have. Measured live 2026-09-15 against the real league
(`(Foot) Ball Knowers` 2026, sleeper `1346366555759341568`, league id 4).

ffwrapped shows four things. **Three of them are new; the fourth we already
ship.** Their bump chart with a Preseason/Week toggle is the same object as
Power Rankings' own bump chart, so this page links to it rather than drawing a
second one. Building it twice is the failure mode this repo keeps re-learning —
the plain-snake default has now shipped in three separate implementations of one
rule.

## Why a new page and not a fourth tab

`web/src/pages/PowerRankings.tsx` is **1504 lines** and already carries four
ladder modes, a vote board, a bump chart and the Realized popup. Two of the
three pieces below (roster projections, position-group heatmap) have no existing
analog anywhere in the app and read a data source nothing else reads.

The split is not arbitrary. **Power Rankings answers "who is best"** — it is
about ordering managers, and every one of its modes is an ordering. **League
analysis answers "what is this roster made of"** — it is about composition, and
none of its content is an ordering of the league except as a side effect.

## The data source, measured

Sleeper serves weekly projections from an endpoint that is not in their public
docs and carries no version prefix:

    GET https://api.sleeper.app/projections/{sport}/{season}/{week}
        ?season_type=regular&position[]=QB&position[]=RB&position[]=WR
        &position[]=TE&position[]=K&position[]=DEF&order_by=ppr

Measured 2026-09-15 for `nfl/2026`, weeks 2, 8, 14, 15 and 18:

| fact | value |
|---|---|
| HTTP | 200, no auth, no key |
| rows per week | ~3,300 |
| rows carrying a real `pts_ppr` | 445–511 (the rest are stubs with only `adp_dd_ppr`) |
| payload | ~2.1 MB per week |
| latency | 270–320 ms per week |
| source | `company: "rotowire"` |
| key | `player_id` — **the same Sleeper id our `player.sleeper_id` already stores** |

Available stat keys include `pts_ppr`, `pts_half_ppr` and `pts_std`, plus the
component stats (`rec`, `rec_yd`, `rush_td`, …). Weeks past the league's playoff
start return data too, which we deliberately do not use — see "rest-of-season"
below.

**Coverage against the real league: 173 of 180 rostered players (96.1%) have a
week-2 projection.** The seven without are not a data gap: six are IR / Out /
PUP (Tank Dell, A.J. Brown, Jordyn Tyson, Ja'Kobi Lane, De'Zhaun Stribling, Zach
Charbonnet) and Josh Jacobs carries no `pts_ppr` in *any* week probed. A player
with no projection contributes 0 to that week and must not be special-cased into
something friendlier — a roster whose WR1 is on IR really is projected lighter.

### Do not hardcode `pts_ppr`

League 4's `scoring_json->>'rec'` is `1.0`, so PPR is the right column *for this
league*. Picking it in code as a constant asserts a rule while looking like it
reads a value — the shape of bug this repo has now shipped three times. The stat
key is derived from the league's own scoring:

    rec >= 1.0  -> pts_ppr
    rec >= 0.5  -> pts_half_ppr
    otherwise   -> pts_std

with the resolved key carried in the response so the page can say which one it
used, rather than leaving the reader to assume.

## The blocker, and it is data, not code

**`roster_week_points` has zero rows for league 4, and every `roster_season` row
for it reads 0-0-0 with `points_for` 0.00.** Historical seasons are fine (league
5 / 2025 has 17 weeks, league 2 / 2025 has 18).

Sleeper has the data: `GET /league/1346366555759341568/matchups/1` returns all 12
rosters scored, high 157.4, low 90.76. The 2026 season simply has not been
through `POST /api/ingest/league-history/{sleeperLeagueId}` since week 1 was
played. **Run that first** — piece 1 reads exactly these two tables and nothing
else, so on today's database it would render twelve identical zeroes and look
like a formula bug.

This is the failure the playoff-odds work hit, and it is solved the same way:
never write a number before the week that feeds it is scored.

## Piece 1 — Ranking score

ffwrapped's composite, shown inline on their own page:

    ((avgWeeklyScore * 6) + ((highScore + lowScore) * 2) + (winPct * 400)) / 10

then normalized to a 1–100 scale centered on the league at 50. Every input comes
from tables we already have: `roster_week_points.starters_points` gives the
average, high and low; `roster_season` gives the record.

**It needs a minimum-weeks gate.** After one scored week `high == low == avg` and
the formula collapses to a rescaled version of that single score — a
confident-looking 1–100 number built on one game. Below three scored weeks the
section renders the count and what it is waiting for, not a ladder. This is the
"--" discipline from `claude/playoff-odds.md`, not a new idea.

Deliberately **not** a fifth `power_ranking.kind`. It is a different question
asked on a different page, and adding a kind means a migration, a compute path, a
snapshot row and a tab on a file we just decided not to touch. It is computed on
read here; if it later earns a place on the ladder, promoting it is a small
change and demoting it after the fact is not.

## Piece 2 — Roster projections

Horizontal stacked bars, one per manager, rest-of-season projected points split
by position group.

**Rest-of-season is bounded by the league, not the calendar.** League 4 has
`playoff_week_start: 15`, so rest-of-season is weeks `[currentWeek, 14]` — 13
weeks at today's `week: 2`. The endpoint happily serves weeks 15–18; using them
would credit a roster for games the league does not play.

### The reuse that matters

`FootballRules.startingLineupValue` (`sport/FootballRules.java:220`) already does
the greedy lineup assembly this needs — dedicated slots first, then FLEX from the
leftovers, with the comment explaining why greedy is optimal here.
`PowerRankingService.computePreseasonBaseline` already calls it over a roster's
*entire* player pool rather than trusting Sleeper's `starters` snapshot, which is
the behavior we want.

Two things stop it being used as-is: it is hardwired to `value(BoardEntry)`
(ADP-derived, and `FootballRules`' own javadoc says "swap `#value` for a
projection lookup when one exists" — this is that moment), and it returns a
scalar where the bars need a per-position breakdown.

So: **generalize it, do not copy it.** A lineup assembly that takes the value
function as a parameter and returns the chosen slots, with the existing scalar
falling out as a sum over them. Writing a second greedy assembler valued in
projected points would be two implementations of one rule.

## Piece 3 — Position group rankings

Teams × position groups, each cell that team's rank within the league at that
position group. Falls out of piece 2's breakdown at no extra data cost — it is
the same matrix read down the columns instead of across the rows.

Each cell carries the rank *and* the projected points it came from. One encoding
per mark, exact value beside it — not a color the reader has to decode against a
legend.

## Storage

Projections are **not** final the way results are, so they cannot use
`roster_week_points`' "fetch once, never refetch" rule — next week's projection
moves every time somebody tweaks an ankle. A new `player_projection` table keyed
`(sport, season, week, sleeper_player_id)` with a `fetched_at`, refreshed when
stale rather than on every page load. ~500 rows per week, ~6,500 for a full
rest-of-season refresh: trivial to store, but ~27 MB and ~4 s to fetch, so it
belongs behind an explicit refresh like the existing compute endpoints, never
inline in a GET the page makes on mount.

## Phases

- **0.** Re-ingest league 4's history so weeks are scored. No code. Blocks piece 1.
- **1.** `player_projection` table + ingest service + the scoring-key derivation.
- **2.** Backend `GET /leagues/{sleeperId}/analysis`: ranking score (gated),
  roster projections by position group, the position-group matrix.
- **3.** Generalize `startingLineupValue` over a value function; the existing ADP
  caller keeps identical output. Parity is checkable —
  `claude/scripts/football-parity-hash.py`.
- **4.** The page, its route, its rail row (the rail gates History and Power
  rankings on `d.sport === 'nfl'` at `LeagueRailSection.tsx:60`).
- **5.** Tests, and a live pass — this repo's last three features each shipped a
  bug a green suite did not catch.

## What live verification found

Built and verified 2026-09-15 against the real league and a real browser. Four
things a green suite did not catch, recorded because three of the last three
features here shipped a bug of exactly this kind.

**Ties in the position matrix were numbered as if they were not ties.** Three
rosters project to the same 81.3 at K, and the first cut numbered them 4th, 5th
and 6th — a rank asserting a difference the number printed beside it denies. The
fix was not to write tie handling: `engine/Ranker.java` already does standard
competition ranking and exists specifically because the *second* copy of that
rule was wrong (it was direction-coupled and silently inverted). Both this
page's rankings now go through it. **Reaching for the shared one is the lesson;
the tie was only what exposed not having.**

**The naive generalization of `startingLineupValue` would have started the
wrong players.** `RosterState.at()` returns a position's players ordered by ADP,
and the existing greedy takes the first *n* as "the best" — true only because
board value is monotone in ADP, and false for projected points, where a 3rd-round
WR can outproject a 2nd-round one. `startingLineup` therefore sorts by the
supplied value function before filling a slot. Under `value` that sort is a
no-op, which is what makes the existing caller provably unchanged rather than
probably unchanged; `FootballRulesTest` pins both halves.

**The page rendered initials where the app ships photos.** The first cut passed
`avatarId={null}` to `Avatar`, quietly reversing the Sleeper avatar import.
`avatarId` now rides through the wire shape from `roster_season`.

**A reason the reader could not act on.** A basketball league reaching the
endpoint was told to run `POST /api/ingest/projections?sport=nba…` — an endpoint
that answers 400 by design. The sport is now checked *before* the cache, so it
says projections are football-only instead of prescribing an impossible command.

**The football parity check was already unrunnable, and not because of this
work.** Phase 3 owed it a run. Both baselines come back MOVED — but the engine
is deterministic (same seed, same hash, twice), `startingLineup`'s only callers
are `PowerRankingService` and `LeagueAnalysisService` (neither is in `/api/sims`),
and the board the engine reads was rebuilt on 2026-09-13, after the baselines
were captured on 2026-09-09: **791 of 850 entries changed adp, 18 players left
the board, largest move 418 picks.** The hashes could not have matched whatever
the code did.

`claude/scripts/football-parity-hash.py` now fingerprints the board it ran
against and exits 2 with INCONCLUSIVE instead of 1 with MOVED when the board has
drifted. The docstring already warned that hashing raw bodies "trains you to
ignore the one check standing between a seven-phase refactor and a silent
regression" — it guarded `name` and `team` and not the board, which is the input
that actually decides picks. **The expected hashes were deliberately NOT
refreshed**: they are the only record of what the engine used to decide, and
re-baselining is a decision to make on purpose, in its own commit. Until someone
does, HANDOFF's acceptance criterion 1 cannot clear a refactor.

Also measured, and working as the brief predicted:

- **The min-weeks gate is live, not theoretical.** The 2026 season has exactly
  one scored week, so the page currently explains itself instead of ranking.
  Run against the 2025 season (17 weeks) the ladder renders correctly:
  popsharky 100.0 at 10-4 and 145.2/week, BamAddABio 4.5 at 2-12.
- **A finished season refuses the projections half** for its own reason
  ("weeks run to 14, and 17 are scored") rather than rendering empty bars.
- Roster projections for the live league: 12 rosters, weeks 2–14, full PPR
  derived from the league's own `rec: 1.0`, in **450 ms**.
- The rest-of-season ingest is 13 calls, 6,045 rows, **15 s**; a second run
  inside the staleness window fetches nothing.

One property worth knowing about ffwrapped's formula, pinned in
`LeagueAnalysisServiceTest`: a perfect record is worth exactly 40 raw points,
which is also what 40 points per week of scoring is worth. An undefeated team
averaging 100 and a winless team averaging 140 tie exactly. That is the
formula's central claim, and it is the thing to re-examine first if the ladder
ever looks wrong.

## Non-goals

- A second bump chart. Power Rankings has one; link to it.
- A fifth `power_ranking.kind`.
- Basketball. `pts_ppr` is a football stat key and the NBA leagues have no scored
  2026 week at all. The sport seam exists; nothing here fills it.
