import { describe, expect, it } from 'vitest'

// Spec 073 T009: every Time Machine link comes from tmHome()/tmGame() (site.ts), so a build that serves the
// game at / and /game/:id cannot leave a link pointing at /history. This reads the source from disk and
// globs the directories, so a Historical file added later is covered too. Vitest runs from web/, so paths
// are relative to it (the same way DraftRoomLayout.test.tsx reads styles.css).

async function historicalFiles(): Promise<{ name: string; text: string }[]> {
  // @ts-ignore -- test-only Node import, the app has no @types/node
  const fs = await import('node:fs')
  const out: { name: string; text: string }[] = []
  for (const dir of ['src', 'src/pages', 'src/components']) {
    for (const f of fs.readdirSync(dir) as string[]) {
      if (/^Historical.*\.tsx$/.test(f) && !/\.test\.tsx$/.test(f)) {
        out.push({ name: `${dir}/${f}`, text: fs.readFileSync(`${dir}/${f}`, 'utf8') as string })
      }
    }
  }
  return out
}

describe('Time Machine paths', () => {
  it('finds the Historical files and reads them non-empty (so the guard below cannot pass on nothing)', async () => {
    const files = await historicalFiles()
    expect(files.length).toBeGreaterThan(10)
    const names = files.map((f) => f.name)
    for (const n of ['src/pages/HistoricalDraft.tsx', 'src/pages/HistoricalRoom.tsx', 'src/components/HistoricalProfileCard.tsx', 'src/components/HistoricalResults.tsx']) {
      expect(names).toContain(n)
    }
    for (const f of files) expect(f.text.length, f.name).toBeGreaterThan(100)
  })

  it('no non-test Historical*.tsx holds a /history string literal', async () => {
    const offenders: string[] = []
    for (const f of await historicalFiles()) {
      f.text.split(/\r?\n/).forEach((line: string, i: number) => {
        if (/['"`]\/history\b/.test(line)) offenders.push(`${f.name}:${i + 1}: ${line.trim()}`)
      })
    }
    expect(offenders).toEqual([])
  })
})
