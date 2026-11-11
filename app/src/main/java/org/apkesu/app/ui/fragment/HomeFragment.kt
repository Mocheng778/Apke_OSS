package org.apkesu.app.ui.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.SystemClock.elapsedRealtime
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.MyApp.Companion.hmaApp
import icu.nullptr.hidemyapplist.common.Constants.ENABLE_INTERNET_OFF
import icu.nullptr.hidemyapplist.common.Constants.ENABLE_INTERNET_ON
import icu.nullptr.hidemyapplist.common.Constants.ENABLE_INTERNET_UNKNOWN
import icu.nullptr.hidemyapplist.common.Constants.MANAGER_WORK_MODE_CRASHED
import icu.nullptr.hidemyapplist.common.Constants.MANAGER_WORK_MODE_LOADING
import icu.nullptr.hidemyapplist.common.Constants.MANAGER_WORK_MODE_NO_HOOKS
import icu.nullptr.hidemyapplist.common.Constants.MANAGER_WORK_MODE_UNKNOWN
import icu.nullptr.hidemyapplist.data.fetchLatestUpdate
import icu.nullptr.hidemyapplist.service.PrefManager
import icu.nullptr.hidemyapplist.service.ServiceClient
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.attrDrawable
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.getColor
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.homeItemBackgroundColor
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.themeColor
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.asDrawable
import icu.nullptr.hidemyapplist.ui.util.dpToPx
import icu.nullptr.hidemyapplist.ui.util.isTestBuild
import icu.nullptr.hidemyapplist.ui.util.navigate
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import icu.nullptr.hidemyapplist.ui.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apkesu.app.BuildConfig
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentHomeBinding
import org.apkesu.app.ui.util.DeviceInfo
import kotlin.concurrent.thread

