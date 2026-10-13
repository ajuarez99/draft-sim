# Feature Specification: Custom stat calculator with a ranked chart

**Feature Branch**: `032-stat-calculator`

**Created**: 2026-10-12

**Status**: Draft

**Input**: User description: "ok now that we have a basic stats page i want a calucaltor now. like ways to come up with new stats like usage rate + vorp / turnovers type beat and have that be on a chart to see top players currently or last season"

## Context: what exists, and what that means for this feature *(read first)*

### The stats this calculator can build on (read from `origin/main`, 2026-10-12)

Spec 022 shipped a league-scoped NBA stat leaderboard (`web/src/pages/StatLeaderboard.tsx`).
Its column definitions in `web/src/statLeaderboard.ts` already provide, for every player in the
league's pool, for a chosen season and window (season, last 10, last 5):

- **Basic**: games, minutes, points, rebounds, assists, steals, blocks, threes made, turnovers,
  FG% and FT%. Counting stats are available per game, as totals, or per 36 minutes.
- **Shooting**: FG%, 3P% and FT% with makes and attempts, TS%, eFG%, free throw rate and
  three-point attempt rate.
- **Advanced**: usage rate, share of team minutes, assist, offensive/defensive/total rebound,
  steal, block and turnover rates, and game score per game.
- **Fantasy, in this league's scoring**: fantasy points per game, league rank, position rank,
  points rank, rank move, and **value over replacement (VOR)** in fantasy points per game.
- A season picker covering every stored NBA season, with a fallback to last season while the
  current season has no games, and a minimum-minutes qualification rule for rate stats.

So every input in the user's example except one already exists as a number on the wire. **No
new stat has to be computed from box scores for this feature.** The calculator combines stats
the leaderboard already shows.

### One name collision that must be handled honestly

The user wrote "VORP". On Basketball Reference, VORP means *value over replacement player*
derived from Box Plus/Minus, a regression model with published coefficients that this app does
**not** compute. This app's "VOR" is a different thing: fantasy points per game above a
replacement level set from this league's team count and starting slots (spec 022 US4).

Decision this spec makes: the calculator offers this app's VOR under its own name, "VOR
(fantasy)", with its existing definition. It never labels it "VORP", and it does not add
Basketball Reference's VORP. Computing Box Plus/Minus would be a new model with its own
verification burden. That is out of scope here; see "Not being built".

### Why combining stats needs a rule

The user's example adds usage rate (a percentage, roughly 10–40) to VOR (fantasy points per
game, roughly −10 to +25) and divides by turnovers (roughly 0–5 per game). Raw arithmetic on
those numbers runs, but its ranking is driven mostly by whichever input has the largest spread.
That isn't "wrong", but a user may read the result as a balanced blend of the three when it isn't.
Resolved at clarification (Q1): raw by default, with a "standardize inputs" switch (FR-006).

## Clarifications

### Session 2026-10-12

- Q: When a formula mixes stats with different scales, compute on raw values, offer a
  standardize switch, or standardize by default? → A: Raw by default, plus a "standardize inputs"
  switch that converts each input to standard deviations from the qualifying group's average
  (FR-006).
- Q: Where do saved formulas live? → A: Share links, plus a list saved in the member's own
  browser. Nothing is stored on the server, and the list says it isn't synced (US3 scenario 3).

### Amended at plan (2026-10-12)

Reading the code during `/speckit-plan` changed four things. Each is noted at the point it
applies; details are in `research.md`.

- **SC-002**: the hand-check now runs against the payload's values (R4).
- **US2**: the example preset "points per shot attempt" is replaced, because FGA and FTA aren't
  leaderboard columns (R11).
- **FR-015**: the link also carries filters, qualification and standardization (R10).
- **FR-010**: qualification defaults to on for every formula (R6).

### Amended after plan review (2026-10-12)

The adversarial review (`plan-review.md`) found the plan wrong or incomplete in 13 places. Each
amendment is marked inline as "amended after plan review, F<n>". In short:

