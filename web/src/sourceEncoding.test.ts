import { describe, expect, it } from 'vitest'

/**
 * Spec 013: a build agent's file write on Windows saved part of
 * AvailabilityPanel.tsx in the Windows-1252 codepage. The page then showed
 * "ADP 1�60" instead of "ADP 1–60", and no other test noticed. This fails if
 * any source file under src/ is not valid UTF-8.
 */
describe('source encoding', () => {
  it('every file under src/ is valid UTF-8', async () => {
    // @ts-ignore -- test-only Node imports; the app has no @types/node
    const fs = await import('node:fs')
    // @ts-ignore
    const path = await import('node:path')
    const bad: string[] = []
    const walk = (dir: string) => {
      for (const name of fs.readdirSync(dir)) {
        const p = path.join(dir, name)
        if (fs.statSync(p).isDirectory()) walk(p)
        else if (/\.(tsx?|css)$/.test(name)) {
          try {
            new TextDecoder('utf-8', { fatal: true }).decode(fs.readFileSync(p))
          } catch {
            bad.push(p)
          }
        }
      }
    }
    walk('src') // vitest runs with cwd = web/
    expect(bad).toEqual([])
  })
})
