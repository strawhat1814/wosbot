# How to run overnight from your fork

## Short version

1. Quit **Frostguard Nightly** (and Watcher if it stays open).
2. Double-click on Desktop:  
   **`Start Frostguard FROM FORK (real accounts - quit Nightly first).cmd`**
3. Wait for Maven to compile, then the Frostguard window opens.
4. Use your usual accounts — same DB as `default-repaired`.

That window is **your fork code**, not the Nightly installer.

## Practice first (optional, safer)

Double-click: **`Start Frostguard FROM FORK (practice).cmd`**  
Uses a separate empty-ish workspace. OK even if Nightly is still open.

## Manual commands (same thing)

**Practice:**
```powershell
cd E:\Desktop\frostguard-daily
.\mvnw.cmd javafx:run
```

**Overnight:**
```powershell
# Quit Nightly first! Also quit any practice / Development fork window.
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
