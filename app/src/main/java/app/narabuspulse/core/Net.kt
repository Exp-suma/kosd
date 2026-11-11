package app.narabuspulse.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 通信モード（Web の lib/net/network.js の planFor と同じ設定）。
 * profile: 利用者が選ぶ「auto / low / fast」、speedClass: 実測の回線区分。
 */
object Net {
    data class Plan(
        val key: String = "standard",
        val profile: String = "auto",
        val intervalMs: Long = 30_000,
        val gpsMs: Long = 60_000,
        val radarMs: Long = 300_000,
        val snowMs: Long = 600_000,
        val timeoutMs: Long = 35_000,
        val tiles: Boolean = true,
        val logDays: Int = 60,
        val low: Boolean = false,
        val short: String = "標準",
        val label: String = "自動",
        val radarDetail: String = "high",
        val radarFrames: Int = 0,
        val raw: JsonElement? = null,
    )

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _plan = MutableStateFlow(Plan())
    val plan: StateFlow<Plan> = _plan

    private fun prefs() = appContext.getSharedPreferences("net", Context.MODE_PRIVATE)
    val profile: String get() = prefs().getString("profile", "auto") ?: "auto"
    val speedClass: String get() = prefs().getString("speedClass", "standard") ?: "standard"

    fun init(context: Context) {
        appContext = context.applicationContext
        recompute()
    }

    fun setProfile(p: String) { prefs().edit().putString("profile", p).apply(); recompute() }
    fun setSpeedClass(c: String) { prefs().edit().putString("speedClass", c).apply(); recompute() }

    fun recompute() {
        scope.launch {
            val p = Logic.call("net/network", "planFor", profile, speedClass)
            if (p.obj == null) return@launch
            _plan.value = Plan(
                key = p.str("key") ?: "standard",
                profile = p.str("profile") ?: profile,
                intervalMs = p.long("intervalMs") ?: 30_000,
                gpsMs = p.long("gpsMs") ?: 60_000,
                radarMs = p.long("radarMs") ?: 300_000,
                snowMs = p.long("snowMs") ?: 600_000,
                timeoutMs = p.long("timeoutMs") ?: 35_000,
                tiles = p.bool("tiles") ?: true,
                logDays = p.int("logDays") ?: 60,
                low = p.bool("low") ?: false,
                short = p.str("short") ?: "標準",
                label = p.str("label") ?: "自動",
                radarDetail = p.str("radarDetail") ?: "high",
                radarFrames = p.int("radarFrames") ?: 0,
                raw = p,
            )
        }
    }
}
