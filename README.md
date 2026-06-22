# USB Device Monitor

An Android app that monitors USB device attach/detach events for the **Zebra RFD40 RFID Sled** and displays live status on a modern Material UI.

## Features

- **Live status indicator** — large green circle (⌛ RFD40 Awake) when the device is connected; gray circle (💤 Sleep Mode) when disconnected
- **Device details** — shows Device Count, Vendor ID (VID `1504`), Product ID (PID), and device path on connect
- **USB transport & power monitoring** — decodes the active USB mode (Power-Only / Charging vs. File Transfer / Debug) from `USB_STATE` and power connect/disconnect broadcasts
- **Interface-change counters** — running totals of USB **attach**, **detach**, and combined **total** events for the current session
- **Automatic RFID reader control** — on app startup and on RFD40 attach the app initializes the Zebra RFID SDK and connects the reader; on detach it stops using the reader (stale-session cleanup is deferred to the next attach)
- **RFD40 connection prompt** — if the reader cannot connect at startup (sled off or unplugged) the status card prompts the user to *"Turn on the RFD40 (hold its trigger to wake it) or attach it via USB"*
- **Event log** — timestamped scrollable log of all USB attach/detach, transport, power, and RFID reader events
- **Wake hint** — prompts user to *"Hold RFD40 trigger to wake up RFID Reader"* when in sleep mode
- **Toast notifications** on each attach/detach event

## Screenshots

| Awake (Connected) | Sleep Mode (Disconnected) |
|---|---|
| Green circle, ⌛ icon, VID/PID populated | Gray circle, 💤 icon, wake instruction shown |

## Requirements

- Android **API 34+** (Android 14)
- Zebra RFD40 RFID Sled (VID `1504`, PID `5889`)
- USB Host mode enabled on the Android device
- **Zebra RFID API3 SDK** `.aar` placed in `app/libs/` (proprietary — see [app/libs/README.md](app/libs/README.md))

## Project Structure

```
app/src/main/
├── java/com/zebra/rfid/usb/
│   ├── MainActivity.java          # USB BroadcastReceiver + UI logic
│   └── RFIDHandler.java           # Zebra RFID SDK lifecycle (init/connect/disconnect)
├── libs/                          # Zebra RFID API3 SDK .aar goes here
├── res/
│   ├── layout/activity_main.xml   # Material card-based UI
│   └── drawable/
│       ├── shape_circle_awake.xml # Green status circle
│       └── shape_circle_sleep.xml # Gray status circle
build_run.sh                       # Clean → Build → Install → Launch script
```

## Build & Run

### One-command script (requires connected device)

```bash
./build_run.sh
```

This script:
1. Checks for a connected ADB device/emulator
2. Runs `./gradlew clean`
3. Runs `./gradlew assembleDebug`
4. Installs the APK via `adb install -r`
5. Launches `MainActivity`
6. Streams `MYUSB` logcat output

### Manual

```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.zebra.rfid.usb/.MainActivity
```

## USB Event Handling

The app registers **two** runtime `BroadcastReceiver`s.

**`mUsbReceiver`** — host attach/detach:

| Intent Action | Meaning |
|---|---|
| `USB_DEVICE_ATTACHED` | RFD40 plugged in — green circle, populate VID/PID |
| `USB_DEVICE_DETACHED` | RFD40 unplugged — gray circle, clear device info |

**`mBatteryReceiver`** — transport mode + power state:

| Intent Action | Meaning |
|---|---|
| `USB_STATE` | Decode transport mode via `updateUsbModeFromIntent()` (`connected`, `configured`, `mtp`, `ptp`, `mass_storage`, `adb`, `rndis`) |
| `ACTION_POWER_CONNECTED` | Cable connected — classify as *Power-Only (Charging)* or *File Transfer / Debug* |
| `ACTION_POWER_DISCONNECTED` | Cable removed — reset decoded USB state |

