# Bug-hunting code review: 076 Usage analytics we own

**Date**: 2026-11-17 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: AGENTS.md, the headings of claude/lessons.md (57, 60 and 61 checked against this change), spec.md
(both amendment blocks), plan.md (the final "Amended after review" section), data-model.md,
contracts/usage-api.md, tasks.md; the whole uncommitted change (`git status`, `git diff`, every untracked
file) and the code it meets (`AppShell.tsx`, `TimeMachineShell.tsx`, `main.tsx`, `user.ts`,
`HistoricalGameRepository.moveOwner`, `Identity.isGuest`, `LeagueLockInterceptor`, `WebConfig`, `ClockConfig`).
plan-review.md was read only through the plan's summary of it.

**What I ran** (nothing in the project was changed except this file):

- Backend, targeted: `./gradlew test --tests "*Usage*" --tests "*StartedAsGuest*" --tests "*LeagueLockMappings*"`
  → BUILD SUCCESSFUL, **58 tests, 0 skipped, 0 failed** (AdminUsageMvcIT 7, UsageCollectMvcIT 9,
  StartedAsGuestIT 3, UsageFunnelIT 4, LeagueLockMappingsIT 4, UsageLimiterTest 5, UsageServiceTest 11,
  UsageVocabularyTest 8, plus the unrelated AdvancedStatsUsageTest 7 the pattern also matched). Postgres on 5433
  was up and already at V51. The full backend suite was **not** run.
- Web: `npx tsc -b` clean. The six named vitest files: 6 files, 139 tests passed. Full `npx vitest run`:
  **218 files, 3599 tests passed**. `npm run build` was **not** run.
- A scratch Java harness (scratchpad, compiled against `build/classes`) that feeds `UsageVocabulary.validate`
  every body `analytics.ts` can produce, and drives `UsageLimiter` with oversized keys.
- psql against localhost:5433 inside a transaction that was rolled back (0 rows left, checked): an insert that
  does not name `started_as_guest`, and the funnel SQL over an owner-owned guest-started game.
- The body of `funnelTotals` / `daysBetween` from `usageSummary.ts` under node with concrete rows (the file's
  code copied to the scratchpad with only its imports removed; a scratch vitest could not load a file outside
  the web root).
- No bootRun, no dev server, no browser. Everything about production (Railway's `X-Real-IP`, CORS preflight on
  a keepalive fetch, CSP) is unverified here and belongs to live verification.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | MEDIUM | funnel SQL + usageSummary | The owner's claimed guest game is counted as both "owner" and "guest": the page shows "Started signed in: -1" and a guest row larger than the headline |
| CR-02 | MEDIUM | AdminUsage.tsx | "Yours, left out of the totals above" is true of the three headline numbers only; the game-of-the-day row and the per-day table still include the owner's games |
| CR-03 | LOW | UsageController / UsageLimiter | The `X-Real-IP` header is used as a map key at any length: 10,000 keys of 7 KB each are accepted in one window |
| CR-04 | LOW | UsageService | The rejected counter can lose increments that race the flush |
| CR-05 | LOW | UsageService / page | A limited or over-ceiling send is counted nowhere and logged nowhere, so the owed `X-Real-IP` check has nothing to read |
| CR-06 | LOW | tests | One vocabulary assertion passes for a rejected body too; the funnel IT has one sport and no owner/guest overlap |
| CR-07 | LOW | docs / plan amendments | DEPLOY.md, HANDOFF.md and the lessons entry are not in the change; the page does not say the per-address limit is best effort |
| CR-08 | LOW | AdminUsage.tsx | A site's days before its own first count are drawn as "0 views" once any site has counted |

No HIGH. Counts: 0 HIGH, 2 MEDIUM, 6 LOW.

## Findings

### CR-01 (MEDIUM, VERIFIED) - an owner game that started as a guest is subtracted twice

**Where**: `backend/src/main/java/com/ballknowers/draftsim/usage/UsageRepository.java:141-143` (the
`owner_started` / `owner_finished` filters) and `web/src/usageSummary.ts:51` (`signedInStarted`).

