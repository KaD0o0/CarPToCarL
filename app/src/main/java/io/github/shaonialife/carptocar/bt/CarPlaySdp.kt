package io.github.shaonialife.carptocar.bt

import java.util.UUID

/**
 * 无线 CarPlay 的 SDP 身份记录（手机侧）。
 *
 * 车机判断「这台手机是不是无线 CarPlay 设备」看的是 **SDP 服务记录**，不是蓝牙名，
 * 也不是 EIR 里的厂商字段：
 *
 *  - `…cafe`：iPhone 的 iAP2 服务 UUID。Aptiv/GWM 的 `RemoteDevices` 用它置
 *    `mCarplaySupported`，`AptivBluetoothConnectionManagerService.isIAPSupported()`
 *    也用它。`Iap2BluetoothServer` 已经注册。
 *  - `2d8d2466-…-291a`：Aptiv/GWM V3（坦克 300 等同平台车机）在
 *    `AptivBluetoothConnectionManagerService.isCPSupportedOverWiFi()` 里**只认这个探针
 *    UUID** 判断「支持无线 CarPlay」。只注册 `…cafe` 时车机会把手机归到「仅 iAP2/有线」，
 *    `PhoneProjectionService` 不会把它标记成 `CARPLAY_WIRELESS`，也就不会发起无线会话。
 *    因此必须额外发布一条只占 SDP 位、不承载任何协议的记录（参考 CarWith CT5 的
 *    `PHONE_EIR_UUID` / `CarPlay Identity Probe`）。
 *
 * 常量单独放在这个纯 JVM 对象里，便于单测锁定 UUID 原文；业务代码不要另抄一份。
 */
object CarPlaySdp {
    /** iPhone 侧 iAP2 服务 UUID（配件端是 `…caff`）。 */
    val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    /** Aptiv/GWM V3 车机用来识别「无线 CarPlay 设备」的 SDP 探针 UUID。 */
    val IDENTITY_PROBE_UUID: UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")

    /** SDP 记录名（只给人/日志看，车机只认 UUID）。 */
    const val IAP2_SDP_NAME = "iAP2"
    const val IDENTITY_PROBE_NAME = "CarPlay Identity Probe"
}
