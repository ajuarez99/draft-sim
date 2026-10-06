import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecapCard from './RecapCard'
import type { RecapResponse } from '../api'

const fetchRecap = vi.fn()
vi.mock('../api', () => ({
  fetchRecap: (...args: unknown[]) => fetchRecap(...args),
}))

function resp(over: Partial<RecapResponse> = {}): RecapResponse {
  return {
    state: 'READY',
    season: 2026,
    week: 3,
    model: 'claude-haiku-4-5',
    generatedAt: '2026-10-06T19:02:11Z',
    revision: 1,
    revisionReason: null,
    stale: false,
    headline: 'Master Bates hits 188.48 and stays perfect',
    sections: [
      { title: 'Top score', body: 'Master Bates scored 188.48 in week 3.', cites: ['/matchups/3'] },
      { title: 'Unlucky', body: 'Khatt Stafford lost anyway.', cites: ['/awards/1'] },
      { title: 'Margin', body: 'The margin was 67.58.', cites: ['/matchups/3'] },
    ],
    failureReason: null,
    ...over,
  }
}

/** Moves fake time forward and lets the promise chain settle between ticks. */
const tick = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms) })

beforeEach(() => {
  vi.useFakeTimers()
  fetchRecap.mockReset()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('RecapCard', () => {
  it.each(['FEATURE_OFF', 'NOT_ENTITLED'] as const)('%s renders nothing, not even an empty container', async (state) => {
    fetchRecap.mockResolvedValue(resp({ state, headline: null, sections: null, model: null }))
    const { container } = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(fetchRecap).toHaveBeenCalledTimes(1)
    expect(container.innerHTML).toBe('')
  })

  it('waits 1 s on screen before the first fetch', async () => {
    fetchRecap.mockResolvedValue(resp())
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(999)
    expect(fetchRecap).not.toHaveBeenCalled()
    await tick(1)
    expect(fetchRecap).toHaveBeenCalledTimes(1)
    expect(fetchRecap.mock.calls[0][0]).toBe('L1')
    expect(fetchRecap.mock.calls[0][1]).toBe(3)
  })

  it('a week change inside 1 s fires no fetch for the week that was left', async () => {
    fetchRecap.mockResolvedValue(resp())
    const { rerender } = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(600)
    rerender(<RecapCard sleeperLeagueId="L1" week={4} />)
    await tick(600)
    expect(fetchRecap).not.toHaveBeenCalled()
    await tick(400)
    expect(fetchRecap).toHaveBeenCalledTimes(1)
    expect(fetchRecap.mock.calls[0][1]).toBe(4)
  })

  it('does not fetch after an unmount, and aborts a fetch in flight', async () => {
    fetchRecap.mockImplementation(() => new Promise(() => {}))
    const { unmount } = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    const signal = fetchRecap.mock.calls[0][2] as AbortSignal
    expect(signal.aborted).toBe(false)
    unmount()
    expect(signal.aborted).toBe(true)

    fetchRecap.mockClear()
    const second = render(<RecapCard sleeperLeagueId="L1" week={5} />)
    await tick(500)
    second.unmount()
    await tick(2000)
    expect(fetchRecap).not.toHaveBeenCalled()
  })

  it('polls GENERATING every 3 s and stops at READY', async () => {
    fetchRecap
      .mockResolvedValueOnce(resp({ state: 'GENERATING', headline: null, sections: null, model: null, revision: null }))
      .mockResolvedValueOnce(resp({ state: 'GENERATING', headline: null, sections: null, model: null, revision: null }))
      .mockResolvedValue(resp())
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText(/Writing this week/)).toBeTruthy()
    await tick(2999)
    expect(fetchRecap).toHaveBeenCalledTimes(1)
    await tick(1)
    expect(fetchRecap).toHaveBeenCalledTimes(2)
    await tick(3000)
    expect(fetchRecap).toHaveBeenCalledTimes(3)
    expect(screen.getByText('Master Bates hits 188.48 and stays perfect')).toBeTruthy()
    await tick(30000)
    expect(fetchRecap).toHaveBeenCalledTimes(3)
  })

  it('gives up after 40 polls with "Still writing, check back shortly"', async () => {
    fetchRecap.mockResolvedValue(resp({ state: 'GENERATING', headline: null, sections: null, model: null, revision: null }))
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.queryByText(/Still writing/)).toBeNull()
    await tick(3000 * 39)
    expect(fetchRecap).toHaveBeenCalledTimes(40)
    expect(screen.queryByText(/Still writing/)).toBeNull()
    await tick(3000)
    expect(fetchRecap).toHaveBeenCalledTimes(41)
    expect(screen.getByText(/Still writing, check back shortly/)).toBeTruthy()
    await tick(30000)
    expect(fetchRecap).toHaveBeenCalledTimes(41)
  })

  it('READY shows the headline, sections, model line, the checked note and the season', async () => {
    fetchRecap.mockResolvedValue(resp())
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Master Bates hits 188.48 and stays perfect')).toBeTruthy()
    expect(screen.getByText('Top score')).toBeTruthy()
    expect(screen.getByText('Master Bates scored 188.48 in week 3.')).toBeTruthy()
    expect(screen.getByText('The margin was 67.58.')).toBeTruthy()
    expect(screen.getByText(/Written by AI \(claude-haiku-4-5\)/)).toBeTruthy()
    expect(screen.getByText(/numbers checked against this report/)).toBeTruthy()
    expect(screen.getByText(/2026/)).toBeTruthy()
  })

  it('shows the right extra line for each revision reason', async () => {
    fetchRecap.mockResolvedValue(resp({ revision: 2, revisionReason: 'NUMBERS_CHANGED' }))
    const a = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Revised after a scoring correction.')).toBeTruthy()
    a.unmount()

    fetchRecap.mockResolvedValue(resp({ revision: 2, revisionReason: 'NAMES_CHANGED' }))
    const b = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Rewritten after a team name change.')).toBeTruthy()
    b.unmount()

    fetchRecap.mockResolvedValue(resp({ revision: 2, revisionReason: 'REPORT_CHANGED' }))
    const d = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText("Rewritten after this week's report changed.")).toBeTruthy()
    expect(screen.queryByText(/scoring correction/)).toBeNull()
    d.unmount()

    fetchRecap.mockResolvedValue(resp({ revision: 2, revisionReason: 'MODEL_OR_PROMPT_CHANGED' }))
    const c = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.queryByText(/Revised after/)).toBeNull()
    expect(screen.queryByText(/Rewritten after/)).toBeNull()
    expect(screen.getByText(/Written by AI/)).toBeTruthy()
    c.unmount()
  })

  it('a stale body with a failure reason shows the old body, its model and the reason', async () => {
    fetchRecap.mockResolvedValue(
      resp({ state: 'FAILED', stale: true, model: 'claude-sonnet-5-5', failureReason: 'UNGROUNDED' }),
    )
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Master Bates hits 188.48 and stays perfect')).toBeTruthy()
    expect(screen.getByText(/Written by AI \(claude-sonnet-5-5\)/)).toBeTruthy()
    // The stale body must not claim the current report was checked (R5), and must say which cause applies.
    expect(screen.getByText(/The latest rewrite failed \(the draft didn.t check out against the numbers\)\./)).toBeTruthy()
    expect(screen.getByText(/Older version · numbers were checked against the report as it was on Oct \d+/)).toBeTruthy()
    expect(screen.queryByText(/numbers checked against this report/)).toBeNull()
    expect(screen.queryByText(/from before the latest scores/)).toBeNull()
  })

  it('a stale body while updating or rate limited names the right cause', async () => {
    fetchRecap.mockResolvedValue(resp({ state: 'RATE_LIMITED', stale: true }))
    const a = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Recap paused for today; showing the last version.')).toBeTruthy()
    expect(screen.getByText(/Older version/)).toBeTruthy()
    a.unmount()

    fetchRecap.mockResolvedValue(resp({ state: 'GENERATING', stale: true }))
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Updating this recap…')).toBeTruthy()
    expect(screen.getByText(/Older version/)).toBeTruthy()
  })

  it('a READY response with no headline and no sections renders nothing', async () => {
    fetchRecap.mockResolvedValue(resp({ headline: null, sections: null }))
    const { container } = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(container.innerHTML).toBe('')
  })

  it('null sections with a headline renders the headline only', async () => {
    fetchRecap.mockResolvedValue(resp({ sections: null }))
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Master Bates hits 188.48 and stays perfect')).toBeTruthy()
    expect(screen.queryByText('Top score')).toBeNull()
  })

  it('FAILED with no body says the recap is unavailable, with the reason', async () => {
    fetchRecap.mockResolvedValue(
      resp({ state: 'FAILED', headline: null, sections: null, model: null, revision: null, failureReason: 'REFUSED' }),
    )
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText(/Recap unavailable this week/)).toBeTruthy()
    expect(screen.queryByText(/Written by AI/)).toBeNull()
  })

  it('WEEK_NOT_FINAL shows its copy', async () => {
    fetchRecap.mockResolvedValue(resp({ state: 'WEEK_NOT_FINAL', headline: null, sections: null, model: null, revision: null }))
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('The recap is written once this week is final.')).toBeTruthy()
  })

  it('RATE_LIMITED shows its copy, or the stale body when there is one', async () => {
    fetchRecap.mockResolvedValue(resp({ state: 'RATE_LIMITED', headline: null, sections: null, model: null, revision: null }))
    const a = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Recap paused for today')).toBeTruthy()
    a.unmount()

    fetchRecap.mockResolvedValue(resp({ state: 'RATE_LIMITED', stale: true }))
    render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(screen.getByText('Master Bates hits 188.48 and stays perfect')).toBeTruthy()
    expect(screen.queryByText('Recap paused for today')).toBeNull()
    expect(screen.getByText('Recap paused for today; showing the last version.')).toBeTruthy()
  })

  it('a failed request renders nothing rather than breaking the page', async () => {
    fetchRecap.mockRejectedValue(new Error('network'))
    const { container } = render(<RecapCard sleeperLeagueId="L1" week={3} />)
    await tick(1000)
    expect(container.innerHTML).toBe('')
  })
})
