# Feature Specification: Lock-in threshold — how far above projection people lock a game in, and when you should

**Feature Branch**: `044-lock-in-threshold`

**Created**: 2026-10-21

**Status**: Draft (amended after research and plan review, 2026-10-21)

**Input**: User description: "another stat that id liek to know is what percentage of points should it be abouve projected where people lock in not just per manager but per players as well"

## Context (measured before writing, 2026-10-21)

- Both "Ball Knowers" NBA leagues (2024, 2025, 2026) run Sleeper's `game_mode: 1`, in which each
  starter is credited with **one game per week**: the game a manager locks in, or a default when
  they don't. This is established and verified (540/540 roster-weeks, memory
  `project-nba-one-game-per-week-is-correct`). Among multi-game starter-weeks the credited game was
  the week's first ~30% of the time and its last ~43%, and beat the week's average game by ~14–19%.
- **Sleeper does not say which games were locked.** A matchup row carries only
  `players_points` / `starters_points`; there is no lock flag or timestamp (checked against
  `/v1/league/{id}/matchups/5` for 2025). A lock can only be *inferred* by matching the credited
  points to one of the player's games that week.
- **Real pre-game projections exist only from 2026-10-20 onward**, via spec 036's frozen
  `projection_snapshot`. Sleeper's served projections for past games were all modified after the
  game (spec 036 research R1), so 2024/2025 have no honest projection at all. Locally the snapshot
  table is empty today.
- *Added after plan review F5*: **the default is the last scheduled game**, measured on bench
  players, who can't be locked (credited their last game in 860/864 and 848/865 uniquely matched
  weeks). A credited non-last game is therefore a manager's choice.

## Clarifications

### Session 2026-10-21

- Q: What does "projected" mean as the baseline? → A: The pre-game projection snapshot where one
  exists; otherwise the player's season-to-date per-game average *before* that game, as a
  **labelled stand-in**. The two are never pooled into one number unlabelled.
- Q: Which credited games count as a lock-in? → A: **Provable locks only**: the credited game was
  not the player's last game of that week, so a later game existed and the manager chose this one.
  A credited last game is reported separately as "rode it out" and is never counted as a lock.
- Q: Is "should" descriptive or advice? → A: **Both**: what people actually did (per manager, per
  player), plus a payoff table from league history.

### Session 2026-10-21 (2), after the plan review

- Q: The payoff table: what does "waiting" mean? → A (Allan, in his own words): *"i want this to be
  like a you should lock this based on previous locks and averages. I also want this as a tweet
  section"*. Two follow-ups were then asked and answered:
  - Q: What rule turns history into "you should lock this"? → A: a **break-even threshold** learned
    from this league's history, per number of games left.
  - Q: Where does the tweet section live? → A: a new card in spec 043's **admin digest**.
- As a consequence, the out-of-scope line "recommending a lock for a live game this week" is
  **reversed** (see Assumptions), and US4 and US5 are added.
