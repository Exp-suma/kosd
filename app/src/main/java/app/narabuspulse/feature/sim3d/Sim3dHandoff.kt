package app.narabuspulse.feature.sim3d

import kotlinx.serialization.json.JsonObject

/** 他の画面から 3D シミュレーターへ条件を渡す（Web の sessionStorage "sim3d:load"）。 */
object Sim3dHandoff {
    var pending: JsonObject? = null
    fun take(): JsonObject? = pending.also { pending = null }
}
