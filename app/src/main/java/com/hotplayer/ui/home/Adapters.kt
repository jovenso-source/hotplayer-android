package com.hotplayer.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hotplayer.R
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.EpgItem
import kotlin.math.abs

/* ════════════════════════════════════════════════════
   CHANNEL ADAPTER  (HomeActivity — unchanged)
════════════════════════════════════════════════════ */
class ChannelAdapter(
    private val onChannelClick : (Channel) -> Unit,
    private val onChannelFocus : (Channel) -> Unit,
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo  : ImageView = view.findViewById(R.id.ivChannelLogo)
        val name  : TextView  = view.findViewById(R.id.tvChannelName)
        val group : TextView  = view.findViewById(R.id.tvChannelGroup)

        fun bind(ch: Channel) {
            name.text  = ch.name
            group.text = ch.group ?: ""
            if (!ch.logo.isNullOrBlank()) {
                Glide.with(itemView).load(ch.logo)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(logo)
            } else {
                logo.setImageResource(R.drawable.ic_channel_placeholder)
            }
            itemView.setOnClickListener  { onChannelClick(ch) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onChannelFocus(ch)
                itemView.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(150).start()
            }
            itemView.isFocusable            = true
            itemView.isFocusableInTouchMode = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel)    = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}

/* ════════════════════════════════════════════════════
   CATEGORY ADAPTER  (HomeActivity — unchanged)
════════════════════════════════════════════════════ */
class CategoryAdapter(
    private val onCategoryClick: (String) -> Unit
) : ListAdapter<String, CategoryAdapter.VH>(DIFF) {

    private var selectedPosition = 0

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.tvCategoryLabel)
        fun bind(category: String, isSelected: Boolean) {
            label.text           = category
            itemView.isSelected  = isSelected
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                val prev = selectedPosition
                selectedPosition = p
                notifyItemChanged(prev)
                notifyItemChanged(p)
                onCategoryClick(category)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), position == selectedPosition)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String)    = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  LIVE TV — CATEGORY ADAPTER  (item_live_cat.xml)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Selected state: white card, dark text, subtitle visible ("N chaînes").
// Unselected state: transparent bg, light text, subtitle hidden.
// Colors toggled in selectAt() (for live updates on visible VHs) and
// onBindViewHolder (for items scrolled into view from RecyclerView pool).

