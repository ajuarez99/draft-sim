import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CommissionerScoresPanel, { buildScores, parsePoints } from './CommissionerScoresPanel'
import type { ScoreOverrideView } from '../api'

const getScoreOverride = vi.fn()
const saveScoreOverride = vi.fn()
const clearScoreOverride = vi.fn()
vi.mock('../api', () => ({
  getScoreOverride: (...a: unknown[]) => getScoreOverride(...a),
  saveScoreOverride: (...a: unknown[]) => saveScoreOverride(...a),
  clearScoreOverride: (...a: unknown[]) => clearScoreOverride(...a),
}))

function view(over: Partial<ScoreOverrideView> = {}): ScoreOverrideView {
  return {
    week: 1, isFinal: true, canEdit: true, commissionerKnown: true, source: 'FEED', enteredAt: null,
    teams: [
      { rosterId: 1, teamName: 'Alpha', feedPoints: 227, inUsePoints: 227, overridePoints: null },
      { rosterId: 2, teamName: 'Bravo', feedPoints: 233, inUsePoints: 233, overridePoints: null },
    ],
    checks: {
      totals: { status: 'UNAVAILABLE', throughWeek: null, feedWeeks: [], rows: [] },
      record: { status: 'UNAVAILABLE', throughWeek: null, feedWeeks: [], rows: [] },
    },
    ...over,
  }
}

beforeEach(() => {
  getScoreOverride.mockReset()
  saveScoreOverride.mockReset()
  clearScoreOverride.mockReset()
})

describe('parsePoints', () => {
  it('accepts whole numbers and up to two decimals', () => {
    expect(parsePoints('239')).toBe(239)
    expect(parsePoints('239.5')).toBe(239.5)
    expect(parsePoints(' 239.25 ')).toBe(239.25)
    expect(parsePoints('.5')).toBe(0.5)
    expect(parsePoints('0')).toBe(0)
  })

  it('refuses blank, negative, three decimals, text and absurd values, never rounding', () => {
    for (const bad of ['', '  ', '-1', '239.005', '239.', 'abc', '1e3', '1,5', '1000000']) {
      expect(parsePoints(bad), bad).toBeNull()
    }
  })
})

describe('buildScores', () => {
  const teams = [{ rosterId: 1, teamName: 'Alpha' }, { rosterId: 2, teamName: 'Bravo' }]

  it('builds one entry per team', () => {
    expect(buildScores(teams, { 1: '239', 2: '231.5' })).toEqual({
      scores: [{ rosterId: 1, points: 239 }, { rosterId: 2, points: 231.5 }],
    })
  })

  it('names the first team with a bad box', () => {
    expect(buildScores(teams, { 1: '239', 2: '231.555' })).toEqual({
      error: 'Bravo: enter a score of 0 or more with at most two decimals.',
    })
    expect(buildScores(teams, { 1: '-5', 2: '1' })).toEqual({
      error: 'Alpha: enter a score of 0 or more with at most two decimals.',
    })
    expect(buildScores(teams, { 1: '1' })).toHaveProperty('error')
  })
})

