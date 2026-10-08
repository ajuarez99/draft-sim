# Feature Specification: Player stat analysis, nightly and season-long

**Feature Branch**: `022-player-stat-analysis`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "i wanna create a comprehensive analysis of stats each night and also season long on how a player is doing so simple stats but also advanced stats. sleeper has good stuff but basketball reference will probably need to be researched to look into and help with the analysis this is more of a bigger project so we can split into big chunks. also as a design figure out best way to put this into"

## Context: what exists, and what the research found *(read first)*

This section records measured facts and one policy finding. They decide the scope below, so they
come before the stories.

### What the app already shows about NBA players

Every existing surface ranks or summarises players by **fantasy points** or **minutes**. None of
them shows a player's actual stat line.

- **Weekly Report, Best Nights / Best Week** (spec 005): single games and weeks ranked by fantasy
  points.
- **League home spotlight** (spec 014): top of the night, trending and rookies, also by fantasy
  points.
- **Player Trends** (spec 019): minutes risers and fallers, recent and season usage, points per
  minute, and streaming candidates.

The app has no per-player page, no game log, no shooting splits and no advanced rates. A player's
name is not a link anywhere.

### What Sleeper's stored box scores contain (measured 2026-10-07, local DB, NBA 2025)

- **Coverage**: 29,143 rows across 1,232 games, 2025-10-21 to 2026-04-12, in fantasy weeks 1–25.
  That is one row per player per game they appeared in. NBA 2024 has 28,798 rows.
- **Fields per row**: points, total/offensive/defensive rebounds, assists, steals, blocks,
  turnovers, fouls, made and attempted field goals, threes and free throws, plus-minus and
  seconds played. Quarter, half and overtime splits of points, rebounds and assists are also
  stored, as are double-double and triple-double flags. Zero-valued stats are omitted from a row
  rather than stored as 0.
- **Team-total rows**: each game also stores one total row per team (for example `TEAM_LAL`).
  Its seconds field reads as 240 minutes in regulation, plus 25 per overtime period, matching the
  minutes column of a standard box score. The existing usage calculation in spec 019 already
  reads these rows and excludes them from player lists. With both teams' totals stored, the
  team-context rates (usage, rebound, assist, steal and block percentages) and a possessions
  estimate are computable **from data already stored**. No new source is needed for them.
- **Measured trap**: summing every row for one side of a game counts the team row as if it were
  a player. That doubles team minutes to exactly 480. Any new reader must exclude these rows the
  way the existing one does.
- **Per-game team**: a player's own team in a given game is the team whose total row shares the
  same game and home/away side. A traded player is therefore attributed to the correct team for
  each game.

### What Basketball Reference can and cannot be for this project

Read 2026-10-07 from Sports Reference's data use policy and bot traffic page:

- The policy prohibits building "websites or tools based on data you scrape". It also prohibits
  any data store that "competes with or constitutes a material substitute" for its services.
- Automated access is limited to 20 requests a minute. Going over it puts the session "in jail
  for up to a day".
- Bulk data is a paid custom request with a $5,000 minimum.
- The policy also states that facts cannot be copyrighted.

**Decision this spec makes**: Basketball Reference is **not a data source** for this feature. It
plays two roles only:

1. **A reference for definitions.** Stat formulas are public facts. This feature computes every
   advanced stat itself, from Sleeper's stored box scores, using the standard published
   definitions.
2. **A manual cross-check during verification.** A person looks up a small sample of players by
   hand and compares Basketball Reference's figures with ours, within stated tolerances
   (SC-003). Nothing in the product fetches from Basketball Reference, links to it as a data
   feed, or stores anything copied from it.

### Measured: why league-specific values matter (2026-10-07, local DB, NBA 2025)

The local "Ball Knowers" NBA league is 12 teams, points scoring, with 9 starters (PG, SG, G, SF,
PF, F, C, UTIL, UTIL) and 5 bench spots. Points are worth 0.5 each, and steals and blocks are worth
2 each. Ranking players with at least 50 games by fantasy points per game under these weights,
instead of by real points per game, moves players a long way:

| Player | Rank by real points | Rank in this league |
|---|---|---|
| Rudy Gobert | 139 | 37 |
| Donovan Clingan | 118 | 26 |
| Dillon Brooks | 33 | 102 |

The figures come from an ad-hoc SQL approximation: stat × weight, summed over stored keys. That
is not the app's scorer, so exact values may differ, especially for bonus handling. The scale of
the reordering is the finding.

## Clarifications

### Session 2026-10-07

- Q: Which of the league-specific additions discussed should be in scope? → A: All of them.
  - **Chunk 1 (player page)**: league rank and position rank, the move from points rank, and the
    fantasy-points breakdown.
  - **Chunk 2 (advanced stats)**: a comparison group of players rostered in this league.
  - **Chunk 4 (leaderboard)**: value over replacement, draft pick and ADP columns, value vs. draft
    cost, and a stat-leaders view. Chunk 4 moves from P3 to P2.
