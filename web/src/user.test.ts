import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearUser, currentUserId, setUser, type BkUser } from './user'

const sample: BkUser = {
  sleeperUserId: '123',
  username: 'popsharky',
  displayName: 'popsharky',
  avatar: 'abc123',
}

describe('user.ts', () => {
  beforeEach(() => {
    localStorage.clear()
    clearUser()
  })

  it('starts signed out', () => {
    expect(currentUserId()).toBeNull()
  })

  it('setUser makes currentUserId synchronously readable and persists to localStorage', () => {
    setUser(sample)
    expect(currentUserId()).toBe('123')
    expect(JSON.parse(localStorage.getItem('bk.user.v1')!)).toEqual(sample)
  })

  it('clearUser signs out and removes the stored key', () => {
    setUser(sample)
    clearUser()
    expect(currentUserId()).toBeNull()
    expect(localStorage.getItem('bk.user.v1')).toBeNull()
  })

  it('corrupt stored JSON is treated as signed out, not a crash', async () => {
    localStorage.setItem('bk.user.v1', '{not json')
    // The module under test reads localStorage once at import time
    // (readStored() runs at module load), so a fresh module registry is
    // needed to re-exercise that read against the corrupt value.
    vi.resetModules()
    const fresh = await import('./user')
    expect(fresh.currentUserId()).toBeNull()
  })

  it('a stored value missing sleeperUserId is treated as signed out', async () => {
    localStorage.setItem('bk.user.v1', JSON.stringify({ username: 'x' }))
    vi.resetModules()
    const fresh = await import('./user')
    expect(fresh.currentUserId()).toBeNull()
  })
})