**Mode determination:** explicit transport function extras (`mtp` / `ptp` / `mass_storage` / `adb` / `rndis`) take priority; if none are present, a `connected && configured` USB is inferred as a data-capable (file-transfer/debug) link.

See [USB_ATTACH_DETACH_DESIGN.md](USB_ATTACH_DETACH_DESIGN.md) for the full design document with code snippets.

## Interface-Change Counters

The app maintains session counters for USB interface changes, shown in the **USB INTERFACE CHANGES** card:

| Counter | Increments on | Color |
|---|---|---|
| **Attach** (`mAttachCount`) | `ACTION_USB_DEVICE_ATTACHED` | green |
| **Detach** (`mDetachCount`) | `ACTION_USB_DEVICE_DETACHED` | red |
| **Total** | `attach + detach` (derived) | blue |

Each increment is also appended to the event log (`Attach count = N` / `Detach count = N`). Counters are session-scoped — they start at `0` on launch and are not persisted across activity recreation.

## RFID SDK Integration

On startup and whenever the Zebra RFD40 sled is attached/detached, `MainActivity` drives the Zebra RFID API3 SDK through a dedicated `RFIDHandler`:

| Trigger | `RFIDHandler` call | Action |
|---|---|---|
| App startup (after Bluetooth permissions) | `startup()` | Initialize SDK → enumerate readers → connect; on failure show the RFD40 prompt |
| `USB_DEVICE_ATTACHED` (VID `1504`) | `onUsbAttached()` | Clean up any stale session → initialize SDK → enumerate readers → connect → configure event listeners |
| `USB_DEVICE_DETACHED` | `onUsbDetached()` | Best-effort stop the reader's read pipeline (so the SDK IO manager stops polling the dead endpoint); full SDK cleanup is deferred to the next attach |
| `onDestroy()` | `onDestroy()` | Dispose SDK + shut down background executor |

The Zebra RFID SDK enumerates bonded Bluetooth devices on init, so on API 31+ `MainActivity` requests the runtime `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` permissions on launch and runs the startup connect once they are granted.

Reader status (connecting, connected, disconnected, tag reads, errors) is routed back into the event log with a `[RFID]` prefix via a `StatusListener` callback. The same listener also reports a terminal connection result — `onRfidConnectionResult(connected, message)` — that drives the status card: a green **RFD40 Connected** state on success, or an amber **RFD40 Not Connected** prompt on failure. SDK work runs on a single-threaded background executor with `volatile` guards (`initializationInProgress`, `connectionInProgress`, `resumeRequested`, `readersAttached`) to avoid duplicate init/connect races.

> **SDK requirement:** the proprietary Zebra RFID API3 `.aar` is **not** bundled in this repository. Drop it into `app/libs/` (see [app/libs/README.md](app/libs/README.md)). `app/build.gradle` pulls it in via `implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])`. Until present, the `com.zebra.rfid.api3.*` symbols in `RFIDHandler.java` will not resolve and the project will not build.

See [USB_ATTACH_DETACH_DESIGN.md](USB_ATTACH_DETACH_DESIGN.md) §10 for the full integration design.

## Key Log Tags

```
adb logcat -s MYUSB:D MYUSB_STATE:D
```

```
D  Device List Size: 1
D  Device VID: 1504
D  Device PID: 5889
D  USB action: android.hardware.usb.action.USB_DEVICE_DETACHED
D  USB device detached
D  USB action: android.hardware.usb.action.USB_DEVICE_ATTACHED
D  ACTION_USB_DEVICE_ATTACHED
D  --> Vid =1504
```

USB transport/power events are logged under the `MYUSB_STATE` tag:

```
D  USB_STATE[broadcast] connected=true, configured=true, mtp=false, ptp=false, mass_storage=false, adb=true, rndis=false, explicitDataModeKnown=true, fileTransfer=true
D  ACTION_POWER_CONNECTED mode=File Transfer / Debug, connected=true, configured=true, fileTransfer=true
D  ACTION_POWER_DISCONNECTED
```

## License

Apache 2.0
