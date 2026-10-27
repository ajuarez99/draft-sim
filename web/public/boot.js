// Spec 050: marks a signed-in visitor before the app mounts, so styles.css can hide the
// pre-rendered landing (html[data-bk-user] .landing-prerender) instead of flashing it.
// Same test as user.ts:readStored -- keep the two in step. Any failure (blocked storage,
// bad JSON) leaves the landing visible, which is the signed-out behaviour anyway.
(function () {
  try {
    var parsed = JSON.parse(localStorage.getItem('bk.user.v1'))
    if (parsed && typeof parsed.sleeperUserId === 'string') {
      document.documentElement.setAttribute('data-bk-user', '1')
    }
  } catch (e) {}
})()
