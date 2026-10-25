# CarPToCarL — 让 Android 手机充当 CarPlay 发送端，把原生 CarLife 投到车机

一台普通 Android 手机（未 root）扮演 **CarPlay 发送端（iPhone 角色）**，  
连到支持 CarPlay 的车机；画面内容取自**手机原生 CarLife+ 引擎的 H.264 码流**，  
经本 App 劫持后**直通转发**给车机（不重编码）。

> **非官方项目**：个人实验性作品，与 Apple Inc.、百度及任何汽车制造商均无关联，  
> 未获授权或认证，**不是 MFi 产品**。CarPlay / AirPlay / CarLife 均为其各自权利人的商标，  
> 文中出现仅用于说明兼容目标。
>
> **驾驶安全**：仅限停车时配置与操作；行驶中依赖本方案可能导致导航中断，  
> 请以原厂导航作为安全冗余。
>
> 完整条款见 [DISCLAIMER.md](DISCLAIMER.md)（与应用内首次启动的免责声明同源）。

---

## 一、项目简介

| 项    | 值                                                                  |
| ---- | ------------------------------------------------------------------ |
| 当前版本 | `0.2.14`（实验阶段；版本号唯一来源是 `app/build.gradle.kts` 顶部 `appVersionName`） |
| 包名   | `io.github.shaonialife.carptocar`                                  |
| 应用名  | **CarPToCarL**（不含任何第三方商标）                                          |
| 最低系统 | Android 12（minSdk 31，targetSdk 34）                                 |
| 适用车型 | 支持**无线 CarPlay** 的车机（真车已验证：奥迪 MIB3；测试台：DiPlay / xcertplay）         |
| 画面来源 | 本机 **CarLife+** 引擎（荣耀：`com.baidu.carlife.honor` 8.8.5）             |
| 音频   | PCM 直通（CarLife 音频通道 → AirPlay 音频通道）                                |

### 方向说明

- 本项目是**发送端**（手机扮演 iPhone），与 DiPlay / xcertplay 这类**接收端**（扮演车机）方向相反。  
  **DiPlay 是 xcertplay 的衍生版，是本项目实际使用的测试台**。
- 发送端**不需要苹果签名身份**：iAP2 认证是单向的（手机验证车机的 MFi 证书，车机信任手机自报结果）。
- **有线 USB 路线已排除**（App 无法控制 USB gadget，测试机 BL 无法解锁），只走无线。

### 背景：为什么需要它

Android 手机连 CarPlay 车机，官方路径不存在。本项目的做法是：既然车机只认 CarPlay，  
那就让手机**冒充 iPhone** 完成 iAP2 + AirPlay 全链路；既然手机侧最完整的车机界面是原生  
CarLife+，那就把它的视频/音频流**截下来直通**给车机。两边各自用最成熟的实现，中间只做搬运。

---

## 二、工作原理

### 整体链路

```
┌──────────────────── 手机（本 App） ────────────────────┐
│                                                        │
│  ① CarLife+ 引擎（com.baidu.carlife.honor）             │
│       │  H.264 码流 + PCM 音频（127.0.0.1:7240 等 7 个端口）
│       ▼                                                │
│  ② CarLifeHijack（冒充车机，接管码流，不重编码）         │
│       │                                                │
│       ▼                                                │
│  ③ VideoSender / AudioSender                           │
│       │  AirPlay 视频/音频流（ChaCha20 加密）            │
│       ▼                                                │
│  ④ 蓝牙 iAP2 控制通道 + WiFi（加入车机热点）             │
│                                                        │
└────────────────────────┬───────────────────────────────┘
                         │  无线 CarPlay
                         ▼
                  ⑤ 车机（CarPlay 接收端）
```

### 关键协议动作

| 阶段   | 协议               | 本 App 做什么                                        |
| ---- | ---------------- | ------------------------------------------------ |
| 识别车机 | 蓝牙 RFCOMM + iAP2 | 与车机配对，走 iAP2 握手，拿到车机热点凭据与 AirPlay 端点             |
| 入网   | WiFi             | 用 iAP2 拿到的凭据自动加入车机热点；mDNS 发现 AirPlay 服务          |
| 建立会话 | AirPlay          | PairVerify → SETUP（拿到车机视图尺寸）→ RECORD             |
| 视频   | AirPlay 视频流      | 把 CarLife 的 H.264 帧原样封装推流；`VideoConfig` 声明车机画布尺寸 |
| 音频   | AirPlay 音频流      | CarLife PCM → RTP 封装                             |
| 触控   | iAP2 HID         | 车机触摸回传到手机，经 CarLife 通道注入                         |
| 媒体信息 | iAP2 NowPlaying  | 歌名 / 歌手 / 歌词回传给车机                                |

