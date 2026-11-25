package com.zen.bmwlink

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface AuthUi {
    data object Idle : AuthUi
    data class Waiting(val code: String, val url: String) : AuthUi
    data class Failed(val msg: String) : AuthUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val auth = Auth(store, OkHttpClient())
    private val stream = Stream()
    private val tripLog = TripLog(app)

    var authUi by mutableStateOf<AuthUi>(AuthUi.Idle)
    var loggedIn by mutableStateOf(store.refreshToken.isNotEmpty())
    var status by mutableStateOf("Not connected")
    var readings by mutableStateOf(store.loadReadings())
    var trips by mutableStateOf(tripLog.trips())
    var lastUpdate by mutableStateOf<Long?>(null)

    private var job: Job? = null

    init {
        if (loggedIn) startStream()
    }

    fun connect(clientId: String, vin: String) {
        store.clientId = clientId.trim()
        store.vin = vin.trim().uppercase()
        viewModelScope.launch {
            try {
                val flow = auth.start()
                authUi = AuthUi.Waiting(flow.userCode, flow.url)
                auth.poll(flow)
                authUi = AuthUi.Idle
                loggedIn = true
                startStream()
            } catch (e: Exception) {
                authUi = AuthUi.Failed(e.message ?: "Unknown error")
            }
        }
    }

    fun logout() {
        job?.cancel()
        stream.disconnect()
        store.clearSession()
        loggedIn = false
        status = "Not connected"
    }

    private fun startStream() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!auth.ensureFresh()) {
                        withContext(Dispatchers.Main) { logout() }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { status = "Connecting…" }
                    stream.connect(store.gcid, store.idToken, store.vin) { onData(it) }
                    withContext(Dispatchers.Main) { status = "Live" }
                    // ID token lasts ~1h: reconnect with a fresh one before it expires.
                    delay(50 * 60_000L)
                    stream.disconnect()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { status = "Reconnecting… (${e.cause?.message ?: e.message})" }
                    stream.disconnect()
                    delay(30_000L)
                }
            }
        }
    }

    private fun onData(m: Map<String, Reading>) {
        viewModelScope.launch(Dispatchers.Main) {
            readings = readings + m
            store.saveReadings(readings)
            lastUpdate = System.currentTimeMillis()
            Metrics(readings).odometer?.miles()?.let {
                tripLog.add(it)
                trips = tripLog.trips()
            }
        }
    }

    override fun onCleared() {
        stream.disconnect()
        super.onCleared()
    }
}
