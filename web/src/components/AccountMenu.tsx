import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import Avatar from './Avatar'
import { usePopup } from '../popupFocus'

type Props = {
  username: string
  displayName: string | null
  avatarId: string | null
  onSignOut: () => void
}

/**
 * The avatar button and its sign-out menu, lifted out of TopBar so the Time Machine's slim bar
 * (spec 073) uses the same one. Same classes, same behaviour: a menu, not a mode, so navigating
 * closes it; Escape / outside click go through usePopup.
 */
export default function AccountMenu({ username, displayName, avatarId, onSignOut }: Props) {
  const location = useLocation()
  const name = displayName || username
  const [open, setOpen] = useState(false)
  const menu = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    setOpen(false)
  }, [location.pathname])
  usePopup(open, () => setOpen(false), menu, button)

  return (
    <div className="topbar-account">
      <button
        type="button"
        ref={button}
        className="topbar-account-button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account: ${name}`}
        title={name}
      >
        <Avatar avatarId={avatarId} seed={username} label={name} />
      </button>
      {open && (
        <div className="topbar-account-menu" role="menu" ref={menu}>
          <span className="topbar-account-name">{name}</span>
          <button
            type="button"
            role="menuitem"
            className="topbar-account-item"
            onClick={() => {
              setOpen(false)
              onSignOut()
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
