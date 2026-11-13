import { describe, expect, it } from 'vitest'

// Phase 1 of claude/site-redesign.md. The old palette's tokens (--teal, --volt,
// --crimson*, --row-hover) were REMOVED, not aliased, so a usage left behind
// renders as nothing. This is the net for that: it fails if any survive in the
// stylesheet or in any TypeScript string (inline styles, managerColor.ts, ...).
//
// It deliberately does NOT check "every var(--x) is defined in :root": 22 names
// are legitimately set elsewhere (component-scoped, or inline from TSX), so that
// rule fails a correct stylesheet (plan review PR-06).
//
// Vitest stubs stylesheet imports to "", so the file is read from disk with
// node:fs (cwd is web/). The length assertion comes first so an empty read
// cannot pass every "does not contain" check below.

type Fs = {
  readFileSync: (p: string, enc: string) => string
  readdirSync: (p: string) => string[]
  statSync: (p: string) => { isDirectory: () => boolean }
}

async function loadFs(): Promise<Fs> {
  // @ts-expect-error node:fs has no types here
  return (await import('node:fs')) as Fs
}

const REMOVED = /--(?:teal|volt|crimson|row-hover)\b/
const OLD_FONTS = /Oswald|Plus Jakarta/

const NEW_TOKENS = [
  '--bg', '--panel', '--raised', '--line', '--text', '--muted',
  '--brand', '--you', '--you-text', '--you-fill',
  '--data', '--up', '--down', '--danger', '--fitted', '--stated',
  '--sport-nfl', '--sport-nba',
  '--qb', '--rb', '--wr', '--te', '--k', '--def',
  '--pg', '--sg', '--sf', '--pf', '--c',
  '--font-body', '--font-display', '--font-num',
]

async function stylesheet(): Promise<string> {
  const { readFileSync } = await loadFs()
  const css = readFileSync('src/styles.css', 'utf8')
  expect(css.length, 'styles.css read as empty -- the guard would pass vacuously').toBeGreaterThan(1000)
  return css
}

async function sourceFiles(): Promise<string[]> {
  const { readdirSync, statSync } = await loadFs()
  const out: string[] = []
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const p = `${dir}/${name}`
      if (statSync(p).isDirectory()) walk(p)
      else if (/\.tsx?$/.test(name) && name !== 'paletteTokens.test.ts') out.push(p)
    }
  }
  walk('src')
  return out
}

describe('palette tokens (Phase 1)', () => {
  it('styles.css no longer mentions a removed token', async () => {
    const css = await stylesheet()
    const hit = css.split('\n').findIndex((l) => REMOVED.test(l))
    expect(hit, `styles.css line ${hit + 1} still names a removed token`).toBe(-1)
  })

  it('no TypeScript file under web/src names a removed token', async () => {
    const { readFileSync } = await loadFs()
    const files = await sourceFiles()
    expect(files.length, 'found no source files').toBeGreaterThan(50)
    const bad = files.filter((f) => REMOVED.test(readFileSync(f, 'utf8')))
    expect(bad, `still name a removed token: ${bad.join(', ')}`).toEqual([])
  })

  it('styles.css has no @import (fonts load from index.html) and names no retired font', async () => {
    const css = await stylesheet()
    expect(css).not.toContain('@import')
    expect(css).not.toMatch(OLD_FONTS)
  })

  it('every new token is defined in the :root block', async () => {
    const css = await stylesheet()
    const root = /:root\s*\{([^}]*)\}/.exec(css)
    expect(root, 'no :root block').not.toBeNull()
    const block = root![1]
    const missing = NEW_TOKENS.filter((t) => !new RegExp(`${t}\\s*:`).test(block))
    expect(missing, `not defined in :root: ${missing.join(', ')}`).toEqual([])
  })

  it('a solid --you-fill never carries --text, which is why the fill is gold (--bg text)', async () => {
    const css = await stylesheet()
    const bad = css.split('\n').filter((l) => /background:\s*var\(--you-fill\)/.test(l) && /color:\s*var\(--text\)/.test(l))
    expect(bad).toEqual([])
  })
})
