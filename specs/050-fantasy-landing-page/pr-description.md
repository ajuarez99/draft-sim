# Spec 050: fantasy-first landing page and share preview

## What changed

- **Landing page.** The signed-out root used to be a sign-in screen pitching "real managers, simulated". It is now a fantasy-first landing page:
  - Headline "Your fantasy league, all season long." with NFL and NBA tags.
  - The existing Sleeper username form in the hero. Its behaviour is unchanged.
  - 21 features below the hero, in the rail's own groups (This week, The season, Draft, History).
  - Basketball-only features carry an NBA tag. The tag is read from `destinations.ts`, not retyped.
- **Share preview.** Every URL now delivers:
  - New title: "Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues".
  - New description, `summary_large_image`, and a 1200×630 `og-image.png` with absolute URLs and dimensions.
  - A canonical `https://www.ballknowers.co/`.
  - A `WebApplication` JSON-LD block with no rating or review fields.
- **Crawler-readable.** A Vite `transformIndexHtml` plugin (`web/src/landingHtml.ts`) writes the head tags and a static copy of the landing into `index.html`. Bots that don't run JS see the text. React replaces it on mount.
- **`robots.txt` and `sitemap.xml`** are real files now. In production today, both return the SPA HTML with a 200. `robots.txt` deliberately has **no Disallow lines**: preview bots obey robots, so blocking `/leagues/` would kill deep-link previews.
- **`boot.js`** (same-origin, CSP-safe) marks signed-in visitors before the app mounts, so they never see the prerendered landing flash.
- **Tab title.** Signed-in tabs keep the short "Ball Knowers" title.

## Why

The landing page and link previews still described the original draft simulator ("runs Monte Carlo drafts"). The product is now a whole-season league companion for NFL and NBA. Group-chat links are how it spreads, so the preview is most people's first impression.

## Guard tests

- **Honesty rule.** The copy may not contain "Monte Carlo" or "simulat", or unmeasured claims such as accuracy, "million" or "#1".
- **Destinations.** Every feature maps to a real destination, in the same rail group.
- **Copy limits.** Title is 65 characters or fewer, description 160 or fewer, each feature line 110 or fewer.
- **Prerender matches the app.** The static prerender's text equals the React page's text, element by element.
- **Head tags.** The transformed head carries each tag once, with absolute preview URLs.
- **Signed-in check.** `boot.js` uses `user.ts`'s `STORAGE_KEY`.

## Reviewer notes

- **ESPN wording.** The adversarial plan review (`plan-review.md`) caught that ESPN support was overstated. ESPN leagues only get a subset of pages (`LeagueAvailability.java`), so the copy is Sleeper-first.
- **Feature lines.** The bug-hunting review (`code-review.md`) caught three lines that described pages wrongly:
  - "who reaches" for basketball, where there is no reach data.
  - Bench points listed under awards.
  - A "this week" rivalry the page doesn't have.

  All three are fixed.
- **Changed assertions.** Only the old headline assertions in `SignIn.test.tsx` and `App.test.tsx` changed. The sign-in behaviour tests are untouched.
- **Preview image.** The PNG was rendered once from `web/scripts/og-image/og-image.svg` with resvg-js, which is not a project dependency.
- **No backend, migration or `api.ts` change.**

## Verification (`verification.md`)

- **Build and tests.** `tsc -b` is clean, vitest passes 152 files / 2637 tests (baseline 149 / 2616), and `vite build` succeeds.
- **Served output.** Run with the Dockerfile's exact `serve@14.2.6 -s dist --config ../serve.json`:
  - `/` and `/leagues/1/power` are byte-identical.
  - `robots.txt` comes back as `text/plain` and `sitemap.xml` as `application/xml`.
  - `og-image.png` is a 1200×630 `image/png`.
  - "monte carlo" and "simulat" appear 0 times.
- **Browser, 390×844.** The headline, sport tags and username field are above the fold, and nothing scrolls sideways.
- **Browser, 1440×900.** The landing is fine and the footer is reachable.
- **Signed-in reload.** No flash of the landing.
- **Sign-out.** Signing out in the same tab shows the landing.
- **CSP.** No report-only violations.
- **Real sign-in.** Signing in as `popsharky` through a local backend reached league home.

**Owed after deploy:**
- Re-run the robots, sitemap and preview checks against production.
- Paste the root link and a deep link into Discord, Slack or iMessage.
- **Allan:** switch the `ballknowers.co` → `www` Squarespace forwarding from 302 to 301.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
