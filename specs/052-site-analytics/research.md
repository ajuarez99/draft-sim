# Research: Site analytics (spec 052)

Phase 0 for `plan.md`. The vendor survey is in `research-notes.md` (search results, not
vendor-verified). This file records the decisions. **"Doc" means read on docs.umami.is
on 2026-10-28; "assumed" means not yet observed, and the verification pass must measure it.**

## R1 — Which hosted service

- **Decision**: Umami Cloud, Hobby (free) plan.
- **Rationale**: Clarified in the spec (hosted, cookie-free). Of the cookie-free options it is
  the only free one with named custom events carrying properties (needed for US2/US3);
  Cloudflare Web Analytics has page views only. Its tracker is open source and the same
  script works against a self-hosted Umami, so FR-013's "replaceable later" is a config change.
- **Alternatives**: Plausible (no free hosted tier), PostHog (sets cookies unless configured
  otherwise; much bigger than the ask), Cloudflare (no custom events), GA4 (cookies; FR-005).
- **Assumed, not verified**: the free plan's cap (~100K events/month) and retention (~6
  months) come from third-party pages. The account owner reads the real numbers when
  creating the account.

## R2 — Automatic vs manual tracking

- **Decision**: load the tracker with `data-auto-track="false"` and send every page view and
  event ourselves through `umami.track(...)`, object form.
- **Rationale** (doc): automatic tracking sends `window.location` as the URL, which here
  carries Sleeper league, draft, manager and player ids (FR-003, FR-006). The object form
  "sends only the properties you include", so we choose the URL (a route template), the title
  and the referrer, and nothing else goes out. Auto-track also counts every path change; we
  want one view per route change and not per re-render (FR-001).
- **Alternatives**: `data-auto-pageview="false"` (keeps click tracking initialised, which we
  don't use); `data-before-send` (it has to be a function on `window` defined *before* the
  script loads, which means inline script or a second file; the CSP's `script-src 'self'`
  rules out inline). Object form makes it unnecessary.

## R3 — Title and referrer

- **Decision**: `title` = the route template's own label (e.g. "League power rankings"),
  never `document.title`. `referrer` = `document.referrer` only on the **first** page view
  of the document, and only when its origin is not our own; reduced to the origin
  (`https://www.google.com`), never a full URL. In-app navigations send no referrer.
- **Rationale**: page titles can carry a league or manager name; an in-app referrer is our
  own URL with ids in it; an outside full URL could carry anything. The origin is enough
  for "where did they come from" (FR-002).

## R4 — Per-league attribution (US3)

- **Decision**: a named event `league-view` with `{ route, sport, league }`, sent once per
  route change on a league-scoped page, after the rail's league resolves. `league` is the
  lineage's **current-season** Sleeper league id, so all seasons of one league count as one.
- **Rationale** (doc): "Event data cannot be sent without an event name", so page views
  themselves can't carry league/sport. Costs at most one extra event per league page view.
- **Not sent**: league name (user-chosen text, changes per season; the owner can look an id
  up on the site).
- **Alternative rejected**: putting the league id in the page-view URL — breaks FR-003's grouping.

## R5 — Key actions (FR-004)

- **Decision** (*amended after review: now three, see plan F5/F6*): v1 list was four, each fired after the call succeeds:
  `sign-in` (SignIn `setUser`), `league-added` (DraftPicker's add-league ingest),
  `mock-started` (`createMockSession` in MockSetup, `createMockSessionFromDraft` in
  LiveDraftView; prop `from: 'setup' | 'live'`), `live-draft-followed` (`trackDraft` in
  LiveStatusBar).
- **Rationale**: the spec's other examples (running a simulation, comparing players, opening
  power rankings) are not discrete user actions here: the simulation runs on its own when the
  draft room opens and re-runs on every pick, and comparing or opening a page *is* a page
  view. Counting them as events would double-count page views or count re-sims. See the
  spec amendment under US2.

## R6 — Owner's own visits

- **Confirmed in script source 2026-10-28** (plan F8): setting `localStorage['umami.disabled'] = '1'`
  in a browser stops that browser's tracker. Verification checks it in the network panel; if
  it doesn't hold, the owner uses a content blocker or Umami's own IP filter instead.

## R7 — Where the script and data go

- **Decision**: script `https://cloud.umami.is/script.js`.
- **Amended after review**: the script source shows the endpoint is `https://gateway.umami.is/api/send`, not the script origin. Original guess, kept for the record: the collection endpoint is on the same origin (doc: "by default, data goes to
  wherever the script is hosted"), but Umami Cloud may route through another host. The CSP
  change lists `https://cloud.umami.is` for `script-src` and `connect-src`; verification reads
  the real request host from the network panel and adds it if different (FR-011).

## R8 — Off by default

- **Decision**: build-time variable `VITE_UMAMI_WEBSITE_ID`. Absent or empty → no script tag,
  no `umami` global, every tracking call a no-op (FR-014, FR-008). Plus `data-domains=
  "www.ballknowers.co,ballknowers.co"` (doc), so a preview build that did get the id still
  sends nothing from another hostname.
- **Why build-time**: same as `VITE_API_BASE` — Vite inlines `VITE_*` at build time, so it is
  a Railway build variable and a Dockerfile `ARG`.