### CarLife 劫持的协议参数（98386 / 98311）

本 App 冒充车机连上本机 CarLife 引擎后，主动下发两条关键配置：

**98386 `HU_FEATURE_CONFIG_RESPONSE`**

| 字段                          | 值       | 作用                                                                      |
| --------------------------- | ------- | ----------------------------------------------------------------------- |
| `CONNECT_TYPE`              | 5（WiFi） | 连接类型                                                                    |
| `I_FRAME_INTERVAL`          | 20      | I 帧间隔                                                                   |
| `DISABLE_RESOLUTION_OPTION` | **1**   | 让引擎 `useVehicleResolution=true`，按 98311 上报的车机真实分辨率编码，不再按宽度分档压成 1280×720 |
| `MEDIA_LYRIC_SUPPORT`       | **1**   | 开启歌词支持（车机侧显示歌名歌词）                                                       |

**98311 `CarlifeVideoEncoderInfo`**

| 字段                    | 值              | 说明                   |
| --------------------- | -------------- | -------------------- |
| 1 / 2 / 3             | 车机视图宽 / 高 / 帧率 | 来自 AirPlay SETUP，非写死 |
| 9 `needAdapterScreen` | **true**       | 比例不一致时请引擎等比适配，而不是硬拉伸 |

### 三个必须知道的技术事实（实测结论）

1. **画面源是荣耀车联创建的虚拟屏，不是普通屏幕采集。**  
   走荣耀车联流程时系统会创建 `HonorAutoDisplay`：`1920×1080 @ 20fps`，`type=VIRTUAL`，  
   `FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY`。CarLife 引擎只是编码这块屏。  
   → **分辨率和 20fps 上限都由荣耀侧决定，CarLife 协议改不动。**
2. **厂商适配层会覆盖编码尺寸。**  
   引擎把 98311 的尺寸交给 `MixActionDispatcher("carlife_encode_format")`（荣耀 mix 模块），  
   厂商层返回非 null 时覆盖 `MediaFormat` 宽高。实测请求 1412×654 时 SPS 仍可能回到 1280×720。  
   → 因此 `VideoConfig` 的声明**以车机画布为准**（`liveSize` 实时读 98311），  
   SPS 解析值只作诊断与兜底，绝不反过来改写声明（历史上这么做过，导致真车黑屏）。
3. **引擎不能由 App 自动拉起。**  
   自动拉起（`bindService` / `startForegroundService` / `startActivity`）确实能让引擎监听 7240  
   并被成功劫持，但**拿不到荣耀的画面源**——引擎只会把「荣耀车联 Powered by MagicOS」欢迎页  
   编码成恒定 ~10KB 的静止帧反复发送，车机表现为「连接成功但长时间没画面」。  
   → 所以 `0.2.14` 起**移除自动拉起**，改回用户手动打开 CarLife+ 点「无线连接」。

---

## 三、使用说明

### 前置条件

- 车机支持**无线 CarPlay**（有线不适用）。
- 手机已安装 **CarLife+**（荣耀机型为系统内置的「荣耀车联」CarLife 通道）。
- 手机蓝牙开启，且**已与车机完成系统级蓝牙配对**（不是 App 内扫描配对，是系统设置里的配对）。
- 首次使用需授予若干权限（见下方「权限清单」）。

### 首次配置（只做一次）

1. 安装并打开 **CarPToCarL**，阅读并同意免责声明。
2. 按提示授予权限：
   - **附近设备 / 蓝牙**：扫描与配对车机
   - **通知使用权**：读取音乐会话（歌名、歌手、歌词、播放状态联动）
   - **通知权限**：前台保活服务的常驻通知
   - 可选：**所有文件访问**（仅「画质增强」需要）
3. 在「连接准备」里选择目标车机（可选）。不指定时会依次尝试全部已配对设备。
4. 如车机蓝牙地址不固定，可在 **MAC** 输入框手填并保存。

### 每次使用（重要：顺序）

