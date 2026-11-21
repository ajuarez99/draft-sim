import { createServer as createHttp } from 'node:http'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { pageHeadTags } from '../src/landingHtml'
import { appShellPage } from '../src/redraftPages'
import { makeConfig } from '../src/site'
import { serveConfig, serverConfig } from '../src/siteFiles'
import { createServer } from './server.mjs'

// Spec 079 T014: the real server on a random port, a temp dist, and a stub backend.
const headers = JSON.parse(readFileSync(join(process.cwd(), 'serve.json'), 'utf8')).headers
const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)))
const close = (srv) =>
  new Promise((r) => {
    srv.closeAllConnections?.()
    srv.close(() => r())
  })

const hits = {}
let root, backend, backendBase

function writeSite(dir, cfg, apiBase) {
  const tm = cfg.site === 'timemachine'
  const dist = join(dir, 'dist')
  mkdirSync(join(dist, 'assets'), { recursive: true })
  writeFileSync(join(dist, 'index.html'), '<html><head><title>home</title></head><body>HOME</body></html>')
  writeFileSync(join(dist, 'assets', 'app.js'), 'const x = "padding padding padding";\n'.repeat(3000))
  if (tm) {
    const shell = `<!doctype html><html><head>\n${pageHeadTags(appShellPage(cfg), cfg)}\n</head><body>SHELL</body></html>`
    writeFileSync(join(dist, 'app-shell.html'), shell)
    writeFileSync(join(dist, 'nba.html'), '<html><head><title>nba</title></head><body>NBA</body></html>')
    writeFileSync(join(dist, '404.html'), '<html><head><title>nope</title></head><body>NOT FOUND</body></html>')
  }
  writeFileSync(join(dir, 'serve.site.json'), JSON.stringify(serveConfig(cfg, headers)))
  writeFileSync(join(dir, 'server.site.json'), JSON.stringify(serverConfig(cfg, { VITE_API_BASE: apiBase })))
  writeFileSync(join(dir, 'package.json'), '{"name":"secret"}')
  return dist
}

async function start(dir) {
  const srv = createServer({
    distDir: join(dir, 'dist'),
    serveConfigPath: join(dir, 'serve.site.json'),
    serverConfigPath: join(dir, 'server.site.json'),
    log: () => {},
  })
  const port = await listen(srv)
  return { srv, base: `http://127.0.0.1:${port}` }
}

let tm, leagues
let finished48 = false
beforeAll(async () => {
  root = mkdtempSync(join(tmpdir(), 'preview-server-'))
  backend = createHttp((req, res) => {
    hits[req.url] = (hits[req.url] ?? 0) + 1
    const m = /^\/api\/history\/public\/games\/(\d+)\/preview$/.exec(req.url)
    if (!m) {
      res.statusCode = 404
      return res.end()
    }
    if (['42', '45', '47'].includes(m[1])) {
      res.setHeader('Content-Type', 'application/json')
      return res.end(JSON.stringify({ title: 'Redraft daily · Nov 21 · Champion', description: 'Draft score 87%', imageAlt: 'alt', card: { x: 1 } }))
    }
    // CR-01: game 48 is a draft in progress (404) until the test flips it finished.
    if (m[1] === '48' && finished48) {
      res.setHeader('Content-Type', 'application/json')
      return res.end(JSON.stringify({ title: 'Finished 48', description: 'Draft score 70%', imageAlt: 'alt' }))
    }
    if (m[1] === '44') return setTimeout(() => res.end('{}'), 3000)
    if (m[1] === '46') return res.end('{"title":1}')
    res.statusCode = 404
    res.end()
  })
  backendBase = `http://127.0.0.1:${await listen(backend)}`
  const tmDir = join(root, 'tm')
  const lgDir = join(root, 'lg')
  writeSite(tmDir, makeConfig({ VITE_SITE: 'timemachine' }), backendBase)
  writeSite(lgDir, makeConfig({ VITE_SITE: 'leagues' }), backendBase)
  tm = await start(tmDir)
  leagues = await start(lgDir)
})
afterAll(async () => {
  await close(tm.srv)
  await close(leagues.srv)
  await close(backend)
  rmSync(root, { recursive: true, force: true })
})

