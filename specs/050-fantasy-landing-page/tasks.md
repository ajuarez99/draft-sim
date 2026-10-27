---
description: "Task list for spec 050: fantasy-first landing page and share preview"
---

# Tasks: Fantasy-first landing page and share preview

**Input**: `specs/050-fantasy-landing-page/` — spec.md, plan.md (read its "Amendments after
review" section; they override D1–D6 where they differ), research.md, data-model.md,
contracts/public-surface.md, quickstart.md, plan-review.md
**Tests**: included — the plan names guard tests (FR-004/FR-018/SC-008), and the repo's
convention is tests alongside every change.
**Scope**: `web/` only. No backend, no migration, no `api.ts` change.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [x] T001 Confirm the baseline in the worktree: `cd web && npm ci && npx tsc -b && npm test`, then record the pass/skip counts at the top of `specs/050-fantasy-landing-page/verification.md` (create the file) so later runs compare against measured numbers.

## Phase 2: Foundational (blocks US1, US2, US3)

- [x] T002 Create `web/src/landingCopy.ts` per data-model.md. It must have no React import and no browser globals, because `vite.config.ts` imports it in Node. Export:
  - `SITE_ORIGIN = 'https://www.ballknowers.co'` (no trailing slash)
  - `title = 'Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues'`
  - `description`: ≤ 160 chars, names NFL and NBA, power rankings, playoff odds, awards, rivalries, draft grades and mock drafts against your league-mates
  - `headline`: the h1, fantasy-first, no banned words
  - `subheadline`: one sentence
  - `sports: ['NFL','NBA']`
  - `platformsNote = 'Built for Sleeper leagues. Sign in with your Sleeper username. Some season pages also work for ESPN leagues.'`
  - `imageAlt`
  - `groups`: the four non-home `DESTINATION_GROUPS` keys in rail order (`thisWeek`, `season`, `draft`, `history`). Each feature is `{ name, line, destination }`, with `destination` typed `DestinationKey | 'managers'`.
  - The headings and per-feature sports come from `web/src/destinations.ts`: headings from `DESTINATION_GROUPS`, sports from each destination's `sports`. Write a helper `featureSports(f)` that returns `['nfl','nba']` for `'managers'`.
  - Use the existing `'mock'` key for mock drafts and list `'managers'` under `draft`. Word "Follow live" as a draft-day tool.
  - Cover every destination that a fan would recognise. `line` must be ≤ 110 chars.
  - Copy rules: no accuracy, usage or testimonial claims; no "Monte Carlo" or "simulat"; plain fan language.
- [x] T003 [P] Create `web/src/landingCopy.test.ts`. It asserts:
  - (a) every feature's `destination` exists in `DESTINATIONS` or is `'managers'` (SC-008)
  - (b) group keys equal the four non-home `DESTINATION_GROUPS` keys, in order
  - (c) no string anywhere in the copy, lowercased, contains `monte carlo`, `simulat`, `accura`, `million`, `trusted by` or `#1` (FR-001/FR-004, R6 as amended A11)
  - (d) `description.length <= 160`, `title.length <= 65`, and every `line.length <= 110`
  - (e) the title contains `Fantasy Football`, `Basketball` and `Sleeper`
  - (f) `platformsNote` contains `Sleeper` and does not list ESPN as a supported platform: the only ESPN mention is the "Some season pages also work for ESPN leagues" phrasing (FR-009)
  - (g) `featureSports` of every NBA-only destination (`schedule`, `trends`, `players`, `compare`, `stats`, `adp`) returns `['nba']` (FR-009a)

**Checkpoint**: copy module green; every story reads from it.

## Phase 3: User Story 1 — fan lands and understands it's a league companion (P1) 🎯 MVP

**Goal**: The signed-out root is the landing page: a hero that holds the unchanged form, then four feature groups.
**Independent test**: Sign out. At 390×844 and 1440×900, the headline, NFL/NBA and the username field are above the fold, nothing scrolls sideways, the groups render, and a real sign-in reaches league home.

- [x] T004 [US1] Rewrite `web/src/pages/SignIn.tsx` into the landing page. Keep its default export, `usernameFrom`, the `Status` states, the form, its label "Your Sleeper username", the Continue button, the privacy note and both error states exactly as they are (FR-006). Structure:
  - Root element `<div className="content landing">` with its own scroll container (A5): content top-aligned, no `min-height: 70vh` centring.
  - Hero: a `<p className="landing-kicker cond">Ball Knowers</p>`, an `h1` with `headline`, a `p` with `subheadline`, a sports row of `NFL` and `NBA` pills, the form, the privacy note, the errors, and the `platformsNote`.
  - Then one `<section>` per group: an `h2` with the heading from `DESTINATION_GROUPS`, and a grid of feature tiles. Each tile shows the name, the line, and an `NBA` (or `NFL`) tag only when `featureSports` has one sport.
  - A short footer line: "Free. No password."
  - No images of real leagues (FR-005).
  - Never use the class `landing-prerender` on the React tree (A10).
- [x] T005 [US1] Add landing styles to `web/src/styles.css`, replacing the `/* 013 US9: SignIn */` block in place and keeping any `.signin-*` classes the form still uses:
  - Dark tokens only (`--bg`, `--panel`, `--line`, `--teal`, `--volt`, `--muted`, `--text`).
  - A distinct tint per group, so the tiles aren't flat uniform cards (memory: feedback_avoid_flat_uniform_cards).
  - Tiles in a responsive grid that becomes one column under 560px.
  - At 390×844 the hero must keep the h1, the sports pills and the input above the fold.
  - No horizontal overflow.
  - `html[data-bk-user] .landing-prerender { display: none }` (D3).
- [x] T006 [P] [US1] Update the headline assertions only (A4/SC-007): in `web/src/pages/SignIn.test.tsx` lines ~98–108, and in `web/src/App.test.tsx` lines 109/124/134, replace `/real managers, simulated/i` with a query for the new h1 (`getByRole('heading', { level: 1 })` checked against `headline` from `landingCopy`). Replace any `getByText('Ball Knowers')` that now matches more than once with a role query. Change no other assertion.
- [x] T007 [P] [US1] Add tests to `web/src/pages/SignIn.test.tsx`:
  - the four group `h2`s render, in order
  - an NBA-only feature (Schedule grid) shows an `NBA` tag, and a two-sport feature (Power rankings) shows none
  - the page text contains "Sleeper", and any ESPN mention matches `platformsNote`
  - no element carries `landing-prerender`

**Checkpoint**: US1 works in `vite dev`. Run `npm test` and `npx tsc -b`.

## Phase 4: User Story 2 — shared links preview as a fantasy product (P1)

**Goal**: Every URL delivers the new title, description, large card and absolute image without running scripts.
**Independent test**: Build, serve `dist` with the Dockerfile's command, and curl `/` and `/leagues/1/power`. Both must carry the same new tags, and `/og-image.png` must be a 1200×630 PNG.

- [x] T008 [US2] Create `web/src/landingHtml.ts`. It holds pure string functions with no React and no DOM globals:
  - an `esc()` HTML escaper
  - `headTags(copy)`, which emits:
    - title and description
    - `link rel="canonical"` to `${SITE_ORIGIN}/`
    - `og:title`, `og:description`, `og:url`, `og:type=website`, `og:site_name`
    - `og:image=${SITE_ORIGIN}/og-image.png`, `og:image:width=1200`, `og:image:height=630`, `og:image:type=image/png`, `og:image:alt`
    - `twitter:card=summary_large_image`, `twitter:title`, `twitter:description`, `twitter:image`
    - `meta name="theme-color"` set to the `--bg` hex
  - `jsonLd(copy)`: one `<script type="application/ld+json">` `WebApplication` with name, url, description, `applicationCategory: 'SportsApplication'`, `operatingSystem: 'Web'` and `offers {price:'0', priceCurrency:'USD'}`. No rating or review fields (FR-017). Escape `<` inside the JSON.
  - `staticBody(copy)`: the same element tree and class names as T004's JSX, wrapped in `<div class="landing-prerender">`. It includes the form markup with a disabled input and a disabled button (A9), and no inline `style` (CSP).
  - `transform(html)`: inserts `headTags` and `jsonLd` and `<script src="/boot.js"></script>` before `</head>`, and `staticBody` inside `<div id="root">`. It throws if either anchor is missing.
- [x] T009 [US2] Edit `web/index.html`:
  - Delete the old `<title>`, description, `og:*` and `twitter:*` tags (A11). The plugin is now the only writer.
  - Keep the charset, viewport and favicon tags.
  - Wrap nothing else.
- [x] T010 [US2] In `web/vite.config.ts`:
  - Add a plugin `landingHtml()` with `transformIndexHtml: { order: 'pre', handler: (html) => transform(html) }`, imported from `./src/landingHtml`.
  - Add a short comment that says why this exists: bots don't run JS, and the copy has one source.
  - `App.tsx:112` renders `<div className="app">` around AppShell and SignIn, so `staticBody` emits `<div class="app">…</div>` as its outer wrapper, and layout matches before and after mount (A5; analyze F2).
- [x] T011 [P] [US2] Create `public/boot.js` at `web/public/boot.js`, about 10 lines in an IIFE with try/catch:
  - Read `localStorage.getItem('bk.user.v1')` and parse it.
  - If `typeof parsed.sleeperUserId === 'string'`, set `document.documentElement.setAttribute('data-bk-user','1')`.
  - Mirror `user.ts:readStored`, and add a comment pointing at it.
- [x] T012 [P] [US2] Create the preview image source `web/scripts/og-image/og-image.svg` (1200×630):
  - `--bg` dark background, with "BALL KNOWERS" in a condensed face (Bahnschrift, then a fallback stack).
  - A short headline, NFL/NBA pills, and a few abstract rounded tiles in the group tints.
  - All text inside the centered 630×630 square (FR-011).
  - No real names or data.
- [x] T013 [US2] Create `web/scripts/og-image/render.mjs`, which takes the svg path and the out path from argv and renders them with `@resvg/resvg-js`, loading system fonts. Then:
  - Install resvg-js into a scratchpad directory, not into `web/package.json`, and run the script.
  - Write `web/public/og-image.png`.
  - Record in verification.md the measured dimensions, the file size (target < 300 KB) and which font actually rendered.
  - Add a README comment at the top of render.mjs with the exact command.
- [x] T014 [P] [US2] Create `web/src/landingHtml.test.ts`:
  - (a) Run `transform()` on the real `web/index.html` source read with `fs`. The output contains exactly one `<title>`, `summary_large_image`, an absolute `og:image` with width 1200 and height 630, canonical `https://www.ballknowers.co/`, `application/ld+json` and `/boot.js`, and no `Monte Carlo` or `simulat` anywhere in the head.
  - (b) FR-018 text equality (A9): parse `staticBody(copy)` with `DOMParser`, and render `<SignIn/>` with Testing Library. Compare `textContent` (whitespace-collapsed) for the h1, the subheadline, every h2, and every feature name and line, in order.
  - (c) The JSON-LD parses as JSON and has no `aggregateRating` or `review`.
  - (d) `staticBody` contains no `style=`.

**Checkpoint**: `npm run build` produces `dist/index.html` with the tags, and the tests pass.

## Phase 5: User Story 3 — search engines can read and index (P2)

**Goal**: A real robots file, a sitemap, and readable landing text without JS.
**Independent test**: Run quickstart.md step 2 against local `serve`.

- [x] T015 [P] [US3] Create `web/public/robots.txt` (A3, no Disallow lines):
  ```
  User-agent: *
  Allow: /

  Sitemap: https://www.ballknowers.co/sitemap.xml
  ```
- [x] T016 [P] [US3] Create `web/public/sitemap.xml`: a valid `urlset` (xmlns `http://www.sitemaps.org/schemas/sitemap/0.9`) with exactly one `<url><loc>https://www.ballknowers.co/</loc></url>`.

## Phase 6: Polish, review, verification

- [x] T017 Run `cd web && npx tsc -b && npm test && npm run build`. Compare the counts against T001 and record them in verification.md.
- [x] T018 Serve the built output exactly as the Dockerfile does: `npx serve@14.2.6 -s dist --config ../serve.json -l 4173`, run in the background. Then work through quickstart.md step 2 with `curl -sD -`:
  - `/`, `/leagues/1/power`, `/robots.txt`, `/sitemap.xml`, `/boot.js` and `/og-image.png`: record each status and content-type.
  - Confirm the HTML for `/` and `/leagues/1/power` is identical.
  - Run `grep -ci "monte carlo\|simulat"` on the head and confirm it finds nothing.
- [x] T019 Check in the built-in browser against that `serve` URL, not `vite dev` (A10):
  - signed out, at 390×844 and 1440×900: above the fold (SC-001), no horizontal scroll, screenshots of both
  - a real sign-in with `popsharky` lands on league home (SC-007)
  - signed in, a hard reload of `/` shows no landing flash (D3)
  - signing out in the same tab shows the landing
  - with JS disabled (or via `curl`), the text is readable (SC-003)
  - the CSP report-only console shows no new violations
- [x] T020 Bug-hunting code review → `specs/050-fantasy-landing-page/code-review.md`. Fix the findings.
- [x] T021 Write `specs/050-fantasy-landing-page/verification.md` with measured results for every SC. Mark SC-002, SC-004 and SC-005 production items as **owed after deploy**. Add the operational note: the apex 302 → 301 is set in Squarespace domain settings and is Allan's step.

## Dependencies & Execution Order

- T001 → T002 → (T003 ∥ T004)
- T004 → T005 → T006/T007
- T002 → T008 → T009 → T010 → T014
- T011, T012/T013, T015 and T016 are independent once T002 exists
- T017–T021 run last, in order
- US1 and US2 share `SignIn.tsx`'s markup, so T008's `staticBody` follows T004's final JSX. Do US1 before US2.

## Parallel Example

```text
After T002: T003 (copy tests), T011 (boot.js), T012 (svg), T015 (robots), T016 (sitemap)
After T004: T006 and T007 (test files) beside T005 (CSS)
```

## Implementation Strategy

MVP is US1, the landing page itself, which is shippable alone. US2 is the share preview and is where most of the "move off Monte Carlo" value sits for links in group chats. US3 is small and rides along. All of it ships as one PR after Allan's go-ahead.