```
① 手机与车机蓝牙配对（系统蓝牙里完成）
        ↓
② 打开「荣耀车联 / CarLife+」→ 点【无线 WLAN 连接】
        ↓
③ 停在第二步「正在连接」界面（不要退出、不要返回）
        ↓
④ 回到 CarPToCarL → 点【启动连接】
        ↓
⑤ App 自动加入车机热点 → 建立 CarPlay 会话 → 画面出现在车机
```

> **第 ③ 步是关键**：必须让 CarLife+ 真的进入「正在连接」状态。  
> 此时引擎才启动、才创建虚拟屏、才开始监听 7240。App 检测到端口一开立即接管。
>
> 顺序不是绝对强制的——App 一启动就常驻守候，先点「启动连接」再开 CarLife+ 也可以。  
> 但推荐按上面的顺序，因为「正在连接」有超时，先让 App 就位更稳。

### 界面上的状态提示

主界面状态卡片**上方**有一行 CarLife 状态文字，直接告诉你现在卡在哪：

| 显示                             | 含义           | 该做什么                  |
| ------------------------------ | ------------ | --------------------- |
| 等待你手动打开 CarLife+ 并点「无线连接」      | 没检测到 7240 监听 | 去做上面的第 ②③ 步（点这行可重启守候） |
| 已劫持 CarLife+ 画面 —— 点「启动连接」推到车机 | 码流已接到，还没推给车机 | 点「启动连接」               |
| 车机画面来自 CarLife+（直通中）           | 正在推流         | 无需操作                  |
| CarLife 劫持已在设置里关闭 —— 不会有车机画面   | 开关被关了        | 打开「本地 CarLife 劫持」开关   |

### 断开

- 点【断开连接】：停止推流并关闭保活服务，车机画面立即中断。
- 投屏授权会随服务停止失效，下次若要车机侧出声需重新授权。

### 权限清单

| 权限                                     | 用途                                      | 必需      |
| -------------------------------------- | --------------------------------------- | ------- |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | iAP2 控制通道、扫描配对车机                        | ✅       |
| `NEARBY_WIFI_DEVICES`（12L 及以下还需定位）     | 用 `WifiNetworkSpecifier` 加入车机热点         | ✅       |
| `RECORD_AUDIO`                         | `AudioPlaybackCapture` 捕获本机媒体/导航声音      | ✅（要声音时） |
| `POST_NOTIFICATIONS`                   | 前台服务常驻通知                                | ✅       |
| 通知使用权（特殊权限）                            | 读取媒体会话：歌名/歌手/歌词/播放状态                    | 要歌词就必需  |
| `MANAGE_EXTERNAL_STORAGE`              | 写 `/sdcard/BaiduCarlife` 下的引擎调试配置（画质增强） | ❌ 可选    |
| `SYSTEM_ALERT_WINDOW`                  | 暗幕（替代息屏）                                | ❌ 可选    |

---

## 四、已实现能力

| 能力               | 状态    | 说明                                                                                   |
| ---------------- | ----- | ------------------------------------------------------------------------------------ |
| 无线 iAP2 发送端会话    | ✅     | 走通至 0x4301，拿到热点凭据与 AirPlay 端点                                                        |
| AirPlay 主会话      | ✅     | PairVerify + H.264 推流 + HID 触摸回传                                                     |
| 真车验证（奥迪 MIB3）    | ✅     | 2026-10-11 全通：视频上屏 + 触摸可交互                                                           |
| CarLife 画面直通     | ✅     | 劫持本机引擎码流，不重编码                                                                        |
| CarLife 音频直通     | ✅     | PCM → RTP，支持播放/暂停联动                                                                  |
| 歌名 / 歌手 / 歌词     | ✅     | 98386 `MEDIA_LYRIC_SUPPORT=1` + iAP2 NowPlaying                                      |
| 车机分辨率自适应         | ⚠️    | 98311 上报车机真实视图 + `needAdapterScreen=true`；**但受厂商 mix 层限制，实际码流可能仍为 1280×720 或 1080p** |
| 触摸回传             | ✅     | 车机触摸 → CarLife 通道注入；坐标以 **SPS 真实分辨率**为基准，触摸异步队列不积压                                    |
| 画质增强             | ⚠️ 实验 | 写引擎调试配置；实测该 MediaFormat 通道对码率/帧率无效                                                   |
| 自绘隐形桌面（launcher） | 保留    | 早期主路径，现非默认；画面唯一来源已是 CarLife 劫持                                                       |

