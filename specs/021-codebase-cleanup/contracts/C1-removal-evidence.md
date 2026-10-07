# C1: Removal evidence

No file, route, asset, branch or worktree is removed without all of the checks below
recorded in the PR or commit body.

## Files, routes and assets

1. **Importers**: grep the basename across `web/src`, `backend/src`, `scripts/`,
   `claude/scripts/` and `.github/`. The expected result is 0 hits outside the item
   itself and the other items being removed in the same change.
2. **Operator docs**: grep `README.md`, `DEPLOY.md` and `HANDOFF.md`. Expected: 0
   instructions to use it. If there is one, the verdict is **keep** (spec edge case:
   "no UI caller is not unused").
3. **Runtime proof** for anything that shipped: after deploy,
   `curl -s -o /dev/null -w "%{http_code}"` against the production URL.
   - `/pr-reference/2a-front-page-desktop.png` must no longer return
     `200 image/png`. Note that the static server may fall back to `index.html`
     with a 200; the check is on content-type, not only the status code.

## Branches and worktrees

1. The candidate passes `git merge-base --is-ancestor <head> origin/main`.
2. A worktree's `git -C <path> status --porcelain` output is empty.
3. The full list is shown to the owner in chat, and they explicitly say yes before
   any `git worktree remove` or `git branch -d` (never `-D`) is run.
