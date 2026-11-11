package org.apkesu.app.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
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
import org.apkesu.app.databinding.ViewSusfsPathRowBinding
import org.apkesu.app.ui.util.DeviceInfo
import kotlin.concurrent.thread

class SusfsFragment : Fragment(R.layout.fragment_susfs) {
    private val binding by viewBinding(FragmentSusfsBinding::bind)

    companion object {
        private const val PREFS_NAME = "susfs_path_hide"

        private const val KEY_JAILBREAK = "jailbreak"
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

    @Volatile
    private var supported = false

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

    // ---------------------------------------------------------------- 状态检测

    private fun loadDetection(refresh: Boolean) {
        if (refresh) {
            binding.susfsStatus.setText(R.string.susfs_support_checking)
        }

        thread {
            val isSupported = DeviceInfo.susfsSupported()
            val version = DeviceInfo.susfsVersion()
            val framework = DeviceInfo.rootFramework()
            val features = DeviceInfo.susfsEnabledFeatures()
            supported = isSupported

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.susfsStatus.setText(
                        if (isSupported) R.string.susfs_support_yes else R.string.susfs_support_no
                    )
                    binding.susfsVersion.text = listOfNotNull(version, features)
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() }
                    binding.rootFramework.text = framework
                }
            }
        }
    }

    // ---------------------------------------------------------------- 开关

    private fun restoreSwitches() {
        binding.jailbreakSwitch.isChecked = prefs.getBoolean(KEY_JAILBREAK, false)
        binding.switchAppHide.isChecked = prefs.getBoolean(KEY_APP_HIDE, true)
        binding.switchKernelSpoof.isChecked = prefs.getBoolean(KEY_KERNEL_SPOOF, true)
        binding.switchSusfsLog.isChecked = prefs.getBoolean(KEY_SUSFS_LOG, false)
        binding.switchHideMntNonroot.isChecked = prefs.getBoolean(KEY_HIDE_MNT_NONROOT, true)

        binding.jailbreakSwitch.setOnCheckedChangeListener { _, v -> saveBool(KEY_JAILBREAK, v) }
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
        val jailbreak = binding.jailbreakSwitch.isChecked
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
            val hasRoot = DeviceInfo.hasRoot()
            val allowed = hasRoot && (supported || jailbreak)
            val ok = if (allowed) DeviceInfo.execRoot(buildScript(state, paths)) else false

            if (isAdded) {
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.loadEnableBtn.isEnabled = true
                    binding.applyBtn.isEnabled = true
                    when {
                        !hasRoot -> snack(R.string.susfs_apply_noroot)
                        !allowed -> snack(R.string.susfs_support_no)
                        ok -> snack(getString(R.string.susfs_loaded, paths.size))
                        else -> snack(R.string.susfs_apply_failed)
                    }
                }
            }
        }
    }

    private fun buildScript(state: SwitchState, paths: List<String>): String {
        val lines = mutableListOf<String>()
        lines += "mkdir -p $SUSFS_DIR"

        val cfg = listOf(
            "sus_su=${if (state.appHide) 2 else 0}",
            "hide_cusrom=${if (state.appHide) 1 else 0}",
            "spoof_uname=${if (state.kernelSpoof) 1 else 0}",
            "spoof_cmdline=${if (state.kernelSpoof) 1 else 0}",
            "susfs_log=${if (state.susfsLog) 1 else 0}",
            "hide_sus_mnts_for_non_su_procs=${if (state.hideMnt) 1 else 0}",
        )
        lines += "printf '%s\\n' " + cfg.joinToString(" ") { "'$it'" } +
            " > $SUSFS_DIR/config.sh"

        lines += if (paths.isEmpty()) {
            ": > $SUSFS_DIR/sus_path.txt"
        } else {
            "printf '%s\\n' " + paths.joinToString(" ") { "'$it'" } +
                " > $SUSFS_DIR/sus_path.txt"
        }

        lines += "chmod 644 $SUSFS_DIR/config.sh $SUSFS_DIR/sus_path.txt"

        // 若 ksu_susfs 可用，立即下发路径（越狱模式下即使内核不支持也尽力尝试）
        DeviceInfo.ksuSusfsBin()?.let { bin ->
            for (path in paths) {
                lines += "[ -x $bin ] && $bin add_sus_path '$path' 2>/dev/null || true"
            }
        }

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
