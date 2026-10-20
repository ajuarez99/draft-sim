# Feature Specification: Awards — commissioner's list, every player, and a fairer Embiid cost

**Feature Branch**: `040-superlatives-commissioner-ux`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "ok season starting. we have the commissioners list there but need it more fleshed out and a better UX to properly use that. so lets improve the flow and the location and look of that list to determine the unethical award. also for some of the awards id like to see all players as well as an option. so for the joel embiid award it only show 3 but id like to see all of them if i click on it also need to calculate it better as we didnt really lose all those points we lost the average of those many points in a week"

## What's there now (read from `main` at 7736114)

- **Commissioner's list** — `web/src/pages/Superlatives.tsx:696-907` (`ConductListSection`). It sits at the very
  bottom of the Awards page, below every card and below "How this works", disconnected from the Unethical
  Award card it feeds. Adding a player means typing a **raw Sleeper player id** (`:862-868`) — the doc comment
  at `:712-719` records this as a fallback because no player picker existed. An entry has a reason (≤140 chars)
  and an "applies from week" with **no end week**, so a three-game suspension counts for the rest of the
  season (`SeasonSuperlativesService.computeUnethical`, `:1159-1163`). Entries can be added or removed but
  **not edited** — the save is an upsert keyed on (league-season, player) (`LeagueConductRepository:59`,
  `V22__season_superlatives.sql:88`), invisible in the UI. A row shows player, reason, start week and adder,
  but not which fantasy team it is currently costing or how many player-weeks it has produced.
- **New season lock** — the list follows the season the page resolves to (spec 008 amendment 15), so before
  a new season's first scored week the page shows *last* season and the new season's list can't be touched
  (`Superlatives.tsx:815-819`). NBA 2026 tipped off on 2026-10-20; that is the window the commissioner is in now.
- **Player-level detail is capped at 3** — Joel Embiid (`SeasonSuperlativesService.java:1022`) and Waiver Wire
  Warrior (`:764`) send only each winner's top 3 players. The "See all" modal (spec 010,
  `components/SuperlativeStandings.tsx`) ranks every *team* but shows no players. Jabari Smith Jr. ranks
  players but stops at the top 10 plus ties (`:832`).
- **Embiid cost** — `AbsenceCost.compute` charges `gamesMissed × mean points per game played`
  (`AbsenceCost.java:96-104`). In football that is one game per week. In basketball this league credits
  **one game per player per week** (measured 2026-09-19 and 2026-10-05: `starters_points` = Σ starters'
  `players_points` in 540/540 roster-weeks), so a star who sits three games of a four-game week is charged
  three games of points the team could never have scored — the credited game came from the night he played.

> **This feature reverses a decision Allan made on 2026-09-23.** Spec 008 deliberately costed every missed
> game ("as a player you want every single game anyways"), recorded in `AbsenceCost`'s class comment and in
> project memory as "don't re-propose week-level costing". Allan is now asking for the opposite, on his own
> initiative, after seeing real numbers. The old rule's rationale gets a dated "superseded" note in place, not a
> silent rewrite.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Embiid cost reflects what a team could actually have scored (Priority: P1)

A manager opens the Joel Embiid Award in a basketball league. A player who missed games is charged what his
team realistically lost that week — at most one week's worth of his average production — not the sum of
every game he sat.

**Why this priority**: The current number is wrong by up to ~3× in basketball and it is the award's headline
figure. Every other part of this feature displays this number, so it has to be right first.

**Independent Test**: For a known basketball player-week where he sat 3 of 4 games, the award shows the new
weekly cost, not 3 × his per-game average; a football league's award shows the same totals as before.

**Acceptance Scenarios**:

