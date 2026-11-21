# Bug-hunting code review: 079 Redraft share preview

**Date**: 2026-11-21 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `AGENTS.md`; `spec.md`, `plan.md` (the "Amended after review 2026-11-21" section is treated as binding),
`plan-review.md`, `tasks.md`. The whole uncommitted diff against main `c00a846`:
- Backend: `historical/SharePreview{,Image,Service,BusyException}.java`, `api/RedraftPublicController.java`,
  `SharePreviewTest`, `SharePreviewImageTest`, and the `RedraftPublicMvcIT` / `LeagueLockMappingsIT` additions.
- Web: `web/server/{server,preview}.mjs` and their tests, `Dockerfile`, `package.json`, `.gitignore`, `vite.config.ts`,
  `src/{siteFiles,site,landingHtml,pageTitle}.ts`, `src/pageTitle.test.ts`.
- Code around the diff: `HistoricalGameService.publicGame`/`reduced`/`markOf`, `api/ErrorHandler`,
  `historicalCopy.ts` (`outcomeHeadline`, `ordinal`), `historicalShare.ts` (`pickMark`, `draftScore`),
  `historicalDaily.ts` (`dayLabel`), `App.tsx` routes, `index.html`, the built `dist/app-shell.html`,
  `compression@1.8.1/index.js`.

**What I ran** (nothing in the project was changed except this file; scratch files are in the session scratchpad):
- **Web, targeted**: `npx vitest run server src/pageTitle.test.ts src/siteFiles.test.ts` → 4 files, **50 passed, 0 skipped**.
- **Web, full**: `npx tsc -b` → clean. `npx vitest run` → 3632 passed, **1 failed**, 0 skipped. The failure is
  `src/usageVocabulary.test.ts`, a CRLF-vs-LF comparison of the committed `usage-vocabulary.json`. That file is not
  touched by 079 (it is not in `git status`), so this is the Windows checkout's line endings, not this branch.
- **Backend, targeted**: `.\gradlew.bat test --tests '*SharePreview*' --tests '*RedraftPublicMvcIT' --tests '*LeagueLockMappingsIT'`
  → BUILD SUCCESSFUL. Per the XML reports: SharePreviewImageTest 5/0 skipped, SharePreviewTest 13/0,
  RedraftPublicMvcIT 10/0, LeagueLockMappingsIT 4/0. That is **32 tests, 0 skipped, so Postgres 5433 was really used**.
  Render figures printed by the test: median 37 ms, max 43 ms, about 3.2 MB allocated per render, PNG 37.7 KB.
- **Real backend**: `bootRun` on port 8091 against 5433, with `refresh.on-visit.enabled=false`. It was killed afterwards.
  - curl of `/preview` for game 608 (chosen-season free play), 605 (a past daily) and 609 (DRAFTING, gave 404).
  - `/preview.png` under five different `Accept` headers, for a finished game, an unfinished game, a non-numeric id
    and an id that overflows `long`.
  - I fetched the 605 PNG and looked at it.
- **Real web server**:
  - `node server/server.mjs` on the real built `dist/` (timemachine), pointed at that backend.
  - Next to it, the real `serve@14.2.6` CLI on the same `dist` and `serve.site.json`, so the two could be compared.
  - 19 paths compared on both servers (status, redirect, size), plus the headers on a JS asset and `OPTIONS` / `POST`.
  - `/game/608` checked with gzip, HEAD, and HEAD+gzip.
- **Docker layout, simulated**: the Docker daemon is down, so the image was **not** built.
  - Instead, an empty directory, `npm install --no-save serve-handler@6.1.7 compression@1.8.1`, then `dist/`,
    `server/`, `serve.site.json` and `server.site.json` copied in the Dockerfile's layout.
  - `node <abs>/server/server.mjs` started from cwd `/`. Results: `/` gave 200, `/game/605` carried `og:url`,
    `/server/server.mjs` gave 404.