### 已知限制

1. **帧率上限 20fps**：来自荣耀虚拟屏 `renderFrameRate 20.0`，不是本 App 的节流，协议改不了。
2. **分辨率不可完全自主**：厂商 mix 层可能把编码尺寸改回 1280×720 / 1080p。当前策略是  
   「声明跟车机画布一致」，尽量不出现变形；比例差异大的车机（如 21:9）可能仍有黑边或轻微拉伸。
3. **必须手动进 CarLife+**：自动拉起拿不到画面源（见第二节事实 3），这是设计取舍，不是 bug。
4. **依赖 CarLife+ 版本行为**：逆向结论基于荣耀 8.8.5（versionCode 274），升级后协议可能变化。

---

## 五、故障排查

### 「连接成功但长时间没画面」

按可能性排序：

1. **CarLife+ 没停在「正在连接」** —— 最常见。引擎没启动或没创建虚拟屏，  
   `netstat -tln | grep 7240` 无监听。重做「每次使用」第 ②③ 步。
2. **引擎被别的东西拉起来了** —— 如果引擎是被 App 或第三方直接拉起（没有荣耀虚拟屏），  
   它只会编码欢迎页。执行 `am force-stop com.baidu.carlife.honor`，再走正式流程。
3. **`VideoConfig` 声明与码流不一致** —— 车机按声明建解码器，尺寸不符就解不出画面。  
   正确状态是日志里 `DISPLAY: CarLife 直通流 WxH` 与 `VIDEO: VideoConfig 声明` 一致。

### 触摸迟钝 / 拖不动 / 点不准

按 2026-10-25 实测日志 + 引擎字节码定位到 5 个原因，均已修：

1. **坐标被缩放了两次（v0.2.17 修正）**。引擎 `CarlifeTouchManager.dispatchTouchEvent`
   拿到 x/y 后会自己乘 `getTouchXOffset()`：mix 连接下是 `CastConfig.width / mContainerWidth`，
   普通投屏下是 `手机屏宽 / mContainerWidth`——**输入域就是车机 display 坐标，换算由引擎做**。
   v0.2.15 拿码流 SPS 分辨率当基准、v0.2.16 又改成 `x × 视频宽 / 视图宽`，
   本质都是替引擎做了那次换算：码流 1280×720 配车机 1412×654 时横竖向各缩 10%，
   拖拽位移同比变小、落点偏移。现在**坐标原样透传**，只做 `0..视图宽高` 的边界钳位。
   顺带把发送层的 `coerceIn(0, videoSize-1)` 也去掉了——它会把右侧 132 像素压成同一个点。
2. **UP 丢了会让整块屏幕永久卡死（v0.2.16 修一半，v0.2.17 补全）**。
   引擎 `onActionDownEvent` 的反汇编逻辑是：
   ```java
   if (isErrorTouchModel()) { if (eventIng) { errorDown = (x,y); return; } }  // DOWN 被丢弃
   eventIng = true;
   ```
   只要某一笔的 UP 没送到，`eventIng` 永远是 `true`，之后每一次 DOWN 都被丢弃；
   而 MOVE 又会被 `isErrorEvent` 判成「更靠近上次的错误按下点」而 return。
   表现是：点一下（或拖一下）之后，整块屏幕再也点不动、拖不动，像死机。
   v0.2.16 修了「(0,0) 空槽过滤把 UP 吞掉」，但车机把鼠标拖出控件/窗口时仍可能不发 UP，
   所以 v0.2.17 加了兜底：**只要与上一笔间隔超过 220ms 又收到按下，先补一个 UP 再开新笔**
   （日志 `CARLIFE: 上一笔缺抬起 XXXXms → 补发 UP`）。
3. **事件积压（v0.2.15 已修）**。引擎消费触摸是串行的，慢时一条要几百毫秒，
   直接写 socket 会把车机端回调线程一起拖住。现在是**单线程队列**：
   DOWN/UP 保序不丢，MOVE 只保留最新坐标。
4. **本地节流去掉了（v0.2.16）**。引擎自身对 MOVE 有 10ms 限频（字节码 `const-wide/16 v4, 10`），
   本地再叠加 16ms 只会让拖拽变成跳跃采样。
