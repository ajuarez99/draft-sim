// Spec 013 measurement harness (quickstart §4a/§4b), amended after adversarial
// review (B4). Paste into the console of an app tab (same origin), then:
//   bkRun([...routes])  -> poll window.bkBusy / window.bkOut
// Definitions:
//   surface = visible element with padding > 0, >= 1 element child, and a
//             background color, a border on any side, or an inset box-shadow.
//   leaf    = visible element with non-empty OWN text; depth = number of surface
//             ANCESTORS (the leaf itself is never counted).
//   grids   = `.board` and `table` are measured separately as gridDepth.
window.bkMeasure = async function (routes, w = 1440, h = 900) {
  const out = []
  for (const r of routes) {
    const f = document.createElement('iframe')
    f.style.cssText = `position:fixed;left:-${w + 50}px;top:0;width:${w}px;height:${h}px;border:0`
    f.src = r
    document.body.appendChild(f)
    await new Promise((res) => (f.onload = res))
    await new Promise((res) => setTimeout(res, 3500))
    const doc = f.contentDocument, win = f.contentWindow
    const cs = (el) => win.getComputedStyle(el)
    const visible = (el) => {
      if (el.closest('details:not([open]) > :not(summary)')) return false
      const s = cs(el)
      return s.display !== 'none' && s.visibility !== 'hidden' && el.getClientRects().length > 0
    }
    const isSurface = (el) => {
      if (!el.children.length) return false
      const s = cs(el)
      const pad = ['Top', 'Right', 'Bottom', 'Left'].some((k) => parseFloat(s[`padding${k}`]) > 0)
      if (!pad) return false
      const bg = s.backgroundColor !== 'rgba(0, 0, 0, 0)' && s.backgroundColor !== 'transparent'
      const border = ['Top', 'Right', 'Bottom', 'Left'].some(
        (k) => parseFloat(s[`border${k}Width`]) > 0 && s[`border${k}Style`] !== 'none' && s[`border${k}Color`] !== 'rgba(0, 0, 0, 0)',
      )
      const inset = /inset/.test(s.boxShadow)
      return bg || border || inset
    }
    const ownText = (el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
    const main = doc.querySelector('main, .app-main') || doc.body
    let depth = 0, where = '', gridDepth = 0, gridWhere = ''
    for (const el of main.querySelectorAll('*')) {
      if (!ownText(el) || !visible(el)) continue
      const grid = el.closest('.board, table')
      let n = 0
      for (let p = el.parentElement; p && p !== main; p = p.parentElement) {
        if (grid && !grid.contains(p) && p !== grid) continue // count only inside the grid for grid leaves
        if (isSurface(p)) n++
      }
      if (grid) { if (n > gridDepth) { gridDepth = n; gridWhere = el.textContent.trim().slice(0, 30) } continue }
      if (n > depth) { depth = n; where = el.textContent.trim().slice(0, 40) }
    }
    out.push({ route: r, depth, where, gridDepth, gridWhere, hscroll: doc.documentElement.scrollWidth > win.innerWidth })
    f.remove()
  }
  return out
}
window.bkRun = (routes, w, h) => {
  window.bkOut = []; window.bkBusy = true
  bkMeasure(routes, w, h).then((r) => { window.bkOut.push(...r); window.bkBusy = false })
  return 'started'
}
