# 坦克 300 / GWM V3（Aptiv 栈）无线 CarPlay 适配笔记

对象：`D:\temp\20250321_FBL_release64_NoMapDate_bn139_SWIT`（GWM V3 国内版 OTA，
内核路径里带 `GWM_V3_Domestic_Haval_MY23_4039`）。

方法：OTA 的 `system.img` / `vendor.img` 按 ext4 直接读；`CarPlay.apk`、
`PhoneProjectionService.apk`、`AptivBluetooth.apk`、`AptivCarService.apk`
用 REA 自带的 `jadx-headless-mcp-0.7.1-all.jar`（JADX API）反编译；
`aapt2` 读清单/资源；ext4 的 extent 表把镜像里的原始字符串偏移映射回文件路径。
分析目录里的 `carplay_analysis_report.txt` 是同一份 OTA 的字符串级扫描结果。

结论先说：**车机侧无线 CarPlay 是开着的；真正的缺口在手机侧少发布了一条 SDP 记录。**
本仓库新增的 `bt/CarPlaySdp.kt` + `Iap2BluetoothServer.startProbeRecord()` 补的就是这条。

---

## 1. 车机侧画像

| 项 | 值 | 证据 |
|---|---|---|
| 车机平台 | GWM V3，Aptiv 电话投影栈 | `com.aptiv.phoneprojectionmanager` `1.5.25.65_V3_Tank_SOP3`；`com.aptiv.car` |
| CarPlay 接收端 APK | `com.aptiv.carplay` `1.6.69` | `CarPlay.apk` badging；`CarPlayProjectionService` 日志 `CarPlay Version 1.6.69` |
| AirPlay 接收端 | `AirPlay/320.17.8` | `libAirPlay.so` 字符串；支持 `ANNOUNCE, SETUP, RECORD…` 与 `pair-setup`/`pair-verify` |
| 品牌资源 | `assets/tank/`、`assets/haval/` | `CarPlay.apk` 条目 |
| 无线 CarPlay | **强制开启** | `AptivCarService` 的 `AptivConfigurationService` 在配置更新时无条件 `setConfigCarplayEnable(true)` / `setConfigCarplayWirelessEnable(true)`（`AptivConfigurationService.java:717-722`） |
| 国内默认档 | `def_config_carplay_wireless_default = 1` | `AptivConfigurationService.java:723`：`!isOverseaProject(...) ? 1 : 0`；本 OTA 是 Domestic |
| CarPlay 模式 | 无线 = `mode 68`，有线 = `mode 4` | `CarPlayProjectionService.java:222`；`e/b.java:139-140` 无线时 `ProductPlanUUID=de6e48b7` |
| iAP2 无线配置 | `vendor/etc/aptiv/iap/iap2_cp_bt.cfg` | `BluetoothTransportComponent=1`、`WirelessCarPlayTransportComponent=1`、`AccessoryWiFiInfoSharing=1` |

`iap2_cp_bt.cfg` 里对手机侧有意义的参数：

```
Name=Delphi_Demo
ModelIdentifier=DEA7xx
Manufacturer=Aptiv
CurrentLanguage=en
SupportedLanguages=en,fr,es
MaxNumberOfOutstandingPackets=1
MaxReceivedPacketLength=1024
RetransmissionTimeout=1000
CumulativeAcknowledgementTimeout=500
MaxNumberOfRetransmissions=2
MaxCumulativeAcknowledgements=1
iAP2ControlSessionVersion=2
```

含义：

- 车机自报 **Delphi/Aptiv** → 本仓库 `LEGACY_RECEIVER_MARKERS` 已命中 `delphi`，MFi 首试
  20B（与真 iPhone 实录一致），不需要再加分支。
- 车机单帧最多收 **1024B**，RTO **1s**；本仓库链路层已按对端 `maxLength` 分片、
  按对端 RTO 重传。
- `MaxNumberOfOutstandingPackets=1` 是车机声明的接收窗口。本仓库为了兼容 MIB3 的
  突发消息，发送窗口仍按自己声明的 `0x7F` 计；真车若在识别后出现「只 ACK 不推进」，
  这是第一个要看的点（见 §5）。

## 2. 车机怎么判断「这台手机支持无线 CarPlay」

