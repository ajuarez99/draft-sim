# Adversarial plan review: 079 Redraft share preview

**Date**: 2026-11-21 · **Reviewer**: adversarial plan-review pass (read cold, no 079 code exists)

**Inputs read**: `AGENTS.md`; `specs/079-redraft-share-preview/spec.md`, `plan.md` (no research, data model, contracts or checklists exist yet); `specs/074-redraft-seo/plan-review.md` (format), `code-review.md` (CR-03), `DEPLOY.md` "Security headers" and "How the web container serves it now". Web: `src/siteFiles.ts`, `src/landingHtml.ts`, `src/redraftPages.ts`, `src/historicalShare.ts`, `src/historicalCopy.ts`, `src/historicalDaily.ts`, `src/pageTitle.ts` (`usePageHead`/`applyPageHead`), `src/pages/SharedResult.tsx`, `src/App.tsx` (game routes), `vite.config.ts`, `Dockerfile`, `serve.json`, `railway.toml`, `package.json`, `tsconfig.json`, `.gitignore`, `.dockerignore`. Backend: `historical/HistoricalGameService` (`publicGame`, `reduced`, `state`, `abandon`, advance path), `HistoricalGameRepository` (every `update`), `PublicGame`, `DraftScore`, `HistoricalDailyService`, `api/RedraftPublicController`, `api/LeagueLockInterceptor`, `api/ApiTokenFilter`, `config/WebConfig`, `LeagueLockMappingsIT`, `RedraftPublicMvcIT` (outline), root `Dockerfile`, `railway.toml`, `application.yml` (rounds). The uncommitted diff in the sibling worktree `redraft-daily-number-1df2f8` (spec 078).

**Checks I ran** (read-only, scratchpad only; no tracked file written except this one):

- `npm pack serve@14.2.6 serve-handler@6.1.7` into the scratchpad and read `serve/build/main.js` (the CLI) and `serve-handler/src/index.js`.
- A probe in the scratchpad: `serve-handler@6.1.7` behind `node:http` with the plan's config shape, with and without `public`, fetching `/`, `/serve.site.json`, `/server.site.json`, `/package.json` with `Accept-Encoding: gzip, br`.
- `curl -D -` against production: `https://www.ballknowers.co/`, `/game/1`, the main JS asset with identity/gzip/br, `/serve.site.json`, `/package.json`, `robots.txt` on both hosts, and timing of `GET https://api.ballknowers.co/api/history/public/games/1` (key names only printed).
- Read-only `SELECT`s on `localhost:5433/draftsim`: game counts by sport/status/format, board and result sizes, one stored result's key list.
- Fetched the Adoptium `21/jre/ubuntu/jammy` Dockerfile and the Ubuntu jammy `fontconfig-config` package page.
- `git branch -a`, `git worktree list`, and `git status`/`git diff` in the spec 078 worktree.

**Not run**: Docker (the daemon is not running on this machine, so the font check in `eclipse-temurin:21-jre-jammy` is NOT done), the backend or web suites, any Java2D render, any write anywhere.

