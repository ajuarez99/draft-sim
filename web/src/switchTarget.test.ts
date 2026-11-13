import { describe, expect, it } from 'vitest'
import { switchTarget } from './switchTarget'
import type { LeagueContext } from './destinations'
import type { LeagueLineage } from './leagueLineage'
import { draftSummary } from './testShellHelpers'

/*
 * Moving between leagues and seasons without going Home (specs/011, specs/003).
 *
 * The rule worth testing is the fallback: a page you are on may not exist in the league you are
 * switching to, and the season dropdown and league switcher have to land you somewhere real rather
 * than on a page that league does not have. These were the pure halves of LeagueRailSection.test and
 * LeagueSwitcher.test; the components that call switchTarget are tested in LeagueHeader.test.tsx and
 * LeagueSwitcher.test.tsx.
 */

const NFL_B = draftSummary({ id: 2, leagueId: 2, sleeperLeagueId: 'LB', sleeperDraftId: 'DB', leagueName: 'Beta League' })
const NBA_C = draftSummary({ id: 3, leagueId: 3, sleeperLeagueId: 'LC', sleeperDraftId: 'DC', leagueName: 'Hoops League', sport: 'nba' })

function ctxOf(d: typeof NFL_B): LeagueContext {
  return { lineage: { current: d, seasons: [d] }, season: d }
}

describe('switchTarget across leagues', () => {
  it('keeps the page you were on when the other league has it', () => {
    expect(switchTarget('history', ctxOf(NFL_B))).toBe('/leagues/LB/history')
    expect(switchTarget('power', ctxOf(NFL_B))).toBe('/leagues/LB/power')
  })

  // The Schedule grid is basketball-only, and the rule for that lives in the destination table --
  // this reads it rather than restating it, which is why the two can't drift apart.
  it('falls back to League home when the target league has no such page', () => {
    expect(switchTarget('schedule', ctxOf(NFL_B))).toBe('/leagues/LB')
  })

  it('keeps Analysis for a football league and for a basketball one', () => {
    expect(switchTarget('analysis', ctxOf(NFL_B))).toBe('/leagues/LB/analysis')
    expect(switchTarget('analysis', ctxOf(NBA_C))).toBe('/leagues/LC/analysis')
  })

  it('sends a board to the target league’s own board', () => {
    expect(switchTarget('board', ctxOf(NFL_B))).toBe('/drafts/DB/board')
  })

  // A page with no league destination of its own (a mock room, a manager's history) lands on the
  // target's board, so a menu's year links differ per year instead of all pointing at one URL.
  it('falls back to the board from a page that is not a league page at all', () => {
    expect(switchTarget(null, ctxOf(NFL_B))).toBe('/drafts/DB/board')
  })
})

