# Contract: backend API

## Public, no identity header read (`/api/history/public`)

| Method + path | 200 body | Other |
|---|---|---|
| `GET /seasons?sport=NBA\|NFL` | `SeasonRef[]`, ascending | 400 missing/unknown sport (no default) |
| `GET /seasons/{sport}/{slug}` | `SeasonPage` | 404 unknown sport, unknown or unoffered season |
| `GET /daily/yesterday` | `YesterdayDaily[]`, one per sport | never deals a puzzle (read-only) |
| `GET /games/{id}` | `PublicGame` | 404 unless status is `COMPLETE` |

- Responses carry `Cache-Control: public, max-age=3600` for seasons, `no-store` for games and daily.
- None of these returns an owner id, a Sleeper id or a guest id.
- Allowed from the Time Machine origin by CORS, including preflight (bug class 6: checked from a real browser, not only curl).

## Identity for the existing `/api/history/**` routes

Resolved once per request:

1. `X-Sleeper-User` present and not blank → that id. If it starts with `guest:` → 400.
2. else `X-Guest-Id` is a canonical UUID → owner key `guest:<uuid>`.
3. else → 401 (as today).

## Changed

| Route | Change |
|---|---|
| `POST /api/history/games` | body gains optional `seasonStart`. With it: that season, `chosenSeason=true`; 400 if not offered. Guest: allowed only with `seasonStart` and only while the guest owns no non-daily game; otherwise 403 `{ "code": "GUEST_LIMIT" }`. |
| `GET /api/history/seasons` | unchanged for callers with identity (kept; the public list is the new one) |
| `GET/POST /api/history/daily` | accepts a guest |
| `GET /api/history/games`, `GET /games/{id}`, `pick`, `auto`, `abandon` | accept a guest, owner check unchanged |
| achievements routes | guest → 403 `GUEST_LIMIT` |

## New

`POST /api/history/claim`, requires **both** headers. Moves every game owned by `guest:<uuid>` to the Sleeper id, except daily games that would break `historical_game_daily_once`. Idempotent: a second call moves 0. Body `ClaimResult { moved, leftBehind }`. 400 if either header is missing or malformed.

## Error shape

`GUEST_LIMIT` uses the project's existing error body with a `code` field; the exact existing shape is read in the build stage and matched, not invented.