describe('time machine site', () => {
  it("/game/42 answers the shell with that game's tags and the security headers", async () => {
    const res = await fetch(`${tm.base}/game/42`)
    const html = await res.text()
    expect(res.status).toBe(200)
    expect(res.headers.get('content-type')).toBe('text/html; charset=utf-8')
    expect(res.headers.get('cache-control')).toBe('no-cache')
    expect(res.headers.get('content-security-policy-report-only')).toContain("default-src 'self'")
    expect(res.headers.get('x-content-type-options')).toBe('nosniff')
    expect(html).toContain('<title>Redraft daily · Nov 21 · Champion</title>')
    expect(html).toContain('<meta property="og:url" content="https://www.ballknowers.co/game/42" />')
    expect(html).toContain(`content="${backendBase}/api/history/public/games/42/preview.png?v=1"`)
    expect(html).toContain('<meta name="robots" content="noindex">')
    expect(html).toContain('SHELL')
  })

  it('a query string does not matter; HEAD sends headers and no body', async () => {
    const q = await fetch(`${tm.base}/game/42?utm=1`)
    expect(await q.text()).toContain('og:url')
    const head = await fetch(`${tm.base}/game/42`, { method: 'HEAD' })
    expect(head.status).toBe(200)
    expect(await head.text()).toBe('')
  })

  it('caches the words: repeat loads do not call the backend again', async () => {
    await fetch(`${tm.base}/game/45`)
    await fetch(`${tm.base}/game/45`)
    await fetch(`${tm.base}/game/45`)
    expect(hits['/api/history/public/games/45/preview']).toBe(1)
  })

  it('coalesces concurrent first loads of one id into one backend call', async () => {
    await Promise.all([1, 2, 3, 4].map(() => fetch(`${tm.base}/game/47`)))
    expect(hits['/api/history/public/games/47/preview']).toBe(1)
  })

  it('a 404 is not remembered: the room mid-draft, then the finished game, gets the preview at once (CR-01)', async () => {
    expect(await (await fetch(`${tm.base}/game/48`)).text()).not.toContain('Finished 48')
    finished48 = true
    expect(await (await fetch(`${tm.base}/game/48`)).text()).toContain('Finished 48')
  })

  it('backend 404 and a wrong-shaped body fall back to the plain shell', async () => {
    for (const id of ['43', '46']) {
      const res = await fetch(`${tm.base}/game/${id}`)
      const html = await res.text()
      expect(res.status).toBe(200)
      expect(html).toContain('SHELL')
      expect(html).not.toContain('og:url')
      expect(res.headers.get('content-security-policy-report-only')).not.toBeNull()
    }
  })

  it('a slow backend (3 s) costs about the 1 s timeout, then the plain shell, and the failure is remembered', async () => {
    const t0 = Date.now()
    const res = await fetch(`${tm.base}/game/44`)
    const html = await res.text()
    const took = Date.now() - t0
    expect(res.status).toBe(200)
    expect(html).not.toContain('og:url')
    expect(took).toBeGreaterThanOrEqual(900)
    expect(took).toBeLessThan(1800)
    const t1 = Date.now()
    await (await fetch(`${tm.base}/game/44`)).text()
    expect(Date.now() - t1).toBeLessThan(300)
  })

  it('/game/abc is served as before: the rewrite to the shell, not the preview', async () => {
    const res = await fetch(`${tm.base}/game/abc`)
    expect(res.status).toBe(200)
    const html = await res.text()
    expect(html).toContain('SHELL')
    expect(html).not.toContain('og:url')
  })

  it('/ is 200, /nba is nba.html, an unknown path is the real 404', async () => {
    expect((await fetch(`${tm.base}/`)).status).toBe(200)
    const nba = await fetch(`${tm.base}/nba`)
    expect(nba.status).toBe(200)
    expect(await nba.text()).toContain('NBA')
    const nope = await fetch(`${tm.base}/nowhere-at-all`)
    expect(nope.status).toBe(404)
    expect(await nope.text()).toContain('NOT FOUND')
  })

  it('does not publish the config files or package.json that sit beside dist', async () => {
    for (const p of ['/serve.site.json', '/server.site.json', '/package.json', '/../package.json', '/%2e%2e/package.json']) {
      expect((await fetch(`${tm.base}${p}`)).status, p).toBe(404)
    }
  })

  it('compresses a large script when the client accepts gzip, and sends an ETag', async () => {
    const res = await fetch(`${tm.base}/assets/app.js`, { headers: { 'Accept-Encoding': 'gzip' } })
    expect(res.status).toBe(200)
    expect(res.headers.get('content-encoding')).toBe('gzip')
    expect(res.headers.get('etag')).toBeTruthy()
    const again = await fetch(`${tm.base}/assets/app.js`, { headers: { 'If-None-Match': res.headers.get('etag') } })
    expect(again.status).toBe(304)
  })
})

describe('leagues site', () => {
  it('never intercepts /game/<id> and never calls the backend for it', async () => {
    const before = hits['/api/history/public/games/42/preview']
    const res = await fetch(`${leagues.base}/game/42`)
    const html = await res.text()
    expect(res.status).toBe(200)
    expect(html).toContain('HOME')
    expect(html).not.toContain('og:url')
    expect(hits['/api/history/public/games/42/preview']).toBe(before)
  })
})
