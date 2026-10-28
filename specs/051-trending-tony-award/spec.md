# Feature Specification: The Trending Tony Award

**Feature Branch**: `051-trending-tony-award`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "new award the trending tony award where a manager continues to pickup a player thats in the top10-15 in trending"

## What's there now (read from `main` at 7168753)

- **Awards** — 13 kinds in `SeasonSuperlativesService.Kind` (`backend/.../engine/SeasonSuperlativesService.java:84-87`).
  The two pickup awards are the closest neighbours: Waiver Wire Warrior (`:700-766`, team-headed, points from
  pickups) and Jabari Smith Jr. (`:810-873`, player-headed, most-added player). Both read one shared parse of
  completed waiver/free-agent adds, `WaiverPickupAttribution.completedAdds`, whose `CompletedAdd` already carries
  the add's `createdAt` timestamp (`WaiverPickupAttribution.java:53`) — so *when* a pickup happened is known.
- **Trending** — spec 014 stores Sleeper's platform-wide "trending adds" list per sport
  (`V26__sport_trending.sql`): top 25 players by adds across all of Sleeper over the last 24 hours, fetched at
  most hourly (`TrendingRefresh.java:36-39`, `RefreshProperties.STALE_AFTER` = 1h).
- **The gap that shapes this whole feature**: every successful fetch **replaces the list wholesale**
  (`SportTrendingRepository.replace`, delete-then-insert). No history is kept. And Sleeper offers no way to ask
  what was trending on a past date. So today there is no way to know whether a pickup made last Tuesday was a
  trending player *at the time*. The award can only judge pickups made after this feature starts keeping a
  history of the list — nothing before that can be backfilled, for any season.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See who chases the trending list (Priority: P1)

A league member opens the Awards page and sees a new card, **The Trending Tony**, naming the manager who most
often picked up a player while that player was near the top of Sleeper's trending-adds list. The card says how
many of their pickups were trending ones, and over what span the award has been able to judge pickups.

**Why this priority**: This is the award itself; everything else supports it.

**Independent Test**: With a stored history of trending lists and a season of completed pickups, the Awards
page shows the card with the right manager, the right count, and a coverage line.

**Acceptance Scenarios**:

1. **Given** manager A made 4 pickups of players inside the trending top 15 at the time and manager B made 2,
   **When** a member opens the Awards page, **Then** the Trending Tony card names A with "4 trending pickups".
2. **Given** two managers tie on the top count, **When** the page loads, **Then** both are named, as every other
   award handles ties.
