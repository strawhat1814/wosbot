# Game UI update — template misses (2026-10-20)

Update landed **today**. Recapture only templates that failed **on the right
screen** with a routine warn/fail in the last 12h (Main A/B/C,
`default-repaired`). Bare `found=0` is not enough — many searches are optional
or state probes.

ANR and ADB are **not** tracked here. Sidebar open-confirm with a tall World
march queue, Bear join-when-full, and shared-login inconclusive polls have
code fixes (collapse/fallback handle; abort full join; bounded Back escalate).

Evidence window: local 2026-10-20 ~06:35–18:35.

## Recapture these (only)

| Template | Why (not just found=0) | Capture 2026-10-20 ~18:25–18:32 |
|---|---|---|
| `ALLIANCE_SHOP_ADVANCED_TELEPORT` | Shop open (`EXPERT_ICON` hit); 27× miss; WARN `Could not determine card coordinates for item: Advanced Teleport` | Saved, but **bytes identical** to prior PNG — look/scroll/region, not a new crop |
| `POLAR_TERROR_TAB_SPECIAL_REWARDS` | Polar search open (`SEARCH_ICON` + magnifying-glass tab hit); 12× miss; `SEARCH_FAILED` / special rewards could not be checked | **New pixels** written |
| `GAME_HOME_SHORTCUTS_FARM_COAL` | Gather COAL path; 8× miss / 0 hit while WOOD/MEAT shortcuts still hit | Saved, but **bytes identical** to prior PNG — look/scroll/region, not a new crop |

Optional watch (do **not** recapture unless you see the control and left ≠ right):
`GAME_HOME_SHORTCUTS_FARM_IRON` (8 miss / 1 hit).

## Do not recapture from found=0 alone

| Template | Why skip |
|---|---|
| `INTEL_CLAIM_ALL`, `INTEL_COMPLETED` | Intel screen verified; nothing to claim is normal |
| `RALLY_TROOP_TRAINING_BUTTON` | Only when zero deployable troops |
| `DEPLOY_CONFIRMATION_DIALOG` | Optional troop-imbalance popup after Deploy |
| `PETS_SKILL_USE` | Skills screen open; Use absent on cooldown is normal |
| `NOMADIC_MERCHANT_VIP`, `REPLENISH_ALL_BUTTON` | Optional / small N |
| `INTEL_SCREEN_2` | Alternate; `INTEL_SCREEN_1` still works |

## Not a template miss

Wilderness Init thrash is **sidebar open confirmation**
(`Sidebar open not confirmed` / `observed=closed/unknown`). Region/classifier
work, not a named PNG from this list.

## Capture

`tools/template-crop/Launch-TemplateCrop.cmd` → **Tracking list** (3 keys) →
compare previous vs live → overwrite only if different.
