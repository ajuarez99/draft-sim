# Feature Specification: Down the ffwrapped list, in both sports

**Feature Branch**: `004-ffwrapped-feature-parity`

**Created**: 2026-09-18

**Status**: Draft

**Input**: User description: "ok lets go down the list here on this website lets plan and get this out.
https://ffwrapped.com/?leagueId=1346366555759341568&view=roster-management when i say this i also want
this to be compatible with basketball"

## Problem

ffwrapped's sidebar is nineteen views across four groups. Ball Knowers ships strong versions of a few
of them, nothing at all for most, and — critically — the ones it does ship are football-only in a way
that is not obvious from the outside.

The list, read off ffwrapped's own sidebar on 2026-09-18 against league `1346366555759341568`
((Foot) Ball Knowers, the same league this app models):

| # | ffwrapped view | Ball Knowers today | Verdict |
|---|---|---|---|
| 1 | League Hub | `/` picker: drafts + mocks + "add a draft" | PARTIAL |
| 2 | Dashboard | — | GAP |
| 3 | Start/Sit | — | GAP (projection-bound) |
| 4 | Weekly Report | — | GAP |
| 5 | Standings | `/leagues/:id/history` | PARTIAL |
| 6 | Power Rankings | `/leagues/:id/power` — ballots + market value + realized | **HAVE** (ahead) |
| 7 | Expected Wins | — | GAP |
| 8 | **Roster Management** | — | GAP |
| 9 | Playoffs | one odds number in the Record cell | PARTIAL (engine exists) |
| 10 | Season Forecast | same engine, unexposed | PARTIAL (engine exists) |
| 11 | Draft | board, live tracking, mocks, manager models | **HAVE** (well ahead) |
| 12 | Player Values | board/ADP | PARTIAL |
| 13 | Trade Lab | — | GAP |
| 14 | Rate My Team | `/leagues/:id/analysis` roster projections | PARTIAL |
| 15 | Player comparison | — | GAP |
| 16 | League History | `/leagues/:id/history` record book | **HAVE** |
| 17 | Manager Profiles | `/managers`, `/managers/:id/history`, tendencies | **HAVE** (ahead) |
| 18 | Historian | — | GAP (LLM Q&A) |
| 19 | Wrapped | — | GAP (season-end recap) |

The interesting finding is not the size of the gap. It is that the gap is much cheaper than it looks
in one direction and much more expensive than it looks in another, and the dividing line is the same
line that decides whether a view can work for basketball at all.

### The dividing line: realized vs projected

Every view on that list asks one of two questions.

