package com.zebra.rfid.usb;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
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
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MYUSB";
    private static final int RFID_VID = 1504;

    /* USB system service */
    private UsbManager mUsbManager;

    /* UI elements */
    private TextView mTvDeviceCount;
    private TextView mTvVid;
    private TextView mTvPid;
    private TextView mTvDeviceName;
    private TextView mTvUsbStatus;
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
        mTvEventLog       = findViewById(R.id.tv_event_log);
        mScrollLog        = findViewById(R.id.scroll_log);
        mViewStatusCircle = findViewById(R.id.view_status_circle);
        mTvStatusIcon     = findViewById(R.id.tv_status_icon);
        mTvStatusTitle    = findViewById(R.id.tv_status_title);
        mTvStatusHint     = findViewById(R.id.tv_status_hint);

        mUsbManager = getSystemService(UsbManager.class);

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
        registerReceiver(mUsbReceiver, filter);

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
     * Broadcast receiver to handle USB attach/detach events.
     */
    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "USB action: " + action);

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB device detached");

                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String nameInfo = "—";
                if (device != null) {
                    nameInfo = device.getDeviceName() + device.getProductName() + device.getDeviceId();
                    Log.d(TAG, "EXTRA_DEVICE Name=" + nameInfo);
                }

                final String logName = nameInfo;
                runOnUiThread(() -> {
                    mTvUsbStatus.setText("DETACHED  " + mTimeFormat.format(new Date()));
                    appendLog("USB action: " + action);
                    appendLog("USB device detached");
                    appendLog("EXTRA_DEVICE Name=" + logName);
                    refreshDeviceInfo();
                    makeText(MainActivity.this, "ACTION_USB_DEVICE_DETACHED", LENGTH_SHORT).show();
                });
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "ACTION_USB_DEVICE_ATTACHED");

                UsbDevice device = (UsbDevice) intent.getExtras().get("device");
                String nameInfo = "—";
                int vid = -1;
                if (device != null) {
                    nameInfo = device.getDeviceName() + device.getProductName()
                            + device.getDeviceId() + device.getVendorId();
                    vid = device.getVendorId();
                    Log.d(TAG, "Name=" + nameInfo);
                    Log.d(TAG, "--> Vid =" + vid);
                }

                final String logName = nameInfo;
                final int finalVid = vid;

                runOnUiThread(() -> {
                    mTvUsbStatus.setText("ATTACHED  " + mTimeFormat.format(new Date()));
                    appendLog("USB action: " + action);
                    appendLog("ACTION_USB_DEVICE_ATTACHED");
                    appendLog("Name=" + logName);
                    appendLog("--> Vid =" + finalVid);
                    refreshDeviceInfo();
                    makeText(MainActivity.this, "ACTION_USB_DEVICE_ATTACHED", LENGTH_SHORT).show();
                    if (finalVid == RFID_VID)
                        makeText(MainActivity.this, "SLED_ZEBRA_ATTACHED VID=" + RFID_VID, LENGTH_SHORT).show();
                });
            }
        }
    };

}