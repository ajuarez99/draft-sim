import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerCompare from './PlayerCompare'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import { ApiError } from '../apiError'
import { DASH, madeAttempted, num } from '../statFormat'
import type { PlayerStatsPage, PlayerWindow } from '../api'
import { owner, statsPage, win } from '../testPlayerStats'
import { board, row } from '../testStatBoard'

const getPlayerStats = vi.fn()
const fetchDraftStats = vi.fn()
vi.mock('../api', () => ({
  getPlayerStats: (...args: unknown[]) => getPlayerStats(...args),
}))
vi.mock('../draftStatsCache', () => ({
  fetchDraftStats: (...args: unknown[]) => fetchDraftStats(...args),
}))

/** The same numbers in all three windows, so a row differs between the players in every window. */
function windows(over: Parameters<typeof win>[0]): Record<'SEASON' | 'LAST_10' | 'LAST_5', PlayerWindow> {
  return { SEASON: win({ games: 60, ...over }), LAST_10: win({ games: 10, ...over }), LAST_5: win({ games: 5, ...over }) }
}

/** Alpha is ahead on the marked rows; every unmarked row differs too, so a stray mark would show. */
const ALPHA = statsPage({
  id: '1',
  name: 'Alpha One',
  windows: windows({
    minutesPerGame: 34,
    pg: { pts: 25, reb: 6, ast: 7, stl: 1.5, blk: 0.8, tpm: 3, tov: 3.2, pf: 2.1 },
    shooting: { fgPct: { value: 48, reason: null }, tpPct: { value: 38, reason: null }, ftPct: { value: 85, reason: null } },
    adv: { tovPct: { value: 12, reason: null } },
  }),
  fantasy: { ...statsPage().fantasy!, fpPerGame: { SEASON: 30, LAST_10: 31, LAST_5: 32 }, seasonTotal: 1800 },
  thisWeek: { week: 2, played: 1, remaining: 3, scheduleStored: true },
})
const BETA = statsPage({
  id: '2',
  name: 'Beta Two',
  windows: windows({
    games: 55,
    minutesPerGame: 30,
    pg: { pts: 20, reb: 9, ast: 3, stl: 0.9, blk: 1.8, tpm: 1, tov: 1.1, pf: 3 },
    shooting: { fgPct: { value: 45, reason: null }, tpPct: { value: 33, reason: null }, ftPct: { value: 72, reason: null } },
    adv: { tovPct: { value: 16, reason: null } },
  }),
  fantasy: { ...statsPage().fantasy!, fpPerGame: { SEASON: 25, LAST_10: 26, LAST_5: 27 }, seasonTotal: 1400 },
  thisWeek: { week: 2, played: 0, remaining: 2, scheduleStored: true },
})
// Beta has fewer games in each window only where it should: keep LAST_N full so the last-N rows are markable.
BETA.windows.LAST_10 = win({ ...BETA.windows.LAST_10!, games: 10 })
BETA.windows.LAST_5 = win({ ...BETA.windows.LAST_5!, games: 5 })

/** Every response the mock will give, by player id. A function value is called per request. */
let byId: Record<string, PlayerStatsPage | Error | (() => PlayerStatsPage)> = {}

function Where() {
  const l = useLocation()
  const nav = useNavigationType()
  return (
    <>
      <div data-testid="where">{`${l.pathname}${l.search}`}</div>
      <div data-testid="nav">{nav}</div>
    </>
  )
}

function mount(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <LeagueDataVersionProvider>
        <Routes>
          <Route path="/leagues/:sleeperLeagueId/compare" element={<PlayerCompare />} />
        </Routes>
        <Where />
      </LeagueDataVersionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  byId = { '1': ALPHA, '2': BETA }
  getPlayerStats.mockReset()
  getPlayerStats.mockImplementation((_league: string, id: string) => {
    const r = byId[id]
    if (r instanceof Error) return Promise.reject(r)
    if (typeof r === 'function') return Promise.resolve(r())
    return r ? Promise.resolve(r) : Promise.reject(new ApiError(404))
  })
  fetchDraftStats.mockReset()
  fetchDraftStats.mockResolvedValue(board([
    row('1', { name: 'Alpha One' }),
    row('2', { name: 'Beta Two' }),
    row('3', { name: 'Nikola Jokić', team: 'DEN' }),
    row('4', { name: null }),
  ]))
})

