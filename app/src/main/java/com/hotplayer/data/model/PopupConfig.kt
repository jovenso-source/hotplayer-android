package com.hotplayer.data.model

import com.google.gson.annotations.SerializedName

data class PopupResponse(
    val popup: PopupConfig?
)

data class PopupConfig(
    val id: String,
    val type: String,                                           // "announcement" | "maintenance" | "update"
    val title: String,
    val message: String,
    val force: Boolean = false,
    @SerializedName("new_version")     val newVersion: String? = null,
    @SerializedName("current_version") val currentVersion: String? = null,
    @SerializedName("download_url")    val downloadUrl: String? = null,
    @SerializedName("button_ok")       val buttonOk: String = "Fermer",
    @SerializedName("button_action")   val buttonAction: String? = null,
    @SerializedName("auto_close_sec")  val autoCloseSec: Int? = null
)
