package com.example.libusbAndroidTest;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

/**
 * Invisible entry point for USB device attach events. The system starts this
 * activity when a headphone/IEM is plugged in; it applies the saved volume
 * silently and finishes without ever showing a window, so the user interface
 * never opens on connect.
 */
public class UsbAttachActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            handleIntent();
        } catch (Throwable t) {
            // This activity is invisible; an exception must never surface as a
            // crash dialog.
            android.util.Log.e("USB DAC Volume Adjustment", "attach handling failed", t);
            finish();
        }
    }

    private void handleIntent() {
        // Only the system may start us for a USB attach event. Even if another
        // app sends a crafted intent, the device is validated against the live
        // USB device list below, so a fake UsbDevice cannot be injected.
        if (getIntent() == null
                || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            finish();
            return;
        }

        // Auto handling not wanted: do nothing at all, stay invisible.
        if (!UsbController.shouldAutoApply(this)) {
            finish();
            return;
        }

        UsbDevice claimed = UsbController.getUsbDeviceExtra(getIntent());
        if (claimed == null) {
            finish();
            return;
        }

        // Trust only devices that are really attached right now.
        UsbDevice device = UsbController.findPresentDevice(this, claimed);
        if (device == null) {
            finish();
            return;
        }

        if (!UsbController.isAudioDevice(device)
                && !UsbController.isSingleAttachedDevice(this, device)) {
            finish();
            return;
        }

        if (!UsbController.hasPermission(this, device)) {
            // Ask once; the request is deduplicated inside the controller, and
            // the result is delivered to UsbPermissionReceiver which then
            // applies the volume silently.
            UsbController.requestPermission(this, device);
            finish();
            return;
        }

        // silentApply ignores re-attach events caused by our own reset and
        // applies exactly once per physical connection. Finish when done; the
        // activity itself never has a visible window.
        UsbController.silentApply(this, device, this::finish);
    }
}
