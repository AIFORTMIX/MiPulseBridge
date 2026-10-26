package com.mipulsebridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块自身进程的轻量配置存储(SharedPreferences)。
 * Hook 代码运行在目标 App 进程,不读写这里;配置只服务于本模块 UI 与 BLE 服务。
 */
public final class Prefs {

    private static final String FILE = "mipulsebridge_prefs";

    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_LAST_BPM = "last_bpm";
    private static final String KEY_CONNECTED_DEVICES = "connected_devices";

    public static final String DEFAULT_DEVICE_NAME = "MiPulseBridge HR";
    public static final int BPM_UNKNOWN = -1;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isServiceEnabled() {
        return sp.getBoolean(KEY_SERVICE_ENABLED, false);
    }

    public void setServiceEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
    }

    public String getDeviceName() {
        return sp.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME);
    }

    public void setDeviceName(String name) {
        sp.edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    public int getLastBpm() {
        return sp.getInt(KEY_LAST_BPM, BPM_UNKNOWN);
    }

    public void setLastBpm(int bpm) {
        sp.edit().putInt(KEY_LAST_BPM, bpm).apply();
    }

    public int getConnectedDevices() {
        return sp.getInt(KEY_CONNECTED_DEVICES, 0);
    }

    public void setConnectedDevices(int count) {
        sp.edit().putInt(KEY_CONNECTED_DEVICES, count).apply();
    }
}
