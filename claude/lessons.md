# Bugs found, and what would have caught them

Kept because each one is a *class* of mistake, not a one-off, and the same classes
will recur in this codebase.

## 1. `valueDelta` had its sign inverted

`(boardPosition - pickNo)` instead of `(pickNo - boardPosition)`. The engine
preferred the **worst** available player at every pick.

**Every structural test passed.** All 210 picks made, no player drafted twice,
kickers correctly gated to round 13, softmax distribution correct to three decimals,
completed picks replayed exactly. None of them asserted that *good players go first*.

Caught by running a draft at temperature 0 and looking at the 1.01 pick, which came
back as the last man on the board.

**The class:** invariant tests verify structure, not direction. A model can be
perfectly well-formed and pointed backwards. Any scoring change needs at least one
assertion about *preference ordering* — `theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop`
and `aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot` exist for this. Keep them.

Note also that the design doc has the same inversion in its definition of
`reachBias`. The doc is wrong; the code is right. Do not "fix" the code to match.

## 2. `UPDATE ... FROM d JOIN s ON s.x = dp.x`

Postgres rejects referencing the UPDATE target from inside a JOIN's `ON` clause:
*"invalid reference to FROM-clause entry for table dp"*. Every board rebuild would
have failed at runtime. Fixed by comma-joining the FROM relations and moving all
predicates into `WHERE`.

**The class:** SQL that reads correctly and the database refuses. Not findable by
inspection, trivially findable by execution. Postgres is installed in the sandbox —
see `environment.md`. Run the SQL.

## 3. Predicted a failure that was not real

Flagged `make_interval(days => ?)` as likely to reject a bind parameter. It does not.
The guess went into a plan document and would have sent Allan looking for a
non-problem.

**The class:** asserting an untested hypothesis with the same confidence as a tested
one. If something is a guess, say "guess" — or spend the five minutes and find out.

## 4. Nearly shipped a coin flip

`LeagueRepository.upsert` bound a bare `String[]` to a `text[]` column, relying on
pgjdbc inferring the SQL type. That could not be tested here (no Maven). Rather than
document it as a risk, it was rewritten to use an explicit `createArrayOf` — nothing
left to infer.

**The class:** when a thing cannot be verified, prefer designing the uncertainty out
over documenting it. A caveat in a runbook is worse than a fix.

(Rewriting it introduced a second bug — `JdbcTemplate.execute` takes the SQL first,
not the callback — caught by reading the signature. Compile what you change.)

## 5. Declared something unverifiable too early

Told Allan the Spring layer could not be tested because Maven Central was blocked.
True as far as it went, but PostgreSQL 16, Node and npm were all sitting there
unused. He pushed back, and checking properly found bug #2 plus a clean frontend
build and 19 SSE-parser assertions.

**The class:** one failed check generalised into a capability claim. Enumerate what
is actually available before saying something cannot be done. `environment.md` now
records what works so this does not need rediscovering.

## Standing rule that came out of all five

Separate **verified** from **assumed** in every summary, and never let the second
borrow the tone of the first. `claude/verification-log.md` in the Claude project
exists to keep that split explicit across sessions.

## 6. The frontend displayed configured seats as unconfigured

`api.ts` types are hand-maintained and had gone stale: they carried no
`provenance`, `note` or `unpredictability` after those were added to the backend.
So `SeatList` branched on `picksScored === 0`, and a seat the user had explicitly
configured rendered as *"No history. Running the league-average model."*

Their input was displayed as an absence of input — the exact honesty failure
`Provenance` had been added to prevent, reintroduced one layer up.

**The class:** extra fields in a JSON response are silently ignored by TypeScript.
A stale hand-maintained type produces no error anywhere — not at build, not at
runtime — it just quietly renders the old behaviour. Any backend contract change
needs the matching edit in `web/src/api.ts` in the same commit.

**How it was found:** building the app against a mock API and screenshotting it in
headless Chromium. Invisible in source review and invisible to a passing
`tsc -b`. The recipe is in `environment.md`; use it after any UI change.

## 7. The predicted board showed one player at seven slots

Round one had Justin Jefferson as the "predicted" pick at seven different slots.
Not an arithmetic error: each cell was the most-voted player at that pick computed
independently, which is the correct *marginal* statistic and carries no
exclusivity constraint. But nobody reads a board that way, and it looks like
broken software rather than like a distribution.

Two different objects were being conflated:

- the **marginal mode per cell** — what was built
- the **single most likely board** — what a reader assumes they are looking at

Fixed in `BoardAssembler`: walk picks in order, assign each pick's highest-voted
player that is not already placed. The reported probability stays the assigned
player's own marginal share — reporting the modal player's share next to a
different player would be the worst of both worlds — and `isModal` marks cells
where the two diverge, which are exactly the least certain cells.

**The class:** a statistic can be correct and still be the wrong thing to show.
Check what the reader will assume a display means, not only whether the numbers
are right.

## 8. A lambda captured a reassigned local

`BoardAssembler` assigned `chosen` in a loop and then referenced it inside a
`stream().filter()`. Not effectively final; does not compile.

Trivial, but notable for *when* it was caught: the device bridge dropped
mid-edit, and reconstructing the file locally to test the algorithm surfaced a
compile error that would otherwise have reached Allan's first build. **Anything
written straight to the device without compiling somewhere is unverified**, no
matter how simple it looks. Copy it into `/home/claude/verify` and run `javac`.

## 9. `bootRun`'s first real call was a 500, on a bind type Java's compiler can't catch

`DraftRepository.upsert` bound `java.sql.Timestamp.from(startTime)` against
`Types.TIMESTAMP_WITH_TIMEZONE` for a `timestamptz` column. Compiles fine —
`setObject(int, Object, int)` takes `Object`. Fails at runtime, every time:
pgjdbc refuses to cast `java.sql.Timestamp` (no offset) to a WITH TIME ZONE
target. Fixed by binding `OffsetDateTime` instead, which pgjdbc accepts for
that SQL type.

