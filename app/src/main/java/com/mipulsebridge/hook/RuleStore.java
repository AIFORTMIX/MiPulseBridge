package com.mipulsebridge.hook;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 跨进程读取模块规则的标准 Xposed 方案。
 *
 * 模块 UI(MainActivity)把 assets/hook_rules.json 写入
 * SharedPreferences("hook_rules", key="rules_json") 并置为其他 uid 可读;
 * Hook 进程(小米运动健康)通过 XSharedPreferences 读取同一份文件。
 */
public final class RuleStore {

    private static final String MODULE_PACKAGE = "com.mipulsebridge";
    private static final String PREF_FILE = "hook_rules";
    private static final String KEY_RULES_JSON = "rules_json";

    private RuleStore() {
    }

    /**
     * 读取 hook 规则 JSON。
     *
     * @return JSON 字符串;模块尚未打开过(未同步)或读取失败时返回空串
     */
    public static String readRulesJson() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, PREF_FILE);
            prefs.reload();
            String json = prefs.getString(KEY_RULES_JSON, "");
            return json == null ? "" : json;
        } catch (Throwable t) {
            XposedBridge.log("MiPulseBridgeHook:读取 hook_rules 失败 " + t.getMessage());
            return "";
        }
    }
}