5. **引擎侧响应慢（改不了）**。日志实测点击到画面反应 0.7~3.1s，平均帧只有 4KB，
   说明手机端 CarLife 引擎本身处理就慢（它同时在跑 1080p 编码）。

自检：拖一次，日志应出现 `CARLIFE: 手势结束 N次移动 时长Xms 位移Ypx ↓(x,y) → UP(x,y)`
（N ≥ 3、位移和手指移动距离一致），且不应出现「补发 UP」。
出现「补发 UP」说明车机端漏发抬起，那不是本 App 的问题，但补发能保证不死锁。

### 连接很慢（点了「启动连接」半天没反应）

主要卡在**蓝牙 iAP2 出站**这一步：

- 如果「连接准备」里指定了目标车机，旧版会**死等目标**：出站只试它、入站把其它设备全拒掉。
  坐进另一台车（或换测试台）时表现为四十多秒毫无反应。
- v0.2.16 起目标连续 2 轮（约 6 秒）连不上就自动**降级为优先**：出站回退到全部已配对设备、
  入站不再拒绝，日志会打印 `BT-C: 目标「X」连续 N 轮连不上——本轮放开目标限制…`。
  每次点「启动连接」都会重新给目标一个窗口，真车场景行为不变。
- 还有两件事只能等系统：首次用 `WifiNetworkSpecifier` 加入车机热点会弹一次确认框；
  车机热点本身可能没有互联网，不要手动到系统 WLAN 里连它（会让手机以为断网）。

### 连上后过一会儿自己断

按 2026-10-25 实测：**音频还在推、视频 0 帧超过约 30 秒，车机会判定「源没了」并拆整轮会话**
（日志 `VIDEO: 直通 keepAlive 中止 - Broken pipe` / `EVENT: 通道关闭（EOF）`）。
常见原因是 CarLife+ 那边还没进「无线连接」、或在虚拟屏上没内容可编码。

v0.2.16 起直通模式在**空闲 1.5 秒**后每 2 秒重发**最后一个 IDR**，撑住车机的存活判定
（重发 IDR 而不是最后那帧 P 帧：IDR 自包含，重复解码只是同一幅画面；P 帧重复会花屏）。
日志标志：`VIDEO: 已 Ns 没有新画面——重发最后一个 IDR 维持车机连接`。

注意这只维持连接，不会凭空产生新画面。真要出画面仍需在手机上走一次
「打开 CarLife+ → 无线连接 → 停在正在连接」。

### 画面拉伸 / 变形

- 车机请求的视图比例（如 1412×654 ≈ 2.16:1）与码流比例（16:9）不同就会变形。
- 当前已下发 `needAdapterScreen=true` 请求等比适配，但厂商层不一定采纳。
- 缓解手段：在车机侧把显示比例调成接近 16:9；或在 CarLife+ 设置里调整画面分辨率。

### 车机上没有歌名歌词

- 检查通知使用权是否授予（读不到媒体会话就没有歌名）。
- 检查日志里 98386 是否包含 `MEDIA_LYRIC_SUPPORT=1`。
- 确认音乐 App 正在播放且媒体会话可见。

### App 显示「直通中」但车机没画面

- 看日志 `★ 收到 CarLife 首帧` 的字节数：恒定 ~10KB 且全是关键帧 = 引擎在送欢迎页（见上「没画面」）。
- 正常应是几十 KB 的关键帧 + 几百字节到几 KB 的 P 帧交替。

### 日志与诊断

主界面底部「日志」区可**复制 / 导出 / 清空**，还有「残留清理」用于清掉上次的脏状态。  
调试文件日志在应用私有目录 `files/main_debug.log`：

```bash
adb shell "run-as io.github.shaonialife.carptocar cat files/main_debug.log"
```

---

## 六、构建

> 本项目**固定使用 JDK 17**（`.tools/jdk-17.0.20.1+1`，Temurin 17），  
> `gradle.properties` 的 `org.gradle.java.home` 已指向它，**不要改**。  
> 系统默认 `JAVA_HOME` 若指向 JDK 8 会导致编译极慢甚至卡死。

```bash
# 推荐：wrapper 已改为本地发行版（不再访问 services.gradle.org）
./gradlew assembleDebug --offline
```

也可以直接调本地 Gradle：

```bash
./.tools/gradle-8.10.2/bin/gradle assembleDebug -I .tools/init-mirror.gradle
```

其它命令：

