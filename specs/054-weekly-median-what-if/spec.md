# Feature Specification: Weekly median what-if on League history

**Feature Branch**: `054-weekly-median-what-if`

**Created**: 2026-11-02

**Status**: Draft

**Input**: User description: "in https://www.ballknowers.co/leagues/1339351318115946496/history can you fill in the rest of the data on what wouldve happen if we did enable weekly median and how many wins it wouldve gained each manger"

## Context: what is there now (read from the code on `main` ad1d031, 2026-11-02, not measured in prod)

- **The League history page** (`web/src/pages/LeagueHistory.tsx`) shows one standings table per
  season. Each has a **"Vs weekly median"** column (`LeagueHistory.tsx:432-469`): one game a
  week against that week's median score, counted on its own and deliberately **not** added to
  the real W-L (the How-this-works copy at `:651-656` says so).
- **That column is filled for one season only.** It is fed by the Luck page's expected-wins
  calculation, which answers for a single season (`ExpectedWinsService.forLeague`,
  `ExpectedWinsService.java:360`). The page uses its figures only where that season matches the
  table's (`LeagueHistory.tsx:393-396`); every other season shows a dash. This is the
  "rest of the data" the request is about.
- **The median calculation already exists and is tested** (`ExpectedWinsService.median`,
  `:169`): above the week's median is a win, below a loss, at it a tie; the median is the
  middle score, or the mean of the middle two with an even roster count. It counts regular-season
  weeks this app has marked final only.
- **The page never answers the actual question**: what the standings would have been *with*
  median games added. It shows the median games in isolation, never real + median combined,
  never how many wins each manager would have gained, and never whether the order would have
  changed.
- **Sleeper's own median setting is already read** (`league_average_match`,
  `LeagueRepository.PlayoffFormat.medianMatch`, `LeagueRepository.java:322`). When a season had
  it on, Sleeper's W-L *already includes* the median games, so a "what if we had enabled it"
  for that season would double-count. The history page does not consult this flag today.
- **The league in the request** is "Ball Knowers" (NBA 2026, Sleeper `1339351318115946496`),
  with earlier seasons linked behind it. Basketball is one game per week per team in this app
  (memory: NBA one game per week is correct), so the median game is likewise one per week.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Median record for every past season (Priority: P1)

A league member opens League history and scrolls through past seasons. Every completed season's
standings table shows each manager's record against the weekly median, not just the one season
the Luck page happens to cover.

**Why this priority**: This is the literal "fill in the rest of the data". Without it, nothing
else in this spec can be shown for older seasons.

**Independent Test**: Open the history page for a league with three or more seasons of
history; every season with final regular-season weeks shows a median record in every row, and
each row's median wins + losses + ties equals that season's number of final regular-season weeks.

**Acceptance Scenarios**:

1. **Given** a league with seasons 2024, 2025 and 2026 loaded, **When** a member opens League
   history, **Then** each of the three tables shows a median record for every manager who has
   final regular-season weeks in that season.
2. **Given** a season the Luck page does not currently cover, **When** the history page loads,
   **Then** that season's median column shows that season's own figures, never another season's
   and never a dash where the data exists.
3. **Given** a season with no final regular-season weeks yet, **When** the page loads, **Then**
   that season's median column shows a dash and the season is not silently dropped.

---

### User Story 2 - "If median had been on": combined record and wins gained (Priority: P1)

For each season that did *not* play median games, the member sees what each manager's
regular-season record would have been with a median game added every week (real games plus
median games), and how many wins that would have gained them.

**Why this priority**: This is the question the request actually asks: "what would have
happened" and "how many wins it would have gained each manager".

**Independent Test**: For one season, pick a manager; their "with median" wins equal their real
wins plus their median wins over the same weeks, their losses likewise, and the "wins gained"
figure equals their median wins.

**Acceptance Scenarios**:

1. **Given** a manager who went 8-6 in real games and 9-5 against the median over the same 14
   final weeks, **When** the table renders, **Then** it shows a "with median" record of 17-11 and
   median games "+9 W / +5 L" *(amended after analyze, 2026-11-02: was "+9 wins", superseded by FR-004)*.
