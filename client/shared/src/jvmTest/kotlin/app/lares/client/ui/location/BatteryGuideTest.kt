package app.lares.client.ui.location

import kotlin.test.Test
import kotlin.test.assertEquals

/** Vendor hints for battery saving (LOC-008): `Build.MANUFACTURER` → vendor. */
class BatteryGuideTest {
    @Test
    fun `LOC-008 manufacturers map to their vendor hints`() {
        val cases =
            mapOf(
                "Xiaomi" to BatteryVendor.XIAOMI,
                "Redmi" to BatteryVendor.XIAOMI,
                "POCO" to BatteryVendor.XIAOMI,
                "HUAWEI" to BatteryVendor.HUAWEI,
                "HONOR" to BatteryVendor.HUAWEI,
                "samsung" to BatteryVendor.SAMSUNG,
                "OnePlus" to BatteryVendor.ONEPLUS,
                "OPPO" to BatteryVendor.ONEPLUS,
                "realme" to BatteryVendor.ONEPLUS,
                "Google" to BatteryVendor.OTHER,
                "unknown" to BatteryVendor.OTHER,
                "" to BatteryVendor.OTHER,
            )
        cases.forEach { (manufacturer, vendor) -> assertEquals(vendor, batteryVendor(manufacturer), manufacturer) }
    }

    @Test
    fun `every vendor has its own hint`() {
        assertEquals(
            BatteryVendor.entries.size,
            BatteryVendor.entries
                .map { it.hint }
                .toSet()
                .size,
        )
    }
}
