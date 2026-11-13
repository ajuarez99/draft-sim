# Feature Specification: Time Machine draft room and playoffs

**Feature Branch**: `063-time-machine-draft-room`

**Created**: 2026-11-13

**Status**: Clarified 2026-11-13

**Input**: Allan, after playing a Time Machine NBA game on 2026-11-13: the computer picks land all at once and he wants to see them happen; the season should end in playoffs the way his league does (top 6 after 19 weeks, top 2 on a bye, week-by-week elimination); and the draft screen should be redesigned after looking at other draft simulators.

## What is there now *(read from `main` at 1369668, not assumed)*

- **Picks**: when the fan picks, the server makes every computer pick up to the fan's next turn in the same request and the page paints them all at once (`web/src/pages/HistoricalRoom.tsx`, `HistoricalGameService.advanceBots`). There is no reveal step. The league mock draft has one (`web/src/useRevealedBoard.ts`, one pick per 450 ms); Time Machine does not use it.
- **Season**: `SeasonReplay.replay` plays a seeded round robin over every week the season has. The fan's finish is their standings rank (record, then points for, then a seeded coin). There are no playoffs.
- **Screen**: page header, then a full "what counts this season" panel, then the shared draft room layout (turn indicator, board, player list). There is no pick-by-pick rail and no view of the fan's own roster by lineup slot.

## What was looked at *(2026-11-13)*

A 12-team FantasyPros basketball mock was run by hand. Its room has: a left rail listing every pick in order with the fan's picks highlighted and each upcoming computer pick showing that team's position needs; a pinned "You're on the clock" banner with "Next turn in N picks"; the player list as the main area with the board as one tab among several; a roster view by lineup slot; and Undo, Restart and Auto Draft in the header. Its computer picks were also fast (21 picks inside roughly 8 seconds, not timed precisely). Sleeper's mock room is behind a login and was not seen beyond its marketing screenshot.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Watch the computer teams pick (Priority: P1)

A fan makes a pick. The computer teams between that pick and the fan's next turn then pick one at a time, about a second apart. Each pick appears on the board and in the pick rail as it happens, and that player leaves the available list at that moment, not before. The fan can change the speed or skip straight to their turn.

**Why this priority**: it is the complaint that started this. A draft where eleven picks appear in one frame does not feel like a draft, and the fan cannot follow who went where.

**Independent Test**: start a game, make a pick, and watch. The picks arrive one at a time in pick order; nothing shows a player as taken before his pick appears; "skip" lands the room in the same state a full reveal would.

**Acceptance Scenarios**:

1. **Given** it is the fan's turn at pick 2 of a 12-team draft, **When** they pick, **Then** picks 3 to 23 appear one at a time in order and the room stops on pick 23 with the fan on the clock.
2. **Given** a reveal is running, **When** a computer pick has not been revealed yet, **Then** its player is still in the available list and its board cell is still empty.
3. **Given** a reveal is running, **When** the fan presses skip, **Then** every remaining pick appears at once and the fan is on the clock.
4. **Given** the fan sets the speed to instant, **When** they pick, **Then** the room behaves as it does today.
5. **Given** a reveal is running, **When** the fan tries to draft a player, **Then** nothing is drafted; drafting is possible only once the reveal has reached their turn.
6. **Given** the fan chose a speed, **When** they open another Time Machine game in the same browser, **Then** that speed is still selected.
7. **Given** the fan reloads the page mid-reveal, **When** it loads, **Then** the room shows the true draft state with every pick made so far and the fan on the clock. Nothing replays and nothing is lost.
8. **Given** the fan's operating system asks for reduced motion, **When** picks are revealed, **Then** they still arrive one at a time but without movement or flashing.

---

### User Story 2 - A season that ends in playoffs (Priority: P1)

After the draft, the season is replayed as a regular season followed by playoffs. The top six teams by record qualify. Seeds 1 and 2 sit out the first playoff week. Seed 3 plays seed 6 and seed 4 plays seed 5. The next week seed 1 plays the lowest seed left and seed 2 plays the other. The winners play the final the week after. The fan's finish is where the playoffs left them.

