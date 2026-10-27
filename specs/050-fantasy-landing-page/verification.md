# Verification: spec 050

## T001 baseline (worktree, before any change)

`cd web && npm ci && npx tsc -b && npm test`

- tsc -b: clean
- vitest: 149 files passed, 2616 tests passed, 0 failed, 0 skipped (measured 2026-10-26)

## T013 preview image (measured 2026-10-26)

- `web/public/og-image.png`: 1200 x 630 (read from the PNG IHDR), 41,057 bytes (target < 300 KB; contract < 1 MB)
- Rendered with @resvg/resvg-js installed in the scratchpad (not in web/package.json), `loadSystemFonts: true`.
- Font: Bahnschrift (Windows system font, first in the SVG's stack) for the wordmark, headline and NFL/NBA pills;
  Segoe UI for the "For Sleeper leagues" line. Judged by looking at the rendered PNG; resvg does not report the
  resolved family, so this is visual, not logged.
- All text lies inside the centred 630 x 630 square (x 285..915): the widest line spans about x 358..842.

## T017 build and tests (measured 2026-10-26)

`cd web && npx tsc -b && npm test && npm run build`

- tsc -b: clean
- vitest: 151 files / 2634 tests passed, 0 failed (baseline 149 / 2616: +2 files, +18 tests =
  7 landingCopy + 7 landingHtml + 4 SignIn landing)
- vite build: succeeded; `dist/` holds index.html (with ld+json, `/boot.js`), boot.js, og-image.png, robots.txt, sitemap.xml
- Not yet done (T018-T021, parent session): serve-based curl checks, browser checks at 390x844 / 1440x900, code review.

## After the code-review fix pass (measured 2026-10-26)

- vitest: 152 files / 2637 tests passed (fix pass added F4 group guard, F6 name assertion,
  F8 `boot.test.ts`, F9 title test). tsc clean, vite build succeeded.

## T018 served exactly as the Dockerfile does (`serve@14.2.6 -s dist --config ../serve.json`, port 4173)

| Path | Status | Content-Type |
|---|---|---|
| `/` | 200 | text/html |
| `/leagues/1/power` | 200 | text/html — byte-identical to `/` (`cmp`) |
| `/robots.txt` | 200 | text/plain |
| `/sitemap.xml` | 200 | application/xml |
| `/boot.js` | 200 | application/javascript |
| `/og-image.png` | 200 | image/png, 1200×630 |

`grep -ci "monte carlo\|simulat"` on the delivered HTML: **0**. Head carries each tag once, absolute
`og:image`/`twitter:image`/canonical, `summary_large_image`, JSON-LD with no rating fields.
Re-run after the fix pass: same statuses, reworded copy present in the delivered HTML.

## T019 browser (built-in pane)

Against the `serve` build (4173):
- **SC-001, 1440×900:** hero, both sports, field above the fold; footer reachable; `scrollWidth` 1440.
- **SC-001, 390×844:** h1 bottom 117px, sports pills 229px, input bottom 336px (fold 844); `scrollWidth` 390;
  no element wider than the viewport; footer reachable by scrolling.
- **SC-003 / no-JS:** delivered HTML parsed with the app bundle removed, signed out: prerender visible
  (`display:flex`), h1 "Your fantasy league, all season long.", h2s This week / The season / Draft / History,
  21 tiles, form disabled.
- **No flash (D3):** same delivered HTML with a stored `bk.user.v1` user: `html[data-bk-user="1"]` set by
  boot.js, prerender `display:none` before the app runs.
- **CSP:** no report-only violations in the console.

Against `vite dev` (5181) proxied to this worktree's backend (`bootRun` on 8081, Postgres 5433):
- signed-in home unchanged ("Welcome back, popsharky", league list).
- **Sign out in the same tab** → landing (4 groups, no `.landing-prerender` in the React tree).
  `data-bk-user` stays set after sign-out; harmless because only the prerender is hidden by it.
- **SC-007:** real sign-in from the landing as `popsharky` (real backend → Sleeper) → "Welcome back, popsharky".

## Success criteria

| SC | Result |
|---|---|
| SC-001 | **PASS** (local, both widths, measured above) |
| SC-002 | **PASS locally**; production re-check **owed after deploy** |
| SC-003 | **PASS** (local) |
| SC-004 | **PASS locally**; production **owed after deploy** (today prod returns HTML for both) |
| SC-005 | **Owed after deploy**: paste the root *and* a deep link into Discord/Slack/iMessage, screenshot here |
| SC-006 | **Owed, Allan**: informal 10-second check with a league-mate (anecdotal by design) |
| SC-007 | **PASS** (behaviour tests unchanged, headline assertions updated, real sign-in) |
| SC-008 | **PASS** (destination + group guard tests; copy checked page by page in code review) |

## Operational, not code (Allan)

`ballknowers.co` → `www.ballknowers.co` is a **302** from Squarespace domain forwarding (measured
2026-10-26). Switch it to a permanent 301 in the Squarespace domain settings.