```bash
./gradlew :app:assembleRelease      # 发布包
./gradlew testDebugUnitTest         # 单元测试（33 个测试文件）
./gradlew :app:lintDebug            # lint 检查
```

- `.tools/` 是工具链 junction（JDK 17 + Android SDK 34 + Gradle 8.10.2），  
  指向 `../cardeck/.tools`（Windows 目录 junction，共享工具链，省 ~870MB）。
- `init-mirror.gradle` 把依赖源指向腾讯云镜像（`google()` / `mavenCentral()` 本机易超时）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装与调试

```bash
# adb 不在 PATH 时，项目内自带一份（.tools/platform-tools/）
./.tools/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
./.tools/platform-tools/adb.exe shell am start -n io.github.shaonialife.carptocar/.MainActivity
```

---

## 七、工程结构

单模块 Android 应用（`app/`），主源码 83 个 Kotlin 文件，按**链路职责**切分。

### 根目录

```
CarPToCarL/
├── app/                          # 唯一的 Android 应用模块
├── docs/                         # 协议知识库与逆向结论
├── gradle/wrapper/               # wrapper（已改为本地发行版 zip）
├── .tools/                       # 工具链 junction → ../cardeck/.tools
├── build.gradle.kts              # 根构建脚本
├── settings.gradle.kts           # 项目名 + 仓库/镜像源
├── gradle.properties             # JVM 参数 + org.gradle.java.home（固定 JDK 17）
├── keystore.properties.example   # 签名配置模板
├── .editorconfig                 # Kotlin/ktlint 代码风格
├── LICENSE                       # MIT
├── DISCLAIMER.md                 # 免责声明
└── README.md                     # 本文档
```

### docs/（逆向与协议沉淀）

| 文档                                                                             | 内容                        |
| ------------------------------------------------------------------------------ | ------------------------- |
| [docs/carlife-hijack-findings.md](docs/carlife-hijack-findings.md)             | CarLife 协议逆向发现（端口、消息号、时序） |
| [docs/carlife-hijack-integration.md](docs/carlife-hijack-integration.md)       | 劫持功能的集成实现                 |
| [docs/carlife-wireless-verification.md](docs/carlife-wireless-verification.md) | 无线链路验证记录                  |
| [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md)                               | iAP2 / AirPlay 协议依据       |
| [docs/CARLIFE-GATEWAY-FEASIBILITY.md](docs/CARLIFE-GATEWAY-FEASIBILITY.md)     | 网关方案可行性评估                 |

### 主源码包结构（`app/src/main/java/io/github/shaonialife/carptocar/`）

```
├── MainActivity.kt               # 手机侧操作台 UI（连接 / 声音 / 暗幕 / 日志 / 免责声明）
├── SessionController.kt          # 会话总管（进程级单例）：链路生命周期、配对、WiFi、取消
├── SenderApp.kt                  # Application（未捕获异常兜底、包变化失效缓存）
├── carlife/       (5)            # 本地 CarLife 劫持（HU 客户端 / 协议 / 音频 / 画质配置）
├── iap2/          (8)            # iAP2 链路层 + CSM + 发送端会话 + NowPlaying
├── airplay/      (10)            # AirPlay 配对 / 控制通道 / 流协商 / 加密 / Timing
├── audio/         (6)            # 音频通道（AAC 编码 / 播放捕获 → RTP/ChaCha20）
├── video/         (1)            # H.264 推流（VideoSender，含 SPS 解析与声明对齐）
├── bt/            (5)            # RFCOMM 双向（Server 等回连 / Client 真车主动连）
├── launcher/     (11)            # 隐形屏启动器（保留，非默认画面来源）
│   └── settings/  (2)            #   设置面板（显示缩放、应用方向兼容）
├── render/        (2)            # EGL 多屏合成 → 编码 Surface
├── input/         (4)            # 触摸分流 + 消费键 + CarInput 客户端
├── capture/       (2)            # 画面源
├── net/           (6)            # WiFi 入网 / mDNS 发现与 ctrl 广播
├── media/         (2)            # 通知使用权 → 媒体会话（音乐卡 / 歌词数据源）
├── service/       (1)            # 前台保活服务
├── transport/     (2)            # 传输层封装
├── ui/            (5)            # 暗幕、波形图、壁纸、深色弹窗、面板尺寸
├── legal/         (3)            # 免责声明
└── util/          (5)            # 后台任务池、日志导出、设备标识
```


