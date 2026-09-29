# 06 — The frontend sends no security headers

**Severity: Medium. Verified against production** with a read-only
`curl -sD - -o /dev/null https://www.ballknowers.co/` (2026-09-29).

## What's there now

- The response carries only `content-type`, `etag`, `accept-ranges`, `content-disposition`
  and Railway's own headers. There is no CSP, no `frame-ancestors`/`X-Frame-Options`, no HSTS,
  no `X-Content-Type-Options` and no `Referrer-Policy`.
- `web/Dockerfile:20-24` serves `dist/` with an unpinned `npm install -g serve` and
  `serve -s dist`. It has no config file, so nothing sets headers. Railway's edge adds none.
- External origins the built app loads (from grepping `web/src`, `web/index.html` and `web/public`):
  - `VITE_API_BASE` = `https://api.ballknowers.co` (`src/api.ts:168`). This covers fetch and the live `EventSource`.
  - `https://sleepercdn.com/avatars/thumbs/…` for `<img>` (`src/components/Avatar.tsx:10`).
  - `https://fonts.googleapis.com` for the stylesheet `@import` (`src/styles.css:1`), plus `https://fonts.gstatic.com` for the font files.
  - `index.html` has no inline scripts. React `style={{}}` props go through CSSOM, which CSP doesn't block.
  - `PowerRankings.verify.tsx:294` iframes a same-origin page. `frame-ancestors 'self'` is fine for that; `'none'` would break it.

## Fix

1. Pin `serve` to a version in `web/Dockerfile`. Add `web/serve.json` with a `headers` block,
   `COPY` it into the runtime stage, and pass it explicitly (`serve -s dist --config ../serve.json`
   or wherever it lands). Confirm the flag and path against the pinned version locally with
   `curl -D -`. Don't put the file in `public/`, or it gets published.
2. Phase 1, **`Content-Security-Policy-Report-Only`**:
   `default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com;
   font-src https://fonts.gstatic.com; img-src 'self' data: https://sleepercdn.com;
   connect-src 'self' https://api.ballknowers.co; frame-ancestors 'self'; base-uri 'self';
   object-src 'none'; form-action 'self'`.
   Phase 1 also ships the three headers that can't break anything:
   - `X-Content-Type-Options: nosniff`
   - `Referrer-Policy: strict-origin-when-cross-origin`
   - `Strict-Transport-Security: max-age=31536000`, with **no** `includeSubDomains`
     until every `*.ballknowers.co` host is confirmed HTTPS-only.
3. Click every page on production with DevTools open: sign-in, leagues, board, mock, live,
   /managers, power rankings, weekly report and history. Include an avatar-heavy page. Fix the
   policy for any violation, then rename the header to `Content-Security-Policy` in a second deploy.
4. Decide separately whether the backend (`api.ballknowers.co`) needs `nosniff` and HSTS too.
   It's a separate service, and this plan doesn't cover it.
5. Add a short "Security headers" section to `DEPLOY.md`: the headers live in `web/serve.json`,
   and adding a new external origin means updating the CSP.

## Not in scope

- Nonce- or hash-based CSP, CSP reporting endpoints, HSTS preload.

## Acceptance criteria

- [ ] After deploy, `curl -sD - -o /dev/null https://www.ballknowers.co/` shows all headers.
      Quote the output in the verification write-up.
- [ ] A deep link like `/managers` (SPA fallback) carries the same headers.
- [ ] Zero CSP violations in the console across every page above, and avatars and fonts render.
      Do this under Report-Only first, then again after enforcing.
- [ ] `DEPLOY.md` names where the headers live.
