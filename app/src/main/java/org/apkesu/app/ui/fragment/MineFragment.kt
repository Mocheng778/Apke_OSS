package org.apkesu.app.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.ui.fragment.SettingsFragment
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentMineBinding

class MineFragment : Fragment(R.layout.fragment_mine) {
    private val binding by viewBinding(FragmentMineBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(toolbar = this, title = getString(R.string.title_mine))
        }

        setEdge2EdgeFlags(binding.root)

        // Embed the full settings panel directly on the "Mine" page;
        // the "About" entry lives at the bottom of the settings list.
        if (childFragmentManager.findFragmentById(R.id.settings_embed_container) == null) {
            val settingsFragment = SettingsFragment()
            settingsFragment.arguments = bundleOf("embedded" to true)
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_embed_container, settingsFragment)
                .commit()
        }
    }
}
