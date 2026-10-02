# Feature Specification: Player spotlight on the home page, one tab per league

**Feature Branch**: `015-home-roster-players`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "i also want a tab on the main page to show the top players and rookies
that we have on each" (with a screenshot of the root home page, the area between "Your leagues" and
"Mock drafts" circled, where "From Sleeper" sits today). Follow-up, with a screenshot of spec 014's
Player spotlight on a league home (Top players · Week 3, Trending · last 24h, Rookie watch · Week 3):
"i want it like this but on the main page".

## Problem

The root home page (`/`, the page after sign-in) lists the viewer's leagues, the Sleeper leagues they
have not set up yet, and their mock drafts. Nothing on it is about **players**. Spec 014 built a Player
spotlight (top players, trending, rookie watch), but only on **one league's** home. A manager in five
leagues (the screenshot shows two NBA and three NFL) has to open each league to see it.

### What already exists, and what does not

Checked against `origin/main` (29073a1) on 2026-10-02:

- **Spec 014's Player spotlight is the whole design.** The user's follow-up screenshot is it: three
  columns (Top players, Trending, Rookie watch), five entries each with "Show all 10", each entry with
  player photo, points under the league's scoring, position · team · opponent, and the owning manager
  or a "Free agent" pill. It is served per league by `/api/leagues/{id}/player-spotlight`
  (`PlayerSpotlightController`), scoped by league membership, and rendered on `LeagueHome`.
- **Nothing shows a spotlight outside a league's home**, and nothing lets the viewer switch between
  leagues' spotlights in one place.
- **The root home page already knows the viewer's leagues** and filters them with the rail's
  All sports / NBA / NFL filter.
- **Every rule a spotlight needs is already decided in spec 014**: which period (most recent completed
  night for basketball, the shown week for football), what a rookie is, the pre-season state, the
  "did not play, never 0" rule, trending's attribution and staleness, and current-season only. This
  feature reuses them; it decides none of them again.

### Timing that shapes the empty states

On the day this was written the NBA 2026 season has not started (Sleeper's season start is
2026-10-20), and NFL 2026 is in week 4. Every basketball tab will open in its pre-season state when
this ships.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A league's spotlight on the home page (Priority: P1)

A signed-in manager lands on the home page and, in the space between "Your leagues" and "Mock
drafts", sees a Player spotlight with one tab per league they are in. The first tab is open; it shows
that league's spotlight exactly as the league's own home does: Top players, Trending and Rookie watch.

**Why this priority**: It is the request, in the user's own words "like this but on the main page".
The spotlight already exists per league; what is new is showing it where the user lands.

**Independent Test**: For a viewer in an ingested NFL league, open the home page and confirm the
spotlight shows, entry for entry and point for point, what that league's home shows.

**Acceptance Scenarios**:

1. **Given** a viewer in at least one ingested league, **When** the home page loads, **Then** a Player
   spotlight section appears between "Your leagues" and "Mock drafts", with one tab per league.
2. **Given** the open tab, **When** it is read, **Then** it shows the same three columns, entries,
   points, periods, owners and "Free agent" marks as that league's home spotlight at the same moment.
3. **Given** the open tab, **When** it is read, **Then** the tab names the league and its sport, so a
   reader knows whose scoring and owners they are looking at.
4. **Given** a league in pre-season or with no completed period, **When** its tab is open, **Then** it
   shows the same stated reasons the league home shows, never an empty list or last season's games.

---

### User Story 2 - Switch between leagues (Priority: P1)

The manager clicks another league's tab and the spotlight swaps to that league: its own scoring, its
own period (a night for an NBA league, a week for an NFL one), its own owners.

**Why this priority**: "On each" is the point of putting it on the home page instead of the league
home. Without switching, US1 is one league's spotlight in a second place.

**Independent Test**: With two leagues of different sports, switch tabs and confirm each tab matches
its own league's home, and that the period label changes shape with the sport.

**Acceptance Scenarios**:

1. **Given** several leagues, **When** the viewer clicks a tab, **Then** that league's spotlight
   replaces the previous one, without reloading the rest of the page.
2. **Given** the same player rostered in two of the viewer's leagues, **When** each tab is read,
   **Then** he shows each league's own points and owner. Nothing is combined across leagues.
3. **Given** the rail's sport filter is set to NBA or NFL, **When** the spotlight renders, **Then**
   only tabs for leagues of that sport are offered, matching "Your leagues".
4. **Given** one league's spotlight fails to load, **When** its tab is open, **Then** that tab says so,
   and the other tabs and the rest of the home page are unaffected.

---

### User Story 3 - Go to the league from its tab (Priority: P3)

From the open tab the viewer can go to that league's home.

**Why this priority**: Small, and only useful once tabs exist.

**Independent Test**: Follow the link from a tab and land on that league's home.

