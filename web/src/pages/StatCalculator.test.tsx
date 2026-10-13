import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import StatCalculator from './StatCalculator'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type { StatLeaderboard as Board } from '../api'
import { board, rate, row, win } from '../testStatBoard'
import { saveFormula } from '../statCalcSaved'

/** specs/032 T017. */

const getStatLeaderboard = vi.fn()
vi.mock('../api', () => ({
  getStatLeaderboard: (...args: unknown[]) => getStatLeaderboard(...args),
}))

/** (usg + vor) / tov: Ann 15, Cy 14, Bob 12. Dee is unqualified (would be 100). */
const rows = () => [
  row('1', { name: 'Ann', valueOverReplacement: 10, team: 'LAL', stats: win({ pg: { tov: 2, pts: 10, ast: 10 }, adv: { usg: rate(20) } }) }),
  row('2', { name: 'Bob', valueOverReplacement: 6, team: 'DEN', positions: ['C'], stats: win({ pg: { tov: 3, pts: 20, ast: 6 }, adv: { usg: rate(30) } }) }),
  row('3', { name: 'Cy', valueOverReplacement: 4, team: 'LAL', stats: win({ pg: { tov: 1, pts: 30, ast: 3 }, adv: { usg: rate(10) } }) }),
  row('4', {
    name: 'Dee', qualified: false, reason: 'NOT_QUALIFIED', valueOverReplacement: null, leagueRank: null,
    stats: win({ pg: { tov: 1, pts: 40, ast: 100 }, adv: { usg: rate(50) } }),
  }),
]

function Where() {
  const l = useLocation()
  return <output data-testid="where">{l.pathname + l.search}</output>
}

function mount(url = '/leagues/L25/stats/calculator') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <LeagueDataVersionProvider>
        <Where />
        <Routes>
          <Route path="/leagues/:sleeperLeagueId/stats/calculator" element={<StatCalculator />} />
        </Routes>
      </LeagueDataVersionProvider>
    </MemoryRouter>,
  )
}

async function show(url?: string, b: Board = board(rows())) {
  getStatLeaderboard.mockResolvedValue(b)
  mount(url)
  await screen.findByLabelText('Formula')
}

const type = (f: string) => fireEvent.change(screen.getByLabelText('Formula'), { target: { value: f } })
const run = () => fireEvent.click(screen.getByRole('button', { name: 'Run' }))
const where = () => screen.getByTestId('where').textContent ?? ''
const chartNames = () =>
  within(screen.getByRole('region', { name: 'Ranked chart' })).getAllByRole('listitem').map((li) => li.querySelector('a')?.textContent)
const tableNames = () =>
  within(screen.getByRole('region', { name: 'Calculator results' })).getAllByRole('rowheader').map((th) => th.querySelector('a')?.textContent)
const seg = (group: string, name: string) => within(screen.getByRole('group', { name: group })).getByRole('button', { name })

beforeEach(() => {
  getStatLeaderboard.mockReset()
})

