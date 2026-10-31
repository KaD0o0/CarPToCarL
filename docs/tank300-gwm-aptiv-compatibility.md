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

## 8. 实车日志追加（2026-10-31）：握手成功但约 2 秒后断链

日志：`CarPToCarL-log-20261031-082923-all.txt`（同机、同车）。

这次 `0x4E0E` 修复命中，完整走到了 AirPlay：

- `iap2 rx=0x5703 ssid=坦克 300ypyjllj channel=149 security=2`；
- WiFi 应用专属网络加入成功（本机 192.168.40.95 → 车机 192.168.40.82）；
- `/info`：`name=CarPlay model=P01-YY deviceID=DE:FE:23:FE:9E:55 srcvers=320.17.8
  features=0x6104040280 display=1920×720@30`；
- `/pair-setup` M2/M4/M6、`/pair-verify` 全部通过；
- 屏幕流 SETUP 成功 `dataPort=43971`，视频帧已发送，feedback 200；
- 音频流 SETUP 成功 `type=102 media/0x800000 48kHz`，RECORD 完成。

然后：

```text
08:28:54.570 IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage）——等待车机 SYN
08:28:54.879 IAPLINK: 车机 marker（#1）——凑够 2 个后补发协商
08:28:55.403 SESSION 保活结束（链路断开） / LINK closed: bt socket closed
08:28:57.017 EVENT: 读取结束 - Connection reset / NSD: 服务消失 CarPlay
```

根因不是“蓝牙没连上”，也不是 AirPlay 配对失败：

- 车机在屏幕 SETUP 后**只发一个 SYN marker**（`FF 55 …`），随后约 0.5s 关闭蓝牙
  iAP2 链路；
- 旧逻辑 `EVENT_LINK_MARKER_THRESHOLD=2` 要等第二个 marker 才回协商，但车机不会再发；
- 虚拟 iAP2 链路停在 `NEGOTIATING`，车机等不到 WiFi 侧的控制会话，约 1.6s 后把
  整条 AirPlay 会话（事件通道、视频 43971、音频 49038、NSD）一起 reset。

追加修复（本版已落地）：

1. 首个 marker 后 **250ms 仍无第二个** → 按「单次 SYN」兜底升级为控制通道协商；
   第二个 marker 到达时旧路径仍立即升级（不改变 MIB3/Honda 行为）。
2. 兜底由 200ms 节拍线程驱动，新增可测试入口 `VirtualIap2Link.tick()`。
3. 新增单测 `singleMarkerGetsControlRoundAfterFallbackWindow`，锁定「首个 marker
   不立即回、250ms 后必须回 marker + 同步包」。

预期新日志：

```text
iap2 0x5703 ...
IAPLINK: MIB3 detect 已发 ...
IAPLINK: 车机 marker（#1）——250ms 内无第二个则按单 marker 兜底回协商
IAPLINK: 首个车机 marker 后 ...ms 无第二个——按单次 SYN 兜底回协商
IAPLINK: 控制通道 marker + 本表同步包（第 1/5 轮，首个 marker 超时兜底）——等车机同步包（FF5A）
IAPLINK: 车机 marker（#2）链路状态=...
```

如果仍断：

- 先看 `IAPLINK` 是否从 `NEGOTIATING` 进入 `NORMAL`，以及车机是否回 `FF5A`
  同步包 / `0x1D01`；
- 若车机在关 BT 后完全不回同步包，说明它不接受事件通道 + 控制通道的 WiFi iAP2
  形态，下一步需要车机侧日志（`com.aptiv.carplay` / `PhoneProjectionService`）
  或抓 AirPlay `POST /command` 的响应；
- `TUNNEL: SETUP 应答无 dataPort` 是正常的：这台车机不支持 type-130 iAP 隧道，
  本版走事件通道入站 + 控制通道出站的形态。

## 9. 为什么 09:50 那轮连 0x5703 都没有：车机热点/电源态

日志：`CarPToCarL-log-20261031-095214-all.txt`。这轮和 08:28 的成功轮相比，
iAP2/MFi 全部正常（`0x1D01`、`0xAA01/0xAA03/0xAA05`、逐帧 ACK），但车机
**始终没有发 0x5703**；App 的 `0x5702 + 0x4E0E` 重试也拿不到热点凭据。
这不是手机侧协议没发对，而是**车机热点没有开**。

用 REA/JADX 对 `system.img` 里的 `com.aptiv.wifi`（`AptivWifiService.apk`）和
`com.aptiv.phoneprojectionmanager`（`PhoneProjectionService.apk`）分析：