describe('CommissionerScoresPanel', () => {
  it('renders nothing for a reader who cannot edit', async () => {
    getScoreOverride.mockResolvedValue(view({ canEdit: false }))
    const { container } = render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    await waitFor(() => expect(getScoreOverride).toHaveBeenCalledWith('L0', 1))
    expect(container.textContent).toBe('')
  })

  it('saves what was typed against the given league id and reports the change', async () => {
    getScoreOverride.mockResolvedValue(view())
    saveScoreOverride.mockResolvedValue(view({ source: 'COMMISSIONER' }))
    const onChanged = vi.fn()
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={onChanged} />)
    const alpha = await screen.findByLabelText('Alpha score for week 1')
    expect(alpha).toHaveValue('')
    expect(alpha).toHaveAttribute('placeholder', '227.00')
    await userEvent.type(alpha, '239')
    await userEvent.type(screen.getByLabelText('Bravo score for week 1'), '233')
    await userEvent.click(screen.getByRole('button', { name: 'Save week 1' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
    expect(saveScoreOverride).toHaveBeenCalledWith('L0', 1, [
      { rosterId: 1, points: 239 },
      { rosterId: 2, points: 233 },
    ])
  })

  it('does not call the server with an invalid box, and says why', async () => {
    getScoreOverride.mockResolvedValue(view())
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    const alpha = await screen.findByLabelText('Alpha score for week 1')
    await userEvent.type(alpha, '12.345')
    await userEvent.type(screen.getByLabelText('Bravo score for week 1'), '233')
    await userEvent.click(screen.getByRole('button', { name: 'Save week 1' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Alpha: enter a score of 0 or more')
    expect(saveScoreOverride).not.toHaveBeenCalled()
  })

  it('refuses a save while any box is blank, naming the teams', async () => {
    getScoreOverride.mockResolvedValue(view())
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    await userEvent.type(await screen.findByLabelText('Alpha score for week 1'), '239')
    await userEvent.click(screen.getByRole('button', { name: 'Save week 1' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Still blank: Bravo')
    expect(saveScoreOverride).not.toHaveBeenCalled()
  })

  it('prefills only scores the commissioner already entered', async () => {
    getScoreOverride.mockResolvedValue(
      view({
        source: 'COMMISSIONER',
        teams: [
          { rosterId: 1, teamName: 'Alpha', feedPoints: 227, inUsePoints: 239, overridePoints: 239 },
          { rosterId: 2, teamName: 'Bravo', feedPoints: 233, inUsePoints: 231.5, overridePoints: 231.5 },
        ],
      }),
    )
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    expect(await screen.findByLabelText('Alpha score for week 1')).toHaveValue('239.00')
    expect(screen.getByLabelText('Bravo score for week 1')).toHaveValue('231.50')
  })

  it('enables Clear whenever override rows exist, even if the week resolved to the feed', async () => {
    getScoreOverride.mockResolvedValue(
      view({
        source: 'FEED',
        teams: [
          { rosterId: 1, teamName: 'Alpha', feedPoints: 227, inUsePoints: 227, overridePoints: 239 },
          { rosterId: 2, teamName: 'Bravo', feedPoints: 233, inUsePoints: 233, overridePoints: null },
        ],
      }),
    )
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    expect(await screen.findByRole('button', { name: 'Clear entered scores' })).toBeEnabled()
  })

  it('names the weeks still on the public feed beside a mismatch', async () => {
    getScoreOverride.mockResolvedValue(
      view({
        checks: {
          totals: {
            status: 'MISMATCH', throughWeek: 3, feedWeeks: [2, 3],
            rows: [{ rosterId: 1, teamName: 'Alpha', inUseTotal: 1, sleeperTotal: 2 }],
          },
          record: { status: 'UNAVAILABLE', throughWeek: 3, feedWeeks: [2, 3], rows: [] },
        },
      }),
    )
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    expect(await screen.findByText(/Weeks 2, 3 still use the public feed, so a mismatch is expected/)).toBeInTheDocument()
  })

  it('shows a failed server answer as the error', async () => {
    getScoreOverride.mockResolvedValue(view())
    saveScoreOverride.mockRejectedValue(new Error('week 1 is not final yet'))
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    await userEvent.type(await screen.findByLabelText('Alpha score for week 1'), '227')
    await userEvent.type(screen.getByLabelText('Bravo score for week 1'), '233')
    await userEvent.click(await screen.findByRole('button', { name: 'Save week 1' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('week 1 is not final yet')
  })

  it('highlights mismatched checks with both numbers, and says a match plainly', async () => {
    getScoreOverride.mockResolvedValue(
      view({
        source: 'COMMISSIONER',
        teams: [
          { rosterId: 1, teamName: 'Alpha', feedPoints: 227, inUsePoints: 239, overridePoints: 239 },
          { rosterId: 2, teamName: 'Bravo', feedPoints: 233, inUsePoints: 231, overridePoints: 231 },
        ],
        checks: {
          totals: {
            status: 'MISMATCH', throughWeek: 2, feedWeeks: [2],
            rows: [
              { rosterId: 1, teamName: 'Alpha', inUseTotal: 439, sleeperTotal: 439 },
              { rosterId: 2, teamName: 'Bravo', inUseTotal: 403, sleeperTotal: 421 },
            ],
          },
          record: {
            status: 'MATCH', throughWeek: 2, feedWeeks: [],
            rows: [{ rosterId: 1, teamName: 'Alpha', computed: '2-0', sleeper: '2-0' }],
          },
        },
      }),
    )
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    expect(await screen.findByText('Bravo: the app has 403.00, Sleeper has 421.00')).toBeInTheDocument()
    expect(screen.queryByText(/Alpha: the app has/)).not.toBeInTheDocument()
    expect(screen.getByText(/Win-loss records through week 2: every team matches Sleeper\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear entered scores' })).toBeEnabled()
  })

  it('clears entered scores', async () => {
    getScoreOverride.mockResolvedValue(view({ source: 'COMMISSIONER',
        teams: [
          { rosterId: 1, teamName: 'Alpha', feedPoints: 227, inUsePoints: 239, overridePoints: 239 },
          { rosterId: 2, teamName: 'Bravo', feedPoints: 233, inUsePoints: 231, overridePoints: 231 },
        ],
    }))
    clearScoreOverride.mockResolvedValue(view())
    const onChanged = vi.fn()
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={onChanged} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Clear entered scores' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(clearScoreOverride).toHaveBeenCalledWith('L0', 1)
  })

  it('cannot save a week that is not final', async () => {
    getScoreOverride.mockResolvedValue(view({ isFinal: false }))
    render(<CommissionerScoresPanel leagueSleeperId="L0" week={1} onChanged={() => {}} />)
    expect(await screen.findByText(/still being played/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save week 1' })).toBeDisabled()
  })
})
