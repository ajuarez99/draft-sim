# Sandbox constraints and verification recipes

Everything here was discovered the slow way. Read it before deciding something
cannot be tested — that conclusion was reached too early once already this session
and it was wrong.

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
