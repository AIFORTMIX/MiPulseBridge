package com.mipulsebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * BLE 心率广播前台服务。
 *
 * 核心职责:以标准蓝牙心率服务(Heart Rate Service, 0x180D)的身份对外广播实时心率,
 * 使 Keep 等第三方运动 App 把它当成一个普通 BLE 心率带连接。
 *
 * 数据来源:BridgeServerThread 通过 LocalServerSocket 接收被 Hook 进程推送的 BPM,
 * 或通过 sendTestBpm() 注入测试值(用于在无手环时验证整条 BLE 链路)。
 */
public class BleHeartRateService extends Service {

    private static final String TAG = "MiPulseBridge";

    public static final String ACTION_START = "com.mipulsebridge.action.START";
    public static final String ACTION_STOP = "com.mipulsebridge.action.STOP";
    /** 启动时附带此 extra(int)可注入一条测试心率,用于验证 BLE 链路 */
    public static final String EXTRA_TEST_BPM = "com.mipulsebridge.extra.TEST_BPM";

    /** 标准 BLE 心率服务 / 特征 UUID */
    static final UUID UUID_HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID UUID_HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID UUID_BODY_SENSOR_LOCATION = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_ID = "mipulsebridge_channel";
    private static final int NOTIFICATION_ID = 1001;

    /** 最近一次心率值 */
    private volatile int lastBpm = Prefs.BPM_UNKNOWN;

