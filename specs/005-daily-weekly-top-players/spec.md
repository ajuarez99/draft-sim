# Feature Specification: Best nights, beside best weeks

**Feature Branch**: `005-daily-weekly-top-players`

**Created**: 2026-09-19

**Status**: Draft

**Input**: User description: "i want a section somwehre where it makes sense to have best daily
players then another secition next two it witht the best players that week"

## Problem

The Weekly Report already has a **Top performers** list: the week's best players by points, with the
owning team named. It answers one question and labels it as though it answered a different one.

For basketball that gap is the whole feature. An NBA player plays three or four games in a fantasy
week, on named nights, against named opponents. "Jokić — 58.5" tells a manager nothing about whether
that was one enormous Tuesday or four quiet outings, and those are completely different stories to
argue about in a league chat.

### What was measured, and why it makes this cheaper than it looks

Measured on 2026-09-19 against NBA league `1229352720222134272` (Ball Knowers, 2025,
`status: complete`, 21 scored weeks):

**The points already stored for basketball are single-game numbers.** `roster_week_points.players_points`
holds roughly one game's worth per player per week, not the week's sum. Jokić's stored week 5 value is
58.5; his four games that week were 58.5, 34.0, 44.0 and 45.5, summing to 182.0. Across 18 weeks and
two players, the stored value equalled exactly one game every time and the sum never once.

This was checked carefully and is **not an ingest defect**. Sleeper's own matchup payload reports 58.5,
and the app's weeks 1–18 totals reconcile against Sleeper's own season `fpts` per roster — exactly for
roster 3 (4583.0 vs 4583.0), within 6.5 points for three others. The league's settings carry
`game_mode: 1`, and roughly 240 points per roster per week is simply what this format produces. An
earlier ~710-point discrepancy turned out to be playoff weeks 19–21 counted locally but excluded from
Sleeper's regular-season `fpts`.

So the "best nights" list is largely **naming what is already on screen**: attaching a date and an
opponent to a number the page already prints. The "best weeks" list is the genuinely new one, because
a week total across every game a player played is a figure this app has never stored.

Per-game data exists and carries what is needed — date, opponent, home/away and a full box score — but
only through a per-player lookup, so obtaining it has a cost that scales with roster size rather than
with leagues.

### Football is deliberately untouched

An NFL player plays at most one game per scoring period. "Best night" and "best week" are therefore the
same list, in the same order, with the same numbers — so there is nothing here for football to gain and
a working page to put at risk. The decision (2026-09-19) is that football keeps its current Top
performers list exactly as it is, and the new pair appears only where a player can play more than once
per scoring period.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Best nights of the week (Priority: P1)

A manager opens the Weekly Report for a basketball league and sees the week's biggest single-game
performances: the player, what they scored, **which night**, and **against whom**. It answers "what was
the best individual game anyone on a roster played this week".

**Why this priority**: It is the section the user asked for first, it is the one basketball genuinely
lacks, and the underlying points are already stored and already displayed — what is missing is the
night and the opponent that make the number mean something.

**Independent Test**: Open a scored week of an ingested NBA league and confirm the list names a date and
an opponent for every entry, ordered by points, with the owning team shown. Ships without the second
section and is useful alone.

**Acceptance Scenarios**:

1. **Given** a scored week in a basketball league, **When** the Weekly Report loads, **Then** a Best
   Nights section lists the top single-game performances, each showing player, position, owning team,
   points, the calendar date and the opponent.
2. **Given** the same week, **When** the list is ordered, **Then** it is ordered by the points scored in
   that single game, highest first.
3. **Given** a player who appears in the list, **When** their entry is read, **Then** the points shown
   are that one game's points and are never a total across several games.
4. **Given** a week where per-game detail cannot be obtained for a player, **When** the section renders,
   **Then** that player is either omitted or shown without a date, and the reason is stated — never a
   guessed or blank date presented as fact.
5. **Given** a league with no scored week, **When** the page loads, **Then** the section says so rather
   than rendering an empty list.

---

### User Story 2 - Best weeks, beside them (Priority: P2)

Next to Best Nights, a second section ranks players by **everything they scored across the whole
fantasy week** — all games summed. For basketball the two lists differ sharply: a player with four
steady games can top the week without owning a single night.

**Why this priority**: It is the comparison that makes the pair worth having, and it is the half that
needs data the app has never stored. It is sequenced second because Best Nights is useful without it and
it is not useful without Best Nights.

**Independent Test**: For one scored week of an NBA league, confirm each entry's total equals the sum of
that player's individual games that week, and that the two sections visibly disagree in ordering.

**Acceptance Scenarios**:

1. **Given** a scored week in a basketball league, **When** the Weekly Report loads, **Then** a Best
   Week section sits beside Best Nights, ranking players by their total across every game they played
   that week, showing the number of games that total covers.
2. **Given** a player in the Best Week list, **When** their total is read, **Then** it equals the sum of
   their individual games for that week.
3. **Given** a week where the two rankings differ, **When** both sections are read, **Then** they are
   visibly distinct lists and each is labelled so a reader cannot mistake one for the other.
4. **Given** a player whose week total includes games the league's own scoring did not count, **When**
   the total is shown, **Then** the page states what the figure does and does not represent, so it is
   never mistaken for points that decided a matchup.
5. **Given** per-game data that cannot be obtained for a week, **When** the section renders, **Then** it
   says the week's totals are unavailable and why, rather than falling back to the stored single-game
   value and presenting it as a week.

---

### User Story 3 - Football is left exactly as it is (Priority: P3)

A manager opens the Weekly Report for a football league and sees precisely what they see today: the
existing Top performers list, unchanged. Neither new section appears, because an NFL player plays at
most one game per scoring period and the pair would be the same list twice.