3. **Given** the best count in the league is 1, **When** the page loads, **Then** the card names nobody and says
   nobody has chased the trending list twice yet (one pickup is not a habit — the user's "continues to").
4. **Given** a pickup of a player who was trending at #22 (outside the top 15), **When** the award is computed,
   **Then** that pickup does not count.

---

### User Story 2 - See the pickups behind the award, and everyone's count (Priority: P2)

A member clicks the card's "See all" and sees every manager ranked by trending pickups, and for each counted
pickup: the player, the week, and where that player sat on the trending list when they were picked up
(e.g. "#3 trending").

**Why this priority**: Awards in this app show their working (spec 010, spec 040); a bare name invites
"says who?". The trending rank at pickup is the evidence.

**Independent Test**: Open "See all" on the card; every manager appears with a count, and each counted
pickup lists player, week and trending rank.

**Acceptance Scenarios**:

1. **Given** the award has a winner, **When** a member opens "See all", **Then** every manager in the league
   is listed in competition-rank order by count, including managers at 0.
2. **Given** a counted pickup, **When** it is shown, **Then** it reads the player's name, the week, and the
   trending rank at pickup time.

---

### User Story 3 - Be honest about what couldn't be judged (Priority: P1)

Because trending history only exists from the day this feature starts recording it, the card says plainly
from when pickups could be judged and how many pickups in the season fell outside that window (before
recording began, or during a gap when no recent trending list was on file). A past season, which has no
history at all, shows the award as unavailable with that reason — never as "nobody qualified".

**Why this priority**: Equal to P1 under this project's rule that output must not look more certain than it
is. Without it, a manager who chased trending players all September would silently lose to one who did it
once in November.

**Independent Test**: With history starting mid-season, the card shows the start date and the number of
unjudged pickups; a prior season's Awards page shows the award as unavailable with a reason.

**Acceptance Scenarios**:

1. **Given** trending history began on a date after the season's first pickups, **When** the card renders,
   **Then** it states the date judging began and how many pickups could not be judged.
2. **Given** a season that ended before any history was recorded, **When** its Awards page loads, **Then** the
   Trending Tony card is marked unavailable with a reason saying trending history wasn't being kept then.
3. **Given** a pickup with no trending list on file within 24 hours either side of it, **When** the award is
   computed, **Then** the pickup is counted as "not judged", not as "not trending". *(Reworded at analyze to
   match A1/A3.)*

---

### Edge Cases

- **Waiver claims process later than the decision.** A waiver claim's timestamp is when it *processed*, not
  when the manager bid. The rule judges every pickup by the trending list on file at its recorded time; for a
  waiver claim that list may be up to a day newer than the decision. Accepted for v1 and stated in "How this
  works" (see Assumptions).
- **Failed waiver bids** never count — only completed pickups, the same set the other pickup awards use.
- **Team defenses** (football DEF) are excluded, matching Jabari Smith Jr.
- **A player dropped and re-added** by the same manager counts once per completed pickup, as each was a
  separate decision to chase.
- **Trades and commissioner moves** are not pickups and never count.
- **Trending fetches fail** for a stretch (Sleeper down): pickups in that stretch with no list within 24
  hours either side fall into "not judged". *(Reworded at analyze to match A1/A3.)*
- **Player unknown to the app** (not in the player table): still counts, named "Unknown player", as
  Jabari Smith Jr. does.
- **Both sports**: trending is per sport, so an NBA league is judged against the NBA list and an NFL league
  against the NFL list.
- **ESPN leagues**: the award is unavailable there with a reason. *(Amended after review, plan-review F5:
  the original said ESPN has "no Sleeper pickups history in the same shape" — wrong; ESPN adds mostly carry
  Sleeper ids. The exclusion is a product choice: Sleeper's platform-wide list says little about an ESPN
  league's waiver pool, and ESPN times pickups at bid, not processing.)*

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST keep every successful trending-list fetch as a dated snapshot, per sport, in
  addition to the current list the home page already reads. Keeping history MUST NOT change what the home
  page's trending section shows. *(Note after review, plan-review F13: the snapshot is written in the same
  transaction as the current list, so a failed snapshot write also keeps the previous list — and its true
  age — on the home page. Accepted: the two never disagree.)*
- **FR-002**: A completed waiver or free-agent pickup MUST count as a **trending pickup** when the picked-up
  player was ranked within the trending threshold (FR-003) in the snapshot that judges it: the earliest
  snapshot for that sport taken in the 24 hours after the pickup (its 24-hour window contains the pickup),
  or, when there is none, the latest one taken in the 24 hours before it. A snapshot with no entries judges
  nothing. *(Amended at plan, A1: was "at or before only". Amended after review, A3: order flipped to
  after-first, plus the empty-snapshot rule — plan-review F3, F13.)*
- **FR-003**: The trending threshold MUST be the top **15** of the list, held as a single labelled, hand-set
  value (not presented as derived from data). *(Default chosen from the user's "top 10–15"; see
  Clarifications.)*
- **FR-004**: A pickup with no qualifying snapshot (FR-002's 24-hour rule) MUST be classed as **not judged**,
  never as a non-trending pickup.
- **FR-005**: The award MUST name the manager(s) with the most trending pickups in the season through the
  current scored window, with ties sharing the award.
- **FR-006**: The award MUST name nobody, with a plain reason, when the top count is below 2.
- **FR-007**: The card MUST show the winner's count, and a coverage line stating the first date pickups could
  be judged and how many of the season's completed pickups were not judged. The line MUST name what the
  total excludes: pickups after the last scored week, and (football) team defenses. *(Amended after review,
  A4 — plan-review F9.)*
- **FR-008**: A season in which no pickup could be judged MUST show the award as unavailable with a reason,
  not as a season where nobody qualified — "trending history wasn't being recorded yet" when no history
  exists or a finished season ended before it began; otherwise "nothing could be judged yet: trending
  history began on <date>". *(Amended after review, A4 — plan-review F2: one condition, two honest
  reasons. Amended after code review, A5: which reason applies now depends on whether the season is
  finished — see A5.)*
- **FR-009**: "See all" MUST rank every manager in the league by trending-pickup count (competition ranking,
  zeros included), and list each counted pickup with player, week and trending rank at pickup time.
- **FR-010**: Only completed waiver and free-agent adds count — the same pickup set Waiver Wire Warrior and
  Jabari Smith Jr. use; trades, commissioner moves and failed bids never count; football team defenses are
  excluded.
- **FR-011**: The Awards page's "How this works" MUST describe the rule: platform-wide Sleeper trending
  (not this league's), the top-15 threshold, the 24-hour freshness window, the "not judged" class, and that
  history only exists from the date recording began.
- **FR-012**: The award MUST credit Sleeper as the source of trending data, as the home page already does.
- **FR-013**: The award MUST work for both NFL and NBA Sleeper leagues, judging each against its own sport's
  list, and be unavailable with a reason for non-Sleeper leagues.
- **FR-014**: The new award MUST NOT change any existing award's result.

### Key Entities

- **Trending snapshot**: one successful fetch of a sport's trending list — sport, time fetched, the window it
  covers (24 hours), and its ranked entries (player, rank, platform-wide add count).
- **Trending pickup**: a completed pickup, its manager, player, week and time, plus its judgement — trending
  (with the player's rank at the time), not trending, or not judged.
- **Trending Tony award**: the season's standing of managers by trending-pickup count, with its coverage
  (judging start date, count of not-judged pickups).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a fixture season with known pickups and known snapshots, the award's winner and every
  manager's count match a hand count exactly (0 discrepancies).
- **SC-002**: Every pickup in the season lands in exactly one of trending / not trending / not judged; the
  three counts sum to the season's completed-pickup total.
- **SC-003**: A prior season with no history shows "unavailable" with a reason in 100% of cases — never a
  winner and never "nobody qualified".
- **SC-004**: Adding the award changes no other award's output (the 13 existing awards' results are
  identical before and after on the same data).
- **SC-005**: The Awards page loads no slower than today by a margin a member would notice (within 0.5 s of
  the current load for the same league-season).
- **SC-006**: After one day of normal refreshes in production, at least 1 snapshot per active sport is on
  file, and verification reports the measured number per day. *(Amended at plan, A2: was "at least 20" —
  a guess that assumed an hourly schedule which doesn't exist.)*

## Assumptions

- **No backfill is possible.** Sleeper's trending endpoint only answers "now". The award therefore starts
  judging on the day history recording ships; for the current NBA 2026 and NFL 2026 seasons, earlier pickups
  are "not judged" and the card says so.
- **Platform-wide trending.** "Trending" means Sleeper's list across all Sleeper leagues, the same list the
  home page shows — not adds within this league.
- **Pickup time is the transaction's recorded time.** For waiver claims that is processing time, which can
  trail the decision by up to a day; v1 accepts this rather than guessing the bid time, and says so.
- **Counts, not rate.** "Continues to pick up" is read as a count of trending pickups; the card may show the
  share of the manager's judged pickups alongside, but the winner is decided by count.
- **Snapshot retention**: history is kept indefinitely. Upper bound: 25 entries × 24 fetches a day ≈ 220k
  entry rows per sport-year at the hourly ceiling; the real number depends on visits and is measured in
  verification. *(Amended after review, plan-review F4: the spec, plan and research stated three different
  figures; this bound is the one figure now.)*
- **Hall of Fame trophy case**: an *unavailable* Trending Tony is hidden there (every past season would
  otherwise carry an "unavailable" row); the Awards page always shows it with its reason. *(Decided after
  review, plan-review F8.)*
- **Refresh cadence unchanged**: snapshots arrive as often as the existing refresh already fetches trending
  (at most hourly); this feature adds no new fetch schedule. *(Amended at plan, A2: "at most hourly" is the
  ceiling; the guaranteed floor is once a day — research R2.)*
- **Award placement**: the card sits with the other pickup awards on the Awards page and follows the same
  season scoping, "See all" modal and early-season conventions those awards use.

## Amendments

- **A1 (2026-10-27, at plan)** — FR-002 now also accepts a snapshot taken up to 24 hours *after* the pickup
  when none precedes it. Why: research R2 found production fetches trending once a day for certain
  (11:00 UTC cron) and otherwise only when someone visits; a before-only rule would leave pickups made just
  before a late daily fetch unjudged. A snapshot after the pickup has a window that contains it.
- **A2 (2026-10-27, at plan)** — SC-006 lowered from "≥20 snapshots a day" to "≥1, measured number
  reported". The 20 was a guess built on an hourly schedule that doesn't exist (research R2). Named wrong
  here, not quietly adjusted.
- **A3 (2026-10-27, after plan review)** — FR-002's order flipped to *after-first*: the earliest snapshot
  within 24h after the pickup (whose window contains it), then the latest within 24h before. A1's
  before-first could pick a list 23h old whose window doesn't contain the pickup over one taken 5 minutes
  later that does (plan-review F3). An empty snapshot judges nothing (F13).
- **A4 (2026-10-27, after plan review)** — FR-007/FR-008: one unavailable condition (nothing judged) with
  two reasons; the coverage line names its exclusions (plan-review F2, F9).

- **A5 (2026-10-28, after code review + live verification)** — FR-008's reason selection. Live on NBA 2026
  (history began 2026-10-27, after every scored pickup) the A4 rule printed "wasn't being recorded yet for
  this season" on a season still being played, which reads as "never"; code review B1 found the mirror case
  (a fetch gap mid-history printing "no pickups since"). Now: "wasn't being recorded yet" iff no snapshot
  exists, or the season is complete and history began after its last pickup + 24h; otherwise "Nothing could
  be judged yet: trending history began on <date> (UTC), and no pickup since has a list within a day of it."
  Also from code review: `judgingSinceAt` is the earliest *judged pickup*, not the snapshot (B5, since an
  after-first snapshot can postdate the pickup it judged); the winner's card lists at most 3 pickups (B6).

## Clarifications

### Session 2026-10-27

- Q: What counts as a trending pickup ("top 10–15")? → A: Top 15 — any player ranked #1–#15 on the list
  when picked up (FR-003).
- Q: Winner by count or by share of pickups? → A: Count; share may be shown alongside (FR-005, Assumptions).
- Q: Pickups before history exists can't be judged — judge from ship day, or hide until a fully covered
  season? → A: Judge from ship day, with the start date and not-judged count on the card (FR-007, US3).
