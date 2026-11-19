package com.stormnav.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min

/**
 * The Menu: a scrolling panel that leaves part of the map visible and undimmed, so changes
 * such as radar opacity show live. Landscape: slides in from the right. Portrait: from the bottom.
 */
class SidePanel(private val act: Activity) {
    val root = FrameLayout(act)
    private val card = LinearLayout(act)
    private val content = LinearLayout(act)
    private val scroll = ScrollView(act)
    private var builder: ((SidePanel) -> Unit)? = null
    private var insets = IntArray(4)

    val isOpen get() = root.visibility == View.VISIBLE

    /** Called after the panel opens or closes (used for Android 13+ back handling). */
    var onVisibilityChanged: (() -> Unit)? = null

    private fun dp(v: Float) = (v * act.resources.displayMetrics.density).toInt()
    private fun TextView.sizeDp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, v)

    init {
        root.visibility = View.GONE
        root.setBackgroundColor(Color.argb(30, 0, 0, 0)) // barely tinted: the map stays visible
        root.setOnClickListener { close() }              // tap outside the panel to close
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        scroll.isVerticalScrollBarEnabled = true
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16f), dp(4f), dp(16f), dp(20f))
        scroll.addView(content)
        root.addView(card)
    }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) { insets = intArrayOf(left, top, right, bottom) }

    fun open(build: (SidePanel) -> Unit) {
        builder = build
        layout()
        refresh()
        root.visibility = View.VISIBLE
        root.bringToFront()
        scroll.scrollTo(0, 0)
        onVisibilityChanged?.invoke()
    }

    fun close() { root.visibility = View.GONE; onVisibilityChanged?.invoke() }

    /** Rebuilds the contents, e.g. after a setting changes so the selection highlights update. */
    fun refresh() {
        val y = scroll.scrollY
        content.removeAllViews()
        builder?.invoke(this)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun layout() {
        val dm = act.resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        card.removeAllViews()
        card.background = HudStyle.panel(act, 22f)
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18f), dp(10f), dp(6f), dp(2f)) }
        header.addView(TextView(act).apply { text = "StormNav"; setTextColor(Color.WHITE); sizeDp(22f); typeface = HudStyle.BOLD },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(act).apply {
            text = "✕"; setTextColor(Color.rgb(190, 196, 206)); sizeDp(22f); gravity = Gravity.CENTER
            setOnClickListener { close() }; contentDescription = "Close menu"
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        card.addView(header)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        card.layoutParams = if (landscape) {
            FrameLayout.LayoutParams(min(dp(430f), (dm.widthPixels * 0.48).toInt()), -1, Gravity.END).apply {
                setMargins(0, insets[1] + dp(8f), insets[2] + dp(8f), insets[3] + dp(8f))
            }
        } else {
            FrameLayout.LayoutParams(-1, (dm.heightPixels * 0.72).toInt(), Gravity.BOTTOM).apply {
                setMargins(insets[0] + dp(6f), 0, insets[2] + dp(6f), insets[3] + dp(6f))
            }
        }
    }

    // ------------------------------------------------------------ building blocks

    fun section(title: String) {
        content.addView(TextView(act).apply {
            text = title.uppercase(); setTextColor(HudStyle.INFO_BLUE); sizeDp(12f); typeface = HudStyle.BOLD
            letterSpacing = 0.08f; setPadding(dp(4f), dp(16f), 0, dp(6f))
        })
    }

    fun note(text: String) {
        content.addView(TextView(act).apply { this.text = text; setTextColor(Color.rgb(150, 160, 175)); sizeDp(12f); setPadding(dp(4f), dp(2f), dp(4f), dp(4f)) })
    }

    private fun button(label: String, selected: Boolean = false, rim: Int? = null, action: () -> Unit) = Button(act).apply {
        text = label; isAllCaps = false; sizeDp(14f); setTextColor(Color.WHITE)
        background = HudStyle.panel(act, 12f, rim ?: if (selected) HudStyle.INFO_BLUE else HudStyle.RIM, if (selected) 2.5f else 1.2f)
        setOnClickListener { action() }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(6f), 0, dp(6f), 0)
    }

    /** A row of equal-width buttons. */
    fun buttons(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (label, action) ->
            row.addView(button(label) { action() }, LinearLayout.LayoutParams(0, dp(50f), 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
        }
        content.addView(row)
    }

    /** One big highlighted button (used for "Buy me a coffee"). */
    fun bigButton(label: String, rim: Int, action: () -> Unit) {
        content.addView(button(label, rim = rim) { action() }.apply { sizeDp(16f) },
            LinearLayout.LayoutParams(-1, dp(54f)).apply { setMargins(dp(3f), dp(4f), dp(3f), dp(4f)) })
    }

    /** Pick one of several options; the chosen one is outlined in the accent colour. */
    fun choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, label ->
            row.addView(button(label, selected = i == selected) { onPick(i); refresh() },
                LinearLayout.LayoutParams(0, dp(46f), 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
        }
        content.addView(row)
    }

    /** Colour swatches with a label under each. */
    fun swatches(items: List<Pair<String, IntArray>>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (label, colors) ->
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { onPick(i); refresh() }
                setPadding(0, dp(4f), 0, dp(4f))
            }
            val sw = View(act).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (colors.size > 1) colors else intArrayOf(colors[0], colors[0])).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(if (i == selected) 3f else 1f), if (i == selected) Color.WHITE else Color.argb(120, 255, 255, 255))
                }
            }
            cell.addView(sw, LinearLayout.LayoutParams(dp(38f), dp(38f)))
            cell.addView(TextView(act).apply {
                text = label; setTextColor(if (i == selected) Color.WHITE else Color.rgb(160, 168, 180)); sizeDp(11f); gravity = Gravity.CENTER
                setPadding(0, dp(3f), 0, 0)
            })
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(row)
    }

    /** Pictures to pick from (e.g. the map marker), label under each, chosen one outlined. */
    fun pictures(items: List<Pair<String, android.graphics.Bitmap>>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (label, bmp) ->
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2f), dp(6f), dp(2f), dp(4f))
                background = HudStyle.panel(act, 12f, if (i == selected) HudStyle.INFO_BLUE else Color.argb(60, 255, 255, 255), if (i == selected) 2.5f else 1f)
                setOnClickListener { onPick(i); refresh() }
            }
            cell.addView(android.widget.ImageView(act).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            cell.addView(TextView(act).apply {
                text = label; setTextColor(if (i == selected) Color.WHITE else Color.rgb(160, 168, 180)); sizeDp(11f); gravity = Gravity.CENTER
                maxLines = 1
            })
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2f), dp(2f), dp(2f), dp(2f)) })
        }
        content.addView(row)
    }

    /** Slider 0–100 with a live value; [onChange] fires while dragging, [onDone] on release. */
    fun slider(label: String, value: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit) {
        val title = TextView(act).apply { setTextColor(Color.WHITE); sizeDp(14f); setPadding(dp(4f), dp(4f), 0, 0) }
        fun show(v: Int) { title.text = "$label: $v%" }
        show(value)
        content.addView(title)
        content.addView(SeekBar(act).apply {
            max = 100; progress = value
            progressTintList = ColorStateList.valueOf(HudStyle.INFO_BLUE)
            thumbTintList = ColorStateList.valueOf(HudStyle.INFO_BLUE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { show(progress); onChange(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onDone(seekBar?.progress ?: value) }
            })
        }, LinearLayout.LayoutParams(-1, dp(44f)))
    }
}
