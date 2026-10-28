# Contract: `web/src/analytics.ts` (spec 052)

The only module that talks to the analytics service. Nothing else in the app references
`window.umami` or the service's URL.

## Exports

```ts
initAnalytics(): void                       // once, from main.tsx; no-op without a website id
trackPageView(pathname: string, signedIn: boolean): void   // AppShell, on signedIn+pathname change
trackLeagueView(pathname: string, signedIn: boolean, sport: Sport, leagueId: string): void
trackAction(name: KeyAction, props?: Record<string, string>): void
routeTemplate(pathname: string, signedIn: boolean): { url: string; title: string }   // pure; exported for tests
export type KeyAction = 'sign-in' | 'league-added' | 'mock-started'
```

## Behaviour

- Without `VITE_UMAMI_WEBSITE_ID`: every function returns immediately; no element is added
  to the document and no request is made (FR-014).
- `initAnalytics` appends one `<script async src="https://cloud.umami.is/script.js"
  data-website-id=… data-auto-track="false" data-domains="www.ballknowers.co,ballknowers.co">`.
- Calls made before the script has loaded are queued (cap 20) and flushed on load; if the
  script fails to load (blocked, offline), the queue is dropped and later calls are no-ops.
- Every call is wrapped so a throw inside the tracker cannot reach React (FR-007).
- `trackPageView` ignores a call whose template equals the last one sent for the same
  pathname (guards StrictMode double effects and re-renders, FR-001).
- `routeTemplate` covers every `<Route path>` in `App.tsx`. A test reads `App.tsx` and fails
  if a path is missing from the template table — two lists of one rule, held together by a
  test (memory: multi-sport landmines).

## Callers

| Caller | Call |
|---|---|
| `main.tsx` | `initAnalytics()` |
| `AppShell.tsx` | `trackPageView(location.pathname)` in an effect on `location.pathname`; `trackLeagueView(...)` when `railLeague` resolves for that pathname |
| `SignIn.tsx` | `trackAction('sign-in')` after `setUser(found)` |
| `DraftPicker.tsx` (Sleeper setup `:152-177`, pasted id `:196`), `EspnLeagueSetup.tsx:68-74` | `trackAction('league-added', { via })` after success |
| `MockSetup.tsx` / `LiveDraftView.tsx` | `trackAction('mock-started', { from })` after the session is created |

## Amended after review (2026-10-28)

- Without an id, **or** on any hostname other than `www.ballknowers.co` / `ballknowers.co`,
  **or** with `localStorage['umami.disabled']` set: no script, no sends (F8).
- `VITE_ANALYTICS_DEBUG=1` (dev only): log the would-be payload with `console.debug`
  instead of loading the script (F9).
- Only `window.umami.track(payload)` with a full object built here; never a string first
  argument (F4). Never set `data-errors` (the script's error reporter sends stack traces).
- `league-view` only when the resolved league matches the current path's league (F3);
  page-view de-dupe keyed on `signedIn + pathname` (F2); templates from `matchRoutes` (F11).
- CSP origins: `script-src https://cloud.umami.is`, `connect-src https://gateway.umami.is` (F9).
