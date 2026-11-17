# Bug-hunting code review: 074 Rename Time Machine to Redraft and make it findable in search

**Date**: 2026-11-17 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: AGENTS.md, spec.md (with Clarifications), plan.md (the "Amended after review" section taken as binding), tasks.md (with "Amended after analysis"), contracts/public-api.md, contracts/pages.md, t001-local-data.md, t027-serve.md; the whole uncommitted diff on top of 81c780e and every untracked source file. design.md, backend-records.md, t017-measurements.md, plan-review.md and claude/lessons.md were **not** read in full: the Java records were diffed against `api.ts` and against real JSON directly instead.

**What I ran** (nothing in the project was changed except this file):

- Backend suite, `DB_URL=jdbc:postgresql://localhost:5433/draftsim_074 ./gradlew test`: **3017 tests, 12 failed, 48 skipped**. The 12 failures are the same 12 names as the baseline in t001-local-data.md (AccessControlMvcIT.draftRoutesAre404WithNoIdentity, AdminDigestControllerIT.anExplicitDateIsAccepted, eight in TargetControllerIT, two in LeagueRepositorySuccessorIT). The new classes ran and were not skipped: RedraftGuestMvcIT 16, RedraftPublicMvcIT 8, SeasonSummaryServiceTest 9, IdentityTest, HistoricalBoardBuilderRankedCountTest 3, LeagueLockMappingsIT 4.
- Web: `npx tsc -b` clean; `npm test` **213 files, 3459 tests, all passing** (equal to the stated baseline).
- A backend from this tree on port 8081 against `draftsim_074`, driven with curl: public routes with odd slugs and ids, six parallel guest creates, header combinations, a stranger on a drafting game, a guest daily played to the end, the reduced public JSON walked key by key, claim by a third party, claim replay. The three games and the award, achievement and profile rows this made were deleted afterwards (`historical_game` is back to its 3 rows); the backend was stopped by its own PID. Nothing on 8080, 5173, 5432 or the shared `draftsim` database was touched.
- All 47 local NBA season pages fetched and checked for invariants (bust and steal direction, no player in both lists, dream roster size and duplicates, rank order).
- Four `vite build` runs into the scratchpad (never into `web/dist`): timemachine from a hostile fixture, timemachine from real NBA data plus the fixture's NFL, leagues, combined.

**Caveats**: there are no NFL seasons locally, so every football statement is from code or the fixture. No browser was driven. The real `serve` command was not re-run (t027 measured it on a fixture build before Phase 5). The scratch web render of the real reduced payload was not done; that path was checked by reading only.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Status | Area | One line |
|---|---|---|---|---|
| CR-01 | HIGH | VERIFIED | Rename | `og-image-tm.png` was never re-rendered: every link preview still says "Time Machine" and "Free with a Sleeper username" |
| CR-02 | MEDIUM | VERIFIED in part | Claim | Claim turns throwaway guest ids into retries of the one-go daily: play it as a guest until it goes well, then sign in and keep that one |
| CR-03 | MEDIUM | VERIFIED | Daily answer key | Today's daily season is named before the draft and its public season page is that day's answer key; the reduced shared result does not close this |
| CR-04 | MEDIUM | REASONED | Combined build | `/history` on the combined build now links to `/daily`, `/nba`, `/nfl`, which are not routes there; a non-owner's game link changed behaviour; the tab title sticks |
| CR-05 | MEDIUM | REASONED | Season page | After a 403 `GUEST_LIMIT`, signing in from the panel leaves the panel up and the draft button disabled |
| CR-06 | LOW | REASONED | Guest id | Two tabs opened together each mint their own guest id; one tab's games are orphaned |
| CR-07 | LOW | VERIFIED | Build | `closeBundle` still writes `serve.site.json` after a failed build, and if that write fails it replaces the real error |
| CR-08 | LOW | VERIFIED | Build | A sport with zero seasons fails the whole web build, so T047's local-backend build cannot pass on this machine |
| CR-09 | LOW | REASONED | Home | A failed season-list request silently removes "Draft a random year" and the tiles, with no error and no retry; the test that pinned the old fallback lost that line |
| CR-10 | LOW | REASONED | Rollover | "Yesterday" on `/daily` and a reduced shared result do not refresh when midnight ET passes under an open page |
| CR-11 | LOW | VERIFIED in part | Guest id | The guest UUID is a bearer credential: it is logged, its fallback generator is called "not a secret", and the nil UUID is accepted |
| CR-12 | LOW | REASONED | Hygiene | Stale "GUESSED" comment in `api.ts`; slug used as a file name unchecked; award panel 404 after a left-behind claim; missing regression tests |

