# City Upgrade

Building requirement dialogs have different vertical sizes. Fire Crystal
buildings can show additional prerequisite rows, which moves the final blue
`Upgrade` action below the position used by younger cities.

City Upgrade therefore detects the fixed `Upgrade` label inside the right-hand
bottom action region and taps the detected match. The adjacent premium `Finish`
action is outside that region. After the tap, the routine requires the Upgrade
dialog evidence to disappear and the Home anchor to return before requesting
alliance help or processing a second construction queue. A missing button or an
unproven Home transition is a bounded unresolved attempt and returns through
normal Home recovery.

Saved-frame coverage uses
`modules/tasks/src/test/resources/city/fire-crystal-building-upgrade-ready-20260821.png`.
The frame preserves the lower Fire Crystal action position without account
identifiers.

## Growth Mission city dialog

After Growth Mission Go opens a normal building (Storehouse, Furnace, etc.), the
Building road taps ~30px below screen center to clear the guide hand, looks for the
up-arrow, and if still missing taps once more (max 2 per execute; dismiss budget
resets each run). If the arrow is still missing after that, exit **without Back/ESC**
— Back closes the upgrade bubble. Furniture missions never center-tap.

Successful live matches often land near `(360–418, 879–883)` at ~99%. Alternate
template: `templates/building/upgradeButton.png` (legacy fallback). Retrace without
the guide hand: `patches/Trace-KevinCityUpgradeArrow.bat`.

Retrace helper: `patches/Trace-KevinCityUpgradeArrow.bat` (Kevin).

## Growth Mission Go vs Claim

Incomplete Main missions show blue **Go**; completed ones show green **Claim** in
the same slot. Open flow (upper Main band only, `y` 140–620, right column):

1. Claim repeatedly until Claim is gone (safety cap 12). Claim template must
   match the current green Main pill (live ~`y` 315–370); the older smaller crop
   scored only ~48 against today's button.
2. Only then search for Main Go in the upper card (`y` ≤ 480). Do not accept Go
   matches on Side rows (`y` ~546+) — those are blue Side **Go** buttons and
   false-start the building road with no city up-arrow while the queue stays Idle.
3. If neither Claim nor Go: dismiss and retry in 3 hours.

Do not search lower Side/list rows (false Main Go hits near `y` ~910) and do not
use a generic panel-colored Go fallback.

Bottom-bar **Growth tab** is the left blue pill (`x` < 360). Do not tap
Growth-selected template hits near the screen center/right — those match the
Daily selected pill and leave the panel on Daily. Prefer unselected Growth on
the left, else the fixed `GROWTH_TAB_FALLBACK_POINT` (~210,1166). Clamp taps to
`y` ≤ 1174 — Heroes home nav is ~160–217, `y` ≥ 1190, and a low Growth tap
opens Heroes. Success requires Growth title **and** Daily tab not selected
(title alone can false-positive).

## Growth Mission construction timer

After a successful building Upgrade, Growth opens the CITY left menu, captures a
fresh frame (OCR must not reuse the pre-sidebar Home screenshot), and OCRs the
two construction queue timer strips (`y` ~370 and ~443). It retries up to 5 times
with settle delays. If still unreadable, it falls back to a 20-minute retry and
logs rejected raw OCR text on the final attempt.

If Upgrade was tapped but Home is not confirmed in time
(`POSTCONDITION_NOT_MET`), Growth still treats construction as started and OCRs
the queue — otherwise the next Growth Go can run while a build is already busy.
A city up-arrow miss also checks the construction queue before the default retry
(no arrow often means the building is already upgrading).

## Growth Mission Telegram notify

When Telegram is enabled, Growth Mission pushes to the configured chat on:

- building construction started (header Upgrade confirmed);
- furniture piece progress (Upgrade/Next loop made progress);
- all Growth missions complete.

## Growth Mission furniture vs building roads

After Go, detect the furniture panel first (bottom-left "Furniture" marker).
Furniture road skips center dismiss (no hand). Building road center-taps to
clear the guide hand, looks for the up-arrow, and if still missing center-taps
once more (max 2) before giving up.
Roads are logged as `Furniture road` / `Building road`:

1. **Furniture road** — panel marker found; no center dismiss.
   - **Save steel** (default, City Upgrades checkbox): single **Upgrade** taps,
     **Next** between pieces, refuse steel-cost furniture.
   - **Fast hold** (save-steel off): repeated **2s** holds on piece Upgrade.
     After each release only check **main (header) Upgrade** (tap+confirm) and
     **Next** when a piece is done. No steel/resource-cost inspect. Cap 8 holds.
   - All pieces done: **building header Upgrade** (confirm OK) starts construction.
   Cap 16 piece/hold steps per run.
   Retrace: `patches/Trace-KevinFurnitureUpgrade.bat`,
   `patches/Trace-KevinFurnitureNextAndPanel.bat`.
2. **Building road** — no furniture panel marker: center dismiss (retry once if
   up-arrow missing), then city up-arrow → panel/dialog Upgrade.

