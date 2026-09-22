# Feature Specification: Reordering a ranking on a phone

**Feature Branch**: `007-mobile-rank-drag`

**Created**: 2026-09-22

**Status**: Draft

**Input**: User description: "ok rankings on mobile is still really wonky dragging teams up and down. lets fix this"

## Problem

The power-rankings board (the commissioner ranking and the member ballot) is the one screen in this app where the primary action is a sustained drag. It was built pointer-first and it works on a mouse. On a phone it does not, and the reasons are structural rather than cosmetic: the board opens in a modal capped at 80% of screen height, with its own internal scrolling list of one row per team. On a 375px-wide phone three or four of a twelve-team league sit off-screen at any moment, and the board offers no way to reach them while a finger is down. A move of any real distance therefore has to cross a window shorter than the journey.

Four behaviours combine into "wonky":

1. **A drag cannot travel past the visible rows.** Nothing scrolls the list while a chip is held, so a team can only be dropped somewhere already on screen. Moving a team from last to first -- the single most common thing anyone wants to do to a ranking -- cannot be done in one gesture. The member drops, scrolls, re-grabs, and repeats.
2. **Scrolling the board is itself unreliable.** Every row is a drag handle and the rows tile the list, so a thumb placed anywhere on the list to scroll it begins a drag instead. There is no press-and-hold pause that separates "I am scrolling" from "I am moving this team".
3. **Drops can land on the wrong row.** The board measures where each row sits at the moment a drag begins and does not re-measure if the list scrolls underneath. Once scrolling during a drag exists at all, every measurement taken before the scroll is wrong by the scrolled distance, and the chip lands somewhere the member did not aim.
4. **A missed drop says nothing.** Releasing between rows, or outside the board, leaves the order unchanged with no message and no motion. Under a fingertip that covers the target this is frequent, and it is indistinguishable from the app being broken.

There is a second, reliable path already in the board -- tap a team, then tap where it should go -- but the on-screen instructions point phone users at keyboard arrow keys instead, which a phone does not have.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Move a team the full height of the board (Priority: P1)

A league member opens their weekly ballot on their phone. The team they think is worst is sitting near the top; they want it last. The team they think is best is below the fold. They can pick up a team and move it anywhere in the ranking -- including to a position they cannot currently see -- in one continuous gesture, without dropping it partway and starting again.

**Why this priority**: This is the defect. Every other item on this list is a supporting condition for this one working. A ranking whose extremes cannot reach each other is not a ranking tool.

**Independent Test**: On a 375px-wide viewport with a twelve-team league, drag the team in slot 12 to slot 1 in a single uninterrupted gesture, and confirm the board reads 1-12 with that team first and no other pair transposed.

**Acceptance Scenarios**:

1. **Given** a twelve-team ballot on a phone-sized screen where only part of the list is visible, **When** the member holds a team and moves it to the top edge of the list, **Then** the list scrolls toward rank 1 while the team stays held, and keeps scrolling until it reaches the top or the member moves away from the edge.
2. **Given** the same board, **When** the member holds a team and moves it to the bottom edge of the list, **Then** the list scrolls toward the last rank on the same terms.
3. **Given** the list has scrolled during a drag, **When** the member releases over a row, **Then** the team lands in that row's position -- the position under the finger at the moment of release, not the position that row occupied when the drag began.
4. **Given** a team is dropped into a new position, **When** the drop completes, **Then** every other team keeps its relative order and the ranks renumber 1..N with no gaps or repeats.

---

### User Story 2 - Scroll the ranking without disturbing it (Priority: P1)

The same member wants to read the whole ranking before changing anything. They swipe up and down over the list. The list scrolls. No team moves, and no team ends up held.

**Why this priority**: Equal to US1 and inseparable from it -- the two compete for the same gesture on the same pixels. Fixing dragging while leaving every scroll attempt a misfire trades one complaint for another, and reading the board is the more frequent act.

**Independent Test**: On a phone-sized viewport, swipe vertically starting with the finger on a team row; confirm the list scrolls, no team is picked up, no drag indicator appears, and the resulting order is identical to the order before the swipe.

**Acceptance Scenarios**:

