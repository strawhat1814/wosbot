# Checklist: after pulling official changes

Use this every time you update `local/daily-driver` from official `main`.
Goal: notice conflicts and risky file changes **before** you overnight on this build.

Work only in: `E:\Desktop\frostguard-daily`

---

## A. Update (do in order)

```powershell
cd E:\Desktop\frostguard-daily
git status
```

- [ ] `git status` is clean (no leftover half-done edits), **or** you know what the dirty files are
- [ ] Fetch official:

```powershell
git fetch origin main
```

- [ ] Merge official into your branch:

```powershell
git merge origin/main
```

### If Git says CONFLICT

- [ ] **Stop.** Do not push. Do not overnight on this folder.
- [ ] Note the file names Git lists
- [ ] Ask for help (or fix carefully), then:

```powershell
git status
# after fixes:
git add <fixed-files>
git commit -s -m "merge: resolve conflicts with origin/main"
```

Only continue this checklist after the merge is finished and `git status` is clean.

### If merge is clean

- [ ] Continue to section B

---

## B. What just landed?

```powershell
# New commits that arrived (including the merge commit)
git log --oneline HEAD@{1}..HEAD

# Which files changed in this update
git diff --stat HEAD@{1}..HEAD
```

- [ ] Skim the commit subjects — anything about schedule, emulator launch, sidebar, trek, storehouse, config/UI?
- [ ] Skim the file list — any paths in the **Watch list** below?

---

## C. Watch list (our work)

For each path that appears in `git diff --stat HEAD@{1}..HEAD`, inspect it:

```powershell
git diff HEAD@{1}..HEAD -- path\to\file
```

### On the fork today

| Feature | Watch these paths | What “good” looks like after update |
|---|---|---|
| LDPlayer runapp | `modules/automation/src/main/java/dev/frostguard/engine/emulator/instance/LDPlayerEmulatorInstance.java` | Still launches via `runapp` (not monkey). Cold start log can later show `LDPlayer runapp`. |
| This checklist / guide | `HOW-I-USE-THE-FORK.md`, `CHECKLIST-AFTER-OFFICIAL-UPDATE.md` | Still present; re-add if a merge removed them (unlikely). |

**Checkboxes for today**

- [ ] Runapp file unchanged by official, **or** I read the diff and our runapp behavior is still there
- [ ] Guide/checklist files still present

### Add when we port each feature into source

| Feature | Watch paths (fill in when ported) | Smoke signal in logs / UI |
|---|---|---|
| Daily idle pause | _(TBD: policy + Human-like UI + config keys)_ | Config → Human-like shows idle controls; log: `Daily idle pause after UTC reset is active` |
| Tundra Idle Trek | _(TBD: routine + templates + destinations)_ | Log: `Starting Idle Trek sequence` / `Idle button found` |
| Storehouse Daily | _(TBD)_ | Log: Online Rewards / Warm Welcome claim lines |
| Growth Mission | _(TBD)_ | Growth task runs without missing-class crashes |
| War Academy | _(TBD)_ | Log: `Looking for Redeem tab to confirm War Academy` |
| Troop promotion | _(TBD)_ | Promotion flow still finds jump arrow / badge |

When a feature is ported, update this table with real paths and tick it on every official merge.

---

## D. Smoke checks (before overnight)

Quit Nightly first, then start from the fork against `default-repaired`:

```powershell
cd E:\Desktop\frostguard-daily
.\mvnw.cmd javafx:run `
  "-Dfrostguard.run.workspace=C:\Users\tacki\.frostguard\workspaces\nightly\default-repaired" `
  "-Dfrostguard.run.channel=nightly"
```

Or Desktop: **`Start Frostguard FROM FORK.cmd`**

- [ ] Window title shows `default-repaired` (not Development)
- [ ] Profiles load
- [ ] App opens (no `Startup failure` / `ClassNotFoundException`)
- [ ] Optional focused tests if you touched that area:

```powershell
.\mvnw.cmd -pl modules/automation -am test
# or modules/tasks, modules/vision, etc.
```

- [ ] Confirm runapp / idle / other must-haves in live logs as needed

---

## E. Save to your fork

Only when A–D look good:

```powershell
git push fork local/daily-driver
```

- [ ] Push succeeded
- [ ] Optional: open https://github.com/strawhat1814/wosbot/tree/local/daily-driver and confirm the latest commit message

---

## F. One-line meanings (cheat sheet)

| Command | Plain meaning |
|---|---|
| `git fetch origin main` | Download official’s newest commits (don’t change your files yet) |
| `git merge origin/main` | Apply those commits onto your branch |
| `git log HEAD@{1}..HEAD` | “What commits did I just get?” |
| `git diff --stat HEAD@{1}..HEAD` | “Which files changed in that update?” |
| `git diff HEAD@{1}..HEAD -- some/File.java` | “Show the exact edits in this file” |
| `git push fork local/daily-driver` | Upload your branch to **your** GitHub fork |

`HEAD@{1}` = where your branch was **before** the last big move (usually the merge). If that looks wrong, use:

```powershell
git reflog -5
```

and pick the commit hash from **before** the merge instead of `HEAD@{1}`.

---

## Reminder

- Clean merge ≠ “my features are safe.” Always skim the watch list + boot once.
- Conflicts = official and you edited the same lines. Fix before push/overnight.
- Do not push to `origin` for daily updates — push to **`fork`**.
