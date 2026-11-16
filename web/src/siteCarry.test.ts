import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CARRY_MAX_CHARS, acceptCarry, withCarry } from './siteCarry'
import { DEFAULT_SAVED_SETTINGS, SAVED_KEY, readSaved, removeSaved, saveFormula } from './statCalcSaved'

// Spec 073 T022 / FR-020. The carry rides in the URL fragment from the old main address to the league site.

function store(initial: Record<string, string> = {}) {
  const m = new Map(Object.entries(initial))
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: vi.fn((k: string, v: string) => void m.set(k, v)),
    raw: (k: string) => m.get(k) ?? null,
  }
}

function formulaStore(n = 1, name = 'Mine') {
  const formulas = Array.from({ length: n }, (_, i) => ({
    id: `id${i}`, name: `${name}${i}`, expr: 'ast / tov', settings: DEFAULT_SAVED_SETTINGS, savedAt: '2026-11-01T00:00:00.000Z',
  }))
  return JSON.stringify({ v: 1, formulas })
}

function fakeHistory() {
  const calls: string[] = []
  return { state: null, replaceState: vi.fn((_s: unknown, _t: string, url?: string | URL | null) => void calls.push(String(url))), calls }
}

const loc = (hash: string, pathname = '/leagues/9/power', search = '?a=1') => ({ pathname, search, hash })

