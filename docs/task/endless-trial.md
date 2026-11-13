# Endless Trial

Events sibling of Hero Mission (tab next to Hero's Mission). Toggle:
**Endless Trial Event** under Events. Templates: `templates/events/endlesstrial/`.
Operator full frames (uncommitted): `.garbage/emulator-snapshot/endless-trial/`.

## Flow

Windhowler attacks cost **no stamina**. Do not gate on HUD stamina, do not OCR
deploy cost, and do not deduct stamina. Clear any leftover stamina deferral at
visit start. March-queue fullness is handled only if Deploy is missing after
Attack.

1. Open Events → select **Endless Trial** tab (`endlessTrialEventTab.png`).
2. **Before attacking**, OCR Daily Challenges progress (`n/3` in
   `region(55, 1175, 128, 1208)`). If already `3/3`, claim and stop. Otherwise
   only run the remaining attacks (`3 - n`). Do not claim before attacks unless
   already complete.
3. Tap **Go** (`endlessTrialGo.png`) to travel to the world target (Windhowler).
4. On the target card: **Attack** (`endlessTrialAttack.png`, with mercenary
   Attack fallback) → **Deploy** (`endlessTrialDeploy.png`, with shared Deploy
   fallback).
5. Wait ~30s for the march/hit. The post-hit damage popup is **random**: Back
   only when Attack is hidden; never Back when Attack is already visible. Check
   early (~2.5s) and again after the wait.
6. Prefer staying on World while the Attack card remains available (or after a
   tap ~20% above screen center). Re-enter Events → Endless tab → Go **only if**
   the target area was left and Attack is no longer tappable in place. Events
   carousel search resets, scans forward (finger left), then reverses (finger
   right) if the tab was overshot; a still-missing tab retries in ~15 minutes.
7. After remaining attacks, reopen the event. Tap Daily Challenges
   (`region(52, 1143, 130, 1211)` / `endlessTrialCompletion.png`) and tap green
   **Claim** (`endlessTrialClaim.png`). One Claim gathers all ready rows; at most
   two Claim taps.

Unknown Attack/Deploy/Go outcomes do not record completion. After three verified
deploys in a visit, schedule the next UTC daily reset.

## Templates

| File | Role |
|---|---|
| `endlessTrialEventTab.png` | Events carousel tab |
| `endlessTrialGo.png` | Go (travel to target) |
| `endlessTrialAttack.png` | World Attack on Windhowler |
| `endlessTrialDeploy.png` | Deploy / confirm march |
| `endlessTrialCompletion.png` | Daily Challenges entry (gift; counter) |
| `endlessTrialClaim.png` | Green Claim on Daily Challenges rows |