- Q: Should the player page and leaderboard let members switch to past stored NBA seasons, or show
  only the current season? → A: A season picker on the player page and leaderboard, covering every
  stored NBA season. It defaults to the current season, or to last season until the current one
  has games. The nightly report can also pick nights from past seasons.
- Q: On a phone, how should the leaderboard's many columns be shown? → A: The same table on every
  screen size, split into switchable column groups (Basic, Shooting, Advanced, Fantasy, Draft
  value). The player-name column stays pinned while the rest scrolls sideways.
- Agreed in conversation before this session and recorded here so the trail stays visible:
  - **Data timing**: an opening-night measurement of how soon Sleeper's box scores are complete
    is a dependency of the nightly report.
  - **ESPN**: recorded as the evaluated fallback source. It is not adopted.
  - **Season numbering**: Basketball Reference names a season by its end year, so it must be
    mapped to Sleeper's start-year numbering for the SC-003 cross-check.

## How this is split: four chunks

The user asked for big chunks. Each user story below is one chunk and can be planned, built and
shipped as its own spec-sized piece, in this order. Chunk 1 is the foundation the other three
link into.

| Chunk | Story | What it adds |
|---|---|---|
| 1 | US1: Player page, the basics (P1) | A page per player with a season line, game log, fantasy points under this league's scoring, league rank and position rank (and how far that differs from his rank by real points), and where his fantasy points come from. Every player name becomes a link to it. |
| 2 | US2: Advanced stats on the player page (P1) | Efficiency, team-context rates, per-36, game score, splits over recent windows, and percentiles both against NBA position peers and against players rostered in this league. |
| 3 | US3: The nightly report (P2) | One page per night: every game played, the night's best lines by real-basketball and fantasy measures, your roster's lines, and flagged standout performances. |
| 4 | US4: Leaderboard, stat leaders and draft value (P2) | A sortable, filterable table of every player, including free agents. It has draft pick, ADP, value over a replacement player and value vs. draft cost columns, plus a stat-leaders view showing the top players in each category. |

## Design: where this lives

The user asked for a placement recommendation. The design rests on four decisions:

- **The player page is the hub, and it is league-scoped.** Real-basketball stats are the same in
  every league, but fantasy points, ownership ("on whose roster") and roster flags differ. The
  page therefore sits under a league, like every other page in the app, and shows both kinds of
  number, each labelled.
- **It opens from every player name.** Rows in the spotlight, Weekly Report, Player Trends,
  roster and Superlatives views that name an NBA player link to the player's page (amended
  2026-10-07, review F11: League Analysis is NFL-only, so it can't link to a basketball page).
  Draft boards are
  not league pages, so linking them is out of scope here. A detail that only one entry point can
  reach would get little use.
- **The nightly report is a new page in the league's navigation, beside the Weekly Report.**
  The Weekly Report is organised by fantasy week. The nightly report is organised by one
  calendar night, because NBA games are played nightly. The home spotlight's "top of the night"
  links to it.
- **The leaderboard is a new page in the league's navigation.** It is a table, because the
  content is tabular: many players compared on the same columns. Cards would not suit it.
  Its columns are split into groups the member switches between (Basic, Shooting, Advanced,
  Fantasy, Draft value), and the player-name column stays pinned. Phones use this same layout;
  it is not swapped for cards.

Visual direction follows the project's existing choices. Card and avatar layouts are used where
the content is one player or one game. Grids are used where the content is many players by many
stats. Exact values sit beside any bar. One mark encodes one thing.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Player page: how is this player doing (Priority: P1)

A league member clicks any NBA player's name anywhere in the app. They land on that player's page
for this league. It shows who the player is (photo, NBA team, positions) and who has them on a
roster in this league, or that they are a free agent. It then shows the season so far in
traditional stats (per-game averages and totals, with shooting splits) and the player's fantasy
points per game under this league's scoring. It also shows his rank in this league, both overall
and at his position, and how far that rank differs from his rank by real points per game. A
breakdown shows which stats his fantasy points come from under this league's weights. Below that
is a game log with one row per game,
newest first, giving the date, opponent, home or away, minutes, the full stat line and the
fantasy points that game was worth in this league.

**Why this priority**: The app has no answer to "how is he actually playing?" beyond a fantasy
point total. Every later chunk links here.

**Independent Test**: Open the page for a player with a full 2025 season, and check his season
averages and five of his game-log rows against the stored box scores. Then open the page for a
player who changed teams mid-season, and check that each game shows the team he played for that
night.

**Acceptance Scenarios**:

