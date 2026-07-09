package com.hotplayer.ui.renewal

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.hotplayer.data.model.RenewalConfig
import com.hotplayer.databinding.DialogRenewalBinding
import com.hotplayer.databinding.ViewRenewalBannerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RenewalManager {

    private const val PREFS_NAME  = "renewal_prefs"
    private const val KEY_DATE    = "shown_date"
    private const val BANNER_TAG  = "renewal_banner"

    // Flag en mémoire : popup affiché au moins une fois dans cette session (durée de vie = processus)
    private var popupShownThisSession = false

    /**
     * Point d'entrée unique. Appelé depuis HomeActivity.onCreate() et onResume().
     * Android n'applique aucune logique métier — il affiche uniquement ce que le backend décide.
     *
     *  - Bannière : toujours visible tant que show=true && type="banner" (mise à jour en direct)
     *  - Popup    : une seule fois par session (flag mémoire) + une fois par jour (SharedPreferences date)
     */
    fun checkAndShow(activity: AppCompatActivity, config: RenewalConfig?) {
        if (config?.show != true) {
            dismissBanner(activity)
            return
        }
        when (config.type) {
            "banner" -> showBanner(activity, config)
            else     -> showPopupIfNeeded(activity, config)
        }
    }

    // ─── Bannière ─────────────────────────────────────────────────────────────

    private fun showBanner(activity: AppCompatActivity, config: RenewalConfig) {
        dismissBanner(activity)

        val binding = ViewRenewalBannerBinding.inflate(activity.layoutInflater)
        binding.root.tag = BANNER_TAG

        binding.tvBannerMessage.text = config.title ?: ""

        // Bouton "Renouveler" : ouvre le popup directement sans passer par les filtres session/date
        binding.btnBannerAction.setOnClickListener {
            dismissBanner(activity)
            showPopup(activity, config.copy(type = "popup"))
        }

        binding.btnBannerDismiss.setOnClickListener { dismissBanner(activity) }

        val contentView = activity.window.decorView
            .findViewById<FrameLayout>(android.R.id.content)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        contentView.addView(binding.root, lp)
    }

    private fun dismissBanner(activity: AppCompatActivity) {
        val contentView = activity.window.decorView
            .findViewById<FrameLayout>(android.R.id.content)
        contentView.findViewWithTag<View>(BANNER_TAG)?.let { contentView.removeView(it) }
    }

    // ─── Popup ────────────────────────────────────────────────────────────────

    private fun showPopupIfNeeded(activity: AppCompatActivity, config: RenewalConfig) {
        if (popupShownThisSession) return
        if (config.showOncePerDay == true) {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (prefs.getString(KEY_DATE, "") == today) return
            prefs.edit().putString(KEY_DATE, today).apply()
        }
        popupShownThisSession = true
        showPopup(activity, config)
    }

    private fun showPopup(activity: AppCompatActivity, config: RenewalConfig) {
        if (activity.isFinishing || activity.isDestroyed) return

        val binding = DialogRenewalBinding.inflate(activity.layoutInflater)
        val dialog  = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(binding.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.setCancelable(config.dismissible == true)

        applyPopupContent(binding, config)
        wirePopupButtons(binding, config, dialog, activity)

        dialog.show()

        // Focus initial sur le premier bouton visible
        val firstButton = listOf(
            binding.btnRenewalCopyId,
            binding.btnRenewalContact,
            binding.btnRenewalDismiss
        ).firstOrNull { it.visibility == View.VISIBLE }
        firstButton?.post { firstButton.requestFocus() }
    }

    private fun applyPopupContent(
        binding: DialogRenewalBinding,
        config: RenewalConfig
    ) {
        binding.tvRenewalTitle.text   = config.title   ?: ""
        binding.tvRenewalMessage.text = config.message ?: ""
        binding.tvRenewalPlan.text    = config.plan    ?: "—"
        binding.tvRenewalDeviceId.text = config.deviceId ?: "—"

        binding.tvRenewalExpiry.text = config.expirationDate ?: "—"

        val days = config.daysRemaining
        binding.tvRenewalDays.text = when {
            days == null -> "—"
            days == 0   -> "Aujourd'hui"
            days > 0    -> "$days j"
            else        -> "Expiré"
        }

        // Couleur de l'icône selon la priorité
        val iconColor = when (config.priority) {
            "critical" -> "#ff4466"
            "warning"  -> "#f5c518"
            else       -> "#00A8FF"
        }
        binding.tvRenewalIcon.setTextColor(android.graphics.Color.parseColor(iconColor))

        // Bouton contact : visible uniquement si le backend l'a activé
        val contactLabel = config.buttons?.contact
        if (!contactLabel.isNullOrBlank()) {
            binding.btnRenewalContact.text       = contactLabel
            binding.btnRenewalContact.visibility = View.VISIBLE
        }

        // Bouton dismiss
        val dismissLabel = config.buttons?.dismiss
        if (config.dismissible == true && !dismissLabel.isNullOrBlank()) {
            binding.btnRenewalDismiss.text       = dismissLabel
            binding.btnRenewalDismiss.visibility = View.VISIBLE
        }

        // Libellé copier
        config.buttons?.copyId?.let { binding.btnRenewalCopyId.text = it }

        // Chaîne D-Pad : ajustement si contact masqué
        if (binding.btnRenewalContact.visibility != View.VISIBLE) {
            binding.btnRenewalCopyId.nextFocusDownId  = binding.btnRenewalDismiss.id
            binding.btnRenewalDismiss.nextFocusUpId   = binding.btnRenewalCopyId.id
        }
    }

    private fun wirePopupButtons(
        binding: DialogRenewalBinding,
        config: RenewalConfig,
        dialog: Dialog,
        activity: AppCompatActivity
    ) {
        // Copier l'identifiant
        binding.btnRenewalCopyId.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("device_id", config.deviceId ?: "")
            )
            binding.btnRenewalCopyId.text = "✓  Copié !"
            binding.btnRenewalCopyId.postDelayed({
                binding.btnRenewalCopyId.text = config.buttons?.copyId ?: "Copier l'identifiant"
            }, 2000)
        }

        // Contacter l'administrateur
        binding.btnRenewalContact.setOnClickListener {
            openContactAction(activity, config)
        }

        // Plus tard / Fermer
        binding.btnRenewalDismiss.setOnClickListener {
            if (config.dismissible == true) dialog.dismiss()
        }
    }

    // ─── Action contact (extensible via action.type) ───────────────────────────

    private fun openContactAction(activity: AppCompatActivity, config: RenewalConfig) {
        val contact = config.contact ?: return
        val actionType = config.action?.type ?: "contact_admin"

        val intent: Intent? = when (actionType) {
            "whatsapp" -> buildWhatsAppIntent(contact.whatsapp ?: contact.phone)
            "phone"    -> buildPhoneIntent(contact.phone)
            "email"    -> buildEmailIntent(contact.email)
            "website"  -> buildUrlIntent(contact.website)
            else       -> {
                // "contact_admin" : première méthode disponible
                buildWhatsAppIntent(contact.whatsapp)
                    ?: buildPhoneIntent(contact.phone)
                    ?: buildEmailIntent(contact.email)
                    ?: buildUrlIntent(contact.website)
            }
        }
        intent?.let { activity.startActivity(it) }
    }

    private fun buildWhatsAppIntent(number: String?): Intent? {
        if (number.isNullOrBlank()) return null
        val clean = number.replace(Regex("[^\\d+]"), "")
        return Intent(Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$clean")).apply {
            setPackage("com.whatsapp")
        }
    }

    private fun buildPhoneIntent(number: String?): Intent? {
        if (number.isNullOrBlank()) return null
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
    }

    private fun buildEmailIntent(email: String?): Intent? {
        if (email.isNullOrBlank()) return null
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
    }

    private fun buildUrlIntent(url: String?): Intent? {
        if (url.isNullOrBlank()) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
}