**What happens**: plan F5 defines `ownerStarted` / `ownerFinished` as "his signed-in games". The SQL counts
every row whose `sleeper_user_id` is the owner id, whatever `started_as_guest` says. Claiming
(`HistoricalGameRepository.moveOwner`) rewrites `sleeper_user_id` and leaves `started_as_guest = true`, so a game
the owner played signed out and then claimed is in `guestStarted` **and** `ownerStarted`. The same overlap
exists with `unknownStarted` for an owner row written by code that predates the column. The page then computes
`signedInStarted = allStarted - guestStarted - unknownStarted - ownerStarted`, which assumes the three are
disjoint.

**Reproduce**: owner id configured; the owner plays one NBA game as a guest, finishes it, signs in (claim).
SQL in a rolled-back transaction returned, for that day: `started 1, finished 1, guest_started 1,
owner_started 1`. Feeding that row to `funnelTotals(rows, true)` returned
`{"started":0,"finished":0,"guestStarted":1,"guestFinished":1,"signedInStarted":-1,"ownerStarted":1,...}`.
The page would read: 0 games started, "Started as a guest: 1 (1 finished)", "Started signed in: -1". With the
owner's game mixed into a busier day the result is not negative, just one too low, which is harder to notice.
The caveat on the page ("Your own guest-mode games cannot be told apart from anyone's") is also false for a
claimed one.

**Fix**: make the owner counts what the plan says, in the SQL:
`... sleeper_user_id = cast(? as text) and started_as_guest is false` on both filters. Then owner, guest and
unknown partition cleanly, `signedInStarted` is right, and a claimed guest game stays in the guest row and in
the headline (consistent with the caveat). Update `UsageRepository.funnel`'s javadoc and the `ownerStarted`
comment in `UsageReport` / `api.ts` to say "signed-in games".

**Regression test**: in `UsageFunnelIT.seedWorld` add `game(OWNER, ..., "COMPLETE", null, true)` and
`game(OWNER, ..., "DRAFTING", null, null)` and assert `ownerStarted` is unchanged and
`started == guestStarted + unknownStarted + ownerStarted + (other signed-in)`. In `usageSummary.test.ts` assert
`signedInStarted >= 0` for a row where the owner's game is also a guest game.

### CR-02 (MEDIUM, VERIFIED) - the owner is left out of three numbers and the sentence claims all of them

