# Feature Specification: Usage analytics we own

**Feature Branch**: `076-usage-analytics`

**Created**: 2026-11-17

**Status**: Draft

**Input**: User description: "now lets focus and create analytics" — narrowed by two answers
the same day: add Redraft game events, and build our own usage dashboard (counts stored by
this site, shown on an admin page), rather than only turning on the hosted service.

## Context: what is there now (read from the code and from production 2026-11-17)

- **Spec 052 built usage measurement and it has never recorded anything.** The front end has
  one module that sends page views, league views and three key actions (`sign-in`,
  `league-added`, `mock-started`) to a hosted service (Umami Cloud). It sends nothing unless
  a website id is set at build time. **Measured 2026-11-17**: the production bundle on
  `www.ballknowers.co` contains the module but no website id, so it is inert. How many
  people use either site is still unknown — not low, not high, unknown.
- **The site has since split in two** (spec 073): `www.ballknowers.co` is the public Redraft
  game, `leagues.ballknowers.co` is the league site, locked to the owner. Spec 052's module
  already knows the two sites and has a route-template table for each.
- **Redraft is now the public product and it has a guest mode** (spec 074): a visitor can
  play without signing in. Today the only thing 052 would record about Redraft is page
  views. Starting a game, finishing a draft, playing the game of the day and sharing a
  result are not recorded anywhere as usage (finished games exist as game rows, but nothing
  counts starts that were abandoned, or shares).
- **Spec 052 decided against a first-party store** ("no first-party build in v1",
  clarification 2026-10-28) and against an owner page inside the site (FR-013). This spec
  reverses both, on the owner's instruction, three weeks later. The reason on record: the
  hosted route needs an account the owner has not created, and nothing has been measured in
  the meantime.
- **There is already an owner-only gate**: an admin token, used by the unlisted
  `/admin/digest` page on the league site (spec 043, extended by 073).
- **Spec 052's privacy rules stand**: no cookies, nothing per person, a route template in
  place of the real address, an owner opt-out switch in the browser, off outside production.

## Clarifications

### Session 2026-11-17

- Q: What does "create analytics" mean, given 052 exists? → A: Add Redraft game events, and
  build our own usage dashboard.
- Q: Should our own store count distinct visitors? → A: No. Views and events only; nothing
  about the visitor is stored, not an address and not a hash of one.

## Amended at plan time 2026-11-17

Reading the schema (research.md R1) showed this spec was wrong about the funnel. Saved Redraft games
already record when each was started, whether it finished or was abandoned, its sport and whether it was
the game of the day, and no code deletes them. So:

- **Started, finished and game-of-the-day counts are read from the saved games**, not counted from browser
  events. They are exact, cannot be blocked or faked, and go back to the game's launch, not to this
  feature's deploy. FR-003 and FR-005 as first written (browser events, "once per completed action") apply
  only to **result shared**, which is the one funnel step the server never sees.
- **FR-016 is reversed**: started and finished are the same games, so the page shows a true completion
  rate per start day. It also shows abandoned and still-drafting.
- The assumption "v1 does not [count games made before release]" is dropped. The remaining gap is the
  guest/signed-in split, which needs one new stored fact per game and is slightly inexact for games a
  guest started and then signed in on before this ships. The page says so.
- New honesty note for FR-015: funnel counts include the owner's own games (the browser opt-out cannot
  reach saved games); share counts are a browser-reported floor and are not part of the completion rate.
- SC-002 gains: 1 abandoned.

## Amended after review 2026-11-17

From plan-review.md (details and reasons in plan.md "Amended after review"):

- **FR-006**: the only new event is **result shared**, sent to both our store and the hosted service.
  "Game started" and "draft finished" are not sent anywhere as events; the saved games count them (F6).
- **FR-010**: a non-production host sends to our store only under a new development-only switch; the
  existing debug switch still only prints (F9).
- **FR-014 / FR-015**: the funnel headline is finished vs not finished; "abandoned" is only a pressed
  button and a game left open stays open for ever; finished includes auto-finished drafts (F4). The
  owner's own signed-in games are shown apart from the totals when his id is configured (F5). A small
  "not recorded" bucket exists for the guest split (F1).
- **FR-011**: the per-source limit is best effort (the source address behind the host's proxy is checked
  only after deploy), backed by a site-wide ceiling (F3, F7).
- **SC-002** is run as: finish the NBA game of the day, start and abandon one chosen-season game — the only
  way a guest can own two games in a day (F4). **SC-006**'s burst is "at most the limit in one window".

The original text below is left as written.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The owner sees what pages get viewed, without a third party (Priority: P1)

Allan opens an owner-only page on the league site and sees, for a chosen range of days, how
many page views each site got per day and which pages (by route, e.g. "Redraft game",
"NBA season page") were viewed most. The counts come from this site's own store and work
whether or not the hosted service was ever switched on.

**Why this priority**: It is the first number the site has ever had about itself, and
everything else on the page is a breakdown of it.

**Independent Test**: On a running copy, open four known pages by in-app navigation on the
Redraft site and two on the league site, then open the owner page and confirm six views,
on the right routes, split four and two between the sites.

