package com.zebra.rfid.usb;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MYUSB";
    private static final String TAGUSB = "MYUSB_STATE";
    private static final int RFID_VID = 1504;

    /* USB state / power broadcast actions and extras */
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";
    private static final String EXTRA_USB_CONFIGURED = "configured";
    private static final String EXTRA_USB_MTP = "mtp";
    private static final String EXTRA_USB_PTP = "ptp";
    private static final String EXTRA_USB_MASS_STORAGE = "mass_storage";
    private static final String EXTRA_USB_ADB = "adb";
    private static final String EXTRA_USB_RNDIS = "rndis";

    /* Decoded USB transport state */
    private boolean usbConnected = false;
    private boolean usbConfigured = false;
    private boolean usbFileTransferModeActive = false;

    /* USB interface change counters */
    private int mAttachCount = 0;
    private int mDetachCount = 0;

    /* Runtime Bluetooth permission request (Zebra RFID SDK enumerates bonded devices) */
    private static final int REQ_BT_PERMISSIONS = 1001;

    /* USB system service */
    private UsbManager mUsbManager;

    /* Zebra RFID SDK lifecycle handler */
    private RFIDHandler mRfidHandler;

    /* UI elements */
    private TextView mTvDeviceCount;
    private TextView mTvVid;
    private TextView mTvPid;
    private TextView mTvDeviceName;
    private TextView mTvUsbStatus;
    private TextView mTvAttachCount;
    private TextView mTvDetachCount;
    private TextView mTvTotalCount;
    private TextView mTvEventLog;
    private ScrollView mScrollLog;
    private View     mViewStatusCircle;
    private TextView mTvStatusIcon;
    private TextView mTvStatusTitle;
    private TextView mTvStatusHint;

    private final StringBuilder mEventLogBuilder = new StringBuilder();
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        mTvDeviceCount = findViewById(R.id.tv_device_count);
        mTvVid         = findViewById(R.id.tv_vid);
        mTvPid         = findViewById(R.id.tv_pid);
        mTvDeviceName  = findViewById(R.id.tv_device_name);
        mTvUsbStatus      = findViewById(R.id.tv_usb_status);
        mTvAttachCount    = findViewById(R.id.tv_attach_count);
        mTvDetachCount    = findViewById(R.id.tv_detach_count);
        mTvTotalCount     = findViewById(R.id.tv_total_count);
        mTvEventLog       = findViewById(R.id.tv_event_log);
        mScrollLog        = findViewById(R.id.scroll_log);
        mViewStatusCircle = findViewById(R.id.view_status_circle);
        mTvStatusIcon     = findViewById(R.id.tv_status_icon);
        mTvStatusTitle    = findViewById(R.id.tv_status_title);
        mTvStatusHint     = findViewById(R.id.tv_status_hint);

        mUsbManager = getSystemService(UsbManager.class);

        // Route RFID reader status messages into the event log, and reflect the
        // connection outcome in the status card (prompting the user when the
        // RFD40 is not connected).
        mRfidHandler = new RFIDHandler();
        mRfidHandler.onCreate(this, new RFIDHandler.StatusListener() {
            @Override
            public void onRfidStatus(String status) {
                appendLog("[RFID] " + status);
            }

            @Override
            public void onRfidConnectionResult(boolean connected, String message) {
                if (connected) {
                    showRfidConnected(message);
                } else {
                    showRfd40Prompt(message);
                }
            }
        });

        // The Zebra RFID SDK queries bonded Bluetooth devices on init, which
        // requires the runtime BLUETOOTH_CONNECT/SCAN permissions on API 31+.
        ensureBluetoothPermissions();

        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        int count = deviceList.size();
        Log.d(TAG, "Device List Size: " + count);
        mTvDeviceCount.setText(String.valueOf(count));
        appendLog("Device List Size: " + count);

        if (count > 0) {
            for (UsbDevice device : deviceList.values()) {
                Log.d(TAG, "Device VID: " + device.getVendorId());
                Log.d(TAG, "Device PID: " + device.getProductId());
                mTvVid.setText(String.valueOf(device.getVendorId()));
                mTvPid.setText(String.valueOf(device.getProductId()));
                mTvDeviceName.setText(device.getDeviceName());
                appendLog("Device VID: " + device.getVendorId());
                appendLog("Device PID: " + device.getProductId());
            }
        }
        updateStatusUI(count);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter, Context.RECEIVER_EXPORTED);

        // Active USB broadcast and state monitoring (USB_STATE + power connect/disconnect)
        IntentFilter usbStateFilter = new IntentFilter();
        usbStateFilter.addAction(ACTION_USB_STATE);
        usbStateFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        usbStateFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(mBatteryReceiver, usbStateFilter, Context.RECEIVER_EXPORTED);

        // Seed the initial mode from the sticky USB_STATE broadcast, if available.
        refreshUsbModeFromStickyState();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
        unregisterReceiver(mBatteryReceiver);
        if (mRfidHandler != null) {
            mRfidHandler.onDestroy();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When the app returns to the foreground, clean up any stale SDK state
        // and reconnect to the reader if it is present or becomes available.
        attemptStartupRfidConnection();
    }

    @Override
    protected void onPause() {
        if (mRfidHandler != null) {
            mRfidHandler.onAppBackground();
        }
        super.onPause();
    }

    /** Requests the runtime Bluetooth permissions the RFID SDK needs on API 31+. */
    private void ensureBluetoothPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!needed.isEmpty()) {
            appendLog("Requesting Bluetooth permissions for RFID SDK...");
            requestPermissions(needed.toArray(new String[0]), REQ_BT_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMISSIONS) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            appendLog(allGranted
                    ? "Bluetooth permissions granted"
                    : "Bluetooth permissions denied - RFID connect may fail");

            // Permissions resolved -> attempt the startup RFID connection now.
            // If no RFD40 is attached/awake, the connection-result callback
            // shows the prompt to turn on or attach the device.
            if (allGranted && mRfidHandler != null) {
                appendLog("Permissions granted - starting RFID connect");
                mRfidHandler.startup();
            } else {
                showRfd40Prompt("Bluetooth permission denied");
            }
        }
    }

    /**
     * Startup entry point: initializes the RFID SDK and attempts to connect to
     * the RFD40. When the required Bluetooth permissions are not yet granted,
     * the connect is deferred until {@link #onRequestPermissionsResult}.
     */
    private void attemptStartupRfidConnection() {
        if (mRfidHandler == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            appendLog("Waiting for Bluetooth permissions before RFID connect...");
            return;
        }
        appendLog("Startup: initializing RFID SDK and connecting...");
        mRfidHandler.startup();
    }

    /** Updates the status card to reflect a successful RFID connection. */
    private void showRfidConnected(String message) {
        mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_awake);
        mTvStatusIcon.setText("\u2705");
        mTvStatusTitle.setText("RFD40 Connected");
        mTvStatusTitle.setTextColor(0xFF2E7D32);
        mTvStatusHint.setText((message != null && !message.isEmpty())
                ? message : "RFID reader connected and ready");
    }

    /**
     * Shows a prompt in the status card asking the user to turn on (wake) or
     * attach the RFD40 after a failed RFID connection attempt.
     */
    private void showRfd40Prompt(String reason) {
        mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_sleep);
        mTvStatusIcon.setText("\u26A0\uFE0F");
        mTvStatusTitle.setText("RFD40 Not Connected");
        mTvStatusTitle.setTextColor(0xFFC62828);
        mTvStatusHint.setText("Turn on the RFD40 (hold its trigger to wake it) or attach it via USB");
        if (reason != null && !reason.isEmpty()) {
            appendLog("[RFID] Not connected: " + reason);
        }
        makeText(this, "Please turn on or attach the RFD40", LENGTH_SHORT).show();
    }

    private void updateStatusUI(int deviceCount) {
        if (deviceCount >= 1) {
            mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_awake);
            mTvStatusIcon.setText("⌛");
            mTvStatusTitle.setText("RFD40 Awake");
            mTvStatusTitle.setTextColor(0xFF2E7D32);
            mTvStatusHint.setText("Device connected and ready");
        } else {
            mViewStatusCircle.setBackgroundResource(R.drawable.shape_circle_sleep);
            mTvStatusIcon.setText("💤");
            mTvStatusTitle.setText("Sleep Mode");
            mTvStatusTitle.setTextColor(0xFF546E7A);
            mTvStatusHint.setText("Hold RFD40 trigger to wake up RFID Reader");
        }
    }

    private void appendLog(String message) {
        String line = mTimeFormat.format(new Date()) + "  " + message + "\n";
        mEventLogBuilder.append(line);
        mTvEventLog.setText(mEventLogBuilder.toString());
        mScrollLog.post(() -> mScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Refreshes the attachment/detach/total interface-change counters in the UI. */
    private void updateCountersUI() {
        mTvAttachCount.setText(String.valueOf(mAttachCount));
        mTvDetachCount.setText(String.valueOf(mDetachCount));
        mTvTotalCount.setText(String.valueOf(mAttachCount + mDetachCount));
    }

    private void refreshDeviceInfo() {
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        int count = deviceList.size();
        mTvDeviceCount.setText(String.valueOf(count));
        if (count > 0) {
            UsbDevice device = deviceList.values().iterator().next();
            mTvVid.setText(String.valueOf(device.getVendorId()));
            mTvPid.setText(String.valueOf(device.getProductId()));
            mTvDeviceName.setText(device.getDeviceName());
        } else {
            mTvVid.setText("—");
            mTvPid.setText("—");
            mTvDeviceName.setText("—");
        }
        updateStatusUI(count);
    }

    /**
     * Broadcast receiver for active USB broadcast and state monitoring.
     * Listens to USB_STATE plus power connect/disconnect intents and decodes
     * the current USB transport mode.
     */
    BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAGUSB, "USB Client action: " + action);

            switch (action) {
                case ACTION_USB_STATE:
                    updateUsbModeFromIntent(intent, "broadcast");
                    break;

                case Intent.ACTION_POWER_CONNECTED: {
                    refreshUsbModeFromStickyState();
                    final String mode = usbFileTransferModeActive
                            ? "File Transfer / Debug" : "Power-Only (Charging)";
                    Log.d(TAGUSB, "ACTION_POWER_CONNECTED mode=" + mode
                            + ", connected=" + usbConnected
                            + ", configured=" + usbConfigured
                            + ", fileTransfer=" + usbFileTransferModeActive);
                    mTvUsbStatus.setText(String.format(Locale.US, "POWER CONNECTED  %s", mTimeFormat.format(new Date())));
                    appendLog("USB Client action: " + action);
                    appendLog("Power connected -> " + mode);
                    break;
                }

                case Intent.ACTION_POWER_DISCONNECTED: {
                    usbConnected = false;
                    usbConfigured = false;
                    usbFileTransferModeActive = false;
                    Log.d(TAGUSB, "ACTION_POWER_DISCONNECTED");
                    mTvUsbStatus.setText(String.format(Locale.US, "POWER DISCONNECTED  %s", mTimeFormat.format(new Date())));
                    appendLog("USB Client action: " + action);
                    appendLog("Power disconnected");
                    break;
                }
            }
        }
    };

    /**
     * Reads the sticky USB_STATE broadcast (if any) and refreshes decoded mode.
     */
    private void refreshUsbModeFromStickyState() {
        try {
            Intent stickyUsbState = registerReceiver(null, new IntentFilter(ACTION_USB_STATE));
            if (stickyUsbState != null) {
                updateUsbModeFromIntent(stickyUsbState, "sticky");
            } else {
                Log.d(TAGUSB, "USB_STATE sticky intent unavailable");
            }
        } catch (Exception e) {
            Log.w(TAGUSB, "Failed to refresh USB state from sticky intent", e);
        }
    }

    /**
     * Decodes USB transport mode from a USB_STATE intent.
     * Explicit function extras (mtp/ptp/mass_storage/adb/rndis) take priority;
     * otherwise a connected + configured USB is treated as a data-capable link.
     */
    private void updateUsbModeFromIntent(Intent usbStateIntent, String source) {
        usbConnected = usbStateIntent.getBooleanExtra(EXTRA_USB_CONNECTED, false);
        usbConfigured = usbStateIntent.getBooleanExtra(EXTRA_USB_CONFIGURED, false);

        boolean hasMtp = usbStateIntent.hasExtra(EXTRA_USB_MTP);
        boolean hasPtp = usbStateIntent.hasExtra(EXTRA_USB_PTP);
        boolean hasMassStorage = usbStateIntent.hasExtra(EXTRA_USB_MASS_STORAGE);
        boolean hasAdb = usbStateIntent.hasExtra(EXTRA_USB_ADB);
        boolean hasRndis = usbStateIntent.hasExtra(EXTRA_USB_RNDIS);

        boolean mtp = usbStateIntent.getBooleanExtra(EXTRA_USB_MTP, false);
        boolean ptp = usbStateIntent.getBooleanExtra(EXTRA_USB_PTP, false);
        boolean massStorage = usbStateIntent.getBooleanExtra(EXTRA_USB_MASS_STORAGE, false);
        boolean adb = usbStateIntent.getBooleanExtra(EXTRA_USB_ADB, false);
        boolean rndis = usbStateIntent.getBooleanExtra(EXTRA_USB_RNDIS, false);

        boolean explicitDataModeKnown = hasMtp || hasPtp || hasMassStorage || hasAdb || hasRndis;
        if (explicitDataModeKnown) {
            usbFileTransferModeActive = mtp || ptp || massStorage || adb || rndis;
        } else {
            // Fallback for vendor builds that omit per-function extras:
            // configured USB is treated as data-capable mode.
            usbFileTransferModeActive = usbConnected && usbConfigured;
        }

        final String summary = "USB_STATE[" + source + "] connected=" + usbConnected
                + ", configured=" + usbConfigured
                + ", mtp=" + mtp
                + ", ptp=" + ptp
                + ", mass_storage=" + massStorage
                + ", adb=" + adb
                + ", rndis=" + rndis
                + ", explicitDataModeKnown=" + explicitDataModeKnown
                + ", fileTransfer=" + usbFileTransferModeActive;
        Log.d(TAGUSB, summary);

        runOnUiThread(() -> appendLog(summary));
    }

    /**
     * Broadcast receiver to handle USB attach/detach events.
     */
    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "USB action: " + action);

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB device detached");

                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                String nameInfo = "—";
                if (device != null) {
                    nameInfo = device.getDeviceName() + device.getProductName() + device.getDeviceId();
                    Log.d(TAG, "EXTRA_DEVICE Name=" + nameInfo);
                }

                mDetachCount++;
                mTvUsbStatus.setText(String.format(Locale.US, "DETACHED  %s", mTimeFormat.format(new Date())));
                appendLog("USB action: " + action);
                appendLog("USB device detached");
                appendLog("EXTRA_DEVICE Name=" + nameInfo);
                appendLog("Detach count = " + mDetachCount);
                updateCountersUI();
                refreshDeviceInfo();
                makeText(MainActivity.this, "ACTION_USB_DEVICE_DETACHED", LENGTH_SHORT).show();

                // USB detached -> disconnect the RFID reader and clean up.
                if (mRfidHandler != null) {
                    mRfidHandler.onUsbDetached();
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "ACTION_USB_DEVICE_ATTACHED");

                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                String nameInfo = "—";
                int vid = -1;
                if (device != null) {
                    nameInfo = device.getDeviceName() + device.getProductName()
                            + device.getDeviceId() + device.getVendorId();
                    vid = device.getVendorId();
                    Log.d(TAG, "Name=" + nameInfo);
                    Log.d(TAG, "--> Vid =" + vid);
                }

                mAttachCount++;
                mTvUsbStatus.setText(String.format(Locale.US, "ATTACHED  %s", mTimeFormat.format(new Date())));
                appendLog("USB action: " + action);
                appendLog("ACTION_USB_DEVICE_ATTACHED");
                appendLog("Name=" + nameInfo);
                appendLog("--> Vid =" + vid);
                appendLog("Attach count = " + mAttachCount);
                updateCountersUI();
                refreshDeviceInfo();
                makeText(MainActivity.this, "ACTION_USB_DEVICE_ATTACHED", LENGTH_SHORT).show();
                if (vid == RFID_VID) {
                    makeText(MainActivity.this, "SLED_ZEBRA_ATTACHED VID=" + RFID_VID, LENGTH_SHORT).show();
                }

                // Zebra RFID sled attached -> initialize the SDK and connect.
                if (vid == RFID_VID && mRfidHandler != null) {
                    mRfidHandler.onUsbAttached();
                }
            }
        }
    };

}