```

### 各包职责速查

| 包 | 定位 | 关键内容 |
|---|---|---|
| `carlife` | CarLife 劫持 | 冒充车机连本机 CarLife+，H.264/PCM 直通，98386/98311 参数下发 |
| `iap2` | iAP2 协议链路 | Link 引擎、CSM、发送端会话状态机、NowPlaying |
| `airplay` | AirPlay 主会话 | PairVerify、控制通道、流协商、FairPlay/ChaCha 加密、Timing |
| `video` | 视频推流 | H.264 封装、GOP 缓存、SPS 解析、`VideoConfig` 声明对齐 |
| `audio` | 音频通道 | AAC 编码、播放捕获、RTP 封装 |
| `bt` | 蓝牙链路 | RFCOMM Server/Client、目标车机设备 |
| `net` | 网络入网 | WiFi、mDNS 发现、ctrl 广播 |
| `launcher` | 隐形桌面 | Dock、应用网格、地图屏（保留能力，默认不走） |
| `render` | 画面合成 | EGL 多屏合成 → 编码 Surface |
| `input` | 输入链路 | 触摸分流、消费键、CarInput |
| `media` | 媒体会话 | 通知使用权、音乐与歌词数据源 |
| `service` | 前台服务 | 连接/投屏保活 |
| `ui` / `legal` / `util` | 通用 | 暗幕、免责声明、日志导出等 |

### AIDL

```

app/src/main/aidl/io/github/shaonialife/carptocar/input/ICarInput.aidl

```
跨进程传输触摸/输入事件（`input/` 包下客户端通过 Binder 调用）。

### 单元测试

`app/src/test/java/io/github/shaonialife/carptocar/`，共 **33** 个测试文件，重点覆盖协议编解码与密码学：

- `airplay/`：PairVerify、BinaryPlist、控制通道加密、流帧加密、Timing
- `iap2/`：编解码、Link 引擎、发送端会话
- `audio/`：AAC 编码、载荷封装
- `video/`：SPS 解析（含宏块对齐与 frame_cropping 的回归用例）
- `input/` `launcher/` `net/` `ui/` `util/` 等：各自单元逻辑

> 早期版本依赖 **Shizuku**（虚拟屏注入 / 触摸注入），2026-10-24 起已**移除**，
> 代码中 `shizuku/` 目录与 `ShizukuGate` 均已删除，残留文字仅为历史注释。

---

## 八、代码质量（提交前自动拦截）

`git commit` 前 pre-commit 钩子自动执行两步，任一失败即阻止提交：

1. **格式化**：Spotless + ktlint（规则见 `.editorconfig`）
2. **Android Lint**：`lintDebug` 全量检查，error 级使提交失败

存量问题记录在 `app/lint-baseline.xml` 中豁免；新代码再引入同类问题仍会被拦截。

---

## 九、签名与发布

- **版本号**：`app/build.gradle.kts` 顶部 `appVersionName` 是唯一来源，`versionCode` 自动推导
  （目前 `0.2.14` → `214`）。
- **签名**：release 默认退回 debug 签名；正式分发前配置真实签名：
  1. 复制 `keystore.properties.example` → `keystore.properties`（已 gitignore）
  2. `keytool -genkeypair -v -keystore carptocar-release.jks -alias carptocar -keyalg RSA -keysize 4096 -validity 10000`
  3. 填密码后 `assembleRelease`，用 `apksigner verify --print-certs` 确认证书 CN 非 `Android Debug`

> ⚠️ 私钥泄露 = 任何人可签发能覆盖安装的更新；私钥丢失 = 已装用户再也收不到更新。

---

## 十、边界与商标

- **许可证**：源码以 [MIT](LICENSE) 发布。`reference/` 下参考项目（GPL-3.0 等）
  已被 `.gitignore` 排除、不随仓库分发；本项目为独立实现，仅参考公开协议格式与真机抓包。
- 应用名为 **CarPToCarL**，**不含**任何第三方商标。
- 「CarPlay」「AirPlay」「CarLife」均为其权利人的商标，文中出现仅作**描述性引用**。
- 本项目**不使用** Apple、百度或任何车厂的图形标识与认证标（含 MFi）。
- 真车验证前，建议先用可借到的 iPhone 与车机做一次正常无线配对，留作对照基线。
```
