package com.veldash.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.veldash.R
import com.veldash.databinding.DialogPlaceBinding
import com.veldash.map.BatArt
import com.veldash.routing.Connectivity
import com.veldash.search.Geocoder
import com.veldash.search.Place
import com.veldash.util.Bg
import org.maplibre.android.geometry.LatLng

/**
 * The dialog behind "Tap to set" and Edit on Home and Work, and behind saving or editing a
 * favorite: a name (favorites only), an address line and a row of logos to pick from
 * ([PlaceIcons], plus the row's default glyph).
 *
 * Saving with the address line untouched keeps the place where it is and only changes the
 * name and logo. A typed address is resolved first: "lat, lon" instantly, anything else
 * through the online geocoder, whose first hit becomes the place. The dialog stays up while
 * that runs and reports failures inline, so a dropped connection never loses what was typed.
 */
class PlaceEditor(private val activity: Activity, private val currentPosition: () -> LatLng?) {

    /** What is being edited: decides the title, the default glyph and whether a name is asked. */
    enum class Target { HOME, WORK, FAVORITE }

    /**
     * Shows the editor. [initial] is the place as known so far (null when setting up a new
     * Home or Work); [saved] says it is already in its list (an edit rather than an add, which
     * only changes the title and whether the keyboard comes up); [onSave] gets the finished
     * place; [onSearch], when given, adds a Search button that hands over to picking the
     * place from the search list instead.
     */
    fun show(
        target: Target,
        initial: Place?,
        saved: Boolean,
        onSave: (Place) -> Unit,
        onSearch: (() -> Unit)? = null,
    ) {
        val v = DialogPlaceBinding.inflate(LayoutInflater.from(activity))
        val isFavorite = target == Target.FAVORITE
        val anchorText = initial?.address() ?: ""

        v.editPlaceName.visibility = if (isFavorite) View.VISIBLE else View.GONE
        v.editPlaceName.setText(initial?.name ?: "")
        v.editPlaceAddress.setText(anchorText)
        v.editPlaceAddress.setSelection(v.editPlaceAddress.length())

        // ---- logo chips: the default glyph first, then the five badges ----
        var selected = initial?.icon?.takeIf { PlaceIcons.drawable(it) != 0 } ?: ""
        val glyph = when (target) {
            Target.HOME -> R.drawable.ic_home
            Target.WORK -> R.drawable.ic_work
            Target.FAVORITE -> R.drawable.ic_star
        }
        val chips = ArrayList<ImageView>()
        fun select(id: String) {
            selected = id
            for (c in chips) c.isSelected = c.tag == id
        }
        for (id in listOf("") + PlaceIcons.ALL) {
            v.rowPlaceIcons.addView(chip(id, glyph) { select(id) }.also { chips += it })
        }
        select(selected)

        // ---- dialog ----
        val title = when (target) {
            Target.HOME -> R.string.home
            Target.WORK -> R.string.work
            Target.FAVORITE -> if (saved) R.string.edit_favorite else R.string.save_favorite
        }
        val builder = AlertDialog.Builder(activity, R.style.Theme_Veldash_Dialog)
            .setTitle(title)
            .setView(v.root)
            .setPositiveButton(R.string.save, null) // replaced below: Save must not auto-dismiss
            .setNegativeButton(R.string.cancel, null)
        if (onSearch != null) builder.setNeutralButton(R.string.search_instead) { _, _ -> onSearch() }
        val dialog = builder.create()
        // Something new starts with the keyboard up, in the field that needs typing (the name of
        // a favorite, the address of a shortcut). An edit is more likely about the logo, so
        // there the keyboard waits for a tap.
        if (!saved) {
            (if (isFavorite) v.editPlaceName else v.editPlaceAddress).requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        // The IME was raised for this window; make sure it goes down with it.
        dialog.setOnDismissListener { imm().hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
        dialog.show()
        fitWidth(dialog)

        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun status(res: Int) {
            v.txtPlaceStatus.setText(res)
            v.txtPlaceStatus.visibility = View.VISIBLE
        }
        fun finish(p: Place) {
            imm().hideSoftInputFromWindow(v.root.windowToken, 0)
            onSave(p)
            dialog.dismiss()
        }
        fun attempt() {
            val name = v.editPlaceName.text.toString().trim()
            val text = v.editPlaceAddress.text.toString().trim()
            if (isFavorite && name.isEmpty()) {
                status(R.string.enter_name)
                return
            }
            if (initial != null && (text.isEmpty() || text == anchorText)) {
                finish(initial.with(name = if (isFavorite) name else initial.name, icon = selected))
                return
            }
            if (text.isEmpty()) {
                status(R.string.enter_address)
                return
            }
            resolve(text, dialog, save, ::status) { hit ->
                finish(if (isFavorite) Place(name, hit.address(), hit.lat, hit.lon, selected) else hit.with(icon = selected))
            }
        }
        save.setOnClickListener { attempt() }
        v.editPlaceAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attempt()
                true
            } else {
                false
            }
        }
    }

