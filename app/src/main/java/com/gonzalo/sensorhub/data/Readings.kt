package com.gonzalo.sensorhub.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

object Readings {
    private val iso = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private fun row(metric: String, value: Double, unit: String, time: Long, mobile: Boolean) =
        Measurement().apply {
            medicionId = UUID.randomUUID().toString()
            dispositivoId = if (mobile) "MOVIL_01" else "ESP32_01"
            origen = if (mobile) "ANDROID" else "ESP32"
            variable = metric
            valor = value
            unidad = unit
            fechaHora = iso.format(Instant.ofEpochMilli(time))
        }

    fun telemetry(bytes: ByteArray, time: Long): List<Measurement> {
        if (bytes.size != 6 || bytes[0].toInt() != 1 || bytes[1].toInt() != 1) return emptyList()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val t = data.getShort(2) / 10.0
        val h = data.getShort(4) / 10.0
        if (t !in 0.0..50.0 || h !in 0.0..100.0) return emptyList()
        return listOf(row("temperatura", t, "°C", time, false), row("humedad", h, "%", time, false))
    }

    fun motion(values: FloatArray, time: Long): List<Measurement> {
        if (values.size < 3 || (0..2).any { !values[it].isFinite() }) return emptyList()
        return listOf("x","y","z").mapIndexed { i, axis ->
            row("aceleracion_" + axis, values[i].toDouble(), "m/s²", time, true)
        }
    }
}