1. **Given** a basketball league and a player with stored games this season, **When** a member
   opens the player's page, **Then** the page shows:
   - games played and minutes per game;
   - points, rebounds, assists, steals, blocks, turnovers and threes made per game;
   - field goal, three-point and free throw percentages, each with makes and attempts;
   - fantasy points per game under this league's scoring.

   Each figure is labelled as either a real-basketball stat or a fantasy figure.
2. **Given** the same page, **When** the member reads the game log, **Then** each game played
   appears once, newest first, with its date, opponent, home or away, minutes, full stat line and
   this league's fantasy points for that game.
3. **Given** a player traded mid-season, **When** the game log is shown, **Then** each game shows
   the team the player played for that night, and the season line says the player played for
   more than one team.
4. **Given** a player who missed team games, **When** the page is shown, **Then** it states how
   many of his team's games he missed. Missed games are not shown as zero-stat rows.
5. **Given** any player name in the spotlight, Weekly Report, Player Trends, Roster Management
   or Superlatives views of a basketball league (amended at review, F11: Superlatives replaces
   League Analysis, which is NFL-only), **When** the member clicks it, **Then** they land on that
   player's page for the same league.
6. **Given** a player with no games yet this season (before opening night, or a rookie yet to
   debut), **When** the page is opened, **Then** it says no games have been played yet. It
   never shows an empty table or zeros. Before the current season has any games, the page opens
   on last season, and the season picker says so.
7. **Given** a football league, **When** a member opens a player page, **Then** it states that
   this analysis covers basketball only. No partial basketball layout appears.
8. **Given** a player with stored games, **When** the page is shown, **Then** it shows:
   - his fantasy rank in this league, overall and at his position, among players meeting a
     stated minimum-games rule;
   - his rank by real points per game in the same group;
   - the difference between the two, in places.

   Each rank names the group it was taken over and that group's size.
9. **Given** the same page, **When** the fantasy-points breakdown is shown, **Then** it lists the
   share of his season fantasy points from each scoring stat, under this league's weights. The
   shares add up to his total. Negative contributions, such as turnovers, are shown as negative,
   not hidden.
10. **Given** two basketball leagues with different scoring, **When** the same player's page is
    opened in each, **Then** the real-basketball figures are identical, and the fantasy figures,
    ranks and breakdown each reflect that league's own scoring.
11. **Given** the season picker, **When** the member chooses any stored NBA season, **Then** every
    figure on the page is computed over that season. Fantasy figures, ranks and the breakdown use
    that season's league scoring and rosters, not the current season's. The chosen season is
    named on the page, using Sleeper's start-year numbering (for example, "2025–26").

---

### User Story 2 - Advanced stats on the player page (Priority: P1)

On the same player page, a member opens an "Advanced" view of the season. It shows:

- **Efficiency**: true shooting percentage, effective field goal percentage, free throw rate and
  three-point attempt rate.
- **Role**: usage rate, plus the player's share of his team's minutes.
- **Contribution rates**: assist, offensive rebound, defensive rebound, total rebound, steal,
  block and turnover percentages.
- **Per-36-minute** production.
- **Game score** per game.
- **Plus-minus per game**, labelled as noisy.

Each stat has a short plain-language definition available beside it. Every advanced figure can
be compared across windows (last 5 games, last 10 games, season) and against league peers at the
same position, as a percentile among qualified players.

**Why this priority**: This is the "advanced stats" half of the request, and it is the part
Sleeper does not show. It shares P1 with US1 because the user named both halves. It is listed
second because it adds a view to US1's page.

**Independent Test**: For five players with full 2025 seasons, compare true shooting percentage,
usage rate and total rebound percentage with the values Basketball Reference publishes for the
same players and season, looked up by hand. They must fall within the tolerances in SC-003.

**Acceptance Scenarios**:

1. **Given** a player with stored games, **When** the Advanced view is opened, **Then** every
   stat listed above is shown for the season and for the last 5 and last 10 games, side by side.
2. **Given** a window with fewer games than its size (for example, 3 games played when the window
   is "last 5"), **When** it is shown, **Then** it states the number of games it actually covers.
   It does not silently present 3 games as 5.
3. **Given** a stat whose denominator is zero for a window (for example, no three-point attempts),
   **When** it is shown, **Then** it reads as "no attempts" or similar. It never shows 0%, a blank
   or "NaN".
4. **Given** the percentile against peers, **When** it is shown, **Then** it names the peer group
   (position and minimum-minutes rule), the number of players in it, and the player's exact value
   beside the percentile. A player below the qualification threshold has his values shown but is
   not ranked, and the page says why.
5. **Given** a player with very few minutes, **When** rates are shown, **Then** they carry a
   small-sample label that is visibly different from a full-season figure.
6. **Given** any advanced stat, **When** the member asks what it means, **Then** a one- or
   two-sentence definition is available beside it in plain language. Formula notation is not
   required.
