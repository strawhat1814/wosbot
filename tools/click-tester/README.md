# Click tester

Pick an emulator, set **X** and **Y** (720×1280 device pixels), press **TAP**.
Uses ADB `input tap` — same path as Frostguard, not a mouse click on the window.

## Run

Double-click `Launch-ClickTester.cmd`, or:

```powershell
powershell -ExecutionPolicy Bypass -File tools\click-tester\ClickTester.ps1
powershell -ExecutionPolicy Bypass -File tools\click-tester\ClickTester.ps1 -Serial 127.0.0.1:5563 -X 2 -Y 582
```

## UI

1. **Emulator** — Main A / B / C (or any online serial). Refresh if needed.
2. **X** / **Y** — tap point.
3. **TAP** (or Enter) — sends the tap to that emulator.
4. **Capture mark** — screencap with a crosshair at X,Y.

Quick buttons fill primary / fallback centers from the current sidebar bands.
