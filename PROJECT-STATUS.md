# Our projects - status list

Quick scan only. Update the right-hand mark when something changes.

**Marks**

| Mark | Meaning |
|---|---|
| `check` | Done where we need it (official and/or on `local/daily-driver`) |
| `on-fork` | On `local/daily-driver` source - run from `frostguard-daily` |
| `jar-only` | Still only Nightly jar patch - **must port** to fork |
| `upstream` | Already in official Nightly/`main` - no local work needed |
| `todo` | Not started / blocked / unclear |
| `later` | Wanted, but not next |

---

## Must-have overnight features

- Growth Mission - `on-fork`
- Troop promotion (training / promote flow) - `on-fork`
- Daily idle pause (Human-like) - `on-fork`
- Tundra Idle Trek - `on-fork`
- Storehouse Daily (Online Rewards + Warm Welcome) - `on-fork`
- War Academy (Redeem tab) - `on-fork`
- LDPlayer runapp launch - `on-fork` + `check`

## Already upstream (usually leave alone)

- Gather resource level 9 - `upstream` + `check`
- Profile status dots - `upstream` + `check`

## Infra / process

- Fork daily-driver branch (`local/daily-driver`) - `check`
- Beginner guide (`HOW-I-USE-THE-FORK.md`) - `check`
- After-official-update checklist - `check`
- How to run overnight (`HOW-TO-RUN-OVERNIGHT.md`) - `check`
- Desktop: `Start Frostguard FROM FORK.cmd` - `check`
- Retire Nightly jar-swap workflow - `check` (features on fork; use fork launcher for overnight)
- Private overnight run from source - `check` (launcher fixed for `default-repaired`)

## Next

Live smoke from fork overnight (compile OK; live evidence still pending).

---

## After every official merge - tick these if you use them

```
growth ............ on-fork
troop/training .... on-fork
daily idle ........ on-fork
tundra idle trek .. on-fork
storehouse ........ on-fork
war academy ....... on-fork
ldplayer runapp ... on-fork check
```

---

## Folders

| Project | Folder |
|---|---|
| Daily driver (use this) | `E:\Desktop\frostguard-daily` |
| Old review / patches | `E:\Desktop\frostguard-review` |
| Installed Nightly (legacy) | `%LOCALAPPDATA%\Frostguard Nightly\` |

---

Last updated: 2026-09-30 evening - troop, trek, storehouse, war academy ported (compile OK; live evidence pending)