**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | HIGH | Web server | `serve-handler` without `public` serves the working directory: `/` is 404 (healthcheck fails) and the config files are published |
| PR-02 | HIGH | Web server | The `serve` CLI compresses (br/gzip) and the library does not: the 1 MB JS bundle would ship uncompressed |
| PR-03 | MEDIUM | Web server | "Every other path behaves exactly as today" is false: ETag off, no request log, no SIGTERM handling |
| PR-04 | MEDIUM | Web server | The leagues and combined builds have no `app-shell.html`; loading it unconditionally crashes the leagues service |
| PR-05 | MEDIUM | Client head | After mount, `usePageHead` puts the generic title back but leaves the per-game image; that image then sticks after client-side navigation |
| PR-06 | LOW | Doc drift | The spec cites the league landing's head (`landingHtml.ts:53-71`) as app-shell's; the real app-shell head lacks 8 of the tags |
| PR-07 | MEDIUM | Contradiction | FR-008's "daily #23 · 🏆" drops the date that in-flight spec 078 keeps ("#8 · Nov 21"); 078 also changes the record constructor 079 tests would use |
| PR-08 | MEDIUM | Rule twice | Java copies of `outcomeHeadline`, `ordinal`, `isOldFormat`, `dayLabel` with no shared vectors; the "neutral voice" adds a third phrasing |
| PR-09 | MEDIUM | Privacy | In full mode the builder reads `hindsight.pointsFor`, a field the reduced view drops; FR-002 holds only by arithmetic, not by construction |
| PR-10 | MEDIUM | Honesty | "Can you beat it?" is true only on the day; an immutable, long-cached card says it forever |
| PR-11 | MEDIUM | Latency | Every uncached `/game/<id>` load, human or bot, waits on a backend round trip; when the backend is down, each one waits the full 1.5 s |
| PR-12 | MEDIUM | Backend perf | Renders are CPU and allocation work on virtual threads, with no limit, on enumerable ids; no native-memory measurement planned |
| PR-13 | MEDIUM | Fonts | DejaVu is likely present but not checked; it has no emoji, a missing fontconfig throws an `Error`, and the local (Windows) render will not look like prod |
| PR-14 | MEDIUM | Other sport | NFL cannot be proved on 5433 (0 NFL games, 0 NFL season rows); the plan's verification would prove NBA and assert NFL |
| PR-15 | LOW | Path parsing | Trailing slash and query string are not specified; `/history/<id>` share links stay generic, unstated |
| PR-16 | LOW | Spring | `produces`, the 400 on a non-numeric id, and error bodies under `Accept: image/png` are not specified |
| PR-17 | LOW | Packaging | The version is pinned in two places, there is no lockfile in the runtime stage, `server.site.json` is not git-ignored, and `server/` is not type-checked |
| PR-18 | LOW | Image URL | The plan does not say which host `og:image` points at |
| PR-19 | LOW | Caching | The data never changes, but the picture does (078 numbering, copy edits) while unfurlers cache it; the URL is unversioned |

**Count: 0 CRITICAL, 2 HIGH, 11 MEDIUM, 6 LOW.**

Checked and **OK** (no finding):

- **Line references.** `siteFiles.ts:53` `APP_ONLY_SOURCES` and `:66` `serveConfig` are correct, and so are `HistoricalGameService.publicGame` at `:296` and `reduced` at `:317`. The `landingHtml.ts` reference is wrong (PR-06).
- **serve-handler routing semantics.** `cleanUrls`, `trailingSlash`, `rewrites`, `headers`, `directoryListing` and the custom `404.html` are all implemented inside `serve-handler` (`src/index.js` 121-160, 194-256, 685-710). The CLI only adds what PR-01/02/03 list. `serve@14.2.6` pins `serve-handler` at exactly `6.1.7` (VERIFIED, its package.json).
- **Lock and token.** `/api/history/**` is excluded from the lock by Spring's PathPattern (`WebConfig.java:52-54`), so `/games/{id}/preview` and `/games/{id}/preview.png` are public with the lock ON. The plan adds both to `LeagueLockMappingsIT`'s `EXPECTED_PUBLIC_HISTORY_AND_LOOKUP` (they must be listed there, `LeagueLockMappingsIT.java:61-78`). `API_TOKEN` is blank in prod (`DEPLOY.md:168`), and `DEPLOY.md:292` already says to exempt `/api/history/public/**` if it is ever switched on.
- **A COMPLETE game is immutable** (VERIFIED). `writeResult` is the only writer of `result` (`HistoricalGameRepository.java:209`). `abandon` calls `requireDrafting` (`HistoricalGameService.java:449`). The claim's `moveOwner` changes only `sleeper_user_id`. `chosen_season` and `daily_date` are set at create. No `delete from historical_game` exists. So caching a COMPLETE game's preview by id is sound for the data (PR-19 covers the code).
- **What the reduced view keeps** (VERIFIED, `HistoricalGameService.java:317-361`): `userFinish`, `userOutcome`, `format`, the user's own standings row, `hindsight.{label,userPointsFor,userBeatIt,draftScore}`, and `pickFinishes[].{pickNo,mark}`. That matches the spec's Context list.
- **No migration**, so no V-number collision. 077 holds V52 and the 078 worktree adds none.
- **Spring 6 path matching.** Suffix pattern matching is gone, so `/games/{id}` cannot swallow `/games/42.png`, and `/games/{id}/preview.png` is a literal segment. `png` is on the RFD safe-extension list, so no forced `Content-Disposition`.
- **Node 20** (`node:20-alpine`) has global `fetch` and `AbortSignal.timeout`.
- **`api.ballknowers.co/robots.txt` is 404**, so image fetchers that honour robots.txt (Twitterbot) are not blocked (VERIFIED).
- **The emoji are kept out of the description**, and the squares are drawn as rects, not glyphs. Both are right.

