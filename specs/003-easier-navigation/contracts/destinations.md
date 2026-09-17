# Contract: `web/src/destinations.ts`

**Feature**: `003-easier-navigation`

This is an **internal module contract**, not an HTTP one. It is written down as a contract because
four components depend on it agreeing with itself, and the defect this feature fixes is exactly what
happens when that agreement is left implicit (research.md R1).

## The guarantee

> There is exactly one declaration of what pages a league has. Any component that needs to know —
> to link to them, to recognise them in a URL, to search them, or to carry the user between leagues
> — reads it from here and nowhere else.

## Exports

```ts
export type DestinationKey =
  | 'board' | 'live' | 'history' | 'power' | 'analysis' | 'mock'

export type LeagueDestination = {
  key: DestinationKey
  glyph: string
  sports: Sport[]                        // never defaulted, never empty
  label: string | ((season: DraftSummary) => string)
  href: (ctx: LeagueContext) => string
  match: RegExp | null                   // null only for isAction destinations
  requiresStatus: DraftStatus[] | null
  isAction: boolean
}

/** The declaration. Order is rail render order. */
export const LEAGUE_DESTINATIONS: readonly LeagueDestination[]

/** Destinations a given league actually offers, sport and status applied. */
export function destinationsFor(ctx: LeagueContext): LeagueDestination[]

/** Which destination a pathname is, if any. */
export function destinationFromPath(pathname: string): DestinationKey | null
```

## Consumers and what each takes

| Consumer | Uses | Must not |
|---|---|---|
| `railLeague.ts` → `leagueRefFromPath` | `match` patterns | keep its own alternation of route shapes |
| `LeagueRailSection.tsx` | `destinationsFor()`, `label`, `glyph`, `href` | re-test `sport === 'nfl'` in JSX |
| `searchIndex.ts` | `destinationsFor()` per league | emit a destination the sport does not offer |
| league switcher (Phase 4) | `key` to preserve the page, `destinationsFor()` for the fallback | assume the target league has the current page |

## Invariants (each is a test, not a convention)

1. **Every `match` has an `href` and vice versa.** For every non-action destination, a URL built by
   `href` must be recognised by `match`. This single test is what would have caught the Analysis bug
   the day it shipped.
2. **`destinationFromPath` is total over the app's league routes.** Every league-scoped path in
   `App.tsx` resolves to a key. A route added to `App.tsx` without a row here fails this test.
3. **`sports` is explicit.** No destination may rely on a default. Asserted by construction — the
   type requires the field — and by a test that the array is non-empty.
4. **Sport gating is one-way.** `destinationsFor()` on an NBA league never returns `analysis`.
5. **Status gating is one-way.** `live` appears only for `pre_draft` and `drafting`.

## Why `match` is a `RegExp` and not React Router's matcher

`AppShell` mounts outside `<Routes>` on purpose, so that the `Keyed*` wrappers can remount draft
views without taking the rail with them — `AppShell.tsx:72` records this as load-bearing, because
those keys also stop an in-flight resim landing on the next draft's board. Outside `<Routes>`,
`useParams` returns `{}` and `matchPath` would need the route objects re-declared. A regex per
destination keeps the declaration in one file and costs nothing at this scale.

## Non-goals

- This module does not own **global** routes (Home, Managers, Mock drafts). Those are not
  league-scoped and stay in `Rail.tsx`.
- It does not own route *rendering*. `App.tsx` remains the route table; this module describes what
  those routes mean to a league.
