# Feature Specification: Navigation that doesn't strand you

**Feature Branch**: `003-easier-navigation`

**Created**: 2026-09-17

**Status**: Draft

**Input**: User description: "the site seems a bit hard to get to every feature that the site offers like history or league analysis — plan a way to make navigation easier. not just for this feature but if you see anything else plan that as well"

## Problem

The site has twelve routes and five leagues. Reaching most of them means starting from Home, finding
the right league card, and clicking through — and on three routes the rail drops the league you are
inside, so the way back is the browser's Back button or the wordmark.

This is not a hypothetical. Measured on production (ballknowers.co, 2026-09-17, signed in as
`popsharky`) by reading `nav.app-rail` on each route:

| Route | League section in rail? | Consequence |
|---|---|---|
| `/leagues/:id/history` | yes | — |
| `/leagues/:id/power` | yes | — |
| `/drafts/:id/board` | yes | — |
| **`/leagues/:id/analysis`** | **no** | Rail offers it, then strands you on it |
| **`/mock/:id`** | **no** | A mock seeded from a league forgets the league |
| **`/managers/:id/history`** | **no** | No route back to the league whose table you came from |

The Analysis case is the sharpest: the rail's own League section links to Analysis, and arriving
there deletes the section that linked you. Four of the league's five destinations disappear at the
moment you use the fifth.

A second class of problem is reachability rather than return. `/managers` lists 53 manager profiles
and links to none of their history pages — `/managers/:id/history` is reachable only by clicking a
name inside a League History or League Analysis standings table. And there is no way to move between
leagues without going Home first: the rail shows the league you are in and no other.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The league you are in stays with you (Priority: P1)

A manager opens their league's Analysis page from the rail, reads the roster projections, and wants
to compare against the record book in History. Today the League section is gone and History is not on
screen anywhere. They press Back.

**Why this priority**: It is a defect, not a missing feature — the rail already renders exactly the
right block on three sibling routes and fails to on three others, because one regex lists two of the
six route shapes. It is the cheapest fix with the largest share of the complaint, and every other
story in this spec assumes league context is reliable.

**Independent Test**: Visit each of the six league-scoped routes and assert the rail's League section
is present and marks the current page. Ships and is verifiable with no new UI.

**Acceptance Scenarios**:

1. **Given** a signed-in user on `/leagues/:id/analysis`, **When** the page settles, **Then** the rail
   shows the League section with Draft board, History, Power rankings, Analysis and Mock it, and marks
   Analysis as current.
2. **Given** a user on `/managers/:id/history` reached from a league's standings table, **When** the
   page settles, **Then** the rail shows that league's section and marks no league row current.
3. **Given** a mock draft seeded from a league (`/mock/:id`), **When** the room opens, **Then** the rail
   shows the seeding league's section, so the room the mock came from is one click away.
4. **Given** a mock draft not seeded from any league, **When** the room opens, **Then** no League
   section is shown and nothing is invented.

---

### User Story 2 - Jump to anything from anywhere (Priority: P2)

A manager two clicks deep in one league wants another league's power rankings, or one of 53 manager
profiles. Today that is: rail → Home → scan five cards → the right link. Four steps, and the first one
throws away where they were.

