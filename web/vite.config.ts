/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { transform } from './src/landingHtml'

// Spec 050: link-preview bots and most crawlers don't run scripts, so the title, the
// preview tags and the landing text have to be in the HTML as delivered. Writing them
// here from the same copy module SignIn.tsx renders keeps one source of the words
// instead of a hand-edited index.html that would drift from the page.
function landingHtml(): Plugin {
  return { name: 'landing-html', transformIndexHtml: { order: 'pre', handler: (html) => transform(html) } }
}

export default defineConfig({
  plugins: [react(), landingHtml()],
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
})
