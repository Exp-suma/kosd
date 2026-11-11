package app.narabuspulse

import android.app.Application
import app.narabuspulse.core.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        app.narabuspulse.core.Logic.init(this)
    }
}
