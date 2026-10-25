# Feature Specification: Hall of fame (trophy case and discovered rivalries)

**Feature Branch**: `047-hall-of-fame`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "whats next". Allan picked roadmap item 4.1, Hall of fame, from the four
candidates left on `claude/competitor-gap-roadmap.md` (2026-10-24). The design is
`claude/hall-of-fame-and-rivalries.md`. The roadmap puts it before 4.3 Wrapped (spec 038, planned on
its own branch), because Wrapped's slides draw on it.

## Background

What exists now. This was checked in code and data on main `ebf4ee8` (2026-10-24), not taken from
the 2026-10-05 design doc, which is out of date in two places:

- **Spec 034 has already built half of the doc's "rivalries" section.** The Rivalries page
  (`/leagues/:id/rivalries`, `RivalryService.java`) has:
  - an all-time manager × manager head-to-head matrix per league chain;
  - the regular-season/playoff split (`RivalryGameKind`), which the doc said had to be added to
    `HeadToHeadService`;
  - a hand-set minimum-meetings threshold N (`config/weights.yml` `rivalry.min-meetings: 3`),
    printed on the page;
  - the rivalry week recommendation.

  What it doesn't do is **discover** anything. The reader still has to scan the grid to find the
  most-played pair, the closest rivalry, or their own nemesis.
- **Placements are thinner than the doc assumed (measured 2026-10-24 on the local 5433 DB).** Every
  complete season of every real league has `final_placement` on **1 roster of 12**: the champion
  only. That covers "Ball Knowers" NBA 2024 and 2025, "(Foot) Ball Knowers" NFL 2025, and "West
  Coast Fantasy Football" NFL 2025. The reason is that `LeagueHistoryIngestService` reads the
  champion from `league.metadata.latest_league_winner_roster_id`, with the V21 status gate. The doc's
  rule, "last place is the highest `final_placement`", would therefore name the **champion** as last
  place in every season. Its fallback text ("last place not reported by Sleeper") would be true
  everywhere and would hide that the rule was wrong.
- The winners bracket **is** stored (V33 `league_bracket_match`, from spec 034, written by
  `BracketIngestService`). Each match has its winner, loser and placement-game number, so the
  championship game's loser can be known. The losers bracket ("toilet bowl") is not fetched.
- Champions already appear as a 🏆 in standings rows (`LeagueHistoryController.java:318`) and as a
  title count on manager pages (`ManagerCareerService.java:203`). Both require a stored placement
  of 1 **and** a complete season. The trophy case must use that same rule rather than a second copy.
- Season superlatives (spec 008/010/040) exist per season on `/leagues/:id/superlatives`. There's no
  all-time view of who won them.
- The record book (`LeagueRecordService.RecordBook`) covers single-week highs and lows, margins,
  all-time points leaders and streaks. This feature doesn't duplicate it.

**Thin data shapes everything here.** Chains have 1–3 seasons. Most pairs have met 2–5 times, and one
complete NFL season has a single champion. Every claim prints the count it rests on, and a
claim below the threshold isn't made.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Trophy case: who won what, every season (Priority: P1)

A manager opens their league's history and sees a trophy case: one row per completed season of this
league chain, naming the champion, the runner-up, the regular-season points leader, and last place.
Each name links to that manager's page. A season that isn't finished has no row.

**Why this priority**: it's the gathered view the doc says is missing. Today a champion is a small
icon in one standings row per season, and the runner-up and points leader aren't named anywhere.
It's also what Wrapped (spec 038) needs.

**Independent Test**: on "(Foot) Ball Knowers" and "Ball Knowers" (NBA), the champion in every
trophy-case row equals the manager the existing 🏆 marks for that season. A test asserts this.

**Acceptance Scenarios**:

1. **Given** a complete season, **When** the trophy case renders, **Then** its champion is exactly
   the manager the standings row marks with 🏆, by the same rule (stored placement 1 and season
   complete).
