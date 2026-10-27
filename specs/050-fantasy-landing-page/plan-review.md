# Plan review: spec 050 (adversarial, read cold)

**Date**: 2026-10-26 · **Reviewer**: separate pass, plan read against the real tree at `76e9740`.
**Method**: every claim checked against code. Where marked **MEASURED**, I ran it against a scratch
copy of `web/` (outside the repo, `node_modules` linked from the main checkout, link removed after);
nothing was left in the repo. Everything else is **INFERRED** from reading code or docs, and says so.

## What held up (measured)

- **D1 is sound.** `destinations.ts` imports only `import type { DraftSummary, Sport } from './api'`
  (destinations.ts:1), erased at compile time; no React, no `window`/`localStorage` at module load.
  MEASURED: a scratch `src/landingCopy.ts` importing `DESTINATION_GROUPS` + `type DestinationKey`,
  imported by `vite.config.ts` and used in a `transformIndexHtml` plugin, passes `tsc -b` and
  `vite build`; the four group headings landed in `dist/index.html`. Vite also leaves
  `<script src="/boot.js">` alone with no warning.
- **D2.3 is sound.** MEASURED (React 18.3.1, jsdom, dev build, StrictMode): `createRoot(el).render()`
  on a `#root` that already holds `<div class="landing-prerender">…` replaces it on first commit;
  `innerHTML` afterwards is React's only, zero console errors/warnings.
- **R2 resolved: no `serve.json` change needed.** MEASURED with the Dockerfile's exact command
  (`serve@14.2.6 -s dist --config ../serve.json`):
  `/robots.txt` → 200 `text/plain; charset=utf-8`; `/sitemap.xml` → 200 `application/xml`;
  `/boot.js` → 200 `application/javascript; charset=utf-8`; `/leagues/1/power` → 200 `text/html`
  (the SPA page); all carry the CSP-report-only and nosniff headers. T001 can be recorded as done.
