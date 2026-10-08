package com.example.libusbAndroidTest;

import android.app.Application;

/**
 * Application entry point. Installs the crash guard (no "app has a bug" dialog)
 * and keeps the invisible attach handler in sync with the saved preferences.
 */
public class UsbDacApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        UsbController.installCrashGuard(this);
        UsbController.ensureState(this);
        UsbController.syncAttachComponent(this);
    }
}
