# Zigbee Window Shade/Blind (Timed)

Hubitat (Groovy) driver for Zigbee curtain/blind modules that **don't report their position** — it
**estimates position from movement time** (open/close time + motor delays). Namespace `edu`, hub
name `Zigbee Window Shade/Blind (Timed)`. Own git repo: `esimioni/hubitat-window-shade-timed`.

## File & deploy

- Source: [`window-shade-timed.groovy`](window-shade-timed.groovy) — **this is the source of truth**.
- Distribution: **HPM** via [`manifest.json`](manifest.json) (package "Window Shade Timed").
- Manual deploy: Hubitat → *Drivers Code* → *Import* → *Save*. Always edit this file, never only the
  hub's editor (otherwise the two copies drift apart).

## Supported devices

- **Sonoff MINI-ZBRBS** (ZCL) — recommended (strong signal, works perfectly).
- **LoraTap SC500ZB** (Tuya) — works fine, weaker signal than the Sonoff.
- **Zemismart ZW-EC-01** (Tuya) — not recommended (calibration can't be disabled; occasionally drops
  off the network).
- Generic **ZCL**; similar **Tuya** devices may work (untested).

Tuya vs ZCL is detected by `isSonoff()` (`device.getDataValue('manufacturer') == 'SONOFF'`), which
switches the commands sent/received (`OUT/IN_COMMAND_TUYA_*` vs `*_ZCL_*`) and the parsing.

## How position estimation works

- **No device feedback**: position is computed from time. Key preferences: `openTime`/`closeTime`
  (full-travel time in ms), `openStartDelay`/`closeStartDelay` (motor delay after the relay click),
  `openCloseSafetyMargin`.
- On open/close it records `motionStartTime`; on pause, `updatePosition()` computes how far it moved
  (elapsed time ÷ travel time × 100).
- **Semi-blind (3%)**: fixed time-based position (`semiBlindTime`), for cellular-shade cells.
- **Inverted threshold (≤5%)**: for accuracy near the lower limit, fully close first, then position
  (`state.pendingPosition`).
- **Turbo Mode** (Sonoff only): raises the Zigbee radio power (cluster `0xFC11`, attr `0x0012`, INT16
  9/20), written on *Save Preferences*. ⚠️ It must be a **plain ZCL write, no manufacturer code**: the
  MINI-ZBRBS answers `UNSUPPORTED_ATTRIBUTE` (0x86) to the manufacturer-specific write (mfgCode 0x1286,
  the Sonoff ZBMicro variant in kkossev's driver) — up to 1.1.2 the setting never took effect. Measured
  2026-09-29: plain write → `SUCCESS` on all 11 shades with it enabled. The device's answer is logged
  (`Turbo Mode … confirmed by the device` / `REJECTED … (ZCL status 0x..)`).
- Recovery: `safetyTimeoutCheck` / `recoverStaleState` if a pause command is lost.

## Pure logic under test

Extracted as named pure methods (minimal extraction; the rest of each method only orchestrates /
reads settings):
- `estimatePosition(currentPos, elapsedMs, travelTimeMs, upwards)` — position after moving for X ms;
  rounds the delta to 0.1% (HALF_UP) and clamps to [0, 100]. (was inline in `updatePosition`)
- `calcTravelTime(currentPos, targetPos, travelTimeMs, startDelayMs)` — linear time to cover the %
  distance plus the motor start delay. (was inline in `calcTimeToReach`; semi-blind and the safety
  margin stay in `calcTimeToReach`, which depends on `settings`)
- `turboWriteStatus(descMap)` — the ZCL status of the device's Write Attributes Response on `0xFC11`
  (0 = accepted), or null when the message is something else. (logging stays in `logTurboWriteResult`)

## Tests

Spec lives **in this repo** (travels with the driver):
[`src/test/groovy/windowshadetimed/WindowShadeTimedSpec.groovy`](src/test/groovy/windowshadetimed/WindowShadeTimedSpec.groovy).
The **test infrastructure is shared** and lives in the parent `edu-custom-drivers` workspace (Gradle
+ hubitat_ci + platform shims); its root `./gradlew` auto-discovers this repo's specs. Run from
there:

```bash
cd .. && ./gradlew test --tests '*WindowShadeTimed*'   # this driver only
cd .. && ./gradlew test                                 # everything
```

Because the spec resolves the driver via the `driversRoot` system property set by that build, it
only runs through the parent workspace — a standalone clone of this repo won't run it on its own.
Versions/flags/shims and how to add a new driver: [edu-custom-drivers/CLAUDE.md](../CLAUDE.md).
