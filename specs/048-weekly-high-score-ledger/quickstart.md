# Quickstart: verifying spec 048

## Prerequisites
- Local Postgres on 5433 (`draftsim`), backend on 8080 started **from this worktree**
  (memory: worktree preview serves main — check the bootRun classpath), web on 5173.
- At least one real league re-ingested: `curl -X POST localhost:8080/api/ingest/all/{sleeperLeagueId}`.
- Commissioner identity: the `X-Sleeper-User` of that league's `is_commissioner` member
  (`select m.sleeper_user_id from league_member lm join manager m on m.id = lm.manager_id where lm.league_id = ? and lm.is_commissioner`).

## Scenarios

1. **Off by default (FR-001, SC-005)**: `GET /api/leagues/{id}/weekly-pot` → `enabled:false`,
   no `balances`. Page shows no pot amount (stake/balance/pot/payment) to a non-commissioner.
2. **Non-commissioner refused (US3)**: `PUT .../settings` with a non-commissioner header → 403
   with `commissionerKnown`.
3. **Turn on (US3)**: as commissioner, `PUT {enabled:true, stakeCents:100}` → 200 with ledger.
4. **Winners match Sleeper (SC-001)**: for every counted week, compare `weeks[].winners`
   with `select week, roster_id, starters_points from (select *, rank() over (partition by week
   order by starters_points desc) r from roster_week_points where league_id=?) t where r=1` restricted
   to the payload's `DECIDED` weeks (ties show as several rows), and spot-check two weeks in the Sleeper app.
5. **Zero-sum (SC-002)**: Σ `balances[].netCents` = 0.
6. **Settle-up clears (SC-003)**: apply `pairwise` and `shortcut` to balances → all 0;
   `len(shortcut) <= len(pairwise)`.
7. **Stake scales (US3.2)**: `PUT stakeCents:200` → every untied week's figures double; tied weeks
   follow the cent rule (3-way: 34/33/33 → 67/67/66).
8. **Regular season only (FR-012)**: `throughWeek <= regularSeasonThroughWeek`; a complete past
   season with playoffs lists no playoff week.
9. **Live leader (US4)**: during a live week, `liveLeader.week` = Sleeper's current week and
   balances unchanged vs. the previous load.
10. **UI**: open `/leagues/{id}/weekly-pot` on desktop and at 375px: standings, week list,
    settle-up tabs; commissioner panel visible only to the commissioner.
11. **ESPN refusal (plan A9)**: an ESPN league's page shows "The weekly pot follows Sleeper leagues only."
12. **ITs ran**: `WeeklyPotControllerIT` reports 0 skipped.