**Why this priority**: it changes what "winning" a game means, and it is the format Allan's own league uses. It is independent of the screen work.

**Independent Test**: finish a game and read the results. The regular-season table stops at the last regular-season week; the bracket shows three rounds with scores; the champion is the final's winner; every bracket score equals that team's best-ball score for that week.

**Acceptance Scenarios**:

1. **Given** a finished NBA game in a full-length season, **When** results are shown, **Then** the regular season is weeks 1 to 19, the playoffs are weeks 20, 21 and 22, and no later week is played.
2. **Given** the regular season is over, **When** seeds are set, **Then** they follow wins (a tie counts half), then points for, then the game's seeded coin, the same order the standings already use.
3. **Given** seed 6 beats seed 3 and seed 4 beats seed 5, **When** the second round is drawn, **Then** seed 1 plays seed 6 and seed 2 plays seed 4.
4. **Given** a playoff matchup ends level to the hundredth of a point, **When** a winner is needed, **Then** the better seed advances and the results say so.
5. **Given** the fan finished the regular season 8th, **When** results are shown, **Then** their finish is 8th, the page says they missed the playoffs, and the bracket is still shown.
6. **Given** the fan loses the final, **When** results are shown, **Then** their finish is 2nd.
7. **Given** two teams lose in the same round, **When** final places are set, **Then** the better seed takes the better place (semifinal losers are 3rd and 4th, first-round losers are 5th and 6th). No extra game is played for those places.
8. **Given** the same game is replayed from the same stored inputs, **When** the result is produced twice, **Then** the two results are identical in every field.
9. **Given** a game that was completed before this feature shipped, **When** it is opened, **Then** it shows the result it was given at the time, with standings rank as the finish and no bracket, and a line saying it was played under the old format.

---

### User Story 3 - A draft room built around the picks and the fan's roster (Priority: P2)

The room opens on the draft, not on a rules panel. A pick rail shows the last few picks and the next ones in order, with the fan's picks marked and a count of picks until their turn. A banner that stays in view says who is on the clock. The player list is the main area. The board is one switch away. A roster panel shows the fan's lineup slots filling in as they draft.

**Why this priority**: it makes the pacing in Story 1 worth watching and answers "what do I still need", but Stories 1 and 2 each stand without it.

**Independent Test**: open a game at laptop width and at phone width. Without scrolling, the fan can see who is on the clock, how many picks until their turn, the available players, and their own roster by slot or a single control that opens it.

**Acceptance Scenarios**:

0. **Given** the fan has queued three players and the first is taken by a computer team, **When** that pick is revealed, **Then** he leaves the queue at that moment and the other two keep their order; on the fan's turn, Auto-pick says it will take the top queued player and does.
1. **Given** a game in progress at a 1366×768 window, **When** the room loads, **Then** the on-the-clock banner, the pick rail and at least five available players are visible without scrolling.
2. **Given** the fan is not on the clock, **When** they look at the banner, **Then** it names the team picking and says how many picks remain until the fan's turn.
3. **Given** the fan has drafted three players, **When** they look at the roster panel, **Then** each lineup slot shows the player who would fill it or is shown empty, and extra players are listed as bench.
4. **Given** the fan wants the rules, **When** they open "what counts this season", **Then** the same disclosure as today appears: counted stats, excluded stats with reasons, data notes, board order and attribution. Nothing in it is removed.
5. **Given** the room is open on a phone, **When** the fan switches between players, board, picks, queue and roster, **Then** each is reachable with one tap and the page does not scroll sideways.
6. **Given** the draft is running, **When** the fan looks at the pick rail, **Then** upcoming picks show the team that will pick, and the fan's own upcoming picks are marked.
7. **Given** the room is used with a keyboard only, **When** the fan tabs through it, **Then** the speed control, skip, the view switches and every draft button can be reached and operated, and a newly revealed pick is announced to a screen reader without stealing focus.

---

### Edge Cases

