# Implementation Plan: Fantasy-first landing page and share preview

**Branch**: `050-fantasy-landing-page` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/050-fantasy-landing-page/spec.md`

## Summary

Replace the signed-out sign-in screen with a fantasy-first landing page (hero with the
existing username form, then the feature list in the app's own rail groups), and make
what crawlers and link-preview bots receive match it: new title/description/preview
tags, a 1200×630 preview image, a canonical URL, JSON-LD, a real `robots.txt` and
`sitemap.xml`, and the landing copy pre-rendered into the delivered HTML. Frontend-only;
no backend, schema or API change.

Approach in one line: one copy module (`web/src/landingCopy.ts`) feeds both the React
page and a small Vite `transformIndexHtml` plugin that writes the head tags and a static
copy of the landing markup into `index.html` at build (and dev) time.

## Technical Context

**Language/Version**: TypeScript 5.7, React 18.3, Vite 6 (web only)
**Primary Dependencies**: none added at runtime. `@resvg/resvg-js` used once, outside the
app's dependencies, to rasterize the preview image (research R4)
**Storage**: N/A (no data; `bk.user.v1` localStorage key read only to avoid a flash, R3)
**Testing**: vitest + Testing Library (existing); one new build-output test that reads
`dist/index.html`; live checks with curl and the built-in browser
**Target Platform**: production static host — `serve -s dist` on Railway behind
`www.ballknowers.co`
**Project Type**: web application (frontend service only touched)
**Performance Goals**: landing first screen readable before the app script runs; no
extra request on the critical path beyond one tiny same-origin boot script
**Constraints**: CSP (report-only today) is `script-src 'self'` — no inline executable
script; JSON-LD is a data block, not executed, and is allowed. No real league data on
the public page (FR-005). Copy must not claim accuracy or usage (FR-004)
**Scale/Scope**: one public page, 2 static files, 1 image, ~6 source files

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Rule | Applies? | How this plan meets it |
|---|---|---|
| Migrations append-only | No | No schema change |
| `api.ts` mirrors Java records | No | No API/record change |
| Never retune a hand-set constant to match a guess | No | No model constants touched |
| `Map.of` nulls in JSON paths | No | No backend change |
| Ask before committing | Yes | Commit/push/PR wait for Allan (pipeline end) |
| Planning-doc scope isn't a spec | Yes | Feature list is checked against `destinations.ts` and the rail, not against roadmap docs (SC-008 guard test) |
| Project principle: honesty over confidence | Yes | FR-004 copy rule enforced by a test that greps the copy for banned claim words (R6) |

Post-design re-check: still passes; nothing in Phase 1 adds backend or data work.

## Project Structure

### Documentation (this feature)

```text
specs/050-fantasy-landing-page/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0
├── data-model.md        # Phase 1 (copy + preview shape)
├── quickstart.md        # Phase 1 (validation guide)
├── contracts/
│   └── public-surface.md   # what crawlers/bots/visitors receive, URL by URL
├── plan-review.md       # adversarial review (pipeline step 4)
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
web/
├── index.html                    # head tags + <div id="root"> get filled by the plugin
├── vite.config.ts                # + landingHtml() plugin (transformIndexHtml)
├── serve.json                    # + headers for robots/sitemap if R2 measures them wrong
├── public/
│   ├── robots.txt                # NEW
│   ├── sitemap.xml               # NEW
│   ├── og-image.png              # NEW 1200×630, rendered from og-image.svg
│   └── boot.js                   # NEW ~10 lines: hide prerender for signed-in visitors
├── scripts/og-image/
│   ├── og-image.svg              # NEW source of the preview image
│   └── render.mjs                # NEW one-off rasterizer (not part of the build)
└── src/
    ├── landingCopy.ts            # NEW single source: headline, sub, sports, platforms, groups
    ├── landingHtml.ts            # NEW pure string renderers: head tags, static body, JSON-LD
    ├── landingCopy.test.ts       # NEW destination guard (SC-008), banned-words (FR-001/004)
    ├── landingHtml.test.ts       # NEW static markup == React markup text (FR-018)
    ├── pages/SignIn.tsx          # becomes the landing page (hero + features); form unchanged
    ├── pages/SignIn.test.tsx     # existing tests unchanged (SC-007) + feature-section tests
    └── styles.css                # landing section styles beside the 013 US9 block
