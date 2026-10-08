package com.example.libusbAndroidTest;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;

/**
 * Invisible entry point for USB device attach events. The system starts this
 * activity when a DAC is plugged in; it applies the saved volume silently and
 * finishes without ever showing a window, so the user interface never opens on
 * connect. The component is only enabled while automatic mode is on.
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
                || !android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
                        .equals(getIntent().getAction())) {
            finish();
            return;
        }

        // A re-attach caused by our own volume reset must not do anything at
        // all - in particular it must not ask for permission again.
        if (UsbController.isInResetWindow()) {
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
        if (!UsbController.isSilentMode(this)) {
            finish();
            return;
        }

        UsbController.noteAttach(device);

        if (!UsbController.hasPermission(this, device)) {
            // Ask once; the request is deduplicated inside the controller, and
            // the result is delivered to UsbPermissionReceiver.
            UsbController.requestPermission(this, device);
            finish();
            return;
        }

        // Apply in the background and finish as soon as it is done. The
        // activity itself never has a visible window.
        UsbController.silentApply(this, device, this::finish);
    }
}