这是本次适配的核心。Aptiv/GWM 的蓝牙栈里有两套检查，**查的不是同一个 UUID**：

| 位置 | 检查的 SDP UUID | 作用 |
|---|---|---|
| `RemoteDevices.isCPSupportedOverWiFi(ParcelUuid[])` | `00000000-DECA-FADE-DECA-DEAFDECACAFE` | 置 `mCarplaySupported`，供 `BluetoothDevice.isCarplaySupported()` 使用 |
| `AptivBluetoothConnectionManagerService.isIAPSupported()` | `00000000-DECA-FADE-DECA-DEAFDECACAFE` | iAP2 链路/回连能力 |
| **`AptivBluetoothConnectionManagerService.isCPSupportedOverWiFi()`** | **`2D8D2466-E14D-451C-88BC-7301ABEA291A`** | **`PhoneProjectionService` 用它把设备标成 `CARPLAY_WIRELESS`** |

`PhoneProjectionService` 的两个入口分别用到它们：

- `ConnIfAdaptorBT.processPairedDeviceListUpdate()` / `getWirelessCpSupportedDevices()`：
  `bluetoothDevice.isCarplaySupported()` → 依赖 `…cafe`。
- `ConnIfAdaptorBT.setProjectionCapabilityCP()`：
  `isCPSupportedOverWiFi(bluetoothDevice)` → 依赖 **`2D8D2466…` 探针**。
- `ConnIfCtrl` 在决定是否走无线 CarPlay 时也调 `isWirelessCarplaySupported()`，
  它落到 `BluetoothDevice.isCarplaySupported()`（`…cafe`）。

所以手机必须**同时**在 SDP 里挂出：

1. `…cafe`（本仓库 `Iap2BluetoothServer` 已用 `listenUsingRfcommWithServiceRecord` 注册）；
2. `2D8D2466-E14D-451C-88BC-7301ABEA291A`（**此前缺失**）。

参考实现 CarWith-CarPlay（凯迪拉克 CT5）也注册过同一个探针 UUID，注释是
`CarPlay Identity Probe` / `no protocol handler`：只占 SDP 位，不承载协议。
本仓库同样处理：探针监听收到连接就立即关掉，SDP 记录留在栈里供车机查询。

> 时序很关键：车机的 `BluetoothDevice.getUuids()` 是**缓存**，通常在配对/首次 SDP
> 查询时写入。探针要在配对前就在；配对完成后再发布，车机可能直到解绑重配才再查。
> 因此本仓库在 App `onCreate`、蓝牙授权回调、`startConnection()` 三个时点都会
> `startProbeRecord()`（幂等）。

## 3. 本次代码改动

| 文件 | 改动 |
|---|---|
| `app/src/main/java/.../bt/CarPlaySdp.kt` | 新增：`…cafe` 与 `2D8D2466…` 两个 UUID 的唯一来源，附车机判定依据 |
| `app/src/main/java/.../bt/Iap2BluetoothServer.kt` | 新增 `startProbeRecord()` / `probeAcceptLoop()` / `stopProbeRecord()`；`start()` 先发布探针再开 iAP2；`stop()` 一并撤下；`isProbePublished` 供诊断 |
| `app/src/main/java/.../SessionController.kt` | `ensure()` 与新增 `startSdpProbe()`：App 打开/授权后立即发布；状态行加 `probe=` |
| `app/src/main/java/.../MainActivity.kt` | `BLUETOOTH_CONNECT` 授权回调里补一次 `startSdpProbe()` |
| `app/src/test/java/.../bt/CarPlaySdpTest.kt` | 锁定探针 UUID 原文，防止手抄错一位 |

没有改 iAP2 链路、MFi、0x4300/0x4301、AirPlay：这些层在本仓库已有车机兼容阶梯，
先只补「车机根本不把手机当无线 CarPlay 设备」这一层。

## 4. 实车验证步骤（坦克 300）

1. 安装新 APK（debug 或 release 均可；本仓库 release 默认 debug 签名）。
2. 打开 App → 允许「附近设备/蓝牙」权限。日志应立即出现：
   ```
   BT: CarPlay 身份探针 SDP 已发布（2d8d2466-e14d-451c-88bc-7301abea291a）——Aptiv/GWM 车机据此把本机识别为无线 CarPlay 设备
   ```
   状态行应为 `probe=true`。
