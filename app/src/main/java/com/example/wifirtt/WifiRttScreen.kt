package com.example.wifirtt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private const val TAG_SCREEN = "WifiRttScreen"

/**
 * Comprueba si la ubicación está activada en el dispositivo.
 */
fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

/**
 * Composable encargado de solicitar permisos necesarios para escanear redes Wi‑Fi y Wi‑Fi RTT,
 * y verificar que la ubicación esté activada.
 */
@Composable
fun RequestWifiPermissions(onAllGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    // Lista de permisos a solicitar
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES, // Permiso para buscar dispositivos Wi‑Fi cercanos en Android 13+

                Manifest.permission.ACCESS_FINE_LOCATION // Permiso para obtener ubicación precisa necesaria para escaneo Wi‑Fi
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,

                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    // Estado que indica si todos los permisos fueron concedidos por el usuario
    var allGranted by remember { mutableStateOf(false) }
    // Estado que indica si el servicio de ubicación está activado en el dispositivo
    var locationEnabled by remember { mutableStateOf(false) }

    // ActivityResult para solicitar múltiples permisos
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Verificamos si todos los permisos fueron otorgados
        allGranted = permissions.values.all { it }
        Log.d(TAG_SCREEN, "Permissions granted: $allGranted")
    }

    // Lanzamos la solicitud de permisos apenas se componga la UI
    LaunchedEffect(Unit) {
        launcher.launch(requiredPermissions.toTypedArray())
    }

    // Verificar si la ubicación del dispositivo está encendida
    locationEnabled = isLocationEnabled(context)
    if (!locationEnabled) {
        Log.d(TAG_SCREEN, "La localizacion esta desactivada")
    }

    if (allGranted && locationEnabled) {
        onAllGranted()
    } else {
        // Mostramos pantalla que advierte que faltan permisos o la ubicación
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!allGranted) {
                Text("Se requieren permisos para escaneo Wi‑Fi y Wi‑Fi RTT.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(requiredPermissions.toTypedArray()) }) {
                    Text("Solicitar permisos")
                }
            }
            if (!locationEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Se requiere que la ubicación esté activa.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text("Activar ubicación")
                }
            }
        }
    }
}

/**
 * Composable que encapsula la pantalla principal, incluyendo la solicitud de permisos.
 */
@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun MainScreenWithPermissions(viewModel: WifiRttViewModel = viewModel()) {
    Log.d(TAG_SCREEN, "antalla principal con permisos")
    RequestWifiPermissions {
        WifiRttScreen(viewModel)
    }
}

/**
 * Pantalla principal donde se muestra la demo de escaneo Wi‑Fi y Wi‑Fi RTT.
 * Muestra toda la información del AP: BSSID, SSID, nivel de señal, frecuencia, capacidades, etc.
 * Utiliza un LazyColumn para permitir scroll en caso de haber muchos AP.
 */
@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun WifiRttScreen(viewModel: WifiRttViewModel) {
    Log.d(TAG_SCREEN, "WifiRttScreen")
    val displayResults by viewModel.displayResults.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Búsqueda Wi‑Fi RTT",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            Log.d(TAG_SCREEN, "Iniciar escaneo button clicked")
            viewModel.startScanAndRangingSafe()
        }) {
            Text("Iniciar escaneo")
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (displayResults.isEmpty()) {
            Text("No se encontraron resultados o no se ha iniciado el escaneo.")
        } else {
            Text("Total de AP detectados: ${displayResults.size}")
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(displayResults) { ap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("BSSID: ${ap.bssid}")
                            val ssidDisplay = if (ap.ssid.isBlank()) "<Oculto>" else ap.ssid
                            Text("SSID: $ssidDisplay")
                            Text("Nivel de señal: ${ap.level} dBm")
                            Text("Frecuencia: ${ap.frequency} MHz")
                            Text("Capacidades: ${ap.capabilities}")
                            Text("Compatible con RTT: ${ap.rttCapable}")
                            if (ap.rttCapable) {
                                if (ap.distanceMeters != null) {
                                    Text("Distancia: ${ap.distanceMeters} m")
                                    Text("Error: ${ap.distanceStdDev} m")
                                } else {
                                    Text("Ranging pendiente o sin resultados.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