## Findings

### PR-01 (HIGH, VERIFIED) - `serve-handler` without `public` serves the working directory

**What.** The plan has `server.mjs` call `handler(req, res, serveConfig)` with the config read from `serve.site.json`. That file has no `public` key (`siteFiles.ts:66-77`). The CLI fills it in: `config.public = relative(cwd, resolve(directoryToServe))` (`serve/build/main.js:475-481`). The library falls back to `process.cwd()` when it is missing (`serve-handler/src/index.js:550`). The container's working directory is `/app` (`web/Dockerfile:31`).

**Measured** (scratchpad probe, the same layout):
- Without `public`: `/` answers **404**, while `/server.site.json`, `/serve.site.json` and `/package.json` answer **200**.
- With `public: 'dist'`: `/` answers 200 and all three answer 404.

**Why it fails.** Railway's healthcheck is `/` (`web/railway.toml`), so the deploy fails. If the working directory is "fixed" some other way, `server/*.mjs`, `node_modules/` and both config files become public.

**Change.**
- Set `public` to the absolute path of `dist`, resolved from `import.meta.url`, not from the working directory.
- `server.test` asserts that `/`, `/nba` and a season page are 200, and that `/serve.site.json`, `/server.site.json`, `/package.json` and `/server/server.mjs` are 404.

### PR-02 (HIGH, VERIFIED) - the CLI compresses and the library does not

**What.** `serve` wraps every request in `compression@1.8.1` before the handler (`main.js:166, 186-188`). `serve-handler` has no compression dependency.

**Measured in prod.** `/assets/index-CCtW4XKK.js` is 1,024,746 bytes with identity encoding, and it is served `content-encoding: br` or `gzip` (with `vary: Accept-Encoding`) when asked. The probe confirms bare `serve-handler` sends no `content-encoding`. The compression is attributed to `serve` and not to Railway's edge by reading the CLI source (REASONED), but either way the plan removes the only compression the code applies.

**Why it fails.** Every first visit to every page on both web services would download about 4x the bytes. It is a user-visible slowdown on phones, which is exactly where shared links get opened.

