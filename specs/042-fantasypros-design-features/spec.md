# Feature Specification: FantasyPros design and feature borrowings

**Feature Branch**: `042-fantasypros-design-features`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "look at fantasy pros website [...] and see what we can improve on our design" + "also features"

## Context: what was looked at, and how

Looked at on 2026-10-20 (NBA opening night) in the built-in browser, at 1366×900. **Signed out the whole time.**
The password in the request was not used, because entering credentials is something the user has to do
themselves. So everything below FantasyPros' sign-in and paywall (My Playbook with a synced league,
premium assistants) is known only from its menu labels and landing copy, not from using it.

Pages actually opened: home feed, `/nba/` hub, Draft Wizard landing, `/nba/myplaybook/` (sync prompt
only), `/nfl/myplaybook/` (full tool menu, signed out), NBA Rankings / Research / More indexes, the NBA
Player Rater, NBA ROS rankings, and Victor Wembanyama's player page. `/nba/matchup-ratings.php`, which the
Research index links to, returned a 404.

This is the second comparison against FantasyPros. The first
(`claude/competitor-gap-research.md`, 2026-10-05) read feature lists only. Spec 013
(`claude/design-review-fan-first.md`) already borrowed the icon rail, grouped navigation, letter grades
and faces. Neither was signed in, and neither looked at how FantasyPros marks *your* players. That is
what this spec adds.

### What FantasyPros does that Ball Knowers doesn't (observed)

| Pattern | Where it was seen | Ball Knowers today (from the code, main @ `2df1682`) |
|---|---|---|
| **Mine / taken / available on every player list.** The Player Rater has a three-colour legend (Roster / Taken / Available). Rankings have an "Availability" filter and a "My Leagues" picker. The feed is "filtered by players on your team". | Player Rater, ROS rankings, home feed | Ownership shows as text in a few places: "Free agent" on the stat leaderboard and player page, an owner column on Trends. No page marks *your* players differently from another manager's, and no page can filter to free agents only. |
| **Player page header answers "what now".** Next game (opponent, date, time), positional rank, eligibility by platform, "Rostered in ~100% of leagues", then tabs: Overview / Notes / News / Articles / Rankings / Projections / Stats / Game Log / Schedule. | Wembanyama page | `PlayerPage` has a name header and long stacked tables (season, advanced, game log). The next game isn't on it, and neither is the owner in this league as a header fact. There are no tabs. |
| **"Are They Playing?"** A lineup check for starters who are out or have no game. | My Playbook menu (Lineup group) | League home shows next opponent, and the roster model zeroes Out/IR players (`api.ts:1506`). No view says "your starter X is out tonight". |
| **"Who Should I Start?"** A two-player box on the home page ("Enter first player / Enter second player → See advice"). | Home feed sidebar, More index | No player-vs-player view. |
| **Multi-League Assistant.** One view of all your teams. | My Playbook menu, feed promo | Home lists leagues. Roadmap item 4.2 (cross-league summary) is not built. |
| **Rankings spread columns.** Best / Worst / Avg / Std.Dev / ECR vs ADP, position tabs (PG SG SF PF G F C), print and download. | ROS rankings | Expert consensus doesn't apply here (no experts). Position tabs and spread don't exist on our player tables. |
| **Dense mobile-friendly rail.** Icon plus a short label (Home, Feed, Draft, Playbook, Rankings, Research, DFS, More), a sticky sub-menu per section, and ctrl+K search in the top bar. | Every page | Already shipped (spec 013 rail, `JumpTo` palette). **No change proposed.** |

### Deliberately not borrowed

- The news feed, reactions, polls, expert articles, podcasts and video. These are content businesses
  (the 10-05 research reached the same conclusion).
- Expert consensus rankings (ECR) and "ECR vs ADP". There are no experts here, and inventing a consensus
  would look more certain than the data is.
- DFS, props, betting systems, odds, and the "Coach" AI assistant (spec 020 already covers AI recaps).
- Start/Sit, Waiver and Trade *assistants that issue a verdict*. These depend on projections whose accuracy
  spec 036 is still measuring. This spec does not issue start/sit verdicts (see FR-012).
- The light theme and premium upsell banners (spec 013 §7 already decided against the light theme).

