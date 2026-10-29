# How to run overnight from your fork

## Short version

1. Quit **Frostguard Nightly** (leave Telegram watcher alone, or let the launcher restart it).
2. Double-click on Desktop: **`Start Frostguard FROM FORK.cmd`**
3. Wait for Maven to compile, then the Frostguard window opens.
4. Title must say **`default-repaired`** (not Development / frostguard-daily).
5. Same accounts as before — workspace `nightly\default-repaired`.

That window is **your fork code**, not the Nightly installer.

## Manual command (same thing)

```powershell
# Quit Nightly first!
cd E:\Desktop\frostguard-daily
.\Launch-Frostguard-Fork.cmd
```

(`FROSTGUARD_WORKSPACE` alone is not enough — `javafx:run` sets a JVM workspace flag that must be overridden.)

## Rules

- One app at a time on `default-repaired`.
- Do not start Nightly.exe for overnight once you switch.
- First start can take several minutes (download/compile).

## Telegram

1. Watcher: `Start-Frostguard-Fork-Watcher.cmd` (needs a prior build so fork watcher classes exist).
2. `/launch` starts the **fork** via `Launch-Frostguard-Fork.cmd` (`botLauncherPath` in workspace watcher props + `-Dfrostguard.launcher`).
3. `/startbot` starts the **task queue** (routines) inside the already-running app. It does not start Nightly.
4. Stats: `/stats` = all-time, `/dailystats` = today UTC (`/allstats` still works as an alias for `/stats`).
5. While the fork is already open, normal bot commands talk to it on port 23001.
6. If Telegram is silent: run the watcher script, confirm a `TelegramWatcher` java process with `Launch-Frostguard-Fork.cmd` in its command line, try `/whelp`.
