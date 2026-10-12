# Feature Specification: The season after the draft, before the games

**Feature Branch**: `030-post-draft-season-view`

**Created**: 2026-10-11

**Status**: Draft

**Input**: User description: "now that the draft is done we are in a weird state of the league where it shows stuff from last year and not like who's the favorite this year and who's got the strongest team and relevant stats for this year. you can also cut up some research points to see what other sites use during this time"

## Background

The "Ball Knowers" NBA 2026 draft finished on 2026-10-10. The NBA regular season
doesn't tip off until about 2026-10-20 (the project's own notes say first-night
checks are owed "after 2026-10-21"). For those ten days, and for the first week or
so after tip-off, the new season has rosters but no scored games.

What a member sees in that window today (read from the code on `main`, 943b303,
not yet from the live site):

| Where | What it does with no scored games this season |
|---|---|
| League home, header (`LeagueHome.tsx:153-160`) | "Your season hasn't started yet." Accurate, but it's the only thing about this season on the page. |
| League home, standings (`LeagueHome.tsx:175`, `:286`) | "Standings appear once the season is under way." Nothing else in its place. |
| League home, latest matchup (`LeagueHome.tsx:337-338`) | Its endpoint falls back to the newest *played* season, so the block is last season's last week. |
| League home, power block (`LeagueHome.tsx:515-518`) | The headline comes from the newest season that has a vote, which right now is last season's. |
| League home, award block (`LeagueHome.tsx:544`) | Falls back to an earlier season's awards. |
| Expected wins, Roster management, Player page, Player trends (`SeasonFallbackNote`, `fallbackLabel`) | Fall back to the newest played season, with a small note saying so. |
| Season forecast / playoff odds | Never written before a week is scored. That's on purpose: on a live preseason league every simulated game ended 0-0 and the tiebreak handed out 100%/0% from nothing (see the playoff-odds memory, `claude/playoff-odds.md`). |

Each of those fallbacks was a deliberate, honest decision, and each one is labelled.
But put together, the page a member opens the day after the draft is mostly about
**last** season, and none of it answers the questions people actually ask in that
window: who won the draft, who's the favorite, whose roster is strongest, and where
mine stands.

What already exists that this can build on (checked in the code, not just the docs):

- **Draft grades** (spec 018, deployed) grade every pick against a baseline. *Amended after planning:* the grades are built from real scored games, so they're not available until week 1 is scored (research R6, FR-010).
- **Football already has "strongest team".** *Found in planning:* the Analysis page's roster projections rank every roster's best lineup by projection, by position. Only a sport gate keeps basketball out (research R4).
- **NBA season projections exist upstream.** On 2026-10-05, Sleeper's season
  projection endpoint for NBA 2026 returned per-game averages for 529 players
  (`claude/projection-tools.md:35-37`). Not measured: how accurate they are for
  these leagues. The roadmap's "projection foundation" (3.1) that would store them
  isn't built yet.
- **NBA schedule grid** (spec 017, deployed): games per team per week.
- **Power rankings** with member ballots, which have no votes for 2026 yet.

### Research: what other sites show in this window

