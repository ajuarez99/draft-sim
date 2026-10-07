import { describe, expect, it } from 'vitest'
import { NARROW } from './useNarrow'

describe('useNarrow breakpoint', () => {
  it('matches a media query styles.css actually uses for the phone layout', async () => {
    // Vitest stubs CSS, so `styles.css?raw` comes back empty here; read the file.
    // node:fs is loaded dynamically and untyped because the app has no @types/node.
    // @ts-ignore -- test-only Node import
    const fs = await import('node:fs')
    // styles.css is an @import index since spec 021 (US4): follow its local imports in
    // order, so this reads the same stylesheet the build inlines.
    const index: string = fs.readFileSync('src/styles.css', 'utf8') // vitest runs with cwd = web/
    const pieces = [...index.matchAll(/^@import '\.\/(styles\/[^']+\.css)';$/gm)].map((m) => m[1])
    expect(pieces.length).toBeGreaterThan(0)
    const css: string = pieces.map((p) => fs.readFileSync(`src/${p}`, 'utf8')).join('\n')
    expect(css.length).toBeGreaterThan(1000)
    expect(css).toContain(`@media ${NARROW}`)
  })
})