2. **Given** a complete season with a stored winners bracket, **When** the row renders, **Then** the
   runner-up is the loser of that bracket's championship game. If the bracket's champion
   disagrees with the stored champion, the row shows the stored champion and says the two sources
   disagree. It never picks one silently.
3. **Given** a complete season, **When** the row renders, **Then** the regular-season points leader
   is the manager with the most regular-season points for, with the number printed. A tie names
   every co-leader.
4. **Given** a season where Sleeper reports no last place, **When** the row renders, **Then** the
   last-place cell says "not reported by Sleeper". It's never inferred from regular-season record,
   and never taken as the highest stored placement.
5. **Given** a season in progress (e.g. 2026), **When** the trophy case renders, **Then** that season
   has no row and no partial champion.
6. **Given** a chain with no completed season, **When** the section opens, **Then** it says no
   season has finished yet, instead of an empty table.

---

### User Story 2 - Rivalries, discovered (Priority: P2)

On the Rivalries page, above the matrix, a manager sees the league's notable rivalries, already
picked out:

- **Most-played**: the pair with the most meetings.
- **Closest**: the most even record among pairs with at least N meetings.
- **Most lopsided**: the most one-sided record among pairs with at least N meetings.
- **Playoff meetings**: every pair that has met in the playoffs, with those games.

Every entry prints its record and meeting count, and links to that pair's versus page.

> **Amended at plan stage (research R5):** "the league's" means this season's managers, the
> matrix's own population, so every entry has a cell to open. Former managers appear in US3.

**Why this priority**: it answers "who's the rivalry in this league" without reading a 12×12 grid.
It reuses spec 034's numbers, so it should be cheap. It only has value if it never calls a 2–0
record a rivalry.

**Independent Test**: on a league with known history, each entry's record and meeting count equal
that pair's matrix cell, and no closest or lopsided entry has fewer than N meetings.

**Acceptance Scenarios**:

1. **Given** the league's matrix, **When** discovery renders, **Then** every number in it matches
   the matrix cell for that pair, both the total and the regular-season/playoff split.
2. **Given** pairs tied for most-played, closest or most lopsided, **When** it renders, **Then**
   all tied pairs are listed. The tie-break is never hidden.
3. **Given** no pair with at least N meetings, **When** it renders, **Then** closest and most
   lopsided say no pair has met N times yet. They name N and label it a hand-set threshold.
4. **Given** no playoff meetings in the chain, **When** it renders, **Then** the playoff list says
   so in words.
5. **Given** a basketball and a football league with the same managers, **When** either is opened,
   **Then** it counts only that league's chain.

---

### User Story 3 - Nemesis and victim on a manager's page (Priority: P2)

On a manager's history page, each league chain they've played in shows their **nemesis** (the
opponent they have the worst record against) and their **victim** (the one they have the best
record against). Only opponents with at least N meetings count. Each entry links to the versus page.

**Why this priority**: it's the personal version of US2, and the one managers will look for first.
It's P2 rather than P3 because it reads the same numbers as US2, so most of the cost is already paid.

**Independent Test**: for each manager and chain, the nemesis's record equals the matrix cell for
that pair, and no other opponent with N+ meetings has a worse record.

**Acceptance Scenarios**:

1. **Given** a manager with qualifying opponents, **When** their page renders, **Then** nemesis and
   victim show per league chain, with the record and meeting count, and never combined across
   chains or sports.
2. **Given** a manager with no opponent at N+ meetings in a chain, **When** it renders, **Then** that
   chain says there's no nemesis yet (no opponent met N times), not a thin pick.
3. **Given** a manager whose worst record is shared by two opponents, **When** it renders, **Then**
   both are named.
4. **Given** a manager whose only qualifying record is winning or even, **When** it renders, **Then**
   no "nemesis" is named. A nemesis needs a losing record, and a victim needs a winning one.

---

### User Story 4 - Past award winners in the trophy case (Priority: P3)