## Amended after planning (2026-10-20)

Reading the code during planning showed three statements above were wrong. They stay in place, and this
note corrects them (see `research.md` R1, R4, R5):

1. **"No page can filter to free agents only" was wrong.** The stat leaderboard already has an
   Availability filter (All / Free agents / Rostered / per roster) and marks your avatar. The player page
   already shows a "Yours" pill. US1 shrinks to four things: one shared mark across the three pages, a
   "My roster" option, the filter kept in the URL, and `isMe` on Trends (which shows owner names only).
2. **US2's "date and time" can't be met.** The stored schedule has a date and no time. The header shows
   the date and opponent only.
3. **SC-005 as written can't be met.** Injury status refreshes once a day (11:00 UTC). It now reads
   "every starter tagged Out *as of the last status refresh*", with that time on the page. A second
   refresh before tip-off is an open follow-up for Allan, not part of this spec.

Also: the stat leaderboard, Trends and player page are basketball-only pages today (`destinations.ts`).
So US1 and US2 are NBA-only by existing scope. US3 (league home) covers both sports.

## Clarifications

### Session 2026-10-20

- Q: Which stories does 042 build? → A: US1–US3 only. US4 and US5 are deferred to a follow-up spec.
- Q: Which player lists get ownership marks? → A: In-season lists only: stat leaders, Trends,
  schedule-grid streaming, player page.
- Q: Should the compare show Sleeper's (RotoWire) projection? → A: No, not until spec 036 measures accuracy.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See at a glance whose player it is (Priority: P1)

A manager scanning an in-season player list (stat leaders, Trends, schedule-grid streaming, player page)
sees right away which players are theirs, which belong to another manager
(and whom), and which are free agents. They can narrow the list to free agents with one control.

**Why this priority**: This was the most-repeated pattern on FantasyPros (three separate surfaces), it uses
only data Ball Knowers already has (current rosters), it works in both sports, and it turns every
existing table into a waiver tool without a new page.

**Independent Test**: Open the stat leaderboard for a league with known rosters. Every row carries one of
three marks that match Sleeper's rosters. Choosing "Free agents" leaves only unrostered players, and
the count shown matches the rows.

**Acceptance Scenarios**:

1. **Given** a signed-in manager in a league with current rosters, **When** they open any in-scope player
   list, **Then** their own players are visually distinct from other managers' players. Other managers'
   rows name the owner, and free agents are labelled as available.
2. **Given** the same list, **When** they choose "Free agents" (or "My roster"), **Then** only unrostered players remain,
   and the filter state survives a reload of that page.
3. **Given** a past season, or a season whose rosters aren't known, **When** the list renders, **Then** no
   ownership marks are shown, the filter is disabled, and a one-line note says why. It never guesses from
   draft results.

---

### User Story 2 - Player page answers "what now" first (Priority: P2)

A manager opening a player sees a header with the facts they act on: who rosters him in this league,
injury status, next game (opponent and tip-off/kickoff time) and positional rank in this league. The
existing detail moves into tabs (Overview, Game log, Schedule, plus the existing advanced stats) instead
of one long scroll.

**Why this priority**: The player page is where every other page links. FantasyPros' header shows that the
answer belongs above the stats, which is spec 013's own "answer first, method one click away" rule
applied to the page it never reached.

**Independent Test**: Open a rostered NBA player on game day. The header shows the owner, injury status,
tonight's opponent and date (no time is stored), and rank. *(Amended after analyze 2026-10-20: no time is stored.)* Each tab shows the same numbers the current page shows.

**Acceptance Scenarios**:

1. **Given** a player with a scheduled game, **When** the page opens, **Then** the header shows the opponent,
   home/away, and the date. *(Amended after analyze 2026-10-20: no time is stored.)*
2. **Given** a player with no remaining game in the season, or a football player whose schedule isn't
   stored, **When** the page opens, **Then** the next-game slot says so plainly rather than disappearing or
   showing a blank.
3. **Given** the page loads with tabs, **When** the viewer switches tabs or shares the URL, **Then** the
   selected tab is in the URL and every number matches the pre-change page for the same player and season.

---

### User Story 3 - "Are my starters playing?" (Priority: P3)

