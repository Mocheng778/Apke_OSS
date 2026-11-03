package icu.nullptr.hidemyapplist

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import icu.nullptr.hidemyapplist.receiver.AppChangeReceiver
import icu.nullptr.hidemyapplist.service.ConfigManager
import icu.nullptr.hidemyapplist.service.PrefManager
import icu.nullptr.hidemyapplist.service.ServiceClient
import icu.nullptr.hidemyapplist.util.ConfigUtils.getLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class MyApp : Application() {
    companion object {
        lateinit var hmaApp: MyApp
    }

    val globalScope = CoroutineScope(Dispatchers.Default)
    var updateDialogSkipped: Boolean = false

    fun loadConfiguration() {
        if (ServiceClient.serviceVersion > 0) {
            ConfigManager.init()
        }
    }

    fun loadPreferences() {
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        reloadLocale(getLocale())
    }

    @Suppress("DEPRECATION")
    fun reloadLocale(locale: Locale) {
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onCreate() {
        super.onCreate()
        hmaApp = this

        // 先装崩溃日志钩子，保证任何阶段崩溃都能落盘
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "Thread: ${t.name}\n" + e.stackTraceToString()
                hmaApp.getExternalFilesDir(null)?.let { dir ->
                    java.io.File(dir, "apkesu_crash.log").writeText(text)
                }
                try {
                    val download = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    java.io.File(download, "apkesu_crash.log").writeText(text)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
            ServiceClient.log(Log.ERROR, t.name, e.stackTraceToString())
            defaultHandler?.uncaughtException(t, e)
        }

        loadPreferences()
        AppChangeReceiver.register(this)
    }
}