2. **Given** two real and median halves counted over different weeks (Sleeper's W-L includes a
   week this app has not marked final), **When** the combined record is built, **Then** both halves
   count the same weeks, and the table names the span, so the combined record never mixes two
   game counts.
3. **Given** a season where the league already had Sleeper's median setting on, **When** the
   table renders, **Then** no what-if is shown for that season; the table says median games were
   already part of that season's real record.

---

### User Story 3 - Would the order have changed? (Priority: P2)

The member sees, per season, where each manager would have finished in the regular-season order
with median games counted, next to where they actually finished, so it is obvious who would have
moved up or down and who would have crossed the playoff line.

**Why this priority**: "What would have happened" is mostly a standings question. Wins alone
don't say whether anything changed; the order does. It builds on Story 2's records.

**Independent Test**: For one season, sort managers by the "with median" record; each row's
what-if place matches that order, and the movement shown equals actual place minus what-if place.

**Acceptance Scenarios**:

1. **Given** a manager who finished 6th in the real regular season and would rank 4th with median
   games, **When** the table renders, **Then** the row shows the what-if place 4 and a move of +2.
2. **Given** a league with 6 playoff spots, **When** a manager would cross the 6/7 line in either
   direction, **Then** the row marks it as "would have made" or "would have missed" the playoffs.
3. **Given** two managers level on the "with median" record, **When** their what-if places are
   assigned, **Then** the tie-break rule is the one the page states, and the How-this-works copy
   names it.

### Edge Cases

- **Odd roster count**: the median team ties every week by construction; their median games are
  ties, and their "wins gained" counts only wins (ties are shown, not converted to half wins in the
  wins-gained figure).
- **Score exactly at an even-count median**: a tie, consistent with the existing calculation.
- **A season already played with median on** (Story 2, scenario 3): no what-if; no double count.
- **Season in progress**: covers final regular-season weeks so far, labelled as in progress with
  the week count, the same way the existing columns name their span.
- **Playoff weeks**: never counted. Median games are a regular-season concept.
- **Unowned roster / roster with a mid-season manager change**: shown against the roster row as
  the standings already do; no attempt to split a season between managers.
- **A season whose stored weeks are partial or disputed** (the known Sleeper NBA 2025 matchup
  drift): counts only weeks marked final and states how many, rather than guessing the rest.
- **Format the app cannot seed** (divisions, non-standard seeding): Story 3's playoff-line marker
  is withheld for that season and the page says why; the records from Story 1 and 2 still show.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The history page MUST show each manager's record against the weekly median for
  every season with at least one final regular-season week, using that season's own games only.
