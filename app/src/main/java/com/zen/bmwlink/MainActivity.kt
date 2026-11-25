package com.zen.bmwlink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            AppTheme { Root(vm, this) }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun rememberTabletop(activity: Activity): Boolean {
    val flow = remember { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState<WindowLayoutInfo, WindowLayoutInfo?>(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    return fold != null &&
        fold.state == FoldingFeature.State.HALF_OPENED &&
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun Root(vm: AppViewModel, act: Activity) {
    if (!vm.loggedIn) {
        SetupScreen(vm)
        return
    }
    val sizeClass = calculateWindowSizeClass(act)
    val tabletop = rememberTabletop(act)
    val metrics = Metrics(vm.readings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BMW 430i") },
                actions = { TextButton(onClick = { vm.logout() }) { Text("Sign out") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusLine(vm)
            when {
                // Flex Mode: fold is horizontal and half open -> gauges on top, details below.
                tabletop -> Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) { Dashboard(metrics, vm) }
                    HorizontalDivider()
                    Box(Modifier.weight(1f)) { Details(vm) }
                }
                // Unfolded Fold 7 / tablets: two panes side by side.
                sizeClass.widthSizeClass != WindowWidthSizeClass.Compact -> Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) { Dashboard(metrics, vm) }
                    VerticalDivider()
                    Box(Modifier.weight(1f)) { Details(vm) }
                }
                // Cover screen / phones: single pane with tabs.
                else -> CompactTabs(metrics, vm)
            }
        }
    }
}

@Composable
fun StatusLine(vm: AppViewModel) {
    val t = vm.lastUpdate?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    Text(
        text = "Stream: ${vm.status}" + (t?.let { "  •  last data $it" } ?: ""),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun CompactTabs(metrics: Metrics, vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dashboard") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Trips & data") })
        }
        if (tab == 0) Dashboard(metrics, vm) else Details(vm)
    }
}

@Composable
fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun BigValue(r: Reading?, fallback: String = "—") {
    Text(r?.pretty() ?: fallback, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Dashboard(m: Metrics, vm: AppViewModel) {
    val ctx = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (vm.readings.isEmpty()) {
            item {
                InfoCard("Waiting for data") {
                    Text(
                        "The car only sends data when something changes. Lock/unlock it, open a door " +
                            "or take a short drive to trigger the first update. Make sure the data " +
                            "groups you want are ticked under CarData → Stream → Change data selection."
                    )
                }
            }
        }
        item {
            InfoCard("Fuel & range") {
                BigValue(m.fuel)
                val pct = m.fuel?.takeIf { it.unit == "%" }?.value?.toFloatOrNull()
                if (pct != null) LinearProgressIndicator(progress = { (pct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("Range: " + (m.range?.pretty() ?: "—"))
            }
        }
        item {
            InfoCard("Odometer") {
                BigValue(m.odometer)
                m.odometer?.miles()?.let { Text("≈ ${"%,.0f".format(it)} mi") }
            }
        }
        item {
            InfoCard("Security") {
                if (m.locks.isEmpty()) Text("Lock state: —")
                m.locks.forEach { Text("${shortKey(it.key)}: ${it.pretty()}") }
                if (m.openItems.isEmpty()) Text("Nothing reported open") else m.openItems.forEach { Text("Open: ${shortKey(it.key)}") }
            }
        }
        item {
            InfoCard("Roof") {
                if (m.roof.isEmpty()) Text("—")
                m.roof.forEach { Text("${shortKey(it.key)}: ${it.pretty()}") }
            }
        }
        item {
            InfoCard("Location") {
                val lat = m.latitude?.value
                val lon = m.longitude?.value
                if (lat == null || lon == null) {
                    Text("—")
                } else {
                    Text("$lat, $lon")
                    TextButton(onClick = {
                        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(BMW 430i)")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) { Text("Open in Maps") }
                }
            }
        }
        item {
            InfoCard("12V battery") { BigValue(m.battery12v) }
        }
    }
}

@Composable
fun Details(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Trips") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All data") })
        }
        if (tab == 0) {
            if (vm.trips.isEmpty()) {
                Text(
                    "No trips yet. Trips are inferred from odometer updates while the app is open, " +
                        "so they are approximate.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.trips) { t ->
                    val f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${"%.1f".format(t.miles)} mi", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${f.format(Date(t.start))} → ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.end))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.readings.values.sortedBy { it.key }) { r ->
                    Column {
                        Text(r.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(r.pretty())
                    }
                }
            }
        }
    }
}

private fun shortKey(k: String) = k.substringAfterLast('.')

@Composable
fun SetupScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var clientId by remember { mutableStateOf(vm.store.clientId) }
    var vin by remember { mutableStateOf(vm.store.vin) }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("BMW Link", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "One-time setup: on the MyBMW / ConnectedDrive website go to Personal Data → My Vehicles → " +
                    "CarData, tap Create CarData client, enable both API and Stream, then paste the Client ID " +
                    "here. Under CarData Stream tap \"Change data selection\" and tick the groups you want."
            )
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(vin, { vin = it }, label = { Text("VIN (17 characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(
                onClick = { vm.connect(clientId, vin) },
                enabled = clientId.isNotBlank() && vin.trim().length == 17 && vm.authUi !is AuthUi.Waiting
            ) { Text("Connect") }

            when (val s = vm.authUi) {
                is AuthUi.Waiting -> {
                    Text("Your code", style = MaterialTheme.typography.labelLarge)
                    Text(s.code, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) } }) {
                        Text("Open BMW login")
                    }
                    Text("Approve the request in the browser, then come back. This screen continues on its own.")
                }
                is AuthUi.Failed -> Text(s.msg, color = MaterialTheme.colorScheme.error)
                AuthUi.Idle -> Unit
            }
        }
    }
}
