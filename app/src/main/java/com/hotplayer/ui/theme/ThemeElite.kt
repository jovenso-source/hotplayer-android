package com.hotplayer.ui.theme

import android.view.animation.OvershootInterpolator
import com.hotplayer.R

/** Top tier — black/gold/purple, exclusive background, refined animations, "ELITE" badge. */
object ThemeElite : PlanTheme {
    override val tier = "ELITE"
    override val accentColorRes = R.color.elite_accent
    override val rootBackgroundRes: Int? = R.drawable.bg_home_root_elite
    override val cardStyle = CardStyle(
        backgroundRes = R.drawable.bg_home_card_elite,
        focusForegroundRes = R.drawable.bg_home_card_focus_elite,
        glowEnabled = true,
        focusElevationDp = 24f
    )
    override val badge = BadgeStyle(
        text = "ELITE",
        backgroundRes = R.drawable.badge_elite,
        textColorRes = R.color.elite_badge_text
    )
    override val nameGradientStartRes = R.color.elite_name_gradient_start
    override val nameGradientEndRes = R.color.elite_name_gradient_end
    override val animations = AnimationStyle(
        focusScale = 1.10f,
        focusDurationMs = 220L,
        interpolator = OvershootInterpolator(1.4f)
    )
    override val welcomeSubtitle = "Bienvenue dans votre expérience ELITE"
}
