# Mercenary Event

## World summon shortcut (1A)

When a new merc needs summoning, prefer the **World HUD** icon over Events.
Icons differ by difficulty; registered templates are tried in order
(`MERCENARY_WORLD_SUMMON`, `_2`, `_3`, …). The HUD tap opens the
merc **card**; the routine then taps **Scout** (`MERCENARY_SCOUT_BUTTON` /
`mercenaryEventScout.png`) or **Challenge** to travel, then uses Attack /
Request Help / Rally on the World action popup. If neither Scout/Challenge nor
the action popup appears, it logs `world-summon-no-scout` and falls back to
Events.

Capture new difficulty icons with `tools/template-crop/TemplateCrop.ps1`
(pick the emulator in the dropdown; presets `mercenaryWorldSummon.png` /
`mercenaryWorldSummon2.png`). Already wired in `WORLD_SUMMON_TEMPLATES`; more
difficulties need a new enum + properties entry and an array append.

## Request Help milestones (2A)

Levels **5, 10, 15, 20, 25** tap **Request Help** (wide blue control under
Attack/Rally), then **Confirm** on the Tips dialog (Mercenary Contract /
Alliance Challenge), and reschedule (~15 minutes). No Attack/Rally march and
no deploy stamina spend on those levels. Missing Request Help or Confirm →
short retry + diagnostic snapshot (unknown outcome), except **Lv.25**: after
Request Help, one ~15m verify visit; if Request Help is still absent (or any
visit would otherwise Attack at 25), rest until daily reset — do not keep
soloing. On the Events card, **Lv.25 + no Scout/Challenge** also rests until
daily reset (do not treat as unconfirmed controls every 5 minutes).

Templates: `MERCENARY_ASK_HELP_BUTTON` (`mercenaryEventAskHelp.png`),
`MERCENARY_ASK_HELP_CONFIRM` (`mercenaryEventAskHelpConfirm.png`).

## Level OCR (two bands)

| Band | When | Area (720x1280) | Text |
|---|---|---|---|
| Pre-scout | Events merc card pink `Lv. N` badge | `355,870`–`435,906` | white on pink |
| Pre-attack | World merc popup title `Lv. N …` | `175,840`–`340,890` | blue on white |

Pre-attack gates the help vs attack branch (required after World-shortcut
travel). Truncated two-digit reads (e.g. 25→2 after a teen level) are rejected
and retried on a wider band.

## Capture helper

```powershell
powershell -ExecutionPolicy Bypass -File tools\template-crop\TemplateCrop.ps1
```

Drag a green rectangle (Shift = square). Presets cover summon, Ask for Help,
and both OCR bands.
