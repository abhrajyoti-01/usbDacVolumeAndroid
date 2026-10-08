package com.example.libusbAndroidTest;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UsbController {

    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    public static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String PREFS = "myPrefs";

    // The reset's re-enumeration happens within a few seconds of the write
    // starting. Attaches inside this window are ours; anything later is a
    // genuine user replug which must apply again. The write itself takes up to
    // a few seconds, so the window is generous.
    private static final long REATTACH_CONSUME_MS = 12000;

    // A detach this soon after the write starts belongs to our own reset;
    // later detaches are real unplug events.
    private static final long RESET_DETACH_MS = 10000;

    // After this long without the expected re-attach, stop waiting entirely.
    private static final long REATTACH_TIMEOUT_MS = 30000;

    // Two handlers (visible UI + invisible attach handler) can react to the
    // same attach event; this debounce merges them. It also absorbs the second
    // handler's copy of the reset's own re-attach event.
    private static final long MIN_REAPPLY_MS = 4000;

    // How long a permission request stays pending before it can be retried.
    private static final long PERMISSION_RETRY_MS = 30000;

    private static final String KEY_AWAIT_REATTACH = "awaitReattach";
    private static final String KEY_WRITE_DONE_AT = "writeDoneAt";
    private static final String KEY_LAST_APPLY_PREFIX = "lastApply_";

    private static final Map<Integer, Long> pendingPermissionIds = new HashMap<>();
    private static final Map<String, Long> lastApplyByDevice = new HashMap<>();

    private static volatile boolean awaitReattach = false;
    private static volatile long writeDoneAt = 0;
    private static volatile boolean stateLoaded = false;
    private static volatile SharedPreferences prefsRef;

    // All access to the DAC, libusb and the file descriptor goes through this
    // single thread. Concurrent native calls from the UI and the background
    // attach activity used to crash the native library.
    private static final ExecutorService usbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "usb-worker");
        t.setDaemon(true);
        return t;
    });
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile PermissionListener permissionListener;

    private UsbController() {
    }

    public interface PermissionListener {
        void onPermissionResult(UsbDevice device, boolean granted);
    }

    public static void setPermissionListener(PermissionListener listener) {
        permissionListener = listener;
    }

    public static PermissionListener getPermissionListener() {
        return permissionListener;
    }

    public static void runOnUsbThread(Runnable runnable) {
        runOnUsbThread(runnable, null);
    }

    public static void runOnUsbThread(Runnable runnable, Runnable onDone) {
        usbExecutor.execute(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                Log.e(TAG, "USB worker failed", t);
            } finally {
                if (onDone != null) {
                    mainHandler.post(onDone);
                }
            }
        });
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * A reset re-enumerates the DAC within a few seconds of the write. If the
     * persisted "waiting for re-attach" state is older than this when the
     * process starts, it cannot be the reset's own attach anymore (the process
     * was killed in between), so it is cleared to let the next attach apply.
     */
    private static final long REATTACH_STALE_MS = 15000;

    /** Loads the persisted attach state once. */
    public static void ensureState(Context context) {
        if (stateLoaded) {
            return;
        }
        SharedPreferences p = prefs(context);
        prefsRef = p;
        awaitReattach = p.getBoolean(KEY_AWAIT_REATTACH, false);
        writeDoneAt = p.getLong(KEY_WRITE_DONE_AT, 0);

        // If the process was killed (e.g. cleared from recents / low memory)
        // after a write, the reset's own re-attach is long gone. Keeping the
        // waiting flag would make the next replug silently skip the apply, so
        // drop it when it is stale.
        if (awaitReattach
                && System.currentTimeMillis() - writeDoneAt > REATTACH_STALE_MS) {
            Log.d(TAG, "stale re-attach wait cleared after process restart");
            awaitReattach = false;
            p.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
        }
        stateLoaded = true;
    }

    public static boolean isAutomatic(Context context) {
        return prefs(context).getBoolean("automatic", false);
    }

    public static boolean isAutoApply(Context context) {
        return prefs(context).getBoolean("autoApply", false);
    }

    /**
     * True when the volume should be handled automatically, either invisibly
     * ("Automatic") or through "Auto Apply on Start".
     */
    public static boolean shouldAutoApply(Context context) {
        return isAutomatic(context) || isAutoApply(context);
    }

    /**
     * Enables/disables the invisible mode and keeps the attach component in
     * sync with the auto-apply preference. The component is only enabled while
     * automatic handling is wanted, so with everything off plugging a DAC
     * launches nothing at all.
     */
    public static void setAutomatic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("automatic", enabled).apply();
        syncAttachComponent(context);
    }

    /** Re-applies the attach component state from the stored preferences. */
    public static void syncAttachComponent(Context context) {
        Context appContext = context.getApplicationContext();
        boolean enabled = shouldAutoApply(appContext);
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            appContext.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(appContext, UsbAttachActivity.class),
                    state, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "syncAttachComponent failed", e);
        }
    }

    public static String getVolumeHex(Context context) {
        String volume = prefs(context).getString("volume", "0000");
        return (volume != null && volume.matches("[0-9A-Fa-f]{4}")) ? volume : "0000";
    }

    public static UsbDevice getUsbDeviceExtra(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    public static boolean isAudioDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSingleAttachedDevice(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null) {
            return false;
        }
        return usbManager.getDeviceList().size() == 1;
    }

    public static boolean isSameDevice(UsbDevice first, UsbDevice second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getDeviceId() == second.getDeviceId()) {
            return true;
        }
        return first.getVendorId() == second.getVendorId()
                && first.getProductId() == second.getProductId();
    }

    public static UsbDevice findPresentDevice(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null) {
            return null;
        }
        for (UsbDevice attached : usbManager.getDeviceList().values()) {
            if (attached.getVendorId() == device.getVendorId()
                    && attached.getProductId() == device.getProductId()) {
                return attached;
            }
        }
        return null;
    }

    public static boolean hasPermission(Context context, UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return usbManager != null && device != null && usbManager.hasPermission(device);
    }

    public static synchronized void requestPermission(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long pending = pendingPermissionIds.get(device.getDeviceId());
        if (pending != null && now - pending < PERMISSION_RETRY_MS) {
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, UsbPermissionReceiver.class);
        intent.setAction(ACTION_USB_PERMISSION);

        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills in the result extras through this PendingIntent.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent =
                PendingIntent.getBroadcast(appContext, device.getDeviceId(), intent, flags);

        pendingPermissionIds.put(device.getDeviceId(), now);
        try {
            usbManager.requestPermission(device, permissionIntent);
        } catch (Throwable t) {
            Log.e(TAG, "requestPermission failed", t);
        }
    }

    public static synchronized void clearRequested(UsbDevice device) {
        if (device != null) {
            pendingPermissionIds.remove(device.getDeviceId());
        }
    }

    private static String deviceKey(UsbDevice device) {
        return device.getVendorId() + ":" + device.getProductId();
    }

    /**
     * True while the app is waiting for its own reset to re-enumerate the DAC.
     * During this time detach/attach events are consumed instead of treated as
     * user actions.
     */
    public static synchronized boolean isWaitingForReattach() {
        if (!awaitReattach) {
            return false;
        }
        if (System.currentTimeMillis() - writeDoneAt > REATTACH_TIMEOUT_MS) {
            // The expected re-attach never came (device gone for good, DAC
            // without reset support...). Stop waiting so future events are
            // treated normally again.
            awaitReattach = false;
            if (prefsRef != null) {
                prefsRef.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
            }
            return false;
        }
        return true;
    }

    /** Consumes the expected re-attach event of our own reset. */
    public static synchronized void consumeReattachIfWaiting() {
        if (awaitReattach) {
            Log.d(TAG, "re-attach of our own reset consumed");
            awaitReattach = false;
            if (prefsRef != null) {
                prefsRef.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
            }
        }
    }

    /**
     * Decides whether an attach event should apply the volume.
     *
     * The attach produced by our own reset is consumed; any other attach (a
     * real plug or replug) applies the volume again.
     */
    public static synchronized boolean claimAttachApply(Context context, UsbDevice device) {
        if (device == null) {
            return false;
        }
        ensureState(context);

        long now = System.currentTimeMillis();

        if (awaitReattach) {
            long sinceWrite = now - writeDoneAt;
            if (sinceWrite <= REATTACH_CONSUME_MS) {
                // This is the DAC re-appearing after our volume write. Consume
                // it and remember it as handled so the second handler that
                // receives the same event cannot apply again (no loop).
                awaitReattach = false;
                prefsRef.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
                markHandled(device, now);
                Log.d(TAG, "attach after reset ignored");
                return false;
            }
            // Too late to be our reset's re-attach: real replug, apply.
            awaitReattach = false;
            prefsRef.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
            Log.d(TAG, "late attach treated as replug");
        }

        String key = deviceKey(device);
        Long last = lastApplyByDevice.get(key);
        if (last == null) {
            long stored = prefsRef.getLong(KEY_LAST_APPLY_PREFIX + key, 0);
            if (stored > 0) {
                last = stored;
                lastApplyByDevice.put(key, stored);
            }
        }
        if (last != null && now - last < MIN_REAPPLY_MS) {
            Log.d(TAG, "attach already handled for " + key);
            return false;
        }

        // Reserve the slot so a concurrent handler cannot double-apply.
        markHandled(device, now);
        return true;
    }

    private static void markHandled(UsbDevice device, long now) {
        String key = deviceKey(device);
        lastApplyByDevice.put(key, now);
        prefsRef.edit().putLong(KEY_LAST_APPLY_PREFIX + key, now).apply();
    }

    /**
     * Arms the re-attach wait before the device is opened. The reset inside the
     * native write can emit its detach/attach broadcasts while the write is
     * still running (the main thread is free to receive them), so the wait must
     * already be active to treat them as ours.
     */
    public static synchronized void noteVolumeWriteStart(Context context) {
        ensureState(context);
        writeDoneAt = System.currentTimeMillis();
        awaitReattach = true;
        prefsRef.edit()
                .putLong(KEY_WRITE_DONE_AT, writeDoneAt)
                .putBoolean(KEY_AWAIT_REATTACH, true)
                .apply();
    }

    /**
     * Marks the completion of a volume write. If the reset's own re-attach was
     * already consumed during the write, nothing more is expected (the apply
     * debounce covers stray broadcasts). Otherwise the wait stays armed so the
     * re-attach event that has not arrived yet is consumed as ours.
     */
    public static synchronized void noteVolumeWriteDone(Context context, UsbDevice device,
                                                        boolean success) {
        ensureState(context);
        long now = System.currentTimeMillis();

        if (device != null && success) {
            String key = deviceKey(device);
            lastApplyByDevice.put(key, now);
            prefsRef.edit().putLong(KEY_LAST_APPLY_PREFIX + key, now).apply();
        }

        if (awaitReattach) {
            // Still waiting: give the window fresh time from the moment the
            // write actually completed.
            writeDoneAt = now;
            prefsRef.edit().putLong(KEY_WRITE_DONE_AT, now).apply();
        }
    }

    /**
     * Called for detach events. The detach performed by our own reset arrives
     * right after the write and is ignored; a later detach is a real unplug,
     * which stops the re-attach wait and clears the debounce so the following
     * replug applies the volume immediately.
     */
    public static synchronized void noteDetach(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        ensureState(context);
        if (awaitReattach) {
            long sinceWrite = System.currentTimeMillis() - writeDoneAt;
            if (sinceWrite <= RESET_DETACH_MS) {
                // This is the detach performed by our own USB reset. Keep
                // waiting for the matching attach event.
                Log.d(TAG, "detach of our own reset observed");
                return;
            }
            // Real unplug while waiting: stop waiting so the replug's attach
            // is treated as a fresh connection.
            awaitReattach = false;
            prefsRef.edit().putBoolean(KEY_AWAIT_REATTACH, false).apply();
            Log.d(TAG, "real detach during re-attach wait");
        }
        String key = deviceKey(device);
        lastApplyByDevice.remove(key);
        prefsRef.edit()
                .remove(KEY_LAST_APPLY_PREFIX + key)
                .apply();
    }

    /**
     * Opens the DAC and returns its name. Must be called on the USB worker
     * thread (see runOnUsbThread).
     */
    public static String openDeviceName(Context context, UsbDevice device, int[] outFd) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null || !usbManager.hasPermission(device)) {
            return null;
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "openDevice failed");
            return null;
        }
        int fd = connection.getFileDescriptor();
        if (fd < 0) {
            Log.e(TAG, "invalid file descriptor");
            connection.close();
            return null;
        }
        if (outFd != null && outFd.length > 0) {
            outFd[0] = fd;
        }
        String name = null;
        try {
            name = UsbNative.initializeNativeDevice(fd);
        } catch (Throwable t) {
            Log.e(TAG, "initializeNativeDevice failed", t);
        } finally {
            // The file descriptor is not needed afterwards: every volume write
            // opens its own connection on the worker thread.
            connection.close();
        }
        return name;
    }

    /**
     * Opens the DAC, writes the given volume and closes the connection. Runs
     * on the USB worker thread and resets the DAC afterwards (which re-attaches
     * the kernel audio driver and restores playback).
     */
    public static boolean writeVolume(Context context, UsbDevice device, String volumeHex) {
        if (device == null || volumeHex == null || !volumeHex.matches("[0-9A-Fa-f]{4}")) {
            return false;
        }
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null || !usbManager.hasPermission(device)) {
            return false;
        }

        noteVolumeWriteStart(context);

        boolean success = false;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "writeVolume: openDevice failed");
        } else {
            try {
                UsbNative.setDeviceVolume(connection.getFileDescriptor(),
                        hexStringToByteArray(volumeHex));
                Log.d(TAG, "writeVolume: volume written");
                success = true;
            } catch (Throwable t) {
                Log.e(TAG, "writeVolume failed", t);
            } finally {
                connection.close();
            }
        }

        noteVolumeWriteDone(context, device, success);
        return success;
    }

    /**
     * Invisible background apply: writes the saved volume when automatic
     * handling is enabled. The callback runs on the main thread.
     *
     * Attach events caused by our own reset are filtered out by the
     * suppression window; a genuine attach/replug applies the volume.
     */
    public static void silentApply(Context context, UsbDevice device, Runnable onDone) {
        if (device == null || !shouldAutoApply(context)) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        Context appContext = context.getApplicationContext();
        runOnUsbThread(() -> {
            if (!hasPermission(appContext, device)) {
                return;
            }
            UsbDevice present = findPresentDevice(appContext, device);
            if (present == null) {
                return;
            }
            if (!claimAttachApply(appContext, present)) {
                return;
            }
            writeVolume(appContext, present, getVolumeHex(appContext));
        }, onDone);
    }

    /**
     * Last-resort crash guard. Logs the failure (logcat + files/crash.txt) and
     * restarts the process silently instead of showing the system
     * "app has a bug" dialog.
     */
    public static void installCrashGuard(final Context context) {
        final Context app = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "uncaught exception on " + thread.getName(), throwable);
            try {
                File file = new File(app.getFilesDir(), "crash.txt");
                try (FileWriter writer = new FileWriter(file, false)) {
                    writer.write(new Date().toString());
                    writer.write("\n");
                    writer.write(Log.getStackTraceString(throwable));
                }
            } catch (Throwable ignored) {
                // Never fail while handling a crash.
            }
            // Do not rethrow and do not call the previous handler: the system
            // would show the "app has a bug" dialog. Killing silently lets
            // Android restart the process on the next USB event.
            try {
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {
                // ignore
            }
            System.exit(10);
        });
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