- **Signed-out deep links (plan's claim holds).** `AppShell` returns `<>{children}</>` when no user
  (AppShell.tsx:155); `App` renders `<SignIn />` at every path while signed out (App.tsx:113-197), and
  after `setUser` the same URL's `<Routes>` render — deep link preserved, no navigation.
- **JSON-LD under CSP** (INFERRED from the HTML spec): a `type="application/ld+json"` block is a data
  block, never "prepared" as script, so `script-src 'self'` does not apply.

## Findings

### F1 — HIGH — The ESPN claim in both the spec (FR-009) and plan D4 is false
"ESPN leagues can be added … for the season pages" overstates what's live. `LeagueAvailability.orderedFixed()`
(backend/.../engine/LeagueAvailability.java:101-115) marks these **fixed-unavailable for every ESPN league**:
draft room, draft grades, player stats/trends/calculator, schedule grid, **awards (superlatives)**,
**weekly pot**, **matchups & awards (weekly report)**, **luck (expected wins)**, **playoff odds (forecast)**,
recap, lineup check. That leaves ESPN with power rankings, team strength (data-driven, can still be
refused), bench points, standings/history, rivalries and ADP compare. Adding an ESPN league also needs
login cookies for a private league (EspnLeagueSetup.tsx `ESPN_PRIVATE_NO_ACCESS`).
**Amend**: spec FR-009 and the edge case (dated "amended after review" note) and D4's platform note to
something like "Sign in with your Sleeper username. ESPN leagues can be added for standings, power
rankings and a few other pages; most season tools and the draft room are Sleeper-only." Better: drop
ESPN from the hero entirely and put that sentence in the footer. Also confirm ESPN add actually works in
prod before claiming it at all (MEMORY lists 035's happy path as unverified). INFERRED from code.

### F2 — HIGH — Six of the listed features are NBA-only, and the plan never marks sport
`destinations.ts` gives `sports: ['nba']` to schedule, trends, players, compare, stats (+calculator) and
adp. The page sells "fantasy football and basketball" then lists them flat, so an NFL fan reads
"Schedule grid", "Player stats", "ADP & prices" as theirs. Data-model has no sport field on a feature.
**Amend**: `LandingFeature` gains no hand-typed sport; instead the renderer reads `sports` off the
destination row and shows an "NBA" tag when it is not both. The SC-008 guard test also asserts every
`destination` key is a real row (already planned) **and** that the tag rendered equals the row's
`sports`. Same for status-gated rows: `live` exists only for `pre_draft`/`drafting`
(destinations.ts `requiresStatus`) — fine to list "Follow a live draft", but SC-008 ("a page a signed-in
user can actually open today") can't be literally true in October; reword SC-008 to "exists in the
app's navigation table" and say so. INFERRED from code.

### F3 — HIGH — robots `Disallow: /leagues/` (for `*`) likely kills deep-link previews on some apps
US2 scenario 3 / FR-010 want the site preview on **every** URL, and deep league links are exactly what
gets pasted. Twitterbot and LinkedInBot honour robots.txt (per their published card/inspector docs — not
measured here), so `User-agent: *` + `Disallow: /leagues/` means no card on X/LinkedIn for those links.
And a Disallowed URL can't be fetched, so Google never sees its canonical (→ "indexed, though blocked").
Every deep URL serves the same landing HTML (measured above), so there is nothing private in it to hide.
**Amend** D5 to one of: (a) drop the Disallow lines, rely on canonical → `/` and the sitemap
(FR-015's "not invited" = absent from sitemap + canonicalised elsewhere; amend FR-015/US3-5 wording in
place); or (b) keep them but add explicit groups `User-agent: Twitterbot`, `LinkedInBot`,
`facebookexternalhit`, `Slackbot`, `Discordbot` each with `Allow: /` (a named group overrides `*`).
Recommend (a). `Allow: /$` is redundant either way (default is allow). Verification must paste a
**deep** link, not just the root, into one app (extend SC-005).

### F4 — HIGH — "Existing sign-in tests unchanged" (SC-007, plan Structure) is false
The tests encode the old copy: SignIn.test.tsx:98-108 asserts the exact h1 "Your league's real
managers, simulated. …"; App.test.tsx:109, 124, 134 assert `heading /real managers, simulated/i` for
sign-out, signed-out deep link and junk path. Also SignIn.test.tsx:101 `getByText('Ball Knowers')`
throws if the name appears twice (kicker + footer/wordmark), and :116-117 require exactly one `input`
and one `.signin-continue` — fine only if the feature section adds no inputs/buttons.
**Amend** SC-007 and plan: "the behavioural sign-in tests (three outcomes, profile URL, Enter submits,
one input) pass unchanged; the four copy assertions are updated to read the new headline from
`landingCopy`" — and list App.test.tsx as touched. INFERRED from code (not run).

### F5 — MEDIUM — The existing CSS chain will clip the top of a landing taller than the viewport
`.app { height: 100vh; display:flex; flex-direction:column }` (styles.css:231-236); signed out, the page
root is `.content.signin-welcome`: `flex: 1 1 auto` with `min-height: 70vh` and
`justify-content: center` (styles.css:1081, 5468-5471). Add the feature groups inside that element and it
shrinks to 100vh while its content overflows **both** ends equally — the top half (kicker/h1) goes
above the scroll origin and can't be scrolled to. D4 only mentions changing `min-height`.
**Amend** D4: the landing root must not be a vertically-centred child of a fixed-height `.app`: give it
`overflow-y: auto; flex: 1 1 0; min-height: 0` (own scroller, like `.app-main`, styles.css:1958-1960)
and centre only the hero block. The prerender wrapper must sit in the same structure — React renders
`<div class="app">` around SignIn (App.tsx:112), so the static markup needs that `.app` wrapper too or
pre/post-mount layout differs. INFERRED from CSS; verify in the browser at 390×844 and 1440×900.

### F6 — MEDIUM — FR-016 title phrases vs R5/data-model
FR-016 requires the phrases "fantasy football", "fantasy basketball", "Sleeper league". R5's title
("Ball Knowers — Fantasy football & basketball league companion for Sleeper") contains neither
"fantasy basketball" nor "Sleeper league", and is 75 characters (MEASURED, `wc -m`), not "under ~60".
Data-model's test rule ("fantasy football", "basketball", "Sleeper") is weaker than the FR.
**Amend** either the FR (to "fantasy football, basketball, Sleeper") or the title, e.g.
"Fantasy football & fantasy basketball tools for your Sleeper league | Ball Knowers" (82 — accept
truncation, put the keywords first). Make the test assert exactly what the FR says.

### F7 — MEDIUM — Managers and Mock drafts have no group
D1 lets a feature point at `'managers' | 'mockDrafts'`, but data-model says groups are "exactly the four
non-home `DESTINATION_GROUPS`", and neither global entry belongs to one (Rail.tsx:174-195 renders them
under "Menu"). `'mock'` already exists as a `draft`-group key (destinations.ts, "Mock it"), so
`'mockDrafts'` is redundant. Also the guard can't check `'managers'` against anything — it's a literal.
**Amend**: use `'mock'` for mock drafts; place Managers (tendencies + head-to-head) explicitly in
History or Draft and say which; guard test asserts `'managers'` against the Rail's actual link
(`to="/managers"`) or just accepts it's an unchecked literal and says so.

### F8 — MEDIUM — The build-output test reads `dist/`, which doesn't exist when tests run
Quickstart runs `npm test && npm run build`; the Dockerfile runs only `npm run build`. A vitest test
reading `dist/index.html` fails or skips on a clean checkout and is silently stale otherwise.
**Amend**: export the plugin's pure transform (`landingHtml(htmlSource) → string`) from
`landingHtml.ts`, and test it on the real `web/index.html` source text. Keep the `dist/` check as a
quickstart curl step, not a unit test.

### F9 — MEDIUM — "Identical text" must be compared as parsed text, and the form placeholder is a CLS risk
Static output is HTML-escaped (`&amp;`, `&#39;`); React escapes differently. Compare `textContent`
(whitespace-normalised) element by element — h1, sub, each h2, each tile name/line — after parsing the
static string with jsdom, never `innerHTML`. Typographic apostrophes in copy (`’` vs `'`) must come from
the same string, not be retyped in JSX. For the hero: an "equal-height placeholder" is a guess at
two breakpoints plus font-swap. **Amend** D4: render the real form markup statically (same label,
`input` and button classes, both `disabled`) so height is exact and no-JS visitors see the field;
React replaces it on mount. Static markup must use no `style=""` attributes (CSP `style-src` lacks
`'unsafe-inline'`; report-only today, would break when enforced). INFERRED.

### F10 — LOW — boot.js edge cases
(a) React's own landing must **not** carry the `.landing-prerender` class: `html[data-bk-user]` is set
once at load and never cleared, so a user who signs out in the same tab would get a hidden landing.
(b) Signed-in + boot.js hides the prerender → blank until the bundle runs, same as today; fine.
(c) In `vite dev`, `styles.css` is injected by JS, so the prerender is unstyled until the bundle runs —
"no flash" and SC-001 checks must be done on the built `serve` output, not `npm run dev`. Add that to
quickstart step 3/4. INFERRED.

### F11 — LOW — Smaller plan gaps
- `vite.config.ts` is outside `tsconfig.json`'s `include: ["src"]`, so `tsc -b` never type-checks the
  plugin (MEASURED: scratch build passed with an untyped plugin). Keep the plugin a thin call into
  `src/landingHtml.ts` so the logic is type-checked and tested there.
- Remove the old title/description/og/twitter tags from `index.html` and let the plugin emit them,
  rather than regex-replacing the multi-line `<meta>` (index.html:7-14).
- R4: `@resvg/resvg-js` is a library, not a CLI; "npx" won't run it. Say `npm i --no-save` in a scratch
  dir + `node render.mjs`, and check the PNG's actual pixel size in verification.
- R6 bans "simulat" across **all** copy, stricter than FR-001 (headline/sub/title/description/preview).
  Fine, but then the draft-room line must still be honest about being a prediction ("who's likely gone"),
  not upgraded to certainty to avoid the word.
- SC-002's "three deep URLs" are byte-identical to `/` by construction (measured: same HTML); one is
  enough evidence, three adds nothing.

## Spec ↔ plan coverage

Covered: FR-001-008, 010-014, 017-018. Under-specified: FR-009 (F1), FR-015 (F3), FR-016 (F6), SC-007 (F4),
SC-008 (F2). Nothing in the plan builds what the spec rules out (no per-league previews, no new public
pages, no analytics, no social proof). The apex 302 → 301 stays an operational step for Allan — correct.
