# Spec 052 research notes: options for site analytics

Gathered 2026-10-28 during `/speckit-specify`, for `/speckit-plan` to pick up. **Vendor
prices and limits below come from web search results, several of them third-party
comparison blogs, and were not checked against each vendor's own pricing page.** Treat
every number here as "reported", not verified; the plan stage must re-check whichever
option it picks.

## Constraints the site already imposes (read from the code)

- SPA (react-router): the tool must count client-side route changes, or we call it on
  each route change ourselves.
- `web/serve.json` CSP (report-only today): a hosted tool needs its script origin in
  `script-src` and its collection origin in `connect-src`. A first-party option needs nothing.
- No cookies today, no banner. Keep it that way (FR-005).
- URLs carry league ids and Sleeper user ids → whichever tool, send a route *template*
  (e.g. `/leagues/:id/power`), never the raw path (FR-003, FR-006).
- Admin token gate exists (spec 043) → a first-party owner page can reuse it.

## Options

| Option | Cost (reported) | Cookies | Custom events (US2) | Notes |
|---|---|---|---|---|
| **Umami Cloud** (hosted) | Hobby free, ~100K events/mo, ~6 mo retention; Pro ~$20/mo | No | Yes, with properties (league/sport for US3) | Open source; can later self-host on Railway with the same script. Free-tier website count reported inconsistently (1 vs 3). |
| **Umami self-hosted on Railway** | Railway compute + a Postgres | No | Yes | No third party, but another service to run and keep patched. |
| **Cloudflare Web Analytics** | Free; up to 10 sites without proxying through Cloudflare | No | **No** (page views + performance only) | Would cover US1 only. DNS is at Squarespace, so no Cloudflare proxy. |
| **Plausible** | No free hosted tier; from ~$9/mo (10K page views); self-host CE free | No | Yes | Most polished cookie-free option; costs money. |
| **PostHog Cloud** | Free ~1M events/mo | Configurable (cookieless mode) | Yes, plus funnels/replays | Much bigger than the ask; default config sets cookies, so it must be configured carefully. |
| **GoatCounter** | Free (donation-based); limits not found | No | Limited | Small and simple; terms not confirmed. |
| **Google Analytics 4** | Free | **Yes** | Yes | Sets cookies → banner for EU visitors; violates FR-005. Ruled out unless FR-005 changes. |
| **First-party (our API + Postgres + admin page)** | Zero extra | No | Yes, anything | No third party, no CSP change, data next to league data (easy US3 joins). Cost: a migration, an ingest endpoint, bot filtering, distinct-visitor logic and a dashboard we have to build and verify. Not "now". |

## Leaning (a recommendation, not a decision — FR-013 is open)

For "analytics **now**": **Umami Cloud, free tier**, with route-template page views
sent from the router and a short list of custom events carrying league/sport as
properties (if FR-006 allows). It satisfies FR-005/006/007 out of the box, covers all
three stories, needs one CSP change, and leaves a no-lock-in exit (self-host the same tool
on Railway) if the third-party hosting ever matters. Cloudflare is the fallback if only
US1 is wanted. First-party is the right answer only if "no third party sees any of it"
outweighs "numbers this week".

## Sources

- [Umami Cloud FAQ](https://umami.is/docs/cloud/faq), [Umami Cloud docs](https://docs.umami.is/docs/cloud), [freetier.co: Umami](https://freetier.co/directory/products/umami), [toolpick.dev: Umami pricing](https://www.toolpick.dev/pricing/umami)
- [Cloudflare Web Analytics: about](https://developers.cloudflare.com/web-analytics/about/), [Cloudflare Web Analytics: limits](https://developers.cloudflare.com/analytics/web-analytics/understanding-web-analytics/limits/)
- [PostHog vs Plausible (PostHog blog)](https://posthog.com/blog/posthog-vs-plausible), [Plausible alternatives with free tiers](https://agentdeals.dev/alternative-to/plausible-analytics), [Databuddy: privacy-first analytics 2026](https://www.databuddy.cc/blog/plausible-vs-matomo-vs-privacy-first-analytics-2026)
