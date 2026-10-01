import { describe, expect, it } from 'vitest'
import { NARROW } from './useNarrow'

describe('useNarrow breakpoint', () => {
  it('matches a media query styles.css actually uses for the phone layout', async () => {
    // Vitest stubs CSS, so `styles.css?raw` comes back empty here; read the file.
    // node:fs is loaded dynamically and untyped because the app has no @types/node.
    // @ts-ignore -- test-only Node import
    const fs = await import('node:fs')
    const css: string = fs.readFileSync('src/styles.css', 'utf8') // vitest runs with cwd = web/
    expect(css.length).toBeGreaterThan(1000)
    expect(css).toContain(`@media ${NARROW}`)
  })
})
