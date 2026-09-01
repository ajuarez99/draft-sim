# AGENTS.md — draft-sim

Tool-agnostic entry point for any coding agent working in this repo. Distilled from
`claude/lessons.md` (bug post-mortems), `claude/environment.md` (sandbox/verification
recipes), `HANDOFF.md`, `claude/README.md`, and this session's own discoveries. Those
files have more detail than fits here — this file tells you what to read and when, and
pulls the highest-value, most actionable rules up front so you don't have to.

## Read in this order, every session

1. **`HANDOFF.md`** (repo root) — current state, what is and isn't verified, next
   steps. Source of truth for *what to do*.
2. **This file** — *how* to work here: environment, hard rules, recurring bug
   classes, the convention this repo uses for building anything nontrivial.
3. **`git log --oneline` and `git status`** — this repo has had multiple Claude
   sessions running concurrently against the same working tree. Diff before assuming
   the tree matches what any one doc describes.
4. **`claude/environment.md`** — before concluding something can't be built, tested,
   or run. Several obvious approaches don't work in a cloud sandbox and the
   workarounds aren't guessable; see "Which environment am I in" below.
5. **`claude/lessons.md`** — before trusting a green test suite. Full detail behind
   the condensed list further down.

## What this is, in one paragraph

A fantasy football draft simulator that models the *actual managers* in a real
Sleeper league (reach bias, positional tilt, unpredictability — fitted from their
draft history where it exists) rather than filling seats with generic ADP bots.
Spring Boot 3.5 / Java 21 / Postgres backend (virtual threads, no reactive
framework), React + TypeScript + Vite frontend. The engine runs thousands of
independent Monte Carlo draft simulations and aggregates them into a predicted
board and, per player, an availability curve — the probability he survives to each
of your own upcoming picks. See `README.md` for layout/running and `HANDOFF.md` for
current state; both are kept current, this file is not a substitute for either.

## The thing to understand before changing anything

