# How to run overnight from your fork

## Short version

1. Quit **Frostguard Nightly** (and Watcher if it stays open).
2. Double-click on Desktop: **`Start Frostguard FROM FORK.cmd`**
3. Wait for Maven to compile, then the Frostguard window opens.
4. Title must say **`default-repaired`** (not Development / frostguard-daily).
5. Same accounts as before — workspace `nightly\default-repaired`.

That window is **your fork code**, not the Nightly installer.

## Manual command (same thing)

```powershell
# Quit Nightly first!
cd E:\Desktop\frostguard-daily
.\mvnw.cmd javafx:run `
  "-Dfrostguard.run.workspace=C:\Users\tacki\.frostguard\workspaces\nightly\default-repaired" `
  "-Dfrostguard.run.channel=nightly"
```

(`FROSTGUARD_WORKSPACE` alone is not enough — `javafx:run` sets a JVM workspace flag that must be overridden.)

## Rules

- One app at a time on `default-repaired`.
- Do not start Nightly.exe for overnight once you switch.
- First start can take several minutes (download/compile).

## Telegram

Fork overnight uses the installed **FrostguardNightlyWatcher.exe** (set by the Desktop launcher).
If Telegram commands do nothing: confirm that process is running, or restart via `Start Frostguard FROM FORK.cmd`.