describe('switchTarget across seasons', () => {
  const NFL25 = draftSummary({ sleeperDraftId: 'D_NFL25', sleeperLeagueId: 'L_NFL25', sport: 'nfl', season: 2025 })
  const NFL26 = draftSummary({ sleeperDraftId: 'D_NFL26', sleeperLeagueId: 'L_NFL26', previousLeagueId: 'L_NFL25', sport: 'nfl', season: 2026 })

  it('with no current page, a year goes to its own board', () => {
    const ctx: LeagueContext = { lineage: { current: NFL26, seasons: [NFL26, NFL25] }, season: NFL25 }
    expect(switchTarget(null, ctx)).toBe('/drafts/D_NFL25/board')
  })

  it('falls back to League home when the target sport lacks the page', () => {
    const nflLineage: LeagueLineage = { current: NFL26, seasons: [NFL26, NFL25] }
    expect(switchTarget('schedule', { lineage: nflLineage, season: NFL26 })).toBe('/leagues/L_NFL26')
  })

  // Spec 046 C9: an auction league offers no `adp` row (its board row IS that page), so a year link on the ADP
  // page must still stay on the ADP page rather than dropping to League home.
  it('a year link on an auction league ADP page stays on the ADP page', () => {
    const a25 = draftSummary({ sleeperDraftId: 'D_A25', sleeperLeagueId: 'L_A25', sport: 'nba', season: 2025, draftType: 'auction' })
    const a26 = draftSummary({ sleeperDraftId: 'D_A26', sleeperLeagueId: 'L_A26', previousLeagueId: 'L_A25', sport: 'nba', season: 2026, draftType: 'auction' })
    const ctx: LeagueContext = { lineage: { current: a26, seasons: [a26, a25] }, season: a25 }
    expect(switchTarget('adp', ctx, '/leagues/L_A26/adp')).toBe('/leagues/L_A25/adp')
  })

  // specs/022 T027: the player page lives at /leagues/:league/players/:player, and
  // a season switch must keep the player, not drop to League home.
  it('a year link on a player page keeps the player id read from the path', () => {
    const nba25 = draftSummary({ sleeperDraftId: 'D_NBA25', sleeperLeagueId: 'L_NBA25', sport: 'nba', season: 2025 })
    const nba26 = draftSummary({ sleeperDraftId: 'D_NBA26', sleeperLeagueId: 'L_NBA26', previousLeagueId: 'L_NBA25', sport: 'nba', season: 2026 })
    const ctx: LeagueContext = { lineage: { current: nba26, seasons: [nba26, nba25] }, season: nba25 }
    expect(switchTarget('players', ctx, '/leagues/L_NBA26/players/1350')).toBe('/leagues/L_NBA25/players/1350')
    // no player in the path: League home rather than a broken URL
    expect(switchTarget('players', ctx)).toBe('/leagues/L_NBA25')
  })

  // specs/032 C2 (F7, F9): the stat calculator shares the Player stats row, so a year link must stay
  // on the calculator, keep the formula's query string, and drop the roster filter (roster ids
  // repeat every season).
  describe('stat calculator year links (specs/032)', () => {
    const nba25 = draftSummary({ sleeperDraftId: 'D_NBA25', sleeperLeagueId: 'L_NBA25', sport: 'nba', season: 2025 })
    const nba26 = draftSummary({ sleeperDraftId: 'D_NBA26', sleeperLeagueId: 'L_NBA26', previousLeagueId: 'L_NBA25', sport: 'nba', season: 2026 })
    const ctx: LeagueContext = { lineage: { current: nba26, seasons: [nba26, nba25] }, season: nba25 }
    const calc = '/leagues/L_NBA26/stats/calculator'

    it('keeps /calculator and the query, and drops own=r:3', () => {
      const t = switchTarget('stats', ctx, calc, '?f=pts%2Bast&w=LAST_10&own=r%3A3&team=LAL')
      expect(t.startsWith('/leagues/L_NBA25/stats/calculator?')).toBe(true)
      const q = new URLSearchParams(t.split('?')[1])
      expect(q.get('f')).toBe('pts+ast')
      expect(q.get('w')).toBe('LAST_10')
      expect(q.get('team')).toBe('LAL')
      expect(q.has('own')).toBe(false)
    })

    it('keeps own=free (only a roster id is season-specific) and has no "?" when the query is empty', () => {
      expect(switchTarget('stats', ctx, calc, 'own=free')).toBe('/leagues/L_NBA25/stats/calculator?own=free')
      expect(switchTarget('stats', ctx, calc, '')).toBe('/leagues/L_NBA25/stats/calculator')
      expect(switchTarget('stats', ctx, calc + '/', undefined)).toBe('/leagues/L_NBA25/stats/calculator')
    })

    it('the plain stats page is unchanged: no /calculator, no query string', () => {
      expect(switchTarget('stats', ctx, '/leagues/L_NBA26/stats', '?f=pts&own=r%3A3')).toBe('/leagues/L_NBA25/stats')
      expect(switchTarget('stats', ctx)).toBe('/leagues/L_NBA25/stats')
    })
  })
})