**This project's central problem is thin data, and its central value is honesty
about that.** One or two drafts of history per manager, no true ADP feed, no
backtesting (a deliberate decision — don't relitigate it). That shapes how code
gets written here:

- A hand-set constant (`config/weights.yml`) is *labelled* arbitrary, not presented
  as principled.
- Anything that would make output look more confident than it is gets surfaced in
  the payload (`SimulationResult.Confidence`'s caveats/provenance ride all the way
  to the UI on purpose) — never quietly dropped.
- "Verified" and "assumed" are kept visibly apart in every summary. If something
  hasn't been executed, say so, in those words.

If a change would make the tool look more certain without making it more correct,
that's a reason not to make it.

## Which environment am I in?

Check before assuming either applies: `java -version`, whether you're on Windows or
a Linux cloud sandbox, and whether a shell tool like `netstat`/`ps` shows anything
already listening on 8080/5173/5432/5433.

**Windows machine, running natively (the common case as of 2026-08-30):**
Maven Central, Gradle's distribution server, npm, and the Sleeper API are all
directly reachable — no `curl`-vs-`WebFetch` asymmetry. A JDK is present but not
necessarily 21; Gradle's toolchain (`foojay-resolver-convention`, already in
`settings.gradle.kts`) auto-provisions 21 itself, no manual install needed. A
throwaway Postgres 14 cluster may already be running on `localhost:5433` (trust
auth, db/user `draftsim`, matching `application.yml`'s local-dev defaults exactly)
— **do not confuse it with a separate, unrelated Postgres this machine may also run
on the standard 5432 port; leave that one alone.** The 5433 cluster lives in a temp
dir and will not survive a reboot. Windows-specific syntax traps:
- `gradlew.bat` invoked bare from `cmd.exe` (e.g. via a launch-config `runtimeArgs`)
  fails with `'gradlew.bat' is not recognized` even from the right directory — use
  `.\gradlew.bat` or `call gradlew.bat` explicitly.
- PowerShell and the Bash tool (Git Bash) are both available but are separate
  shells with separate syntax (`$env:VAR` vs `$VAR`, no `&&`/`||` in Windows
  PowerShell 5.1, etc.) — pick one per command, don't mix.
- Prefer the Browser tool's `preview_start`/`preview_logs`/`preview_stop` for
  running `bootRun` and `vite dev` over raw backgrounded Bash — it manages the dev
  server lifecycle and captures logs cleanly. Add entries to `.claude/launch.json`
  for anything you need to start this way.

**Cloud sandbox (Linux):** most of `claude/environment.md` is written for this case
and doesn't apply on the Windows machine — read it in full before assuming a recipe
applies either way. Highlights: Maven Central and `apt-get` are blocked; **`WebFetch`
reaches hosts `curl` cannot** (this is how Sleeper API data gets read there); a
`javac`-only recipe exists for running engine code without Spring at all (stub
`@Component`/`@ConfigurationProperties`, exclude anything importing slf4j/servlet/
JDBC); `device_bash` is a separate Linux VM with its own gotchas (no delete
permission by default, no global git identity, JDK 11 not 21 — don't build the
project there).

## Build, test, run

    cd backend && ./gradlew test          # full suite; see "Recurring bug classes" before trusting green
    cd backend && ./gradlew bootRun       # needs Postgres reachable per application.yml's defaults
    curl localhost:8080/api/health        # weightsLoaded must be true
    curl -X POST localhost:8080/api/ingest/all/{sleeperLeagueId}   # idempotent; do this before trusting any board/sim
    cd web && npx tsc -b && npm run build # strict TS + production build
    cd web && npm run dev                 # vite dev server, proxied to the backend

**Restarting the backend after a Java change matters.** `bootRun` does not hot-reload
— if a dev server from a previous session is already listening on 8080, it's serving
whatever bytecode existed when it started. Check `netstat` for the PID, kill it, and
restart before testing a backend change, or you'll debug a "bug" that's actually
stale compiled code. The frontend's `vite dev` *does* hot-reload via HMR — but
restarting the frontend dev server breaks any already-open browser tab's HMR
websocket silently; that tab keeps rendering pre-restart JS while looking otherwise
responsive, and needs a hard refresh (Ctrl+Shift+R), not a normal reload, to catch
up. Confusion from this exact thing cost real turns in a 2026-08-30 session — if a
fix looks right in code and passes a build, but a live tab still shows the old
behavior, suspect this before suspecting the fix.

## Hard rules

- **Schema migrations are append-only.** `backend/src/main/resources/db/migration/`
  — check the highest `V<n>__*.sql` already there and do not edit it once applied;
  any change is the next `V<n+1>`.
- **`web/src/api.ts` types are hand-maintained and must mirror the Java records
  field-for-field**, in the same change that touches the backend record. TypeScript
  gives no warning about an extra field a JSON response now carries that the type
  doesn't know about — a stale type fails silently, not loudly, and has shipped a
  real bug before (a configured value rendered as if it were absent, one layer
  above where a similar bug had already been fixed once).
- **Never retune a hand-set constant (`config/weights.yml`, or similar) to make a
  freshly measured number match an earlier session's guess.** If a guess was wrong,
  say so and name it wrong — don't quietly adjust the model to agree with it. The
  same discipline runs the other way: don't report a suspected failure as real
  without running it first. Either direction, the fix is the same — run it, report
  the actual number, and be explicit about which one you did.
- **A `Map.of(...)` in a JSON response path throws on any null value.** Check for
  legitimately-nullable fields (a free agent's team, a nullable tendency) before
  using it; build the map mutably instead if one might be null.
- **Ask before committing.** Multiple docs in this repo say so explicitly, and
  concurrent sessions on this tree are a real, observed occurrence — diff what's
  staged before assuming it's only your own changes.
- **A roadmap or planning doc's stated scope/difficulty is not a verified spec.**
  `claude/next-features-roadmap.md` called one feature "the largest lift" of four —
  reading the actual engine code before building it found the mechanism it needed
  already existed, fully tested, just unexposed. Check the code before accepting a
  planning doc's framing of how hard something is.

## Recurring bug classes (full detail and code in `claude/lessons.md`)

Each of these is a *class*, not a one-off — the same shape has recurred more than
once in this codebase:

1. A scoring/ranking change can be well-formed and pointed backwards. Structural
   tests (no crashes, no duplicates, right counts) don't catch a sign inversion —
   only a test that asserts *preference ordering* does. Any scoring-math change
   needs one of those.
2. SQL that reads correctly and Postgres refuses at runtime (e.g. referencing an
   `UPDATE` target inside a `JOIN`'s `ON` clause) — not findable by inspection,
   trivially findable by execution. Run the SQL.
3. A JDBC bind where the Java type and the declared `java.sql.Types`/column type
   look compatible but aren't (`String[]` → `text[]`, `java.sql.Timestamp` →
   `timestamptz`) — compiles fine, fails every time at runtime, only surfaces by
   actually executing the real code path, not a narrower standalone check.
4. Trusting a declared value instead of the thing it describes — a `Content-Type`
   header that lies about the actual body shape. Verify with `curl -D -` before
   assuming a client's content negotiation will parse it.
5. A statistic can be correct and still be the wrong thing to display — the
   marginal mode per cell is real math, but a reader assumes a "predicted board" is
   one coherent board, not seven independent guesses that happen to agree on one
   player. Check what the reader will assume a number means, not just whether it's
   right.
6. "Has no UI/caller yet" is not the same claim as "works," and shouldn't quietly
   become one. An endpoint that has only ever been called by curl/Postman (no CORS
   preflight) can be completely broken from a real browser and nothing before that
   browser actually tries it will show it.
7. One failed check generalizes into a capability claim too easily ("Maven's
   blocked, so nothing here is testable") — enumerate what's actually reachable
   before concluding something can't be done.
8. Guessing a number and stating it with the same confidence as a measured one.
   Label a guess as a guess, or spend the time to measure it — don't let the two
   blur together in a summary.

## This repo's convention for building anything feature-sized

Every substantial feature in `claude/*.md` (not the smaller planning/idea docs)
follows the same shape — match it rather than inventing a new process:

- **Design doc first**, written to `claude/<topic>.md` before code, with: what's
  there now (with file:line references), proposed design, what's explicitly *not*
  being built and why, and acceptance criteria for the verification pass.
- **Built via a multi-stage pipeline**, each stage a genuinely separate pass, not
  a single agent doing all of it in one breath: plan → an adversarial review that
  reads the plan cold and hunts for gaps *before* any code exists → build → a
  bug-hunting code review (not a style pass) → live verification that actually
  drives the real thing (run the real server, click the real UI, hit the real
  endpoint — a passing test suite alone is not this project's bar for "verified").
- **Corrections are shown, not hidden.** When a review or verification pass finds
  the original doc wrong about something, the doc gets an explicit "amended after
  review" note with what changed and why, rather than being silently rewritten.
  Same for a wrong guess anywhere in this repo's docs (`HANDOFF.md`'s round-1
  probability write-up is the reference example) — correct it visibly in place.
- Examples of the built-feature half of this convention:
  `claude/live-poller-plan.md`, `claude/live-reveal-and-tendencies-ui.md`,
  `claude/your-pick-and-pacing.md`, `claude/board-redesign-pick-by-pick-playercard.md`,
  `claude/reactive-resimulation.md`. Examples of the planning/reconciliation half
  (where "nothing built" is a legitimate outcome): `claude/next-features-roadmap.md`,
  `claude/borrowed-drafts.md`, `claude/adp-sources.md`.

## Working style that fits this project

- Allan is a backend engineer (Java, Go, C#/.NET) — skip framework tutorials and
  boilerplate walkthroughs, assume competence. TypeScript/frontend is not his
  deepest area; a little more explanation there is welcome.
- He pushes back on weak claims, and he's usually right to — treat pushback as a
  prompt to go check something for real, not to re-explain the same claim.
- Prefer testing a claim over asserting it, and say plainly which one you did.
- He asks for durable lessons to be recorded as they're found, not batched for the
  end of a session — this file and `claude/lessons.md` exist because of that habit.
