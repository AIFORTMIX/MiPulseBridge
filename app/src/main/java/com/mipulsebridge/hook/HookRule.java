package com.mipulsebridge.hook;

import org.json.JSONObject;

/**
 * 一条 hook 规则:描述在目标 App 中 hook 哪个类的哪个方法,以及如何提取 BPM。
 *
 * 字段说明:
 *  className  目标类全限定名(混淆后请按实际填写)
 *  methodName 目标方法名
 *  paramTypes 方法参数类型数组,基本类型用 int/long/float/double/boolean/char/byte/short,
 *             其余用全限定类名;无参方法传空数组
 *  valueFrom  取值来源:"return" 取返回值,"param:0" 取第 1 个参数
 *  minBpm / maxBpm  有效范围过滤,防止把其他数值当心率
 */
public class HookRule {

    public String description = "";
    public String className = "";
    public String methodName = "";
    public String[] paramTypes = new String[0];
    public String valueFrom = "return";
    public int minBpm = 30;
    public int maxBpm = 250;

    public static HookRule fromJson(JSONObject obj) {
        HookRule r = new HookRule();
        r.description = obj.optString("description", "");
        r.className = obj.optString("className", "");
        r.methodName = obj.optString("methodName", "");
        r.valueFrom = obj.optString("valueFrom", "return");
        r.minBpm = obj.optInt("minBpm", 30);
        r.maxBpm = obj.optInt("maxBpm", 250);

        org.json.JSONArray types = obj.optJSONArray("paramTypes");
        if (types != null) {
            String[] arr = new String[types.length()];
            for (int i = 0; i < types.length(); i++) {
                arr[i] = types.optString(i, "");
            }
            r.paramTypes = arr;
        }
        return r;
    }

    public boolean isValid() {
        return className != null && className.length() > 0
                && methodName != null && methodName.length() > 0;
    }
}
