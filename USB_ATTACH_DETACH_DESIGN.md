# USB Attach / Detach Handling — Design Document

**Project:** `com.zebra.rfid.usb`  
**Target Device:** Zebra RFD40 RFID Sled (VID `1504`)  
**Min SDK:** 34 (Android 14)

---

## Overview

Android exposes USB host events via the `UsbManager` system service and broadcasts two key actions:

| Action | Constant | When fired |
|---|---|---|
| `android.hardware.usb.action.USB_DEVICE_ATTACHED` | `UsbManager.ACTION_USB_DEVICE_ATTACHED` | USB device plugged in |
| `android.hardware.usb.action.USB_DEVICE_DETACHED` | `UsbManager.ACTION_USB_DEVICE_DETACHED` | USB device unplugged |

The app handles both with a single `BroadcastReceiver` registered at runtime.

---

## Architecture

```
┌─────────────────────────┐
│       Android OS        │
│  UsbManager broadcasts  │
└────────────┬────────────┘
             │  Intent (action + EXTRA_DEVICE)
             ▼
┌─────────────────────────┐
│  BroadcastReceiver      │  ◄── registered in onCreate()
│  mUsbReceiver           │       unregistered in onDestroy()
└────────────┬────────────┘
             │  runOnUiThread()
             ▼
┌─────────────────────────┐
│  MainActivity (UI)      │
│  • updateStatusUI()     │  green circle = awake
│  • refreshDeviceInfo()  │  gray  circle = sleep
│  • appendLog()          │
└─────────────────────────┘
```

---

## 1. IntentFilter Registration

Register the receiver **programmatically** (not via manifest) so it receives events while the app is in the foreground.

```java
IntentFilter filter = new IntentFilter();
filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);   // "android.hardware.usb.action.USB_DEVICE_ATTACHED"
filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);   // "android.hardware.usb.action.USB_DEVICE_DETACHED"
filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
registerReceiver(mUsbReceiver, filter);
```

> **Important:** Always pair `registerReceiver()` in `onCreate()` with `unregisterReceiver()` in `onDestroy()` to avoid memory leaks.

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    unregisterReceiver(mUsbReceiver);
}
```

---

## 2. BroadcastReceiver Implementation

```java
BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "USB action: " + action);

        // ── DETACH ──────────────────────────────────────────────────
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            Log.d(TAG, "USB device detached");

            // EXTRA_DEVICE is available on detach (device already gone from bus)
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.d(TAG, "EXTRA_DEVICE Name=" + device.getDeviceName()
                        + device.getProductName() + device.getDeviceId());
            }

            runOnUiThread(() -> {
                refreshDeviceInfo();   // re-query UsbManager (count will drop to 0)
                updateStatusUI(0);     // → gray circle, sleep hint
            });
        }

        // ── ATTACH ──────────────────────────────────────────────────
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            Log.d(TAG, "ACTION_USB_DEVICE_ATTACHED");

            // On attach, device is in the Intent extras under key "device"
            UsbDevice device = (UsbDevice) intent.getExtras().get("device");
            int vid = (device != null) ? device.getVendorId() : -1;
            Log.d(TAG, "--> Vid =" + vid);

            final int finalVid = vid;
            runOnUiThread(() -> {
                refreshDeviceInfo();                  // re-query UsbManager (count now ≥ 1)
                if (finalVid == RFID_VID)
                    updateStatusUI(1);                // → green circle, awake state
            });
        }
    }
};
```

---

## 3. Initial Device Enumeration on Launch

On startup, query `UsbManager` before registering the receiver to handle the case where the RFD40 is already connected:

```java
UsbManager mUsbManager = getSystemService(UsbManager.class);

HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
int count = deviceList.size();
Log.d(TAG, "Device List Size: " + count);         // e.g. "Device List Size: 1"

for (UsbDevice device : deviceList.values()) {
    Log.d(TAG, "Device VID: " + device.getVendorId());   // e.g. 1504
    Log.d(TAG, "Device PID: " + device.getProductId());  // e.g. 5889
}

updateStatusUI(count);   // immediately reflect awake/sleep state
```

---

## 4. Status UI Logic

```java
private static final int RFID_VID = 1504;   // Zebra VID

private void updateStatusUI(int deviceCount) {
    if (deviceCount >= 1) {
        // Green circle — device awake
        mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_awake);
        mTvStatusIcon.setText("⌛");
        mTvStatusTitle.setText("RFD40 Awake");
        mTvStatusTitle.setTextColor(0xFF2E7D32);
        mTvStatusHint.setText("Device connected and ready");
    } else {
        // Gray circle — sleep / disconnected
        mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_sleep);
        mTvStatusIcon.setText("💤");
        mTvStatusTitle.setText("Sleep Mode");
        mTvStatusTitle.setTextColor(0xFF546E7A);
        mTvStatusHint.setText("Hold RFD40 trigger to wake up RFID Reader");
    }
}
```

---

## 5. Key Log Messages and Their Meaning

```
Device List Size: 1          → RFD40 present on startup
Device VID: 1504             → Zebra vendor confirmed
Device PID: 5889             → RFD40 product ID

USB action: android.hardware.usb.action.USB_DEVICE_DETACHED
USB device detached
EXTRA_DEVICE Name=/dev/bus/usb/004/004  RFD4031-G00B700-E8::::EA4004

USB action: android.hardware.usb.action.USB_DEVICE_ATTACHED
ACTION_USB_DEVICE_ATTACHED
Name=/dev/bus/usb/004/005  RFD4031-G00B700-E8::::EA4005  1504
--> Vid =1504
```

---

## 6. Manifest — USB Device Filter (Optional Deep-Link)

To launch the app automatically when the RFD40 is plugged in, add to `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/usb_device_filter" />
</activity>
```

`res/xml/usb_device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="1504" product-id="5889" />
</resources>
```

> This is **not required** for the runtime receiver — only needed if you want the OS to auto-launch the app on plug-in.

---

## 7. Lifecycle Summary

```
onCreate()
    └── enumerate existing devices  →  updateStatusUI(count)
    └── registerReceiver(mUsbReceiver, filter)

onReceive(ATTACHED)
    └── runOnUiThread → refreshDeviceInfo() → updateStatusUI(1)  [green]

onReceive(DETACHED)
    └── runOnUiThread → refreshDeviceInfo() → updateStatusUI(0)  [gray]

onDestroy()
    └── unregisterReceiver(mUsbReceiver)
```
