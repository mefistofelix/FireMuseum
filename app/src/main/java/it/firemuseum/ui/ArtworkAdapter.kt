package it.firemuseum.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.firemuseum.App
import it.firemuseum.R
import it.firemuseum.model.Artwork

class ArtworkAdapter(
    private val thumbs: ThumbLoader,
    private val onClick: (Artwork, Int) -> Unit,
    private val onLongClick: (Artwork) -> Unit,
) : RecyclerView.Adapter<ArtworkAdapter.VH>() {

    var items: List<Artwork> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val badge: TextView = v.findViewById(R.id.badge)
        val flags: TextView = v.findViewById(R.id.flags)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_artwork, parent, false)
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = items[pos]
        h.title.text = a.title
        h.subtitle.text = listOfNotNull(a.author, a.year).joinToString(" · ")
        h.badge.text = a.resolutionBadge()
        val fav = a.id in App.instance.settings.favorites
        h.flags.text = buildString {
            if (fav) append("★")
            if (a.targets.isNotEmpty()) append("🎯")
            if (a.portals.isNotEmpty() || a.tags.contains("quadri nel quadro")) append("🪟")
            if (!a.kidSafe) append("⚠")
        }
        h.flags.visibility = if (h.flags.text.isEmpty()) View.GONE else View.VISIBLE
        thumbs.load(a, h.thumb)
        h.itemView.setOnClickListener { onClick(a, h.bindingAdapterPosition) }
        h.itemView.setOnLongClickListener { onLongClick(a); true }
    }
}