No BLOCKER. One HIGH, four MEDIUM, seven LOW.

## Findings

### CR-01 (HIGH, VERIFIED) - the preview image still says "Time Machine"

**Where**: `web/public/og-image-tm.png` (unmodified in `git status`); source `web/scripts/og-image/og-image-tm.svg` (modified); referenced by every page at `web/src/landingHtml.ts:164`.

**What happens**: the SVG was reworded but the PNG was not rendered from it. I opened the PNG: it reads "BALL KNOWERS / Time Machine / Draft a real season. See how it played out. / Free with a Sleeper username". Every indexable page and the app shell point `og:image` and `twitter:image` at it, so every shared link's preview card carries the old name and the sign-in wall the feature removes. This breaks FR-001, US2 scenario 1 and SC-004. T030 is still unchecked in tasks.md, so it is known as owed, but nothing fails while it is missing: the "Time Machine" guard and the `grep dist` check read text and cannot see pixels.

**Fix**: run `web/scripts/og-image/render.mjs`, look at the PNG, commit it with the SVG.

**Regression test**: a test that fails when the SVG is newer in content than the PNG, for example a hash of the SVG stored beside the PNG and compared.

### CR-02 (MEDIUM, VERIFIED in part) - claim makes the game of the day repeatable

**Where**: `HistoricalGameService.claim` (`backend/.../historical/HistoricalGameService.java:176`), `HistoricalGameRepository.moveOwner` (`:267`), `HistoricalAchievementRepository.unsettled`.

**What happens**: the daily is one go per person per day (it cannot even be abandoned). A guest id costs nothing: a private window or cleared storage gives a new one, and each may play today's daily, same season and seed. A player who has not yet played today as their Sleeper id can play it as a guest as many times as they like, then sign in from the browser holding the best attempt. Claim moves that game to the Sleeper id, where it settles like any daily: XP, badges, titles, streak, best and average draft score.

**Verified**: a finished guest daily claimed into a Sleeper id settled at once (`GET /profile` answered `xp: 500, totals.games: 1`). **Reasoned**: the repetition itself, which is only "do the verified thing in several browsers".

Before this feature the same practice was possible only by calling the API with invented `X-Sleeper-User` values, and those results could not be moved onto the real id. Claim is the new transfer. The spec calls the *allowance* a courtesy limit; it does not say the daily's one-go rule or achievements are meant to be that soft.

**Fix** (a decision for Allan, not only code): either accept it and say so in spec.md and HANDOFF, or make a claimed game of the day keep its place in history and the streak but earn no XP, badge or record (a `claimed` flag on the row, checked where `chosen_season` is checked). The second keeps FR-021c ("games move") and closes the farm.

**Regression test**: whichever rule is chosen, an IT that claims a finished guest daily and asserts what `ProfileView.totals` and XP do.

### CR-03 (MEDIUM, VERIFIED) - today's season page is today's answer key, and the reduced view leaks exact ranks

**Where**: `DailyCards.tsx:149` (prints `seasonLabel` before play), `RedraftPublicController.season`, `HistoricalGameService.publicGame` (`:300` passes `full.season()`), `reduced(...)` (`:340` keeps `position`, `draftedPositionRank`, `finishPositionRank`), `SharedResult.tsx:64` (kicker prints the season label).

**What happens**: PR-07 reduced the shared result of today's daily so it is not an answer key. But:

1. `GET /api/history/daily` and the home card name today's season ("2012-13") before the draft, to anyone including a guest.
2. `/nba/2012-13` and `GET /api/history/public/seasons/NBA/2012-13` are public and give the top 12, top 5 per position, steals, busts and the dream roster for that season. Measured today: top three LeBron James, Kevin Durant, Dwight Howard; steals Larry Sanders, Nikola Vučević, Ömer Aşık.
3. The reduced view itself still carries, per pick, the pick number, position and exact position finish rank. Joined with the season page's `topByPosition` it names players: today's reduced game said pick 7 was "SF, finished SF 2", which the season page resolves to Kevin Durant.

So a daily that earns XP, badges and a streak now ships with an in-product answer key one click from the play button. The plan records Allan's choice for a *chosen* season (folded spoilers, earns nothing); it does not record a choice for the daily, and `SharedResult.tsx:75` withholds only the link while printing the label that is the address.

**Fix** (decision for Allan): accept and write it down (real-world knowledge of a famous season is an answer key anyway), or stop naming today's season before the draft starts (the card and `DailyView.seasonLabel` for a player with no game), which is the only lever a static site has. Independently, the reduced view should send bucketed marks (ahead, same, behind) instead of the two raw ranks, and no `position`; the squares need nothing else.

**Regression test**: `RedraftPublicMvcIT.todaysFinishedDailyIsReducedAndYesterdaysIsFull` asserts no rank or position key in a reduced `pickFinishes` row.

### CR-04 (MEDIUM, REASONED) - the combined build is not unchanged

**Where**: `web/src/App.tsx:255-256` (CombinedTree mounts `HistoricalDraft` and `HistoricalRoom`), `web/src/pages/HistoricalDraft.tsx:48,170,209`, `web/src/components/RedraftFoot.tsx`, `web/src/pages/HistoricalRoom.tsx:153`, `web/src/pages/SharedResult.tsx`, `web/src/redraftPages.ts` (`DAILY_PATH`, `sportIndexPath`, `seasonPath` are literals, not `site.ts` helpers).

**What happens** when `VITE_SITE` is unset (every `npm run dev`, and the rollback build):

- `/history` now renders "About the game of the day" to `/daily`, the sport tiles to `/nba` and `/nfl`, and a footer with the same three. None is a route in `CombinedTree`; each lands on "Nothing here".
- A signed-in non-owner opening `/history/<id>` used to get not-found. They now get the shared result, whose "How 2016-17 played out" link and whose not-found variant's links go to the same missing routes.
- `usePageTitle` in both pages sets the Redraft title; `App`'s effect only re-runs on user, site or lock change, so after a visit to `/history` the tab keeps the Redraft title on every league page until reload.

The plan says "The combined and leagues builds are untouched", and the constraint list says paths go through `site.ts`.

**Fix**: put the three path helpers in `site.ts` beside `tmHome`/`tmGame`, returning null (link not rendered) on the combined site, or add the five routes to `CombinedTree` under `/history/...`. Guard `usePageTitle` in the two shared pages with `currentSite().site === 'timemachine'`.

**Regression test**: in `App.site.test.tsx`, combined mode: render `/history` signed in, collect every `a[href]`, assert each resolves to something other than the not-found page; assert `document.title` after `/history` then `/leagues`.

### CR-05 (MEDIUM, REASONED) - signing in from the limit panel does not lift the limit

**Where**: `web/src/pages/SeasonPage.tsx:300` (`const atLimit = limited || usedAllowance`), `:328` (`disabled={starting || atLimit}`), `:343`.

**What happens**: `usedAllowance` is `!user && ...` and clears on sign-in. `limited` is set by a 403 `GUEST_LIMIT` and never cleared. A guest who reaches the panel through the 403 (their games list had not loaded or failed, or the allowance was spent in another tab) and signs in with the form inside that panel is now signed in, still looking at "sign in to draft more" with a sign-in form, and the draft button is still disabled. This is the conversion moment the panel exists for (US3 scenario 5). A reload fixes it.