```

**Structure Decision**: web-only. `SignIn.tsx` keeps its name and default export so
`App.tsx` and the existing sign-in tests are untouched; it grows from "welcome" into the
landing page. A separate `Landing.tsx` wrapping `SignIn` was considered and rejected: two
components for one screen, and the tests would have to move.

## Design

### D1. Copy module (FR-018)

`landingCopy.ts` exports a plain object (shape in data-model.md): `title`, `description`,
`headline`, `subheadline`, `sports`, `platformsNote`, `groups[]` each with `heading` and
`features[]` of `{ name, line, destination }`. `destination` is a `DestinationKey` from
`destinations.ts`, or one of the global rail entries (`'managers' | 'mockDrafts'`) — the
guard test fails if a key doesn't exist (SC-008). Group headings are taken from
`DESTINATION_GROUPS` by key, not retyped.

No React and no browser globals in this module or in `landingHtml.ts` — both are imported
by `vite.config.ts`, which runs in Node.

### D2. Delivered HTML (FR-010, FR-013, FR-016, FR-017)

A Vite plugin `landingHtml()` in `vite.config.ts` uses `transformIndexHtml` to:

1. Replace the `<title>` and description, and add: `link rel=canonical`
   (`https://www.ballknowers.co/`), `og:title`, `og:description`, `og:url`, `og:type`,
   `og:site_name`, `og:image` (absolute), `og:image:width`=1200, `og:image:height`=630,
   `og:image:alt`, `og:image:type`, `twitter:card`=`summary_large_image`,
   `twitter:title`, `twitter:description`, `twitter:image`, `theme-color`.
2. Add one `<script type="application/ld+json">` `WebApplication` block (name, url,
   description, applicationCategory `SportsApplication`, operatingSystem `Web`,
   `offers` price 0) — no `aggregateRating`/`review` (FR-017).
3. Insert the static landing markup inside `<div id="root">` as
   `<div class="landing-prerender">…</div>`. `createRoot().render()` replaces the root's
   children on first commit, so React owns the node from then on.
4. Add `<script src="/boot.js"></script>` in `<head>` (synchronous, same-origin, CSP-safe).

The production origin is one constant in `landingCopy.ts` (`SITE_ORIGIN`). Every route
serves the same `index.html` (`serve -s`), so every URL gets the same site-level preview
and canonical (FR-012). That is correct for signed-out content: every URL *is* the landing
page to a visitor without a user.

### D3. No flash for signed-in visitors (R3)

Without care, a signed-in visitor would see the pre-rendered landing until the bundle
runs. `public/boot.js` reads `localStorage['bk.user.v1']` in a try/catch and, if it parses
with a string `sleeperUserId` (same test as `user.ts:readStored`), sets
`document.documentElement.dataset.bkUser = '1'`; CSS hides `.landing-prerender` under
`html[data-bk-user]`. A broken or blocked storage read leaves the landing visible, which
is the signed-out behaviour anyway.

### D4. The landing page (FR-001..FR-009)

`SignIn.tsx` renders, in order:

