package io.parkflow.probe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Native Views adapted from the Pleos grayscale and typography guidance. */
class ParkingUi(val context: Context) {
    private fun color(id: Int) = context.getColor(id)
    val ink = color(R.color.parkflow_ink)
    val supporting = color(R.color.parkflow_supporting)
    val muted = color(R.color.parkflow_muted)
    val canvas = color(R.color.parkflow_canvas)
    val surface = color(R.color.parkflow_surface)
    private val borderColor = color(R.color.parkflow_border)
    private val focus = color(R.color.parkflow_focus)
    private val pressed = color(R.color.parkflow_pressed)
    private val disabled = color(R.color.parkflow_disabled)
    private val disabledInk = color(R.color.parkflow_disabled_ink)
    private val filled = color(R.color.parkflow_filled)
    private val filledPressed = color(R.color.parkflow_filled_pressed)
    companion object { private var cachedTypeface: Typeface? = null }
    private val regular: Typeface = cachedTypeface ?: context.resources.getFont(R.font.asta_sans).also { cachedTypeface = it }
    private val bold = Typeface.create(regular, 700, false)
    fun typography(view: TextView, bold: Boolean = false) { view.typeface = if (bold) this.bold else regular }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 16, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1).coerceAtLeast(1), borderColor)
    }
    private fun focusedShape(color: Int, radius: Int) = shape(color, radius).apply { setStroke(dp(2).coerceAtLeast(2), focus) }
    private fun states(primary: Boolean, radius: Int = 24): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(disabled, radius))
        addState(intArrayOf(android.R.attr.state_focused), focusedShape(if (primary) filled else surface, radius))
        addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) filledPressed else pressed, radius, !primary))
        addState(intArrayOf(), shape(if (primary) filled else surface, radius, !primary))
    }
    fun field(view: EditText) {
        typography(view); view.setTextColor(ink); view.setHintTextColor(muted)
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(disabled, 16))
            addState(intArrayOf(android.R.attr.state_focused), focusedShape(surface, 16))
            addState(intArrayOf(), shape(canvas, 16, true))
        }
        view.setPadding(dp(18), dp(16), dp(18), dp(16)); view.minHeight = dp(80)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun space(parent: LinearLayout, height: Int = 24) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(borderColor) }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply { topMargin = dp(16); bottomMargin = dp(24) }) }
    fun text(parent: LinearLayout, value: String, size: Float = 26f, color: Int = ink, bold: Boolean = false): TextView = TextView(context).also {
        it.text = value; it.textSize = size; it.setTextColor(color); it.setLineSpacing(dp(4).toFloat(), 1f)
        typography(it, bold)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    fun card(parent: LinearLayout): LinearLayout = column().also {
        it.background = shape(surface); it.setPadding(dp(28), dp(28), dp(28), dp(28))
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
    }
    // Status is information, not a selectable pill.
    fun chip(parent: LinearLayout, value: String) = text(parent, value, 20f, supporting, true)
    fun button(parent: LinearLayout, value: String, primary: Boolean = true, action: () -> Unit): Button = Button(context).also {
        it.text = value; it.isAllCaps = false; it.textSize = 24f; typography(it, true)
        it.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabledInk, if (primary) 0xffffffff.toInt() else ink)))
        it.backgroundTintList = null
        // Darken Filled feedback: white ripple over the night pressed gray would reduce
        // the white label below 4.5:1 contrast at the ripple's maximum opacity.
        it.background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x22000000 else 0x22686a72), states(primary), shape(0xffffffff.toInt(), 24))
        it.minHeight = dp(80); it.minimumWidth = 0; it.setPadding(dp(24), dp(12), dp(24), dp(12)); it.stateListAnimator = null
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        it.setOnClickListener { action() }
    }
}
