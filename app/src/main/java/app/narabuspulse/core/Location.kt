package app.narabuspulse.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LatLon(val lat: Double, val lon: Double, val accuracy: Float? = null)

/** 現在地の取得（Web の navigator.geolocation に相当）。 */
object Locator {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 10_000, maxAgeMs: Long = 600_000): LatLon? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= maxAgeMs }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent.toLatLon()
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull() ?: return null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> cont.resume(loc?.toLatLon()) }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { loc -> cont.resume(loc.toLatLon()) }, context.mainLooper)
                }
            }
        }
    }

    private fun Location.toLatLon() = LatLon(latitude, longitude, accuracy)
}

/** 位置情報の許可を求めてから現在地を取る。onResult(null) は取得できなかった場合。 */
@Composable
fun rememberLocationRequest(onResult: (LatLon?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) scope.launch { onResult(Locator.current(context)) } else onResult(null)
    }
    return remember(context) {
        {
            if (Locator.hasPermission(context)) scope.launch { onResult(Locator.current(context)) }
            else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