**Why this priority**: It is the largest reduction in steps per destination, and it subsumes several
smaller gaps at once (no league switcher, `/managers` linking to no manager, no way to reach an older
season's board except through a card). It is second only because it is additive — P1 must land first
so that arriving somewhere via the palette leaves you somewhere navigable.

**Independent Test**: Open the palette from any route with a keyboard shortcut, type three characters
of a league, manager or page name, and land on it. Testable without touching the rail.

**Acceptance Scenarios**:

1. **Given** any signed-in route, **When** the user presses the palette shortcut, **Then** a search
   overlay opens with focus in its input.
2. **Given** the palette is open, **When** the user types part of a league name, **Then** that league's
   destinations (board, history, power rankings, analysis where it applies) appear as separate results,
   each labelled with the league it belongs to.
3. **Given** the palette is open, **When** the user types part of a manager name, **Then** that
   manager's profile appears, for every manager the app knows, not only those in the current league.
4. **Given** the palette is open, **When** the user presses Escape, **Then** it closes and focus returns
   to where it was, with no navigation.
5. **Given** a league with several seasons, **When** the user searches it, **Then** each season's board
   is reachable, distinguished by year.

---

### User Story 3 - Switch league without going home (Priority: P3)

A manager in one league's history wants the same page for another league.

**Why this priority**: Real, but narrower than P2 and largely covered by it once the palette exists.
It earns its own story because the rail is where league identity already lives, and a switcher there
serves the person who navigates by pointing rather than typing.

**Independent Test**: From any league-scoped route, open the rail's league control and pick a different
league; land on the equivalent page for that league where one exists.

**Acceptance Scenarios**:

1. **Given** a user on `/leagues/A/history`, **When** they switch to league B, **Then** they land on
   `/leagues/B/history`.
2. **Given** a user on `/leagues/A/analysis` (an NFL league), **When** they switch to an NBA league,
   **Then** they land on that league's History rather than a page that league does not offer.
3. **Given** a draft room with the rail collapsed, **When** the league has more than one season, **Then**
   the seasons are still reachable — today they are hidden exactly where they are most wanted.

---

### User Story 4 - Navigation you can get back to on a phone (Priority: P4)

On a phone the rail becomes a horizontal bar that scrolls away with the page. Getting back to
navigation from the bottom of a long standings table means scrolling to the top.

**Why this priority**: Last because the current behaviour is a deliberate, measured trade, not an
oversight — `styles.css` records that pinning the bar cost 252px of an 812px viewport and judges "a
third of the viewport spent permanently on navigation is worse than navigation you scroll back up to
reach." That reasoning stands. What is missing is only the return trip.

**Independent Test**: At 375×812, scroll to the bottom of a long page and reach another page without
scrolling back up.

**Acceptance Scenarios**:

1. **Given** a phone-width viewport scrolled well down a page, **When** the user wants another
   destination, **Then** a control to reach navigation is on screen without scrolling up.
2. **Given** that control is used, **When** navigation opens, **Then** it costs no permanent viewport
   band when closed.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every route that belongs to a league MUST show that league's context in the rail. The set
  of such routes MUST be derived from one declaration, so that adding a league-scoped route cannot
  silently omit it.
- **FR-002**: A league-scoped route MUST mark the current destination in the rail.
- **FR-003**: A mock draft session MUST show its seeding league's context when it was seeded from a
  league, and no league context when it was not.
- **FR-004**: A search overlay MUST be reachable from every signed-in route by keyboard and by a visible
  control, and MUST offer leagues, their per-league destinations, seasons, and managers.
- **FR-005**: Search results MUST state which league each destination belongs to, so two leagues with a
  "History" page are distinguishable.
- **FR-006**: Search MUST NOT offer a destination the target does not have (for example Analysis for a
  basketball league).
- **FR-007**: The `/managers` list MUST link each manager to their profile page.
- **FR-008**: A league switcher MUST preserve the current destination when the target league has it, and
  fall back to that league's History when it does not.
- **FR-009**: Season links for a multi-season league MUST be reachable when the rail is collapsed.
- **FR-010**: At phone width, navigation MUST be reachable from a scrolled position without a permanent
  viewport band.
- **FR-011**: No route may lose the user's ability to reach Home, their leagues, or the page they came
  from.

### Non-Functional Requirements

- **NFR-001**: Opening the search overlay MUST NOT issue a network request on a warm cache; it reads the
  league list the rail already caches.
- **NFR-002**: The rail MUST NOT refetch the league list when moving between routes of one league.
  (Already true via the module-scope cache in `railLeague.ts`; this feature must not regress it.)
- **NFR-003**: The overlay MUST be operable by keyboard alone: open, type, arrow, Enter, Escape.
- **NFR-004**: Nothing in this feature may add a fixed element to a draft room's horizontal space — the
  board already overflows a 1440px viewport by 99px with no rail at all.

### Key Entities

- **Destination**: a reachable page — a URL, a label, the league or manager it belongs to, and whether
  the current sport offers it.
- **League context**: the league a URL is inside, the season being viewed, and that league's set of
  destinations.
- **Search index**: the flattened list of destinations across every league, season and manager the
  signed-in user can see.

## Success Criteria *(mandatory)*

- **SC-001**: All six league-scoped routes show league context. Measured by visiting each and asserting
  the League section is present — today three of six fail.
- **SC-002**: Any destination in the app is reachable in at most two actions from any other (open
  palette, choose) — today the worst case is four and passes through Home.
- **SC-003**: `/managers` links to all 53 manager profiles; today it links to none.
- **SC-004**: Seasons are reachable from a collapsed rail; today they are not.
- **SC-005**: No route regresses: the league list is fetched at most once per session on a warm cache.

## Out of Scope

- Player search. The palette indexes navigation (leagues, pages, managers), not the player pool — draft
  rooms already have their own player search and that is where it belongs.
- Changing what any destination page shows. This feature moves people between pages; it does not alter
  them.
- The power rankings week-drift defect noted separately — a content bug on one page, not navigation.
- Sign-in and the signed-out shell, which deliberately render no navigation at all.
