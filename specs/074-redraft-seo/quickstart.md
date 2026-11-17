# Quickstart: validating 074

Prerequisites: Postgres on `localhost:5433` (see memory note "Restart local Postgres 5433"), with historical seasons imported for both sports. If the local import is partial, say which seasons were present in verification.md.

## 1. Backend

```bash
cd backend && ./gradlew test
```

Check the skipped count: Postgres ITs skip silently when the database is down.

```bash
cd backend && ./gradlew bootRun
```

```bash
curl -s "localhost:8080/api/history/public/seasons?sport=NFL" | head -c 400
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/history/public/seasons/NFL/1900
```

Expected: a list; then `404`.

## 2. Guest allowance, by direct call

With a fresh UUID in `X-Guest-Id` and no `X-Sleeper-User`:

1. `POST /api/history/daily {"sport":"NFL"}` → 201
2. `POST /api/history/games {"sport":"NFL","seasonStart":2007}` → 201, `chosenSeason: true`
3. the same call again → 403 `GUEST_LIMIT`
4. `POST /api/history/games {"sport":"NFL"}` → 403 `GUEST_LIMIT`
5. `POST /api/history/claim` with both headers → `moved: 2`; again → `moved: 0`

## 3. Static site, served the way production serves it

```bash
cd web && VITE_SITE=timemachine VITE_API_BASE=http://localhost:8080 npm run build
```

```bash
cd web && npx serve@14.2.6 dist --config ../serve.site.json -l 4173
```

Then, for each row of [contracts/pages.md](./contracts/pages.md), `curl -s -o /dev/null -D - <address>` and compare status and `Location`. `vite dev` and `vite preview` do not prove this: they have their own fallback.

```bash
cd web && grep -ril "time machine" dist ; echo "exit $?"
```

Expected: no files, exit 1.

## 4. League build is unchanged

```bash
cd web && VITE_SITE=leagues npm run build
```

Serve it the same way; `/leagues/123/power` answers 200 with the app, `/sitemap.xml` is not a sitemap, `robots.txt` disallows all.

## 5. Browser

Private window against the served build: play the daily as a guest, start one season from `/nfl/2007`, hit the limit, sign in, see both games once. Open the finished game's address in a second private window: result, no owner. Console: no CSP reports from the embedded JSON.
