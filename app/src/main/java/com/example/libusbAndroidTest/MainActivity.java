package com.example.libusbAndroidTest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'lib' library on application startup.
    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tv;
    private EditText volInput;

    private CheckBox autoApply;

    private int deviceDescriptor = -1;

    // Keep strong references: if the connection is garbage collected the
    // file descriptor is closed and later native calls use a dead fd.
    private UsbDeviceConnection connection;
    private UsbDevice connectedDevice;

    private boolean receiverRegistered = false;
    private final Set<Integer> requestedDeviceIds = new HashSet<>();

    // Setting the volume resets the USB device, which detaches and re-attaches
    // it. This flag makes sure the re-attach does not run auto apply again
    // (otherwise apply -> reset -> attach -> apply -> ... would loop).
    private boolean skipAutoApplyOnce = false;

    // While our own reset is in progress the detach/attach events of the DAC
    // must be ignored: the existing connection and file descriptor stay valid.
    private long ignoreUsbEventsUntil = 0;
    private static final long IGNORE_USB_EVENTS_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clearAutoApplySkip = new Runnable() {
        @Override
        public void run() {
            skipAutoApplyOnce = false;
        }
    };

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = getUsbDeviceExtra(intent);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectDevice(device);
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device);
                        if (device != null) {
                            // Allow a later re-plug to ask again.
                            requestedDeviceIds.remove(device.getDeviceId());
                        }
                        tv.setText("USB permission denied");
                        tv.setBackgroundColor(Color.RED);
                    }
                }
            }
            else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (System.currentTimeMillis() < ignoreUsbEventsUntil) {
                    // Re-attach caused by our own reset, keep the connection.
                    return;
                }
                UsbDevice device = getUsbDeviceExtra(intent);
                if (device != null && shouldHandleDevice(device)) {
                    if (usbManager.hasPermission(device)) {
                        connectDevice(device);
                    } else {
                        requestPermission(device);
                    }
                }
            }
            else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (System.currentTimeMillis() < ignoreUsbEventsUntil) {
                    // Detach caused by our own reset, keep the connection.
                    return;
                }
                UsbDevice device = getUsbDeviceExtra(intent);
                if (device != null) {
                    requestedDeviceIds.remove(device.getDeviceId());
                    if (connectedDevice != null
                            && device.getDeviceId() == connectedDevice.getDeviceId()) {
                        resetConnection();
                        tv.setText("USB device disconnected");
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                }
            }
        }
    };

    private UsbDevice getUsbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private boolean isAudioDevice(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                return true;
            }
        }
        return false;
    }

    // While a DAC is connected, ignore other (non audio) devices such as
    // keyboards, so they cannot steal the existing connection.
    private boolean shouldHandleDevice(UsbDevice device) {
        if (connectedDevice != null && deviceDescriptor >= 0 && !isAudioDevice(device)) {
            requestedDeviceIds.add(device.getDeviceId());
            return false;
        }
        return true;
    }

    private void resetConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        connectedDevice = null;
        deviceDescriptor = -1;
    }

    protected void connectDevice(UsbDevice device)
    {
        if (device == null || !usbManager.hasPermission(device)) {
            return;
        }
        if (connectedDevice != null && connectedDevice.getDeviceId() == device.getDeviceId()
                && deviceDescriptor >= 0) {
            return;
        }
        if (device.getInterfaceCount() == 0) {
            Log.e(TAG, "Device has no interfaces");
            tv.setText("Device has no interfaces");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        // Do not claim the interface here. The native layer only claims it
        // while setting the volume and re-attaches the kernel audio driver
        // afterwards, otherwise the DAC loses sound permanently.
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open device connection");
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        int fileDescriptor = conn.getFileDescriptor();
        if (fileDescriptor < 0) {
            Log.e(TAG, "Invalid file descriptor");
            conn.close();
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        String deviceName;
        try {
            deviceName = initializeNativeDevice(fileDescriptor);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize native USB device", t);
            conn.close();
            tv.setText("Failed to open device");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        resetConnection();
        connection = conn;
        connectedDevice = device;
        deviceDescriptor = fileDescriptor;

        tv.setText(deviceName != null && !deviceName.isEmpty() ? deviceName : "USB Device");
        tv.setBackgroundColor(Color.TRANSPARENT);

        if (autoApply.isChecked() && !skipAutoApplyOnce) {
            try {
                setDeviceVolume(fileDescriptor);
            } catch (IllegalArgumentException e) {
                volInput.setBackgroundColor(Color.RED);
            }
        }
    }

    private void requestPermission(UsbDevice device) {
        if (device == null || requestedDeviceIds.contains(device.getDeviceId())) {
            return;
        }
        requestedDeviceIds.add(device.getDeviceId());

        Intent intent = new Intent(ACTION_USB_PERMISSION);
        // On Android 14+ a mutable implicit PendingIntent is blocked, so
        // make the intent explicit for our own package.
        intent.setPackage(getPackageName());

        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills in the extras (device + granted) through this
            // PendingIntent, which is only possible if it is mutable.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    protected void checkUsbDevices()
    {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            tv.setText("No USB device connected");
            tv.setBackgroundColor(Color.TRANSPARENT);
            return;
        }

        // Connect an already permitted audio device.
        for (UsbDevice device : deviceList.values()) {
            if (usbManager.hasPermission(device) && isAudioDevice(device)) {
                connectDevice(device);
                return;
            }
        }

        // Connect an already permitted device when it is the only one.
        if (deviceList.size() == 1) {
            UsbDevice onlyDevice = deviceList.values().iterator().next();
            if (usbManager.hasPermission(onlyDevice)) {
                connectDevice(onlyDevice);
                return;
            }
        }

        // Ask for permission on the first audio device.
        for (UsbDevice device : deviceList.values()) {
            if (isAudioDevice(device)) {
                requestPermission(device);
                return;
            }
        }

        // Some DACs report a vendor specific device class. If only one
        // device is attached, ask for it as a fallback.
        if (deviceList.size() == 1) {
            requestPermission(deviceList.values().iterator().next());
        } else {
            tv.setText("No USB audio device detected");
            tv.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private boolean handleUsbDeviceIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        UsbDevice device = getUsbDeviceExtra(intent);
        if (device == null) {
            return false;
        }
        if (!shouldHandleDevice(device)) {
            return true;
        }
        if (usbManager.hasPermission(device)) {
            connectDevice(device);
        } else {
            requestPermission(device);
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tv = binding.sampleText;
        volInput = binding.volume;
        autoApply = binding.autoApply;

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        volInput.setText(settings.getString("volume", "0000"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // Android 14+ requires an explicit exported flag on registered receivers.
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        requestRecordAudioPermission();

        if (!handleUsbDeviceIntent(getIntent())) {
            checkUsbDevices();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleUsbDeviceIntent(intent)) {
            checkUsbDevices();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clearAutoApplySkip);
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        resetConnection();
        super.onDestroy();
    }

    private void requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    RECORD_AUDIO_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                Log.d(TAG, "RECORD_AUDIO permission denied");
            }
        }
    }

    public void applyButtonPressed(View view){
        String volume = volInput.getText().toString();

        if (deviceDescriptor < 0 || connection == null) {
            tv.setText("No USB device connected");
            tv.setBackgroundColor(Color.RED);
            return;
        }

        // The native call resets the USB device, which would otherwise trigger
        // a detach/attach cycle and run auto apply again.
        skipAutoApplyOnce = true;
        ignoreUsbEventsUntil = System.currentTimeMillis() + IGNORE_USB_EVENTS_MS;
        handler.removeCallbacks(clearAutoApplySkip);
        handler.postDelayed(clearAutoApplySkip, IGNORE_USB_EVENTS_MS);

        try {
            setDeviceVolume(deviceDescriptor);
            volInput.setBackgroundColor(Color.TRANSPARENT);
        } catch (IllegalArgumentException e){
            volInput.setText("");
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        Toast.makeText(getApplicationContext(), "Volume set for DAC!", Toast.LENGTH_SHORT).show();

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        if(!settings.getString("volume", "").equals(volume)) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("volume", volume);
            editor.apply();
        }
    }

    public void checkboxPressed(View view){
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("autoApply", autoApply.isChecked());
        editor.apply();
    }


    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * A native method that is implemented by the 'lib' native library,
     * which is packaged with this application.
     */
    public native String initializeNativeDevice(int fileDescriptor);
    public native void setDeviceVolume(int fileDescriptor, byte[] volume);

    public void setDeviceVolume(int fileDescriptor){
        String volume = volInput.getText().toString();

        if(!volume.matches("[0-9A-Fa-f]{4}")){
            throw new IllegalArgumentException();
        }

        setDeviceVolume(fileDescriptor, hexStringToByteArray(volume));
    }
}
