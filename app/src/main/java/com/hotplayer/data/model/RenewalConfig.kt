package com.hotplayer.data.model

import com.google.gson.annotations.SerializedName

data class RenewalConfig(
    @SerializedName("show")              val show: Boolean,
    @SerializedName("type")              val type: String?            = null,
    @SerializedName("priority")          val priority: String?        = null,
    @SerializedName("title")             val title: String?           = null,
    @SerializedName("message")           val message: String?         = null,
    @SerializedName("days_remaining")    val daysRemaining: Int?      = null,
    @SerializedName("expiration_date")   val expirationDate: String?  = null,
    @SerializedName("plan")              val plan: String?            = null,
    @SerializedName("device_id")         val deviceId: String?        = null,
    @SerializedName("dismissible")       val dismissible: Boolean?    = null,
    @SerializedName("show_once_per_day") val showOncePerDay: Boolean? = null,
    @SerializedName("contact")           val contact: RenewalContact? = null,
    @SerializedName("action")            val action: RenewalAction?   = null,
    @SerializedName("buttons")           val buttons: RenewalButtons? = null
)

data class RenewalContact(
    @SerializedName("name")     val name:     String?,
    @SerializedName("phone")    val phone:    String?,
    @SerializedName("whatsapp") val whatsapp: String?,
    @SerializedName("email")    val email:    String?,
    @SerializedName("website")  val website:  String?
)

data class RenewalAction(
    @SerializedName("type") val type: String?
)

data class RenewalButtons(
    @SerializedName("copy_id") val copyId:  String?,
    @SerializedName("contact") val contact: String?,
    @SerializedName("dismiss") val dismiss: String?
)