Labelled by how I know it, because the two get blurred otherwise (AGENTS.md bug class #8).

**Confirmed from search results on 2026-10-11:**

- **FantasyPros My Playbook / League Analyzer** syncs a Sleeper/ESPN/Yahoo league
  and shows *projected standings*, *power rankings* that "show you why", and
  *playoff and championship odds* built by simulating the season from rosters
  ([FantasyPros tools](https://www.fantasypros.com/2026/07/best-fantasy-football-tools/amp/),
  [My Playbook](https://fantasypros.com/2014/08/fantasy-football-my-playbook/amp)).
- **FantasyPros Draft Wizard** draft results pages show *projected standings* for the
  league straight after a real or mock draft
  ([example](https://draftwizard.fantasypros.com/mlb/real-draft/f2DfML2J/a)).
- **Basketball Monster** offers "Analysis Monster", a standings analysis tuned to the
  league's settings, plus team analysis and full-season projections that update
  several times a day ([Basketball Monster](https://basketballmonster.com/default.aspx),
  [Team analysis](https://basketballmonster.com/TeamAnalysis.aspx)).
- **Hashtag Basketball** sells league scouting and team analysis tools for synced
  leagues ([Hashtag Basketball](https://hashtagbasketball.com/fantasy-basketball-sleepers)).
- **DraftSharks** has a team analyzer ([DraftSharks](https://beta.draftsharks.com/article/fantasy-football-team-analyzer)).

**From general knowledge, not checked this session:**

- Yahoo shows a post-draft grade and a projected rank/record per team on the
  draft results page.
- ESPN shows a projected rank for every team before week 1 and publishes
  preseason power rankings.
- Sleeper's league view in the preseason shows week 1 projected matchup scores.
- ffwrapped (one of Allan's design references) leans on per-team stories and
  rankings more than raw tables.

**The pattern across all of them:** in this window the home turns into a
*projection-led* view of **this** season. A projected ranking of every team, a
"why" for each (which positions or stats carry them), the draft's winners and
losers, and odds once a simulation can run. Last season moves to history.

## Clarifications

### Session 2026-10-11

- Q: How is the preseason favorite measured? → A: The top of the strength ranking and its margin over 2nd, with no odds. Simulated odds (option B) are a planned, in-depth follow-up: Story 5, FR-015 to FR-021, SC-007 to SC-010.
- Q: What happens to blocks on the home whose only data is last season's? → A: They're removed from the home and stay on their own labelled pages.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The league home is about this season once the draft is done (Priority: P1)

A member opens the league home the day after the draft. The page leads with this
season: the draft's done, here's when the first games are, here's how the new
rosters stack up. Anything from last season is either gone from the home or
clearly framed as last season. It never sits in the place where this season's
content will be, looking like this season's.

**Why this priority**: this is the "weird state" itself. Even with no new numbers,
not showing last year as the main thing fixes most of the complaint, and it's the
smallest piece.

**Independent Test**: on a league whose draft is complete and that has no scored
games, open the league home and check that every block either shows this
season's data or says which other season it's from, and that the first screen is
about this season.

**Acceptance Scenarios**:

1. **Given** a league whose draft is complete and that has no scored games this season, **When** a member opens the league home, **Then** the first screen names this season, says the draft is complete, and shows when the first scored games are expected (or says that date isn't known).
2. **Given** the same league, **When** a block's only data is from an earlier season (matchup, power headline, awards), **Then** it's handled according to the rule chosen in FR-003. In every case it never appears unlabelled.
3. **Given** a league whose current season has at least one scored week, **When** a member opens the home, **Then** it behaves exactly as it does today. This feature changes nothing once games are scored.
4. **Given** a league whose draft hasn't finished yet, **When** a member opens the home, **Then** the existing pre-draft draft block is unchanged.

---

### User Story 2 - Who has the strongest team, and why (Priority: P1)

A member wants to know how the freshly drafted rosters rank against each other
for **this** season, and what puts a team where it is. They see every team ranked
by projected strength. Each team shows its projected weekly score (or season
total) under this league's scoring, and the players or positions that carry it.
Their own team is marked.

**Why this priority**: "who's got the strongest team" is the question the user
named, and every competitor in the research answers it in this window.

**Independent Test**: for a drafted league with season projections available,
open the ranking and check that each team's number can be rebuilt by hand from
its best legal lineup's projected points under the league's own scoring.

**Acceptance Scenarios**:

1. **Given** a league whose draft is complete and projections are available, **When** a member opens the team strength view, **Then** every roster appears exactly once, ranked by projected strength, with the exact value shown beside any bar.
2. **Given** a team in that ranking, **When** the member looks at its row, **Then** they can see which starters contribute most and where it ranks against the league at each lineup position.
3. **Given** a roster with players who have no projection (injured, unsigned, rookies not covered), **When** the ranking is built, **Then** those players are counted as zero and the row says how many were left out. The rest of the roster still produces a number.
4. **Given** projections aren't available for this sport or season, **When** a member opens the view, **Then** it says plainly that projections aren't available yet. It doesn't fall back to last season's results presented as strength.
5. **Given** the view shows a projected number, **Then** it says which source the projections come from and that their accuracy for this league hasn't been measured.

---

### User Story 3 - Who's the favorite (Priority: P2)

A member wants a single answer to "who's going to win this?" before any games are
played. They see a preseason favorite, with how strong a favorite it is, built
from the same projections as Story 2 and labelled as a preseason projection.

**Why this priority**: it's the headline people talk about in the group chat,
but it depends on Story 2's projections. This version is deliberately just the
ranking's top team and its margin (FR-007). Odds come later, in Story 5.

**Independent Test**: with Story 2 in place, check that the favorite shown is
consistent with the strength ranking and that its caveats name the projection
source and that no games have been played.

**Acceptance Scenarios**:

1. **Given** a projected strength ranking exists, **When** a member opens the home, **Then** it names the preseason favorite, with a measure of how strong the favorite is, in the form chosen in FR-007.
2. **Given** the first week is scored, **When** the existing in-season forecast becomes available, **Then** the preseason favorite stops being the headline and the in-season numbers take over. The two are never shown side by side as if they measured the same thing.
3. **Given** two or more teams are effectively tied, **Then** the headline says so instead of naming one.

---

### User Story 4 - This season's numbers, not last season's (Priority: P2)

A member looking for "relevant stats for this year" gets this season's draft and
roster facts: draft grades and the draft's biggest steals and reaches (already
built, surfaced here), each team's games scheduled in week 1 (NBA, from the
schedule grid), and once games start, this season's early leaders. Last season's
leaders aren't presented as this year's.

**Why this priority**: it's mostly bringing existing pieces onto the home, but
it fills the page with this season's content while there are no scores yet.

**Independent Test**: on a drafted, unscored league, check that every stat shown
on the home is from this season's draft, rosters, schedule or projections, or
says which other season it's from.

**Acceptance Scenarios**:

1. **Given** a league whose draft is complete, **When** a member opens the home, **Then** before any scored week it links to the completed draft board, and from the first scored week it shows this draft's grades summary (best and worst graded teams) linked to the full draft grades page. *(Amended after planning: see FR-010.)*
2. **Given** a basketball league with the schedule loaded, **When** a member opens the home, **Then** it shows their team's scheduled games in the first scored week.
3. **Given** this season has its first scored games, **When** the player spotlight or leaders would otherwise fall back to last season, **Then** they use this season's games, however few, and say how many games they're based on.

---

### User Story 5 - Preseason title and playoff odds (Priority: P3, follow-up)

*Added 2026-10-11 at Allan's request: "B as a follow up to do, and go more in depth."
This is the next increment after Stories 1-4 ship. It isn't part of the first build,
but it's specified now so the first build doesn't paint it into a corner.*

A member wants more than "strongest roster". They want "what are my chances?"
before a game is played. They see each team's projected chance of making the
playoffs, of finishing with the best record, and of winning the title. These come
from simulating this season's actual schedule using each roster's projected
strength, with every input that isn't measured labelled as a guess.

**Why this is a follow-up, not part of the first build**: it's the version
FantasyPros sells, but it's the version most likely to look more certain than it
is. The in-season forecast already showed this exact failure. Run on a preseason
league, every game ended 0-0, the tiebreak handed out 100%/0%, and the invariant
check (odds summing to the right total) still passed. A ranking with a margin
(Story 3) can't fail like that. Odds can, so they come after the ranking exists
and can be checked against it.

**What the simulation needs, and where each piece comes from:**

| Input | Source | Measured or assumed? |
|---|---|---|
| Each team's projected weekly mean | Story 2's strength value (best legal lineup, league scoring, weekly crediting rule) | Derived from projections. Projection accuracy itself is unmeasured. |
| Each team's week-to-week spread | Option 1: this league's *own* week-to-week score spread from its completed past seasons (2024, 2025 for NBA). Option 2: a hand-set constant. Option 1 is preferred because it's measured. Option 2 is the fallback for a league with no history, labelled as a guess in config, like every other hand-set constant. | Option 1 measured, option 2 assumed. The page says which one it used. |
| The schedule | This season's real head-to-head schedule from the league, where it's published for future weeks | Measured, if it's available. If it isn't, the simulation doesn't run (FR-018). |
| Playoff format | The league's own settings: playoff teams, start week, seeding, tiebreak | Measured. The same unsupported formats as the in-season forecast (divisions, non-default seeding) store nothing, as they do today. |
| Roster changes during the season | Not modelled. Each run uses current rosters as if they stayed fixed all season. | Assumed, and stated on the page. |
| Injuries and missed games | *Amended after plan review, and again after the 2026-10-12 live check:* no games-played model (the per-game source has none, research R1). Players tagged Out, IR or Sus count as zero wherever they sit. DTD and GTD players, including those on IR+, count in full. | Assumed, and stated on the page. |

**Independent Test**: on the live NBA 2026 league before week 1, run the preseason
simulation and check four things. Odds sum to 100% for the title and to the
playoff-spot count × 100% for the playoffs. No team sits at exactly 0% or 100%
unless that's provable from the schedule. The ordering of title odds broadly
follows the strength ranking. Moving a star player from the strongest roster to
the weakest raises the weakest team's odds.

**Acceptance Scenarios**:

1. **Given** a drafted league with projections, a published schedule and supported playoff settings, **When** the preseason simulation runs, **Then** every team gets a playoff chance, a best-record chance and a title chance, each shown with its exact value.
2. **Given** a team's projected weekly mean or spread is zero or missing, **When** the simulation would run, **Then** it doesn't produce odds for the league at all. It says which input was missing, instead of producing 0-0 games (the failure seen on the in-season forecast).
3. **Given** the spread came from the hand-set fallback instead of the league's own history, **Then** the page says so in words next to the odds.
4. **Given** the first week is scored, **Then** the preseason odds stop appearing as the current odds. The in-season forecast takes over once it has a snapshot. Until it does, the preseason odds may stay visible, labelled "preseason projection, before week 1", and are never mixed into the in-season numbers.
5. **Given** the same inputs, **When** two pages show a team's odds, **Then** they show the same number. The odds are computed once when inputs change, not separately on each page load.
6. **Given** the existing in-season forecast's NFL-only hardcode (`PowerRankingService.nflState()`, from the playoff-odds memory), **Then** this follow-up must not inherit it. Basketball is the league it's built for.

---

### Edge Cases

- **Draft complete, but the roster list hasn't been refreshed since the draft.** Ranking an empty or stale roster as "strength" would be wrong. The view must say rosters aren't loaded yet instead of ranking zeros.
- **Trades or adds between the draft and the first game.** Strength is computed from current rosters, not draft picks, and says when rosters were last refreshed.
- **A league with keepers or a dynasty carry-over**, where the "draft" is only part of the roster. Same rule: current rosters.
- **Football next August.** The same window exists for the NFL league. The rules apply to both sports. Football projections already exist for the analysis page, but this spec verifies basketball first because it's the league in the window right now.
- **Scoring the projections don't cover** (a custom stat the league scores that the projection source doesn't project). The number must say which scored stats it couldn't project.
- **A member who isn't on a roster** (commissioner view, or someone who left). Everything shows except the "you" markers.
- **IR+ slots hold players who will play** *(added after live check, 2026-10-12)*. This season the league allows day-to-day players onto IR (`reserve_allow_dtd: 1`), and 11 of 17 IR-slotted players were only DTD/GTD (Giannis: "DTD · Rest"). Strength must judge a player by his injury status, not his slot. Out, IR and Sus count as zero wherever they sit. DTD and GTD count in full, including on IR+, and are flagged. In past seasons an IR player was out; with IR+ that no longer holds (Allan, 2026-10-12).
- **The basketball weekly scoring rule.** This league credits each starter one game per week (memory: "NBA one game per week is correct", 540/540 measured). Projected weekly strength must use that rule, not games-per-week times per-game average. The two implementations of one rule landmine applies (multi-sport landmines memory).

## Requirements *(mandatory)*

### Functional Requirements

**Season state**

- **FR-001**: The system MUST recognise a "drafted, not yet scored" state for a league season: its draft is complete and it has no scored week. Every behaviour below keys off that one state, and the rule for it lives in one place.
- **FR-002**: In that state, the league home MUST lead with this season: its name and year, that the draft is complete, and the expected first scored week (or that it isn't known).
- **FR-003**: In that state, blocks whose only data is from an earlier season (latest matchup, power headline, awards) MUST be **removed from the home**. *(Amended after plan review, B2: the test is per block, "this block's data is from another season", not the state alone. Week 1's live 2026 matchup shows as soon as it exists.)* They stay reachable on their own pages, where the existing season-fallback labelling already says which season they're from. *(Clarified 2026-10-11 by Allan: Q2 option A. The alternative, keeping them below a "Last season" heading, was rejected.)*
- **FR-004** *(amended after plan review)*: Once the season has a final scored week, the home MUST behave exactly as it does today, except for the draft-grades summary block FR-010 adds. *Was:* "exactly as it does today". That contradicted FR-010, which adds a block from week 1 (review S6).

**Team strength (Story 2)**

- **FR-005**: The system MUST rank every roster in the league by projected strength for this season. Strength is the projected points of that roster's best legal starting lineup under the league's own roster slots and scoring rules, using the sport's weekly crediting rule.
- **FR-006**: Each team's entry MUST show its exact projected value, its rank, which starters contribute most, its rank at each lineup position, and how many rostered players had no projection.
- **FR-006a**: The view MUST name the projection source, when projections were last fetched, when rosters were last refreshed, and that the projections' accuracy for this league hasn't been measured.

**Favorite (Story 3)**

- **FR-007**: The system MUST name a preseason favorite and how strong a favorite it is, in this form: **the top team in the Story 2 strength ranking, plus its projected margin over 2nd**. The margin is shown both as projected points per week and as a percentage of 2nd place's value. No odds or probabilities are shown in this version. *(Clarified 2026-10-11 by Allan: Q1 option A now. Option B, simulated odds, is a planned follow-up: Story 5 and FR-015 to FR-021.)*
- **FR-007a**: The favorite headline MUST say what it's based on, e.g. "Projected strongest roster, by N pts/week". It must never use words that imply a probability ("likely", "odds", "% to win").
- **FR-008**: When the first scored week arrives, the preseason favorite MUST step aside for the existing in-season forecast. The two are never presented as the same measure.
- **FR-009** *(amended after plan review)*: Near-ties MUST be reported as ties, not as one named favorite. A single favorite is named only when the projected gap is at least a hand-set, ARBITRARY cut (3%, measured against the lower team's total). Both teams' projected values are always shown beside the gap (review S11).

**This season's stats (Story 4)**

- **FR-010** *(amended after planning, 2026-10-11)*: In the drafted-not-scored state, the home MUST link to the completed draft's board, and the Story 2 strength ranking is the "who won the draft" answer. From the first scored week, the home MUST show a draft grades summary (best and worst graded team, with the existing early-season badge).
  *Was:* "In the drafted-not-scored state, the home MUST show a draft grades summary from the existing draft grades." *Why it changed:* spec 018's grades are built from players' real scored games. They answer `NO_SCORED_WEEKS` until a week is scored, so this couldn't be met as written. The spec assumed "already built" meant "available now", and it doesn't (research R6). Grading picks by projection instead was rejected, because it would be a second model under the same name.
- **FR-011** *(amended after plan review)*: For basketball, the home MUST show how many of the member's projected starters have no game scheduled in the first scored week, from the existing schedule data. *Was:* "the team's games scheduled". Because Sleeper credits one game per starter per week, a raw game count invites a "more games = more points" reading the data contradicts (review S5).
- **FR-012**: Once this season has scored games, the season-scoped stats the home shows (spotlight, leaders) MUST use this season's games and say how many there are, instead of falling back to the previous season.

**Honesty**

- **FR-013**: Every projected number MUST be visibly labelled as a projection, and kept apart from anything that has actually been scored.
- **FR-014**: When any input is missing (no projections, rosters not refreshed, schedule not loaded), the affected block MUST say what's missing. It must not fall back to a different season or show zeros as if they were real values.

**Preseason odds (Story 5, follow-up increment, not in the first build)**

- **FR-015**: The system MUST simulate this season's remaining schedule from each team's projected weekly mean and spread, and produce each team's chance of making the playoffs, finishing with the best regular-season record, and winning the title.
- **FR-016**: The weekly spread MUST come from the league's own completed seasons where there are any. Otherwise it comes from a hand-set fallback, labelled arbitrary where it's configured and named as a guess on the page.
- **FR-017**: Before writing any odds, the system MUST check that every team has a nonzero projected mean and a nonzero spread. If any team fails, it writes nothing and records why. An invariant like "odds sum to 100%" passing is not enough on its own.
- **FR-018**: If this season's head-to-head schedule isn't published for the weeks being simulated, or the playoff settings are a format the in-season forecast doesn't support, the system MUST write nothing and say which of those is the reason.
- **FR-019**: Odds MUST be computed once each time their inputs change (rosters, projections, schedule), not on page load, so every page showing them agrees.
- **FR-020**: Preseason odds MUST be labelled "preseason projection, before week 1" everywhere they appear. Once week 1 is scored they stop being presented as current, and they never feed into the in-season forecast.
- **FR-021**: When Story 5 ships, the Story 3 headline MAY switch from the margin form to title odds. The margin stays visible, because it's the number the odds were built from.

### Key Entities

- **Season state**: for one league season, whether the draft is complete and whether any week has been scored. The single source the home and the season pages read.
- **Team strength entry**: one roster's projected strength. The exact value, its rank, top contributors, rank at each position, the count of players with no projection, and its provenance (projection source and fetch time, roster refresh time).
- **Preseason favorite**: the team, or tied teams, at the top, with a strength measure in the FR-007 form, plus its caveats.
- **Preseason odds snapshot** *(follow-up)*: for one league season, each team's playoff, best-record and title chance, plus the inputs used. That's the spread source (league history or fallback), the schedule weeks covered, the iteration count and when it was computed. If no odds were written, the snapshot holds the reason instead.
- **Projection set**: per-player season projections for one sport and season, from a named source, with a fetch time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the live "Ball Knowers" NBA 2026 league before its first scored week, 100% of the blocks on the league home show 2026 content or are removed per FR-003. None shows unlabelled 2025 data. Checked block by block in a real browser.
- **SC-002**: A member can find who has the strongest team and who the favorite is from the league home within one tap.
- **SC-003**: For at least three teams, the projected strength value is rebuilt by hand from that roster's best lineup and the published projections, and matches to the displayed precision.
- **SC-004**: When a preference-ordering test swaps a star into a weak roster, that roster's rank goes up (AGENTS.md bug class #1). Structural checks alone don't count.
- **SC-005** *(amended after plan review)*: On a league with at least one final scored week, the league home is identical to today's (same blocks, same values), except for the draft-grades summary block.
- **SC-006**: Every projected figure on any screen this feature touches carries a projection label and its source, with zero exceptions on a manual sweep.

**Follow-up (Story 5) only:**

- **SC-007**: On the live NBA 2026 league, title odds sum to 100% ± rounding, and playoff odds sum to (playoff spots × 100%) ± rounding.
- **SC-008**: No team is shown at exactly 0% or 100% for the title before week 1.
- **SC-009**: A preference-ordering test, where the same star is moved from the strongest roster to the weakest, raises the weakest team's title odds and lowers the strongest's.
- **SC-010**: Feeding the simulation a preseason league with all-zero projections writes no odds and records the reason. This reproduces the in-season forecast's failure case and shows it's guarded.

## Assumptions

- The window this targets is from draft completion to the first scored week. For "Ball Knowers" NBA 2026 that's 2026-10-10 to roughly 2026-10-26 (first scored week, assumed and not checked against the league's settings).
- Sleeper's season projections for NBA are the projection source, since they're the only one measured as available (`claude/projection-tools.md`). Their accuracy for this league is unmeasured and stays labelled as such. It doesn't get tuned.
- Strength is measured from **current rosters**, not draft picks, so trades and adds after the draft count.
- Both sports follow the same rules. Basketball is verified first because it's in the window now. Football's equivalent window (August next year) gets verified when it comes.
- "Relevant stats for this year" before tip-off means this season's draft, rosters, schedule and projections. There are no real game stats until tip-off, and the spec doesn't invent any.
- No backtesting of the projections (a standing project decision, AGENTS.md).
- Built under this repo's pipeline: plan, adversarial review, build, bug-hunting review, live verification on the real league.

## Not being built

- Trade analyzer, waiver suggestions and start/sit advice. Competitors offer them, but they're separate features (roadmap 3.3 and later).
- Storing weekly projection stat lines (roadmap 3.1, "projection foundation"). This only needs season-level per-player projections. *Resolved in planning (research R1): 3.1 isn't a prerequisite. A separate season-projection table sits beside the weekly one, so 3.1 can still be built later.*
- Changing the in-season forecast or its "no scored week, no snapshot" rule.
- Story 5 (preseason odds) in the first build. It's specified here as the next increment and gets its own plan, review and verification pass once Stories 1-4 are live.
- Blending preseason projections into the in-season forecast after week 1 (a Bayesian prior). It's a natural step after Story 5, but it would change a shipped feature's numbers, so it's out of scope here.
- Category (roto/H2H-category) strength. The real leagues are points leagues.