1. **Given** a ballot open on a phone, **When** the member swipes vertically starting on a team row, **Then** the list scrolls and no team is picked up.
2. **Given** a ballot open on a phone, **When** the member presses and holds on a team row without moving, **Then** the board indicates that team is now held and ready to move.
3. **Given** a team has been picked up by holding, **When** the member moves their finger, **Then** the team follows the finger and the list no longer scrolls from that gesture.
4. **Given** a member begins a hold and then moves their finger before the board has taken the team, **When** the movement exceeds an ordinary touch wobble, **Then** the board treats the gesture as a scroll and does not pick the team up.

---

### User Story 3 - Know what a gesture did (Priority: P2)

Whatever the member does -- a successful move, a release between rows, a release outside the board, an interrupted drag -- the board says what happened, in a way visible on a screen where a finger is covering the target.

**Why this priority**: The mechanism can be correct and still feel broken if silence and success look identical. This is what turns "wonky" into trust, but it has no value until US1 and US2 make the outcomes correct in the first place.

**Independent Test**: Perform a move, a release in the gap between two rows, and a release outside the board; confirm each produces a distinguishable on-screen outcome and each is announced to assistive technology.

**Acceptance Scenarios**:

1. **Given** a team is being held, **When** the member moves over the list, **Then** the board shows where that team will land if released, positioned so it is not hidden by the finger.
2. **Given** a team is being held, **When** the member releases somewhere that is not a valid position, **Then** the team returns to where it started, visibly, and the board says the ranking is unchanged.
3. **Given** a team is moved successfully, **When** the drop completes, **Then** the board states the team's new rank and total, both on screen and to assistive technology.
4. **Given** a drag is interrupted by the operating system -- an incoming call, a notification, a system gesture -- **When** the interruption occurs, **Then** the ranking is left exactly as it was before the drag and nothing is left stranded on screen.

---

### User Story 4 - Rank without dragging at all (Priority: P2)

A member who cannot or does not want to drag can reach any ordering by tapping, and the board tells them so in words that match the device they are holding.

**Why this priority**: The tap path largely exists already and is the most reliable route on touch; what is missing is that phone users are told to use arrow keys. Correcting the instructions and completing the path is cheap and independently valuable, but it does not excuse leaving dragging broken.

**Independent Test**: Without using a drag at any point, reorder a twelve-team board into an arbitrary target order using only taps, and submit it.

**Acceptance Scenarios**:

1. **Given** a ballot on a phone, **When** the member reads the board's instructions, **Then** the instructions describe gestures available on a touch screen and do not direct them to keyboard arrow keys.
2. **Given** a member taps a team, **When** they then tap another position, **Then** the first team moves to that position and the teams between shift by one -- they are not swapped.
3. **Given** a member has a team selected, **When** they change their mind and dismiss the selection, **Then** nothing moves.
4. **Given** a member is using a keyboard or screen reader, **When** they use the board, **Then** the existing keyboard and assistive-technology routes continue to work unchanged.

---

### Edge Cases

- A team is held when the board's content changes underneath it -- the tray shrinks, the modal resizes, the on-screen keyboard opens or closes, or the device rotates.
- A second finger touches the screen while a team is held; or the member lifts the wrong finger first.
- The member holds a team and moves outside the modal entirely, over the backdrop or the browser chrome.
- The member moves toward an edge and holds still there after the list has already reached its end -- scrolling must stop rather than continuing to consume the gesture.
- A league small enough that the whole board fits on screen, where no scrolling is needed or wanted.
- A league large enough that the board is several screens tall.
- The board is opened on a tablet, or on a phone with an attached keyboard and a mouse, where both interaction styles are available at once.
- A gesture ends while the submission request from a previous action is still in flight.
- The member moves a team onto the position it already occupies.

## Requirements *(mandatory)*

### Functional Requirements

**Reaching off-screen positions**

- **FR-001**: While a team is held, the board MUST be able to bring positions that are off-screen into view, in both directions, without the member releasing the team.
- **FR-002**: Movement toward off-screen positions MUST stop at the first and last rank and MUST NOT continue once an end is reached.
- **FR-003**: A held team MUST be placeable at any rank from first to last, including ranks that were off-screen when the gesture began.

**Correct landing**

- **FR-004**: The position a team lands in MUST be the position indicated on screen at the moment of release, regardless of how much the list moved during the gesture.
- **FR-005**: Placing a team at a new rank MUST shift the intervening teams by one and preserve the relative order of every team not moved; it MUST NOT swap two teams.
- **FR-006**: After any completed gesture the board MUST hold exactly one team per rank, numbered 1..N with no gaps, repeats, or lost teams.