- *Design note, not a question*: a naive break-even ("lock if riding it out beat games like this
  less than half the time") comes out at "lock anything above average" for every number of games
  left. That ignores the chance to lock a later game, so it's bad advice. The threshold is
  therefore computed by optimal stopping (research R8). It's still a break-even per games-left
  count, as Allan chose; it just counts what waiting is actually worth.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — What margin each manager locks at (Priority: P1)

A league member opens the league analysis page of an NBA lock-in league and sees, for every
manager, the typical percentage above projection at which they lock a game in: the median, the
middle half of their locks, and how many locks it's based on. They can see at a glance who locks
early on a modest bump and who holds out for a monster game.

**Why this priority**: It's the stat Allan asked for first, and it's the reading every other part
of this feature is built from.

**Independent Test**: Open the analysis page of the 2025 NBA league. Every manager with at least
the minimum number of provable locks shows a median margin and a lock count, and for one manager
both numbers equal a hand computation over that manager's provable locks.

**Acceptance Scenarios**:

1. **Given** an NBA lock-in league with scored weeks, **When** a member opens league analysis,
   **Then** a "Lock-ins" section lists each manager with their median % above baseline at lock,
   the interquartile range, and their count of provable locks.
2. **Given** a manager whose locks are measured partly against real projections and partly
   against the stand-in, **When** the section renders, **Then** the two baselines are shown as
   separate figures (each with its own count) and are never averaged together into one number.
3. **Given** a manager with fewer locks than the minimum sample, **When** the section renders,
   **Then** their row shows the count and says the sample is too small, instead of a median.
4. **Given** a manager who also "rode it out" on some weeks, **When** the section renders,
   **Then** the count of those weeks is shown beside the lock count and excluded from the margin.

---

### User Story 2 — What margin each player gets locked at (Priority: P2)

On a player's league page (and as a sortable player list in the same analysis section), a member
sees how far above projection that player's games were when they got locked in. It covers every
manager who rostered and started the player, so a player who got traded still has one history.

**Why this priority**: Allan asked for it explicitly ("not just per manager but per players as
well"). It reuses US1's inference, so it's a cheap second view of the same data.

**Independent Test**: For one player with several provable locks in 2025, the player figure
equals a hand computation over those locks, and each lock lists the manager who made it.

**Acceptance Scenarios**:

1. **Given** a player with provable locks this season, **When** a member opens the player's
   league page, **Then** it shows the median % above baseline at lock, the lock count, and the
   individual locks (week, manager, points, baseline, margin, games left that week).
2. **Given** a player who was never a starter in a multi-game week, **When** the page renders,
   **Then** the section says there are no provable locks for this player, rather than showing 0%.
3. **Given** the per-player list in league analysis, **When** a member sorts it by median margin
   or by lock count, **Then** players below the minimum sample sort after those above it.

---

### User Story 3 — When locking paid off (Priority: P3)

A member sees a payoff table built from the league's own history. Rows are margin bands (below
average, 0–10%, 10–25%, 25–50%, 50%+), and columns are how many games the player still had left
that week. Each cell answers: "when someone locked at this margin with this many games left, did
riding it out (Sleeper's default, the last game) score more?", with the sample count in the cell.
*Amended after plan review F4 (2026-10-21)*: "a later game beat it" was the original headline. It's
now shown smaller beside the ride-it-out rate, labelled "in hindsight", because nobody knows which
later game to lock, and the hindsight maximum roughly triples the apparent cost of locking.

**Why this priority**: It's the evidence behind US4's calls.

**Independent Test**: For one cell, the published rates and count equal a hand count over the
qualifying locks.

**Acceptance Scenarios**:

1. **Given** provable locks with known later games, **When** the payoff table renders, **Then**
   every cell shows the ride-it-out rate, the in-hindsight rate and the sample count, and any cell
   below the minimum sample shows the count but no rate.
2. **Given** the payoff table, **When** a member reads it, **Then** a plain sentence says it
   describes this league's past weeks and is not a prediction for any future game.
3. ~~**Given** a lock whose later game the player missed (DNP / injury), **When** the payoff is
   counted, **Then** the missed game counts as not beating the lock.~~ *Amended after research
   (2026-10-21, research R4)*: stored games hold only games played (0 DNP rows in any NBA season),
   and 2024 has no schedule rows to see a missed game. So **Given** a lock, **When** the payoff is
   counted, **Then** "a later game" means a later game the player played, and the table's caption
   says so. A lock followed only by missed games looks like the week's last game and is excluded as
   "rode it out", which can undercount locks but never invents one.

---

### User Story 4 — "You should lock this" (Priority: P2)

During a live week, a member opens the league's lock-in section and sees, for every starter who
has played this week and still has games left, a call: **Lock** or **Wait**. Each call shows the
game's points, the player's average, the margin, games left, and the threshold for that many games
left (for example, "lock at +23% or more with 2 games left, learned from 2,190 past starter-weeks").
The thresholds come from this league's own completed seasons. They're the margin above which taking
the game beats what waiting is expected to return, given that the player can still lock a later
game and that the default is the last scheduled game, which he sometimes misses.

**Why this priority**: This is the "should" Allan asked for, stated as advice. It depends on US1's
inference and on the same baseline.

**Independent Test**: In a fixture week, a player who scored +30% above his average with 2 games
left, against a 2-left threshold of +23%, gets "Lock". At +20%, he gets "Wait". The thresholds
published for one league equal a hand computation over the same completed seasons.

**Acceptance Scenarios**:

1. **Given** a live week with a starter who has played ≥1 game and has ≥1 scheduled game left,
   **When** a member opens the section, **Then** that starter shows Lock or Wait for his most recent game *(Amended after /speckit-analyze F1, 2026-10-22: Allan confirmed only the latest game can be locked.)*,
   with the margin, the games left, the threshold and its sample count.
2. **Given** a player with fewer than the minimum prior games this season, **When** the call is
   made, **Then** it uses his previous-season average, labelled as such. If there's none, it says
   "too early to call" rather than guessing.
3. **Given** a league with no completed lock-in season in its history to learn from, **When** the
   section renders, **Then** no calls are made, and it says why.
4. **Given** any call, **Then** the section states that it's a rule learned from past weeks, that it
   doesn't know about injuries or rest days, and that it isn't a guarantee.

---

### User Story 5 — Lock-in calls as a tweet card (Priority: P3)

On the admin digest (spec 043), a "Lock-in calls" card lists last night's games in each current
NBA lock-in league that cleared the lock threshold with games still left that week. Each entry gives
the player, the points, the margin vs his average, the games left and the call. It comes with
ready-to-paste tweet text of 280 characters or fewer, copied the same way as the other digest cards.

**Why this priority**: Allan asked for it, and it reuses US4's calls for one night.

**Independent Test**: With last night's games in a lock-in league fixture, the card lists exactly
the starters whose margin met the threshold for their games left, sorted by margin, and the copy
text is within the length limit.

**Acceptance Scenarios**:

1. **Given** a READY night (spec 043's rule) and an NBA lock-in league in the current season,
   **When** the admin opens the digest, **Then** a Lock-in calls card shows the qualifying entries
   and a copy button.
2. **Given** a night with no qualifying games, **Then** the card says so instead of disappearing.
3. **Given** a league with no completed season to learn thresholds from, **Then** the card shows
   that reason and no calls.

### Edge Cases

- **Football leagues, and NBA leagues not in lock-in mode**: there are no locks. The section is not
  shown, and the API says why (not lock-in mode) instead of returning empty numbers.
- **A credited score that matches two of the player's games that week** (a tie in points): which
  game was locked is unknown. It's counted as ambiguous and excluded from margins, never guessed.
- **A credited 0 when the player played other games that week** (44 in 2024, 14 in 2025: every
  "unmatched" starter credit). *Added after plan review F5*: the default landed on a last scheduled
  game he missed. It's counted as "rode it out onto a missed game", not as an inference failure.
- **A credited score that matches none of the player's games and isn't 0**: a real inference
  failure (measured at 0 in both seasons). It's excluded and counted, and the count is surfaced,
  because a large one means the inference is broken.
- **Single-game weeks**: there's no choice to make, so it's not a lock. These are excluded and not
  counted as "rode it out".
- **Too little history for a baseline** (fewer than the minimum prior games): no margin is
  computed. The lock is counted as "no baseline" and excluded (plan review F10).
- **Negative margins**: locking below average is legitimate (for example, a bad matchup ahead) and
  stays in the data. It's never clipped to zero.
- **Starter changed mid-week**: the stored starter list is end-of-week. *Measured in plan review*:
  end-of-week starters reproduce Sleeper's own weekly total in 288/288 and 252/252 roster-weeks,
  so attributing by them is attributing the way Sleeper scores.
- **The current, unfinished week**: excluded from US1–US3 until it's final, because its "last game"
  isn't known yet (plan review F7). It's the week US4's calls are made for.
- ~~**NBA Cup final and other excluded games** (`weights.yml` excluded-game-ids): treated the same
  way every other stat in the app treats them.~~ *Amended after plan review F2*: Sleeper's fantasy
  week **does** credit the Cup final (1 unique credit per season, measured), so this feature
  deliberately keeps it. That differs from the real-basketball pages, which drop it. **The All-Star
  game is dropped** (F1): Sleeper never credits it (0/30), and keeping it would create fake "later
  games".
- **Playoff weeks**: included, and labelled as playoff weeks where the lock list is shown.
- **Thin samples are normal**: only ~30–60 players per season reach the minimum lock count, so most
  player rows say "too few". That's expected; the minimum is not lowered to fill the table (F13).
- **A roster taken over mid-season**: locks are credited to the roster's final manager, because the
  stored data holds one manager per roster-season. This is a named assumption (F14).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST identify, for every starter-week in a final week of an NBA lock-in
  league, which of the player's games that week was credited. It does this by matching the credited
  points to each game's points under that league's own scoring.
- **FR-002**: A credited game MUST count as a **provable lock** only when the player had at least
  one later played game that same week. A credited last game MUST be counted as "rode it out".
  Single-game weeks, ties, credited-0-onto-a-missed-game and other unmatched credits MUST each be
  counted separately and excluded from every margin.
- **FR-003**: Each provable lock's **margin** MUST be (credited points − baseline) ÷ baseline,
  shown as a percentage. The baseline is the pre-game projection snapshot for that game when one
  exists, with both sides scored on spec 036's restricted category map. Otherwise it's the player's
  average league points over his same-season games before that game (the stand-in), requiring at
  least a minimum number of prior games.
- **FR-004**: Every figure MUST carry its baseline kind (projection or stand-in) and its sample
  count. Figures from different baseline kinds MUST NOT be combined into one number.
- **FR-005**: The league analysis page MUST show, per manager: the median margin, the
  interquartile range, the provable-lock count, and the rode-it-out count, per baseline kind.
- **FR-006**: The system MUST show the same figures per player, attributed across every manager
  who locked that player. The player's league page MUST list the individual locks.
- **FR-007**: The system MUST show a payoff table of margin band × games-left-that-week. Each cell
  shows the share of provable locks where riding it out (the week's last played game) scored more,
  and, smaller and labelled "in hindsight", the share where any later played game scored more,
  each with its count. "Later game" means a later game the player played (amended after research
  R4: missed games aren't visible in stored data).
- **FR-008**: Any figure based on fewer than a minimum sample MUST show its count and withhold the
  statistic. The minimums are hand-set constants, labelled arbitrary in the config.
- **FR-009**: The figures MUST be available for every NBA season in the league's history chain
  (2024, 2025, 2026), each computed under that season's own league scoring.
- **FR-010**: The section MUST state, in the UI, that locks are inferred from scores (Sleeper
  doesn't publish them), that the default is the last scheduled game, and how many credited games
  were excluded, by reason.
- **FR-011**: Football leagues, non-Sleeper leagues and non-lock-in NBA leagues MUST NOT show the
  section. The API MUST report the reason instead of empty results.
- **FR-012**: Nothing in this feature MAY feed back into the draft engine, projections or any
  weight. It's a read-only report.
- **FR-013**: The system MUST compute per-league lock thresholds, one per games-left count (1, 2,
  3, 4+), from the league's completed lock-in seasons, by optimal stopping over the per-game margin
  distribution. The last game's value includes the measured rate at which the default landed on a
  missed game. Each threshold is published with its sample size and the seasons it was learned from.
- **FR-014**: During a live week, the system MUST give every starter with ≥1 game played and ≥1
  scheduled game left a Lock or Wait call, judging his **most recent** game against the threshold
  for the games left after it. An earlier game he passed on can no longer be locked. *(Amended after /speckit-analyze F1, 2026-10-22: Allan confirmed only the latest game can be locked.)* A player without enough prior games this season uses his
  previous-season average, labelled; with neither, the call is "too early".
- **FR-015**: The admin digest MUST show a Lock-in calls card for each current NBA lock-in league
  for the digest's night, with copyable tweet text of 280 characters or fewer.

### Key Entities

- **Credited game**: one starter-week's credited points, matched to one of the player's games
  that week. Its status is provable lock, rode-it-out, rode-it-out onto a missed game,
  single-game, tie or unmatched.
- **Lock**: a provable credited game, with its manager, player, week, credited points, baseline,
  baseline kind, margin, games left that week, the last game's score and the best later-game score.
- **Lock summary**: the aggregate for one manager or one player, per season and baseline kind:
  median, IQR, count and rode-it-out count.
- **Payoff cell**: margin band × games left. It holds the count, the ride-it-out rate and the
  in-hindsight rate.
- **Lock threshold**: for one league and one games-left count, the margin above which locking is
  the better bet. It holds the sample size and the seasons used.
- **Lock call**: one live starter's most recent game this week, its margin, games left, threshold and
  call (Lock / Wait / Too early).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For one manager and one player in the 2025 NBA league, the published median margin
  and lock count equal a hand computation over the same rows exactly.
- **SC-002**: ~~The lock inference matches the credited points to exactly one game for at least
  95% of multi-game starter-weeks.~~ *Amended after measurement (2026-10-21, research R2)*: the 95%
  bar was a guess written before measuring, and it mixed genuine ties with inference failures.
  Measured unique-match was 96.0% (2025) and **94.7% (2024, which misses the original bar)**. Ties
  were ~3.5%, and the rest were credited-0 defaults onto a missed game (F5), with 0 real failures.
  The criterion is now: **real unmatched credits (nonzero, matching no game) are ≤ 1% of
  multi-game starter-weeks in each season, and tie and credited-0 counts are published
  separately**. Above that, the feature stops and reports the number. **Accepted by Allan, 2026-10-22.**
- **SC-003**: For one payoff cell, the published rates and count equal a hand count.
- **SC-004**: No screen shows a margin without its baseline kind and sample count (checked on the
  analysis page and one player page, in the live UI).
- **SC-005**: The league analysis page with this section still loads in under 2 seconds for the
  largest NBA season.
- **SC-006**: A football league's analysis page and the existing digest cards are unchanged (no
  section, no new errors).
- **SC-007**: For one league, the published thresholds equal a hand computation of the
  optimal-stopping recursion over the same completed seasons, at 3 dp.
- **SC-008**: For one fixture night, the digest card's entries equal the starters whose margin met
  their games-left threshold, and the copy text is ≤ 280 characters.

## Assumptions

- "Lock in" means Sleeper NBA `game_mode: 1` choosing which game counts. This was inferred from
  the league settings and the one-game-per-week data, not from Sleeper documentation.
- ~~The default when nobody locks is not known.~~ *Measured in plan review F5 (2026-10-21)*: the
  default is the last scheduled game (bench players, 860/864 and 848/865). A credited non-last game
  is a manager's choice, so FR-002's premise is measured, not assumed.
- The stand-in baseline (season-to-date average) is a stand-in, not a projection, and is labelled
  that way everywhere. For 2026 it will dominate until enough snapshot-backed locks accumulate.
- The per-game rows needed (`player_game`) and the credited points (`roster_week_points`) are
  already ingested for all three NBA seasons. Local 2024/2025 last weeks may be partial (memory
  `project-sleeper-nba-2025-matchup-drift`).
- The margin bands, the minimum samples and the minimum prior games are hand-set and labelled
  arbitrary. They're not tuned to make the tables look decisive.
- Sleeper lets a manager lock only a player's latest game, before his next game starts (Allan,
  2026-10-22). The threshold model assumes exactly this.
- The lock thresholds don't know about injuries, rest or minutes news. They're a rule learned from
  past weeks, and every call says so. Live calls use the stand-in baseline, the only one whose
  threshold history exists. A threshold backtest is a sanity check reported in research, never a
  claim shown in the UI.
- Out of scope: ~~recommending a lock for a live game this week~~ (*reversed 2026-10-21 by Allan's
  answer; this is now US4*), predicting which game a manager *will* lock, notifications, football,
  and other platforms (ESPN/Yahoo have no lock-in mode here).
