# MiPulseBridge

把**小米手环的实时心率**桥接成**标准蓝牙心率服务(Heart Rate Service, 0x180D)**,
让 Keep 等第三方运动 App 像连接心率带一样连接你的手环。

> 背景:小米手环 7 Pro 之后的机型(含 8 Pro、9 等)阉割了系统自带的蓝牙心率广播,
> 但小米运动健康 App 在运动模式下仍可持续收到手环实时心率。本项目通过
> **LSPosed Hook 小米运动健康 → 进程间传输 → 本机 BLE 心率服务广播** 还原这一能力。

## 工作链路

```
小米手环 8 Pro
    │ 蓝牙私有协议
    ▼
小米运动健康 (com.mi.health)
    │ LSPosed Hook(规则驱动,读 assets/hook_rules.json)
    ▼
MiPulseBridge Hook 进程内代码
    │ LocalServerSocket IPC("mipulsebridge_ipc",文本行协议)
    ▼
MiPulseBridge 模块进程
    │ BluetoothGattServer:Heart Rate Service 0x180D / 特征 0x2A37(Notify)
    ▼
Keep / 咕咚 / 码表 …(当作标准 BLE 心率带连接)
```

## 特性

- ✅ 标准 BLE 心率服务广播:使用官方 GATT 服务 `0x180D`、心率特征 `0x2A37`(READ + NOTIFY),Keep 直接识别
- ✅ 规则驱动的 Hook:类名/方法名写进 `assets/hook_rules.json`,App 更新导致 hook 点变化时改配置即可,无需改代码
- ✅ 同值去重:相同 BPM 2 秒内不重复发送,避免无效广播
- ✅ 测试模式:一键注入 `123 BPM`,不依赖手环即可验证 Keep 整条链路
- ✅ 前台服务 + 常驻通知:实时显示当前 BPM 与连接设备数
- ✅ 无第三方运行时依赖,纯 Android framework + Xposed API

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 手机 | Root,已装 [LSPosed](https://github.com/LSPosed/LSPosed)(要求 LSPosed,经典 Xposed 无 `moduleContext`) |
| 小米运动健康 | 3.58.x(社区已验证);其他版本需按下方逆向步骤调整 hook 点 |
| 目标设备 | 小米手环 7 Pro 及之后机型(依赖 App 实时心率,手环需已绑定并开启运动模式) |
| Android | 最低 8.0(API 26);蓝牙运行时权限需 Android 12+ 手动授予 |
| 编译环境 | JDK 17 + Android Studio(或命令行 Gradle 8.7) |

## 构建

用 Android Studio 打开本目录直接 Sync + Build;或命令行:

```bash
cd MiPulseBridge
./gradlew assembleDebug
```

产物:`app/build/outputs/apk/debug/app-debug.apk`

## 安装与使用

1. 安装 `app-debug.apk`;
2. 打开 **LSPosed → 模块 → MiPulseBridge → 启用**;
3. 作用域勾选 **小米运动健康(`com.mi.health`)**;
4. 强制停止并重新打开小米运动健康(让 Hook 生效);
5. 打开 **MiPulseBridge App**,授予蓝牙权限,点 **「启动广播服务」**;
6. 按下一节逆向并填写 hook 规则(否则只有测试心率能广播);
7. 在手环上开始 **自由训练 / 步行 / 跑步** 等运动;
8. 打开 **Keep → 我的设备 → 添加心率设备**,扫描到 `MiPulseBridge HR`(或你的自定义名)并连接;
9. 运动页即可看到实时心率。

### 先做链路验证(不需要手环)

1. 启动服务后,点 App 内 **「发送测试心率 123 BPM」**;
2. Keep 心率设备列表里应能扫到并连接本设备,显示 `123 BPM`;
3. 若 123 能到 Keep,说明 BLE 链路 OK,之后只需排查 Hook。

## 逆向 Hook 点(关键步骤)

小米运动健康是混淆应用,每个版本的心率回调类名可能不同。用 jadx 逆向后填写规则:

1. 下载 APK 并用 [jadx](https://github.com/skylot/jadx) 反编译:
   ```bash
   jadx -d out/ com.mi.health.apk
   ```
2. 在反编译代码里搜心率相关标识:
   ```bash
   grep -rn "heartRate\|heart_rate\|onHeartRate" out/sources/ --include="*.java" | head -50
   grep -rn "bpm" out/sources/com/mi/health/sport --include="*.java" | head -50
   ```
3. 找到**运动实时链路**中回调心率的类与方法(社区参考线索:3.58.x 中运动链路为 `SportDataServer` 一类、日常链路为 `DailyHrReport` 一类),确定:
   - 类的完整包名(混淆后以实际为准);
   - 方法的参数类型与**心率数值出现在哪个参数**(或返回值);
4. 打开 `app/src/main/assets/hook_rules.json`,按下面格式填写(可填多条):
   ```json
   {
     "targetPackage": "com.mi.health",
     "rules": [
       {
         "description": "运动实时心率(示例,请按实际填写)",
         "className": "com.mi.health.sport.SportDataServer",
         "methodName": "onHeartRate",
         "paramTypes": ["int"],
         "valueFrom": "param:0",
         "minBpm": 30,
         "maxBpm": 250
       }
     ]
   }
   ```
   字段说明:

   | 字段 | 含义 |
   | --- | --- |
   | `paramTypes` | 参数类型数组;基本类型写 `int/long/float/double/boolean/char/byte/short`,其余写全限定类名;无参写 `[]` |
   | `valueFrom` | `return` 取返回值;`param:0` 取第 1 个参数,`param:1` 取第 2 个,依此类推 |
   | `minBpm` / `maxBpm` | 取值范围过滤,防止把步频等其他数值当心率 |

5. 重新编译安装 APK,重启小米运动健康,开始运动;
6. 打开 **LSPosed 日志**,确认出现 `MiPulseBridgeHook:成功 hook x / y 条规则`;Keep 中应显示真实心率。

> 日常心率链路(`DailyHrReport`)刷新频率低(几十秒一次),直播/运动场景请使用**运动模式**实时链路。

## 故障排查

| 现象 | 排查 |
| --- | --- |
| Keep 扫不到设备 | 确认服务已启动、蓝牙已开启、已授予 `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` 权限;先点「测试心率 123」再扫 |
| Keep 能连但一直 123 BPM | Hook 未生效:检查 LSPosed 日志、规则是否填对、是否强制重启过小米运动健康 |
| 运动中心率不变 | 手环需先开始运动模式;日常心率链路本身刷新慢 |
| Hook 日志提示类不存在 | 你的小米运动健康版本 hook 点已变,按「逆向 Hook 点」重新定位 |
| 连接不稳定 | 手机蓝牙需同时承担「连手环(GATT Client)+ 对外广播(GATT Server)」双角色,部分芯片/ROM 兼容性有限;可换运动模式低频率场景试稳定性 |

## 已知限制

- **Hook 点随 App 版本变化**:小米运动健康更新后可能失效,需按上文重新逆向(建议保留可用的 APK 版本);
- **蓝牙双角色**:手机作为 GATT Client 连手环的同时,又要作为 GATT Server 被 Keep 连接;Android 官方支持多角色,但个别芯片/ROM 可能不稳;
- **设备名**:BLE 广播名跟随系统蓝牙名称(`setIncludeDeviceName(true)`),App 内「设备名」用于标识;如需伪装特定品牌名,可在 root 下修改系统蓝牙名称后重启蓝牙(命令见社区方案,兼容性待实测);
- **Keep 兼容性**:Keep 一般按标准 HRS 服务识别设备,不校验厂商;若个别版本有白名单,可尝试修改蓝牙名。

## 致谢

- [ChlorophyTeio/Xiaomi-LSPosed-PulseRelay](https://github.com/ChlorophyTeio/Xiaomi-LSPosed-PulseRelay)(MIT):小米运动健康 Hook 与数据链路的社区先行实现,本项目 Hook 思路与版本适配线索参考自此;
- [xihan123/HeartRateHook](https://github.com/xihan123/HeartRateHook):「新版手环无心率广播的曲线救国路线」讨论与实现参考;
- 米坛社区 BandBBS 相关讨论帖。

## License

[MIT](LICENSE)
