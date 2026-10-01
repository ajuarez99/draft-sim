# Feature Specification: Player spotlight on the league home

**Feature Branch**: `014-home-player-spotlight`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "ok on main page i want to know more about day to day players fantasy wise.
day to day I want to know for nba like top players of the night that we have. i want to see what the
trending players scored that night. i want to see rookie watch. and for nfl we can do top players of
that week"

## Problem

The league home (the page a league's name leads to, built in spec 013 US4) is entirely about
**managers**: standings, your record, the latest matchup, the power headline, the top award. Nothing on
it is about **players**: who went off last night, whether the guy everyone is picking up actually
produced, how the rookies are doing. That is the everyday fantasy conversation, and today it means
leaving the app.

### What already exists, and what does not

Checked against `origin/main` (2dd47f2) and the live Sleeper API on 2026-10-01:

- **Per-game stat lines are already stored for every player, not just rostered ones.** Spec 005 added
  them and spec 009 rebuilt them on a per-week feed. That covers "top players of the night" and
  "rookie watch": a rookie or a free agent who played last night already has a stored game. Points are
  computed at read time from each league's own scoring, so this feature can score a night under *this*
  league's settings.
- **Spec 005's Best Nights section is per fantasy week, on the Weekly Report.** This feature asks a
  different question ("what happened last night"), on a different page. It is a new grouping of the
  same stored games, not a copy of that section.
- **Player experience (years in the league) is already stored** for every ingested player. That is the
  input to "rookie".
- **Trending players are not ingested at all.** Sleeper publishes trending add and drop lists for both
  NBA and NFL (measured: both return data today). This feature introduces trending.
- **Football already has a Top performers list for a week** on the Weekly Report. For football, the
  home's "top players of the week" reads the same thing rather than computing a second version.

### Timing that shapes the empty states

Today the NBA 2026 season has **not started**: Sleeper reports `season_type: pre` and a season start of
2026-10-20. No basketball night exists for the current season yet. NFL 2026 is in week 4. Every
basketball section must therefore have an honest pre-season state, and that state will be the one that
ships first. It is not a corner case.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Top players of the night, from our league (Priority: P1)

A manager opens their basketball league's home the morning after a slate of games and sees the best
fantasy performances from the most recent night: the player, the points under this league's scoring,
the opponent, and which manager in the league rosters him.

**Why this priority**: It is the first thing the user asked for and it is the daily habit the page is
missing. It needs no new data source: the games are stored, and so are the rosters.

**Independent Test**: For an ingested NBA league on a day after games were played, open the league home
and confirm the section names the night, lists rostered players ordered by points scored that night
under the league's scoring, and shows each one's owner and opponent.

**Acceptance Scenarios**:

1. **Given** a basketball league and a most recent night with completed games, **When** the league home
   loads, **Then** a "Top players of the night" section lists the best single-game fantasy scores from
   that night among players rostered in this league, each with points, opponent and owning manager.
2. **Given** that list, **When** it is read, **Then** the night it covers is stated as a calendar date,
   never only as "last night", so a reader opening the page two days later is not misled.
3. **Given** the viewer is a member of the league, **When** one of their own players is in the list,
   **Then** that entry is visibly marked as theirs.
4. **Given** points shown in the list, **When** compared with the same game elsewhere in the app (Weekly
   Report Best Nights), **Then** the figures agree to the point.
5. **Given** the season has not started, or no night of the current season has completed games yet,
   **When** the page loads, **Then** the section says so and when play begins, rather than showing an
   empty list or last season's games as though they were current.

---

### User Story 2 - Trending players, and what they actually scored (Priority: P2)

A manager sees the players most added across Sleeper right now and, beside each one, what that player
scored on the most recent night: whether the waiver-wire hype was backed by an actual game. Each entry
says who in this league has him, or that he is a free agent here.

**Why this priority**: It is the second ask and the one that needs a new data source. It is useful
without US1, but US1 establishes "the night" this story scores against.

**Independent Test**: Open the league home after a night of games, confirm the trending list matches
Sleeper's own trending adds for the stated window, and that each player's figure equals their game that
night under this league's scoring, or says plainly that they did not play.

**Acceptance Scenarios**:

1. **Given** Sleeper's trending adds for the sport, **When** the league home loads, **Then** a "Trending"
   section lists the most added players in their trending order, each with the number of adds and the
   window those adds cover.
2. **Given** a trending player who played on the most recent night, **When** his entry is read, **Then**
   it shows his points from that game under this league's scoring.
3. **Given** a trending player who did not play that night, **When** his entry is read, **Then** it says
   he did not play, and never shows 0 as though it were a score.
4. **Given** a trending player, **When** his entry is read, **Then** it says which manager in this
   league rosters him, or that he is unrostered here.
5. **Given** the trending feed cannot be reached or has not been refreshed recently, **When** the section
   renders, **Then** it says the list is unavailable or names how old it is, rather than showing a stale
   list as current.

---

### User Story 3 - Rookie watch (Priority: P3)

A manager sees how first-year players performed on the most recent night, ranked by fantasy points,
whether or not anyone in the league rosters them, with owner or "free agent" shown for each.

**Why this priority**: Explicitly requested, but the most self-contained of the three and the one that
depends on a "rookie" definition the data has to support. It is sequenced after the two that answer
"what happened last night for players that matter to us".

**Independent Test**: For a night with games, confirm every player listed is in his first season, the
list is ordered by that night's points under this league's scoring, and each entry shows owner or free
agent.

**Acceptance Scenarios**:

1. **Given** a most recent night with games, **When** the league home loads, **Then** a "Rookie watch"
   section lists the best fantasy performances by first-season players that night, rostered or not.
2. **Given** a player in the list, **When** his entry is read, **Then** it shows his points, opponent,
   and his owner in this league or that he is a free agent.
3. **Given** a night on which no rookie played, **When** the section renders, **Then** it says so, not an
   empty list.
4. **Given** a player whose experience is unknown, **When** rookies are selected, **Then** he is left
   out rather than guessed to be a rookie.

---

### User Story 4 - Football: the same three sections, by week (Priority: P2)

A manager opening a football league's home sees the week's best fantasy performances among players
rostered in the league, with owner and points, for the same week the home's latest-matchup block
already shows, plus Trending and Rookie watch scored against that week.

**Why this priority**: Explicitly asked for, and nearly free: football's Weekly Report already ranks a
week's top performers. It sits at P2 because NFL is in season today, while NBA is not, so this story is
the one a reader can actually see first.

**Independent Test**: Open an ingested NFL league's home during the season and confirm the list matches
the Weekly Report's Top performers for the same week, entry for entry.

**Acceptance Scenarios**:

1. **Given** a football league, **When** the league home loads, **Then** a "Top players of the week"
   section lists the week's best performers among rostered players, with points and owning manager,
   and names the week. *(Amended after planning, 2026-10-01: "among **starters**", not "rostered".
   The list is the Weekly Report's own Top performers, which counts started players only. Scenario 3,
   agreeing with that list, is the stronger requirement. See research R9.)*
2. **Given** the week is still being played, **When** the section renders, **Then** it says the week is
   in progress, so the list is not read as settled.
3. **Given** the same week on the Weekly Report, **When** the two lists are compared, **Then** they
   agree entry for entry and point for point.
4. **Given** football, **When** the league home loads, **Then** the Trending and Rookie watch sections
   also appear, scored against that same week instead of a night: each trending player's points for
   the week (or "did not play" / "bye"), and the week's best first-season performances, rostered or
   not. *(Clarified 2026-10-01: Allan chose "all three for football, scored by week", conditional on
   it being possible. It is: football per-game lines are already stored by the automatic refresh. The
   row counts for the current NFL season still need measuring in planning.)*

---

### Edge Cases

- **The most recent night**: the latest calendar date on which at least one of the sport's games has
  completed. A slate still being played is not "the night" until its games are final. If tonight's games
  are in progress, the page shows last completed night and says which date that is. *(Amended after
  planning: "stored" does not mean "completed". A US evening slate's rows become storable at 8 pm Eastern,
  while games are still being played. A night counts as complete only once its data was refreshed after
  10:00 UTC the next morning. The cutoff is an assumption until the first NBA night. See research R6.)*
- **A football bye** is not stored as such. "Bye" is shown only when the player's team demonstrably had
  no game that week; otherwise the entry says "did not play". *(Added after planning, research R3.)*
- **Pre-season and off-season** (the case on the day this spec was written for NBA): no current-season
  night exists. Sections say so; they do not fall back to last season's final night unlabelled.
- **A player traded or dropped since the night**: ownership is shown as it stands now, and the label
  says "rostered by", not "scored for".
- **A trending player Sleeper knows but this app has never ingested**: shown by name if resolvable;
  otherwise left out, with a count of how many were omitted and why.
- **Ties on points**: ordering is deterministic so two loads show the same list.
- **Games scored under a league whose scoring the app cannot read**: the section says points are
  unavailable rather than showing another league's numbers.
- **A night with very few games** (e.g. two games on a Sunday): the list may be shorter than usual and
  says how many games it covers.
- **A double-header day for one player** cannot occur in either sport; one game per player per night is
  assumed and a violation is surfaced rather than summed silently.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The league home MUST show, for a basketball league, a ranking of the most recent completed
  night's best single-game fantasy scores among players rostered in that league, each with points,
  opponent and owning manager.
- **FR-002**: Every night-based section MUST name the calendar date it covers, and every week-based
  section MUST name the week, beside the list, from the same source as the numbers in it.
- **FR-003**: All points MUST be computed under the viewing league's own scoring settings and MUST agree
  with the same game's figure elsewhere in the app.
- **FR-004**: The league home MUST show a Trending section listing Sleeper's most added players for the
  sport, in Sleeper's order, with the add count and the window it covers.
- **FR-005**: Each trending entry MUST show the player's fantasy points from the most recent completed
  night (basketball) or the week the home already shows (football), or state that he did not play
  (or, in football, that his team was on bye). A player who did not play MUST never be shown as scoring 0.
- **FR-006**: The league home MUST show a Rookie watch section listing first-season players' best
  fantasy scores from the most recent night (basketball) or the shown week (football), rostered or not,
  each with owner or "free agent".
- **FR-007**: A player whose experience is unknown MUST NOT be treated as a rookie.
- **FR-008**: The league home MUST show, for a football league, the week's top performers among rostered
  players, drawn from the same source as the Weekly Report's Top performers so the two cannot disagree.
- **FR-009**: Every entry in every section MUST name the owning manager in this league, or state that
  the player is unrostered here. When the viewer owns the player, the entry MUST be marked as theirs.
- **FR-010**: When a section has nothing to show (pre-season, no completed night, no rookie played, feed
  unavailable), it MUST state the reason in words rather than render an empty list or zeros.
- **FR-011**: Trending data MUST record when it was last fetched, and the section MUST show that age when
  it exceeds the trending window, rather than presenting an old list as current.
- **FR-012**: Each section MUST load independently, so a slow or failing one does not block the rest of
  the league home, matching how the existing home blocks behave. *(Amended after planning: the three
  sections share one request, because they share every lookup. Each still reports its own failure, and
  that request failing costs only these sections, never the rest of the home. See research R11.)*
- **FR-013**: Which sections appear MUST follow from the sport's rules (whether a player plays more than
  once per scoring period), not from a sport name compared in a service or a component, the same rule
  spec 005 established.
