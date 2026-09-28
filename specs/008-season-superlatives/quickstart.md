# Quickstart: validating season superlatives

The live-verification stage of this repo's pipeline. It's a run guide, not implementation. Contracts
are in [contracts/superlatives-api.md](contracts/superlatives-api.md), rules in
[data-model.md](data-model.md).

**The bar** (AGENTS.md): a passing suite isn't "verified". Every check below is run against the real
server and, for the page, the real browser. Record in the verification notes which checks were run
and which were not.

## Prerequisites

```bash
docker compose up -d
```

- That's Postgres 17 on **5433**. Leave the unrelated Postgres on 5432 alone.
- If Docker Desktop's engine isn't running, `docker ps` fails with `dockerDesktopLinuxEngine … cannot
  find the file`; start Docker Desktop first. The `initdb`/`pg_ctl` recipe in
  `claude/environment.md` is the fallback.
- Start the backend with `preview_start {name: "draft-sim-api"}`. If something is already listening
  on 8080, kill it first: `bootRun` doesn't hot-reload, and a server left over from an earlier
  session serves stale bytecode.

```bash
curl -s localhost:8080/api/health
```

Expect `weightsLoaded: true`.

**Reference leagues**:

| League | Sport | Season | Why |
|---|---|---|---|
| `1346366555759341568` | nfl | 2026 | live season; `last_scored_leg` 2 on 2026-09-22 |
| `1254190892974084096` | nfl | 2025 | complete; `playoff_week_start` 15, scored through 17 (playoff weeks exist) |
| `1229352720222134272` | nba | 2025 | complete; `playoff_week_start` 19, scored through 21 |

Ingest each, in this order:

```bash
curl -s -X POST localhost:8080/api/ingest/all/1346366555759341568
```

```bash
curl -s -X POST localhost:8080/api/ingest/league-history/1346366555759341568
```

```bash
curl -s -X POST localhost:8080/api/ingest/transactions/1346366555759341568
```

```bash
curl -s -X POST localhost:8080/api/ingest/player-games/1346366555759341568
```

Repeat the four for `1229352720222134272`, and the last three for `1254190892974084096`. Read each
`player-games` result: `playersWalked`, `gamesStored`, `playersFailed`, and the new unclassified-week
count. A non-zero `playersFailed` must be explained before continuing.

## 1. Open measurements planning couldn't make (do these first)

These decide whether research is right. Record the actual numbers.

- **R3, expected wins and playoffs.** Count stored pairings at or past `playoff_week_start` for NFL
  2025. If it's non-zero, the pre-change Expected wins page counted playoff games. Capture that
  page's NFL 2025 numbers **before** the change, so the difference can be shown to Allan.
  - Also capture `GET /api/managers/{managerId}/history` for one manager with seasons in both
    sports. Its career `winsAboveExpected` comes from the same service and will change too.
- **R8, transaction `leg`.** Find one waiver add in NFL 2026 and check its stored `week` against the
  first week that player appears in the adding roster's `starters`. Is the add's week ≤ the first
  started week?
- **R9, football absence coverage.**
  - For NFL 2025, count `player_absence` rows by `basis`, and the unclassified weeks the ingest
    reported.
  - Check two cases by hand. McCaffrey-style: an injured regular's weeks show as
    `ENTRY_WITHOUT_PLAY`. Bye: a known bye week of a rostered player has no absence row.
- **R9, football latent bug.** Confirm `player_game` holds no NFL row for a week the player didn't
  play (the `playedIn` fix).
- **R11, players in an IR/reserve slot.** Find any rostered player in an IR slot in any reference
  league. Sleeper's `/league/{id}/rosters` lists them under `reserve`. Confirm whether he appears as
  a key in that roster's `players_points` for a week he sat there.
  - This decides roster membership for the Embiid and Unethical awards (see the tasks' membership
    decision task). IR is exactly where injured players sit, so it matters more than it looks.
  - If no such player exists, record "not checkable today", not "passed".
