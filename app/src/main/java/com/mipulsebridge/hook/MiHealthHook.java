package com.mipulsebridge.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MiPulseBridge 的 LSPosed / Xposed 入口。
 *
 * 运行在被 Hook 进程(小米运动健康)内:
 * 1. 从模块同步的 SharedPreferences(见 RuleStore)读取 hook 规则;
 * 2. 按规则 hook 心率相关方法;
 * 3. 把提取到的实时 BPM 通过 HrSender 推送到本模块进程的 BLE 广播服务。
 *
 * 规则文件采用「规则驱动」设计:小米运动健康每个版本类名可能不同,
 * 用 jadx 逆向后把新的类名/方法名写进 assets/hook_rules.json 即可,无需改代码。
 * 具体逆向步骤见 README「逆向 Hook 点」章节。
 *
 * 注意:仅依赖标准 Xposed API(api.xposed.info 的 api:82),兼容原版 Xposed 与 LSPosed。
 */
public class MiHealthHook implements IXposedHookLoadPackage {

    private static final String TAG = "MiPulseBridgeHook";

    /** 默认目标包名,可被 hook_rules.json 中的 targetPackage 覆盖 */
    private static final String DEFAULT_TARGET_PACKAGE = "com.mi.health";

    /** hook 方法参数数量上限(心率回调不可能超过 6 个参数,超出则跳过) */
    private static final int MAX_PARAM_COUNT = 6;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!shouldHandle(lpparam)) {
            return;
        }

        List<HookRule> rules = loadRules();
        if (rules.isEmpty()) {
            XposedBridge.log(TAG + ":无有效 hook 规则(请先打开 MiPulseBridge App 完成规则同步)");
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
        String target = DEFAULT_TARGET_PACKAGE;
        try {
            JSONObject root = new JSONObject(RuleStore.readRulesJson());
            String pkg = root.optString("targetPackage", "");
            if (pkg.length() > 0) {
                target = pkg;
            }
        } catch (Exception e) {
            // 规则未同步或解析失败,使用默认包名
        }
        return lpparam.packageName != null && lpparam.packageName.equals(target);
    }

    private List<HookRule> loadRules() {
        List<HookRule> rules = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(RuleStore.readRulesJson());
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
            XposedBridge.log(TAG + ":解析 hook 规则失败 " + e.getMessage());
        }
        return rules;
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
        if (paramClasses.length > MAX_PARAM_COUNT) {
            XposedBridge.log(TAG + ":跳过超过 " + MAX_PARAM_COUNT + " 个参数的方法 "
                    + rule.className + "." + rule.methodName);
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
            // 类型化展开调用,避免 Object[] 无法匹配 findAndHookMethod 重载
            switch (paramClasses.length) {
                case 0:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, hook);
                    break;
                case 1:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], hook);
                    break;
                case 2:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], paramClasses[1], hook);
                    break;
                case 3:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], paramClasses[1], paramClasses[2], hook);
                    break;
                case 4:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], paramClasses[1], paramClasses[2],
                            paramClasses[3], hook);
                    break;
                case 5:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], paramClasses[1], paramClasses[2],
                            paramClasses[3], paramClasses[4], hook);
                    break;
                case 6:
                    XposedHelpers.findAndHookMethod(rule.className, lpparam.classLoader,
                            rule.methodName, paramClasses[0], paramClasses[1], paramClasses[2],
                            paramClasses[3], paramClasses[4], paramClasses[5], hook);
                    break;
                default:
                    XposedBridge.log(TAG + ":不支持的参数数量 " + paramClasses.length);
                    return false;
            }
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
