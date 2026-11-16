/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL prepended to every `/api/...` path. Blank (local default) means same-origin. */
  readonly VITE_API_BASE?: string
  /** Sent as `Authorization: Bearer <token>` on every request when set. See DEPLOY.md. */
  readonly VITE_API_TOKEN?: string
  /** Spec 052: Umami Cloud website id. Absent or blank = analytics off (nothing loads, nothing is sent). */
  readonly VITE_UMAMI_WEBSITE_ID?: string
  /** Spec 052: '1' in dev logs would-be analytics payloads with console.debug instead of loading the tracker. */
  readonly VITE_ANALYTICS_DEBUG?: string
  /** Spec 073: 'timemachine' | 'leagues'; unset or blank = the combined single site. Anything else fails the build. */
  readonly VITE_SITE?: string
  /** Spec 073: the Time Machine site's origin (no trailing slash). Defaults to https://www.ballknowers.co. */
  readonly VITE_TM_ORIGIN?: string
  /** Spec 073: the league site's origin (no trailing slash). Defaults to https://leagues.ballknowers.co. */
  readonly VITE_LEAGUES_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
