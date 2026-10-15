# Feature Specification: Rivalry week recommends which week, without hindering the rest of the schedule

**Feature Branch**: `037-rivalry-week-pick`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "looking at rivalry week it doesnt reccomenend the week for it it should also not hinder the rest of the schedule"

## Background

What exists now (checked in code on `origin/main` 70159ea, 2026-10-15, not taken from a planning doc):

- Spec 034 shipped the Rivalries page (`web/src/pages/Rivalries.tsx`, `RivalryWeekPanel` at
  line 270). Its rivalry week section recommends a full set of pairings. Each manager is
  paired once, and each pair has a reason. The pairings come from an exact max-weight matching
  over a hand-set history score (`engine/RivalryMatching.java`, weights in `config/weights.yml`
  under `draftsim.rivalry`).
- That matching reads **only history**. It never looks at this season's schedule. It doesn't
  know whether a recommended pair already plays each other this season, or in which week.
- **Spec 034 put "which week" out of scope on purpose.** Its Assumptions said "The commissioner
  chooses the week. The tool doesn't recommend a week number, because a commissioner-set week
  is a league decision, not a data question", and its Out of Scope listed "Recommending which
  week should be rivalry week". **This spec reverses that call at Allan's request
  (2026-10-15).** Spec 034's text stays as written. This note is the visible amendment.
- The season grid in the same payload already holds the data a week pick needs:
  - the season's full scheduled pairings (`scheduledPairs`), including weeks not yet played
  - the current week
  - which weeks are playoff weeks (`isPlayoffWeek`, derived from `playoff_week_start`)
- Sleeper's league settings carry no rivalry week field. Checked live on "(Foot) Ball Knowers"
  2026: `settings` has `playoff_week_start: 15` and nothing rivalry-related. The app stays
  read-only toward Sleeper, so the commissioner still applies any change by hand.

**Why the schedule matters.** Putting a custom set of pairings into week W replaces W's
original matchups. A rivalry pair that already meets in other regular-season weeks gets one
extra meeting. If they already meet twice elsewhere, rivalry week would make it three, which
breaks Allan's rule (Clarifications). Today's recommendation can't see this, because it never
reads the schedule.

How often a history-best pair already meets twice on the real leagues has **not been
measured**. It's a plan-stage measurement, and this spec doesn't guess a number.

## Clarifications

### Session 2026-10-15

- Q: What does "not hinder the rest of the schedule" mean? → A: "that we should not play
  someone else more than twice really." **No pair of managers may meet more than twice in the
  regular season once the rivalry week is in place.** That's a hard rule, not a weighted
  preference. It's the only thing that counts as hindering the schedule. Displaced matchups and
  back-to-backs are shown for information, but they don't rank weeks.
- Consequence: the rule can rule out a history-best pair in a given week. So the week and the
  pairings are chosen **together**: for each eligible week, the best rivalry pairings that obey
  the cap, then the week whose pairings score highest. That's close to the option B offered,
  with the trade-off made by a hard rule instead of a tuned weight.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The page names a recommended week and why (Priority: P1)

A commissioner opens the Rivalries page. The rivalry week section now names one week, such as
"Week 9", next to the pairings, with a reason, such as "best rivalry set that keeps every pair
at two meetings or fewer". It also lists the other eligible weeks with how strong their
best rivalry set is. The commissioner can see the pick wasn't arbitrary, and can choose
another week knowing what it gives up.

**Why this priority**: this is the gap Allan named. Without a week, the commissioner has to
work out the schedule impact by hand from the season grid.

**Independent Test**: on a league with a published full-season schedule, the section names
exactly one week. That week is an unplayed regular-season week, and its pairings obey the
two-meeting cap when recounted by hand from the season grid.

**Acceptance Scenarios**:

1. **Given** a league whose regular season has unplayed weeks and a published schedule,
   **When** the page loads, **Then** the rivalry week section names one recommended week
   and its pairings, with a reason.
2. **Given** the same league, **When** the page loads, **Then** every other eligible week is
   listed with its best rivalry set's strength, strongest first.
3. **Given** two eligible weeks whose best sets score the same, **When** the recommendation is
   generated twice, **Then** the same week is named both times (deterministic tie-break).

---

### User Story 2 - Nobody plays the same manager more than twice (Priority: P1)

The recommended week and its pairings never push any pair past two regular-season meetings.
A rivalry pair that already meets twice elsewhere this season can't be paired that week. The
matching finds the best rivalry set without them. When the cap changes the pairings from the
history-best set of spec 034, the section says which pair was held back and why, e.g. "A and B
already meet in weeks 3 and 11".

