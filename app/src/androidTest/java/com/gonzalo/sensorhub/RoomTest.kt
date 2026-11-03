package com.gonzalo.sensorhub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gonzalo.sensorhub.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomTest {
    @Test fun claimAndConfirmRespectOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,HubDatabase::class.java).build()
        try {
            val dao = db.measurements()
            val rows = Readings.motion(floatArrayOf(1f,2f,3f),0)
            rows.first().usuarioId = "old"
            dao.insert(rows)
            dao.claim("new")
            assertEquals(2,dao.pending("new").size)
            val old = rows.first()
            dao.confirm(old.medicionId,"new")
            assertEquals(1,dao.pending("old").size)
            dao.confirm(old.medicionId,"old")
            assertEquals(1,dao.count(true).first())
            assertEquals(3,dao.latest().first().size)
            assertEquals(1,dao.history("ANDROID","aceleracion_x").first().size)
        } finally { db.close() }
    }
}