**The class:** same as #4 (the `text[]` binding) — a JDBC bind where the Java
type and the declared `java.sql.Types` constant look compatible but aren't, and
nothing before actual execution catches it. Not findable by `javac`, not
findable by the `PREPARE`/`EXECUTE` recipe either unless the prepared value is
an actual timezone-bearing type — only running the real code path that
constructs the bind value surfaces it. This is also the first bug in the
project found by running the *Spring app itself* rather than an isolated piece
of it: the standalone-compile and real-Postgres recipes in `environment.md`
each verify half of this codepath, but `DraftRepository` is a `@Repository`
wired through `JdbcClient`, autoconfigured by Spring — nobody had run that glue
until `bootRun` did.

## 10. A guessed sanity-check band was wrong, and got corrected rather than chased

HANDOFF predicted round-1 modal probabilities in the 20–60% band, as a thing to
check before trusting a first real simulation. The actual run: 3.5%–8.8%,
consistently. Traced to the math, not a bug: `weights.yml`'s `adpScale: 12.0`
makes the score gap between adjacent top-of-board players tiny (a 3-pick ADP
gap is worth 0.25), and at `temperature: 1.0` a softmax over score gaps that
small is close to uniform among the whole top tier.

**The class:** distinct from #3 ("predicted a failure that was not real") in
the opposite direction — here the guess *was* wrong, confirmed by running it,
and the fix is not obvious (is T=1.0 miscalibrated, or is the board's top tier
genuinely this flat this year?). The move per project convention is not to
retune `weights.yml` to hit the number a previous session guessed — that would
be designing to match a guess, the exact failure mode this file exists to
avoid — but to record that the guess was checked and failed, name the two live
hypotheses, and leave the parameter change to whoever decides which hypothesis
is right. See HANDOFF's "First real simulation" section.

**Follow-up, same evening:** after building the FFC ADP source (below) and
re-running the same simulation on the improved board, the numbers didn't move.
That's useful — it isolates the flatness to the scoring math, not the board —
and it's a second instance of the same discipline: a plausible alternative
explanation existed (bad board) and got checked rather than assumed away.

## 11. `RestClient.body(Map.class)` refused a response that was valid JSON

Building the FFC ADP source (`claude/adp-sources.md`): FFC's API serves a
correct JSON body but declares `Content-Type: text/html; charset=utf-8`.
Confirmed with plain `curl -D -` first, so it's genuinely the server, not a
Spring quirk. Spring's content negotiation picks an `HttpMessageConverter` off
the declared content type, and none of the default ones parse JSON out of a
body marked `text/html`, so `.retrieve().body(Map.class)` threw on every call.

**The class:** trusting a header instead of the thing it describes. Same shape
as #4 and #9 — a value that *looks* like it constrains what's safe to do, and
doesn't. Fixed by fetching as `String` and parsing with Jackson directly,
sidestepping content-type negotiation entirely. Worth remembering for any
future external API this project pulls from directly: check the actual
`Content-Type` header with `curl -D -` before assuming `RestClient`'s defaults
will parse it, especially for anything that isn't a well-behaved documented
API (FFC's is neither documented nor obviously build-tested against Java
clients).

## 12. `Map.of("team", null)` — the workaround was worse than the null

`LeagueController.board()` serialized a player's team as
`String.valueOf(e.player().team())` instead of the field directly. Not a typo
— `Map.of()` throws `NullPointerException` on a null value, and a free agent
or retired player legitimately has a null team (Todd Gurley, still sitting in
Sleeper's static player dump and apparently still draftable in someone's mock,
surfaced this directly in the real board this session). `String.valueOf(null)`
doesn't throw, but it returns the four-character string `"null"`, which is
valid JSON and indistinguishable from a real team code to anything that isn't
specifically checking for it.

**The class:** the same as #6 (the frontend displaying configured seats as
unconfigured) one layer down — a workaround for a language/library constraint
that quietly changes what a field *means* rather than failing loudly. Fixed by
building the response with a mutable `LinkedHashMap` instead, which tolerates
nulls directly. Worth a general check: any other `Map.of(...)` in an API
response path that might carry a nullable field is worth the same look.

## 13. A team can draft one position 6+ times in a row, and it's real, not display

Allan noticed this by eye in the UI. Reproduced and quantified with a standalone
harness (1000 trials, real `DraftSimulator`, one seat, `T=1.0`): run-length
histogram `{1:6, 2:277, 3:417, 4:188, 5:80, 6:23, 7:5, 8:3, 9:1}` — about 3% of
trials hit a run of 6 or worse, worst observed was 9 straight WRs.

**First hypothesis, checked and ruled out:** that this was a `BoardAssembler`
display artifact like #7 (the marginal-mode-per-cell issue) rather than real
per-trial behavior. It is not — a single `T=0` deterministic trajectory alternates
positions normally (max run of 2), but real `T=1.0` per-trial roster construction
does produce long runs in the tail. Confirmed by instrumenting `DraftSimulator`'s
own scoring loop and replaying the worst seed with a printed candidate/score
breakdown at every pick.

