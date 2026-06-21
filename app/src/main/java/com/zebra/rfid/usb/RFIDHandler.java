package com.zebra.rfid.usb;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Thin wrapper around the Zebra RFID SDK (com.zebra.rfid.api3) that drives the
 * reader lifecycle from USB attach/detach events:
 *
 *  - {@link #onUsbAttached()}  : initialize the SDK and connect to the reader.
 *  - {@link #onUsbDetached()}  : disconnect and clean up reader state.
 *  - {@link #onDestroy()}      : dispose SDK resources.
 *
 * Adapted from GelatoCookie/TC53eBspTest (RFIDHandler.java), trimmed to the
 * attach/detach connect/cleanup flow used by USB Device Monitor.
 *
 * NOTE: requires the Zebra RFID API3 SDK .aar to be present under app/libs/.
 */
class RFIDHandler implements Readers.RFIDReaderEventHandler {

    /** Callback for surfacing reader status messages to the UI / event log. */
    interface StatusListener {
        void onRfidStatus(String message);
    }

    private static final String TAG = "MYUSB_RFID";
    private static final int MAX_DISCOVERY_RETRIES = 3;

    private Readers readers;
    private ArrayList<ReaderDevice> availableReaders;
    private RFIDReader reader;
    private EventHandler eventHandler;
    private Context appContext;
    private StatusListener listener;
    private final ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* Concurrency guards (mirrors the reference implementation). */
    private volatile boolean initializationInProgress = false;
    private volatile boolean connectionInProgress = false;
    private volatile boolean resumeRequested = false;
    private volatile boolean readersAttached = false;

    void onCreate(Context context, StatusListener statusListener) {
        appContext = context.getApplicationContext();
        listener = statusListener;
    }

    boolean isReaderConnected() {
        return reader != null && reader.isConnected();
    }

    //
    // USB-driven lifecycle entry points
    //

    /** ACTION_USB_DEVICE_ATTACHED -> initialize SDK and connect. */
    void onUsbAttached() {
        Log.d(TAG, "STEP: onUsbAttached -> init RFID SDK + connect");
        resumeRequested = true;
        if (isReaderConnected()) {
            notifyStatus("RFID already connected: " + reader.getHostName());
            return;
        }
        initSdk();
    }

    /** ACTION_USB_DEVICE_DETACHED -> disconnect and clean up. */
    void onUsbDetached() {
        Log.d(TAG, "STEP: onUsbDetached -> disconnect + cleanup");
        resumeRequested = false;
        connectionInProgress = false;
        disconnect();
    }

    void onDestroy() {
        resumeRequested = false;
        dispose();
    }

    //
    // RFID SDK init / connect
    //

    private void initSdk() {
        Log.d(TAG, "STEP: InitSDK");
        if (readers == null) {
            if (initializationInProgress) {
                Log.d(TAG, "STEP: Skip duplicate InitSDK");
                return;
            }
            initializationInProgress = true;
            notifyStatus("Initializing RFID SDK...");
            runOnBackground(this::createInstanceAndConnect);
        } else if (resumeRequested) {
            connectReader();
        }
    }

    /** Enumerates available readers and hands off to the connect path. */
    private void createInstanceAndConnect() {
        try {
            readers = new Readers(appContext, ENUM_TRANSPORT.ALL);
            availableReaders = readers.GetAvailableRFIDReaderList();
            int count = availableReaders == null ? 0 : availableReaders.size();
            Log.d(TAG, "Readers found in ALL transport: " + count);
            if (count == 0) {
                scheduleDiscoveryRetry(0);
                return;
            }
        } catch (InvalidUsageException e) {
            e.printStackTrace();
            initializationInProgress = false;
            readers = null;
            readersAttached = false;
            notifyStatus("Failed to enumerate RFID readers: " + e.getInfo());
            return;
        }
        initializationInProgress = false;
        if (resumeRequested) {
            connectReader();
        }
    }

    private void scheduleDiscoveryRetry(int attempt) {
        if (!resumeRequested || attempt >= MAX_DISCOVERY_RETRIES) {
            initializationInProgress = false;
            if (resumeRequested) {
                notifyStatus("No RFID readers found");
            }
            readers = null;
            readersAttached = false;
            return;
        }
        runOnBackground(() -> {
            try {
                Log.d(TAG, "Retry reader discovery via RE_USB, attempt=" + attempt);
                readers.setTransport(ENUM_TRANSPORT.RE_USB);
                availableReaders = readers.GetAvailableRFIDReaderList();
                if (availableReaders != null && !availableReaders.isEmpty()) {
                    initializationInProgress = false;
                    if (resumeRequested) {
                        connectReader();
                    }
                } else {
                    scheduleDiscoveryRetry(attempt + 1);
                }
            } catch (InvalidUsageException e) {
                e.printStackTrace();
                scheduleDiscoveryRetry(attempt + 1);
            }
        });
    }

    private synchronized void connectReader() {
        if (!resumeRequested || initializationInProgress || connectionInProgress || isReaderConnected()) {
            Log.d(TAG, "STEP: skip duplicate connectReader");
            return;
        }
        connectionInProgress = true;
        runOnBackground(() -> {
            getAvailableReader();
            if (reader == null) {
                connectionInProgress = false;
                notifyStatus("No reader available to connect");
                return;
            }
            String result = connect();
            connectionInProgress = false;
            if (!result.isEmpty()) {
                notifyStatus(result);
            }
        });
    }

    private synchronized void getAvailableReader() {
        if (readers == null) {
            return;
        }
        if (!readersAttached) {
            Readers.attach(this);
            readersAttached = true;
        }
        try {
            ArrayList<ReaderDevice> list = readers.GetAvailableRFIDReaderList();
            if (list == null || list.isEmpty()) {
                return;
            }
            availableReaders = list;
            // Use the first available reader (the attached RFD40 sled over USB).
            reader = list.get(0).getRFIDReader();
            Log.d(TAG, "Selected reader: " + list.get(0).getName());
        } catch (InvalidUsageException e) {
            e.printStackTrace();
        }
    }

    private synchronized String connect() {
        if (reader == null || !resumeRequested) {
            return "";
        }
        try {
            if (reader.isConnected()) {
                return "";
            }
            Log.d(TAG, "connect " + reader.getHostName());
            beep();
            long start = System.currentTimeMillis();
            reader.connect();
            long elapsed = System.currentTimeMillis() - start;
            Log.d(TAG, "STEP: Reader Connected in " + elapsed + "ms");
            configureReader();
            if (reader.isConnected()) {
                return "RFID Connected: " + reader.getHostName() + " (" + elapsed + " ms)";
            }
        } catch (InvalidUsageException e) {
            e.printStackTrace();
        } catch (OperationFailureException e) {
            e.printStackTrace();
            return "RFID connection failed: " + e.getResults();
        }
        return "";
    }

    private void configureReader() {
        if (reader == null || !reader.isConnected()) {
            return;
        }
        try {
            if (eventHandler == null) {
                eventHandler = new EventHandler();
            }
            reader.Events.addEventsListener(eventHandler);
            reader.Events.setHandheldEvent(true);
            reader.Events.setTagReadEvent(true);
            reader.Events.setAttachTagDataWithReadEvent(false);
            reader.Events.setReaderDisconnectEvent(true);
        } catch (InvalidUsageException | OperationFailureException e) {
            e.printStackTrace();
        }
    }

    //
    // Disconnect / cleanup
    //

    private synchronized void disconnect() {
        Log.d(TAG, "STEP: Disconnect");
        try {
            if (reader != null) {
                if (eventHandler != null) {
                    reader.Events.removeEventsListener(eventHandler);
                }
                reader.disconnect();
                reader = null;
                notifyStatus("RFID Disconnected");
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Re-attach on the next connect attempt to avoid stale subscriptions.
            readersAttached = false;
        }
    }

    private synchronized void dispose() {
        disconnect();
        backgroundExecutor.shutdownNow();
        try {
            toneG.release();
            if (readers != null) {
                readers.Dispose();
                readers = null;
                readersAttached = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //
    // Readers.RFIDReaderEventHandler — SDK-level attach/disappear callbacks
    //

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderAppeared " + readerDevice.getName());
        notifyStatus("Reader appeared: " + readerDevice.getName());
        if (resumeRequested) {
            connectReader();
        }
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderDisappeared " + readerDevice.getName());
        notifyStatus("Reader disappeared: " + readerDevice.getName());
        disconnect();
    }

    //
    // Runtime reader events
    //

    public class EventHandler implements RfidEventsListener {
        @Override
        public void eventReadNotify(RfidReadEvents e) {
            if (reader == null || !reader.isConnected()) {
                return;
            }
            TagData[] tags = reader.Actions.getReadTags(100);
            if (tags != null && tags.length > 0) {
                notifyStatus("Tag read: " + tags[0].getTagID() + " (RSSI " + tags[0].getPeakRSSI() + ")");
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents e) {
            STATUS_EVENT_TYPE type = e.StatusEventData.getStatusEventType();
            Log.d(TAG, "Status Notification: " + type);
            if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                notifyStatus("RFID disconnection event — cleaning up");
                runOnBackground(RFIDHandler.this::disconnect);
            }
        }
    }

    //
    // Helpers
    //

    private void notifyStatus(String message) {
        Log.d(TAG, message);
        if (listener != null) {
            mainHandler.post(() -> listener.onRfidStatus(message));
        }
    }

    private void runOnBackground(Runnable task) {
        try {
            backgroundExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Background executor rejected task", e);
        }
    }

    private void beep() {
        runOnBackground(() -> toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200));
    }
}
