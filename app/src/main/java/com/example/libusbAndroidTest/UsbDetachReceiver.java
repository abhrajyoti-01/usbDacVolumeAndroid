package com.example.libusbAndroidTest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * Tracks physical unplug events. USB_DEVICE_DETACHED is a protected system
 * broadcast, so this receiver cannot be spoofed by other apps. Its only job is
 * to clear the apply cooldown after a real unplug so a replug applies the
 * volume immediately (a detach caused by the app's own volume reset is
 * ignored).
 */
public class UsbDetachReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = UsbController.getUsbDeviceExtra(intent);
        if (device == null) {
            return;
        }
        UsbController.clearRequested(device);
        UsbController.onDetach(device);
    }
}
