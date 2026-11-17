import SignInForm from '../components/SignInForm'
import { analyticsLine, keepBody, keepHeading, keepNote } from '../redraftCopy'

/**
 * Spec 074 T038: the sign-in PANEL of the Redraft home, for a guest. (Spec 073 made this the whole front door shown at
 * every URL until sign-in; a guest now sees the real pages and this panel sits at the end of the home page, where the
 * bar's "Sign in" link scrolls to `#sign-in`.) Sign-in is only the existing Sleeper username: no password, email or
 * account. Mirrors the `section#sign-in` in homeBody() (seasonPageHtml.ts), whose form is disabled until React mounts.
 * `autoFocus` is off: a phone would scroll to the bottom and open the keyboard on load.
 */
export default function TimeMachineSignIn() {
  return (
    <section className="tm-signin" id="sign-in">
      <h2>{keepHeading}</h2>
      <p>{keepBody}</p>
      <SignInForm autoFocus={false} notes={[keepNote, analyticsLine]} />
    </section>
  )
}
