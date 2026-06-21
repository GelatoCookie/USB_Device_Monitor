# USB Device Monitor

An Android app that monitors USB device attach/detach events for the **Zebra RFD40 RFID Sled** and displays live status on a modern Material UI.

## Features

- **Live status indicator** — large green circle (⌛ RFD40 Awake) when the device is connected; gray circle (💤 Sleep Mode) when disconnected
- **Device details** — shows Device Count, Vendor ID (VID `1504`), Product ID (PID), and device path on connect
- **USB transport & power monitoring** — decodes the active USB mode (Power-Only / Charging vs. File Transfer / Debug) from `USB_STATE` and power connect/disconnect broadcasts
- **Event log** — timestamped scrollable log of all USB attach/detach, transport, and power events
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

## Project Structure

```
app/src/main/
├── java/com/zebra/rfid/usb/
│   └── MainActivity.java          # USB BroadcastReceiver + UI logic
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
