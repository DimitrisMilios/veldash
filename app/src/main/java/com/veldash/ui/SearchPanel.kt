package com.veldash.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import com.veldash.R
import com.veldash.databinding.ActivityMainBinding
import com.veldash.map.BatArt
import com.veldash.routing.Connectivity
import com.veldash.search.Geocoder
import com.veldash.search.Place
import com.veldash.search.PlaceStore
import com.veldash.util.Bg
import com.veldash.util.Prefs
import org.maplibre.android.geometry.LatLng

/**
 * Google Maps style search dropdown. The top-left search bar opens a card in the same spot:
 * the field on top, then Home and Work shortcuts, saved places and recent destinations, which
 * filter locally as you type. Go (or the keyboard's search key) adds online results below.
 *
 * Home, Work and favorites are set up in the [PlaceEditor] dialog (address line, name, logo).
 * From there, Home and Work can also switch the card into "pick" mode: the next place chosen
 * from the list is stored as that shortcut instead of being driven to.
 *
 * Hidden (GONE) until opened, so it costs nothing while driving. Rows are a plain LinearLayout
 * of recycled row views: at most a dozen, so no adapter machinery.
 */
class SearchPanel(
    private val activity: Activity,
    private val b: ActivityMainBinding,
    private val prefs: Prefs,
    private val favorites: PlaceStore,
    private val recents: PlaceStore,
    private val onPick: (Place) -> Unit,
    private val currentDestination: () -> LatLng?,
    private val currentDestinationName: () -> String?,
    private val currentPosition: () -> LatLng?,
) {

    private enum class Kind { HOME, WORK, SAVE_DEST, HERE, FAVORITE, RECENT, RESULT, STATUS, MORE }

    private class Row(val kind: Kind, val place: Place?, val title: String, val subtitle: String = "")

    private class Holder(val icon: ImageView, val title: TextView, val subtitle: TextView, val action: TextView)

    private val rows = ArrayList<Row>()
    private val inflater = LayoutInflater.from(activity)
    private val editor = PlaceEditor(activity, currentPosition)
    private var searchSeq = 0

    /** Home or Work while picking a place for that shortcut, else null. */
    private var pickingFor: Kind? = null
    private var recentsExpanded = false

    private val logo: Bitmap by lazy { BatArt.logoIcon(activity) }
    private val iconPad = dimen(R.dimen.row_icon_padding)
    private val logoPad = dimen(R.dimen.row_logo_padding)
    private val badgePad = dimen(R.dimen.row_badge_padding)
    /** Side of a place logo inside the chip, in dp. */
    private val badgeDp = Math.round((dimen(R.dimen.chip) - 2 * badgePad) / activity.resources.displayMetrics.density)
    private val colorText = color(R.color.bat_text)
    private val colorDim = color(R.color.bat_text_dim)
    private val colorYellow = color(R.color.bat_yellow)

    val isOpen: Boolean get() = b.panelSearch.visibility == View.VISIBLE

    init {
        b.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(b.editSearch.text.toString())
                true
            } else {
                false
            }
        }
        // Local rows filter as you type; the network is only asked on an explicit search.
        b.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isOpen) return
                searchSeq++ // an in-flight online search no longer matches the text
                showLocal(s?.toString() ?: "")
            }
        })
        b.btnSearchGo.setOnClickListener { search(b.editSearch.text.toString()) }
        b.btnSearchClose.setOnClickListener { back() }
        b.scrimSearch.setOnClickListener { close() }
    }

    /**
     * Opens the dropdown. The keyboard is not raised: in a car the saved and recent rows are the
     * common case, and the IME would cover them. Tapping the field brings it up.
     */
    fun open(initialQuery: String? = null) {
        fitWidth()
        pickingFor = null
        recentsExpanded = false
        b.editSearch.setHint(R.string.search_hint)
        b.scrimSearch.visibility = View.VISIBLE
        b.panelSearch.visibility = View.VISIBLE
        b.editSearch.setText(initialQuery ?: "") // the watcher fills the rows
        b.editSearch.setSelection(b.editSearch.length())
        b.scrollPlaces.scrollTo(0, 0)
        if (initialQuery != null) search(initialQuery)
    }

    fun close() {
        imm().hideSoftInputFromWindow(b.editSearch.windowToken, 0)
        b.editSearch.clearFocus()
        b.panelSearch.visibility = View.GONE
        b.scrimSearch.visibility = View.GONE
        pickingFor = null
        searchSeq++ // drop any in-flight result
    }

    /** Back: leave pick mode first, then close. */
    fun back() {
        if (pickingFor != null) endPicking() else close()
    }

    /** Called when favorites or recents finish loading, or after an add/remove. */
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
            rows += Row(Kind.RESULT, p, p.name, p.detail)
            render()
            return
        }

        showLocal(query)
        if (query.isEmpty()) return
        if (!Connectivity.isOnline) {
            rows += Row(Kind.STATUS, null, activity.getString(R.string.search_offline))
            render()
            return
        }

        val seq = ++searchSeq
        rows += Row(Kind.STATUS, null, activity.getString(R.string.searching))
        render()

        val near = currentPosition()
        Bg.compute({
            try {
                Geocoder.search(query, near?.latitude, near?.longitude)
            } catch (e: Exception) {
                null
            }
        }) { result ->
            if (seq != searchSeq || !isOpen) return@compute
            rows.removeAll { it.kind == Kind.STATUS }
            if (result == null) {
                rows += Row(Kind.STATUS, null, activity.getString(R.string.search_failed))
            } else if (result.isEmpty() && rows.none { it.place != null }) {
                rows += Row(Kind.STATUS, null, activity.getString(R.string.no_results))
            } else {
                for (p in result) {
                    if (rows.none { it.place?.sameSpot(p.lat, p.lon) == true }) rows += Row(Kind.RESULT, p, p.name, p.detail)
                }
            }
            render()
        }
    }

    /** Shortcuts, saved and recent places matching [query]. No network. */
    private fun showLocal(query: String) {
        rows.clear()
        val q = query.trim()
        val picking = pickingFor

        if (picking == null) {
            shortcutRow(Kind.HOME, prefs.home, R.string.home, q)
            shortcutRow(Kind.WORK, prefs.work, R.string.work, q)
            val dest = currentDestination()
            if (q.isEmpty() && dest != null && !favorites.contains(dest.latitude, dest.longitude)) {
                rows += Row(Kind.SAVE_DEST, null, activity.getString(R.string.save_destination))
            }
        } else if (q.isEmpty()) {
            val here = currentPosition()
            if (here != null) {
                rows += Row(Kind.HERE, null, activity.getString(R.string.use_current_location), coords(here.latitude, here.longitude))
            }
        }

        for (p in favorites.matching(q)) rows += Row(Kind.FAVORITE, p, p.name, p.detail)

        // A recent that is already a shortcut or a favorite is listed once, as that.
        val home = prefs.home
        val work = prefs.work
        val recent = recents.matching(q).filter { r ->
            !favorites.contains(r.lat, r.lon) && home?.sameSpot(r.lat, r.lon) != true && work?.sameSpot(r.lat, r.lon) != true
        }
        val cap = if (q.isEmpty() && !recentsExpanded) RECENTS_COLLAPSED else Int.MAX_VALUE
        for (p in recent.take(cap)) rows += Row(Kind.RECENT, p, p.name, p.detail)
        if (recent.size > cap) rows += Row(Kind.MORE, null, activity.getString(R.string.more_recents))

        render()
    }

    private fun shortcutRow(kind: Kind, p: Place?, labelRes: Int, q: String) {
        val label = activity.getString(labelRes)
        if (q.isNotEmpty()) {
            // While typing, a shortcut shows only if set and matching (by its label or address).
            if (p == null) return
            if (!label.contains(q, true) && !p.name.contains(q, true) && !p.detail.contains(q, true)) return
        }
        rows += Row(kind, p, label, p?.address() ?: activity.getString(R.string.tap_to_set))
    }

    // ---- rows ----

    private fun render() {
        val list = b.listPlaces
        for (i in rows.indices) {
            val v = list.getChildAt(i) ?: inflater.inflate(R.layout.item_place, list, false).also {
                it.tag = Holder(
                    it.findViewById(R.id.row_icon),
                    it.findViewById(R.id.row_title),
                    it.findViewById(R.id.row_subtitle),
                    it.findViewById(R.id.row_action),
                )
                list.addView(it)
            }
            bind(v, rows[i])
        }
        if (list.childCount > rows.size) list.removeViews(rows.size, list.childCount - rows.size)
    }

    private fun bind(v: View, row: Row) {
        val h = v.tag as Holder
        val kind = row.kind

        // Home, Work and favorites wear their chosen badge, or the bat logo (the default), which
        // search results also use. The other rows keep their Material glyphs.
        val saved = kind == Kind.HOME || kind == Kind.WORK || kind == Kind.FAVORITE
        val badgeRes = if (saved) PlaceIcons.drawable(row.place?.icon ?: "") else 0
        val iconRes = when (kind) {
            Kind.SAVE_DEST -> R.drawable.ic_star
            Kind.HERE -> R.drawable.ic_my_location
            Kind.RECENT -> R.drawable.ic_history
            else -> 0
        }
        // The wide bat logo needs less inset than the square glyphs to read at badge size;
        // the square place logos sit in between.
        val pad = when {
            badgeRes != 0 -> badgePad
            saved || kind == Kind.RESULT -> logoPad
            else -> iconPad
        }
        h.icon.setPadding(pad, pad, pad, pad)
        when {
            badgeRes != 0 -> {
                h.icon.setImageBitmap(BatArt.placeLogo(activity, badgeRes, badgeDp))
                h.icon.visibility = View.VISIBLE
            }
            iconRes != 0 -> {
                h.icon.setImageResource(iconRes)
                h.icon.visibility = View.VISIBLE
            }
            saved || kind == Kind.RESULT -> {
                h.icon.setImageBitmap(logo)
                h.icon.visibility = View.VISIBLE
            }
            else -> h.icon.visibility = View.GONE
        }

        h.title.text = row.title
        h.title.setTextColor(
            when (kind) {
                Kind.STATUS -> colorDim
                Kind.MORE, Kind.SAVE_DEST -> colorYellow
                else -> colorText
            },
        )
        h.title.gravity = if (kind == Kind.MORE) Gravity.CENTER else Gravity.START
        h.subtitle.text = row.subtitle
        h.subtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE

        val editable = (kind == Kind.HOME || kind == Kind.WORK) && row.place != null
        h.action.visibility = if (editable) View.VISIBLE else View.GONE
        h.action.setOnClickListener(if (editable) View.OnClickListener { editShortcut(kind) } else null)

        if (kind == Kind.STATUS) {
            v.setOnClickListener(null)
            v.setOnLongClickListener(null)
            v.isClickable = false
            v.isLongClickable = false
        } else {
            v.setOnClickListener { onRowClick(row) }
            v.setOnLongClickListener { onRowLongClick(row) }
        }
    }

    private fun onRowClick(row: Row) {
        val p = row.place
        when (row.kind) {
            Kind.HOME, Kind.WORK -> if (p != null) go(p) else editShortcut(row.kind)
            Kind.SAVE_DEST -> {
                val dest = currentDestination() ?: return
                // A searched destination keeps its name; a dropped pin is named by the driver.
                val name = currentDestinationName()
                val seed = Place(name ?: "", if (name == null) coords(dest.latitude, dest.longitude) else "", dest.latitude, dest.longitude)
                editor.show(PlaceEditor.Target.FAVORITE, seed, saved = false, onSave = { favorites.add(it); refresh() })
            }
            Kind.HERE -> {
                val here = currentPosition() ?: return
                assign(Place(activity.getString(R.string.use_current_location), coords(here.latitude, here.longitude), here.latitude, here.longitude))
            }
            Kind.FAVORITE, Kind.RECENT, Kind.RESULT -> if (p != null) {
                if (pickingFor != null) assign(p) else go(p)
            }
            Kind.MORE -> {
                recentsExpanded = true
                showLocal(b.editSearch.text.toString())
            }
            Kind.STATUS -> Unit
        }
    }

    private fun onRowLongClick(row: Row): Boolean {
        val p = row.place ?: return false
        when (row.kind) {
            Kind.HOME, Kind.WORK -> confirm(activity.getString(R.string.remove_shortcut, row.title)) {
                setShortcut(row.kind, null)
                refresh()
            }
            Kind.FAVORITE -> AlertDialog.Builder(activity, R.style.Theme_Veldash_Dialog)
                .setTitle(p.name)
                .setItems(arrayOf(activity.getString(R.string.edit), activity.getString(R.string.remove))) { _, which ->
                    if (which == 0) {
                        editor.show(PlaceEditor.Target.FAVORITE, p, saved = true, onSave = { favorites.replace(p, it); refresh() })
                    } else {
                        confirm(activity.getString(R.string.remove_favorite, p.name)) { favorites.remove(p); refresh() }
                    }
                }
                .show()
            Kind.RECENT -> AlertDialog.Builder(activity, R.style.Theme_Veldash_Dialog)
                .setTitle(p.name)
                .setItems(arrayOf(activity.getString(R.string.save_favorite), activity.getString(R.string.remove_recent))) { _, which ->
                    if (which == 0) {
                        editor.show(PlaceEditor.Target.FAVORITE, p, saved = false, onSave = { favorites.add(it); refresh() })
                    } else {
                        recents.remove(p)
                        refresh()
                    }
                }
                .show()
            Kind.RESULT -> editor.show(PlaceEditor.Target.FAVORITE, p, saved = false, onSave = { favorites.add(it); refresh() })
            else -> return false
        }
        return true
    }

    /** Drive there: remember it in the recent history (without its logo), then hand it to the router. */
    private fun go(p: Place) {
        recents.add(p.with(icon = ""))
        close()
        onPick(p)
    }

    // ---- Home / Work ----

    /** The editor for a shortcut: type the address, pick its logo, or switch to picking from the list. */
    private fun editShortcut(kind: Kind) {
        val target = if (kind == Kind.HOME) PlaceEditor.Target.HOME else PlaceEditor.Target.WORK
        editor.show(
            target,
            shortcut(kind),
            saved = shortcut(kind) != null,
            onSave = { setShortcut(kind, it); refresh() },
            onSearch = { startPicking(kind) },
        )
    }

    private fun shortcut(kind: Kind): Place? = if (kind == Kind.HOME) prefs.home else prefs.work

    private fun setShortcut(kind: Kind, p: Place?) {
        if (kind == Kind.HOME) prefs.home = p else prefs.work = p
    }

    private fun startPicking(kind: Kind) {
        pickingFor = kind
        b.editSearch.setHint(if (kind == Kind.HOME) R.string.set_home_hint else R.string.set_work_hint)
        b.editSearch.setText("")
        b.scrollPlaces.scrollTo(0, 0)
        b.editSearch.requestFocus()
        imm().showSoftInput(b.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    /** A place picked from the list becomes the shortcut; the logo chosen earlier stays. */
    private fun assign(p: Place) {
        val kind = pickingFor ?: return
        setShortcut(kind, p.with(icon = shortcut(kind)?.icon ?: ""))
        endPicking()
    }

    private fun endPicking() {
        pickingFor = null
        imm().hideSoftInputFromWindow(b.editSearch.windowToken, 0)
        b.editSearch.setHint(R.string.search_hint)
        b.editSearch.setText("")
    }

    // ---- helpers ----

    /** Card width: the design width, or the screen minus margins on a narrow (portrait) unit. */
    private fun fitWidth() {
        val margin = dimen(R.dimen.gap_large)
        val want = dimen(R.dimen.search_panel_width)
        val avail = b.root.width - 2 * margin
        val w = if (avail in 1 until want) avail else want
        val lp = b.panelSearch.layoutParams
        if (lp.width != w) {
            lp.width = w
            b.panelSearch.layoutParams = lp
        }
    }

    private fun coords(lat: Double, lon: Double): String = activity.getString(R.string.coords, lat, lon)

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(activity, R.style.Theme_Veldash_Dialog)
            .setMessage(message)
            .setPositiveButton(R.string.remove) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun imm(): InputMethodManager =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun dimen(id: Int): Int = activity.resources.getDimensionPixelSize(id)

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = activity.resources.getColor(id)

    private companion object {
        /** Recent rows shown before "More from recent history". */
        const val RECENTS_COLLAPSED = 4
    }
}