3. **先点「启动连接」再配对**（或在车机上重新选择本机）。日志应出现：
   ```
   BT: 监听 iAP2 服务与 CarPlay 身份探针，等待接收端回连…
   ```
   `…cafe` 的 iAP2 监听与探针同时在场，是车机两套检查都通过的条件。
4. 手机与车机系统级蓝牙配对；车机 CarPlay/手机投影列表里应出现本机。
   车机若显示「正在连接」，App 日志应进入：
   ```
   iap2 tx=0x1d00
   iap2 rx=0x1d01 …
   iap2 link ready peerMaxControl=1014
   iap2 tx=0xaa05 authentication-succeeded（自报）
   iap2 rx=0x4301 …
   ```
5. 若车机仍不出现本机：在车机上解绑手机 → 确认 App 处于 `probe=true` → 重新配对
   （让车机重新做一次 SDP 查询）。

### PC 侧可用的 Android 工具命令

手机（已连 adb）：

```powershell
adb devices -l
adb shell dumpsys package io.github.shaonialife.carptocar | Select-String versionName
adb shell cat /sys/class/bluetooth/hci0/address
adb shell dumpsys bluetooth_manager | Select-String -Pattern "Bonded|CarPlay|UUID"
# debug 包可直接读文件日志；release 包用 App 内「导出日志」
adb shell run-as io.github.shaonialife.carptocar cat files/main_debug.log |
  Select-String -Pattern "身份探针|监听 iAP2|0x1d01|0x4301|link ready"
```

车机（如果车机 adb/工程模式可用）：

```powershell
adb -s <车机序列号> shell settings get system def_config_carplay_enable
adb -s <车机序列号> shell settings get system def_config_carplay_wireless_enable
adb -s <车机序列号> shell settings get system def_config_carplay_wireless_default
adb -s <车机序列号> shell dumpsys package com.aptiv.phoneprojectionmanager |
  Select-String versionName
adb -s <车机序列号> shell logcat -d |
  Select-String -Pattern "isCPSupportedOverWiFi|Device supports carplay over WIFI|Not Apple dev|CARPLAY_WIRELESS"
```

判读：

- `isCPSupportedOverWiFi return false, as wireless CP is not supported` → 车机配置没开
  （本 OTA 正常应为 1）。
- `No UUIDs for this device` 或没有 `Device supports carplay over WIFI` → 车机没查到
  探针 UUID：确认 App 在配对前已发布、并解绑重配。
- `Not Apple dev/Cp Supported` → 车机没在 SDP 里看到 `…cafe`，或只看到了旧缓存。

## 5. 仍未实车确认的点

这些不是本次改动引入的，按优先级列在下面，实车日志一出来就能定位：

1. **接收窗口 1**：车机声明 `MaxNumberOfOutstandingPackets=1`，本仓库发送窗口按
   自己的 `0x7F` 计。若识别后车机只 ACK 不推进，先看 `LINK tx/rx` 的 seq/ack，
   再考虑按对端窗口限流（不要全局改，MIB3 依赖突发）。
2. **0x4300 报文体**：本仓库按「新版嵌套体 → 老版平铺体 → 不发」阶梯记忆。
   Aptiv 1.6.69 是 iAP2 2.0 栈，正常应吃新版；若车机在 0x4300 后零反馈，
   日志会出现 `0x4300 … 零反馈` 并自动换档。
3. **Wi-Fi 接入方式**： `PhoneProjectionService` 用普通 `WifiManager` / 热点路径，
   没有看到 `WifiP2pManager` 的强依赖；若实车给的是 `DIRECT-` 前缀的 P2P 组，
   本仓库仍缺 Wi-Fi Direct 路径（见 `docs/wireless-diplay-comparison.md` §3.1）。
4. **AirPlay 配对**：接收端是 `AirPlay/320.17.8`，同时带 `pair-setup`/`pair-verify`。
   本仓库先按 `_airplay._tcp` features 判 pre-HomeKit / HomeKit，失败会明文/加密互换一次；
   实车若停在 SETUP 403，日志会明确写出降级路径。
5. **车机 HID/音频**：`iap2_cp_bt.cfg` 没有声明 iAP2 HID/音频组件，触摸与音频应走
   AirPlay 的 HID/音频流；本仓库这两条是既有实现，不需要额外适配。