**Root cause, from the actual numbers, not a guess:** `rosterNeed` *is* discounting
correctly — a 5th+ WR scores `need=0.150` (`benchFloor`) exactly as designed,
correctly below `need=1.000` for an empty RB/QB/TE slot. The problem is what it's
discounted *against*: at `candidatePool=30`, WR is often 12-13 of the 30 candidates
scored. Even with each individual WR pick unlikely under the softmax, the
*aggregate* probability mass of "some WR wins" stays non-trivial every single
pick, because there are so many of them competing. Over a long draft (up to 9
of a manager's own picks watched here), a run of unlucky-but-not-impossible draws
compounds. Seed 303's pick 67, for example: top candidate was a TE at score 0.910,
the best WR candidate scored 0.175 (`need=0.150` already applied) — individually a
longshot, but one of ~12 similarly-longshot WRs, and it won anyway.

**Not yet fixed — this is a modeling decision, not a bug fix, per the project's own
rule against silently retuning `weights.yml`.** Two candidate directions, neither
implemented:

- Lower `benchFloor` globally (blunt, touches every position's bench value, not
  just the pathological case).
- A stacking/diminishing term scoped to *count already rostered at this
  position*, independent of starting-slot math — so a team's 6th WR is worth
  measurably less than its 5th even though both are technically "bench," which
  `rosterNeed` alone can't currently express since it only asks "does this fill a
  starting slot," not "how many have I already taken."

See `claude/live-reveal-and-tendencies-ui.md`'s "Related" section for where this
is tracked next.

## 14. Manager tendencies could never have worked from a browser, and nothing caught it until a browser tried

`WebConfig.addCorsMappings` allowed `GET, POST, OPTIONS` only. `PUT
/api/managers/{id}/tendencies` and `DELETE /api/managers/{id}/tendencies` —
the only PUT/DELETE calls in the whole app — have existed since `76d661d`
(the tendencies feature itself). Every save and clear from an actual browser
origin was silently rejected by Spring's own CORS preflight before ever
reaching `ManagerController`: `403 Forbidden`, `"Invalid CORS request"`. The
endpoints worked flawlessly by every method this project used to verify them —
curl in this session, Postman per HANDOFF's own instructions — because neither
sends a CORS preflight. **This bug was invisible to every verification method
in this file except the one that actually drove it from a browser.**

Found by a verification-pipeline agent that ran a real simulation, opened the
real UI, and clicked "save" on a seat card, in a session where a coding agent
had just built the first-ever frontend consumer of these endpoints. The bug
predates that session's frontend work entirely — it was sitting there,
unreachable by design of how it had only ever been tested, since the tendencies
feature was born with no UI in front of it.

**The class:** an API that has genuinely never been called the way its real
client will call it isn't verified by that client's absence — it's untested in
exactly the dimension that matters. `claude/lessons.md` #5 already warned about
generalizing one failed check into a capability claim; this is the same lesson
about the *positive* case — passing every check available so far is not the
same as being correct, when none of the checks available so far exercised the
actual path. The general version: **"has no UI yet" is not the same claim as
"works," and shouldn't be allowed to quietly become one** just because nothing
has said otherwise. Fixed by adding `PUT, DELETE` to `allowedMethods`.

## 15. Three draft-night bugs that only exist in a state nothing had ever reached

Found by a review pass on 2026-09-02, hours before the first truly
`drafting`-status Sleeper draft this project has ever polled. None of the three
is reachable by any test, any curl, or any code path that had ever executed:

- **`Thread.sleep` inside the `try`.** `LiveDraftPoller.loop` slept as the last
  statement of the try block, so an exception out of `pollOnce` jumped straight
  past it. The failure isn't the missed sleep, it's what the missed sleep
  causes: an unthrottled retry loop trips Sleeper's rate limit, which is itself
  an exception, which sustains the loop. One transient 500 becomes a
  self-inflicted outage. It reads as correct in review because the sleep is
  visibly right there.
- **A "stop" condition that skipped the work it was stopping after.** The same
  poller returned on `status == "complete"` *before* fetching picks, so the last
  few picks of every draft — the ones made between the final `drafting` tick and
  the draft closing — were silently never ingested.
- **State captured once and held for the thread's whole life.** The poller's
  slot->manager map came off an immutable `DraftRow` read at `/track` time.
  Sleeper populates `draft_order` only when the commissioner sets the order, so
  the map persisted for a `pre_draft` league was *empty* and would have stayed
  empty all night. Every autopick (Sleeper leaves `picked_by` blank on those)
  would have landed unattributed, and all 14 seats would have simulated as
  league-average bots.

**The class:** correct-looking code whose bug exists only in a state the process
has never been in — a transient failure, a terminal transition, or a field that
starts null and fills in later. The useful test question is not "does this work"
but **"what does this do the first time it is wrong?"** Related to #14 from the
other side: a code path that has only ever run against `complete` drafts is not
verified for `drafting` ones, and the absence of a failure report is not
evidence.

**Corollary found while fixing them:** the obvious change-detector
(`derived.equals(stored)`) is a trap here, because the stored map comes back
from Jackson with `Integer` values while the derived one holds `Long`s.
`Integer.valueOf(5).equals(5L)` is `false`, so the "only write when it changed"
guard would have written every 10 seconds forever — the opposite of what it was
added for. Normalize before comparing, or skip the comparison: an UPDATE by
primary key is cheaper than the bug.

**And one from the same batch that isn't about liveness at all:**
`DraftSimulator.run` called `available.remove(e)` and ignored the return value.
A duplicate id in `startState` therefore made the removal a no-op while the
roster add on the next line still ran — the same player on a roster twice,
double-counted in `rosterNeed`. Same shape as #1: structurally valid, silently
wrong, and it surfaced only as an opaque downstream error message. **A
collection mutator that returns a boolean is telling you something; dropping it
is a decision, and it should be an explicit one.**

---

## 16. A second implementation of the pick order defaulted to plain snake — for the third time

Found while building NBA mock drafts (`claude/nba-mock-drafts.md`), by opening
the room in a browser. The board grid rendered the user's own highlighted picks
**in Bot 12's column** from round 3 on.

Nothing was wrong with either piece. `DraftBoard` takes a `reversalRound` prop
and computes cell pick numbers with it — that was itself the fix for the same
bug one round earlier (HANDOFF's "Three bugs the live pass found", where
`DraftBoard` had drawn plain snake from a literal `round % 2 === 1`).
`MockDraftView` simply never passed it, so it took the prop's default of `0`
while `myPicks`, computed on the backend, was reversal-aware. Two correct
implementations, one silent default between them.

**The class: an optional parameter that encodes a rule.** `reversalRound = 0`
reads like "no opinion" and means "plain snake" — a real, specific claim about
pick order. Every caller that omits it is asserting that claim without knowing
it. The same shape produced #6 (a football-shaped default nothing failed on) and
the `?sport=` default that wrote basketball notes onto football managers.

This is the third time the pick order has been re-implemented and defaulted
wrong, which says the problem is not carelessness but the shape:

- `DraftSlot.slot(pickNo, teams)` and `picksForSlot(slot, teams, rounds)` exist
  as plain-snake convenience overloads, and they are what a caller reaches for.
- **Before adding one, ask what the no-argument version silently asserts.** A
  convenience overload that guesses a *rule* is different from one that guesses
  a *value*.

**What caught it:** a browser, in about four seconds of looking. What did not:
379 backend tests and 211 frontend tests, including tests written that same hour
specifically about the reversal round — because they asserted the numbers
(`myPicks == [1, 24, 36, 37]`), and the numbers were right. The bug was in where
those numbers were *drawn*. **An assertion about data does not cover a layout
that reads the same data through a second path.**

**Found in the same pass, same lesson from the other end:** `buildState` loaded
the board with a hardcoded `Sport.NFL` on the one branch no test exercised —
the plain `GET`, which is every page load of the room. Create and pick both
passed a real `DraftContext` and behaved perfectly. A basketball draft room
offered Jahmyr Gibbs. **When you delete a guard, grep for what the guard was
making redundant**; three of the four hardcoded sports were found by reading,
and the fourth only by driving the thing.

---

## 17. A field the frontend shipped and the backend didn't took the whole site to a white page

2026-09-14, in production. The home screen of ballknowers.co rendered nothing at
all:

```
Uncaught TypeError: Cannot read properties of undefined (reading 'toUpperCase')
  at Array.map (<anonymous>)
```

`MockSessionSummary` gained a `sport` field, and the mock-drafts list rendered
`{m.sport.toUpperCase()}` on every row. Correct against the backend it was
written with. The frontend and backend are **separate Railway services that
deploy independently**, the frontend auto-deployed and the backend did not, and
for the whole window in between the list mapped over rows with no `sport` on
them. One absent string, one dead site — not a broken badge, the *entire page*,
because an exception in render unmounts the tree.

**The class: a wire contract that changes on both sides of an independent
deploy boundary.** The failure is not in either version. It is in the state
between them, which every rollout passes through and which nothing in the
repo's tests can reach, because tests only ever see both halves at the same
commit.

Worse, the risk *was* flagged before the push — as "deploy both services, or an
NBA mock's board will draw plain snake." That is the harmless direction, and
naming it created a false sense of having thought it through. The fatal
direction is the other one:

| rollout state | symptom |
|---|---|
| old frontend + new backend | extra JSON fields ignored; app fine |
| **new frontend + old backend** | **unguarded read of an absent field; white page** |

**The rule:** when a frontend starts reading a field a backend release adds,
that read must tolerate the field's absence until the backend release is
everywhere. Not forever, and not as a shrug about API contracts — for the
length of one rollout. Do it at the boundary the data enters (one
normalizer in `api.ts`), not at each of the N call sites that read it.

**Deploy order follows from the same asymmetry:** backend first, then frontend.
Additive backend fields are invisible to an old frontend; new frontend reads are
fatal against an old backend. If both are one push, the tolerance is what buys
the gap.

**What caught it:** Allan opening the site. Not 379 backend tests, not 211
frontend tests, not a typecheck — TypeScript believed the field was there,
because in the repo it *is*. `src/api.mockSport.test.ts` now pins the tolerance
using a response body copied verbatim from the old backend while it was still
serving.

## 18. A JDBC stream that nobody closed held a pool connection per request

**2026-09-23, spec 008.** `PlayerGameRepository.playersWithGames` returned
`.query(String.class).stream().collect(toSet())`. Spring's `JdbcClient` `stream()` holds its
connection until the `Stream` is closed, and `collect` doesn't close it. Every superlatives
request leaked one Hikari connection, and after 10 page loads the endpoint hung while
`/api/health` (no DB) still answered.

The method had been on `main` since spec 005 with **no callers**, so it was dead code until 008
called it. 650 green tests never saw the problem, because each makes one call. What found it was
reloading the real page a dozen times; `pg_stat_activity` then showed all 10 connections idle
after that exact query.

**The rule:** from `JdbcClient`, use `.list()` or `.set()`; use `.stream()` only inside
try-with-resources. The other `.stream()` sites in `store/` were all `.list().stream()`, which is
safe. A pool-sized repeat IT (`PlayerGameRepositoryConnectionLeakIT`: pool 3, 25 calls) fails on
the leaking form.

## 19. A lenient measurement script can hide the exact shape the code gets wrong

**2026-09-23, spec 008.** Research R9 measured Sleeper's per-week stats and wrote football down as
"one entry per week". The script that took the measurement did `e = v if isinstance(v, dict)
else v[0]`: it silently accepted both a bare object and a list. So the *difference*, that football
weeks are bare objects while basketball weeks are lists, never reached the research text. The
ingest, written from the text, cast every football week to a list. The first live run threw on all
315 NFL players, stored nothing, and reported 5,670 "unclassified" weeks.

**The rule:** when research records a data shape, write down the literal type the measurement saw,
not the meaning. A script that normalises its input is measuring the normaliser. The same feature
had a sibling: research R10's "regular contributor" denominator (*weeks he played*) became *weeks
rostered* in code, which silently dropped the award's namesake case. Research text and code
drifted apart and no test compared them. The review caught it by reading one against the other.

## 20. Fail-open on a missing identity is worse than no scoping at all

**2026-09-28 audit, fixed 2026-09-29.** Every scoping check in the app read `if the header is
blank, allow`: `LeagueMembership.canSee`, `canSeeManager`, `MockDraftService.mayUse`,
`OwnerSlot.mayActAsSlot`, and the draft and mock lists. Each was written with a reasonable
sentence ("the pre-identity contract; nothing that never signs in breaks"), and each was true on
the day it was written, because the header did not exist yet. Once the header did, the rule
inverted the incentive: **leaving the header off got more than signing in as a stranger.**
Measured on production, GET only: `/api/leagues/<id>/analysis` was 200 with no header and 404
with `X-Sleeper-User: 999999999999`; `/api/drafts` returned every league's drafts with no header
and `[]` with a stranger's. Locally the same rule let a header-less caller read and pick into
anyone's mock and write any seat's manual pick.

A missing identity is not a weaker identity, it is the *absence of a claim*, and the only safe
answer to no claim is nothing. A scoping scheme that fails open rewards opting out, so it is worse
than none: with no scoping nobody believes the data is private, and with fail-open scoping
everyone does while the one request shape an attacker needs (the empty one) is the most
permissive of all. The signed-in path had been tested thoroughly for years; nobody had a reason to
ask what the *unsigned* path returned, because every browser sent the header.

**What caught it:** the audit sending a request with no header and one with a wrong header and
diffing the answers. 800 green tests had never made that comparison, because each test named a
caller. Fixing it found the same class one layer over: five league routes
(`roster-management`, `transactions`, `expected-wins`, `forecast`, `weekly-report`) had **no
scoping at all**, header or not, and `/power/compute` labelled "commissioner" checked membership
only. The new `AccessControlMvcIT` loops every league route through no-header, blank-header and
stranger, so adding an unscoped route is a failing test.

**The rules:**
- **No identity means no access.** The operator's way around it is a separate, explicit,
  server-side secret (`ADMIN_TOKEN`), never "the caller left something out".
- **Test the absent input next to the wrong one.** For any gate, assert `null`, `""`, `"  "` and
  a stranger produce the same refusal.
- **A secret's blank value must fail closed.** `API_TOKEN` blank means *off* (the local-dev
  default) and `ADMIN_TOKEN` blank means *disabled*; the opposite polarity is deliberate and each
  has a test, because a blank secret that matches a blank header is the same bug wearing a
  different hat.