- stat names that collide with the formula syntax get clean names (F1);
- in standardized mode, dividing by a stat is blocked rather than warned about (F2);
- the reasons a player can't be computed now come from one shared rule (F3);
- the editor survives a reload of the data (F4);
- there is a highest-first or lowest-first control (F6);
- the rail's season switch keeps the calculator and its formula (F7);
- the scoring sentence names which season's scoring was used (F8);
- filters read from a link are checked against the data (F9);
- the page states how many players qualification hides (F10).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Build a stat from existing stats and see who leads it (Priority: P1)

A league member opens the stat calculator from the league's stats area. They build a formula by
picking stats from the same list the leaderboard uses and joining them with +, −, × and ÷,
using brackets and number constants where needed. For example: (USG + VOR) ÷ TO, with VOR
shown carrying its "(fantasy)" tag. They
name it ("Usage value"). They choose a season and a window. The page then shows a ranked chart
of the top players by the new stat, each bar labelled with the player's name and exact value.
A table below lists every qualifying player with the new stat and each input stat beside it.

**Why this priority**: This is the whole request. Without both the formula and the chart there
is nothing to ship.

**Independent Test**: Build (PTS + AST) ÷ TO for the 2025–26 season with the default
qualification rule. For five players, take PTS, AST and TO from the leaderboard **payload**
and compute the result by hand. It must match the calculator's value to the displayed
precision. The chart's order must match the table's order. *(Amended at analysis: this used to
say to use the leaderboard's displayed values, which conflicts with SC-002 and FR-005.)*

**Acceptance Scenarios**:

1. **Given** a basketball league, **When** a member opens the calculator, **Then** they can pick
   any stat column the leaderboard offers (Basic, Shooting, Advanced, Fantasy). Each one shows
   the same label and definition it has on the leaderboard.
2. **Given** a formula with +, −, ×, ÷, brackets and number constants, **When** it is evaluated,
   **Then** each player's value follows normal operator precedence and brackets. The formula is
   shown back in readable form beside the result, for example
   "(USG + VOR) ÷ TO". The readable form parses back to the same formula. "(fantasy)" appears as
   a visual tag beside VOR, outside the text of the formula. *(Amended after plan review, F1:
   "VOR (fantasy)" inside the formula text could not be parsed back.)*
3. **Given** a valid formula, **When** results are shown, **Then** a ranked horizontal bar chart
   shows the top players (default 15, adjustable from 5 to 50). Each bar shows the player's name
   and the exact value beside it. The bar length encodes the value and nothing else. Bars grow
   from a zero baseline, so negative values extend left and show their sign. *(Amended after plan
   review, F6.)*
3a. **Given** a formula where smaller is better (for example, TO per 36), **When** the member
   picks "Rank by: lowest first", **Then** the chart and table rank from lowest to highest, and
   the chart's title says so. The default is highest first. This is a sort direction, not a sign
   flip. *(Added after plan review, F6.)*
4. **Given** the same results, **When** the member reads the table, **Then** every qualifying
   player appears with the result and every input stat's value in its own column, in the same
   order as the chart. Any column can be sorted, and ties are broken the same way on every load.
   The chart always follows the result's ranking direction, never the table's column sort.
   *(Amended after plan review, F6.)*
5. **Given** an incomplete or invalid formula (for example, an unclosed bracket or two operators
   in a row), **When** the member tries to run it, **Then** the page says what is wrong and
   where. It shows no chart and keeps the last valid result labelled as the previous formula.
   Neither the typed text nor that result is lost when the window changes or the league's data
   refreshes. *(Amended after plan review, F4.)*
6. **Given** a player whose result can't be computed (for example, ÷ TO when he has 0
   turnovers, or an input with "no attempts"), **When** results are shown, **Then** he is left
   out of the ranking and listed below it with the reason in words, for example "0 turnovers, so
   ÷ TO is undefined". He is never ranked as 0 or as infinite. Each reason is the same one the
   leaderboard's cell gives for that missing value, for example "no minutes", "not qualified", or
   "no replacement level at his position". *(Amended after plan review, F3.)*
