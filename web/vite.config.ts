/// <reference types="vitest/config" />
import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { transform } from './src/landingHtml'
import { makeConfig, type SiteConfig } from './src/site'
import { robotsTxt, sitemapXml } from './src/siteFiles'

// Spec 050: link-preview bots and most crawlers don't run scripts, so the title, the
// preview tags and the landing text have to be in the HTML as delivered. Writing them
// here from the same copy module SignIn.tsx renders keeps one source of the words
// instead of a hand-edited index.html that would drift from the page.
//
// Spec 073: which site this build is (VITE_SITE) decides the words, the origin in every
// tag, and robots.txt / sitemap.xml, which are emitted here instead of living in public/.
function landingHtml(cfg: SiteConfig): Plugin {
  const files: Record<string, { type: string; body: string }> = {
    '/robots.txt': { type: 'text/plain; charset=utf-8', body: robotsTxt(cfg) },
    '/sitemap.xml': { type: 'application/xml; charset=utf-8', body: sitemapXml(cfg) },
  }
  return {
    name: 'landing-html',
    transformIndexHtml: { order: 'pre', handler: (html) => transform(html, cfg) },
    generateBundle() {
      for (const [path, f] of Object.entries(files)) {
        this.emitFile({ type: 'asset', fileName: path.slice(1), source: f.body })
      }
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const f = files[(req.url ?? '').split('?')[0]]
        if (!f) return next()
        res.setHeader('Content-Type', f.type)
        res.end(f.body)
      })
    },
  }
}

export default defineConfig(({ mode }) => {
  // makeConfig throws on an unknown VITE_SITE, which fails the build with a message naming the value.
  const cfg = makeConfig(loadEnv(mode, process.cwd(), 'VITE_'))
  return {
    plugins: [react(), landingHtml(cfg)],
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
