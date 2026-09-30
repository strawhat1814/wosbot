# Our projects — status list

Quick scan only. Update the right-hand mark when something changes.

**Marks**

| Mark | Meaning |
|---|---|
| `check` | Done where we need it (official and/or on `local/daily-driver`) |
| `on-fork` | On `local/daily-driver` source — run from `frostguard-daily` |
| `jar-only` | Still only Nightly jar patch — **must port** to fork |
| `upstream` | Already in official Nightly/`main` — no local work needed |
| `todo` | Not started / blocked / unclear |
| `later` | Wanted, but not next |

---

## Must-have overnight features

- Growth Mission — `jar-only`
- Troop promotion (training / promote flow) — `jar-only`
- Daily idle pause (Human-like) — `jar-only`
- Tundra Idle Trek — `jar-only`
- Storehouse Daily (Online Rewards + Warm Welcome) — `jar-only`
- War Academy (Redeem tab) — `jar-only`
- LDPlayer runapp launch — `on-fork` + `check` (on `local/daily-driver`)

## Already upstream (usually leave alone)

- Gather resource level 9 — `upstream` + `check`
- Profile status dots — `upstream` + `check`

## Infra / process

- Fork daily-driver branch (`local/daily-driver`) — `check`
- Beginner guide (`HOW-I-USE-THE-FORK.md`) — `check`
- After-official-update checklist — `check`
- Retire Nightly jar-swap workflow — `todo` (blocked on porting jar-only items)
- Private overnight run from source (not Nightly.exe) — `todo`

## Porting order (suggested)

1. Daily idle pause — `todo` (next)
2. Growth Mission — `todo`
3. Troop promotion — `todo`
4. Tundra Idle Trek — `todo`
5. Storehouse Daily — `todo`
6. War Academy — `todo`

---

## After every official merge — tick these if you use them

Copy/paste into chat or edit marks above:

```
growth ............ 
troop/training .... 
daily idle ........ 
tundra idle trek .. 
storehouse ........ 
war academy ....... 
ldplayer runapp ... 
```

For each one you care about overnight: skim watch paths in
`CHECKLIST-AFTER-OFFICIAL-UPDATE.md`, then mark `check` or note “broke / needs fix”.

---

## Folders (so you don’t open the wrong one)

| Project | Folder |
|---|---|
| Daily driver (use this) | `E:\Desktop\frostguard-daily` |
| Old review / patches | `E:\Desktop\frostguard-review` |
| Growth worktree | `E:\Desktop\frostguard-growth` |
| Runapp PR worktree | `E:\Desktop\frostguard-runapp-pr` |
| Gather L9 worktree | `E:\Desktop\frostguard-gather-l9` |
| Status dots worktree | `E:\Desktop\frostguard-status-dots` |
| Installed Nightly (legacy) | `%LOCALAPPDATA%\Frostguard Nightly\` |

---

Last updated: 2026-09-30
