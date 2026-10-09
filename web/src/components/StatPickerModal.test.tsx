import { useRef, useState } from 'react'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import StatPickerModal from './StatPickerModal'

/** A controlled host, as the panel is: the modal only reports changes, the host keeps the list. */
function Host({ initial, onClose = () => {}, spy }: { initial: string[]; onClose?: () => void; spy?: (c: string[]) => void }) {
  const [cols, setCols] = useState(initial)
  // Resolves updaters against the latest list, as the panel's chooseStats does.
  const latest = useRef(initial)
  return (
    <>
      <output data-testid="cols">{cols.join(',')}</output>
      <StatPickerModal
        columns={cols}
        onChange={(next) => {
          const c = typeof next === 'function' ? next(latest.current) : next
          latest.current = c
          spy?.(c)
          setCols(c)
        }}
        onClose={onClose}
      />
    </>
  )
}
const cols = () => screen.getByTestId('cols').textContent

describe('StatPickerModal (spec 023 US2)', () => {
  it('is a labelled modal dialog', () => {
    render(<Host initial={['gp']} />)
    expect(screen.getByRole('dialog', { name: 'Choose stats' })).toHaveAttribute('aria-modal', 'true')
  })

  it('offers each stat with its full name, and no Draft value stats', () => {
    render(<Host initial={[]} />)
    expect(screen.getByRole('checkbox', { name: /USG\s*Usage rate/ })).toBeInTheDocument()
    expect(screen.queryByText('Overall pick number')).toBeNull()
    expect(screen.getByText('No stats chosen.')).toBeInTheDocument()
  })

  it('reports a toggle at once, appending to the end', () => {
    const spy = vi.fn()
    render(<Host initial={['gp', 'pts']} spy={spy} />)
    fireEvent.click(screen.getByRole('checkbox', { name: /USG/ }))
    expect(spy).toHaveBeenLastCalledWith(['gp', 'pts', 'usg'])
    fireEvent.click(screen.getByRole('checkbox', { name: /USG/ }))
    expect(cols()).toBe('gp,pts')
  })

  it('moves a stat up and down, and removes one', () => {
    render(<Host initial={['gp', 'pts', 'reb']} />)
    fireEvent.click(screen.getByRole('button', { name: 'Move REB up' }))
    expect(cols()).toBe('gp,reb,pts')
    fireEvent.click(screen.getByRole('button', { name: 'Move GP down' }))
    expect(cols()).toBe('reb,gp,pts')
    fireEvent.click(screen.getByRole('button', { name: 'Remove GP' }))
    expect(cols()).toBe('reb,pts')
    expect(screen.getByRole('button', { name: 'Move REB up' })).toBeDisabled()
  })

  it('resets to the default columns', () => {
    render(<Host initial={['usg']} />)
    fireEvent.click(screen.getByRole('button', { name: 'Reset to default' }))
    expect(cols()).toMatch(/^gp,min,fp,/)
  })

  it('closes on Escape and on Done', () => {
    const onClose = vi.fn()
    render(<Host initial={['gp']} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Done' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('returns focus to the opener when it closes', () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const { unmount } = render(<Host initial={['gp']} />)
    expect(opener).not.toHaveFocus()
    unmount()
    expect(opener).toHaveFocus()
    opener.remove()
  })

  it('composes clicks that land before a re-render (two removes in one batch both apply)', () => {
    render(<Host initial={['gp', 'pts', 'reb']} />)
    const removeGp = screen.getByRole('button', { name: 'Remove GP' })
    const removePts = screen.getByRole('button', { name: 'Remove PTS' })
    // One act: React renders once at the end, so both handlers run against the same render.
    act(() => {
      removeGp.click()
      removePts.click()
    })
    expect(cols()).toBe('reb')
  })
})
