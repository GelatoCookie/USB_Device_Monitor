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

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Thin wrapper around the Zebra RFID SDK (com.zebra.rfid.api3) that drives the
 * reader lifecycle from USB attach/detach events:
 *
 *  - {@link #onUsbAttached()}  : clean up any stale session, then init the SDK and connect.
 *  - {@link #onUsbDetached()}  : stop using the reader (cleanup deferred to next attach).
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

        /**
         * Reports a terminal connect outcome so the UI can prompt the user.
         *
         * @param connected true once the reader is connected, false when a
         *                  connect attempt finished without a usable reader.
         * @param message   human-readable detail for the log / prompt.
         */
        void onRfidConnectionResult(boolean connected, String message);
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

    /**
     * App foreground / resume -> clean up any stale session, then init the SDK
     * and attempt to connect.
     */
    void onAppForeground() {
        Log.d(TAG, "STEP: onAppForeground -> cleanup stale session + init RFID SDK + connect");
        prepareForConnect();
    }

    /**
     * App background / suspend -> disconnect quietly so the reader is not left
     * active while the activity is no longer visible.
     */
    void onAppBackground() {
        Log.d(TAG, "STEP: onAppBackground -> disconnect quietly");
        resumeRequested = false;
        connectionInProgress = false;
        runOnBackground(this::disconnectQuietly);
    }

    /**
     * App startup -> initialize the RFID SDK and attempt to connect to an
     * already-attached RFD40. If no reader is present / reachable, the
     * connection-result callback reports the failure so the UI can prompt the
     * user to turn on or attach the RFD40.
     */
    void startup() {
        Log.d(TAG, "STEP: startup -> init RFID SDK + attempt connect");
        onAppForeground();
    }

    /** ACTION_USB_DEVICE_ATTACHED -> clean up any stale session, then init SDK and connect. */
    void onUsbAttached() {
        Log.d(TAG, "STEP: onUsbAttached -> cleanup stale session + init RFID SDK + connect");
        prepareForConnect();
    }

    private void prepareForConnect() {
        resumeRequested = true;
        // Tear down any leftover session from a previous connection FIRST, then
        // (re)initialize and connect. Serialized on the single background
        // thread, then init is posted back to the main looper so the connect
        // path runs after cleanup.
        runOnBackground(() -> {
            if (readers != null) {
                readers.Dispose();
                readers = null;
                readersAttached = false;
            }
            mainHandler.post(this::initSdk);
        });
    }

    /** ACTION_USB_DEVICE_DETACHED -> stop using the reader; cleanup deferred to next attach. */
    void onUsbDetached() {
        Log.d(TAG, "STEP: onUsbDetached -> stop reading (cleanup deferred to next attach)");
        resumeRequested = false;
        connectionInProgress = false;
        // The in-flight read that fails at the instant of a physical detach is
        // logged INSIDE the SDK (RFIDSerialIOMgr: "Queueing USB request failed")
        // tens of milliseconds before this broadcast even arrives, so that single
        // line cannot be prevented from app code. What we can do is best-effort
        // stop the SDK's tag-read pipeline so its serial IO manager is not left
        // actively polling the now-dead endpoint, which keeps it to that one
        // benign occurrence instead of cascading read errors. Every SDK call is
        // guarded because the USB endpoint is already gone.
        final RFIDReader detached = reader;
        final EventHandler handler = eventHandler;
        runOnBackground(() -> stopReaderQuietly(detached, handler));
        // NOTE: a full reader.disconnect() is intentionally still NOT performed
        // here; the stale session is torn down at the start of the next
        // onUsbAttached() instead.
    }

    /**
     * Best-effort, fully-guarded stop of the SDK's event/read pipeline used on
     * USB detach. The endpoint is already gone, so any of these calls may throw
     * (or trigger the SDK's own serial IO error); all such failures are expected
     * teardown noise and are swallowed here.
     */
    private void stopReaderQuietly(RFIDReader r, EventHandler handler) {
        if (r == null) {
            return;
        }
        try {
            r.Events.setTagReadEvent(false);
            r.Events.setHandheldEvent(false);
        } catch (Exception e) {
            Log.d(TAG, "Detach: ignoring expected error disabling events on dead endpoint: "
                    + e.getMessage());
        }
        try {
            if (handler != null) {
                r.Events.removeEventsListener(handler);
            }
        } catch (Exception e) {
            Log.d(TAG, "Detach: ignoring expected error removing listener on dead endpoint: "
                    + e.getMessage());
        }
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
            Log.e(TAG, "Failed to enumerate RFID readers", e);
            initializationInProgress = false;
            readers = null;
            readersAttached = false;
            notifyConnectionResult(false, "Failed to enumerate RFID readers: " + e.getInfo());
            return;
        } catch (RuntimeException e) {
            // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
            Log.e(TAG, "RFID init error", e);
            initializationInProgress = false;
            readers = null;
            readersAttached = false;
            notifyConnectionResult(false, "RFID init error: " + e.getMessage());
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
                notifyConnectionResult(false, "No RFID readers found");
            }
            readers = null;
            readersAttached = false;
            return;
        }
        runOnBackground(() -> {
            try {
                Log.d(TAG, "Retry reader discovery via SERVICE_USB, attempt=" + attempt);
                readers.setTransport(ENUM_TRANSPORT.SERVICE_USB);
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
                Log.e(TAG, "Reader discovery failed", e);
                scheduleDiscoveryRetry(attempt + 1);
            } catch (RuntimeException e) {
                // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
                Log.e(TAG, "RFID discovery error", e);
                initializationInProgress = false;
                readers = null;
                readersAttached = false;
                notifyConnectionResult(false, "RFID discovery error: " + e.getMessage());
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
                notifyConnectionResult(false, "No reader available to connect");
                return;
            }
            String result = connect();
            connectionInProgress = false;
            if (isReaderConnected()) {
                notifyConnectionResult(true,
                        result.isEmpty() ? "RFID Connected: " + reader.getHostName() : result);
            } else {
                notifyConnectionResult(false,
                        result.isEmpty() ? "RFID connection failed" : result);
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
            Log.e(TAG, "Error looking up available readers", e);
        } catch (RuntimeException e) {
            // e.g. SecurityException when BLUETOOTH_CONNECT is not granted.
            Log.e(TAG, "RFID reader lookup error", e);
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
            configureReader();
            if (reader.isConnected()) {
                return "RFID Connected: " + reader.getHostName() + " (" + elapsed + " ms)";
            }
        } catch (InvalidUsageException e) {
            Log.e(TAG, "Connect failed", e);
        } catch (OperationFailureException e) {
            Log.e(TAG, "Connect failed: " + e.getResults(), e);
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
        int attempt = 1;
        while (true) {
            try {
                long reStart = System.currentTimeMillis();
                reader.connect();
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
                    attempt++;
                    sleepQuietly(RECONNECT_RETRY_DELAY_MS);
                } else {
                    Log.e(TAG, "STEP: reconnect failed after " + attempt
                            + " attempt(s): " + e.getResults(), e);
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
            Log.e(TAG, "Failed to configure reader", e);
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
                notifyConnectionResult(false, "RFID Disconnected");
                playDisconnectBeep();
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "Disconnect error", e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected disconnect error", e);
        } finally {
            // Re-attach on the next connect attempt to avoid stale subscriptions.
            readersAttached = false;
        }
    }

    private synchronized void disconnectQuietly() {
        Log.d(TAG, "STEP: Disconnect quietly");
        try {
            if (reader != null) {
                if (eventHandler != null) {
                    reader.Events.removeEventsListener(eventHandler);
                }
                reader.disconnect();
                reader = null;
                playDisconnectBeep();
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            Log.e(TAG, "Quiet disconnect error", e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected quiet disconnect error", e);
        } finally {
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
            Log.e(TAG, "Dispose error", e);
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

    private void notifyConnectionResult(boolean connected, String message) {
        Log.d(TAG, "Connection result: connected=" + connected + " " + message);
        if (listener != null) {
            mainHandler.post(() -> listener.onRfidConnectionResult(connected, message));
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
     *    channel down on disconnect/reconnect, and
     *  - {@code IOException} ("Queueing USB request failed") from the receive
     *    thread's read path when the USB device is detached and its endpoint
     *    disappears.
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
        // Match by the SDK's well-known teardown messages first, independent of
        // the (R8-obfuscated) stack, so the artifact is reliably swallowed across
        // SDK builds even if the receive thread re-throws it uncaught.
        String msg = t.getMessage();
        if (msg != null && (msg.contains("Queueing USB request failed")
                || msg.contains("Receive thread interrupted"))) {
            return true;
        }
        if (!isFromSerialInputOutputManager(t)) {
            return false;
        }
        // The SDK stops its receive thread by interrupting it; the resulting
        // InterruptedException is expected teardown noise, not a crash.
        if (t instanceof InterruptedException) {
            return true;
        }
        // When the USB device is detached the receive thread's read() fails
        // with IOException("Queueing USB request failed") because the endpoint
        // is gone; the SDK just ends the thread. We re-init on the next attach.
        if (t instanceof IOException) {
            return true;
        }
        // Starting the serial IO manager twice (USB re-enumeration race) throws
        // IllegalStateException("Already running").
        return t instanceof IllegalStateException
                && msg != null
                && msg.contains("Already running");
    }

    private boolean isFromSerialInputOutputManager(Throwable t) {
        for (StackTraceElement el : t.getStackTrace()) {
            String cls = el.getClassName();
            if (cls.contains("SerialInputOutputManager") || cls.contains("CommonUsbSerialPort")) {
                return true;
            }
        }
        return false;
    }

    private void beep() {
        runOnBackground(() -> toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200));
    }

    private void playDisconnectBeep() {
        mainHandler.post(() -> {
            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120);
            mainHandler.postDelayed(() ->
                    toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120), 160L);
        });
    }
}
