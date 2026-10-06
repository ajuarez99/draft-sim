import type { OneGameCredit, StreamingReason } from './api'

/**
 * Copy for the Trends page (specs/019-minutes-streaming). Kept out of the
 * component so the wording rules are testable: no sentence names an internal
 * route or an "ingest", and nothing promises a refresh that isn't coming.
 */

export function streamingReasonSentence(reason: StreamingReason): string {
  switch (reason) {
    case 'SEASON_COMPLETE':
      return 'This league’s season is over, so there are no streaming candidates. Streaming is for the current season.'
    case 'NOT_DRAFTED':
      return 'This league hasn’t drafted yet, so every player is still available and there is nothing to stream.'
    case 'ROSTERS_NOT_LOADED':
      return 'Rosters aren’t loaded yet, so we can’t tell who is on a roster. They load on the next refresh.'
  }
}

/** Measured once, not live: +4% for 4 vs 2 scheduled games, 2025, Ball Knowers. */
export function oneGameNote(credit: OneGameCredit | null): string | null {
  if (!credit) return null
  const pct = Math.round(credit.share * 100)
  return (
    `In this league a starter’s week counts one game (${pct}% of multi-game weeks in ${credit.seasonMeasured}). ` +
    'Extra games help a little: measured +4% for 4 vs 2 scheduled games (same player, 2025, Ball Knowers).'
  )
}

/** Leads a section whose list fell back to last season's data. */
export function fallbackLabel(season: number, listSeason: number, cutoverGames: number): string {
  return `${listSeason} season (${season} is too early: fewer than half the teams have played ${cutoverGames} games)`
}

/** The risers/fallers window label, "end of the 2025 season" when fallen back or when the season is complete. */
export function rolesWindowLabel(endOfSeason: boolean, listSeason: number | null, season: number, recentGames: number): string {
  return endOfSeason && listSeason != null
    ? `end of the ${listSeason} season, last ${recentGames} games vs season`
    : `${listSeason ?? season} season, last ${recentGames} games vs season`
}