**Why this priority**: It is the guard that keeps this a basketball feature rather than a change to a
page football managers already use. It is last because it is satisfied by *not* acting, and the other
two stories are verifiable without it.

**Independent Test**: Open a scored week of an ingested NFL league and confirm the page is unchanged
from its current behaviour — Top performers renders as before and neither new section is present.

**Acceptance Scenarios**:

1. **Given** a scored week in a football league, **When** the Weekly Report loads, **Then** the existing
   Top performers list renders as it does today and neither Best Nights nor Best Week appears.
2. **Given** a sport where a player can play more than once per scoring period, **When** the page loads,
   **Then** the pair replaces the Top performers list for that sport.
3. **Given** the decision of which form to render, **When** it is made, **Then** it follows from the
   sport's own rules about games per scoring period, never from a sport name compared in a service or a
   component — so a future sport is served correctly without this page being edited.

---

### Edge Cases

- A player is rostered by nobody for part of the week — is their game eligible for Best Nights? (Assumed
  yes if they were on a roster when the week was scored; see Assumptions.)
- A player plays zero games in a fantasy week: they must not appear in Best Week with a total of 0.
- Two performances tie exactly on points — ordering must be stable and not vary between loads.
- A game is postponed into a different fantasy week: the night belongs to the week the game was played,
  not the week it was scheduled.
- A week is in progress rather than final: the sections must not imply the week's best is settled.
- Per-game retrieval partially fails: some players enriched, others not, within the same week.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Weekly Report MUST present a Best Nights ranking of single-game performances for a
  chosen scored week, each naming the player, the owning team, the points, the calendar date and the
  opponent.
- **FR-002**: The Weekly Report MUST present a Best Week ranking beside it, ordered by a player's total
  across every game they played in that fantasy week, stating how many games each total covers.
- **FR-003**: A single-game figure MUST never be presented as a week total, and a week total MUST never
  be presented as a single game. Each section MUST be labelled so the two cannot be confused.
- **FR-004**: Whether both sections apply MUST be decided by the sport's own rules — specifically
  whether a player can play more than once per scoring period — and never by a sport name compared in a
  service or a component.
- **FR-005**: A week total that includes games the league's scoring did not count MUST state what it
  represents, so it is not read as points that decided a matchup.
- **FR-006**: When per-game detail cannot be obtained, the affected section MUST say so and why, and
  MUST NOT substitute the stored single-game value for a week total or invent a date.
- **FR-007**: Every entry MUST carry its exact value beside the name, per the project's existing charting
  rule; one encoding per mark.
- **FR-008**: The sections MUST read from stored data on page load rather than performing per-player
  retrieval while a reader waits.
- **FR-009**: Ordering MUST be deterministic, including for exact ties, so two loads of the same week
  produce the same list.
- **FR-010**: A week with no scored games MUST produce a stated reason rather than an empty or
  zero-filled ranking.

### Key Entities *(include if data involved)*

- **Game performance** — one player's single game: the player, the calendar date, the opponent, whether
  it was home or away, and the points that game earned under the league's scoring.
- **Player week** — one player's fantasy week: the games that fell in it, the total across them, the
  count of those games, and whether that total is complete or partial.
- **Ranking entry** — a placed row in either section: the player, their owning team at the time the week
  was scored, the figure being ranked, and which of the two measures it is.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a scored basketball week, a reader can name the single best individual game of that
  week, the night it happened and the opponent, without leaving the page.
- **SC-002**: For at least one scored basketball week, the two rankings differ in their top three,
  demonstrating the pair carries information a single list did not.
- **SC-003**: Every Best Week total equals the sum of that player's individual games for the week, for
  every entry shown.
- **SC-004**: In a football league, the Weekly Report shows exactly what it shows today — the existing
  Top performers list, with neither new section present.
- **SC-005**: Both sections render for an ingested basketball league through the same path football
  uses, with no sport name compared outside the sport rules.
- **SC-006**: Two consecutive loads of the same scored week produce identical orderings in both sections.

## Assumptions

- **Home is the Weekly Report.** The feature extends the existing per-week page, which already carries a
  week selector, matchups, awards and a Top performers list. For sports where the pair applies it
  replaces that existing list rather than sitting alongside a third ranking of the same thing; for
  football the existing list stays exactly as it is.
- **Scope is the Weekly Report only.** A season-level "best nights of the year" is a natural follow-on
  and is deliberately not planned here.
- **Eligibility follows rostered status at scoring time**, matching how the existing Top performers list
  is built from stored per-player points, rather than reconstructing who owned a player on each night.
- **Five entries per section** unless the design finds a better number; the exact count is a presentation
  detail, not a requirement.
- **This is a basketball feature.** Confirmed with the user on 2026-09-19: football keeps its current
  Top performers list untouched, and the pair appears only where a player can play more than once per
  scoring period. Football is in scope only as the case the feature must leave alone.
- **The all-games week total is shown, and labelled.** Confirmed with the user on 2026-09-19: the Best
  Week figure counts every game a player played, including games the league's scoring did not count, and
  the page must state that it is real-world production rather than points that decided a matchup
  (FR-005).
- **The existing per-week storage stays the source for points under league scoring.** This feature adds
  the night and the week total; it does not re-derive what a player scored for a matchup.
- **Dependency**: per-game detail with dates and opponents is obtainable per player from the existing
  upstream provider; the retrieval cost scales with roster size, which the planning phase must account
  for rather than assume away.

## Out of scope

- A season-level or career-level version of either ranking.
- Changing how any matchup, roster total or efficiency figure is computed. This feature adds a reading
  of existing data; it does not restate what the league scored.
- Projections of who will have the best night next week — this app's projection source is football's,
  and the whole point of this feature is that basketball is served by what already happened.
