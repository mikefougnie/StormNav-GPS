package com.stormnav.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Full-screen destination search: one big field, results as you type, recents, Home/Work.
 * Pressing Go on the keyboard finds the best match and starts the route right away.
 */
class SearchPanel(
    private val act: Activity,
    private val search: PlaceSearch,
    private val prefs: SharedPreferences,
    private val bias: () -> GeoPoint?,
    private val userTown: () -> String?,
    private val units: () -> UnitSystem,
    private val onGo: (PlaceResult) -> Unit,
    private val onDropPin: () -> Unit
) {
    val root = FrameLayout(act)
    private val column = LinearLayout(act)
    private val input = EditText(act)
    private val clear = TextView(act)
    private val chips = LinearLayout(act)
    private val status = TextView(act)
    private val list = LinearLayout(act)
    private val ui = Handler(Looper.getMainLooper())
    private var seq = 0
    private var lastQuery = ""
    private var lastResults: List<PlaceResult> = emptyList()
    private val pending = Runnable { runQuick() }

    val isOpen get() = root.visibility == View.VISIBLE

    /** Called after the panel opens or closes (used for Android 13+ back handling). */
    var onVisibilityChanged: (() -> Unit)? = null

    private fun dp(v: Float) = (v * act.resources.displayMetrics.density).toInt()
    private fun TextView.sizeDp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, v)
    private fun rounded(color: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(1.5f), stroke)
    }

    init {
        root.setBackgroundColor(Color.argb(246, 8, 11, 16))
        root.visibility = View.GONE
        root.isClickable = true // swallow touches meant for the map underneath
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))

        // Header: back, search pill, clear
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(act).apply {
            text = "‹"; setTextColor(Color.WHITE); sizeDp(40f); gravity = Gravity.CENTER
            setOnClickListener { close() }; contentDescription = "Close search"
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48f), dp(56f)))
        val pill = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(26, 31, 40), 28f, HudStyle.INFO_BLUE)
            setPadding(dp(16f), 0, dp(6f), 0)
        }
        pill.addView(TextView(act).apply { text = "🔍"; sizeDp(18f) })
        input.apply {
            background = null; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140, 150, 165)); sizeDp(19f)
            hint = "Where to? Try “Springfield Walmart”"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(10f), 0, dp(4f), 0)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { go(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onTyping() }
            })
        }
        pill.addView(input, LinearLayout.LayoutParams(0, dp(54f), 1f))
        clear.apply {
            text = "✕"; setTextColor(Color.rgb(170, 178, 190)); sizeDp(18f); gravity = Gravity.CENTER; visibility = View.GONE
            setOnClickListener { input.setText(""); showKeyboard() }
        }
        pill.addView(clear, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        header.addView(pill, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8f), 0) })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4f), dp(8f), dp(4f), dp(6f)) })

        // Chips: Home / Work / Drop a pin
        val chipScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
        chips.orientation = LinearLayout.HORIZONTAL
        chipScroll.addView(chips)
        column.addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12f), 0, dp(12f), dp(4f)) })

        status.apply { setTextColor(Color.rgb(150, 160, 175)); sizeDp(13f); setPadding(dp(16f), dp(6f), dp(16f), dp(6f)) }
        column.addView(status)

        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(8f), 0, dp(8f), dp(24f))
        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Keeps the panel clear of system bars and centred as a column on wide screens. */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        root.setPadding(left, top, right, bottom)
        val screenW = act.resources.displayMetrics.widthPixels - left - right
        column.layoutParams = FrameLayout.LayoutParams(min(screenW, dp(640f)), -1, Gravity.CENTER_HORIZONTAL)
    }

    fun open() {
        root.visibility = View.VISIBLE
        root.bringToFront()
        buildChips()
        if (input.text.isNullOrBlank()) showRecents()
        else if (lastResults.isNotEmpty() && QueryParse.clean(input.text.toString()) == lastQuery) {
            render(lastResults, "Other matches for “$lastQuery” — tap one to go there")
        } else onTyping()
        input.requestFocus()
        input.selectAll()
        ui.postDelayed({ showKeyboard() }, 150)
        onVisibilityChanged?.invoke()
    }

    fun close() {
        ui.removeCallbacks(pending); seq++
        hideKeyboard()
        root.visibility = View.GONE
        onVisibilityChanged?.invoke()
    }

    private fun showKeyboard() {
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun onTyping() {
        val q = QueryParse.clean(input.text.toString())
        clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        ui.removeCallbacks(pending)
        if (q.length < 3) { seq++; showRecents(); return }
        ui.postDelayed(pending, 350)
    }

    private fun runQuick() {
        val q = QueryParse.clean(input.text.toString())
        val mySeq = ++seq
        status.text = "Searching…"
        val b = bias()
        thread {
            val r = search.quick(q, b)
            ui.post { if (mySeq == seq && isOpen) render(r, if (r.isEmpty()) "Press Go to search harder, or add the town or ZIP" else "Press Go for the best match") }
        }
    }

    /** Thorough search, then route to the best match. */
    private fun go() {
        val q = QueryParse.clean(input.text.toString())
        if (q.isEmpty()) return
        ui.removeCallbacks(pending)
        val mySeq = ++seq
        hideKeyboard()
        status.text = "Finding the best match for “$q”…"
        val b = bias(); val town = userTown()
        thread {
            val r = search.full(q, b, town)
            ui.post {
                if (mySeq != seq || !isOpen) return@post
                lastQuery = q; lastResults = r
                if (r.isEmpty()) {
                    render(r, "No match for “$q”. Add the town or ZIP code, or use Drop a pin.")
                } else {
                    pick(r.first())
                }
            }
        }
    }

    private fun pick(r: PlaceResult) {
        addRecent(r)
        close()
        onGo(r)
    }

    // ------------------------------------------------------------ rows

    private fun render(results: List<PlaceResult>, message: String) {
        status.text = message
        list.removeAllViews()
        results.take(12).forEach { list.addView(row(it)) }
    }

    private fun icon(r: PlaceResult): String = when (r.kind) {
        PlaceKind.ADDRESS -> "🏠"; PlaceKind.STREET -> "🛣️"; PlaceKind.PLACE -> "🏙️"; PlaceKind.RECENT -> "🕘"
        PlaceKind.SAVED -> when (r.category) { "work" -> "💼"; "car" -> "🅿️"; else -> "🏠" }; PlaceKind.PIN -> "📍"
        PlaceKind.POI -> when (r.category) {
            "supermarket", "department_store", "mall", "convenience", "variety_store", "general" -> "🛒"
            "fuel" -> "⛽"; "restaurant", "fast_food" -> "🍔"; "cafe" -> "☕"; "hotel", "motel" -> "🏨"
            "hospital", "clinic", "doctors" -> "🏥"; "school", "university", "college" -> "🏫"
            "place_of_worship" -> "⛪"; "parking" -> "🅿️"; "bank", "atm" -> "🏦"; "pharmacy", "chemist" -> "💊"
            else -> "📍"
        }
    }

    private fun row(r: PlaceResult): View {
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(20, 255, 255, 255), 14f)
            setPadding(dp(12f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            setOnClickListener { pick(r) }
            setOnLongClickListener { offerSave(r); true }
        }
        v.addView(TextView(act).apply {
            text = icon(r); sizeDp(20f); gravity = Gravity.CENTER
            background = rounded(Color.argb(36, 41, 182, 246), 20f)
        }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(8f), 0) }
        texts.addView(TextView(act).apply {
            text = r.name; setTextColor(Color.WHITE); sizeDp(17f); typeface = HudStyle.BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (r.detail.isNotBlank()) texts.addView(TextView(act).apply {
            text = r.detail; setTextColor(Color.rgb(150, 160, 175)); sizeDp(13f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        v.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        bias()?.let { b ->
            v.addView(TextView(act).apply {
                text = Units.short(GeoMath.km(b, r) * 1000.0, if (units() == UnitSystem.BOTH) UnitSystem.IMPERIAL else units())
                setTextColor(Color.rgb(150, 160, 175)); sizeDp(13f)
            })
        }
        v.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3f), 0, dp(3f)) }
        return v
    }

    private fun chip(label: String, action: () -> Unit) = TextView(act).apply {
        text = label; setTextColor(Color.WHITE); sizeDp(14f); gravity = Gravity.CENTER
        background = rounded(Color.rgb(26, 31, 40), 18f, Color.argb(120, 160, 170, 180))
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8f), 0) }
    }

    private fun buildChips() {
        chips.removeAllViews()
        val home = saved("home"); val work = saved("work")
        chips.addView(chip(if (home != null) "🏠 Home" else "🏠 Set Home") {
            if (home != null) pick(home) else status.text = "Search for your home, then long-press it to save as Home"
        })
        chips.addView(chip(if (work != null) "💼 Work" else "💼 Set Work") {
            if (work != null) pick(work) else status.text = "Search for your work, then long-press it to save as Work"
        })
        parkedCar()?.let { car -> chips.addView(chip("🅿️ My car") { pick(car) }) }
        chips.addView(chip("📍 Drop a pin") { close(); onDropPin() })
    }

    private fun showRecents() {
        val recents = loadList("recents")
        render(recents, if (recents.isEmpty()) "Type an address, a place, or something like “Springfield Walmart”. Long-press a result to save it as Home or Work." else "Recent")
    }

    // ------------------------------------------------------------ storage

    private fun toJson(r: PlaceResult) = JSONObject().put("name", r.name).put("detail", r.detail).put("lat", r.lat).put("lon", r.lon).put("category", r.category)
    private fun fromJson(o: JSONObject, kind: PlaceKind) =
        PlaceResult(o.optString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"), kind, o.optString("category"))

    private fun loadList(key: String): List<PlaceResult> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { fromJson(a.getJSONObject(it), PlaceKind.RECENT) }
    }.getOrDefault(emptyList())

    private fun addRecent(r: PlaceResult) {
        if (r.kind == PlaceKind.SAVED) return
        val list = loadList("recents").filterNot { GeoMath.km(it.lat, it.lon, r.lat, r.lon) < 0.05 }
        val a = JSONArray(); a.put(toJson(r)); list.take(9).forEach { a.put(toJson(it)) }
        prefs.edit().putString("recents", a.toString()).apply()
    }

    /** The saved parking spot, if any. */
    private fun parkedCar(): PlaceResult? = runCatching {
        prefs.getString("parking", null)?.let { JSONObject(it) }?.let {
            PlaceResult("Your parked car", it.optString("address"), it.getDouble("lat"), it.getDouble("lon"), PlaceKind.SAVED, "car")
        }
    }.getOrNull()

    private fun saved(which: String): PlaceResult? = runCatching {
        prefs.getString(which, null)?.let { fromJson(JSONObject(it), PlaceKind.SAVED).copy(category = which) }
    }.getOrNull()

    private fun offerSave(r: PlaceResult) {
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle(r.name)
            .setItems(arrayOf("Save as Home", "Save as Work", "Go here")) { _, which ->
                when (which) {
                    0, 1 -> {
                        val key = if (which == 0) "home" else "work"
                        prefs.edit().putString(key, toJson(r).toString()).apply()
                        buildChips(); status.text = "Saved as ${if (which == 0) "Home" else "Work"}"
                    }
                    else -> pick(r)
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
