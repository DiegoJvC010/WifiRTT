package com.example.wifirtt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

// Nuevo modelo de datos para representar un AP (Access Point) mostrado en pantalla
data class ApDisplayResult(
    val bssid: String,
    val rttCapable: Boolean,
    val distanceMeters: Float? = null,
    val distanceStdDev: Float? = null
)

class WifiRttViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "WifiRttViewModel"

    // Estado que expone la lista completa de AP a mostrar en pantalla.
    private val _displayResults = MutableStateFlow<List<ApDisplayResult>>(emptyList())
    val displayResults: StateFlow<List<ApDisplayResult>> = _displayResults

    /**
     * Función segura que verifica permisos y llama a la lógica de escaneo y ranging.
     * Se verifica que el Wi‑Fi esté activado y que se tengan los permisos necesarios.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun startScanAndRangingSafe() {
        val context = getApplication<Application>().applicationContext
        Log.d(TAG, "startScanAndRangingSafe() called")

        // Verificar permisos: para Android 13+ usamos NEARBY_WIFI_DEVICES; en versiones anteriores, ACCESS_FINE_LOCATION.
        val nearbyPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        val fineLocPermGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

        val canDoRanging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyPermGranted
        } else {
            fineLocPermGranted
        }

        if (!canDoRanging) {
            Log.d(TAG, "Permissions not granted, cannot perform scan and ranging")
            _displayResults.value = emptyList()
            return
        }

        try {
            Log.d(TAG, "Permissions OK, starting scan and ranging")
            startScanAndRanging()
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in startScanAndRanging: ${se.message}")
            _displayResults.value = emptyList()
        }
    }

    /**
     * startScanAndRanging() asume que los permisos ya están otorgados.
     * Realiza el escaneo completo, crea un listado con todos los AP, y para los que soportan RTT, realiza ranging.
     *
     * @RequiresApi(Build.VERSION_CODES.P) Wi‑Fi RTT está disponible desde Android 9 (API 28)
     * @RequiresPermission(anyOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION])
     */
    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(anyOf = [Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION])
    @SuppressLint("MissingPermission") // Advertencia suprimida ya que se verifican los permisos en startScanAndRangingSafe()
    private fun startScanAndRanging() {
        val context = getApplication<Application>().applicationContext

        // Obtener WifiManager y WifiRttManager
        val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        val rttManager = context.getSystemService(android.content.Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager

        if (!wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi is disabled")
            _displayResults.value = emptyList()
            return
        }
        Log.d(TAG, "WiFi is enabled")

        // Obtener el escaneo de redes
        val scanResults = wifiManager.scanResults
        Log.d(TAG, "Scan results count: ${scanResults.size}")

        // Crear listado inicial con todos los AP detectados
        val initialList = scanResults.map { scan ->
            ApDisplayResult(
                bssid = scan.BSSID,
                rttCapable = scan.is80211mcResponder
            )
        }
        _displayResults.value = initialList
        Log.d(TAG, "Initial display list updated with all AP")

        // Filtrar AP compatibles con RTT para realizar ranging
        val rttCapableList = scanResults.filter { it.is80211mcResponder }
        Log.d(TAG, "RTT capable AP count: ${rttCapableList.size}")

        if (rttCapableList.isEmpty()) {
            Log.d(TAG, "No RTT capable access points found")
            return
        }

        // Construir solicitud de ranging para los AP RTT-capables
        val request = RangingRequest.Builder().apply {
            rttCapableList.forEach { addAccessPoint(it) }
        }.build()
        Log.d(TAG, "Ranging request built, starting ranging...")

        rttManager.startRanging(
            request,
            Executors.newSingleThreadExecutor(),
            object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    Log.e(TAG, "Ranging failed with code: $code")
                    // No modificamos _displayResults, pues ya tenemos la lista inicial
                }

                override fun onRangingResults(resultsList: List<RangingResult>) {
                    Log.d(TAG, "Ranging results received: ${resultsList.size}")
                    // Actualizar el listado para los AP RTT compatibles con los resultados
                    val updatedList = _displayResults.value.map { ap ->
                        if (ap.rttCapable) {
                            // Buscar medición para este AP por BSSID
                            val r = resultsList.find { it.macAddress.toString() == ap.bssid }
                            if (r != null) {
                                ap.copy(
                                    distanceMeters = r.distanceMm / 1000f,
                                    distanceStdDev = r.distanceStdDevMm / 1000f
                                )
                            } else {
                                ap // No se recibió medición, se deja sin datos
                            }
                        } else {
                            ap // AP no compatible, se deja igual
                        }
                    }
                    Log.d(TAG, "Display list updated with ranging results")
                    _displayResults.value = updatedList
                }
            }
        )
    }
}