class LiveCategoryAdapter(
    private val onFocused : (Int, String) -> Unit,
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<LiveCategoryAdapter.VH>() {

    private var items = listOf<Pair<String, Int>>()
    var selectedPos: Int = 0
        private set
    private var rv: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rv = null
    }

    fun getCategoryAt(pos: Int): String? = items.getOrNull(pos)?.first

    fun selectAt(pos: Int) {
        if (pos == selectedPos) return
        val prev = selectedPos
        selectedPos = pos
        applySelectionToVh(rv?.findViewHolderForAdapterPosition(prev), false)
        applySelectionToVh(rv?.findViewHolderForAdapterPosition(pos),  true)
    }

    private fun applySelectionToVh(vh: RecyclerView.ViewHolder?, selected: Boolean) {
        val h = vh as? VH ?: return
        h.itemView.isSelected = selected
        if (selected) {
            h.tvName.setTextColor(COLOR_SELECTED_NAME)
            h.tvName.textSize = 13.5f
            h.tvCount.visibility = View.VISIBLE
        } else {
            h.tvName.setTextColor(COLOR_NORMAL_NAME)
            h.tvName.textSize = 13f
            h.tvCount.visibility = View.GONE
        }
    }

    fun setData(data: List<Pair<String, Int>>) {
        if (data == items) return
        val prevName = items.getOrNull(selectedPos)?.first
        val oldItems = items
        items = data
        selectedPos = if (prevName != null)
            data.indexOfFirst { it.first == prevName }.coerceAtLeast(0)
        else 0

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = data.size
            override fun areItemsTheSame(o: Int, n: Int) = oldItems[o].first == data[n].first
            override fun areContentsTheSame(o: Int, n: Int) = oldItems[o] == data[n]
        })
        diff.dispatchUpdatesTo(this)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvAvatar: TextView = v.findViewById(R.id.tvCatAvatar)
        val tvName  : TextView = v.findViewById(R.id.tvCatName)
        val tvCount : TextView = v.findViewById(R.id.tvCatTotal)
        private val circleBg = GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            tvAvatar.background = it
        }
        fun setAvatarColor(color: Int) = circleBg.setColor(color)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_cat, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (name, count) = items[pos]
        val isSelected = pos == selectedPos

        // Avatar: 2-letter abbreviation + palette color
        val abbr = name.filter { it.isLetter() }.take(2).uppercase()
            .ifEmpty { name.take(2).uppercase() }
        h.tvAvatar.text = abbr
        h.setAvatarColor(AVATAR_COLORS[abs(name.hashCode()) % AVATAR_COLORS.size])

        h.tvName.text   = name
        h.tvCount.text  = "$count chaînes"

        // Selection state: white card + dark text vs. transparent + light text
        h.itemView.isSelected = isSelected
        if (isSelected) {
            h.tvName.setTextColor(COLOR_SELECTED_NAME)
            h.tvName.textSize    = 13.5f
            h.tvCount.visibility = View.VISIBLE
        } else {
            h.tvName.setTextColor(COLOR_NORMAL_NAME)
            h.tvName.textSize    = 13f
            h.tvCount.visibility = View.GONE
        }

        h.itemView.isFocusable = true
        (h.itemView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        h.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val cat = items.getOrNull(p)?.first ?: return@setOnFocusChangeListener
                    onFocused(p, cat)
                }
            }
            h.itemView.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }

        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                selectAt(p)
                onSelected(items.getOrNull(p)?.first ?: return@setOnClickListener)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        h.itemView.onFocusChangeListener = null
        h.itemView.setOnClickListener(null)
    }

    companion object {
        private val COLOR_SELECTED_NAME = Color.parseColor("#1A1D2E")
        private val COLOR_NORMAL_NAME   = Color.parseColor("#94A3B8")

        private val AVATAR_COLORS = intArrayOf(
            0xFF1B4F72.toInt(), 0xFF117A65.toInt(), 0xFF1A5276.toInt(),
            0xFF6E2F5E.toInt(), 0xFF784212.toInt(), 0xFF2E4057.toInt(),
            0xFF1E8449.toInt(), 0xFF922B21.toInt(), 0xFF4A235A.toInt(),
            0xFF1F618D.toInt(), 0xFF0E5251.toInt(), 0xFF4D1B0F.toInt()
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  LIVE TV — CHANNEL ADAPTER  (item_live_ch.xml)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Plain RecyclerView.Adapter with synchronous notifyDataSetChanged().
// No DiffUtil — avoids the 20-200ms async delay on category switches.

class LiveChannelAdapter(
    private val onChClick: (Channel, Int) -> Unit,
    private val onChFocus: (Channel, Int) -> Unit
) : RecyclerView.Adapter<LiveChannelAdapter.VH>() {

    private var items       = listOf<Channel>()
    private val epgMap      = mutableMapOf<String, EpgItem?>()
    private var selectedUrl : String? = null
    private var rv          : RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        rv = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rv = null
    }

    fun setData(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelectedUrl(url: String?) {
        if (url == selectedUrl) return
        val prev = selectedUrl
        selectedUrl = url
        if (prev != null) {
            val i = items.indexOfFirst { it.url == prev }
            if (i >= 0) rv?.findViewHolderForAdapterPosition(i)?.itemView?.isSelected = false
        }
        if (url != null) {
            val i = items.indexOfFirst { it.url == url }
            if (i >= 0) rv?.findViewHolderForAdapterPosition(i)?.itemView?.isSelected = true
        }
    }

    fun updateEpg(channelUrl: String, epg: EpgItem?) {
        epgMap[channelUrl] = epg
        val idx = items.indexOfFirst { it.url == channelUrl }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_EPG)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvLogo   : TextView    = v.findViewById(R.id.tvChLogo)
        val ivLogo   : ImageView   = v.findViewById(R.id.ivChLogo)
        val tvName   : TextView    = v.findViewById(R.id.tvChName)
        val tvQuality: TextView    = v.findViewById(R.id.tvChQuality)
        val tvNum    : TextView    = v.findViewById(R.id.tvChNum)
        val tvEpg    : TextView    = v.findViewById(R.id.tvChEpg)
        val pbEpg    : ProgressBar = v.findViewById(R.id.pbChEpg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_ch, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]

        h.tvName.text = ch.name
        h.tvNum.text  = "%02d".format(pos + 1)

        if (!ch.logo.isNullOrBlank()) {
            Glide.with(h.itemView.context).load(ch.logo)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(h.ivLogo)
            h.ivLogo.visibility = View.VISIBLE
            h.tvLogo.visibility = View.GONE
        } else {
            Glide.with(h.itemView.context).clear(h.ivLogo)
            h.ivLogo.visibility = View.GONE
            h.tvLogo.visibility = View.VISIBLE
            h.tvLogo.text = ch.name.take(3).uppercase()
        }

        val badge = ch.liveTvQualityBadge()
        h.tvQuality.text       = badge
        h.tvQuality.visibility = if (badge != null) View.VISIBLE else View.GONE

        bindEpg(h, ch)

        h.itemView.isSelected = ch.url == selectedUrl
        h.itemView.isFocusable = true
        (h.itemView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        h.itemView.setOnFocusChangeListener { _, hasFocus ->
            val p = h.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION) {
                onChFocus(items[p], p)
            }
            h.itemView.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }

        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onChClick(items[p], p)
        }
    }

    override fun onBindViewHolder(h: VH, pos: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_EPG)) bindEpg(h, items[pos])
        else super.onBindViewHolder(h, pos, payloads)
    }

    private fun bindEpg(h: VH, ch: Channel) {
        val epg = epgMap[ch.url]
        h.tvEpg.text     = epg?.title ?: "En direct"
        h.pbEpg.progress = epg?.progress() ?: 0
    }

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        h.itemView.onFocusChangeListener = null
        h.itemView.setOnClickListener(null)
        try { Glide.with(h.itemView.context.applicationContext).clear(h.ivLogo) } catch (_: Exception) {}
    }

    companion object {
        private const val PAYLOAD_EPG = "epg"
    }
}

private fun Channel.liveTvQualityBadge(): String? {
    val t = (name + " " + (group ?: "")).uppercase()
    return when {
        t.contains("4K")  || t.contains("UHD")                     -> "4K"
        t.contains("FHD") || t.contains("FULL HD")                 -> "FHD"
        t.contains(" HD") || t.endsWith("HD") || t.contains("HD+") -> "HD"
        t.contains(" SD") || t.endsWith("SD")                      -> "SD"
        else -> null
    }
}
