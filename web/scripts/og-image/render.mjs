// Spec 050: one-off rasteriser for the share-preview image. NOT part of the build and
// @resvg/resvg-js is deliberately not a dependency of web/ -- the PNG is committed.
//
// Usage (install resvg somewhere outside web/, then point NODE_RESVG_DIR at it):
//   mkdir /tmp/resvg && cd /tmp/resvg && npm init -y && npm install @resvg/resvg-js
//   NODE_RESVG_DIR=/tmp/resvg node web/scripts/og-image/render.mjs \
//     web/scripts/og-image/og-image.svg web/public/og-image.png
// Fonts come from the system (Bahnschrift on Windows, per the SVG's font stack).
import { readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'

const [svgPath, outPath] = process.argv.slice(2)
if (!svgPath || !outPath) {
  console.error('usage: render.mjs <in.svg> <out.png>')
  process.exit(1)
}
const require = createRequire(join(process.env.NODE_RESVG_DIR ?? process.cwd(), 'noop.js'))
const { Resvg } = require('@resvg/resvg-js')

const resvg = new Resvg(readFileSync(svgPath), {
  fitTo: { mode: 'width', value: 1200 },
  font: { loadSystemFonts: true, defaultFontFamily: 'Segoe UI' },
})
const png = resvg.render().asPng()
writeFileSync(outPath, png)
console.log(`wrote ${outPath} (${png.length} bytes)`)
