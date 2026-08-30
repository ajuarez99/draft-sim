# claude/

Notes for Claude sessions working on this repo. Not documentation for humans —
`README.md`, `HANDOFF.md` and `DEPLOY.md` cover that.

    claude/README.md        this file: orientation and working conventions
    claude/environment.md   sandbox constraints and verification recipes
    claude/lessons.md       bugs found, their class, and what catches them
    claude/design/          chosen visual direction for the board re-skin, not yet built

## Read in this order, every session

1. **`HANDOFF.md`** (repo root) — current state, what is and is not verified, next
   steps. This is the source of truth for *what to do*. Everything in `claude/` is
   about *how to work here*.
2. **`claude/environment.md`** — before attempting to build, test or run anything.
   Several obvious approaches do not work in this sandbox and the workarounds are
   not guessable.
3. **`claude/lessons.md`** — before trusting a test suite that passes.

Then `git log --oneline` and `git status`. The repo changes between sessions.

## What this project is, in one paragraph

A fantasy football draft simulator that models the *actual managers* in Allan's
Sleeper league rather than filling seats with ADP bots. Spring Boot / Java 21 /
Postgres backend, React+TS frontend, Monte Carlo over a softmax pick model. The
headline output is the availability curve: for each player, the probability he
survives to each of Allan's upcoming picks. The design of record is
`draft-simulator-plan.md` in the Claude project — but see "Corrections to the plan"
in `HANDOFF.md`, because three things in it are now wrong.

## The thing to understand before changing anything

**This project's central problem is thin data, and its central value is honesty
about that.** One or two drafts of history per manager. No true ADP feed. No
backtesting, by an explicit decision that should not be relitigated.

That shapes how code gets written here:

- An arbitrary modelling choice goes in `config/weights.yml` and is *labelled*
  arbitrary. It does not get presented as principled.
- Anything that makes output look more confident than it is gets flagged in the
  payload, not buried. `SimulationResult.Confidence` carries caveats and provenance
  all the way to the UI on purpose. Do not quietly drop them.
- "Verified" and "assumed" are kept apart in writing. If something has not been
  executed, say so.

If a change would make the tool look more certain without making it more correct,
that is a reason not to make it.

## Working conventions with Allan

- Backend engineer — Java, Go, C#/.NET. Skip framework tutorials and boilerplate
  walkthroughs. Assume competence.
- Frontend is TypeScript by preference but not his deepest area; a bit more
  explanation is welcome there specifically.
- He pushes back on weak claims, and he is usually right to. In this session
  "can you verify that yourself?" found a real bug that would otherwise have
  shipped. Treat pushback as a prompt to go check, not to re-explain.
- Prefer testing a claim over asserting it, and label which one you did.
- He asks for things to be recorded for future sessions. Do it as you go rather
  than at the end.

## Where to look for bugs next

`HANDOFF.md` has the ordered plan. This is the different question — where the code
is most likely to be *wrong*, ranked by how little scrutiny it has had:

1. **Anything Spring does.** Context startup, bean wiring, Flyway, config binding,
   Jackson, pgjdbc. Never executed, not once. First `bootRun` is the real test.
2. **`ManualTendencies` deserialization.** A record with a compact constructor and
   three nullable boxed fields, populated from JSON. Constructor binding plus
   `-parameters` should handle it; nobody has watched it happen.
3. **`ProfileService.fit()` manager union.** It now builds a profile for every
   manager known to the system, not just those with picks. Check that a manager who
   appears in `manager` but in no draft does not break slot mapping in
   `SimulationService`.
4. **`MonteCarloRunner` aggregation.** Availability rows only cover the top 75
   available at each of Allan's picks. Correct by design, easy to misread as a bug,
   and worth an assertion that survival probability decreases monotonically across
   his picks — that invariant is not currently tested.
5. **`available.remove(choice)`** in `DraftSimulator` — linear scan, 210 removals
   per iteration. Fine at 2k iterations, possibly minutes at 10k.
