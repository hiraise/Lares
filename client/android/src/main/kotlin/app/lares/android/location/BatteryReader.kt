package app.lares.android.location

import android.content.Context
import android.os.BatteryManager

/** Battery level (0..100) and charging for every recorded fix (LOC-007); nulls when the phone does not tell. */
class BatteryReader(
    context: Context,
) {
    private val manager = context.getSystemService(BatteryManager::class.java)

    fun level(): Int? = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    fun charging(): Boolean? = manager?.isCharging
}
