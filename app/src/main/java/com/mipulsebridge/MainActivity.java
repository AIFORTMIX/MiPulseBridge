package com.mipulsebridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * MiPulseBridge 配置界面。
 *
 * 提供:BLE 广播服务启停、广播设备名设置、当前心率与连接状态查看、
 * 以及「发送测试心率 123 BPM」(不依赖手环,用于单独验证 Keep 能否连上本设备)。
 */
public class MainActivity extends Activity {

    public static final String ACTION_BPM_UPDATE = "com.mipulsebridge.action.BPM_UPDATE";

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button btnToggle;
    private TextView tvBpm;
    private TextView tvDevices;
    private TextView tvStatus;
    private EditText etDeviceName;
    private Button btnTest;

    private final BroadcastReceiver bpmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_BPM_UPDATE.equals(intent.getAction())) {
                refreshStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
        syncRulesToPrefs();
        requestNeededPermissions();
        refreshStatus();
    }

    /**
     * 把 assets/hook_rules.json 同步到 SharedPreferences(hook_rules/rules_json),
     * 并让文件对 hook 进程(小米运动健康)可读。
     * Hook 侧用标准 Xposed API 的 XSharedPreferences 跨进程读取规则。
     */
    private void syncRulesToPrefs() {
        try (java.io.InputStream in = getAssets().open("hook_rules.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
            String content = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            getSharedPreferences("hook_rules", MODE_PRIVATE)
                    .edit()
                    .putString("rules_json", content)
                    .commit(); // commit 立即落盘,hook 进程才能读到

            // 让文件对其他 uid 可读(owner 可自行修改自己的文件权限位,无需 root)
            java.io.File f = new java.io.File(getFilesDir().getParent() + "/shared_prefs/hook_rules.xml");
            if (f.exists()) {
                f.setReadable(true, false);
            }
        } catch (Exception e) {
            toast("同步 hook 规则失败:" + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ACTION_BPM_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bpmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bpmReceiver, filter);
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(bpmReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
        }
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            requestPermissions(perms, 1001);
        }
    }

    // ----------------------------------------------------------------------------------
    // UI
    // ----------------------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MiPulseBridge · 手环心率 → BLE 心率带");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp());

        addSpace(root);

        TextView desc = new TextView(this);
        desc.setText("把小米运动健康里的手环实时心率,通过标准蓝牙心率服务广播给 Keep 等运动 App。\n\n"
                + "使用前:① 本页启动服务;② LSPosed 启用本模块并勾选作用域 com.mi.health;"
                + "③ 按 README 逆向往 hook_rules.json 填写心率方法,否则 Keep 只会看到测试心率。");
        desc.setTextSize(13);
        desc.setTextColor(0xFF555555);
        root.addView(desc, lp());

        addSpace(root);

        btnToggle = new Button(this);
        root.addView(btnToggle, lp());

        addSpace(root);

        tvBpm = new TextView(this);
        tvBpm.setTextSize(34);
        tvBpm.setGravity(Gravity.CENTER);
        root.addView(tvBpm, lp());

        tvDevices = new TextView(this);
        tvDevices.setGravity(Gravity.CENTER);
        tvDevices.setTextSize(14);
        root.addView(tvDevices, lp());

        addSpace(root);

        TextView lblName = new TextView(this);
        lblName.setText("广播设备名(Keep 列表里显示的名字)");
        lblName.setTextSize(13);
        root.addView(lblName, lp());

        etDeviceName = new EditText(this);
        etDeviceName.setText(prefs.getDeviceName());
        root.addView(etDeviceName, lp());

        Button btnSaveName = new Button(this);
        btnSaveName.setText("保存设备名");
        btnSaveName.setOnClickListener(v -> {
            String name = etDeviceName.getText().toString().trim();
            if (name.isEmpty()) {
                toast("设备名不能为空");
                return;
            }
            prefs.setDeviceName(name);
            toast("已保存,重启服务后生效:" + name);
        });
        root.addView(btnSaveName, lp());

        addSpace(root);

        btnTest = new Button(this);
        btnTest.setText("发送测试心率 123 BPM(验证 Keep 链路)");
        btnTest.setOnClickListener(v -> {
            Intent i = new Intent(this, BleHeartRateService.class);
            i.setAction(BleHeartRateService.ACTION_START);
            i.putExtra(BleHeartRateService.EXTRA_TEST_BPM, 123);
            startServiceCompat(i);
            prefs.setServiceEnabled(true);
            refreshStatus();
            toast("已注入 123 BPM,请到 Keep 里查看");
        });
        root.addView(btnTest, lp());

        addSpace(root);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(0xFF777777);
        root.addView(tvStatus, lp());

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addSpace(LinearLayout root) {
        TextView space = new TextView(this);
        space.setText("");
        space.setTextSize(4);
        root.addView(space, lp());
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(6);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshStatus() {
        boolean enabled = prefs.isServiceEnabled();
        int bpm = prefs.getLastBpm();
        int devices = prefs.getConnectedDevices();
        String advertise = prefs.getAdvertiseStatus();

        btnToggle.setText(enabled ? "停止广播服务" : "启动广播服务");
        btnToggle.setOnClickListener(v -> toggleService(enabled));

        tvBpm.setText(bpm > 0 ? "♥ " + bpm + " BPM" : "♥ -- BPM");
        tvDevices.setText(devices > 0
                ? devices + " 台设备连接中(Keep / 码表)"
                : "等待 Keep 扫描连接…");

        tvStatus.setText("服务 " + (enabled ? "运行中" : "未启动")
                + " · 广告: " + (advertise.isEmpty() ? "未知(启动后刷新)" : advertise)
                + " · 心率来源: " + (bpm == 123 ? "手动测试" : "hook 推送 / 未知"));
    }

    private void toggleService(boolean currentlyEnabled) {
        Intent i = new Intent(this, BleHeartRateService.class);
        if (currentlyEnabled) {
            i.setAction(BleHeartRateService.ACTION_STOP);
            startServiceCompat(i);
            prefs.setServiceEnabled(false);
            toast("已停止广播服务");
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNeededPermissions();
                toast("请先授予蓝牙权限");
                return;
            }
            i.setAction(BleHeartRateService.ACTION_START);
            startServiceCompat(i);
            prefs.setServiceEnabled(true);
            toast("广播服务已启动,请到 Keep 中连接设备");
        }
        handler.postDelayed(this::refreshStatus, 300);
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
