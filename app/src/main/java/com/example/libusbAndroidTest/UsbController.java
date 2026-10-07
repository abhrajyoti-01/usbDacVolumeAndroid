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
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public final class UsbController {

    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    public static final long RESET_WINDOW_MS = 6000;
    public static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String PREFS = "myPrefs";

    // How long a permission request stays pending before it can be retried.
    private static final long PERMISSION_RETRY_MS = 30000;

    private static final Map<Integer, Long> pendingPermissionIds = new HashMap<>();

    // Automatic volume writes happen exactly once per physical connection. A
    // reset caused by the write re-enumerates the DAC and fires attach events
    // again; without this guard those events would apply -> reset -> apply in
    // an endless loop (the "blinking" sound / USB toast).
    private static boolean applied = false;
    private static int appliedVendorId = -1;
    private static int appliedProductId = -1;

    // Timestamp of the last volume write. Events that arrive while this window
    // is open are caused by our own reset, not by the user.
    private static long lastVolumeWriteTime = 0;

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

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isAutomatic(Context context) {
        return prefs(context).getBoolean("automatic", false);
    }

    /** True when the app should work invisibly in the background. */
    public static boolean isSilentMode(Context context) {
        return isAutomatic(context);
    }

    /**
     * Enables/disables the automatic (invisible) mode. The attach activity is
     * a manifest component that is only enabled while automatic mode is on, so
     * with it disabled plugging a DAC cannot launch anything at all.
     */
    public static void setAutomatic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("automatic", enabled).apply();
        toggleAttachActivity(context, enabled);
    }

    /** Re-applies the component state from the stored preference. */
    public static void syncAutomaticComponent(Context context) {
        toggleAttachActivity(context, isAutomatic(context));
    }

    private static void toggleAttachActivity(Context context, boolean enabled) {
        Context appContext = context.getApplicationContext();
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            appContext.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(appContext, UsbAttachActivity.class),
                    state, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "setAutomatic: failed to toggle attach activity", e);
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
        usbManager.requestPermission(device, permissionIntent);
    }

    public static synchronized void clearRequested(UsbDevice device) {
        if (device != null) {
            pendingPermissionIds.remove(device.getDeviceId());
        }
    }

    /**
     * Called for every USB detach event. Only a real unplug resets the
     * "already applied" state; a detach caused by our own volume write is
     * ignored so it cannot lead to repeated applies (blinking).
     */
    public static synchronized void onDetach(UsbDevice device) {
        if (device == null) {
            return;
        }
        if (isInResetWindow()) {
            Log.d(TAG, "detach during reset window ignored");
            return;
        }
        if (device.getVendorId() == appliedVendorId && device.getProductId() == appliedProductId) {
            applied = false;
            appliedVendorId = -1;
            appliedProductId = -1;
        }
    }

    /**
     * Called for every USB attach event. An attach that arrives while our own
     * reset window is open is the re-enumeration caused by the volume write;
     * anything else is a fresh physical connection.
     */
    public static synchronized boolean noteAttach(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (applied
                && device.getVendorId() == appliedVendorId
                && device.getProductId() == appliedProductId
                && !isInResetWindow()) {
            // Fresh connection after a very quick unplug/replug whose detach
            // event was swallowed by the reset window.
            applied = false;
            appliedVendorId = -1;
            appliedProductId = -1;
            return true;
        }
        return false;
    }

    /**
     * Claims the single automatic apply for the current physical connection.
     * Returns false if the DAC was already handled since it was plugged in.
     */
    public static synchronized boolean acquireApply(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (applied
                && device.getVendorId() == appliedVendorId
                && device.getProductId() == appliedProductId) {
            return false;
        }
        applied = true;
        appliedVendorId = device.getVendorId();
        appliedProductId = device.getProductId();
        return true;
    }

    public static synchronized void beginVolumeWrite() {
        lastVolumeWriteTime = System.currentTimeMillis();
    }

    public static synchronized boolean isInResetWindow() {
        return System.currentTimeMillis() - lastVolumeWriteTime < RESET_WINDOW_MS;
    }

    /**
     * Opens the DAC, writes the saved volume and closes it again. Used by the
     * background components when automatic mode is enabled, so no user
     * interface has to be shown. The callback runs on the main thread.
     */
    public static void silentApply(Context context, UsbDevice device, Runnable onDone) {
        if (device == null || !isSilentMode(context)) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                silentApplyInternal(appContext, device);
            } finally {
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        }, "usb-silent-apply").start();
    }

    private static void silentApplyInternal(Context context, UsbDevice device) {
        if (!hasPermission(context, device) || isInResetWindow()) {
            return;
        }
        if (!acquireApply(device)) {
            Log.d(TAG, "silentApply: already applied for this connection");
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }

        beginVolumeWrite();
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "silentApply: openDevice failed");
            return;
        }

        try {
            byte[] volume = hexStringToByteArray(getVolumeHex(context));
            UsbNative.setDeviceVolume(connection.getFileDescriptor(), volume);
            Log.d(TAG, "silentApply: volume written");
        } catch (Throwable t) {
            Log.e(TAG, "silentApply failed", t);
        } finally {
            connection.close();
        }
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
