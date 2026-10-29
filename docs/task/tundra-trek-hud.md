# Tundra Trek HUD (City / World)

The City/World trek icon appears **only after Idle Trek completes**, when the
reward is ready to claim. Tapping it opens End Idle Trek. It is not shown while
Idle is still running.

## Detection

- Template: `TUNDRA_TREK_HUD_ICON` (`trekHudIcon.png`) — claim icon only.
- Optional OCR band below the match for `current/max` (complete when
  `current >= max`, any max — e.g. `58/58`, `100/100`, `400/400`). Incomplete OCR
  is treated as a miss; do not infer an in-progress Idle run from it.
- Claim: `TUNDRA_TREK_HUD_CLAIM` (`trekHudClaim.png`, End Idle Trek label), with
  fallback to `TUNDRA_TREK_END_IDLE`.

Do not reuse the Dawn Academy in-event counter parser here: that one treats
`0/100` remaining as complete.

## Ownership

HUD claim lives inside **Tundra Trek Automation** only (no separate claim task).

- **Collect only via City/World HUD** (tap map icon → End Idle Trek). Dawn Academy
  must not force-claim, or the map-icon path never runs.
- When the HUD icon is **absent**, start Idle Trek the normal way (Daily sidebar →
  Dawn Academy). Do not stay on the map forever waiting for a claim icon that
  only appears after completion.
- `TUNDRA_TREK_AWAITING_HUD_CLAIM_BOOL` means a claim may become available soon
  (after Idle start, or after End Idle / complete was seen in-event). While set,
  if Idle cannot be started again, keep polling the HUD on the ~15 minute cadence.
- If End Idle is discovered in-event during a start attempt, back out, set the
  awaiting flag, and retry the HUD claim soon.
- After Idle Trek starts successfully, set awaiting and reschedule ~15 minutes later.
- After a successful HUD claim, clear awaiting and reschedule by
  `TUNDRA_TREK_AUTOMATION_INTERVAL_DAYS_INT`.

## Evidence

- Captured on Carl (2026-10-03): native adb `trekHudIcon.png` + End Idle claim.
- Live claim confirmed on Carl: OCR `100/100` → End Idle score 100.
- Live stuck state (2026-10-10): awaiting flag + absent HUD icon blocked Idle
  start for all accounts until the “absent → Dawn Academy start” path was restored.
