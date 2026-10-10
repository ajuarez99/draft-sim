# Feature Specification: Draft room UX, borrowed from Sleeper and FantasyAlarm

**Feature Branch**: `024-draft-room-ux`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description: "ok research agents in haiku or sonnet onto other fantasy draft sites like sleeper and improve the UI UX experience here
https://sleeper.com/draft/nba/1414361905279049728?ftue=commish
https://www.fantasyalarm.com/nba/mock-draft-simulator" — sent with a screenshot of the live draft room before the draft started (an NBA league with 4 teams and 14 rounds, no managers identified yet, seat 1 assumed).

## Where this comes from

Two Sonnet research agents looked at the two sites on 2026-10-09. Their notes are in [research/sleeper.md](research/sleeper.md) and [research/fantasyalarm.md](research/fantasyalarm.md). How much each one saw differs, and the difference matters:

- **FantasyAlarm: observed directly.** The agent ran one complete 12-team mock draft at the default settings.
- **Sleeper: only the pre-draft lobby was observed.** The browser was signed out, so the agent saw the "claim a team" page and nothing more. Everything this spec borrows from Sleeper's *live* room (the queue, how autopick works) comes from Sleeper's help articles. None of it was seen.
- **No other site was inspected.** FantasyPros, ESPN, Yahoo, Underdog and Draft Sharks are not covered.

### What the screenshot shows wrong in our room today

These come from looking at the screenshot, cross-checked against `web/src/pages/LiveDraftView.tsx` on main (`d145f38`):

1. **The player list covers the board.** "Best available" floats over rounds 7–14 as a sheet (`AvailabilityPanel`, `.avail-sheet`), and the rows underneath bleed through blurred. You can see the board or the list, never both whole.
2. **The column headers say only "1 2 3 4".** No manager has been identified yet, so there is no name, no avatar, and nothing telling you how to claim your seat. The only cue is a muted line: "slot 1 (assumed — click your seat)".
3. **Cells show a bare pick number** ("8", "17"), not the round and pick. There is no cue for which way the snake runs.
4. **Your column is drawn as 14 separate red outlines**, one per cell. It is the loudest thing on the page, it marks a seat that is only *assumed*, and the cells it outlines are empty.
5. **The waiting state is said twice.** "DRAFT HAS NOT STARTED" sits at the top and "WAITING FOR THE DRAFT TO START" at the bottom. The format summary ("56 picks · 14 rounds") also leaves out the team count and the pick timer.
6. **The scarcity chips read "SG 0/0" and "SF 0/0".** These are positions with no players in the starter pool, and they look broken.
7. **The four room controls are spread across the full width at uneven gaps:** Announcing, Pick cards, Project again and Continue as a mock.
8. **The player list has no search box, no list of players you're targeting, and no way to hide players who are already drafted.** Position chips are its only filter.

## Clarifications

### Session 2026-10-09

