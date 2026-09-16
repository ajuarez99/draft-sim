# League analysis, second pass — the lineup behind the number

Three additions to `/leagues/:sleeperLeagueId/analysis`, chosen 2026-09-16.
`claude/league-analysis.md` is the page's first brief and its non-goals still
hold; this one only says what changes.

The page currently tells a manager their roster projects to 1,683.9 points and
gives them no way to find out which players that is. All three pieces below
are the same complaint answered at three altitudes: one roster, two rosters,
and the roster you play next.

## What is already on the wire

**The starting lineup is already computed and already serialized.**
`LeagueAnalysisService.Starter` carries the Sleeper id, name, position, the
slot filled and the projected points, one entry per started player, and
`AnalysisRosterProjection.starters` is in `web/src/api.ts` with a doc comment.
`LeagueAnalysis.tsx` never reads the field. Piece 1 is therefore mostly a
rendering job, not a data job.

**The fixture list is already ingested.** `league_matchup` exists —
`claude/playoff-odds.md` built it precisely because `roster_week_points` caches
what every team scored and drops who they played — and
`LeagueHistoryIngestService` already walks *unplayed* weeks to store their
pairings. `LeagueMatchupRepository.between(leagueId, season, w, w)` is the
whole read for piece 3. No new ingest, no new table, no new Sleeper endpoint.

## Piece 1 — The lineup drilldown

Each bar in Roster projections expands to the lineup card behind it: every
starter in slot order with the points it contributes, then the bench.

**Slot order, not points order.** The service currently sorts starters by
points descending, and that has to change, because a lineup card that reads
`RB, WR, QB, FLEX, TE…` is not a lineup card. It cannot simply use the order
`startingLineup` returns either: that iterates `LeagueSettings.dedicatedStarters()`,
which is a `Map.of(...)` and therefore has **no specified iteration order** —
`Map.of`'s ordering is salted per JVM run, so the wire order today is
reproducible only because the service re-sorts it away. Starters are now
ordered by where their slot first appears in the league's own
`rosterPositions` (`QB, RB, RB, WR, WR, TE, FLEX, FLEX, K, DEF`), ties broken
by points. That is the league's own lineup card, read off the league.

**The bench is new, and it is what makes `missing` legible.** The page says
"6 unprojected" and names nobody. `RosterProjection.bench` now carries every
rostered player who did not start, with the same projected points, sorted
descending — so a zero at the bottom of the bench *is* the IR explanation, in
place, instead of a count in a tooltip. `missing` stays a count: it also
counts rostered ids with no row in `player` at all, which have no name to
show, and collapsing those two into one list would claim a name the DB does
not have.

## Piece 2 — Head to head

Two rosters, slot against slot, over the same rest-of-season window. Pure
frontend: every number it needs is already in the `projections` block once
piece 1 ships the lineups.

It defaults to **your** roster against the league leader, resolved through the
signed-in Sleeper id the app already carries (`user.ts`'s `currentUserId`,
matched to `managerId`). A signed-out reader gets rank 1 against rank 2.

Per-slot rows carry both sides and the margin; the panel foots with the
position-group split and the overall margin. Nothing is a new computation —
this is the same lineup two ways, which is the point.

## Piece 3 — Week N matchups

The next unplayed week's real pairings, each side projected **for that week
alone**.

**A weekly lineup is not a slice of the rest-of-season lineup.** The starter a
roster projects highest over thirteen weeks is not necessarily the one it
starts in week 2 — a bye, or a one-week injury, moves it. So the week's
projection re-runs the same greedy assembly against a one-week points map,
rather than dividing anything by thirteen. Concretely that is
`totalsByPlayer(sport, season, w, w, key)` and a second `startingLineup` pass;
the existing block keeps `[fromWeek, toWeek]` untouched.

**One assembler, called twice.** The per-roster loop (Sleeper rosters ->
`RosterState` -> `startingLineup` -> group by position) is extracted to take a
points map and is called for both windows. Writing a second loop valued weekly
would be two implementations of "what does this roster start", which is the
bug this repo has now shipped three times under three names.

`fromWeek` is the week both blocks agree on: `lastScored + 1`, taken from the
rows this DB actually holds rather than Sleeper's `last_scored_leg`, same as
the window the page already prints.

