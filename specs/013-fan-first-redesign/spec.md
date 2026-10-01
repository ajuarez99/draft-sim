# Feature Specification: Fan-first redesign

**Feature Branch**: `013-fan-first-redesign`

**Created**: 2026-09-30

**Status**: Draft

**Input**: User description: "i want you as a designer to go through all the site every page and come up with ways to imporve the looks on the page and the whole layout to make it look less engineery and more for a football fantasy fan looking to gain an edge. suggest any color inuput how to navigate and if we need less boxes to mkae the site flow better". The page-by-page review that came out of that request, and its five clarifications, are in `claude/design-review-fan-first.md`. That doc is the design source for this spec. Visual references: FantasyPros rankings, ffwrapped standings, DraftSharks rankings (links in that doc).

## Scope in one line

**Every page of the site, both sports, three screen sizes.** Scope covers the look,
the wording, navigation and a small set of fan-facing additions built from data the
app already has. No new models or statistics are invented, and no page address
changes.

## Clarifications

Carried over from the clarify session on the design review (2026-09-30). Every
answer came from the user.

### Session 2026-09-30

- Q: Does this spec cover all three phases of the review, or only the first? → A: All three, in one spec on one branch, built in phase order with each phase live-checked before the next.
- Q: How does the site decide who is a league's commissioner? → A: Sleeper's own commissioner flag for that league. It decides who **sees** commissioner controls. Running them still needs the existing commissioner key, because signing in here is a typed username, not a password.
- Q: What screen sizes must the redesign work at? → A: Desktop (1440×900), tablet (768×1024) and phone (375×812), each checked separately.
- Q: Does the redesign apply to NBA leagues too? → A: Yes. Both sports get every change and are checked separately. Player photos and team logos were measured as available for both sports on 2026-09-30.
- Q: How are letter grades decided? → A: By a team's rank within its own league, not by fixed score thresholds. The cutoffs are hand-set and labelled arbitrary. The grade carries the existing "early — this is mostly noise" badge until the season's existing early threshold (4 scored weeks) is reached, and the number is always shown beside it.

## Amended after adversarial review (2026-09-30)

A cold-read review of the plan, before any code, changed FR-011, FR-012, FR-016, FR-017, FR-021, FR-025, FR-027, FR-030, FR-031, SC-001, SC-005, SC-007 and US2's scenarios. Each is edited in place and marked. The full decision table is in [plan.md](plan.md) "Amended after adversarial review".

## Amended after planning (2026-09-30)

Planning checked the spec against the code and production. It found four things
the spec had wrong or didn't know. The spec below has been changed in place to
match, and each change is listed here (details in [research.md](research.md)):

1. **Commissioner controls were mostly hidden already.** Power rankings and
   Playoff odds already show their recompute buttons only to the commissioner.
   The design review saw them because it was run signed in *as* the commissioner.
   The real gap is History's **Compute** button, which every member sees.
   "Commissioner" also includes the app's configured owner, which is what the
   server already does. (US2, FR-009)
2. **Mock drafts can't show next-pick availability.** They run no simulation, so
   there's no availability figure to show. The "Your pick" panel ships in mocks
   with tiers and photos, and says why the bar is missing. (US6, FR-020, SC-006)
3. **The weekly report opening on week 2 is deliberate.** Measured: it opens on the
   latest *final* week, and week 3 is scored but not final. The requirement is the
   label, which was already FR-019. Why week 3 isn't final yet is out of scope.
4. **The existing "you" color fails contrast.** White on the crimson fill measures
   4.07:1, under FR-036's 4.5:1. FR-036 already covered it; it's named here so it
   isn't missed.

## Who this is for