Before lineups lock, a manager sees which of their current starters are out, doubtful/questionable, or
have no game in the current scoring period. Each flagged starter is paired with the bench players who
could replace him.

**Why this priority**: This matters most on a real NBA game night, starting today. It uses injury status
and the stored schedule only. It flags facts and doesn't recommend a start.

**Independent Test**: In a league where one of your starters is tagged Out, league home shows that starter
flagged with the reason and lists your eligible bench players with a game in the period.

**Acceptance Scenarios**:

1. **Given** a starter tagged Out/IR/Suspended, **When** league home loads, **Then** that starter is listed
   under a "Check your lineup" heading with the status and when that status was last refreshed.
2. **Given** an NBA starter whose team has no counted game in the current week at all, **When** league home
   loads, **Then** he is flagged "no game this week". NBA starters are credited one game per week, so
   "no game *left*" isn't a lineup hole. *(Amended after review 2026-10-20, plan-review F6.)*
3. **Given** no starter is flagged, **When** league home loads, **Then** the section shows a single "All
   starters are active" line rather than an empty box.

---

### User Story 4 - Compare two players side by side (Priority: P4) — DEFERRED, not built in 042

A manager picks two players and sees them side by side: owner in this league, recent form (last 7/14/30
days, or recent weeks for football), games this scoring period, and season totals under this league's
scoring. No verdict is issued.

**Why this priority**: This is FantasyPros' most-promoted free tool. A no-verdict version is honest with the
data available now. A verdict waits on projection accuracy (spec 036).

**Independent Test**: Choose two NBA players. Both columns show the same stats each player's own page
shows, and the higher value in each row is marked.

**Acceptance Scenarios**:

1. **Given** two players of the same sport, **When** compared, **Then** each row marks the better value. The
   page never states a recommendation such as "Start X".
2. **Given** one player has too few games for a window, **When** compared, **Then** that cell shows the game
   count and is not marked as better or worse.

---

### User Story 5 - All my teams on one screen (Priority: P5) — DEFERRED, not built in 042

A manager in several leagues sees one summary row per league on the home page: record, standing, this
week's matchup score, and the count of starters flagged by US3.

**Why this priority**: FantasyPros' Multi-League Assistant, and roadmap item 4.2. It is lower priority
because most users here are in one or two leagues.

**Independent Test**: A user in three leagues sees three rows whose numbers equal each league home's own.

**Acceptance Scenarios**:

1. **Given** a user in N leagues for the current season, **When** home loads, **Then** N rows appear, each
   linking to that league's home.
2. **Given** one league fails to load, **When** home loads, **Then** the other rows still render and the
   failed row says it couldn't load.

---

### Edge Cases

- A player rostered by two of your own teams in different leagues. Ownership is per league, and marks
  never mix leagues.
- Multi-position NBA players (spec 025). Position tabs and rank use the existing eligibility rule, not a
  second copy of it.
- A player traded or dropped mid-session. Ownership marks show their "as of" time, and stale marks are not
  presented as live.