## 21. Ten cached Spring contexts starved Postgres, and the ITs SKIPPED instead of failing

**2026-09-29.** Adding two `@SpringBootTest` configurations (a MockMvc IT with mocked services and
one with `draftsim.admin.token=` overridden) took 74 integration tests from *run* to *skipped* and
left `BUILD` line reading fine. Each distinct configuration caches its own Spring context, each
context opens a Hikari pool of `DB_POOL_SIZE` (10) that stays open for the whole test JVM, and
Postgres's default `max_connections` is 100, so around ten cached contexts leave nothing for the
next IT's `@BeforeAll` `DriverManager.getConnection`. That assumption fails, and the class is
skipped with no message. Same shape as the "backend suite skips ITs silently" note, with a new
cause: it was not "Postgres is down", it was "Postgres is full".

**The rule:** after adding a `@SpringBootTest` variant, read the skipped count, not just the
build line. The test JVM now sets `spring.datasource.hikari.maximum-pool-size=3`
(`build.gradle.kts`), which leaves room for many more contexts.

## 22. An IT run applied a destructive migration to the shared dev database

**2026-09-29.** V25 (`manager_note_private`) deletes every existing manager note, because old
notes have no recorded author. It was meant to run on the next deploy. Instead, it ran the moment
the implementing agent ran its integration tests. The ITs boot Spring with Flyway against
`localhost:5433/draftsim`, the same database the dev servers and every other session on this
machine use.