- **Reproductions** (scratchpad scripts):
  - `inj.mjs`: `injectPreview` on the real built `app-shell.html`, with `$&`, `$1`, `$'`, `` $` ``, `$$`,
    `</title><script>` and quotes in the values.
  - `neg.mjs`: the server with a stub backend that answers 404 and then 200 (CR-01).

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | MEDIUM | Web server cache | `/game/<id>` is also the draft room. A reload mid-draft caches "no preview" for 60 s, so a share right after finishing unfurls generic, and the unfurler keeps that generic card |
| CR-02 | LOW | Web server errors | `wordsFor(id).then(...)` has no rejection handler. Any future throw there crashes the whole web process (Node 20 unhandled rejection) |
| CR-03 | LOW | Path parsing | `/game/0608` is intercepted as a separate id: its own cache entry and a non-canonical `og:url` |
| CR-04 | LOW | Backend errors | The PNG route catches every `IllegalStateException` from `png()`, not just render failures: a data error becomes a 503 "render failed" with a retry hint |

**Count: 0 HIGH, 1 MEDIUM, 3 LOW.**

### Checked and OK (the parent asked about each)

**Privacy (US2 / FR-002), VERIFIED.**
- `SharePreview.project` reads only `userOutcome`, `userFinish`, `format`, the user's own standings row (only
  `wins`/`losses`/`ties`), `hindsight.{userPointsFor, pointsFor → score, draftScore}` and `pickFinishes[].{pickNo, mark|ranks}`.
  `pointsFor` never leaves the method.
- The reduced and full inputs give equal projections:
  - The score comes from `DraftScore.of` in full mode and from the reduced `draftScore` otherwise; it is the same formula.
  - The marks: `reduced` recomputes them with `markOf`, and `project` uses `markOf` when there is no valid mark;
    both are sorted by `pickNo`.
- The IT checks every one of more than 100 player names against today's reduced preview body.
- The live `/preview` bodies for games 605 and 608 carry only the title, description, alt text and the card
  (headline, record, score, season line, day, marks). The drawn card uses no other field.

**`$` in replacement strings, escaping, duplicate tags, VERIFIED.**
- Every `replace` in `preview.mjs` uses a function replacer. On the real built shell, `$&`, `$1`, `$'`, `` $` `` and
  `$$` come out literally.
- `<`, `>`, `&`, `"` and `'` are escaped, and `&` in the image URL becomes `&amp;`.
- Each of the 15 tags appears exactly once, there is one `<title>`, and the `index.html` comment that mentions
  "og:* and twitter:*" is untouched (the regex needs `<meta`).
- Everything after `</head>` is byte-identical.
- One cosmetic point: the added tags land after `<script src="/boot.js">`, with 6-space indentation. Parsers do not care.
- `preview.test.mjs` also covers `$&`/`$1`. Its shell comes from `pageHeadTags` and not from the built template,
  which PR-06 asked for. Running against the real build shows that gap hides nothing.

**`server.mjs` vs the `serve@14.2.6` CLI, VERIFIED.**
- The same status, redirect target and size for `/`, `/index.html` (301), `/daily`, `/daily.html` (301), `/nba`,
  `/nba/` (301), `/game/608/` (301 to `/game/608`, so PR-15 holds), `/game/abc`, `/game/` (301), `/history/5`,
  `/nowhere` (404 with the custom 404.html), `/og-image-tm.png`, `/assets/` (301), `/serve.site.json` (404),
  `/app-shell.html` (301), `/app-shell`, `/404.html` (301) and `/game/608%2F`.
- The JS asset gets the same headers byte for byte: identical ETag, `Content-Encoding: br`, `Vary`, and the security headers.
- `OPTIONS` and `POST` answer the same.
- The only difference is `/game/0608` (CR-03).

**Compression and Content-Length on the intercepted page, VERIFIED.**
- `compression@1.8.1` removes `Content-Length` when it compresses (`index.js:225`): the gzip GET of `/game/608` is
  chunked, with no stale length.
- It skips HEAD (`:192`), so HEAD sends `Content-Length: 3183` and no body.
- The security headers are present on both.

**Error paths, timeout, caching and coalescing, VERIFIED by the vitest suite plus reading.**
- `handler()` rejections are caught and answered 500.
- `AbortSignal.timeout` covers the body read too.
- The body is capped by the declared length and by a streaming count.
- `inflight` is cleared in `finally`. The cache is bounded at 500 entries, evicted by insertion order.
- An empty `apiBase` makes `fetch` throw, which falls back to the plain shell.

