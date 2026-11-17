/// <reference types="vitest/config" />
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import { defineConfig, loadEnv, type Plugin, type ResolvedConfig } from 'vite'
import react from '@vitejs/plugin-react'
import type { RedraftSeasonData } from './src/api'
import { pageParts, transform } from './src/landingHtml'
import { appShellPage, homePageEntry, indexablePages, notFoundPage, type RedraftPage } from './src/redraftPages'
import { makeConfig, type SiteConfig } from './src/site'
import { isSafePageFile, robotsTxt, serveConfig, sitemapXml } from './src/siteFiles'
import { ALLOW_EMPTY_ENV, loadSeasonData } from './scripts/fetchSeasons'

// Spec 050: link-preview bots and most crawlers don't run scripts, so the title, the
// preview tags and the landing text have to be in the HTML as delivered. Writing them
// here from the same copy module SignIn.tsx renders keeps one source of the words
// instead of a hand-edited index.html that would drift from the page.
//
// Spec 073: which site this build is (VITE_SITE) decides the words, the origin in every
// tag, and robots.txt / sitemap.xml, which are emitted here instead of living in public/.
//
// Spec 074: the Time Machine site's BUILD (not dev, not vitest) also emits one static HTML file per
// indexable page, plus 404.html and app-shell.html, from season data fetched from the backend (or a fixture).
// Mechanism, measured against a real build (plan-review PR-02): a normal-order plugin's generateBundle does not
// see index.html, and transform() refuses a filled file. So a `post`-order transformIndexHtml hook (a) fills
// index.html as before and (b) captures the built, still-unfilled template, hashed asset tags present; and
// closeBundle fills that template once per other page and writes it into outDir. A serve.site.json is written
// next to outDir for every site (the leagues and combined sites' is one `**` rewrite, equal to `serve -s`).
function landingHtml(cfg: SiteConfig, command: 'build' | 'serve', env: Record<string, string>): Plugin {
  const timeMachineBuild = cfg.site === 'timemachine' && command === 'build'
  let resolved: ResolvedConfig
  let template: string | null = null
  let data: RedraftSeasonData | null = null
  let indexable: RedraftPage[] = []
  let pages: RedraftPage[] = []
  // CR-07: set by buildEnd when the build failed, so closeBundle writes nothing and cannot mask the real error.
  let buildFailed = false

  const sitemap = () => sitemapXml(cfg, timeMachineBuild ? indexable : undefined)
  const files = () => {
    const out: Record<string, { type: string; body: string }> = {
      '/robots.txt': { type: 'text/plain; charset=utf-8', body: robotsTxt(cfg) },
    }
    // The locked league site has no sitemap: not emitted, and the dev server answers 404 for it rather than
    // falling through to index.html (which would hand a crawler a 200 HTML page for a sitemap URL).
    const sm = sitemap()
    if (sm !== null) out['/sitemap.xml'] = { type: 'application/xml; charset=utf-8', body: sm }
    return out
  }
  // The Redraft site's index.html is its home page, made from the same page list as every other page (spec 074): in a
  // build the parts carry the fetched season data (tiles, embedded lists); a dev server has none and writes the bare home.
  const fill = (html: string) =>
    timeMachineBuild && data ? transform(html, cfg, pageParts(homePageEntry(cfg), cfg, data)) : transform(html, cfg)

  return {
    name: 'landing-html',
    configResolved(c) {
      resolved = c
    },
    async buildStart() {
      if (!timeMachineBuild) return
      // Only this branch ever reaches a backend: vitest and `vite dev` have command === 'serve'.
      const fixture = (process.env.REDRAFT_SEASON_DATA ?? '').trim()
      data = await loadSeasonData({
        apiBase: env.VITE_API_BASE ?? '',
        fixturePath: fixture === '' ? undefined : resolve(resolved.root, fixture),
        // Build-time opt-in (CR-08): without it a sport with no seasons fails the build, loudly.
        allowEmptySport: (process.env[ALLOW_EMPTY_ENV] ?? '').trim() === '1',
        log: (line) => console.log(line),
      })
      indexable = indexablePages(cfg, data)
      pages = [...indexable, notFoundPage(cfg), appShellPage(cfg)]
    },
    // timemachine fills index.html AFTER Vite has added the asset tags, so the same hook can capture the built
    // template (build only: ctx.bundle). The other two sites fill it before, exactly as spec 073 did.
    transformIndexHtml: cfg.site === 'timemachine'
      ? {
          order: 'post',
          handler: (html, ctx) => {
            if (ctx.bundle) template = html
            return fill(html)
          },
        }
      : { order: 'pre', handler: fill },
    buildEnd(error) {
      if (error) buildFailed = true
    },
    generateBundle() {
      for (const [path, f] of Object.entries(files())) {
        this.emitFile({ type: 'asset', fileName: path.slice(1), source: f.body })
      }
    },
    closeBundle() {
      // A failed build also reaches closeBundle; nothing to write then, and nothing that could replace its error (CR-07).
      if (buildFailed || !resolved || resolved.command !== 'build') return
      const outDir = resolve(resolved.root, resolved.build.outDir)
      // The Redraft build's season data never arrived (buildStart threw): nothing was built, so nothing is written.
      if (timeMachineBuild && !data) return
      if (timeMachineBuild && data) {
        if (template === null) throw new Error('landing-html: the built index.html template was not captured')
        for (const page of pages) {
          if (page.file === 'index.html') continue
          if (!isSafePageFile(page.file)) throw new Error(`landing-html: refusing to write "${page.file}": not a plain page file name`)
          const target = resolve(outDir, page.file)
          // B1: a page file is made from a season slug, which the fetch already validates; this is the second lock.
          const rel = relative(outDir, target)
          if (rel === '' || isAbsolute(rel) || rel.split(sep).includes('..')) {
            throw new Error(`landing-html: refusing to write ${page.file}: it resolves outside ${outDir}`)
          }
          mkdirSync(dirname(target), { recursive: true })
          writeFileSync(target, transform(template, cfg, pageParts(page, cfg, data)))
        }
        console.log(`[redraft] wrote ${pages.length - 1} static pages + sitemap (${indexable.length} indexable)`)
      }
      // serve.site.json sits NEXT TO dist (not inside it, so it is not published); the Dockerfile copies it.
      const headers = JSON.parse(readFileSync(resolve(resolved.root, 'serve.json'), 'utf8')).headers
      writeFileSync(resolve(outDir, '..', 'serve.site.json'), JSON.stringify(serveConfig(cfg, headers), null, 2) + '\n')
    },
    configureServer(server) {
      const f = files()
      const sm = sitemap()
      server.middlewares.use((req, res, next) => {
        const path = (req.url ?? '').split('?')[0]
        if (sm === null && path === '/sitemap.xml') {
          res.statusCode = 404
          res.end()
          return
        }
        const hit = f[path]
        if (!hit) return next()
        res.setHeader('Content-Type', hit.type)
        res.end(hit.body)
      })
    },
  }
}

export default defineConfig(({ mode, command }) => {
  // makeConfig throws on an unknown VITE_SITE, which fails the build with a message naming the value.
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const cfg = makeConfig(env)
  return {
    plugins: [react(), landingHtml(cfg, command, env)],
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/setupTests.ts'],
    },
    server: {
      port: 5173,
      // Proxying /api means the browser sees one origin and CORS never comes up
      // in dev. The backend's CorsRegistry entry is a belt-and-braces fallback.
      proxy: {
        '/api': {
          // Overridable so a second session can run its own backend beside one
          // that already holds 8080 -- several Claude sessions share this tree
          // and stopping another one's server to test a backend change is a poor
          // trade. `DRAFTSIM_PROXY_TARGET=http://localhost:8081 npm run dev`
          // pairs with `bootRun --args='--server.port=8081'`.
          //
          // Deliberately NOT a `VITE_` name: that prefix means "inline this into
          // the browser bundle", and this is a dev-server concern the client
          // never sees. VITE_API_BASE (api.ts) is the client-side one and is a
          // different thing -- it points the browser at another origin, which
          // then needs CORS; this keeps everything same-origin.
          target: process.env.DRAFTSIM_PROXY_TARGET ?? 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
