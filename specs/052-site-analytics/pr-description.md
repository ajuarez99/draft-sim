**Title:** Spec 052: cookie-free site analytics via Umami Cloud

## What changed

The site had no usage numbers. This adds cookie-free measurement through Umami Cloud's
free plan, front end only. There is no backend, migration or `api.ts` change.

- **`web/src/analytics.ts`** (new) is the only module that knows the service exists.
  - **Page views:** one per route change. The URL is reduced to a route template, such as
    `/leagues/:league/power`, because real paths carry Sleeper league, draft, manager and
    player ids.
  - **Signed-out visits:** recorded as `/(landing)` plus the template, so shared-link
    arrivals (spec 050's question) stay separate from league page views.
  - **League attribution:** a `league-view` event carries the league id and sport, never a
    person.
  - **Three key actions:** `sign-in`, `league-added` (`via` is `sleeper`, `id` or `espn`)
    and `mock-started` (`from` is `setup` or `live`). Each fires only after success.
- **Wiring:** page and league views go through `AppShell`. The key-action call sites are
  in SignIn, DraftPicker, EspnLeagueSetup, MockSetup and LiveDraftView.
- **`railLeague.ts`:** the rail re-resolves on sign-in. Before this, a league link opened
  signed out kept an empty rail, and sent no league view, after signing in.
- **Disclosure:** one plain-words line on the sign-in screen and in the static landing,
  both read from `landingCopy.ts`.
- **CSP:** `script-src https://cloud.umami.is`, `connect-src https://gateway.umami.is`.
  The policy is still report-only.
- **Config and docs:** `Dockerfile` gets an `ARG VITE_UMAMI_WEBSITE_ID`. DEPLOY.md gets a
  new "Analytics (spec 052)" section; HANDOFF.md gets an entry.

## Why it's built this way

- **Off by default.** Nothing loads unless the build has `VITE_UMAMI_WEBSITE_ID` **and**
  the host is `www.ballknowers.co` or `ballknowers.co` **and**
  `localStorage['umami.disabled']` is unset. Merging this changes nothing in production
  until the build variable is set.
- **`umami.track(object)` only.** In the live `script.js`, the string form
  `track(name, data)` merges in the raw URL and `document.title`. The object form sends
  exactly what we build. A test asserts no string-form call is ever made.
- **Collection host is `gateway.umami.is`.** This comes from reading the script source,
  not from the docs. `data-errors` (the script's stack-trace reporter) is never set.

## Reviewer notes

- `specs/052-site-analytics/` holds the full pipeline:
  - spec and clarifications
  - plan and the adversarial `plan-review.md` (13 findings, all folded in)
  - `tasks.md`
  - `code-review.md` (8 findings, all fixed or documented)
  - `verification.md`
- **Verified locally in a real browser:**
  - Signed-out landing, and a signed-out shared league link.
  - Sign-in, which records one view.
  - League → Managers → league, which sends two league views.
  - Starting a mock: one `mock-started`.
  - 25 mock picks with the board re-simulating after each: no extra views.
  - A failed mock start: no event.
  - No cookies set, and no ids or usernames in any payload.
  - The real tracker script loaded with our attributes on localhost sends nothing.
- **Tests:** `tsc -b` clean, 2,724 vitest tests passing, `npm run build` passes.
- **Not verified until the Umami site exists:**
  - That Umami accepts a relative `url`.
  - Dashboard counts.
  - The CSP in a real production browser.
  - The Railway build arg.

## After merge

1. Create the site in Umami Cloud.
2. Set `VITE_UMAMI_WEBSITE_ID` as a **build** variable on the frontend service, then
   redeploy.
3. Exclude your own browser: `localStorage.setItem('umami.disabled','1')`.
4. Run the production steps in `specs/052-site-analytics/quickstart.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
