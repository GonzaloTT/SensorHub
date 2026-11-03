package com.gonzalo.sensorhub

import android.app.Application
import android.hardware.*
import android.os.SystemClock
import androidx.lifecycle.*
import com.gonzalo.sensorhub.ble.BleClient
import com.gonzalo.sensorhub.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class HubViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {
    val repo = Repository.get(app)
    val error = MutableStateFlow("")
    val ble = BleClient(app) { save(Readings.telemetry(it, System.currentTimeMillis())) }
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val motion = MutableStateFlow(if (accelerometer == null) "No disponible" else "Detenido")
    private var lastSample = Long.MIN_VALUE
    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.authenticate() } }
    }
    private fun save(rows: List<Measurement>) {
        if (rows.isEmpty()) return
        viewModelScope.launch { try { repo.save(rows) } catch (e: Exception) { error.value = "Room: " + e.localizedMessage } }
    }
    fun start() {
        if (motion.value == "Activo" || accelerometer == null) return
        lastSample = Long.MIN_VALUE
        motion.value = if (sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)) "Activo" else "Error de sensor"
    }
    fun stop() {
        sensors.unregisterListener(this)
        motion.value = if (accelerometer == null) "No disponible" else "Detenido"
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (motion.value != "Activo" || (lastSample != Long.MIN_VALUE && event.timestamp-lastSample < 1_000_000_000L)) return
        lastSample = event.timestamp
        val epoch = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos()-event.timestamp)/1_000_000
        save(Readings.motion(event.values, epoch))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    fun sync() { viewModelScope.launch { repo.sync() } }
    fun release() { stop(); ble.close() }
    override fun onCleared() { release() }
}