- **FR-014**: Ordering MUST be deterministic, including for exact ties.
- **FR-015**: Each entry MUST show its exact points beside the player's name: one number per mark, no
  bar or color standing in for the value.
- **FR-016**: Nothing in these sections may be fetched per player while the reader waits; the page reads
  stored data.
- **FR-017**: The Trending section MUST credit Sleeper as its source and say that the add counts are
  across all Sleeper leagues, not this one. *(Added after planning: Sleeper's API documentation asks
  for attribution of trending data. See research R5.)*

### Key Entities *(include if feature involves data)*

- **Night**: a calendar date in a sport-season on which games completed; the most recent one is what the
  basketball sections cover. Carries the date and the number of games it includes.
- **Night performance**: one player's single game on a night, with opponent and points under a given
  league's scoring. Already stored as a game; this feature groups it by date instead of fantasy week.
- **Trending entry**: a player's place on Sleeper's most-added list for a sport, with add count, the
  window the count covers, and when the list was fetched. New to this app.
- **Rookie**: a player in his first season, as determined from stored experience. Unknown experience
  means not a rookie.
- **Ownership**: which manager in the viewing league currently rosters a player, if any, and whether that
  manager is the viewer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a morning after NBA games, a manager can name the best fantasy performance in their
  league from the previous night, its date and its owner without leaving the league home.