/** Rendered text with a space between elements, as a reader sees it (textContent glues "Alpha One" + "Start"). */
function spacedText(root: Node): string {
  const out: string[] = []
  const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let n = w.nextNode(); n; n = w.nextNode()) out.push(n.textContent ?? '')
  return out.join(' ')
}

const cellsOf = (label: string | RegExp) => {
  const r = screen.getAllByRole('row').filter((x) => {
    const th = within(x).queryByRole('rowheader')
    return th && (typeof label === 'string' ? th.textContent === label : label.test(th.textContent ?? ''))
  })
  return r.map((x) => within(x).getAllByRole('cell'))
}

describe('PlayerCompare', () => {
  it('shows each figure exactly as the player page formats it', async () => {
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    const a = ALPHA.windows.SEASON!
    const b = BETA.windows.SEASON!
    const [fpSeason, fpL10] = cellsOf('Fantasy pts / game')
    expect(fpSeason[0]).toHaveTextContent(num(30, 2))
    expect(fpSeason[1]).toHaveTextContent(num(25, 2))
    expect(fpL10[0]).toHaveTextContent(num(31, 2))
    expect(cellsOf('PTS')[0][0]).toHaveTextContent(num(a.perGame!.pts))
    expect(cellsOf('PTS')[0][1]).toHaveTextContent(num(b.perGame!.pts))
    expect(cellsOf('Minutes / game')[0][0]).toHaveTextContent(num(a.minutesPerGame))
    expect(cellsOf('Turnover rate')[0][1]).toHaveTextContent('16.0%')
    const [fg] = cellsOf('FG%')
    expect(fg[0]).toHaveTextContent('48.0%')
    expect(fg[0]).toHaveTextContent(madeAttempted(a.totals.fgm, a.totals.fga))
    expect(cellsOf('Games')[0][0]).toHaveTextContent('60')
    expect(cellsOf(/^Season total/)[0][0]).toHaveTextContent('1800.0')
    expect(getPlayerStats).toHaveBeenCalledWith('L25', '1')
    expect(getPlayerStats).toHaveBeenCalledWith('L25', '2')
  })

  it('marks only the rows the rule allows, and says which side is higher', async () => {
    const { container } = mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    const allowed = new Set(['Fantasy pts / game', 'Minutes / game', 'PTS', 'REB', 'AST', 'STL', 'BLK', '3PM', 'Turnover rate'])
    const marked = [...container.querySelectorAll('td.pc-win')].map((td) => td.closest('tr')!.querySelector('th')!.textContent!)
    expect(marked.length).toBeGreaterThan(0)
    for (const label of marked) expect(allowed.has(label), label).toBe(true)
    // Alpha leads PTS, Beta leads REB, and Alpha's LOWER turnover rate is the marked one
    expect(cellsOf('PTS')[0][0]).toHaveTextContent('higher')
    expect(cellsOf('PTS')[0][1]).not.toHaveTextContent('higher')
    expect(cellsOf('REB')[0][1]).toHaveTextContent('higher')
    expect(cellsOf('Turnover rate')[0][0]).toHaveTextContent('lower')
    // never marked: games, per-game TOV and PF, the season total and the shooting percentages
    for (const label of ['Games', 'TOV', 'PF', 'FG%', '3P%', 'FT%']) {
      for (const cells of cellsOf(label)) for (const c of cells) expect(c.className, label).not.toContain('pc-win')
    }
    for (const c of cellsOf(/^Season total/)[0]) expect(c.className).not.toContain('pc-win')
  })

  it('has no verdict text anywhere (SC-006), and the scan itself works', async () => {
    const { container } = mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    await screen.findByText('1 played · 3 left')
    const text = spacedText(container)
    const verdicts = [
      /\b(start|sit|bench)\b/i,
      /\b(better pick|recommend(ed|s)?|winner|edge)\b/i,
      /\b\d+\s*(of\s*\d+\s*)?(rows?|categories)\s+won\b/i,
    ]
    // the full page, with the this-week note, passes
    expect(text).toContain('one game per player each week')
    for (const re of verdicts) expect(text, String(re)).not.toMatch(re)
    // positive controls: the scan does catch a planted verdict
    expect(`${text} Start Jokić`).toMatch(verdicts[0])
    expect(`${text} Recommended: Alpha`).toMatch(verdicts[1])
    expect(`${text} Alpha won 7 of 12 rows won`).toMatch(verdicts[2])
    // ...including a verdict in its own element right after another word, which textContent would glue together
    const planted = document.createElement('b')
    planted.textContent = 'Start'
    container.querySelector('h3')!.after(planted)
    expect(container.textContent).not.toMatch(verdicts[0])
    expect(spacedText(container)).toMatch(verdicts[0])
  })

  it('shows this week for each player, with the note and the schedule as-of', async () => {
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(await screen.findByText('1 played · 3 left')).toBeInTheDocument()
    expect(screen.getByText('0 played · 2 left')).toBeInTheDocument()
    expect(screen.getByText(/schedule as of/)).toBeInTheDocument()
  })

  it('says "No game this week" plainly', async () => {
    byId['2'] = statsPage({ ...BETA, thisWeek: { week: 2, played: 0, remaining: 0, scheduleStored: true } })
    mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findByText('No game this week')).toBeInTheDocument()
  })

  it('shows no this-week line when the backend does not send one', async () => {
    const strip = (p: PlayerStatsPage) => {
      const q = { ...p }
      delete q.thisWeek
      return q
    }
    byId = { '1': strip(ALPHA), '2': strip(BETA) }
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.queryByText('This week')).not.toBeInTheDocument()
    expect(screen.queryByText(/played ·/)).not.toBeInTheDocument()
  })

  it('renders the other column and marks nothing when one player 404s (FR-012)', async () => {
    byId['2'] = new ApiError(404)
    const { container } = mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findByText('We have no player with that id in this league.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Alpha One' })).toBeInTheDocument()
    expect(cellsOf('PTS')[0][0]).toHaveTextContent('25.0')
    expect(container.querySelectorAll('td.pc-win')).toHaveLength(0)
  })

  it('shows one column and a chooser when the same player is given twice', async () => {
    mount('/leagues/L25/compare?a=1&b=1')
    await screen.findByRole('heading', { name: 'Alpha One' })
    expect(screen.getAllByRole('heading', { name: 'Alpha One' })).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Choose a player' })).toBeInTheDocument()
    expect(getPlayerStats).toHaveBeenCalledTimes(1)
  })

  it('says football is not supported after exactly one fetch, and makes no second column or chooser request', async () => {
    const nfl = statsPage({ sport: 'nfl', available: false, reason: 'NOT_BASKETBALL', windows: {}, fantasy: null, windowQualification: {} })
    byId = { '1': nfl, '2': nfl }
    mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findByText('Player comparison is available for basketball leagues only.')).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 20))
    expect(getPlayerStats).toHaveBeenCalledTimes(1)
    expect(fetchDraftStats).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Choose a player' })).not.toBeInTheDocument()
  })

  it('learns the sport from the leaderboard when neither player is chosen, and does so for football too', async () => {
    fetchDraftStats.mockResolvedValue(board([], { sport: 'nfl', available: false, reason: 'NOT_BASKETBALL' }))
    mount('/leagues/L25/compare')
    expect(await screen.findByText('Player comparison is available for basketball leagues only.')).toBeInTheDocument()
    expect(getPlayerStats).not.toHaveBeenCalled()
  })

  it('refetches the older column exactly once when the two came from different refreshes, then withholds every mark', async () => {
    const stale = statsPage({ ...BETA, dataAsOf: '2026-04-12T03:00:00Z' })
    byId['2'] = () => ({ ...stale })   // a fresh response object per request, as the network gives
    const { container } = mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findByText(/come from different refreshes/)).toBeInTheDocument()
    // Beta is the older response, so Beta was refetched once: 2 calls for it, 1 for Alpha
    expect(getPlayerStats.mock.calls.filter((c) => c[1] === '2')).toHaveLength(2)
    expect(getPlayerStats.mock.calls.filter((c) => c[1] === '1')).toHaveLength(1)
    expect(container.querySelectorAll('td.pc-win')).toHaveLength(0)
  })

  it('marks again when the refetch brings the two to the same refresh', async () => {
    let calls = 0
    byId['2'] = () => (++calls === 1 ? statsPage({ ...BETA, dataAsOf: '2026-04-12T03:00:00Z' }) : { ...BETA })
    const { container } = mount('/leagues/L25/compare?a=1&b=2')
    await waitFor(() => expect(container.querySelectorAll('td.pc-win').length).toBeGreaterThan(0))
    expect(calls).toBe(2)
    expect(screen.queryByText(/come from different refreshes/)).not.toBeInTheDocument()
  })

  it('names why a part-filled window is withheld', async () => {
    byId['2'] = statsPage({
      ...BETA,
      windows: { ...BETA.windows, LAST_10: win({ games: 3 }) },
      windowQualification: { ...BETA.windowQualification, LAST_10: { qualified: false, reason: 'NOT_QUALIFIED', lastN: 10 } },
    })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(cellsOf('Fantasy pts / game')[1][0].className).not.toContain('pc-win')
    // said once, in the window's header, not on every row (review N4); the cell itself carries a hidden cue
    expect(screen.getAllByText(/3 of 10 games/)).toHaveLength(1)
    expect(cellsOf('Fantasy pts / game')[1][1]).toHaveTextContent('not compared')
  })

  it('shows a fallback season line once', async () => {
    byId = { '1': statsPage({ ...ALPHA, requestedSeason: 2026 }), '2': statsPage({ ...BETA, requestedSeason: 2026 }) }
    mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findAllByText(/has no games yet; showing/)).toHaveLength(1)
  })

  it('has the dash for a player with no games in a window, not a zero', async () => {
    byId['2'] = statsPage({ ...BETA, windows: { ...BETA.windows, LAST_5: win({ games: 0, perGame: null, minutesPerGame: 0 }) } })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(cellsOf('Minutes / game')[2][1]).toHaveTextContent(DASH)
  })

  it('does not mark a stale LAST_10 that is also under the minutes floor (review B2)', async () => {
    byId['2'] = statsPage({
      ...BETA,
      windows: { ...BETA.windows, LAST_10: win({ games: 10, minutes: 120, minutesPerGame: 12, lastGameDate: '2026-01-01', pg: { pts: 40 } }) },
      fantasy: { ...BETA.fantasy!, fpPerGame: { SEASON: 25, LAST_10: 90, LAST_5: 27 } },
      windowQualification: { ...BETA.windowQualification, LAST_10: { qualified: false, reason: 'NOT_QUALIFIED', lastN: 10, stale: true } },
    })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    for (const c of cellsOf('Fantasy pts / game')[1]) expect(c.className).not.toContain('pc-win')
    expect(screen.getByText(/last game .* not compared/)).toBeInTheDocument()
  })

  it('shows the owner with its as-of label and, on a fallback season, the current owner too (review B3)', async () => {
    const now = owner({ ownerName: 'New Owner', asOf: { kind: 'CURRENT', fetchedAt: '2026-10-20T09:00:00Z', week: null } })
    byId['1'] = statsPage({ ...ALPHA, requestedSeason: 2026, currentOwnership: now })
    byId['2'] = statsPage({ ...BETA, requestedSeason: 2026 })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.getAllByText(/End of .* regular season \(week 18\)/).length).toBeGreaterThan(0)
    expect(screen.getByText('Now (2026–27)')).toBeInTheDocument()
    expect(screen.getByText('New Owner')).toBeInTheDocument()
  })

  it('says a player with no games has not played this season, and shows the teams disclosure (S1, S4)', async () => {
    byId['2'] = statsPage({ ...BETA, reason: 'NO_PLAYER_GAMES', teamsThisSeason: ['SAC', 'CHI'], teamGamesMissed: 4 })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.getByText('He hasn’t played a game this season.')).toBeInTheDocument()
    expect(screen.getByText(/Played for SAC, CHI in /)).toBeInTheDocument()
    expect(screen.getByText(/Missed 4 of his team’s games/)).toBeInTheDocument()
  })

  it('does not claim "no game this week" when no schedule is stored (review S3)', async () => {
    byId['1'] = statsPage({ ...ALPHA, scheduleAsOf: null, thisWeek: { week: 2, played: 1, remaining: 0, scheduleStored: false } })
    byId['2'] = statsPage({ ...BETA, scheduleAsOf: null, thisWeek: { week: 2, played: 0, remaining: 0, scheduleStored: false } })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.queryByText('No game this week')).not.toBeInTheDocument()
    expect(screen.getByText('1 played · schedule not stored')).toBeInTheDocument()
    expect(screen.getByText(/no schedule is stored/)).toBeInTheDocument()
  })

  it('gives a teamless player a reason in the this-week cell (review N5)', async () => {
    byId['2'] = statsPage({ ...BETA, thisWeek: null, player: { ...BETA.player, team: null } })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(await screen.findByText('No current team')).toBeInTheDocument()
  })

  it('still learns the sport when the only player id is unknown (review N3)', async () => {
    byId = {}
    fetchDraftStats.mockResolvedValue(board([], { sport: 'nfl', available: false, reason: 'NOT_BASKETBALL' }))
    mount('/leagues/L25/compare?a=nope')
    expect(await screen.findByText('Player comparison is available for basketball leagues only.')).toBeInTheDocument()
  })

  it('says why an empty window is not compared as "no games", once per window (S2, N4)', async () => {
    byId['2'] = statsPage({ ...BETA, windows: { ...BETA.windows, LAST_5: win({ games: 0, perGame: null, minutes: 0, minutesPerGame: 0, smallSample: true }) } })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.getAllByText(/^no games; not compared$/)).toHaveLength(1)
  })
})

