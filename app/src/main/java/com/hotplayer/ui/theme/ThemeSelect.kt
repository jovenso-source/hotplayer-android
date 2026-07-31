package com.hotplayer.ui.theme

import android.view.animation.DecelerateInterpolator
import com.hotplayer.R

/** Premium tier — gold accents, light glow, "SELECT" badge, more elegant chrome. */
object ThemeSelect : PlanTheme {
    override val tier = "SELECT"
    override val accentColorRes = R.color.select_accent
    override val rootBackgroundRes: Int? = null
    override val cardStyle = CardStyle(
        backgroundRes = R.drawable.bg_home_card_select,
        focusForegroundRes = R.drawable.bg_home_card_focus_select,
        glowEnabled = true,
        focusElevationDp = 20f
    )
    override val badge = BadgeStyle(
        text = "SELECT",
        backgroundRes = R.drawable.badge_select,
        textColorRes = R.color.select_badge_text
    )
    override val nameGradientStartRes = R.color.select_name_gradient_start
    override val nameGradientEndRes = R.color.select_name_gradient_end
    override val animations = AnimationStyle(
        focusScale = 1.08f,
        focusDurationMs = 180L,
        interpolator = DecelerateInterpolator()
    )
    override val welcomeSubtitle = "Bienvenue dans votre espace SELECT"
}
