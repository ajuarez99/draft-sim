# Feature Specification: NFL scoring check and draft grades

**Feature Branch**: `018-draft-grades`

**Created**: 2026-10-05

**Status**: Draft, amended after adversarial review 2026-10-06 ([plan-review.md](plan-review.md); dispositions in [plan.md](plan.md#amended-after-review-2026-10-06))

**Input**: User description: "phase 2". That's Phase 2 of
`claude/competitor-gap-roadmap.md`, scoped on 2026-10-05 (Allan's choice) to its first two
items, the critical path: **2.1** the NFL scoring check (`GameScoringService` vs
`players_points`) and **2.2** draft grades (how each pick actually scored). Items 2.3–2.6
(minutes trends, streaming, ownership timeline, schedule swap) are later specs.

Design doc: [claude/draft-grades.md](../../claude/draft-grades.md).

## Background

Measured 2026-10-05 against the local DB (research R1–R3):

- **The NFL scoring check passes in SQL.** Re-computing every starter-week's points from
  `player_game` × the league's `scoring_json` matches Sleeper's stored `players_points`
  exactly in **5,334 of 5,334** starter-weeks that have a game row, across five NFL
  leagues (2025 and 2026), D/ST included. The other 63 starter-weeks have no game row and
  every one of them is a 0-point starter. That's the SQL reimplementation of the rule,
  not `GameScoringService` itself, so the Java check is still owed (FR-001).
- **The NBA leagues credit one game per starter per week, not the week's games.**
  `starters_points` equals the sum of the starters' `players_points` in 540 of 540
  roster-weeks, and each `players_points` value is one game's score (2,153 of 2,171
  multi-game starter-weeks in 2025). Summing every game would overstate what the league
  awarded by ~2.5×. This was already in memory; it decides how NBA production is counted.
- **NBA drafts have no draft-time ADP** (0 of 168 picks, 2024 and 2025), and neither do
  the NFL 2025 drafts (0 of 180). Reach is unknown for all of them. The three NFL 2026
  drafts have it for every pick.
- NFL 2026 has **3 final weeks** today. The 2025 NBA league has a full season (21 league
  weeks).

**Correction to the roadmap and design doc (shown, not hidden):** the design doc's
"production = points this season" can't mean "sum every game" in basketball, and the
roadmap's "NFL drafts are five weeks old" is three scored weeks. Both get dated amended
notes. So does `claude/nba-schedule-grid-and-streaming.md`, whose "a 4-game week is
worth ~2×" contradicts the one-game-per-week measurement (research R2).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Prove NFL scoring before anything reads it (Priority: P1)

The app's own scoring of an NFL stat line reproduces what Sleeper credited, checked by
the real Java code against real stored rows, so draft grades (and later projections) can
read NFL `player_game` without a hidden mismatch.

**Why this priority**: it's the gate. The design doc says to stop if it fails. Nothing
user-facing ships in this story, but every NFL number in US2 depends on it.

**Independent Test**: run the scoring parity test. It passes on a fixture of real 2025
and 2026 starter-weeks and fails if one stat multiplier is changed.

**Acceptance Scenarios**:

1. **Given** a fixture of real NFL starter-weeks (stat lines, the league's scoring and
   Sleeper's credited points), **When** `GameScoringService` scores each stat line,
   **Then** every one matches the credited points to the cent.
2. **Given** the local DB with NFL leagues ingested, **When** the parity check runs over
   every starter-week, **Then** it reports how many matched, how many mismatched and how
   many had no game row, and a mismatch fails it.
3. **Given** the D/ST starters in the fixture, **When** scored, **Then** they match too.

---

### User Story 2 - See how each pick actually played out (Priority: P1)

A manager opens a finished draft's board and turns on "How it played out". Each pick
shows the player's production over the league's scored weeks and how far above or below
the players **at his position** drafted around him he finished (amended after review F1:
against all positions, NFL 2025's top 5 "steals" were all late QBs). A team strip shows each team's draft value, its best and
worst pick, and a letter beside the number.

**Why this priority**: it's the payoff of a draft simulator, and every input already
exists.

**Independent Test**: open `/drafts/1229352720230514688/board` (NBA "Ball Knowers" 2025),
turn on the view, and see a production number on all 168 picks, with injury seasons
(Walker Kessler, Trae Young, Sabonis) among the busts. The picks with 0 weeks played are
listed by name in verification (review N17).

**Acceptance Scenarios**:

1. **Given** a completed draft whose league has scored weeks, **When** the view is
   opened, **Then** every pick with a resolvable player shows production, weeks played
   and value over slot, labelled with what each measures and the weeks counted.
2. **Given** a synthetic draft where pick 10 outscores picks 1–9, **When** it's graded,
   **Then** pick 10 is the top steal (preference ordering, lessons bug class #1).
2a. **Given** a late QB scoring at QB-typical levels and a WR who outscored every WR
   drafted before him, **When** graded, **Then** the WR ranks above the QB (review F1).
3. **Given** a basketball league, **When** production is shown, **Then** it's labelled
   "season points, counting each week's average game". The legend states only what was
   measured about one-game crediting, and it's never called "league points" (review F5).
4. **Given** fewer than 4 scored weeks, **When** the view is opened, **Then** it leads
   with "early — N weeks played", and grades carry the existing early caveat.
5. **Given** a draft whose league has no scored weeks yet, **When** grades are requested,
   **Then** the answer is "unavailable" with a reason, not a table of zeros.

---

### User Story 3 - See what a pick did for the team that drafted him (Priority: P2)

Beside production, each pick shows the points that counted for the drafting team: the
same production rule, over the weeks he started for that roster in a week it played a
matchup (amended after review F2, F3). In basketball, Sleeper's exact credited sum is
shown separately and labelled as a different scale. A player dropped in
week 3 who broke out elsewhere has high production and near-zero "counted for you".

**Why this priority**: it's the second half of "was this a good pick".

**Independent Test**: a loyal starter's counted-for-you is close to his production, a
dropped player's is far lower, and `countedForYou <= production` always (review F2: comparing
credited with production made every NBA pick "differ").

**Acceptance Scenarios**:

1. **Given** a player who left the drafting roster, **When** shown, **Then** production
   and counted-for-you differ, each with its own label.
2. **Given** a pick whose drafting manager has no roster, or more than one, in that league
   season, **When** shown, **Then** counted-for-you is unknown, not 0, and counted in
   `unmappedPicks`.
3. **Given** a playoff week where the drafting roster had no matchup, **When** counted,
   **Then** that week adds nothing (review F3).

---

### User Story 4 - Steals and busts of the draft (Priority: P3)

A league-wide list: the five biggest steals and five biggest busts by value over slot,
each with the round it went in and the drafting team.

**Why this priority**: it's a view over US2's numbers, and it's the part people share.

**Independent Test**: the NBA 2025 list matches a hand query of the same rule.

---

### Edge Cases

- A pick with no player row, or an unfilled slot: excluded from grading and from every
  neighbour baseline, and counted in `excludedPicks`.
- A player with no game in any counted week: production 0, weeks played 0. Shown as
  "didn't play", not as missing.
- Picks near the start or end of the draft: the per-position fit treats them like any other
  pick (amended during build; the shifted window biased the first picks, see research R4).
- A draft with ADP: the existing steals & reaches view keeps working unchanged, and its
  numbers are never mixed into value over slot.
- A tie in team draft value: tied teams share a rank and a grade (`LetterGrades`).
- An NBA league's weeks 22–25 (after the league's last week) exist in `player_game`.
  They're never counted.
- An in-progress week: never counted (final weeks only).
- A final league week with no per-game data: not counted, and named in
  `weeksMissingGameData`. Never read as "nobody played" (review F7).
- A player with no positions: graded on production, but no baseline and no positional
  ranks (review N11).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The NFL scoring rule MUST be checked by `GameScoringService` itself against
  a committed fixture of real starter-weeks (can't be skipped silently), plus a DB-backed
  check over every stored starter-week that reports matched / mismatched / no-row counts.
- **FR-002**: Production MUST be computed by one rule for both sports: over the league's
  final weeks, the sum of the player's average league-scored game in each week he played.
  In football that's exactly the week's game (R1). In basketball it's a stand-in for the
  one game the league credits, and the payload says so (`productionBasis`).
- **FR-003**: Only the league's final weeks count (`ScoredWeeks.finalWeeks`), including
  its playoff weeks. `player_game` weeks outside them are ignored.
- **FR-004** *(amended during build 2026-10-06, Allan: per-position log fit)*: Value over slot MUST
  be production minus `a + b·ln(pickNo)`, fitted by least squares over the graded picks at the
  same position in this draft. It's null when that position has fewer than `minPicksPerPosition`
  picks (hand-set, ARBITRARY). *Superseded text:* Value over slot MUST be production minus the
  median production of the `n` same-position graded picks drafted just before and the `n`
  just after (shifted inward at the ends), with `n` hand-set in `config/weights.yml` and
  labelled ARBITRARY.
- **FR-005** *(amended after review F2, F3, F10)*: Counted-for-you MUST use production's
  weekly rule over counted weeks where he was in the drafting roster's starters **and** the
  roster had a matchup. Sleeper's credited sum is a separate field. Weeks with unknown
  starters are counted as unknown, never 0. Null when the drafter maps to zero or several
  rosters.
- **FR-006** *(amended after review F4)*: Team draft value MUST be the sum of its picks'
  value over slot **minus the draft's average team**, ranked with
  `LetterGrades.ranksDescending` and graded with the existing `grades` cutoffs. No second
  curve.
- **FR-007**: Every response MUST carry `weeksCounted`, `gradesEarly`
  (`SeasonWindow.isEarly`) and `earlyThresholdWeeks`. No separate early threshold.
- **FR-007a** *(added after review F7)*: Counted weeks MUST be the league's final weeks that
  also have per-game data. Any final week without it is named in the response.
- **FR-008**: Reach MUST NOT be recomputed server-side. The view reuses
  `stealsReaches.ts`. A null `adpAtDraft` stays unknown.
- **FR-009**: The endpoint MUST be scoped through `LeagueMembership.visibleDraft`, like
  every `/api/drafts/{id}/...` route.
- **FR-010**: Unavailable states MUST be an answer with a reason code, not an error:
  config block absent, draft not complete, no final weeks.
- **FR-011**: `web/src/api.ts` types MUST mirror the Java records in the same change.
- **FR-012**: The view MUST work at phone width without page-level horizontal scroll.

### Key Entities

- **PickGrade**: one pick's production, weeks played, value over slot, slot baseline,
  counted-for-you, and positional ranks.
- **TeamGrade**: one team's draft value, rank, grade, best and worst pick.
- **DraftGrades**: the response, with the counting window and caveats.

See [data-model.md](data-model.md).

## Success Criteria *(mandatory)*

- **SC-001**: The NFL parity test passes on real fixtures, and the DB check reports 0
  mismatches (today's SQL measurement: 5,334 / 5,334).
- **SC-002**: NBA 2025: all 168 picks get a production number. The page loads in the real
  UI against the real backend.
- **SC-003**: The preference-ordering tests pass: pick 10 outscoring picks 1–9 ranks as
  the top steal, and the positional test (review F1) passes. NFL 2025's top 5 steals are
  no longer all QBs (re-run in verification).
- **SC-004** *(amended)*: `countedForYou <= production` for every pick. A player who left
  the drafting roster shows a much lower counted-for-you, labelled.
- **SC-005**: Grades for the largest draft (210 picks) respond in a time measured and
  reported in verification (no target set; it's unmeasured).

## Assumptions

- Production is roster-independent on purpose: it judges the pick, not the manager's
  in-season moves. Counted-for-you is the roster-dependent number.
- Mock drafts aren't graded (no season behind them). Predicted-vs-actual for past drafts
  isn't built: no pre-draft sim was stored, and re-running one would leak (design doc).
- Keepers and auctions aren't handled: no league has them.