**Why this priority**: it's the second half of Allan's request, and he defined it as this one
rule.

**Independent Test**: a synthetic schedule where the history-best pair already meets twice in
the regular season. In every week where pairing them would make a third meeting, that pair
must be absent. A week where one of those meetings *is* the rivalry week's own original
matchup can still pair them, because the count stays at two. This is a preference/constraint
test, per the repo's sign-inversion bug class.

**Acceptance Scenarios**:

1. **Given** a pair that meets twice in the regular season outside week W, **When** week W's
   pairings are chosen, **Then** that pair isn't paired in W.
2. **Given** a pair that meets in week W and once elsewhere, **When** week W's pairings are
   chosen, **Then** pairing them is allowed (still two meetings) and they're marked "already
   scheduled this week".
3. **Given** the cap removed a history-best pair from the recommended week, **When** the section
   renders, **Then** it names that pair and the weeks they already meet.
4. **Given** the recommended week, **When** the commissioner reads the section, **Then** the
   original matchups it displaces are listed by name, for information. They don't affect which
   week is chosen.

---

### User Story 3 - Honest when the cap can't be met (Priority: P2)

If no eligible week has a full set of pairings that obeys the cap, the section says so in
words, instead of naming a week that breaks it. If no week is eligible at all, it says why. In
both cases the history-best pairings from spec 034 still show, as today.

**Why this priority**: late-season leagues and leagues with many repeat matchups can hit this.
The repo's bar is honesty over a confident-looking answer.

**Independent Test**: a synthetic league where every eligible week forces some manager into a
third meeting. The section names no week and shows the "no week fits" message.

**Acceptance Scenarios**:

1. **Given** the regular season is over (or only playoff weeks remain), **When** the page
   loads, **Then** the section says no week is left to recommend, and it still shows the
   pairings.
2. **Given** the schedule isn't published, **When** the page loads, **Then** the section says
   a week can't be recommended until the schedule exists, and it still shows the pairings.
3. **Given** no eligible week admits a cap-obeying full set of pairings, **When** the page
   loads, **Then** no week is named, and the message says the two-meeting rule can't be kept
   in any remaining week.

### Edge Cases

- **Odd-sized league.** The existing matching leaves one manager unpaired. That manager has no
  rivalry game that week, so they gain no meeting. Their original week-W game is displaced.
- **A pair already scheduled to meet in week W.** Pairing them in W replaces their own game, so
  their count doesn't change.
- **A pair that already meets three or more times in the published schedule** (possible in
  small leagues with long seasons). Rivalry week can't fix that. Pairing them is not allowed in
  **any** week, including one of their own, so their count never rises. The section notes that
  the published schedule already exceeds the cap for that pair. *(Amended after plan review
  F6. The original allowed their own weeks, which contradicted the counting rule.)*
- **A partly published schedule.** If any regular-season week is missing pairings, meetings
  can't be counted, so no week is recommended (`NOT_PUBLISHED`). *(Added after plan review
  F4.)*
- **Orphan teams or a manager with two teams.** The rivalry pairings are per manager, but
  Sleeper's weeks are per team. No week is recommended, and the section says why. *(Added
  after plan review F5.)*
- **The current week.** If it's in progress or partly scored, it isn't eligible. Only weeks
  not yet started are.
- **Playoff weeks.** Never eligible, and never counted toward the cap, since bracket games
  aren't scheduled meetings.
- **Median scoring.** A median game isn't a head-to-head pairing, so it doesn't count toward
  the cap.
- **NBA weeks of unusual length** (week 1, All-Star break). They don't affect eligibility or
  ranking.
- **Large leagues** above the matching cap. They're already refused by spec 034, with no
  change here.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The rivalry week section MUST name one recommended week when at least one
  eligible week admits a full set of pairings that obeys the cap (FR-003).
- **FR-002**: A week is eligible only if it's a regular-season week **after the week Sleeper
  reports as current, and after the last scored week**. If the season is complete, no week is
  eligible. Playoff weeks MUST NOT be eligible. *(Amended after plan review F15/F8: the
  original said "hasn't started". The app can't tell whether the current week has started, so
  it's always excluded. Before the NBA's tip-off, that costs week 1.)*
