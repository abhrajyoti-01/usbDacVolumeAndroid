package com.example.libusbAndroidTest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.HashMap;

/**
 * Re-applies the saved volume after the phone reboots. When the DAC is left
 * plugged in, the system enumerates it during boot and never delivers a
 * USB_DEVICE_ATTACHED broadcast, so the invisible attach handler does not run.
 * This receiver closes that gap: it scans the already-connected USB devices and
 * writes the volume to every permitted audio device.
 */
public class UsbBootReceiver extends BroadcastReceiver {

    private static final String TAG = "USB DAC Volume Adjustment";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        if (!UsbController.shouldAutoApply(context)) {
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (!UsbController.isAudioDevice(device) && deviceList.size() != 1) {
                continue;
            }
            if (!usbManager.hasPermission(device)) {
                Log.d(TAG, "boot: no permission for " + device.getDeviceName());
                continue;
            }
            UsbController.silentApply(context, device, null);
        }
    }
}
