# Feature Specification: Matchups shows the live season, then lock-ins on Matchups

**Feature Branch**: `045-matchups-live-season`

**Created**: 2026-10-23

**Status**: Draft (clarified, then amended after plan review, 2026-10-23)

**Input**: User description: "next on handoff". That is HANDOFF.md's "NEXT UP (written 2026-10-23)": first fix
Matchups & awards showing 2025 while the 2026 NBA season is live, then add spec 044's lock-in calls to the
Matchups page. Allan set the order: **fix first, then add.**

## Context (measured before writing, 2026-10-23)

- **Sleeper, live, 2026-10-23** (the NBA league `1339351318115946496`): `status: in_season`, `settings.leg: 1`,
  `settings.last_scored_leg` **absent**, `game_mode: 1`. `/state/nba` reads week 1, `season_has_scores: true`.
  `/league/{id}/matchups/1` already returns all 12 rosters with live points (101.5–217.0) and their
  `matchup_id` pairings.
- **So the week being played is never stored while it is played, at least on opening week.** Weekly points
  ingest walks weeks `1..last_scored_leg` (`LeagueHistoryIngestService.java:263-286`), and with the value
  absent it stores nothing. Spec 009's finality note says NBA's `last_scored_leg` tracks the week being
  played (R14, which was reasoned and never measured mid-week), and that NFL's is the last *completed* week
  (measured 2026-10-02). Three days into NBA week 1 it reads like NFL's. Whether it starts tracking the
  current leg from week 2 is **not known until week 2** (~10-27).
