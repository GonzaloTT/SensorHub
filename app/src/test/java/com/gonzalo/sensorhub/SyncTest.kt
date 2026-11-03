package com.gonzalo.sensorhub

import com.gonzalo.sensorhub.data.requiresCreation
import org.junit.Assert.*
import org.junit.Test

class SyncTest {
    private val local = mapOf("medicionId" to "a", "valor" to 2.0)
    @Test fun createsMissingDocument() = assertTrue(requiresCreation(false,null,local))
    @Test fun confirmsExactReplayWithoutWrite() = assertFalse(requiresCreation(true,local.toMap(),local))
    @Test(expected=IllegalStateException::class) fun rejectsConflict() {
        requiresCreation(true,local + ("valor" to 3.0),local)
    }
}