- **Seasons too short for 19 + 3 weeks** (NBA 1998-99, 2011-12, 2019-20 and 2020-21; measured, see FR-011 and research R3): the playoffs are the last three weeks the season has and the regular season is every week before them. The results say the season was short. *(Allan, clarify 2026-11-13.)*
- **Football**: an NFL season has 17 or 18 weeks, so 19 regular-season weeks cannot apply. Football plays 14 regular-season weeks and playoffs in weeks 15 to 17. Week 18 is unused in the seasons that have it. *(Allan, clarify 2026-11-13.)*
- **Weeks after the final**: a full NBA season has more than 22 weeks. Those weeks are not played and do not appear in the results. The results say how many real weeks were left unused.
- **A playoff team whose whole roster had no game that week**: it scores 0 for the week, as in the regular season.
- **The fan presses "Auto-finish the draft" during a reveal**: the reveal ends and the draft finishes; the result appears as it does today.
- **The fan presses Auto-pick on their turn**: their own pick appears at once, then the computer picks reveal at the chosen speed.
- **A request fails mid-draft**: the room shows the error and the last true state. No pick is shown that the server did not make.
- **The last pick of the draft is a computer pick**: the remaining picks reveal, then the results appear. Skip jumps straight to the results.
- **An in-progress game started before this feature ships**: it finishes under the new format, because its season has not been replayed yet.
- **Hindsight, bust and steal comparisons**: they keep comparing whole-season value over every week of the real season, as today. The results say that these cover the full season while the standings stop at the playoffs.

## Requirements *(mandatory)*

### Functional Requirements

**Pick pacing**

- **FR-001**: After the fan's pick, the computer picks up to the fan's next turn MUST appear one at a time, in pick order. The computer picks made before the fan's first turn MUST be paced the same way when the fan first opens a game they have not picked in yet. *(Amended after review 2026-11-13, PR-10: a new game arrives with up to 11 picks already made, and they would have landed in one frame.)*
- **FR-002**: A player MUST NOT be shown as taken anywhere in the room (available list, board, pick rail, roster counts) before his pick has been revealed.
- **FR-003**: The fan MUST be able to choose a reveal speed from four settings: slow, normal, fast and instant. Normal is the default and is about one second per pick. The exact intervals are hand-set and MUST be labelled as such where they are defined.
- **FR-004**: The fan MUST be able to skip the rest of a reveal at any time.
- **FR-005**: The chosen speed MUST be remembered in the fan's browser across games. If it cannot be stored, the room MUST still work at the default.
- **FR-006**: The fan MUST NOT be able to draft or auto-pick while a reveal is running, other than by skipping first. Adding to and reordering the queue MUST stay possible during a reveal. Auto-finish MUST remain available. *(Amended after review 2026-11-13, PR-11: it said "queue a second pick", which contradicted FR-031.)*
- **FR-007**: A page reload MUST always show the true state of the draft. Pacing is presentation only and MUST NOT change which picks are made, in what order, or by which rule.
- **FR-008**: With reduced motion requested, picks MUST still be paced but without animation.

**Playoffs**

