# ADB outage tracking

## Grep markers (frostguard.log)

| Marker | Meaning |
|---|---|
| `OUTAGE marker=ADB_CIRCUIT_OPEN` | Coordinated recovery started; includes host ADB process/version snapshot |
| `OUTAGE marker=ADB_RECOVERY_OK` | Recovery probes succeeded (may be partial, e.g. 3/4) |
| `OUTAGE marker=ADB_RECOVERY_FAILED` | Recovery failed; `causeClass=HOST_ADB_DEAD` or `DEVICES_UNREACHABLE` |
| `OUTAGE marker=ADB_HOST_SUSPENDED` | Host `adb.exe` still dead after bounded kill-server attempts; queues stay paused; kill-server loops stopped; Telegram alert sent once |

## Coordinated recovery behavior

`/adbreboot` and automatic breaker recovery:

1. Pause queues / suppress captures.
2. `adb kill-server`, then **force-kill** remaining host `adb.exe` processes.
3. Recreate the single ddmlib bridge.
4. Probe each enabled emulator; on ❌ quit+launch that LDPlayer index and poll ADB up to ~60s (staggered).
5. Resume when any device is healthy; Telegram warns on partial / failed.
6. **Auto-recovery quiet ~5 min** after every attempt so Connection-reset thrash cannot reopen the breaker every minute. Manual `/adbreboot` bypasses quiet. Failed recovery also uses a **~5 min cooldown** (captures suppressed).

Operator path after `ADB_HOST_SUSPENDED`:

1. Dismiss `adb.exe` / `ldconsole.exe` error popups on the PC (or reboot the PC).
2. Telegram `/adbreboot` — clears `SUSPENDED`, runs one coordinated recovery, resumes queues on success.
3. If still suspended: reboot PC, then `/adbreboot` or `/startbot` (Frostguard does not auto-start after a Windows reboot unless you configure Windows autostart separately).

Also correlate Windows **Application** log around the same second for:

- `adb.exe` / `ldconsole.exe` popup or fault `0xc000012d` / `0xc0000142` / `0xc0000409`
- `CreateProcess error=1455` (paging file too small) when spawning `adb.exe`

## Prevention margin (host)

Good 4-client days stay under the cliff; bad days tip over it. Reduce tip risk:

- Windows pagefile: system-managed or a fixed size with real headroom (not “disabled”).
- Before overnight: Task Manager → only **one** healthy `adb.exe` after a quiet `/adbreboot`; quit scrcpy / extra SDK platform-tools / other bots on the same ports.
- Do not run Nightly and the fork against the same LDPlayers at once.
- Leave RAM/commit headroom; 4× LDPlayer + OCR is heavy.

## 2026-10-14 outage (evidence)

- **07:39:38** Frostguard: `Connection reset` → circuit OPEN (Stuart)
- **07:39:38** Windows: `adb.exe` Application Error `0xc0000142`
- **07:40:06** Windows: `adb.exe` `0xc000012d`
- **07:40:47–49** Windows: `adb.exe` `0xc0000142`, `ldconsole.exe` `0xc000012d`
- Soft screencap flakes earlier (00:09, ~06:13) did not open the breaker alone
- **16:26** second wave: Windows `adb.exe` `0xc000012d` again → OPEN burst while clients already white

Conclusion: host `adb.exe` crash/init failure is the outage trigger; the breaker storm is amplification on a dead host bridge. `SUSPENDED` + auto-recovery quiet exist so Frostguard stops that amplification and pages the operator instead of looping kill-server for hours.

## 2026-11-22 weekend (evidence)

- Soft `ShellCommandUnresponsive` / offline across Fri–Sun.
- **Sat ~07:30**: `HOST_ADB_DEAD` with `CreateProcess error=1455` (paging file too small) spawning LDPlayer `adb.exe`, `exit=-1073741502`.
- Later: `/adbreboot` `DEVICES_UNREACHABLE` 0/7; Main Profile B thrash with `RECOVERY_OK` **2/3** every ~minute until quiet/escalation fixes.
