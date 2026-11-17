# Contract: what each address answers (Redraft site, fetched without running scripts)

| Address | Status | Indexable | In sitemap | Delivered HTML contains |
|---|---|---|---|---|
| `/` | 200 | yes | yes | title + description with "Redraft" and "past … season"; links to `/daily`, `/nba`, `/nfl` |
| `/daily` | 200 | yes | yes | what the game of the day is; link to play; no season named |
| `/nba`, `/nfl` | 200 | yes | yes | a link to every offered season of that sport |
| `/nba/<label>`, `/nfl/<label>` (offered) | 200 | yes | yes | FR-006 content; own title, description, canonical, og, JSON-LD; prev/next/index links |
| `/nba/<anything else>`, `/xyz` | **404** | no | no | not-found page, links home |
| `/game/<id>` | 200 | **no** (`X-Robots-Tag: noindex`) | no | the app shell; generic preview tags |
| `/history`, `/history/**`, `/leagues/**`, `/drafts/**`, `/mock/**`, `/managers/**`, `/admin/**` | 200 | no (`X-Robots-Tag: noindex`) | no | the app, which redirects as today |
| `/robots.txt`, `/sitemap.xml` | 200 | n/a | n/a | sitemap = exactly the "yes" rows |

- Every indexable page's canonical equals the address that answers 200 with no redirect. If the server redirects `/nba` to `/nba/` (or the reverse), the canonical and sitemap use the form that does not redirect.
- No two indexable pages share a title or a description (a test asserts uniqueness over the page list).

## League site and combined build

Unchanged: every path answers as it does today (`index.html`, status 200), robots and sitemap as spec 073 left them.

## Head tags per season page

- `<title>`: `<label> <NBA|NFL> Redraft: Fantasy <Basketball|Football> Draft Do-Over | Ball Knowers`
- description: one sentence naming the season, the top finisher and the biggest steal, ending with the call to draft it. Built from data; no superlative the data does not back.
- JSON-LD: `WebPage` with `isPartOf` the site's `WebApplication`, plus a `BreadcrumbList` (Home → sport → season). No `Review`, `Rating` or `FAQ` markup: the page has none of those.
