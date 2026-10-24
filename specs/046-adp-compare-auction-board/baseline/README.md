# Spec 046 baselines (T001), measured 2026-10-23 on the old code (`origin/main` `5512cd7`)

These were run against a **scratch database**, never the shared 5433 one (analyze C1):

- container `draftsim-pg-046`, image `postgres:17-alpine`, on **5436**
- restored by `pg_dump -Fc` from `draftsim-pg` (5433): migrations through V38, 1,520 picks,
  10 leagues

## Backend suite

`DB_URL=jdbc:postgresql://localhost:5436/draftsim ./gradlew test`: BUILD SUCCESSFUL. **2,217 tests,
6 skipped, 0 failures, 0 errors**. The 6 skips are the known NBA-fallback ITs (memory: spec 041).
Log: `gradle-test-before.log`.

## Profiles: US4 measured with today's code

Steps: old-code `bootRun` on 8146 against 5436. `GET /api/managers?sport=nba` (admin token), then
`POST /api/ingest/all/1415422393706455040` ("Legacy Ball Knowers", the 2026-10-21 $200 auction: 230
picks ingested), then `GET /api/managers?sport=nba` again.

- `profiles-before-ingest.json`: 47 NBA managers.
- `profiles-after-ingest-oldcode.json`: 49 managers.
  - **All 47 existing profiles changed.** Example, manager 48:
    - `draftsObserved` 3 → 4
    - `picksScored` 14 → 37
    - `empiricalReachBias` 4.36 → −6.22
    - `relativeReachBias` −1.63 → −17.34
    - positional tilt shifted
  - **2 new FITTED profiles** for managers whose only history is the auction (hinrichmanuver,
    s2jaKe).
- So today's code feeds an auction's nomination order into every NBA profile. The auction is a
  shared ADP-backfill and shrinkage input, so even managers not in that league move. This is the
  US4 failure, measured rather than assumed.

After the change (T035), the same steps must give profiles identical to
`profiles-before-ingest.json` for the 47, and the two auction-only managers must show no fitted
history.

**Open question for Allan (not checked):** whether "Legacy Ball Knowers" was ever ingested in
production. If it was, prod NBA profiles are polluted today, and a re-fit after deploy fixes them.