- **Hero**: kicker (product name), `h1` headline, subheadline, a sports row (NFL, NBA
  pills), the unchanged form and privacy note and error states, a one-line platform note
  ("Sign in with your Sleeper username. ESPN leagues can be added once you're in, for the
  season pages; the draft room is Sleeper-only.").
- **Feature groups**: four sections (This week, The season, Draft, History) each an `h2`
  and a grid of tinted feature tiles (name + one line). No screenshots, no real data
  (FR-005); a small CSS-only motif per group (accent tint per group) instead of images —
  design memory says avoid flat uniform cards, so groups get distinct tints and the grid
  follows content length.
- **Footer line**: "Free. No password. Built for one league's group chat and opened up."

The static markup from `landingHtml.ts` mirrors the same elements and classes, so styles
apply before and after mount and the text is identical (FR-013). The form is *not* in the
static markup (it needs React); the static hero shows the headline/sub/sports and a
placeholder of equal height so nothing jumps.

Phone width: hero first; `min-height` drops from `70vh` so the field stays above the fold
at 390×844; tiles become one column.

### D5. robots and sitemap (FR-014, FR-015)

`public/robots.txt`:

```text
User-agent: *
Allow: /$





Sitemap: https://www.ballknowers.co/sitemap.xml
```

`public/sitemap.xml`: one `<url>` (`https://www.ballknowers.co/`). Whether `serve` sends
`text/plain` / `application/xml` for these and serves them ahead of the SPA fallback is
measured in T001 (R2), not assumed; `serve.json` gets explicit headers only if needed.

### D6. Preview image (FR-011)

Designed as `web/scripts/og-image/og-image.svg` (1200×630, dark `--bg`, wordmark, the
headline, NFL/NBA pills, a few abstract tile shapes; all text inside a centered 630×630
square so square crops keep it), rasterized once to `web/public/og-image.png` by
`render.mjs` with resvg-js using a local system font (R4). The PNG is committed; the
build doesn't regenerate it.

### D7. Out of scope (from the spec, restated for the reviewer)

Per-league previews; new public content pages; analytics; the apex 302 (Allan's
registrar setting — written into quickstart as an operational step); a light theme.

## Complexity Tracking

No constitution violations. One added moving part worth naming: a Vite plugin that
writes HTML at build time. Alternative rejected: hand-editing `index.html` — it would
duplicate the copy and drift (FR-018 forbids exactly that).

## Amendments after review (2026-10-26)

`plan-review.md` read this plan cold against the code. Its measurements confirmed D1 (the
copy module imports cleanly into `vite.config.ts`), React replacing the prerendered
children with no warning, and R2: `serve@14.2.6 -s dist --config ../serve.json` serves
`/robots.txt` as `text/plain`, `/sitemap.xml` as `application/xml` and `/boot.js` as
`application/javascript`, with **no `serve.json` change needed**. What it changed:

- **A1 (F1, HIGH): ESPN wording.** D4's platform note overstated ESPN. New note: "Built
  for Sleeper leagues. Sign in with your Sleeper username. Some season pages also work
  for ESPN leagues." Spec FR-009 amended to match.
- **A2 (F2, HIGH): per-feature sport tags.** `LandingFeature` drops a hand-typed sport.
  The sports are read from the destination's own `sports` field, so they can't drift,
  and an "NBA" tag shows when only NBA applies. "Follow live" is worded as a draft-day
  tool. Spec FR-009a and SC-008 were added or amended.
- **A3 (F3, HIGH): robots allows everything.** The D5 `Disallow` lines are dropped. X's
  and LinkedIn's preview bots obey robots, so the Disallow lines would kill deep-link
  previews. A blocked URL also hides its canonical from Google. Deep URLs deliver only
  the landing HTML with a root canonical, so there is nothing private to index. The
  sitemap still lists only `/`. Verification pastes a deep link as well as the root.
  Spec FR-015 and US3-5 amended.
- **A4 (F4, HIGH): tests that pin the old headline.** `SignIn.test.tsx:98-108` and
  `App.test.tsx:109/124/134` change to the new headline. Behaviour assertions stay
  untouched. The product name will appear more than once, so `getByText('Ball Knowers')`
  becomes a role or heading query. Spec SC-007 amended.
- **A5 (F5, MEDIUM): scrolling.** `.app` is a fixed `100vh` and `.signin-welcome` centres
  its content vertically, so a tall landing would clip its top. The landing gets its own
  scroll container and top-aligned content. The static markup sits inside the same
  `.app` wrapper so layout matches before and after mount. Checked in a real browser.
- **A6 (F6, MEDIUM): title.** `Ball Knowers: Fantasy Football & Basketball for Sleeper
  Leagues` (63 chars).
- **A7 (F7, MEDIUM): Managers and Mock drafts are listed.** Mock drafts use the
  existing `'mock'` destination key, which replaces the invented `'mockDrafts'`.
  Managers (the tendencies page) is the one global rail entry, listed under Draft as
  `'managers'`.
- **A8 (F8, MEDIUM): test the transform, not `dist/`.** Unit tests run the plugin's pure
  `transform(html)` on the source `index.html` string. Checking the real `dist/` is a
  quickstart and verification step.
- **A9 (F9, MEDIUM): identical text, no layout shift.**
  - The text-equality test parses both outputs into DOM and compares `textContent` per
    element (h1, sub, each h2, each feature name and line). It does not compare raw HTML.
  - The prerender includes the real form markup, disabled, instead of a guessed-height
    placeholder.
  - The static markup has no inline `style=""` (CSP).
- **A10 (F10, LOW): sign-out and checks on real build output.**
  - React's landing never carries `.landing-prerender`, so signing out in the same tab
    still shows it.
  - The no-flash and above-the-fold checks run against `serve` on the built output, not
    `vite dev`, because dev injects CSS through JS.
- **A11 (F11, LOW): tidy-ups.**
  - The old tags are deleted from `index.html` and the plugin inserts the new ones
    (no regex replace).
  - `vite.config.ts` isn't covered by `tsc -b`, so the plugin's logic lives in
    `src/landingHtml.ts`, which is type-checked and tested.
  - resvg-js runs from a Node script with a temporary install in the scratchpad, not
    from `npx`.
  - The R6 banned list is trimmed to the spec: `monte carlo`, `simulat`, `accura`,
    `million`, `trusted by`, `#1`. The bare `%` is dropped.
