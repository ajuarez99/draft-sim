# Live verification: spec 056 (Football Time Machine)

> **Renumbered 2026-11-06:** this feature's migration is now `V46__historical_nfl.sql` (057 keeps V45, Allan's call; 057 merges first). See plan.md. "V45" below means this migration under its old number.

**Date**: 2026-11-06. **Setup**:
- API `056-api` (bootRun from this worktree) on 8156, web `056-web` (vite) on 5256, scratch Postgres `draftsim-pg-056` on 5439.
- Real data: the full NFLVERSE import (27 seasons) and the NBA SLEEPER import (2017-18 to 2025-26), on the same V45 database.
- Identity: a local test identity, `verify056`.

Every line below was **run** unless it says otherwise.

## What was driven

| Check | How | Result |
|---|---|---|
| Start page with both sports | browser, `/history` | Basketball/Football chips. Football copy reads "2000 to 2025", from the seasons response. Settings line: "Hand-set 12-team, 15-round roster…" ✅ |
| A football draft, US1 | browser: choose Football, Start → 2005, one manual pick (Culpepper), then Auto-finish | Board QB/RB/WR/TE only. Labeled "ranked by last season's points over replacement", no "ADP" text anywhere. Cards show only the 2004 line ✅ |
| Bot behaviour | 2005 round 1 | Tomlinson, Muhammad, Walker, Westbrook, Martin, Gates, Barber, Horn. No QB in round 1 ✅ |
| Results, US1/US2 | browser | 3rd of 12, 12–5. 17 weeks. Standings. Hindsight +941.6. Bust = Culpepper (taken 9th, finished 118th: 2005 ACL tear). Steal = LaMont Jordan over Jon Kitna. Lineup rows read "Week 1", never "did not play" ✅ |
| Provenance, US3 | browser + API | Attribution "Stats: nflverse (nflverse-data), CC BY 4.0" in the room and results. Notes: 2000 "246 of 248 regular-season games…", 2001 "3 source rows…", 2003/2005 "1 source row … was left out", 2022 positions note only ✅ |
| Past games, US4 | API `GET /api/history/games` | rows carry `sport`. A DRAFTING game's `finish` is `null` (review S1 fix) ✅ |
| Required sport | curl | `GET /seasons` and `POST /games {}` with no sport → 400 ✅ |
| Basketball unchanged, SC-008 (live part) | API + browser: NBA game, auto-finish | 2022-23. 25 weeks with real dates. NBA roster. `attribution` null, `dataNotes` []. Room shows 053's disclosure copy unchanged ✅ |
| SC-003 hand checks (three eras) | raw `historical_nfl_player_week` rows → best legal lineup with FR-003 (INT −1) vs stored result | 2005 wk 1: 148.4 = 148.4. 2019 wk 5: 163.34 = 163.34. 2025 wk 5: 101.72 = 101.72 ✅ |
| SC-002 game counts | import report (27 seasons) | every offered season matches its schedule count except 2000 (246/248, disclosed). 2022 = 271/271 ✅ |
| SC-004 scoring vs source | parent T003 (all 27 seasons, INT −2) and code review (2000/2003/2014/2024) | 100% of skill rows match the source PPR column within 0.01 in every season ✅ |
| SC-005 stats vs Sleeper (amended) | 2021, 8 counting stats, players with 50+ PPR | 292 players. 217 join on `gsis_id`. **215/217 (99.1%) equal on all 8**; one miss is Fournette rush yd 812 vs 811. 75 don't join because Sleeper's dump has `gsis_id = null` for them (Claypool, AJ Dillon, Zach Wilson, Aiyuk…), a gap in Sleeper's data |
| SC-006 no "ADP" | browser text of the NFL room and results | absent ✅ |
| SC-007 same-name | `NflGameFlowIT` (synthetic) + import (ids are GSIS) | separate cards. A real-data SQL pass over every same-name pair was **not run** |
| SC-001 timing | logs/review | create 68–464 ms, replay 174–425 ms, auto FINISH ≈ 0.6 s server-side ✅ |

## Found and fixed during verification

- **V1: anachronistic team codes** (measured). nflverse normalizes `team`/`opponent_team` to today's franchise codes, so a 2004 card said "LAC" (Tomlinson) and "LA" (Torry Holt). `NflverseSource.franchiseCodeIn` now maps LAC→SD (≤2016), LA→STL (≤2015) and LV→OAK (≤2019); these are relocation facts. Re-imported, and the DB now shows 2004/2015 = OAK, SD, STL; 2016 = LA, OAK, SD; 2017/2019 = LA, LAC, OAK; 2020 = LA, LAC, LV. Tomlinson 2004 = SD and Holt 2004 = STL. Tests are in `NflverseSourceTest` (3 new).
- **V2: "1 source rows … were"**: singular wording fixed in `NflHistoricalSport`, and seen live.

## Test suites

- **Web** (run): `tsc -b` clean, `npm run build` OK, vitest **160 files / 2763 tests, 0 failed**.
- **Backend on a brand-new DB** (`draftsim_fresh`, run 2026-11-06): **2751 tests, 3 failed, 334 skipped**.
  - The 3 failures (`EspnRefreshOnVisitIT`, 2× `LeagueRepositorySuccessorIT`) are in the pre-existing list from `research-notes.md`.
  - `DraftGradesReadIT.nfl2025`, the "14th failure" on the used scratch DB, now **skips**. It was a half-ingested real draft left in that DB by an app cold-start, not a regression. No file outside the historical feature changed.
  - **Caveat:** 307 skips come from 63 classes that probe the shared 5433 DB at class level, and 5433 is down after this morning's reboot. This run therefore covers **less** than the 2026-11-05 runs, which had 5433 up: 2748 tests, the 13 pre-existing failures plus that data artefact, 53 skipped.
  - All historical/NFL tests: **150 ran, 5 skipped** (real-season assumptions), 0 failed.

## Not verified / owed

- **Deploy-window B1** (new web on the old 053 backend) is covered only by old-shape vitest fixtures (`HistoricalOldBackend.test.tsx`), never driven live against a real 053 server.
- **Prod `historical_game` row count before V45** (SC-008 on real prod games). Not available from here.
- **S2: bots stack bench QBs** (2.87 QBs per bot roster across 99 rosters). Measured by the code review, and seen again live: auto-pick drafted 4 QBs onto this session's 2005 roster. Allan's call; not changed.
- **The V45 number collides with spec 057** (`V45__week_score_override.sql`, uncommitted in worktree peaceful-panini-70c6a7). Whichever merges second must renumber. Allan's call.