1. **Given** a basketball regular contributor who missed every game of a fantasy week while on a roster,
   **When** the award is computed, **Then** that week costs the roster his average points per game for the season,
   once — not once per game missed. *(Amended 2026-10-20 after plan review: was "his average credited weekly
   score"; Allan chose the per-game average, see Clarifications.)*
2. **Given** a basketball player who missed some games of a week but played at least one, **When** the award is
   computed, **Then** that week costs nothing — the week's credited game came from a night he played.
3. **Given** a football league, **When** the award is computed, **Then** every roster's total is unchanged from
   the previous rule (one game per week makes the two rules agree), within rounding.
4. **Given** any card or list showing an Embiid figure, **When** a reader looks at it, **Then** it says what the
   number is ("N weeks missed · X pts/game · Y pts lost") rather than a count of games that no longer drives it.

---

### User Story 2 - The commissioner can run the Unethical list without leaving the award (Priority: P1)

The commissioner sees a player in the news, opens the Unethical Award, picks the player by name, sets the
week range and reason, and immediately sees which team it charges. Every member can read the same list in
the same place.

**Why this priority**: The NBA season started today. The list is the only real input to the Unethical Award
(zero suspended players were rostered when last measured), and the current flow — raw Sleeper ids, bottom
of the page, last season's list until week 1 is scored — is effectively unusable.

**Independent Test**: As the commissioner of a league whose new season has no scored week yet, add, edit and
remove an entry by player name, with a start and end week, without typing an id; as a non-commissioner,
see the same list read-only.

**Acceptance Scenarios**:

1. **Given** the Awards page, **When** anyone looks at the Unethical Award, **Then** the Unethical card carries a "Commissioner's list" button that opens a panel holding the
   list (add/edit/remove for the commissioner, read-only for everyone else), and the list no longer sits
   orphaned at the bottom of the page.
2. **Given** the commissioner adds an entry, **When** they start typing a player's name, **Then** they can pick
   from the players currently on this league's rosters (name, position, fantasy team, avatar), with no raw id.
3. **Given** an entry, **When** the commissioner sets "from week" and optionally "through week", **Then** only
   weeks in that range count; an empty "through week" means "rest of the season".
4. **Given** an existing entry, **When** the commissioner edits its reason or weeks, **Then** the change saves
   in place and the Unethical card reflects it without a page reload.
5. **Given** a list entry, **When** anyone reads it, **Then** it shows the player, the fantasy team(s) it has
   charged, the week range, how many player-weeks it has contributed so far, the reason and who added it.
6. **Given** a new season with no scored week yet, **When** the commissioner opens the list, **Then** they can
   edit *this* season's list, and the page says plainly that the awards above still show last season.
7. **Given** a non-commissioner, **When** they view the list, **Then** it is read-only with no edit affordances.

---

### User Story 3 - See every player behind a player-based award (Priority: P2)

On the Joel Embiid Award a reader sees the winner's top 3 players on the card; clicking through shows every
player who cost any team points, across the whole league. The same "all players" option exists for the
other awards whose figure is built from individual players.

**Why this priority**: Explains the headline numbers and makes the award fun to argue about, but depends on
US1's number being right.

**Independent Test**: Open the Embiid "See all" view, switch to players, and see every costed player with
his team, weeks missed and points lost, summing to each team's total in the team view.

**Acceptance Scenarios**:

1. **Given** the Embiid, Waiver Wire Warrior or Unethical award, **When** the reader opens its full view,
   **Then** they can switch between the existing team standings and a ranked list of every contributing player.
2. **Given** the player list, **When** read, **Then** each row shows player, fantasy team, the award's per-player
   figure, and rank; a player who contributed to two teams appears once per team.
3. **Given** the player list for any team, **When** summed, **Then** it equals that team's figure in the team view.
4. **Given** Jabari Smith Jr., **When** the reader opens its full view, **Then** the list is not capped at 10.
5. **Given** an award with no player-level breakdown (highest week, luck, etc.), **When** opened, **Then** no
   players option appears.

---

### Edge Cases

- A list entry for a player who isn't on any roster in the chosen weeks: saved and shown, contributes 0
  player-weeks, and says so ("not rostered in these weeks") rather than looking broken.
- A player traded or dropped mid-range: charged only to the team(s) that rostered him in each counted week.
- "Through week" before "from week": rejected with a message.
- Duplicate entry for the same player: the picker shows he's already listed and offers edit instead.
- Basketball player with zero games played all season (season-long injury from week 1): no per-game average
  exists — not charged, same as today's regular-contributor rule.
- A week with no basketball games scheduled for his NBA team (e.g. All-Star break): not a missed week.
- Commissioner unknown for the league: list readable, the "can't be edited" note stays.
- A past season's list: still viewable when that season is selected; editing a completed season is allowed
  only as it is today (no new restriction added).

## Requirements *(mandatory)*

### Functional Requirements

**Embiid cost (US1)**

- **FR-001**: A missed week MUST cost a roster one game's worth of the player's production, valued at his mean
  points per game played this regular season under the league's scoring (today's value), charged once per week.
  *(Amended 2026-10-20 after plan review F1: was the mean credited weekly score.)*
- **FR-002**: A basketball week in which the player played at least one game MUST cost nothing; only weeks
  with games scheduled for his team and zero games played count.
- **FR-003**: Football totals MUST be unchanged from the current rule (same counted weeks, same value).
- **FR-004**: The regular-contributor rule, regular-season window, unclassified-week coverage notes and the
  ≤ 0 empty state from specs 008/010 MUST carry over unchanged.
- **FR-005**: Every place an Embiid figure is shown MUST describe it in weeks missed and points per game, and the
  "How this works" text MUST state the rule in one sentence, including that it is an estimate.

