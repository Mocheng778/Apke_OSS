package org.apkesu.app.ui.fragment

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentSusfsBinding
import org.apkesu.app.databinding.ViewSusfsInfoRowBinding
import org.apkesu.app.databinding.ViewSusfsPathRowBinding
import org.apkesu.app.ui.util.DeviceInfo
import kotlin.concurrent.thread

class SusfsFragment : Fragment(R.layout.fragment_susfs) {
    private val binding by viewBinding(FragmentSusfsBinding::bind)

    companion object {
        private const val PREFS_NAME = "susfs_path_hide"

        private const val KEY_APP_HIDE = "sw_app_hide"
        private const val KEY_KERNEL_SPOOF = "sw_kernel_spoof"
        private const val KEY_SUSFS_LOG = "sw_susfs_log"
        private const val KEY_HIDE_MNT_NONROOT = "sw_hide_mnt_nonroot"
        private const val KEY_PRESET_PATHS = "preset_paths"
        private const val KEY_CUSTOM_PATHS = "custom_paths"

        val PRESET_PATHS = listOf(
            "/data/adb/ksu",
            "/data/adb/magisk",
            "/data/adb/apd",
            "/data/adb/modules",
            "/data/adb/susfs4ksu",
            "/system/bin/su",
            "/system/xbin/su",
            "/system_ext/bin/su",
            "/vendor/bin/su",
            "/sbin/.magisk",
            "/debug_ramdisk",
            "/debug_ramdisk/.magisk",
        )

        private const val SUSFS_DIR = "/data/adb/susfs4ksu"
    }