/**
 * A simple [Fragment] subclass.
 * Use the [HomeFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private val binding by viewBinding(FragmentHomeBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.app_name),
                menuRes = R.menu.menu_home,
                onMenuOptionSelected = ::onMenuOptionSelected,
            )
            // isTitleCentered = true

            setOnLongClickListener {
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.app_name)
                    .create()

                dialog.setView(Chronometer(context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                    base = elapsedRealtime() + 3000
                    textSize = 24.dpToPx
                    gravity = Gravity.CENTER
                    typeface = Typeface.SERIF
                    onChronometerTickListener = {
                        if (elapsedRealtime() >= base) {
                            stop()
                            dialog.dismiss()

                            // is it really final countdown?
                            isTheFinalCountDown
                        }
                    }
                    isCountDown = true
                    start()
                })

                dialog.show()

                true
            }
        }

        setEdge2EdgeFlags(binding.root)
    }

    override fun onStart() {
        super.onStart()

        waitForService()

        with(binding.howToUse.root.parent as ViewGroup) {
            val childCount = childCount

            val softCorner = 24.dpToPx
            val squareCorner = 8.dpToPx
            val pad = 16.dpToPx.toInt()

            for (i in 0..< childCount) {
                getChildAt(i).apply {
                    (this as ViewGroup).apply {
                        val textColor = themeColor(
                            com.google.android.material.R.attr.colorOnSurface,
                        )

                        findViewById<TextView>(android.R.id.text1).setTextColor(textColor)
                        findViewById<ImageView>(android.R.id.icon).setColorFilter(textColor)
                    }

                    (layoutParams as LinearLayout.LayoutParams).apply {
                        setMargins(pad, 0, pad, 0)
                    }

                    val backgroundDrawable = GradientDrawable()
                    backgroundDrawable.setColor(homeItemBackgroundColor())

                    when (i) {
                        0 -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                softCorner,
                                softCorner,
                                softCorner,
                                softCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner
                            )
                        }
                        childCount - 1 -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                softCorner,
                                softCorner,
                                softCorner,
                                softCorner
                            )
                        }
                        else -> {
                            backgroundDrawable.cornerRadii = floatArrayOf(
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner,
                                squareCorner
                            )
                        }
                    }

                    val ripple = attrDrawable(android.R.attr.selectableItemBackground)
                    val layerDrawable = LayerDrawable(arrayOf(
                        backgroundDrawable,
                        ripple,
                    ))

                    background = layerDrawable
                    clipToOutline = true
                }

            }
        }

        with(binding.howToUse) {
            text1.text = getString(R.string.about_how_to_use_title)
            icon.setImageResource(R.drawable.baseline_help_outline_24)
            root.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.about_how_to_use_title)
                    .setMessage(
                        getString(R.string.about_how_to_use_description_1) +
                                "\n\n" +
                                getString(R.string.about_how_to_use_description_2) +
                                "\n\n" +
                                getString(R.string.about_how_to_use_description_3))
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }

        loadDeviceInfo()

        with(binding.backupConfig) {
            setOnClickListener {
                navigate(
                    R.id.nav_backup_restore,
                    BackupRestoreFragmentArgs(true).toBundle()
                )
            }
        }

        with(binding.restoreConfig) {
            setOnClickListener {
                navigate(
                    R.id.nav_backup_restore,
                    BackupRestoreFragmentArgs(false).toBundle()
                )
            }
        }

        lifecycleScope.launch {
            loadDialogs()
        }
    }

    private fun loadDeviceInfo() {
        // 同步可得的即时信息
        with(binding.infoDevice) {
            infoRowIcon.setImageResource(R.drawable.outline_android_24)
            infoRowLabel.setText(R.string.home_device_label)
            infoRowValue.text = DeviceInfo.deviceModel()
        }
        with(binding.infoAppCount) {
            infoRowIcon.setImageResource(R.drawable.baseline_apps_24)
            infoRowLabel.setText(R.string.home_app_count_label)
            infoRowValue.text = DeviceInfo.installedAppCount(requireContext()).toString()
        }

        // 需要 Root 的探测放到后台线程（context 提前取好，避免已 detach 时崩溃）
        val appContext = requireContext().applicationContext
        thread {
            // 真实识别 Root 框架品牌（KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk）
            val rootEnv = DeviceInfo.detectRoot(appContext)
            val moduleCount = DeviceInfo.moduleCount()
            val env = "${DeviceInfo.selinuxMode()} · Zygisk ${DeviceInfo.zygiskStatus()}"
            val root = rootEnv.framework

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    with(binding.infoModuleCount) {
                        infoRowIcon.setImageResource(R.drawable.outline_storage_24)
                        infoRowLabel.setText(R.string.home_module_count_label)
                        infoRowValue.text = moduleCount?.toString()
                            ?: getString(R.string.home_module_count_unknown)
                    }
                    with(binding.infoEnv) {
                        infoRowIcon.setImageResource(R.drawable.outline_bug_report_24)
                        infoRowLabel.setText(R.string.home_env_label)
                        infoRowValue.text = env
                    }
                    with(binding.infoRoot) {
                        infoRowIcon.setImageResource(R.drawable.plug_connect_24px)
                        infoRowLabel.setText(R.string.home_root_label)
                        infoRowValue.text = root
                    }
                }
            }
        }
    }

    fun waitForService() {
        var serviceVersion = ServiceClient.serviceVersion
        var workMode = ServiceClient.managerWorkMode
        loadEnabledIndicator(serviceVersion, workMode)
        if (serviceVersion > 0) {
            return
        }

        thread {
            var count = 0

            while (ServiceClient.serviceVersion.also { serviceVersion = it } <= 0 && count++ < 100) {
                Thread.sleep(100)
            }

            if (serviceVersion > 0) {
                workMode = ServiceClient.managerWorkMode

                lifecycleScope.launch {
                    loadEnabledIndicator(serviceVersion, workMode)
                }
            }
        }
    }

    fun loadEnabledIndicator(serviceVersion: Int, workMode: Int) {
        hmaApp.loadConfiguration()

        val isWorking = serviceVersion > 0 && workMode != MANAGER_WORK_MODE_UNKNOWN
        val isCrashed = workMode == MANAGER_WORK_MODE_CRASHED
        val isNoHooks = isCrashed || workMode == MANAGER_WORK_MODE_NO_HOOKS
        val isVersionMismatch = ServiceClient.serviceVersionName != BuildConfig.VERSION_NAME

        val backgroundRes = when {
            !isWorking -> R.drawable.status_card_inactive
            isNoHooks -> R.drawable.status_card_error
            else -> R.drawable.status_card_normal
        }

        with(binding.statusCard) {
            statusCardContent.background = backgroundRes.asDrawable(requireContext())

            if (isWorking) {
                if (isNoHooks) {
                    val colorError = ColorStateList.valueOf(
                        getColor(R.color.md_theme_material_amber_dark_error))

                    moduleStatus.setText(R.string.sick_mode_title)
                    moduleStatus.setTextColor(colorError)
                    setStatusIcon(R.drawable.sick_24px)
                    serviceStatus.setText(R.string.sick_mode_description)
                    serviceStatus.setTextColor(colorError)
                    filterCount.isVisible = false

                    migrateBtn.isVisible = !isCrashed
                    @Suppress("DEPRECATION")
                    migrateBtn.setOnClickListener {
                        navigate(R.id.nav_fix_issue)
                    }
                } else {
                    val image = when {
                        isVersionMismatch -> R.drawable.sentiment_worried_24px
                        workMode == MANAGER_WORK_MODE_LOADING -> R.drawable.sentiment_stressed_24px
                        else -> R.drawable.sentiment_calm_24px
                    }
                    setStatusIcon(image)

                    val versionNameSimple = if (isVersionMismatch) {
                        "${ServiceClient.serviceVersionName} - ${BuildConfig.VERSION_NAME}"
                    } else {
                        ServiceClient.serviceVersionName
                    }
                    moduleStatus.text =
                        getString(R.string.home_xposed_activated, versionNameSimple)
                    root.setOnLongClickListener {
                        ServiceClient.reloadConfigFromFile()
                        showToast(android.R.string.ok)

                        true
                    }

                    serviceStatus.text =
                        getString(R.string.home_xposed_service_on, serviceVersion)
                    filterCount.visibility = View.VISIBLE
                    filterCount.text =
                        getString(R.string.home_xposed_filter_count, ServiceClient.filterCount)
                }
            } else {
                setStatusIcon(R.drawable.sentiment_very_dissatisfied_24px)
                moduleStatus.setText(R.string.home_xposed_not_activated)
                serviceStatus.setText(R.string.home_xposed_service_off)
                filterCount.isVisible = false
            }

            TextViewCompat.setCompoundDrawableTintList(
                moduleStatus, moduleStatus.textColors)
        }

        val isHidden = isWorking && !isNoHooks

        binding.homeHiddenTitle.setText(
            if (isHidden) R.string.home_hidden_title else R.string.home_hidden_title_off
        )
        binding.homeHiddenSubtitle.text = when {
            isHidden -> getString(R.string.home_xposed_filter_count, ServiceClient.filterCount)
            else -> getString(R.string.home_hidden_subtitle_off)
        }
    }

    private fun setStatusIcon(@DrawableRes res: Int) {
        binding.statusCard.moduleStatus
            .setCompoundDrawablesRelativeWithIntrinsicBounds(res, 0, 0, 0)
    }

    private fun loadDialogs() {
        if (PrefManager.enableInternet == ENABLE_INTERNET_UNKNOWN) {
            loadEnableInternetDialog()
            return
        }

        loadUpdateDialog()
    }

    private fun loadEnableInternetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setCancelable(false)
            .setTitle(R.string.settings_enable_internet)
            .setMessage(R.string.settings_enable_internet_summary)
            .setPositiveButton(R.string.yes) { _, _ ->
                PrefManager.enableInternet = ENABLE_INTERNET_ON
                loadUpdateDialog()
            }
            .setNegativeButton(R.string.no) { _, _ ->
                PrefManager.enableInternet = ENABLE_INTERNET_OFF
            }
            .show()
    }

    private fun loadUpdateDialog() {
        if (PrefManager.enableInternet != ENABLE_INTERNET_ON ||
            hmaApp.updateDialogSkipped || PrefManager.disableUpdate || isTestBuild) {
            return
        }

        fetchLatestUpdate { updateInfo ->
            if (updateInfo.versionName != BuildConfig.VERSION_NAME) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setCancelable(false)
                        .setTitle(getString(R.string.home_new_update, updateInfo.versionName))
                        .setMessage(updateInfo.content)
                        .setPositiveButton("GitHub") { _, _ ->
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    updateInfo.downloadUrl.toUri()
                                )
                            )
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener {
                            hmaApp.updateDialogSkipped = true
                        }
                        .show()
                }
            }
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_info -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/frknkrc44/ApkeSU/wiki/About-HMA%E2%80%90OSS".toUri()
                })
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = HomeFragment()
    }
}
