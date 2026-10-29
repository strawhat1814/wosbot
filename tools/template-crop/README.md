# Template capture assist

Side-by-side helper for post-update recaptures:

1. Pick the **previous template** (left) from `templates.properties`.
2. Pick an **emulator** and **Capture ADB** (right).
3. Compare — only crop/save if the live control still looks different.
4. Drag a green box on the live frame → **Save Crop** (overwrites the selected template after confirm).

Tracking-list keys from `docs/task/game-ui-update-tracking.md` are listed first; use **Tracking list** to show only those.

## Run

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File tools\template-crop\TemplateCrop.ps1
```

Or double-click `tools\template-crop\Launch-TemplateCrop.cmd`.

Optional:

```powershell
powershell -ExecutionPolicy Bypass -File tools\template-crop\TemplateCrop.ps1 `
  -Serial 127.0.0.1:5567 `
  -TemplateKey ALLIANCE_SHOP_ADVANCED_TELEPORT
```

## Save modes (dropdown)

| Choice | Result |
|---|---|
| `overwrite-selected-template` | Writes the crop to the selected TemplatesEnum PNG path |
| mercenary `*.png` presets | Writes under `templates/mercenary/` |
| `ocr-character-name` / `ocr-character-id` | Clipboard `CommonGameAreas` region + crop PNG (shared-login verify) |
| `ocr-pre-*` | Clipboard region only |
| `custom-name.png` | Prompted name under mercenary folder |

Full live frames land in `.garbage/emulator-snapshot/`. Do not commit those.
Privacy-redact before sharing any frame that shows account identity.
