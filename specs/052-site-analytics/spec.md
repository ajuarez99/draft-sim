# Feature Specification: Site analytics

**Feature Branch**: `052-site-analytics`

**Created**: 2026-10-28

**Status**: Draft

**Input**: User description: "research best way to get some analytics now on our webstie"

## Context: what is there now (read from the code 2026-10-28, not measured in prod)

- **The site has no analytics of any kind.** No page-view counter, no event tracking, no
  third-party measurement script. The only usage signal today is the hosting provider's
  request logs, which nobody aggregates. How many people use the site, which leagues, and
  which pages they open is **unknown** — not low, not high, unknown.
- **The site is two separately deployed services**: the web front end (a single-page app,
  so moving between pages does not reload the document) and the API. A page-view count
  that only fires on a full document load would undercount every in-app navigation.
- **The front end already publishes a security policy that lists every outside origin the
  page may talk to.** It is currently report-only, not enforcing (DEPLOY.md "Security
  headers"). Anything that sends data to a new outside origin has to be added to it, and
  the policy will eventually be enforced.
- **What the site promises visitors about privacy today**: the sign-in screen says there
  is no password and the app "never asks Sleeper for anything private". It makes **no**
  promise about not measuring usage. There is no cookie banner and no cookies are set;
  per-browser preferences live in browser storage.
- **Sign-in is a Sleeper username, not an account.** The app knows which Sleeper user and
  which league a signed-in visitor is looking at. That is the only identity there is.
- **There is already an admin-only gate** (a token, used by the unlisted `/admin/digest`
  page, spec 043). An owner-only view can reuse it rather than inventing new access control.
- **The public landing page and share previews were just rebuilt for search and sharing**
  (spec 050). Whether anyone actually arrives from search or from shared links is exactly
  the question nothing can answer yet.

## Clarifications

### Session 2026-10-28

- Q: Where should the analytics data live? → A: A hosted, cookie-free analytics service
  (research-notes.md leans Umami Cloud's free tier); no first-party build in v1.
- Q: How much should usage be tied to who or what? → A: Per league and sport only, never
  per Sleeper user.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The owner can see whether anyone is using the site (Priority: P1)

Allan (the site's owner) opens one place and sees, for a chosen time range, how many
visits the site got, how many distinct visitors, which pages they viewed, and where they
came from (direct, search, a shared link, a referring site). Each in-app page change
counts as a page view, the same as a fresh load.

**Why this priority**: This is the whole ask. Every later question (which features
matter, did the landing page work) needs this first, and it is useful on its own.

**Independent Test**: Visit the production site from two different browsers, open three
different pages in each by clicking in-app links (no reloads), then open the owner view
and confirm it shows 2 visitors and 6 page views on the right pages within the stated delay.

**Acceptance Scenarios**:

1. **Given** a visitor opens the landing page and then clicks through to two other pages
   without reloading, **When** the owner views today's numbers, **Then** three page views
   are counted against the three correct pages.
2. **Given** a visitor arrives by following a shared link from another site, **When** the
   owner views referrers, **Then** that visit is attributed to the referring site.
3. **Given** a visitor's browser blocks measurement (content blocker, strict privacy mode),
   **When** they use the site, **Then** every page still works exactly as before.
4. **Given** someone who is not the owner, **When** they try to reach the owner view,
   **Then** they see no numbers.

---

### User Story 2 - The owner can see which features get used (Priority: P2)

Beyond page views, the owner sees counts of a short, named list of key actions — for
example running a draft simulation, starting a mock draft, following a live draft,
comparing two players, opening a league's power rankings — so he can tell which of the
many built features people actually use.

*Amended at plan time 2026-10-28*: three of the examples above (running a simulation,
comparing two players, opening power rankings) aren't separate user actions in the code.
The simulation runs by itself when the draft room opens and re-runs after every pick, and
the other two are just page views. The v1 list is `sign-in`, `league-added`,
`mock-started` and `live-draft-followed` (research.md R5). *Amended after review 2026-10-28*:
`live-draft-followed` dropped (its call site turned out to be the manual Refresh button);
live-room page views answer that question. v1 is three actions.

**Why this priority**: The site has grown to dozens of features (spec 050's context lists
them). Knowing which ones get used decides what to build next, but it needs P1 first.

**Independent Test**: Perform each listed action once on production, then confirm each
appears exactly once in the owner view.

**Acceptance Scenarios**:

1. **Given** a visitor starts one mock draft, **When** the owner views key actions,
   **Then** "mock draft started" shows a count of 1, not one per pick or per re-render.
2. **Given** an action fails (for example the simulation errors), **When** the owner views
   key actions, **Then** the failed attempt is not counted as a success.

---

### User Story 3 - The owner can see usage per league and per sport (Priority: P3)

The owner can break the P1 and P2 numbers down by league and by sport (NFL / NBA), to see
whether league-mates in each real league are using it, and whether basketball (the real
target) gets used as much as football.

**Why this priority**: Useful for a multi-league site, but only after the basic counts
exist, and it is the part that touches identity.

**Independent Test**: View pages in two different leagues, one per sport, and confirm the
owner view splits the counts correctly.

**Acceptance Scenarios**:

1. **Given** visits to pages in two leagues, **When** the owner filters by league,
   **Then** each league shows only its own visits.
2. **Given** a signed-out visitor on the landing page, **When** the owner views per-league
   numbers, **Then** that visit appears as "no league", not attributed to any league.

---

### Edge Cases

- **Single-page navigation**: in-app page changes must count; a re-render of the same page
  (a live draft updating every couple of seconds, a filter change) must not.
- **Live draft room**: the room refreshes continuously for hours; it must not inflate page
  views or key-action counts, and measurement must not add requests to the live-poll path.
- **Content blockers**: a meaningful share of visitors may block measurement. Numbers are
  therefore a floor, and the owner view MUST say so rather than presenting them as exact.
- **Owner's own visits and local development**: visits from local development and
  preview environments must not be counted; the owner's own production visits should be
  excludable.
- **Bots and crawlers**: search crawlers fetching the landing page, robots.txt and
  sitemap (spec 050) must not be counted as visitors.
- **Measurement service unavailable**: if the place data is sent to is down or slow, no
  page may slow down, error, or show anything different.
- **URLs carrying identifiers**: page addresses include league ids and Sleeper user ids;
  see FR-006.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The site MUST record a page view for every page a visitor sees, including
  in-app navigations that do not reload the document, and MUST NOT record one for a
  re-render of the page already being viewed.
- **FR-002**: The owner MUST be able to see, for a chosen time range: visits, distinct
  visitors, page views per page, and traffic sources (referrer).
- **FR-003**: Pages MUST be grouped by route (e.g. "league power rankings"), not by raw
  address, so the same page across different leagues counts as one page.
- **FR-004**: The site MUST record a fixed, named list of key actions (P2), each counted
  once per completed action. The list is decided in planning and written down; adding an
  action later is a deliberate change, not ad-hoc.
- **FR-005**: Measurement MUST NOT set cookies and MUST NOT require a consent banner. No
  visitor-facing change (banner, prompt) is part of this feature.
- **FR-006**: Measurement MUST NOT collect or send a Sleeper username, Sleeper user id,
  ESPN credential, or any other personal identifier, including inside page addresses or
  referrers. **Identity scope (clarified 2026-10-28)**: usage is attributed per league
  (league id and sport) only, never per person. A signed-out page view carries no league.
- **FR-007**: Measurement MUST be invisible to the visitor's experience: no page may load
  slower in a way a visitor can notice, error, or render differently when measurement is
  blocked, slow or failing.
- **FR-008**: Visits from local development and non-production environments MUST NOT be
  counted.
- **FR-009**: The numbers MUST be visible only to the owner.
- **FR-010**: Wherever the numbers are shown, they MUST be labelled as a floor
  (blocked visitors are not counted), and the date measurement began MUST be shown, so a
  low early number is not read as "nobody uses it" for the period before it existed.
  *Amended at plan time 2026-10-28*: with a hosted dashboard (FR-013) this label can't go
  on the service's own screens. It lives in DEPLOY.md's Analytics section and HANDOFF.md,
  and FR-009 means the owner's login at the service.
- **FR-011**: Any new outside destination for measurement data MUST be added to the
  front end's published security policy in the same change.
- **FR-012**: The site MUST say, in plain words somewhere a visitor can find it (for
  example beside the existing sign-in privacy line), that anonymous usage is counted and
  what is and is not collected.
- **FR-013**: **Where the data lives (clarified 2026-10-28)**: a hosted, cookie-free
  analytics service. Its own dashboard, behind the owner's login there, is the owner view
  (FR-009); no owner page is built inside the site. The service must support custom
  events with properties (for US2/US3) and must be replaceable later without changing
  what the site records.
- **FR-014**: When no analytics site key is configured, the site MUST send nothing and
  load nothing for measurement (off by default), so local development and preview builds
  satisfy FR-008 without any extra filtering.

### Key Entities

- **Page view**: one visitor seeing one page (route group) at one time, with its referrer
  and, if allowed by FR-006, its league and sport.
- **Key action**: one completed named action (from the FR-004 list) at one time, with the
  same optional league/sport attribution.
- **Visitor**: a distinct browser over a day, recognised without cookies and without any
  personal identifier; not linkable across days or to a Sleeper user (unless FR-006's
  answer says otherwise).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Within 1 week of release, the owner can answer "how many people used the
  site this week, and which 5 pages did they view most" in under 1 minute.
- **SC-002**: A scripted test visit (2 browsers × 3 in-app pages) appears with the exact
  expected counts (2 visitors, 6 page views) within 5 minutes.
- **SC-003**: With measurement blocked in the browser, a full click-through of every main
  page shows zero errors and no visible difference.
- **SC-004**: An hour on the live draft room page counts as 1 page view, not one per refresh.
- **SC-005**: A capture of everything the site sends for measurement during a full
  click-through contains zero Sleeper usernames, Sleeper user ids or credentials.
- **SC-006**: Time from approving the plan to the first real production numbers is
  measured and reported (target: same day if a hosted service is chosen).

## Assumptions

- "Analytics" means usage measurement for the owner, not analytics shown to league members
  (the site already has plenty of fantasy analytics pages).
- The owner is the only consumer of the numbers; no sharing with league-mates in v1.
- Cost should be zero or near zero at this site's traffic; traffic volume is unmeasured,
  but a private-league companion site is assumed to be well under 100,000 page views a month.
- Visitors are mostly in the US; the cookie-free / no-personal-data constraint (FR-005,
  FR-006) is chosen so no consent banner is needed in any region, not because a specific
  law was checked.
- Historical usage before release cannot be recovered; numbers start on release day.
- Server/API-side performance metrics, error tracking and uptime monitoring are out of
  scope (different problem; the hosting provider covers some of it).
- Session recordings, heatmaps, A/B tests and funnels are out of scope for v1.