The two local notes were gone before anyone had reviewed the migration. Nothing else broke. Flyway
ignores applied migrations newer than a branch's own (`*:future`), so a pre-V25 branch still booted
and passed `LeagueMembershipIT` with 0 skipped.

**The rule:**
- A migration that deletes or rewrites data is live on the shared dev DB as soon as its tests
  run, not when it merges.
- Before running the suite on such a branch, snapshot what it will touch:
  `pg_dump -t <table>`, or copy the rows you care about.
- Or point the ITs at a scratch database.

Review a destructive migration before its tests run, not after.

## 23. CPU-bound work on virtual threads starved every other request

**2026-09-29.** Tomcat runs request handlers on virtual threads (`spring.threads.virtual.enabled`),
and `MonteCarloRunner` started one virtual thread per iteration. That meant 5,000 CPU-bound tasks
that never yield, sharing the same few carrier threads as every request handler. While a sim ran,
unrelated requests queued behind it.

Measured locally on 12 cores, loading Expected wins:
- idle: 0.037 s;
- during a 5,000-iteration sim: 0.61 s;
- during a 20,000-iteration sim (the old cap, still what production accepts): 3.7 s.

It surfaced as a different bug. A "replace my run" request couldn't get a carrier back after its
own JDBC calls, so it reached the cancel only after the old run had finished. The lease logic was
correct, and every unit test passed, because none of them ran the runner and a request handler on
the same scheduler.

After moving iterations to a fixed platform-thread pool, one thread per core, the same page load
during a 5,000-iteration sim took 0.23 s, and replacement cancels the old run live (409 at 0.86 s).

**The rule:**
- Virtual threads are for blocking I/O, not CPU-bound fan-out.
- Heavy compute goes on a bounded platform pool.
- A test of cancellation or fairness has to put the heavy work and a request handler on the same
  scheduler, or it can't see this.

## 24. A lookup that silently drops its misses turns bad input into a plausible wrong answer

**2026-09-30, spec 012 (T023).**

**What happened.**
- Measuring the per-pick re-projection, the first runs sent a `startState` of 24 real picks exported with `psql` on Windows.
- Every sleeper id carried a trailing `\r`.
- `SimulationService.resolveStartState` looks each id up in `idsBySleeperId` and **skips any that miss**, so all 24 were dropped with no error and no log line.
- The engine then simulated the whole draft as if nothing had been picked.
- The response was a normal, well-formed 200: 500 iterations, 180 cells, sensible-looking timings.

