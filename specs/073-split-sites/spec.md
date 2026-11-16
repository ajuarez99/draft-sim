# Feature Specification: Split the website into a Time Machine site and a league site

**Feature Branch**: `073-split-sites`

**Created**: 2026-11-15

**Status**: Draft

**Input**: Allan, 2026-11-15: "I want to separate time machine and the rest of the ball knowers site so that they dont touch each other at all", then, asked how far: "I just mean from a website POV i want a user to go to ballknowers.co and it be timemachine and then ballknowers other website is the other stuff." League site address chosen the same day: `leagues.ballknowers.co`.

## What is there now (read from `origin/main` d3a29f0, not run)

- One web app serves everything at `www.ballknowers.co`. Time Machine is two routes inside it, `/history` and `/history/:gameId` (`web/src/App.tsx:142-143`), behind the same sign-in screen and the same top bar as the league pages.
- The top bar and the phone menu both link to Time Machine (`web/src/components/TopBar.tsx:121`, `web/src/components/NavSheet.tsx:105`).
- The signed-out root is the spec 050 landing page, which is about the league tools (`web/src/pages/SignIn.tsx`, `web/src/landingCopy.ts`, baked into the delivered HTML by `web/src/landingHtml.ts`). `SITE_ORIGIN`, the sitemap and the robots file all name `www.ballknowers.co`.
- Sign-in is a Sleeper username kept in the browser's storage for the address it was typed on (`web/src/user.ts`, key `bk.user.v1`). Every Time Machine request needs that identity (`HistoricalGameController`, `X-Sleeper-User`, 401 without it).
- Time Machine's share text builds its link from the current address plus `/history` (`web/src/components/HistoricalResults.tsx:82`).
- One web service and one backend service on Railway. The backend accepts browser calls only from the addresses listed in `CORS_ORIGINS`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - ballknowers.co is the Time Machine (Priority: P1)

Someone types `ballknowers.co`, or taps a shared Time Machine result. They land on a site that is only the Time Machine: its own front door explaining the game, a place to enter their Sleeper username, then the game. Nothing on the screen mentions leagues, mock drafts, managers or league search, and there is no league rail or league switcher.

**Why this priority**: This is the request. The main address becomes the game.

**Independent Test**: Open the Time Machine site signed out and signed in, on a phone width and a desktop width; play a pick; confirm no league navigation or league wording appears anywhere and that every in-game link stays on this site.

**Acceptance Scenarios**:

1. **Given** a signed-out visitor, **When** they open the root of the Time Machine site, **Then** they see a front door that describes the Time Machine and asks for a Sleeper username, with no league-tool descriptions.
2. **Given** a signed-in player, **When** they open the root, **Then** they see the Time Machine home (game of the day, start a game, their games and profile) with no league rail, league switcher, league search or links to Leagues / Mock drafts / Managers.
3. **Given** a player in a game, **When** they follow "back to Time Machine", finish a game, or open a past game, **Then** they stay on the Time Machine site at its new addresses.
4. **Given** a player who shares a result, **When** the share text is produced, **Then** its link points at the Time Machine site's root address, not at `/history`.
5. **Given** a browser tab on the Time Machine site, **When** the tab title, the link preview (title, description, image) or the search listing is read, **Then** it describes the Time Machine, not the league tools.

---

### User Story 2 - The league tools live at leagues.ballknowers.co (Priority: P1)

A league-mate opens `leagues.ballknowers.co`. They get everything that is not Time Machine: the league landing page when signed out, then their leagues, draft boards, live draft room, mock drafts, managers and every league page, exactly as today. Time Machine is not in the top bar or the phone menu.

**Why this priority**: Without it the league tools have no home once the main address changes. Stories 1 and 2 ship together.

**Independent Test**: Open the league site signed out and signed in; visit a league home, a draft board, a mock and the managers page; confirm they behave as they do today and that no Time Machine link is offered.

**Acceptance Scenarios**:

1. **Given** a signed-out visitor, **When** they open the league site root, **Then** they see today's league landing page.
2. **Given** a signed-in league-mate, **When** they look at the top bar on desktop or open the menu on a phone, **Then** Leagues, Mock drafts and Managers are there and Time Machine is not.
3. **Given** a signed-in league-mate, **When** they use any league, draft, mock or manager page, **Then** it loads its data and behaves as it does on the single site today.
4. **Given** the league site, **When** its tab title, link preview, sitemap and robots file are read, **Then** they name the league site's own address.

