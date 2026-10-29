# Shared-login dual-emulator handshake

Two profiles can share one game account on different emulators. Link them with
**Shared login peer** in Edit Profile (set on both). Each profile must have its
own Character name (or ID): that character is the one that profile must end on.

## Behavior

During `INITIALIZE`, a paired profile joins an in-process handshake instead of
the normal character verify/switch path. Reconnection-time pauses are suppressed
while the handshake is active.

The same recovery runs mid-day: if either paired profile hits Reconnect, both
queues skip the reconnection-time pause, the peer’s non-Initialize task is
cancelled, and both re-enter `INITIALIZE` for a fresh handshake.

Roles follow the live character on the connected emulator:

- Both already on home → skip Connect/Reconnect; OCR-verify owned characters.
- Both on Reconnect (kicked out / “logged in on another device”) → lower-id profile
  taps Reconnect alone (bootstrap), then roles follow the live character as usual.
- Live = profile A's character and peer shows Reconnect → profile B prep-reconnects
  if needed, switches to B's character, and both fire Connect (B) + Reconnect (A).
- Live = profile B's character and peer shows Reconnect → profile A switches while
  B taps Reconnect at the same barrier.

Inconclusive polls (neither home nor Reconnect) do not count as “disconnected”
and must not force a Switch Character dance. City/World chrome (pets/intel/shop)
counts as connected without opening the profile. Profile OCR runs on a cooldown
and only trusted names (matching a paired character) are used — garbage OCR must
not fail the handshake. Accidental Back opens “Quit Game?”; that dialog is
dismissed via Cancel before observe/verify. Login/promo ads use the same
closeable-overlay dismisser as normal Initialize (no generic Back).

After commit (or the both-home shortcut), both sides OCR-verify their owned
character. A failed verify retries a bounded number of times; it does not fall
back to the uncoordinated reconnect pause loop.

The switcher opens the avatar/profile sheet before Settings (with one Back+retry
if Settings is missing). If Switch Character / Connect fails, the session aborts
for both sides immediately (barrier waiters are released). Initialize re-kicks
the peer and keeps retrying with backoff until the handshake succeeds.

Until that success, a pair gate blocks every non-Initialize task on both
profiles (status: "Paused — shared login not ready"). No farming on the wrong
character. While waiting for a missing peer, Initialize re-requests the peer
join about every 20 seconds.

Once the pair gate is ready, later Initialize runs (before other tasks) only
verify the owned character and skip the handshake. Peer kicks are ignored while
ready so the pair cannot thrash-reset; reconnect recovery markPending()s first,
then the kick is accepted.

Character verify only scores fields that are configured. An unset Character ID
must not make verification succeed (that bug let garbage OCR pass and farm Polar
on the wrong character). A **conclusive** ready-path mismatch (solid OCR of the
wrong identity) clears that profile’s Polar special-rewards budget before
re-handshaking. Inconclusive OCR (null / short junk / failed profile sheet) keeps
the pair ready and treats Initialize as success (no re-handshake, no Init loop).
It must not thrash Quit Game via repeated full handshakes.
When either side reports inconclusive Home/Reconnect polls (stuck on War, Intel,
formation, etc.), Initialize escalates with bounded Back + Quit-Game Cancel so the
handshake does not idle until timeout while the peer is already home.
