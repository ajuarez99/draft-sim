# Quickstart / validation: spec 050

## Local

```bash
cd web && npx tsc -b && npm test && npm run build
```

1. `dist/index.html` contains the `h1`, all four group headings, `og:image` with an
   absolute URL, `summary_large_image`, canonical, JSON-LD; no "Monte Carlo"/"simulat".
2. `npx serve -s dist -l 4173 --config ../serve.json` (same as the Dockerfile), then:
   - `curl -sD - localhost:4173/robots.txt` → 200 `text/plain`, robots body
   - `curl -sD - localhost:4173/sitemap.xml` → 200 XML
   - `curl -s localhost:4173/leagues/1/power` → same HTML as `/`
   - `curl -sD - -o og.png localhost:4173/og-image.png` → `image/png`, 1200×630
3. Browser (built-in pane), signed out, at 390×844 and 1440×900: headline, NFL/NBA and the
   username field above the fold; no horizontal scroll; sign in as a real username →
   league home.
4. Signed in, hard reload `/`: no flash of the landing page.
5. Disable JS (or curl): landing text is readable.

## After deploy (needs Allan's go-ahead to deploy)

- Repeat step 2 against `https://www.ballknowers.co`.
- Paste the URL into Discord/Slack/iMessage; screenshot into verification.md.
- Optional validators: opengraph.xyz, X card validator, LinkedIn Post Inspector.

## Operational step for Allan (not code)

`ballknowers.co` → `www` is a **302** from the registrar's (Squarespace) domain
forwarding. Switch it to a permanent (301) redirect in the Squarespace domain settings.
