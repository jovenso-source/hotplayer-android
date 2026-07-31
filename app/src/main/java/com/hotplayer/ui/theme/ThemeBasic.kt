package com.hotplayer.ui.theme

import android.view.animation.DecelerateInterpolator
import com.hotplayer.R

/**
 * Default tier — deliberately identical to the app's original design.
 * Fallback for devices with no tier assigned yet and for backends that
 * don't send `tier` at all (see ThemeRegistry.resolve).
 */
object ThemeBasic : PlanTheme {
    override val tier = "BASIC"
    override val accentColorRes = R.color.accent
    override val rootBackgroundRes: Int? = null
    override val cardStyle = CardStyle(
        backgroundRes = R.drawable.bg_home_card,
        focusForegroundRes = R.drawable.bg_home_card_focus,
        glowEnabled = false,
        focusElevationDp = 16f
    )
    override val badge: BadgeStyle? = null
    override val nameGradientStartRes = R.color.basic_name_gradient_start
    override val nameGradientEndRes = R.color.basic_name_gradient_end
    override val animations = AnimationStyle(
        focusScale = 1.06f,
        focusDurationMs = 160L,
        interpolator = DecelerateInterpolator()
    )
    override val welcomeSubtitle: String? = null
}