- **FR-002**: The median rule MUST be the one already used on the page and the Luck page (win
  above, loss below, tie at the week's median; median of the rosters scored that week). One rule,
  one implementation; this feature MUST NOT introduce a second median calculation.
- **FR-003**: For each season that did not have Sleeper's median setting on, the page MUST show a
  "with median" record per manager equal to real regular-season W-L-T plus median W-L-T, both
  counted over the same final regular-season weeks.
- **FR-004** *(amended after review, 2026-11-02: show losses too, review #8)*: The page MUST show the median games each manager would have added for those seasons, wins AND losses (e.g. "+9 W / +5 L"), never wins alone; "wins gained" is the median wins.
  The place movement (FR-007) is what says whether those games helped.
- **FR-005**: For a season that already had the median setting on, the page MUST NOT show a
  what-if record or wins gained, and MUST say that median games were already in that season's
  real record.
- **FR-006**: The page MUST name the number of weeks each median and what-if figure covers
  wherever it could differ from the week count behind Sleeper's own W-L.
- **FR-007**: The page MUST show, per manager, the regular-season place with median games counted
  next to the actual regular-season place, and the movement between them.
- **FR-008**: Where the league's playoff format is one the app can seed (plain best-record
  ladder, no divisions), the page MUST mark managers whose what-if place crosses the playoff line
  in either direction. Where it cannot, it MUST withhold the marker and say so.
- **FR-009**: The what-if MUST be labelled as hypothetical everywhere it appears, and kept
  visually apart from the real record, the champion, and the real final rank. It MUST NOT change
  or replace any real figure.
- **FR-010**: The page MUST NOT claim what the playoffs, champion, or final ranks would have been.
  The bracket is not replayed (see Out of scope).
- **FR-011**: The How-this-works copy MUST explain the combined record, wins gained, the what-if
  place and its tie-break, and the already-on case, and replace the current copy's statement that
  median games are "not added to the real W-L record" with wording that fits both columns.
- **FR-012**: A failure to load the median/what-if figures MUST leave those columns as dashes and
  never block the real standings, matching how the page treats the existing optional columns.
- **FR-013**: The feature MUST work for both sports and for every league the history page already
  supports, not just the league in the request.
- **FR-014** *(amended during plan, 2026-11-02)*: The "Record vs all" column beside the median one
  MUST likewise show each season's own figures. It is dashed for older seasons for the identical
  reason (research R2), and filling one column while the one next to it stays dashed would read as
  broken.

### Key Entities

- **Season median record**: per season, per roster: median wins, losses, ties, and the number of
  final regular-season weeks counted.
- **What-if record**: per season, per roster: real W-L-T plus median W-L-T over the same weeks;
  wins gained; what-if place; actual place; playoff-line crossing (when the format allows).
- **Season median status**: per season: whether Sleeper's median setting was on (no what-if),
  off (what-if shown), or there is nothing final yet.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the requested league, every season with final regular-season weeks shows a median
  record for 100% of its rows (zero dashes where data exists), checked live against the deployed
  or local real server.
- **SC-002** *(amended after review, 2026-11-02: per team, review #1)*: For every row on that league, median W + L + T equals that team's own counted game count,
  and "with median" W + L + T equals real games plus median games over those same games.
- **SC-003**: A hand check of at least two managers in at least two seasons, computed directly
  from that season's weekly scores, matches the page exactly (median record, combined record, wins
  gained, what-if place).
- **SC-004**: The figure for the one season the Luck page covers is identical on the history page
  and the Luck page (same rule, same weeks).
- **SC-005**: A season that already had median on shows no what-if record, verified by a test with
  that setting on.
- **SC-006**: The history page's first render is no slower than today by more than 1 second for
  the requested league with all its seasons.
- **SC-007**: A member can answer "how many wins would I have gained, and would I have made the
  playoffs" for any season from the table and its explainer alone, without leaving the page.

## Out of scope

- **Replaying the playoffs.** Who would have won the bracket depends on seeds *and* playoff
  games; the page stops at the regular-season order and the playoff line. Saying who would have
  been champion would make the tool look more certain than the data allows.
- **Changing the real record or any stored standings.** This is a what-if display only.
- **Changing the Luck page.** It keeps its one-season scope; this feature only stops the history
  page from depending on that scope.
- **A per-week drilldown of median games.** Possible later; not asked for.
- **ESPN leagues' median setting.** ESPN has no median game (`EspnTranslator.java:148` writes
  it as off), so the what-if applies there unchanged.

## Assumptions

- "Fill in the rest of the data" means the median column for the seasons that show dashes today,
  and "what would have happened / wins gained" means the combined real + median record and its
  effect on the regular-season order. A playoff replay was not asked for and is out of scope.
- Regular season only, final weeks only, matching the existing columns.
- *(amended after review, 2026-11-02)* The what-if tie-break is win share (W + ½T per game), then points for, then roster id; points for is the conventional
  Sleeper default, and the league sets no other tie-breaker (research R5). Both places use the same rule.
- "Actual place" is the regular-season order by the app's own counted weeks, not Sleeper's final
  rank (which includes playoffs and is shown separately as "Rank").
- Seasons are the ones the history page already lists; no new ingest is needed, but older
  seasons must have their weekly scores stored. If any season lacks them, the page shows a dash
  for that season and the plan must measure how many seasons this affects on the requested league.
