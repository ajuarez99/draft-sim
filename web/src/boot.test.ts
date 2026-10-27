import { describe, expect, it } from 'vitest'
import bootJs from '../public/boot.js?raw'
import { STORAGE_KEY } from './user'

// Spec 050 (review F8): public/boot.js is plain JS that cannot import user.ts, so it
// repeats the storage key. This fails if the two drift (e.g. a bump to bk.user.v2).
describe('public/boot.js', () => {
  it('reads the same storage key and field as user.ts', () => {
    expect(bootJs).toContain(`'${STORAGE_KEY}'`)
    expect(bootJs).toContain('sleeperUserId')
  })
})