- **FR-009**: The replay MUST split the season into a regular season and three playoff weeks. For a full-length NBA season the regular season is weeks 1 to 19.
- **FR-010**: For football the regular season MUST be weeks 1 to 14 and the playoffs weeks 15, 16 and 17. Week 18, where the season has one, MUST NOT be played.
- **FR-011**: When a season has fewer weeks than its sport's regular season plus three, the playoffs MUST be its last three weeks and the regular season every week before them, provided that leaves at least one full round robin (teams minus one weeks, 11 for 12 teams). A season shorter than that MUST be played without playoffs, as a full round robin with the standings rank as the finish, and the results MUST say the season was too short for playoffs. The final of a short season can fall in a week with only a few real game days; the results MUST NOT hide that the season was cut short. *(Amended after review 2026-11-13, PR-03 and PR-08: the floor was unstated and the no-playoffs case had no wording. Measured 2026-11-13 from a fresh import (research R3): the short NBA seasons are 1998-99 (14 weeks, so 11 + 3), 2011-12 (19, so 16 + 3), 2019-20 and 2020-21 (21 each, so 18 + 3). None is too short for playoffs.)* The results MUST say the regular season was shortened and to how many weeks. The regular-season lengths (19 and 14) are league-format choices, not measurements, and MUST be labelled so where they are set.
- **FR-012**: The regular season MUST keep today's round-robin schedule and today's standings order (wins with ties as half, then points for, then the seeded coin), counted over regular-season weeks only.
- **FR-013**: The top six teams in those standings MUST qualify, seeded 1 to 6. Seeds 1 and 2 MUST NOT play in the first playoff week.
- **FR-014**: Round one MUST be 3 v 6 and 4 v 5. Round two MUST be seed 1 against the lowest seed remaining and seed 2 against the other. Round three MUST be the final between the round-two winners.
- **FR-015**: A playoff team's score for a week MUST be the same best-ball score the regular season would give it for that week. A level score MUST send the better seed through, and the results MUST mark that matchup as decided by seed.
- **FR-016**: Final places MUST be: champion 1st, final loser 2nd, semifinal losers 3rd and 4th by seed, first-round losers 5th and 6th by seed, and the other teams in regular-season standings order from 7th.
- **FR-017**: The results MUST show the regular-season standings with a visible line between 6th and 7th, the bracket with both scores for every matchup, the champion, and the fan's final place in words ("Champion", "Lost the final", "Lost in the semifinal", "Lost in round one", "Missed the playoffs, 8th").
- **FR-018**: The fan's week-by-week lineups MUST be shown for every week the fan's team played, playoff weeks included, and not for weeks it did not play (a bye week or after elimination).
- **FR-019**: The same stored game MUST always replay to an identical result: the replay's own output is byte-identical, and what is read back from storage is the same JSON value. *(Amended after review 2026-11-13, PR-09: the result column is `jsonb`, which does not keep bytes or key order, so "byte-identical" can only be promised for the replay's output.)*
- **FR-020**: A game completed before this feature MUST keep and show its stored result unchanged, labelled as the old format. It MUST NOT be re-scored.
- **FR-021**: Any list or total that ranks finished games across formats (a fan's past games, a best finish) MUST NOT present an old-format standings rank and a new-format playoff finish as the same kind of number without saying which is which.
- **FR-022**: The results MUST say plainly that weeks after the final were not played and that the draft comparisons cover the whole real season.

**Draft screen**

- **FR-023**: The room MUST show a pick rail with recent picks and upcoming picks in order, the fan's picks marked, and the current pick marked.
- **FR-024**: The room MUST show, in a position that stays in view, who is on the clock and how many picks remain until the fan's next turn, or that it is the fan's turn.
- **FR-025**: The available-player list MUST be the main area during the draft. The full board MUST be reachable with one control and MUST keep its current content.
- **FR-026**: The room MUST show the fan's roster arranged by lineup slot, using the app's existing lineup rules for the sport, with unfilled slots shown as empty and the rest as bench. It is a display of the roster so far, not a prediction.
- **FR-027**: The "what counts this season" disclosure MUST be collapsed by default during the draft and MUST keep all of its current content when opened.
- **FR-028**: The room MUST work at phone width without sideways scrolling, with players, board, picks, queue and roster each one tap away.
- **FR-029**: Every new control MUST be keyboard-operable with a visible focus state, and revealed picks MUST be announced politely to assistive technology.
- **FR-031**: The fan MUST be able to add any available player to a personal queue and remove him, from the player list, and see the queue as its own view in queue order with the ability to reorder it.
- **FR-032**: A queued player MUST leave the queue at the moment his pick is revealed (FR-002 applies to the queue too), and the fan MUST be able to draft straight from the queue on their turn.
- **FR-033**: When the fan presses Auto-pick with at least one queued player still available, the top queued player MUST be the pick, and the control MUST say so before it is pressed. With an empty queue Auto-pick behaves as today. Auto-finish ignores the queue and MUST say so.
- **FR-034**: The queue MUST be kept per game in the fan's browser and survive a reload. If it cannot be stored the room MUST still work without it.
- **FR-030**: The screen MUST use the app's existing visual language (tokens, position colours, card and avatar treatment) and MUST work for both basketball and football games.

### Key Entities

- **Reveal**: the browser's view of how many of the server's picks the fan has been shown so far. Not stored on the server.
- **Queue**: the fan's ordered shortlist for one game. Stored in the browser only.
- **Reveal speed**: the fan's chosen pacing. Stored in the browser only.
- **Season format**: for one replayed game, how many weeks were regular season, which weeks were playoffs, and which format version produced the result. Stored with the result so old and new games can be told apart.
- **Seed**: a playoff team's regular-season rank, 1 to 6.
- **Playoff matchup**: round, week, the two seeds, both scores, the winner, and whether seed decided it.
- **Final place**: each team's finishing position after the playoffs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At the normal setting, the computer picks between two of the fan's turns in a 12-team draft take between 0.5 and 1.5 seconds each, measured in a running browser, and never arrive two in one frame.
- **SC-002**: In a full draft watched at normal speed, there is no moment where a player appears as taken before his pick is shown. Checked by an automated test over every reveal step of at least one full draft and by eye in the browser.
- **SC-003**: Skip lands the room in a state identical to waiting for the full reveal.
- **SC-004**: For at least one finished game per sport, every bracket score is checked against that team's weekly best-ball score and matches to the hundredth, and the bracket's pairings are worked out by hand from the standings and match.
- **SC-005**: Replaying the same stored game twice gives byte-identical output from the replay, and equal JSON once stored.
- **SC-006**: Every game completed before the change still opens and shows the same finish it showed before. Checked against a list of finishes captured before the change.
- **SC-007**: At 1366×768 the fan sees who is on the clock, picks until their turn and at least five available players without scrolling. At 375 px wide the page has no sideways scroll and each of the five views is one tap away.
- **SC-008**: A fan can complete a whole draft at instant speed in no more time than today.
- **SC-009**: Both a basketball game and a football game are drafted and finished end to end in the real app after the change.

## Assumptions

- Time Machine keeps its current shape: 12 teams, a plain snake draft, one fan against computer teams, best ball with no in-season moves.
- "Top 6 after 19 weeks, top 2 on a bye, week-by-week elimination" means three single-elimination weeks with reseeding in round two (the lowest remaining seed plays seed 1). There is no third-place game and no consolation bracket.
- Pacing is done in the browser over picks the server has already made. The server keeps making computer picks in one request. A reload therefore shows picks the reveal had not reached yet; that is accepted.
- The reveal intervals are a matter of feel. They are set by hand and can be retuned after Allan plays with them; they are not derived from anything.
- A player queue is in scope *(Allan, clarify 2026-11-13)*. No pick clock for the fan, and no undo or restart in this feature. The queue lives in the browser, so it does not follow the fan to another device.
- No change to how computer teams choose players (spec 062's bot strength work stands).
- No change to the Time Machine start page or to the league mock draft and live draft rooms, which share some components. Shared components may gain options but their behaviour in those rooms must not change.
- The fan's finish number is used elsewhere in the app only where a finished game is listed; FR-021 covers those places.

## Clarifications

### Session 2026-11-13

- Q: Football regular-season length? → A: 14 regular-season weeks, playoffs in weeks 15 to 17, week 18 unused.
- Q: NBA seasons too short for 19 + 3 weeks? → A: shrink the regular season; playoffs are the last three weeks.
- Q: Add queue, undo or restart? → A: player queue only.

## Queued for later *(Allan, 2026-11-13, sent while the plan was in review)*

"The bones are there but there needs to be an ease and fun factor here. I like the missed opportunities and
who you got right and should head that direction."

Not added to this feature's scope. Recorded so the next Time Machine spec starts from it: the results should
lead with the story of the draft (what the fan got right, what they missed) rather than with tables, and the
room should be easy before it is complete. Within 063 this only tips ties: where a layout or wording choice is
open, pick the lighter, more playful one, and keep the existing bust and passed-on-steal callouts prominent on
the new results page.