### What it refuses, and why it says so

Same `available`/`reason` convention as the two existing blocks, and the order
of the gates is deliberate — each one is checked before the thing it would
otherwise produce a misleading reason about:

1. Not football -> the projections reason (the source is `pts_ppr` and friends).
2. Projections unavailable -> say that, do not re-derive it.
3. No stored pairing for `fromWeek` -> name the ingest that stores one.
   Sleeper answers an unscheduled week with every `matchup_id` null, and
   `LeagueMatchupRepository` deliberately does not count that as cached, so
   this state is reachable and is not an error.
4. A `matchup_id` with one roster in it (odd team count, a bye) renders as a
   bye rather than being dropped — a manager with no game next week needs to
   be told that, and dropping the row tells them nothing.

## What live verification found

Built and verified 2026-09-16 against the real league ((Foot) Ball Knowers 2026,
sleeper `1346366555759341568`) in a real browser. 399 backend tests / 0 failures
/ **0 skipped** (the skip count is the check that the ITs actually ran — see
`claude/lessons.md`), 236 frontend.

Measured: the whole response, both windows and six pairings, in **475 ms**. The
week-2 schedule was already in `league_matchup` (12 paired rosters for every
week 1-6), exactly as the brief predicted, so piece 3 shipped without an ingest.

Four things a green suite did not catch, all of them in the page rather than
the engine:

**The injury tag was painted `--crimson`, which this stylesheet reserves for
"this is yours".** Every IR and QUESTIONABLE badge was wearing the colour that
means "you" on the board, the on-clock strip, the power-rankings ladder and the
`.mine` row six pixels away. It is now a colourless outline: the injury tag is
not a severity scale this app can rank, so the WORD carries the meaning and
nothing else does. The long words are also abbreviated to the codes every
fantasy site uses (Q, D), with the full word in the `title` so shortening it
never costs the reader the fact.

**The head-to-head margin read `-144.6 for kieriskash`.** The number was signed
against the left roster and the label named the winner, so a number and its own
label argued with each other whenever the right side was ahead. It prints the
magnitude and the name now. Same lesson as
`feedback_label_the_axis_spell_out_the_number`: the sign was an encoding nobody
had been told how to read. The position-group column below it kept its signed
form — it is genuinely a left-minus-right column — and now says so in a line
above it instead of leaving the reader to infer the direction from one row.

**Both top scorers on a matchup card sat in one shared row.** "QB Brock Purdy
20.8 · QB Jalen Hurts 20.9" under two managers, with nothing but left-to-right
order saying which was whose. Each now sits under the manager it belongs to.

**A finished season headed its own refusal "Week 18 matchups".** `week` is
`lastScored + 1`, which for a season with 17 scored weeks is 18 — a week the
league does not play, over a reason that says so. The heading names a week only
when there is one to name.

Also measured, and working as the brief predicted:

- **The gates say actionable things.** A basketball league gets the
  football-only reason for both blocks rather than a command that answers 400.
  A finished 2025 season refuses both projection blocks and still ranks its 17
  scored weeks.
- **`missing` is legible now.** kieriskash's "1 unprojected" is Zach Charbonnet
  at 0.0, tagged PUP, at the bottom of the bench — the count and the name are
  the same fact, and only one of them can be acted on.
- The lineup card comes out in the league's real order (QB, RB, RB, WR, WR, TE,
  FLEX, FLEX, K, DEF) with both FLEX pills coloured as running backs, which is
  the slot-vs-position encoding doing its job.
- Mobile at 375px: no horizontal overflow, the drawer stacks to one column.

## Non-goals

- **A win probability on the matchup card.** The margin is two projected
  totals subtracted, and that is all it is. Turning it into "68% to win" needs
  a variance model per roster; `PlayoffOddsSimulator` has one, and wiring it in
  is its own piece of work with its own gate on scored weeks. A number that
  looks like a probability and is really a subtraction is worse than the
  subtraction.
- **Every remaining week's matchups.** One week. Thirteen weeks of pairings
  against thirteen one-week lineup assemblies is a different page.
- **Basketball.** Unchanged from the first brief: the projection source is
  football's.
