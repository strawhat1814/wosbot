# ADB outage tracking

## Grep markers (frostguard.log)

| Marker | Meaning |
|---|---|
| `OUTAGE marker=ADB_CIRCUIT_OPEN` | Coordinated recovery started; includes host ADB process/version snapshot |
| `OUTAGE marker=ADB_RECOVERY_OK` | Recovery probes succeeded |
| `OUTAGE marker=ADB_RECOVERY_FAILED` | Recovery failed; `causeClass=HOST_ADB_DEAD` or `DEVICES_UNREACHABLE` |
| `OUTAGE marker=ADB_HOST_SUSPENDED` | Host `adb.exe` still dead after bounded kill-server attempts; queues stay paused; kill-server loops stopped; Telegram alert sent once |

Operator path after `ADB_HOST_SUSPENDED`:

1. Dismiss `adb.exe` / `ldconsole.exe` error popups on the PC (or reboot the PC).
2. Telegram `/adbreboot` — clears `SUSPENDED`, runs one coordinated recovery, resumes queues on success.
3. If still suspended: reboot PC, then `/adbreboot` or `/startbot`.

Also correlate Windows **Application** log around the same second for:

- `adb.exe` / `ldconsole.exe` popup or fault `0xc000012d` / `0xc0000142` / `0xc0000409`

## 2026-10-14 outage (evidence)

- **07:39:38** Frostguard: `Connection reset` → circuit OPEN (Stuart)
- **07:39:38** Windows: `adb.exe` Application Error `0xc0000142`
- **07:40:06** Windows: `adb.exe` `0xc000012d`
- **07:40:47–49** Windows: `adb.exe` `0xc0000142`, `ldconsole.exe` `0xc000012d`
- Soft screencap flakes earlier (00:09, ~06:13) did not open the breaker alone
- **16:26** second wave: Windows `adb.exe` `0xc000012d` again → OPEN burst while clients already white

Conclusion: host `adb.exe` crash/init failure is the outage trigger; the breaker storm is amplification on a dead host bridge. `SUSPENDED` exists so Frostguard stops that amplification and pages the operator instead of looping kill-server for hours.
