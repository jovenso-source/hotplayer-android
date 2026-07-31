package com.hotplayer.ui.theme

import com.google.gson.annotations.SerializedName

/**
 * Backend-pushed overrides for the current tier's theme (see PlanTheme).
 * Every field is optional: null = keep the app's compiled default for that
 * field. This is what lets the backend change a color, the badge text, the
 * background image or the welcome message without a new APK release.
 */
data class ThemeConfig(
    @SerializedName("tier")                val tier: String? = null,
    @SerializedName("primary_color")       val primaryColor: String? = null,
    @SerializedName("secondary_color")     val secondaryColor: String? = null,
    @SerializedName("accent_color")        val accentColor: String? = null,
    @SerializedName("badge_text")          val badgeText: String? = null,
    @SerializedName("background_url")      val backgroundUrl: String? = null,
    @SerializedName("welcome_message")     val welcomeMessage: String? = null,
    @SerializedName("glow_enabled")        val glowEnabled: Boolean? = null,
    @SerializedName("animations_enabled")  val animationsEnabled: Boolean? = null
)
