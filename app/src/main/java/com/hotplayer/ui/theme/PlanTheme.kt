package com.hotplayer.ui.theme

import android.view.animation.Interpolator
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * Full visual contract for a subscription tier. One object implements this
 * per plan (ThemeBasic/ThemeSelect/ThemeElite) — this interface is the ONLY
 * place the app declares "what a plan looks like"; nothing outside ui/theme
 * ever branches on a tier string (see ThemeRegistry).
 */
interface PlanTheme {
    val tier: String

    @get:ColorRes val accentColorRes: Int

    /** null = leave the existing root background untouched (this is what BASIC does). */
    @get:DrawableRes val rootBackgroundRes: Int?

    val cardStyle: CardStyle

    /** null = no badge shown (BASIC has none). */
    val badge: BadgeStyle?

    @get:ColorRes val nameGradientStartRes: Int
    @get:ColorRes val nameGradientEndRes: Int

    val animations: AnimationStyle

    /** null = keep the layout's default subtitle text (this is what BASIC does). */
    val welcomeSubtitle: String?
}

data class CardStyle(
    @DrawableRes val backgroundRes: Int,
    @DrawableRes val focusForegroundRes: Int,
    val glowEnabled: Boolean,
    val focusElevationDp: Float
)

data class BadgeStyle(
    val text: String,
    @DrawableRes val backgroundRes: Int,
    @ColorRes val textColorRes: Int
)

data class AnimationStyle(
    val focusScale: Float,
    val focusDurationMs: Long,
    val interpolator: Interpolator
)
