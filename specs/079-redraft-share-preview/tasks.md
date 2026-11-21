# Tasks: 079 Redraft share preview

## Backend (builder A)
- [ ] T001 `historical/SharePreview.java` record + `from(PublicGame, Integer dailyNumber)` with own whitelist projection (PR-09), words per plan + amendments (PR-07, PR-08, PR-10)
- [ ] T002 `historical/SharePreviewImage.java` Java2D 1200x630 PNG, `VERSION = 1`, error handling (PR-13)
- [ ] T003 `historical/SharePreviewService.java` preview + PNG LRU(256) + Semaphore(2) (PR-12)
- [ ] T004 Two routes in `RedraftPublicController` (PR-16), dailyNumber SEAM comment
- [ ] T005 Tests: SharePreviewTest (words, reduced==full, no names), SharePreviewImageTest (PNG, size, square colours, timing/allocation printed), RedraftPublicMvcIT + LeagueLockMappingsIT additions

## Web (builder B)
- [ ] T010 `web/server/preview.mjs` pure: gameIdFromPath, injectPreview, escape
- [ ] T011 `web/server/server.mjs` serve-handler(public=dist, etag) + compression, interception, caching (PR-11), headers, logging, SIGTERM
- [ ] T012 build writes `server.site.json`; gitignore; Dockerfile runtime; package.json pins
- [ ] T013 `applyPageHead` image reset (PR-05) + test
- [ ] T014 vitest: preview.test, server.test (stub backend, temp dist)

## Then
- [ ] T020 bug-hunter review → code-review.md, fixes
- [ ] T021 live verification → verification.md
- [ ] T022 DEPLOY.md + HANDOFF note
