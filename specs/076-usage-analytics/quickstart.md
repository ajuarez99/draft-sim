# Quickstart: validating spec 076

Prerequisites: the local Postgres on 5433 (memory note "Restart local Postgres 5433" if it is down), a
backend started **from this worktree** with `ADMIN_TOKEN` set and `CORS_ORIGINS` including the vite port,
and vite started with `VITE_USAGE_COLLECT=1`. Check the bootRun classpath points at this worktree before
trusting anything (a preview started from a worktree has served main's code before).

1. **Migration**: backend starts; `\d usage_daily` and `\d historical_game` show the table and
   `started_as_guest`; backfill check: `select count(*) from historical_game where started_as_guest <>
   (sleeper_user_id like 'guest:%')` is 0 right after migrating.
2. **Views (SC-001)**: Redraft build — open `/`, click to `/daily`, `/nba`, one season page. League build —
   two pages. `select * from usage_daily where day = current_date` (ET) shows six `view` rows' worth of
   counts on the right routes and sites. Re-render (leave a draft room open 2 min): no change.
3. **Funnel (SC-002, SC-003)**: as a guest start 2 NBA games, finish 1, abandon 1, share once, reload the
   results five times. Owner page: started 2, finished 1, abandoned 1, guest 2/1; shares 1.
4. **No identity (SC-004)**: capture the `/api/usage` requests in the browser's network panel while signed
   in and as a guest: no `X-Sleeper-User`, `X-Guest-Id`, `X-League-Session`; bodies hold only vocabulary.
5. **Unreachable (SC-005)**: stop the backend's usage route (or block the request in devtools) and play a
   full game: no console error from analytics, no visible change.
6. **Junk and burst (SC-006, SC-007)**: 1,000 curl sends with unknown routes/events → no new rows except
   the `rejected` counter; 200 valid sends in a few seconds from one address → the view count rises by 60.
7. **Gate (SC-008)**: `curl -i localhost:8080/api/admin/usage` → 403 `admin_token_required`.
8. **Opt-out**: set `localStorage['umami.disabled']`, navigate: no `/api/usage` request.
9. **Vocabulary guard**: add a fake route to `analytics.ts` → `npx vitest run usageVocabulary` fails naming it.
10. `cd backend && ./gradlew test` (check the skipped count is not the 52-IT silent skip), `cd web && npx
    tsc -b && npx vitest run && npm run build`.
