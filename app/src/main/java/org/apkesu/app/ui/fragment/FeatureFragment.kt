package org.apkesu.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.MyApp.Companion.hmaApp
import icu.nullptr.hidemyapplist.service.ConfigManager
import icu.nullptr.hidemyapplist.ui.util.navigate
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentFeaturesBinding
import org.apkesu.app.ui.util.DeviceInfo

class FeatureFragment : Fragment(R.layout.fragment_features) {
    private val binding by viewBinding(FragmentFeaturesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(toolbar = this, title = getString(R.string.title_features))
        }

        setEdge2EdgeFlags(binding.root)

        binding.manageApps.setOnClickListener {
            navigate(R.id.nav_app_manage)
        }

        binding.manageTemplates.setOnClickListener {
            navigate(R.id.nav_template_manage)
        }

        binding.managePresets.setOnClickListener {
            navigate(R.id.nav_presets)
        }

        binding.navBulkConfigWizard.setOnClickListener {
            navigate(R.id.nav_bulk_config_wizard)
        }

        binding.oneKeyConfig.setOnClickListener {
            applyOneKeyConfig()
        }

        binding.navSusfs.setOnClickListener {
            navigate(R.id.nav_susfs)
        }

        binding.navLogs.setOnClickListener {
            navigate(R.id.nav_logs)
        }

        // SUSFS 路径隐藏仅「解锁模式」可用；越狱模式下不显示该入口，避免误导。
        refreshSusfsEntryVisibility()
    }

    /** 越狱模式下隐藏 SUSFS 入口卡片（该能力仅在解锁模式 / LKM / GKI 下可用）。 */
    private fun refreshSusfsEntryVisibility() {
        val appContext = requireContext().applicationContext
        kotlin.concurrent.thread {
            val env = DeviceInfo.detectRoot(appContext, force = false)
            val jailbreak = env.jailbreak || env.workMode == DeviceInfo.WorkMode.JAILBREAK_LKM
            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.navSusfs.visibility = if (jailbreak) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun applyOneKeyConfig() {
        try {
            val json = requireContext().assets.open("config.json").bufferedReader().use { it.readText() }
            ConfigManager.importConfig(json)
            hmaApp.loadConfiguration()
            Snackbar.make(requireView(), R.string.one_key_config_success, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(requireView(), R.string.one_key_config_failed, Snackbar.LENGTH_SHORT).show()
        }
    }
}