- **The cause of "Matchups shows 2025"**, read and not guessed: `WeeklyReportService.java:188` calls the
  one-argument `LeagueSeasonResolver.resolve(String)`, which defaults to `Rule.PLAYED_WEEKS` ("at least one
  stored scored week"). 2026 has none, so it walks back to 2025. The resolver's own javadoc says the rule is
  "a required argument everywhere, never defaulted" (memory: *optional params that encode rules*, the
  fourth time this class has shipped).
- **HANDOFF's list of affected pages is too long, corrected here.** The one-argument overload is called by:
  `WeeklyReportService:188`; `ExpectedWinsService:361, :373`; `PlayoffOddsService:311`;
  `RosterManagementService:140`; `SeasonSuperlativesService:254`; `RecapService:113, :205`;
  `AdminRecapController:45, :54, :66, :79`; `LeagueRefreshService:162`. `PlayerStatsService` already names
  `Rule.STORED_GAMES`. `DraftGrades`, `ManagerCareer`, `NextMatchup`, `PlayerSpotlight`, `Rivalry` and
  `ScheduleGrid` **only mention the resolver in comments** and explain why they deliberately don't use it.
  `SuperlativesController` reaches it through `SeasonSuperlativesService`.
- **The page already knows "in progress"** for a week that is stored but not final. `ScoredWeeks` separates
  stored from final, and `WeeklyReport.tsx:154-160` links "Week N in progress →". What it can't show is a
  week that is in progress and **not stored at all**, which is the opening-week case above.
- **Spec 044's lock-in data is already served.** `GET /api/leagues/{id}/lock-ins` returns `live.calls[]`
  keyed by `rosterId`, with latest game, baseline + kind, margin, games left, threshold and LOCK / WAIT /
  TOO_EARLY. The live form is cached 60 s. Provable past locks (`Lock`: week, rosterId, manager, player,
  game, points, stand-in/projection margin, later games) exist in the computed report, but the endpoint
  only hands them out for one `?player=`.

## Clarifications

### Session 2026-10-23

- Q: What does Matchups show for a live week that has no stored scores? → A: **(a) Live scores.** The
  current season's current week, with in-progress scores read live from Sleeper and labelled in progress.
  Mid-season, the default stays the latest final week, with the existing link reaching the live one.
- Q: What happens to the other pages that use the defaulted rule? → A: **Name the rule, no change.** Each
  names `PLAYED_WEEKS` explicitly and behaves exactly as today. Only Matchups changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Matchups shows this season's week while it's being played (Priority: P1)

A member opens Matchups for the NBA league on 2026-10-23, during week 1. Today they see 2025's week 21 with a
"showing 2025" note. They should see 2026, week 1, with every matchup's live score so far and a clear "in
progress" label. The page shouldn't present a final result before the week is over.

**Why this priority**: it's a live-season bug Allan sees in prod. Story 3 can't exist without it, because
there's no live week on the page to attach calls to.

**Independent Test**: on a league whose current season has games played but no stored scored week (the real
NBA league on 10-23 to 10-26, or a test fixture of that shape), open Matchups and check the season, the week
and the in-progress scores against Sleeper's own matchups for that week.

**Acceptance Scenarios**:

1. **Given** the current season is in season, its current week has started, and no week of it is stored,
   **When** a member opens Matchups with no week chosen, **Then** the page shows the current season's
   current week, with each matchup's in-progress score read live from Sleeper (clarified 2026-10-23).
2. **Given** that state, **When** the page is shown, **Then** no "showing last season" note appears, because
   it isn't true.
3. **Given** a live week is on screen, **When** the scores are shown, **Then** each is labelled in progress,
   and no winner, record change or final-only ranking is shown for that week as if it were decided.
4. **Given** the league is in its offseason (new season created, not started, no games), **When** a member
   opens Matchups, **Then** behaviour is unchanged from today: last season, with the fallback note.
5. **Given** any past, finished season is chosen, **When** Matchups is opened for any of its weeks,
   **Then** the page is identical to today.

---

### User Story 2 — Every season-scoped page says which "played" rule it uses (Priority: P1)

Nothing user-visible changes for this story unless a page is listed as changing. Each page that picks "the
season to show" must name the rule it picks by, so a new caller can't inherit a default nobody chose. This is
the second half of the fix: the bug existed because the rule was a default.

**Why this priority**: it's the root cause. Fixing Matchups alone would leave the same default waiting for
the next page.

**Independent Test**: no way remains to ask for "the season to show" without naming a rule (checked at
compile time). For every page not deliberately changed, the response for both real leagues is identical
before and after.

**Acceptance Scenarios**:

1. **Given** the code after this change, **When** any page asks which season to show, **Then** it has
   named its rule. A call that names none doesn't compile.
2. **Given** the pages other than Matchups, **When** they run on 10-23 against the real NBA league,
   **Then** each keeps today's behaviour exactly. They still show 2025 until 2026 has a scored week, now by
   an explicit, named choice (clarified 2026-10-23).

---

### User Story 3 — Lock or wait, per matchup, this week (Priority: P2)

In an NBA lock-in league during a live week, each matchup card shows both sides' starters' calls from spec
044: Lock / Wait / Too early, the latest game's points, the margin over the baseline, the baseline's kind
(projection, stand-in, last season), games left, and the bar for that many games left. A manager can see
their own calls and their opponent's in one place.

**Why this priority**: it's the "then add" half, and it's only useful once Story 1 puts a live week on the
page.

**Independent Test**: on the live NBA league, open Matchups for the current week. Each matchup card's calls
match `/lock-ins`' live calls for those two rosters. The page costs one live roster read, not one per
matchup.

**Acceptance Scenarios**:

1. **Given** an NBA lock-in league with a live week on screen, **When** the page loads, **Then** each
   matchup shows both rosters' calls, paired by roster, with the baseline kind named on every margin.
2. **Given** a starter whose latest game is still being played, or who can no longer be locked, **When**
   calls are shown, **Then** that starter has no call (044's existing omission rules).
3. **Given** the calls can't be produced (no history to learn thresholds, or the live roster read failed),
   **When** the page loads, **Then** the scores still show and the calls area says why, in words, without
   breaking the page.
4. **Given** a football league, or an NBA league that isn't lock-in, **When** Matchups is opened, **Then**
   no lock-in content appears and the page is unchanged.
5. **Given** a past or final week is on screen, **When** the page loads, **Then** no live calls appear.
6. Every place calls appear says that **locks are inferred** and that the **bars are learned from past
   weeks**.

---

### User Story 4 — Who locked what, in a past week (Priority: P3)

For a finished week in an NBA lock-in league, each matchup card lists the provable locks each side made, e.g.
"Mitchell +54% stand-in (2 later games)" (reworded after plan review F15): the player, the margin with its baseline kind, and how many games were left.

**Why this priority**: it's a recap view. It's nice to have, and it depends on nothing live.

**Independent Test**: for a stored 2025 week, each listed lock matches spec 044's provable locks for those
rosters in that week. A "rode it out" credit is never listed as a lock.

**Acceptance Scenarios**:

1. **Given** a final week of a lock-in league, **When** Matchups shows it, **Then** each side's provable
   locks that week are listed with player, margin, baseline kind and games left.
2. **Given** a side made no provable locks that week, **When** shown, **Then** it says so ("no locks")
   rather than showing nothing.
3. **Given** a week where lock inference isn't possible (no stored games for it), **When** shown, **Then**
   the locks area says it can't tell, not "no locks".
4. Stand-in and projection margins are never pooled or shown unlabelled (044 P5).

---

### Edge Cases

- **The week rolls over while the page is open.** Week 1 ends and week 2 starts. The live week shown is the
  one Sleeper reports now. Week 1 then becomes a stored, final week through normal ingest. *Amended after
  plan review F13:* between Sleeper's rollover and the next ingest, the week that just ended is neither
  live nor stored for a short window. During it, a live week 2 shows records "before this week" from
  stored weeks only (0-0 on opening rollover). That's accepted and stated, not hidden.
- **The current week is partly stored** (if NBA's `last_scored_leg` does track the current leg from week 2
  on, as R14 assumed). The page shows the stored-partial week through today's "in progress" path, labelled
  the same way as the live-unstored case. The reader can't tell the two sources apart, and there are never
  two different "in progress" scores for one week.
- **Sleeper's live read fails or times out.** The page says live scores are unavailable and links to the
  latest stored week. It doesn't silently show last season instead.
- **A week with byes or unpaired rosters** (a null `matchup_id`): show them the way stored weeks show them
  today, never as a 0–0 matchup.
- **A member asks for a week past the current one.** Same "not scored yet" answer as today.
- **Playoff weeks** (from `playoff_week_start`): a live playoff week shows the same way. Rosters already
  eliminated keep today's treatment.
- **An ESPN league (spec 035).** It has no Sleeper live matchups read. It keeps today's behaviour, and
  this spec doesn't add live scores for it.
- **The resolver's offseason case**: a new season created but not started, with no games and no live week,
  still falls back to last season with the note (Story 1, scenario 4).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When a league's current season is in season and its current week has started, Matchups (no
  week chosen) MUST answer about the current season. It MUST NOT fall back to the previous season.
- **FR-002**: In FR-001's state with no final week yet, the default week MUST be the live current week. Its
  per-matchup scores so far and pairings come from Sleeper at read time. When a final week exists, the
  default stays the latest final week, and the live week is reachable by choosing it.
- **FR-003**: Any score for a week that isn't final MUST be labelled in progress. Win/loss, record changes
  and final-only rankings MUST NOT treat it as decided.
- **FR-004**: The "showing another season" note MUST appear only when the season shown really differs from
  the one asked for.
- **FR-005**: Past seasons' Matchups responses MUST be unchanged, field for field, for every stored week of
  both real leagues.
- **FR-006**: There MUST be no way to ask which season to show without naming the rule. The one-argument
  form is removed, not just deprecated.
- **FR-007**: Every existing caller MUST name its rule explicitly. Callers listed as unchanged MUST name the
  rule they get today (`PLAYED_WEEKS`), so their responses stay identical.
- **FR-008**: An automated test MUST pin opening-week behaviour: a season with games stored, a current week
  started, and no scored week stored. That's the shape that shipped this bug.
- **FR-009**: In an NBA lock-in league, each matchup in a live week MUST show both rosters' live calls from
  spec 044. Each call shows: latest game points, margin, baseline kind, games left, threshold, and the call.
- **FR-010**: Live calls for a page view MUST cost at most one live roster read from Sleeper, reusing
  044's 60-second live cache. There must not be one read per matchup.
- **FR-011**: Live calls MUST NOT appear for football leagues, non-lock-in NBA leagues, or weeks that aren't
  live.
- **FR-012**: For a final week of an NBA lock-in league, each matchup MUST list each side's provable locks.
  Each lock shows player, margin, baseline kind, and games left. Showing them MUST NOT change what spec 044's
  existing `/lock-ins` responses return to their current callers.
- **FR-013**: Every lock-in surface on Matchups MUST say locks are inferred and thresholds are learned from
  past weeks. A STAND_IN or LAST_SEASON baseline MUST be named as such (044 P5). Stand-in and projection
  margins MUST NOT be pooled.
- **FR-014**: If calls or locks can't be produced, MUST say why in words, without hiding the scores.

*Added after plan review (2026-10-23):*

- **FR-015** (F1): The AI recap MUST keep resolving and reading on `PLAYED_WEEKS` end to end, and MUST
  never be generated from a live, partial week.
- **FR-016** (F2): The live week MUST be reachable from the page's week controls mid-season. Navigation
  bounds include it.
- **FR-017** (F11): A week counts as live only once it has started: at least one roster has points, or a
  stored game that week has started. Before that, the page answers "week L hasn't started" for that week,
  and the default doesn't move to it.
- **FR-018** (F6): A league Sleeper no longer serves (404 or null body) has **no** live week. It is
  treated like the offseason, never as a permanent "live scores unavailable".
- **FR-019** (F4): A live read MUST give up within a bounded time. A slow Sleeper read produces the
  "unavailable" answer for the current season, never a hung page and never last season.

### Key Entities

- **Live week**: the current week of an in-season league, as Sleeper reports it now. It has per-roster
  points so far and pairings. It's never final, and may not be stored.
- **Season choice rule**: what "this season has been played" means for a page: a stored scored week
  (`PLAYED_WEEKS`), stored games (`STORED_GAMES`), or Matchups' own rule that a live current week counts. It is
  always named by the caller.
- **Live call** (spec 044): one starter's Lock / Wait / Too early on his latest game this week, keyed by
  roster.
- **Provable lock** (spec 044): a credited game that wasn't the player's last of the week, with its margin
  over a named baseline.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the real NBA league during a live week with no stored scored week, Matchups shows the
  current season (2026), not 2025, with no fallback note. Checked live, not only in tests.
- **SC-002**: Every in-progress score shown matches Sleeper's own matchups for that week at the time of the
  read, within the live cache window.
- **SC-003**: 100% of past-season Matchups responses, every stored week of both real leagues, are
  identical before and after the change.
- **SC-004**: Every page other than Matchups returns identical responses before and after, for both real
  leagues, (clarified: no other page moves).
- **SC-005**: Zero callers ask for a season without naming a rule (checked at compile time).
- **SC-006**: Matchups with live calls loads in under 2 seconds on a warm cache, and adds at most one
  Sleeper roster read per page view.
- **SC-007**: For every matchup in the live week, the calls shown equal `/lock-ins`' live calls for those
  two rosters.
- **SC-008**: For at least one final 2025 week, every lock listed equals spec 044's provable locks for that
  week, and no "rode it out" credit appears as a lock.

## Assumptions

- "Live" scores come from Sleeper's matchups for the current week, as spec 044's live calls read rosters
  live. Nothing new is stored by this spec. Normal ingest still stores the week once it's scored.
- The live-week view applies to any Sleeper league in season, both sports. A fix for "the current week
  isn't stored yet" shouldn't fork the page per sport. Lock-ins (Stories 3–4) are NBA lock-in leagues only.
- When a live week exists **and** a final week exists (mid-season), the default stays as today: the latest
  final week, with the existing "Week N in progress →" link, which now also reaches an unstored live week.
  Only when **no** week is final (opening week) does the default become the live week.
- No schema migration is expected. If planning finds one is needed, it takes the next free number after
  checking open branches (memory: 033/035/036 collided on V32–V37).
- Football's Matchups lock-in content is out of scope (football has no lock-ins).
- **Out of scope**: live updates while the page is open (a reload gets fresh scores); ESPN live scores;
  checking projection-baseline margins on prod after week 1 (still owed from 044, unchanged here).

## Owed regardless of this spec (from HANDOFF / 044)

- Check whether NBA `last_scored_leg` tracks the current leg once week 2 starts (~10-27). That settles
  whether the live-unstored case is opening week only or every week, and the edge case above covers both.