**How it was caught.** Only by reading a number that should have been certain: pick 1, a locked
real pick, read **0.094** instead of **1.0**. The timings were off by that mistake too, since an
unlocked run simulates more picks.

**Why it matters beyond the script.** The app's own live tab builds `startState` from
`RealPick.player.sleeperId`, so it is not exposed today. Any future caller that passes ids from
anywhere else gets the same silent unlock. The DB-replay fallback has the same shape: it skips a
`draft_pick` row whose `player_id` is null.

**The rule:**
- When input maps through a lookup, a miss is either an error or a counted, logged event. It is never a silent `continue`, least of all when the missing entries change what the output means (locked vs. simulated).
- Before trusting a measurement, check one value whose answer you already know. Here, a locked pick must read 1.0.

## 25. Build agents re-derive the rule they were told already exists (spec 013, four times in one build)

Spec 013 was built by Sonnet coding agents, each given the tasks plus AGENTS.md's
"two implementations of one rule" warning. Four separate agents still wrote a
second copy of something:
- the 4-week "early" threshold, as a frontend constant on the Luck page, and again
  in `GradeChip` for a sentence;
- the Power page's hero-week rule, re-derived for League home, and already choosing
  the season differently (MEMBER entries vs all entries);
- the 860px phone breakpoint, a third JS copy beside AppShell's.

Each one passed its own tests. They were caught only because the parent session read every
diff with this one question in mind.

**The class:** an agent asked to *show* a number reaches for the nearest literal.
The fix is mechanical every time: the server sends the value (`early`,
`earlyThresholdWeeks`), or one exported function/hook is shared (`leagueVoteHero`,
`useNarrow`), plus a test that pins the single source (`SeasonWindowSingleSourceTest`,
`useNarrow.test.ts`). **When reviewing agent output, grep its diff for new numeric
literals and new `const X = <number>` declarations before reading anything else.**

## 26. A file written on Windows came back in two encodings at once

A build agent's edit left `AvailabilityPanel.tsx` with three lines saved as
Windows-1252 bytes inside an otherwise UTF-8 file. Type-check, all 885 tests and the
production build passed. The page showed "ADP 1�60" instead of "ADP 1–60", and a
broken tooltip separator. A whole-file `iconv -f cp1252` would have double-encoded the
lines that were already correct (the ▾/▴ arrows became "â–¾"); the repair had to be
per-line (decode UTF-8, fall back to cp1252 only for lines that fail).

**The class:** nothing in the toolchain checks source encoding. `web/src/sourceEncoding.test.ts`
now does (strict `TextDecoder('utf-8', { fatal: true })` over `src/**/*.{ts,tsx,css}`),
proven to fail on an injected 0x96 byte.

## 27. A guard test that reads `styles.css?raw` under Vitest passes against nothing

Vitest stubs CSS, so `import css from './styles.css?raw'` yields `""` in tests. The
first breakpoint-guard test asserted on that empty string. It *failed* (luckily the
assertion was `toContain`); written as `not.toContain` it would have passed forever.
Read the file from disk (`node:fs`, cwd = `web/`), and assert the content is non-trivial
(`css.length > 1000`) before asserting anything about it.

## 28. Rank-based display can manufacture certainty from nothing

League home said "You're 1st of 12 at 0-0" for an NBA league whose season hadn't
started: an `isMe` row exists, so its index was shown as a position. Same build:
an award block showed 2025's winner on the 2026 home with no year, because the
endpoint falls back a season. Unit tests passed for both. Both were found by reading the
rendered page for a league in an unusual state (pre-season, fallback season).
**Live checks need at least one league in each odd state**, not just the populated one.

## 29. "It falls out of the same rule for the other sport" is a claim, not a proof

Spec 014's plan inferred a football bye from "no game row, and his team is nobody's opponent this week",
and research R3 said basketball "has no byes, and that falls out of the same rule without a sport check."
Both halves were wrong. A basketball NIGHT is one date, and on most NBA nights only 12–20 of 30 teams play,
so every off-night would have read "Bye". In an unfinished football week, a team that simply hasn't
kicked off yet is also nobody's opponent, so week 1 would have said "Bye in Week 1". The unit tests passed,
because they encoded the same assumption. A cold bug-hunting review caught it with one SQL query
(distinct opponents per `game_date`).
**When a rule is called sport-agnostic, count the cases that make it true in each sport, against real
data, before writing it down.** And never infer an absence ("bye", "no game") from a period that isn't settled.
*Second instance, 2026-10-02:* spec 009's week-finality rule read `last_scored_leg` as "the week being
played", which was reasoned from NBA. In NFL it's the last completed week, so every NFL week was marked
final a full week late. One `curl` of the live league settings while a week was in progress showed it.

## 30. A "flaky" test that was a false claim passing on storage rounding

`RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete` was
written off as intermittent in four specs in a row (010–014): "fails on base too, passes on
re-run". It was never flaky. It asserted that the 1998 page's `lastSuccessAt` moved after a
1999 refresh, but `chainBySleeperId` walks backwards, so 1998's chain is [1998] and 1999's success
can't reach it. It passed only when Postgres rounded `oldSuccess`'s sub-microsecond digits
(Windows `Instant.now()` has 100 ns ticks) **up** on storage, which made the stored "old" success
a few hundred ns later than the in-memory one. Truncating `oldSuccess` to `MICROS` made it fail 4 of 4.
Fixed 2026-10-02 by testing the page the fix was about: the newest season's id, with the resolver
showing an older played season. Mutation-checked: reverting either guarded behaviour fails it.
**A test that fails "sometimes" with no concurrency in the asserted value is a deterministic bug
plus a coin flip. Find the coin before re-running.** And compare timestamps that went through the
DB at the column's precision (`truncatedTo(MICROS)` for `timestamptz`), or `isAfter` can pass on rounding.

## 31. A guard test's javadoc is not its rule set

