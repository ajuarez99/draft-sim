# Playoff odds: making `makesPlayoffsPct` real

Status: **planned, not built** (2026-09-14). The wire field, three render sites
and a ladder column already exist and have always shown `--`; this brief is the
backend that fills them in, and the UI decisions that come with a number that is
finally not null.

## What is there now

| Piece | Where | State |
|---|---|---|
| `makesPlayoffsPct` on the entry type | `web/src/api.ts:719` | client-side type only — **no backend field exists** |
| `pctLabel()` | `PowerRankings.tsx:259` | every site degrades to `--`; "no data" and "0%" can never be confused |
| Hero "MAKES PLAYOFFS" stat | `PowerRankings.tsx:787` | renders `--` |
| Your-team strip "`--` to make it" | `PowerRankings.tsx:806` | renders as a broken fragment today |
| Ladder column "Makes playoffs" | `PowerRankings.tsx:928` | Box score + Commissioner only; a full column of `--` with an empty bar |
| Footnote "Playoff odds come from simulating the rest of the season" | `:1054`, `:1360` | **false today** — nothing simulates anything |

The League-vote ladder no longer has this column at all: its slot was recycled
into the ballot-spread row, and as of `a8afa79` that cell is the "Ballot range"
pill. Putting odds back on that tab is a layout decision, not a revert — see
[Where the number goes](#where-the-number-goes).

## The one real gap: we do not store the schedule

Everything else the simulation needs is already in Postgres. The schedule is not:

- `roster_week_points` (V5) stores `starters_points` and `players_points` per
  (league, season, week, roster) but **drops `matchup_id`** — so we know what
  every team scored and not who they played.
- `LeagueHistoryIngestService.ingestWeeklyPoints` only walks weeks
  `1..last_scored_leg`, so nothing about *future* weeks is ever fetched.

Verified live against (Foot) Ball Knowers (`1346366555759341568`) on 2026-09-14:
`GET /league/{id}/matchups/5` for an unplayed week returns all 12 rosters with
`matchup_id` set and `points: 0.0`. **The full remaining schedule is one call per
week**, and the pairing is the `matchup_id` grouping, exactly as it is for a
played week.

Also verified on that league: `playoff_teams: 6`, `playoff_week_start: 15`
(regular season = weeks 1-14), `playoff_seed_type: 0`, `league_average_match: 0`,
`divisions: null`. All of it already lands in `league.settings_json` (V1).

## Storage (V13)

```sql
-- The schedule, including weeks not yet played. Separate from
-- roster_week_points on purpose: that table is a cache of RESULTS keyed to
-- last_scored_leg, this one is a cache of FIXTURES that extends past it.
create table league_matchup (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    roster_id   int    not null,
    matchup_id  int,               -- null = no game that week (bye/odd count)
    unique (league_id, season, week, roster_id)
);
create index league_matchup_lookup_idx on league_matchup (league_id, season, week);

-- One odds snapshot per (league, season, week), same snapshot discipline as
-- power_ranking: "up 8 points since last week" needs history, and the inputs
-- (records, the schedule, a team's own scoring) move underneath you.
create table playoff_odds (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    iterations  int    not null,
    model       text   not null,   -- version tag, e.g. 'shrunk-normal-v1'
    created_at  timestamptz not null default now(),
    unique (league_id, season, week)
);

create table playoff_odds_entry (
    id             bigserial primary key,
    odds_id        bigint not null references playoff_odds (id) on delete cascade,
    roster_id      int    not null,
    made_pct       numeric(5,2) not null,
    bye_pct        numeric(5,2),          -- null unless the format has byes
    seed_one_pct   numeric(5,2),
    proj_wins      numeric(5,2) not null,
    proj_points    numeric(9,2) not null,
    unique (odds_id, roster_id)
);
```

Storing `proj_wins`/`proj_points` costs nothing and is what a "projected finish"
column or a future story card would need; `bye_pct`/`seed_one_pct` are nullable
because not every league has a bye.

## The model

**Strength.** For roster *i*, from that season's `roster_week_points`:

- `mu_i` = mean weekly `starters_points`, shrunk toward the league mean:
  `mu_i = w*mean_i + (1-w)*leagueMean`, `w = n/(n+4)`.
- `sigma_i` = per-roster stdev shrunk the same way toward the league-wide stdev
  (a 1-2 game sample has a meaningless stdev of its own).

`k = 4` is the knob: at n=4 a team is half its own average, at n=12 it is 75% its
own. **At n=0 every team is the league average and the odds differ only by
schedule and standings — which is the truth, not a placeholder.** No mapping from
the week-0 board baseline into points-per-week; that mapping would be invented,
and inventing it is exactly the kind of thing this page refuses to do elsewhere.

**Simulation.** Per iteration:

1. Seed each roster with its Sleeper record and `points_for` from `roster_season`
   — Sleeper's own numbers, not a recount from matchups. A recount silently
   disagrees in median-scoring and hand-corrected leagues.
2. For each remaining regular-season week (`currentWeek..playoff_week_start-1`),
   for each `matchup_id` pair, draw both scores from `Normal(mu, sigma)` clamped
   at 0, award the win to the higher score, accumulate points. A roster with a
   null `matchup_id` that week does not play.
3. If `league_average_match == 1`, each team plays a second "game" against that
   week's simulated median — the setting exists on every league object and
   silently doubles the number of games when it is on.
4. Sort by wins (ties = 0.5), then `points_for`. This is Sleeper's default
   standings order; it is **not** every league's seeding rule (divisions, H2H
   tiebreaks, `playoff_seed_type != 0`), so Honesty below says what we do about it.
