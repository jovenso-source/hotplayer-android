package com.hotplayer.data.model

enum class ChannelType { LIVE, MOVIE, SERIES }

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val type: ChannelType = ChannelType.LIVE
)

data class EpgItem(
    val title: String,
    val description: String,
    val startTs: Long,
    val stopTs: Long
) {
    val isNow: Boolean
        get() = System.currentTimeMillis() / 1000 in startTs..stopTs

    fun startFormatted(): String = fmtTs(startTs)
    fun stopFormatted(): String  = fmtTs(stopTs)

    fun progress(): Int {
        val now   = System.currentTimeMillis() / 1000
        val total = stopTs - startTs
        return if (total <= 0) 0 else ((now - startTs) * 100 / total).toInt().coerceIn(0, 100)
    }

    private fun fmtTs(ts: Long) = timeFmt.get()!!.format(java.util.Date(ts * 1000))

    companion object {
        // ThreadLocal avoids creating a new SimpleDateFormat per call (GC pressure on Fire TV)
        private val timeFmt = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        }
    }
}
