import { describe, expect, it } from 'vitest'

// Spec 074 T040: the Redraft pages' stylesheet rules. Vitest stubs stylesheet imports to "", so the file is read from disk
// with node:fs (cwd is web/); the length check comes first so an empty read cannot pass every check below.

type Fs = { readFileSync: (p: string, enc: string) => string }
async function css(): Promise<string> {
  // @ts-expect-error node:fs has no types here
  const fs = (await import('node:fs')) as Fs
  return fs.readFileSync('src/styles.css', 'utf8')
}

const NEW_CLASSES = [
  '.tm-pill-link', '.tm-facts', '.tm-going-in', '.tm-spoilers', '.tm-rank-list', '.tm-rank-row', '.tm-rank-num', '.tm-rank-who',
  '.tm-rank-pts', '.tm-rank-sub', '.tm-movers.bust', '.tm-movers.steal', '.tm-move-nums', '.tm-move-delta', '.tm-slot-pts',
  '.tm-dream-total', '.tm-pos-grid', '.tm-neighbours', '.tm-limit', '.tm-newest', '.tm-decade-head', '.tm-years', '.tm-sport-tiles',
  '.tm-sport-tile', '.tm-signin', '.tm-game-chosen', '.topbar-signin', '.tm-foot', '.tm-links', '.tm-drafter',
]