- **FR-003**: With the rivalry pairings put into week W, no pair of managers may meet more
  than **2** times in the regular season. Meetings are counted from the published schedule,
  with week W's original matchups replaced by the rivalry pairings. This is a hard rule. The
  value 2 is Allan's league rule, shown on the page as such.
- **FR-004**: For each eligible week, the pairings MUST be the best-scoring full set under
  spec 034's existing rivalry score, among sets that obey FR-003. The recommended week is the
  one whose set scores highest.
- **FR-005**: The choice MUST be deterministic. Weeks rank by:
  1. total score
  2. how many of the history-best pairs the week keeps as they are
  3. earliest week

  *(Amended after plan review F2, with Allan's choice on 2026-10-15. The original said only
  "ties break toward the earliest week". On West Coast, all 10 eligible weeks tie on score.
  "Earliest" picked week 5, which rearranges 4 of the best pairs, while week 14 keeps all of
  them.)*
- **FR-006**: The recommended week MUST carry a one-line reason, stated in concrete terms.
- **FR-007**: When FR-003 keeps a history-best pair (from the unconstrained matching) out of
  the recommended week, the section MUST name that pair and the weeks they already meet.
- **FR-008**: For the recommended week, the section MUST list the displaced original matchups
  by manager names, for information only.
- **FR-009**: Pairs already scheduled to meet in the recommended week MUST be marked as such.
- **FR-010**: When no week is eligible, the schedule isn't published, or no eligible week
  admits a cap-obeying set, the section MUST say which, in words, and still show spec 034's
  pairings.
- **FR-011**: The page MUST keep stating that the tool doesn't change the Sleeper schedule.
- **FR-012**: Everything works for both football and basketball leagues, under the existing
  league access scoping.

### Key Entities

- **Candidate week**: one eligible week, its best cap-obeying pairings and their total score,
  pairs the cap held out, and its rank.
- **Meeting count**: per manager pair, the number of regular-season meetings with a given
  week's matchups replaced by rivalry pairings.
- **Week recommendation**: the chosen candidate week, its reason, the held-out pairs, the
  displaced matchups and the ordered runner-ups. It extends spec 034's rivalry week
  recommendation and doesn't replace it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On "(Foot) Ball Knowers" 2026, West Coast 2026 and NBA "Ball Knowers" 2026, a
  script recounts every pair's regular-season meetings with each candidate week's pairings in
  place. For every week the page offers, no pair's count rises above 2, and no pair ends above
  2 unless the published schedule already had it there. *(Restated after plan review F6.)*
- **SC-002**: On the same leagues, the section names a week in 100% of loads where some
  eligible week admits a cap-obeying set, or shows the FR-010 message otherwise. Checked on
  live payloads.
- **SC-003**: Tests pin all four of these:
  - a pair at two meetings elsewhere is never paired in that week
  - a pair whose second meeting is week W itself may be paired in W
  - among cap-obeying weeks, the higher-scoring set wins
  - ties resolve to the earliest week
- **SC-004**: The recommended week is never a played, in-progress or playoff week, checked
  against Sleeper's **live** current week rather than the payload's own (0 violations across
  the three leagues). *(Amended after plan review F8: checking against the payload was
  circular.)*
- **SC-005**: A commissioner can read the recommended week, its pairings, and any held-back
  pair without leaving the rivalry week section, and without cross-referencing the season
  grid by hand.

## Assumptions

- **"More than twice" means regular-season meetings only.** Playoff and consolation games
  aren't scheduled, so they don't count.
- **The cap is 2, a league rule.** It's set from Allan's answer, kept beside the other rivalry
  settings, and labelled as a hand-set rule, not a fitted number.
- **The published schedule is taken as it stands.** It doesn't assume the commissioner has made
  other edits.
- **Displaced matchups don't rank weeks.** A pair losing its only meeting is shown, but Allan's
  rule is the cap alone. If that turns out to matter in practice, it's a follow-up, not an
  invented weight.
- **The final regular-season week isn't excluded.** It's a common rivalry week choice.
- **No new data source.** Everything comes from the schedule and history spec 034 already
  reads.
- **The pair score is unchanged.** Spec 034's hand-set score, weights and N stay as they are.
  This spec only adds the cap and the week choice, and never retunes the score to make a week
  look better.

## Out of Scope

- Writing anything to Sleeper. The app stays read-only toward Sleeper.
- Rearranging other weeks to restore displaced matchups (a schedule rebuilder).
- Changing how pairs are scored.
- Letting the commissioner pick a week and re-scoring live in the UI. That's a possible
  follow-up, and not needed for the stated ask.
