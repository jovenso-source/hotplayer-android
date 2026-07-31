package com.hotplayer.ui.theme

/**
 * The one and only place a tier string is matched against a concrete theme.
 * Adding a new plan later (FAMILY/BUSINESS/VIP) means adding one PlanTheme
 * object + one line here — no other file in the app needs to change.
 */
object ThemeRegistry {
    private val themes: Map<String, PlanTheme> = linkedMapOf(
        ThemeBasic.tier  to ThemeBasic,
        ThemeSelect.tier to ThemeSelect,
        ThemeElite.tier  to ThemeElite
    )

    fun resolve(tier: String?): PlanTheme =
        themes[tier?.trim()?.uppercase()] ?: ThemeBasic
}