Spec 016 rewrote two user-facing strings and planned them against
`NoIngestHintsInMessagesTest`. The plan read the class javadoc, "fails if a string literal
contains `/api/ingest` or `POST /api/`", and wrote wording that avoided both. Both strings
still failed. `hasHint` has a second rule, added during live verification on 2026-09-28 and
documented only in an inline comment: any sentence-like literal (one with a space) that
uses the word "ingest" at all fails as developer jargon. The javadoc was never updated.
The fix was the text ("only has football projections"), not the scanner.
**When a guard test constrains what you're about to write, read its assertion code, not
its summary.** A guard that grows by accretion documents its first rule and enforces all
of them.

Same feature, same class of miss, one layer out: the plan's live checks assumed
`GET /analysis` and `POST /ingest/projections` were open locally. Since spec 013 / the
honour-system change, one needs a member's `X-Sleeper-User` (else 404, by design) and the
other `X-Admin-Token` (else 403, fail-closed when blank). A curl in a quickstart should
name the identity it runs as.

## 32. A baseline built from a draft's own picks is biased at its edges, and moving the edge doesn't fix it

Spec 018 judged each pick against "the picks around it". Three versions, each measured on
NBA and NFL 2025 after it looked right on paper:

1. **All positions, ±half a round.** It passed the pick-10-beats-picks-1–9 ordering test.
   On real data NFL 2025's top 5 steals were all late QBs (QB mean +94.9). It compared a QB
   with the WRs taken beside him. Caught by the adversarial plan review.
