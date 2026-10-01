import { describe, expect, it } from 'vitest'
import { hasAnyAdpAtDraft, pickValue, signedPicks, tintPercent } from './stealsReaches'

describe('pickValue', () => {
  it('ORDERING: a pick 20 after its draft-time ADP is a steal, never a reach', () => {
    const v = pickValue(40, 20)
    expect(v.kind).toBe('steal')
    expect(v.delta).toBe(20)
    expect(v.kind).not.toBe('reach')
  })

  it('ORDERING: a pick 20 before its draft-time ADP is a reach, never a steal', () => {
    const v = pickValue(10, 30)
    expect(v.kind).toBe('reach')
    expect(v.delta).toBe(-20)
  })

  it('null or missing ADP is unknown, never tinted as either', () => {
    expect(pickValue(5, null)).toEqual({ kind: 'unknown', delta: null })
    expect(pickValue(5, undefined)).toEqual({ kind: 'unknown', delta: null })
  })

  it('calls a sub-half-pick difference even', () => {
    expect(pickValue(10, 10.3).kind).toBe('even')
  })
})

describe('signedPicks', () => {
  it('prints an explicit sign', () => {
    expect(signedPicks(12.4)).toBe('+12')
    expect(signedPicks(-7.6)).toBe('−8')
    expect(signedPicks(0.2)).toBe('0')
  })
})

describe('tintPercent / hasAnyAdpAtDraft', () => {
  it('grows with the gap and is capped', () => {
    expect(tintPercent(2)).toBeLessThan(tintPercent(10))
    expect(tintPercent(500)).toBe(26)
    expect(tintPercent(-500)).toBe(26)
  })
  it('is false when every pick lacks a draft-time ADP', () => {
    expect(hasAnyAdpAtDraft([null, undefined])).toBe(false)
    expect(hasAnyAdpAtDraft([null, 12])).toBe(true)
  })
})