7. **Given** the percentile view, **When** the member switches the comparison group to "rostered
   in this league", **Then** percentiles are recomputed among players currently on a roster in
   this league. The group's size and ownership refresh time are stated, and the NBA-wide position
   percentile stays available beside it.

---

### User Story 3 - The nightly report: what happened last night (Priority: P2)

A member opens the league's nightly report. It defaults to the most recent night with completed
games, and other nights can be chosen. For that night it shows:

- every game played, with the final score;
- the night's best individual performances, ranked by game score (real basketball) and,
  separately, by this league's fantasy points;
- every player on the member's own roster who played, with their line;
- standout lines worth noticing, such as a season high in points or minutes, a shooting night far
  above or below the player's season norm, or a big minutes jump for a player on a free-agent
  list.

Every player name links to that player's page from US1.

**Why this priority**: This is the "each night" half of the request. It depends on US1's page for
drill-down and on US2's stats for game score and the standout rules, so it comes after both.

**Independent Test**: Pick a past night in the 2025 season. Check the list of games and final
scores, the top three by game score and the member's roster lines against the stored box scores
for that date. Check that every flagged standout line meets its stated rule.

**Acceptance Scenarios**:

1. **Given** a night with completed games, **When** the report opens, **Then** every game from
   that night is listed with both teams and the final score.
2. **Given** that night, **When** the rankings are shown, **Then** the game-score and
   fantasy-point rankings are separate and each is labelled. Each entry shows the exact value and
   the player's line.
3. **Given** a signed-in member with a roster in this league, **When** the report is shown,
   **Then** their players who played that night appear in their own section, and so do their
   players who were on a team that played but did not play themselves.
4. **Given** a standout line, **When** it is flagged, **Then** the flag states which rule it met
   in words (for example, "season high in points: 41, previous high 33"). No line is flagged
   without a stated reason.
5. **Given** a night whose games are still in progress or not yet fully stored, **When** the
   report is shown, **Then** it says the night is incomplete and when the data was last
   refreshed. It does not present partial totals as final.
6. **Given** a night with no NBA games, **When** it is selected, **Then** the report says there
   were no games. It does not show empty sections.

---

### User Story 4 - Leaderboard, stat leaders and draft value (Priority: P2)

A member opens a leaderboard of every NBA player who has played this season. It is a table with
traditional and advanced columns. The member can sort by any column, choose the window (season,
last 10, last 5), show per-game, totals or per-36 figures, and filter by position, NBA team and
availability in this league (free agents only, rostered only, or a given manager's roster).
Every name links to the player's page.

The table also carries league-specific value columns:

- **This league's draft**: the pick and round where the player was taken and who took him, or
  "undrafted".
- **Preseason ADP**, labelled with its source and capture date.
- **Value over a replacement player** for this league's shape, in fantasy points per game.
- **Value vs. draft cost**: the pick's value over its draft slot, taken from Draft Grades (see the
  FR-034 amendment), so the draft's steals and busts can be sorted to the top.

A **stat-leaders view** shows the top 5 players in each category on one screen: points,
rebounds, assists, steals, blocks, threes, true shooting %, usage rate and this league's fantasy
points per game. Each entry shows its exact value.

**Why this priority**: Draft value and replacement value are the in-season questions league
members ask most, and only a league-scoped view can answer them. This was moved from P3 to P2 at
clarification. It still comes after US1 and US2, because it reuses every number they define.

**Independent Test**:

1. Sort by true shooting percentage with the default minimum-minutes filter and the free-agent
   filter on. Check that the top five rows are free agents in this league, appear in descending
   order, and match each player's page.
2. For the completed 2025 draft, check five players' pick, round and drafting manager against
   the stored draft.
3. Sort by value vs. draft cost, and check that the top five rows' values equal those picks'
   values on the Draft Grades page for the same draft.

**Acceptance Scenarios**:

1. **Given** the leaderboard, **When** any column header is chosen, **Then** rows sort by that
   column. Ties are broken deterministically, so two loads give the same order.
2. **Given** the free-agent filter, **When** it is applied, **Then** only players on no roster in
   this league are shown. The page says when ownership was last refreshed.
3. **Given** the default view, **When** it loads, **Then** a minimum-minutes qualification
   applies to rate stats and is stated on the page. The member can turn it off.
4. **Given** any value in the table, **When** the same player's page is opened for the same
   window, **Then** the same value is shown there.
5. **Given** a season whose league draft is complete, **When** the draft columns are shown,
   **Then** every drafted player shows his pick, round and drafting manager. Every other player
   reads "undrafted" and is excluded from value vs. draft cost, never ranked as if picked last.
6. **Given** a season with no stored ADP (NBA 2025 has none, as measured), **When** the ADP
   column is shown, **Then** it reads "no ADP stored for this season". It is never blank or zero.
   **Given** a season with ADP, **Then** the column names its source and capture date. Sleeper's
   search rank is never labelled as ADP.