**Change.**
- Add `compression@1.8.1` (exact pin, the CLI's version) and run it before both the preview branch and `serve-handler`, the way the CLI does.
- Add a test that `Accept-Encoding: br` on a JS asset gets `content-encoding: br`.
- Correct the plan's "one new runtime dep" to two.

### PR-03 (MEDIUM, VERIFIED) - other CLI behaviour the plan drops silently

**What.** The plan says every other path behaves "exactly as today". Three things differ:
1. **ETag.** The CLI forces `config.etag = true` (`main.js:497`). The library default is `false` and sends only `Last-Modified` (`index.js:195, 227-235`). Prod sends `etag: "cfb8…"` today (VERIFIED by curl). The probe shows the library sends no ETag.
2. **Request log.** The CLI logs two lines per request (`main.js:170-197`), and those are the Railway web logs today.
3. **Shutdown.** The CLI registers SIGINT/SIGTERM and calls `server.close()` (`main.js:133-143`). With `CMD ["node", ...]`, Node is PID 1, and a PID 1 process has no default SIGTERM action. Unless it installs a handler, it ignores the signal until it is killed (REASONED, standard Linux behaviour).

**Change.**
- Pass `etag: true`.
- Install a SIGTERM handler that calls `server.close()`.
- Either log one line per request or say in `DEPLOY.md` that the per-request log is gone.
- Catch a rejected `handler()` promise and answer 500, rather than letting an unhandled rejection kill the process.
- Replace "exactly as today" with the list of what was kept and what was changed.

### PR-04 (MEDIUM, VERIFIED) - no `app-shell.html` outside the Redraft build

**What.** `vite.config.ts:97-112` writes the page files, `app-shell.html` included, only when `timeMachineBuild && data`. The leagues and combined builds have only `index.html`. `web/Dockerfile` is shared by both Railway web services (`DEPLOY.md:374`), so the leagues service runs `server.mjs` too.

**Why it fails.** The plan says the server "loads `dist/app-shell.html` into memory" at start, with no condition. On the leagues service that read throws and the service never comes up.

**Change.**
- Read the shell only when `server.site.json.site === 'timemachine'`, and fail loudly at start if it is missing in that mode.
- Add a server test with a leagues-shaped temp dist (no shell): it starts and `/game/42` is the `**` rewrite to `index.html`, as today.

### PR-05 (MEDIUM, VERIFIED code; the effect on JS-running previewers is REASONED) - the head after mount and after navigation

**What.** Three places call `usePageHead` with the generic head: `SharedResult` (`SharedResult.tsx:74`), `NoFinishedGame` (`:44`) and `HistoricalRoom` (`:155`). Each passes `title: brand`, `description: appShellDescription`, `noindex: true`.

**What `applyPageHead` then does** (`pageTitle.ts:51-75`):
- Changes: `document.title`, `og:title`, `description`, `og:description`.
- Removes: `twitter:title`, `twitter:description`, `og:url`, the canonical.
- Leaves alone: `og:image`, `og:image:alt`, `og:image:width/height/type` and `twitter:image` (by design, `pageTitle.ts:50`: "the same on every page").

**Two consequences.**
1. A second after load, the tab and the DOM say "Ball Knowers Redraft" with the generic description, next to game 42's picture and alt text. A previewer that runs scripts (Apple's LinkPresentation loads pages in WebKit; whether iMessage's sender-side fetch waits for scripts is not verified) could unfurl the generic words over the per-game image.
2. After a client-side move from `/game/42` to `/nba/2016-17`, the season page's DOM still carries game 42's `og:image` and `og:image:alt`, because nothing resets the image tags. This breaks the "site-wide, same on every page" assumption in `pageTitle.ts:50`.

**Change (decision for Allan on point 1).**
- Either `SharedResult` keeps the server's per-game words (for example, skip the title and description writes when the injected head carries a marker for this id), or accept that the tab shows the generic title and write that down.
- In both cases, `applyPageHead` must reset the image tags to the site default (or remove the per-game ones) on every page. Add a test that `/game/42` → `/nba` leaves no game image behind.

### PR-06 (LOW, VERIFIED) - the spec cites the wrong head

**What.** spec.md says app-shell "carries the generic head … (`web/src/landingHtml.ts:53-71`)". Lines 53-71 are `headTags`, the league landing's head. That head has `og:url`, `og:image:width/height/type/alt`, `twitter:title`, `twitter:description` and `twitter:image:alt`.

**The real app-shell head** is `pageHeadTags`'s `appShell` branch (`landingHtml.ts:168-182`). It has none of those 8 tags. Its robots tag is `<meta name="robots" content="noindex">` with no self-closing slash. An HTML comment in `index.html`'s head names "og:* and twitter:*". Production `/game/1` confirms this exact markup.

**Change.**
- Correct the reference.
- `preview.test` must use a shell produced by `transform(template, cfg, pageParts(appShellPage(cfg), …))`, or the built file, not handwritten markup.
- Assert there is exactly one of each tag after injection: no duplicate `og:title`, and the comment left untouched.
- Use a function replacer, not a replacement string, so a `$` in a value cannot expand.

### PR-07 (MEDIUM, VERIFIED) - FR-008 contradicts spec 078, and 078 changes the record

**What.** The sibling worktree `redraft-daily-number-1df2f8` (uncommitted, spec 078) adds `DailyNumber.of(LocalDate)` and `web/src/historicalDaily.ts dailyDay(isoDate, n)`. That function writes `"#8 · Nov 21"`, number AND date. 078's share text becomes "Ball Knowers Redraft daily #8 · Nov 21 · …". 079's FR-008 title is "Redraft daily #23 · 🏆 Champion", which drops the date.

**Other inconsistencies.**
- The prefix differs three ways: "Redraft daily" (079's daily title), "Ball Knowers Redraft daily" (share text), and "Ball Knowers Redraft · …" (079's free-play title).
- 078 inserts `Integer dailyNumber` before `chosenSeason` in the positional `HistoricalGameState` constructor. It also edits `RedraftPublicMvcIT` and `historicalShare.ts`. Any 079 test that constructs `HistoricalGameState`/`PublicGame` directly fails to compile at whichever merge comes second.

**Change.**
- Use 078's form: "… daily #23 · Nov 21 · 🏆 Champion".
- Pick one prefix rule and state it.
- Build test fixtures through one helper, so the 078 merge is a one-line change.
- Record the merge order in the plan. The seam stays (the plan rightly refuses a numbering of its own), and `DailyNumber.of(date)` is the call once it is on main.

### PR-08 (MEDIUM, REASONED) - the same rule in Java and TypeScript with no shared vectors

**What.** `SharePreview` re-implements `outcomeHeadline` and `ordinal` (`historicalCopy.ts:174-226`), `isOldFormat` (`:197`), `dayLabel` (`historicalDaily.ts:9`) and the score fallback of `draftScore` (`historicalShare.ts:35`) in Java.

**Precedent.** `DraftScore.java:7-10` shows how this repo handles that: "two implementations of one rule … pinned to the same vectors in their tests". The plan names the mirrors but plans no shared vectors.

**The voice.** The plan's "neutral voice" ("Lost the final") is a third phrasing. Web already has two voices: `'you'` gives "You lost the final", and the team voice gives "This team lost the final".

**Change.**
- Use the web's existing third-person voice word for word, or state why not.
- Add one JSON vector file read by both vitest and JUnit. It covers each outcome, old format, ordinals 1/2/3/11/12/13/21/111, ties, a null score, and day labels for every month. Java `MMM`/`Locale.US` and JS `toLocaleDateString('en-US', {month:'short'})` must agree; check September.

### PR-09 (MEDIUM, REASONED) - FR-002 should hold by construction, not by arithmetic

**What.** In full mode the plan computes the score as "`hindsight.draftScore` if present else `DraftScore.of(userPointsFor, pointsFor)`". `pointsFor` is the hindsight total, which is the one number `reduced(...)` withholds (`:330-333`).

**Why it matters.** The result is equal by construction today. But the builder holds the whole result: lineups, hindsight picks, `biggestBust`, `bestStealPassedOn`, `playoffs`, `hindsightSeason`, all present in a stored result (VERIFIED, game 608's keys). One later edit reading a "harmless" field breaks US2 with no type error.

**Change.**
- Make the reducer package-visible, and have `SharePreview.from` always take `reduced(result, userSlot)` in both modes.
- The preview code then never sees a player. US2.2 (the same before and after midnight) becomes true by construction.
- The reduced-equals-full test becomes a second lock instead of the only one.

### PR-10 (MEDIUM, REASONED) - a call to play that goes stale

**What.** The plan's description ends "Can you beat it?" for a daily, because "a stranger can play the same one today". That is true only until midnight ET (`HistoricalDailyService.ZONE`).

**Why it is stale.** The spec makes the preview immutable on purpose (US2.2). The PNG is `max-age=86400`, and Discord, Slack and iMessage cache unfurls for much longer. So a card read the next day invites a stranger to beat a game that is no longer the daily, and the link then shows them the full result.

**Change.** Use a line that is true forever ("Play today's Redraft daily." or "Draft your own past season.") rather than a time-dependent one. A time-dependent line would also break the "nothing to flip" property.

### PR-11 (MEDIUM, VERIFIED timings from this machine; the in-Railway figure is REASONED) - `/game` pages now wait on the backend

**What.** Today `/game/<id>` is a static file. After this change, every uncached load waits on `fetch(apiBase + …/preview)` through Railway's public edge first. That includes friends tapping the link and the owner reloading a result.

**Measured from here.** `GET https://api.ballknowers.co/api/history/public/games/1` took 0.40-0.51 s total, and about 0.10-0.15 s of that was after the TLS handshake. Each call builds the full state (about 150 KB of board JSON and 43 KB of result parsed per call, measured on 5433).

**Backend down or slow.** Every `/game` page load is held the full 1.5 s. Failures are not cached, so each request waits again.

**Change.**
- Cache misses briefly (a 404 for 30-60 s; a game turns COMPLETE later, so keep it short).
- Add a breaker: after a timeout, skip the backend for about 30 s.
- Consider a timeout near 800 ms.
- In verification.md, measure TTFB p50 of `/game/<id>` before and after, cached and uncached, and say how it was measured.

### PR-12 (MEDIUM, REASONED) - unbounded render work, and memory under the 041 budget

**What.** A cache miss on `preview.png` costs `publicGame` (state build, board parse), a 1200×630 INT_RGB `BufferedImage` (about 3 MB) and a PNG encode, all on a virtual thread. Game ids are sequential and public, and there is no rate limit, so anyone can walk the ids and force a render each time (the 256-entry LRU evicts as they go).

**Measured vs not.** The plan measures render time and allocation. It does not measure native memory: Java2D's font manager and freetype are off-heap, and spec 041 sized the container as heap 1024 MB plus about 250-380 MB non-heap inside 2 GB (root `Dockerfile` comments).

**Change.**
- Bound concurrent renders with a small permit pool (`api/SimulationPermits` is the existing pattern): wait briefly, else 503 with no body.
- Measure process RSS (or NMT) before the first render and after 50, and write the numbers next to the timing.

### PR-13 (MEDIUM; fontconfig in the image VERIFIED from source, fonts REASONED) - fonts in `eclipse-temurin:21-jre-jammy`

**What we know.**
- The Adoptium jammy JRE Dockerfile installs `fontconfig` (for the `libfontmanager`/`libfreetype` errors it names).
- Jammy's `fontconfig-config` depends on one of a font list with `fonts-dejavu-core` first. So DejaVu is very likely present.
- It was not checked: Docker is not running here.

**Risks.**
- (a) DejaVu Sans has no emoji. Any 🏆 or 🟩 passed to `drawString` renders as a box. The Card's `headline` and title strings are one refactor away from carrying the 🏆.
- (b) With fontconfig present but no fonts, the JDK fails at first text use with an `InternalError`/NPE. Those are `Error`s, not `Exception`s, so a `catch (Exception)` fallback never runs.
- (c) Logical `SansSerif` is Arial on Windows and DejaVu in prod. A picture looked at locally (SC-002) is not the prod picture.

**Change.**
- No emoji in any drawn string; draw the trophy as a shape.
- A test that `font.canDisplayUpTo(s) == -1` for every drawn string.
- `catch (Throwable)` around text drawing, falling back to the squares-only card.
- Verification renders inside the real image (or once in prod) and says which it did.

### PR-14 (MEDIUM, VERIFIED) - NFL cannot be proved locally

**What.** On 5433, `historical_game` holds 6 COMPLETE games, all NBA with 14 rounds, and `historical_nfl_player_season` has 0 rows. The plan's verification runs on 5433, so it proves basketball only.

**How football differs.**
- 15 rounds (`application.yml:187`), so 15 squares and 2 rows.
- A single-year label ("2001", prod game 1).
- The one prod NFL game read is old format: no `format`, `userOutcome` or `pickFinishes`.

**Change.**
- Add unit fixtures for an NFL new-format result (15 squares, "2001 NFL") and an NFL old-format result.
- Add a post-deploy prod check on a real NFL game.
- Until then, verification.md says "NFL not verified with real data", in those words.

### PR-15 (LOW, VERIFIED) - path parsing

**What.**
- `gameIdFromPath` accepts `/game/42/`. Today that path 301s to `/game/42` (`trailingSlash: false`), and the plan would answer it with 200 instead.
- The plan does not say the query string is stripped. `App.tsx` forwards `/history/<id>` keeping query and hash, so queries do occur, and a regex run on `req.url` sends `/game/42?ref=x` the plain shell.
- `/history/<id>` links on the Redraft site are rewritten to the shell and redirected in-app, so they never get a preview. The spec does not say so.

**Change.**
- Parse with `new URL(req.url, origin).pathname` and match `^/game/(\d{1,18})$` only.
- Let `serve-handler` 301 the slashed form.
- State that `/history/<id>` stays generic.

### PR-16 (LOW, REASONED) - Spring route details

**What.**
- `@PathVariable long id` answers 400, not 404, for a non-numeric id (the same as the existing `/games/{id}`).
- If the PNG route declares `produces = "image/png"`, a fetcher sending a narrow `Accept` gets 406.
- If an exception escapes under `Accept: image/png`, the JSON error body cannot be written, and Spring logs `HttpMediaTypeNotAcceptableException`.

**Change.**
- Set the content type on the response, not `produces`.
- Turn any failure into a bodiless 404 or 500.
- `LeagueLockMappingsIT`'s lock-ON table gets `/games/2147483000/preview` and `/preview.png` → 404.

### PR-17 (LOW, VERIFIED) - packaging hygiene

**What.**
- The runtime stage runs `npm install serve-handler@6.1.7` with no package.json or lockfile, while the plan also pins it in `web/package.json`. That is two version sources.
- `npm ci --omit=dev` would not fix it: it would also install react, react-dom and react-router-dom, which are runtime `dependencies` there.
- `web/.gitignore` lists only `serve.site.json`, so the new `server.site.json` would be committable.
- `tsconfig.json` includes only `src` and `scripts/fetchSeasons.ts`, so `server/*.mjs` is not type-checked.

**Change.**
- Give `web/server/` its own package.json and lockfile (exact pins for `serve-handler` and `compression`), installed with `npm ci` in the runtime stage.
- Git-ignore `server.site.json`.
- Say that `server/` is plain JS, tested only by vitest.

### PR-18 (LOW, REASONED) - which host serves the image

**What.** The spec says `og:image` is "an absolute PNG URL". The plan passes `imageUrl` into `injectPreview` without saying whether it is `apiBase + /api/history/public/games/<id>/preview.png` or a path on `www` that the web server proxies.

**Change.** Say `api.ballknowers.co` directly. Proxying would add a second backend hop and a second cache. Note that an unset `VITE_API_BASE` (the local combined build) makes `fetch` throw on a relative URL, which is the plain-shell path, and test that it is.

### PR-19 (LOW, REASONED) - unchanging data, changing picture

**What.** "Content never changes" holds for the data (see OK). The image, though, changes with code: 078's number arriving, a copy edit, a colour fix. Meanwhile the PNG is `public, max-age=86400` and unfurlers keep it for much longer.

**Change.** Version the image URL (`preview.png?v=1`, bumped when the card's design changes) so a deliberate change can reach new shares. Say in the plan that cards already posted keep their old picture.

---

**To the parent session.** 0 CRITICAL, 2 HIGH (PR-01 missing `public`, PR-02 lost compression), 11 MEDIUM, 6 LOW. The two HIGHs are both in the web-server swap, and both are fixed in the plan, not the spec. Findings that change *what the feature is* and need Allan:

- **PR-05**: what the tab and the DOM head say on a shared result after mount.
- **PR-07**: the daily title form against spec 078 ("#23 · Nov 21"), plus the merge order of 078 and 079.
- **PR-10**: the call-to-play wording.
- Arguably **PR-11**: accepting that every `/game` page load now depends on backend latency, which is the core architectural trade.

The rest are build-level and can be folded into the plan with a dated "amended after review" note.