5. Top `playoff_teams` made it; track seed 1 and (when the bracket has them) byes.

**Cost.** 10k iterations x 12 rosters x ~13 weeks is about 1.6M draws — tens of ms
in Java, nothing like the draft sim's 500-iteration cap
(`claude/reactive-resimulation.md`). Compute on the existing commissioner
`POST /power/compute` path and on first read of a week with no snapshot; never on
every page load.

## Honesty rules

These are the same rules that made `pctLabel()` exist, applied to a number that
is now real:

- **Never show odds for a league we cannot seed correctly.** If
  `settings.divisions` is set, or `playoff_seed_type != 0`, we do not model that
  league's tiebreaks — store nothing and keep rendering `--`. A wrong 78% is
  worse than a dash.
- **Say what the number is made of.** The footnote gets rewritten from the
  current false sentence to the actual one, including the iteration count and
  how many games of scoring the strength estimate is standing on
  ("10,000 seasons, from 2 weeks of scoring" reads very differently from
  "from 11 weeks", and it should).
- **0% and 100% are real answers** once a team is mathematically in or out, and
  must not be confused with the dash's job — `pctLabel` already keeps them
  distinct.
- Regular season over (`currentWeek >= playoff_week_start`): the question is
  answered, not simulated. Store the actual outcome as 0/100.

## Where the number goes

The League-vote ladder is at seven columns and was just cleaned up precisely
because its right end was unreadable; an eighth column undoes that work.

**Recommended:** odds ride in the **Record** cell, on all three tabs — `0-0`
with a small tinted odds pill under it. Record and odds are both "how this
team's season is going", neither is about the ranking mode, the column already
exists on every tab, and it costs zero horizontal space. The standalone
"Makes playoffs" column on Box score/Commissioner then goes away rather than
being a second place the same number lives (the multi-sport lesson from
`claude/multi-sport-landmines.md`: two implementations of one rule is the class
of bug to avoid, and two renderings of one number is the same shape).

**Alternative:** keep the dedicated column on Box score/Commissioner, show
nothing on League vote. Cheaper, but then the page's most-used tab is the one
tab without the number.

Either way the hero stat and the your-team strip (`-- to make it`) light up for
free — they already read the field.

## Sport gate

`PowerRankingService.nflState()` hardcodes `sleeper.state("nfl")`, and
`LeagueHistoryController:421` already refuses basketball for the computed mode
because of it. Playoff odds inherit that gate — NFL first. When basketball comes,
the fix is to make *that* method take the league's sport (`/state/nba` returns the
same `week`/`leg`/`season` shape — verified 2026-09-14), **not** to add a second
odds path for NBA.

## Not building

- Anything player-level: injuries, bye weeks, trades, waiver adds, or a roster's
  remaining strength of schedule by opponent quality. The model is team-level
  scoring, and the footnote will say so.
- The playoff bracket itself (championship odds). `bye_pct`/`seed_one_pct` are
  stored so it can come later without a migration.
- Backfilling odds for past weeks/seasons — snapshots start the day it ships.

## Acceptance criteria

1. `GET /leagues/{id}/power` returns a real `makesPlayoffsPct` per entry for an
   NFL league mid-season, and the three existing render sites show it with no
   client change beyond the Record-cell layout.
2. The odds for a league sum to `playoff_teams * 100` (within rounding) — the
   single cheapest check that the seeding step is not double-counting.
3. A league with `divisions` set stores no snapshot and still renders `--`.
4. Re-running compute for the same week replaces that week's snapshot and leaves
   earlier weeks untouched.
5. The footnote names the iteration count and the weeks of scoring behind it.
6. Backend suite passes with Postgres actually up — `claude/lessons.md`: BUILD
   SUCCESSFUL with 52 skipped means the ITs never ran.

## Open decisions for Allan

1. **Record cell vs dedicated column** (recommendation above).
2. **`k = 4`** for the shrinkage. Higher = odds move slower early; lower = week 2
   already has opinions.
3. **Division leagues:** dash them (recommended), or model them and accept that
   the tiebreak is a guess?
