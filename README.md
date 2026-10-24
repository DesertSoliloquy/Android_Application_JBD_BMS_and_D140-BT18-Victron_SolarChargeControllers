# Battery & Solar Monitor — Android

A native Android (Kotlin + Jetpack Compose) app that monitors lithium battery
BMSs and solar charge controllers over Bluetooth Low Energy. It is the Android
counterpart to the Windows desktop monitor, and reuses the same reverse-
engineered protocols.

**Read-only, with one deliberate exception.** It never writes to a BMS and
never changes any charge parameter. The *only* command it ever sends is the
D140's **load on/off** toggle (the switchable auxiliary output on that
controller), and only when you flip that switch in the D140's detail view.
Everything else is observation.

---

## Building

Requires **Android Studio** (Koala / Ladybug or newer) with a JDK 17.

1. Open the `BMSMonitorAndroid` folder in Android Studio.
2. If it prompts that the **Gradle wrapper** is missing, let it generate one,
   or run `gradle wrapper --gradle-version 8.9` in the project folder. (The
   wrapper `.jar` isn't shipped in this zip.)
3. Let Gradle sync (it downloads the Android SDK bits and dependencies).
4. Press **Run** with a phone connected, or **Build → Build APK(s)** for an
   installable APK.

Toolchain pinned in `gradle/libs.versions.toml`: Kotlin 2.0.21, AGP 8.7.2,
Compose BOM 2024.10.01. `minSdk 26` (Android 8.0), `targetSdk 35`.

The `applicationId` is `com.offgrid.bmsmonitor` — change it in
`app/build.gradle.kts` before publishing under your own account.

## Screens

- **Summary** — a dense, glanceable one-row-per-device dashboard (SoC / voltage
  / current for batteries; PV watts / battery volts / state for controllers),
  designed to stay readable in a small or split-screen window.
- **Batteries / Solar** — card grids; tap a card for the full detail view.
- **History** — recent-sample trend charts per device.

The **D140 detail view** has a **Load output** switch that turns its auxiliary
load on and off remotely. Entering a **Victron key** validates it immediately
against the latest advertisement, rather than waiting for the next poll.

## First run

- The app opens in **Simulation mode** (synthetic batteries and controllers),
  so the whole UI works with no hardware and no permissions. Flip the **Sim**
  switch off in the top bar to scan for real devices.
- Turning Sim off triggers the **Bluetooth permission** prompt (Android 12+
  asks for Nearby devices; older asks for Location, which BLE scanning needs).
- A **foreground-service notification** appears in live mode so monitoring
  keeps running when the app is backgrounded.

## Victron keys

Victron controllers broadcast encrypted "Instant Readout" data. For each one:
VictronConnect → gear → Product info → enable **Instant readout** → **Encryption
data → Show**, copy the 32-char key. In the app: **Settings (gear) → Controller
keys → Set key**. Stored on-device, entered once.

---

## How it's built

```
model/        Telemetry data classes (BatteryTelemetry / SolarTelemetry)
driver/       Protocol layer — the valuable, verified part
  ByteUtils     endian/CRC helpers
  Driver        ConnectDriver / BroadcastDriver contracts + frame buffer
  ble/          BleConnection: Android GATT wrapped as a coroutine BleLink
  bms/          JBD, JK, Daly, ANT
  solar/        Victron (broadcast), Renogy/RichSolar, D140 (Modbus TCP)
  sim/          Simulator — synthetic devices for the UI
ble/          BleScanner, Poller (connect→read→disconnect), Permissions
data/         SettingsStore, CredentialStore, in-memory History
service/      MonitorService (foreground)
ui/           Compose: ViewModel, card grids, detail views, charts, dialogs
```

**One BLE connection at a time.** The Poller connects, reads, and disconnects
each device in turn (a global mutex), so it never exceeds the phone's
simultaneous-LE-connection ceiling — the same lesson learned on the desktop
side. Victron is passive (broadcast), so it costs no connection.

**Adding a protocol** is one file: implement `ConnectDriver` (or
`BroadcastDriver`) and add it to `DriverRegistry`.

## Supported devices

BMS (BLE connect): **JBD** (= Jiabaida / Xiaoxiang), **JK-BMS**, **Daly**,
**ANT**.
Solar: **Victron SmartSolar** (Instant Readout, needs key), **Renogy / Rich
Solar** (Modbus RTU over BLE), **D140 / BT18** (Modbus TCP over BLE).

## Known limitations / TODO

- **History is in-memory** (recent samples for the trend charts). Persistent
  daily-rollup storage (Room) is a planned addition.
- **D140 charge-state enum** (`0x000B`) values 2/3/4 are known to be charge
  stages but not individually named.
- **Victron battery monitors / SmartShunts** are recognized as Victron but not
  displayed — the app is scoped to chargers and BMS.