## 6. 复现分析

```powershell
# 1) OTA 镜像按 ext4 读（Python ext4 包）
python -c "import ext4; v=ext4.Volume(open(r'D:\temp\...\system\system.img','rb'), ignore_checksum=True); print(v.inode_at('/system/priv-app/PhoneProjectionService/PhoneProjectionService.apk').i_size)"

# 2) APK 反编译：REA 自带的 jadx-headless-mcp 0.7.1（底层是 JADX API）
java -jar C:\Users\yuxun\.local\share\rea\android\jadx-headless-mcp-0.7.1-all.jar --transport http --apk <apk>
#    再用它的 get_class_source / search_classes_by_keyword 查
#    isCPSupportedOverWiFi、2D8D2466、IAP_CONNECT_UUID

# 3) 原生库字符串（AirPlay 版本）
python -c "import re; d=open(r'...\libAirPlay.so','rb').read(); print([s for s in re.findall(rb'[ -~]{6,}',d) if b'AirPlay/' in s])"
```

分析产物不入库；本笔记只保留结论与可复现命令。

## 7. 实车日志追加（2026-10-30）

日志：`CarPToCarL-log-20261030-180545-all.txt`（应用 1.2.6-debug，手机 Samsung SM-S9280 / Android 16）。

已确认：

- `BT-C: ✓ 已连入 坦克 300 的无线 iAP2 服务（RFCOMM 建立）`——蓝牙/iAP2 链路本身是通的。
- `0x1D01` 身份：`name=坦克 300`、`model=P01-YY`、`manufacturer=Great Wall Motors`、
  `ProductPlanUUID=de6e48b7`（无线档）。
- 车机**发送清单**只有 `0xAA06`、`0x5703`；**可收清单**是
  `0x5702 0x4e09 0x4e0a 0x4e0b 0x4e0c 0x4e0d 0x4e0e`。
  没有 `0x4300/0x4301`、没有 `0x6800`、没有导航 `0x52xx`。
- MFi `0xAA00→0xAA01→0xAA02→0xAA03→0xAA05` 全通（32B 挑战）。
- 应用发 `0x5702` 后车机**逐帧 ACK**，但 `0x5703` 始终不来；应用改发的 `0x4300`
  被车机忽略（不在可收清单里）。
- 旧代码把 `0x4E0E` 排在 `0x4301` 之后，而 `0x4301` 永远不会来——于是每轮
  都在 `0x5703` 上空等、抛 `未拿到车机热点凭据`、拆链重连。

追加修复（本版已落地）：

1. `0x5702` 发出后**紧跟**一次 `0x4E0E`（传输标识，蓝牙 MAC，发后不管）。
2. 首轮 1s 无 `0x5703`：补发 `0x4E0E + 0x5702` 再等 1s，不再直接切 `0x4300`。
3. 两轮仍无、且车机在 `0x1D01` 里声明会发 `0x5703`：**不拆链**，返回空端点进入保活；
   保活泵每 15s 重发问候 + `0x4E0E + 0x5702`。
4. 保活期收到晚到 `0x5703`：解析成端点交给主控走「绑 WiFi + 广播 ctrl + 等邀请」，
   不再把它当 keep-alive 日志丢掉。
5. 失败日志会明确提示：若长期无回，在车机上**删除本机配对后重新配对**（车机缓存的
   SDP 里可能没有 `2d8d2466` 探针 UUID）。

判读：

- 看到 `iap2 tx=0x4e0e 传输标识` 后不久出现 `iap2 rx=0x5703` → 修复命中，继续看 WiFi/ctrl。
- 看到 `车机声明会发 0x5703 但两轮 0x5702 均无应答` 且长时间没有 `0x5703（晚到）`
  → 车机热点/投影服务没有启动。优先做「解绑 → 确认 App `probe=true` → 重配」；
  若仍不行，用车机 adb 查 §4 的 `def_config_carplay_wireless_enable` 与
  `isCPSupportedOverWiFi` 日志。
- 车机声明 `0x5703` 但没有 `0x4301` 是正常的：这类车机的 AirPlay 端点走 WiFi 上的
  `_airplay._tcp` / ctrl 邀请，不需要 `0x4301`。