**Acceptance Scenarios**:

1. **Given** a visitor opens the Redraft front page and clicks through to two more pages
   without reloading, **When** the owner opens the usage page, **Then** three page views
   are counted for the Redraft site on the three correct routes, dated today.
2. **Given** a page that re-renders for a long time (a draft room), **When** an hour passes,
   **Then** it has counted one page view.
3. **Given** someone without the owner's token, **When** they request the numbers,
   **Then** they get none.
4. **Given** the place counts are sent to is down or slow, **When** a visitor uses the site,
   **Then** every page behaves exactly as before.
5. **Given** the hosted service is not configured, **When** a visitor views pages on
   production, **Then** the views are still counted in our own store.

---

### User Story 2 - The owner sees the Redraft funnel (Priority: P2)

On the same page the owner sees, per day and per sport, how many Redraft games were started,
how many drafts were finished, how many games of the day were played, and how many times a
result was shared, each split by guest and signed-in. He can read off how many started
games get finished.

**Why this priority**: Redraft is the public product. Page views say people arrived; the
funnel says whether the game holds them. It needs US1's collection path first.

**Independent Test**: As a guest, start one NBA game and finish its draft, start a second
and abandon it, press share once; then confirm the owner page shows 2 started, 1 finished,
1 shared, all NBA, all guest.

**Acceptance Scenarios**:

1. **Given** a guest starts a game, **When** the owner views the funnel, **Then** "game
   started" shows 1 for that sport under guest, not one per pick or per re-render.
2. **Given** a start that fails (the server refuses it), **When** the owner views the
   funnel, **Then** it is not counted.
3. **Given** a finished draft's results page is reloaded or reopened later, **When** the
   owner views the funnel, **Then** "draft finished" still shows 1 for that game.
4. **Given** a visitor presses share and the share or copy succeeds, **When** the owner
   views the funnel, **Then** "shared" goes up by 1; a cancelled share sheet does not count.
5. **Given** the game played was the game of the day, **When** the owner views the funnel,
   **Then** it is counted under "game of the day" as well as under started/finished.

---

### User Story 3 - The owner sees which leagues get looked at (Priority: P3)

The owner sees page views per league and sport on the league site, so he can tell whether
each real league is being opened at all.

**Why this priority**: The league site is locked to the owner today (spec 073), so until it
reopens this mostly counts his own visits. It is cheap because 052 already resolves the
league for a view, but it is the least informative part right now.

**Independent Test**: Open pages in two leagues, one per sport, and confirm the owner page
lists both with the right counts and sports.

**Acceptance Scenarios**:

1. **Given** views of pages in two leagues, **When** the owner views the per-league table,
   **Then** each league shows only its own views and its sport.
2. **Given** a page with no league (the Redraft site, the league picker), **When** the owner
   views the per-league table, **Then** that view is in no league's count.

---

### Edge Cases

- **Blocked or failed sends**: content blockers and flaky networks drop sends. Counts are a
  floor, and the page says so.
- **Anyone can post to a public collection address**: a script can invent views. The store
  must only accept the fixed vocabulary (known sites, known routes, known event names, known
  property values) and must bound how much one source can add, so junk cannot fill the
  database or put arbitrary text on the owner page. Inflated counts from a determined faker
  cannot be fully prevented without identifying visitors; the page says the numbers are
  unauthenticated.
- **Crawlers**: a crawler that runs scripts would count as views. Known crawlers are not
  counted; unknown ones may be, which is part of why the numbers are labelled as rough.
- **The owner's own visits and local development**: the existing browser opt-out switch
  stops both the hosted sends and ours. Nothing is counted outside production hosts, except
  under the existing local debug switch used to verify the feature.
- **Before the first count**: an empty store shows "nothing counted yet" and the start date
  as "not started", never a zero that reads as "nobody came".
- **Days with no views** inside the range are shown as zero, distinct from days before
  counting began.
- **Day boundaries**: a "day" is one stated time zone, named on the page.
- **A route that is later renamed or removed**: its past counts stay under the old name.
- **The league site is locked**: the owner page lives on it and is reached with the same
  token; the Redraft site has no owner page.

## Requirements *(mandatory)*

### Functional Requirements

**Collection**

- **FR-001**: The site MUST record, in its own store, a page view for every page a visitor
  sees on either site, including in-app navigations, and MUST NOT record one for a re-render
  of the page already being viewed.
- **FR-002**: A stored page view MUST carry only: the day, the site, the route template, and
  — for a league-scoped page on the league site — the league and its sport. Never the real
  address, a query string, a page title, or anything about the visitor.
- **FR-003**: The site MUST record these Redraft events, each once per completed action:
  **game started**, **draft finished**, **result shared**. Each carries the sport, whether
  the player was a guest or signed in, and whether the game was the game of the day.
  "Game of the day played" is reported from the started/finished events with that mark, not
  as a fourth event. Adding an event later is a deliberate change to this list.
- **FR-004**: Spec 052's three key actions (`sign-in`, `league-added`, `mock-started`) MUST
  also be recorded in our store, since they already pass through the same code path.