7. **Given** value over replacement, **When** it is shown, **Then** the page states:
   - the replacement rule, derived from this league's team count and starting slots and labelled
     as a simplification;
   - the replacement level it produced, in fantasy points per game, for each position.
8. **Given** the stat-leaders view, **When** it is shown, **Then** each category lists its top 5
   with exact values, under the same window and minimum-minutes rule as the table. Ties are
   broken deterministically, and every name links to the player page.
9. **Given** a league whose draft has not happened yet (for example, NBA 2026 before 10-10),
   **When** the leaderboard is shown, **Then** the draft and value vs. draft cost columns say the
   draft has not happened. The rest of the table still works.
10. **Given** the leaderboard on any screen width, including a 375-pixel phone, **When** the
    member switches column group (Basic, Shooting, Advanced, Fantasy, Draft value), **Then**:
    - only that group's columns are shown;
    - the player-name column stays pinned while the rest scrolls sideways within the table;
    - the current sort, window and filters are kept;
    - the page itself never scrolls sideways.

---

### Edge Cases

- **Team-total rows**: they must never appear as players, count toward player rankings or
  percentiles, or be double-counted into team totals.
- **All-Star and exhibition games**: games outside the regular-season team set, such as the
  All-Star game, are excluded from every player stat and from the nightly report's game list, as
  they already are elsewhere.
- **Omitted zero stats**: a stat missing from a stored row counts as 0 for that game. A game with
  no row means the player did not appear. These two cases are never confused.
- **Stat corrections**: stored games are re-fetched and can change after the fact. Pages show the
  current stored values and the time of the last refresh. They do not freeze a night's figures.
- **Traded players**: season rates that depend on team totals use each game's own team. The
  season line marks a multi-team season.
- **Overtime**: team minutes include overtime, so per-36 and team-share figures stay correct in
  overtime games.
- **A player not in this app's player list** (a two-way or end-of-bench player, for example):
  stored games still count in team totals and on the nightly report. The player page shows what
  is known and labels missing identity details as unknown.
- **Before the season starts**: the player page, nightly report and leaderboard open on last
  season and say the current season has no games yet. A member who picks the current season
  explicitly sees that statement, not an empty table. Because seasons are URLs, an explicit pick
  and the default link are the same page, so the fallback note is that statement (amended at
  review, N15). The same note also shows who owns the player now, in the newer season, labelled
  separately (F6).
- **A past season's ownership**: "rostered", "free agent" and "your roster" for a past season or
  night come from that season's stored rosters. Where none are stored for that point in time, the
  page says ownership is unavailable rather than showing today's rosters.
- **A past season's league**: each season's league is its own stored league, linked to the
  previous one. Fantasy figures for a past season use that season's scoring settings. If no
  league is stored for a season, the picker does not offer that season.
- **A football league**: every page in this feature says it covers basketball only.

## Requirements *(mandatory)*

### Functional Requirements

**Data and computation (all chunks)**

- **FR-001**: Every stat in this feature MUST be computed from the box scores and team totals the
  app already stores from Sleeper. No figure may be fetched from, copied from or stored from
  Basketball Reference or any other site whose terms prohibit it.
- **FR-002**: Each advanced stat MUST use one published standard definition, recorded in the
  feature's documentation together with where the definition was taken from. Where Sleeper's
  data forces a deviation from the standard formula, the deviation MUST be recorded and visible
  to the reader in the stat's definition text.
- **FR-003**: Team-total rows MUST be used only as team context, never as players. Games outside
  the regular-season team set MUST be excluded, matching existing behaviour.
- **FR-004**: Real-basketball stats MUST be identical across leagues for the same player, season
  and window. Fantasy figures MUST use the viewing league's own scoring settings.
- **FR-005**: Every figure shown MUST be labelled as either a real-basketball stat or a fantasy
  figure. The two MUST never share an unlabelled column.
- **FR-006**: A window with fewer games than its nominal size MUST state the number of games it
  covers. A rate with a zero denominator MUST show a stated "none" value, never 0, blank or an
  error token.
- **FR-007**: Rates from small samples MUST carry a visible small-sample label. The threshold is
  a hand-set value, labelled as arbitrary in configuration.
- **FR-008**: Pages MUST state when the underlying data was last refreshed. A night or season
  with incomplete data MUST say so.
- **FR-009**: Rankings and sorts MUST be deterministic, with exact ties broken by a stated
  secondary order.

**US1: player page**

- **FR-010**: The system MUST provide one page per NBA player per league. It shows identity, NBA
  team, positions, and ownership in this league (owner or free agent).
- **FR-011**: The player page MUST show a season line of traditional stats (per-game averages
  and totals, shooting makes, attempts and percentages) and fantasy points per game in this
  league's scoring.
