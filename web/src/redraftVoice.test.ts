import { describe, expect, it } from 'vitest'
import { LEAGUE_WORDS } from './leagueWords'
import { drafterClass, drafterSuffix, voiceWords } from './redraftVoice'

// Spec 074 T042/T044: the voice table itself. The rendered-page check (HistoricalResults.test.tsx) is what catches a string
// added to a component and not to this table; this one is what keeps the table's own words honest.

function strings(v: 'you' | 'shared'): string[] {
  const out: string[] = []
  for (const val of Object.values(voiceWords(v))) {
    if (typeof val === 'string') out.push(val)
    else if (typeof val === 'function') {
      for (const args of [[3, 12, 5], [1, 12, 2], [4, 3]] as number[][]) {
        const r = (val as (...a: unknown[]) => unknown)(...args)
        if (typeof r === 'string') out.push(r)
        else if (Array.isArray(r)) out.push(...(r as string[]))
      }
    } else if (val && typeof val === 'object') out.push(...Object.values(val as Record<string, string>))
  }
  return out
}

describe('redraftVoice', () => {
  it('both voices define the same words (a key missing from one is a TypeScript error, and nothing is silently skipped)', () => {
    expect(Object.keys(voiceWords('shared')).sort()).toEqual(Object.keys(voiceWords('you')).sort())
    expect(strings('you').length).toBeGreaterThan(40)
  })

  it('the shared voice never says you, your or yours; the owner voice does (so the two are not the same table)', () => {
    expect(strings('shared').filter((s) => /\byou(r|rs|rself)?\b/i.test(s))).toEqual([])
    expect(strings('you').filter((s) => /\byou(r|rs)?\b/i.test(s)).length).toBeGreaterThan(20)
  })

  it('neither voice has league wording or the old name', () => {
    for (const v of ['you', 'shared'] as const) {
      expect(strings(v).filter((s) => LEAGUE_WORDS.test(s) || /time\s*machine/i.test(s))).toEqual([])
    }
  })

  it('the drafter is "this team" in a sentence and "Team n" in a table, with a suffix only in the shared voice', () => {
    const w = voiceWords('shared')
    expect(w.teamInline(3, 3)).toBe('This team')
    expect(w.teamInline(4, 3)).toBe('Team 4')
    expect(w.teamCell(3, 3)).toBe('Team 3')
    expect(drafterSuffix('shared')).toBe(' · this team')
    expect(drafterSuffix('you')).toBe('')
    expect(voiceWords('you').teamCell(3, 3)).toBe('You')
  })

  it('the drafter row class is the viewer tint for the owner and .tm-drafter for anyone else, and nothing for another team', () => {
    expect(drafterClass('you', true)).toBe('hist-me')
    expect(drafterClass('you', true, 'me')).toBe('me')
    expect(drafterClass('shared', true)).toBe('tm-drafter')
    expect(drafterClass('shared', true, 'me')).toBe('tm-drafter')
    expect(drafterClass('shared', false)).toBeUndefined()
    expect(drafterClass('you', false)).toBeUndefined()
  })
})
