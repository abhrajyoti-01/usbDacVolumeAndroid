# usbDacVolumeAndroid

Simple application to set the USB DAC volume on UNROOTED Android Devices

[DOWNLOAD](https://github.com/abhrajyoti-01/usbDacVolumeAndroid/releases/download/release-1.5/app-release.apk)

> **This fork** fixes Samsung Galaxy S24 Ultra / Android 15-16 (One UI 7/8) compatibility, including the permission/relaunch crash loop and the "no sound after Apply" problem (the kernel `snd-usb-audio` driver is now re-attached by resetting the DAC after the volume is set).

# Why
The vast majority of high end android smartphones sold today do not contain a 3.5mm headphone jack. To remedy this, most people will use a USB-C to 3.5mm DAC. However, there are certian DACs (namely the Apple USB- C DAC) that do not default to their highest output setting. On most platforms (Windows, Linux, macOS, iOS), this isn't an issue because they either force the highest DAC volume and adjust their own mixer volume, or they control the DAC volume explicitly. Android does neither, so as a result, some DACs are quieter than they possibly can be.

## Tested Devices
 - OnePlus 12R/Android 14 (Working)
 - OnePlus 7T/Android10/12 (Working)
 - Pixel 3/Android 11 (Working)
 - Galaxy A34/Android 14 (Working)
 - Galaxy S24 Ultra/Android 16/One UI 8 (Working - this fork)
 - Samsung Galaxy A56/Android 15 (Working - this fork)
 - Huawei P20/Android 10 (NOT WORKING... Unknown reason...)
 - Maybe more? Let me know.

# What was fixed in this fork
 - Galaxy S24 Ultra / Android 15-16 (One UI 7/8) support (targetSdk 36, 16 KB page size, modern NDK).
 - USB permission prompt no longer loops or crashes the app (explicit mutable `PendingIntent`, proper receiver export flags, `singleTask` launch mode).
 - "No sound after Apply" fixed: the DAC is reset after the volume is written so the kernel audio driver is re-attached and playback keeps working.
 - Null/endpointless device, invalid file descriptor and permission-denied crashes guarded.
 - Crash-free when other USB devices (keyboards, etc.) are attached while a DAC is connected.
 - No more "app is crashing frequently" (Device care): all native USB calls now run on a single serialized background thread instead of the UI thread, and no connection/file descriptor is kept open between operations.
 - The app clears its own cache automatically on every launch.
 - The permission dialog is not shown again after the app's own USB reset.

# Security hardening
 - **Malformed USB descriptors** can no longer hang or crash the app (bounds-checked descriptor walking in the native helper).
 - **No component accepts untrusted input**: the invisible attach handler validates the device against the live USB device list and the system-granted USB permission, so a crafted intent from another app cannot trigger any USB operation.
 - **Backups disabled** (`allowBackup=false`), so app data cannot be extracted via `adb backup`.
 - **Supply chain locked**:
   - `gradle/verification-metadata.xml` pins SHA-256 checksums of every dependency; the build fails if any artifact changes.
   - The Gradle wrapper script and JAR are the official Gradle 8.11.1 releases (verified by hash) and the wrapper distribution is pinned with `distributionSha256Sum`.
   - The `libusb` submodule is pinned to a specific upstream commit of `libusb/libusb`.
   - Release builds are minified/obfuscated with R8 and are not debuggable.
 - **No permissions** are declared except `RECORD_AUDIO` (used only to suppress the USB audio-capture warning) and USB host. The app never accesses the network, storage, contacts, location or any other data.

# Automatic (invisible) mode
Tick **Automatic (invisible)** in the app and keep **"Always use for this device"** checked on the one-time USB permission popup. From then on:
 - The app never opens a window when a DAC is connected - the volume is applied silently in the background.
 - The volume is applied exactly once per connection, so the USB reset cannot cause a connect/disconnect loop ("blinking" sound).
 - If the app is not running at all when the DAC is plugged in, nothing is launched and no interface appears.

Note: while a DAC is plugged in, Android may show its normal "USB device connected" notification; that is a system notification, not this app.

# Usage without automatic mode
 - The top text input is the HEXADECIMAL value to send to the dongle. Leave this as 0000 for 100% volume.
 - Auto Apply on Start means that the new value will be sent to the dongle immediately upon detection.
 - Apply will set the volume on the device.
 - The bottom is the name of the detected USB Device.

On first launch, connect your DAC and accept the USB permission prompt:
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/48d92739-bc2a-406b-853c-a14bf6f1228a" width="512">

Once you have accepted, you should see this:<br>
<img src="https://github.com/guyman624/usbDacVolumeAndroid/assets/82007920/b9e5bdfe-7f91-4eb1-b846-b6d6fb4a7216" width="512">

You will temporarily lose sound during the setting of volume, however you should be able to restart playback after it has been set. Please be aware that this setting is only saved until you unplug the headphones.

# Building
```
git submodule update --init --recursive
./gradlew assembleRelease
```
The APK is written to `app/build/outputs/apk/release/app-release.apk` (or `app/build/outputs/apk/debug/app-debug.apk` for `assembleDebug`).
Requires JDK 17+, Android SDK with API 36 platform, NDK 27.1 and CMake.

# Special thanks to:
[ibaiGorordo](https://github.com/ibaiGorordo/libusbAndroidTest) for most of the code :>

[polhdez](https://github.com/polhdez) for the improved permission handling, the auto-quit toggle, the settings saving fix and the app icon.