2. **Same-position neighbours, n each side, shifted inward at the ends.** Built and green, 20
   unit tests. On real data Jokić (#1) and Dončić (#2) were top-4 *steals*, and picks 1–6
   averaged +106.7. The first player at a position has only later, worse players to be
   compared with. Same-position windows span more picks, so the bias got bigger than in
   version 1, not smaller.
3. **Rank-matched** (k-th drafted vs. k-th best finisher): the bias flips. Pick 1 can at
   best break even, and the last 12 NBA picks averaged +227.

What shipped: a per-position least-squares fit `production ≈ a + b·ln(pick)`. Its residuals
sum to 0 within each position, so neither end is favoured by construction. Measured
edges: NBA picks 1–6 +14.7, last 12 +56.7; NFL picks 1–12 +0.9.

**For any "value vs. expectation" number, report the mean by draft region (first 6–12
picks, the middle, the last 12) and by position before believing it.** Ordering tests on
synthetic picks can't see an edge bias, because they don't have a sloped, skewed real
distribution. Two of these three rules passed every test written for them.

## 33. A test named for a rule can pass because the fixture happens not to need it

Spec 017's `sc003_2025HasThirtyTeams…AndNoExhibitionRows` asserted no All-Star rows on the real
2025 schedule, and passed. The grid had no exhibition rule at all. It passed because Sleeper
happened to mark 2025's All-Star game (STP/STR) `canceled`, so the postponed/canceled filter
dropped it by accident. The 2024 schedule, backfilled to production on 2026-10-07, marks its
All-Star final (CHK vs SHQ) `complete`, and the grid showed 32 teams.

**When a test's name claims a rule, check the fixture actually exercises that rule rather than
another one that happens to cover it, e.g. by switching the rule off and watching the test fail.**
One real season is one sample of how the source encodes an edge case; a second season can
encode it differently.

## 34. A source that encodes an edge case as ordinary data can only be caught against an outside count

Spec 022's hand check against Basketball Reference was meant to test formulas. It also found
that Jalen Brunson had 75 games where the NBA counts 74. Sleeper stores the NBA Cup final as an
ordinary regular-season game, with two real team codes and status `complete`, so no rule built
from Sleeper's own fields could flag it.

The data was internally consistent: NYK and SAS had 83 games, and every other team had 82. It took
an independent source's count to make 83 look wrong. It had already been feeding Trends (spec 019)
since that shipped. 2024's final (OKC vs MIL) was encoded the same way.

**When a feature counts things such as games, rows or players, compare a sample against an
outside source's count at least once, even if the outside source is only read by hand.** Then put
the result into the data rule explicitly. The fix here was a hand-maintained, labelled exclusion
list, because the source gives nothing to detect it from.

A related pattern from the same spec: a live check in the browser found six display defects that
1,123 passing unit tests had all asserted around. Examples were a percentage without makes and
attempts, and a rank described as "season total" when it was per game. Two of our own design docs
were also wrong, and a builder or reviewer caught each one: a percentile formula that could exceed
100, and a value labelled "per week" that was a season sum. Each correction is dated in place.

## 35. A fix for one component on a 0px container doesn't fix its siblings on the same container

Spec 012 (T042) found that at phone width `.board-stage` measures 0 px tall, so the pick card,
hung off the stage, landed below the fold. It was fixed by pinning the pick card to the viewport.
The **available-players sheet** was positioned against the same stage with `max-height: 46%`.
That resolved to 0 px, its content spilled out, and the board painted over it: every tap on a
chip or a player row hit a board cell instead. It had been like this in production since then.
Spec 023 found it only because its new Stats switch lived in that sheet and the 375 px check
tried to tap it. `elementFromPoint` at the button's centre returned a board cell.

**When a layout root cause is found and fixed for one component, list every other component
positioned against the same container and check each one at the same width.** The fix is one
line per component. The check is `elementFromPoint` at the control's centre, because a
screenshot of an overlapped control can look perfectly fine.

Two more from the same verification, both found by driving the real room and not by the 1,290
tests that passed before it:

- **A memoized-looking table can still re-render on every tick.** The room re-renders once a
  second for its "Live · Ns" clock. A 400-row table with no `memo` and with inline callbacks
  cost 18 long tasks (65–147 ms) per 10 seconds, against 0 for the view beside it. A
  `PerformanceObserver({type: 'longtask'})` sample, taken with the view open and then closed,
  is the check. Make sure the view really did switch before trusting the "closed" sample: the
  first one here was taken with a pick card covering the switch, so the click never landed.
- **A test harness that writes to the shared dev database changes other features' tests.** A
  synthetic `drafting` copy of the 2026 NBA draft made spec 022's IT skip ("the 2026 draft has
  been held"). The skip count went from 0 to 1 with no failure, which is exactly the signal
  that's easy to read past (memory: "Backend suite skips ITs silently"). Delete harness rows when
  the check is done, and re-run whatever skipped.

## 36. A threshold of "> 0" on a score with a floor filters nothing

Spec 024's first plan defined "fits an open slot" for mock auto-pick as
`SportRules.rosterNeed(candidate, lineup) > 0`. `rosterNeed` documents its range as
`[benchFloor, 1]`, and `benchFloor` is 0.15 in `config/weights.yml` for both sports. So `> 0`
was true for every player. The "need over ADP" step would have collapsed to plain ADP, and its
test could only have passed if it were written wrong. The adversarial plan review caught it by
reading the two implementations, before any code existed.

The fix compares against the floor read from config (`> benchFloor`), never a literal. It
also keeps the bots' hard gate (`isDraftable`) in front, so auto-pick can't take a kicker in
round 9. The guarding test was checked by flipping the rule back to `> 0` and seeing it fail
(exactly one test went red), then restoring it.

**Before thresholding a score, read the function's documented range and its early returns.
A floor, a clamp or a default turns "> 0" into "always". Prove the test can fail under the
wrong threshold, not just pass under the right one.**

Two more from the same build, both caught by running the code rather than by reading it:

- **A Java text block drops a line's trailing space.** A SQL fragment ending `... and` with a
  trailing space was concatenated into `andt.sleeper_draft_id`. Unit tests with a mocked
  repository could not see it; the real-Postgres IT failed on its first run (lessons #2's
  class). Put the separator at the start of the next fragment, or concatenate `" and "`
  explicitly, never rely on trailing whitespace in a text block.
- **Delete-then-insert "replace the whole list" needs a lock.** Two concurrent PUTs for the
  same owner and list both deleted, then both inserted, and the second hit the partial unique
  index with `DuplicateKeyException`, which surfaced as a 500.
  `pg_advisory_xact_lock(hashtext(owner || ':' || scope))` at the start of the transaction
  fixed it. The IT was checked by commenting the lock out and watching it fail.

## 37. `open(p, 'w').write(transform(s))` empties the file if the transform throws first

A spec 024 fix agent edited `AvailabilityPanel.tsx` with a Python one-liner in the
`open(p, 'w').write(...)` shape. On Windows, Python's default text encoding is cp1252. The new
content had a "ⓘ" in it, so encoding failed. `open(p, 'w')` had already truncated the file, and
the 0-byte result was left in the working tree. The file was uncommitted, and `git checkout`
would only have restored main's version, dropping three agents' worth of edits from this
feature.

Recovery was possible only because every edit was still in the session transcripts. Main's
version was replayed through the four scripts that had actually succeeded, in order, into a
scratch copy. The scratch copy was checked for the expected markers, then copied over the empty
file. `tsc` came back clean, and the suite gave 1,408/1,408, the last known count.

**Edit source files with the Edit tool, or in Python only through
`io.open(p, encoding='utf-8', newline='')`, building the whole new string before opening the
file for writing. A script that opens for writing before its content is ready can destroy the
file it was meant to change.** On this machine, also set `PYTHONUTF8=1` for any ad hoc Python.


## 38. "First in the list" is not "primary": Sleeper sorts positions alphabetically

The app treated `player.positions[0]` as an NBA player's primary position. Sleeper's
`fantasy_positions` is sorted alphabetically for every player (1478 of 1478 active
multi-position NBA players), and Sleeper's own stated primary (`position`) matched the first
entry for only 52% of them. So Anthony Edwards was a "PG", Kevin Durant a "PF", and SG/SF
almost never appeared first. That surfaced as "SG 0/0" chips, an SG filter that found 0 of 42
SG-eligible players, and positional ranks ("PF4") counted within an alphabetical artefact.

**Before treating an ordered list from an external API as meaningful (first = primary, first
= best), check whether the source sorts it, against the source's own explicit field if it has
one.** One query over the real payload answered it here.

Two more from spec 025:

- **A test can assert the bug.** A `teamNeeds` test expected "Fills SG" for a roster where the
  re-seated lineup actually filled G. The test encoded the same wrong heuristic as the code, so
  it passed until a property test ("the named slot is the one that becomes filled") compared the
  claim with the result. When a label describes the outcome of a computation, test it against
  that computation, not against a hand-written expectation.
- **A layout spec that says "compact" needs a measured reading, not just a measured height.**
  Spec 024 measured rounds and rows to the pixel and never measured the name inside a compact
  cell: it had 15–19 px, about 2–3 characters, for every player. `scrollWidth <= clientWidth`
  per cell is the check. Inside the Browser pane's scaled viewport, compare layout widths, not
  `getBoundingClientRect`, which disagreed by about 10% here.

## 39. A subagent's commit can store `\r\r\n` and turn a 250-line change into a 7,700-line one

**What happened (2026-10-10).** A Sonnet build agent on Windows committed 26 files whose blobs held `\r\r\n` line endings. That's a doubled CR, most likely from writing CRLF text into a checkout with `core.autocrlf=true`. `git diff --stat` showed 3,945+/3,752−. `git diff -w` showed the real 248+/55−. `--ignore-cr-at-eol` did **not** hide it, because the extra CR isn't at the end of the line.

**Check every agent commit before merging:** `git diff main...BRANCH --stat` against `git diff -w` on the same range. If they differ wildly, run `git cat-file -p HEAD:<file> | od -c | head` and look for `\r \r \n`.

**The fix:** strip every `\r` from the affected files (`sed -i 's/\r//g'`), then re-add and amend. autocrlf stores LF.
