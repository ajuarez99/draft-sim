# Bug-hunting code review: 064 Time Machine achievements and levels

**Date**: 2026-11-13 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `AGENTS.md`, the headings of `claude/lessons.md` (checked classes 1, 2, 3, 5, 12, 14, 16, 17, 20, 21, 22,
24, 27, 29 against the new code), `spec.md`, `plan.md` (amendments A1-A15 and the final wire contract),
`plan-review.md`; `V48__time_machine_achievements.sql`; `Badge`, `Levels`, `AchievementRules`,
`HistoricalAchievementRepository`, `HistoricalAchievementService`, `HistoricalAchievementController`; the diffs to
`HistoricalGameService` (`finishDraft`, `capturePickFacts`) and `HistoricalGameRepository.writeResult`; around
them `HistoricalResult`, `Comparisons`, `SeasonFormat`, `ErrorHandler`, `WebConfig`, `HistoricalGameController`;
web `api.ts` additions, `AchievementToasts`, `HistoricalAwardPanel`, `HistoricalProfileCard`, the diffs to
`HistoricalResults`, `HistoricalRoom`, `HistoricalDraft`, `styles.css`; all seven new test files.

**What I ran** (nothing in the project was changed except this file):

- Backend, the requested filter `--tests "*Achievement*" --tests "*Levels*" --tests "*Historical*"`:
  **139 ran, 0 failed, 4 skipped**, against Postgres on `localhost:5433` (Flyway at V48 already). The 4 skips
  are data-dependent real-season tests (`HistoricalRealSeasonIT` x2, `HistoricalSameNameIT` x1,
  `HistoricalGameMvcIT.postCreatesARealSeasonGame...` x1), not "Postgres down". `HistoricalAchievementMvcIT`
  7/7 executed, `AchievementRulesTest` 26/26, `LevelsTest` 5/5.
  **Caveat**: my first invocation printed `:test UP-TO-DATE` and executed nothing; the XML in
  `build/test-results` was the parent's run from 20:29Z. The counts above are from a forced second run
  (20:43Z) that also added `--tests "*Nfl*"` and my scratch tests: 191 ran, 0 failed, 5 skipped in total.
  The requested filter does **not** match `NflGameFlowIT` (no "Historical" in its name); it passes 8/8 when
  included.
- Web: `npx tsc -b` exit 0; `npx vitest run` **176 files, 2,949 tests, 0 failed, 0 skipped**.
  `npm run build` was not asked for and not run.
