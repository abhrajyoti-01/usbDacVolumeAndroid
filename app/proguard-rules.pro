# Keep the JNI bridge: the native library is bound by the fully qualified
# class/method names (Java_com_example_libusbAndroidTest_UsbNative_*), so
# renaming or stripping these would break it and must never happen.
-keep class com.example.libusbAndroidTest.UsbNative {
    native <methods>;
}

# Components declared in AndroidManifest.xml.
-keep class com.example.libusbAndroidTest.UsbDacApp { *; }
-keep class com.example.libusbAndroidTest.MainActivity { *; }
-keep class com.example.libusbAndroidTest.UsbAttachActivity { *; }
-keep class com.example.libusbAndroidTest.UsbPermissionReceiver { *; }
-keep class com.example.libusbAndroidTest.UsbDetachReceiver { *; }

# Keep methods referenced from layout XML (android:onClick).
-keepclassmembers class com.example.libusbAndroidTest.MainActivity {
    public void applyButtonPressed(android.view.View);
    public void checkboxPressed(android.view.View);
    public void automaticCheckboxPressed(android.view.View);
}

# Keep annotations and line numbers for meaningful crash reports.
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
