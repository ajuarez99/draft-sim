# draft-sim

Fantasy football mock drafts simulated against models of the actual managers in
a Sleeper league, rather than generic ADP bots.

## Status

Vertical slice, end to end: ingest -> board -> profiles -> Monte Carlo -> SSE -> UI.
Nothing has been backtested. See "What not to trust" below.

## Running

    docker compose up -d                     # Postgres 17 on localhost:5433
    cd backend && gradle wrapper --gradle-version 8.14   # once; wrapper is not committed
    ./gradlew bootRun

    curl localhost:8080/api/health           # weightsLoaded must be true
    curl -X POST localhost:8080/api/ingest/all/1391509063170293760
    curl localhost:8080/api/board?limit=40   # eyeball this before trusting a sim

    cd web && npm install && npm run dev      # http://localhost:5173

Ingest order matters the first time: players -> leagues -> board. `/api/ingest/all/{leagueId}`
does all three. It is idempotent; re-run it whenever you want a fresh board.

No environment variables are needed locally — every deployment-varying value has a
local default. `docker compose --profile full up --build` runs the production image
against local Postgres if you want to rehearse a deploy.

## Configuration and auth

All deployment values are env vars with local defaults; see `.env.example` and
`config/weights.yml`. The one that matters:

`API_TOKEN` blank (the local default) means **authentication is off** and every route
is open. Set it to any long random string and `/api/**` starts requiring
`Authorization: Bearer <token>`, with `/api/health` left open for platform health
checks. The app logs which mode it started in — check it before exposing anything.

`DEPLOY.md` has the full deployment sequence. Nothing has been deployed yet, and the
frontend still assumes a same-origin API with no auth header, so it needs two small
changes before a remote backend will work.

## Layout

    config/weights.yml   every scoring weight and model constant, external by design
    docker-compose.yml   Postgres
    backend/             Spring Boot 3.5 / Java 21, virtual threads
    web/                 React + TS + Vite

Backend packages mirror the plan's module layout as packages, not separate Gradle
modules. Split them later if the seams hold; a six-module build for a one-person
project is friction with no payoff yet.

    ingest/   Sleeper client, league-chain crawl, board derivation
    store/    JdbcClient repositories
    domain/   Player, BoardEntry, RosterState, LeagueSettings, snake-order math
    sport/    SportRules seam + football impl
    profile/  reach bias, positional tilt, P(position | round)
    engine/   scoring, softmax sampling, Monte Carlo runner, aggregation
    api/      REST + SSE

## Leagues

Sleeper user: popsharky (1122386008709910528)

    fantasy(heart)         2026  league 1391509063170293760  draft 1391509064357273600  14 teams, no history
    West Coast FF          2026  league 1389361939561332736  draft 1389361939561332737  14 teams, 2025 predecessor
    (Foot) Ball Knowers    2026  league 1346366555759341568  draft 1346366555776126976  12 teams, complete
    (Foot) Ball Knowers    2025  league 1254190892974084096  draft 1254190894563729408  12 teams, complete

All PPR, snake, 15 rounds. Starters QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench.

## What not to trust

Stated here rather than buried, because the outputs look more precise than they are.

**The board.** There is no true 14-team PPR ADP feed wired up. The board is
Sleeper's `search_rank` (a popularity ordering, not league-size or scoring aware)
blended with the observed pick order of completed drafts, rescaled by team count.
The blend weight is a free parameter with nothing behind it. This is the weakest
link in the model. Everything downstream reads `BoardEntry`, so replacing it with
a real ADP source touches one class.

**The weights.** Every number in `config/weights.yml` is hand-set. None is fit.

**Per-manager profiles.** Two parameters, shrunk hard. With one scoreable draft a
manager's own history carries 1/5 weight; with two, 1/3. Their profile is mostly
the league average, which is the correct outcome at this sample size. A pick can
only be scored for reach against a board captured near it in time, so 2025 picks
are excluded entirely rather than measured against a 2026 board. `drafts_observed`
and `picks_scored` ride all the way out to the UI for this reason.

**fantasy(heart) has no history at all.** Every seat there is the league-average
drafter. That version answers "what does a realistic draft room do", not "what do
these 13 people do".

**Calibration.** Nothing is backtested. The probabilities are internally
consistent, which is not the same as calibrated. When the 2027 drafts land,
`weights.yml` is where fitted values go.

## A bug worth remembering

`valueDelta` was originally written as `(boardPosition - pickNo)`, which is
inverted: it made the engine prefer the *worst* available player. Every
structural test still passed -- all 210 picks made, no duplicates, kickers
gated -- because none of them asserted that good players go first.
`PickScorerTest.aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot` and
`DraftSimulatorTest.theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop` exist
to catch it. Keep them.