- Commissioner-less or orphaned teams. They show as "Roster N" (the existing `PlayerOwnership` label) and are not available. *(Amended after analyze 2026-10-20: was "Unowned team", which the code doesn't do.)*
- A football league where the schedule isn't stored. US2's next game and US3's "no game" check say
  "not available for football yet" instead of guessing.
- Small phone widths (375 px). The ownership marks have to stay readable without colour (shape or text
  too), and the comparison stacks vertically.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every in-scope player list MUST mark each player as one of: yours, another manager's (named),
  or available, for the league and season being viewed.
- **FR-002**: The ownership mark MUST be distinguishable without colour (text or shape as well), to stay
  readable for colour-blind viewers and in the existing dark theme.
- **FR-003**: The stat leaderboard (and the stat calculator, which shares its filter) MUST offer free agents,
  rostered and **My roster** filters that persist in the URL. Trends already splits free agents from
  rostered players, and its streaming table is free agents by construction, so it gets the marks, not a
  filter. *(Amended after review 2026-10-20, plan-review F12.)*
- **FR-004**: When current rosters aren't known for the season shown, the system MUST omit marks, disable the
  filter, and say why in one line. It MUST NOT infer ownership from draft results.
- **FR-005**: Ownership data MUST carry an "as of" time, shown on hover or in a note.
- **FR-006**: The in-scope lists are the in-season ones: stat leaders, Trends, schedule-grid streaming
  candidates, and the player page. Draft boards and roster pages are out of scope. On a draft board,
  "current owner" would be confused with "who drafted him". *(Clarified 2026-10-20.)*
- **FR-007**: The player page MUST show a header with owner in this league, injury status, next game
  (opponent, home/away, date; no time is stored) and positional rank in this league (the existing
  `positionRank`). *(Amended after planning and review 2026-10-20.)*
- **FR-008**: The player page MUST split its current content into URL-addressable tabs without changing any
  number it shows.
- **FR-009**: League home MUST list current starters who are out (the existing Out/IR/Sus rule, or
  suspended), carry any other listed status (Doubtful, Questionable and so on, shown as listed), or (NBA)
  have no counted game in the week, with the status's refresh time. When live starters load, this replaces
  the existing "projected starters with no game" note rather than sitting beside it. *(Amended after review 2026-10-20, plan-review F4, F5, F6.)*
- **FR-010**: Each flagged starter MUST be paired with the viewer's eligible bench players who have a game in
  the week, sorted by season average under league scoring (NBA). The average is labelled with its season and
  game count. Football is sorted by name and says so. This is labelled "options", never "recommended". *(Amended after review 2026-10-20, plan-review F3, F10.)*
- **FR-011**: A two-player comparison MUST show both players' ownership, recent-form windows, games this
  period and season totals under league scoring, and mark the better value per row.
- **FR-012**: The comparison MUST NOT issue a start/sit verdict, and MUST NOT show projections until spec 036
  reports their accuracy. *(Clarified 2026-10-20. Deferred with US4.)*
- **FR-013**: Home MUST show one summary row per current-season league with record, standing, this week's
  score and flagged-starter count. One league's failure MUST NOT block the others.
- **FR-014**: Every new view MUST work in both sports, or state on the page which sport it isn't available
  for yet.
- **FR-015**: This spec builds US1–US3 only. US4 (compare) and US5 (multi-league) stay written here so a
  follow-up spec can start from them, but FR-011–FR-013 are **not built in 042**. *(Clarified 2026-10-20.)*

### Key Entities

- **Ownership mark**: per player × league × season. One of mine / owned-by(manager) / available /
  unknown, plus an "as of" time. Derived from current rosters, which are already ingested.
- **Lineup flag**: per starter × scoring period. The reason (status or no-game), the status refresh time,
  and the bench options.
- **Player comparison**: two players of one sport, a set of stat rows, and a per-row "better" marker
  (or none when the sample is too small).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a real league, ownership marks match Sleeper's rosters for 100% of rows checked across every
  in-scope list (checked against the live roster endpoint, not the local copy only).
- **SC-002**: A manager can go from league home to a filtered list of available players in 2 clicks or fewer.
- **SC-003**: The player page header answers "who owns him / is he playing / when is his next game" without
  scrolling at 1366×900 and at 375×812.
- **SC-004**: All pre-change player-page numbers are identical after the tab split (same player, same
  season, compared value by value).
- **SC-005**: On an NBA game night, every starter tagged Out for the viewer's team *as of the last status
  refresh* appears in the lineup check, and the page shows that refresh time. Live-verified on a real
  night, not only in a fixture. *(Amended after planning: status refreshes daily, research R4.)*
- **SC-006**: No new view contains a recommendation verb ("start", "sit", "add", "drop") as a
  system-issued verdict.

## Assumptions

- Current rosters, Sleeper injury status and the NBA schedule are already ingested (specs 017, 019, 030).
  The football schedule is not stored, so football next-game and no-game checks are out of scope unless
  planning finds otherwise.
- "Signed-in manager" means the existing Sleeper-username sign-in. No new auth.
- The existing dark theme, rail and jump-to palette stay as they are. This spec changes content, not the
  shell.
- FantasyPros' premium tools (Start/Sit Assistant, Waiver Assistant, Trade Analyzer, League Analyzer) were
  not seen beyond their names. If the user signs in later, a short follow-up pass may amend this
  context section, marked "amended after sign-in".
