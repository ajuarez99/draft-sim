import SignInForm from '../components/SignInForm'
import { featureSports, footerLine, groups, headingOf, headline, platformsNote, sports, subheadline } from '../landingCopy'
import { siteCopy } from '../siteCopy'
import { currentSite } from '../siteEnv'

/**
 * The hard gate at `/` (claude/user-identity-and-onboarding.md §5b). The form itself is
 * components/SignInForm.tsx (shared with the Time Machine front door, spec 073); this page is the
 * league landing around it. On success App renders DraftPicker the instant a user exists.
 */
export default function SignIn() {
  // Spec 073: the sign-in note and the analytics disclosure come from siteCopy, so no screen
  // carries wording its site can't use. On this page (combined and leagues) they are today's words.
  const copy = siteCopy(currentSite())

  return (
    // Spec 050: the signed-out root is the landing page -- a hero that holds the
    // unchanged sign-in form (spec 013 US9: one field, one --brand action), then the
    // rail's own feature groups. Its words come from landingCopy.ts, which
    // landingHtml.ts also writes into the delivered HTML, so keep the element tree and
    // class names here in step with staticBody() there (landingHtml.test.ts compares
    // the text). `.landing` is its own scroll container: `.app` is a fixed 100vh.
    <div className="content landing">
      <header className="landing-hero">
        <p className="landing-kicker cond">Ball Knowers</p>
        <h1 className="landing-headline">{headline}</h1>
        <p className="landing-sub">{subheadline}</p>
        <p className="landing-sports">
          {sports.map((s) => (
            <span key={s} className="landing-sport">{s}</span>
          ))}
        </p>

        <SignInForm notes={[copy.signInNote, copy.analyticsLine]} />

        <p className="landing-platforms muted small">{platformsNote}</p>
      </header>

      {groups.map((g) => (
        <section key={g.key} className="landing-group" data-group={g.key}>
          <h2 className="landing-group-title cond">{headingOf(g.key)}</h2>
          <ul className="landing-tiles">
            {g.features.map((f) => {
              const only = featureSports(f)
              return (
                <li key={f.name} className="landing-tile">
                  <div className="landing-tile-head">
                    <h3 className="landing-tile-name">{f.name}</h3>
                    {only.length === 1 && <span className="landing-tag">{only[0].toUpperCase()}</span>}
                  </div>
                  <p className="landing-tile-line">{f.line}</p>
                </li>
              )
            })}
          </ul>
        </section>
      ))}

      <p className="landing-footer muted small">{footerLine}</p>
    </div>
  )
}
