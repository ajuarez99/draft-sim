# Quickstart: verifying spec 036

Prerequisites: local Postgres on 5433 (`draftsim`/`draftsim`), the backend from **this
worktree** (check the bootRun classpath, memory `worktree_preview_serves_main_checkout`), and
the admin token if one is configured locally.

## 0. Environment (local DB, never the shared one)

Use the separate `draftsim_036` database on the 5433 cluster (created with
`createdb`/`create database draftsim_036`, user/password `draftsim`/`draftsim`). Both the app
(`application.yml`: `${DB_URL:...}`) and the ITs (`System.getenv("DB_URL")`) read the `DB_URL`
environment variable, and the Gradle test JVM inherits it, so no file is edited:

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5433/draftsim_036 ./gradlew.bat test
DB_URL=jdbc:postgresql://localhost:5433/draftsim_036 SERVER_PORT=8136 PORT=8136 ./gradlew.bat bootRun
```

psql: `PGPASSWORD=draftsim timeout 20 psql -w -h 127.0.0.1 -p 5433 -U draftsim -d draftsim_036`.

## 1. Capture (US1)

```bash
curl -s -X POST "localhost:8080/api/ingest/projection-snapshot?sport=nba" | jq
```

Expect `weeks` to include 1 before 2026-10-20, with `rowsWritten` ≈ 936 (the 2026-10-14 week-1
count) and `rowsNotPreGame = 0`.

```sql
select count(*), min(cutoff_at), bool_and(fetched_at < cutoff_at)
from projection_snapshot where sport='nba' and season=2026;
```

Run it again: `rowsWritten` refreshes the same rows and the count doesn't grow. The freeze is
proven by the IT that drives `capture(sport, now)` with `now` past a row's cutoff and a
changed line. Expect `rowsFrozenSkipped > 0` and the stored stats unchanged. The table's
`check (fetched_at < cutoff_at)` must reject a hand-written post-cutoff insert.

Daily path: `POST /api/refresh/daily` with the secret shows a `PROJECTION_SNAPSHOT` step per sport.

## 2. Accuracy (US2)

Before the season it's local only: the IT seeds snapshots + `player_game` rows by hand and
asserts MAE, lean, n and DNP against a hand computation (SC-003).

```bash
curl -s -H "X-Sleeper-User: <member>" localhost:8080/api/leagues/<nbaLeague>/projection-accuracy | jq '.status, .series[0].overall'
```

At deploy it should read `TOO_EARLY` or `NO_SNAPSHOTS`. On the Analysis page the source line
reads "RotoWire projection via Sleeper · …", the accuracy panel shows counts and "too early",
and there are no figures (SC-004).

## 3. Football parity (US3)

```bash
curl -s -X POST "localhost:8080/api/ingest/projections?sport=nfl&season=2026&fromWeek=7&toWeek=7&force=true"
curl -s "localhost:8080/api/ingest/projection-parity?sport=nfl&season=2026&week=7" | jq '.scoringSource, .matches, .mismatches'
```

Record the mismatch count in the spec (SC-005). Flip `nfl-exact-scoring` only on the
decision rule in contracts/parity.md.

## 4. Live UI

Open `/leagues/<nba>/analysis` and `/leagues/<nfl>/analysis` in the browser pane. Check the
source line on both, the accuracy panel, and that no projected and realized number share a
column (SC-006). Also open League home's preseason strength block for NBA: its source line
must carry the same accuracy status as the Analysis page.
