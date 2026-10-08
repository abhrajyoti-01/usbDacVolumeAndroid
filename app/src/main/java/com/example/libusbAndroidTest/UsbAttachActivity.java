package com.example.libusbAndroidTest;

import android.app.Activity;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible entry point for USB device attach events. The system starts this
 * activity when a DAC is plugged in; it applies the saved volume silently and
 * finishes without ever showing a window, so the user interface never opens on
 * connect. The component is only enabled while automatic handling is wanted.
 */
public class UsbAttachActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

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
        UsbDevice device = UsbController.getUsbDeviceExtra(getIntent());
        if (device == null) {
            finish();
            return;
        }

        // Only audio devices (or a single attached device) are interesting.
        // Everything else is ignored invisibly.
        if (!UsbController.isAudioDevice(device)
                && !UsbController.isSingleAttachedDevice(this, device)) {
            finish();
            return;
        }

        if (!UsbController.shouldAutoApply(this)) {
            finish();
            return;
        }

        if (!UsbController.hasPermission(this, device)) {
            // Only the invisible mode asks for the permission dialog; with
            // "Auto Apply on Start" the volume is applied when permission was
            // already granted earlier, without any popup.
            if (UsbController.isAutomatic(this)) {
                UsbController.requestPermission(this, device);
            }
            finish();
            return;
        }

        UsbController.noteAttach(device);

        if (UsbController.isInResetWindow()) {
            // Re-attach caused by our own volume write, nothing to do.
            finish();
            return;
        }

        // Apply in the background and finish as soon as it is done. The
        // activity itself never has a visible window.
        UsbController.silentApply(this, device, this::finish);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
