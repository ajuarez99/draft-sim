/**
 * One person, named the same way on every league page: the Sleeper team name
 * first, the Sleeper username as a muted second line. The second line is
 * dropped when it would only repeat the first (Sleeper's team name falls back
 * to the display name for a manager who never set one), so nobody reads the
 * same string twice.
 *
 * `fallback` is what a roster nobody owns is called ("roster 4").
 */
export default function PersonName({
  teamName,
  username,
  fallback,
}: {
  teamName?: string | null
  username?: string | null
  fallback?: string
}) {
  const team = teamName?.trim() || username?.trim() || fallback || ''
  const user = username?.trim()
  const showUser = !!user && user.toLowerCase() !== team.toLowerCase()
  return (
    <span className="person-name">
      <span className="person-team">{team}</span>
      {showUser && <span className="person-user">{user}</span>}
    </span>
  )
}
