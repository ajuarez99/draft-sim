# Feature Specification: Commissioner score override

**Feature Branch**: `057-commissioner-score-override`
**Created**: 2026-11-05
**Status**: Draft
**Input**: Commissioner-only entry of official weekly team scores, one score resolver (commissioner > official snapshot > Sleeper feed) used by every game-result consumer, and visible provenance per week.

## Why

The NBA league "Ball Knowers" 2026 stores Sleeper's public `/matchups` points, and those are not the official
scores (`claude/nba-2026-score-drift.md`). Spec 055 (`claude/official-week-scores.md`) recovers official scores
from season-total snapshots, but only from week 3 on, and only the weekly pot uses them. Weeks 1–2 can never be
recovered from the public API.

Concrete bug: the Awards page's **Closest wins** credits jstrobe (roster 5) with a week-1 close win over popsharky
(roster 1, Allan), but the Sleeper app shows popsharky won that game. Prod stored week 1 as jstrobe 233.0, popsharky
227.0 (prod `/weekly-report/1`, read 2026-11-05). *(Amended after review: the brief's "219.5 to 239.0" was stale; a
fresh read of `/matchups/1` on 2026-11-05 gives popsharky 236.5, jstrobe 231.5, so the feed has drifted again and a
fresh local ingest does not reproduce the bug. Verification mirrors prod's stored weeks 1–2 into a scratch DB.)*

The only source that knows the real weeks 1–2 scores is the Sleeper app, read by a person. This feature lets the
league commissioner type those in, and makes every page that decides a game read them.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Commissioner enters a week's official scores (Priority: P1)

The commissioner opens a week, sees each team's currently used score and where it came from, types the official
score from the Sleeper app for every team, and saves. From then on, every page that shows who won that week's
games, by how much, or what a team scored uses the entered numbers.

**Why this priority**: Without it, weeks 1–2 of NBA 2026 are wrong everywhere, and nothing else in this spec has
data to act on.

**Independent Test**: As the commissioner, enter week-1 scores with popsharky ahead of jstrobe; the Awards page no
longer lists a week-1 close win for jstrobe, and the week-1 report shows popsharky winning.

**Acceptance Scenarios**:

1. **Given** a final week with feed scores, **When** the commissioner saves a score for every team, **Then** the
   week's games are decided by the entered scores on the Awards, weekly report, weekly pot, record book and
   head-to-head views.
2. **Given** a saved override, **When** a later Sleeper re-ingest rewrites the feed scores for that week, **Then**
   the entered scores still win.
3. **Given** a saved override, **When** the commissioner clears it, **Then** the week goes back to the official
   snapshot score if there is a plausible one, otherwise the feed.
4. **Given** a signed-in league member who is not the commissioner, **When** they try to save or clear an
   override, **Then** it is refused and nothing changes.

### User Story 2 - Everyone can see where a week's scores came from (Priority: P1)

A league member reading the Awards page, a weekly report, or the weekly pot can tell which weeks use
commissioner-entered scores, which use Sleeper's official totals, and which use the public feed.

**Why this priority**: The project's rule is that a hand-entered number is never presented as if it were measured.
An override without a label would make the site look more certain than it is.

**Independent Test**: With week 1 overridden, the Awards page and the week-1 report each name week 1 as
commissioner-entered; a week left on the feed is not labelled commissioner.

**Acceptance Scenarios**:

1. **Given** weeks 1–2 overridden and later weeks on the feed, **When** a member opens Awards, **Then** a note says
   weeks 1–2 use commissioner-entered scores.
2. **Given** a weekly report for an overridden week, **When** it is opened, **Then** it says the scores were entered
   by the commissioner.
3. **Given** the weekly pot, **When** a week is overridden, **Then** its week row is labelled as commissioner scores
   (alongside the existing official/feed labels).

### Edge Cases

- **Partial entry**: a week is saved only as a whole (every team that played that week). A partial submission is
  refused, so no week mixes sources (the same rule 055 applies to official weeks).
- **A week not yet final**: refused. Overrides are for closed weeks; the live week keeps reading live data.
- **A week with no stored feed scores**: refused (no games to decide, and no list of teams to require).
- **Negative or absurd values**: negative refused; there is no upper cap (an NBA week can be 300+).
- **Tie created by entered scores**: allowed; consumers already handle ties.
- **Override and official snapshot both exist**: the override wins.
- **Per-player numbers** (bench points, lineup efficiency, top scorer of the week): stay on the feed. The app does
  not know official per-player points, and is not told; those views are not relabelled.
- **Season totals on the roster-management page** (`RosterManagementService`) sum per-player feed points, so for
  NBA 2026 they will disagree with power-ranking PPG and record-book totals. That is FR-012, not a bug. *(Added after review, L4.)*
- **Other leagues** (e.g. the NFL league, where the feed equals the official totals): no override, no change.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST store a commissioner-entered team score keyed by league, week and roster, with who
  entered it and when.
- **FR-002**: Only a user who can commission the league MUST be able to save or clear a week's override. Reading
  which weeks are overridden is open to anyone who can see the league.
- **FR-003**: Saving MUST be whole-week: the submitted rosters MUST equal the rosters that have a stored score for
  that week; otherwise the save is refused with a message naming what is missing or extra.
- **FR-004**: Saving MUST be refused for a week that is not final, and for any negative score.
- **FR-005**: Clearing a week's override MUST remove all of that week's override rows.
- **FR-006**: One resolver MUST decide each team's score for a week: commissioner override first, then the
  official snapshot score (055, with its existing plausibility gate), then the feed.
- **FR-007**: Every game-result consumer MUST use that resolver: the Awards kinds that decide or rank games or week
  totals (close wins, close losses, closest game, biggest blowout, highest and lowest week, luckiest/unluckiest via
  expected wins), the weekly report (scores, records, results), the weekly pot, the record book, and head-to-head
  meetings.
- **FR-008**: The resolver MUST report a source per week: COMMISSIONER, OFFICIAL or FEED.
- **FR-009**: The Awards payload, the weekly report payload and the weekly pot payload MUST carry the source of the
  weeks they use, and their pages MUST label commissioner-entered weeks in words.
- **FR-010**: Overrides MUST survive any re-ingest of Sleeper data.
- **FR-011**: The commissioner entry view MUST show, per team, the score currently in use and its source, so the
  commissioner sees what they are replacing.
- **FR-012**: Per-player score breakdowns MUST NOT be altered or relabelled by an override. Ratios that compare a
  team total with per-player numbers (lineup efficiency, a player's share of the team total) keep using the feed
  total on both sides, so they never mix sources. *(Amended after review, M1.)*
- **FR-013**: The entry view MUST show two exact checks against Sleeper's official numbers that are already stored:
  (a) per roster, the in-use scores of weeks 1..N summed vs Sleeper's season total through week N, for the latest
  stored season-total snapshot; (b) per roster, the W-L recomputed from in-use scores (head-to-head plus the median
  game when the league plays one) vs Sleeper's official W-L, when that record is through the same week. A mismatch
  is shown with both numbers; it warns, it does not block the save. *(Added after review, H1.)*

### Key Entities

- **Score override**: one team's official score for one week of one league-season, as typed by the commissioner;
  who entered it, when.
- **Resolved week score**: a team's score for a week plus its source (COMMISSIONER / OFFICIAL / FEED).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With week-1 scores entered so that popsharky beats jstrobe, the Awards page credits jstrobe with no
  week-1 close win, live-verified locally against the real league data.
- **SC-002**: An automated regression test fails if the feed score ever wins over an override.
- **SC-003**: Every page listed in FR-007 decides the overridden week the same way (one winner per game across all
  of them), checked by reading each payload for week 1 after entry.
- **SC-004**: A non-commissioner's save attempt is refused, checked live.
- **SC-005**: Seasons with no override and no season-total snapshot (NBA 2024, NBA 2025) return the same payloads as
  before, apart from the new source fields. *(Amended after review, M2: "byte-identical NFL" could not hold, since the
  payloads gain fields and NFL weeks with snapshots resolve to OFFICIAL.)*
- **SC-006**: With weeks 1–2 entered from the Sleeper app, both FR-013 checks read MATCH for all 12 rosters.

## Assumptions

- Allan is the commissioner of NBA "Ball Knowers" 2026 and will type the 24 week-1 and week-2 scores from the
  Sleeper app. The values are not checked against anything; they are labelled as entered by hand.
- Overrides apply to the league-season row they are entered on; a league's season is one row (`league.id`), so
  the key is league row + week + roster.
- The existing commissioner check (`LeagueMembership.canCommission`) is the authority for "commissioner".
- Switching non-pot consumers from the feed to 055's official weeks is part of this feature (the resolver's middle
  tier). Today it changes nothing measurable: NBA 2026 has no plausible official week yet (week 3 is the first
  candidate, decisive check owed after 2026-11-10) and the NFL league's official totals equal the feed.
  *(Review M3 flagged that this goes against 055's "one page first" rule before 055's own check. Kept, because the
  brief that started this work names the three-tier order explicitly, and the plausibility gate still guards every page.
  To revert, drop the OFFICIAL tier for non-pot reads: one line in the resolver.)*