**Backward-looking** views ask what already happened — what a roster scored, what its best possible
lineup would have scored, who got lucky. Sleeper reports per-player weekly points for NBA exactly as
it does for NFL, and **this app already stores them**: `roster_week_points.players_points` is a jsonb
map of `sleeper_player_id -> points`, ingested and cached for every scored week of every ingested
season. The V5 migration comment anticipates this exact use ("in case a future view wants a per-player
breakdown, not just the roster total"). These views need **no new ingest** and **work for both sports**.

**Forward-looking** views ask what will happen — projected points, start/sit calls, rest-of-season
value. These depend on a projection source, and the app's projection source is football's: Sleeper's
`pts_ppr` and friends. `SportRules.startingLineup`'s javadoc says so in as many words, and throws
rather than answer a projection question with a draft-board number.

That distinction is the spine of this roadmap. It is also currently mis-drawn in code: the football-only
blocker sits on `startingLineup`, which backward-looking views need too — so basketball is locked out
of views whose data it actually has.

### The basketball landmine, precisely

`SportRules.startingLineup(roster, settings, valueOf)` — "which players the seat would actually start,
valued by `valueOf`" — is implemented by `FootballRules` only. `BasketballRules` does not override it,
so the interface default throws `UnsupportedOperationException`.

This is the repo's own recurring bug class, in its purest form yet:

- `FootballRules` was **deliberately refactored** so `startingLineupValue` delegates to `startingLineup`.
  Its javadoc: *"Two callers, one rule... Writing the second one separately would be two implementations
  of 'what is this roster's starting lineup', which is the shape of bug that has shipped three times in
  this repo under a different name each time."*
- `BasketballRules` has the opposite shape: `startingLineupValue` is standalone, and the reporting form
  does not exist.

And the cost of fixing it is far lower than the gap suggests, because basketball already has the hard
part. `BasketballRules.prepareLineup(roster, settings, valueOf)` **already takes an arbitrary value
function** and already solves the genuinely difficult problem — optimal lineup under multi-position
eligibility across nine nested slots, as a maximum-weight independent set over a transversal matroid,
provably optimal by the matroid greedy theorem, measured rather than guessed. `startingLineupValue` is
literally `lineupValue(prepareLineup(roster, settings, this::value))`.

So "the optimal lineup for an arbitrary value function" is solved for basketball. What is missing is
only the accessor that **names** the seated players instead of summing them. That is the whole fix, and
once it lands, every backward-looking view in this roadmap is sport-agnostic by construction.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A basketball roster can name its own starting lineup (Priority: P1)

Nothing else in this roadmap can be sport-agnostic until this is true. Today a call to
`startingLineup` on an NBA league throws, so any view built on "what would this roster have started"
either crashes for basketball or quietly grows a second, football-shaped implementation.

**Why this priority**: It is a prerequisite, it is small, and it is the exact defect class this repo has
shipped repeatedly. Doing it first means every later story gets basketball for free instead of paying
for it separately — which is what "compatible with basketball" has to mean if it is to mean anything.

**Independent Test**: Call `startingLineup` on an NBA roster with a value function and assert it returns
the seated players with their slots, and that their values sum to `lineupValue(prepareLineup(...))` with
the same function. Ships with no UI.

**Acceptance Scenarios**:

1. **Given** an NBA roster and any value function, **When** `startingLineup` is called, **Then** it
   returns the seated players with their slots, and does not throw.
2. **Given** the same roster and function, **When** both methods are called, **Then**
   `sum(startingLineup(...).value)` equals `lineupValue(prepareLineup(...))` exactly — one rule, two
   readings, provably not two implementations.
3. **Given** a value function that is NOT monotone in ADP (realized weekly points), **When** the lineup
   is computed, **Then** the result is optimal for that function, not for board order.
4. **Given** an NFL roster, **When** `startingLineup` is called, **Then** its existing behaviour and
   values are unchanged.

---

### User Story 2 - Roster Management, for both sports (Priority: P2)

The view the user pointed at. A manager opens their league and sees, per team: total points scored,
the maximum they could have scored with perfect weekly lineups, and the efficiency between them —
plus the same comparison as a chart.

**Why this priority**: It is the named request, and after P1 every input it needs is already in the
database. No new ingest, no new external dependency, both sports.

**Independent Test**: For an ingested league with at least one scored week, assert each roster's total
equals the sum of its stored weekly `starters_points`, and its potential equals the sum of per-week
optimal lineups computed from `players_points`. Verifiable against ffwrapped's published numbers for
the same league.

**Acceptance Scenarios**:

1. **Given** a league with scored weeks, **When** the Roster Management view loads, **Then** each team
   shows Total Points, Potential Points and Efficiency (total ÷ potential), sorted by total.
2. **Given** the same league, **When** the Points vs Potential chart renders, **Then** each team shows
   both bars with the exact value beside each, not a bar alone.
3. **Given** an NBA league with scored weeks, **When** the view loads, **Then** it renders the same
   three columns, computed through the same code path as football.
4. **Given** a league with zero scored weeks, **When** the view loads, **Then** it says so rather than
   printing 0.0 or a 100% efficiency.
5. **Given** a week where a roster's `players_points` is empty, **When** potential is computed, **Then**
   that week is excluded and the exclusion is visible, not silently summed as zero.

---

### User Story 3 - Expected Wins and schedule luck (Priority: P3)

Each team's expected wins against a uniformly random opponent each week, wins above expected, strength
of schedule, and the specific weeks where luck swung a result.

**Why this priority**: Same input as P2 (`roster_week_points`, already stored), no new ingest, both
sports, and it is the single most-argued-about number in a fantasy league. It rides directly behind
Roster Management because it reuses the same loaded week data.

**Independent Test**: For a league with N scored weeks, assert each team's expected wins equals its
all-play win rate times weeks played, and that expected wins across the league sums to actual wins.

**Acceptance Scenarios**:

1. **Given** a scored week, **When** expected wins are computed, **Then** a team's contribution is the
   fraction of the other teams it outscored that week.
2. **Given** a full league, **When** expected wins are summed, **Then** the total equals total actual
   wins (within floating-point tolerance) — the conservation check that proves the model.
3. **Given** a team, **When** strength of schedule is shown, **Then** it is the average difference
   between its opponents' points per game and the league-wide average, with the sign explained.
4. **Given** a team with a lucky or unlucky record, **When** the detail is shown, **Then** it names
   either the specific swing weeks or states that the cause was consistent opponent scoring, not both.
5. **Given** an NBA league, **When** the view loads, **Then** it computes identically — nothing in this
   story is sport-specific.

---

### User Story 4 - Season Forecast and the Playoff picture (Priority: P4)

ffwrapped's Season Forecast and Playoffs views are two readings of one simulation: projected wins over
the remaining schedule, win percentile ranges, average seed, per-seed odds, the most likely bracket
path, and a threat map of likely opponents.

**Why this priority**: Ball Knowers **already runs this simulation**. `PlayoffOddsSimulator` runs 10,000
seasons and `PlayoffOddsService` stores the result — and the UI surfaces a single percentage in the
Record cell. The distributions needed for both ffwrapped views are computed and thrown away. This is
mostly a serialization and presentation job, which makes it unusually cheap for how substantial it looks.

**Independent Test**: Assert the odds endpoint returns per-seed probabilities and win percentiles whose
marginals reproduce the already-published single playoff-odds number.

**Acceptance Scenarios**:

1. **Given** a league with a scored week, **When** the forecast loads, **Then** each team shows playoff
   odds, average wins, a 10th–90th percentile win range, average seed and No. 1 seed odds.
2. **Given** the same snapshot, **When** playoff odds are read from the Record cell and from this view,
   **Then** they are the same number from the same snapshot, never two computations.
3. **Given** a league with divisions or a non-default `playoff_seed_type`, **When** the view loads,
   **Then** it refuses to answer and says why — the existing honest-refusal rule is preserved, not
   bypassed by a new endpoint.
4. **Given** a league with no scored week yet, **When** the view loads, **Then** no forecast is shown.
5. **Given** an NBA league whose seeding this app models, **When** the view loads, **Then** it forecasts;
   the simulator is driven by weekly scores and pairings, both of which NBA has.

---

### User Story 5 - Weekly Report (Priority: P5)

A per-week digest: every matchup with scores, weekly awards ("Self-Inflicted Wound", "Got Away With
It", "Deserved Better", "One-Player Carry"), and the week's top performers.

**Why this priority**: Most of it computes from data already stored, but the awards are the first thing
in this roadmap that needs data the app does not have — see the `starters` gap in research. It is
sequenced after the views that need nothing new.

**Independent Test**: For one scored week, assert matchups, top performers and every award that does
not require starter identity render from stored data alone.

**Acceptance Scenarios**:

1. **Given** a scored week, **When** the report loads, **Then** every matchup shows both teams, records
   and final scores.
2. **Given** a scored week, **When** top performers are listed, **Then** they are ranked by that week's
   actual points from `players_points`, with the owning team named.
3. **Given** a team that lost while leaving a better lineup on its bench, **When** awards are computed,
   **Then** the award names the specific bench player and starter involved.
4. **Given** a week ingested before starter identity was stored, **When** an award needs it, **Then**
   that award is omitted with a stated reason rather than guessed at.
5. **Given** an NBA league, **When** the report loads, **Then** matchups, top performers and
   efficiency-based awards all render.

---

### User Story 6 - Transactions, trades and waiver grades (Priority: P6)

The rest of the Roster Management page: transaction counts by type per manager, every trade with each
side's post-trade positional performance, waiver and free-agent adds with FAAB spend, and a Best Adds
ranking.

**Why this priority**: Last of the committed work because it is the only story requiring a genuinely new
ingest pipeline. It completes the view the user named, so it is committed rather than deferred — but
everything ahead of it ships without waiting on it.

**Independent Test**: Ingest transactions for one league and assert counts by type per manager match
Sleeper's own transaction log for those weeks.

**Acceptance Scenarios**:

1. **Given** an ingested league, **When** transactions are ingested, **Then** waiver claims, free-agent
   adds and drops, and trades are stored per week with their manager and FAAB bid where present.
2. **Given** a manager, **When** the transaction chart loads, **Then** their moves are broken out by
   type, with the count labelled on each segment.
3. **Given** a trade, **When** it is shown, **Then** each player carries their average positional rank
   over the weeks played since the trade date, and the direction of "lower is better" is stated.
4. **Given** a league with no trades, **When** the section loads, **Then** it says no trades have been
   made rather than rendering an empty chart.
5. **Given** an NBA league, **When** transactions are ingested, **Then** the same pipeline stores them;
   positional grading uses basketball's positions, not football's.

---

### Out of scope for this feature

Deliberately not planned here, with the reason:

- **Start/Sit, Rate My Team, Player Values, Trade Lab, Player comparison** — all projection-bound. They
  need a rest-of-season value model per player, and for basketball they need a projection source that
  does not exist. Planning them now would either commit to a football-only design or invent an NBA
  projection source on speculation. They get a documented seam, not an implementation.
- **Historian** — an LLM Q&A surface over league data. It is a different kind of feature (a model
  integration, not an analysis), and it is worth doing only once the data it would answer from exists.
  This roadmap is what creates that data.
- **Wrapped** — a season-end recap. ffwrapped's own version answers "come back after the season is
  complete". Same here: it is a presentation layer over everything above, and it should be built last.
- **Dashboard / League Hub personalization** — both hinge on "which team is yours", which is a user-to-
  roster binding. Ball Knowers already has sign-in and league scoping; wiring a personal roster onto it
  is real work but is navigation and identity, not analysis.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `SportRules.startingLineup` MUST be implemented for every sport the app supports. The
  interface default MUST NOT remain a throwing stub that a sport can silently inherit.
- **FR-002**: For each sport, "what is this roster's starting lineup" MUST have exactly one
  implementation, with the value-summing form delegating to the player-naming form.
- **FR-003**: Optimal-lineup computation MUST be correct for value functions that are not monotone in
  ADP. Realized weekly points are not.
- **FR-004**: Every view that asks a backward-looking question MUST work for both NFL and NBA, and MUST
  reach its sport-specific behaviour through `SportRules` rather than a sport branch in a service.
- **FR-005**: A view that requires a projection source MUST declare the sports it supports explicitly,
  by the same non-defaulted mechanism `LeagueDestination.sports` already uses. A defaulted sport list
  asserts a rule rather than a value.
- **FR-006**: Total, potential and efficiency MUST be computed from stored weekly data, per week, and
  summed — not from a season aggregate.
- **FR-007**: A week with missing or empty per-player points MUST be excluded from potential-points
  totals, and the exclusion MUST be visible to the reader.
- **FR-008**: Playoff and forecast figures MUST come from one stored simulation snapshot. Two views
  showing the same league MUST NOT show two different numbers.
- **FR-009**: The existing refusal rules MUST be preserved: no forecast for an unmodelled seeding
  scheme, and nothing computed on page load.
- **FR-010**: Storing a new per-week field MUST come with a backfill path that actually refetches
  already-settled weeks. The ingest skip gate is keyed on rows existing, not on columns being populated.
- **FR-011**: Every chart MUST carry the exact value beside the mark and a labelled axis; one encoding
  per mark.
- **FR-012**: Each new page MUST be registered in `destinations.ts` so the rail, palette and league
  switcher pick it up from the single declaration.

### Key Entities

- **Roster week performance** — one roster's scored week: actual starter total, per-player points, and
  the computed optimal lineup for that week.
- **Season roster summary** — total, potential and efficiency for one roster across a season, plus the
  count of weeks that contributed.
- **Expected wins record** — per roster: expected wins, actual wins, the difference, strength of
  schedule, and the weeks that drove the gap.
- **Season forecast** — per roster, read off one simulation snapshot: playoff odds, win distribution,
  seed distribution, championship odds and likely opponents.
- **Transaction** — one roster move: type, week, manager, players added and dropped, FAAB bid.

## Success Criteria *(mandatory)*

- **SC-001**: Roster Management renders Total, Potential and Efficiency for every team in an ingested
  NFL league, and the numbers reconcile with ffwrapped's for league `1346366555759341568`.
- **SC-002**: The same view renders for an ingested NBA league through the same code path, with no
  sport branch in the service layer.
- **SC-003**: `startingLineup` has no throwing implementations left, and a test asserts value/report
  agreement for every registered sport.
- **SC-004**: Expected wins sum to actual wins across the league.
- **SC-005**: Playoff odds shown on the forecast view and in the Record cell are the same number from
  the same snapshot.
- **SC-006**: Adding a new league-scoped page without declaring it in `destinations.ts` fails a test.