    private val prefs get() = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(toolbar = this, title = getString(R.string.title_susfs))
        }

        setEdge2EdgeFlags(binding.root)

        restoreSwitches()
        renderPresetPaths()
        renderCustomPaths()

        binding.addPathBtn.setOnClickListener { addCustomPath() }
        binding.pathInput.setOnEditorActionListener { _, _, _ ->
            addCustomPath()
            true
        }

        binding.loadEnableBtn.setOnClickListener {
            loadDetection(refresh = true)
            applyAll()
        }

        binding.applyBtn.setOnClickListener {
            applyAll()
        }

        loadDetection(refresh = false)
    }

    // ---------------------------------------------------------------- 状态与环境检测

    private fun loadDetection(refresh: Boolean) {
        if (refresh) {
            binding.susfsStatus.setText(R.string.susfs_support_checking)
        }

        val appContext = requireContext().applicationContext
        thread {
            // 真实识别 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk
            // 及 LKM / GKI / 自定义 / 越狱模式，越狱模式（临时 root）同样可采集。
            val env = DeviceInfo.detectRoot(appContext, force = refresh)

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    renderStatus(env)
                }
            }
        }
    }

    private fun renderStatus(env: DeviceInfo.RootEnv) {
        binding.susfsStatus.setText(
            if (env.working) R.string.susfs_status_working else R.string.susfs_status_inactive
        )
        val version = env.frameworkVersion.takeIf { it != "—" && it.isNotEmpty() }
            ?: env.kernel.takeIf { it != "未知" && it.isNotEmpty() }
            ?: "—"
        binding.susfsVersion.text = getString(
            R.string.susfs_version_label,
            if (env.working) "$version · ${env.framework}" else env.framework
        )

        renderModeChips(env.modes)
        renderEnvInfo(env)
    }

    private fun renderModeChips(modes: List<DeviceInfo.ModeTag>) {
        val container = binding.modeChips
        container.removeAllViews()

        val shown = modes.ifEmpty { listOf(DeviceInfo.ModeTag("未知模式")) }
        for (tag in shown) {
            val chip = TextView(requireContext()).apply {
                text = tag.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (tag.accent) Color.WHITE else Color.parseColor("#5F4386"))
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (tag.accent) R.drawable.susfs_chip_jailbreak else R.drawable.susfs_chip_normal
                )
                setPadding(dp(10), dp(4), dp(10), dp(4))
                gravity = Gravity.CENTER
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            container.addView(chip, lp)
        }
    }

    private fun renderEnvInfo(env: DeviceInfo.RootEnv) {
        binding.statSuperuser.text = env.superuser
        binding.statModules.text = env.modules

        val container = binding.infoContainer
        container.removeAllViews()

        val rows = listOf(
            R.string.susfs_info_framework to env.framework,
            R.string.susfs_info_manager_ver to env.frameworkVersion,
            R.string.susfs_info_kernel to env.kernel,
            R.string.susfs_info_kernel_full to env.kernelFull,
            R.string.susfs_info_hook to env.hookType,
            R.string.susfs_info_zygisk to env.zygisk,
            R.string.susfs_info_selinux to env.selinux,
            R.string.susfs_info_fingerprint to env.fingerprint,
            R.string.susfs_info_susfs to getString(
                if (env.susfs) R.string.susfs_info_susfs_yes else R.string.susfs_info_susfs_no
            ) + if (env.susfsVersion.isNotEmpty()) " ${env.susfsVersion}" else "",
        )

        for ((labelRes, value) in rows) {
            val row = ViewSusfsInfoRowBinding.inflate(layoutInflater, container, false)
            row.infoRowLabel.setText(labelRes)
            row.infoRowValue.text = value
            container.addView(row.root)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 开关

    private fun restoreSwitches() {
        binding.switchAppHide.isChecked = prefs.getBoolean(KEY_APP_HIDE, true)
        binding.switchKernelSpoof.isChecked = prefs.getBoolean(KEY_KERNEL_SPOOF, true)
        binding.switchSusfsLog.isChecked = prefs.getBoolean(KEY_SUSFS_LOG, false)
        binding.switchHideMntNonroot.isChecked = prefs.getBoolean(KEY_HIDE_MNT_NONROOT, true)

        binding.switchAppHide.setOnCheckedChangeListener { _, v -> saveBool(KEY_APP_HIDE, v) }
        binding.switchKernelSpoof.setOnCheckedChangeListener { _, v -> saveBool(KEY_KERNEL_SPOOF, v) }
        binding.switchSusfsLog.setOnCheckedChangeListener { _, v -> saveBool(KEY_SUSFS_LOG, v) }
        binding.switchHideMntNonroot.setOnCheckedChangeListener { _, v -> saveBool(KEY_HIDE_MNT_NONROOT, v) }
    }

    private fun saveBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun selectedPresets(): Set<String> =
        prefs.getStringSet(KEY_PRESET_PATHS, PRESET_PATHS.toSet()) ?: PRESET_PATHS.toSet()

    private fun customPaths(): MutableSet<String> =
        prefs.getStringSet(KEY_CUSTOM_PATHS, emptySet())?.toMutableSet() ?: mutableSetOf()

    // ---------------------------------------------------------------- 路径列表

    private fun renderPresetPaths() {
        val selected = selectedPresets()
        binding.presetContainer.removeAllViews()

        for (path in PRESET_PATHS) {
            val check = CheckBox(requireContext()).apply {
                text = path
                textSize = 12f
                isChecked = path in selected
                setOnCheckedChangeListener { _, checked ->
                    val current = selectedPresets().toMutableSet()
                    if (checked) current.add(path) else current.remove(path)
                    prefs.edit().putStringSet(KEY_PRESET_PATHS, current).apply()
                }
            }
            binding.presetContainer.addView(check)
        }
    }

    private fun renderCustomPaths() {
        binding.customContainer.removeAllViews()

        for (path in customPaths()) {
            val row = ViewSusfsPathRowBinding.inflate(layoutInflater, binding.customContainer, false)
            row.pathText.text = path
            row.pathRemove.setOnClickListener {
                val current = customPaths()
                current.remove(path)
                prefs.edit().putStringSet(KEY_CUSTOM_PATHS, current).apply()
                renderCustomPaths()
            }
            binding.customContainer.addView(row.root)
        }
    }

    private fun addCustomPath() {
        val raw = binding.pathInput.text?.toString()?.trim().orEmpty()
        when {
            raw.isEmpty() -> {
                snack(R.string.susfs_path_empty)
                return
            }
            !raw.startsWith("/") || raw.any { it.isWhitespace() } || raw.contains("'") -> {
                snack(R.string.susfs_path_invalid)
                return
            }
        }

        val current = customPaths()
        if (raw in current || raw in PRESET_PATHS) {
            snack(R.string.susfs_path_exists)
            return
        }

        current.add(raw)
        prefs.edit().putStringSet(KEY_CUSTOM_PATHS, current).apply()
        binding.pathInput.text?.clear()
        renderCustomPaths()
        snack(getString(R.string.susfs_path_added, raw))
    }

    // ---------------------------------------------------------------- 应用配置

    private data class SwitchState(
        val appHide: Boolean,
        val kernelSpoof: Boolean,
        val susfsLog: Boolean,
        val hideMnt: Boolean,
    )

    private fun applyAll() {
        val paths = (selectedPresets() + customPaths()).distinct().sorted()

        if (paths.isEmpty()) {
            snack(R.string.susfs_select_first)
            return
        }

        // 主线程一次性读取开关状态，后续 shell 操作全部放后台
        val state = SwitchState(
            appHide = binding.switchAppHide.isChecked,
            kernelSpoof = binding.switchKernelSpoof.isChecked,
            susfsLog = binding.switchSusfsLog.isChecked,
            hideMnt = binding.switchHideMntNonroot.isChecked,
        )

        binding.loadEnableBtn.isEnabled = false
        binding.applyBtn.isEnabled = false

        thread {
            // 只要有 root 即允许写入配置：
            // 兼容 KernelSU / KernelSU Next / SukiSU Ultra / SterSU / APatch / Magisk，
            // 也兼容 LKM / GKI / 自定义内核 / 越狱模式（permissive + 运行时加载 LKM 的临时 root）。
            // 不再以「是否检测到 SUSFS 补丁内核」作为写入前置条件。
            val hasRoot = DeviceInfo.hasRoot()
            val ok = if (hasRoot) DeviceInfo.execRoot(buildScript(state, paths)) else false

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadEnableBtn.isEnabled = true
                    binding.applyBtn.isEnabled = true
                    when {
                        !hasRoot -> snack(R.string.susfs_apply_noroot)
                        ok -> snack(getString(R.string.susfs_loaded, paths.size))
                        else -> snack(R.string.susfs_apply_failed)
                    }
                }
            }
        }
    }

    private fun buildScript(state: SwitchState, paths: List<String>): String {
        val lines = mutableListOf<String>()

        // susfs4ksu 在不同实现/模块版本下可能落在多个位置，全部覆盖写入
        val dirs = listOf(
            SUSFS_DIR,
            "/data/adb/modules/susfs4ksu",
            "/data/adb/ksu/susfs4ksu",
        )

        val cfg = listOf(
            "sus_su=${if (state.appHide) 2 else 0}",
            "hide_cusrom=${if (state.appHide) 1 else 0}",
            "spoof_uname=${if (state.kernelSpoof) 1 else 0}",
            "spoof_cmdline=${if (state.kernelSpoof) 1 else 0}",
            "susfs_log=${if (state.susfsLog) 1 else 0}",
            "hide_sus_mnts_for_non_su_procs=${if (state.hideMnt) 1 else 0}",
        )
        val cfgArgs = cfg.joinToString(" ") { "'$it'" }
        val pathArgs = if (paths.isEmpty()) "" else paths.joinToString(" ") { "'$it'" }

        for (dir in dirs) {
            lines += "mkdir -p $dir 2>/dev/null || true"
            lines += "printf '%s\\n' $cfgArgs > $dir/config.sh 2>/dev/null || true"
            lines += if (paths.isEmpty()) {
                ": > $dir/sus_path.txt 2>/dev/null || true"
            } else {
                "printf '%s\\n' $pathArgs > $dir/sus_path.txt 2>/dev/null || true"
            }
            lines += "chmod 644 $dir/config.sh $dir/sus_path.txt 2>/dev/null || true"
        }

        // 若 ksu_susfs 可用，立即下发路径（越狱模式可能没有该二进制，失败不影响配置写入）
        lines += "for b in /data/adb/ksu/bin/ksu_susfs /data/adb/ksu/susfs4ksu/ksu_susfs /data/adb/modules/susfs4ksu/ksu_susfs /data/adb/susfs4ksu/ksu_susfs; do"
        lines += "  if [ -x \$b ]; then"
        for (path in paths) {
            lines += "    \$b add_sus_path '$path' 2>/dev/null || true"
        }
        lines += "    break"
        lines += "  fi"
        lines += "done"

        lines += "true"
        return lines.joinToString("\n")
    }

    private fun snack(resId: Int) {
        Snackbar.make(binding.root, resId, Snackbar.LENGTH_LONG).show()
    }

    private fun snack(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }
}