- **FR-012**: The player page MUST show a game log with one row per game played, newest first:
  date, opponent, home or away, the team played for, minutes, the full stat line and this
  league's fantasy points.
- **FR-013**: The player page MUST state the number of team games missed, and MUST NOT show
  missed games as zero-stat rows.
- **FR-014**: Every display of an NBA player name in the league-scoped pages listed in US1
  scenario 5 MUST link to that player's page in the same league.
- **FR-015**: A player with no games in the chosen season MUST be shown with a stated reason,
  never as an empty table or zeros.

**US2: advanced stats**

- **FR-016**: The Advanced view MUST show the following for the season, the last 5 games and the
  last 10 games:
  - **efficiency**: true shooting %, effective FG %, free throw rate, three-point attempt rate;
  - **role**: usage rate and share of team minutes;
  - **contribution rates**: assist %, offensive rebound %, defensive rebound %, total rebound %,
    steal %, block % and turnover %;
  - **per-36-minute** traditional stats;
  - **game score** per game;
  - **plus-minus per game**, labelled as noisy.
- **FR-017**: Each advanced stat MUST have a plain-language definition available beside it.
- **FR-018**: Each advanced stat MUST be ranked against same-position peers who meet a stated
  minimum-minutes qualification, as a percentile. The page shows the peer count and the player's
  exact value. Unqualified players are shown but not ranked, with the reason stated.

**US3: nightly report**

- **FR-019**: The system MUST provide a nightly report per league that defaults to the most
  recent night with completed games and lets the member choose any night of any stored NBA
  season.
- **FR-020**: The nightly report MUST list every game from the chosen night with final scores,
  and MUST rank that night's individual lines separately by game score and by this league's
  fantasy points.
- **FR-021**: The nightly report MUST show the signed-in member's own rostered players who played
  that night, and separately those whose team played but who did not.
- **FR-022**: The nightly report MUST flag standout lines only by named rules. Each flag MUST
  state its rule and the comparison values. The initial rules are:
  - a season high in points, rebounds, assists or minutes (shown only after a stated minimum
    number of prior games);
  - a true shooting % far above or below the player's season figure, at a stated minimum number
    of attempts;
  - a minutes figure well above the player's recent average for a player on no roster in the
    league.

  Every threshold in these rules is a hand-set value labelled as arbitrary.

**US4: leaderboard**

- **FR-023**: The system MUST provide a league-scoped leaderboard of every player with games in
  the chosen season. It is sortable by any traditional or advanced column, with a choice of window (season,
  last 10, last 5) and mode (per game, totals, per 36).