- **SC-002**: For every trending player shown, a reader can tell within a glance whether he played on the
  covered night and what he scored, with no entry showing 0 for a player who did not play.
- **SC-003**: For a sampled night, 100% of the points shown in Top players of the night equal the same
  game's points on the Weekly Report's Best Nights.
- **SC-004**: For a sampled NFL week, Top players of the week matches the Weekly Report's Top performers
  entry for entry.
- **SC-005**: Before the NBA season starts, every basketball section shows a stated reason and the season
  start date; none shows an empty list, zeros, or last season's games labelled as current.
- **SC-006**: When the trending feed is unreachable, the rest of the league home still loads fully and
  the Trending section states why it is empty.
- **SC-007**: The league home's time until its existing blocks are visible does not get worse with these
  sections added.

## Assumptions

- **"Main page" is the league home** (`/leagues/:id`), not the root draft picker. "Players that we have"
  only means something inside a league, and spec 013 made the league home the league's front page.
- **"Players that we have" means rostered in this league.** Top players of the night is limited to
  rostered players; Trending and Rookie watch are league-wide (that is their point) and show ownership
  instead.
- **"Trending" is Sleeper's trending adds** over the last 24 hours, the default window Sleeper's own app
  uses. Trending drops are not shown in this version.
- **Rookie means first season** per stored years of experience being zero. Whether Sleeper's experience
  figure is reliable for rookies before and during their first season is a measurement for the planning
  phase, not an assumption to carry into code.
