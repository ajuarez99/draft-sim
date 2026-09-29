import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/*
 * specs/009-auto-data-refresh T025 (research R1).
 *
 * The rail starts a league's background refresh and watches it, but the pages
 * own their data. This is the one thing they share: a per-league counter that
 * the rail bumps when a refresh finishes. A page adds it to its data-fetch
 * effect's dependencies and refetches, without knowing a refresh exists.
 *
 * The provider must sit ABOVE both the rail and the routes (AppShell), since
 * the rail writes and the pages read; a provider inside <Routes> would be
 * remounted by App's Keyed* wrappers and lose every count.
 *
 * Keyed by Sleeper league id, like every league route. A page for an older
 * season of the same league is keyed by that season's own id, so the rail bumps
 * every season of the lineage rather than only the newest.
 */

type Versions = ReadonlyMap<string, number>

type Ctx = {
  versions: Versions
  bump: (sleeperLeagueId: string) => void
}

const LeagueDataVersionContext = createContext<Ctx>({
  versions: new Map(),
  // Outside a provider (a page rendered alone in a test) there is nothing to
  // refetch for, and a silent no-op is the right answer.
  bump: () => {},
})

export function LeagueDataVersionProvider({ children }: { children: ReactNode }) {
  const [versions, setVersions] = useState<Versions>(() => new Map())

  const bump = useCallback((sleeperLeagueId: string) => {
    setVersions((prev) => {
      const next = new Map(prev)
      next.set(sleeperLeagueId, (prev.get(sleeperLeagueId) ?? 0) + 1)
      return next
    })
  }, [])

  const value = useMemo(() => ({ versions, bump }), [versions, bump])
  return <LeagueDataVersionContext.Provider value={value}>{children}</LeagueDataVersionContext.Provider>
}

/** Starts at 0 and only ever goes up. Put it in a data-fetch effect's dependency list. */
export function useLeagueDataVersion(sleeperLeagueId: string | undefined): number {
  const { versions } = useContext(LeagueDataVersionContext)
  return sleeperLeagueId ? (versions.get(sleeperLeagueId) ?? 0) : 0
}

/** Stable across renders, so it is safe in an effect's dependency list. */
export function useBumpLeagueDataVersion(): (sleeperLeagueId: string) => void {
  return useContext(LeagueDataVersionContext).bump
}
