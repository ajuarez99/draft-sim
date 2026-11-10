import {
  LEAGUE_DESTINATIONS,
  destinationsFor,
  playerIdFromPath,
  railKeyFor,
  type DestinationKey,
  type LeagueContext,
} from './destinations'
import { stripRoster } from './statCalcUrl'

/**
 * Where switching to `target` should land you, given where you are now.
 *
 * Keeps the page you were on when the target has it -- another year of the same
 * league, or another league. When it does not (Analysis is football-only; Follow
 * live exists only while a draft is running), the fallback depends on what kind
 * of page you were on: a league page lands on League home (specs/013), and
 * anything else on '/'. A draft page lands on the target's board, and so does no page at all
 * (`current === null`): a hinted room (a mock, a manager's history) is inside a
 * league without being on one of its pages, and falling back to History there
 * pointed every year in the flyout at the same URL. The board is the page that
 * differs per year. The Leagues flyout group calls this with the same `current`,
 * so from a mock room it now lands on the other league's board rather than its
 * History -- intended, for the same reason. The
 * availability rule is read from `destinationsFor`, never restated here: a
 * second copy of "which sports have Analysis" is how the rail and the palette
 * would eventually disagree.
 */
export function switchTarget(
  current: DestinationKey | null,
  target: LeagueContext,
  pathname?: string,
  search?: string,
): string {
  const offered = destinationsFor(target).filter((d) => !d.isAction)
  // Spec 046 C9: on an auction league's ADP page the "here" row is the board row (there is no separate adp row).
  current = railKeyFor(current, offered)
  const same = current ? offered.find((d) => d.key === current) : undefined
  if (same) {
    // The player page is addressed by a league AND a player; the league context
    // alone cannot say which player, so it is read back off the current path.
    // Without one (no pathname given) the row's href falls back to league home.
    const playerId = pathname ? playerIdFromPath(pathname) : null
    // The stat calculator shares the Player stats row; its year links stay on the calculator and
    // keep the query string (the formula travels), minus the roster filter, because roster ids
    // repeat every season (specs/032 C2, F7, F9); the rule itself lives in statCalcUrl's stripRoster.
    if (current === 'stats' && pathname && /\/stats\/calculator\/?$/.test(pathname)) {
      const qs = stripRoster(new URLSearchParams(search ?? '')).toString()
      return `${same.href(target)}/calculator${qs ? `?${qs}` : ''}`
    }
    return same.href(playerId ? { ...target, playerId } : target)
  }
  const wasDraftPage = current
    ? LEAGUE_DESTINATIONS.find((d) => d.key === current)?.idKind === 'draft'
    : true
  const fallback = offered.find((d) => d.key === (wasDraftPage ? 'board' : 'home'))
  return fallback ? fallback.href(target) : '/'
}