- **FR-024**: The leaderboard MUST filter by position, NBA team and league availability (free
  agents, rostered, or one manager's roster). It MUST state when ownership was last refreshed.
- **FR-025**: The leaderboard MUST apply a stated, removable minimum-minutes qualification to
  rate stats by default. A value shown on the leaderboard MUST equal the value the player's page
  shows for the same window.
- **FR-038** (added at clarification): The leaderboard MUST present its columns in switchable
  groups (Basic, Shooting, Advanced, Fantasy, Draft value), using the same table layout at every
  screen width.
  - The player-name column MUST stay pinned while other columns scroll sideways inside the table.
  - Switching group MUST keep the current sort, window and filters.
  - Sorting by a column outside the current group is not offered.

**Placement**

- **FR-026**: The nightly report and leaderboard MUST appear in the league's navigation for
  basketball leagues. The home spotlight's "top of the night" MUST link to the nightly report
  for that night.

**League-specific values (added at clarification, 2026-10-07)**

- **FR-027** (US1): The player page MUST show the player's fantasy rank in this league, overall
  and at his position, together with his rank by real points per game. Both ranks are taken over
  the same stated group (a minimum-games rule, hand-set and labelled arbitrary), and the page
  shows the difference between them.
- **FR-028** (US1): The player page MUST show a breakdown of the player's season fantasy points by
  scoring stat, under this league's weights. Negative contributions are shown as negative, and
  the parts sum to his total.
- **FR-029**: Every fantasy figure in this feature MUST be computed by the app's existing scoring
  of a stored game under the league's scoring settings. A second implementation of the same
  scoring rule is not allowed.
- **FR-030** (US2): Percentiles MUST be available against a second comparison group: players
  currently on a roster in this league. The group's size and ownership refresh time are stated,
  alongside the NBA-wide position percentile.
- **FR-031** (US4): The leaderboard MUST show each player's draft pick, round and drafting manager
  from this league's draft for the season. A player not drafted reads "undrafted", and a season
  whose draft has not happened says so.
- **FR-032** (US4): The leaderboard MUST show preseason ADP for the season where it is stored,
  labelled with its source and capture date. A season with none MUST read "no ADP stored for
  this season". A search rank MUST NOT be labelled as ADP.

  > **Amended at planning, 2026-10-07** (research R10): ADP snapshots carry no season. A season's
  > ADP is the latest blend capture on or before that season's draft. The blend is itself built
  > from Sleeper search rank plus observed drafts, so its label names that composition rather
  > than calling it plain ADP.
- **FR-033** (US4): The leaderboard MUST show value over a replacement player, in fantasy points
  per game. Replacement level is derived from this league's team count and starting slots by a
  stated rule, labelled as a simplification. The replacement level per position is shown on the
  page.
- **FR-034** (US4): The leaderboard MUST show value vs. draft cost for drafted players only. Where
  this overlaps the existing draft grades, it MUST reuse their figures rather than recompute a
  second version of the same rule.

  > **Amended at planning, 2026-10-07** (research R9): this first read "league rank against draft
  > position". Draft Grades already answers "how did this pick do", as `valueOverSlot` against a
  > fit of pick number. A rank-vs-pick column would be a second rule that disagrees with it,
  > because Draft Grades credits the weeks the league counted, while FP/G counts every game. So
  > value vs. draft cost **is** Draft Grades' figure, shown beside the pick and linking to that
  > page. League rank by FP/G stays its own, separately labelled column.
- **FR-035** (US4): A stat-leaders view MUST show the top 5 in each of points, rebounds, assists,
  steals, blocks, threes, true shooting %, usage rate and this league's fantasy points per game,
  with exact values. It uses the table's current window and qualification rule, and breaks ties
  deterministically.

**Season choice (added at clarification, 2026-10-07)**

- **FR-037**: The player page and leaderboard MUST offer a season picker covering every NBA season
  with a stored league and stored games.
  - **Default**: the current season, or the most recent season with games while the current one
    has none. The page says when it has fallen back.
  - **Past seasons**: every figure is computed over the chosen season, using that season's league
    scoring, draft, ADP and rosters. Where a past season's rosters are not stored, ownership reads
    as unavailable.

**Data timing (agreed before clarification, 2026-10-07)**

- **FR-036** (US3): Before the nightly report is accepted, the delay between an NBA game going
  final and its complete box score being stored MUST be measured on real 2026 regular-season
  games and recorded. The report's "incomplete night" behaviour (US3 scenario 5) MUST be set from
  that measurement, not from a guess.

### Key Entities

- **Player game line**: one player's box score for one game. It records the player's team and
  opponent that night, home or away, minutes, all counting stats, and plus-minus. It is the
  stored row this feature reads; it is not new data.
- **Team game totals**: one team's box-score totals for one game, already stored. It provides
  the denominators for team-context rates and the possessions estimate.
- **Stat window**: a span of a player's games (season, last 5, last 10, or one night). Every
  aggregate is computed over a window and states how many games it covers.
- **Stat definition**: a named stat with its formula source, any data-driven deviation, a
  plain-language explanation, and whether it is real-basketball or fantasy.
- **Peer group**: the players compared for a percentile. It is defined by position and a
  minimum-minutes qualification, and carries a stated size.
- **Night**: one calendar date of NBA games. It holds its games, final scores, ranked lines and
  flagged standouts, and states whether it is complete.
- **Standout flag**: a named rule, the values that triggered it, and the line it is attached to.
- **League rank**: a player's position in an ordering of a stated group by fantasy points per game
  under one league's scoring. It is computed overall and per position, and paired with the same
  group's ordering by real points per game.
- **Fantasy-points breakdown**: one player's season fantasy points split by scoring stat under one
  league's weights. The parts sum to the total.
- **Replacement level**: for one league and position, the fantasy points per game of the player
  the stated rule identifies as the best non-starter, given the league's team count and starting
  slots.
- **Draft cost**: the pick, round and drafting manager for a player in one league's draft for one
  season, or "undrafted". This is existing stored data.
- **ADP reading**: a preseason average draft position with its source and capture date. It is
  existing stored data, and is absent for seasons before the snapshot window.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From any page in a basketball league that names a player, a member reaches that
  player's page in one click. This is checked on every page listed in US1 scenario 5.
- **SC-002**: For 10 players with full 2025 seasons, every traditional per-game average and
  shooting percentage on the player page equals the value recomputed directly from the stored
  box scores, to the displayed precision. The test passes only with zero mismatches.
- **SC-003**: For at least 5 players with full 2025 seasons, looked up by hand on Basketball
  Reference, our figures fall within these tolerances:
  - true shooting %: within 0.5 percentage points;
  - effective field goal %: within 0.5 percentage points;
  - usage rate: within 1.0 percentage point;
  - total rebound %: within 1.0 percentage point.

  Any stat outside tolerance is reported with its actual difference and an explanation. It is
  not adjusted to match.
- **SC-004**: For 3 past nights, the nightly report's game list and final scores match the
  stored totals for every game, and every flagged standout line meets its stated rule when
  checked by hand.
- **SC-005**: No figure on any page in this feature shows 0%, a blank, "NaN" or an error token
  where the true answer is "no attempts" or "not enough games". This is checked against at least
  one zero-attempt and one small-sample case per stat family.
- **SC-006**: Player page, nightly report and leaderboard each become usable within 2 seconds
  of opening on the deployed site, for a basketball league with a full season stored. This is
  measured, not estimated.
- **SC-007**: A member unfamiliar with advanced stats can say in their own words what usage rate
  and true shooting percentage mean, using only the definitions on the page. This is checked
  qualitatively with at least one league member.
- **SC-008**: For the 2025 season, the league rank and fantasy points per game of 10 players match
  the app's existing fantasy figures for the same games. That figure is the one the Weekly Report
  and Player Trends already use. The test passes only with zero mismatches. The fantasy-points
  breakdown of each of the 10 sums to its total.
- **SC-009**: For the completed 2025 league draft, every drafted player's pick, round and drafting
  manager on the leaderboard matches the stored draft. No player marked "undrafted" appears in
  the stored draft.
- **SC-010**: On a 375-pixel-wide phone, every leaderboard column group can be read with the
  player's name visible on every row, and the page never scrolls sideways. This is checked in a
  real browser at that width for all five groups.

## Assumptions

- **Basketball only.** Football leagues get a clear "basketball only" message on every page in
  this feature. Football box-score analysis is a different problem with different data, and is
  not part of this request.
- **Composite all-in-one metrics are out of scope.** PER, BPM, Win Shares, VORP and offensive or
  defensive rating are deferred:
  - they depend on league-wide constants and adjustments, and in some cases on play-by-play
    data, that Sleeper's box scores do not carry;
  - an approximation could not be matched to Basketball Reference's published figure;
  - a number branded "PER" that disagrees with the well-known one would mislead.

  A later spec can measure whether a close-enough version is buildable.
- **No new data source.** Sleeper's stored box scores and team totals are enough for every stat
  in scope, as measured above. Quarter and half splits are stored and could feed later standout
  rules, such as fourth-quarter scoring, but are not required by this spec.
- **Refresh timing.** The nightly report reads whatever the existing automatic data refresh has
  stored. It does not trigger fetches when a page is viewed. How soon after a night's last game
  the data is complete depends on that refresh. For NBA this is still unmeasured, and the first
  night of the 2026 season is the first chance to measure it.
- **Season under test.** NBA 2026 begins later in October 2026. Verification uses the complete,
  stored 2025 season, and the 2026 season is checked once games exist.
- **Positions for peer groups.** Positions are taken from the player's stored eligible positions.
  A multi-position player is compared within his first listed position. This is a labelled
  simplification.
- **Hand-set thresholds.** Every threshold is a hand-set value, labelled as arbitrary in
  configuration, consistent with the project's existing practice. This covers the small-sample
  label, the peer qualification and the standout-rule cutoffs.
- **Ownership** comes from the existing roster data and carries that data's own refresh time.
- **Replacement rule.** The exact rule is a plan-stage decision. For example, it could fill every
  team's starting slots by fantasy points per game in slot order and take the best player left
  over at each position. Whatever the rule, it is a labelled simplification, not a claim about
  real waiver availability.
- **Draft and ADP data** already exist:
  - this league's NBA drafts for 2024 and 2025 are stored as complete, with 168 picks each, and
    the 2026 draft is pending, per memory on 2026-10-10;
  - NBA ADP is stored only from four 2026 preseason snapshots, dated 2026-09-08 to 09-28, from two
    sources (a blend, and Sleeper's search rank);
  - no NBA pick carries ADP at the time it was made.

  So NBA 2025 shows draft position but no ADP.
- **Draft grades overlap.** Spec 018's draft grades already score each pick on how it actually
  played. Value vs. draft cost reuses them where the two rules coincide, rather than running a
  second implementation of one rule.
- **ESPN as fallback.** On 2026-10-07, ESPN's public scoreboard and game summary returned full
  final box scores within hours of preseason games. They included minutes, shooting splits,
  offensive and defensive rebounds, plus-minus, team totals and play-by-play. NBA.com's
  endpoints were blocked from this machine. ESPN is recorded as the evaluated fallback if Sleeper
  proves too slow (FR-036). It is not adopted, its terms of use have not been reviewed, and the
  API is undocumented and unofficial.
- **Basketball Reference season numbering.** Basketball Reference names a season by its end year
  (Sleeper's 2025 season is Basketball Reference's NBA_2026). SC-003's cross-check uses that
  mapping.
- **Plus-minus** is shown as provided by Sleeper. It is single-game, on-court plus-minus, labelled
  as noisy, and is not adjusted.
