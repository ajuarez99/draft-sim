import { describe, expect, it } from 'vitest'
import { pronounceable } from './pronounce'

describe('what gets handed to the voice', () => {
  it('respells a name the OS voices mangle', () => {
    expect(pronounceable("Ja'Marr Chase")).toBe('juh-MAR Chase')
  })

  it('finds the entry however the feed spelled the punctuation', () => {
    // Sleeper's own spelling varies (and has changed) for apostrophes and the
    // period in "St."; all of these are the same player.
    expect(pronounceable('JaMarr Chase')).toBe('juh-MAR Chase')
    expect(pronounceable('Amon-Ra St. Brown')).toBe('AH-mon RAH Saint Brown')
    expect(pronounceable('Amon-Ra St Brown')).toBe('AH-mon RAH Saint Brown')
  })

  it('drops a generational suffix -- voices read "III" as letters', () => {
    expect(pronounceable('Marvin Harrison Jr.')).toBe('Marvin Harrison')
    expect(pronounceable('Kenneth Walker III')).toBe('Kenneth Walker')
  })

  it('strips the suffix before looking the name up, not after', () => {
    // The table is keyed on the bare name. A lookup that ran first would miss
    // every player whose feed spelling carries a suffix.
    expect(pronounceable('Brian Thomas Jr.')).toBe('Brian Thomas')
  })

  it('passes an ordinary name straight through', () => {
    // The default matters more than the table: most names are read correctly,
    // and a generic transformation applied to all of them would break more
    // than it fixed.
    expect(pronounceable('Josh Allen')).toBe('Josh Allen')
    expect(pronounceable('Seattle Seahawks')).toBe('Seattle Seahawks')
  })

  it('never returns nothing to say', () => {
    // A name that is only a suffix would otherwise announce silence.
    expect(pronounceable('III')).toBe('III')
    expect(pronounceable('  ')).toBe('')
  })
})
