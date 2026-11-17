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

// Spec 074 V-04: the mark boot.js sets also hides the Redraft static pages' signed-out parts, so main.tsx must take it off
// once the app has mounted (a visitor who then signs out would otherwise never see the app's own Sign in link).
describe('the boot mark is spent when the app mounts', () => {
  it('main.tsx removes data-bk-user in a layout effect around the app', async () => {
    // @ts-expect-error node:fs has no types here
    const fs = (await import('node:fs')) as { readFileSync: (p: string, enc: string) => string }
    const main = fs.readFileSync('src/main.tsx', 'utf8')
    expect(main.length).toBeGreaterThan(500)
    expect(main).toMatch(/useLayoutEffect\([\s\S]*removeAttribute\('data-bk-user'\)/)
    expect(main).toMatch(/<BootMarkSpent>[\s\S]*<App \/>[\s\S]*<\/BootMarkSpent>/)
  })
})
