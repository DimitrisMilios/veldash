package com.veldash.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import com.veldash.R
import com.veldash.databinding.ActivityMainBinding
import com.veldash.map.BatArt
import com.veldash.routing.Connectivity
import com.veldash.search.Favorites
import com.veldash.search.Geocoder
import com.veldash.search.Place
import com.veldash.util.Bg
import org.maplibre.android.geometry.LatLng

/**
 * Full-screen destination search over the map. Hidden (GONE) until opened, so it costs nothing
 * while driving. One EditText, two buttons, one ListView with a BaseAdapter that recycles a
 * single TextView per row.
 *
 * Row order: [save current destination] [favorites matching query] [online results].
 */
class SearchPanel(
    private val activity: Activity,
    private val b: ActivityMainBinding,
    private val favorites: Favorites,
    private val onPick: (Place) -> Unit,
    private val currentDestination: () -> LatLng?,
    private val currentPosition: () -> LatLng?,
) {

    private class Row(val place: Place?, val favorite: Boolean, val label: String?)

    private val rows = ArrayList<Row>()
    private val adapter = PlaceAdapter()
    private var searchSeq = 0

    val isOpen: Boolean get() = b.panelSearch.visibility == View.VISIBLE

    init {
        b.listPlaces.adapter = adapter
        b.listPlaces.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
        b.listPlaces.setOnItemLongClickListener { _, _, pos, _ -> onRowLongClick(rows[pos]); true }
        b.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(b.editSearch.text.toString())
                true
            } else {
                false
            }
        }
        b.btnSearchGo.setOnClickListener { search(b.editSearch.text.toString()) }
        b.btnSearchClose.setOnClickListener { close() }
    }

    fun open(initialQuery: String? = null) {
        b.panelSearch.visibility = View.VISIBLE
        if (initialQuery != null) {
            b.editSearch.setText(initialQuery)
            search(initialQuery)
        } else {
            showLocal("")
        }
        b.editSearch.requestFocus()
        imm().showSoftInput(b.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        imm().hideSoftInputFromWindow(b.editSearch.windowToken, 0)
        b.editSearch.clearFocus()
        b.panelSearch.visibility = View.GONE
        searchSeq++ // drop any in-flight result
    }

    /** Called when favorites finish loading, or after an add/remove. */
    fun refresh() {
        if (isOpen) showLocal(b.editSearch.text.toString())
    }

    // ---- search ----

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        imm().hideSoftInputFromWindow(b.editSearch.windowToken, 0)

        // Coordinates: instant, offline.
        Geocoder.parseCoordinates(query)?.let { p ->
            rows.clear()
            rows += Row(p, favorites.isFavorite(p.lat, p.lon), null)
            adapter.notifyDataSetChanged()
            return
        }

        showLocal(query)
        if (query.isEmpty()) return
        if (!Connectivity.isOnline) {
            rows += Row(null, false, activity.getString(R.string.search_offline))
            adapter.notifyDataSetChanged()
            return
        }

        val seq = ++searchSeq
        rows += Row(null, false, activity.getString(R.string.searching))
        adapter.notifyDataSetChanged()

        val near = currentPosition()
        Bg.compute({
            try {
                Geocoder.search(query, near?.latitude, near?.longitude)
            } catch (e: Exception) {
                null
            }
        }) { result ->
            if (seq != searchSeq || !isOpen) return@compute
            rows.removeAll { it.place == null && it.label != null }
            if (result == null) {
                rows += Row(null, false, activity.getString(R.string.search_failed))
            } else if (result.isEmpty() && rows.none { it.place != null }) {
                rows += Row(null, false, activity.getString(R.string.no_results))
            } else {
                for (p in result) {
                    if (rows.none { it.place?.sameSpot(p.lat, p.lon) == true }) {
                        rows += Row(p, favorites.isFavorite(p.lat, p.lon), null)
                    }
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    /** Favorites (filtered) plus the save-destination row. No network. */
    private fun showLocal(query: String) {
        rows.clear()
        val dest = currentDestination()
        if (dest != null && !favorites.isFavorite(dest.latitude, dest.longitude)) {
            rows += Row(null, false, activity.getString(R.string.save_destination))
        }
        for (p in favorites.matching(query)) rows += Row(p, true, null)
        adapter.notifyDataSetChanged()
    }

    // ---- row actions ----

    private fun onRowClick(row: Row) {
        val p = row.place
        if (p != null) {
            close()
            onPick(p)
            return
        }
        if (row.label == activity.getString(R.string.save_destination)) {
            val dest = currentDestination() ?: return
            promptName(null) { name -> favorites.add(Place(name, "", dest.latitude, dest.longitude)); refresh() }
        }
    }

    private fun onRowLongClick(row: Row) {
        val p = row.place ?: return
        if (row.favorite) {
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.remove_favorite, p.name))
                .setPositiveButton(R.string.remove) { _, _ -> favorites.remove(p); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            promptName(p.name) { name -> favorites.add(Place(name, p.detail, p.lat, p.lon)); refresh() }
        }
    }

    private fun promptName(initial: String?, onName: (String) -> Unit) {
        val edit = EditText(activity).apply {
            setText(initial ?: "")
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.favorite_name)
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun imm(): InputMethodManager =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ---- adapter ----

    private inner class PlaceAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(activity)
        private val dimColor = color(R.color.bat_text_dim)
        private val starColor = color(R.color.bat_yellow)
        // Row icons, one instance each (a drawable can back many TextViews while it is not mutated).
        private val starIcon: Drawable = activity.getDrawable(R.drawable.ic_star)!!
        private val placeIcon: Drawable = BitmapDrawable(activity.resources, BatArt.logoIcon(activity))

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = rows[position].place != null || rows[position].label == activity.getString(R.string.save_destination)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView ?: inflater.inflate(R.layout.item_place, parent, false)) as TextView
            val row = rows[position]
            val p = row.place
            if (p == null) {
                val save = row.label == activity.getString(R.string.save_destination)
                tv.text = row.label
                tv.setTextColor(if (save) starColor else dimColor)
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(if (save) starIcon else null, null, null, null)
                return tv
            }
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(if (row.favorite) starIcon else placeIcon, null, null, null)
            val sb = SpannableStringBuilder()
            sb.append(p.name)
            if (p.detail.isNotEmpty()) {
                val start = sb.length
                sb.append('\n').append(p.detail)
                sb.setSpan(RelativeSizeSpan(0.7f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(dimColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tv.text = sb
            tv.setTextColor(color(R.color.bat_text))
            return tv
        }
    }

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = activity.resources.getColor(id)
}