describe('PlayerCompare chooser and address (US3)', () => {
  async function open(label = 'Choose a player') {
    fireEvent.click(await screen.findByRole('button', { name: label }))
    return screen.findByRole('combobox')
  }

  it('picking a player for the empty column writes only that side and does not refetch the other', async () => {
    mount('/leagues/L25/compare?a=1')
    await screen.findByRole('heading', { name: 'Alpha One' })
    const input = await open()
    fireEvent.change(input, { target: { value: 'bet' } })
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Beta Two/ }))
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.getByTestId('where')).toHaveTextContent('/leagues/L25/compare?a=1&b=2')
    expect(getPlayerStats.mock.calls.filter((c) => c[1] === '1')).toHaveLength(1)
  })

  it('changing a FILLED column rewrites only that side, replaces history, and does not refetch the other (review B1)', async () => {
    byId['3'] = statsPage({ id: '3', name: 'Nikola Jokić' })
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    expect(screen.getByTestId('nav')).toHaveTextContent('POP')
    fireEvent.click(screen.getByRole('button', { name: 'Change player B' }))
    const input = await screen.findByRole('combobox')
    fireEvent.change(input, { target: { value: 'jokic' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(await screen.findByRole('heading', { name: 'Nikola Jokić' })).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/leagues/L25/compare?a=1&b=3')
    expect(screen.getByTestId('nav')).toHaveTextContent('REPLACE')
    expect(screen.getByRole('heading', { name: 'Alpha One' })).toBeInTheDocument()
    expect(getPlayerStats.mock.calls.filter((c) => c[1] === '1')).toHaveLength(1)
    expect(getPlayerStats.mock.calls.filter((c) => c[1] === '3')).toHaveLength(1)
  })

  it('cancelling a change keeps the player', async () => {
    mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    fireEvent.click(screen.getByRole('button', { name: 'Change player A' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('heading', { name: 'Alpha One' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change player A' })).toBeInTheDocument()
    expect(screen.getByTestId('where')).toHaveTextContent('/leagues/L25/compare?a=1&b=2')
  })

  it('renders the same pair when the address is opened fresh', async () => {
    const first = mount('/leagues/L25/compare?a=1&b=2')
    await screen.findByRole('heading', { name: 'Beta Two' })
    first.unmount()
    mount('/leagues/L25/compare?a=1&b=2')
    expect(await screen.findByRole('heading', { name: 'Alpha One' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Beta Two' })).toBeInTheDocument()
  })

  it('offers two choosers when neither player is chosen', async () => {
    mount('/leagues/L25/compare')
    expect(await screen.findAllByRole('button', { name: 'Choose a player' })).toHaveLength(2)
  })
})