describe('withCarry', () => {
  it('leaves the url alone when there is nothing saved, or the store is blocked or garbage', () => {
    expect(withCarry('https://l.co/x', store())).toBe('https://l.co/x')
    expect(withCarry('https://l.co/x', store({ [SAVED_KEY]: '{not json' }))).toBe('https://l.co/x')
    expect(withCarry('https://l.co/x', { getItem: () => { throw new Error('blocked') } })).toBe('https://l.co/x')
    expect(withCarry('https://l.co/x', store({ [SAVED_KEY]: JSON.stringify({ v: 1, formulas: [] }) }))).toBe('https://l.co/x')
  })

  it('adds the carry to the fragment, before any fragment the link already had', () => {
    const s = store({ [SAVED_KEY]: formulaStore() })
    expect(withCarry('https://l.co/x?q=1', s)).toMatch(/^https:\/\/l\.co\/x\?q=1#bk-carry=[A-Za-z0-9_-]+$/)
    expect(withCarry('https://l.co/x#section', s)).toMatch(/^https:\/\/l\.co\/x#bk-carry=[A-Za-z0-9_-]+;section$/)
  })

  it('does not attach a carry over the size cap, and never touches the source', () => {
    const big = store({ [SAVED_KEY]: formulaStore(50, 'x'.repeat(40)) })
    // 50 formulas with 40-character names is the largest the app can store; if it fits, the cap is not what this checks.
    const url = withCarry('https://l.co/', big)
    const frag = url.includes('#bk-carry=') ? url.split('#bk-carry=')[1] : ''
    expect(frag.length).toBeLessThanOrEqual(CARRY_MAX_CHARS)
    expect(big.setItem).not.toHaveBeenCalled()
    // One formula that alone is over the cap: nothing can be carried.
    const one = store({ [SAVED_KEY]: formulaStore(1).replace('ast / tov', 'ast / tov + '.repeat(2000) + 'pts') })
    expect(withCarry('https://l.co/', one)).toBe('https://l.co/')
  })
})

// CR-01: real saveFormula-shaped entries, about 320 encoded characters each.
function saveReal(n: number) {
  for (let i = 0; i < n; i++) {
    saveFormula(
      { name: `My formula number ${i}`, expr: 'pts + 1.2*reb + 1.5*ast + 3*stl + 3*blk - tov', settings: DEFAULT_SAVED_SETTINGS },
      [],
      new Date(Date.UTC(2026, 10, 1, 0, i)),
    )
  }
}

describe('carry size (CR-01)', () => {
  beforeEach(() => localStorage.clear())

  it.each([20, 30, 50])('%i real saved formulas all arrive', (n) => {
    saveReal(n)
    const sent = readSaved().map((f) => f.id)
    const url = withCarry('https://l.co/', localStorage)
    expect(url).toContain('#bk-carry=')
    localStorage.clear()
    acceptCarry(loc(url.slice(url.indexOf('#'))), fakeHistory(), localStorage)
    expect(readSaved().map((f) => f.id)).toEqual(sent)
  })

  it('an oversized set carries the newest formulas that fit, oldest first, and arrives', () => {
    // 50 entries with 40-character names and a long expression: well over the cap as a whole.
    const long = 'pts + reb + ast + stl + blk - tov + '.repeat(8) + 'pts'
    const formulas = Array.from({ length: 50 }, (_, i) => ({
      id: `id${String(i).padStart(2, '0')}`, name: 'n'.repeat(40), expr: long, settings: DEFAULT_SAVED_SETTINGS,
      savedAt: new Date(Date.UTC(2026, 10, 1, 0, i)).toISOString(),
    }))
    const s = store({ [SAVED_KEY]: JSON.stringify({ v: 1, formulas }) })
    const url = withCarry('https://l.co/', s)
    const frag = url.split('#bk-carry=')[1]
    expect(frag).toBeDefined()
    expect(frag.length).toBeLessThanOrEqual(CARRY_MAX_CHARS)
    const dest = store()
    acceptCarry(loc(url.slice(url.indexOf('#'))), fakeHistory(), dest)
    const got = JSON.parse(dest.raw(SAVED_KEY)!).formulas.map((f: { id: string }) => f.id)
    expect(got.length).toBeGreaterThan(0)
    expect(got.length).toBeLessThan(50)
    // The newest ones, in the original (oldest first) order, ending at the newest.
    expect(got).toEqual(formulas.slice(50 - got.length).map((f) => f.id))
  })
})

describe('acceptCarry', () => {
  beforeEach(() => localStorage.clear())

  it('round trip: formulas saved on the old address are saved on the new one, and the fragment is stripped', () => {
    saveFormula({ name: 'Mine', expr: 'ast / tov', settings: DEFAULT_SAVED_SETTINGS })
    const url = withCarry('https://l.co/leagues/9/power?a=1', localStorage)
    const hash = url.slice(url.indexOf('#'))
    const before = localStorage.getItem(SAVED_KEY)
    localStorage.clear() // the league site is a different origin: an empty store
    const h = fakeHistory()
    acceptCarry(loc(hash), h, localStorage)
    expect(readSaved().map((f) => [f.name, f.expr])).toEqual([['Mine', 'ast / tov']])
    expect(JSON.parse(localStorage.getItem(SAVED_KEY)!).formulas).toHaveLength(1)
    expect(before).not.toBeNull()
    expect(h.calls).toEqual(['/leagues/9/power?a=1'])
  })

  it('restores an original fragment after stripping the carry', () => {
    const s = store({ [SAVED_KEY]: formulaStore() })
    const url = withCarry('https://l.co/leagues/9/power#section', s)
    const h = fakeHistory()
    acceptCarry(loc(url.slice(url.indexOf('#'))), h, store())
    expect(h.calls).toEqual(['/leagues/9/power?a=1#section'])
  })

  it('writes nothing when the destination already has formulas, but still strips', () => {
    const src = store({ [SAVED_KEY]: formulaStore(1, 'Theirs') })
    const url = withCarry('https://l.co/x', src)
    const dest = store({ [SAVED_KEY]: formulaStore(1, 'Mine') })
    const h = fakeHistory()
    acceptCarry(loc(url.slice(url.indexOf('#'))), h, dest)
    expect(dest.setItem).not.toHaveBeenCalled()
    expect(h.calls).toHaveLength(1)
  })

  it('does not bring back formulas the member deleted on this site (CR-03)', () => {
    const src = store({ [SAVED_KEY]: formulaStore(2, 'Old') })
    const url = withCarry('https://l.co/x', src)
    localStorage.clear()
    saveFormula({ name: 'Mine', expr: 'ast / tov', settings: DEFAULT_SAVED_SETTINGS })
    const id = readSaved()[0].id
    removeSaved(id) // the real empty shape statCalcSaved writes
    const emptied = localStorage.getItem(SAVED_KEY)
    expect(JSON.parse(emptied!)).toEqual({ v: 1, formulas: [] })
    const h = fakeHistory()
    acceptCarry(loc(url.slice(url.indexOf('#'))), h, localStorage)
    expect(localStorage.getItem(SAVED_KEY)).toBe(emptied)
    expect(h.calls).toHaveLength(1)
  })

  it('a garbage value in the store counts as never saved', () => {
    const src = store({ [SAVED_KEY]: formulaStore(1, 'Old') })
    const url = withCarry('https://l.co/x', src)
    const dest = store({ [SAVED_KEY]: '{not json' })
    acceptCarry(loc(url.slice(url.indexOf('#'))), fakeHistory(), dest)
    expect(dest.setItem).toHaveBeenCalledTimes(1)
  })

  it('drops garbage and still strips it from the address bar', () => {
    for (const data of ['!!!notbase64!!!', 'e30', btoa('{"v":1,"formulas":[{"id":1}]}').replace(/=/g, '')]) {
      const dest = store()
      const h = fakeHistory()
      acceptCarry(loc(`#bk-carry=${data}`), h, dest)
      expect(dest.setItem).not.toHaveBeenCalled()
      expect(h.calls).toEqual(['/leagues/9/power?a=1'])
    }
  })

  it('drops an oversized carry, still strips', () => {
    const dest = store()
    const h = fakeHistory()
    acceptCarry(loc(`#bk-carry=${'A'.repeat(CARRY_MAX_CHARS + 1)}`), h, dest)
    expect(dest.setItem).not.toHaveBeenCalled()
    expect(h.calls).toHaveLength(1)
  })

  it('ignores an address with no carry and leaves the fragment alone', () => {
    const h = fakeHistory()
    acceptCarry(loc('#section'), h, store())
    acceptCarry(loc(''), h, store())
    expect(h.replaceState).not.toHaveBeenCalled()
  })

  it('does not throw when storage is blocked', () => {
    const src = store({ [SAVED_KEY]: formulaStore() })
    const url = withCarry('https://l.co/x', src)
    const h = fakeHistory()
    const blocked = { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') } }
    expect(() => acceptCarry(loc(url.slice(url.indexOf('#'))), h, blocked)).not.toThrow()
    expect(h.calls).toHaveLength(1)
  })
})
