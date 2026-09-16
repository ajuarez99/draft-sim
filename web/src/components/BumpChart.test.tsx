import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import BumpChart, { type Series } from './BumpChart'

const seriesFor = (rosterIds: number[], weeks = [1, 2, 3]): Series[] =>
  rosterIds.map((rosterId, i) => ({
    rosterId,
    managerId: rosterId,
    manager: `manager${rosterId}`,
    hue: i * 30,
    points: weeks.map((week) => ({
      week,
      rank: i + 1,
      score: 100 + i,
      note: null,
      ballotCount: null,
      thin: false,
    })),
  }))

const roleOf = (container: HTMLElement, rosterId: number) =>
  container.querySelector(`[data-roster-id="${rosterId}"]`)?.getAttribute('data-role')

const strokeOf = (container: HTMLElement, rosterId: number) =>
  container
    .querySelector(`[data-roster-id="${rosterId}"] polyline`)
    ?.getAttribute('stroke')

const renderChart = (props: Partial<React.ComponentProps<typeof BumpChart>> = {}) =>
  render(
    <BumpChart
      series={seriesFor([1, 2, 3, 4])}
      weeks={[1, 2, 3]}
      teamCount={4}
      colorBy="focus"
      selection={[null, null, null]}
      meRosterId={null}
      onToggle={() => {}}
      {...props}
    />,
  )

/** US1 -- the default state stops being fourteen competing lines. */
describe('US1: find my own roster', () => {
  it('draws the reader in crimson and everyone else as context', () => {
    const { container } = renderChart({ meRosterId: 2 })

    expect(roleOf(container, 2)).toBe('me')
    expect(strokeOf(container, 2)).toBe('var(--crimson)')

    for (const other of [1, 3, 4]) {
      expect(roleOf(container, other)).toBe('context')
      expect(strokeOf(container, other)).toBe('var(--muted)')
    }
  })

  it('does not lean on the per-series hue any more', () => {
    const { container } = renderChart({ meRosterId: 2 })
    // The hues are 0/30/60/90 in the fixture; none of them should reach a stroke.
    for (const rosterId of [1, 2, 3, 4]) {
      expect(strokeOf(container, rosterId)).not.toMatch(/oklch\(70%/)
    }
  })

  it('still draws every series when nobody is the reader', () => {
    const { container } = renderChart({ meRosterId: null })
    expect(container.querySelectorAll('[data-roster-id]')).toHaveLength(4)
  })
})

/** US2 -- pinned comparison on the three validated colours. */
describe('US2: compare against a rival', () => {
  it('assigns pin slots in pin order', () => {
    const { container } = renderChart({ selection: [3, 1, null] })

    expect(roleOf(container, 3)).toBe('focus-1')
    expect(strokeOf(container, 3)).toBe('#3987e5')
    expect(roleOf(container, 1)).toBe('focus-2')
    expect(strokeOf(container, 1)).toBe('#199e70')
    expect(roleOf(container, 2)).toBe('context')
  })

  it('keeps every series on screen when pins change -- appearance only', () => {
    const { container, rerender } = renderChart({ selection: [null, null, null] })
    expect(container.querySelectorAll('[data-roster-id]')).toHaveLength(4)

    rerender(
      <BumpChart
        series={seriesFor([1, 2, 3, 4])}
        weeks={[1, 2, 3]}
        teamCount={4}
        colorBy="focus"
        selection={[1, 2, 3]}
        meRosterId={null}
        onToggle={() => {}}
      />,
    )
    expect(container.querySelectorAll('[data-roster-id]')).toHaveLength(4)
  })

  it('colours by pin order, never by the order series arrive in', () => {
    const forwards = renderChart({ selection: [4, null, null] })
    expect(strokeOf(forwards.container, 4)).toBe('#3987e5')

    const backwards = render(
      <BumpChart
        series={seriesFor([4, 3, 2, 1])}
        weeks={[1, 2, 3]}
        teamCount={4}
        colorBy="focus"
        selection={[4, null, null]}
        meRosterId={null}
        onToggle={() => {}}
      />,
    )
    expect(strokeOf(backwards.container, 4)).toBe('#3987e5')
  })

  it('lets a pin outrank being the reader', () => {
    const { container } = renderChart({ selection: [2, null, null], meRosterId: 2 })
    expect(roleOf(container, 2)).toBe('focus-1')
  })

  it('asks the caller to toggle when a line is clicked', async () => {
    const onToggle = vi.fn()
    const { container } = renderChart({ onToggle })

    await userEvent.click(container.querySelector('[data-roster-id="3"]')!)

    expect(onToggle).toHaveBeenCalledWith(3)
  })
})

/** US3 -- identity without colour at all. */
describe('US3: identify a line without selecting it', () => {
  it('labels every series at its right end', () => {
    const { container } = renderChart()
    const labels = [...container.querySelectorAll('.bump-end-label')].map((n) => n.textContent)

    expect(labels).toHaveLength(4)
    expect(labels).toEqual(expect.arrayContaining(['manager1', 'manager2', 'manager3', 'manager4']))
  })

  it('falls back to a roster number when a manager has no name', () => {
    const nameless = seriesFor([1])
    nameless[0].manager = null
    const { container } = render(
      <BumpChart
        series={nameless}
        weeks={[1, 2, 3]}
        teamCount={1}
        colorBy="focus"
        selection={[null, null, null]}
        meRosterId={null}
        onToggle={() => {}}
      />,
    )
    expect(container.querySelector('.bump-end-label')?.textContent).toContain('1')
  })

  it('separates labels that would otherwise overlap', () => {
    // Four rosters all sharing rank 1 put four labels at the same y.
    const stacked = seriesFor([1, 2, 3, 4]).map((s) => ({
      ...s,
      points: s.points.map((p) => ({ ...p, rank: 1 })),
    }))
    const { container } = render(
      <BumpChart
        series={stacked}
        weeks={[1, 2, 3]}
        teamCount={4}
        colorBy="focus"
        selection={[null, null, null]}
        meRosterId={null}
        onToggle={() => {}}
      />,
    )

    const ys = [...container.querySelectorAll('.bump-end-label')].map((n) =>
      Number(n.getAttribute('y')),
    )
    expect(new Set(ys).size).toBe(ys.length)
  })

  it('reports manager, week and rank on hover', () => {
    const { container } = renderChart()
    const title = container.querySelector('[data-roster-id="1"] title')?.textContent ?? ''
    expect(title).toContain('manager1')
    expect(title).toContain('week')
  })
})

/** Bye weeks and thin coverage are not this feature's business. */
describe('untouched behaviour', () => {
  it('still breaks the line at a bye week', () => {
    const withGap = seriesFor([1], [1, 2, 4])
    const { container } = render(
      <BumpChart
        series={withGap}
        weeks={[1, 2, 4]}
        teamCount={1}
        colorBy="focus"
        selection={[null, null, null]}
        meRosterId={null}
        onToggle={() => {}}
      />,
    )
    // Weeks 1-2 join; week 4 is its own segment, drawn as a lone circle.
    // `:not(.bump-hit)` skips the transparent fat stroke that widens the hit target.
    expect(
      container.querySelectorAll('[data-roster-id="1"] polyline:not(.bump-hit)'),
    ).toHaveLength(1)
    expect(container.querySelectorAll('[data-roster-id="1"] circle[fill="none"]')).toHaveLength(1)
  })
})