describe('StatCalculator', () => {
  it('runs (USG + VOR) / TO: the chart and the table agree, with the unit warning and exact values', async () => {
    await show()
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
    type('(USG + VOR) / TO')
    run()
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(chartNames()).toEqual(['Ann', 'Cy', 'Bob'])
    expect(tableNames()).toEqual(['Ann', 'Cy', 'Bob'])
    expect(screen.getByLabelText('Formula as read')).toHaveTextContent('(USG + VOR (fantasy, per game)) ÷ TO (per game)')
    const warnings = screen.getByRole('note', { name: 'Formula warnings' })
    expect(warnings).toHaveTextContent('USG (percentage points)')
    expect(warnings).toHaveTextContent('VOR (fantasy points per game)')
    // exact values beside the bars, and the canonical form in the link
    const chart = screen.getByRole('region', { name: 'Ranked chart' })
    expect(chart).toHaveTextContent('15.00')
    expect(chart).toHaveTextContent('14.00')
    expect(chart).toHaveTextContent('12.00')
    expect(where()).toContain('f=%28usg+%2B+vor%29+%2F+tov')
    // Dee (unqualified, would be first) is not shown while qualification is on
    expect(screen.queryByText('Dee')).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Ranked chart' }).querySelector('figcaption')).toHaveTextContent(
      '(USG + VOR) ÷ TO · 2025–26, full season · highest first',
    )
  })

  it('"Rank by: lowest first" reverses chart and table, and the title says so', async () => {
    await show('/leagues/L25/stats/calculator?f=%28usg+%2B+vor%29+%2F+tov')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(seg('Rank by', 'Lowest first'))
    expect(chartNames()).toEqual(['Bob', 'Cy', 'Ann'])
    expect(tableNames()).toEqual(['Bob', 'Cy', 'Ann'])
    expect(where()).toContain('dir=asc')
    expect(screen.getByRole('region', { name: 'Ranked chart' }).querySelector('figcaption')).toHaveTextContent('lowest first')
  })

  it('sorting the table never reorders the chart', async () => {
    await show('/leagues/L25/stats/calculator?f=usg+%2B+tov')
    await screen.findByRole('region', { name: 'Ranked chart' })
    // usg+tov: Bob 33, Ann 22, Cy 11
    expect(chartNames()).toEqual(['Bob', 'Ann', 'Cy'])
    fireEvent.click(within(screen.getByRole('region', { name: 'Calculator results' })).getByRole('button', { name: /^TO/ }))
    expect(tableNames()).toEqual(['Cy', 'Ann', 'Bob'])
    expect(chartNames()).toEqual(['Bob', 'Ann', 'Cy'])
  })

  it('an invalid formula shows where it is wrong and keeps the previous result, labelled', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov')
    await screen.findByRole('region', { name: 'Ranked chart' })
    type('(usg +')
    run()
    expect(screen.getByRole('alert')).toHaveTextContent('The formula ends after an operator')
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toBeInTheDocument()
    expect(screen.getByText('previous formula')).toBeInTheDocument()
    // the link still holds the good formula
    expect(where()).toContain('f=ast+%2F+tov')
    expect(screen.getByLabelText('Formula')).toHaveValue('(usg +')
  })

  it('restores state from the link, and writes changes back with replace (no new history entry)', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov&n=Mine&w=LAST_10&m=per36&top=7&q=0&dir=asc&team=LAL')
    await waitFor(() => expect(getStatLeaderboard).toHaveBeenCalledWith('L25', 'LAST_10'))
    expect(screen.getByLabelText('Formula')).toHaveValue('ast / tov')
    expect(screen.getByLabelText('Name (optional)')).toHaveValue('Mine')
    expect(seg('Window', 'Last 10')).toHaveAttribute('aria-pressed', 'true')
    expect(seg('Counting stats', 'Per 36')).toHaveAttribute('aria-pressed', 'true')
    expect(seg('Rank by', 'Lowest first')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('checkbox', { name: 'Qualified players only' })).not.toBeChecked()
    expect(screen.getByLabelText('NBA team')).toHaveValue('LAL')
    expect(screen.getByLabelText(/^Bars/)).toHaveValue(7)
    // the chart title uses the name, the data's window and the direction
    expect(screen.getByRole('region', { name: 'Ranked chart' }).querySelector('figcaption')).toHaveTextContent('Mine · 2025–26, full season · lowest first')
    type('pts + ast')
    run()
    expect(where()).toContain('f=pts+%2B+ast')
    expect(where()).toContain('team=LAL')
    expect(where()).toContain('top=7')
  })

  it('drops a team that is not in the data and says so', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&team=ZZZ')
    expect(await screen.findByText(/left out: team ZZZ/)).toBeInTheDocument()
    expect(screen.getByLabelText('NBA team')).toHaveValue('')
    expect(chartNames()).toHaveLength(3)
  })

  it('a football board says it is basketball-only and shows no partial layout', async () => {
    getStatLeaderboard.mockResolvedValue(board([], { sport: 'nfl', available: false, reason: 'NOT_BASKETBALL' }))
    mount()
    expect(await screen.findByText('Player pages are for basketball leagues.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Formula')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Stats pages' })).not.toBeInTheDocument()
  })

  it('names the scoring season and says when the scoring changed on a fallback board', async () => {
    await show(
      '/leagues/L25/stats/calculator?f=fp',
      board(rows(), { requestedSeason: 2026, season: 2025, scoringSeason: 2025, scoringMatchesRequested: false }),
    )
    expect(await screen.findByText(/Fantasy points under 2025–26 league scoring\./)).toBeInTheDocument()
    expect(screen.getByText(/can rank players differently in another league/)).toBeInTheDocument()
    expect(screen.getByText('This league’s scoring has changed since then.')).toBeInTheDocument()
    expect(screen.getByText(/2026–27 has no games yet; showing 2025–26/)).toBeInTheDocument()
  })

  it('says nothing about scoring for a formula with no fantasy input', async () => {
    await show('/leagues/L25/stats/calculator?f=pts', board(rows(), { scoringSeason: 2025, scoringMatchesRequested: null }))
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.queryByText(/league scoring/)).not.toBeInTheDocument()
  })

  it('shows "Showing n of N" with the full rule, and lists everyone when qualification is off', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.getByText(/Showing 3 of 4 players\./)).toBeInTheDocument()
    expect(screen.getByText(/at least 42 games/)).toBeInTheDocument()
    expect(screen.getByText(/in the last 14 days/)).toBeInTheDocument()
    expect(screen.getByText(/guard against a tiny sample or a tiny denominator/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Qualified players only' }))
    expect(screen.getByText(/Showing 4 of 4 players\./)).toBeInTheDocument()
    expect(chartNames()).toContain('Dee')
    expect(where()).toContain('q=0')
  })

  it('changing the mode, the formula, the filters or standardize makes no new request; a window change does', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    fireEvent.click(seg('Counting stats', 'Per 36'))
    fireEvent.click(seg('Counting stats', 'Totals'))
    type('ast + pts')
    run()
    fireEvent.change(screen.getByLabelText('NBA team'), { target: { value: 'LAL' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Standardize inputs' }))
    fireEvent.click(seg('Rank by', 'Lowest first'))
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    fireEvent.click(seg('Window', 'Last 5'))
    await waitFor(() => expect(getStatLeaderboard).toHaveBeenCalledTimes(2))
    expect(getStatLeaderboard).toHaveBeenLastCalledWith('L25', 'LAST_5')
  })

  it('switching to per 36 re-evaluates counting inputs from the held payload', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toHaveTextContent('30.00')
    fireEvent.click(seg('Counting stats', 'Per 36'))
    // win() makes per36 pts = pg pts * 1.2
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toHaveTextContent('36.00')
  })

  it('a window change keeps the typed but unrun text, the last chart and a "updating" note while it loads', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    type('ast / tov')
    let resolve!: (b: Board) => void
    getStatLeaderboard.mockImplementationOnce(() => new Promise<Board>((r) => { resolve = r }))
    fireEvent.click(seg('Window', 'Last 10'))
    expect(await screen.findByText('Updating…')).toBeInTheDocument()
    expect(screen.getByLabelText('Formula')).toHaveValue('ast / tov')
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toBeInTheDocument()
    await act(async () => resolve(board(rows(), { window: 'LAST_10' })))
    await waitFor(() => expect(screen.queryByText('Updating…')).not.toBeInTheDocument())
    expect(screen.getByLabelText('Formula')).toHaveValue('ast / tov')
    expect(screen.getByRole('region', { name: 'Ranked chart' }).querySelector('figcaption')).toHaveTextContent('last 10 games')
  })

  it('standardize shows each input\'s n, mean and SD, and z beside the raw value', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    // qualified pts: 10, 20, 30 -> mean 20, population SD sqrt(200/3) = 8.165
    expect(within(screen.getByRole('list', { name: 'Standardization groups' })).getByText('PTS: n 3, mean 20.00, SD 8.16')).toBeInTheDocument()
    expect(screen.getByText('standardized')).toBeInTheDocument()
    const z = (30 - 20) / Math.sqrt(200 / 3)
    expect(screen.getByRole('region', { name: 'Calculator results' })).toHaveTextContent(`z ${z.toFixed(2)}`)
    expect(chartNames()).toEqual(['Cy', 'Bob', 'Ann'])
    // no mixed-unit warning in standardized mode
    expect(screen.queryByRole('note', { name: 'Formula warnings' })).not.toBeInTheDocument()
  })

  it('usg / tov with standardize on shows the error and keeps the previous chart', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    type('usg / tov')
    run()
    expect(screen.getByRole('alert')).toHaveTextContent('With standardize on, dividing by a stat isn\'t meaningful')
    expect(screen.getByText('previous formula')).toBeInTheDocument()
    expect(chartNames()).toEqual(['Cy', 'Bob', 'Ann'])
    expect(where()).toContain('f=pts')
  })

  it('turning standardize on for a formula that divides by a stat keeps the previous result', async () => {
    await show('/leagues/L25/stats/calculator?f=usg+%2F+tov')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Standardize inputs' }))
    expect(screen.getByRole('alert')).toHaveTextContent('With standardize on')
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toBeInTheDocument()
    expect(screen.getByText('previous formula')).toBeInTheDocument()
  })

  it('when every player is excluded it says why and draws no chart', async () => {
    const zeroTov = rows().map((r) => ({ ...r, stats: win({ pg: { tov: 0 }, adv: { usg: rate(20) } }) }))
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov', board(zeroTov))
    expect(await screen.findByText(/No player has a computable value for AST ÷ TO/)).toBeInTheDocument()
    expect(screen.getByText(/0 turnovers, so ÷ TO is undefined/, { selector: 'p.muted' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Calculator results' })).not.toBeInTheDocument()
  })

  it('lists the players it could not compute with the reason, never as 0', async () => {
    const mixed = rows()
    mixed[0] = { ...mixed[0], stats: win({ pg: { tov: 0, ast: 10 }, adv: { usg: rate(20) } }) }
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov', board(mixed))
    const list = await screen.findByRole('region', { name: 'Couldn’t compute' })
    expect(list).toHaveTextContent('0 turnovers, so ÷ TO is undefined (1)')
    expect(within(list).getByRole('link', { name: 'Ann' })).toBeInTheDocument()
    expect(chartNames()).not.toContain('Ann')
    expect(document.body.textContent).not.toMatch(/NaN|Infinity/)
  })

  it('names the missing input\'s own reason for a rate that has none', async () => {
    const r = rows()
    r[1] = { ...r[1], stats: win({ pg: { tov: 3 }, adv: { usg: rate(null, 'NO_TEAM_ROW') } }) }
    await show('/leagues/L25/stats/calculator?f=usg', board(r))
    const list = await screen.findByRole('region', { name: 'Couldn’t compute' })
    expect(list).toHaveTextContent('USG: Needs team totals for his games, which we don’t have. (1)')
  })

  it('pins the player column and scrolls the table inside its own container', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    const region = await screen.findByRole('region', { name: 'Calculator results' })
    const wrap = region.querySelector('.sl-wrap')!
    expect(wrap.querySelector('thead th.sl-pin')).toBeInTheDocument()
    for (const th of wrap.querySelectorAll('tbody th')) expect(th).toHaveClass('sl-pin')
  })

  it('season links keep the calculator and the query string but drop a roster filter', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&team=LAL&own=r:1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    const link = within(screen.getByRole('navigation', { name: 'Season' })).getByRole('link', { name: '2024–25' })
    expect(link).toHaveAttribute('href', '/leagues/L24/stats/calculator?f=pts&team=LAL')
  })

  it('links Leaderboard and Calculator in a "Stats pages" nav with aria-current', async () => {
    await show()
    const nav = screen.getByRole('navigation', { name: 'Stats pages' })
    expect(within(nav).getByRole('link', { name: 'Calculator' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Leaderboard' })).toHaveAttribute('href', '/leagues/L25/stats')
  })

  it('inserts a picked stat at the cursor', async () => {
    await show()
    const input = screen.getByLabelText('Formula') as HTMLInputElement
    fireEvent.change(input, { target: { value: '() / TO' } })
    input.setSelectionRange(1, 1)
    fireEvent.change(screen.getByLabelText('Insert a stat'), { target: { value: 'usg' } })
    expect(input).toHaveValue('(USG) / TO')
    const opt = screen.getByRole('option', { name: /^VOR — .*\(fantasy\)$/ })
    expect(opt).toBeInTheDocument()
    // The lower-is-better note appears once: TO's and TOV%'s titles already say it, so it isn't appended
    // a second time (specs/032 live check, 2026-10-13).
    expect(screen.getByRole('option', { name: 'TO — Turnovers (fewer is better)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'TOV% — Turnover rate (lower is better)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /^PTSRANK — .*\(lower is better\)$/ })).toBeInTheDocument()
  })
})

describe('StatCalculator presets (US2, T020)', () => {
  const presets = () => screen.getByRole('region', { name: 'Presets' })
  const use = (name: string) => fireEvent.click(within(presets()).getByRole('button', { name: `Use the ${name} preset` }))

  it('lists the five presets when the editor is empty, each with a description', async () => {
    await show()
    expect(within(presets()).getAllByRole('listitem')).toHaveLength(5)
    expect(presets()).toHaveTextContent('Usage value')
    expect(presets()).toHaveTextContent('usage rate + vorp / turnovers')
    expect(presets()).toHaveTextContent('role, not his quality')
  })

  it('choosing one sets f, n and m, runs it, and hides the list', async () => {
    await show()
    use('Usage value')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(where()).toContain('f=%28usg+%2B+vor%29+%2F+tov')
    expect(where()).toContain('n=Usage+value')
    expect(screen.getByLabelText('Formula')).toHaveValue('(usg + vor) / tov')
    expect(chartNames()).toEqual(['Ann', 'Cy', 'Bob'])
    expect(screen.queryByRole('region', { name: 'Presets' })).not.toBeInTheDocument()
    // the brackets note stays visible with the loaded preset
    expect(screen.getByText(/Brackets were added/)).toBeInTheDocument()
  })

  it('"Stocks per 36" switches the counting mode to per 36', async () => {
    await show()
    use('Stocks per 36')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(where()).toContain('m=per36')
    expect(seg('Counting stats', 'Per 36')).toHaveAttribute('aria-pressed', 'true')
  })

  it('is reachable from a Presets button when a formula is loaded, and toggles', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    expect(screen.queryByRole('region', { name: 'Presets' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Presets' }))
    expect(presets()).toBeInTheDocument()
    use('Assist-to-turnover')
    expect(where()).toContain('f=ast+%2F+tov')
    expect(screen.queryByRole('region', { name: 'Presets' })).not.toBeInTheDocument()
  })

  it('editing the expression drops the preset label; re-running the same expression keeps it', async () => {
    await show()
    use('Assist-to-turnover')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.getByLabelText('Name (optional)')).toHaveValue('Assist-to-turnover')
    run()
    expect(where()).toContain('n=Assist-to-turnover')
    type('ast / tov + 1')
    run()
    expect(where()).toContain('f=ast+%2F+tov+%2B+1')
    expect(where()).not.toContain('n=')
    expect(screen.getByLabelText('Name (optional)')).toHaveValue('')
    expect(screen.queryByText(/Assists for every turnover/)).not.toBeInTheDocument()
  })

  it('keeps a member own name when they edit a formula that was not a preset', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&n=Mine')
    await screen.findByRole('region', { name: 'Ranked chart' })
    type('ast')
    run()
    expect(where()).toContain('n=Mine')
  })

  it('loading a preset turns standardize off when it would block the preset', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(screen.getByRole('button', { name: 'Presets' }))
    use('Assist-to-turnover')
    expect(where()).not.toContain('z=1')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('StatCalculator copy link and saved formulas (US3, T022)', () => {
  beforeEach(() => localStorage.clear())
  const panel = () => screen.getByRole('region', { name: 'Saved in this browser' })
  const items = () => within(panel()).queryAllByRole('listitem')

  function clipboard(impl: ((t: string) => Promise<void>) | null) {
    Object.defineProperty(navigator, 'clipboard', { value: impl ? { writeText: impl } : undefined, configurable: true })
  }

  it('copies the full URL with the clipboard', async () => {
    const write = vi.fn().mockResolvedValue(undefined)
    clipboard(write)
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov&m=per36')
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(await screen.findByText(/Link copied/)).toBeInTheDocument()
    expect(write).toHaveBeenCalledWith(expect.stringMatching(/\/leagues\/L25\/stats\/calculator\?f=ast\+%2F\+tov&m=per36$/))
    expect(write.mock.calls[0][0]).toMatch(/^https?:\/\//)
  })

  it('falls back to a selectable text field when the clipboard is unavailable or refuses', async () => {
    clipboard(null)
    await show('/leagues/L25/stats/calculator?f=pts')
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    const field = await screen.findByLabelText('Link to copy')
    expect(field).toHaveAttribute('readonly')
    expect((field as HTMLInputElement).value).toMatch(/\/leagues\/L25\/stats\/calculator\?f=pts$/)
    clipboard(vi.fn().mockRejectedValue(new Error('denied')))
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
    expect(await screen.findByLabelText('Link to copy')).toBeInTheDocument()
  })

  it('the saved panel says it is in this browser and not synced, and starts empty', async () => {
    await show()
    expect(panel()).toHaveTextContent('saved in this browser and is not synced to your account or other devices')
    expect(panel()).toHaveTextContent('Nothing saved here yet.')
    expect(within(panel()).getByRole('button', { name: 'Save this formula' })).toBeDisabled()
  })

  it('saves with a name, lists it, and the list survives a reload', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov&m=per36&top=9&dir=asc')
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save this formula' }))
    fireEvent.change(within(panel()).getByLabelText(/^Name \(up to 40/), { target: { value: 'My ratio' } })
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    expect(items()).toHaveLength(1)
    expect(items()[0]).toHaveTextContent('My ratio')
    expect(items()[0]).toHaveTextContent('AST ÷ TO · per 36')
    cleanup()
    getStatLeaderboard.mockClear()
    await show()
    expect(items()).toHaveLength(1)
    // loading it restores the formula, name and settings
    fireEvent.click(within(panel()).getByRole('button', { name: 'Load My ratio' }))
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(where()).toContain('f=ast+%2F+tov')
    expect(where()).toContain('n=My+ratio')
    expect(where()).toContain('m=per36')
    expect(where()).toContain('top=9')
    expect(where()).toContain('dir=asc')
    expect(screen.getByLabelText('Formula')).toHaveValue('ast / tov')
  })

  it('renames and deletes', async () => {
    localStorage.setItem('bk.statCalc.saved.v1', JSON.stringify({ v: 1, formulas: [{ id: 'a', name: 'Old', expr: 'pts', settings: {}, savedAt: '' }] }))
    await show()
    fireEvent.click(within(panel()).getByRole('button', { name: 'Rename Old' }))
    fireEvent.change(within(panel()).getByLabelText('New name'), { target: { value: 'New' } })
    fireEvent.click(within(panel()).getByRole('button', { name: 'Rename' }))
    expect(items()[0]).toHaveTextContent('New')
    expect(JSON.parse(localStorage.getItem('bk.statCalc.saved.v1')!).formulas[0].name).toBe('New')
    fireEvent.click(within(panel()).getByRole('button', { name: 'Delete New' }))
    expect(items()).toHaveLength(0)
    expect(panel()).toHaveTextContent('Nothing saved here yet.')
  })

  it('shows a stale entry as using a stat that no longer exists, and does not offer Load', async () => {
    localStorage.setItem('bk.statCalc.saved.v1', JSON.stringify({ v: 1, formulas: [{ id: 'a', name: 'Gone', expr: 'pts + vorp9', settings: {}, savedAt: '' }] }))
    await show()
    expect(items()[0]).toHaveTextContent('uses a stat that no longer exists')
    expect(within(panel()).getByRole('button', { name: 'Load Gone' })).toBeDisabled()
    expect(within(panel()).getByRole('button', { name: 'Delete Gone' })).toBeEnabled()
  })

  it('with blocked storage the list is empty, saving says it was not saved, and the page still works', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(items()).toHaveLength(0)
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save this formula' }))
    fireEvent.change(within(panel()).getByLabelText(/^Name \(up to 40/), { target: { value: 'Temp' } })
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save' }))
    expect(panel()).toHaveTextContent('Couldn’t save to this browser')
    expect(panel()).toHaveTextContent('share links still work')
    expect(items()).toHaveLength(1)
    vi.restoreAllMocks()
  })

  it('caps a saved name at 40 characters', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save this formula' }))
    const input = within(panel()).getByLabelText(/^Name \(up to 40/)
    expect(input).toHaveAttribute('maxlength', '40')
    fireEvent.change(input, { target: { value: 'x'.repeat(55) } })
    expect(input).toHaveValue('x'.repeat(40))
  })
})

describe('StatCalculator scatter (US4, T024)', () => {
  const sc = () => screen.getByRole('region', { name: 'Scatter chart' })
  const dotLabels = () => within(sc()).getAllByTestId('scatter-dot').map((d) => d.getAttribute('aria-label'))

  it('the Chart control switches to the scatter, writes chart=scatter, and the ranked chart goes', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(seg('Chart', 'Scatter'))
    expect(where()).toContain('chart=scatter')
    expect(sc()).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
    // the formula's table stays
    expect(screen.getByRole('region', { name: 'Calculator results' })).toBeInTheDocument()
    fireEvent.click(seg('Chart', 'Ranked'))
    expect(where()).not.toContain('chart=')
    expect(screen.getByRole('region', { name: 'Ranked chart' })).toBeInTheDocument()
  })

  it('plots two built-in stats with labelled axes, exact values and qualified players only', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=usg&y=pts')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(dotLabels()).toHaveLength(3)
    expect(dotLabels()).toContain('Ann: USG (percentage points) 20.0%, PTS (counts per game) 10.0')
    expect(sc()).toHaveTextContent('3 players plotted.')
    expect(sc().querySelectorAll('.sc-axis-label')[0]).toHaveTextContent('USG (percentage points)')
    expect(sc().querySelector('figcaption')).toHaveTextContent('against')
  })

  it('works with no formula at all, defaulting to USG against TS%', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(dotLabels()).toHaveLength(3)
    expect(dotLabels()[0]).toContain('USG')
    expect(dotLabels()[0]).toContain('TS%')
  })

  it('the formula can be an axis, and its values are the formula own', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&f=ast+%2F+tov&x=usg&y=f')
    await screen.findByRole('region', { name: 'Scatter chart' })
    // ast/tov: Ann 5, Bob 2, Cy 3
    expect(dotLabels()).toContain('Ann: USG (percentage points) 20.0%, AST ÷ TO (a plain ratio) 5.00')
    expect(dotLabels()).toContain('Bob: USG (percentage points) 30.0%, AST ÷ TO (a plain ratio) 2.00')
  })

  it('choosing axes writes x and y; the formula is only offered once there is one', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(within(sc()).queryByRole('option', { name: 'Your formula' })).not.toBeInTheDocument()
    fireEvent.change(within(sc()).getByLabelText('X axis'), { target: { value: 'ast' } })
    fireEvent.change(within(sc()).getByLabelText('Y axis'), { target: { value: 'pts' } })
    expect(where()).toContain('chart=scatter')
    expect(where()).toContain('x=ast')
    expect(where()).toContain('y=pts')
    type('tov')
    run()
    expect(within(sc()).getAllByRole('option', { name: 'Your formula' })).toHaveLength(2)
  })

  it('leaves out players with a missing value and says how many, never as 0', async () => {
    const r = rows()
    r[1] = { ...r[1], stats: win({ pg: { tov: 3 }, adv: { usg: rate(null, 'NO_TEAM_ROW') } }) }
    await show('/leagues/L25/stats/calculator?chart=scatter&x=usg&y=pts', board(r))
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(dotLabels()).toHaveLength(2)
    expect(sc()).toHaveTextContent('2 players plotted. 1 player is not plotted because one of the two values is missing')
  })

  it('an axis that names no stat falls back to the default and says so', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=nope&y=pts')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(sc()).toHaveTextContent('The X axis in this link (“nope”) isn’t a stat here')
    expect(dotLabels()[0]).toContain('USG')
  })

  it('a formula axis with no formula asks for one and draws nothing', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=usg&y=f')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(sc()).toHaveTextContent('the formula, and there isn’t one yet')
    expect(within(sc()).queryAllByTestId('scatter-dot')).toHaveLength(0)
  })

  it('shows the chosen player exact values in a tooltip on tap', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=usg&y=pts')
    await screen.findByRole('region', { name: 'Scatter chart' })
    fireEvent.click(within(sc()).getAllByTestId('scatter-dot').find((d) => d.getAttribute('aria-label')?.startsWith('Bob'))!)
    expect(within(sc()).getByRole('status')).toHaveTextContent('USG (percentage points): 30.0%')
    expect(within(sc()).getByRole('status')).toHaveTextContent('PTS (counts per game): 20.0')
  })
})

