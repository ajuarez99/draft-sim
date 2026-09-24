# Feature Specification: Season superlatives, so far

**Feature Branch**: `008-season-superlatives`

**Created**: 2026-09-22

**Status**: Draft (clarified 2026-09-22, see "Clarifications")

**Input**: User description: "the superlatives idea" — i.e. `ideas/ongoing-superlatives.md` (moved to `origin-idea.md` in this folder on 2026-09-23), parked
earlier on 2026-09-22 as "table for later". Follow-up: "season long. but i want like waiver wire
warrior. whos been the most unlucky with injury (joel embiid award), closest wins, closest losses".

## Clarifications

### Session 2026-09-22

- Q: Should the headline superlatives come from season-long measures, from a tally of weekly
  awards, or both? → A: **Season-long measures.** Allan also named four superlatives the list must
  include: a **Waiver Wire Warrior**, a **Joel Embiid Award** (the unluckiest team with injuries),
  **closest wins** and **closest losses**.
- Q: For the Unethical Award (added by Allan the same day), what decides that a player is
  "troubled"? → A: **Both**: official league suspensions from Sleeper, captured automatically from
  now on, **plus** a list the league's commissioner keeps by hand. Each entry shows which of the two
  it came from.

## Amended after planning (2026-09-22)

Planning ([research.md](research.md)) measured things this spec had guessed. The original text below
is left in place. These corrections take precedence where they conflict:

1. **Placement** (Assumptions): not a section of the League analysis page. The Weekly Report is its
   own page, so superlatives get their own page, with a rail entry directly after "Weekly report"
   (research R1).
2. **Football missed games are measurable** (Assumptions, US4 scenario 3): the spec guessed football
   might ship US4 as unavailable. Sleeper's weekly stats mark a did-not-play week (entry present,
   no `gp`) distinctly from a bye (`None`), measured on McCaffrey and Mahomes 2024. The one
   ambiguity: a rested player's week can also be `None`. That's resolved by checking whether his
   team played, with unresolvable weeks reported rather than guessed (research R9).