- Q: At desktop widths, should the player list sit to the right of the board, or below it, with the two splitting the screen height? → A: Stacked and adjustable. The board is on top and the list below, both full width. A divider between them can be dragged to give either more room, and the position is remembered on the device.
- Q: Should your target list be saved on the device you made it on, or on your Ball Knowers account so it follows you between laptop and phone? → A: On the account, per draft. The same list appears on every device the user signs in on.
- Q: Where in the room should your target list appear: as a tab in the player list area, as a compact strip always visible above the list, or as its own column beside the list? → A: A pinned strip. One row of target chips sits above the player list and is always visible. Reordering and removing happen in a popover opened from the strip.
- Q: When you start auto-finish in a mock, should the remaining picks play out at the room's normal pace, or should the room jump straight to the finished board? → A: Jump straight to the finished board. There is no per-pick reveal, no pick cards and no announcements. The pick feed still records every pick, with the user's auto-made picks marked.
- Q (raised by the user during planning): Should a real draft's room look like the mock room? → A: Yes. "This should also look the same for actual draft." All three rooms (live, projection, mock) share one layout. See FR-003.
- Q (raised by plan review #4, with measurements): Filled cells are 70 px tall, so how should the split fit? → A: Compact one-line cells in the split, with full size when the board is given room (FR-001b).
- Q (raised by plan review #8): Where do the live room's feed, team strip and scarcity chips go? → A: One compact row above the board, with the feed as a one-line ticker (FR-001c).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the board and the player list together (Priority: P1)

A manager in the live room (or a mock) wants to read the board and scan the best available players at the same time, without one covering the other. On a laptop or desktop screen, the board sits on top and the list below it, both at full width. A divider between them can be dragged to give either one more room, and the room remembers where it was left. On a phone, one control switches between them, as FantasyAlarm's board/list toggle does.

**Why this priority**: This is the biggest problem in the screenshot. Every later improvement to the list or the board is wasted if one covers the other.

**Independent Test**: Open the live room for a draft that hasn't started, on a 1440×900 window and then on a 375-wide phone. On the desktop window, every board column and the player list are fully visible with nothing overlapping. On the phone, a single control switches between them.

**Acceptance Scenarios**:

1. **Given** a 1440×900 window and a 4-team draft, **When** the room loads, **Then** all 4 board columns, at least 7 rounds of the board, and at least 8 player rows are visible at once, and no part of the list draws over the board.
2. **Given** a 12-team draft at 1440 wide, **When** the room loads, **Then** the board scrolls sideways inside its own area if it has to, and the player list stays in place and visible.
3. **Given** the default split, **When** the user drags the divider up or down, **Then** the board and list resize with no overlap. Neither can be dragged below a minimum of 3 board rounds or 3 player rows.
4. **Given** a divider the user has moved, **When** they reload the page or open another draft on the same device, **Then** the divider is where they left it. **When** they reset it (by double-clicking the divider), **Then** it returns to the default split.
5. **Given** a 375-wide phone, **When** the room loads, **Then** one control toggles between the board and the list, and the page itself never scrolls sideways.
6. **Given** the mock room, **When** it is your turn, **Then** you can see your player choices and the board at the same time, under the same layout rules.
7. **Given** the same Sleeper draft open in its live room and its projection room, and a mock forked from it, **When** the three are compared side by side at the same width, **Then** the board, the list, the divider, the target strip and the control group sit in the same places and look the same.

---

### User Story 2 - A board you can read at a glance (Priority: P1)

Before and during a draft, the manager wants the board to answer four questions without any counting: who sits in each seat, where the draft is right now, which way the snake is going, and which picks are theirs. The board must also keep clear which picks have actually happened and which are only projected. That honesty is something this product does and competitors don't.

**Why this priority**: The board is the main surface in both rooms. Right now the headers, labels and seat marking in the screenshot make it harder to read than Sleeper's empty lobby board.

**Independent Test**: Load a draft with no seats mapped, then the same draft after seats are mapped and a few picks have landed. Check each element below by looking at the board, without opening anything.

**Acceptance Scenarios**:

1. **Given** a seat with no identified manager, **When** the board renders, **Then** that column's header says the seat is unclaimed and offers one action to make it yours, in the header itself, like Sleeper's CLAIM pill.
2. **Given** a seat with an identified manager, **When** the board renders, **Then** its header shows the manager's avatar and name.
3. **Given** any cell, **When** it renders, **Then** it shows its pick as round.pick (e.g. "2.03") and a small cue for the snake's direction. A draft with a third-round reversal must show that reversal correctly.
4. **Given** your seat is known, **When** the board renders, **Then** your column is marked once, in its header with a light column tint, rather than with an outline on every cell. **Given** your seat is only assumed, **Then** the marking reads as assumed (visibly weaker, with "assumed" in the header) and never as confirmed.
5. **Given** a draft in progress, **When** the board renders, **Then** the cell that is on the clock shows "On the clock" in the cell itself and stands out from every other cell.
6. **Given** a draft in progress, **When** the board renders, **Then** a test reader can say what kind of cell each one is without a tooltip, under FR-008's per-room rule. *(Amended after plan review #7: the original "landed vs projected" described a board no room draws. The live board has never drawn the projection past the last real pick.)*

---

### User Story 3 - Find players and keep a target list (Priority: P2)

A manager preparing for, or sitting in, a draft wants to type a name and jump to that player, hide players who are already gone, and keep a short, ordered list of the players they're targeting. For each target they want the chance that the player is still there at their next pick, and they want to see straight away when someone takes one. In the live room the picks happen on Sleeper, so this list is a watchlist and drafts no one. In the mock room it also feeds auto-pick (User Story 5).

**Why this priority**: Every competitor has search and a queue, and we have neither. A target list is also where our own availability numbers become most useful. Sleeper's queue tells you nothing about whether a target will last; ours can.

**Independent Test**: In the player list, search for a player by part of their name. Add three targets and reorder them. Mark one as drafted (in the mock room, or by a landed pick in the live room). Check that the list says that target is gone. Reload the page and check the targets are still there.

**Acceptance Scenarios**:

1. **Given** the player list, **When** the user types at least 2 letters of a player's name, **Then** the list narrows to matching players, accents ignored (typing "jokic" finds "Jokić").
2. **Given** players already drafted, **When** "Hide drafted" is on, **Then** they leave the list. **When** it is off, **Then** they stay, visibly marked as taken.
3. **Given** a player in the list, **When** the user adds them as a target (one click on the player's row), **Then** they appear as a chip in the target strip pinned above the list. Each chip shows the player, their chance of surviving to the user's next pick wherever that number is available, and whether they've been taken. The order can be changed, and targets removed, from a popover opened from the strip.
4. **Given** a target, **When** another seat drafts them, **Then** the target list marks them taken in a way the user notices without looking for it, and the next available target becomes the top one.
5. **Given** targets saved for a draft on one device, **When** the same user opens that draft on another device (or reloads), **Then** the same targets appear in the same order.
6. **Given** the user's seat is not known, **When** they look at targets, **Then** the survival chances are held back with the same reason the list already gives ("Availability appears once your seat is known."), and no seat-1 number is shown in their place.
7. **Given** two signed-in users in the same draft, **When** either one edits their targets, **Then** the other user's list is unchanged. Targets are private to their owner.

---

### User Story 4 - A calm pre-draft room (Priority: P2)

A manager who opens the room before the draft starts (the screenshot's exact state) wants one clear statement of where things stand: the draft's format in a few numbers, how to claim their seat, and what they're waiting for. They don't want the same message twice, broken-looking counts, or controls scattered across the page.

**Why this priority**: Every league member sees this state first, often hours ahead of the draft. It sets how trustworthy the whole room feels.

**Independent Test**: Open a draft that hasn't started and has no seats mapped, and check each scenario below on that one screen.

**Acceptance Scenarios**:

1. **Given** a draft that hasn't started, **When** the room loads, **Then** the waiting message appears exactly once.
2. **Given** any draft, **When** the room loads, **Then** a summary gives the team count, the round count, the pick timer if the draft has one, and the draft type (e.g. "4 teams · 14 rounds · 2 min · snake"), in the way Sleeper's three-number strip does.
3. **Given** a position with no players in the starter pool, **When** the scarcity chips render, **Then** that position shows no "0/0" chip. It is either left out or explained in words.
4. **Given** the room's controls (announce picks, pick cards, project again, continue as a mock), **When** the room loads, **Then** they sit together as one compact group, and "Continue as a mock" stays the single most prominent action whenever it is available.

---

### User Story 5 - Mock room pacing: auto-pick and auto-finish (Priority: P3)

A manager running a mock wants to hand one pick to the computer, or to finish the whole mock in one step and go straight to the completed board, as FantasyAlarm's Auto Pick / Auto All do (FantasyAlarm's Auto All plays out over about 15 s; ours jumps straight to the end). Auto-pick takes the user's top available target first and falls back to the room's own best-available order only if no target is left (Sleeper's queue-first rule).

**Why this priority**: It makes mocks quicker to repeat, but nothing in the live room depends on it.

**Independent Test**: Start a mock, add two targets, press auto-pick on your turn, and check it took the top available target. Then choose auto-finish, and check that the board is at once complete, that no pick cards or announcements fired, and that the pick feed lists every pick with yours marked as auto-picked.

**Acceptance Scenarios**:

1. **Given** it is your turn in a mock and you have an available target, **When** you choose auto-pick, **Then** your top available target is drafted.
2. **Given** none of your targets are available, **When** you auto-pick, **Then** the room's own top best-available player who fits an open roster slot is drafted, and the pick is marked as made by auto-pick.
3. **Given** a mock in progress, **When** you choose auto-finish, **Then** the room jumps straight to the completed board. Every remaining pick, yours included, is made without any per-pick reveal, pick card or announcement.
4. **Given** auto-finish has completed the mock, **When** you look at the board, your roster and the pick feed, **Then** they look exactly as they would if you'd made those picks by hand, except that your auto-made picks are marked as auto-picked.
5. **Given** auto-finish is not reversible, **When** you choose it, **Then** the control's label makes clear that it finishes the whole mock (e.g. "Auto-finish the draft"). No cancel is offered, because there is no gap in which to cancel.

### Edge Cases

- **Seat assumed vs known.** Every seat-dependent element (column marking, target survival chances, "fills a need" tags, the turn chime) must keep today's rule: an assumed seat is never presented as known.
- **Many teams.** For a 16-team draft on a 1280-wide window, the board scrolls inside its own area and the player list keeps its space. Headers must not cut names off so badly that two of them read the same, which is FantasyAlarm's "T1, T1, T1" bug.
- **A finished draft.** Nothing is on the clock and nothing is projected. The board shows no on-the-clock cell and no projected styling.
- **A target picked between two refreshes.** It shows as taken on the next update. It is never silently dropped from the list.
- **A target who is not in the projection** (no survival number). The target still shows, with no number, not a 0%.
- **Sleeper resets a draft.** A known open bug leaves stale picks behind (`upsertPicks` never deletes). *Amended after plan review #22:* until that bug is fixed, targets drafted before a reset **stay marked taken**. "Taken" is computed from the stored picks, and those picks don't go away. This edge case is **not met** by this feature, and is owed with the reset bug.
- **No player pool loaded** (the stats pool failed). Search and targets degrade to what the projection holds, with today's "Couldn't load the player list." message.
- **Basketball multi-position players.** *Amended at plan time (2026-10-09). The first version of this bullet said "PG/SG" players already match a filter on any of their positions, as "today's behaviour". That was wrong.* Every player the room receives carries one position, the first one Sleeper lists. This is measured: among the top 36 NBA players by ADP in the local database that first position is PG 17, C 11, PF 8, and never SG or SF. That is exactly the "SG 0/0 / SF 0/0" in the screenshot. This feature keeps single-position behaviour and fixes only how it is shown (FR-018). Counting a player toward every position he is eligible for is a separate fix. See plan research R6.

## Requirements *(mandatory)*

### Functional Requirements

**Layout (US1)**

- **FR-001**: At 1280 px wide and above, the room MUST stack the board above the player list, each at full width and each scrolling inside its own area. Neither MUST cover the other.
- **FR-001a**: A divider between the board and the list MUST let the user resize the two by dragging, with a minimum of 3 board rounds and 3 player rows. The divider MUST work from the keyboard as well. Its position MUST be remembered on the device across reloads and drafts, and it MUST be resettable to the default. The default split MUST meet SC-001.
- **FR-001b** *(added after plan review #4, decided by the user 2026-10-09)*: In the split layout, filled board cells MUST use a **compact one-line density** (position badge and short name, about 36 px per round instead of the measured 74 px). When the user drags the divider to give the board enough height for full-size rounds, the cells return to full size (face, team, meta line).
- **FR-001c** *(added after plan review #8, decided by the user 2026-10-09)*: In the live room, the pick feed, "Your team" strip and position-scarcity chips, measured at 438 px above the board today, MUST collapse into **one compact row above the board** of about 80 px:
  - the feed becomes a one-line ticker of the latest pick, which expands to the last few on click;
  - "Your team" and the scarcity chips share one row.
  All of it stays visible without opening anything.
- **FR-002**: Below that width, the room MUST give one control to switch between the board and the list, and MUST NOT make the page scroll sideways. The board may scroll sideways inside its own area.
- **FR-003**: Every draft room MUST look and behave the same, using the same layout rules, board, player list, target strip and room controls. That means the live room on a real Sleeper draft (`/drafts/:id/live`), the projection room for the same draft (`/drafts/:id`) and the mock room (`/mock/:id`). A room differs from the others only where its data differs: a mock has no survival numbers and no live clock, and only a mock offers auto-pick. Such a difference MUST show as an explained absence, never as a different layout.

**Board (US2)**

- **FR-004**: Each board column header MUST show either the seat's manager (avatar and name) or an "unclaimed" state with a single action to make it your seat.
- **FR-005**: Each cell MUST show its pick as round.pick and a snake-direction cue. Both MUST follow the draft's actual order, including a reversal round.
- **FR-006**: The user's own seat MUST be marked at the column level, not with a mark on every cell. A seat that is assumed but not confirmed MUST look visibly different from a confirmed one and MUST be labelled "assumed".
- **FR-007**: The on-the-clock cell MUST be marked inside the cell itself while a draft is in progress, and MUST NOT appear when the draft hasn't started or is finished.
- **FR-008** *(amended after plan review #7; this is Claude's decision, the conservative reading, not the user's)*: Each room MUST make its own cell kinds visibly distinct:
  - **Live room:** landed vs empty. The live board keeps not drawing the projection past the last real pick, as it does today (lessons #5: a board is read as one coherent board).
  - **Projection room:** projected vs the user's own chosen pick.
  - **Mock:** every cell is a real pick.
  
  Drawing the projection onto the live board is out of scope.

**Player list and targets (US3)**

- **FR-009**: The player list MUST have a name search that ignores case and accents, and that narrows the list as the user types.
- **FR-010**: The player list MUST have a "Hide drafted" toggle. With it off, drafted players MUST be marked taken.
- **FR-011**: Users MUST be able to add players to an ordered target list for a draft from the player's row, remove them, and reorder them.
- **FR-011a**: The target list MUST be shown as a single-row strip of chips, pinned above the player list and visible whenever the list is. Each chip shows the player, their survival chance (under FR-012), and whether they've been taken. If there are more targets than fit, the strip MUST show how many are hidden (e.g. "+4") and never wrap onto a second row. Reordering and removing MUST be done from a popover opened from the strip. With no targets, the strip shows one line explaining how to add one.
- **FR-012**: The target list MUST show each target's chance of surviving to the user's next pick when the seat is known and the number exists. It MUST show no number (not a zero, not another seat's number) otherwise.
- **FR-013**: A target taken by any seat MUST be marked taken within one board update, and MUST stay in the list, marked as taken.
- **FR-014**: Targets MUST be saved to the signed-in user's account, scoped to one draft, and visible only to that user. They MUST appear unchanged on any device the user signs in on. An edit made on one device MUST show on another the next time that device loads or refreshes the room. Instant syncing between two open devices is not required.
- **FR-014a**: If saving a target edit fails, the room MUST say so and keep the user's edit on screen, rather than silently dropping it or showing it as saved.
- **FR-015**: In the live room, the target list MUST NOT claim to draft or queue anything on Sleeper. Its copy MUST make clear that picks are made on Sleeper.

**Pre-draft room (US4)**

- **FR-016**: The room MUST state the waiting / not-started status exactly once.
- **FR-017**: The room MUST show a format summary: teams, rounds, pick timer (when the draft has one) and draft type.
- **FR-018**: Position scarcity MUST NOT show a "0/0" count for a position with no starter-pool players.
- **FR-019**: The room-level controls MUST be grouped together, and "Continue as a mock" MUST stay the most prominent control whenever it is available. Before the draft is drafting, it MUST show as disabled, with a reason in its title, rather than hidden.

**Mock pacing (US5)**

- **FR-020**: In the mock room, the user MUST be able to auto-pick their current turn. It MUST take the top available target first, then the room's top best-available player who fits an open slot. *Amended after plan review #1/#15:* "fits an open slot" means **draftable** (the same hard gate the computer drafters apply, e.g. no kicker in round 9) **and would start** (roster need above the bench floor). If no player would start, auto-pick takes the top draftable player by ADP. A target who isn't draftable is skipped.
- **FR-021**: In the mock room, the user MUST be able to auto-finish. This makes every remaining pick, the user's own included under FR-020's rule, and goes straight to the completed board. It MUST NOT play out pick by pick, show pick cards, or announce picks. The pick feed MUST still record every pick.
- **FR-022**: Picks made by auto-pick MUST be identified as such on the board or in the pick feed.

**Cross-cutting**

- **FR-023**: This feature MUST NOT change how the simulation projects picks, the numbers it produces, or any hand-set constant. It is a presentation and interaction change, plus an account-saved target list.
- **FR-024**: Everything that exists today MUST keep working: pick cards, pick announcements and chime, Room read / on-brand reads, team needs, player cards, seat popovers, the stats columns from spec 023, and forking a live draft into a mock.

### Key Entities

- **Target list**: An ordered list of players one user is watching for one draft. It is saved to that user's account and is private to them. Each entry is a player plus a position in the order, and a player appears at most once per list. Whether a target is taken, and its survival chance, are worked out from the draft's current state each time. Neither is stored.
- **Seat claim state**: For each seat, whether it is unclaimed, assumed to be the user's, or confirmed as the user's. This already exists; this feature changes how it is shown, not what it means.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** *(amended after plan review #4)*: In a 1440×900 **viewport** (`innerWidth × innerHeight`, not the window), with a 4-team or 12-team draft at the default split, at least 7 board rounds at compact density (FR-001b) and at least 8 player-list rows are visible at once, and the board and list overlap by 0 px. The original "the whole of the player list" was ill-defined for a scrolling table of 100+ rows. Measured before the change: filled cell 70 px + 4 px gap, column header 72 px, list row 27 px (tiers view), and 438 px of live-room chrome above the board. This is measured in the live room and the mock room, both before and during a draft.
- **SC-002**: A person new to the room can find a named player in the list in 2 interactions or fewer: click the search box, then type.
- **SC-003**: On a screenshot of a draft in progress, a reader can name, for any cell, its round.pick, its kind under FR-008, and whether it belongs to the user, without opening anything. This is checked against 10 randomly chosen cells with 10/10 correct.
- **SC-004**: The pre-draft screenshot state (no seats mapped) shows the waiting message once, shows no "0/0" chip, and has a format summary naming teams, rounds and draft type.
- **SC-005**: A target drafted by another seat is marked taken in the same update that puts that pick on the board.
- **SC-006**: A 12-team NBA mock (14 rounds, the NBA mock default; amended after plan review #21) can be completed from the user's first turn in one interaction (auto-finish). The completed board appears in no more than the time the room already takes to simulate the rest of the draft, with no per-pick reveal added on top.
- **SC-007**: At 375 px wide, both rooms have no sideways page scroll, and switching between board and list takes one tap.

## Assumptions

- **All three draft rooms are in scope and must match** (clarified 2026-10-09 by the user: "this should also look the same for actual draft"). Those are the live room, the projection room for a real draft, and the mock room. The completed-draft board picks up the board changes from US2 automatically, but gets no new work of its own.
- **The live room stays read-only toward Sleeper.** Nothing here drafts, queues, claims a seat or changes settings on Sleeper. "Claim this seat" means telling *this app* which seat is yours, which is today's seat popover / "make mine" action, given a clearer home in the column header.
- **Targets are saved to the account** (clarified 2026-10-09). The research's one draft-night habit is "prep on desktop, pick on your phone", and the app already requires sign-in. This is the feature's only new stored data.
- **A mock forked from a live draft starts with a copy of that live draft's targets.** After the fork, the two lists are separate.
- **The floating "Best available" sheet is being replaced, not tuned.** It was a deliberate choice in `claude/board-first-layout-and-pick-latency.md` §E, made when the list was a short survival table. Since spec 023 the list is a full stats table. The sheet's "board owns the whole content area" goal is kept at phone widths, and replaced at desktop widths by an adjustable stacked split: board above, list below, with a draggable divider. The design doc for this feature must record that reversal openly, with an "amended" note against §E.
- **Pick timer.** It is shown only when the draft data carries one. Nothing is invented for drafts that don't.
- **The visual direction stays the current dark card/avatar style** (saved preference). Sleeper's light pastel board is a source for *structure* (round.pick labels, snake arrows, claim pills), not for colour.

## Explicitly not in scope

- **Chat, trades, commissioner tools** (pause, undo, forced autopick) and anything that writes to Sleeper.
- **A 9-category or category-strength view.** Neither competitor that was looked at has one, and the leagues this product serves score in points.
- **Changing CPU opponent behaviour, the simulation, or its numbers.** That covers FR-023.
- **Post-draft CSV export, share links, and a light theme.** These could be later ideas, not part of this feature.
- **The other sites the research never inspected** (FantasyPros, ESPN, Yahoo, Underdog, Draft Sharks). Nothing here is claimed from them.