- **R11, capture rows.** After one `POST /api/ingest/players?sport=nfl`, confirm a `status_capture`
  row for (nfl, 2026, current week) and one `player_suspension` row per `Sus`-tagged player (10 on
  2026-09-22).

## 2. API checks (SC-001 to SC-003, SC-007)

```bash
curl -s -H "X-Sleeper-User: <your sleeper user id>" localhost:8080/api/leagues/1346366555759341568/superlatives
```

- **Shape**: all twelve kinds present, in contract order; `throughWeek` equals the highest stored
  week below `playoff_week_start`.
- **SC-001**: hand-check highest week, lowest week, blowout, closest game and the close-win/loss
  counts against the stored scores and pairings.
- **SC-002**: `LUCKIEST`/`UNLUCKIEST` `detail` equals the (bounded) `GET …/expected-wins` row for
  the same roster, to the cent.
- **SC-007**: `HIGHEST_WEEK` equals the record book's highest week restricted to this season and to
  weeks below `playoff_week_start`.
- **SC-003**: for `WAIVER_WIRE_WARRIOR`'s holder, sum by hand: each started week × a player whose
  latest arrival on that roster was a completed WAIVER/FREE_AGENT row. Must equal `value`.
- **Scoping**: the same call with no header, or a user outside the league, gets 404.
- **Past season**: `GET /api/leagues/1254190892974084096/superlatives` (the NFL 2025 league's own
  id; there is no `?season=` parameter) returns the 2025 window with `throughWeek: 14`, not 17,
  because playoff weeks 15–17 are excluded.

## 3. Basketball (SC-004, SC-006)

```bash
curl -s -H "X-Sleeper-User: <id>" "localhost:8080/api/leagues/1229352720222134272/superlatives"
```

- **`JOEL_EMBIID`**: for the holder's top absence, count by hand from `player_absence` the nights he
  missed while on that roster. `gamesMissed` must equal it. No night his NBA team didn't play may
  appear.
- **Cost**: `pointsPerGame` must equal the mean of his played games, each scored under this
  league's settings. Hand-score one game against the league's scoring to check it.
  `estimatedPointsLost` = `gamesMissed` × `pointsPerGame`.
- **Margin**: `closeGameMargin` is 15, and the close-game counts use it.

**Football scoring check (analysis M1).** `GameScoringService` was only ever verified against
basketball leagues. For one NFL 2025 regular contributor, score two of his played weeks with it under
the league's settings, and compare against his stored `players_points` for those weeks. Football has
one game per week, so the two must match. If they don't, `pointsPerGame` for football is wrong. Stop
and report; don't widen a tolerance to make it pass.

## 4. Commissioner's list (US6)