7. **Given** the season picker and window choice, **When** the member changes either, **Then**
   every value is recomputed for that season and window. The chart's title names both, using the
   app's start-year season numbering (for example, "2025–26, last 10 games"). The fallback to
   last season works and is stated the same way as on the leaderboard.
8. **Given** the per-game, totals or per-36 choice, **When** it changes, **Then** counting
   inputs switch accordingly. The formula's readable form shows which mode applies, for
   example "(per 36)". Inputs that stay per game in every mode (MIN, FPG, VOR, GmSc) are marked
   "per game". Rate stats are unaffected.
9. **Given** a football league, **When** a member opens the calculator, **Then** it says this
   covers basketball only. No partial layout appears.
10. **Given** a formula that uses a fantasy stat (FP/G, VOR, ranks), **When** results are shown,
    **Then** the page names the season whose league scoring produced them, and says the same
    formula can rank players differently in another league. When the page has fallen back to an
    earlier season whose scoring differs from the requested season's, it says so. *(Amended after
    plan review, F8: "this league's scoring" is false on a fallback.)*
11. **Given** qualification on, **When** results are shown, **Then** the page says how many
    players are shown out of how many have games, for example "303 of 582 players". It also gives
    the rule in full, including the last-5/last-10 recency clause, which hides a player with no
    recent game even on a counting formula. *(Added after plan review, F10.)*

---

### User Story 2 - Start from a ready-made formula (Priority: P2)