- `com.aptiv.wifi` 的 `WifiService.startApSta()` 在开机/服务启动时读
  `wifiAPPreference.apState`：**只有 `apState=true` 才调用 `turnOnHotspot()`**；
  `turnOnHotspot()` 走 `WifiManager.startSoftAp()`。`apState=false` 时车机蓝牙/iAP2
  照常工作，但根本不会有 AP，也就没有 0x5703。
- `CarPowerModeService` 监听车机电源态：
  - `FULL_OPERATION` 才会在 AP 关闭时重新 `turnOnHotspot()`；
  - `SUSPEND_ENTER` 会先 `turnOfHotspot()`（热点直接停掉）；
  - `LOCAL_INFOTAINMENT`（模式 3）只清标志就返回，**不会把 AP 拉起来**。
  所以车机屏幕亮着不等于热点在：ACC/待机/本地娱乐模式下，BT 还在、AP 已关。
- `WifiService.pCodeChange()` 生成 AP 配置：SSID 是 `GWM_V3` 或
  `getDeviceName() + 随机串`（实车日志里的 `坦克 300ypyjllj` 就是设备名 + 随机串），
  5GHz `apBand=1/apChannel=149`；密码由 `NativePassword.GenSystemPassword(12)` 生成。
  08:28 的 `0x5703 ssid=坦克 300ypyjllj channel=149` 与此完全一致；
  09:50 没有这条，说明 AP 没起来，而不是 SSID/密码变了。
- `CarPlay.apk` 的 `ProjectionServiceListenerImpl.onRequestLocalWifiConfigInfo()`
  **是空实现**；`onRequestiAPWiFiStart()` 只是用 mode=16 启动另一条 iAP2 服务。
  也就是说：**0x5702 不会让车机创建热点**，App 再发多少 iAP2 消息也起不了 AP。

因此判读规则：

- 日志有 `iap2 rx=0x5703` → 车机 AP 在，继续看 AirPlay；
- 日志只有 `车机声明会发 0x5703 但两轮 0x5702 均无应答` → 车机 AP 不在/被电源态关掉。
  这不是改 App 能解决的，先在车机侧把热点拉起来。

车机侧操作（按优先级）：

1. **启动车辆到 FULL_OPERATION**（点火/READY，不只是 ACC 或屏幕亮），等车机
   WiFi 热点出现；车机处于 SUSPEND/LOCAL_INFOTAINMENT 时 AP 一定是关的。
2. 在车机的 WiFi/热点设置里确认热点开关为开（对应 `wifiAPPreference.apState=true`）。
   用手机 WLAN 扫描：应能看到形如 `坦克 300xxxxxx` / `GWM_V3xxxx` 的 5GHz 热点
   （channel 149）。看不到就是 AP 没起来。
3. 若热点开关是开但仍不广播：长按车机电源/音量重启车机（`WifiService`/`startSoftAp`
   卡在 `ApProgressCheck` 时只能重启），再试。
4. 若车机有工程 adb，直接查：
   ```powershell
   adb -s <车机> shell dumpsys wifi | Select-String -Pattern "SoftAp|mWifiApState|apState"
   adb -s <车机> shell logcat -d -s WifiService CarPowerModeService WifiAPReceiver |
     Select-String -Pattern "turnOnHotspot|startSoftAp|FULL_OPERATION|SUSPEND_ENTER|hotspot enabled"
   ```
   看到 `SUSPEND_ENTER` 或 `LOCAL_INFOTAINMENT` 而没有 `turnOnHotspot/startSoftAp`
   就是电源态没到 FULL_OPERATION；看到 `startSoftAp` 返回 false 就是 WiFi 芯片/热点
   服务卡住，需要重启车机。
5. SDP 探针仍要保证：车机配对前 App 已 `probe=true`；若之前配过对，删除车机上的
   本机配对后重配一次，避免车机缓存的 UUID 里没有 `2d8d2466`。

> 结论：09:50 这轮不是 AirPlay 握手或单 marker 兜底的问题——那一层根本没被触发。
> 先把车机 AP 拉起来（FULL_OPERATION + 热点开），0x5703 才会出现；之后的
> AirPlay 单 marker 兜底（§8）才有机会生效。

## 10. 2026-10-31 14:03：手机已连车机热点，但 App 看不到 SSID

用 adb 查 Samsung SM-S9280（Android 16）：

```text
adb shell cmd wifi status
→ Wifi is not connected   （测试结束、车机 AP 已关）
```

但 `dumpsys wifi` 的连接历史明确记录：

```text
14:03:06.403 CMD_START_CONNECT targetConfigKey="坦克 300ypyjllj"WPA_PSK ...
14:03:06.594 ssid: "坦克 300ypyjllj" bssid: de:fe:23:fe:9e:55 frequencyMhz: 5745
14:03:06.742 CMD_IPV4_PROVISIONING_SUCCESS IP 192.168.40.22/24 gateway 192.168.40.21
14:03:36.032 NETWORK_DISCONNECTION_EVENT ssid: "坦克 300ypyjllj" ... locallyGenerated:false
```