Each completed season's trophy-case row can be expanded to list that season's superlative winners
(spec 008's awards), linking to the Superlatives page for that season.

**Why this priority**: the doc asks for it, and it rounds out the case. But the per-season page
already shows the awards, so this is a gathering, not new information.

**Independent Test**: for each completed season, the expanded list equals the winners the
Superlatives page shows for that season.

**Acceptance Scenarios**:

1. **Given** a completed season, **When** its row is expanded, **Then** each award lists the same
   winner(s) the Superlatives page shows for that season, including that page's empty or "no
   winner" states.
2. **Given** a season in progress, **When** the trophy case renders, **Then** none of its awards
   appear here (awards appear once a season is final).

---

### Edge Cases

- **Seat changed hands mid-chain**: trophies and rivalries follow the manager, not the roster slot
  (the spec 034 rule).
- **A manager who left the league**: still appears in the trophy case for the seasons they won, and
  still counts as someone's nemesis. Linking to their page still works.
- **Two-week championship** (spec 034 `Final.twoWeek`): the runner-up comes from the bracket match,
  not from a single week's score.
- **Bracket missing for a complete season** (fetch failed, or an ESPN league): runner-up says "not
  available", and the champion still shows from the stored placement.
- **ESPN leagues** (spec 035): champion only, as ESPN ingest stores placement 1 only. The other cells
  follow the same "not reported" rules.
- **Disputed finals** (memory: NBA 2025 local drift; spec 034's "final disputed" note): wherever the
  Rivalries page shows that note, the trophy case shows it too, worded the same.
- **Median-scoring leagues**: the points leader is points for, which median scoring doesn't change.
  The record-based claims (nemesis, closest) count head-to-head meetings only, as spec 034's matrix
  does.
- **Ties in a meeting**: counted as in the matrix (a third number, when nonzero). An all-ties pair
  counts as even.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST show, for each league chain, a trophy case with one row per completed
  season, in both sports.
- **FR-002**: The trophy-case champion MUST use the exact rule the existing champion mark uses
  (stored placement 1 and season complete). There MUST be one implementation of that rule, not two.
- **FR-003**: Runner-up MUST come from a reported source (the championship game's loser in the stored
  bracket). It MUST NOT be inferred from standings or points. When the bracket and the stored
  champion disagree, the row MUST say so.
- **FR-004**: Last place MUST be shown only when Sleeper reports it, and otherwise read "not reported
  by Sleeper". It MUST NOT be inferred from regular-season record, and MUST NOT be read as the
  highest stored placement.
  > **Amended at plan stage, 2026-10-24 (research R2):** Sleeper's losers bracket was measured,
  > and which end of it is "last" depends on a setting the API returns as null. So no league has a
  > reported last place. A per-row "not reported" cell would be true in every row, which makes it a
  > dead column. The trophy case therefore has **no last-place column**. It prints one section note
  > saying Sleeper doesn't report last place. The rule itself (never inferred) is unchanged, and
  > acceptance scenario 1.4 now reads: the section note is shown and no row names a last place.
  >
  > **Amended after review, 2026-10-24 (plan-review F1). The amendment above was wrong and is
  > reversed.** The direction of the losers bracket *can* be measured from stored scores. In
  > "(Foot) Ball Knowers" 2025, `w` scored less in 7 of 7 matches (a toilet bowl). The NBA leagues and
  > West Coast score the other way (consolation brackets). I re-checked one match by hand: week 15,
  > roster 6 advanced on 123.96 against roster 7's 141.60. Allan chose to **build last place, and
  > refuse on doubt**. A season names last place only when:
  > - the losers bracket is stored;
  > - it covers every non-playoff roster;
  > - every decided match agrees on one direction, using stored scores.
  >
  > Toilet bowl → the p=1 `w`. Consolation → the loser of the bottom placement match. Any doubt → that
  > row says "not reported" and why. Today's measured data yields:
  > - FBK 2025 names roster 2 (2-12);
  > - West Coast 2025 resolves as a consolation bracket;
  > - both NBA seasons refuse, because one match each disagrees (the known NBA score drift).
  >
  > FR-004 and AS 1.4 stand as originally written. Last place is never inferred from the
  > regular-season record.
- **FR-005**: The regular-season points leader MUST print the points. Ties MUST name every co-leader.
- **FR-006**: Seasons not yet complete MUST NOT appear in the trophy case.
- **FR-007**: The Rivalries page MUST list most-played, closest and most lopsided pairs and all
  playoff meetings, each with its record and meeting count, taken from the same data as the matrix.
- **FR-008**: Closest, most lopsided, nemesis and victim MUST only consider pairs with at least N
  meetings, where N is the existing hand-set threshold, printed and labelled as hand-set.
- **FR-009**: Each manager's page MUST show nemesis and victim per league chain, never combined
  across chains or sports. A nemesis requires a losing record, and a victim a winning one.
- **FR-010**: Ties for any "most" or "worst" claim MUST list every tied entry.
- **FR-011**: Every entry naming a pair MUST link to that pair's existing versus page, and every
  manager name MUST link to that manager's page.
- **FR-012**: An expanded trophy-case row MUST list that season's superlative winners exactly as the
  Superlatives page shows them.
- **FR-013**: Every empty state (no completed season, no qualifying pair, no playoff meeting, no
  bracket) MUST say so in words. None may render an empty table.

### Key Entities

- **Trophy-case season**: a completed season of a chain, with its champion, runner-up (or why it
  isn't available), points leader(s) with points, last place (or "not reported"), and any
  source-disagreement note.
- **Discovered rivalry**: a pair, the claim it's listed under (most-played, closest, lopsided or
  playoff), and its record split and meeting count, all from the spec 034 pair record.
- **Nemesis/victim**: per manager per chain, the opponent(s) and the record, or the reason there
  isn't one.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every complete season of every real league in the local DB, the trophy-case
  champion equals the 🏆 standings mark: 4 of 4 seasons today, asserted by a test.
- **SC-002**: Zero discovered rivalries, nemeses or victims rest on fewer than N meetings, asserted by
  a test.
- **SC-003**: Every number printed by US2 and US3 equals the matching matrix cell. A test checks every
  pair in at least one league per sport.
- **SC-004**: Zero last-place names come from anything other than the losers-bracket refusal rule.

  > **Amended after analyze, 2026-10-24.** With today's data the rule gives:
  > - FBK 2025 → roster 2;
  > - West Coast 2025 → the consolation bottom-match loser;
  > - NBA 2024/2025 → refused (research R2, amended).
  >
  > The original text expected "not reported" everywhere. That was superseded by the F1 reversal.
- **SC-005**: A reader finds a league's champion history, or their own nemesis, in one page view,
  with no grid scanning.
- **SC-006**: Pages that already exist (Rivalries matrix, History standings, manager pages) show the
  same numbers before and after. Verified with a before/after capture, the way spec 045/046 did it.

## Assumptions

- **N is spec 034's `rivalry.min-meetings` (3)**, reused rather than a second threshold. One hand-set
  number for "enough meetings to call it a rivalry" is the one-source rule.
- **Runner-up and last place: whether to fetch Sleeper's losers bracket is a plan-stage question.**
  The spec only requires that last place come from a reported source or say "not reported". If the
  plan finds the losers bracket reliably reports last place, it may add that fetch. If not,
  "not reported" everywhere is an accepted outcome.
- "Regular-season points for" means the regular-season figure the standings already show. The plan
  confirms whether stored `points_for` includes playoff weeks.
- The trophy case lives on the History page, and discovery lives on the Rivalries page, next to the
  matrix it summarizes. This follows the doc's split, adjusted for spec 034 having added a Rivalries
  page since the doc was written.
- **Not building**:
  - physical trophies or belts (as in the doc);
  - cross-league or cross-sport rivalries;
  - a new "biggest rival this season" definition (spec 038 FR-009 decides that for Wrapped);
  - any change to the matrix, rivalry week, or superlative calculations themselves.