**Backend content negotiation and paths, VERIFIED by curl on the real server.**
- `/preview.png` with `Accept: image/png`, `*/*` or `image/webp,image/*`: 200 for a finished game, 404 with an empty
  body for a drafting game, 400 with an empty body for `abc` and for an overflowing id.
- `Accept: application/json` or `text/html` only: 406, with no 500 and no stack. Unfurlers send `*/*` or `image/*`.
- `/games/{id}` and `/games/{id}/preview(.png)` route independently.

**Semaphore and cache, REASONED.**
- The permit is taken outside the `try` and released in its `finally`, so a failed `tryAcquire` never releases.
- An interrupt maps to busy.
- The `LinkedHashMap` (access order, so `get` mutates) is only touched under `lock`.
- `catch (Exception | Error)` rethrows `VirtualMachineError`.

**Docker runtime, VERIFIED by simulation (Docker not run).**
- `server.mjs` resolves `../dist`, `../serve.site.json` and `../server.site.json` from `import.meta.url`, not from cwd.
- `compression` and `serve-handler` resolve from `/app/node_modules` by walking up from `/app/server`.
- `/server/*` is not published.
- Transitive deps of the two pins are not lockfile-pinned. That is the same as the old `npm install -g serve@14.2.6`,
  so it is not a regression.

**074 head behaviour, VERIFIED by tests.**
- The full web suite passes, except the unrelated CRLF test above.
- `applyPageHead` resets `og:image`, `twitter:image` and both alt tags to the site image.
  `og:image:width`/`height`/`type` stay, but they hold the same values as for the site image (1200, 630, png).
- `usePageHead` still does nothing off the Redraft site.

**Ordering sign, VERIFIED.**
- Green = `ahead` = finish rank < drafted rank, the same rule as `markOf` and web `pickMark`.
- `SharePreviewImageTest` asserts a square's centre pixel colour per mark.

## Findings

### CR-01 (MEDIUM, VERIFIED) - a reload mid-draft hides the preview of the share that follows

**Where.** `web/server/server.mjs:19` (`TTL_NOT_FOUND_MS = 60_000`) and `:104-107`. The other half is
`web/src/App.tsx:334`: on the Redraft site, `/game/:gameId` is the draft room itself, not only the shared-result page.

**What happens.**
1. A player hard-loads their room while drafting: a reload, or coming back from a bookmark or another tab.
2. `server.mjs` asks `/preview`, the backend answers 404 (the game is not COMPLETE), and the server caches `null`
   for 60 s.
3. The player hits "auto-finish" (or makes the last few picks) and shares at once. That is the normal end of a game.
4. The unfurler's fetch of `/game/<id>` within those 60 s gets the plain shell, so the generic card shows.
5. iMessage, Slack and Discord cache unfurls far longer than 60 s, so that link stays generic in that chat.

**Reproduce.** `neg.mjs` in the scratchpad: a stub backend answers 404, `/game/7` is loaded, then the stub flips to 200.
`/game/7` fetched 5 s later still has no preview (`false`, `false`).

**Fix** (any one of these):
- Do not cache a 404 at all. A 404 is cheap for the backend: `publicGame` filters on status before it builds any state.
- Or cache a 404 for 5 s or less.
- Or have the backend send `Cache-Control: no-store` on the not-COMPLETE 404 and have `loadWords` honour it.

The first is simplest. The 404 TTL was meant to blunt enumeration, and the 500-entry FIFO already bounds that.

**Regression test.** In `server.test.mjs`, make the stub flip from 404 to the JSON answer for one id. Fetch
`/game/<id>`, flip the stub, fetch again at once, and assert that `og:url` is present.

### CR-02 (LOW, REASONED) - an unhandled rejection would take the whole site down

**Where.** `web/server/server.mjs:158-167`: `wordsFor(id).then((words) => { ... sendHtml(...) })` has no `.catch`.

**What happens.** Today nothing inside that callback throws: `loadWords` catches everything, and `injectPreview`
only gets validated strings. But under Node 20's default `--unhandled-rejections=throw`, any later throw in
`injectPreview`, `sendHtml` or a new header line kills the process. That takes down every page on the web service,
not one request, and Railway's `ON_FAILURE` restarts are capped at 3.

**Fix.** Add a catch that logs and still answers:

```js
.catch((e) => {
  log(`preview page error: ${e?.message ?? e}`)
  if (!res.headersSent) sendHtml(req, res, shell)
  else res.end()
})
```