**Acceptance Scenarios**:

1. **Given** an open league tab, **When** the viewer follows its link, **Then** they land on that
   league's home page.

---

### Edge Cases

- **No ingested leagues**: the spotlight section is not shown. "Your leagues" already explains the
  first-run state and points to "From Sleeper"; a second empty message would add nothing.
- **One league**: a single tab, still labelled with the league, so the reader knows whose spotlight
  it is.
- **Many leagues**: tabs stay usable at phone width (scrolling or wrapping), without widening the page.
- **Which tab opens first**: the first league in "Your leagues" order, under the current sport filter.
  If the filter hides the open tab's league, the first visible league opens.
- **A league with past seasons** (the root list groups seasons into lineages): the tab shows the
  current season, matching spec 014's current-season-only rule.
- **A tab opened before its data arrives**: it shows a loading state in the section's space, and the
  rest of the page does not move or wait.
- **Trending appears in every league of a sport**: the list is the same, but the owner and points are
  that league's. This is expected, not a duplicate.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The root home page MUST show a Player spotlight section between "Your leagues" and
  "Mock drafts" whenever the viewer has at least one ingested league.
- **FR-002**: The section MUST offer one tab per ingested league the viewer belongs to, in "Your
  leagues" order and under the same sport filter, each labelled with the league name and sport.
- **FR-003**: The open tab MUST show that league's spotlight with the same content, rules and
  presentation as the league home's spotlight (spec 014): Top players, Trending and Rookie watch, the
  covered period, owners, "Free agent" marks, the viewer's own players marked, and "Show all".
- **FR-004**: For the same league at the same moment, every entry and point on the home page MUST equal
  the league home's spotlight. The two MUST come from one source so they cannot disagree.
- **FR-005**: The section MUST show only leagues the viewer belongs to, and MUST never reveal another
  league's owners or players.
- **FR-006**: Each tab's spotlight MUST load and fail on its own. A failure says so in that tab and
  never blanks the other tabs or the rest of the home page.
- **FR-007**: Loading the home page MUST NOT get slower because of the section. "Your leagues" and
  "Mock drafts" appear as quickly as they do today, and only the open tab's spotlight is fetched up
  front.
- **FR-008**: Switching tabs MUST NOT reload the rest of the page, and returning to a tab already
  opened MUST NOT show a loading state again within the same visit.
- **FR-009**: Each tab MUST offer a link to that league's home.
- **FR-010**: Every empty state (pre-season, no completed period, no rookie played, trending
  unavailable) MUST be the league home's own wording, stated in words, never an empty list or zeros.
- **FR-011**: The section MUST fit phone width with a 16px gutter and no horizontal page scroll; on
  narrow screens the three columns stack.

### Key Entities *(include if feature involves data)*

- **League tab**: one of the viewer's ingested leagues, current season, labelled by name and sport.
  It selects which league's spotlight is shown.
- **Player spotlight**: spec 014's per-league spotlight (period, top players, trending, rookie watch),
  reused unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A manager in several leagues can see each league's top player, top trending pickup and
  top rookie for its latest period from the home page alone, opening no league.
- **SC-002**: For every league sampled, 100% of the entries and points in its home-page tab equal that
  league's home spotlight at the same moment.
- **SC-003**: Before the NBA season starts, every basketball tab shows a stated reason and the season
  start date; none shows an empty list, zeros, or last season's games as current.
- **SC-004**: With one league's spotlight deliberately unavailable, every other tab and the rest of
  the home page still work fully.
- **SC-005**: The time until "Your leagues" is visible on the home page does not get worse with the
  section added.

## Assumptions

- **"Main page" is the root home page** (`/`). The first screenshot is of `/`, and the circled area is
  on it.
- **"Like this" means spec 014's spotlight as it stands**: whole league, owner named, with Trending.
  The follow-up screenshot answered the scope question from the first draft of this spec (own roster
  vs whole league) in favour of whole league, and showed Trending is wanted too.
- **"A tab ... on each" means one tab per league.** Each league scores differently, so a per-league
  view is the only honest unit; points are never combined across leagues.
- **"From Sleeper" stays.** The circle marks where the section goes; "From Sleeper" is still the way to
  set up a league, so it stays above the spotlight when it has rows.
- **The tab choice need not be remembered across visits.** Opening on the first league is enough; a
  remembered tab is a later nicety.
- **The "most recent night" cutoff stays an assumption** until the first NBA night of the season is
  checked, as spec 014's own follow-up already records.

## Out of scope

- Any change to the spotlight's content or rules, or to the league home's spotlight.
- Combining leagues: a cross-league leaderboard, or one player's points summed across leagues.
- Season-long totals or a Rookie of the Year race.
- Mock drafts: mocks are not real teams.
- Leagues on Sleeper that have not been set up here ("From Sleeper" rows): they have no stored data.
