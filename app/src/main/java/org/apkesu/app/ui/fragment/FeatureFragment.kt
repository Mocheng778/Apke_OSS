package org.apkesu.app.ui.fragment

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.androidbroadcast.vbpd.viewBinding
import icu.nullptr.hidemyapplist.service.PrefManager
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.attrDrawable
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.homeItemBackgroundColor
import icu.nullptr.hidemyapplist.ui.util.ThemeUtils.themeColor
import icu.nullptr.hidemyapplist.ui.util.dpToPx
import icu.nullptr.hidemyapplist.ui.util.navigate
import icu.nullptr.hidemyapplist.ui.util.setEdge2EdgeFlags
import icu.nullptr.hidemyapplist.ui.util.setupToolbar
import org.apkesu.app.R
import org.apkesu.app.databinding.FragmentFeaturesBinding

class FeatureFragment : Fragment(R.layout.fragment_features) {
    private val binding by viewBinding(FragmentFeaturesBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding.toolbar) {
            setupToolbar(toolbar = this, title = getString(R.string.title_features))
        }

        setEdge2EdgeFlags(binding.root)

        styleList(binding.featureGroup)

        with(binding.manageApps) {
            text1.text = getString(R.string.title_app_manage)
            icon.setImageResource(R.drawable.outline_android_24)
            root.setOnClickListener {
                navigate(R.id.nav_app_manage)
            }
        }

        with(binding.manageTemplates) {
            text1.text = getString(R.string.title_template_manage)
            icon.setImageResource(R.drawable.ic_outline_layers_24)
            root.setOnClickListener {
                navigate(R.id.nav_template_manage)
            }
        }

        with(binding.managePresets) {
            text1.text = getString(R.string.title_preset_manage)
            icon.setImageResource(R.drawable.baseline_my_location_24)
            root.setOnClickListener {
                navigate(R.id.nav_presets)
            }
        }

        with(binding.navBulkConfigWizard) {
            text1.text = getString(R.string.title_bulk_config_wizard)
            icon.setImageResource(R.drawable.outline_storage_24)
            root.setOnClickListener {
                navigate(R.id.nav_bulk_config_wizard)
            }
        }

        with(binding.navLogs) {
            text1.text = getString(R.string.title_logs)
            icon.setImageResource(R.drawable.outline_assignment_24)
            root.setOnClickListener {
                navigate(R.id.nav_logs)
            }
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

                when (i) {
                    0 -> {
                        backgroundDrawable.cornerRadii = floatArrayOf(
                            softCorner, softCorner, softCorner, softCorner,
                            squareCorner, squareCorner, squareCorner, squareCorner
                        )
                    }
                    childCount - 1 -> {
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
