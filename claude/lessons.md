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