    /** One round logo chip; [glyph] is the vector drawn for the default (empty) id. */
    private fun chip(id: String, glyph: Int, onClick: () -> Unit): ImageView {
        val res = activity.resources
        val size = res.getDimensionPixelSize(R.dimen.pick_chip)
        val chip = ImageView(activity)
        chip.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = res.getDimensionPixelSize(R.dimen.gap_medium)
        }
        chip.setBackgroundResource(R.drawable.bg_chip_pick)
        chip.scaleType = ImageView.ScaleType.FIT_CENTER
        chip.tag = id
        chip.contentDescription = activity.getString(PlaceIcons.label(id))
        chip.isClickable = true
        chip.isFocusable = true
        val logo = PlaceIcons.drawable(id)
        val pad: Int
        if (logo == 0) {
            pad = res.getDimensionPixelSize(R.dimen.pick_glyph_padding)
            chip.setImageResource(glyph)
        } else {
            pad = res.getDimensionPixelSize(R.dimen.pick_chip_padding)
            val dp = Math.round((size - 2 * pad) / res.displayMetrics.density)
            chip.setImageBitmap(BatArt.placeLogo(activity, logo, dp))
        }
        chip.setPadding(pad, pad, pad, pad)
        chip.setOnClickListener { onClick() }
        return chip
    }

    /**
     * Turns the typed line into a place: coordinates at once, otherwise the first online hit.
     * Progress and failures go to [status]; Save is disabled while the request is out.
     */
    private fun resolve(text: String, dialog: AlertDialog, save: Button, status: (Int) -> Unit, onHit: (Place) -> Unit) {
        Geocoder.parseCoordinates(text)?.let {
            onHit(it)
            return
        }
        if (!Connectivity.isOnline) {
            status(R.string.editor_offline)
            return
        }
        status(R.string.searching)
        save.isEnabled = false
        val near = currentPosition()
        Bg.compute({
            try {
                Geocoder.search(text, near?.latitude, near?.longitude)
            } catch (e: Exception) {
                null
            }
        }) { result ->
            if (!dialog.isShowing) return@compute
            save.isEnabled = true
            when {
                result == null -> status(R.string.search_failed)
                result.isEmpty() -> status(R.string.no_results)
                else -> onHit(result[0])
            }
        }
    }

    /** The platform dialog would span most of the wide head-unit screen; cap it like the dropdown. */
    private fun fitWidth(dialog: AlertDialog) {
        val res = activity.resources
        val want = res.getDimensionPixelSize(R.dimen.dialog_width)
        val avail = res.displayMetrics.widthPixels - 2 * res.getDimensionPixelSize(R.dimen.gap_large)
        dialog.window?.setLayout(Math.min(want, avail), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun imm(): InputMethodManager =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}
