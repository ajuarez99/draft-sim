# Research: spec 050

## R1. How to get copy and tags into HTML that bots read

- **Decision**: Build-time injection via a Vite `transformIndexHtml` plugin fed by a
  shared TS copy module.
- **Rationale**: The site has exactly one public page and every route serves the same
  `index.html` (`serve -s`). A build-time write gives non-JS crawlers and preview bots
  everything with no server, no headless browser, no new runtime dependency.
- **Alternatives**: react-helmet(-async) — client-side only, bots don't run JS (research
  sources agree). Prerender.io/react-snap — Puppeteer in the build, heavy for one page.
  Server-side per-route injection — needed only for per-league previews, out of scope.
  Hand-editing `index.html` — duplicates the copy (FR-018).

## R2. Will `serve -s` serve `robots.txt`/`sitemap.xml` correctly?

- **Status**: to MEASURE in T001, not assumed. Expectation: `serve` serves existing
  files before the SPA rewrite and infers `text/plain` / `application/xml` from the
  extension; `cleanUrls` should not affect them. If either is wrong, add explicit
  `Content-Type` headers in `serve.json`.
- Measured today in prod: both URLs return the SPA HTML with 200, because the files
  don't exist.

## R3. Signed-in flash from pre-rendered markup

- **Decision**: same-origin `public/boot.js` sets `html[data-bk-user]` from
  `localStorage['bk.user.v1']`; CSS hides `.landing-prerender`.
- **Rationale**: CSP is `script-src 'self'` (report-only now, presumably enforced later);
  an inline script would be reported. A synchronous same-origin script in head is ~10
  lines and one tiny request.
- **Alternatives**: `<noscript>` prerender — no flash, but Google treats noscript content
  as weaker and it doesn't help the "no flash of different headline" edge case for
  signed-out users on slow loads. Accepting the flash — a signed-in user would see a
  marketing page every load; rejected.

## R4. Rasterizing the preview image

- **Decision**: SVG source → PNG via `@resvg/resvg-js` run once from a script
  (`npx`/temporary install, not added to `package.json`), using a Windows system
  condensed face (Bahnschrift) close to the app's Oswald, with a fallback stack.
- **Rationale**: deterministic, no browser, reproducible from source in the repo. PNG
  because some Slack/Discord clients reject WebP. Target < 300 KB.
- **Alternatives**: screenshot of a live page — not reproducible; bundling Oswald TTF —
  Google Fonts ships it, but adds a font download step for one image.
- **Open**: if Bahnschrift isn't available to resvg, fall back to Arial Black / Segoe UI
  and say so in verification.

## R5. Keywords and title

- **Decision** (amended A6): Title `Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues`; first draft was `Ball Knowers — Fantasy football & basketball league companion for
  Sleeper`; description names power rankings, playoff odds, awards, rivalries, draft
  grades and mock drafts against your league-mates, NFL and NBA.
- **Rationale**: matches how fans search (league platform + feature + sport), ffwrapped's
  pattern ("…for your Sleeper, ESPN, or Yahoo league"). Kept under ~60 chars for the
  title where possible; description ~155 chars.

## R6. Guarding the honesty rule in code

- **Decision**: a test fails if landing copy contains `monte carlo`, `simulat`,
  `accura`, `#1`, `million`, `trusted by`, `%` — words that either name the old pitch
  (FR-001) or make an unmeasured claim (FR-004).
- **Rationale**: copy drifts in later edits; a test makes the rule survive them.

## R7. What competitors do that we deliberately skip

Accuracy awards, user counts, testimonials (DraftSharks), app-store badges, a blog/news
feed, paywall upsell. All either unmeasured claims or content this project doesn't
produce.
