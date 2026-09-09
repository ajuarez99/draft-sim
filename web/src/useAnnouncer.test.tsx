import { StrictMode, createElement, type ReactNode } from 'react'
import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAnnouncer } from './useAnnouncer'

/**
 * The hook's whole job is deciding what NOT to say. jsdom ships no
 * speechSynthesis and no AudioContext, which is convenient: stubbing them is
 * both how these assertions read the decisions and a check that the module
 * degrades instead of throwing where they are missing.
 */

const spoken: string[] = []
let chimes = 0

beforeEach(() => {
  spoken.length = 0
  chimes = 0
  vi.stubGlobal('speechSynthesis', {
    speak: (u: { text: string }) => spoken.push(u.text),
    cancel: () => {},
  })
  vi.stubGlobal('SpeechSynthesisUtterance', class {
    text: string
    lang = ''
    rate = 1
    constructor(text: string) {
      this.text = text
    }
  })
  // The chime is WebAudio; count node creation rather than assert on sound.
  vi.stubGlobal('AudioContext', class {
    currentTime = 0
    destination = {}
    resume() {
      return Promise.resolve()
    }
    createOscillator() {
      chimes++
      return {
        type: '', frequency: { value: 0 },
        connect: () => ({ connect: () => {} }),
        start: () => {}, stop: () => {},
      }
    }
    createGain() {
      return {
        gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} },
        connect: () => ({ connect: () => {} }),
      }
    }
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function pick(pickNo: number, manager = 'Allan', name = 'Josh Allen') {
  return { pickNo, manager, player: { name } }
}

describe('deciding what to announce', () => {
  it('says nothing at all while sound is off', () => {
    const { rerender } = renderHook(
      ({ p }) => useAnnouncer(false, p, false),
      { initialProps: { p: pick(1) } },
    )
    rerender({ p: pick(2) })
    expect(spoken).toEqual([])
  })

  it('does not replay the backlog when sound is switched on mid-draft', () => {
    // 27 picks are already in. Turning the toggle on must not read them out.
    renderHook(() => useAnnouncer(true, pick(27), false))
    expect(spoken).toEqual([])
  })

  it('announces the next pick after that, once', () => {
    const { rerender } = renderHook(
      ({ p }) => useAnnouncer(true, p, false),
      { initialProps: { p: pick(27) } },
    )
    rerender({ p: pick(28, 'Sam', "Ja'Marr Chase") })
    expect(spoken).toEqual(['Sam takes juh-MAR Chase'])
  })

  it('ignores a state frame that redelivers the same pick', () => {
    // recentPicks re-sends the same dozen picks every tick; a fresh object
    // with the same pick number is not news.
    const { rerender } = renderHook(
      ({ p }) => useAnnouncer(true, p, false),
      { initialProps: { p: pick(27) } },
    )
    rerender({ p: pick(28, 'Sam') })
    rerender({ p: pick(28, 'Sam') })
    rerender({ p: pick(28, 'Sam') })
    expect(spoken.length).toBe(1)
  })

  it('announces only the newest of a burst', () => {
    // An autopick run empties four seats in one tick. By the time a voice has
    // read four names the room has moved on.
    const { rerender } = renderHook(
      ({ p }) => useAnnouncer(true, p, false),
      { initialProps: { p: pick(27) } },
    )
    rerender({ p: pick(31, 'Kim', 'Breece Hall') })
    expect(spoken).toEqual(['Kim takes BREESE Hall'])
  })

  it('re-seeds after a mute, so unmuting does not catch up out loud', () => {
    const { rerender } = renderHook(
      ({ on, p }) => useAnnouncer(on, p, false),
      { initialProps: { on: true, p: pick(27) } },
    )
    rerender({ on: false, p: pick(30) })
    rerender({ on: true, p: pick(34) })
    expect(spoken).toEqual([])
    rerender({ on: true, p: pick(35, 'Lee') })
    expect(spoken).toEqual(['Lee takes Josh Allen'])
  })
})

describe('React StrictMode', () => {
  const strict = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children)

  it('does not announce twice when the effect is double-invoked', () => {
    // StrictMode remounts every effect in development. The high-water ref
    // survives that remount, so the second pass is a no-op -- the same
    // mechanism the redelivery case uses, not a special case for this one.
    const { rerender } = renderHook(
      ({ p }) => useAnnouncer(true, p, false),
      { initialProps: { p: pick(27) }, wrapper: strict },
    )
    rerender({ p: pick(28, 'Sam') })
    expect(spoken).toEqual(['Sam takes Josh Allen'])
  })
})

describe('the on-the-clock chime', () => {
  it('fires on the transition into your turn', () => {
    const { rerender } = renderHook(
      ({ mine }) => useAnnouncer(true, pick(27), mine),
      { initialProps: { mine: false } },
    )
    expect(chimes).toBe(0)
    rerender({ mine: true })
    expect(chimes).toBeGreaterThan(0)
  })

  it('does not fire just because you enabled sound while already on the clock', () => {
    // You are looking at the page in that moment; the chime is for when you
    // are not.
    renderHook(() => useAnnouncer(true, pick(27), true))
    expect(chimes).toBe(0)
  })

  it('stays quiet while sound is off', () => {
    const { rerender } = renderHook(
      ({ mine }) => useAnnouncer(false, pick(27), mine),
      { initialProps: { mine: false } },
    )
    rerender({ mine: true })
    expect(chimes).toBe(0)
  })
})
