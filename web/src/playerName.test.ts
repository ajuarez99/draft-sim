import { describe, expect, it } from 'vitest'
import { shortName, shortNameString } from './playerName'

// Every non-two-token name below was taken from the real 845-entry board
// (`/api/board?limit=900`, league 1391509063170293760, 2026-09-07) rather than
// invented -- those are the only shapes this has to survive today. The suffix
// cases are defensive: none are in the current pool, but Sleeper carries them
// and a re-ingest can introduce one.
describe('shortName', () => {
  it('abbreviates the ordinary two-token case', () => {
    expect(shortNameString({ name: 'Jahmyr Gibbs', position: 'RB' })).toBe('J. Gibbs')
    expect(shortNameString({ name: 'Puka Nacua', position: 'WR' })).toBe('P. Nacua')
  })

  it('keeps a hyphenated surname whole', () => {
    expect(shortNameString({ name: 'Jaxon Smith-Njigba', position: 'WR' })).toBe('J. Smith-Njigba')
  })

  it('keeps a particle with the surname', () => {
    expect(shortNameString({ name: 'Amon-Ra St. Brown', position: 'WR' })).toBe('A. St. Brown')
  })

  it('drops a middle name', () => {
    expect(shortNameString({ name: 'John Michael Gyllenborg', position: 'TE' })).toBe('J. Gyllenborg')
  })

  it('does not double-punctuate a given name that is already an initial', () => {
    expect(shortNameString({ name: 'J. Michael Sturdivant', position: 'WR' })).toBe('J. Sturdivant')
  })

  it('keeps a generational suffix', () => {
    expect(shortNameString({ name: 'Kenneth Walker III', position: 'RB' })).toBe('K. Walker III')
    expect(shortNameString({ name: 'Marvin Harrison Jr.', position: 'WR' })).toBe('M. Harrison Jr.')
  })

  it('drops the city from a team defense rather than initialising it', () => {
    expect(shortNameString({ name: 'Seattle Seahawks', position: 'DEF' })).toBe('Seahawks')
    expect(shortNameString({ name: 'Los Angeles Rams', position: 'DEF' })).toBe('Rams')
    expect(shortNameString({ name: 'San Francisco 49ers', position: 'DEF' })).toBe('49ers')
  })

  it('leaves a single-token name alone', () => {
    expect(shortNameString({ name: 'Chosen', position: 'WR' })).toBe('Chosen')
  })

  it('splits the lead off so the cell can de-emphasise it', () => {
    expect(shortName({ name: 'Bijan Robinson', position: 'RB' })).toEqual({ lead: 'B. ', rest: 'Robinson' })
    expect(shortName({ name: 'Denver Broncos', position: 'DEF' })).toEqual({ lead: '', rest: 'Broncos' })
  })

  it('never returns an empty rest', () => {
    for (const name of ['', '   ', 'Jr.', 'A B', 'A']) {
      expect(shortName({ name, position: 'WR' }).rest.length).toBeGreaterThan(0)
    }
  })
})
