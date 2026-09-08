/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
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