**Fix**: `const atLimit = !user && (limited || usedAllowance)`, or reset `limited` in an effect on `user`.

**Regression test**: extend `redraftPages.app.test.tsx` "a refused second season (403 GUEST_LIMIT)": after the panel shows, `setUser(...)`, assert the panel is gone and the button is enabled.

### CR-06 (LOW, REASONED) - two tabs can mint two guest ids

**Where**: `web/src/guest.ts:34` (caches a null read), `:42`.

**What happens**: `peekGuestId` reads storage once and caches the answer, including null. Two tabs opened together on a first visit both cache null, both generate, and the second `setItem` wins. The first tab keeps using its in-memory id for the life of the page; games made there are unreachable after a reload. The same shape occurs when another tab signs in (drops the id) and out again while this tab stays open. Every page load of the home page makes a history request at once, so the window is small but real.

**Fix**: do not cache null (re-read storage whenever `current` is null or undefined), re-read immediately before generating, and update `current` from a `storage` event for `bk.guest.v1`.

**Regression test**: in `guest.test.ts`: `peekGuestId()` returns null, another "tab" writes the key, `guestId()` returns the stored value, not a new one.

### CR-07 (LOW, VERIFIED) - a failed build still runs the serve-config write and can hide the real error

**Where**: `web/vite.config.ts:85-101`.

**What happens**: the comment at `:86` says a failed build has nothing to write, but only the page loop is guarded by `data`. The `serve.site.json` write at `:101` always runs. Reproduced: a timemachine build whose `buildStart` threw the season-data error, with an output parent that did not exist, reported `ENOENT ... serve.site.json` and never showed the season-data message. With the parent present the right message shows, and a `serve.site.json` is written for a build that produced no `dist`.

**Fix**: record failure in a `buildEnd(error)` hook and return from `closeBundle` when it is set.

### CR-08 (LOW, VERIFIED) - one empty sport fails the whole build

**Where**: `web/scripts/fetchSeasons.ts:60`; contrast `web/src/redraftPages.ts` `indexablePages`, which handles a sport with no seasons.

**What happens**: `VITE_SITE=timemachine VITE_API_BASE=http://localhost:8081 vite build` fetched 47 NBA seasons and then failed with "the NFL season list is empty". T024 asks for this, so it is as designed, with two consequences worth stating: T047's "timemachine build against the local backend" cannot pass on this machine until an NFL import is run into `draftsim_074`; and in production a state that has happened before (one sport imported, the other not, 2026-11-08) blocks every web deploy rather than publishing the sport that exists. The page list already knows how to omit a sport.

**Fix**: either keep it and write the consequence into DEPLOY.md, or fail only when *both* lists are empty and log loudly for one.

### CR-09 (LOW, REASONED) - a failed season-list request removes the main action silently

**Where**: `web/src/pages/HistoricalDraft.tsx:177` (`user && offered.length > 0`), `web/src/redraftData.ts:146` (`.catch(() => {})`), `web/src/pages/HistoricalOldBackend.test.tsx`.

**What happens**: the old home kept "Draft a random year" enabled when the seasons request failed ("we can't tell, so the old behaviour stands rather than locking the page"). The new home hides the button and both tiles when the public list request fails, shows no error and offers no retry. A hard load of `/` is safe (the lists are embedded); a client-side arrival from `/game/<id>` or a season page fetches. The rewritten old-backend test dropped `expect(... 'Draft a random year')` and now pins the absence.

**Fix**: track failure in `useHomeLists` and show the same "didn't load, try again" line the sport index uses; keep the random-year button available to a signed-in player when the list is unknown.

### CR-10 (LOW, REASONED) - two things do not follow midnight ET under an open page

**Where**: `web/src/pages/DailyPage.tsx:22` (effect keyed on `attempt` only); `web/src/pages/SharedResult.tsx` (no use of `reducedUntil` beyond a boolean).

**What happens**: at rollover the cards refetch, but "Yesterday" keeps showing the day before the day that just ended (it is labelled with its date, so it is stale, not mislabelled). A reduced shared result stays reduced and keeps saying "show tomorrow" after `reducedUntil` passes, until reload.

