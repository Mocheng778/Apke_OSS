package org.apkesu.app.ui.fragment

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.ui.fragment.SettingsFragment
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.attrDrawable
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.homeItemBackgroundColor
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.themeColor
import icu.nullptr.hidemyapplist.ui.util.dpToPx
import icu.nullptr.hidemyapplist.ui.util.navigate
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

        styleList(binding.mineGroup)

        with(binding.navAbout) {
            text1.text = getString(R.string.title_about)
            icon.setImageResource(R.drawable.outline_info_24)
            root.setOnClickListener {
                navigate(R.id.nav_about)
            }
        }

        // Embed the full settings panel directly on the "Mine" page
        if (childFragmentManager.findFragmentById(R.id.settings_embed_container) == null) {
            val settingsFragment = SettingsFragment()
            settingsFragment.arguments = bundleOf("embedded" to true)
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_embed_container, settingsFragment)
                .commit()
        }
    }

    private fun styleList(group: ViewGroup) {
        val childCount = group.childCount

        val softCorner = 24.dpToPx
        val squareCorner = 8.dpToPx
        val pad = 16.dpToPx.toInt()

        for (i in 0..<childCount) {
            group.getChildAt(i).apply {
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

                when {
                    childCount == 1 -> {
                        backgroundDrawable.cornerRadii = floatArrayOf(
                            softCorner, softCorner, softCorner, softCorner,
                            softCorner, softCorner, softCorner, softCorner
                        )
                    }
                    i == 0 -> {
                        backgroundDrawable.cornerRadii = floatArrayOf(
                            softCorner, softCorner, softCorner, softCorner,
                            squareCorner, squareCorner, squareCorner, squareCorner
                        )
                    }
                    i == childCount - 1 -> {
                        backgroundDrawable.cornerRadii = floatArrayOf(
                            squareCorner, squareCorner, squareCorner, squareCorner,
                            softCorner, softCorner, softCorner, softCorner
                        )
                    }
                    else -> {
                        backgroundDrawable.cornerRadii = floatArrayOf(
                            squareCorner, squareCorner, squareCorner, squareCorner,
                            squareCorner, squareCorner, squareCorner, squareCorner
                        )
                    }
                }

                val ripple = attrDrawable(android.R.attr.selectableItemBackground)
                background = LayerDrawable(arrayOf(backgroundDrawable, ripple))
                clipToOutline = true
            }
        }
    }
}
