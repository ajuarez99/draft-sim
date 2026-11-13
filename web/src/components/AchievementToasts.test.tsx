import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AchievementToasts, { type AchievementToast } from './AchievementToasts'

afterEach(() => vi.useRealTimers())

const t = (key: string, over: Partial<AchievementToast> = {}): AchievementToast => ({
  key,
  kind: 'badge',
  title: `Title ${key}`,
  text: `Text ${key}`,
  xp: 25,
  ...over,
})

describe('AchievementToasts', () => {
  it('shows three at once, queues the rest, and shows each key once even under StrictMode', () => {
    const onShown = vi.fn()
    render(
      <StrictMode>
        <AchievementToasts toasts={[t('a'), t('b'), t('c'), t('d'), t('e')]} onShown={onShown} />
      </StrictMode>,
    )
    expect(screen.getAllByRole('button', { name: /^Dismiss/ })).toHaveLength(3)
    expect(screen.queryByText('Title d')).not.toBeInTheDocument()
    expect(onShown.mock.calls.map((c) => c[0].key)).toEqual(['a', 'b', 'c'])

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss: Title a' }))
    expect(screen.getByText('Title d')).toBeInTheDocument()
    expect(onShown.mock.calls.map((c) => c[0].key)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('ignores a duplicate key in the list', () => {
    render(<AchievementToasts toasts={[t('a'), t('a')]} />)
    expect(screen.getAllByText('Title a')).toHaveLength(1)
  })

  it('is a polite status region and renders the three kinds', () => {
    render(
      <AchievementToasts
        toasts={[
          t('b1'),
          t('m1', { kind: 'moment', xp: undefined }),
          t('l1', { kind: 'level', title: 'Level 4: Starter', text: undefined, xp: undefined }),
        ]}
      />,
    )
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
    expect(screen.getByText('+25 XP')).toBeInTheDocument()
    expect(screen.getAllByText(/XP/)).toHaveLength(1) // the moment and the level-up carry none
    expect(screen.getByText('Level 4: Starter')).toBeInTheDocument()
  })

  it('dismisses itself after about six seconds, but not while hovered', () => {
    vi.useFakeTimers()
    render(<AchievementToasts toasts={[t('a')]} />)
    const card = screen.getByText('Title a').closest('.ach-toast') as HTMLElement
    fireEvent.mouseEnter(card)
    act(() => void vi.advanceTimersByTime(20000))
    expect(screen.getByText('Title a')).toBeInTheDocument()
    fireEvent.mouseLeave(card)
    act(() => void vi.advanceTimersByTime(6100))
    expect(screen.queryByText('Title a')).not.toBeInTheDocument()
  })

  describe('in a background tab', () => {
    const setHidden = (hidden: boolean) => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
      document.dispatchEvent(new Event('visibilitychange'))
    }
    afterEach(() => setHidden(false))

    it('neither reports shown nor runs the clock while hidden, and resumes when visible', () => {
      vi.useFakeTimers()
      setHidden(true)
      const onShown = vi.fn()
      render(<AchievementToasts toasts={[t('a')]} onShown={onShown} />)
      expect(onShown).not.toHaveBeenCalled()
      act(() => void vi.advanceTimersByTime(30000))
      expect(screen.getByText('Title a')).toBeInTheDocument()

      act(() => setHidden(false))
      expect(onShown.mock.calls.map((c) => c[0].key)).toEqual(['a'])
      act(() => void vi.advanceTimersByTime(5900))
      expect(screen.getByText('Title a')).toBeInTheDocument()
      act(() => void vi.advanceTimersByTime(300))
      expect(screen.queryByText('Title a')).not.toBeInTheDocument()
    })
  })
})