也就是说 **14:03 测试期间手机确实连在车机热点上，车机 AP 是开的**；14:03:36 车机
断开、AP 随后关闭（当前扫描已看不到 `坦克 300ypyjllj`）。

App 看不到它的根因是权限：

```text
android.permission.NEARBY_WIFI_DEVICES: granted=true
android.permission.ACCESS_FINE_LOCATION:  granted=false
```

- Shizuku 已移除，App 内 `cmd wifi status` 没有 shell 特权，`SysShell.exec` 失败；
  旧 `shellWifiStatus()` 直接返回 `true to null`——**永远不会知道手机已连热点**。
- Samsung/Android 13+ 上 `WifiManager.getConnectionInfo().SSID` 与
  `WifiManager.getScanResults()` 仍要求 `ACCESS_FINE_LOCATION`，只有
  `NEARBY_WIFI_DEVICES` 会被脱敏成 `<unknown ssid>`/空结果。
- 于是 §8 的等待观察者一直看到「Wi-Fi 已开但未连接任何网络」，既不弹
  「按车机热点继续」，`scanCarHotspots()` 也一直「未发现」。

修复（本版已落地）：

1. `MainActivity.ensureWifiLocationReady()` 在 Android 13+ 同时申请
   `NEARBY_WIFI_DEVICES` **和** `ACCESS_FINE_LOCATION`（两个回调都重新走完整检查）。
2. `SessionController.shellWifiStatus()` 在 shell 不可用时退回
   `WifiManager.getConnectionInfo()`，读到真实 SSID；FINE 缺省时仍会脱敏，所以 1 必须做。
3. `scanCarHotspots()` 现在能拿到真实扫描结果；新增「Wi-Fi 扫描原始结果：N 个 SSID」
   以区分「权限/节流（0 个）」与「车机 AP 没广播（N>0 但无候选）」。

操作要点：车机 AP 不会一直开。14:03:06 连上、14:03:36 车机就断开并关 AP——
**先在车机屏幕选择本机/触发无线 CarPlay，让 `坦克 300...` 热点出现，手机连上后
30 秒内回 App 点「启动连接」**；等待观察者一看到当前 SSID 就会弹出
「按车机热点继续」，确认后走 ctrl/mDNS，不再依赖 0x5703。

## 11. 本机蓝牙地址填成了 WLAN MAC（2026-10-31）

adb 查到的真实蓝牙适配器地址：

```text
adb shell settings get secure bluetooth_address
→ 54:DD:4F:FC:C0:8F

adb shell dumpsys bluetooth_manager | grep Address
→ Address: XX:XX:XX:XX:C0:8F
```

而 App 里填的是：

```text
MAC: 本机蓝牙地址已设为 54:DD:4F:FC:C0:90
CTRL: 广播 _carplay-ctrl._tcp name=sender id=54:DD:4F:FC:C0:90
iap2 tx=0x4e0e 传输标识（54:DD:4F:FC:C0:90）
0x4300 ... body=... 35 34 3a 44 44 3a 34 46 3a 46 43 3a 43 30 3a 39 30
```

`:90` 是 WLAN MAC，`:8F` 才是蓝牙 MAC（同一颗 combo 芯片常见相邻分配）。这个值会进入：

- `0x4E0E DeviceTransportIdentifier`：车机用它把 iAP2 会话挂到对应的蓝牙传输；
- `0x4300 CarPlayAvailability` 的 `transportId`；
- `_carplay-ctrl._tcp` 广播的 `id`（车机凭它邀请 AirPlay）。

代码注释明确写了「真车会校验」。填错时，蓝牙 RFCOMM/MFi 仍然能通（那是真实 socket），
但车机可能不启动热点/不发 `0x5703`、不邀请 ctrl，表现为「握手成功但无线链路起不来」。
本车必须改成 `54:DD:4F:FC:C0:8F` 再重试；日志里 `0x4e0e`/`0x4300`/`CTRL id` 都应显示 `8F`。

### adb 授予的权限是否需要再处理

`pm grant` 写入的是系统运行时权限状态，**与 USB 调试是否开启无关**：

- 关闭 USB 调试、拔线、手机重启 → 权限仍然保留，不需要再 adb；
- `adb install -r` 覆盖安装（同包名/同签名）→ 权限保留；
- 只有卸载重装、清除应用数据、在系统设置里手动撤销、或 Android 的「长期未使用自动撤销权限」
  才会丢失；
- 新版本 App 已自己申请 `NEARBY_WIFI_DEVICES + ACCESS_FINE_LOCATION`，即使权限丢了，
  点「启动连接」时在弹窗里允许即可，不再依赖 adb。