    private final Set<BluetoothDevice> connectedDevices = new HashSet<>();

    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BridgeServerThread bridgeServer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(lastBpm, connectedDevices.size()));

        if (bridgeServer == null) {
            bridgeServer = new BridgeServerThread(this);
            bridgeServer.start();
        }

        startGatt();

        // 支持通过 Intent 注入测试心率(MainActivity 的测试按钮)
        if (intent != null && intent.hasExtra(EXTRA_TEST_BPM)) {
            int testBpm = intent.getIntExtra(EXTRA_TEST_BPM, -1);
            if (testBpm > 0) {
                mainHandler.post(() -> onBpmReceived(testBpm));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGatt();
        if (bridgeServer != null) {
            bridgeServer.shutdown();
            bridgeServer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ----------------------------------------------------------------------------------
    // BLE
    // ----------------------------------------------------------------------------------

    private void startGatt() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "缺少蓝牙权限,无法启动心率广播");
            updateNotification(Prefs.BPM_UNKNOWN, 0, "缺蓝牙权限");
            return;
        }

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "蓝牙不可用或未开启");
            updateNotification(Prefs.BPM_UNKNOWN, 0, "蓝牙未开启");
            return;
        }

        try {
            gattServer = openGattServerReflect(adapter);
        } catch (Exception e) {
            Log.e(TAG, "openGattServer 反射调用失败(权限或 API 兼容)", e);
            updateNotification(Prefs.BPM_UNKNOWN, 0, "GATT 打开失败");
            return;
        }
        if (gattServer == null) {
            Log.w(TAG, "openGattServer 返回 null,系统蓝牙服务可能繁忙");
            updateNotification(Prefs.BPM_UNKNOWN, 0, "GATT 打开失败");
            return;
        }

        BluetoothGattService hrService = new BluetoothGattService(
                UUID_HR_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Heart Rate Measurement:0x2A37,支持 READ + NOTIFY
        BluetoothGattCharacteristic hrMeasurement = new BluetoothGattCharacteristic(
                UUID_HR_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        hrService.addCharacteristic(hrMeasurement);

        // Body Sensor Location:0x2A38,固定值 1 = Chest(让部分 App 校验通过)
        BluetoothGattCharacteristic bodyLocation = new BluetoothGattCharacteristic(
                UUID_BODY_SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        bodyLocation.setValue(new byte[]{1});
        hrService.addCharacteristic(bodyLocation);

        gattServer.addService(hrService);

        startAdvertising(adapter);

        int current = lastBpm;
        updateNotification(current, connectedDevices.size(), null);
        Log.i(TAG, "BLE 心率服务已启动,当前 BPM=" + current);
    }

    private void startAdvertising(BluetoothAdapter adapter) {
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "此设备不支持 BLE 广播(Keep 通常走 GATT 扫描,不影响使用)");
            return;
        }
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(ParcelUuid.fromString(UUID_HR_SERVICE.toString()))
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "BLE 广播权限被拒,跳过", e);
        } catch (Exception e) {
            Log.w(TAG, "BLE 广播启动异常,跳过", e);
        }
    }

    private void stopGatt() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignored) {
            }
            advertiser = null;
        }
        if (gattServer != null) {
            try {
                gattServer.clearServices();
                gattServer.close();
            } catch (Exception ignored) {
            }
            gattServer = null;
        }
        synchronized (connectedDevices) {
            connectedDevices.clear();
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "BLE 广播已开始");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "BLE 广播失败,errorCode=" + errorCode + "(不影响 GATT 连接)");
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            int count;
            synchronized (connectedDevices) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.add(device);
                    Log.i(TAG, "已连接:" + device.getName() + " / " + device.getAddress());
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device);
                    Log.i(TAG, "已断开:" + device.getAddress());
                }
                count = connectedDevices.size();
            }
            new Prefs(getApplicationContext()).setConnectedDevices(count);
            updateNotification(lastBpm, count, null);
            notifyUi();
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (gattServer == null) {
                return;
            }
            try {
                if (UUID_HR_MEASUREMENT.equals(characteristic.getUuid())) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            buildHrMeasurementValue());
                } else {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                            characteristic.getValue());
                }
            } catch (SecurityException e) {
                Log.w(TAG, "onCharacteristicReadRequest 权限异常", e);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            // 通知发送结果,status 非 0 时记录便于排查
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "心率通知发送失败,status=" + status + " device=" + device.getAddress());
            }
        }
    };

    // ----------------------------------------------------------------------------------
    // 对外入口:BridgeServerThread 与 MainActivity 调用
    // ----------------------------------------------------------------------------------

    /** 接收来自 Hook 进程的实时心率 */
    public void onBpmReceived(final int bpm) {
        if (bpm <= 0) {
            return;
        }
        lastBpm = bpm;
        broadcastBpm(bpm);
        updateNotification(bpm, connectedDevices.size(), null);
        new Prefs(getApplicationContext()).setLastBpm(bpm);
        notifyUi();
        Log.d(TAG, "收到实时心率:" + bpm);
    }

    /** 发送一条测试心率(无需手环,用于验证 BLE 链路) */
    public void sendTestBpm() {
        mainHandler.post(() -> onBpmReceived(123));
    }

    /** 手动注入任意 BPM(供调试) */
    public void injectBpm(int bpm) {
        mainHandler.post(() -> onBpmReceived(bpm));
    }

    private void broadcastBpm(int bpm) {
        if (gattServer == null) {
            return;
        }
        byte[] value = buildHrMeasurementValue();
        synchronized (connectedDevices) {
            for (BluetoothDevice device : connectedDevices) {
                BluetoothGattCharacteristic hr = findHrMeasurement();
                if (hr == null) {
                    return;
                }
                try {
                    gattServer.notifyCharacteristicChanged(device, hr, true, value);
                } catch (SecurityException e) {
                    Log.w(TAG, "notify 权限异常", e);
                }
            }
        }
    }

    private BluetoothGattCharacteristic findHrMeasurement() {
        BluetoothGattService service = gattServer.getService(UUID_HR_SERVICE);
        return service != null ? service.getCharacteristic(UUID_HR_MEASUREMENT) : null;
    }

    /**
     * 构造标准心率测量值:
     * byte[0] = flags(0 = 心率值 UInt8,无传感器接触)
     * byte[1] = BPM
     */
    private byte[] buildHrMeasurementValue() {
        int bpm = lastBpm;
        if (bpm < 0 || bpm > 255) {
            bpm = 0;
        }
        return new byte[]{(byte) 0x00, (byte) bpm};
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * 反射调用 BluetoothAdapter#openGattServer(Context, BluetoothGattServerCallback)。
     * 该方法自 API 18 起存在且签名稳定,反射可绕开个别编译环境的符号解析问题;
     * 运行时若因权限(S+)被拒会抛 SecurityException,由调用方处理。
     */
    private BluetoothGattServer openGattServerReflect(BluetoothAdapter adapter) throws Exception {
        java.lang.reflect.Method m = BluetoothAdapter.class.getMethod(
                "openGattServer", Context.class, BluetoothGattServerCallback.class);
        return (BluetoothGattServer) m.invoke(adapter, this, gattServerCallback);
    }

    // ----------------------------------------------------------------------------------
    // 前台通知
    // ----------------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "MiPulseBridge 心率广播",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keep 等 App 正在通过蓝牙连接本设备读取心率");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(int bpm, int deviceCount) {
        return buildNotification(bpm, deviceCount, null);
    }

    private Notification buildNotification(int bpm, int deviceCount, String extra) {
        String content = buildStatusText(bpm, deviceCount, extra);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("MiPulseBridge")
                .setContentText(content)
                .setOngoing(true)
                .build();
    }

    private String buildStatusText(int bpm, int deviceCount, String extra) {
        String hr = bpm > 0 ? bpm + " BPM" : "-- BPM";
        String devices = deviceCount > 0 ? deviceCount + " 台设备连接中" : "等待 Keep 连接";
        if (extra != null) {
            return hr + " · " + devices + " · " + extra;
        }
        return hr + " · " + devices;
    }

    private void updateNotification(int bpm, int deviceCount, String extra) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(bpm, deviceCount, extra));
        } catch (SecurityException e) {
            // 无 POST_NOTIFICATIONS 权限时静默降级
        }
    }

    /** 通知 MainActivity 刷新界面 */
    private void notifyUi() {
        try {
            sendBroadcast(new Intent(MainActivity.ACTION_BPM_UPDATE));
        } catch (Exception e) {
            Log.w(TAG, "通知 UI 失败:" + e.getMessage());
        }
    }
}
