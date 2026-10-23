# Quickstart: validate spec 041

> **Amended after review and rescope (2026-10-20).** This page predates plan D1′. Where they
> differ, **plan.md D1′ and tasks.md T001–T014 win**:
> - `jcmd` runs only in the `scripts/memory/Dockerfile.jdk` variants;
> - the DB is the scratch `draftsim_041`;
> - `:main` containers need `JAVA_OPTS="-XX:MaxRAMPercentage=75 …"`;
> - SC-002 is deferred, and the SCs are the primed ones in spec.md.
>
> The steps below are kept for the record.

## Prerequisites
- Docker Desktop running; `draftsim-pg` up on :5433 with NBA 2024/25 and NFL 2025/26 `player_game`.
- Do not touch the unrelated Postgres on :5432.
- Railway CLI logged in (read-only `metrics` use only).

## 1. Production-shaped container
```bash
docker build -t ballknowers-backend:041 .
docker run --rm -d --name bk041 --memory=2g --cpus=2 -p 8099:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/draftsim \
  -e JAVA_OPTS="<image default> -XX:NativeMemoryTracking=summary" ballknowers-backend:041
curl localhost:8099/api/health        # weightsLoaded: true
```
Build the baseline image from `origin/main` (c987b92) with the tag `:main` the same way.

## 2. Baseline and after
`scripts/memory-baseline.sh <container> <out.md>` runs the C3 profiles, twice. Expected: a C3 table
with every holder measured or explained.

## 3. Equivalence
- `cd backend && ./gradlew test`: 0 failed, **0 skipped** (Postgres up). Includes `MonteCarloGoldenTest` (C1).
- C2 sweep against `:main` and `:041` on the same DB: identical apart from named exclusions.

## 4. Expected outcomes
- SC-002: the 5,000-iteration sim's heap delta (mid-run minus rest) is ≥50% lower on `:041`.
- SC-003: `load-peak` on `:041` < `:main`; no non-2xx that `:main` didn't also return.
- SC-004: `load-after-10min` on `:041` is back near `rest` (the G1 periodic uncommit), against `:main` holding its peak.
- SC-006: first-request latency of the warm sweep within ±10%.

## 5. After deploy (Allan's go-ahead only)
`railway metrics -s draft-sim --memory --since 7d`, compared with research R0: no ratchet above the post-deploy floor + load.