describe('styles.css, Redraft pages', () => {
  it('reads a real stylesheet', async () => {
    expect((await css()).length).toBeGreaterThan(100_000)
  })

  it('defines every class the pages use, in the tm scope names the design gave', async () => {
    const text = await css()
    const missing = NEW_CLASSES.filter((c) => !text.includes(`${c} `) && !text.includes(`${c},`) && !text.includes(`${c}{`) && !text.includes(`${c}:`) && !text.includes(`${c}.`) && !text.includes(`${c}\n`))
    expect(missing).toEqual([])
  })

  it('marks the drafter without the viewer colour, and tints busts and steals with the down and up tokens', async () => {
    const text = await css()
    const drafter = text.match(/\.tm-drafter\s*\{[^}]*\}/)![0]
    expect(drafter).not.toMatch(/--you/)
    expect(drafter).toMatch(/--raised/)
    expect(text).toMatch(/\.tm-movers\.bust[^{]*\{[^}]*--down/)
    expect(text).toMatch(/\.tm-movers\.steal[^{]*\{[^}]*--up/)
  })

  it('adds no fixed layer (nothing new for the z-index ladder) and no hard-coded colour in the new block', async () => {
    const text = await css()
    const block = text.slice(text.indexOf('Spec 074: Redraft public pages'))
    expect(block.length).toBeGreaterThan(2000)
    expect(block).not.toMatch(/position:\s*fixed|z-index/)
    expect(block).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgb\(|oklch\(/)
  })

  it('turns the fold chevron off under reduced motion', async () => {
    const text = await css()
    expect(text).toMatch(/prefers-reduced-motion:\s*reduce\)\s*\{\s*\.tm-fold > summary::after\s*\{\s*transition:\s*none/)
  })

  // Review-fixes A2, C2, C12, C14, D4, D6, D7, D8 (read from disk; the length check above stops an empty read passing these).
  it('the steal callout uses the viewer colour only in the owner voice; a shared page gets the raised surface and the text colour (A2)', async () => {
    const text = await css()
    expect(text).toMatch(/\.tm-page \.hist-callout-steal\s*\{[^}]*--you/)
    const shared = text.match(/\.tm-page \.hist-callout-steal\.shared\s*\{[^}]*\}/)![0]
    expect(shared).toMatch(/--raised/)
    expect(shared).not.toMatch(/--you/)
    expect(text.match(/\.tm-page \.hist-callout-steal\.shared h3\s*\{[^}]*\}/)![0]).toMatch(/var\(--text\)/)
  })

  it('the limit heading shows the focus it takes (no outline: none), and is styled as an h2 (C2)', async () => {
    const text = await css()
    expect(text).not.toMatch(/\.tm-limit h[23]\s*\{[^}]*outline:\s*none/)
    expect(text).toMatch(/\.tm-limit h2:focus\s*\{[^}]*outline:\s*2px solid var\(--text\)/)
  })

  it('links and tiles have a focus ring; controls are 44px tall at phone width; footer links have padding (C12)', async () => {
    const text = await css()
    for (const sel of ['.tm-foot a:focus-visible', '.tm-sport-tile:focus-visible', '.tm-newest:focus-visible', '.tm-fold-link:focus-visible']) {
      expect(text, sel).toContain(sel)
    }
    expect(text).toMatch(/\.tm-foot a:focus-visible[^{]*\{[^}]*outline:\s*2px solid var\(--text\)[^}]*outline-offset:\s*3px/)
    expect(text).toMatch(/\.tm-foot a\s*\{[^}]*padding:\s*11px 4px/)
    const phone = text.match(/@media \(max-width: 640px\) \{\s*\.tm-btn, \.tm-sport, \.topbar-signin, \.tm-pill-link \{[^}]*\}/)![0]
    expect(phone).toMatch(/min-height:\s*44px/)
    expect(text).toMatch(/\.tm-spoilers > \.tm-fold > summary\s*\{[^}]*padding-block/)
  })

  // V-05 / V-06 (verification.md): a footer link was 43px and the fold link 21px; the behind-glyph measured 4.45:1.
  it('footer links and the fold link are at least 44px tall, centred (V-05)', async () => {
    const text = await css()
    expect(text.length).toBeGreaterThan(100_000)
    for (const re of [/^\.tm-foot a\s*\{[^}]*\}/m, /^\.tm-fold-link\s*\{[^}]*\}/m]) {
      const rule = text.match(re)![0]
      expect(rule).toMatch(/display:\s*inline-flex/)
      expect(rule).toMatch(/align-items:\s*center/)
      expect(rule).toMatch(/min-height:\s*44px/)
    }
  })

  it('the behind-glyph is lightened off --down toward the text colour so it clears 4.5:1 on its tint (V-06)', async () => {
    const text = await css()
    const rule = text.match(/\.tm-pick\.behind\s*\{[^}]*\}/)![0]
    expect(rule).toMatch(/color:\s*color-mix\(in oklab, var\(--down\) 85%, var\(--text\)\)/)
    expect(rule).not.toMatch(/color:\s*var\(--down\)/)
  })

  it('the signed-out parts of a delivered static page are hidden for a marked visitor, only until the app mounts (V-04)', async () => {
    const text = await css()
    expect(text).toMatch(/html\[data-bk-user\] \.topbar-signin,\s*html\[data-bk-user\] \.app-main \.tm-signin\s*\{\s*display:\s*none/)
  })

  it('the kicker (now the h1) has no negative margin that could overlap a wrapped title (C14)', async () => {
    const text = await css()
    const kicker = text.match(/\.tm-kicker\s*\{[^}]*\}/)![0]
    expect(kicker).not.toMatch(/margin:[^;]*-\d/)
  })

  it('the lead row belongs to the top-finishers list only, and the first bust and steal get the same treatment (D4)', async () => {
    const text = await css()
    expect(text).toMatch(/\.tm-rank-list\.tm-top > \.tm-rank-row:first-child/)
    expect(text).not.toMatch(/(^|\n)\.tm-rank-list > \.tm-rank-row:first-child/)
    expect(text).toMatch(/\.tm-movers > \.tm-mover:first-child \.tm-rank-who strong/)
  })

  it('a daily card is tinted by its sport, before .done so a finished card goes back to the plain panel (D6)', async () => {
    const text = await css()
    const nba = text.indexOf('.tm-daily-card.nba {')
    const done = text.indexOf('.tm-daily-card.done {')
    expect(nba).toBeGreaterThan(0)
    expect(text.indexOf('.tm-daily-card.nfl {')).toBeGreaterThan(0)
    expect(done).toBeGreaterThan(nba)
    expect(text.match(/\.tm-daily-card\.nba\s*\{[^}]*\}/)![0]).toMatch(/--sport-nba/)
  })

  it('the sign-in panel left-aligns its notes and gives the input the page background so it is visible on the panel (D7)', async () => {
    const text = await css()
    expect(text).toMatch(/\.tm-signin \.signin-note\s*\{[^}]*text-align:\s*left/)
    expect(text).toMatch(/\.tm-signin \.signin-input\s*\{[^}]*background:\s*var\(--bg\)/)
  })

  it('the drafter mark is as specific as the .tm-page rules it must beat (D8)', async () => {
    const text = await css()
    expect(text).toMatch(/\.tm-page \.tm-drafter\s*\{[^}]*--raised/)
    // it comes after .tm-page .hist-champ, .hist-bk-match and .hist-bk-byes li, so equal specificity goes to it
    const drafter = text.indexOf('.tm-page .tm-drafter {')
    for (const sel of ['.tm-page .hist-champ {', '.tm-page .hist-bk-match {', '.tm-page .hist-bk-byes li {']) {
      expect(text.indexOf(sel), sel).toBeGreaterThan(0)
      expect(drafter, sel).toBeGreaterThan(text.indexOf(sel))
    }
  })

  it('a button that is busy or limited looks disabled without being disabled (C3)', async () => {
    const text = await css()
    expect(text).toMatch(/\.tm-play\[aria-disabled="true"\], \.tm-btn\[aria-disabled="true"\]\s*\{[^}]*opacity:\s*0\.5/)
  })
})