**Separating scroll from move**

- **FR-007**: A vertical swipe beginning anywhere on the board MUST scroll the board and MUST NOT move a team.
- **FR-008**: The board MUST provide a deliberate, non-accidental way to pick a team up on a touch screen, distinct from swiping.
- **FR-009**: The board MUST confirm, before a team begins following the finger, that the team has been picked up.
- **FR-010**: Once a team is being moved, that gesture MUST NOT also scroll the board other than as required by FR-001.

**Feedback**

- **FR-011**: While a team is held, the board MUST show where it will land if released, and that indication MUST remain visible when a finger is over the target.
- **FR-012**: The board MUST distinguish, visibly, between a move that took effect and a release that changed nothing.
- **FR-013**: Every change of rank MUST be announced to assistive technology, stating the team and its new rank out of the total.
- **FR-014**: A gesture interrupted by the operating system MUST leave the ranking unchanged and MUST leave nothing stranded on screen.

**A route that needs no drag**

- **FR-015**: Members MUST be able to reach any ordering on a touch screen without performing a drag.
- **FR-016**: On-screen instructions MUST describe interactions that exist on the device in use, and MUST NOT direct touch-screen users to keyboard-only controls.
- **FR-017**: Existing keyboard and assistive-technology interactions MUST continue to work unchanged.

**Scope guards**

- **FR-018**: Changes MUST apply to both boards that use this component -- the commissioner ranking and the member ballot.
- **FR-019**: Nothing MUST be saved until the member submits; all reordering remains local until then.
- **FR-020**: Submission MUST remain blocked until every rank is filled.
- **FR-021**: Behaviour on pointer devices MUST NOT regress: a mouse drag MUST continue to work without a press-and-hold delay.

### Key Entities

- **Ranking**: An ordered list of every team in the league, one team per rank, 1..N. The unit the member edits and submits. Valid only when complete.
- **Team chip**: One team's place in the ranking, identified by its roster, carrying the manager's name, avatar and team name. The thing picked up and moved.
- **Held state**: The transient condition of one chip being moved by a gesture. Exists only between pick-up and release, belongs to no saved data, and must leave no trace if the gesture is interrupted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a 375px-wide screen with a twelve-team league, a member can move the last-ranked team to first in one continuous gesture, without releasing.
- **SC-002**: Reordering a twelve-team ranking from a seeded order into a chosen target order takes under 90 seconds on a phone, down from a flow that today requires an interrupted gesture for any move spanning more than half the visible list.
- **SC-003**: Ten consecutive vertical swipes over the board scroll it ten times and move a team zero times.
- **SC-004**: Ten consecutive deliberate pick-up-and-move gestures land the team on the intended rank ten times.
- **SC-005**: Every gesture that ends without changing the ranking produces a visible indication that nothing changed.
- **SC-006**: Any ordering of a twelve-team board is reachable using taps alone, with no drag.
- **SC-007**: Keyboard and screen-reader users complete a full reordering in no more steps than the board requires today.
- **SC-008**: A mouse drag on a desktop screen moves a team with no added delay compared with today.

## Assumptions

- The target is a phone in portrait at 375px wide, the narrowest case the app's existing breakpoints acknowledge; anything wider is expected to follow.
- "Rankings" refers to the power-rankings board used for the commissioner ranking and the member ballot -- the only place in the app where teams are dragged up and down. Draft-board and roster interactions are out of scope.
- League size is the existing range, around 8 to 14 teams; the board must not assume the list fits on screen.
- The ordering rules themselves are correct and are not being changed -- a placement shifts, it does not swap. Only the way a member expresses an ordering on a touch screen is in question.
- The board continues to hold the ranking locally until submitted, and continues to require a complete ranking to submit.
- The existing keyboard and assistive-technology paths are treated as a floor, not a ceiling: they may gain, but must not lose.
- Haptic feedback on pick-up is desirable where the device supports it, but is not required for any acceptance criterion, since support is inconsistent across mobile browsers.
- Verification happens on a real phone-sized viewport with touch input, not only in a desktop browser's responsive mode, because the defects here are touch-gesture defects that a mouse cannot reproduce.
