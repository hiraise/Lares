package app.lares.android.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * The significant-motion trigger sensor (ADR-0028): armed in STILL, fires once when the phone starts moving, then
 * must be armed again. Phones without it use the shorter STILL interval (TrackingPolicy).
 */
class MotionTrigger(
    context: Context,
    private val onMotion: () -> Unit,
) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var armed = false

    private val listener =
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                armed = false
                onMotion()
            }
        }

    val available: Boolean get() = sensor != null

    fun arm() {
        if (armed || sensor == null) return
        armed = sensors?.requestTriggerSensor(listener, sensor) == true
    }

    fun disarm() {
        if (!armed || sensor == null) return
        sensors?.cancelTriggerSensor(listener, sensor)
        armed = false
    }

    companion object {
        fun present(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null
    }
}
