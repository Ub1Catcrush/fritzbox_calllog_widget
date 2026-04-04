package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.tvcs.fritzboxcallwidget.R

/**
 * A Preference that shows:
 *  - A coloured square swatch as the right-side widget
 *  - On click: an AlertDialog with ARGB sliders + hex EditText + live preview
 */
class ColorPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var currentColor: Int = Color.WHITE
    private var swatchView: View? = null

    init {
        widgetLayoutResource = R.layout.pref_color_swatch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        swatchView = holder.findViewById(R.id.color_swatch)
        updateSwatch()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val default = (defaultValue as? Int) ?: Color.WHITE
        currentColor = getPersistedInt(default)
        updateSwatch()
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any =
        a.getInteger(index, Color.WHITE)

    override fun onClick() {
        showColorDialog()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setColor(color: Int) {
        currentColor = color
        persistInt(color)
        updateSwatch()
        notifyChanged()
    }

    fun getColor(): Int = currentColor

    // ── Dialog ────────────────────────────────────────────────────────────────

    private fun showColorDialog() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_color_picker, null)

        val preview    = view.findViewById<View>(R.id.color_preview)
        val hexEdit    = view.findViewById<EditText>(R.id.et_hex)
        val seekA      = view.findViewById<SeekBar>(R.id.seek_a)
        val seekR      = view.findViewById<SeekBar>(R.id.seek_r)
        val seekG      = view.findViewById<SeekBar>(R.id.seek_g)
        val seekB      = view.findViewById<SeekBar>(R.id.seek_b)
        val labelA     = view.findViewById<TextView>(R.id.label_a)
        val labelR     = view.findViewById<TextView>(R.id.label_r)
        val labelG     = view.findViewById<TextView>(R.id.label_g)
        val labelB     = view.findViewById<TextView>(R.id.label_b)

        var editing = false // guard against recursive updates

        fun updateFromColor(color: Int) {
            val a = Color.alpha(color); val r = Color.red(color)
            val g = Color.green(color); val b = Color.blue(color)
            preview.setBackgroundColor(color)
            labelA.text = "A: $a"; labelR.text = "R: $r"
            labelG.text = "G: $g"; labelB.text = "B: $b"
            seekA.progress = a; seekR.progress = r
            seekG.progress = g; seekB.progress = b
            if (!editing) {
                editing = true
                hexEdit.setText("#%08X".format(color))
                hexEdit.setSelection(hexEdit.text.length)
                editing = false
            }
        }

        fun colorFromSliders() =
            Color.argb(seekA.progress, seekR.progress, seekG.progress, seekB.progress)

        updateFromColor(currentColor)

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                val c = colorFromSliders()
                editing = true
                updateFromColor(c)
                editing = false
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        seekA.setOnSeekBarChangeListener(seekListener)
        seekR.setOnSeekBarChangeListener(seekListener)
        seekG.setOnSeekBarChangeListener(seekListener)
        seekB.setOnSeekBarChangeListener(seekListener)

        hexEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                val hex = s?.toString() ?: return
                try {
                    val parsed = Color.parseColor(
                        if (hex.startsWith("#")) hex else "#$hex"
                    )
                    editing = true
                    updateFromColor(parsed)
                    editing = false
                } catch (_: Exception) { /* ignore invalid input */ }
            }
        })

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = colorFromSliders()
                if (callChangeListener(picked)) setColor(picked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Swatch ────────────────────────────────────────────────────────────────

    private fun updateSwatch() {
        val swatch = swatchView ?: return
        val bg = swatch.background as? GradientDrawable
            ?: GradientDrawable().also { swatch.background = it }
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = 4f * context.resources.displayMetrics.density
        bg.setColor(currentColor)
        bg.setStroke(
            (1 * context.resources.displayMetrics.density).toInt(),
            0x44000000
        )
    }
}