A member who doesn't want to build from scratch picks a ready-made formula from a short list.
It loads into the editor, where they can run it as is or change it. The list includes the
user's example, with brackets added: `(USG + VOR) ÷ TO`. Written without them it would read as
USG + (VOR ÷ TO), and the preset's description says why the brackets were added. The list also
has a few well-known combinations, for example assist-to-turnover
ratio, "stocks" (steals + blocks) per 36, and points per shot attempt. *(Amended at plan:
points per shot attempt needs FGA and FTA, which aren't leaderboard columns, so "Efficient
volume", PTS × TS% ÷ 100, replaces it. See research R11.)*

**Why this priority**: It makes the page useful in one click and shows newcomers what the
calculator can do. The calculator still works without it.

**Independent Test**: Load each preset and check that its formula, name and plain-language
description agree with each other, and that it produces a chart for the 2025–26 season.

**Acceptance Scenarios**:

1. **Given** the preset list, **When** a preset is chosen, **Then** its formula fills the
   editor and a one-sentence description says what it measures, in plain language.
2. **Given** a preset that mixes units, **When** it is shown, **Then** its description says so
   (see FR-006).
3. **Given** a loaded preset, **When** the member edits it, **Then** it becomes their own
   formula and is no longer labelled as the preset.

---

### User Story 3 - Keep and share a formula (Priority: P2)

A member who builds a stat they like can come back to it later and send it to someone else in
the league, who then sees the same formula and chart.

**Why this priority**: A stat you have to rebuild every visit gets used once. Sharing a link is
how a custom stat becomes a talking point in the league. It is P2 because US1 is useful without
it.

**Independent Test**: Build a formula, copy its link, open it in another signed-in browser
session for the same league, and confirm the formula, name, season, window, mode and chart are
identical.

**Acceptance Scenarios**:

1. **Given** a formula on screen, **When** the member copies its link, **Then** opening the link
   restores the formula, its name, the season, window, mode and top-N setting exactly. This
   applies to links. Saved formulas don't carry filters (N8).
2. **Given** a link opened in a different league, **When** it loads, **Then** the formula still
   runs. Fantasy inputs use the scoring that league's payload names, and FR-012's shared sentence
   says which season's scoring it is. *(Amended at analysis, following F8.)*
3. **Given** a formula the member saved, **When** they return later in the same browser,
   **Then** it appears in a "Saved in this browser" list, which says in those words that it isn't
   synced to their account or other devices. Saved formulas can be renamed and deleted. If the
   browser's storage is unavailable or cleared, the list says it is empty, and share links still
   work. *(Resolved 2026-10-12, Q2: A — links plus a browser-local list; nothing is stored on
   the server.)*

---

### User Story 4 - Compare two stats on a scatter chart (Priority: P3)

A member picks two stats, which can be built-in or custom, and sees every qualifying player
plotted with one stat on each axis. Players who stand out on both are easy to spot. Hovering
or tapping a point shows the player's name and both exact values.

**Why this priority**: It answers "who is good at both?", which a single ranking can't. It
needs US1's custom stat to be most useful, and the ranked chart already covers the request
as stated.

**Independent Test**: Plot USG against TS% for 2025–26. For five points, check both values
against the leaderboard.

**Acceptance Scenarios**:

1. **Given** two chosen stats, **When** the scatter is shown, **Then** both axes are labelled
   with the stat name and unit, and each point's exact values are available on hover or tap.
2. **Given** the scatter on a 375-pixel phone, **When** a point is tapped, **Then** its values
   show without the page scrolling sideways.

---

### User Story 5 - More charts, each offered only when it fits (Priority: P3) *(added 2026-10-13)*

Allan asked, after US1–US4 were built and live-verified, for a distribution chart and a
season-versus-recent chart, and for "any possible ones … only for when it makes sense". The chart
picker becomes **Ranked | Spread | Change | Breakdown | Scatter**:

- Ranked is the default.
- A chart that doesn't fit the current formula or settings stays in the picker but is
  **disabled, with its reason in words**. It is never hidden without a reason, and never drawn
  in a way that misleads.

1. **Spread** (distribution strip): one dot per player with a value, along one axis.
   - The current top N is highlighted. *(Amended after US5 review, C10: "the players the member picks" was not built. Dots are not tab stops; the table under the chart is the keyboard path to every value, and the tooltip serves pointer and touch.)*
   - The median and the 25th/75th percentiles are marked and labelled with their values.
   - **Split by position** draws one strip per position (PG, SG, SF, PF, C) on a shared axis,
     each with its own median. That is the by-position comparison. A multi-position player
     appears in every position he is listed at, and the page says so.
   - *Makes sense*: there are at least 5 values. Splitting is offered only when the position
     filter is "All".
   - *(Amended after US5 review, C7)* A strip with fewer than 5 players shows its dots and count
     but no quartiles, and says "too few players for quartiles".
2. **Change** (dumbbell): for each player, his value in the current view and in a comparison
   view, joined by a line, sorted by the size of the change.
   - The comparison is the **season vs the last 10 games** by default (the "currently" question).
   - *(Amended after US5 review, C1)* The change is always **(more recent view) minus (older view)**,
     whichever control picked which. Recency is Last 5 > Last 10 > Full season, and the current season
     > the previous one. The default reads "Full season → Last 10", and a player heating up is a rise.
     The colours do not say better or worse (C3): the line uses two neutral hues and the legend says
     "higher" and "lower".
     **Last 5** and **the previous season** (same window) are the other choices.
   - The member picks whether it sorts by biggest rise or biggest fall.
   - A player needs a computable value in both views and must pass the filters and the
     qualification switch in both. Anyone missing from either view is counted and listed by
     reason.
   - *(Amended after US5 review, C5)* The list of players starts from the current view's players
     (after the filters and the qualification switch). "Left out" names only those with no value in
     the comparison view, and the reason names that view's window and season.
   - *(Amended after US5 review, C4/C9)* Rows are drawn only when the board on screen and the
     comparison board are the two views the chart names. While a window switch loads, the chart says
     "Updating…" and draws nothing.
   - The two views' exact values are shown beside each dumbbell.
   - *Makes sense*: standardize is **off**. A z-score is relative to its own window's group, so
     two windows' z-scores don't share a scale. The reason is given in words.
   - *(Amended after US5 review, C2)* Also disabled, with its reason, when Counting stats is
     **Totals** (totals over different numbers of games can't be compared) or when the formula
     reads **GP**.
   - *Previous season*: offered only when the league has an earlier stored season. Fantasy
     inputs there use that season's scoring, and the page names it.
3. **Breakdown** (contribution bars): for the top N, each bar is split into the share each
   term of the formula adds, with negative terms drawn left of zero. The parts add up to the
   exact result, which is shown.
   - *Makes sense*: only when the formula is a sum or difference of terms. A term is a stat, a
     constant × a stat, or a stat ÷ a constant, so `usg + vor` and `2 * ast - tov` qualify. For
     anything with × or ÷ between stats, there is no honest additive split, and the reason says
     so. *(Amended after US5 review, C6/C8)* A constant factor outside a bracketed sum is
     distributed over it (`2 * (a + b)`, `(a + b) / 2`, `-(a + b)`), so `(usg + vor) / 2`
     qualifies. A refusal names its actual cause ("multiplies one stat by another", "divides by a
     stat"). The legend shows each coefficient as typed ("PTS ÷ 3", not "0.333 × PTS").
   - With standardize on, the parts are each term's z-score contribution, which is exactly what
     shows whether one input dominates.

**Why this priority**: it adds ways to read a formula, and the ranked chart already answers the
request. Each chart is independently droppable.

**Independent Test**:
1. *Spread*: the median and quartiles for `ast / tov` (2025–26) match a script over the
   payload.
2. *Change*: for 3 players, the season and last-10 values match each window's payload.
3. *Breakdown*: for `usg + vor`, each part matches the input values, and the parts sum to the
   result.
4. Each disabled chart shows its reason: Breakdown for `ast / tov`, and Change with standardize
   on.

**Acceptance Scenarios**:

1. **Given** a formula with at least 5 values, **When** Spread is chosen, **Then** every value
   is a dot, and the median and quartiles are marked with their exact values. Dots that overlap
   are offset so each one stays visible.
2. **Given** Spread split by position, **When** shown, **Then** there is one strip per position
   on a shared axis, each with its median and count.
3. **Given** Change with "season vs last 10", **When** shown, **Then** each dumbbell's two ends
   match the two windows' values for that player. The rows are sorted by the change in the
   chosen direction, and players without both values are counted with reasons.
4. **Given** standardize on, **When** the chart picker is read, **Then** Change is disabled and
   its reason says z-scores from two windows don't share a scale.
5. **Given** `ast / tov`, **When** the chart picker is read, **Then** Breakdown is disabled and
   its reason says the formula isn't a sum of terms.
6. **Given** `usg + vor`, **When** Breakdown is shown, **Then** each bar's parts are labelled,
   add up to the shown result, and negative parts extend left.
7. **Given** any of these charts on a 375-pixel phone, **When** shown, **Then** the page doesn't
   scroll sideways.

---

### Edge Cases

- **Division by zero, or an input with no denominator** (no attempts, 0 turnovers, 0 minutes):
  the player is excluded from ranking and listed with the reason. The result is never 0,
  infinity or "NaN".
- **Small samples**: the leaderboard's qualification rule applies by default and is stated.
  Turning it off is allowed. Players below the threshold then carry the leaderboard's unranked
  reason (`reasonSentence(row.reason)`), not the player page's minutes-based `smallSample`
  flag.
- **Ratios with a tiny denominator**: a player with 0.1 turnovers per game in 3 games can top a
  ÷ TO formula. The qualification rule is the guard, and the page says that it exists for this
  reason.
- **Lower-is-better inputs** (TO, TOV%, ranks): the calculator does not flip their sign. A
  formula means exactly what it says. The stat picker shows the existing "lower is better" note
  beside these inputs so the member can see it while building. The member can choose
  "lowest first" (US1 scenario 3a).
- **Every player excluded** (for example, a formula that divides by a stat nobody has yet this
  season): the page says no player has a computable value and why. It doesn't show an empty
  chart.
- **Before the current season has games**: the calculator opens on last season, as the
  leaderboard does, and says so.
- **A past season's fantasy inputs** use the scoring the leaderboard used, which the page names.
  When the page has borrowed an earlier season's box scores, that is still the requested
  league's scoring, so the page states the scoring season rather than assuming it. *(Amended
  after plan review, F8.)*
- **A link with a team, position or roster filter that doesn't exist in this season's data**: the
  filter is dropped, and a sentence names what was dropped. Changing season drops a roster
  filter, because roster numbers repeat every season. *(Added after plan review, F9.)*
- **A denominator that crosses zero** (for example, ÷ VOR, where players near replacement level
  sit at or near 0): in raw mode, the page warns that the denominator has values at or below
  zero in this group. *(Added after plan review, F2.)*
- **Ties**: they are broken deterministically, using the same rule as the leaderboard, so two
  loads give the same chart.
- **Very large or very small results** (for example, × 1000): values are shown in a readable
  format with sensible precision, and the chart's scale adjusts to fit them.
- **A formula longer than the chart title can hold**: the title shortens it with an ellipsis,
  and the full formula is always shown in the readable form beside the result.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The calculator MUST be reachable from the league's stat leaderboard through a pair
  of links labelled "Leaderboard" and "Calculator". While it is open, the league navigation's
  "Player stats" entry stays highlighted. It is for basketball leagues only. *(Amended after plan
  review, F12 and F13: there is no separate rail item.)*
- **FR-002**: The calculator MUST offer as inputs exactly the stat columns the leaderboard
  defines (Basic, Shooting, Advanced, Fantasy), with the same labels, definitions and
  lower-is-better notes. A stat changed or added on the leaderboard MUST appear in the
  calculator from that one definition, not from a second copy. Each input has one formula name.
  It is the column's label when that label contains only letters, digits and `%`, and otherwise
  a short alias, for example FPG for FP/G or POSRANK for Pos rank. No two inputs share a name.
  *(Amended after plan review, F1 and F5.)*
- **FR-003**: Formulas MUST support +, −, ×, ÷, brackets and number constants, with standard
  precedence. Nothing beyond that is required, so no functions such as log or max.
- **FR-004**: The calculator MUST validate a formula before running it, and report each error in
  words with its position in the formula.
- **FR-005**: Each player's result MUST equal the formula applied to the leaderboard payload's
  values for that player, season, window and mode, which are the values the leaderboard sorts by.
  *(Amended after plan review, F12: the displayed values are rounded. See SC-002.)*
- **FR-006**: The calculator MUST compute on raw values by default and MUST offer a
  "standardize inputs" switch. When the switch is on:
  - each input is converted to how many standard deviations the player sits above or below the
    average of **the players currently shown**: those passing the filters and the qualification
    switch with a value for that input, for the same season, window and mode. This happens
    before the formula runs. *(Amended at analysis: this said "qualifying players", which differs
    from the shown set when qualification is off.)*
  - the result is labelled as standardized, and the page explains it in one plain sentence;
  - the page shows each input's reference group size, mean and standard deviation, so SC-007
    can be checked by hand;
  - the table shows each input's raw value beside it;
  - *(Amended after code review, B1)* a `×` whose two sides both contain a stat is an error while the
    switch is on, because the product of two z-scores ranks a player who is below average on both
    first; a stat times a constant stays allowed. Like the `÷` rule, it is checked when the formula
    is run, loaded from a link, a preset or the saved list.

  When the switch is off and a formula adds or subtracts inputs of different units, the page
  MUST say so in words, and say that the input with the widest spread will drive the ranking.
  With the switch on, dividing by anything that contains a stat MUST be a validation error,
  reported like any other (FR-004). Dividing by a number constant stays allowed.
  *(Amended after plan review, F2: z-scores average zero, so about half the players would be
  excluded, and the rest would be ranked by how close they sit to the average.)* In raw mode, a
  denominator with values at or below zero in the reference group MUST produce a warning in
  words. When inputs share a unit but their spreads differ a lot, the page MUST NOT imply that
  sharing a unit makes them balanced (F11).
  *(Resolved 2026-10-12, Q1: B.)*
- **FR-007**: Players whose result is undefined MUST be excluded from the ranking and listed with
  a plain-language reason. They MUST never be shown as 0, infinity or blank.
- **FR-008**: The ranked chart MUST show the top N players (default 15, from 5 to 50) as
  horizontal bars, with the player's name and exact value beside each bar, ranked highest first
  by default, or lowest first when chosen (F6).
- **FR-009**: A sortable table MUST list every qualifying player with the result and each input's
  value, in the same order as the chart by default. Every player name MUST link to the
  player's page for that league and season.
- **FR-010**: Season, window (season, last 10, last 5), mode (per game, totals, per 36) and the
  minimum-minutes rule MUST behave exactly as they do on the leaderboard, including the
  last-season fallback and its statement. *(Amended at plan: one exception. Qualification
  defaults to on for every formula, because the leaderboard applies it per column, and a ratio of
  two counting stats would escape that rule. See research R6.)* The page MUST show how many
  players are shown out of how many there are, and the full qualification rule, including its
  recency clause (amended after plan review, F10).
- **FR-011**: The chart title MUST name the formula (or its given name), the season and the
  window.
- **FR-012**: Fantasy inputs MUST be labelled with the league scoring season that produced them.
  When the scoring differs from the requested season's, the page MUST say so. The label uses the
  sentence the draft room already uses, from one shared definition. *(Amended after plan review,
  F8.)*
- **FR-013**: The input called VOR MUST carry a visible "(fantasy)" tag wherever it is
  displayed, with its existing definition. The tag is not part of the formula's text. Nothing in
  the calculator may label it "VORP". *(Amended after plan review, F1.)*
- **FR-014**: The page MUST work on a 375-pixel phone without the page scrolling sideways. The
  chart MUST fit the width, and the table MUST scroll inside its own container with the player
  column pinned, as the leaderboard's table does.
- **FR-015**: A formula with its name, season, window, mode and top-N setting MUST be shareable
  as a link that restores all of them exactly. *(Amended at plan: the link also carries the
  filters, the qualification switch and the standardize switch, because SC-004's identical chart
  depends on them. See research R10.)* The link also carries the ranking direction (F6).
  Filter values that don't exist in the season shown are dropped, with a sentence saying so
  (F9).
- **FR-016**: A short list of ready-made formulas MUST be offered (US2), each with a
  one-sentence plain-language description. The list MUST include the user's example, with the
  brackets added and the reason stated (amended after plan review, F12). Presets that rank by
  usage MUST say that usage measures a player's role, not his quality (N6).
- **FR-017**: The calculator MUST be filterable by position, NBA team and availability in this league
  (all, free agents, rostered, or a given manager's roster), the same way the leaderboard is.
- **FR-019** *(P3, US5)*: The calculator MUST offer Spread, Change and Breakdown beside Ranked
  and Scatter. A chart MUST be offered only when it fits, under the rules in US5. Otherwise it
  is disabled and its reason is shown in words.
- **FR-020** *(P3, US5)*: The chart, the Spread split, the Change comparison and the Change sort
  direction MUST be part of the share link (C2).
- **FR-018** *(P3, US4)*: A scatter view MUST plot any two stats, each either a built-in input or
  the current formula, with labelled axes and exact values on hover or tap. Saved formulas
  aren't offered as axes.

### Key Entities

- **Custom stat (formula)**: a name, an expression over leaderboard stat columns, operators and
  constants, and its display settings (window, mode, top N, qualification, standardize and
  ranking direction). Season and filters belong to the link, not to the saved formula.
  It is not tied to a particular season. It is evaluated against whichever season and window are
  chosen.
- **Stat input**: one of the leaderboard's existing column definitions, with its label,
  definition, unit, lower-is-better flag and fantasy-or-real classification.
- **Result row**: one player's computed value, or the reason it couldn't be computed, plus the
  input values it came from.
- **Preset**: a named formula with a plain-language description, maintained with the app.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A member can go from opening the calculator to seeing a ranked chart for a
  three-input formula in under 60 seconds, without reading instructions. *(This is judged in the
  live pass, not measured with users. It is labelled as judged, per plan review F12.)*
- **SC-002**: For 10 players, spread over three formulas including one with ÷, each result
  computed by hand from the leaderboard's displayed values matches the calculator's value to the
  displayed precision. 10 of 10 is the bar. *(Amended at plan: the leaderboard rounds its
  display, so a ratio of displayed values can differ from the true one. For example, a display of
  9.0 ÷ 1.0 can come from payload values of 8.96 ÷ 1.04, which is 8.62. The 10-of-10 check
  therefore uses the leaderboard payload's values. A further check on 3 players confirms that any
  difference from displayed values comes from rounding the inputs. See research R4.)*
- **SC-003**: No result on the page reads "NaN", "Infinity", or a blank, across every preset and
  the edge-case formulas above, checked on the real page.
- **SC-004**: A shared link reproduces the identical chart (same players, order and values) in
  another signed-in session, checked 3 out of 3 times.
- **SC-005**: At 375 pixels wide, the page has no sideways page scroll with a 50-player chart and
  a five-input formula, checked on the real page.
- **SC-006**: Changing the mode or the formula updates the chart with no new request. A window or
  season change is timed in the live pass, from click to chart, both warm and cold, and both
  numbers are reported. The target is under 1 second. A cold number above that is reported, not
  hidden. *(Amended after plan review, F4: the endpoint was never measured.)*
- **SC-007**: With "standardize inputs" on, for 5 players the standardized value of one input,
  computed by hand from the shown group's mean and standard deviation (as displayed on the page),
  matches the page's value to the displayed precision.


## Not being built, and why

- **Basketball Reference's VORP / Box Plus/Minus**: a regression model this app doesn't compute.
  Adding it would mean a new model with its own cross-check against published values. It would
  be its own spec.
- **Stats not already on the leaderboard** (for example, PER or win shares): the calculator
  combines existing columns. It doesn't add new box-score computations.
- **Functions** (log, sqrt, min/max, if/else): four operations and brackets cover the request.
  More can be added later if real formulas need them.
- **Football**: the leaderboard is basketball-only, and so is this.
- **Server-side saved formulas or a league-wide formula list**: declined at clarification (Q2).
  Links and a browser-local list cover keeping and sharing without a new table.
- **Weighting suggestions or "optimal" formulas**: the tool computes what the member writes. It
  doesn't recommend weights, which would present a hand-set choice as principled.

## Assumptions

- The calculator reads the same per-player data the leaderboard already loads for a season and
  window. No new data source or stored stat is needed.
- Player qualification reuses the leaderboard's qualification rule unchanged. Only when it
  applies differs (FR-010).
- The default top-N of 15 is a hand-set display choice, labelled as such, not a principled one.
- Signed-in league members are the audience, as for every other league page.
- Standardization uses the players currently shown (filters plus qualification switch) as its reference group,
  so changing a filter changes standardized values. The page names the group and its size.
- Saved formulas live only in the member's browser. Losing them when storage is cleared is
  accepted, because share links survive.
- The preset list is short (about 5) and maintained with the app, not user-editable.
- The chart follows the project's existing visual rules: one encoding per mark, exact value
  beside each bar, and the dark card style.
