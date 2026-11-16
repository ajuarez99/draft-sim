import { Navigate, useLocation, useParams, type Params } from 'react-router-dom'

/**
 * Spec 073: an old address this same site still understands (`/history` on the Time Machine site,
 * `/game/:id` on the combined one). Keeps the query string and hash. `to` builds the path from the route
 * params, so each tree names its target with the site.ts helpers instead of a literal.
 */
export default function InAppRedirect({ to }: { to: (params: Readonly<Params>) => string }) {
  const params = useParams()
  const { search, hash } = useLocation()
  return <Navigate to={`${to(params)}${search}${hash}`} replace />
}
