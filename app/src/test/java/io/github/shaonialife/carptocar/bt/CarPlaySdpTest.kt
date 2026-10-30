package io.github.shaonialife.carptocar.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 锁定无线 CarPlay 的 SDP 身份常量。
 *
 * Aptiv/GWM V3（坦克 300 等同平台）车机在
 * `AptivBluetoothConnectionManagerService.isCPSupportedOverWiFi()` 里按字符串比较
 * `2D8D2466-E14D-451C-88BC-7301ABEA291A`；大小写不敏感，但多一位/少一位就会静默
 * 判成「不支持无线 CarPlay」。这个用例就是防这种手抄错误。
 */
class CarPlaySdpTest {
    @Test
    fun identityProbeMatchesAptivGwmExpectation() {
        assertEquals(
            "2d8d2466-e14d-451c-88bc-7301abea291a",
            CarPlaySdp.IDENTITY_PROBE_UUID.toString(),
        )
    }

    @Test
    fun probeIsNotTheIap2Uuid() {
        assertNotEquals(CarPlaySdp.IPHONE_IAP2_UUID, CarPlaySdp.IDENTITY_PROBE_UUID)
    }

    @Test
    fun sdpNamesAreStable() {
        assertEquals("CarPlay Identity Probe", CarPlaySdp.IDENTITY_PROBE_NAME)
        assertEquals("iAP2", CarPlaySdp.IAP2_SDP_NAME)
    }
}