**Commissioner's list (US2)**

- **FR-006**: The list MUST open from a "Commissioner's list" control on the Unethical Award card, as a panel;
  the bottom-of-page section is removed.
- **FR-007**: Adding an entry MUST use a name search over the players on this league's rosters for the
  season being edited; typing a Sleeper id MUST NOT be required.
- **FR-008**: An entry MUST support a start week and an optional end week; the Unethical Award MUST count only
  weeks inside that range.
- **FR-009**: The commissioner MUST be able to edit an entry's reason and weeks in place, and remove it with a
  confirmation.
- **FR-010**: Each entry MUST show the player, the fantasy team(s) it charged, its week range, player-weeks
  contributed so far, reason and adder.
- **FR-011**: The commissioner MUST be able to edit the current season's list before that season's first
  scored week; the page MUST say which season the list and the award cards each belong to whenever they differ.
- **FR-012**: Write access MUST stay exactly as today (Sleeper commissioner, honour system); the list stays
  scoped to one league-season.

**All players (US3)**

- **FR-013**: Embiid, Waiver Wire Warrior and Unethical MUST offer a players view listing every contributing
  player across all rosters, ranked by the award's per-player figure, with competition ranks on ties.
- **FR-014**: The card itself MAY keep its top-3 preview; the full list MUST be one click away from it.
- **FR-015**: Jabari Smith Jr.'s full view MUST list every eligible player (the existing 2-add minimum and D/ST exclusion from spec 008 stay), not top 10 + ties. *(Amended 2026-10-20 after code review M2: was "at least one add", which contradicted the 2-add floor Allan approved on 2026-09-28.)*

### Key Entities

- **Conduct entry**: one player on one league-season's commissioner list — player, reason, from-week,
  optional through-week (new), who added it, when. Derived for display: teams charged, player-weeks counted.
- **Player award contribution**: one (award, team, player) row — the player's figure for that team
  (points lost, pickup points, flagged weeks, adds), used by both the card preview and the players view.
- **Weekly absence cost**: per (team, player, week) — whether the week counts and what it costs under FR-001/002.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A commissioner can add a player to the list by name, with a week range, in under 30 seconds and
  without leaving the Unethical Award.
- **SC-002**: On both scored NBA leagues (2024 and 2025): a per-roster before/after table is reported; every counted
  (roster, player, week) has zero games played that week; one hand-checked partial week costs 0; no (player, week)
  is counted for two rosters. *(Amended 2026-10-20 after plan review F8: the old wording was true by definition.)*
- **SC-003**: On both real NFL 2025 leagues, every roster's Embiid total matches the pre-change total exactly.
- **SC-004**: For every player-based award, the players view sums to each team's figure in the team view with
  0 mismatches on the real leagues.
- **SC-005**: A three-week suspension entered with an end week contributes exactly the rostered weeks inside
  the range — verified against a real entry in a local league.
- **SC-006**: A non-commissioner sees the list with zero edit controls; a commissioner's write is still refused
  server-side for anyone else.

## Clarifications

### Session 2026-10-20

- Q: A basketball week where the player sat some games but played at least one — what does it cost? → A: Nothing; only weeks with zero games played count, each at his average credited weekly score *(value amended below)*.
- Q: (after plan review F1) What is a missed week worth — the league's credited weekly points (rostered weeks only; NFL totals shift by up to ~11 pts) or the per-game average? → A: Per-game average. Football stays identical; basketball runs ~12% below the credited week, an accepted, labelled under-estimate.
- Q: Where does the commissioner's list live? → A: Inside the Unethical Award — a "Commissioner's list" button on its card opens a panel (edit for the commissioner, read-only for others).

## Assumptions

- The picker searches players rostered in this league-season (the only players the award can ever charge);
  searching every NBA/NFL player is out of scope.
- A missed week is valued at the player's per-game average (Allan, 2026-10-20). In basketball the credited game
  averages +11.9% over the per-game mean (measured, NBA 2025), so the figure is a deliberate, labelled
  under-estimate.
- Waiver Wire Warrior, Unethical and Jabari are the "some of the awards" alongside Embiid, because they're the
  ones built from individual players; team-only awards are unchanged.
- Existing list entries keep working: they get an empty end week, i.e. today's behaviour.
- No change to who can edit, to suspension capture, or to the award's team-level ranking rules.

## Not being built

- A global player search beyond the league's rosters.
- Notifications to the league when an entry is added.
- Backfilling Sleeper suspension captures for weeks before capture started (still impossible).
- Re-deriving which game Sleeper credits in a multi-game basketball week (mechanism still unknown).
