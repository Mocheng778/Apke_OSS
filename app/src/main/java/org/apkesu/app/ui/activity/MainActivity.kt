package org.apkesu.app.ui.activity

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.navigation.findNavController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import icu.nullptr.hidemyapplist.MyApp.Companion.hmaApp
import icu.nullptr.hidemyapplist.service.PrefManager
import icu.nullptr.hidemyapplist.service.ServiceClient
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils
import icu.nullptr.hidemyapplist.util.ConfigUtils
import org.apkesu.app.BuildConfig
import org.apkesu.app.R
import org.apkesu.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    var readyToKill: Boolean = true
    var currentConfiguration: Configuration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // I add this manually because the E2E code is not working like I want
        // They should give us a separate method to set it for enableEdgeToEdge
        // Source: https://github.com/androidx/androidx/blob/c0f9aabcf6f32029249ac7647711744b68e2a003/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt#L299
        window.isNavigationBarContrastEnforced = !PrefManager.systemWallpaper

        if (DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder().also {
                    if (!ThemeUtils.isSystemAccent)
                        it.setThemeOverlay(ThemeUtils.getColorThemeStyleRes(this))
                }.build()
            )
        } else {
            theme.applyStyle(ThemeUtils.getColorThemeStyleRes(this), true)
        }

        currentConfiguration = resources.configuration

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment)
            .navController
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != binding.bottomNav.selectedItemId) {
                navController.navigate(item.itemId)
                true
            } else {
                false
            }
        }

        styleBottomNavigation(binding)
    }

    private fun styleBottomNavigation(binding: ActivityMainBinding) {
        // 选中指示胶囊：柔和粉色高亮
        val indicatorColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
            intArrayOf(0x33E91E63.toInt(), Color.TRANSPARENT)
        )
        binding.bottomNav.itemActiveIndicatorColor = indicatorColor

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        // 三个 Tab（主页 / 功能 / 我的）的图标与文字统一为粉红色：
        // 选中用较深的玫粉保证浅色底栏上的可读性，未选中用略浅的粉红。
        val colors = intArrayOf(0xFFD81B60.toInt(), 0xFFEC6A9C.toInt())
        binding.bottomNav.itemTextColor = ColorStateList(states, colors)
        binding.bottomNav.itemIconTintList = ColorStateList(states, colors)
    }

    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        super.onApplyThemeResource(theme, resid, first)

        theme.applyStyle(ThemeUtils.getOverlayThemeStyleRes(this), true)

        applyWallpaperBackgroundColor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (isNightModeEnabled(currentConfiguration) != isNightModeEnabled(newConfig)) {
            readyToKill = false
            recreate()
        }

        currentConfiguration = newConfig
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = this.findNavController(R.id.nav_host_fragment)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(getLocaleAppliedContext(newBase))
    }

    override fun onDestroy() {
        if (readyToKill) {
            ServiceClient.forceStop(BuildConfig.APPLICATION_ID)
        } else {
            readyToKill = true
        }

        super.onDestroy()
    }

    fun applyWallpaperBackgroundColor(value: Int = PrefManager.systemWallpaperAlpha) {
        if (PrefManager.systemWallpaper) {
            val color = (value shl 24) + if (ThemeUtils.isNightMode(this)) {
                0x00000000
            } else {
                0x00FFFFFF
            }

            window.setBackgroundDrawable(color.toDrawable())
        }
    }

    private fun getLocaleAppliedContext(context: Context?): Context? {
        val config = hmaApp.resources.configuration
        config.setLocale(ConfigUtils.getLocale())

        return context?.createConfigurationContext(config)
    }

    private fun isNightModeEnabled(config: Configuration?) = config?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
}
