package com.example.libusbAndroidTest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * Keeps the app state tidy on unplug. USB_DEVICE_DETACHED is a protected system
 * broadcast, so this receiver cannot be spoofed by other apps. The next attach
 * event always re-applies the volume (see UsbController.claimAttachApply), so
 * nothing special has to be done here besides clearing the pending
 * permission marker.
 */
public class UsbDetachReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null
                    || !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = UsbController.getUsbDeviceExtra(intent);
            if (device == null) {
                return;
            }
            UsbController.clearRequested(device);
            UsbController.noteDetach(context, device);
        } catch (Throwable t) {
            android.util.Log.e("USB DAC Volume Adjustment", "detach handling failed", t);
        }
    }
}