- **FR-005**: An event MUST be recorded only after the action succeeded, and "draft
  finished" MUST count once per game however many times its results are viewed.
- **FR-006**: Our own collection MUST work when the hosted service is not configured, and
  the hosted service MUST keep working as before when it is. The new Redraft events go to
  both.
- **FR-007**: Nothing about a visitor is stored or derived: no network address, no browser
  signature, no hash of either, no guest id, no Sleeper user, no game id, no draft or player
  id. Distinct visitors are not counted. *(Clarified 2026-11-17.)*
- **FR-008**: No cookie is set and no visitor-facing prompt is added.
- **FR-009**: Collection MUST be invisible to the visitor: no page may slow down in a way a
  visitor can notice, error, or render differently when a send is blocked, slow or failing.
- **FR-010**: The existing owner opt-out switch MUST stop our sends as well as the hosted
  ones. Non-production hosts send nothing, apart from the existing local debug switch.
- **FR-011**: The collection address MUST accept only the fixed vocabulary of sites, routes,
  events and property values, reject everything else without storing it, and limit how much
  one source can add in a short time. A rejected or limited send MUST NOT surface to the
  visitor.
- **FR-012**: Requests from known crawlers MUST NOT be counted.

**Owner page**

- **FR-013**: Only the owner (the existing admin token) can read the numbers. The page is
  unlisted, on the league site, beside the existing admin page.
- **FR-014**: For a chosen range of days the page MUST show: page views per day per site;
  page views per route per site; the Redraft funnel (started, finished, shared; by sport,
  by guest/signed-in, and the game-of-the-day share of each); the three older key actions;
  and page views per league with its sport.
- **FR-015**: The page MUST state, next to the numbers: the first day anything was counted;
  that nothing before that day exists; that counts are a floor (blocked sends are missing)
  and unauthenticated (a script could inflate them); that views are not people; and the
  time zone a "day" is in.
- **FR-016**: The funnel MUST show finished as a count beside started and MAY show the
  ratio, but MUST NOT present it as a completion rate of the same games: a game started on
  one day can finish on another, and the two counts are not linked per game.
- **FR-017**: With nothing counted yet, the page MUST say so in words instead of showing a
  table of zeros.

**Housekeeping**

- **FR-018**: The visitor-facing privacy line added by spec 052 (FR-012 there) MUST be
  brought up to date so it describes what this site now stores itself.
- **FR-019**: The store MUST stay small without attention: its size grows with days ×
  routes × sites, not with traffic.

### Key Entities

- **Daily page-view count**: for one day, one site, one route template and optionally one
  league (with sport): how many views. Not a row per view.
- **Daily event count**: for one day, one site, one event name and one combination of its
  properties (sport, guest or signed-in, game of the day or not; or 052's `via`/`from`):
  how many times it happened.
- **Vocabulary**: the fixed lists of sites, route templates, event names and property
  values that a send is checked against. The front end's route tables are its source for
  routes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A scripted visit (4 Redraft pages, 2 league pages, by in-app navigation)
  appears on the owner page with exactly 6 views on the right routes and sites within
  1 minute.
- **SC-002**: A scripted guest session (2 games started, 1 draft finished, 1 share) appears
  as exactly 2 / 1 / 1 under the right sport and under guest.
- **SC-003**: Reloading a finished game's results five times leaves "draft finished" at 1.
- **SC-004**: A capture of everything sent for collection during a full click-through
  contains zero guest ids, Sleeper user ids, usernames, game ids, draft ids, player ids or
  real page addresses; and the stored rows contain no network address or hash.
- **SC-005**: With the collection address unreachable, a full click-through of Redraft
  (start, draft, finish, share) shows zero errors and no visible difference.
- **SC-006**: 1,000 made-up sends with unknown routes, events or values add zero rows; a
  burst of valid sends from one source is capped at the stated limit.
- **SC-007**: After the scripted visits, the store holds no more rows than distinct
  (day, site, route, league) and (day, site, event, properties) combinations used.
- **SC-008**: A request for the numbers without the token returns none.
- **SC-009**: The owner can answer "how many Redraft games were started yesterday, and how
  many drafts were finished" in under 30 seconds from opening the page.

## Assumptions

- "Analytics" still means usage measurement for the owner, not fantasy analytics for
  league members.
- Counting views and events without visitors is accepted: the page cannot tell one person
  from ten, and says so. Referrers (where visitors came from) stay with the hosted service
  and are **not** stored by us in v1; if the hosted service is never switched on, that
  question stays unanswered.
- Daily totals are enough; time of day is not stored.
- Counts are kept indefinitely: totals per day are tiny and hold nothing personal.
- "One source" for the burst limit is judged at request time and never written down.
- History before release cannot be recovered. Finished Redraft games made before release
  exist as game rows and could be counted from there, but mixing them into the funnel
  would put a different kind of number (stored games) under the same label as counted
  events, so v1 does not.
- The league site stays locked to the owner for now, so US3's numbers are mostly his own
  visits until it reopens.
- Charts beyond a simple per-day series, alerts, exports, per-hour views, funnels linked
  per game, and A/B tests are out of scope.
- Turning on the hosted service remains the owner's separate step (he creates the account).
