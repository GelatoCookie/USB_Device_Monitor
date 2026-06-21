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
import com.zebra.rfid.api3.RFIDResults;
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

    /**
     * A first {@code reader.connect()} that takes longer than this indicates a
     * stale session left over from a previous connection. The Zebra SDK
     * recommends issuing a {@code reconnect()} to stabilize the link in that
     * case, otherwise the reader can end up half-connected / unresponsive.
     */
    private static final long SLOW_CONNECT_THRESHOLD_MS = 1000L;

    /**
     * A slow-connect {@code reconnect()} can transiently fail with
     * {@code RFID_COMM_OPEN_ERROR} while the USB serial channel is still busy
     * tearing down the stale session. Retry a few times with a short backoff
     * before giving up.
     */
    private static final int MAX_RECONNECT_RETRIES = 3;
    private static final long RECONNECT_RETRY_DELAY_MS = 200L;

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

    /* Previously installed default handler, restored semantics for real crashes. */
    private Thread.UncaughtExceptionHandler previousUncaughtHandler;
    private boolean crashGuardInstalled = false;

    void onCreate(Context context, StatusListener statusListener) {
        appContext = context.getApplicationContext();
        listener = statusListener;
        installSdkCrashGuard();
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
        // Serialize with connect on the single background thread so the SDK's
        // serial IO manager is never started/stopped concurrently.
        runOnBackground(this::disconnect);
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
            readers = new Readers(appContext, ENUM_TRANSPORT.SERVICE_USB);
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
        } catch (RuntimeException e) {
            // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
            e.printStackTrace();
            initializationInProgress = false;
            readers = null;
            readersAttached = false;
            notifyStatus("RFID init error: " + e.getMessage());
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
            } catch (RuntimeException e) {
                // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
                e.printStackTrace();
                initializationInProgress = false;
                readers = null;
                readersAttached = false;
                notifyStatus("RFID discovery error: " + e.getMessage());
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
        } catch (RuntimeException e) {
            // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
            e.printStackTrace();
            notifyStatus("RFID reader lookup error: " + e.getMessage());
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
            // A slow first connect means a stale session from a prior connection;
            // reconnect once to stabilize the link before configuring it.
            // if (elapsed > SLOW_CONNECT_THRESHOLD_MS && reader.isConnected()) {
            //     Log.d(TAG, "STEP: Slow connect (" + elapsed + "ms > "
            //             + SLOW_CONNECT_THRESHOLD_MS + "ms); reconnecting to stabilize");
            //     notifyStatus("Slow connect (" + elapsed + " ms) - reconnecting to stabilize");
            //     reconnectToStabilize();
            // }
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

    /**
     * Reconnect the reader to stabilize a slow connection, retrying on the
     * transient {@code RFID_COMM_OPEN_ERROR} that can occur while the USB
     * serial channel is still releasing the previous session.
     */
    private void reconnectToStabilize() throws InvalidUsageException {
        for (int attempt = 1; attempt <= MAX_RECONNECT_RETRIES; attempt++) {
            try {
                long reStart = System.currentTimeMillis();
                reader.reconnect();
                long reElapsed = System.currentTimeMillis() - reStart;
                Log.d(TAG, "STEP: Reader Reconnected in " + reElapsed + "ms (attempt "
                        + attempt + ")");
                return;
            } catch (OperationFailureException e) {
                boolean commOpenError = e.getResults() == RFIDResults.RFID_COMM_OPEN_ERROR;
                if (commOpenError && attempt < MAX_RECONNECT_RETRIES) {
                    Log.d(TAG, "STEP: reconnect attempt " + attempt
                            + " failed with RFID_COMM_OPEN_ERROR; retrying");
                    notifyStatus("Reconnect attempt " + attempt
                            + " failed (COMM_OPEN_ERROR) - retrying");
                    sleepQuietly(RECONNECT_RETRY_DELAY_MS);
                } else {
                    Log.e(TAG, "STEP: reconnect failed after " + attempt
                            + " attempt(s): " + e.getResults());
                    e.printStackTrace();
                    return;
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        // Restore the original uncaught-exception handler.
        if (crashGuardInstalled) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
            crashGuardInstalled = false;
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
        // Serialize with connect on the single background thread.
        runOnBackground(this::disconnect);
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

    /**
     * Installs a process-wide uncaught-exception guard that tolerates known
     * Zebra SDK threading artifacts raised on the SDK's own internal threads
     * (which we do not own and cannot wrap in try/catch):
     *
     *  - {@code IllegalStateException("Already running")} from
     *    SerialInputOutputManager during rapid reconnect cycles (the RFD40
     *    re-enumerates on USB right after connect), and
     *  - {@code InterruptedException} from the SerialInputOutputManager receive
     *    thread, which the SDK interrupts to stop it while tearing the serial
     *    channel down on disconnect/reconnect.
     *
     * We swallow only those specific cases and delegate every other throwable to
     * the original handler so genuine crashes still surface normally.
     */
    private void installSdkCrashGuard() {
        if (crashGuardInstalled) {
            return;
        }
        crashGuardInstalled = true;
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isBenignSdkThreadingError(throwable)) {
                Log.w(TAG, "Ignoring benign Zebra SDK threading error on "
                        + thread.getName(), throwable);
                notifyStatus("RFID SDK threading warning ignored: " + throwable.getMessage());
                return;
            }
            if (previousUncaughtHandler != null) {
                previousUncaughtHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private boolean isBenignSdkThreadingError(Throwable t) {
        if (!isFromSerialInputOutputManager(t)) {
            return false;
        }
        // The SDK stops its receive thread by interrupting it; the resulting
        // InterruptedException is expected teardown noise, not a crash.
        if (t instanceof InterruptedException) {
            return true;
        }
        // Starting the serial IO manager twice (USB re-enumeration race) throws
        // IllegalStateException("Already running").
        String msg = t.getMessage();
        return t instanceof IllegalStateException
                && msg != null
                && msg.contains("Already running");
    }

    private boolean isFromSerialInputOutputManager(Throwable t) {
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().contains("SerialInputOutputManager")) {
                return true;
            }
        }
        return false;
    }

    private void beep() {
        runOnBackground(() -> toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200));
    }
}
