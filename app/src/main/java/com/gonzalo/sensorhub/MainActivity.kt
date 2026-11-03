package com.gonzalo.sensorhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.gonzalo.sensorhub.ble.BleClient
import com.gonzalo.sensorhub.data.Measurement
import com.gonzalo.sensorhub.ui.theme.SensorHubTheme

class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[HubViewModel::class.java] }
    private val radio = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (vm.ble.ready() && vm.ble.state.value == "Bluetooth desactivado") vm.ble.close()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SensorHubTheme { Hub(vm) } }
    }
    override fun onResume() { super.onResume(); vm.ble.ready() }
    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this, radio,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStop() { unregisterReceiver(radio); vm.release(); super.onStop() }
}

private val metrics = listOf("temperatura","humedad","aceleracion_x","aceleracion_y","aceleracion_z")
@Composable
private fun Hub(vm: HubViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var metric by rememberSaveable { mutableStateOf<String?>(null) }
    val dao = vm.repo.dao
    val pending by remember(dao) { dao.count(false) }.collectAsState(0)
    val sent by remember(dao) { dao.count(true) }.collectAsState(0)
    val ble by vm.ble.state.collectAsState()
    val sync by vm.repo.status.collectAsState()
    val tabs = listOf("Resumen","Historial","Dispositivos")
    Scaffold(bottomBar = {
        NavigationBar { tabs.forEachIndexed { i,t ->
            NavigationBarItem(selected = page==i, onClick = { page=i }, icon = { Text(listOf("◉","≋","⌁")[i]) }, label = { Text(t) })
        } }
    }) { inset ->
        Column(Modifier.padding(inset).padding(horizontal=16.dp)) {
            Text("SensorHub", style=MaterialTheme.typography.headlineLarge)
            Text(tabs[page] + " · EQUIPO_01", style=MaterialTheme.typography.titleMedium)
            val error by vm.error.collectAsState()
            if (error.isNotEmpty()) Text(error, color=MaterialTheme.colorScheme.error)
            when(page) {
                0 -> {
                    val rows by remember(dao) { dao.latest() }.collectAsState(emptyList())
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        item { Text("Datos locales · BLE: " + ble); Text("Pendientes: " + pending + " · " + sync) }
                        items(metrics) { name ->
                            val m = rows.find { it.variable == name }
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(name, style=MaterialTheme.typography.labelLarge)
                                if (m == null) Text("Sin lectura") else Reading(m)
                            } }
                        }
                    }
                }
                1 -> {
                    val rows by remember(source,metric) { dao.history(source,metric) }.collectAsState(emptyList())
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        item { Filter("Origen",listOf("ESP32","ANDROID"),source) { source=it }
                            Filter("Variable",metrics,metric) { metric=it } }
                        items(metrics.filter { name -> rows.any { it.variable==name } }) { name ->
                            Chart(name,rows)
                        }
                        if (rows.isEmpty()) item { Text("Sin mediciones para estos filtros") }
                        items(rows,key={it.medicionId}) { m ->
                            Text(m.variable); Reading(m)
                            Text(if(m.sincronizado) "Sincronizada" else "Pendiente")
                            HorizontalDivider()
                        }
                    }
                }
                else -> {
                    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        if (vm.ble.ready()) vm.ble.search()
                    }
                    val devices by vm.ble.devices.collectAsState()
                    val motion by vm.motion.collectAsState()
                    val identity by vm.repo.identity.collectAsState()
                    val last by vm.repo.last.collectAsState()
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        item { Text("Bluetooth LE · " + ble)
                            Button(onClick={ request.launch(BleClient.permissions) }) { Text("Buscar ESP32") } }
                        items(devices) { device ->
                            Button(onClick={vm.ble.connect(device)}) { Text("Conectar SensorHub · " + device.address.takeLast(5)) }
                        }
                        item {
                            OutlinedButton(onClick={vm.ble.close()}) { Text("Desconectar") }
                            OutlinedButton(onClick={vm.ble.connect()}) { Text("Reconectar") }
                            HorizontalDivider()
                            Text("Acelerómetro · " + motion)
                            Button(onClick=vm::start,enabled=motion!="Activo" && motion!="No disponible") { Text("Iniciar adquisición") }
                            OutlinedButton(onClick=vm::stop,enabled=motion=="Activo") { Text("Detener") }
                            HorizontalDivider()
                            Text("Firebase · " + identity)
                            Text("Pendientes: " + pending + " · Sincronizadas: " + sent)
                            Text("Última sincronización: " + (last ?: "Ninguna"))
                            Text(sync)
                            Button(onClick=vm::sync,enabled=sync!="Sincronizando") { Text("Sincronizar pendientes") }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun Reading(m: Measurement) {
    Text("%.2f %s".format(m.valor,m.unidad),style=MaterialTheme.typography.titleLarge)
    Text(m.origen + " · " + m.dispositivoId + " · " + m.fechaHora,style=MaterialTheme.typography.bodySmall)
}
@Composable
private fun Filter(label:String, options:List<String>, selected:String?, choose:(String?)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick={open=true}) { Text(label + " · " + (selected ?: "Todas")) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            (listOf(null)+options).forEach { option ->
                DropdownMenuItem(text={Text(option ?: "Todas")},onClick={choose(option);open=false})
            }
        }
    }
}
@Composable
private fun Chart(name:String, rows:List<Measurement>) {
    val points = remember(rows,name) { rows.filter { it.variable==name }.take(60).asReversed() }
    val times = remember(points) { points.map { java.time.Instant.parse(it.fechaHora).toEpochMilli() } }
    val low = points.minOf { it.valor }; val high = points.maxOf { it.valor }
    Text(name + " · %.2f–%.2f %s".format(low,high,points.first().unidad))
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(4.dp)) {
        var previous: Offset? = null
        points.forEachIndexed { i,m ->
            val x = size.width * (times[i]-times.first()).toFloat() / (times.last()-times.first()).coerceAtLeast(1L)
            val y = if (high==low) size.height/2 else size.height*(1-((m.valor-low)/(high-low)).toFloat())
            val point = Offset(x,y)
            previous?.let { drawLine(color,it,point,3f) }
            drawCircle(color,2f,point)
            previous = point
        }
    }
    Text(points.first().fechaHora+" → "+points.last().fechaHora,style=MaterialTheme.typography.labelSmall)
}
