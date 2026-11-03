package com.gonzalo.sensorhub.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

class Repository private constructor(private val context: Context) {
    companion object {
        private var instance: Repository? = null
        @Synchronized fun get(context: Context): Repository =
            instance ?: Repository(context.applicationContext).also { instance = it }
    }
    private val db = Room.databaseBuilder(context, HubDatabase::class.java, "sensorhub.db").build()
    val dao = db.measurements()
    private val auth = FirebaseAuth.getInstance()
    private val prefs = context.getSharedPreferences("ownership", Context.MODE_PRIVATE)
    private val gate = Mutex()
    val identity = MutableStateFlow("Sin sesión")
    val status = MutableStateFlow("En espera")
    val last = MutableStateFlow(prefs.getString("last", null))

    @Synchronized fun authenticate(): String {
        try {
            val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously()).user ?: error("Sin usuario")
            dao.claim(prefs.getString("owner", null) ?: user.uid)
            prefs.edit().putString("owner", user.uid).commit()
            identity.value = "Sesión anónima activa"
            return user.uid
        } catch (e: Exception) {
            identity.value = "Sin sesión disponible: " + e.localizedMessage
            throw e
        }
    }

    suspend fun save(rows: List<Measurement>) = withContext(Dispatchers.IO) {
        val owner = auth.currentUser?.uid ?: prefs.getString("owner", null)
        rows.forEach { it.usuarioId = owner }
        dao.insert(rows)
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) return@withContext
        try {
            status.value = "Sincronizando"
            val net = context.getSystemService(ConnectivityManager::class.java)
            check(net.getNetworkCapabilities(net.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) { "Sin Internet" }
            val uid = authenticate()
            dao.claim(uid)
            val cloud = FirebaseFirestore.getInstance()
            val limit = dao.watermark()
            while (true) {
                val batch = dao.batch(uid, limit)
                if (batch.isEmpty()) break
                for (m in batch) {
                    val ref = cloud.collection("equipos").document(m.equipoId).collection("usuarios")
                        .document(uid).collection("mediciones").document(m.medicionId)
                    val fields = mapOf("medicionId" to m.medicionId, "equipoId" to m.equipoId,
                        "usuarioId" to uid, "dispositivoId" to m.dispositivoId, "origen" to m.origen,
                        "variable" to m.variable, "valor" to m.valor, "unidad" to m.unidad, "fechaHora" to m.fechaHora)
                    Tasks.await(cloud.runTransaction { tx ->
                        val remote = tx.get(ref)
                        if (requiresCreation(remote.exists(), remote.data, fields)) tx.set(ref, fields)
                        true
                    })
                    dao.confirm(m.medicionId, uid)
                }
            }
            last.value = Instant.now().toString()
            prefs.edit().putString("last", last.value).apply()
            val reserved = dao.otherOwners(uid)
            status.value = if (reserved == 0) "Sincronización completada para la sesión actual"
                else "Sesión sincronizada. Pendientes de otra identidad: " + reserved
        } catch (e: Exception) {
            status.value = "Pendientes conservados: " + e.localizedMessage
        } finally {
            gate.unlock()
        }
    }
}

internal fun requiresCreation(exists: Boolean, remote: Map<String, Any>?, local: Map<String, Any?>): Boolean {
    if (!exists) return true
    check(remote == local) { "Conflicto de contenido" }
    return false
}
