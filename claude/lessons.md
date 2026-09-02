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
