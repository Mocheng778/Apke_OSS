package icu.nullptr.hidemyapplist.data

import org.apkesu.app.BuildConfig
import org.apkesu.app.R

object AppConstants {
    const val COMPONENT_NAME_DEFAULT = "${BuildConfig.APPLICATION_ID}.MainActivityLauncher"

    val allAppIcons = listOf(
        R.mipmap.ic_launcher to COMPONENT_NAME_DEFAULT,
    )

    const val UPDATE_CHECK_URL = "https://api.github.com/repos/Mocheng778/Apke_OSS/releases/latest"

    const val APP_CONFIG_MODE_SINGLE = 0
    const val APP_CONFIG_MODE_BULK_CONFIG = 1
    const val APP_CONFIG_MODE_DEFAULT_CONFIG = 2
}
