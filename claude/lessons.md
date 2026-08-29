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