3. **Why the margin is per sport** (FR-011): the stated reason ("basketball weekly totals run far
   higher") was half wrong.
   - Totals are about twice as high, but the spread is nearly identical (SD 27.4 vs 26.6, 2025).
   - So a share-of-score margin, the other option FR-011 allowed, would be wrong.
   - Per-sport absolute margins stand: under 10 points in football and under 15 in basketball, each
     sport's 2025 25th-percentile margin (research R5).
4. **"Date it applies from"** (US6, FR-017) is stored as **the week it applies from**, since FR-018's
   rule compares weeks (research R12).
5. **US5 (weekly award trophy case) is not built** in this feature, as the spec allowed (research R13).
6. **FR-004 has a cost the spec didn't foresee.** The existing Expected wins table almost certainly
   includes playoff games (inferred from Sleeper data and ingest code; the stored rows weren't
   checked). Sharing one figure with a regular-season-only superlative means bounding that table
   too, which changes its numbers for completed seasons. **Approved by Allan on 2026-09-23**
   (research R3).
7. **The Embiid award counts every missed game** (US4, FR-014), decided by Allan on 2026-09-23.
   Planning first proposed costing only fantasy weeks in which the player played no game at all,
   because this NBA league scores one game per player per week. Allan: "as a player you want every
   single game anyways and not just that one game." Each missed game while he was a regular on that
   roster costs his mean points per game played (research R10).

### Amended after analysis (2026-09-23)

`/speckit-analyze` found gaps between this spec, the plan and the tasks. Corrections:

8. **FR-006 covers the Unethical Award too.** FR-006 named four kinds for the early-season caveat,
   while the contract and tasks applied it to six. The six is right: a suspension tally three weeks
   in is as thin a sample as luck is. FR-006 now names all six.
9. **The commissioner's list is per season** (FR-017). The list is stored against one league-season,
   and every superlative on the page is season-scoped, so a new season starts with an empty list.
   **Confirmed by Allan on 2026-09-23**: "new season fresh".
10. **FR-002, for season totals.** Luck and bench points are season-wide sums, not events in a week.
    They show the span they cover ("weeks 1–6") rather than individual weeks. Luck also carries the
    expected-wins table's existing swing weeks, and bench points its single worst week.
11. **Career profiles change too** (amendment 6's consequence). `ManagerCareerService` sums each
    season's wins-above-expected into a manager's career figure. Bounding expected wins to the
    regular season changes those career numbers as well, found by analysis rather than planning.
    The tasks capture a before/after for it and surface it to Allan alongside the Expected wins page.
12. **No `?season=` parameter.** The plan's contract promised one, but a past season is chosen by
    its own league id, the way every other season-scoped page here works.

### Amended after code review (2026-09-23)

The bug-hunting review (T067) found problems that changed behaviour, not just code:

13. **Regular contributor, as research R10 wrote it.** The build counted a player's started weeks
    against *every* week he was rostered. That dropped exactly the award's namesake case: a star
    who starts 5 weeks and is then out 13 fails "half of 18". It now counts against weeks he
    played, as R10 always said.
14. **A game not yet played isn't a missed game.** Mid-season, Sleeper lists upcoming games with
    empty stats. The ingest now skips future-dated entries, and it removes a stale absence row
    once the same game shows up as played.
15. **The commissioner's list follows the season the page is showing.** Before a new season has a
    scored week, the page shows the previous season (the resolver walks back). The list used to
    be the *new* season's, so an entry saved there never changed the award on screen. The list
    now belongs to the displayed season. **Trade-off:** a new season's list can't be edited until
    that season has a scored week. This reverses the contract's earlier "edit the new season
    before week 1" line (contract amended).

## Problem

A league chat argues about the season *while it is happening*. The app answers "what happened this
week" (the Weekly Report) and "what is the best ever" (the record book, spec 002), but nothing
answers "who is this season's luckiest, unluckiest, most self-sabotaging manager **so far**?" The
figures to answer most of it already exist, scattered across one week at a time.

### What was checked, and how

Read in code on 2026-09-22. **Nothing below was run.** The app's own database wasn't running this
session, so every data-coverage question in this section is still open and goes into planning as a
measurement, not an assumption.

- The Weekly Report hands out four per-week awards: *Got away with it* (won while using the least
  of its best possible lineup), *Deserved better* (lost despite a high weekly rank), *One-player
  carry* (one starter's share of the lineup's points) and *Self-inflicted wound* (lost by less than
  a bench swap would have gained; omitted, with a reason, when starting lineups weren't recorded).
- Schedule luck is already computed season-wide and shown (spec 004 US3): wins expected if a team
  played the whole league each week, against its actual wins.
- Roster moves are already stored and graded (spec 004 US6). Each add is typed as waiver,
  free agent, trade or commissioner, with the week it happened and any FAAB bid.
- **Injury history is not stored.** The app keeps only each player's *current* Sleeper injury tag
  as of the last player refresh, with no record of who was hurt in which week. A player who missed
  six weeks and is healthy today looks the same as one who never missed a game. For basketball,
  per-game records (spec 005) show which nights a player actually played. Football has no
  equivalent per-game record, and the app doesn't know bye weeks. See US4.

### Amending the idea doc's guess

The idea doc guessed the cheapest version would be "aggregation, not new math": tally who won each
weekly award over weeks 1..N. Reading the award code says a tally is **the wrong number to put under
a superlative title**, even though the arithmetic would be correct (the class of bug in lessons #5):

- Each award goes to **one team per week, or nobody**, and each has a cut-off. Over a 14-week season
  most teams would show 0 or 1, and ties at the top would be normal.
- A team that was a little lucky every single week never wins a weekly award, yet is plainly the
  season's luckiest. A tally would name someone else.

Allan confirmed season-long measures (see Clarifications). The award tally is kept only as an
optional, separately labelled trophy case (US5).

### "Closest win" and "closest loss" aren't two games

The league's single narrowest win is the same game as its single narrowest loss, seen from the
other side. Shown literally, the two superlatives would always name the same game. So "closest wins"
and "closest losses" are read here as **per-team records in close games**: the team that keeps
escaping (most close wins) and the team that keeps getting its heart broken (most close losses).
The one closest game of the season still appears once, under the extremes in US1.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The season's extremes and close games (Priority: P1)

A manager opens the Superlatives page (amendment 1: its own page, not a section of League analysis) mid-season and sees season-to-date superlatives, each
naming a team and the exact figure behind it:

- highest and lowest single-week score
- biggest blowout
- closest game
- **Closest wins** ("Escape artist"): the team with the most wins by a close margin
- **Closest losses** ("Heartbreak kid"): the team with the most losses by a close margin

The section's title says how many scored weeks it covers ("Season so far · through week 6").

**Why this priority**: These are plain facts from scores and pairings, with no model behind them,
and they're the lines a league chat quotes first. Two of Allan's five named superlatives are here.

**Independent Test**: For a league with at least one fully scored week, open the page and check each
superlative's team and figure against that season's weekly scores by hand.

**Acceptance Scenarios**:

1. **Given** a season with weeks 1–6 fully scored, **When** a manager opens the section, **Then** it
   shows highest and lowest single-week team scores, biggest blowout and closest game, each with the
   team(s), the figure and the week, and the title reads "through week 6".
2. **Given** team C has won three games by less than the close-game margin and nobody else has won
   more than two, **When** the section is opened, **Then** "Closest wins" names team C with "3 wins
   by under N points" and lists the three games (week, opponent, margin).
3. **Given** team D has the most losses within the close-game margin, **When** the section is
   opened, **Then** "Closest losses" names team D with the count and the games listed the same way.
4. **Given** week 7 is in progress (partly scored), **When** the section is opened, **Then** week 7
   contributes nothing and the title still reads "through week 6".
5. **Given** two teams share a record (a tied high score, or an equal close-win count), **When** it
   is shown, **Then** every tied team is named. None is picked silently.
6. **Given** no game this season has been decided by less than the margin, **When** the section is
   opened, **Then** the close-win and close-loss superlatives say "no close games yet" instead of
   naming a team with 0.
7. **Given** a week with scores but no stored game pairings, **When** the section is built, **Then**
   blowout, closest game and the close-game records leave that week out and say so, while the
   score-only extremes still include it.

---

### User Story 2 - Luckiest, unluckiest, most self-sabotaging (Priority: P2)

The same section names:

- the season's **luckiest** team (real wins furthest above expected wins)
- the **unluckiest** team (real wins furthest below expected wins)
- the team that has left the **most points on its bench** in total

Each comes with the figure and a one-line plain-English reading ("2.4 more wins than their scores
earned").

**Why this priority**: This is the question the idea exists to answer, but it carries a model and a
thin-sample problem that US1 doesn't.

**Independent Test**: Compare the luckiest and unluckiest names and figures against the existing
expected-wins table for the same league and season. They must match exactly.

**Acceptance Scenarios**:

1. **Given** the expected-wins table shows team A with the largest wins-above-expected, **When** the
   section is opened, **Then** "Luckiest" names team A with the identical figure.
2. **Given** fewer weeks are scored than the early-season threshold, **When** the section is opened,
   **Then** luck and bench superlatives are shown with a visible "early — this is mostly noise" caveat
   beside them, not hidden and not presented at full confidence.
3. **Given** a team whose best possible lineup can't be worked out for some week, **When** season
   bench points are totalled, **Then** the total says how many weeks it covers for that team.

---

### User Story 3 - Waiver Wire Warrior (Priority: P2)

The section names the team that got the most out of the waiver wire this season: points scored
**in its starting lineup** by players it picked up off waivers or free agency. The superlative shows
the total and the team's best two or three pickups, each with the points it contributed and the week
it was added.

**Why this priority**: Allan named it. It's also the one superlative that rewards effort rather than
luck, and the app already stores and grades roster moves, so the input exists.

**Independent Test**: For the named team, list its waiver and free-agent adds from the existing
transactions view. Sum each pickup's points in the weeks it was started for that team, and check
the total and top pickups against the superlative.

**Acceptance Scenarios**:

1. **Given** team E's waiver and free-agent pickups have scored the most starting-lineup points of
   any team's this season, **When** the section is opened, **Then** "Waiver Wire Warrior" names team E
   with that total and its top pickups.
2. **Given** a player was picked up, dropped and picked up again by the same team, **When** his points
   are counted, **Then** only weeks he was on that team, after an add, count, and nothing is counted
   twice.
3. **Given** a player picked up by team E was later traded to team F, **When** points are counted,
   **Then** his later points count for nobody's waiver total. Team F got him in a trade, not off the
   wire.
4. **Given** some weeks have no recorded starting lineup, **When** the total is shown, **Then** it
   says how many weeks it covers and why the rest were left out. It does **not** quietly fall back
   to counting bench points as if they were started.
5. **Given** commissioner moves exist, **When** pickups are counted, **Then** commissioner moves are
   excluded and the superlative says so.

---

### User Story 4 - The Joel Embiid Award (Priority: P3)

The section names the team that has been hit hardest by its players missing games: the points its
regular contributors would have been expected to score in the games they missed (amendment 7: every missed game, not whole weeks).
It shows the total, the players who cost the most, and how many games or weeks each missed.

It is labelled as **games missed, not injuries**. The app can see that a player didn't play, but
not why (injury, rest, suspension, a coach's decision), and the award says that in one line.

**Why this priority**: Allan named it, and it's the most fun line on the page. It is P3 because it's
the only superlative that needs something the app doesn't store today (see "What was checked").
Measuring it honestly is the riskiest part of the feature.

**Independent Test**: For basketball, pick the named team's top-cost player. From the per-game
records, count the nights his NBA team played while he was on this roster and he didn't, and check
the count and the estimated cost against the award.

**Acceptance Scenarios**:

1. **Given** a basketball league where team G's regular contributors missed the most expected
   points, **When** the section is opened, **Then** "The Joel Embiid Award" names team G with the
   estimated points lost and its costliest absences (player, games missed, estimated cost).
2. **Given** a player whose NBA team didn't play on a given night, **When** absences are counted,
   **Then** that night isn't counted as a missed game.
3. **Given** a football player's week off is his team's bye, **When** absences are counted, **Then**
   it isn't counted as a missed game. If byes can't be told apart from absences with the data
   available, football shows the award as unavailable with that reason rather than a wrong number.
4. **Given** a player who was never a regular contributor (a deep bench stash), **When** he misses
   games, **Then** his absences cost nothing, so stashing injured players doesn't win the award.
5. **Given** a player's current injury tag, **When** the award is computed, **Then** the tag isn't
   what decides it. A player hurt for six weeks and healthy today still counts. A player tagged "Out"
   today who hasn't missed a scored week doesn't.

---

### User Story 5 - Weekly award trophy case (Priority: P4, optional)

A season-to-date count of how many times each team has won each Weekly Report award, with the weeks
listed, clearly labelled as a count of weekly awards and not as a season ranking.

**Why this priority**: Allan didn't ask for it. It's kept as a cheap optional extra, and is
misleading if it stands in for a superlative (see "Amending the idea doc's guess"). Planning may drop
it.

**Independent Test**: Open the Weekly Report for each scored week, note each award's winner, and
compare against the counts.

**Acceptance Scenarios**:

1. **Given** *Deserved better* went to team B in weeks 2 and 5, **When** the trophy case is opened,
   **Then** team B shows 2 for that award, with weeks 2 and 5 listed.
2. **Given** *Self-inflicted wound* was left out for some weeks because lineups weren't recorded,
   **When** its count is shown, **Then** it says how many weeks it couldn't be computed for.

---

### User Story 6 - The Unethical Award (Priority: P3)

The section names the team whose roster has leaned hardest on players with off-field trouble,
with the players listed and what each one's trouble was. A player qualifies in one of two ways, and
the page says which for every entry:

- **Suspended**: Sleeper tagged him as suspended in a week he was on the team's roster. The app
  starts recording that tag weekly when this ships. It can't recover suspensions from before then.
- **Commissioner's list**: the league's commissioner added him by hand, with a short reason and the
  date it applies from. This is the league's own call, and it's labelled as the commissioner's.

**Why this priority**: Allan named it (2026-09-22). It's P3 because the app holds **no data at all**
about player conduct: nothing in code reads or stores a suspension, arrest or discipline record
(searched 2026-09-22). Sleeper tags a suspended player, but, like injuries, the app keeps only today's
tag, with no history.

**Why the source matters more here than anywhere else**: this award makes a claim about a real,
named person's conduct on a site other leagues can sign into. Every other superlative is arithmetic
on scores. This one is only as good as its source, and a wrong entry isn't a rounding error.

**Independent Test**: For the named team, check each "Suspended" entry against the recorded weekly
tags, and each "Commissioner's list" entry against the list. Confirm the player was on that team in
a qualifying week.

**Acceptance Scenarios**:

1. **Given** team H has rostered the most qualifying players this season, **When** the section is
   opened, **Then** "The Unethical Award" names team H and lists each player with what qualified
   him ("Suspended, weeks 3–5" or the commissioner's reason) and the source.
2. **Given** a suspended player was on team H only in weeks he wasn't tagged, **When** the award is
   computed, **Then** he doesn't count for team H. A player counts for a team only in weeks it
   rostered him while he qualified. This rule is the same for every team and is stated on the page.
3. **Given** the commissioner adds a player with a date that applies from week 6, **When** the award
   is computed, **Then** only teams that rostered him in week 6 or later get him.
4. **Given** the commissioner removes an entry, **When** the section is next opened, **Then** that
   player no longer counts for anyone.
5. **Given** a manager who isn't the commissioner, **When** they view the section, **Then** they
   can see the list's entries and reasons but can't add, edit or remove any.
6. **Given** the league has no known commissioner, **When** the section is opened, **Then** the
   award runs on suspensions only and says the commissioner's list isn't available and why.
7. **Given** the feature shipped mid-season, **When** the section is opened, **Then** the award
   says which week suspension tracking started, so an empty early season doesn't read as "nobody
   was suspended".
8. **Given** no rostered player qualifies, **When** the section is opened, **Then** the award says
   so rather than naming a team with 0.

---

### Edge Cases

- **No week fully scored yet** (preseason, or week 1 in progress): the section says so plainly
  instead of showing empty superlatives.
- **Season complete**: the section shows the final season-to-date figures, labelled as the full
  season, and doesn't duplicate the record book's all-time view.
- **Playoff weeks**: left out of season-to-date superlatives, and the section says "regular season"
  so the exclusion is visible.
- **Basketball**: team-level superlatives use the scored matchup totals, which decide games in both
  sports and mean the same thing in each. The Embiid award is the one place basketball has *more*
  to go on than football (per-night records). No player-level "best single performance so far"
  superlative is in scope.
- **Bye weeks / unpaired rosters**: a roster without a game that week counts toward score extremes
  but not toward blowout, closest game, close-game records or luck. That matches the expected-wins
  conservation rule.
- **A team renamed mid-season**: superlatives name the team as it's currently named.
- **A pickup dropped the same week**: contributes only the weeks it was actually started.
- **Everyone healthy**: if no regular has missed a game, the Embiid award says "nobody's been
  bitten yet" rather than naming a team with 0.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The section MUST cover only fully scored regular-season weeks of the league's current
  (or selected) season, and MUST give the last week covered in its title.
- **FR-002**: Every superlative MUST show the team, the exact figure, and the week(s) or games it
  comes from. No superlative may appear as a name alone. Season-long totals show the span of weeks
  they cover instead (amended, see 10).
- **FR-003**: Ties MUST name every tied team.
- **FR-004**: Any superlative that restates a figure the app already shows elsewhere (schedule luck
  from expected wins; pickups from the transactions view; a weekly award from the Weekly Report) MUST
  come from that same source, so the two can never disagree. It MUST NOT be recomputed by a second
  implementation of the same rule.
- **FR-005**: Where a superlative couldn't use every week (missing pairings, unrecorded lineups,
  missing per-game records), it MUST say how many weeks it covers and why the rest were left out.
- **FR-006**: Luck, bench, waiver, Embiid and Unethical superlatives (amended, see 8) MUST carry a visible early-season caveat
  below a minimum number of scored weeks. That threshold is hand-set and MUST be labelled as
  arbitrary where it's configured.
- **FR-007**: The section MUST be labelled "this season" so it can be told apart from the Weekly
  Report ("this week") and the record book ("all time").
- **FR-008**: Team-level superlatives MUST work for both football and basketball leagues without a
  sport-specific definition. Where a superlative can't be measured for a sport (FR-015), it MUST be
  shown as unavailable with the reason, not hidden and not approximated silently.
- **FR-009**: The weekly award tally (US5), if built, MUST be labelled as a count of weekly awards
  and MUST NOT be shown as the answer to "luckiest" or any other season superlative.
- **FR-010**: Headline superlatives MUST come from season-long measures (resolved 2026-09-22).
- **FR-011**: "Closest wins" and "closest losses" MUST be per-team counts of games won or lost by
  less than a close-game margin, each listing the qualifying games. The margin is hand-set, MUST be
  labelled arbitrary, and MUST be stated on the page ("by under N points"). Because basketball
  weekly totals run far higher than football's, the margin MUST be set per sport or as a share of
  the score, not as one fixed number for both.
- **FR-012**: The Waiver Wire Warrior MUST count only points scored in the acquiring team's
  **starting lineup**, by players it acquired through waivers or free agency, in weeks after the add
  and before the player left that team. Trades and commissioner moves MUST NOT count.
- **FR-013**: The Joel Embiid Award MUST be measured from **games actually missed**, never from a
  player's current injury tag, and MUST be labelled as missed games (cause unknown) rather than as
  confirmed injuries.
- **FR-014**: An absence MUST cost points only for a player who was a regular contributor for that
  team. The cost MUST be an estimate from that player's own production when he did play, and MUST be
  labelled as an estimate. How "regular contributor" is defined is hand-set and labelled arbitrary.
- **FR-015**: A scoring period in which the player's real team didn't play (basketball off-night,
  football bye) MUST NOT count as a missed game. If the available data can't tell those apart for a
  sport, the award MUST be unavailable for that sport, with the reason given.

- **FR-016**: The Unethical Award MUST draw only on two sources: Sleeper's suspension tag, recorded
  each week from when this ships, and the league's commissioner's hand-kept list. It MUST show which
  source applies beside every listed player. It MUST NOT infer conduct from anything else, such as
  games missed or news text (resolved 2026-09-22).
- **FR-017**: Only the league's commissioner MAY add, edit or remove entries on the commissioner's
  list. Each entry MUST have a player, a short reason and the date it applies from. Every manager in
  the league MAY see the entries. The list MUST be scoped to that one league and never visible to or
  shared with another league. It is per season: a new season starts empty (amended, see 9).
- **FR-018**: A player MUST count for a team only in weeks that team rostered him while he
  qualified (tagged suspended that week, or on or after a commissioner entry's date).
- **FR-019**: The award MUST state the week suspension tracking began for the season, and MUST NOT
  imply that no suspensions happened before it.

### Key Entities

- **Season superlative**: a title (e.g. "Heartbreak kid"), the team(s) holding it, the figure, the
  week(s) or games it came from, the number of weeks it covers, and any caveat.
- **Close game**: a paired game decided by less than the close-game margin: week, winner, loser,
  margin.
- **Pickup contribution**: a player a team acquired off waivers or free agency, the week added, and
  the points he scored in that team's starting lineup while he was there.
- **Absence**: a scoring period in which a regular contributor on a team didn't play while his real
  team did, with an estimated points cost.
- **Suspension record**: a player, a week, and whether Sleeper tagged him suspended that week. It's
  captured from when this ships and never backfilled.
- **Commissioner's list entry**: a league, a player, a short reason, the date it applies from, and
  who added it.
- **Coverage note**: for a superlative that couldn't use every week, how many weeks it used and why
  the others were left out.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a real league's current season, every US1 superlative matches a hand check against
  that season's weekly scores and pairings. That means 100% agreement, checked live against the
  running app, not only by tests.
- **SC-002**: The luckiest and unluckiest figures equal the expected-wins table's figures for the
  same team, league and season, to the displayed precision, every time.
- **SC-003**: The Waiver Wire Warrior's total for the named team matches a hand sum from that team's
  listed pickups and its recorded starting lineups.
- **SC-004**: For one real basketball league, the Embiid award's top absence for the named team
  matches a hand count of nights missed from the per-game records. No night the player's NBA team
  didn't play is counted.
- **SC-005**: A reader can tell from the section alone how many weeks it's based on, which
  superlatives are early-season noise, and that the Embiid award means missed games, without
  opening any other page.
- **SC-006**: The section renders meaningfully for both a football league and a basketball league,
  checked on one real league of each. Any superlative unavailable for a sport says why.
- **SC-007**: No superlative disagrees with the Weekly Report, the transactions view or the record
  book about the same underlying fact.

## Assumptions

- **Placement**: the League analysis page, near the Weekly Report. The idea doc left this open.
  It's the nearest existing home, and moving it changes nothing in the requirements.
- **Regular season only** for v1. Playoff weeks are left out and the exclusion is stated.
- **Past-season archive is out of scope** ("superlatives by season"). Whether earlier seasons even
  have game pairings depends on whether the pairings backfill ran. Spec 002 measured that they didn't
  at the time. Measure that in planning before promising it.
- **Recorded starting lineups for the current season** are assumed to exist, because the Weekly
  Report's *Self-inflicted wound* depends on them. This isn't measured. If they're missing, US3
  degrades per its scenario 4 and doesn't fall back to all rostered points.
- **Football absences are the open risk.** Football has no per-game record and the app doesn't know
  bye weeks. Planning must find out whether a stored signal (for example a zero week against the
  player's real team having played) can tell a bye from an absence. If not, football ships US4 as
  unavailable-with-reason (FR-015) and only basketball gets the award. This is a guess about what
  planning will find, not a finding.
- **Early-season threshold**: a hand-set number of weeks, labelled arbitrary (see FR-006). Four is a
  placeholder guess, not a measured value.
- **Close-game margin**: hand-set per sport (see FR-011). No value is proposed here. Pick one in
  planning from the league's real score spread, and say how it was picked.
- **"Most self-sabotaging"** means total points left on the bench across the season (best possible
  lineup minus actual). It needs only per-player points, not recorded starting lineups. That's
  assumed from reading the lineup code, not run.
- **The Unethical Award adds the feature's only new stored data**: a weekly record of Sleeper's
  suspension tag, and the commissioner's list. Everything else reads data the app already has. The
  suspension record can't be backfilled (only today's tag is available), so this season's tally
  starts when tracking starts. That's stated on the page (FR-019).
- **Sleeper reports suspensions in a player's status or injury tag.** That's assumed from Sleeper's
  data shape and not verified. No suspended player has been looked at in this app's data. Check it
  in planning against a known suspended player.
- **Superlative titles** ("Escape artist", "Heartbreak kid", "Waiver Wire Warrior", "The Joel Embiid
  Award", "The Unethical Award") are working names. The last three are Allan's.