- **Ten entries per list, give or take.** The count is a presentation detail settled in design.
- **Ownership is current**, not ownership on the night, matching spec 005's eligibility rule.
- **Football's week is the same week the home's latest-matchup block shows**, so the page never names two
  different weeks.
- **Trending refresh rides the existing automatic data refresh** (spec 009) rather than a new schedule;
  the planning phase decides the cadence, which must be more frequent than that spec's daily run for the
  list to mean "right now". *(Resolved in planning: at most hourly, whenever a league in the sport is
  visited, plus the daily run. Research R5.)*
- **Ownership is read from the latest stored week**, refreshed hourly on visit, rather than a live
  roster call, so a pickup since the last refresh briefly shows as unrostered. *(Added after planning,
  research R10.)*
- **The spotlight covers the league's current season only.** A past season's home shows no spotlight,
  because trending is inherently "now" and stored rookie status is today's. *(Added after planning,
  research R4/R8.)*

## Out of scope

- A dedicated player page or player search.
- Trending drops, and trending over windows other than 24 hours.
- Season-long rookie leaderboards: this is a nightly watch, not a Rookie of the Year race.
- Projections for tonight's games. Basketball has no projection source in this app.
- Push notifications or alerts when a rostered player goes off.
- Any change to the Weekly Report's existing sections.
