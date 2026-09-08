package de.codevoid.gpslog

import android.app.Application
import android.content.Context
import de.codevoid.gpslog.data.RunRepository
import de.codevoid.gpslog.data.SettingsStore
import java.io.File

/** Owns the process-wide singletons. No DI framework — manual wiring is enough for this app. */
class App : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var runs: RunRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        runs = RunRepository(File(filesDir, "runs"))
    }

    companion object {
        fun from(context: Context): App = context.applicationContext as App
    }
}