/** specs/032 code review: the fix pass for findings B1-B11. */
describe('StatCalculator code-review fixes', () => {
  const sc = () => screen.getByRole('region', { name: 'Scatter chart' })
  beforeEach(() => localStorage.clear())

  it('B1: a stat times a stat is an error with standardize on, and no chart is drawn', async () => {
    await show('/leagues/L25/stats/calculator?f=pts+*+ts+%2F+100&z=1')
    expect(screen.getByRole('alert')).toHaveTextContent("multiplying two stats isn't meaningful")
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
  })

  it('B1: the Efficient volume preset loads raw when standardize is on, and says so', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(screen.getByRole('button', { name: 'Presets' }))
    fireEvent.click(screen.getByRole('button', { name: 'Use the Efficient volume preset' }))
    expect(where()).not.toContain('z=1')
    expect(screen.getByText(/was turned off for Efficient volume/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('B1: loading a saved standardized product shows the error, not a chart', async () => {
    saveFormula({
      name: 'Old product', expr: 'pts * usg',
      settings: { window: 'SEASON', mode: 'perGame', top: 15, qualifiedOnly: true, standardize: true, dir: 'desc' },
    })
    await show()
    fireEvent.click(within(screen.getByRole('region', { name: 'Saved in this browser' })).getByRole('button', { name: 'Load Old product' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("multiplying two stats isn't meaningful")
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
  })

  it('B2: clicking the Calculator tab (which clears f) clears the editor and the chart', async () => {
    await show('/leagues/L25/stats/calculator?f=pts')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.getByLabelText('Formula')).toHaveValue('pts')
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Stats pages' })).getByRole('link', { name: 'Calculator' }))
    await waitFor(() => expect(screen.getByLabelText('Formula')).toHaveValue(''))
    expect(where()).toBe('/leagues/L25/stats/calculator')
    expect(screen.queryByRole('region', { name: 'Ranked chart' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Formula as read')).not.toBeInTheDocument()
  })

  it('B3: a formula with no stat, a divide by a constant 0 and an oversized number are errors', async () => {
    await show()
    type('1 + 1')
    run()
    expect(screen.getByRole('alert')).toHaveTextContent('Use at least one stat')
    type('usg / 0')
    run()
    expect(screen.getByRole('alert')).toHaveTextContent('Dividing by 0 is undefined')
    type('9'.repeat(400))
    run()
    expect(screen.getByRole('alert')).toHaveTextContent('That number is too large')
    expect(document.body.textContent).not.toMatch(/NaN|Infinity|∞/)
  })

  it('B5: a repeated warning is listed once and React sees no duplicate key', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    await show('/leagues/L25/stats/calculator?f=usg+%2B+vor+-+%28usg+%2B+vor%29')
    await screen.findByRole('region', { name: 'Ranked chart' })
    const items = within(screen.getByRole('note', { name: 'Formula warnings' })).getAllByRole('listitem').map((li) => li.textContent)
    expect(new Set(items).size).toBe(items.length)
    expect(err.mock.calls.map((c) => String(c[0])).join('')).not.toContain('same key')
    err.mockRestore()
  })

  it('B7: the standardization sentence is about players who have a value, with the count per input below', async () => {
    await show('/leagues/L25/stats/calculator?f=pts&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(screen.getByText(/the average of the players shown who have a value for it/)).toBeInTheDocument()
    expect(screen.getByText(/each input's count is listed below, out of 3 players shown/)).toBeInTheDocument()
  })

  it('B8: the table header and the scatter axis print the formula token (FPG), not the label (FP/G)', async () => {
    await show('/leagues/L25/stats/calculator?f=fp')
    await screen.findByRole('region', { name: 'Calculator results' })
    const heads = within(screen.getByRole('region', { name: 'Calculator results' })).getAllByRole('columnheader').map((h) => h.textContent)
    expect(heads.some((h) => h?.startsWith('FPG'))).toBe(true)
    expect(heads.some((h) => h?.startsWith('FP/G'))).toBe(false)
    cleanup()
    await show('/leagues/L25/stats/calculator?chart=scatter&x=fp&y=pts')
    await screen.findByRole('region', { name: 'Scatter chart' })
    expect(sc()).toHaveTextContent('FPG (')
    expect(sc()).not.toHaveTextContent('FP/G')
  })

  it('B10: toggling Ranked and back keeps the scatter axes', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=ast&y=pts')
    await screen.findByRole('region', { name: 'Scatter chart' })
    fireEvent.click(seg('Chart', 'Ranked'))
    fireEvent.click(seg('Chart', 'Scatter'))
    expect((within(sc()).getByLabelText('X axis') as HTMLSelectElement).value).toBe('ast')
    expect((within(sc()).getByLabelText('Y axis') as HTMLSelectElement).value).toBe('pts')
  })

  it('B10: with no formula the formula axis reads "Your formula (none yet)", not GP', async () => {
    await show('/leagues/L25/stats/calculator?chart=scatter&x=usg&y=f')
    await screen.findByRole('region', { name: 'Scatter chart' })
    const y = within(sc()).getByLabelText('Y axis') as HTMLSelectElement
    expect(y.value).toBe('f')
    expect(y.selectedOptions[0].textContent).toBe('Your formula (none yet)')
    expect(y.selectedOptions[0].disabled).toBe(true)
  })

  it('B11: the formula input is marked invalid and points at the error message', async () => {
    await show()
    const input = screen.getByLabelText('Formula')
    expect(input).not.toHaveAttribute('aria-invalid')
    type('usg +')
    run()
    const alert = screen.getByRole('alert')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby')).toBe(alert.id)
    expect(alert.id).not.toBe('')
  })
})

/** specs/032 T032 (US5): Spread, Change and Breakdown, each only when it fits. */
describe('StatCalculator US5 charts', () => {
  beforeEach(() => localStorage.clear())

  /** Six qualified players. pts + ast: Ann 20, Bob 26, Cy 33, Dee 10, Eve 15, Fay 40. */
  const six = () => [
    row('1', { name: 'Ann', positions: ['PG'], valueOverReplacement: 10, stats: win({ pg: { tov: 2, pts: 10, ast: 10 }, adv: { usg: rate(20) } }) }),
    row('2', { name: 'Bob', positions: ['C'], valueOverReplacement: 6, stats: win({ pg: { tov: 3, pts: 20, ast: 6 }, adv: { usg: rate(30) } }) }),
    row('3', { name: 'Cy', positions: ['PG', 'SG'], valueOverReplacement: 4, stats: win({ pg: { tov: 1, pts: 30, ast: 3 }, adv: { usg: rate(10) } }) }),
    row('4', { name: 'Dee', positions: ['C'], valueOverReplacement: 2, stats: win({ pg: { tov: 4, pts: 5, ast: 5 }, adv: { usg: rate(25) } }) }),
    row('5', { name: 'Eve', positions: ['SF'], valueOverReplacement: 1, stats: win({ pg: { tov: 5, pts: 10, ast: 5 }, adv: { usg: rate(15) } }) }),
    row('6', { name: 'Fay', positions: ['PF'], valueOverReplacement: 8, stats: win({ pg: { tov: 2, pts: 30, ast: 10 }, adv: { usg: rate(35) } }) }),
  ]
  /** The last-10 window: Ann and Fay are up in the current view, Bob down; Eve is gone; Gus is only here; Dee is not qualified here. */
  const POS: Record<string, string[]> = { '1': ['PG'], '2': ['C'], '3': ['PG', 'SG'], '4': ['C'], '5': ['SF'], '6': ['PF'] }
  const recent = () => [
    row('1', { name: 'Ann', positions: POS['1'], stats: win({ pg: { tov: 2, pts: 8, ast: 6 } }) }),
    row('2', { name: 'Bob', positions: POS['2'], stats: win({ pg: { tov: 3, pts: 25, ast: 7 } }) }),
    row('3', { name: 'Cy', positions: POS['3'], stats: win({ pg: { tov: 1, pts: 30, ast: 3 } }) }),
    row('4', { name: 'Dee', positions: POS['4'], qualified: false, reason: 'NOT_QUALIFIED', stats: win({ pg: { tov: 4, pts: 5, ast: 5 } }) }),
    row('6', { name: 'Fay', positions: POS['6'], stats: win({ pg: { tov: 2, pts: 20, ast: 10 } }) }),
    row('7', { name: 'Gus', stats: win({ pg: { tov: 2, pts: 9, ast: 9 } }) }),
  ]
  const boards = (opts: { seasons?: Board['seasons'] } = {}) => {
    getStatLeaderboard.mockImplementation((league: string, w: string) => {
      const base = { window: w as Board['window'], ...(opts.seasons ? { seasons: opts.seasons } : {}) }
      if (league === 'L24') return Promise.resolve(board(recent(), { ...base, season: 2024 }))
      return Promise.resolve(board(w === 'SEASON' ? six() : recent(), base))
    })
  }
  const showSix = async (url: string, opts?: { seasons?: Board['seasons'] }) => {
    boards(opts)
    mount(url)
    await screen.findByLabelText('Formula')
  }
  const picker = (name: string) => seg('Chart', name)
  const why = () => document.getElementById('sc-chart-why')?.textContent ?? ''

  it('with no formula, Spread, Change and Breakdown are disabled with the reason in words beside the picker', async () => {
    await showSix('/leagues/L25/stats/calculator')
    for (const n of ['Spread', 'Change', 'Breakdown']) {
      expect(picker(n)).toHaveAttribute('aria-disabled', 'true')
      expect(picker(n)).toHaveAttribute('aria-describedby', 'sc-chart-why')
    }
    expect(picker('Ranked')).not.toHaveAttribute('aria-disabled')
    expect(picker('Scatter')).not.toHaveAttribute('aria-disabled')
    expect(why()).toContain('Spread, Change and Breakdown: Enter a formula first.')
    fireEvent.click(picker('Spread'))
    expect(where()).not.toContain('chart=')
  })

  it('each unavailable chart says why: too few values, standardize on, a product of stats', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov') // the 3 qualified players of rows()
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(picker('Spread')).toHaveAttribute('aria-disabled', 'true')
    expect(why()).toContain('Spread: Spread needs at least 5 players with a value; 3 have one.')
    expect(why()).toContain('Breakdown: Breakdown needs a sum of terms; this formula divides by a stat.')
    expect(picker('Change')).not.toHaveAttribute('aria-disabled')
    cleanup()
    getStatLeaderboard.mockReset()
    await showSix('/leagues/L25/stats/calculator?f=usg+%2B+vor&z=1')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(picker('Change')).toHaveAttribute('aria-disabled', 'true')
    expect(why()).toContain('Change: Change compares two windows')
    expect(picker('Spread')).not.toHaveAttribute('aria-disabled')
    expect(picker('Breakdown')).not.toHaveAttribute('aria-disabled')
  })

  it('a link to a chart that does not fit says so and draws nothing misleading', async () => {
    await show('/leagues/L25/stats/calculator?f=ast+%2F+tov&chart=parts')
    await screen.findByRole('region', { name: 'Calculator results' })
    expect(screen.queryByRole('region', { name: 'Breakdown chart' })).not.toBeInTheDocument()
    expect(screen.getByText(/This chart isn’t shown: Breakdown needs a sum of terms/)).toBeInTheDocument()
  })

  it('Spread: prints the median and quartiles of the formula over the players shown', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=spread')
    const chart = await screen.findByRole('region', { name: 'Spread chart' })
    // 10, 15, 20, 26, 33, 40 -> q1 16.25, median 23, q3 31.25
    expect(chart).toHaveTextContent('25th 16.25 · median 23.00 · 75th 31.25')
    expect(within(chart).getAllByTestId('spread-dot')).toHaveLength(6)
    expect(chart).toHaveTextContent('Top 6 in the ranking')
  })

  it('Spread split by position: one strip per position on one axis, a two-position player in both, and a position filter disables the split', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=spread&split=pos')
    const chart = await screen.findByRole('region', { name: 'Spread chart' })
    const groups = within(chart).getAllByTestId('spread-group')
    expect(groups.map((g) => g.querySelector('text')?.textContent)).toEqual(['PG · 2 players', 'SG · 1 player', 'SF · 1 player', 'PF · 1 player', 'C · 2 players'])
    expect(chart).toHaveTextContent('appears in each of his positions’ strips')
    expect(where()).toContain('split=pos')
    cleanup()
    getStatLeaderboard.mockReset()
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=spread&split=pos&pos=C')
    // only 2 Cs: the chart itself is unavailable for too few values; the split reason sits beside its control
    await screen.findByLabelText('Formula')
    expect(screen.queryByRole('region', { name: 'Spread chart' })).not.toBeInTheDocument()
    expect(screen.getByText(/This chart isn’t shown: Spread needs at least 5 players/)).toBeInTheDocument()
    expect(document.getElementById('sc-split-why')).toHaveTextContent('Split by position needs the position filter set to All')
    expect(seg('Split by position', 'By position')).toHaveAttribute('aria-disabled', 'true')
  })

  it('Breakdown: the parts of usg + vor add up to the result shown beside each bar', async () => {
    await showSix('/leagues/L25/stats/calculator?f=usg+%2B+vor&chart=parts')
    const chart = await screen.findByRole('region', { name: 'Breakdown chart' })
    const rowsEl = within(within(chart).getByRole('list', { name: /what each term adds/ })).getAllByRole('listitem')
    // usg + vor: Fay 43, Ann 30, Bob 36, Cy 14, Dee 27, Eve 16 -> ranked Fay, Bob, Ann, Dee, Eve, Cy
    expect(rowsEl.map((li) => li.querySelector('a')?.textContent)).toEqual(['Fay', 'Bob', 'Ann', 'Dee', 'Eve', 'Cy'])
    const ann = rowsEl[2]
    expect(ann).toHaveAttribute('aria-label', 'Ann: 30.00 = USG 20.00; VOR 10.00')
    for (const li of rowsEl) {
      const nums = [...(li.getAttribute('aria-label') ?? '').matchAll(/(−?[\d,.]+)(?:;|$)/g)].map((m) => Number(m[1].replace('−', '-').replace(',', '')))
      const total = Number((li.getAttribute('aria-label') ?? '').match(/: (−?[\d,.]+) =/)?.[1])
      expect(nums.reduce((a, b) => a + b, 0)).toBeCloseTo(total, 6)
    }
  })

  it('Change: the second request exists only while Change is shown, and a mode or sort change makes none', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast')
    await screen.findByRole('region', { name: 'Ranked chart' })
    expect(getStatLeaderboard.mock.calls.map((c) => c.join(':'))).toEqual(['L25:SEASON'])
    fireEvent.click(picker('Change'))
    await screen.findByRole('region', { name: 'Change chart' })
    await screen.findByText(/players have a value in both views/)
    expect(getStatLeaderboard.mock.calls.map((c) => c.join(':'))).toEqual(['L25:SEASON', 'L25:LAST_10'])
    fireEvent.click(seg('Counting stats', 'Per 36'))
    fireEvent.click(seg('Sort changes', 'Biggest fall'))
    fireEvent.click(seg('Counting stats', 'Per game'))
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
    fireEvent.click(picker('Ranked'))
    fireEvent.click(picker('Spread'))
    fireEvent.click(picker('Breakdown'))
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
  })

  /** The titled rows of the Change chart, in order. */
  const dumbbells = (chart: HTMLElement, title: RegExp) => within(within(chart).getByRole('list', { name: title })).getAllByRole('listitem')
  const names = (lis: HTMLElement[]) => lis.map((x) => x.querySelector('a')?.textContent)

  it('Change: shows both exact values and the signed change (recent minus older), sorts by rise or fall, and lists who is missing from the comparison, by reason', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Full season → Last 10/ })
    // pts+ast, season -> last 10: Ann 20 -> 14, Bob 26 -> 32, Cy 33 -> 33, Fay 40 -> 30 (Dee is unqualified in the last 10).
    // C1: change = last 10 minus season, so Bob is the biggest rise and Fay the biggest fall.
    const li = () => dumbbells(chart, /Full season → Last 10/)
    expect(names(li())).toEqual(['Bob', 'Cy', 'Ann', 'Fay'])
    expect(li()[0]).toHaveTextContent('+6.00')
    expect(li()[0]).toHaveTextContent('26.00 → 32.00')
    expect(li()[3]).toHaveTextContent('−10.00')
    expect(li()[3]).toHaveTextContent('40.00 → 30.00')
    fireEvent.click(seg('Sort changes', 'Biggest fall'))
    expect(names(li())[0]).toBe('Fay')
    expect(where()).toContain('chg=down')
    // C5: the join starts from the players shown now. Dee is unqualified in the last 10 and Eve is not in it;
    // Gus is only in the last-10 view, which is not "left out": he was never in the player list.
    expect(chart).toHaveTextContent('Left out of Change: 2 of the players shown have no value in both views.')
    expect(chart).toHaveTextContent('Filtered out or not qualified in the Last 10 view (1): Dee')
    expect(chart).toHaveTextContent('Not in the Last 10 data (1): Eve')
    expect(chart).not.toHaveTextContent('Gus')
  })

  it('Change (C1 ordering): a player at 10 over the season and 25 over the last 10 is +15 and sorts first under Biggest rise; the slump sorts first under Biggest fall', async () => {
    const mk = (id: string, name: string, pts: number) => row(id, { name, stats: win({ pg: { tov: 2, pts, ast: 5 } }) })
    const season = [mk('1', 'Hot', 10), mk('2', 'Cold', 25), mk('3', 'Mid', 15), mk('4', 'Mid2', 16), mk('5', 'Mid3', 17)]
    const last10 = [mk('1', 'Hot', 25), mk('2', 'Cold', 8), mk('3', 'Mid', 15), mk('4', 'Mid2', 16), mk('5', 'Mid3', 17)]
    getStatLeaderboard.mockImplementation((_l: string, w: string) => Promise.resolve(board(w === 'SEASON' ? season : last10, { window: w as Board['window'] })))
    mount('/leagues/L25/stats/calculator?f=pts&chart=change')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Full season → Last 10 · biggest rise first/ })
    const li = () => dumbbells(chart, /Full season → Last 10/)
    expect(names(li())[0]).toBe('Hot')
    expect(li()[0]).toHaveTextContent('+15.00')
    expect(li()[0]).toHaveTextContent('10.00 → 25.00')
    expect(names(li())[4]).toBe('Cold')
    expect(li()[4]).toHaveTextContent('−17.00')
    fireEvent.click(seg('Sort changes', 'Biggest fall'))
    expect(names(li())[0]).toBe('Cold')
    expect(names(li())[4]).toBe('Hot')
  })

  it('Change (C1): the same orientation when the window is the recent one (Last 10 against the full season) and for Last 5 against Last 10', async () => {
    const mk = (id: string, name: string, pts: number) => row(id, { name, stats: win({ pg: { tov: 2, pts, ast: 5 } }) })
    const by: Record<string, number> = { SEASON: 10, LAST_10: 18, LAST_5: 26 }
    getStatLeaderboard.mockImplementation((_l: string, w: string) =>
      Promise.resolve(board([mk('1', 'Hot', by[w]), mk('2', 'A', 15), mk('3', 'B', 16), mk('4', 'C', 17), mk('5', 'D', 18)], { window: w as Board['window'] })),
    )
    mount('/leagues/L25/stats/calculator?f=pts&chart=change&w=LAST_10')
    let chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Full season → Last 10/ })
    expect(dumbbells(chart, /Full season → Last 10/)[0]).toHaveTextContent('+8.00')
    expect(dumbbells(chart, /Full season → Last 10/)[0]).toHaveTextContent('10.00 → 18.00')
    cleanup()
    getStatLeaderboard.mockClear()
    mount('/leagues/L25/stats/calculator?f=pts&chart=change&w=LAST_5&cmp=LAST_10')
    chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Last 10 → Last 5/ })
    expect(dumbbells(chart, /Last 10 → Last 5/)[0]).toHaveTextContent('+8.00')
    expect(dumbbells(chart, /Last 10 → Last 5/)[0]).toHaveTextContent('18.00 → 26.00')
  })

  it('Change (C1): against the previous season, this season is the recent end', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change&cmp=PREV')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /2024–25 full season → 2025–26 full season/ })
    // L24 serves recent(): Ann 14, Bob 32, Cy 33, Fay 30. This season (six): Ann 20, Bob 26, Cy 33, Fay 40.
    const li = dumbbells(chart, /2024–25 full season → 2025–26 full season/)
    expect(names(li)).toEqual(['Fay', 'Ann', 'Cy', 'Bob'])
    expect(li[0]).toHaveTextContent('+10.00')
    expect(li[0]).toHaveTextContent('30.00 → 40.00')
    // C5: names the season in the reason
    expect(chart).toHaveTextContent('Not in the 2024–25 full season data (1): Eve')
  })

  it('Change (C2): Totals and a formula that reads GP disable Change, each with its reason in words', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change')
    await screen.findByRole('region', { name: 'Change chart' })
    expect(picker('Change')).not.toHaveAttribute('aria-disabled')
    fireEvent.click(seg('Counting stats', 'Totals'))
    expect(picker('Change')).toHaveAttribute('aria-disabled', 'true')
    expect(why()).toContain('totals over different numbers of games can’t be compared')
    expect(screen.queryByRole('region', { name: 'Change chart' })).not.toBeInTheDocument()
    expect(screen.getByText(/This chart isn’t shown: Change compares two views, and totals/)).toBeInTheDocument()
    cleanup()
    getStatLeaderboard.mockReset()
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+gp&chart=change')
    await screen.findByLabelText('Formula')
    expect(picker('Change')).toHaveAttribute('aria-disabled', 'true')
    expect(why()).toContain('games played (GP)')
    expect(screen.queryByRole('region', { name: 'Change chart' })).not.toBeInTheDocument()
  })

  it('Change (C4): while a window switch loads, no rows are drawn and the chart says Updating…, never "Full season → Full season"', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Full season → Last 10/ })
    const seasonCalls = () => getStatLeaderboard.mock.calls.filter((c) => c[1] === 'SEASON').length
    expect(seasonCalls()).toBe(1)
    let release!: (b: Board) => void
    getStatLeaderboard.mockImplementation((_l: string, w: string) =>
      w === 'LAST_10'
        ? new Promise<Board>((res) => {
            release = res
          })
        : Promise.resolve(board(six(), { window: 'SEASON' })),
    )
    fireEvent.click(seg('Window', 'Last 10'))
    await waitFor(() => expect(release).toBeDefined())
    const held = screen.getByRole('region', { name: 'Change chart' })
    expect(held).toHaveTextContent('Updating…')
    expect(within(held).queryByRole('list')).not.toBeInTheDocument()
    expect(held).not.toHaveTextContent('Full season → Full season')
    expect(held).not.toHaveTextContent('0.00')
    // the comparison is not fetched until the main board is current
    expect(seasonCalls()).toBe(1)
    await act(async () => release(board(recent(), { window: 'LAST_10' })))
    const done = await screen.findByRole('region', { name: 'Change chart' })
    await within(done).findByRole('list', { name: /Full season → Last 10/ })
    expect(seasonCalls()).toBe(2)
  })

  it('Change: a position filter applies to both views', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change&pos=C')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByRole('list', { name: /Full season → Last 10/ })
    // Bob and Dee are the Cs; Dee is unqualified in the last 10, so only Bob is in both views
    expect(names(dumbbells(chart, /Full season → Last 10/))).toEqual(['Bob'])
  })

  it('Change: the previous season fetches that league with the same window; it is disabled when there is no earlier season', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change&cmp=PREV')
    const chart = await screen.findByRole('region', { name: 'Change chart' })
    await within(chart).findByText(/players have a value in both views/)
    expect(getStatLeaderboard).toHaveBeenCalledWith('L24', 'SEASON')
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
    cleanup()
    getStatLeaderboard.mockReset()
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change&cmp=PREV', { seasons: [{ season: 2025, sleeperLeagueId: 'L25', hasGames: true }] })
    await screen.findByLabelText('Compare with')
    expect(seg('Compare with', 'Previous season')).toHaveAttribute('aria-disabled', 'true')
    expect(document.getElementById('sc-cmp-why')).toHaveTextContent('no earlier stored season')
    expect(screen.queryByRole('region', { name: 'Change chart' })).not.toBeInTheDocument()
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('Change: from a recent window the comparison defaults to the full season, and the window itself is not offered', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast&chart=change&w=LAST_10')
    await screen.findByRole('region', { name: 'Change chart' })
    expect(seg('Compare with', 'Full season')).toHaveAttribute('aria-pressed', 'true')
    expect(within(screen.getByRole('group', { name: 'Compare with' })).queryByRole('button', { name: 'Last 10' })).not.toBeInTheDocument()
    await waitFor(() => expect(getStatLeaderboard).toHaveBeenCalledWith('L25', 'SEASON'))
  })

  it('the chart, split, comparison and sort round-trip through the link', async () => {
    await showSix('/leagues/L25/stats/calculator?f=pts+%2B+ast')
    await screen.findByRole('region', { name: 'Ranked chart' })
    fireEvent.click(picker('Change'))
    await screen.findByRole('region', { name: 'Change chart' })
    fireEvent.click(seg('Compare with', 'Last 5'))
    fireEvent.click(seg('Sort changes', 'Biggest fall'))
    expect(where()).toContain('chart=change')
    expect(where()).toContain('cmp=LAST_5')
    expect(where()).toContain('chg=down')
    const link = where()
    cleanup()
    getStatLeaderboard.mockReset()
    await showSix(link)
    await screen.findByRole('region', { name: 'Change chart' })
    expect(seg('Compare with', 'Last 5')).toHaveAttribute('aria-pressed', 'true')
    expect(seg('Sort changes', 'Biggest fall')).toHaveAttribute('aria-pressed', 'true')
    expect(picker('Change')).toHaveAttribute('aria-pressed', 'true')
  })
})
