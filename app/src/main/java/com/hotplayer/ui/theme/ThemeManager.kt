package com.hotplayer.ui.theme

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.hotplayer.databinding.ActivityHomeBinding

/**
 * Single entry point for all plan-driven UI customization — same convention
 * as RenewalManager.checkAndShow(): the app never branches on a tier string
 * itself, it just asks ThemeManager to apply whatever the backend/tier says.
 *
 * apply() is safe to call repeatedly (onCreate, and again whenever a fresh
 * `tier`/`theme` arrives from activate()/heartbeat()) — it always resolves
 * a full PlanTheme and re-applies it, there is no partial/incremental state.
 */
object ThemeManager {

    private const val TAG = "ThemeManager"

    // Resolved once per apply() so HomeActivity can re-trigger the gradient after the name
    // text changes (its width — and therefore the shader bounds — depends on the text).
    private var lastGradientColors: Pair<Int, Int> = Color.WHITE to Color.WHITE

    fun apply(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        tier: String?,
        override: ThemeConfig?
    ) {
        val theme = ThemeRegistry.resolve(tier)
        val ctx = activity

        val accentColor    = parseColor(override?.accentColor)    ?: ContextCompat.getColor(ctx, theme.accentColorRes)
        val gradientStart  = parseColor(override?.primaryColor)   ?: ContextCompat.getColor(ctx, theme.nameGradientStartRes)
        val gradientEnd    = parseColor(override?.secondaryColor) ?: ContextCompat.getColor(ctx, theme.nameGradientEndRes)
        val subtitle       = override?.welcomeMessage?.takeIf { it.isNotBlank() } ?: theme.welcomeSubtitle
        val badge          = theme.badge?.let { it.copy(text = override?.badgeText?.takeIf { t -> t.isNotBlank() } ?: it.text) }
        val animationsOn   = override?.animationsEnabled ?: true

        lastGradientColors = gradientStart to gradientEnd

        applyRootBackground(ctx, binding, theme, override?.backgroundUrl)
        applyCards(ctx, binding, theme, animationsOn)
        applyBadge(ctx, binding, badge)
        applyNameGradient(binding, gradientStart, gradientEnd)
        applySubtitle(binding, subtitle)

        Log.i(TAG, "Theme applied: tier=${theme.tier} accent=#${Integer.toHexString(accentColor)}")
    }

    // ─── Root background ────────────────────────────────────────────────────

    private fun applyRootBackground(
        ctx: AppCompatActivity,
        binding: ActivityHomeBinding,
        theme: PlanTheme,
        backgroundUrl: String?
    ) {
        if (!backgroundUrl.isNullOrBlank()) {
            Glide.with(ctx).load(backgroundUrl).into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    binding.root.background = resource
                }
                override fun onLoadCleared(placeholder: Drawable?) { /* keep current background */ }
            })
            return
        }
        theme.rootBackgroundRes?.let { binding.root.setBackgroundResource(it) }
        // rootBackgroundRes == null (BASIC) → leave the XML-defined background untouched.
    }

    // ─── Dashboard cards ────────────────────────────────────────────────────

    private fun applyCards(
        ctx: AppCompatActivity,
        binding: ActivityHomeBinding,
        theme: PlanTheme,
        animationsOn: Boolean
    ) {
        val density = ctx.resources.displayMetrics.density
        val focusedElevation = theme.cardStyle.focusElevationDp * density
        val restElevation = 3f * density
        val scale = if (animationsOn) theme.animations.focusScale else 1f
        val duration = if (animationsOn) theme.animations.focusDurationMs else 0L

        val cards = listOf(
            binding.cardLive    to binding.indicatorLive,
            binding.cardSports  to binding.indicatorSports,
            binding.cardFilms   to binding.indicatorFilms
        )

        cards.forEach { (card, indicator) ->
            card.setBackgroundResource(theme.cardStyle.backgroundRes)
            card.foreground = ContextCompat.getDrawable(ctx, theme.cardStyle.focusForegroundRes)
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) scale else 1f)
                    .scaleY(if (hasFocus) scale else 1f)
                    .setDuration(duration)
                    .setInterpolator(theme.animations.interpolator)
                    .start()
                v.elevation = if (hasFocus) focusedElevation else restElevation
                indicator.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
        }
    }

    // ─── Badge ──────────────────────────────────────────────────────────────

    private fun applyBadge(ctx: AppCompatActivity, binding: ActivityHomeBinding, badge: BadgeStyle?) {
        val tv = binding.tvPlanBadge
        if (badge == null) {
            tv.visibility = View.GONE
            return
        }
        tv.text = badge.text
        tv.setBackgroundResource(badge.backgroundRes)
        tv.setTextColor(ContextCompat.getColor(ctx, badge.textColorRes))
        tv.visibility = View.VISIBLE
    }

    // ─── Name gradient ──────────────────────────────────────────────────────

    /** Re-applies the last resolved gradient — call after tvWelcomeName's text changes. */
    fun refreshNameGradient(binding: ActivityHomeBinding) {
        applyNameGradient(binding, lastGradientColors.first, lastGradientColors.second)
    }

    private fun applyNameGradient(binding: ActivityHomeBinding, start: Int, end: Int) {
        val tv = binding.tvWelcomeName
        tv.post {
            val w = tv.width.toFloat()
            if (w > 0f) {
                tv.paint.shader = LinearGradient(
                    0f, 0f, w, 0f,
                    intArrayOf(start, end),
                    null,
                    Shader.TileMode.CLAMP
                )
                tv.invalidate()
            }
        }
    }

    // ─── Welcome subtitle ───────────────────────────────────────────────────

    private fun applySubtitle(binding: ActivityHomeBinding, subtitle: String?) {
        subtitle?.let { binding.tvWelcomeSub.text = it }
        // null → keep whatever setupWelcome()/layout default already set.
    }

    private fun parseColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try { Color.parseColor(hex) } catch (_: IllegalArgumentException) { null }
    }
}
