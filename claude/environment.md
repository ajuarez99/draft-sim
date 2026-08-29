# Sandbox constraints and verification recipes

Everything here was discovered the slow way. Read it before deciding something
cannot be tested — that conclusion was reached too early once already this session
and it was wrong.

## If you are running directly on Allan's Windows machine, not the cloud sandbox

Most of this file describes a **cloud sandbox** session: Linux, `device_bash`,
blocked Maven Central, no committed Gradle wrapper. A 2026-08-29 evening session
ran directly on Allan's Windows box instead and almost everything below did not
apply: Maven Central, Gradle's distribution server, npm and the Sleeper API were
all directly reachable (no `WebFetch`-vs-`curl` asymmetry), a JDK was present
(24, not 21 — Gradle's toolchain now auto-provisions 21, see HANDOFF's "Build
tooling" note), and a local Postgres 14 service was already running for other
things. That session generated and staged the Gradle wrapper (bootstrapped by
downloading `gradle-8.14-bin.zip` directly and running `gradle wrapper` once)
and stood up a **throwaway** Postgres cluster rather than touch the existing
service or need its password:

    # from a shell with the PostgreSQL 14 bin dir on PATH
    initdb -D <tempdir> -U draftsim --auth=trust --encoding=UTF8
    pg_ctl -D <tempdir> -l <tempdir>/pg.log -o "-p 5433" start
    psql -h localhost -p 5433 -U draftsim -d postgres -c "create database draftsim;"

Port 5433 and user/db `draftsim` with no password match `application.yml`'s
local-dev defaults exactly, so `./gradlew bootRun` needs no configuration
against it. That data directory is temporary and will not survive a reboot —
fine for a same-session verification pass, not a substitute for `docker compose
up -d` as the durable local setup.

**Check what's actually reachable before assuming a recipe below applies.**
`java -version`, `node --version`, `psql --version`, a `curl -sI` to
`services.gradle.org` and `repo.maven.apache.org` settle it in under a minute.

## What does and does not work

| | Status |
|---|---|
| Maven Central (`repo1.maven.org`, `repo.maven.apache.org`) | **Blocked** by egress policy. No Spring/Flyway/Jackson/pgjdbc. Do not route around it |
| `apt-get` / `download.docker.com` | Blocked |
| Direct `curl` to `api.sleeper.app` | Blocked — from the cloud sandbox *and* from `device_bash` |
| `WebFetch` to the Sleeper API | **Works.** GET only. This is how league and draft data was read |
| npm registry, PyPI | Work |
| JDK 21, PostgreSQL 16, Node 22, Docker CLI | Installed in the cloud sandbox |

The important asymmetry: **`WebFetch` reaches hosts that `curl` cannot.** When
something looks network-blocked, try `WebFetch` before concluding it is unreachable.

## Recipe: run the engine without Spring

The only way to execute engine code here. Stub the two annotations and compile the
pure packages with `javac`.

    mkdir -p verify/src/org/springframework/stereotype
    mkdir -p verify/src/org/springframework/boot/context/properties

`Component.java` and `ConfigurationProperties.java` as empty `@interface`s (the
latter needs `String prefix() default "";`). Then copy `domain/`, `sport/`,
`engine/`, `profile/`, `config/` — **excluding** anything importing slf4j, servlet
or JDBC (`MonteCarloRunner`, `ProfileService`, `WebConfig`, `SimulationService`) —
and:

    find src -name '*.java' > sources.txt && javac -d out @sources.txt

Write a `Harness.java` in `com.ballknowers.draftsim.engine` (same package buys access
to package-private methods like `DraftSimulator.sample`) with plain `check(bool, msg)`
assertions. There is no JUnit available. 53 assertions currently run this way.

## Recipe: real Postgres

`initdb` refuses to run as root.

    useradd -m pg && mkdir -p /tmp/pgdata /tmp/pgsock && chown -R pg /tmp/pgdata /tmp/pgsock
    su pg -c '/usr/lib/postgresql/16/bin/initdb -D /tmp/pgdata -U postgres --auth=trust'
    su pg -c '/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pgdata -l /tmp/pg.log -o "-p 5433 -k /tmp/pgsock" start'
    psql -h /tmp/pgsock -p 5433 -U postgres -c "create database draftsim;"

Apply `V1__init.sql` directly, then test each repository query as a
`PREPARE`/`EXECUTE` pair — that exercises bind parameters the way JDBC drives them,
which is what caught the `UPDATE ... FROM ... JOIN` bug. Plain interpolated SQL would
not have.

## Recipe: frontend

    cp -r <staged web/> /home/claude/webcheck && cd /home/claude/webcheck
    npm install --no-audit --no-fund
    npm run build          # tsc -b under strict, then vite build

## Recipe: the SSE parser

`api.ts` hand-rolls SSE parsing over `fetch` because the endpoint is a POST and
`EventSource` is GET-only. Test it by bundling and feeding it a mock server at
hostile chunk sizes:

    npx esbuild src/api.ts --bundle --format=esm --outfile=/tmp/api.mjs

Then a Node script with `http.createServer` that writes the SSE body in slices of
1, 7, 64 and 100000 bytes, overriding `globalThis.fetch` to resolve the relative
`/api` path against the test server. One byte at a time is the case that matters.

## Recipe: see the UI without a backend

The only way to look at the frontend here. It found a bug that was invisible in
source review and to a passing `tsc -b` (lesson 6).

Build `web/`, then serve `dist/` from a small Node server that also answers
`GET /api/drafts/:id/seats` and `POST /api/sims/stream` with mock payloads shaped
like the Java records. Drive it with Playwright — Chromium is pre-installed:

    npm i -D playwright
    chromium.launch({ executablePath: '/opt/pw-browsers/chromium' })

Screenshot whole panels rather than the page: find them by heading text and call
`element.screenshot()`, which avoids stitching a 3000px full-page image.

Two things worth doing every time: collect `console` and `pageerror` events and
report the count, and keep the mock's shapes in sync with the Java records —
a mock that has drifted will hide exactly the bug you are looking for.

Note the mock server needs `nohup ... &` in its own subshell; a plain `&` inside a
`set -e` script exits 144 when the shell returns.

## Recipe: compile something that imports slf4j

`MonteCarloRunner` and the services log. Stub `org.slf4j.Logger` as an interface
with no-op `info/warn/debug/error` (including `warn(String, Throwable)`) and
`LoggerFactory.getLogger` returning an anonymous impl. That brings the whole
`engine` package into the standalone compile, which is worth it — lesson 8 is a
compile error that reached the repo because a file was written straight to the
device and never compiled anywhere.

## device_bash gotchas

- Runs in a **Linux VM on Allan's machine**, not the cloud sandbox. Separate
  filesystems. Connected folders mount at `$HOME/mnt/<name>`.
- Fresh shell per call, ~45s limit. Split long work.
- **Cannot delete by default.** `rm` fails with "Operation not permitted" until
  `device_request_delete_permission` is granted. This matters because **`git commit`
  leaves `.git/*.lock` and `tmp_obj_*` files behind**, which block Allan's next git
  command. Either request delete permission or warn him.
- **No global git identity.** Commit as:
  `git -c user.email=allanjuarez86@gmail.com -c user.name=allan commit ...`
- Java there is 11, not 21. Do not try to build the project in that VM.

## Staging files out to the sandbox

`device_stage_files` **fails beyond 7 folders below the connected folder root.**
Java sources under `backend/src/main/java/com/ballknowers/draftsim/<pkg>/` exceed it.

Workaround: `cp` them flat into a temp dir near the repo root, stage from there,
reconstruct package dirs in the sandbox. Remember to clean the temp dir up
(needs delete permission).

Also: do not `tar | base64` a directory and cat it. Large tool output gets truncated
to a file and you lose the round trip. Stage the files individually.

## Writing to the Claude project

`project_write` with `local_path` requires the file to be **inside the working
directory** (`/home/claude`), not `/mnt/user-data/uploads`. Stage, `cp` into
`/home/claude`, then write. This avoids retyping long documents into context.
