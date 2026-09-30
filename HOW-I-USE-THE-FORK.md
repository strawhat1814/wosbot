# How I use my Frostguard fork (beginner guide)

Written for you. Read this when you get back. Nothing here changes the
installed **Frostguard Nightly** app. Your overnight bot can keep running.

---

## 1. What we set up (while you were away)

| Thing | Meaning |
|---|---|
| **Official repo** | `Shederator/wosbot` — Git nickname: `origin` |
| **Your fork** | `strawhat1814/wosbot` — Git nickname: `fork` (your copy on GitHub) |
| **Daily-driver branch** | `local/daily-driver` — the branch you will run from day to day |
| **This folder** | `E:\Desktop\frostguard-daily` — a checkout of that branch |

On GitHub: https://github.com/strawhat1814/wosbot/tree/local/daily-driver

What is already on `local/daily-driver` today:

1. Latest official `main` (as of this setup)
2. Your **LDPlayer runapp** fix (launch game with `runapp`, not monkey)

Still **not** in source yet (only existed as Nightly jar patches before):

- Daily idle pause / Human-like tab
- Tundra Idle Trek extras
- Growth / Storehouse / War Academy overlays that never became commits

Those get ported later, one feature at a time, as real code on this branch.

---

## 2. Plain-language Git dictionary

- **Repository (repo):** the project (all the Frostguard code).
- **Fork:** your personal copy of the official repo on GitHub.
- **Clone / folder:** a copy of a repo on your PC (this folder is one).
- **Branch:** a named line of work. Like a save slot. `local/daily-driver` is yours.
- **Commit:** one saved snapshot with a message.
- **Remote:** a nickname for a GitHub repo.
  - `origin` = official
  - `fork` = yours
- **Push:** upload your commits to GitHub.
- **Fetch / pull:** download new commits from GitHub.
- **Merge:** combine another branch’s commits into yours.
- **PR (pull request):** ask a repo to accept your branch’s commits.
  - Into **official**: optional, when you want Nightly to ship a feature for everyone.
  - You do **not** need a PR just to run your own fork.

---

## 3. Your folders (do not mix them up)

| Folder | Role |
|---|---|
| `E:\Desktop\frostguard-daily` | **Daily driver** — run and develop here going forward |
| `E:\Desktop\frostguard-review` | Older worktree; leave it alone for now |
| `%LOCALAPPDATA%\Frostguard Nightly\` | Installed Nightly app — **do not jar-swap anymore** |
| `~\.frostguard\workspaces\nightly\default-repaired` | Your real account DB / logs (Nightly uses this) |

---

## 4. How you UPDATE from your fork (day to day)

Open PowerShell:

```powershell
cd E:\Desktop\frostguard-daily
```

### A) Get other people’s new official features into your branch

Official keeps moving. Your branch should absorb that now and then.

**Use the full checkbox list:** [`CHECKLIST-AFTER-OFFICIAL-UPDATE.md`](CHECKLIST-AFTER-OFFICIAL-UPDATE.md)

Short version:

```powershell
# 1. Download latest official main (does not change your files yet)
git fetch origin main

# 2. Put those commits into your daily-driver branch
git merge origin/main

# 3. Check what changed (see checklist sections B–D)
git log --oneline HEAD@{1}..HEAD
git diff --stat HEAD@{1}..HEAD

# 4. Save the updated branch up to YOUR fork on GitHub (only if checks look good)
git push fork local/daily-driver
```

If Git says there is a **conflict**, stop and ask for help. Do not panic —
it only means the same file changed in two places and a human must choose.

### B) After you make your own changes (later)

```powershell
cd E:\Desktop\frostguard-daily
git status
git add -A
git commit -s -m "type(scope): short summary of why"
git push fork local/daily-driver
```

`-s` adds a Signed-off-by line (project rule).

### C) Check that GitHub has what you expect

Browser: https://github.com/strawhat1814/wosbot/tree/local/daily-driver

Or:

```powershell
git log -5 --oneline
git status
```

You want: `local/daily-driver` tracking `fork/local/daily-driver`, and
`git status` saying your branch is up to date with the remote after a push.

---

## 5. How you RUN Frostguard from the fork (not from Nightly jars)

**Important:** only one Frostguard should use `default-repaired` at a time.
Quit Nightly (and Watcher) before starting the fork.

### Overnight / real accounts

1. Quit Frostguard Nightly completely.
2. Double-click Desktop: **`Start Frostguard FROM FORK.cmd`**
3. Or:

```powershell
cd E:\Desktop\frostguard-daily
.\mvnw.cmd javafx:run `
  "-Dfrostguard.run.workspace=C:\Users\tacki\.frostguard\workspaces\nightly\default-repaired" `
  "-Dfrostguard.run.channel=nightly"
```

Window title must show **`default-repaired`**. Same accounts/settings as the
repaired Nightly shortcut — code comes from **this folder’s source**.

(`FROSTGUARD_WORKSPACE` alone is not enough with `javafx:run`.)

---

## 6. What “updating” means in practice

There are **two different updates**. Do not confuse them.

| Update type | What it is | What you do |
|---|---|---|
| **Update my fork’s code** | New commits on GitHub / official | `git fetch` + `git merge origin/main` + `git push fork ...` (section 4) |
| **Update installed Nightly** | The Windows installer overwrites jars | Optional. You can ignore Nightly updates for overnight once source-run works |

Goal: overnight bot = **fork source**, not “install Nightly then re-patch jars.”

---

## 7. Project status list (keep this updated)

Short “growth — check / jar-only / todo” list for every feature:

→ [`PROJECT-STATUS.md`](PROJECT-STATUS.md)

After-official merge steps:

→ [`CHECKLIST-AFTER-OFFICIAL-UPDATE.md`](CHECKLIST-AFTER-OFFICIAL-UPDATE.md)

## 8. What we do next (when you are back)

In order, slowly:

1. You read this file and ask about anything confusing.
2. Quit Nightly, run Desktop **`Start Frostguard FROM FORK.cmd`**, confirm title is `default-repaired`.
3. Port remaining features if any; keep `PROJECT-STATUS.md` current.
4. Retire jar-swap patching / Nightly.exe for overnight.

---

## 9. Safety rules

- Do **not** delete or “fix” the Nightly install while learning.
- Do **not** run two bots against `default-repaired` at once.
- Do **not** `git push origin ...` unless you intentionally open/update a PR to official.
  Day-to-day pushes go to **`fork`**.
- Prefer small commits and one feature at a time.

---

## 10. Quick “am I on the right branch?” check

```powershell
cd E:\Desktop\frostguard-daily
git branch --show-current
git remote -v
git log -3 --oneline
```

Expect:

- current branch: `local/daily-driver`
- remotes include `fork` → `strawhat1814/wosbot` and `origin` → `Shederator/wosbot`
- recent log includes the runapp merge on top of recent official merges