**Where**: `web/src/pages/AdminUsage.tsx:151-154` (game-of-the-day row), `:164-169` (the "Yours, left out of
the totals above" line), `:192-199` (per-day table); `web/src/usageSummary.ts:52-53`.

**What happens**: `started`, `finished` and `notFinished` exclude the owner. `dailyStarted` / `dailyFinished`
and every cell of the per-day table are sums over all games. The only disclosure is for the gave-up and
left-open rows, and only when the owner has an unfinished game. The owner is the person most likely to play the
game of the day every day, so this is the normal state once `APP_OWNER_SLEEPER_USER_ID` is set.

**Reproduce**: one day, owner configured: the owner finishes the NBA game of the day signed in, one guest
finishes it too. `funnelTotals` returned `{"started":1,"finished":1,...,"dailyStarted":2,"dailyFinished":2,
"ownerStarted":1}`. The page shows "1 games started" and, under it, "Game of the day: 2 started, 2 finished",
followed by "Yours, left out of the totals above: 1 game started". The per-day table's row for that day says
Started 2. SC-009 ("how many games were started yesterday") is answered from that table, so the answer includes
the owner's games with no label saying so.

**Fix**: per-day table, frontend only: show `r.started - r.ownerStarted` and `r.finished - r.ownerFinished`
when `report.ownerConfigured` (the fields are already on each row), and say so in the caption. Game of the
day: either add `ownerDailyStarted` / `ownerDailyFinished` to the SQL, the two Java records and `api.ts` in one
change and subtract them, or keep the numbers and label the row "Game of the day (all games, yours included)".
Narrow the sentence to name exactly what was left out.

**Regression test**: `AdminUsage.test.tsx`, owner configured, owner game is a daily: assert the game-of-the-day
count is not larger than the headline started, and that the per-day row sums to the headline.

### CR-03 (LOW, VERIFIED for acceptance; memory size REASONED) - an unbounded header becomes a map key

**Where**: `backend/src/main/java/com/ballknowers/draftsim/api/UsageController.java:42-45`,
`usage/UsageLimiter.java:43-51`.

**What happens**: `X-Real-IP` is trimmed and used as the limiter key at whatever length Tomcat let through
(8 KB of headers by default). The harness sent 10,050 distinct 7,000-character addresses in one window:
10,000 were permitted and retained, and a real new address after that was refused. That is about 70 MB held
for up to a minute on a service capped at `-Xmx1024m` (spec 041), per window, for 10,000 small requests. It
only matters if the header is attacker-controlled, which is exactly the thing the plan says is unknown until
after deploy.

**Fix**: in `address()`, accept the header only when it is at most 45 characters (the longest textual IPv6
address); otherwise fall back to `request.getRemoteAddr()`. One line, and it also keeps junk out of the map.

**Regression test**: `UsageLimiterTest` or a controller test: a 5,000-character `X-Real-IP` is keyed on the
socket address.

### CR-04 (LOW, REASONED) - a rejected count can be lost between `remove` and `sum`

**Where**: `backend/src/main/java/com/ballknowers/draftsim/usage/UsageService.java:132-134` and `:144-148`.

**What happens**: `noteRejected` does `computeIfAbsent(key).increment()`: two steps. The flush does
`remove(key)` then `adder.sum()`. A request thread that fetched the adder before the remove and increments
after the sum adds to an object nobody will read again. The count is silently one short. Under virtual threads
with a burst of junk at the minute boundary this is reachable, though small. No double flush is possible (the
CAS on `lastFlush` and the atomic `remove` see to that).

**Fix**: store `Long` values and use the map's atomic operations: `rejected.merge(key, 1L, Long::sum)` to
count and `Long n = rejected.remove(key)` to take; the put-back becomes `rejected.merge(key, n, Long::sum)`.

**Regression test**: not worth a concurrency test; the code change is mechanical.

### CR-05 (LOW, REASONED) - limiting is invisible, so its failure mode cannot be seen

**Where**: `UsageService.java:93` and `:109`; contract "counted nowhere".

**What happens**: if production does not deliver `X-Real-IP` to the backend, every visitor shares the proxy's
socket address and the whole site is capped at 60 sends a minute. Nothing records that a send was limited: no
row, no log line, nothing on the page. The plan owes a post-deploy check of this, and as built that check has
nothing to read except "the numbers look low", which is indistinguishable from low traffic. That cuts against
this project's rule about not looking more certain than it is.

**Fix**: count limited and over-ceiling sends in two in-memory `LongAdder`s and log one INFO line a minute when
either is non-zero (counts only, no address, no agent). Optionally surface them on the report later; the log
line alone makes the post-deploy check possible and needs no record or `api.ts` change.

### CR-06 (LOW, VERIFIED) - assertions that cannot fail, and funnel cases never seeded

**Where**: `backend/src/test/java/com/ballknowers/draftsim/usage/UsageVocabularyTest.java:94`;
`usage/UsageFunnelIT.java:90-100`.

**What happens**:

- Line 94 asserts `validate({"site":"timemachine","name":"sign-in","props":{}}).props()` equals `""`.
  `Result.rejected(site)` also has props `""`, so the assertion holds whether the body is accepted or refused.
  This is the one body shape the two builders could most easily have disagreed on (the client always sends
  `props: {}` for `sign-in`). I ran the exact client bodies through `validate` in a harness: all 16 the client
  can produce (three kinds, both `sign-in` sites, every `via` / `from`, two `result-shared` combinations, an
  ESPN-style league id) came back `OK`. So the seam is fine; the test just does not prove it.
- `UsageFunnelIT` seeds NBA only ("two start days, one sport"), so `group by sport` and the lowercase
  conversion for `NFL` are never exercised against the database, and no row is both the owner's and a guest's
  (CR-01).
- `UsageCollectMvcIT.rejectedSendsTakeNoWrite...` asserts `>= before + 4`; an over-count would pass.

**Fix**: line 94 → also assert `Status.OK`, and add one test that feeds the literal bodies `analytics.test.ts`
expects (`{"kind":"event","site":"combined","name":"sign-in","props":{}}` and the three in "the body holds only
vocabulary fields"). Add an NFL game to `seedWorld`. Make the rejected assertion exact by clearing the
in-memory counters first (a forced `report()` call does that).

### CR-07 (LOW, VERIFIED) - taken plan amendments that are not in the change

**Where**: `git status` (no `DEPLOY.md`, `HANDOFF.md` or `claude/lessons.md`); `web/src/pages/AdminUsage.tsx`
caveat block.

**What happens**: plan F8 says DEPLOY.md states the vocabulary guard is a local vitest run required for any
route change, and the plan's Docs section adds the deploy order (backend first) and the dev-only switch. None
of the three documents is modified. Plan F3 + F7 say "the page says the per-address limit is best effort"; the
string does not appear on the page (the caveats cover "open to anyone" but not the limit). All tasks in
tasks.md are still unchecked, T021-T023 included.

**Fix**: write T022's DEPLOY.md section and the HANDOFF/lessons entries; add one caveat line: the limit per
address is best effort and a site-wide ceiling of 600 counted sends a minute backs it, so a very busy minute
can under-count. Tick the tasks that are done.

### CR-08 (LOW, REASONED) - "counted" is decided for all sites together

**Where**: `web/src/pages/AdminUsage.tsx:252`; `UsageRepository.countingSince()`.

**What happens**: `countingSince` is the earliest day of any view, league view or event on any site. A site's
per-day series draws every day from then on as "0 views". If the Redraft web service starts sending on day 1
and the league web service is deployed (or first unlocked and opened) on day 5, the league site's days 1 to 4
read "0 views", the exact reading the spec's edge case forbids ("never a zero that reads as nobody came").

**Fix**: in `ViewsPerDay`, use the site's own first day in the data when it is later than `countingSince`
for the "counted" test only if the API can supply it: the report's range may not contain it, so the correct
fix is server-side, `countingSince` per site (`[{site, since}]`, mirrored in `api.ts`). If that is more than
this feature wants, add a caveat line saying counting started on the date given for whichever site sent first.

## Checked and found sound

- **`api.ts` vs `UsageReport.java`**: all 12 top-level fields and every row record match by name, type and
  nullability; days are `YYYY-MM-DD` strings; sport is lowercased in one place; nulls are serialized (the IT
  asserts `has("countingSince")`). No `Map.of` on either response path.
- **Client bodies vs server**: every body `analytics.ts` can build is accepted (harness, above). The vitest
  guard holds the JSON equal to the exports and fails when the file is missing.
- **SQL**: upsert target equals the primary key; `LocalDate` binds work; `created_at` is `timestamptz`, so the
  ET bucketing is right even though the local session zone is America/Chicago; `league.sleeper_id` is unique,
  so the name join cannot multiply; `league.sport` is NOT NULL; statuses match the table's check constraint.
- **V51**: nullable, no default; an insert that does not name the column succeeds and stores null (psql, rolled
  back, and `StartedAsGuestIT`). V50 is the highest on `origin/main`; no other local branch has a V51.
- **`started_as_guest`**: one insert site, set from `Identity.isGuest`, unchanged by claim (IT).
- **Collect path**: nothing can throw past the controller; a refused send touches no repository method; an
  unknown league costs one read under the ceiling; deep or huge JSON is caught.
- **analytics.ts**: no import cycle (`api.ts` does not import it); opt-out stops both sinks; localhost and
  preview hosts send nothing without `DEV && VITE_USAGE_COLLECT === '1'`; debug mode logs and sends nothing;
  the full web suite passes with the new runtime import.
- **ShareButton**: one call per successful share, after the await; none on `AbortError` or the manual fallback;
  only shown to the game's owner.
- **`adminToken.ts`**: a verbatim lift; AdminDigest's behaviour is unchanged. `/admin/usage` is in the shared
  `leaguePages` fragment, so the league and combined trees both have it and the Redraft tree forwards it.

## For the parent session

0 HIGH, 2 MEDIUM, 6 LOW. CR-01 and CR-02 should be fixed before live verification, since both change what the
funnel section prints. One candidate lesson, an extension of lesson 60: **"left out of the totals" is a claim
about every number on the page, and a claim moves rows between the sets you subtract** — when a page excludes
a group from a total, check each other number in the section against the same exclusion, and check that the
groups being subtracted are disjoint after every write that can re-own a row.