**Fix**: refetch yesterday when `useDailyGames` reports a rollover; in `SharedResult`, set a timer for `reducedUntil` and ask the parent to refetch.

### CR-11 (LOW, VERIFIED in part) - the guest UUID is a credential and is not treated as one

**Where**: `HistoricalGameService.java:189` (logs the guest key at INFO), `web/src/guest.ts:27-29` ("It is a label, not a secret", `Math.random` fallback), `Identity.java` `UUID_FORM`.

**What happens**: knowing a guest's UUID is enough to read and play their drafting games and to move all their games to any Sleeper id. **Verified**: `POST /claim` with an invented Sleeper id and the UUID in upper case moved both of a guest's games (`{"moved":2,"leftBehind":0}`); the guest then saw an empty list and a 404 on their own game. A replay moved 0. Nothing the server returns exposes the UUID (checked on the public game JSON and by `assertNoOwner`), so this needs a leak, and the three small ones are: the key is written to the log on a claim race; the fallback generator is predictable; `00000000-0000-0000-0000-000000000000` is accepted as a shared identity.

**Fix**: log a hash or the last four characters; build the fallback from `crypto.getRandomValues`; refuse the nil UUID; correct the comment.

### CR-12 (LOW, REASONED) - hygiene and test gaps

- `web/src/api.ts:5067`: the comment says some field names are "GUESSED" and still to reconcile. They match the Java records and the real JSON (checked, see below); delete the sentence so nobody goes looking.
- `web/scripts/fetchSeasons.ts` validates `slug` as a string only, and `redraftPages.ts` uses it as a file name (`${slug}.html`). The backend is trusted, but a slug check (`/^\d{4}(-\d{2})?$/`) costs one line and makes "a label that is not a safe file name" a named build failure.
- `HistoricalRoom.tsx`: a guest who signs in from "Keep this result" when the claim leaves that daily behind (the Sleeper id already played today) now sees the award panel request a game the Sleeper id does not own (404). Show the left-behind line there instead.
- Missing tests for things that work today: two concurrent `POST /games` from one guest (verified by hand: six in parallel gave one 201 and five 403, one row); the sign-in-from-limit-panel flow (CR-05); a combined-build link check (CR-04).

## Checked and found correct

