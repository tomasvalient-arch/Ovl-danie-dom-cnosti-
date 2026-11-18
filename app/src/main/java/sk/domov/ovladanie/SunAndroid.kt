package sk.domov.ovladanie

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Dnešný východ/západ (minúty od polnoci) a poloha, pre ktorú sú vypočítané. */
data class SunInfo(val place: SunPlace, val sunrise: Int?, val sunset: Int?) {
    val riseText: String? get() = sunrise?.let { Darkness.hhmm(it) }
    val setText: String? get() = sunset?.let { Darkness.hhmm(it) }
}

/**
 * Poloha pre výpočet slnka – jedna pre celú appku (Domov, Automatika, Príchod a odchod, služba na pozadí).
 * Poloha telefónu sa berie len ako posledná známa (bez zapínania GPS, žiadna batéria navyše) a uloží sa,
 * aby ju mohla použiť aj služba na pozadí, kde Android polohu nedáva.
 */
object SunRepository {
    /** Poloha telefónu staršia ako 14 dní sa nepoužije (na čas slnka stačí presnosť ~10 km). */
    private const val PHONE_MAX_AGE_MS = 14 * 86_400_000L

    private fun prefs(c: Context) = c.getSharedPreferences("domov_sun", Context.MODE_PRIVATE)

    private fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun phone(c: Context, now: Long): Pair<Double, Double>? {
        if (hasPermission(c)) {
            val fresh = runCatching {
                val lm = c.getSystemService(LocationManager::class.java)
                lm?.getProviders(true)?.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }?.maxByOrNull { it.time }
            }.getOrNull()
            if (fresh != null && now - fresh.time in 0..PHONE_MAX_AGE_MS) {
                prefs(c).edit().putFloat("lat", fresh.latitude.toFloat()).putFloat("lon", fresh.longitude.toFloat())
                    .putLong("at", fresh.time).apply()
                return fresh.latitude to fresh.longitude
            }
        } else {
            return null
        }
        val p = prefs(c)
        val at = p.getLong("at", 0L)
        if (!p.contains("lat") || now - at !in 0..PHONE_MAX_AGE_MS) return null
        return p.getFloat("lat", 0f).toDouble() to p.getFloat("lon", 0f).toDouble()
    }

    fun place(c: Context, now: Long = System.currentTimeMillis()): SunPlace {
        val cfg = PresenceStore.config(c)
        val w = WeatherStore.settings(c).takeIf { it.source != WeatherSource.NONE }
        return SunTimes.choose(phone(c, now), cfg.lat, cfg.lon, w?.name, w?.lat, w?.lon)
    }

    fun today(c: Context, now: Long = System.currentTimeMillis()): SunInfo {
        val place = place(c, now)
        val (rise, set) = SunTimes.day(place, now, TimeZone.getDefault())
        return SunInfo(place, rise, set)
    }
}

/** Dnešné časy slnka pre UI; prepočíta sa pri zmene [keys] (mimo hlavného vlákna). */
@SuppressLint("ProduceStateDoesNotAssignValue") // lint nevidí priradenie cez withContext (falošné hlásenie)
@Composable
internal fun rememberSunInfo(vararg keys: Any?): State<SunInfo?> {
    val context = LocalContext.current
    return produceState<SunInfo?>(null, *keys) {
        value = withContext(Dispatchers.IO) { runCatching { SunRepository.today(context) }.getOrNull() }
    }
}
