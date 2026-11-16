import SignInForm from '../components/SignInForm'
import { analyticsLine, footerLine, headline, howHeading, signInNote, sports, steps, subheadline } from '../tmLandingCopy'

/**
 * Spec 073: the Time Machine site's front door, shown at whatever URL the visitor opened until they
 * sign in (so a shared game link reaches that game afterward, FR-013). Words come from
 * tmLandingCopy.ts, which landingHtml.ts also writes into the delivered HTML: keep the element tree and
 * class names in step with staticTmBody() there (landingHtml.test.tsx compares the text). It sits in the
 * `.tm` scope like the Time Machine home, and reuses the home's hero classes.
 */
export default function TimeMachineSignIn() {
  return (
    <div className="content tm tm-page tm-door">
      <header className="tm-hero">
        <span className="tm-mark" aria-hidden="true">
          <svg viewBox="0 0 48 48" width="34" height="34" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 24a15 15 0 1 0 5-11.2" />
            <path d="M8 7v7h7" />
            <path d="M24 16v9l6 4" />
          </svg>
        </span>
        <p className="tm-over">Ball Knowers</p>
        <h1 className="tm-title tm-title-sm">{headline}</h1>
        <p className="tm-tagline">{subheadline}</p>
        <p className="landing-sports">
          {sports.map((s) => (
            <span key={s} className="landing-sport">{s}</span>
          ))}
        </p>

        <SignInForm notes={[signInNote, analyticsLine]} />
      </header>

      <section className="tm-section" aria-label={howHeading}>
        <h2>{howHeading}</h2>
        <ol className="tm-steps">
          {steps.map((s) => (
            <li key={s.title}>
              <strong>{s.title}</strong>
              <span>{s.body}</span>
            </li>
          ))}
        </ol>
      </section>

      <p className="landing-footer muted small">{footerLine}</p>
    </div>
  )
}
