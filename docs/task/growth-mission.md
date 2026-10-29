# Growth Mission

Growth opens Missions, switches to the **Growth** tab, drains Main **Claim**,
then taps Main **Go** and follows **furnace**, furniture, or building upgrade
roads.

## Upgrade roads after Go

1. **Furnace** — `FurnacePanelDetector` (title). Upgrade may be the orange furnace
   entry or the same blue Upgrade as completed furniture (header / furniture
   template in the furnace button band). Needs live testing.
2. **Furniture** — furniture panel marker → fast-hold / save-steel road.
3. **Building** — else header Upgrade or city up-arrow.

## Missions tab bar (1 / 2 / 3 tabs)

Bottom tabs can be **Daily**, **Growth**, and **Chapter** (early levels). Count
and order change; Growth is not always left.

Traced tab-strip OCR region (Stuart Copy Debug, 2026-10-10):
`TopLeft(73, 1116)` → `BottomRight(650, 1147)` (label band only).

**Tab strip** = Missions tab labels `(73,1116)–(650,1147)`.
Log `Tab-strip OCR: "s"` means that region only OCR’d the letter `s` (failed
read), not the word Growth.

Growth tab locate: **OCR first**, then **unselected Growth template** (this is
what worked before OCR-only). One tap only — a second unselected match is
Chapter. No left-point fallback.

OCR is biased to the known tab vocabulary **Daily / Growth / Chapter**: near-miss
tokens (`growlh`, `chapler`) canonicalize onto those words. Total garbage
(`s`, `eeee`) still rejects — templates cover that.

Flow: locate Growth → Claim → Go. If neither Claim nor Go: **second Growth tap**
(OCR then template) → recheck. Still none → abort for **1 hour**.

## Fast furniture order

Inspect **main Upgrade** and **Next** first (including a short preflight and a
re-check immediately before any hold). Only then 2s-hold a piece Upgrade. Never
hold when the building header Upgrade is already available.

## Claim / Go / title

Title, Claim, and Go still prefer templates; OCR backups exist for Growth title
and Main Claim/Go when those templates miss. Reject Claim-containing OCR when
deciding Go.

## Fragile assumptions

- City up-arrow after Go still uses templates (threshold 70). Scores in the low
  60s with no arrow are treated as already-building and OCR the construction
  queue.
- Construction-queue timer OCR prefers the **CITY left sidebar**. When that is
  unavailable (early accounts) or unreadable, experimental fallback OCRs the
  city-map band `TopLeft(271, 347)` → `BottomRight(432, 385)`. **Still needs
  heavy live testing** (camera / building position). If both miss → fixed ~20 min.
- `POSTCONDITION_NOT_MET` is Home-not-proven after Upgrade, not missing resources.
- Resource refill: Obtain → wait/poll for **Replenish All** (wider bottom band,
  relaxed/multi-scale template, OCR backup). Stuart Copy previously failed with
  `REPLENISH_BUTTON_MISSING` when Obtain opened but Replenish was not seen in
  500ms inside the old tight 90% window.
