# A full league suite, built on top of draft-sim

## Phase A — built and verified live, 2026-09-07

`claude/plan-review-league-suite.md`'s six amendments are all in. Built via
`LeagueHistoryIngestService` (four new `SleeperClient` methods, `V5` migration:
`roster_season`, `roster_week_points`, `power_ranking`, `power_ranking_entry`)
and `PowerRankingService`, behind `LeagueHistoryController`
(`/api/leagues/{id}/history`, `/api/managers/{id}/history`,
`/api/leagues/{id}/power` + `/power/compute` + `/power/commissioner`) and two
new frontend routes, `/leagues/:id/history` and `/leagues/:id/power` (a
hand-rolled SVG bump chart, mode toggle, per-team transpose view, and a
commissioner-ranking form), plus `/managers/:id/history`. 16 new backend
tests (unit + a `LeagueHistoryContaminationIT` extending
`MockDraftContaminationIT`'s pattern); backend suite 250/250, frontend 48/48,
`tsc -b` and `vite build` clean.

**Verified against real data, not just unit tests** -- Ball Knowers 2025
(`1254190892974084096`): standings ingested and cross-checked byte-for-byte
against `GET /league/{id}/rosters` and `/league/{id}` directly (wins, losses,
points, champion all match); market-value and realized rankings computed for
week 17 and rendered in a real browser through `vite dev`; a commissioner
ranking submitted through the real form and reflected back on refetch.

**Two real bugs found by actually running it, both fixed:**

1. **`starters_points` is a per-slot array, not a roster total.** The ingest
   originally read it as a scalar, which silently produced `0.0` for every
   roster's realized score every week -- a well-formed number (per
   `claude/lessons.md`'s "well-formed and wrong" class of bug) that only
   showed up once real weeks were rendered in the chart. Fixed to read
   `points` instead, verified equal to `sum(starters_points)` against real
   week-1 data.
2. **`(Double) rs.getObject(...)` on a `numeric` column throws
   `ClassCastException` at runtime** (pgjdbc returns `BigDecimal`, not
   `Double`) -- compiled fine, 500'd on the first real request. Same bug
   class as `claude/lessons.md`'s JDBC-bind entries, just on the read side.
   Fixed in `RosterSeasonRepository` and `PowerRankingRepository` with
   `rs.getObject(i) == null ? null : rs.getDouble(i)`.

**Design decisions made during the build, not fully specified by the plan:**

- **Market value scores the ENTIRE roster's optimal starting lineup**
  (`SportRules.startingLineupValue`, the engine's own lineup-slot logic),
  not Sleeper's own `starters` snapshot -- this is what actually answers "not
  the whole roster, but don't trust a manager to have set his lineup" without
  needing a second definition of "best lineup." An OUT/Doubtful starter is
  excluded before scoring (Phase A AC4); a rostered player absent from this
  app's board is excluded and both exclusions are named in the entry's `note`,
  never silent.
- **Realized is cumulative average of each week's own recorded starting-lineup
  total**, including playoff/consolation weeks (`last_scored_leg`, not
  `playoff_week_start`) -- the plan's finding 2 amendment suggested separating
  the two; not built, since Ball Knowers 2025's playoff weeks are real
  consolation-bracket games with real non-zero scores, not the bye-week-zero
  case the finding worried about. Worth revisiting if a league's non-playoff
  teams ever go genuinely idle in weeks past `playoff_week_start`.
- **Champion only** (`metadata.latest_league_winner_roster_id`), not full
  placement (2nd, 3rd, ...) -- the plan's own suggested shortcut. Full
  placement needs the whole `winners_bracket` tree and Phase A's acceptance
  criteria never asked for it.
- **Transactions ingest was not wired up** -- `SleeperClient.transactions()`
  exists (cheap to add, matches the plan's four-method list) but nothing
  calls it. Still just the trade-history idea from `ideas/`, not scheduled.

**Not built (Phase B, unchanged from the plan):** league-member ballots
(mode 2), magic-link auth, generic polls.

---

Design note, 2026-09-07. **Promoted from `ideas/` to a real plan the same day**,
after its two blocking open questions were measured rather than assumed.
**Nothing here is built** — planning only, per the `AGENTS.md` convention where
that is a legitimate outcome for a doc like this.

## The pitch

draft-sim currently answers one question: "what would this draft look like." The
bigger idea is to grow it into the thing a league actually opens all season --
history and polls first, with room to keep adding once those exist:

- **League history.** Standings and records across seasons, not just the current
  one. Past drafts already ingested (`draft` table) are the obvious seed --
  final rosters, who reached, who got value, per the same `valueDelta` /
  `positionalPrior` math the simulator already uses. A "how did last year's mock
  compare to what actually happened" view falls out of data already in the
  database with no new ingest.
- **Polls.** Weekly power rankings, prediction polls, "grade this draft" votes.
  This is the one genuinely new category -- it needs manager-facing input, not
  just Sleeper ingest, which means auth-for-humans (see Open questions) rather
  than the single-user assumption the app runs on today. **Power rankings are the
  concrete first one, and they are specced below** -- see "Power rankings, three
  ways".
- **Whatever else a league wants**, once the first two exist and there's a
  container to hang it on: side bets, trade history, a manager leaderboard for
  "biggest reach of the year." Deliberately not speccing these now.

---

## Measured 2026-09-07 — two of the three open questions are now answered

### 1. There is no Sleeper OAuth. This is settled, from Sleeper's own docs.

The original open question offered "Sleeper OAuth (if it exists) vs. a bespoke
login vs. something lighter", marked **unresearched**. Researched now.
`docs.sleeper.com` states it directly:

> "We do not perform authentication as our API is read-only and only contains
> league information."

So the appealing option is gone: **there is no way to let a manager prove they are
themselves via Sleeper.** Any multi-user feature needs a bespoke identity of its
own, mapped onto the existing `manager.sleeper_user_id` (`V1__init.sql:38-42`) by
hand or by an invite the commissioner sends. That is a real cost, and it lands
entirely on the polls half of this doc — which is the argument for doing history
first, unchanged but now evidence-backed rather than instinct.

The same page also scopes the API as **"free to use for non-commercial purposes."**
Nothing here is commercial, but a league suite is the first thing in this repo that
could plausibly grow an audience, so record it before rather than after.

### 2. The read-only history slice needs no new ingest path. Verified live.

Every endpoint the history half wants was called against (Foot) Ball Knowers 2025
(`1254190892974084096`), unauthenticated, this session:

| endpoint | what came back | why it matters |
| --- | --- | --- |
| `/league/{id}/rosters` | 12 rosters; `settings` carries `wins`, `losses`, `ties`, `fpts`, `fpts_decimal`, `fpts_against`, `ppts` | **standings are a read, not a computation** — no need to sum matchups |
| `/league/{id}/winners_bracket` | bracket rows (`m`,`r`,`w`,`l`,`t1`,`t2`,`t2_from`) | final placement / who won it |
| `/league/{id}/matchups/{week}` | per-roster `points`, `starters`, `players`, `matchup_id` | weekly detail, if ever wanted |
| `/league/{id}/transactions/{week}` | waivers/trades with `creator`, `settings.seq` | trade history, later |
| `/league/{id}` | `metadata.latest_league_winner_roster_id`, trophy fields, `status: complete` | champion without parsing the bracket |
| `previous_league_id` chain | already implemented — `SleeperClient.leagueChain` (`SleeperClient.java:64-73`) | multi-season walk exists today |

So open question 3 ("unverified whether that's a small extension or a new ingest
path") resolves to **small extension**: four new methods on `SleeperClient`
(`rosters`, `winnersBracket`, `matchups`, `transactions`, alongside the seven at
`SleeperClient.java:25-57`), one new table, and the season walk already in the
client. No auth, no new crawl concept, no change to anything the simulator reads.

---

## Why this is bigger than it looks

Everything shipped so far is single-user: Allan runs ingest, Allan looks at the
board, Allan runs mocks. A league suite that other managers open means:

- **Multi-user auth**, not the current binary `API_TOKEN` on/off switch
  (`ApiTokenFilter.java:26`, `WebConfig.java:36-49` — one shared bearer token that
  is either on for everything or off for everything). Each manager needs their own
  identity to vote in a poll or see "your" history. Per the measurement above, that
  identity cannot come from Sleeper.
- **Write paths with real users behind them.** Everything today is read-mostly
  (ingest writes, humans only read) except mock-draft sessions, which are
  already scoped to one browser session with no login. Polls are the first
  feature where arbitrary league members submit data that has to be
  attributed and can't be re-run like a sim.
- **A reason for 13 other people to open the app at all.** Right now the only
  user is Allan. Nothing about the data model says this doesn't work for a
  whole league, but nothing about the product does either -- there's no invite
  flow, no per-manager view, no notion of "your" anything.

None of this is a reason not to do it. It's the reason it's a two-phase plan and
not one feature: it's a different kind of app (multi-user, always-on, social)
layered on top of a simulator (single-user, on-demand, analytical), and that
seam should be crossed deliberately, not backed into feature by feature.

## What already exists that this would build on

- `draft`, `league`, `manager`, `manager_profile` and `board` tables already hold
  the history a "league history" view would read (`V1__init.sql:38-107`). No new
  ingest for the first version — just new read paths and UI over data already there.
- `draft_pick.adp_at_time` (`V1__init.sql:71-83`) is the denormalized board
  position at the time of each pick. **That column is what makes this app's history
  view different from Sleeper's own** — "biggest reach of 2025" and "best value of
  2025" are one query over a column that already exists, using the same reach
  definition the profiles are fit from.
- The manager-tendencies work already put real managers, not just bots, into the
  product (`/managers`, `App.tsx:67`). History and polls are both "more surface
  area for the real managers," same direction.
- `SportRules` seam exists for basketball — a league suite is sport-agnostic in a
  way the simulator mostly is too, worth keeping in mind if this gets built before
  basketball support does.

---

## Proposed design

### Phase A — league history, read-only, no auth (do this first)

**Ingest.** Four new `SleeperClient` methods (above). One new migration — the next
free version is **V5** (`V4__mock_from_draft.sql` is the highest on disk). Shape:

    create table roster_season (
        id            bigserial primary key,
        league_id     bigint not null references league (id) on delete cascade,
        manager_id    bigint references manager (id),      -- nullable: orphan rosters exist
        roster_id     int    not null,                     -- Sleeper's own, for bracket joins
        wins/losses/ties        int,
        points_for/points_against/points_possible numeric(7,2),
        final_placement         int,                       -- from winners_bracket, nullable
        unique (league_id, roster_id)
    );

Written by a new `LeagueHistoryIngestService` walking `leagueChain`. **It must not
write to `draft_pick` or `manager_profile`** — same wall as the mock tables, same
reason (`MockDraftContaminationIT` is the pattern to copy).

**Read paths.** `GET /api/leagues/{sleeperId}/history` — seasons, standings,
champion per season. `GET /api/managers/{id}/history` — one manager across seasons,
record plus their draft-side numbers (reach, value, positional tilt) from data
already present.

**Power rankings, modes 1 and 3** — the commissioner ordering and both computed
ones — land in this phase too, since neither needs auth. See "Power rankings,
three ways" below; this is where `/league/{id}/matchups/{week}`'s per-player
points get ingested.

**Frontend.** Two routes, `/history` and `/power`, alongside the six in
`App.tsx:62-68`. Reuse the existing table/pill vocabulary in `styles.css` — read
its house-style header first, per `HANDOFF.md`.

**This phase is the whole validation.** It answers "would anyone but Allan open
this" without building auth for an audience that may not want it.

### Phase B — polls (only if Phase A gets used)

**Auth, bespoke and minimal.** A per-manager magic link: commissioner generates one
link per `manager` row, the link carries a long random token, the token maps to a
`manager_id` in a `manager_session` table, no passwords, no email dependency if the
links are handed out in the league chat. This is the smallest thing that attributes
a vote to a person. It replaces nothing — `API_TOKEN` stays exactly as it is for
the machine-facing `/api/**` surface.

    poll(id, league_id, season, week, kind, question, opens_at, closes_at)
    poll_option(id, poll_id, label, player_id?, manager_id?)
    poll_vote(id, poll_id, option_id, manager_id, created_at, unique(poll_id, manager_id))

One vote per manager per poll, enforced in the schema, not in the service.

**The first poll to build is mode 2 of the power rankings** — a submitted ordering
is the same machinery as a submitted vote, and it has an audience reason to exist
that a generic poll does not. Generic polls come after it, not before.

**Do not start here.** Everything in Phase B is new risk (auth, spam, moderation,
"who can see results before close") and none of it is testable without other humans.

---

## Power rankings, three ways

Requested 2026-09-07. One page, three independent orderings of the same league:

1. **Commissioner ranking** — Allan orders the teams by hand. One opinion, signed.
2. **League member ranking** — every manager submits their own ordering; the page
   shows the aggregate.
3. **Computed ranking** — roster strength from the players actually on each roster,
   **not** wins and losses.

**The page is not three lists. It is the deltas between them.** Three columns
side by side is a table; the product is "the room ranks you 3rd and your roster
ranks 1st." That gap is the whole reason to have more than one method, and it is
the thing no other fantasy tool shows, because no other tool has all three.

### The auth split falls out for free, and it is convenient

| mode | needs multi-user auth? | phase |
| --- | --- | --- |
| commissioner | **no** — the commissioner is Allan, who is already the only user | **A** |
| computed | **no** — it is a calculation over ingested data | **A** |
| league member | **yes** — arbitrary managers submitting attributed input | **B** |

So two of the three ship in the no-auth phase, and the page is useful with two
columns before anyone else has an account. Mode 2 is also the smallest possible
version of "polls": build ranking submission and a generic poll is mostly the same
machinery, which is the argument for it being the first poll rather than a
separate feature after them.

### Mode 3 is the one worth thinking hardest about

**Measured 2026-09-07 — the data for a real strength ranking is already there,
unauthenticated.** Verified against Ball Knowers 2025:

- `/league/{id}/rosters` carries `players` (16), `starters` (10), `owner_id`,
  `roster_id` — so who is on whose roster is a read.
- `/league/{id}/matchups/{week}` carries **`players_points`** — *actual fantasy
  points, per player, per week* (`{"11786": 4.0, "12490": 8.1, ...}`) — plus
  `starters_points`. So per-player realized production needs **no external stats
  source**, which is what the "not wins and losses" version of this feature was
  otherwise going to depend on.

That gives two honestly different computed rankings, and they should not be
blurred into one number:

    (a) MARKET VALUE    sum of board value over the roster, using the same
                        exp(-adp / valueDecay) the engine already values with.
                        Says: how the market rates the players on this roster.
                        Free -- the board is already in the database.

    (b) REALIZED        per-player points-per-game from players_points, summed
                        over the current starting lineup.
                        Says: whose players have actually produced.

**Amended after review, 2026-09-07.** Mode (a) was written as "draft capital ...
whose roster looked best on draft day" and that name was wrong, on measured
evidence: only **127 of 182 rostered players (70%)** in Ball Knowers 2025, and
**75% of starters**, were drafted in that league's draft at all. The rest arrived
by waiver or trade and have no `adp_at_time` — that column is per *pick*, not per
player — so a draft-capital number over a current roster is missing a third of its
inputs and scores a waiver league-winner as an empty slot. It is computed from the
**board** instead, which covers every player however acquired, and renamed to match.
"Who drafted best" is a real question but a different one: it is fixed at the draft,
would be a **flat line** on a week-by-week graph, and belongs on the history page as
a per-season draft grade. Five further amendments in
`claude/plan-review-league-suite.md`.

**The honest caveat, and it must ship on the page, not in a comment:** neither is
"how good is this team right now." (a) is a preseason expectation and goes stale
the moment the season starts. (b) is backward-looking — a player who scored 25 a
week through October and tore an ACL in November still ranks high on it. Real
current strength needs a *forward* projection, and nothing in Sleeper's public API
provides one. So the page ranks **demonstrated** strength and says so. Per this
project's standing rule: a number that looks more certain than it is gets flagged
in the payload, not buried.

Two smaller decisions that follow:

- **Rank the starting lineup, not the whole roster.** A team stacking three elite
  QBs on the bench is not stronger for it. Use `starters`; the engine already has
  the lineup-slot logic in `SportRules.rosterNeed`.
- **Injury/inactive status is on `player` already** (`status`, `injury_status`,
  `V1__init.sql:4-16`). A strength number that silently counts an IR player is
  the same class of lie as the two above — surface it, or exclude and say so.

### Aggregating mode 2 — show the disagreement, not just the mean

With twelve to fourteen ballots, the *spread* is more interesting than the average
and this project's honesty convention effectively requires showing it: a team
ranked 1st by half the league and 8th by the other half is a different fact from
one everybody puts 4th. So store ballots and compute on read — average rank (or
Borda, decide when building), plus a disagreement measure, plus the most divisive
team as a first-class output rather than a stat somebody could derive.

**Self-ranking is a real decision, not an edge case.** Either exclude a manager's
rank of their own team, or keep it and *show* the bias ("ranks himself 2.1 spots
higher than the room does"), which is more fun and more in keeping with the rest
of this app. Do not silently average it in as though it were neutral.

### The page is a graph, week by week, with the mode as a toggle

Requested 2026-09-07, same conversation: a chart of the league over the season,
three selectable modes, **defaulting to the computed one because it is the only
mode that updates on its own**. Rank on the y-axis, week on the x, one line per
team — a bump chart.

**The y-axis is forced, and it is worth knowing why.** Modes 1 and 2 produce
*orderings only* — a commissioner ordering has no score behind it, and a set of
ballots produces an aggregate position, not a strength. Only mode 3 produces a
number. So the single axis all three modes share is **rank**, and score rides in
the tooltip on the computed modes rather than on the axis. Trying to put score on
the y-axis means two of the three toggles have nothing to draw.

Consequence worth accepting up front: rank hides magnitude. First and second may
be a coin flip or a chasm, and a bump chart cannot tell you which. The computed
modes should say so on hover — the score is right there — rather than pretending
the gaps are uniform.

**Gaps are the real design problem, and they come straight from the cadence
difference Allan already spotted.** The three modes do not cover the same weeks:

    computed        every week, automatically, once ingest has run
    commissioner    only weeks Allan actually sat down and ranked
    member          only weeks enough people submitted -- and possibly 4 of 12

**Do not interpolate across a missing week.** A straight line drawn from week 3 to
week 7 is four weeks of data that does not exist, rendered indistinguishably from
data that does. Break the line, or dot the segment, and label thin coverage
(`4 of 12 ballots`) rather than averaging a handful of ballots into something that
looks like league consensus. This is the same rule as everything else here: the
chart may not look more certain than its inputs.

**Fourteen lines is spaghetti, and the app already owns the fix.** `hueFor()`
(`web/src/hue.ts`, already the identity colour on `DraftBoard` and `OnTheClock`)
gives every manager a stable colour across the whole app, so a line means the same
person here as on the board. Emphasise Allan's own team by default, per the
house rule that crimson means you; hovering a line mutes the rest.

**Two view states, not one.** The toggle Allan described switches *modes* and
shows all fourteen teams. The more valuable view is the transpose: **one team,
all three modes at once** — three lines instead of fourteen, and it is exactly the
delta this page exists for ("the room had you 3rd all October while your roster
ranked 1st"). Same data, same endpoint, far more readable. Build the mode toggle
first; the per-team view is nearly free afterwards.

Smaller decisions that follow:

- **Switching modes preserves the highlighted team.** Losing the selection on
  every toggle destroys the comparison the toggle is for.
- **Default is `computed`, and which computed depends on the week.** Before week 1
  the realized mode has nothing in it and market value is the only thing that
  exists — which is the app's own board talking, a good default for a draft
  simulator. After week 1, realized. **This is the state today, not a hypothetical:**
  `GET /state/nfl` returns week 1 with `season_start_date: 2026-09-09`, so on the
  day this ships the realized mode is empty and "the season hasn't started" is the
  screen Allan actually sees. It needs real copy, not a blank chart. That endpoint
  is also how the page knows what week it is, and it is **not** in `SleeperClient`
  today — see review finding 3.
- **One request, all modes.** Fourteen teams × ~15 weeks × 3 modes is ~630 rows;
  send them in one payload so the toggle is instant and client-side. Do not
  refetch per mode.

**The dependency question, flagged rather than decided.** This is the first chart
in an app with three runtime dependencies (`react`, `react-dom`,
`react-router-dom`) and **no `<svg>` anywhere in `web/src` today**. A bump chart is
polylines, ticks and labels — genuinely hand-rollable in SVG, consistent with a
frontend whose visual vocabulary is already hand-built in `styles.css`, and it
avoids a library whose default look would have to be fought back to house style.
Recommendation: hand-roll this one; revisit if a second, materially different
chart ever shows up.

### Storage sketch

Phase A (V5, alongside the history table):

    power_ranking(id, league_id, season, week, kind, created_at)
        kind in ('COMMISSIONER', 'COMPUTED_MARKET_VALUE', 'COMPUTED_REALIZED')
    power_ranking_entry(ranking_id, roster_id, manager_id, rank, score, note)

Weeks to ingest are bounded by `settings.last_scored_leg`, **not** by looping until
a response comes back empty — measured after review: week 18 of Ball Knowers 2025
returns populated matchup data despite `last_scored_leg: 17`, so a loop-till-empty
ingest silently averages in a week the league never played. `playoff_week_start`
separates regular season from playoffs. The current week is the only one worth
refetching; everything earlier is settled.

**Computed rankings are stored as weekly snapshots, not recomputed on read.**
"Up 3 spots since last week" needs history, and the inputs move underneath you —
recomputing last week's ranking today would silently give a different answer than
it did last week, which is a trap worth designing out rather than discovering.

Phase B adds ballots:

    ranking_ballot(id, league_id, season, week, manager_id, submitted_at,
                   unique (league_id, season, week, manager_id))
    ranking_ballot_entry(ballot_id, roster_id, rank)

One ballot per manager per week, enforced in the schema.

### Acceptance criteria (power rankings)

1. The commissioner ordering and both computed orderings render with **no auth**
   and no ballots present — the page is useful before mode 2 exists.
2. A computed ranking is reproducible from its stored snapshot: reopening week 4
   next month shows what it showed in week 4.
3. Each computed mode states what it measures and what it does not, on the page.
   "Demonstrated strength" and "draft-day expectation" are never labelled
   "strength" unqualified.
4. A starter carrying `injury_status` Out/Doubtful does not silently score as
   though he played, and the page states which way it resolves (excluded, or
   included with a flag). **Amended after review:** IR is a separate `reserve`
   array whose players do not appear in `starters`, so the IR case this criterion
   originally imagined mostly cannot occur through that path — `injury_status` on
   an active starter is the real one.
5. Mode 2 shows spread alongside the aggregate, and self-ranking is handled by an
   explicit, stated rule rather than by accident.
6. None of this writes to `draft_pick`, `manager_profile`, or anything
   `ProfileService.fit()` reads — same wall as the mock tables.
7. **The chart never draws a week it does not have.** A mode with gaps renders as
   broken or dotted segments, never as a straight interpolation, and a partially
   submitted week shows its ballot count.
8. Switching modes keeps the highlighted team; a team's colour is the same
   `hueFor()` colour it has on the draft board.
9. The chart is readable at fourteen teams — verified by looking at it with a real
   fourteen-team season loaded, not by asserting it in a test.
10. Ties in a computed ranking resolve by one stated rule, applied in the service —
    not decided independently by the ranker and the renderer.
11. Week ingest never fetches past `last_scored_leg`, and re-running it does not
    refetch weeks already stored except the current one.

---

## Explicitly not being built

- **Real accounts** — no passwords, no OAuth (there is none to have), no password
  reset flow. Magic links or nothing.
- **A Sleeper chat competitor.** Open question 2 below is still open, and the
  honest answer may be "they already have this and ignore it."
- **Anything commercial**, per Sleeper's own API terms quoted above.
- **Merging league-history tables with `draft`/`draft_pick`.** Same argument as
  `next-features-roadmap.md` §3.4: separate producers, one consumer contract. A
  season-standings row is not a draft pick and should not learn to be one.
- **Multi-sport now.** The tables carry `league.sport` already; nothing in Phase A
  needs to branch on it.

## Acceptance criteria (Phase A)

1. A season ingest of the Ball Knowers chain produces standings that match
   Sleeper's own UI exactly for 2025 and 2026 — wins, losses, points for, champion.
   **Checked against the real site, not just against the API response the code
   itself parsed.**
2. `ProfileService.fit()` output is byte-identical before and after the history
   ingest runs. The contamination guard is a test, not a code comment — extend
   `MockDraftContaminationIT`'s pattern to the new table.
3. A league with a missing/partial season (a chain that ends, a roster with no
   manager) renders rather than 500s. `previous_league_id` chains do end.
4. The history view distinguishes **"what happened"** (wins, points) from
   **"what this app thinks about it"** (reach, value) visually — the second is
   model output and gets the same provenance honesty as everything else.
5. No new endpoint requires auth, and none of them writes to a table the simulator
   reads.

## Open questions still open

1. ~~Auth model~~ — **answered above**: bespoke, because Sleeper offers nothing.
   What remains is only *which* bespoke shape, and that decision belongs to Phase B,
   not now.
2. **Where polls live relative to Sleeper.** Sleeper already has league chat. Is
   this additive or competing with something managers already use and ignore?
   Unresolved, and the cheapest way to find out is to ask the league, not to build.
3. ~~Scope of "history" ingest~~ — **answered above**: small extension, four client
   methods and one table.
4. **New:** does anyone open Phase A? That is the only question whose answer
   changes whether Phase B is worth building at all.

## Recommendation

Unchanged by the measurements, and now supported by them: **start with Phase A,
the read-only slice that needs no new auth.** The measurement made the case
stronger in both directions at once — history got cheaper than assumed (no new
ingest path), and polls got more expensive than assumed (no Sleeper OAuth to lean
on, so auth is fully bespoke).
