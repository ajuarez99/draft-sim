/**
 * Says which season a page is actually showing, when that is not the season
 * the reader asked for.
 *
 * The rail links league pages at the newest season in the chain, because
 * History and Power Rankings walk the whole chain and do not care. The pages in
 * specs/004-ffwrapped-feature-parity each answer about ONE season, so a league
 * whose new year exists but has not been played sent every one of them to an
 * honest but useless "nothing scored yet" -- measured on the real NBA league,
 * where 2026 is created and empty while 2025 sits finished with 21 weeks.
 *
 * They now fall back to the newest played season. This is the half that keeps
 * that from being a lie by omission: quietly showing a different year would be
 * worse than the refusal it replaces.
 */
export default function SeasonFallbackNote({
  season,
  requestedSeason,
}: {
  season: number
  requestedSeason?: number | null
}) {
  if (requestedSeason == null || requestedSeason === season) return null
  return (
    <p className="muted small season-fallback">
      <strong>{requestedSeason}</strong> has no scored weeks yet, so this is{' '}
      <strong>{season}</strong> — the most recent season this league has played.
    </p>
  )
}