Optionally also add `process.on('unhandledRejection', …)` logging in the `node server/server.mjs` start block.

**Regression test.** Not cheap to force without injection. A unit test that `createServer` survives a throwing `log`
on the preview path would do.

### CR-03 (LOW, VERIFIED) - leading zeros make a second id

**Where.** `web/server/preview.mjs:193` (`/^\/game\/(\d{1,18})$/`) and `server.mjs:163`.

**What happens.** `/game/0608` is intercepted, but its 1035-byte answer differs from `serve`'s 873-byte shell.
- The backend parses `0608` as 608, so the words are right.
- The cache key is `"0608"`, a second entry next to `"608"`.
- `og:url` is `https://www.ballknowers.co/game/0608`.
- Any number of zero-padded variants each cost a backend call and a cache slot.

Nobody shares such a link, so the impact is hygiene.

**Fix.** Match `^\/game\/([1-9]\d{0,17})$`, and let anything else fall through to `serve-handler` as `/game/abc` does.
Or normalise the id with `String(BigInt(m[1]))` before using it as a key and in the URLs.

**Regression test.** `gameIdFromPath('/game/0608')` is null (or `'608'`), in `preview.test.mjs`.

### CR-04 (LOW, REASONED) - the PNG route swallows any IllegalStateException as a render failure

**Where.** `api/RedraftPublicController.java` `previewPng`, the `catch (SharePreviewBusyException | IllegalStateException e)`.

**What happens.** `previews.png(id)` also runs `games.publicGame(id)` (the state build) before rendering. An
`IllegalStateException` from there means a data problem that `ErrorHandler.conflict` turns into a 409 elsewhere,
including on the sibling `/preview` route. On this route it becomes:
- a WARN saying "share preview render failed",
- `503` with `Retry-After: 5`, which invites an unfurler to retry a request that will never succeed.

**Fix.** Have `SharePreviewImage.render` throw a dedicated `SharePreviewRenderException` (or keep the ISE but wrap
only the `render` call in the service), and catch only that and `SharePreviewBusyException` in the controller.
Anything else then follows the same path as `/preview`.

**Regression test.** A unit test of the controller (or service) with a `HistoricalGameService` stub whose
`publicGame` throws `IllegalStateException`: assert that the response is not a 503 with `Retry-After`.

---

**To the parent session.** 0 HIGH, 1 MEDIUM (CR-01), 3 LOW. Everything the brief asked about checked out:

- Privacy: the reduced daily is respected by construction, and IT-checked.
- `$` expansion and escaping in `preview.mjs`.
- Duplicate tags.
- CLI parity on 19 paths, plus compression, ETag, HEAD and Content-Length.
- Content negotiation under `Accept: image/png`.
- Semaphore release.
- Module resolution in the `/app` layout.
- The 074 head tests.

**Lesson candidate for `claude/lessons.md`.** CR-01 is a class. A negative cache keyed by a URL that serves two
lifecycles (the in-progress room and the finished share) caches the earlier state into the moment the later one
matters, and a downstream cache (the unfurler) makes that permanent. Before caching a miss, ask what URL the miss
was for, and whether the thing behind it is about to exist.

**Caveats.**
- Docker was not run, so the image build and the fonts in `eclipse-temurin:21-jre-jammy` were not checked.
- NFL was checked by unit tests only, since there is no NFL data on 5433.

## Fixes applied 2026-11-21 (parent session)

| Finding | Fix | Test |
|---|---|---|
| CR-01 | `server.mjs`: a backend 404 is no longer cached (TTL_NOT_FOUND removed; the entry is deleted) | `server.test.mjs` "a 404 is not remembered" flips the stub 404 → 200 between two loads |
| CR-02 | `.catch` on the preview promise: logs and serves the plain shell | — (nothing throws today) |
| CR-03 | id regex `^\/game\/([1-9]\d{0,17})$` | `preview.test.mjs` rejects `/game/0608`, `/game/0` |
| CR-04 | new `SharePreviewRenderException`; the PNG route catches only it and the busy exception | existing render tests |

After the fixes: web targeted 51/51, `tsc -b` clean; backend `*SharePreview*` 5+13, `RedraftPublicMvcIT` 10, `LeagueLockMappingsIT` 4, 0 skipped, 0 failed.
