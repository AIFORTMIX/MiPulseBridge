package com.mipulsebridge.hook;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MiPulseBridge 的 LSPosed 入口。
 *
 * 运行在被 Hook 进程(小米运动健康)内:
 * 1. 从本模块 APK 的 assets/hook_rules.json 读取 hook 规则;
 * 2. 按规则 hook 心率相关方法;
 * 3. 把提取到的实时 BPM 通过 HrSender 推送到本模块进程的 BLE 广播服务。
 *
 * 规则文件采用「规则驱动」设计:小米运动健康每个版本类名可能不同,
 * 用 jadx 逆向后把新的类名/方法名写进 hook_rules.json 即可,无需改代码。
 * 具体逆向步骤见 README「逆向 Hook 点」章节。
 */
public class MiHealthHook implements IXposedHookLoadPackage {

    private static final String TAG = "MiPulseBridgeHook";

    /** 默认目标包名,可被 hook_rules.json 中的 targetPackage 覆盖 */
    private static final String DEFAULT_TARGET_PACKAGE = "com.mi.health";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!shouldHandle(lpparam)) {
            return;
        }

        List<HookRule> rules = loadRules(lpparam);
        if (rules.isEmpty()) {
            XposedBridge.log(TAG + ":hook_rules.json 无有效规则,已跳过 hook");
            return;
        }

        XposedBridge.log(TAG + ":目标包 " + lpparam.packageName + ",共 " + rules.size() + " 条规则");
        int hooked = 0;
        for (HookRule rule : rules) {
            if (hookRule(lpparam, rule)) {
                hooked++;
            }
        }
        XposedBridge.log(TAG + ":成功 hook " + hooked + " / " + rules.size() + " 条规则");
    }

    private boolean shouldHandle(XC_LoadPackage.LoadPackageParam lpparam) {
        // 优先按规则文件里的 targetPackage 匹配,兜底用默认包名
        String target = DEFAULT_TARGET_PACKAGE;
        try (InputStream in = openModuleAsset(lpparam, "hook_rules.json")) {
            if (in != null) {
                String content = readAll(in);
                JSONObject root = new JSONObject(content);
                String pkg = root.optString("targetPackage", "");
                if (pkg.length() > 0) {
                    target = pkg;
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ":读取 targetPackage 失败,使用默认 " + target);
        }
        return lpparam.packageName != null && lpparam.packageName.equals(target);
    }

    private List<HookRule> loadRules(XC_LoadPackage.LoadPackageParam lpparam) {
        List<HookRule> rules = new ArrayList<>();
        try (InputStream in = openModuleAsset(lpparam, "hook_rules.json")) {
            if (in == null) {
                return rules;
            }
            String content = readAll(in);
            JSONObject root = new JSONObject(content);
            JSONArray arr = root.optJSONArray("rules");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    HookRule rule = HookRule.fromJson(arr.getJSONObject(i));
                    if (rule.isValid()) {
                        rules.add(rule);
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ":读取规则失败 " + e.getMessage());
        }
        return rules;
    }

    /**
     * 打开本模块 APK 的 assets 文件。
     * LSPosed 提供 moduleContext,其 AssetManager 即模块自身资源。
     */
    private InputStream openModuleAsset(XC_LoadPackage.LoadPackageParam lpparam, String assetName) {
        try {
            AssetManager am = (AssetManager) lpparam.moduleContext.getAssets();
            return am.open(assetName);
        } catch (Exception e) {
            XposedBridge.log(TAG + ":打开模块资源失败 " + assetName + " " + e.getMessage());
            return null;
        }
    }

    private String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean hookRule(XC_LoadPackage.LoadPackageParam lpparam, HookRule rule) {
        Class<?>[] paramClasses;
        try {
            paramClasses = resolveParamTypes(lpparam.classLoader, rule.paramTypes);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ":规则参数类型无法解析 " + rule.className + "." + rule.methodName
                    + " -> " + e.getMessage());
            return false;
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int bpm = extractBpm(param, rule);
                if (bpm > 0) {
                    HrSender.get().sendBpm(bpm);
                }
            }
        };

        try {
            // 用 List 动态构造,避免「无参方法」时固定数组越位/覆盖 methodName
            List<Object> args = new ArrayList<>();
            args.add(rule.className);
            args.add(lpparam.classLoader);
            args.add(rule.methodName);
            for (Class<?> c : paramClasses) {
                args.add(c);
            }
            args.add(hook);

            XposedHelpers.findAndHookMethod(args.toArray());
            XposedBridge.log(TAG + ":已 hook " + rule.className + "." + rule.methodName
                    + " valueFrom=" + rule.valueFrom);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ":hook 失败 " + rule.className + "." + rule.methodName
                    + " -> " + t.getMessage());
            return false;
        }
    }

    /** 从 hook 回调里按规则提取 BPM */
    private int extractBpm(XC_MethodHook.MethodHookParam param, HookRule rule) {
        Object value = null;
        if ("return".equals(rule.valueFrom)) {
            value = param.getResult();
        } else if (rule.valueFrom != null && rule.valueFrom.startsWith("param:")) {
            try {
                int idx = Integer.parseInt(rule.valueFrom.substring("param:".length()));
                if (idx >= 0 && idx < param.args.length) {
                    value = param.args[idx];
                }
            } catch (NumberFormatException ignored) {
            }
        }

        int bpm = toBpm(value);
        if (bpm >= rule.minBpm && bpm <= rule.maxBpm) {
            return bpm;
        }
        return -1;
    }

    /** 支持 Number / String / 包装类型 三种形态的返回值 */
    private int toBpm(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                String s = ((String) value).trim();
                // 兼容 "87" 与 "87 bpm" 等文本
                int end = 0;
                while (end < s.length() && Character.isDigit(s.charAt(end))) {
                    end++;
                }
                if (end > 0) {
                    return Integer.parseInt(s.substring(0, end));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private Class<?>[] resolveParamTypes(ClassLoader classLoader, String[] typeNames)
            throws ClassNotFoundException {
        Class<?>[] classes = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            classes[i] = resolveType(classLoader, typeNames[i]);
        }
        return classes;
    }

    private Class<?> resolveType(ClassLoader classLoader, String name) throws ClassNotFoundException {
        switch (name) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            default:
                return Class.forName(name, false, classLoader);
        }
    }
}
