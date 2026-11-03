package com.gonzalo.sensorhub

import com.gonzalo.sensorhub.data.Readings
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReadingsTest {
    private val packet = byteArrayOf(1,1,254.toByte(),0,68,2)
    @Test fun telemetryCreatesIndependentPendingRows() {
        val rows = Readings.telemetry(packet, 0)
        assertEquals(listOf("temperatura","humedad"), rows.map { it.variable })
        assertEquals(listOf(25.4,58.0), rows.map { it.valor })
        assertEquals(2,rows.map { UUID.fromString(it.medicionId) }.distinct().size)
        assertTrue(rows.all { !it.sincronizado && it.usuarioId == null && it.origen=="ESP32" &&
            it.fechaHora=="1970-01-01T00:00:00.000Z" })
    }
    @Test fun rejectsInvalidFrames() {
        listOf(packet.copyOf(5),packet.copyOf(7),packet.clone().apply { this[0]=2 },
            packet.clone().apply { this[1]=0 },packet.clone().apply { this[3]=5 },
            packet.clone().apply { this[5]=9 }).forEach { assertTrue(Readings.telemetry(it,0).isEmpty()) }
    }
    @Test fun accelerationHasThreeFiniteAxes() {
        val rows = Readings.motion(floatArrayOf(-1f,0f,9.8f),1000)
        assertEquals(listOf("aceleracion_x","aceleracion_y","aceleracion_z"),rows.map { it.variable })
        assertTrue(rows.all { it.unidad=="m/s²" && it.dispositivoId=="MOVIL_01" })
        assertTrue(Readings.motion(floatArrayOf(Float.NaN,0f,0f),0).isEmpty())
    }
}