- **Fan**: a signed-in league member. They want the answer ("am I good, am I lucky,
  who do I take") before the method.
- **Commissioner**: a fan whom Sleeper marks as that league's commissioner, or the
  app's configured owner (amended after planning). Sees the maintenance controls
  (compute, recompute) that nobody else should see.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every page reads like a fan tool, not an engineering console (Priority: P1)

A fan opens any page and sees one clean surface: tables sit on the page, section
titles are plain headings, numbers are easy to read, and the subtitle tells them the
takeaway in one sentence. The explanation of *how* a number was made is one tap away
under "How this works", not in the subtitle. Any caveat that changes how to read a
number is still right beside that number.

**Why this priority**: This is the heart of the request ("less engineery", "less
boxes") and it touches every page. It changes presentation only, so the risk is low.

**Independent Test**: Visit every page in the route inventory below. Count the nested
bordered surfaces. Read each subtitle. Open each "How this works". Compare the list of
caveats against the pre-change inventory: none may be missing.

**Acceptance Scenarios**:

1. **Given** any page, **When** it loads, **Then** no content sits inside more than two nested bordered surfaces (the page, plus at most one raised surface).
2. **Given** any page subtitle, **When** read, **Then** it is one sentence that states the takeaway and names no implementation step (for example "stored simulation", "computation", "standard error").
3. **Given** a methodology paragraph that was in a subtitle or section intro before the change, **When** the page is viewed after the change, **Then** the same content is available under a "How this works" disclosure in that section.
4. **Given** a caveat badge that qualifies a specific number (such as "early — this is mostly noise"), **When** the page is viewed, **Then** the badge still sits beside that number.
5. **Given** the site's colors, **When** any page is viewed, **Then** each color keeps a single meaning across the site: your own team/seat, interaction (hover/focus/selected), the main action on the page, better vs worse, and awards.

---

### User Story 2 - Only commissioners see commissioner controls (Priority: P1)

A regular fan never sees a "Compute" or "Recompute" button. The league's
commissioner, as Sleeper records it (or the app's configured owner), does see them.
Where running one asks for the commissioner key today, it still does.

**Why this priority**: History's Compute button is visible to every member today
(amended after planning: Power's and Playoff odds' buttons were already hidden). They confuse fans and invite key prompts that
lead nowhere.

**Independent Test**: As a signed-in user who is not the commissioner of a league,
visit every page of that league: no compute/recompute control appears. As
that league's commissioner, the controls appear and still require the key.

**Acceptance Scenarios**:

1. **Given** a signed-in user whom Sleeper does not mark as commissioner of league X, **When** they view any page of league X, **Then** no compute or recompute control is shown.
2. **Given** a signed-in user whom Sleeper marks as commissioner of league X, **When** they view league X's history or playoff odds page, **Then** the controls are shown. Where a control needed the commissioner key before, it still asks for it; History's Compute never did and still doesn't (amended after adversarial review).
3. **Given** a user who is commissioner of league X but not league Y, **When** they view league Y, **Then** no commissioner control is shown.
4. **Given** the playoff odds page has no odds yet, **When** a fan views it, **Then** it says in plain language what makes odds appear ("Odds appear when the commissioner updates them."), with no recompute control. It never promises a date the app can't keep (amended after adversarial review).

---

### User Story 3 - Navigation grouped by what a fan is doing (Priority: P2)

The league menu is grouped into This week, The season, Draft and History, with plain
fan names ("Luck" rather than "Expected wins", "Bench points" rather than "Roster
management"). The league and season switcher is always at the top. Every menu item
has a readable label or an icon with a hover/long-press label, including in the draft
views, where today the menu shrinks to unlabeled dots.

**Why this priority**: Ten flat, equally weighted links are the second biggest source
of the "engineery" feel, and the draft views' unlabeled dots are unusable.

**Independent Test**: From each league page, reach every other league page. Check
that every menu item can be identified by its text or its label at all three screen
sizes.

**Acceptance Scenarios**:

1. **Given** any league page, **When** the fan opens the menu, **Then** the items appear in the four groups with fan names, and the group holding the current page is expanded.
2. **Given** any league page, **When** the fan wants another league page, **Then** they reach it in at most two clicks or taps.
3. **Given** a draft view, **When** the menu is collapsed to make room for the board, **Then** every item is still identifiable by an icon plus a label on hover or long-press.
4. **Given** any existing page address or bookmark, **When** opened after the change, **Then** it reaches the same page.

---

### User Story 4 - A league home that starts with you (Priority: P2)

After picking a league, the fan lands on a league home that leads with their own
team: record, rank, this week's matchup and opponent, then a short standings view,
the current power-rankings headline and the newest award. The site-wide home lists the
fan's leagues as rows with their record and rank, each with one primary action.

**Why this priority**: The app already knows who the fan is. The references' most
personal move ("which team is yours?") is one we can skip straight past.

**Independent Test**: Sign in as a league member and open the league home. Your
record, rank and this week's opponent are visible without scrolling at all three
sizes.

**Acceptance Scenarios**:

1. **Given** a signed-in fan in a league whose season has started, **When** they open the league home, **Then** their record, rank and this week's opponent are visible on the first screen.
2. **Given** a signed-in user who is not a member of the league being viewed, **When** they open the league home, **Then** it shows the league-wide view (standings, headline, award) with no "your team" section and no error.
3. **Given** a league whose season has not started, **When** the fan opens the league home, **Then** it shows the draft status and the draft entry point instead of a matchup.
4. **Given** the site-wide home, **When** it loads, **Then** each league is one row showing sport, the fan's record and rank (when available) and one primary action. Season pickers and refresh live on the league's own pages.

---

### User Story 5 - The weekly report leads with your matchup (Priority: P2)

The weekly report opens on the fan's own matchup, shown as a scoreboard (both
managers, big scores, clear win/loss). The rest of the week follows as a compact
scoreboard strip, then a single "week in review" list of awards and top performers.
The week is chosen with a previous/next stepper, and the page says which week it is
showing and why.

**Why this priority**: The weekly report is the page fans revisit every week, and
today your matchup is one box among six.

**Independent Test**: Open the weekly report as a league member. Your matchup is
first. Step back and forward one week. The label always names the week shown.

**Acceptance Scenarios**:

1. **Given** a fan with a matchup in the shown week, **When** the report loads, **Then** their matchup is first and full width.
2. **Given** the report opens on a week other than the calendar's current week, **When** it loads, **Then** the label says which week it shows and why (for example "Week 3, latest complete week").
3. **Given** the first or last available week, **When** the fan uses the stepper, **Then** the unavailable direction is disabled rather than erroring.

---

### User Story 6 - "Who should I take?" stays on screen in the draft room (Priority: P3)

In the draft simulator, the live draft room and mock drafts, whenever the fan is on
the clock or next up, a "Your pick" panel shows the best available players grouped
into tiers. Each player shows their photo, position rank and a bar giving their
chance of still being available at the fan's next pick. Opening a modal is no longer
the only way to see it. Recent position runs ("4 of the last 6 were PG") appear as a
callout. While simulations run, the board shows a loading state with a progress
message instead of a blank panel.

**Why this priority**: The availability curve is the one thing no reference site
has, and it is currently hidden behind a button. This is P3 only because it depends on
the look (US1) and player images (US7) landing first.

**Independent Test**: Start a mock draft. When on the clock, without opening
anything, read the top available players' tiers and next-pick availability at all
three screen sizes.

**Acceptance Scenarios**:

1. **Given** the fan is on the clock and their seat is known, **When** the draft room is shown, **Then** the best available players, their tiers and their next-pick availability are visible without opening a modal (on a phone: one tap on a persistent control, with the board still visible behind it). *Amended after planning:* in a mock draft, which runs no simulation, the panel shows tiers and photos and says why availability is missing.
2. **Given** the fan's seat is not known yet, **When** the panel is shown, **Then** it lists best available players and says availability appears once their seat is known, instead of showing empty bars.
3. **Given** the simulator page is opened, **When** simulations are still running, **Then** a loading state with a progress message appears within 1 second, and the panel is never blank.
4. **Given** the fan's last pick of the draft, **When** there is no next pick, **Then** the availability bar is omitted rather than shown as 0%.

---

### User Story 7 - Players have faces (Priority: P3)

Everywhere a player is named in a list, board or card, their photo (or team logo) is
shown, for NFL and NBA. Team defenses show the team logo. If an image can't be
loaded, the team logo is shown, then the player's initials. A broken image is never
shown.

**Why this priority**: This is the single biggest visual difference between us and
the references. It needs no new data, only images.

**Independent Test**: Browse a completed NFL draft board and an NBA mock draft. Every
player cell shows a photo, logo or initials, and no image is broken.

**Acceptance Scenarios**:

1. **Given** a player with a photo available, **When** their row or cell is shown, **Then** the photo appears.
2. **Given** a team defense, or a player whose photo fails to load, **When** shown, **Then** the team logo appears, or the initials if the logo also fails.
3. **Given** an NBA league, **When** any player list is shown, **Then** NBA photos and logos behave exactly as NFL ones do.

---

### User Story 8 - Quick-read grades and verdicts (Priority: P3)

Team strength and Bench points show a letter grade (A+ to F) beside the number, based
on the team's rank within its league. Early in the season the grade carries the
"early — this is mostly noise" badge. A finished draft board can be switched to a
"steals and reaches" view that tints each pick by how far it went before or after its
ADP.

**Why this priority**: These give the fastest possible read, but they risk looking
more certain than the data allows. They go last, and the honesty guardrails below are
part of the requirement.

**Independent Test**: With a league in week 3 and then week 4, check that grades
follow the ranking exactly, that the early badge is present at 3 scored weeks and gone
at 4, and that the number is always beside the grade.

**Acceptance Scenarios**:

1. **Given** two teams where team A ranks above team B, **When** grades are shown, **Then** A's grade is never lower than B's.
2. **Given** fewer than 4 scored weeks, **When** a grade is shown, **Then** it carries the early badge. **Given** 4 or more, **Then** it does not.
3. **Given** a finished draft where a pick has no ADP recorded, **When** the steals/reaches view is on, **Then** that cell is left untinted and says there is no ADP, rather than being counted as neither a steal nor a reach.

---

### User Story 9 - Fan-shaped versions of the remaining pages (Priority: P3)

The other pages get the fan treatment described page by page in the design review:
- **Superlatives** becomes a single trophy list, one row per award.
- **The manager profile** gets a player-card header, with league ranks written as a sentence.
- **The scouting report** (managers page) leads with plain archetype labels and groups the fan's own league first.
- **Standings** drops the repeated "season in progress" text, marks the best and worst value in each column, and adds "record vs all" and "median record".
- **Mock setup** is one row of seat chips.
- **Sign-in** is a centered welcome with one line of pitch.

**Why this priority**: These are valuable but independent page reworks that each
reuse the rules from US1.

**Independent Test**: Each page is checked against its section of the design review,
at all three sizes and in both sports.

**Acceptance Scenarios**:

1. **Given** the superlatives page, **When** loaded, **Then** each award is one row with its winner and stat, and "see all" expands in place.
2. **Given** a manager profile, **When** loaded, **Then** name, career record, titles and three to four headline stats appear in one row on the first screen, with the methodology under "How this works".
3. **Given** the scouting report, **When** loaded, **Then** every manager with a draft history shows an archetype label, and managers who share a league with the fan are listed first.
4. **Given** standings for a season in progress, **When** loaded, **Then** "season in progress" appears once, not on every row.

---

### Edge Cases

- **A signed-in user who isn't a member of the league being viewed**: every "you" element (your team strip, your matchup, your pick) is left out, and nothing errors.
- **A league with co-commissioners**: every member Sleeper marks as commissioner sees the controls. Whether Sleeper marks co-commissioners at all is unverified; the one league measured has a single commissioner.
- **The Sleeper commissioner flag is missing for a league** (not yet ingested or not sent): nobody sees the controls in the UI. The commissioner key path is unchanged, so no ability is lost.
- **A player or team with no image at all**: initials, never a broken image.
- **A season with no scored weeks**: grades, luck and bench points show an empty state that says when they will appear, not zeros.
- **A draft older than the ADP snapshot window**: its picks have no ADP (a known, permanent data gap). The steals/reaches view says so instead of tinting.
- **Very long team names** (emoji, 30+ characters, as in the real leagues): truncate with the full name available on hover or long-press, at every size, without widening the page.
- **Phone width in the draft room**: the board must stay usable while the "Your pick" panel is available; the panel must never cover the board completely with no way to close it.

## Requirements *(mandatory)*

### Functional Requirements

**Look and wording (US1)**
- **FR-001**: Every page MUST show content inside at most two nested bordered surfaces: the page, and at most one raised surface.
- **FR-002**: Tables and lists MUST sit directly on the page or the single raised surface, with row separators rather than per-row boxes. Cards are used only for card-shaped things (a matchup, a player).
- **FR-003**: Each color MUST have one site-wide meaning: your own team/seat; interaction (hover, focus, selected); the main action on the page (one per page); better vs worse; awards/earned. Position colors keep their current meaning.
- **FR-004**: Numbers MUST be displayed in the site's regular type with aligned digits, not a code-style font.
- **FR-005**: Every page subtitle MUST be one sentence stating the takeaway, and MUST NOT describe implementation steps.
- **FR-006**: Every methodology explanation removed from a subtitle or section intro MUST remain available under a "How this works" disclosure in the same section.
- **FR-007**: Every caveat that qualifies a specific number MUST remain visible beside that number. No caveat present before the change may be removed.
- **FR-008**: Section titles MUST be plain headings. The display face is reserved for page titles and hero numbers.

**Commissioner controls (US2)**
- **FR-009**: The site MUST show commissioner-only controls (compute, recompute, and any other control that writes league-level data on the commissioner's behalf) only to a signed-in user whom Sleeper marks as commissioner of that specific league, or to the app's configured owner. The server already decides this, and the page must use its answer rather than work it out again.
- **FR-010**: Server-side gating is unchanged. Controls that require the commissioner key today still do, and History's Compute keeps its current server rule (amended after planning, research R1). Showing a control grants nothing by itself.
- **FR-011**: Empty states that today offer a commissioner control MUST give non-commissioners a plain-language message saying what makes the content appear. Example: "Odds appear when the commissioner updates them." / "Final ranks appear once the commissioner computes them." The message must never promise a time the app does not control (amended after adversarial review).

**Navigation (US3)**
- **FR-012**: League navigation MUST be grouped as This week / The season / Draft / History, using fan-facing page names (see Key Entities: Page names). History holds Standings (the all-seasons page). The group holding the current page is expanded, and on phone every group is expanded so nothing takes an extra tap. Page titles use the same fan names (amended after adversarial review).
- **FR-013**: The league and season switcher MUST be visible at the top of every league page.
- **FR-014**: Every navigation item MUST be identifiable by visible text, or by an icon plus a hover/long-press label, at every screen size, including in draft views.
- **FR-015**: No existing page address may change or stop working.

**League home and site home (US4)**
- **FR-016**: The site MUST provide a league home that leads with the signed-in fan's record, standings position and latest matchup (both sports), plus their next opponent where the app knows it (NFL), followed by a standings snippet, the current power-rankings headline and the newest award (amended after adversarial review).
- **FR-017**: The site-wide home MUST list leagues as one row each (sport, season, draft status, one primary action). *Amended after adversarial review:* no per-league record/rank, because no response carries it without one request per league.

**Weekly report (US5)**
- **FR-018**: The weekly report MUST show the signed-in fan's matchup first when they have one in the shown week.
- **FR-019**: The weekly report MUST offer previous/next week navigation and MUST label the shown week and why it was chosen.

**Draft room (US6)**
- **FR-020**: In the simulator, live draft room and mock drafts, when the fan is on the clock or next up, the best available players, grouped into tiers, MUST be visible without opening a modal (on a phone: one tap on a persistent control). Next-pick availability MUST be shown wherever the room has an availability figure (simulator, live room). A mock draft has none, and MUST say so instead (amended after planning).
- **FR-021**: The simulator MUST show a skeleton board within 1 second of opening while its seats load, and keep its existing progress overlay while a simulation runs. It MUST NOT claim simulations are running before the fan presses Start (amended after adversarial review).
- **FR-022**: Recent position runs already detected by the draft room MUST be shown as a callout next to the pick panel.

**Player images (US7)**
- **FR-023**: Every player named in a list, board cell or card MUST show a photo. It falls back to the team logo, then initials. A broken image is never shown. This applies to NFL and NBA.
- **FR-024**: Team defenses MUST show the team logo.

**Grades and verdicts (US8)**
- **FR-025**: Team strength (NFL only, since the page is NFL-only) and Bench points (both sports) MUST show a letter grade (A+ to F) derived only from the team's rank within its league. The rank-to-grade cutoffs are a hand-set value, labelled arbitrary where the other hand-set values are kept. If the cutoffs are missing, no grade is shown and the app still starts (amended after adversarial review).
- **FR-026**: A grade MUST always be shown beside its number and MUST carry the "early — this is mostly noise" badge while fewer scored weeks exist than the season's existing early threshold. The same threshold is reused, not duplicated.
- **FR-027**: A finished draft board MUST offer a steals/reaches view that tints each pick by its distance from **the ADP recorded at draft time**, never today's ADP. Picks without a draft-time ADP stay untinted and are labelled "no ADP at draft time" (amended after adversarial review).

**Remaining pages (US9)**
- **FR-028**: Superlatives MUST present one row per award with winner, stat and an in-place "see all".
- **FR-029**: The manager profile MUST open with a single-row header (name, career record, titles, 3–4 headline stats), with league ranks written as sentences.
- **FR-030**: The scouting report MUST show an archetype label per manager with draft history, derived from the existing reach read and positional tendencies with hand-set, labelled cutoffs. Managers in the fan's currently selected league come first, and the existing `(±n)` uncertainty stays visible (amended after adversarial review).
- **FR-031**: Standings MUST mark the best and worst value in each numeric column, show "season in progress" once, and add regular-season "record vs all" and "median record" columns, filled only for the season those figures were actually computed for (amended after adversarial review).
- **FR-032**: Mock setup MUST present seats as a single row of chips, showing a real manager's avatar and archetype when one is seated.
- **FR-033**: Sign-in MUST be a centered welcome with the product name, one line of pitch ("…see who's likely gone before you pick"), the username field and one primary action. The "no password" reassurance is kept.

**Everywhere**
- **FR-034**: Every changed page MUST work at 1440×900, 768×1024 and 375×812 with no horizontal page scroll and no clipped content.
- **FR-035**: Every changed page MUST work for NFL and NBA leagues.
- **FR-036**: Text and controls MUST meet a 4.5:1 contrast ratio against their background, including text on the new main-action color.

### Key Entities

- **Commissioner flag**: per league member, from Sleeper. Already stored. It decides who sees commissioner controls; it does not authorize anything.
- **Page names**: the fan-facing label for each existing page. Luck = expected wins, Bench points = roster management, Team strength = analysis, Playoff odds = season forecast, Awards = superlatives, Standings = history (current season), Scouting report = managers, Matchups & awards = weekly report. Addresses are unchanged.
- **Letter grade**: a display value derived from a team's rank within its league, with hand-set cutoffs and an "early" state. It is not stored and not a new statistic.
- **Manager archetype**: a plain label ("Reacher", "Waits on QB", "Drafts like the room") derived from existing tendency figures with hand-set cutoffs.
- **Player image**: a photo or team logo for a player, by sport, with a defined fallback order. It carries no new player data.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On every page in the route inventory, outside data grids (the draft board, tables), 0 visible elements sit inside more than two nested surfaces, as measured by the amended harness (quickstart §4a). Inside a data grid, each cell is the only surface inside the grid frame (amended after adversarial review).
- **SC-002**: 100% of page subtitles are a single sentence with no implementation vocabulary, and 100% of the caveats in the pre-change inventory are still present after the change (beside their number, or under "How this works").
- **SC-003**: A non-commissioner sees 0 commissioner controls across every page of a league. The commissioner of that league sees all of them. Measured on "(Foot) Ball Knowers", whose commissioner was confirmed on 2026-09-30.
- **SC-004**: From any league page, any other league page is reachable in at most 2 clicks or taps, at all three screen sizes.
- **SC-005**: On the league home, a member's record, standings position and latest matchup are visible without scrolling at all three screen sizes in both sports; NFL also shows the next opponent (amended after adversarial review).
- **SC-006**: When the fan is on the clock, the tiered best-available list is readable with 0 extra clicks on desktop and tablet and at most 1 tap on phone, in all three rooms. Next-pick availability is readable the same way in the simulator and live room (amended after planning: mocks have none).
- **SC-007**: The simulator shows a skeleton board within 1 second of opening, with 0 seconds of blank panel, and never shows a "running" message before Start (amended after adversarial review).
- **SC-008**: On a full NFL draft board and a full NBA mock board, 100% of player cells show a photo, logo or initials, and 0 broken images appear.
- **SC-009**: Letter grades follow the league ranking with 0 ordering violations, and the early badge is present at 3 scored weeks and absent at 4.
- **SC-010**: Every changed page passes a live check at 3 screen sizes × 2 sports with 0 horizontal page scroll.
- **SC-011**: All text and control labels measure at least 4.5:1 contrast.
- **SC-012**: Every existing page address in the route inventory still opens the same page.

## Route inventory

These are the pages this spec covers, all of which were observed on production on
2026-09-30: sign-in; site home; managers (scouting report); manager profile; manager
comparison; mock setup; mock draft; draft simulator; live draft room; completed draft
board; league standings/history; power rankings; team strength (analysis); bench
points (roster management); luck (expected wins); playoff odds (season forecast);
weekly report; awards (superlatives). The new league home is added.

## Assumptions

- The dark theme stays. The references' structure is adopted, not their light palette (the user's stated preference for a dark, card-and-avatar UI).
- Sleeper's commissioner flag is already ingested per league member. The one league measured has exactly one commissioner; co-commissioner behaviour is unverified.
- Player photos and team logos are available from Sleeper for both sports at stable addresses. This was measured with sample players and teams on 2026-09-30, not for every player.
- "Record vs all" and "median record" can be derived from weekly scores the app already stores. This is not yet verified.
- Archetype and letter-grade cutoffs are new hand-set values, labelled arbitrary, never tuned to make output agree with an expectation.
- The weekly report currently opening on an earlier week than power rankings was observed but not diagnosed. FR-019 requires labelling, whatever the cause turns out to be.
- The manager comparison page was not opened during the review. It is covered by US1's general rules only.
- Delivery follows the review's phase order: look, wording and commissioner controls; then navigation, league home and weekly report; then images, draft room, grades and page reworks. Each phase is live-checked before the next.

## Not in scope

- A light theme.
- Removing or softening any caveat. Relocating one is in scope; deleting one is not.
- New models, statistics or data sources beyond player/team images and the two derived standings columns.
- Changing how the commissioner key works, or adding real authentication.
- Any change to simulation results or the draft engine.