- Scratch reproductions, compiled into the test run through a Gradle init script so nothing was added to the
  repo (all under
  `C:\Users\allan\AppData\Local\Temp\claude\8a96462f274caf72\8407d467-af8c-4def-9956-c838c925ba36\scratchpad\src\com\ballknowers\draftsim\`):
  - `api\Cr64WireIT.java`: 12 auto-drafted NBA games and 12 NFL games on the **long** synthetic seasons
    (22 and 17 weeks, so playoffs, titles and moments actually happen), read through the real MVC routes; the
    raw JSON printed and compared with `api.ts`; a CORS preflight on all three routes.
  - `api\Cr64SavepointIT.java`: a real server-side statement failure (`select 1/0`) inside one game's savepoint,
    on the real `settle` path, through a spied repository.
  - `historical\Cr64RulesTest.java`: rule boundaries.
- Not done: no dev server was started and no browser was driven (that is the verification stage). The CORS
  check is MockMvc against the real `WebConfig`, not a socket-level `curl`. No screen reader. The synthetic
  seasons say nothing about how often a badge fires on real data.
- Another session was using the shared 5433 database during the run (three `hindsight-verify` NFL games and
  NFL 2021-2023 seasons appeared at 14:41 local). I left them alone; my `it-cr64-*` rows are deleted.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

Severity as asked: **BLOCKER** / **SHOULD** / **NIT**.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | SHOULD | Toasts | The first reach / bust / gem pops twice: a badge toast and a moment toast with the same title and the same sentence |
| CR-02 | SHOULD | Accessibility | The `role="status"` region is mounted in the same commit as its first three toasts, so they are probably never announced |
| CR-03 | SHOULD | Rules | REACH_HIT cannot be earned by any round-1 pick (pick 12 taken 28 early and finishing 1st earns nothing); the rule text does not say so |
| CR-04 | SHOULD | Display | A backfilled badge shows the day it was settled as the day it was earned |
| CR-05 | NIT | Toasts | Seen is posted and the 6 s clock runs in a hidden tab; all of a game's moments are marked seen when the first one shows |
| CR-06 | NIT | Rules | One pick can raise three moments; with CR-01 a first-time flop is up to five toasts about one player |
| CR-07 | NIT | Copy | Rule text hard-codes "round 8 or later" and "36 in a 12-team league"; old-format PERFECT_SEASON / TOP_SEED say "regular season" |
| CR-08 | NIT | Settle | A game that cannot settle is re-read, re-parsed and logged at ERROR with a stack trace on every read, twice per results page |
| CR-09 | NIT | Cost | `capturePickFacts` scores the whole board's season a second time inside `finishDraft`; unmeasured |
| CR-10 | NIT | Tests | No committed test runs playoffs, a title, a moment or football through settle, or a failed statement inside the savepoint |

0 BLOCKER, 4 SHOULD, 6 NIT.

## Checked and OK (no finding)

VERIFIED by running:

- **Wire contract vs `api.ts`**, field by field against the real JSON from both routes: `HistoricalProfile`,
  `HistoricalGameAward`, `HistoricalBadge`, `HistoricalMoment`, `lines[]` all match in name, nesting and
  nullability. `source` is the string `STORED` / `NEW_GAMES`; `detail` round-trips as an object
  (`{text}` or `{text, pickNo, playerName}`), null while locked; `unlockedAt` is an ISO string with
  microseconds (`2026-11-13T20:43:46.841557Z`), which `new Date()` parses (checked in Node; V8 only).
- **JDBC binds on the real path**: `pick_facts = ?::jsonb` with a null and with text, `?::jsonb` for lines,
  moments and detail, `case when ? then now()` with a boolean, `code = any(?)` through
  `createArrayOf("text", ...)`, `getObject(..., OffsetDateTime.class)`, the `void` advisory-lock select.
- **Savepoint isolation**: with a real Postgres error raised after the award insert of game A, `settle`
  returned 1 failure, A had no award row and no badge rows, games B and C settled in the same transaction,
  and A settled on the next call. One FIRST_GAME row throughout.
- **Double settle**: the IT's four concurrent `settle` calls (pool of 3) leave one award per game.
- **Cumulative badges include the game just inserted**: WINS_10 unlocked by the game that took the total
  from 7 to 17, WINS_100 by the one that crossed 100, each with that game's id.
- **Arithmetic** on 24 games: lines sum to `xp`; `total = xp + badges`; profile XP = awards + badges;
  `weekly_wins = standings wins + playoff matchups won`; `title` iff `userOutcome == CHAMPION`.
- **Pick facts agree with the frozen result**: in all 24 games the largest `seasonValueRank - pickNo` in
  `pick_facts` is the stored `biggestBust` (same pick, same rank), or there is none in both.
  `seasonWeeks` is 22 (NBA) and 17 (NFL).
- **Rule boundaries**: bust gap 35 no / 36 yes; 15 rounds gives reach in rounds 1-7 and gem from round 8;
  REACH_HIT and REACH_MISS are exclusive.
- **CORS** (MockMvc, real `WebConfig`): preflight from `http://localhost:5173` with
  `content-type,x-sleeper-user` answers 200 on all three routes, `POST` allowed; a foreign origin gets 403.
  Production depends on `CORS_ORIGINS`, unchanged by this branch.
- **Access**: 401 for no header, `""` and `"   "` on all three routes; a stranger gets 404 before 409; Bob
  cannot mark Alice's badges or moments (IT).

REASONED:

- `capturePickFacts` can only throw `RuntimeException`: `write` wraps the checked Jackson exception, and a
  null card, rank or value is an NPE on unboxing, which is caught and stores null.
- REACH_HIT and LATE_GEM cannot both fire for a pick (`round <= rounds/2` against `round > rounds/2`). A badge
  cannot come from both the stored bust and the moments (`else if`).
- No `Map.of` on the response paths. No read-only transaction calls `settle`; both reads are write
  transactions. The lock select runs on the transaction's connection.
- Quiet backfill, mixed batch, `seen_level` upsert and clamp read correctly and are covered by the IT.
- The level-up toast does not repeat after a backfill: `seen_level` is raised per quiet game, and the toast
  posts `level` when shown.
- The fetch cannot fire during the paced reveal: the panel is only in the results branch of `reveal.view`
  (and `HistoricalRoomAchievements.test.tsx` pins it).
- StrictMode: two fetches, one set of toasts (`current` flag, keyed queue, `shown` ref). The week picker
  re-renders `HistoricalResults` without remounting the panel.
- Landing page on an old backend: `json()` throws on 404, the catch leaves `profile` null, nothing renders.
- No string claims an injury. GONE_EARLY reads "his last box score in our data came in week N".
- The styles guard test reads the file with `node:fs`, not `styles.css?raw` (lesson 27 does not apply).

## Findings

### CR-01 (SHOULD, VERIFIED payload, REASONED toast) - the first reach or bust pops twice with the same words

`web/src/components/HistoricalAwardPanel.tsx:25-42` (`toastsFor`). Every unseen badge becomes a toast, and
every moment becomes a toast, with no check that they are the same event. The first time a pick-level badge
unlocks, the badge's `detail.text` **is** the moment's text (`AchievementRules.java:152-160` copies it).
Real payload from the run, game 198:

```
badges[2]  BIG_BUST "Swing and a miss" detail.text "Synthetic 112, pick 24, finished 107th of 168 drafted players by season value, 83 places below where you took him."
moments[0] BIG_BUST "Swing and a miss" text        "Synthetic 112, pick 24, finished 107th of 168 drafted players by season value, 83 places below where you took him."
```

So a fan's first bust shows "Badge unlocked / Swing and a miss / <sentence> / +50 XP" and then "Moment /
Swing and a miss / <same sentence>". SC-001 says one toast per new badge.

**Fix**: in `toastsFor`, collect `code:pickNo` for each unseen badge whose `detail.pickNo` is a number, and
skip a moment with the same `code` and `pickNo`. Still post `momentsGameId` (the `onShown` path does it only
for a moment toast, so when every moment was skipped, post it with the badge's seen call or once on mount).
**Test**: award with one unseen BIG_BUST badge and the matching moment renders one "Swing and a miss" toast
and `momentsSeen` is still posted; a second BIG_BUST moment on another pick still shows.

### CR-02 (SHOULD, VERIFIED structure, REASONED effect) - the live region arrives with its content

`HistoricalAwardPanel.tsx:91` returns `null` until the award loads, and `:149` then mounts
`<AchievementToasts>` (the `role="status" aria-live="polite"` element, `AchievementToasts.tsx:89`) in the
same commit as its first three toasts. Screen readers announce changes to a live region that is already in
the accessibility tree; a region inserted together with its content is commonly not announced. Those first
three toasts are the badges, which are the point of FR-009. Later toasts (promoted from the queue) are
additions and would be read. This is the same trap `HistoricalRoom.tsx:274-275` documents for the
end-of-draft sentence (063 CR-08). The component test only asserts the attributes.

**Fix**: render `<AchievementToasts toasts={toasts} onShown={onShown} />` in every branch of the panel from
the first render (wrap the returns in a fragment, region first), so it is mounted empty while the request is
in flight. **Test**: before the award promise resolves, `getByRole('status')` already exists and is empty.
Needs a real screen reader at verification, as CR-08 did.

### CR-03 (SHOULD, VERIFIED) - "Called it" is unreachable in round 1, and the badge does not say so

`AchievementRules.java:180`: `reach && p.seasonValueRank() <= p.pickNo() - teams`. For any pick in round 1,
`pickNo - teams <= 0`, and a rank is at least 1. Ran it: pick 12, board rank 40, finished **1st** of 168 gives
no moment; pick 13 with the same numbers gives REACH_HIT. This is amendment A5 exactly as written, so the
code is not wrong against the plan; the plan's formula has the hole. The rule text (`Badge.java:42-44`,
"finishes at least R places better than your pick number") reads as possible for every first-half pick, and
`aHitNeedsAFullRoundOfMarginNotJustTheSlot` does not pin round 1 either way.

**Fix, Allan's call** (it changes a rule): either (a) keep it and say so in the rule text ("a pick in rounds
2 to 7"), or (b) give round 1 a reachable bar, for example `seasonValueRank <= max(pickNo - teams, pickNo / 2)`
or "top 3" for round 1, and state it. **Test** either way: a round-1 reach finishing 1st, asserted to be a
hit or asserted not to be, with the reason in the test name.

### CR-04 (SHOULD, VERIFIED value, REASONED display) - a backfilled badge is dated the day it was counted

`HistoricalAchievementRepository.java:81-90` (`insertBadge`) writes `unlocked_at = now()`, and
`HistoricalProfileCard.tsx:85` prints it after the XP as the earned date, beside "See the game". For every
`pre_achievements` game that is the day of the first profile read after deploy, not the day the game was
played; in the run all 12 games' badges carry the one settle timestamp. Every existing player's trophy case
will show the deploy week for games from earlier in November. Spec US3: "earned ones with the date and the
game that earned them". A correct timestamp presented as a different fact (lesson 5).

**Fix**: select `g.completed_at` in `unsettled()` (`:48-63`), carry it on `UnsettledGame` as
`OffsetDateTime`, and bind it for `unlocked_at` in `insertBadge` (bind the `OffsetDateTime` itself, as
`writeResult` does; not a `Timestamp`, lesson 9). Do it for every game, not only quiet ones, so a game opened
a week late is dated right too. `seen_at` stays `now()`. **Test**: in
`aGameFromBeforeAchievementsSettlesQuietly...` the badge's `unlockedAt` equals the game's `completed_at`
(three days ago), not today.

### CR-05 (NIT, REASONED) - seen can be posted for a toast nobody saw

- `AchievementToasts.tsx:24-28, 77-84`: `onShown` fires and the 6 s timer starts on mount whether or not the
  tab is visible. Opening a game in a background tab (middle-click on "See the game") shows, marks seen and
  dismisses the first three toasts unseen. Fix: while `document.visibilityState !== 'visible'`, neither call
  `onShown` nor start the timer; resume on `visibilitychange`.
- `HistoricalAwardPanel.tsx:70-82`: the first moment toast posts `momentsGameId`, which marks **all** of the
  game's moments seen. With three badges ahead in the queue, a fan who leaves after the first moment never
  gets the second as a toast. They stay listed in the panel, so nothing is lost for good. Accept, or post on
  the last moment shown.
- When the profile resolves after the award, the level toast is spliced in ahead of the moments
  (`:33-40`), so a moment already on screen is bumped back into the queue and shown again later with a fresh
  timer. Put the level toast last.

### CR-06 (NIT, VERIFIED) - three moments for one pick

A round-1 reach who flops and stops early gives `[REACH_MISS, GONE_EARLY, BIG_BUST]` for the one pick (ran
it: pick 5, board 30, finished 150th, last week 2). The first time, that is also three badges; with CR-01,
five or six toasts naming one player. Each is true. Either accept, or show one toast per pick that lists the
names. On the synthetic seasons the 12 auto-drafted basketball games raised 9 BIG_BUST and 1 LATE_GEM moments
(9 games had at least one moment) and the 12 football games 16 BIG_BUST and 2 LATE_GEM (11 games had at
least one); those are counts on made-up data, not rates.

### CR-07 (NIT, VERIFIED the first, REASONED the second) - copy that the numbers can outgrow

- `Badge.java:48-49` says "round 8 or later" and `:37-38` "36 in a 12-team league", while the rules compute
  `rounds / 2` and `3 * teams` (`AchievementRules.java:170, 200`). With 10 teams and 10 rounds a round-6 pick
  earns LATE_GEM under a badge that says round 8. Both sports are 12 teams and 14 or 15 rounds in
  `application.yml` today, so this is latent. Fix when a league size changes, or build the sentence from the
  game's numbers in `detail` (the moment text already does).
- `AchievementRules.java:122, 126`: TOP_SEED and PERFECT_SEASON say "the regular season" for an old-format
  result, whose standings cover the whole season. A13 fixed the XP line label only. Use the same
  `newFormat ? ... : ...` switch.

### CR-08 (NIT, REASONED) - an unsettleable game is noisy for ever

`HistoricalAchievementService.java:92-99`: a game that throws stays unsettled (A3, accepted) and is selected
again on every read. `unsettled()` loads its whole `result::text` each time, and the catch logs at ERROR with
the stack trace. The results page calls `/award` and `/profile` together, so one bad game is two stack traces
per page view, and the landing page one more. Fix: log the stack once per process per game id (a small
`ConcurrentHashMap.newKeySet()`), then one WARN line without it. A failure table is more than this needs.

### CR-09 (NIT, REASONED) - the season is scored twice at completion

`HistoricalGameService.java:386-388`: `capturePickFacts` calls `replay.scorePlayerWeeks(scoring, played)` and
rebuilds the value map and ranks that `SeasonReplay.replay` / `Comparisons` computed a moment earlier, inside
the same transaction. It is a second copy of one rule (they agree in all 24 games I ran, and the result is
what settles), and it roughly doubles the scoring pass. The existing "replayed season in N ms" log covers
both, so the verification pass can read the number; I did not measure it. Cleaner: have `replay` return the
`PlayerWeeks` or the rank map alongside the result.

### CR-10 (NIT, VERIFIED gap) - what the committed tests never execute

- `HistoricalAchievementMvcIT` finishes games only on the 3-week synthetic season: no playoffs, no title, no
  PLAYOFFS / PLAYOFF_WIN / CHAMPION, no moment, `seasonWeeks < 10`. Nothing runs a football game through
  settle. My `Cr64WireIT` does both on the long seasons and passes; it can be adopted nearly as is.
- `aGameThatCannotSettle...` fails in Java before any SQL, so it does not prove the savepoint clears an
  aborted Postgres transaction. `Cr64SavepointIT` does (spy, real `select 1/0` after the award insert).
- No test covers CR-01 (badge plus matching moment), CR-03 (round 1) or CR-04 (the date).
- The requested Gradle filter misses `NflGameFlowIT`; use `--tests "*Nfl*"` as well when checking this branch.

## For the parent session

0 BLOCKER, 4 SHOULD (CR-01 to CR-04), 6 NIT. CR-03 needs Allan: it is a rule choice, and the code matches
amendment A5 as written. Nothing found in the settle transaction, the SQL, the binds, the wire types, access
control or CORS.

Lesson candidates for `claude/lessons.md`:

1. **A live region has to be in the DOM before its content** (CR-02). Second time in two specs (063 CR-08);
   a test that only asserts `role` and `aria-live` passes either way.
2. **`:test UP-TO-DATE` reports someone else's run.** Gradle skipped the task and the XML in
   `build/test-results` still showed 139 passed, with timestamps from an earlier run. Read the task outcome and
   the XML timestamps, not only the skipped count, or force the run.
