package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

import java.io.File;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tv;
    private EditText volInput;

    private CheckBox autoApply;
    private CheckBox automatic;

    // The currently known DAC. No UsbDeviceConnection / file descriptor is kept
    // open here: every volume write opens its own connection on the USB worker
    // thread, which prevents stale-descriptor crashes.
    private UsbDevice connectedDevice;

    private boolean receiverRegistered = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsbDevice pendingDetached;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final long VERIFY_DISCONNECT_DELAY_MS = 1500;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                handleUsbBroadcast(intent);
            } catch (Throwable t) {
                Log.e(TAG, "usb broadcast failed", t);
            }
        }
    };

    private void handleUsbBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice device = UsbController.getUsbDeviceExtra(intent);
            if (device == null || !isTargetDevice(device)) {
                return;
            }

            // Refresh the visible state (also after our own reset, the device
            // just re-enumerated with a new name).
            UsbDevice present = UsbController.findPresentDevice(this, device);
            if (present != null) {
                connectDevice(present);
            }

            if (!usbManager.hasPermission(device)) {
                tv.setText("Waiting for USB permission...");
                tv.setBackgroundColor(Color.TRANSPARENT);
                UsbController.requestPermission(this, device);
                return;
            }

            // Apply automatically (deduplicated with the invisible handler, so
            // the app's own reset cannot cause a blink loop, while a real
            // unplug/replug always applies again).
            if (UsbController.shouldAutoApply(this)) {
                UsbDevice target = present != null ? present : device;
                UsbController.silentApply(this, target, null);
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = UsbController.getUsbDeviceExtra(intent);
            if (device != null) {
                // Clear the once-per-connection guard on a real unplug so a
                // replug applies the volume again automatically.
                UsbController.noteDetach(this, device);
                pendingDetached = device;
                handler.removeCallbacks(verifyDisconnect);
                handler.postDelayed(verifyDisconnect, VERIFY_DISCONNECT_DELAY_MS);
            }
        }
    }

    private final Runnable verifyDisconnect = () -> {
        UsbDevice detached = pendingDetached;
        pendingDetached = null;
        if (detached == null) {
            return;
        }

        // A reset or a quick replug makes the DAC disappear and come back
        // within a moment. Verify it is really gone before telling the user.
        UsbDevice present = UsbController.findPresentDevice(this, detached);
        if (present != null) {
            connectDevice(present);
            return;
        }

        if (connectedDevice != null && UsbController.isSameDevice(detached, connectedDevice)) {
            connectedDevice = null;
            tv.setText("USB device disconnected");
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    };

    private final UsbController.PermissionListener permissionListener =
            (device, granted) -> {
                UsbController.clearRequested(device);
                if (granted) {
                    if (device != null) {
                        connectDevice(device);
                        if (UsbController.shouldAutoApply(this)) {
                            UsbController.silentApply(this, device, null);
                        }
                    }
                } else {
                    tv.setText("USB permission denied");
                    tv.setBackgroundColor(Color.RED);
                }
            };

    private boolean isTargetDevice(UsbDevice device) {
        if (UsbController.isAudioDevice(device)) {
            return true;
        }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        return manager != null && manager.getDeviceList().size() == 1;
    }

    /**
     * Refreshes the device name in the background. All native work runs on the
     * serialized USB worker thread so it can never race with a volume write.
     */
    private void connectDevice(UsbDevice device) {
        if (device == null || !usbManager.hasPermission(device)) {
            return;
        }

        final Context appContext = getApplicationContext();
        UsbController.runOnUsbThread(() -> {
            final String name = UsbController.openDeviceName(appContext, device, null);
            handler.post(() -> onDeviceConnected(device, name));
        });
    }

    private void onDeviceConnected(UsbDevice device, String name) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        connectedDevice = device;
        tv.setText(name != null && !name.isEmpty() ? name : "USB Device");
        tv.setBackgroundColor(Color.TRANSPARENT);
    }

    private void applyVolume(boolean fromUser) {
        String volume = volInput.getText().toString();
        if (!volume.matches("[0-9A-Fa-f]{4}")) {
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        // Manual Apply also works when no automatic mode is enabled and when
        // the connection state was lost (the device is looked up again).
        UsbDevice device = connectedDevice;
        if (device == null || !usbManager.hasPermission(device)) {
            device = findTargetDevice();
        }
        if (device == null) {
            if (fromUser) {
                tv.setText("No USB device connected");
                tv.setBackgroundColor(Color.RED);
            }
            return;
        }

        final UsbDevice target = device;
        final Context appContext = getApplicationContext();

        volInput.setBackgroundColor(Color.TRANSPARENT);

        UsbController.runOnUsbThread(
                () -> UsbController.writeVolume(appContext, target, volume),
                () -> {
                    if (fromUser) {
                        Toast.makeText(getApplicationContext(),
                                "Volume set for DAC!", Toast.LENGTH_SHORT).show();
                        SharedPreferences settings = UsbController.prefs(this);
                        if (!settings.getString("volume", "").equals(volume)) {
                            settings.edit().putString("volume", volume).apply();
                        }
                    }
                });
    }

    private UsbDevice findTargetDevice() {
        if (usbManager == null) {
            return null;
        }
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        UsbDevice single = null;
        for (UsbDevice device : deviceList.values()) {
            if (UsbController.isAudioDevice(device)) {
                return device;
            }
            single = device;
        }
        return deviceList.size() == 1 ? single : null;
    }

    private void checkUsbDevices() {
        if (usbManager == null) {
            tv.setText("USB host not supported");
            tv.setBackgroundColor(Color.RED);
            return;
        }
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            tv.setText("No USB device connected");
            tv.setBackgroundColor(Color.TRANSPARENT);
            return;
        }

        UsbDevice target = findTargetDevice();
        if (target == null) {
            tv.setText("No USB audio device detected");
            tv.setBackgroundColor(Color.TRANSPARENT);
            return;
        }

        if (usbManager.hasPermission(target)) {
            UsbDevice present = UsbController.findPresentDevice(this, target);
            UsbDevice device = present != null ? present : target;
            connectDevice(device);
            if (UsbController.shouldAutoApply(this)) {
                UsbController.silentApply(this, device, null);
            }
        } else {
            tv.setText("Waiting for USB permission...");
            tv.setBackgroundColor(Color.TRANSPARENT);
            UsbController.requestPermission(this, target);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;
        automatic = binding.automatic;

        SharedPreferences settings = UsbController.prefs(this);
        volInput.setText(UsbController.getVolumeHex(this));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        automatic.setChecked(UsbController.isAutomatic(this));
        UsbController.ensureState(this);
        UsbController.syncAttachComponent(this);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        UsbController.setPermissionListener(permissionListener);

        clearAppCache();

        // Android only shows the "Always allow" checkbox in the USB dialog
        // when the app already holds RECORD_AUDIO (the Earpods report audio
        // capture). Ask for it first, then continue with the USB flow once the
        // user answered.
        if (hasRecordAudioPermission()) {
            checkUsbDevices();
        } else {
            requestRecordAudioPermission();
        }
    }

    /**
     * Clears the app's own cache directory on every launch. This keeps the
     * cache from growing and stops system tools (Device care) from suggesting
     * a manual cache clear.
     */
    private void clearAppCache() {
        new Thread(() -> {
            try {
                deleteDirContents(getCacheDir());
                deleteDirContents(getExternalCacheDir());
            } catch (Throwable t) {
                Log.w(TAG, "cache clear failed", t);
            }
        }, "cache-clear").start();
    }

    private static void deleteDirContents(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                // Never delete the code cache, it holds JIT profiles.
                if ("code_cache".equals(file.getName())) {
                    continue;
                }
                deleteDirContents(file);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        UsbController.setPermissionListener(null);
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        connectedDevice = null;
        super.onDestroy();
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordAudioPermission() {
        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    UsbController.RECORD_AUDIO_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == UsbController.RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                Log.d(TAG, "RECORD_AUDIO permission denied");
            }
            // Continue the USB flow now that the dialog is (or was) answered.
            checkUsbDevices();
        }
    }

    public void applyButtonPressed(View view) {
        try {
            applyVolume(true);
        } catch (Throwable t) {
            Log.e(TAG, "apply failed", t);
        }
    }

    public void checkboxPressed(View view) {
        try {
            UsbController.prefs(this).edit()
                    .putBoolean("autoApply", autoApply.isChecked())
                    .apply();
            // Keep the invisible attach handler enabled while any automatic
            // handling is wanted.
            UsbController.syncAttachComponent(this);
        } catch (Throwable t) {
            Log.e(TAG, "checkbox failed", t);
        }
    }

    public void automaticCheckboxPressed(View view) {
        try {
            boolean enabled = automatic.isChecked();
            UsbController.setAutomatic(this, enabled);
            if (enabled) {
                // Ask for permission now so the "always use" option can be chosen;
                // from then on everything happens invisible in the background.
                checkUsbDevices();
                requestIgnoreBatteryOptimizations();
            }
        } catch (Throwable t) {
            Log.e(TAG, "automatic checkbox failed", t);
        }
    }

    /**
     * Asks the user to exempt the app from battery optimization. Without this
     * Samsung's aggressive power management can kill the process and skip the
     * automatic volume apply.
     */
    private void requestIgnoreBatteryOptimizations() {
        try {
            android.os.PowerManager powerManager =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "battery optimization request failed", t);
        }
    }
}