- **Guest allowance holds under a race** (VERIFIED): six parallel chosen-season creates, mixed-case UUID, produced exactly one game. The advisory lock is taken inside the `@Transactional` create and the count is read after it.
- **Identity** (VERIFIED): a stranger (other guest id, or a Sleeper id) gets 404 on get, auto and abandon of a drafting game, and the public route answers 404 for it. A blank `X-Sleeper-User` with a guest id resolves to the guest. `X-Sleeper-User: guest:<uuid>` is 400, any case. A guest asking for a random season is 403 `GUEST_LIMIT`; `seasonStart: 1969` is 400; a non-numeric one is 400.
- **Claim transaction** (code read, plus the two race ITs ran): each attempt is its own `TransactionTemplate` transaction and `DuplicateKeyException` is caught outside it, so no retry runs in an aborted transaction. `HistoricalDailyService` is not transactional, so its own duplicate-key catch is also outside the failed transaction.
- **`chosen_season`**: set only by `createFor` with a `seasonStart`; `createDaily` and the test overloads pass false; the V50 check constraint has no existing row to violate (default false). `unsettled` excludes chosen games; `award` answers `notCounted`; web `landingStats` excludes them (test asserts best and average). V50 is the only V50 in any worktree.
- **Season summaries** (VERIFIED on 47 NBA seasons): every bust has `finishRank > goingInRank` and a going-in rank within the first 60; every steal the reverse; no player in both lists; `topOverall` in rank order; dream roster is exactly `rounds` distinct players. 2016-17 reads sensibly (busts Middleton 54 to 309 with 10 weeks played; steals Schröder, Turner). Multi-position players exist (1995-96 SF/PF, 2009-10 PF/C) and are listed under their primary position only, which is the same pool the game uses for `finishPositionRank` (`Comparisons.pickFinishes`), so the two numbers agree by construction.
- **Public routes** (VERIFIED): `2016` 404, `2016-17%20` 404, `%2e%2e` 404, `..%2Fx` 400, unknown sport 404, lower-case `nba` in the path 200, `?sport=nba` 400, missing sport 400, `games/abc` 400, an id beyond long 400. `Cache-Control: public, max-age=3600` on a season. NFL list with no data is `{"schema":1,"sport":"NFL","seasons":[]}`, not an error.
- **Reduced public game** (VERIFIED by walking the JSON): no player, pick, lineup, week, hindsight roster, bust, steal or owner. What remains is in CR-03.
- **Records against `api.ts`** (diffed against Java source and against real JSON keys): `SeasonPage`, `Finisher`, `Mover`, `DreamPlayer`, `SeasonList`, `SeasonRef`, `YesterdayDaily`, `PublicGame`, `ClaimResult`, `HistoricalGameState.chosenSeason`, `GameSummary.chosenSeason`, `AwardView.notCounted`, `CreateRequest.seasonStart`. All match. The guest-limit body is built with `LinkedHashMap`; no `Map.of` with a nullable value was added.
- **Static generation** (VERIFIED): a fixture with `O'Neal </script><script>alert(1)</script> & "Q" <b>` as a name, markup in team, notes, scoring note and a position came out fully escaped in body, meta description and class attributes; the embedded JSON parses and contains no literal `<`; JSON-LD parses. On the real-data build, 53 indexable pages, no duplicate title or description, every canonical equals its file's slashless address, sitemap has the same 53, no "time machine" or "hindsight" in any generated HTML or the JS bundle. `app-shell.html` and `404.html` carry the static noindex.
- **Rewrites against routes**: every `TimeMachineTree` route is either a static file (`/`, `/daily`, `/nba`, `/nfl`, each season) or in the rewrite list (`/game/**`, `/history`, `/history/**`, the five league prefixes bare and with `/**`).
- **Leagues and combined builds** (VERIFIED): both build; no season pages, no embedded data, `serve.site.json` is the single `**` rewrite; leagues emits no sitemap and keeps its bare title.
- **Guest header scope**: sent only when no Sleeper id is set and only on `/api/history/**` outside `/public/`. The league site makes no such request, and `claimGuestGames` returns without a request when no guest id exists.
- **Rewritten tests**: the deleted front-door parity test is replaced by a stricter static-versus-mounted comparison over six pages with named `data-dynamic` exclusions; the title test now covers seven pages. The one lost pin found is in CR-09. The CSS guard reads the stylesheet from disk and checks its length first.

## Not checked

- Anything football with real data (no NFL seasons locally): label and slug, `pointsNote` truth, ranked count on a real board.
- A browser: CORS preflight from a real origin, the CSP report for the JSON data block, hash scroll to `#sign-in`, focus moves.
- The real `serve` command on a Phase 5 build, and Railway's edge in front of it.
- Cold compute time and heap for a season (t017 was not re-measured).
- The web rendering of the real reduced payload (read only; the existing tests use a hand-built fixture).
- T045 to T048 (DEPLOY.md, HANDOFF.md, README, lessons) are unwritten and were not reviewed.

## For the parent session

Counts: 0 BLOCKER, 1 HIGH, 4 MEDIUM, 7 LOW. CR-02 and CR-03 need Allan's decision before a fix is chosen; CR-01, CR-04 and CR-05 are plain fixes.

Lessons that belong in `claude/lessons.md`:

1. **A rendered asset is not shipped by editing its source** (CR-01). A text guard cannot see pixels; pin source and output together.
2. **A "move my things to my account" endpoint is also a transfer for whatever rule the things were limited by** (CR-02). List every one-per-user rule before building a claim or merge.
3. **A page shared by two site trees must take its links from `site.ts`** (CR-04). This is the "second implementation of one rule" class again: a path literal asserts that a route exists on every site.
