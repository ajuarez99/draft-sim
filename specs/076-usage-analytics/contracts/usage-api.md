# Contract: usage API (spec 076)

## `POST /api/usage` — public collection

- Public: on `LeagueLockInterceptor.PUBLIC_PATHS`; behind `ApiTokenFilter` like every `/api/*` route when a
  token is configured.
- Body: see data-model.md "What the browser sends". Max body 1 KB; larger, unparsable or non-JSON → not
  counted.
- **Every request that reaches the handler answers `204 No Content`**, empty body: accepted, refused
  (vocabulary), limited and crawler sends alike. (Upstream of the handler the token filter can still answer
  401 and CORS 403; the browser swallows both. *Amended after review, F10.*)
- Order of checks: crawler user agent → per-address limit (keyed on `X-Real-IP`, else the socket address;
  counts every send that reaches it; 60 a minute; at 10,000 tracked addresses new ones are refused until
  the window turns) → parse → vocabulary → (league-view) league exists → global write ceiling (600 a
  minute) → upsert. A send with a known site that fails vocabulary or league lookup adds 1 to an
  **in-memory** `rejected` count for that site, flushed to that day's `rejected` row at most once a minute.
  A send with an unknown site, an unparsable body, a crawler, a limited send or one over the ceiling is
  counted nowhere. *(Amended after review, F3 + F7. 60, 10,000 and 600 are hand-set guesses.)*
- A database failure is logged at WARN once per minute at most and still answers 204.
- Reads no identity header. Does not log the body, the address or the user agent.

## `GET /api/admin/usage?from=&to=` — owner read

- Behind `AdminGateInterceptor` (`/api/admin/**`): 403 `admin_token_required` without the token.
- `from`, `to`: `YYYY-MM-DD`, inclusive, in ET. Defaults: `to` = today (ET), `from` = `to` − 29 days.
  Bad date or `from > to` or a span over 366 days → 400 `{code: "bad_range", message}` (mutable map).
- 200 body, `UsageReport` (Java record; `web/src/api.ts` type mirrors it field for field):

```text
UsageReport {
  timezone: string                 // "America/New_York"
  from: string, to: string         // the range actually used
  countingSince: [{ site, since }] // per site: min(day) over its view / league-view / event rows, all time; a site with none has no entry (CR-08)
  gamesSince: string | null        // ET date of the oldest historical_game row; null = no games
  ownerConfigured: boolean         // whether draftsim.owner.sleeper-user-id is set (F5)
  viewsByDay:   [{ day, site, views }]              // kind=view; only days that have a row
  viewsByRoute: [{ site, route, views }]            // kind=view summed over the range, most first
  leagueViews:  [{ leagueId, leagueName | null, sport, views }]   // kind=league-view summed, most first
  events:       [{ site, name, props: {k: v}, count }]            // kind=event summed over the range
  rejected:     number                              // kind=rejected summed over the range
  funnel:       [{ day, sport, started, finished, abandoned, drafting,
                   guestStarted, guestFinished, unknownStarted, dailyStarted, dailyFinished,
                   ownerStarted, ownerFinished, ownerAbandoned, ownerDrafting,
                   ownerDailyStarted, ownerDailyFinished }]   // from historical_game, start day in range; sport is "nba" | "nfl"
}
```

- **Owner fields are the owner's SIGNED-IN games** (`sleeper_user_id` = the configured id AND `started_as_guest
  is false`), all 0 when no owner is configured. Guest (`is true`), unknown (`is null`), owner and other
  signed-in members are disjoint, so a game the owner started as a guest and then claimed counts as a guest
  game, never also as an owner game; an owner-owned row with a null flag is "unknown". The page subtracts the
  owner fields from EVERY funnel number (headline, gave up, left open, game of the day, per-day table).
- The request body carries `kind` (`view`, `league-view` or `event`); the server checks it agrees with the
  other fields.

*Amended after code review 2026-11-17 (CR-01, CR-02, CR-08).* `countingSince` was a single date and the owner
fields covered started and finished only; both are now as above. `X-Real-IP` is used as the limiter key only
when it is non-blank and at most 45 characters (CR-03), else the socket address.

- `leagueName` is nullable (a league row deleted since) — no `Map.of` on this path.
- Empty store: every list empty, `countingSince` an empty list. The page turns that into words (FR-017).
- Zero-filling days is the page's job for the series it draws; the API returns only days with rows. A day
  before that site's own `countingSince` entry is "not counted", not zero.