Run these from the **page**, not curl, because POST with JSON and DELETE are preflighted (lessons #6):

1. As the commissioner, add a player with `appliesFromWeek` = 2. Confirm `UNETHICAL` gains a
   `COMMISSIONER` row, only for teams that rostered him from week 2 on.
2. Edit the reason. Confirm it shows as typed, including `<b>` rendered as text, not markup.
3. Remove the entry. Confirm it no longer counts.
4. As a non-commissioner member: entries are visible, there are no edit controls, and a direct POST
   returns 403.
5. A league with `commissionerKnown: false`: the page says the list isn't available, and the award
   runs on suspensions only.

## 5. The page, in the browser

`preview_start {name: "draft-sim-web"}` and open `/leagues/1346366555759341568/superlatives`.

- The rail shows "Superlatives" right after "Weekly report".
- The title reads "Season so far · through week N". With N < 4, the FR-006 caveat is visible beside
  each of the six kinds, not in a footnote.
- Every superlative shows team, figure and week(s). Unavailable ones show their reason. Empty ones
  show their empty reason.
- "Closest wins"/"Closest losses" print "by under 10 points" from `closeGameMargin`.
- The Embiid award says "games missed, cause unknown", and costs are marked as estimates.
- The Unethical Award shows "tracking began week N" and a source for every row.
- Check at 375 px width: no horizontal scroll.
- If a fix looks right in code but the tab doesn't show it after a frontend restart, hard-refresh
  (Ctrl+Shift+R) before debugging (AGENTS.md).
- Take a screenshot for the verification notes.

## 6. Suites

```bash
cd backend && ./gradlew test
```

Read the skip count in the test XML, not the build result. With Postgres down the suite reports
`BUILD SUCCESSFUL` with ~52 integration tests skipped.

```bash
cd web && npx tsc -b && npm run build && npx vitest run
```

## 7. The Expected wins page after the R3 change

Open `/leagues/1254190892974084096/expected-wins` and compare it with the numbers
captured in step 1. Write the before/after into the verification notes (the bound was approved on
2026-09-23, so this records the change rather than asking for it).

Do the same for **career profiles**, which also read expected wins: `ManagerCareerService.java:225`
sums `winsAboveExpected` across a manager's seasons. Compare
`GET /api/managers/{managerId}/history` for one manager against the step 1 capture, and write that
before/after down too. Allan approved the Expected wins page changing; this second change was found
by analysis, so show it to him explicitly.

## 8. The Jabari Smith Jr. Award (US7, added 2026-09-28)

Rules and shape: [research.md](research.md) R16 and the contract's `ADD` row. Expected numbers were
**measured against Sleeper's feed on 2026-09-28**, not the app's stored rows. A mismatch here means
either the ingest or the rule is wrong, so find which before changing either.

1. **Ingest first**, for both seasons, so the stored rows are complete:
   `POST /api/ingest/transactions/1229352720222134272` and `.../1254190892974084096`.
2. **NBA 2025**: `GET /api/leagues/1229352720222134272/superlatives`, kind `JABARI_SMITH_JR`.
   - Expect `playerHolders` = Jake LaRavia and Brice Sensabaugh, both `adds: 14`,
     `distinctTeams: 8`. Expect `holders: []` and `early: false`.
   - Expect 28 `ADD` rows, 14 per player. Sensabaugh's include three week-15 adds by the same team.
3. **NFL 2025**: `GET /api/leagues/1254190892974084096/superlatives`.
   - Expect no D/ST in `playerHolders`. Expect Kareem Hunt and Matt Prater at 3, or whoever else
     ties at 3 once D/ST is removed. The Sleeper measurement listed only the top 8 entries, so a
     third player at 3 is possible. Recount in SQL rather than trusting this line.
   - **D/ST ids in `player`**: check that every D/ST id added this season (e.g. `JAX`, `LAC`, `BAL`)
     exists in `player` with position `DEF`. One that's missing would be counted as an "Unknown
     player" (R16) and could win. Run
     `select sleeper_id, positions from player where sport='nfl' and sleeper_id in ('JAX','LAC','BAL','LAR','ARI','TB');`
     and adapt it to the table's real column names.
4. **Recount in SQL**: for the stored league-season, count completed `WAIVER`/`FREE_AGENT` rows per
   `jsonb_object_keys(adds)` over weeks 1..`throughWeek`. The top player and count must equal the
   payload's (SC-007's rule, applied to this award).
5. **Same answer as Roster management**: on that page's adds list, filtered to completed, count the
   winner's rows. It must match `adds` (research R16, decision 4).
6. **Current season**: open NFL 2026 (`1346366555759341568`). An add made in the in-progress week
   must not be counted until that week is fully scored. Read the add's week off Sleeper's feed and
   compare with the title's "through week N".
7. **Page**, in the browser: the award names the player(s), not a team, and says "N adds by M teams"
   next to each name. The list shows each add's week, team avatar and name, "Waiver ($bid)" or
   "Free agent". Check it at phone width, since 14 rows per player is the page's longest list. Tied
   players are grouped separately, not interleaved.
8. **Suites**: the pure test (below) and the existing `WaiverPickupAttributionTest` must both pass
   after the `completedAdds` extraction. The warrior's numbers on NBA 2025 must be unchanged. Capture
   them before the extraction.