---

### User Story 3 - Old links still land somewhere sensible (Priority: P2)

People have bookmarks and shared links from the single site: league pages under the main address, and Time Machine games under `/history`. Each old link takes them to the same thing at its new home.

**Why this priority**: The split is usable without it, but every existing bookmark and every Time Machine result already shared would break on the day of the switch.

**Independent Test**: Open one old link of each kind on each site and confirm where it ends up, including the path and any query string.

**Acceptance Scenarios**:

1. **Given** the Time Machine site, **When** someone opens `/history`, **Then** they end up at the Time Machine root.
2. **Given** the Time Machine site, **When** someone opens `/history/<game id>`, **Then** they end up at that same game's new address.
3. **Given** the Time Machine site, **When** someone opens an old league address (`/leagues/...`, `/drafts/...`, `/mock/...`, `/managers/...`), **Then** they end up at the same path, with the same query string, on the league site.
4. **Given** the league site, **When** someone opens `/history` or `/history/<game id>`, **Then** they end up at the Time Machine root or that game on the Time Machine site.
5. **Given** either site, **When** someone opens an address that belongs to neither, **Then** they see that site's own not-found page.

---

### Edge Cases

- A player is signed in on one site and follows an old link to the other, where they have never signed in: they meet that site's sign-in first and then reach the page the link named, not the site's root.
- A player's Time Machine games, achievements and streaks were earned under `/history` on the single site: all of them appear on the Time Machine site after signing in with the same Sleeper username, because they are stored against the Sleeper user and not the browser.
- A Time Machine game id that does not exist or belongs to someone else, reached through an old `/history/<id>` link: the Time Machine site shows what it shows today for that case, at the new address.
- The league site's `/managers/<id>/history` and `/leagues/<id>/history` are league pages that happen to end in "history": they stay on the league site and are not treated as Time Machine links.
- Local development: both sites must be runnable on one machine at the same time against one local backend, so each can be checked before anything is deployed.
- The backend is not told about the league site's address: league pages fail to load data there. The deploy notes must name this as a required step, and the failure must be a visible error, not a blank page.
- Analytics: page views from the two sites must not be mixed into one unlabeled stream. Each site reports under its own address.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: There MUST be two sites: the Time Machine site at the main address (`ballknowers.co` and `www.ballknowers.co`) and the league site at `leagues.ballknowers.co`.
- **FR-002**: The Time Machine site MUST serve the Time Machine home at its root and each game at an address beneath the root, with no `/history` segment in either.
- **FR-003**: The Time Machine site MUST have its own shell with no league rail, league switcher, league search, or navigation to Leagues, Mock drafts or Managers.
- **FR-004**: The Time Machine site MUST have its own signed-out front door that describes the game and accepts a Sleeper username, and MUST NOT show the league landing page's content.
- **FR-005**: The Time Machine site MUST carry its own tab title, link-preview title, description and image, sitemap and robots file, all describing the Time Machine and naming the main address.
- **FR-006**: Every Time Machine link, in-app navigation and share link MUST resolve to the Time Machine site's new addresses.
- **FR-007**: The league site MUST serve every page the single site serves today except the Time Machine, with unchanged behaviour.
- **FR-008**: The league site MUST NOT show a Time Machine entry in the top bar, the phone menu, or search.
- **FR-009**: The league site MUST carry the league landing page, tab title, link preview, sitemap and robots file, naming the league site's address.
- **FR-010**: On the Time Machine site, `/history` and `/history/<game id>` MUST redirect to the root and to that game's new address.
- **FR-011**: On the Time Machine site, addresses under `/leagues`, `/drafts`, `/mock` and `/managers` MUST redirect to the same path and query string on the league site.
- **FR-012**: On the league site, `/history` and `/history/<game id>` MUST redirect to the matching address on the Time Machine site.
- **FR-013**: A visitor who is redirected to a site where they are not signed in MUST reach the page the link named after signing in.
- **FR-014**: Both sites MUST use the one existing backend and database; Time Machine progress MUST remain attached to the Sleeper user and appear on the Time Machine site without any data move.
- **FR-015**: Neither site's delivered pages MUST offer a visible link to the other site, with one exception, FR-019. The redirects in FR-010 to FR-012 are the only other crossing points. *(Amended after plan review, 2026-11-15, PR-08, Allan's decision: the original had no exception, which left a signed-in league-mate at the bare main address with no way to find their leagues.)*
- **FR-019**: A browser that was already signed in at the main address before the switch MUST see, on the Time Machine home, a one-line notice that the league pages moved to `leagues.ballknowers.co`, with a link there and a way to dismiss it. Once dismissed or followed it MUST NOT appear again in that browser. Someone who first signs in on the Time Machine site MUST never see it. *(Added 2026-11-15, Allan's decision.)*
- **FR-020**: When the main address forwards a visitor to the league site (an old league link, or the FR-019 notice), the stat-calculator formulas saved in that browser MUST arrive with them and be saved on the league site if it has none of its own; formulas already saved on the league site MUST NOT be overwritten, and nothing may be removed from the old address. Display preferences and the analytics opt-out are not carried. *(Added 2026-11-15, plan review PR-04, Allan's decision.)*
- **FR-016**: Each site MUST report analytics under its own address, and a page view on one site MUST NOT be recorded as a view on the other.
- **FR-017**: Both sites MUST be buildable and runnable locally at the same time against one local backend.
- **FR-018**: The deploy notes MUST list the steps only Allan can do (the DNS record for the league address, the second web service with its custom domain and build settings, the league address added to the backend's allowed origins) and the order to do them in so that neither site is down or dataless during the switch.

### Key Entities

- **Site**: one of two, Time Machine or league. Has an address, a shell, a front door, a set of pages it serves, a set of old paths it redirects, and its own title / preview / sitemap / robots.
- **Sign-in**: a Sleeper username remembered by the browser for one address. A player has at most one per site; the two are independent.
- **Time Machine progress**: games, achievements, profile and streaks, held by the backend against the Sleeper user. Unchanged by this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the Time Machine site, across the front door, the home, a game in progress and a finished game, at phone and desktop widths, zero league navigation elements and zero links to league pages are present.
- **SC-002**: On the league site, zero Time Machine links are present in the top bar, the phone menu and search results.
- **SC-003**: Every one of the old-link cases in User Story 3 (five scenarios, checked on the site each names) ends at the stated destination with path and query string intact.
- **SC-004**: A player with existing Time Machine games sees the same games, achievements and streak on the Time Machine site as they saw at `/history` before the split.
- **SC-005**: Every page of the league site that worked before the split loads its data after it: a league home, a draft board, the live draft room, a mock, the managers page and one page from each league rail group, for one NFL league and one NBA league.
- **SC-006**: The web test suite and type check pass, a production build succeeds in each of the three build modes, and the backend suite is unaffected. *(Reworded after plan review, 2026-11-15, PR-12: "pass for both sites" was ambiguous. Per-site behaviour is covered by tests inside the one suite.)*
- **SC-007**: Each site's delivered root HTML, read without running scripts, names only that site: Time Machine wording on the main address, league wording on the league address.

## Assumptions

- "Don't touch each other at all" means what a visitor sees. Backend code, database and shared web code (draft board, queue, pick reveal, the API client) stay shared; Allan confirmed the website-only reading.
- A game's new address is the root plus a short fixed segment and the game id (the plan picks the segment). The root itself is the Time Machine home.
- No visible cross-links between the sites, not even a footer link. If Allan later wants one, it is a small addition.
- Time Machine still needs a Sleeper username. Letting people play without one is a separate feature.
- Sign-in is not carried between the two addresses; a player signs in once on each. This follows from where the browser keeps it and is accepted, not worked around.
- The league landing page's search standing was built at `www.ballknowers.co` (spec 050). Moving it to `leagues.ballknowers.co` restarts that for the league site. Accepted as a cost of the request; not measured.
- The apex-to-www redirect stays as it is (Allan's Squarespace setting).
- No database migration and no backend code change beyond configuration of allowed origins.
- Analytics keeps using the current provider. Whether the two sites share one website id or get one each is Allan's choice at deploy time; the code works with either.
- Nothing is deployed, and no DNS or Railway setting is changed, by the build itself. Those are Allan's steps.
