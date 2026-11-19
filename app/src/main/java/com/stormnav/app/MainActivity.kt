package com.stormnav.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.format.DateFormat
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences
    private var radarLayer: RasterLayer? = null
    private var radarOpacity = 0.62f
    private var radarVisible = true
    private var showGauges = true
    private var voiceOn = true
    private var stormAlertMode = "severe"
    private lateinit var menuPanel: SidePanel
    private var basemap = Basemap.SATELLITE
    private var unitSystem = UnitSystem.IMPERIAL
    private var lastLocation: Location? = null
    private var lastBearing = 0f
    private var followCamera = true
    private var northUp = false
    private var cursorPos = 0.8f // where "you" sit on screen when heading-up: 0.5 centre … 0.8 near the bottom

    // Current-condition warnings
    private var obsConditions: List<Condition> = emptyList()
    private var alertConditions: List<Condition> = emptyList()
    private var obsSource = ""
    private var conditionsAt: Location? = null
    private var conditionsInFlight = false
    private var conditionWarnings = true
    private val conditionSpokenAt = mutableMapOf<String, Long>()
    private var stationCache: Pair<String, Pair<String, String>>? = null
    private lateinit var conditionsChip: TextView
    private val conditionsCheck = object : Runnable {
        override fun run() { lastLocation?.let { runConditions(it) }; ui.postDelayed(this, 5 * 60_000L) }
    }
    private var overviewUntil = 0L
    private lateinit var recenterButton: TextView
    private val autoResume = Runnable { if (routeActive && !followCamera) recenter() }
    private val reportMarkers = mutableListOf<Marker>()
    private val reportIds = mutableMapOf<Marker, Int>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var routeSteps = mutableListOf<RouteStep>()
    private var currentStepIndex = 1
    private var lastAnnouncedStep = -1
    private var lastReminderStep = -1
    private var routeActive = false
    private var routeDestinationName = ""
    private var routeDestination: Place? = null
    private var routeGeometry: JSONArray? = null
    private var lastStormAlertSignature = ""
    private var offRouteFixes = 0
    private var offRoute = false
    private var rerouteInProgress = false
    private var lastRerouteAt = 0L
    private val defaultCenter = LatLng(39.8283, -98.5795) // middle of the US, until GPS finds you
    private val ui = Handler(Looper.getMainLooper())

    // Trip / weather state shown on the HUD
    private var nwsSummary = ""
    private var nwsDetails: List<String> = emptyList()
    private var destTempSummary = ""
    private var radarSummary = ""
    private var routePoints: List<GeoPoint> = emptyList()
    private var routeCum = DoubleArray(0)
    private var routeTotalM = 0.0
    private var routeAvgSpeedMps = 0.0
    private var radarSegments: List<StormSegment>? = null
    private var lastRadarSpoken = ""
    private var radarCheckGeneration = 0
    private var weatherNow: WeatherNow? = null
    private var weatherAt = 0L
    private var weatherLoc: Location? = null
    private var weatherInFlight = false
    private var hourlyUrlCache: Pair<String, String>? = null

    // Destination search
    private lateinit var placeSearch: PlaceSearch
    private lateinit var searchPanel: SearchPanel
    private var userTown: String? = null
    private var userTownAt: Location? = null
    private var pendingDestination: Place? = null

    // Storm assist: popup, detour, wait it out
    private var nwsSevereOnRoute = false
    private var lastStormPromptKey = ""
    private var stormDialogOpen = false
    private var routeVia: GeoPoint? = null
    private var routeViaAlongM = 0.0
    private var waitMode = false
    private var waitFinalDestination: Place? = null
    private var waitStopName = ""
    private var waitStartedAt = 0L
    private var routeNowcast: Nowcast? = null
    private var departHintShown = false

    // Storm watch (where you are, even parked)
    private var watchNowcast: Nowcast? = null
    private var watchCell: StormCell? = null
    private var watchArrival: Arrival? = null
    private var watchComputedAt = 0L
    private var watchInFlight = false
    private var watchAlertKey = ""
    private val stormWatch = object : Runnable {
        override fun run() { lastLocation?.let { runStormWatch(it) }; ui.postDelayed(this, WATCH_RECHECK_MS) }
    }

    // Storm chaser
    private var chaseMode = false
    private var chaseCell: StormCell? = null
    private var chaseMotion: Motion? = null
    private var chaseUpdatedAt = 0L
    private var chaseMarker: Marker? = null
    private var chaseGrid: LevelGrid? = null
    private var chaseBufferMi = 2

    // Map marker and voice
    private var markerStyle = MarkerStyle.ARROW
    private var speechRate = 1.0f

    // Parking spot
    private var parkingMarker: Marker? = null
    private var autoPark = true
    private var carStormKey = ""
    private val chaseCheck = object : Runnable {
        override fun run() { if (chaseMode) { updateChase(); ui.postDelayed(this, CHASE_RECHECK_MS) } }
    }
    private val waitCheck = object : Runnable {
        override fun run() { if (waitMode) { checkWaitClear(); ui.postDelayed(this, WAIT_RECHECK_MS) } }
    }

    // HUD views
    private lateinit var hudRoot: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var maneuverIcon: ManeuverIconView
    private lateinit var bannerDistance: TextView
    private lateinit var bannerInstruction: TextView
    private lateinit var remainTime: TextView
    private lateinit var remainDist: TextView
    private lateinit var leftColumn: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var rainGauge: GaugeView
    private lateinit var windGauge: GaugeView
    private lateinit var tempGauge: GaugeView
    private lateinit var speedo: SpeedometerView
    private lateinit var etaCard: LinearLayout
    private lateinit var etaValue: TextView
    private lateinit var stormCard: LinearLayout
    private lateinit var stormValue: TextView
    private lateinit var stormTitle: TextView
    private lateinit var statusChip: TextView
    private lateinit var menuButton: TextView
    private lateinit var radarBadge: TextView
    private lateinit var infoGlyph: GlyphView
    private lateinit var carGlyph: GlyphView
    private lateinit var flagGlyph: GlyphView
    private lateinit var boltGlyph: GlyphView
    private lateinit var etaLabel: TextView
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    private val radarRecheck = object : Runnable {
        override fun run() {
            if (routeActive) { checkRadarAhead(); ui.postDelayed(this, RADAR_RECHECK_MS) }
        }
    }
    private val weatherRecheck = object : Runnable {
        override fun run() { lastLocation?.let { refreshWeather(it, force = true) }; ui.postDelayed(this, WEATHER_RECHECK_MS) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private data class Place(val name: String, val lat: Double, val lon: Double)
    private data class RouteStep(val lat: Double, val lon: Double, val instruction: String, val distanceMeters: Double, val kind: ManeuverKind)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        prefs = getSharedPreferences("stormnav", Context.MODE_PRIVATE)
        radarOpacity = prefs.getFloat("radarOpacity", 0.62f)
        basemap = runCatching { Basemap.valueOf(prefs.getString("basemap", Basemap.SATELLITE.name)!!) }.getOrDefault(Basemap.SATELLITE)
        unitSystem = runCatching { UnitSystem.valueOf(prefs.getString("units", UnitSystem.IMPERIAL.name)!!) }.getOrDefault(UnitSystem.IMPERIAL)
        radarVisible = prefs.getBoolean("radarVisible", true)
        showGauges = prefs.getBoolean("showGauges", true)
        voiceOn = prefs.getBoolean("voiceOn", true)
        chaseBufferMi = prefs.getInt("chaseBufferMi", 2)
        radarRefreshS = prefs.getInt("radarRefreshS", 30)
        markerStyle = runCatching { MarkerStyle.valueOf(prefs.getString("markerStyle", MarkerStyle.ARROW.name)!!) }.getOrDefault(MarkerStyle.ARROW)
        speechRate = prefs.getFloat("speechRate", 1.0f)
        autoPark = prefs.getBoolean("autoPark", true)
        northUp = prefs.getBoolean("northUp", false)
        cursorPos = prefs.getFloat("cursorPos", 0.8f)
        conditionWarnings = prefs.getBoolean("conditionWarnings", true)
        trafficOn = prefs.getBoolean("trafficOn", true)
        stormAlertMode = prefs.getString("stormAlerts", "severe") ?: "severe"
        HudStyle.setTheme(prefs.getString("theme", null)); HudStyle.setRoute(prefs.getString("routeColor", null))
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        tts = prefs.getString("ttsEngine", null)?.let { TextToSpeech(this, this, it) } ?: TextToSpeech(this, this)

        hudRoot = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 20, 29)) }
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        hudRoot.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        buildHud()
        placeSearch = PlaceSearch { url -> httpText(url) }
        searchPanel = SearchPanel(this, placeSearch, prefs, { searchBias() }, { userTown }, { unitSystem },
            onGo = { goTo(it) }, onDropPin = { startDropPin() })
        hudRoot.addView(searchPanel.root, FrameLayout.LayoutParams(-1, -1))
        menuPanel = SidePanel(this)
        hudRoot.addView(menuPanel.root, FrameLayout.LayoutParams(-1, -1))
        searchPanel.onVisibilityChanged = { updateBackHandling() }
        menuPanel.onVisibilityChanged = { updateBackHandling() }
        if (!showGauges) leftColumn.visibility = View.GONE
        // targetSdk 35 draws edge-to-edge: keep the HUD clear of the status and navigation bars.
        hudRoot.setOnApplyWindowInsetsListener { _, insets ->
            insetTop = insets.systemWindowInsetTop; insetBottom = insets.systemWindowInsetBottom
            insetLeft = insets.systemWindowInsetLeft; insetRight = insets.systemWindowInsetRight
            searchPanel.setInsets(insetLeft, insetTop, insetRight, insets.systemWindowInsetBottom)
            menuPanel.setInsets(insetLeft, insetTop, insetRight, insets.systemWindowInsetBottom)
            layoutHud()
            insets
        }
        setContentView(hudRoot)
        layoutHud()
        renderHud()

        // Friendly, rare "buy me a coffee" card on opening (never while a route is active).
        ui.postDelayed({ maybeAskForCoffee() }, 5000)

        mapView.getMapAsync { m ->
            map = m
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = true
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
            map.uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
            map.addOnMapLongClickListener { point -> confirmPin(point); true }
            map.setOnMarkerClickListener { marker ->
                if (reportIds.containsKey(marker)) {
                    showReportActions(reportIds[marker]!!, marker.title ?: "Road report")
                    true
                } else false
            }
            // Dragging the map pauses camera-follow; the Menu has "My location" to resume.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) pauseFollow()
            }
            map.cameraPosition = CameraPosition.Builder().target(defaultCenter).zoom(3.5).build()
            loadBasemap()
        }
    }

    private companion object {
        const val RADAR_WMS = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows" +
            "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=conus_bref_qcd" +
            "&STYLES=&FORMAT=image/png&TRANSPARENT=true&SRS=EPSG:3857" +
            "&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}"
        const val RADAR_WMS_BASE = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows" +
            "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=conus_bref_qcd" +
            "&STYLES=&FORMAT=image/png&TRANSPARENT=true&SRS=EPSG:3857"
        const val RADAR_RECHECK_MS = 4 * 60 * 1000L
        const val RADAR_CAPS = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities"
        const val WATCH_RECHECK_MS = 5 * 60 * 1000L
        const val CHASE_RECHECK_MS = 2 * 60 * 1000L
        const val WEATHER_RECHECK_MS = 10 * 60 * 1000L
        const val WAIT_RECHECK_MS = 3 * 60 * 1000L
                const val NWS_ALERTS = "https://api.weather.gov/alerts/active?point="
        const val ROUTE_SOURCE_ID = "stormnav-route-source"
        const val ROUTE_CASING_ID = "stormnav-route-casing"
        const val ROUTE_LAYER_ID = "stormnav-route-line"
        const val ME_SOURCE_ID = "stormnav-me-source"
        const val ME_LAYER_ID = "stormnav-me-layer"
        const val ME_ICON = "stormnav-nav-arrow"
        const val UA = "StormNav/0.5 open-source weather navigation (github.com/stormnav)"
    }

    // ---------------------------------------------------------------- map style

    private fun loadBasemap() {
        if (!::map.isInitialized) return
        val builder = if (basemap == Basemap.SATELLITE) Style.Builder().fromJson(MapStyles.satelliteJson)
            else Style.Builder().fromUri(MapStyles.DARK_URL)
        map.setStyle(builder) { style -> onStyleReady(style) }
    }

    /** Everything StormNav draws on the map; re-run whenever the basemap changes. */
    private fun onStyleReady(style: Style) {
        addRadarOverlay(style)
        // A new style starts empty: put traffic back (or check it for the first time).
        trafficLayerIds = emptyList(); trafficSourceIds = emptyList()
        if (trafficStatus == Traffic.Status.OK) showTrafficLayers() else { ui.removeCallbacks(trafficPulse); ui.postDelayed(trafficPulse, 1500) }
        routeGeometry?.let { drawRoute(it) }
        addUserArrow(style)
        lastLocation?.let { updateUserArrow(it) }
        showParkingMarker()
        refreshReports()
        requestGps()
    }

    private var radarSourceId = ""

    /**
     * Adds the radar layer for frame [timeMs] (0 = whatever is newest) beneath the roads. A
     * specific frame time in the URL means a new frame always gets new tiles: the map can't
     * keep showing a stale cached image.
     */
    private fun addRadarOverlay(style: Style, timeMs: Long = shownRadarTime): RasterLayer? {
        return try {
            val id = "stormnav-radar-${if (timeMs > 0) timeMs else System.currentTimeMillis()}"
            val url = RADAR_WMS + (if (timeMs > 0) "&TIME=" + StormMotion.isoUtc(timeMs) else "")
            style.addSource(RasterSource(id, TileSet("2.2.0", url), 256))
            val layer = RasterLayer("$id-layer", id).withProperties(
                PropertyFactory.rasterOpacity(radarOpacity),
                PropertyFactory.rasterFadeDuration(0f),
                PropertyFactory.visibility(if (radarVisible && radarOpacity > 0f) Property.VISIBLE else Property.NONE)
            )
            // Style layer ids differ between basemaps, so look for several road-ish names and
            // fall back to "below the first label layer" rather than hiding the radar.
            val roadWords = listOf("road", "transportation", "highway", "bridge", "tunnel", "street", "motorway")
            val anchor = style.layers.firstOrNull { l ->
                val lid = l.id.lowercase()
                roadWords.any { lid.contains(it) }
            } ?: style.layers.firstOrNull { it is SymbolLayer }
            if (anchor != null) style.addLayerBelow(layer, anchor.id) else style.addLayer(layer)
            radarLayer = layer
            radarSourceId = id
            layer
        } catch (_: Exception) {
            Toast.makeText(this, "Radar unavailable. Check connection or NOAA service status.", Toast.LENGTH_LONG).show()
            null
        }
    }

    /** Shows a newer radar frame: the new layer goes on top, the old one is removed once tiles have loaded. */
    private fun swapRadarFrame(timeMs: Long) {
        if (!::map.isInitialized) return
        val style = map.style ?: return
        val oldSource = radarSourceId
        addRadarOverlay(style, timeMs) ?: return
        if (oldSource.isNotEmpty() && oldSource != radarSourceId) ui.postDelayed({
            val st = if (::map.isInitialized) map.style else null
            try {
                if (st?.getLayer("$oldSource-layer") != null) st.removeLayer("$oldSource-layer")
                if (st?.getSource(oldSource) != null) st.removeSource(oldSource)
            } catch (_: Exception) { }
        }, 3_000)
    }

    // ---------------------------------------------------------------- radar refresh

    /** Newest radar frame time shown on the map (epoch ms). */
    private var shownRadarTime = 0L
    private var radarRefreshS = 30
    private var lastRouteCheckAt = 0L
    private var lastChaseCheckAt = 0L
    private val radarPulse = object : Runnable {
        override fun run() { pollRadar(); ui.postDelayed(this, radarRefreshS * 1000L) }
    }

    /** Cheap check (a small XML list) for a new NOAA frame; when one appears, refresh everything. */
    private fun pollRadar() {
        thread {
            val times = runCatching { StormMotion.timesFromCapabilities(httpText(RADAR_CAPS)) }.getOrNull()
            if (times.isNullOrEmpty()) return@thread
            radarTimes = times; radarTimesAt = System.currentTimeMillis()
            val newest = times.max()
            runOnUiThread {
                if (newest > shownRadarTime) {
                    shownRadarTime = newest
                    swapRadarFrame(newest)
                    onNewRadarFrame()
                }
                renderHud()
            }
        }
    }

    private fun onNewRadarFrame() {
        val now = System.currentTimeMillis()
        if (routeActive && now - lastRouteCheckAt > 45_000) checkRadarAhead()
        if (chaseMode && now - lastChaseCheckAt > 45_000) updateChase()
        if (!routeActive && !chaseMode && now - watchComputedAt > 45_000) lastLocation?.let { runStormWatch(it) }
    }

    private fun radarAgeText(): String {
        if (shownRadarTime <= 0) return ""
        val at = TripMath.clock(shownRadarTime, DateFormat.is24HourFormat(this))
        val min = ((System.currentTimeMillis() - shownRadarTime) / 60_000.0).toInt()
        return "📡 Radar $at · " + if (min <= 0) "just now" else "$min min ago"
    }

    private fun addUserArrow(style: Style) {
        try {
            style.addImage(ME_ICON, markerImage())
            style.addSource(GeoJsonSource(ME_SOURCE_ID))
            style.addLayer(SymbolLayer(ME_LAYER_ID, ME_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(ME_ICON),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            ))
        } catch (_: Exception) { }
    }

    private fun updateUserArrow(location: Location) {
        if (!::map.isInitialized) return
        val src = map.style?.getSourceAs<GeoJsonSource>(ME_SOURCE_ID) ?: return
        val f = JSONObject().put("type", "Feature")
            .put("properties", JSONObject().put("bearing", lastBearing.toDouble()))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(location.longitude).put(location.latitude)))
        src.setGeoJson(f.toString())
    }

    // ---------------------------------------------------------------- HUD layout

    private fun hudText(size: Float, bold: Boolean = true, color: Int = Color.WHITE) = TextView(this).apply {
        textSize = size; setTextColor(color)
        typeface = if (bold) HudStyle.BOLD else HudStyle.MEDIUM
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
        includeFontPadding = false
        maxLines = 1
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.argb(110, 160, 170, 180)) }

    private fun buildHud() {
        // Top banner: [arrow][distance] | [instruction][info] | [car][time / distance left]
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = HudStyle.panel(this@MainActivity, 18f)
            setPadding(dp(10), dp(4), dp(12), dp(4))
            setOnClickListener { if (routeActive || chaseMode || waitMode) showTripActions() else showDestinationDialog() }
        }
        maneuverIcon = ManeuverIconView(this)
        bannerDistance = hudText(30f)
        bannerInstruction = hudText(22f).apply { ellipsize = TextUtils.TruncateAt.END; maxLines = 2 }
        infoGlyph = GlyphView(this, GlyphView.Glyph.INFO).apply { setOnClickListener { showTripDetails() } }
        carGlyph = GlyphView(this, GlyphView.Glyph.CAR)
        remainTime = hudText(19f); remainDist = hudText(19f)
        val remain = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; addView(remainTime); addView(remainDist) }
        banner.addView(maneuverIcon)
        banner.addView(bannerDistance)
        banner.addView(divider(), LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(0, dp(8), 0, dp(8)) })
        banner.addView(bannerInstruction)
        banner.addView(infoGlyph)
        banner.addView(divider(), LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(0, dp(8), 0, dp(8)) })
        banner.addView(carGlyph)
        banner.addView(remain)
        hudRoot.addView(banner)

        // Left: rain / wind / temperature gauges
        leftColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rainGauge = GaugeView(this, GaugeView.Kind.RAIN).apply { label = "Rain Prob." }
        windGauge = GaugeView(this, GaugeView.Kind.WIND).apply { label = "Wind" }
        tempGauge = GaugeView(this, GaugeView.Kind.TEMP).apply { label = "Temp" }
        listOf(rainGauge, windGauge, tempGauge).forEach { g ->
            g.setOnClickListener { showWeatherDetails() }
            leftColumn.addView(g)
        }
        // The middle gauge overlaps its neighbours' rims, as in the design.
        windGauge.translationZ = 2f
        hudRoot.addView(leftColumn)

        // Right: speedometer, ETA, storm timer
        rightColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        speedo = SpeedometerView(this)
        etaCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = HudStyle.panel(this@MainActivity, 14f); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        etaValue = hudText(26f)
        val etaText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            etaLabel = hudText(14f).apply { text = "ETA" }
            addView(etaLabel); addView(etaValue)
        }
        flagGlyph = GlyphView(this, GlyphView.Glyph.FLAG)
        etaCard.addView(flagGlyph)
        etaCard.addView(etaText, LinearLayout.LayoutParams(0, -2, 1f))
        stormCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = HudStyle.panel(this@MainActivity, 14f, HudStyle.STORM_PURPLE, 3f); setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { when { chaseMode -> showChaseInfo(); routeActive || waitMode -> showStormOptions(auto = false); else -> showStormWatch() } }
        }
        stormTitle = hudText(15f).apply { text = "Storm Timer" }
        stormValue = hudText(26f, color = HudStyle.STORM_YELLOW)
        val stormText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(stormTitle); addView(stormValue)
        }
        boltGlyph = GlyphView(this, GlyphView.Glyph.BOLT)
        stormCard.addView(boltGlyph)
        stormCard.addView(stormText, LinearLayout.LayoutParams(0, -2, 1f))
        rightColumn.addView(speedo); rightColumn.addView(etaCard); rightColumn.addView(stormCard)
        hudRoot.addView(rightColumn)

        // Bottom: status line (radar / NWS summary) and the Menu pill
        statusChip = hudText(12f, bold = false).apply {
            background = HudStyle.panel(this@MainActivity, 12f, Color.argb(90, 160, 170, 180), 1f)
            setPadding(dp(10), dp(4), dp(10), dp(4)); gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
            setOnClickListener { showTripDetails() }
        }
        hudRoot.addView(statusChip)
        menuButton = hudText(22f).apply {
            text = "☰   Menu"; gravity = Gravity.CENTER
            background = HudStyle.panel(this@MainActivity, 28f)
            setPadding(dp(26), dp(10), dp(30), dp(10))
            setOnClickListener { showMenu() }
        }
        hudRoot.addView(menuButton)
        recenterButton = hudText(17f).apply {
            text = "⌖ Re-center"; gravity = Gravity.CENTER
            background = HudStyle.panel(this@MainActivity, 24f, HudStyle.INFO_BLUE, 2f)
            setPadding(dp(16), dp(10), dp(18), dp(10))
            visibility = View.GONE
            setOnClickListener { recenter() }
        }
        hudRoot.addView(recenterButton)
        radarBadge = hudText(11f, bold = false).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = HudStyle.panel(this@MainActivity, 10f, Color.argb(80, 160, 170, 180), 1f)
            visibility = View.GONE
        }
        hudRoot.addView(radarBadge)
        conditionsChip = hudText(14f).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5)); gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
            setOnClickListener { showConditions() }
        }
        hudRoot.addView(conditionsChip)
    }

    /** Sizes the HUD from the screen so it fits a phone in portrait or a car mount in landscape. */
    private fun layoutHud() {
        val dm = resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val hDp = dm.heightPixels / dm.density
        if (wDp > hDp) layoutLandscape(wDp, hDp) else layoutPortrait(wDp, hDp)
        speedo.unit = Units.speedUnit(unitSystem)
        speedo.maxValue = if (unitSystem == UnitSystem.METRIC) 200 else 120
        hudRoot.requestLayout()
    }

    /** Text size in dp, so a large system font setting can't push HUD text out of its panels. */
    private fun TextView.sizeDp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, v)

    private fun sizeBanner(icon: Float, info: Float, carW: Float, distDp: Float, instDp: Float, remainDp: Float, gap: Float, showExtras: Boolean) {
        maneuverIcon.layoutParams = LinearLayout.LayoutParams(dpf(icon), dpf(icon))
        bannerDistance.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dpf(gap * 0.6f), 0, dpf(gap), 0) }
        bannerInstruction.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dpf(gap), 0, dpf(gap * 0.6f), 0) }
        infoGlyph.layoutParams = LinearLayout.LayoutParams(dpf(info), dpf(info)).apply { setMargins(0, 0, dpf(gap), 0) }
        carGlyph.layoutParams = LinearLayout.LayoutParams(dpf(carW), dpf(carW * 0.8f)).apply { setMargins(dpf(gap), 0, dpf(gap * 0.6f), 0) }
        infoGlyph.visibility = if (showExtras) View.VISIBLE else View.GONE
        carGlyph.visibility = infoGlyph.visibility
        bannerDistance.sizeDp(distDp); bannerInstruction.sizeDp(instDp)
        remainTime.sizeDp(remainDp); remainDist.sizeDp(remainDp)
    }

    /**
     * Landscape proportions measured from the design mockup. u = 1 % of the usable screen
     * height (capped by width on squarish tablets), so the HUD scales the same on any phone.
     */
    private fun layoutLandscape(wDp: Float, hDp: Float) {
        val d = resources.displayMetrics.density
        val usableH = hDp - (insetTop + insetBottom) / d
        val usableW = wDp - (insetLeft + insetRight) / d
        val u = min(usableH / 100f, usableW / 166f)
        fun px(units: Float) = dpf(units * u)
        val gauge = 32.5f * u

        banner.layoutParams = FrameLayout.LayoutParams(-1, px(12.5f), Gravity.TOP).apply {
            setMargins(insetLeft + px(30f), insetTop + px(1.2f), insetRight + px(1.3f), 0)
        }
        banner.setPadding(px(8f), 0, px(1.6f), 0)
        banner.background = HudStyle.panel(this, 2.6f * u)
        sizeBanner(9f * u, 6.6f * u, 7.2f * u, 7.2f * u, 5.6f * u, 4.4f * u, 1.6f * u, showExtras = true)

        leftColumn.layoutParams = FrameLayout.LayoutParams(dpf(gauge), -2, Gravity.TOP or Gravity.START).apply {
            setMargins(insetLeft + px(2.4f), insetTop + px(5.5f), 0, 0)
        }
        listOf(rainGauge, windGauge, tempGauge).forEach {
            it.layoutParams = LinearLayout.LayoutParams(dpf(gauge), dpf(gauge)).apply { setMargins(0, 0, 0, -px(2.2f)) }
        }
        // The banner starts under the first gauge, so the gauges draw on top of it.
        hudRoot.bringChildToFront(leftColumn)

        rightColumn.gravity = Gravity.CENTER_HORIZONTAL
        rightColumn.layoutParams = FrameLayout.LayoutParams(px(44f), -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, insetTop + px(15.2f), insetRight + px(1.5f), 0)
        }
        speedo.layoutParams = LinearLayout.LayoutParams(px(42f), px(42f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        etaCard.layoutParams = LinearLayout.LayoutParams(-1, px(15.7f)).apply { setMargins(0, px(1.4f), 0, 0) }
        stormCard.layoutParams = LinearLayout.LayoutParams(-1, px(17.8f)).apply { setMargins(0, px(1.4f), 0, 0) }
        etaCard.setPadding(px(2.2f), 0, px(2.2f), 0); stormCard.setPadding(px(2.2f), 0, px(2.2f), 0)
        etaCard.background = HudStyle.panel(this, 2.4f * u)
        stormCard.background = HudStyle.panel(this, 2.4f * u, HudStyle.STORM_PURPLE, 3f)
        flagGlyph.layoutParams = LinearLayout.LayoutParams(px(9f), px(9f))
        boltGlyph.layoutParams = LinearLayout.LayoutParams(px(7.5f), px(10f))
        etaLabel.sizeDp(3.8f * u); etaValue.sizeDp(6.8f * u)
        stormTitle.sizeDp(4.1f * u); stormValue.sizeDp(7.2f * u)

        menuButton.sizeDp(6f * u)
        menuButton.setPadding(px(4.5f), 0, px(5f), 0)
        menuButton.background = HudStyle.panel(this, 7f * u)
        menuButton.layoutParams = FrameLayout.LayoutParams(-2, px(13.5f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, insetBottom + px(2.3f))
        }
        recenterButton.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, insetBottom + px(3.5f))
        }
        recenterButton.sizeDp(5f * u)
        placeRecenter()
        radarBadge.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, insetTop + px(15f), 0, 0)
        }
        conditionsChip.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(px(36f), insetTop + px(21f), px(48f), 0)
        }
        conditionsChip.sizeDp(4.2f * u)
        statusChip.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(px(50f), 0, px(50f), insetBottom + px(17.5f))
        }
    }

    private fun layoutPortrait(wDp: Float, hDp: Float) {
        val d = resources.displayMetrics.density
        val bannerH = 64f
        val gauge = min(min(wDp * 0.25f, (hDp - bannerH - 40f - (insetTop + insetBottom) / d) / 3.05f), 150f)
        val speedSize = gauge * 1.32f
        val gap = 6

        banner.layoutParams = FrameLayout.LayoutParams(-1, dpf(bannerH), Gravity.TOP).apply {
            setMargins(dp(8) + insetLeft, dp(6) + insetTop, dp(8) + insetRight, 0)
        }
        banner.setPadding(dp(8), 0, dp(10), 0)
        banner.background = HudStyle.panel(this, 18f)
        sizeBanner(40f, 0f, 0f, 22f, 16f, 13f, 8f, showExtras = false)

        leftColumn.layoutParams = FrameLayout.LayoutParams(dpf(gauge), -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(4) + insetLeft, dp(6) + insetTop + dpf(bannerH) + dp(gap), 0, 0)
        }
        listOf(rainGauge, windGauge, tempGauge).forEach {
            it.layoutParams = LinearLayout.LayoutParams(dpf(gauge), dpf(gauge)).apply { setMargins(0, 0, 0, -dp(3)) }
        }

        rightColumn.gravity = Gravity.END
        rightColumn.layoutParams = FrameLayout.LayoutParams(dpf(speedSize * 1.05f), -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(6) + insetTop + dpf(bannerH) + dp(gap), dp(8) + insetRight, 0)
        }
        speedo.layoutParams = LinearLayout.LayoutParams(dpf(speedSize), dpf(speedSize)).apply { gravity = Gravity.END }
        etaCard.layoutParams = LinearLayout.LayoutParams(-1, dpf(gauge * 0.56f)).apply { setMargins(0, dp(gap), 0, 0) }
        stormCard.layoutParams = LinearLayout.LayoutParams(-1, dpf(gauge * 0.62f)).apply { setMargins(0, dp(gap), 0, 0) }
        etaCard.setPadding(dp(10), dp(4), dp(10), dp(4)); stormCard.setPadding(dp(8), dp(4), dp(10), dp(4))
        etaCard.background = HudStyle.panel(this, 14f)
        stormCard.background = HudStyle.panel(this, 14f, HudStyle.STORM_PURPLE, 3f)
        flagGlyph.layoutParams = LinearLayout.LayoutParams(dpf(gauge * 0.3f), dpf(gauge * 0.3f))
        boltGlyph.layoutParams = LinearLayout.LayoutParams(dpf(gauge * 0.26f), dpf(gauge * 0.36f))
        val cardScale = (gauge / 100f).coerceIn(0.75f, 1.3f)
        etaLabel.sizeDp(13f * cardScale); etaValue.sizeDp(22f * cardScale)
        stormTitle.sizeDp(13f * cardScale); stormValue.sizeDp(24f * cardScale)

        menuButton.sizeDp(22f)
        menuButton.setPadding(dp(26), dp(10), dp(30), dp(10))
        menuButton.background = HudStyle.panel(this, 28f)
        menuButton.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, dp(10) + insetBottom)
        }
        // Portrait is too narrow to sit beside Menu: bottom-left corner, above the map credits.
        recenterButton.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(10) + insetLeft, 0, 0, dp(44) + insetBottom)
        }
        recenterButton.sizeDp(16f)
        recenterButton.translationX = 0f
        radarBadge.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, dp(6) + insetTop + dpf(bannerH) + dp(6), 0, 0)
        }
        conditionsChip.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dpf(gauge) + dp(10), dp(6) + insetTop + dpf(bannerH) + dp(34), dpf(speedSize) + dp(10), 0)
        }
        conditionsChip.sizeDp(13f)
        statusChip.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            val side = dpf(gauge) + dp(12)
            setMargins(side, 0, side, dp(70) + insetBottom)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutHud()
        if (::menuPanel.isInitialized && menuPanel.isOpen) menuPanel.open { p -> buildMenu(p) }
    }

    /** Refreshes every HUD value from current state. Cheap; call on each GPS fix or data change. */
    private fun renderHud() {
        val loc = lastLocation
        val progress = if (routeActive && loc != null && routePoints.size >= 2)
            RouteMath.progressAlong(routePoints, routeCum, GeoPoint(loc.latitude, loc.longitude)) else 0.0
        val gpsSpeed = if (loc != null && loc.hasSpeed()) loc.speed.toDouble() else null
        val planSpeed = TripMath.planningSpeed(gpsSpeed, routeAvgSpeedMps)

        // Banner
        if (routeActive && currentStepIndex in routeSteps.indices) {
            val step = routeSteps[currentStepIndex]
            maneuverIcon.kind = if (offRoute) ManeuverKind.UTURN else step.kind
            val d = if (loc != null) {
                val out = FloatArray(1); Location.distanceBetween(loc.latitude, loc.longitude, step.lat, step.lon, out); out[0].toDouble()
            } else step.distanceMeters
            bannerDistance.text = Units.short(d, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem)
            if (!offRoute) bannerInstruction.text = step.instruction
            val remainM = TripMath.remainingMeters(routeTotalM, progress)
            val remainS = TripMath.remainingSeconds(remainM, planSpeed) + trafficDelayAhead(progress, remainM)
            checkIncidentProximity(progress)
            remainTime.text = TripMath.duration(remainS)
            remainDist.text = Units.short(remainM, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem)
            etaValue.text = TripMath.clock(System.currentTimeMillis() + (remainS * 1000).toLong(), DateFormat.is24HourFormat(this))
        } else if (!routeActive) {
            maneuverIcon.kind = ManeuverKind.DEPART
            bannerDistance.text = "--"
            if (bannerInstruction.text.isNullOrBlank() || bannerInstruction.text == "Navigation stopped") bannerInstruction.text = "Where to? Tap to search"
            remainTime.text = "-- min"; remainDist.text = "-- " + (if (unitSystem == UnitSystem.METRIC) "km" else "mi")
            etaValue.text = "--:--"
        }

        // Speed
        speedo.speed = gpsSpeed?.let { Units.speedValue(it, unitSystem) }

        // Gauges
        val w = weatherNow
        rainGauge.valueText = w?.rainChancePct?.let { "$it%" } ?: "--"
        rainGauge.fraction = w?.rainChancePct?.let { it / 100f }
        windGauge.valueText = w?.windMph?.let { Units.wind(it, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem) } ?: "--"
        windGauge.fraction = w?.windMph?.let { (it / 50.0).toFloat() }
        tempGauge.valueText = w?.tempF?.let { Units.temp(it, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem) } ?: "--"
        tempGauge.fraction = w?.tempF?.let { ((it - 0.0) / 110.0).toFloat() }

        // Storm timer
        val chase = chaseCell
        if (chaseMode && chase != null) {
            val d = lastLocation?.let { RouteMath.haversine(GeoPoint(it.latitude, it.longitude), chaseNow(chase)) }
            stormTitle.text = "🌪 Core"; stormValue.text = d?.let { dist(it) } ?: "…"; stormValue.setTextColor(Color.rgb(255, 82, 82))
        } else if (waitMode) {
            stormTitle.text = "Waiting out"; stormValue.text = "storm"; stormValue.setTextColor(HudStyle.STORM_YELLOW)
        } else if (!routeActive) {
            val arr = watchArrival
            val elapsed = (System.currentTimeMillis() - watchComputedAt) / 1000.0
            when {
                arr != null && arr.startS - elapsed <= 60 && arr.endS - elapsed > 0 -> { stormTitle.text = "Storm here"; stormValue.text = "NOW"; stormValue.setTextColor(Color.rgb(255, 82, 82)) }
                arr != null && arr.startS - elapsed > 60 -> { stormTitle.text = "Storm here in"; stormValue.text = TripMath.duration(arr.startS - elapsed); stormValue.setTextColor(HudStyle.STORM_YELLOW) }
                watchCell != null && lastLocation != null -> {
                    stormTitle.text = "Nearest storm"
                    stormValue.text = dist(RouteMath.haversine(GeoPoint(lastLocation!!.latitude, lastLocation!!.longitude), watchCell!!.center))
                    stormValue.setTextColor(HudStyle.STORM_YELLOW)
                }
                watchNowcast != null -> { stormTitle.text = "Storm Timer"; stormValue.text = "Clear"; stormValue.setTextColor(Color.rgb(105, 240, 174)) }
                else -> { stormTitle.text = "Storm Timer"; stormValue.text = "--"; stormValue.setTextColor(Color.rgb(180, 180, 190)) }
            }
        } else when (val t = TripMath.stormTimer(radarSegments, progress, planSpeed, routeActive)) {
            is StormTimer.Minutes -> { stormTitle.text = "Storm Timer"; stormValue.text = "${t.minutes} min"; stormValue.setTextColor(HudStyle.STORM_YELLOW) }
            is StormTimer.Inside -> { stormTitle.text = "In storm"; stormValue.text = "NOW"; stormValue.setTextColor(Color.rgb(255, 82, 82)) }
            StormTimer.Clear -> { stormTitle.text = "Storm Timer"; stormValue.text = "Clear"; stormValue.setTextColor(Color.rgb(105, 240, 174)) }
            StormTimer.Unknown -> { stormTitle.text = "Storm Timer"; stormValue.text = "…"; stormValue.setTextColor(HudStyle.STORM_YELLOW) }
            StormTimer.NoRoute -> { stormTitle.text = "Storm Timer"; stormValue.text = "--"; stormValue.setTextColor(Color.rgb(180, 180, 190)) }
        }

        // Status line
        val status = if (chaseMode) chaseStatus() else if (waitMode) "⏸ Waiting out the storm${if (waitStopName.isNotBlank()) " at $waitStopName" else ""} · radar checked every 3 min"
            else listOf(radarSummary, nwsSummary).filter { it.isNotBlank() }.joinToString("  •  ")
        statusChip.text = status
        val age = radarAgeText()
        renderConditions()
        radarBadge.text = age
        radarBadge.visibility = if (age.isBlank()) View.GONE else View.VISIBLE
        // Older than ~10 minutes means NOAA or the connection is behind: show it in amber.
        radarBadge.setTextColor(if (shownRadarTime > 0 && System.currentTimeMillis() - shownRadarTime > 10 * 60_000) Color.rgb(255, 193, 7) else Color.WHITE)
        statusChip.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setBannerMessage(text: String) { bannerInstruction.text = text }

    // ---------------------------------------------------------------- traffic (TomTom)

    private var trafficOn = true
    private var trafficStatus = Traffic.Status.NO_KEY
    private var trafficStamp = 0L
    private var trafficInFlight = false
    private var trafficLayerIds = listOf<String>()
    private var trafficSourceIds = listOf<String>()

    /** A key pasted in the Menu wins; otherwise the one built into the app (blank by default). */
    private fun trafficKey(): String =
        Traffic.cleanKey(prefs.getString("tomtomKey", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TOMTOM_API_KEY)

    private val trafficPulse = Runnable { checkTraffic() }

    // Crash / closure alerts and traffic delay on the route ahead (TomTom Incident Details).
    private var routeIncidents = listOf<RouteIncident>()
    private var incidentsKnown = false
    private var routeTrafficDelayS = 0.0
    private val spokenIncidents = HashSet<String>()
    private val spokenNearIncidents = HashSet<String>()
    private var incidentInFlight = false

    private val incidentCheck = Runnable { checkIncidents() }

    private fun checkIncidents() {
        ui.removeCallbacks(incidentCheck)
        if (!routeActive) return
        ui.postDelayed(incidentCheck, TrafficIncidents.CHECK_MS)
        if (!tomtomExtrasOn() || !TomTomGate.open() || incidentInFlight || routePoints.size < 2) return
        val loc = lastLocation ?: return
        val pts = routePoints; val cum = routeCum
        val progress = RouteMath.progressAlong(pts, cum, GeoPoint(loc.latitude, loc.longitude))
        val box = TrafficIncidents.boxAhead(pts, cum, progress) ?: return
        val key = trafficKey()
        incidentInFlight = true
        thread {
            val body = tomtomGet(TrafficIncidents.url(key, box[0], box[1], box[2], box[3]))
            val found = body?.let { b -> runCatching { TrafficIncidents.onRoute(b, pts, cum, progress) }.getOrNull() }
            runOnUiThread {
                incidentInFlight = false
                if (found == null || !routeActive || routePoints !== pts) return@runOnUiThread
                routeIncidents = found; incidentsKnown = true
                val sayable = found.filter { TrafficIncidents.worthSaying(it) && it.id !in spokenIncidents && it.aheadM < 30_000 }
                sayable.firstOrNull()?.let { x ->
                    val msg = TrafficIncidents.message(x, unitSystem)
                    speak(msg); Toast.makeText(this, "🚧 $msg", Toast.LENGTH_LONG).show()
                }
                sayable.forEach { spokenIncidents += it.id }
                renderHud()
            }
        }
    }

    /** Extra time from traffic on the road still ahead: TomTom incidents once known, else the route's own estimate. */
    private fun trafficDelayAhead(progressM: Double, remainM: Double): Double {
        if (!tomtomExtrasOn()) return 0.0
        if (incidentsKnown) return routeIncidents.filter { it.startAlongM > progressM }.sumOf { it.delayS }
        return if (routeTotalM > 0) routeTrafficDelayS * (remainM / routeTotalM).coerceIn(0.0, 1.0) else 0.0
    }

    /** A second, closer warning about a crash or closure about a mile before you reach it. */
    private fun checkIncidentProximity(progressM: Double) {
        if (routeIncidents.isEmpty()) return
        val near = routeIncidents.firstOrNull { x ->
            x.category in setOf(TrafficIncidents.ACCIDENT, TrafficIncidents.ROAD_CLOSED, TrafficIncidents.LANE_CLOSED, TrafficIncidents.BROKEN_DOWN) &&
                x.id !in spokenNearIncidents && (x.startAlongM - progressM) in 0.0..1600.0
        } ?: return
        spokenNearIncidents += near.id
        speak(TrafficIncidents.message(near.copy(aheadM = near.startAlongM - progressM), unitSystem))
    }

    /**
     * Asks TomTom for one tile first. Only when that works does the map load traffic, so a bad
     * key or a used-up daily allowance just hides the layer instead of filling the map with errors.
     */
    private fun checkTraffic() {
        ui.removeCallbacks(trafficPulse)
        val key = trafficKey()
        if (key.isEmpty()) { trafficStatus = Traffic.Status.NO_KEY; removeTrafficLayers(); return }
        if (!trafficOn) { removeTrafficLayers(); return }
        if (trafficInFlight) return
        val at = lastLocation?.let { it.latitude to it.longitude }
            ?: (if (::map.isInitialized) map.cameraPosition?.target?.let { it.latitude to it.longitude } else null)
            ?: (39.8283 to -98.5795)
        if (trafficStatus != Traffic.Status.OK) trafficStatus = Traffic.Status.CHECKING
        trafficInFlight = true
        thread {
            val code = try {
                val c = (URL(Traffic.probeUrl(key, at.first, at.second)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 12000; setRequestProperty("User-Agent", UA)
                }
                try { c.responseCode } finally { c.disconnect() }
            } catch (_: Exception) { -1 }
            val st = Traffic.statusFor(code)
            runOnUiThread {
                trafficInFlight = false
                if (!trafficOn || trafficKey() != key) return@runOnUiThread
                trafficStatus = st
                when (st) {
                    Traffic.Status.OK -> { trafficStamp = System.currentTimeMillis(); showTrafficLayers() }
                    Traffic.Status.REJECTED -> removeTrafficLayers()
                    else -> {} // busy or offline: keep whatever traffic is already on the map
                }
                val next = when (st) {
                    Traffic.Status.OK -> Traffic.REFRESH_MS
                    Traffic.Status.REJECTED -> Traffic.RETRY_MS
                    else -> 2 * 60_000L
                }
                ui.postDelayed(trafficPulse, next)
                if (menuPanel.isOpen) menuPanel.refresh()
            }
        }
    }

    /** Fresh traffic tiles above the radar and roads, below labels, the route line and your marker. */
    private fun showTrafficLayers() {
        if (!::map.isInitialized) return
        val style = map.style ?: return
        val key = trafficKey()
        if (key.isEmpty()) return
        val oldLayers = trafficLayerIds; val oldSources = trafficSourceIds
        val layers = mutableListOf<String>(); val sources = mutableListOf<String>()
        try {
            val anchor = style.layers.firstOrNull { it is SymbolLayer }?.id
            val parts = listOf(
                "stormnav-traffic-flow-$trafficStamp" to Traffic.flowUrl(key, trafficStamp),
                "stormnav-traffic-inc-$trafficStamp" to Traffic.incidentsUrl(key, trafficStamp)
            )
            for ((id, url) in parts) {
                if (style.getSource(id) != null) continue
                val tiles = TileSet("2.2.0", url)
                tiles.minZoom = Traffic.MIN_ZOOM
                tiles.maxZoom = Traffic.MAX_ZOOM
                tiles.attribution = "© TomTom"
                style.addSource(RasterSource(id, tiles, 256))
                val layer = RasterLayer("$id-layer", id).withProperties(
                    PropertyFactory.rasterOpacity(if (id.contains("flow")) 0.85f else 1f),
                    PropertyFactory.rasterFadeDuration(0f)
                )
                when {
                    anchor != null -> style.addLayerBelow(layer, anchor)
                    style.getLayer(ME_LAYER_ID) != null -> style.addLayerBelow(layer, ME_LAYER_ID)
                    else -> style.addLayer(layer)
                }
                layers += "$id-layer"; sources += id
            }
        } catch (_: Exception) { return }
        trafficLayerIds = layers; trafficSourceIds = sources
        // Drop the previous tiles once the new ones have had a moment to load (no flicker).
        if (oldLayers.isNotEmpty()) ui.postDelayed({ removeTrafficIds(oldLayers, oldSources) }, 3000)
    }

    private fun removeTrafficLayers() {
        removeTrafficIds(trafficLayerIds, trafficSourceIds)
        trafficLayerIds = emptyList(); trafficSourceIds = emptyList()
    }

    private fun removeTrafficIds(layers: List<String>, sources: List<String>) {
        if (!::map.isInitialized) return
        val style = map.style ?: return
        layers.forEach { runCatching { style.removeLayer(it) } }
        sources.forEach { runCatching { style.removeSource(it) } }
    }

    private fun showTrafficKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste your TomTom API key"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString("tomtomKey", "") ?: "")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(6), dp(22), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🚦 TomTom traffic key")
            .setMessage("1. Sign up free at developer.tomtom.com (no credit card).\n" +
                "2. In your Dashboard, open Keys and copy your API key.\n3. Paste it below. It stays on this phone.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = Traffic.cleanKey(input.text.toString())
                if (k.isNotEmpty() && !Traffic.looksValid(k)) {
                    Toast.makeText(this, "That doesn't look like a TomTom key. Copy it again from your TomTom dashboard.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("tomtomKey", k).putBoolean("trafficOn", true).apply()
                trafficOn = true; trafficStatus = Traffic.Status.CHECKING
                removeTrafficLayers(); checkTraffic()
                if (menuPanel.isOpen) menuPanel.refresh()
            }
            .setNeutralButton("Get a key") { _, _ ->
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.tomtom.com/user/register"))) }
                catch (_: Exception) { Toast.makeText(this, "Open developer.tomtom.com in your browser", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() { menuPanel.open { p -> buildMenu(p) } }

    private fun buildMenu(p: SidePanel) {
        val links = supportLinks()
        if (links.isNotEmpty()) {
            p.bigButton("☕  Buy me a coffee", Color.rgb(255, 196, 0)) { showCoffee(fromMenu = true) }
        }

        p.section("Go")
        p.buttons("🔍 Search" to { menuPanel.close(); showDestinationDialog() }, "⌖ Re-center" to { menuPanel.close(); recenter() })
        p.buttons("⚠ Report hazard" to { menuPanel.close(); showReportDialog() },
            if (routeActive) "■ Stop route" to { menuPanel.close(); stopRoute() } else "ℹ Trip details" to { menuPanel.close(); showTripDetails() })
        if (routeActive || waitMode) p.buttons("⛈ Storm options" to { menuPanel.close(); showStormOptions(auto = false) }, "ℹ Trip details" to { menuPanel.close(); showTripDetails() })

        p.section("Radar")
        p.choice(listOf("Radar on", "Radar off"), if (radarVisible) 0 else 1) { i ->
            radarVisible = i == 0; prefs.edit().putBoolean("radarVisible", radarVisible).apply(); applyRadarStyle()
        }
        p.slider("Radar opacity", (radarOpacity * 100).roundToInt(),
            onChange = { v -> radarOpacity = v / 100f; if (!radarVisible && v > 0) { radarVisible = true; prefs.edit().putBoolean("radarVisible", true).apply() }; applyRadarStyle() },
            onDone = { prefs.edit().putFloat("radarOpacity", radarOpacity).apply() })
        p.note("Changes show live on the map. Radar is drawn under the roads so they stay readable.")
        val refresh = listOf(30, 60, 120)
        p.note("Check for new radar every:")
        p.choice(listOf("30 sec", "1 min", "2 min"), refresh.indexOf(radarRefreshS).coerceAtLeast(0)) { i ->
            radarRefreshS = refresh[i]; prefs.edit().putInt("radarRefreshS", radarRefreshS).apply()
            ui.removeCallbacks(radarPulse); ui.post(radarPulse)
        ui.removeCallbacks(conditionsCheck); ui.postDelayed(conditionsCheck, 6_000)
        }
        p.note("NOAA publishes a new radar image about every 2 minutes; StormNav shows it as soon as it appears. " +
            radarAgeText().ifBlank { "Waiting for the first radar check…" })

        p.section("Traffic")
        if (trafficKey().isEmpty()) {
            p.note(Traffic.describe(Traffic.Status.NO_KEY) + " Paste it here once and it's saved on this phone.")
            p.buttons("🔑 Enter TomTom key" to { showTrafficKeyDialog() })
        } else {
            p.choice(listOf("Traffic on", "Traffic off"), if (trafficOn) 0 else 1) { i ->
                trafficOn = i == 0; prefs.edit().putBoolean("trafficOn", trafficOn).apply()
                if (trafficOn) { trafficStatus = Traffic.Status.CHECKING; checkTraffic() } else { ui.removeCallbacks(trafficPulse); removeTrafficLayers() }
                menuPanel.refresh()
            }
            if (trafficOn) p.note(Traffic.describe(trafficStatus))
            p.buttons("🔑 Change key" to { showTrafficKeyDialog() })
        }
        p.note("With traffic on, each new trip's first route uses TomTom's live traffic, your arrival time includes delays, " +
            "and StormNav warns you about crashes and closures ahead. Re-routes use the free servers. If TomTom's free allowance runs out, these quietly switch off for a while.")

        p.section("Theme")
        val themes = HudStyle.THEMES
        p.swatches(themes.map { it.name to intArrayOf(it.accent, it.panelTop) }, themes.indexOf(HudStyle.theme)) { i ->
            HudStyle.setTheme(themes[i].id); prefs.edit().putString("theme", themes[i].id).apply(); applyTheme()
        }
        p.section("Route line colour")
        val routes = HudStyle.ROUTE_COLORS
        p.swatches(routes.map { it.name to intArrayOf(it.line) }, routes.indexOf(HudStyle.route)) { i ->
            HudStyle.setRoute(routes[i].id); prefs.edit().putString("routeColor", routes[i].id).apply()
            routeGeometry?.let { drawRoute(it) }
            refreshMarker()
        }
        p.note("Route colours avoid radar greens, yellows and reds so the line stays visible in storms.")

        p.section("Your marker on the map")
        val styles = MarkerStyle.values().toList()
        p.pictures(styles.map { it.label to markerBitmap(it, dp(88), HudStyle.ROUTE_BLUE) }, styles.indexOf(markerStyle)) { i ->
            markerStyle = styles[i]; prefs.edit().putString("markerStyle", markerStyle.name).apply(); refreshMarker()
        }
        p.note("Vehicles take your route-line colour and turn with your heading.")

        p.section("Map")
        p.choice(listOf("Satellite", "Dark"), if (basemap == Basemap.SATELLITE) 0 else 1) { i ->
            val b = if (i == 0) Basemap.SATELLITE else Basemap.DARK
            if (b != basemap) { basemap = b; prefs.edit().putString("basemap", basemap.name).apply(); loadBasemap() }
        }
        p.choice(listOf("Heading up", "North up"), if (northUp) 1 else 0) { i ->
            northUp = i == 1; prefs.edit().putBoolean("northUp", northUp).apply(); recenter()
        }
        val positions = listOf(0.5f, 0.7f, 0.8f)
        p.note("Your position on screen (heading up): lower shows more road ahead.")
        p.choice(listOf("Center", "Lower", "Bottom"), positions.indexOfFirst { kotlin.math.abs(it - cursorPos) < 0.01f }.coerceAtLeast(2)) { i ->
            cursorPos = positions[i]; prefs.edit().putFloat("cursorPos", cursorPos).apply(); recenter()
        }
        p.choice(listOf("Gauges shown", "Gauges hidden"), if (showGauges) 0 else 1) { i ->
            showGauges = i == 0; prefs.edit().putBoolean("showGauges", showGauges).apply()
            leftColumn.visibility = if (showGauges) View.VISIBLE else View.GONE
        }

        p.section("Units")
        val unitList = listOf(UnitSystem.IMPERIAL, UnitSystem.METRIC, UnitSystem.BOTH)
        p.choice(listOf("Miles", "Kilometres", "Both"), unitList.indexOf(unitSystem)) { i ->
            unitSystem = unitList[i]; prefs.edit().putString("units", unitSystem.name).apply(); layoutHud(); renderHud()
        }

        p.section("Parking")
        val car = parking()
        if (car != null) p.buttons("🚗 Find my car" to { menuPanel.close(); showFindCar() }, "🅿️ Save spot here" to { lastLocation?.let { saveParking(it) }; menuPanel.refresh() })
        else p.buttons("🅿️ Save parking spot" to { lastLocation?.let { saveParking(it) } ?: Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show(); menuPanel.refresh() })
        p.choice(listOf("Auto-save on arrival", "Manual only"), if (autoPark) 0 else 1) { i ->
            autoPark = i == 0; prefs.edit().putBoolean("autoPark", autoPark).apply()
        }
        car?.let { p.note("Saved ${parkedAgo(it.optLong("at"))}${it.optString("address").takeIf { a -> a.isNotBlank() }?.let { a -> " at $a" } ?: ""}. Storm watch also warns you if a severe storm heads for your car.") }

        p.section("Storm chaser")
        if (!chaseMode) p.buttons("🌪 Start storm chase" to { menuPanel.close(); startChase() })
        else p.buttons("🎯 Pick another storm" to { menuPanel.close(); pickChaseTarget() }, "■ Stop chase" to { menuPanel.close(); endChase(); stopRoute() })
        val buffers = listOf(0, 1, 2, 5)
        p.note("Stop short of the storm core by:")
        p.choice(buffers.map { if (it == 0) "Core" else "$it mi" }, buffers.indexOf(chaseBufferMi).coerceAtLeast(0)) { i ->
            chaseBufferMi = buffers[i]; prefs.edit().putInt("chaseBufferMi", chaseBufferMi).apply()
            if (chaseMode) routeToChase(force = true)
        }

        p.section("Voice & storm alerts")
        p.choice(listOf("Voice on", "Voice off"), if (voiceOn) 0 else 1) { i ->
            voiceOn = i == 0; prefs.edit().putBoolean("voiceOn", voiceOn).apply(); if (!voiceOn) tts?.stop()
        }
        p.note("Voice: ${currentVoiceName()}")
        p.buttons("🗣 Choose voice…" to { showVoicePicker() }, "▶ Test" to { if (!voiceOn) { voiceOn = true; prefs.edit().putBoolean("voiceOn", true).apply(); menuPanel.refresh() }; sampleSpeech() })
        val rates = listOf(0.85f, 1.0f, 1.2f)
        p.choice(listOf("Slower", "Normal", "Faster"), rates.indexOfFirst { kotlin.math.abs(it - speechRate) < 0.01f }.coerceAtLeast(1)) { i ->
            speechRate = rates[i]; prefs.edit().putFloat("speechRate", speechRate).apply(); tts?.setSpeechRate(speechRate); sampleSpeech()
        }
        val engines = runCatching { tts?.engines }.getOrNull().orEmpty()
        if (engines.size > 1) {
            val currentEngine = runCatching { tts?.defaultEngine }.getOrNull()
            val selectedPkg = prefs.getString("ttsEngine", null) ?: currentEngine
            p.note("Voice engine:")
            p.choice(engines.map { it.label.toString().removeSuffix(" Engine").removeSuffix(" text-to-speech engine") },
                engines.indexOfFirst { it.name == selectedPkg }.coerceAtLeast(0)) { i ->
                if (engines[i].name != selectedPkg) switchEngine(engines[i].name)
            }
        }
        val alertModes = listOf("severe", "heavy", "off")
        p.choice(listOf("Severe only", "Heavy + severe", "Off"), alertModes.indexOf(stormAlertMode).coerceAtLeast(0)) { i ->
            stormAlertMode = alertModes[i]; prefs.edit().putString("stormAlerts", stormAlertMode).apply()
        }
        p.note("Storm popups offer a detour or a place to wait. \"Severe only\" is the default.")
        p.choice(listOf("Condition warnings on", "Off"), if (conditionWarnings) 0 else 1) { i ->
            conditionWarnings = i == 0; prefs.edit().putBoolean("conditionWarnings", conditionWarnings).apply(); renderHud()
        }
        p.note("Fog, heavy rain, ice, snow, high winds and flooding where you are: from radar, the nearest NWS station and NWS alerts.")

        p.section("About")
        p.buttons("About radar & alerts" to { showRadarInfo() })
        if (links.isNotEmpty()) p.note("StormNav is free with no ads. Coffee keeps it that way. Thank you!")
    }

    /** Radar layer on/off and opacity, straight from the settings. */
    private fun applyRadarStyle() {
        radarLayer?.setProperties(
            PropertyFactory.rasterOpacity(radarOpacity),
            PropertyFactory.visibility(if (radarVisible && radarOpacity > 0f) Property.VISIBLE else Property.NONE)
        )
    }

    /** Re-skins the HUD after a theme change. */
    private fun applyTheme() {
        layoutHud()
        statusChip.background = HudStyle.panel(this, 12f, Color.argb(90, 160, 170, 180), 1f)
        infoGlyph.invalidate(); boltGlyph.invalidate()
        if (searchPanel.isOpen) searchPanel.close()
        menuPanel.open { p -> buildMenu(p) } // re-open so the panel itself picks up the new colours
    }

    /** The map stops following (you panned or zoomed); show Re-center, and resume by itself while navigating. */
    private fun pauseFollow() {
        followCamera = false
        if (::recenterButton.isInitialized) { recenterButton.visibility = View.VISIBLE; placeRecenter() }
        ui.removeCallbacks(autoResume)
        if (routeActive) ui.postDelayed(autoResume, 12_000)
    }

    private fun recenter() {
        followCamera = true
        overviewUntil = 0L
        ui.removeCallbacks(autoResume)
        if (::recenterButton.isInitialized) recenterButton.visibility = View.GONE
        val loc = lastLocation ?: run { requestGps(); return }
        followUser(loc, animate = true)
    }

    /** Sits just left of the Menu pill. */
    private fun placeRecenter() {
        val dm = resources.displayMetrics
        if (dm.widthPixels <= dm.heightPixels) { recenterButton.translationX = 0f; return }
        recenterButton.post {
            recenterButton.translationX = -(menuButton.width / 2f + dp(10) + recenterButton.width / 2f)
        }
    }

    /**
     * Normal-GPS camera. Driving (navigating, or moving without a route): follows you, heading-up
     * and tilted with you in the lower third (or north-up if chosen), zooming out as you speed up.
     * Parked with no route: a flat, north-up overview that's better for reading radar.
     */
    private fun followUser(loc: Location, animate: Boolean) {
        if (!::map.isInitialized || !followCamera || System.currentTimeMillis() < overviewUntil) return
        val mph = if (loc.hasSpeed()) loc.speed * 2.236936 else 0.0
        val driving = routeActive || mph > 7
        val pos = if (driving) {
            val zoom = when { mph < 25 -> 16.3; mph < 45 -> 15.5; mph < 60 -> 14.9; else -> 14.4 }
            val b = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude)).zoom(zoom)
            if (northUp) b.bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0)
            // Top padding P puts you at (H + P) / 2 from the top: P = (2·pos − 1)·H.
            else b.bearing(lastBearing.toDouble()).tilt(45.0).padding(0.0, mapView.height * (2.0 * cursorPos - 1.0).coerceIn(0.0, 0.7), 0.0, 0.0)
            b.build()
        } else {
            CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude)).zoom(11.5).bearing(0.0).tilt(0.0)
                .padding(0.0, 0.0, 0.0, 0.0).build()
        }
        if (animate) map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        else map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    // ---------------------------------------------------------------- destination + routing

    private fun showDestinationDialog() { searchPanel.open() }

    /** Search bias: GPS when we have it, otherwise the middle of the map. */
    private fun searchBias(): GeoPoint? = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        ?: if (::map.isInitialized) map.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) } else null

    private fun httpText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 12000; setRequestProperty("User-Agent", UA)
        }
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** "Springfield, Illinois": lets "500 Main St" work without typing the town. */
    private fun refreshUserTown(loc: Location) {
        val last = userTownAt
        if (last != null && last.distanceTo(loc) < 5000f) return
        userTownAt = loc
        thread { placeSearch.reverseTown(loc.latitude, loc.longitude)?.let { t -> runOnUiThread { userTown = t } } }
    }

    private fun goTo(result: PlaceResult) {
        // A new destination replaces whatever was going on: a chase or a storm wait.
        endChase(); endWait()
        if (lastLocation == null) {
            Toast.makeText(this, "Waiting for GPS. Your route will be calculated when your location is found.", Toast.LENGTH_LONG).show()
            pendingDestination = Place(result.label, result.lat, result.lon)
            requestGps(); return
        }
        requestRoute(Place(result.label, result.lat, result.lon), traffic = true)
    }

    private fun startDropPin() {
        Toast.makeText(this, "Long-press anywhere on the map to set your destination.", Toast.LENGTH_LONG).show()
        pauseFollow()
    }

    private fun confirmPin(p: LatLng) {
        if (chaseMode) { chaseNear(GeoPoint(p.latitude, p.longitude)); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Go to this spot?")
            .setMessage(String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude))
            .setPositiveButton("Go") { _, _ -> goTo(PlaceResult("Dropped pin", "", p.latitude, p.longitude, PlaceKind.PIN)) }
            .setNegativeButton("Cancel", null).show()
    }

    /** A route from OSRM, parsed but not yet shown. */
    private class FetchedRoute(val geometry: JSONArray, val steps: List<RouteStep>, val durationS: Double, val distanceM: Double, val trafficDelayS: Double = 0.0) {
        val points: List<GeoPoint> by lazy {
            (0 until geometry.length()).map { i -> geometry.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
        }
    }

    private val routingLock = Any()
    @Volatile private var lastRoutingAt = 0L
    /** Which routing server answered last (shown in Trip details). */
    @Volatile private var routingProvider = ""

    /** FOSSGIS asks for at most one request per second; keep every routing call at least 1.1 s apart. */
    private fun routingThrottle() {
        synchronized(routingLock) {
            val wait = 1100L - (System.currentTimeMillis() - lastRoutingAt)
            if (wait > 0) Thread.sleep(wait)
            lastRoutingAt = System.currentTimeMillis()
        }
    }

    private fun httpPostJson(url: String, body: String, auth: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 20000
            setRequestProperty("User-Agent", UA); setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/geo+json, application/json"); setRequestProperty("Authorization", auth)
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * One TomTom request (not a map tile). Returns the body on success; on refusal or a used-up
     * allowance it tells [TomTomGate] to rest and returns null, so callers silently use free servers.
     */
    private fun tomtomGet(url: String): String? {
        if (!TomTomGate.open()) return null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 9000; setRequestProperty("User-Agent", UA)
            }
            try {
                val code = c.responseCode
                TomTomGate.record(code)
                if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
    }

    /** TomTom extras (traffic routes, crash alerts) follow the Traffic switch and need a key. */
    private fun tomtomExtrasOn(): Boolean = trafficOn && trafficKey().isNotEmpty()

    /**
     * New trips with [traffic]: TomTom first (live traffic, one request). Everything else, and
     * TomTom failures, use FOSSGIS OSRM, then openrouteservice (if a key is set), then the OSRM demo.
     */
    private fun routeWithProviders(points: List<GeoPoint>, alternatives: Boolean, traffic: Boolean = false): List<ParsedRoute> {
        if (traffic && tomtomExtrasOn()) {
            tomtomGet(RoutingProviders.tomtomUrl(trafficKey(), points))
                ?.let { body -> runCatching { RoutingProviders.parseTomTom(body) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }?.let { routingProvider = "TomTom (with live traffic)"; return it }
        }
        runCatching {
            routingThrottle()
            RoutingProviders.parseOsrm(httpText(RoutingProviders.osrmUrl(RoutingProviders.FOSSGIS, points, alternatives)))
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { routingProvider = "FOSSGIS OSRM (routing.openstreetmap.de)"; return it }
        val key = BuildConfig.ORS_API_KEY.trim()
        if (key.isNotEmpty()) runCatching {
            RoutingProviders.parseOrs(httpPostJson(RoutingProviders.ORS, RoutingProviders.orsBody(points, alternatives), key))
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { routingProvider = "openrouteservice"; return it }
        runCatching {
            routingThrottle()
            RoutingProviders.parseOsrm(httpText(RoutingProviders.osrmUrl(RoutingProviders.OSRM_DEMO, points, alternatives)))
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { routingProvider = "OSRM demo server"; return it }
        throw IllegalStateException("No routing server could find a route")
    }

    /** Blocking: up to 3 routes (alternatives) from [from] to [dest], optionally through [via]. */
    private fun fetchRoutes(from: GeoPoint, dest: Place, via: GeoPoint? = null, alternatives: Boolean = false, traffic: Boolean = false): List<FetchedRoute> {
        val points = listOfNotNull(from, via, GeoPoint(dest.lat, dest.lon))
        return routeWithProviders(points, alternatives && via == null, traffic).map { r ->
            val geometry = JSONArray().apply { r.coords.forEach { put(JSONArray().put(it[0]).put(it[1])) } }
            val steps = r.steps.map { st ->
                val maneuver = JSONObject().put("type", st.type).put("modifier", st.modifier)
                // Prefer the road number ("SR-29") when there is one, like the mockup.
                val road = st.name.ifBlank { st.ref }
                RouteStep(st.lat, st.lon, maneuverInstruction(maneuver, road, st.ref), st.distanceM, ManeuverKind.from(st.type, st.modifier))
            }
            FetchedRoute(geometry, steps, r.durationS, r.distanceM, r.trafficDelayS)
        }
    }

    /** [traffic]: a new trip, worth one TomTom request for a route that knows current traffic. */
    private fun requestRoute(destination: Place, via: GeoPoint? = null, announce: String? = null, traffic: Boolean = false) {
        val origin = lastLocation ?: return
        routeDestinationName = destination.name
        routeDestination = destination
        offRouteFixes = 0
        setBannerMessage("Calculating route to ${destination.name}…")
        thread {
            try {
                val r = fetchRoutes(GeoPoint(origin.latitude, origin.longitude), destination, via, traffic = traffic).firstOrNull()
                    ?: throw IllegalStateException("No drivable route returned")
                runOnUiThread { applyRoute(destination, r, via, announce) }
            } catch (e: Exception) {
                runOnUiThread {
                    rerouteInProgress = false
                    setBannerMessage("Couldn't calculate route")
                    Toast.makeText(this, "Routing unavailable: ${e.message ?: "try again"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Shows a fetched route and starts guidance, radar and alert checks on it. */
    private fun applyRoute(destination: Place, r: FetchedRoute, via: GeoPoint? = null, announce: String? = null) {
        val geometry = r.geometry
        val steps = r.steps
        routeDestinationName = destination.name
        routeDestination = destination
        routeGeometry = geometry
        routeSteps = steps.toMutableList()
        currentStepIndex = if (steps.size > 1) 1 else 0
        lastAnnouncedStep = -1
        lastReminderStep = -1
        routeActive = true
        offRoute = false
        offRouteFixes = 0
        rerouteInProgress = false
        followCamera = true
        drawRoute(geometry)
        val durationSec = r.durationS
        routeTotalM = r.distanceM
        nwsSummary = ""; nwsDetails = emptyList(); radarSummary = ""; destTempSummary = ""; radarSegments = null
        nwsSevereOnRoute = false
        routeNowcast = null
        departHintShown = false
        routePoints = r.points
        routeCum = RouteMath.cumulative(routePoints)
        routeAvgSpeedMps = if (durationSec > 0) routeTotalM / durationSec else 0.0
        routeVia = via
        routeViaAlongM = via?.let { StormPlanner.nearestOnRoute(routePoints, routeCum, it).second } ?: 0.0
        window.decorView.keepScreenOn = true
        if (steps.size <= 1) setBannerMessage("Continue to destination")
        renderHud()
        val bounds = geometryBounds(geometry)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(90)))
        overviewUntil = System.currentTimeMillis() + 3500
        ui.postDelayed({ lastLocation?.let { followUser(it, animate = true) } }, 3600)
        routeTrafficDelayS = r.trafficDelayS
        routeIncidents = emptyList(); incidentsKnown = false; spokenIncidents.clear(); spokenNearIncidents.clear()
        val trafficNote = if (r.trafficDelayS >= 120) ", including ${(r.trafficDelayS / 60).roundToInt()} minutes of traffic" else ""
        speak(announce ?: "Route calculated. ${TripMath.duration(durationSec + r.trafficDelayS)} to $routeDestinationName$trafficNote.")
        ui.removeCallbacks(incidentCheck)
        ui.postDelayed(incidentCheck, 6000)
        checkStormAlertsOnRoute(geometry)
        checkRadarAhead()
        fetchDestinationTemperature(destination)
        ui.removeCallbacks(radarRecheck)
        ui.postDelayed(radarRecheck, RADAR_RECHECK_MS)
    }

    private fun maneuverInstruction(m: JSONObject, road: String, ref: String): String {
        val type = m.optString("type", "continue")
        val modifier = m.optString("modifier", "")
        // "SR 29" -> "SR-29" to match road signs.
        val label = (ref.takeIf { it.isNotBlank() }?.split(";")?.first()?.trim()?.replace(Regex("^([A-Z]{1,3}) (\\d+)"), "$1-$2"))
            ?: road
        val street = label.takeIf { it.isNotBlank() }?.let { " onto $it" } ?: ""
        return when (type) {
            "depart" -> "Head ${modifier.ifBlank { "ahead" }}$street"
            "arrive" -> "Arrive at destination"
            "turn", "end of road", "fork", "on ramp", "off ramp", "merge" -> {
                val verb = when (modifier) {
                    "left" -> "Turn Left"; "slight left" -> "Bear Left"; "sharp left" -> "Sharp Left"
                    "right" -> "Turn Right"; "slight right" -> "Bear Right"; "sharp right" -> "Sharp Right"
                    "uturn" -> "Make a U-turn"; "straight" -> "Continue straight"; else -> "Continue"
                }
                val prefix = when (type) { "off ramp" -> "Take exit · "; "on ramp" -> "Take ramp · "; else -> "" }
                "$prefix$verb$street"
            }
            "roundabout", "rotary" -> "Enter the roundabout$street"
            "new name" -> "Continue$street"
            else -> "Continue$street"
        }
    }

    private fun geometryBounds(coords: JSONArray): org.maplibre.android.geometry.LatLngBounds {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i); val lon = c.getDouble(0); val lat = c.getDouble(1)
            minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat); minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
        }
        return org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(maxLat, minLon)).include(LatLng(minLat, maxLon)).build()
    }

    private fun drawRoute(coords: JSONArray) {
        if (!::map.isInitialized) return
        val style = map.style ?: return
        val geoJson = JSONObject().apply {
            put("type", "Feature")
            put("properties", JSONObject())
            put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
        }.toString()
        try {
            if (style.getLayer(ROUTE_LAYER_ID) != null) style.removeLayer(ROUTE_LAYER_ID)
            if (style.getLayer(ROUTE_CASING_ID) != null) style.removeLayer(ROUTE_CASING_ID)
            if (style.getSource(ROUTE_SOURCE_ID) != null) style.removeSource(ROUTE_SOURCE_ID)
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geoJson))
            val casing = LineLayer(ROUTE_CASING_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(HudStyle.ROUTE_CASING),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            val line = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(HudStyle.ROUTE_BLUE),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.97f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            // Keep the route under the user arrow when that layer exists.
            if (style.getLayer(ME_LAYER_ID) != null) {
                style.addLayerBelow(casing, ME_LAYER_ID); style.addLayerBelow(line, ME_LAYER_ID)
            } else { style.addLayer(casing); style.addLayer(line) }
        } catch (_: Exception) { Toast.makeText(this, "Could not draw route on map.", Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- guidance

    private fun updateNavigation(location: Location) {
        if (!routeActive || currentStepIndex !in routeSteps.indices) return
        val geometry = routeGeometry ?: return

        // Compare GPS to the route itself, not just to the next turn. This avoids
        // treating a nearby parallel road as a valid maneuver approach.
        val routeDistance = distanceToRouteMeters(location.latitude, location.longitude, geometry)
        val gpsTrustworthy = !location.hasAccuracy() || location.accuracy <= 45f
        if (gpsTrustworthy && routeDistance > 90.0) offRouteFixes++ else offRouteFixes = 0
        if (offRouteFixes >= 3 && !rerouteInProgress &&
            System.currentTimeMillis() - lastRerouteAt > 30_000L) {
            val destination = routeDestination
            if (destination != null) {
                rerouteInProgress = true
                lastRerouteAt = System.currentTimeMillis()
                offRouteFixes = 0
                setBannerMessage("Off route — finding a new route…")
                speak("You are off route. Recalculating.")
                requestRoute(destination, activeVia())
                return
            }
        }

        val step = routeSteps[currentStepIndex]
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, step.lat, step.lon, result)
        val distance = result[0]
        offRoute = gpsTrustworthy && routeDistance > 90.0
        if (offRoute) {
            setBannerMessage("Off route · ${Units.short(routeDistance, unitSystem)} from planned route")
            return
        }
        if (distance <= 35f) {
            if (currentStepIndex < routeSteps.lastIndex) {
                currentStepIndex++
                lastAnnouncedStep = -1
                lastReminderStep = -1
            } else {
                setBannerMessage(if (waitMode) "Waiting out the storm at $routeDestinationName" else "Arriving at $routeDestinationName")
                speak(if (waitMode) "You've arrived at $routeDestinationName. Wait here; I'll tell you when the storm has cleared your route."
                    else "You have arrived at $routeDestinationName")
                if (autoPark && !waitMode && !chaseMode && routeDestinationName != "Your parked car") saveParking(location, auto = true)
                routeActive = false
                window.decorView.keepScreenOn = false
            }
        } else {
            if (distance <= 500f && lastAnnouncedStep != currentStepIndex) announceStep(currentStepIndex)
            if (distance <= 110f && lastReminderStep != currentStepIndex) {
                speak("Now, ${routeSteps[currentStepIndex].instruction}")
                lastReminderStep = currentStepIndex
            }
        }
    }

    private fun distanceToRouteMeters(lat: Double, lon: Double, geometry: JSONArray): Double {
        if (geometry.length() < 2) return Double.POSITIVE_INFINITY
        val metersPerLat = 111_320.0
        val metersPerLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val px = lon * metersPerLon
        val py = lat * metersPerLat
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until geometry.length() - 1) {
            val a = geometry.getJSONArray(i)
            val b = geometry.getJSONArray(i + 1)
            val ax = a.getDouble(0) * metersPerLon
            val ay = a.getDouble(1) * metersPerLat
            val bx = b.getDouble(0) * metersPerLon
            val by = b.getDouble(1) * metersPerLat
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val ex = px - (ax + t * dx)
            val ey = py - (ay + t * dy)
            best = minOf(best, kotlin.math.sqrt(ex * ex + ey * ey))
        }
        return best
    }

    private fun announceStep(index: Int) {
        if (index !in routeSteps.indices) return
        val step = routeSteps[index]
        val distance = lastLocation?.let {
            val out = FloatArray(1); Location.distanceBetween(it.latitude, it.longitude, step.lat, step.lon, out); out[0].toDouble()
        } ?: 500.0
        speak("In ${Units.spokenDistance(distance, unitSystem)}, ${step.instruction}")
        lastAnnouncedStep = index
    }

    private fun speak(message: String) {
        if (ttsReady && voiceOn) tts?.speak(message.replace("·", ","), TextToSpeech.QUEUE_FLUSH, null, "stormnav-${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            // The voice picked in the Menu, if it's still installed.
            prefs.getString("voiceName", null)?.let { name ->
                runCatching { tts?.voices?.firstOrNull { it.name == name }?.let { v -> tts?.voice = v; ttsReady = true } }
            }
            tts?.setSpeechRate(speechRate)
        }
    }

    // ---------------------------------------------------------------- voices

    /** Installed voices, your phone's language first, then by language name and quality. */
    private fun installedVoices(): List<android.speech.tts.Voice> {
        val all = runCatching { tts?.voices?.toList() }.getOrNull() ?: return emptyList()
        val lang = Locale.getDefault().language
        return all.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<android.speech.tts.Voice>({ it.locale.language != lang }, { it.locale.language != "en" },
                { it.locale.getDisplayName(Locale.getDefault()) }, { it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
    }

    private fun voiceLabel(v: android.speech.tts.Voice, index: Int): String {
        val tag = v.name.substringAfterLast("-x-", v.name).removeSuffix("-local").removeSuffix("-network")
            .replace(Regex("^[a-z]{2,3}[-_][A-Za-z]{2}[-_]?"), "").ifBlank { "${index + 1}" }
        val net = if (v.isNetworkConnectionRequired) " · online" else ""
        return "${v.locale.getDisplayName(Locale.getDefault())} · $tag$net"
    }

    private fun sampleSpeech() {
        speak("In half a mile, turn right onto K Y 53. Heavy rain ahead in 6 miles.")
    }

    /** Pick a voice; each tap plays a sample. */
    private fun showVoicePicker() {
        val voices = installedVoices()
        if (!ttsReady || voices.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("No voices found")
                .setMessage("Your phone's text-to-speech engine isn't ready or has no voices installed.")
                .setPositiveButton("Get voices") { _, _ -> openVoiceSettings() }.setNegativeButton("Close", null).show()
            return
        }
        val before = runCatching { tts?.voice }.getOrNull()
        var chosen = voices.indexOfFirst { it.name == before?.name }
        if (!voiceOn) { voiceOn = true; prefs.edit().putBoolean("voiceOn", true).apply() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Choose a voice (tap to hear it)")
            .setSingleChoiceItems(voices.mapIndexed { i, v -> voiceLabel(v, i) }.toTypedArray(), chosen) { _, which ->
                chosen = which
                runCatching { tts?.voice = voices[which] }
                sampleSpeech()
            }
            .setPositiveButton("Use this voice") { _, _ ->
                voices.getOrNull(chosen)?.let { prefs.edit().putString("voiceName", it.name).apply() }
                menuPanel.refresh()
            }
            .setNegativeButton("Cancel") { _, _ -> runCatching { if (before != null) tts?.voice = before } }
            .setNeutralButton("Get more voices") { _, _ -> openVoiceSettings() }
            .show()
    }

    private fun openVoiceSettings() {
        val intents = listOf(
            android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            android.content.Intent("com.android.settings.TTS_SETTINGS"),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        for (i in intents) {
            try { startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
    }

    /** Switch text-to-speech engine (e.g. Google ↔ Samsung). Voices differ per engine. */
    private fun switchEngine(pkg: String) {
        prefs.edit().putString("ttsEngine", pkg).remove("voiceName").apply()
        ttsReady = false
        tts?.stop(); tts?.shutdown()
        tts = TextToSpeech(this, { st -> onInit(st); if (st == TextToSpeech.SUCCESS) runOnUiThread { sampleSpeech(); menuPanel.refresh() } }, pkg)
    }

    private fun currentVoiceName(): String {
        val v = runCatching { tts?.voice }.getOrNull() ?: return "System default"
        val i = installedVoices().indexOfFirst { it.name == v.name }
        return voiceLabel(v, i.coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- map marker

    private fun markerImage(style: MarkerStyle = markerStyle) =
        markerBitmap(style, dp(if (style == MarkerStyle.ARROW) 56 else 68), HudStyle.ROUTE_BLUE)

    /** Replaces the "you" icon on the map (same image id, so the symbol layer picks it up). */
    private fun refreshMarker() {
        if (!::map.isInitialized) return
        runCatching { map.style?.addImage(ME_ICON, markerImage()) }
    }

    // ---------------------------------------------------------------- weather

    private fun httpJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/geo+json")
        }
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    /** NWS hourly forecast at the driver's position for the three gauges (US only). */
    private fun refreshWeather(loc: Location, force: Boolean = false) {
        val moved = weatherLoc?.distanceTo(loc) ?: Float.MAX_VALUE
        val stale = System.currentTimeMillis() - weatherAt > WEATHER_RECHECK_MS
        if (weatherInFlight || (!force && !stale && moved < 8000f)) return
        weatherInFlight = true
        thread {
            try {
                val key = "%.2f,%.2f".format(Locale.US, loc.latitude, loc.longitude)
                val hourly = hourlyUrlCache?.takeIf { it.first == key }?.second ?: run {
                    val pt = httpJson("https://api.weather.gov/points/${"%.4f".format(Locale.US, loc.latitude)},${"%.4f".format(Locale.US, loc.longitude)}")
                    pt.getJSONObject("properties").getString("forecastHourly").also { hourlyUrlCache = key to it }
                }
                val period = httpJson(hourly).getJSONObject("properties").getJSONArray("periods").getJSONObject(0)
                val pop = period.optJSONObject("probabilityOfPrecipitation")
                val now = WeatherNow(
                    rainChancePct = WeatherParse.rainChance(pop?.takeUnless { it.isNull("value") }?.optDouble("value")),
                    windMph = WeatherParse.windMph(period.optString("windSpeed")),
                    tempF = WeatherParse.tempF(period.optDouble("temperature"), period.optString("temperatureUnit", "F"))
                )
                runOnUiThread { weatherNow = now; weatherAt = System.currentTimeMillis(); weatherLoc = loc; weatherInFlight = false; renderHud() }
            } catch (_: Exception) {
                runOnUiThread { weatherInFlight = false; weatherAt = System.currentTimeMillis() - WEATHER_RECHECK_MS + 60_000L }
            }
        }
    }

    private fun checkStormAlertsOnRoute(geometry: JSONArray) {
        thread {
            try {
                // Sample by travelled route distance. Spacing grows on long routes so the whole
                // trip is covered within the request cap instead of only the first ~240 km.
                val pts = geometryToPoints(geometry)
                val spacing = maxOf(20_000.0, RouteMath.totalLength(pts) / 11.0)
                val samples = RouteMath.sampleEvery(pts, spacing).map { it.point.lat to it.point.lon }
                val found = linkedMapOf<String, String>()
                for ((lat, lon) in samples) {
                    val features = httpJson("$NWS_ALERTS$lat,$lon").optJSONArray("features") ?: JSONArray()
                    for (i in 0 until features.length()) {
                        val props = features.getJSONObject(i).optJSONObject("properties") ?: continue
                        val event = props.optString("event", "Weather alert")
                        val severity = props.optString("severity", "")
                        val headline = props.optString("headline").ifBlank { event }
                        val key = "$event|$headline"
                        if (event.contains("chill", true)) continue
                        if (listOf("thunderstorm", "tornado", "flood", "wind", "storm", "hail", "fog", "ice", "snow",
                                "winter", "freez", "blizzard", "sleet", "dust", "smoke").any { event.contains(it, true) }) {
                            found[key] = "$headline${if (severity.isNotBlank()) " ($severity)" else ""}"
                        }
                    }
                }
                runOnUiThread {
                    nwsDetails = found.values.toList()
                    nwsSevereOnRoute = found.keys.any { it.startsWith("Tornado Warning") || it.startsWith("Severe Thunderstorm Warning") }
                    if (nwsSevereOnRoute && radarSegments != null) ui.post { showRadarAhead() }
                    if (found.isNotEmpty()) {
                        val signature = found.keys.joinToString(";")
                        nwsSummary = "⚠ ${found.size} NWS alert${if (found.size > 1) "s" else ""} on route"
                        renderHud()
                        if (signature != lastStormAlertSignature) {
                            lastStormAlertSignature = signature
                            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Weather along your route")
                                .setMessage(found.values.joinToString("\n\n") + "\n\nThese are active National Weather Service alerts at sampled points along your route.")
                                .setPositiveButton("Understood", null).show()
                            speak("Weather alert along your route. ${found.values.first()}")
                        }
                    } else {
                        nwsSummary = "No NWS storm alerts on route"
                        renderHud()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { nwsSummary = "NWS alert check unavailable"; renderHud() }
            }
        }
    }

    private fun geometryToPoints(geometry: JSONArray): List<GeoPoint> =
        (0 until geometry.length()).map { i ->
            val c = geometry.getJSONArray(i)
            GeoPoint(c.getDouble(1), c.getDouble(0))
        }

    /** Hourly-forecast temperature at the destination from NWS (US only), for the trip details. */
    private fun fetchDestinationTemperature(dest: Place) {
        thread {
            try {
                val point = httpJson("https://api.weather.gov/points/${"%.4f".format(Locale.US, dest.lat)},${"%.4f".format(Locale.US, dest.lon)}")
                val hourly = point.getJSONObject("properties").getString("forecastHourly")
                val period = httpJson(hourly).getJSONObject("properties").getJSONArray("periods").getJSONObject(0)
                val f = WeatherParse.tempF(period.getDouble("temperature"), period.optString("temperatureUnit", "F")) ?: return@thread
                val sky = period.optString("shortForecast")
                runOnUiThread { if (routeDestination == dest) destTempSummary = "${Units.tempFromF(f)}${if (sky.isNotBlank()) ", $sky" else ""}" }
            } catch (_: Exception) { /* outside the US or service down: just omit */ }
        }
    }

    /**
     * Downloads one NOAA radar image covering the route and finds stretches of the route
     * that currently show moderate-or-heavier returns. This looks at the radar as it is now;
     * it does not yet track storm motion, so it cannot say where a cell will be later.
     */
    /** Radar now, plus how its storms are moving (null when there's nothing to track). */
    private class Nowcast(val grid: LevelGrid, val motion: Motion?, val takenAt: Long) {
        /** How old the newest radar frame is, in seconds (storms have already moved that long). */
        fun ageS(): Double = ((System.currentTimeMillis() - takenAt) / 1000.0).coerceIn(0.0, 1800.0)
    }
    @Volatile private var radarTimes: List<Long> = emptyList()
    @Volatile private var radarTimesAt = 0L

    /** Newest radar frame and one ~15 min older, from NOAA's time list (refreshed every 90 s). */
    private fun radarPair(): Pair<Long, Long>? {
        if (System.currentTimeMillis() - radarTimesAt > 90_000L) {
            runCatching { StormMotion.timesFromCapabilities(httpText(RADAR_CAPS)) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { radarTimes = it; radarTimesAt = System.currentTimeMillis() }
        }
        return StormMotion.pickPair(radarTimes)
    }

    /** Blocking: one radar image for [win] (at [timeMs], or the latest), as intensity levels. */
    private fun fetchGrid(win: RadarWindow, timeMs: Long?): LevelGrid {
        val t = timeMs?.let { "&TIME=" + StormMotion.isoUtc(it) } ?: ""
        val conn = (URL("$RADAR_WMS_BASE&WIDTH=${win.width}&HEIGHT=${win.height}&BBOX=${win.bbox}$t").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 25000; setRequestProperty("User-Agent", UA)
        }
        val bitmap = try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
            ?: throw IllegalStateException("radar image not decodable")
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val grid = LevelGrid.fromPixels(RadarWindow(win.minX, win.minY, win.maxX, win.maxY, bitmap.width, bitmap.height), px)
        bitmap.recycle()
        return grid
    }

    /** Blocking: radar for [win] plus storm motion from a frame ~15 minutes earlier. */
    private fun nowcast(win: RadarWindow): Nowcast {
        val pair = radarPair()
        val newer = fetchGrid(win, pair?.second)
        val motion = pair?.let { pr ->
            runCatching { StormMotion.estimate(fetchGrid(win, pr.first), newer, (pr.second - pr.first) / 1000.0) }.getOrNull()
        }
        return Nowcast(newer, motion, pair?.second ?: System.currentTimeMillis())
    }

    /** Storm stretches you'll actually meet on [pts], driving at [speedMps] from [fromM]. */
    private fun meetSegments(pts: List<GeoPoint>, nc: Nowcast, speedMps: Double, fromM: Double = 0.0, delayS: Double = 0.0): List<StormSegment> =
        StormMotion.movingSegments(pts, nc.grid, nc.motion ?: Motion(0.0, 0.0, 0.0), speedMps.coerceAtLeast(5.0), fromM, departDelayS = nc.ageS() + delayS)

    /** Blocking: one nowcast covering all [routes]; storm stretches of each, at each route's own pace. */
    private fun stormSegmentsFor(routes: List<FetchedRoute>): List<List<StormSegment>> {
        val all = routes.flatMap { it.points }
        require(all.size >= 2) { "empty routes" }
        val nc = nowcast(RadarWindow.forRoute(all, padM = 60_000.0))
        return routes.map { r -> if (r.points.size < 2) emptyList() else meetSegments(r.points, nc, if (r.durationS > 0) r.distanceM / r.durationS else 25.0) }
    }

    /**
     * Checks the route against radar *moved forward* to when you'll reach each stretch, using
     * storm motion measured from two radar frames ~15 minutes apart. Falls back to radar as it
     * is now if motion can't be measured.
     */
    private fun checkRadarAhead() {
        val pts = routePoints
        if (pts.size < 2) return
        val generation = ++radarCheckGeneration
        val progress = currentProgress()
        val speed = currentPlanSpeed()
        lastRouteCheckAt = System.currentTimeMillis()
        thread {
            try {
                val nc = nowcast(RadarWindow.forRoute(pts, padM = 60_000.0))
                val segments = meetSegments(pts, nc, speed, progress)
                runOnUiThread {
                    if (generation != radarCheckGeneration || !routeActive) return@runOnUiThread
                    routeNowcast = nc
                    radarSegments = segments
                    showRadarAhead()
                    maybeSuggestDeparture()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (generation == radarCheckGeneration && routeActive) {
                        radarSummary = "Radar check unavailable"; renderHud()
                    }
                }
            }
        }
    }

    /** Re-evaluates stored radar segments against current progress along the route. */
    private fun showRadarAhead() {
        val segs = radarSegments ?: return
        val loc = lastLocation
        val progress = if (loc != null && routePoints.size >= 2)
            RouteMath.progressAlong(routePoints, routeCum, GeoPoint(loc.latitude, loc.longitude)) else 0.0
        val text = RadarRouteAnalyzer.summarize(segs, progress, routeAvgSpeedMps)
        val moving = routeNowcast?.motion?.let { m ->
            val lat = routePoints.firstOrNull()?.lat ?: 38.0
            if (m.speedMps(lat) >= 2.0) " · storms moving ${StormMotion.compass(m.headingDeg)} ${Units.wind(m.speedMps(lat) * 2.236936, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem)}" else ""
        } ?: ""
        radarSummary = (if (text == null) "Radar clear on route" else "⛈ $text") + moving
        renderHud()
        val next = RadarRouteAnalyzer.ahead(segs, progress).minByOrNull { it.startM }
        // Speak only for heavy cores, and only once per storm (keyed by peak + rough start distance).
        if (next != null && next.peak >= RadarIntensity.HEAVY) {
            val key = "${next.peak}-${(next.startM / 10_000).toInt()}"
            if (key != lastRadarSpoken) {
                lastRadarSpoken = key
                // The storm popup speaks for itself; don't talk over it.
                if (StormPlanner.promptFor(segs, progress, currentPlanSpeed(), nwsSevereOnRoute) == null) speak("Caution. $text")
            }
        }
        maybePromptStorm(segs, progress)
    }

    private fun stopRoute() {
        endWait()
        endChase()
        routeVia = null
        ui.removeCallbacks(radarRecheck)
        ui.removeCallbacks(incidentCheck)
        routeIncidents = emptyList(); incidentsKnown = false; routeTrafficDelayS = 0.0
        radarCheckGeneration++
        radarSegments = null; radarSummary = ""; nwsSummary = ""; nwsDetails = emptyList(); destTempSummary = ""; lastRadarSpoken = ""
        window.decorView.keepScreenOn = false
        routeActive = false
        offRoute = false
        rerouteInProgress = false
        offRouteFixes = 0
        routeDestination = null
        routeSteps.clear(); routeGeometry = null; routePoints = emptyList(); routeTotalM = 0.0
        setBannerMessage("Navigation stopped")
        speak("Navigation stopped")
        val style = if (::map.isInitialized) map.style else null
        try {
            if (style?.getLayer(ROUTE_LAYER_ID) != null) style.removeLayer(ROUTE_LAYER_ID)
            if (style?.getLayer(ROUTE_CASING_ID) != null) style.removeLayer(ROUTE_CASING_ID)
            if (style?.getSource(ROUTE_SOURCE_ID) != null) style.removeSource(ROUTE_SOURCE_ID)
        } catch (_: Exception) { }
        renderHud()
        recenter()
    }

    // ---------------------------------------------------------------- GPS

    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41); return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        } catch (_: SecurityException) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestGps()
    }

    override fun onLocationChanged(location: Location) {
        val first = lastLocation == null
        // GPS bearing is noise below walking pace; keep the last good heading.
        if (location.hasBearing() && location.hasSpeed() && location.speed > 2.5f) lastBearing = location.bearing
        lastLocation = location
        runOnUiThread {
            updateUserArrow(location)
            if (followCamera) followUser(location, animate = !first)
            updateNavigation(location)
            if (routeActive && radarSegments != null) showRadarAhead()
            refreshWeather(location)
            refreshUserTown(location)
            if ((conditionsAt?.distanceTo(location) ?: Float.MAX_VALUE) > 10_000f) runConditions(location)
            pendingDestination?.let { pendingDestination = null; requestRoute(it, traffic = true) }
            renderHud()
        }
    }

    // ---------------------------------------------------------------- reports

    private fun showReportDialog() {
        val types = arrayOf("Flooding", "Hail", "Fallen tree", "Road hazard", "High winds", "Accident")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Report a road hazard")
            .setItems(types) { _, which ->
                val loc = lastLocation
                if (loc == null) Toast.makeText(this, "Waiting for GPS before placing a report.", Toast.LENGTH_SHORT).show()
                else submitReport(types[which], loc.latitude, loc.longitude)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun submitReport(type: String, lat: Double, lon: Double) {
        val server = BuildConfig.STORMNAV_SERVER
        if (server.isBlank()) {
            Toast.makeText(this, "$type marked locally. Configure STORMNAV_SERVER to share reports with other users.", Toast.LENGTH_LONG).show()
            map.addMarker(MarkerOptions().position(LatLng(lat, lon)).title(type)); return
        }
        thread {
            try {
                val body = JSONObject().put("type", type).put("lat", lat).put("lon", lon).toString()
                val conn = (URL("$server/reports").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val ok = conn.responseCode in 200..299
                conn.disconnect()
                runOnUiThread { Toast.makeText(this, if (ok) "Report shared with StormNav users." else "Could not share report.", Toast.LENGTH_LONG).show() }
                if (ok) refreshReports()
            } catch (_: Exception) { runOnUiThread { Toast.makeText(this, "Report server unavailable.", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun refreshReports() {
        val server = BuildConfig.STORMNAV_SERVER
        if (server.isBlank() || !::map.isInitialized) return
        thread {
            try {
                val conn = (URL("$server/reports").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000 }
                val payload = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect()
                val rows = JSONArray(payload)
                runOnUiThread {
                    reportMarkers.forEach { map.removeMarker(it) }; reportMarkers.clear(); reportIds.clear()
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i)
                        val marker = map.addMarker(MarkerOptions().position(LatLng(item.getDouble("lat"), item.getDouble("lon")))
                            .title(item.getString("type")).snippet("${item.optInt("confirmations", 0)} confirmations • tap to update"))
                        reportMarkers.add(marker); reportIds[marker] = item.getInt("id")
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun showReportActions(id: Int, title: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title)
            .setItems(arrayOf("Confirm this report", "No longer there")) { _, choice ->
                val action = if (choice == 0) "confirm" else "dismiss"
                val server = BuildConfig.STORMNAV_SERVER
                if (server.isBlank()) Toast.makeText(this, "Connect the shared report server to update reports.", Toast.LENGTH_SHORT).show()
                else thread {
                    try {
                        val conn = (URL("$server/reports/$id/$action").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000 }
                        val ok = conn.responseCode in 200..299; conn.disconnect()
                        runOnUiThread { Toast.makeText(this, if (ok) "Report updated." else "Could not update report.", Toast.LENGTH_SHORT).show(); if (ok) refreshReports() }
                    } catch (_: Exception) { runOnUiThread { Toast.makeText(this, "Report server unavailable.", Toast.LENGTH_SHORT).show() } }
                }
            }.setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------- info dialogs

    /** Tap the banner while navigating: change or end the trip without digging through the Menu. */
    private fun showTripActions() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)) }
        box.addView(TextView(this).apply {
            text = if (routeActive) "Going to $routeDestinationName" else "Trip"
            setTextColor(Color.WHITE); textSize = 17f; typeface = HudStyle.BOLD; setPadding(0, 0, 0, dp(8))
        })
        var dialog: AlertDialog? = null
        box.addView(bigButton("🔍  New destination", HudStyle.INFO_BLUE) { dialog?.dismiss(); showDestinationDialog() })
        box.addView(bigButton(if (chaseMode) "■  End chase" else "■  End trip", Color.rgb(229, 57, 53)) { dialog?.dismiss(); stopRoute() })
        box.addView(bigButton("ℹ  Trip details", Color.rgb(110, 118, 130)) { dialog?.dismiss(); showTripDetails() })
        if (routeActive || waitMode) box.addView(bigButton("⛈  Storm options", HudStyle.STORM_PURPLE) { dialog?.dismiss(); showStormOptions(auto = false) })
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setView(box).setNegativeButton("Close", null).create()
        dialog.show()
    }

    private fun showTripDetails() {
        val lines = mutableListOf<String>()
        if (routeActive) {
            lines += "To: $routeDestinationName"
            if (destTempSummary.isNotBlank()) lines += "Destination now: $destTempSummary"
            lines += ""
        }
        if (routeActive && routingProvider.isNotBlank()) lines += "Route by: $routingProvider"
        if (routeActive && tomtomExtrasOn()) {
            val loc = lastLocation
            val prog = if (loc != null && routePoints.size >= 2) RouteMath.progressAlong(routePoints, routeCum, GeoPoint(loc.latitude, loc.longitude)) else 0.0
            val mins = (trafficDelayAhead(prog, TripMath.remainingMeters(routeTotalM, prog)) / 60).roundToInt()
            lines += "Traffic ahead: " + when {
                !incidentsKnown && routeTrafficDelayS <= 0 -> if (TomTomGate.open()) "checking…" else "not available right now"
                mins >= 1 -> "about +$mins min (included in your arrival time)"
                else -> "no delays reported"
            }
            routeIncidents.filter { it.startAlongM > prog && TrafficIncidents.worthSaying(it) }.take(3)
                .forEach { lines += "  • " + TrafficIncidents.message(it.copy(aheadM = it.startAlongM - prog), unitSystem) }
        }
        lines += "Radar on route: " + radarSummary.ifBlank { if (routeActive) "checking…" else "start a route to check" }
        lines += "NWS alerts: " + nwsSummary.ifBlank { if (routeActive) "checking…" else "start a route to check" }
        nwsDetails.forEach { lines += "  • $it" }
        lines += ""
        lines += "Storm Timer = minutes until you reach the next stretch of moderate or heavier rain, using storm motion measured from radar ~15 min apart (where the storm will be when you get there). Storms can still grow, weaken or turn."
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Trip weather")
            .setMessage(lines.joinToString("\n")).setPositiveButton("OK", null)
        if (routeActive && routeNowcast?.motion != null) b.setNeutralButton("🕒 Best time to leave") { _, _ -> showBestDeparture() }
        b.show()
    }

    private fun showWeatherDetails() {
        val w = weatherNow
        val msg = if (w == null) "Waiting for the National Weather Service hourly forecast at your location (US only)."
        else "Next-hour NWS forecast at your location:\n\n" +
            "Chance of rain: ${w.rainChancePct?.let { "$it%" } ?: "n/a"}\n" +
            "Wind: ${w.windMph?.let { Units.wind(it, UnitSystem.BOTH) } ?: "n/a"}\n" +
            "Temperature: ${w.tempF?.let { Units.tempFromF(it) } ?: "n/a"}\n\nRefreshed every 10 minutes or after ~8 km of driving."
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Conditions here")
            .setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- storm assist

    private fun currentPlanSpeed(): Double {
        val loc = lastLocation
        return TripMath.planningSpeed(if (loc != null && loc.hasSpeed()) loc.speed.toDouble() else null, routeAvgSpeedMps)
    }

    private fun currentProgress(): Double {
        val loc = lastLocation ?: return 0.0
        return if (routePoints.size >= 2) RouteMath.progressAlong(routePoints, routeCum, GeoPoint(loc.latitude, loc.longitude)) else 0.0
    }

    /** The detour waypoint, until the driver has passed it. */
    private fun activeVia(): GeoPoint? = routeVia?.takeIf { currentProgress() < routeViaAlongM - 1000.0 }

    private fun dist(m: Double) = Units.short(m, if (unitSystem == UnitSystem.BOTH) UnitSystem.IMPERIAL else unitSystem)

    private fun stormPhrase(seg: StormSegment, progressM: Double): String {
        val d = seg.startM - progressM
        val min = (d / currentPlanSpeed().coerceAtLeast(1.0) / 60.0).roundToInt()
        val what = if (seg.peak >= RadarIntensity.SEVERE) "Severe storm" else "Heavy storm"
        val warn = if (nwsSevereOnRoute) " under an NWS warning" else ""
        return if (d < 300) "$what$warn on your route now" else "$what$warn ahead in ${dist(d)} (about $min min), ${dist(seg.lengthM)} long"
    }

    /** Pops the storm choices once per storm, only for severe cells reached within the hour. */
    private fun maybePromptStorm(segs: List<StormSegment>, progress: Double) {
        if (!routeActive || waitMode || chaseMode || stormDialogOpen || searchPanel.isOpen || stormAlertMode == "off") return
        val storm = StormPlanner.promptFor(segs, progress, currentPlanSpeed(), nwsSevereOnRoute || stormAlertMode == "heavy") ?: return
        val key = StormPlanner.stormKey(storm)
        if (key == lastStormPromptKey) return
        lastStormPromptKey = key
        showStormOptions(auto = true, storm = storm)
    }

    private fun bigButton(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = HudStyle.panel(this@MainActivity, 16f, color, 2.5f)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(62)).apply { setMargins(0, dp(5), 0, dp(5)) }
    }

    /** Detour / wait / keep going, or (while waiting) resume / keep waiting. */
    private fun showStormOptions(auto: Boolean, storm: StormSegment? = null) {
        if (waitMode) { showWaitingOptions(); return }
        val progress = currentProgress()
        val seg = storm ?: radarSegments?.let { segs ->
            RadarRouteAnalyzer.ahead(segs, progress).filter { it.peak >= RadarIntensity.MODERATE }.minByOrNull { it.startM }
        }
        if (!routeActive || seg == null) { showTripDetails(); return }
        val phrase = stormPhrase(seg, progress)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)) }
        box.addView(TextView(this).apply {
            text = "⛈  $phrase"; setTextColor(Color.WHITE); textSize = 18f; typeface = HudStyle.BOLD; setPadding(0, 0, 0, dp(10))
        })
        var dialog: AlertDialog? = null
        box.addView(bigButton("🔀  Find a detour", HudStyle.INFO_BLUE) { dialog?.dismiss(); findDetour(seg) })
        box.addView(bigButton("⏸  Wait it out", HudStyle.STORM_PURPLE) { dialog?.dismiss(); findWaitStops(seg) })
        box.addView(bigButton("▶  Keep going", Color.rgb(110, 118, 130)) { dialog?.dismiss() })
        box.addView(TextView(this).apply {
            text = "Based on radar right now; storms move. Let a passenger choose, or decide when it's safe."
            setTextColor(Color.rgb(150, 160, 175)); textSize = 12f; setPadding(0, dp(6), 0, 0)
        })
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setView(box).create()
        dialog.setOnDismissListener { stormDialogOpen = false }
        stormDialogOpen = true
        dialog.show()
        if (auto) speak("$phrase. You can find a detour, wait it out, or keep going.")
    }

    private fun describeWeather(r: ScoredRoute): String {
        val worst = r.segments.maxByOrNull { it.peak } ?: return "no heavy weather on radar"
        val wet = r.segments.sumOf { it.lengthM }
        return "${RadarIntensity.label(worst.peak).lowercase(Locale.US)} for ${dist(wet)}"
    }

    /** Tries OSRM alternatives plus routes forced around each side of the storm; offers the best. */
    private fun findDetour(storm: StormSegment) {
        val loc = lastLocation ?: return
        val dest = routeDestination ?: return
        val from = GeoPoint(loc.latitude, loc.longitude)
        val vias = StormPlanner.detourVias(routePoints, routeCum, storm)
        setBannerMessage("Looking for a detour around the storm…")
        Toast.makeText(this, "Checking detours against radar…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val main = fetchRoutes(from, dest, alternatives = true)
                if (main.isEmpty()) throw IllegalStateException("no route")
                val around = vias.mapNotNull { v -> runCatching { fetchRoutes(from, dest, via = v).firstOrNull() }.getOrNull()?.let { it to v } }
                val all = main.map { it to null as GeoPoint? } + around
                val segs = stormSegmentsFor(all.map { it.first })
                val scored = all.indices.map { i ->
                    ScoredRoute(RouteOption(all[i].first.points, all[i].first.durationS, all[i].first.distanceM, i.toString()), segs[i])
                }
                val choice = StormPlanner.pickDetour(scored[0], scored.drop(1))
                runOnUiThread {
                    setBannerMessage(routeSteps.getOrNull(currentStepIndex)?.instruction ?: "")
                    if (choice == null) {
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("No good detour")
                            .setMessage("Every nearby route runs into this storm or adds a long way. Your route has ${describeWeather(scored[0])}. Waiting it out is usually the safer choice.")
                            .setPositiveButton("Wait it out") { _, _ -> findWaitStops(storm) }
                            .setNegativeButton("Keep going", null).show()
                        speak("No good detour found. Waiting it out is the safer choice.")
                    } else {
                        val idx = choice.route.option.tag.toInt()
                        val (picked, via) = all[idx]
                        val extraMin = (choice.extraS / 60.0).roundToInt()
                        val extra = if (extraMin <= 0) "No extra time" else "Adds about $extraMin min"
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🔀 Detour found")
                            .setMessage("$extra.\n\nDetour: ${describeWeather(choice.route)}.\nCurrent route: ${describeWeather(choice.baseline)}.")
                            .setPositiveButton("Take detour") { _, _ ->
                                applyRoute(dest, picked, via, announce = "Taking the detour. ${TripMath.duration(picked.durationS)} to ${dest.name}.")
                            }
                            .setNegativeButton("Keep my route", null).show()
                        speak("Detour found. $extra. Take it?")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBannerMessage(routeSteps.getOrNull(currentStepIndex)?.instruction ?: "")
                    Toast.makeText(this, "Couldn't check detours: ${e.message ?: "try again"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Lists gas stations, food, hotels and rest areas on the route before the storm. */
    private fun findWaitStops(storm: StormSegment) {
        val progress = currentProgress()
        val pts = routePoints; val cum = routeCum
        val corridor = StormPlanner.corridor(pts, cum, progress, storm.startM - 2_000.0)
        val dest = routeDestination ?: return
        if (corridor.size < 2) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Storm is very close")
                .setMessage("There's no room to reach a stop before the storm. If conditions get dangerous, pull over somewhere safe, away from trees and power lines, and follow National Weather Service guidance. I'll keep watching radar and tell you when your route clears.")
                .setPositiveButton("Wait here") { _, _ -> startWaiting(dest, null) }
                .setNegativeButton("Keep going", null).show()
            return
        }
        Toast.makeText(this, "Finding places to stop before the storm…", Toast.LENGTH_SHORT).show()
        thread {
            val places = placeSearch.waitPlacesAlong(corridor)
            val serviceDown = places.isEmpty() && placeSearch.lastOverpassIssue != null
            val stops = StormPlanner.waitStops(places, pts, cum, progress, storm.startM)
            runOnUiThread {
                val speed = currentPlanSpeed().coerceAtLeast(1.0)
                val labels = stops.map { st ->
                    val icon = when (st.place.category) { "fuel" -> "⛽"; "hotel", "motel" -> "🏨"; "rest_area" -> "🅿️"; "cafe" -> "☕"; else -> "🍔" }
                    val min = ((st.alongM - progress) / speed / 60.0).roundToInt()
                    "$icon  ${st.place.name}  ·  $min min (${dist(st.alongM - progress)})"
                } + "📍  Just tell me when it's clear (I'll stop on my own)" +
                    (if (serviceDown) listOf("🔄  Try loading stops again") else emptyList())
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(when {
                        serviceDown -> "Couldn't load stops right now (map service busy)"
                        stops.isEmpty() -> "No stops found before the storm"
                        else -> "Wait it out at…"
                    })
                    .setItems(labels.toTypedArray()) { _, which ->
                        when {
                            which < stops.size -> startWaiting(dest, stops[which].place)
                            which == stops.size -> startWaiting(dest, null)
                            else -> findWaitStops(storm)
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** Route to [stop] (if any), then keep re-checking radar on the way to [finalDest]. */
    private fun startWaiting(finalDest: Place, stop: PlaceResult?) {
        waitMode = true
        waitFinalDestination = finalDest
        waitStopName = stop?.name ?: ""
        waitStartedAt = System.currentTimeMillis()
        lastStormPromptKey = ""
        if (stop != null) {
            requestRoute(Place(stop.name, stop.lat, stop.lon))
            speak("Heading to ${stop.name} to wait out the storm.")
        } else {
            speak("Okay. I'll watch radar and tell you when your route is clear.")
        }
        ui.removeCallbacks(waitCheck)
        ui.postDelayed(waitCheck, WAIT_RECHECK_MS)
        renderHud()
    }

    private fun endWait() {
        waitMode = false
        waitFinalDestination = null
        waitStopName = ""
        ui.removeCallbacks(waitCheck)
    }

    /** Every few minutes while waiting: fresh route to the real destination, checked against radar. */
    private fun checkWaitClear() {
        val loc = lastLocation ?: return
        val dest = waitFinalDestination ?: return
        thread {
            try {
                val r = fetchRoutes(GeoPoint(loc.latitude, loc.longitude), dest).firstOrNull() ?: return@thread
                val segs = stormSegmentsFor(listOf(r)).first()
                if (StormPlanner.clearAhead(segs)) runOnUiThread { if (waitMode) offerResume(dest, r) }
            } catch (_: Exception) { /* try again next time */ }
        }
    }

    private fun offerResume(dest: Place, r: FetchedRoute) {
        if (stormDialogOpen) return
        stormDialogOpen = true
        val waited = ((System.currentTimeMillis() - waitStartedAt) / 60_000.0).roundToInt()
        speak("Good news. The storm has cleared your route. Ready to continue to ${dest.name}?")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("✅ Your route is clear")
            .setMessage("Radar shows no heavy storms on the next stretch to ${dest.name} (waited $waited min). ${TripMath.duration(r.durationS)} to go.")
            .setPositiveButton("Resume trip") { _, _ -> endWait(); applyRoute(dest, r, announce = "Resuming. ${TripMath.duration(r.durationS)} to ${dest.name}.") }
            .setNegativeButton("Keep waiting", null)
            .setOnDismissListener { stormDialogOpen = false }
            .show()
    }

    private fun showWaitingOptions() {
        val dest = waitFinalDestination ?: run { endWait(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)) }
        val waited = ((System.currentTimeMillis() - waitStartedAt) / 60_000.0).roundToInt()
        box.addView(TextView(this).apply {
            text = "⏸  Waiting out the storm ($waited min). I check radar every 3 minutes and will tell you when your route to ${dest.name} is clear."
            setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 0, 0, dp(10))
        })
        var dialog: AlertDialog? = null
        box.addView(bigButton("▶  Resume trip now", HudStyle.INFO_BLUE) { dialog?.dismiss(); endWait(); requestRoute(dest, traffic = true) })
        box.addView(bigButton("🔎  Check radar now", HudStyle.STORM_PURPLE) {
            dialog?.dismiss(); Toast.makeText(this, "Checking radar…", Toast.LENGTH_SHORT).show(); checkWaitClear()
        })
        box.addView(bigButton("Keep waiting", Color.rgb(110, 118, 130)) { dialog?.dismiss() })
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setView(box).create()
        dialog.show()
    }

    // ---------------------------------------------------------------- storm watch (where you are)

    private fun runStormWatch(loc: Location) {
        if (watchInFlight) return
        watchInFlight = true
        watchComputedAt = System.currentTimeMillis()
        val here = GeoPoint(loc.latitude, loc.longitude)
        thread {
            try {
                val nc = nowcast(RadarWindow.forRoute(listOf(here), padM = 150_000.0, maxPx = 512))
                val age = nc.ageS()
                val cells = StormMotion.cells(nc.grid, RadarIntensity.HEAVY)
                val nearest = cells.minByOrNull { RouteMath.haversine(here, it.center) }
                val nowLevel = StormMotion.levelAfter(nc.grid, here, nc.motion ?: Motion(0.0, 0.0, 0.0), age)
                val arr = nc.motion?.let { StormMotion.arrivalAt(nc.grid, here, it, RadarIntensity.HEAVY) }
                    ?.let { Arrival((it.startS - age).coerceAtLeast(0.0), (it.endS - age).coerceAtLeast(0.0), it.peak) }
                    ?.takeIf { it.endS > 0 }
                    ?: if (nowLevel >= RadarIntensity.HEAVY) Arrival(0.0, 600.0, nowLevel) else null
                // Severe weather heading for the parked car (hail risk), if it's away from you.
                val car = parking()?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
                val carArr = if (car != null && RouteMath.haversine(here, car) > 1500.0 && nc.motion != null)
                    StormMotion.arrivalAt(nc.grid, car, nc.motion, RadarIntensity.HEAVY, horizonS = 3600.0)
                        ?.let { Arrival((it.startS - age).coerceAtLeast(0.0), (it.endS - age).coerceAtLeast(0.0), it.peak) }?.takeIf { it.endS > 0 }
                    else null
                runOnUiThread {
                    carStormAlert(carArr)
                    watchNowcast = nc; watchCell = nearest; watchArrival = arr
                    watchComputedAt = System.currentTimeMillis(); watchInFlight = false
                    renderHud(); maybeWatchAlert()
                }
            } catch (_: Exception) {
                runOnUiThread { watchInFlight = false }
            }
        }
    }

    /** Spoken heads-up when a storm is due where you are within 30 minutes (not while navigating). */
    private fun maybeWatchAlert() {
        if (routeActive || chaseMode || stormAlertMode == "off") return
        val arr = watchArrival ?: return
        if (arr.startS > 30 * 60) return
        if (stormAlertMode == "severe" && arr.peak < RadarIntensity.SEVERE) return
        val key = "${arr.peak}-${(watchComputedAt / 1000 + arr.startS.toLong()) / 1200}"
        if (key == watchAlertKey) return
        watchAlertKey = key
        val what = RadarIntensity.label(arr.peak).lowercase(Locale.US)
        val msg = if (arr.startS < 120) "Heads up: $what is over your location now." else "Heads up: $what expected at your location in about ${TripMath.duration(arr.startS)}."
        speak(msg)
        Toast.makeText(this, "⛈ $msg", Toast.LENGTH_LONG).show()
    }

    private fun showStormWatch() {
        val loc = lastLocation
        val nc = watchNowcast
        if (loc == null || nc == null) {
            Toast.makeText(this, "Checking radar around you…", Toast.LENGTH_SHORT).show()
            loc?.let { runStormWatch(it) }; return
        }
        val here = GeoPoint(loc.latitude, loc.longitude)
        val lines = mutableListOf<String>()
        val cell = watchCell
        lines += cell?.let { StormMotion.describe(it, here, nc.motion, unitSystem) + "." } ?: "No heavy storms within about 90 miles."
        val arr = watchArrival
        val elapsed = (System.currentTimeMillis() - watchComputedAt) / 1000.0
        lines += when {
            arr == null && nc.motion == null -> "Storm motion couldn't be measured right now, so this is radar as it is."
            arr == null -> "Not expected to reach you in the next 2 hours."
            arr.startS - elapsed <= 60 -> "${RadarIntensity.label(arr.peak)} over you now; should pass in about ${TripMath.duration((arr.endS - elapsed).coerceAtLeast(60.0))}."
            else -> "${RadarIntensity.label(arr.peak)} expected here in about ${TripMath.duration(arr.startS - elapsed)}, lasting about ${TripMath.duration((arr.endS - arr.startS).coerceAtLeast(300.0))}."
        }
        lines += ""
        lines += "Estimated by measuring how storms moved on radar over the last ~15 minutes and projecting that forward. Good for the next hour or so; storms can grow, weaken or turn. Follow official warnings."
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Storms near you")
            .setMessage(lines.joinToString("\n")).setPositiveButton("OK", null)
        if (cell != null) b.setNeutralButton("🌪 Chase it") { _, _ -> startChase(cell, nc) }
        b.show()
    }

    // ---------------------------------------------------------------- best time to leave

    /** After a route is checked while parked: if waiting would dodge the storms, say so once. */
    private fun maybeSuggestDeparture() {
        val nc = routeNowcast ?: return
        val segs = radarSegments ?: return
        if (departHintShown || nc.motion == null || segs.none { it.peak >= RadarIntensity.HEAVY }) return
        val loc = lastLocation
        if (loc != null && loc.hasSpeed() && loc.speed > 3f) return // already driving
        departHintShown = true
        val pts = routePoints; val speed = currentPlanSpeed()
        thread {
            val opts = (0..8).map { i -> i * 900.0 to meetSegments(pts, nc, speed, 0.0, i * 900.0) }
            val best = StormMotion.bestDeparture(opts) ?: return@thread
            runOnUiThread {
                val at = TripMath.clock(System.currentTimeMillis() + (best * 1000).toLong(), DateFormat.is24HourFormat(this))
                val msg = "Storms are crossing your route. Leaving around $at should miss most of it."
                speak(msg)
                Toast.makeText(this, "🕒 $msg Trip details → Best time to leave.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBestDeparture() {
        val nc = routeNowcast ?: return
        val progress = currentProgress()
        val pts = routePoints; val speed = currentPlanSpeed()
        Toast.makeText(this, "Comparing departure times…", Toast.LENGTH_SHORT).show()
        thread {
            val opts = (0..8).map { i -> i * 900.0 to meetSegments(pts, nc, speed, progress, i * 900.0) }
            val best = StormMotion.bestDeparture(opts)
            runOnUiThread {
                val is24 = DateFormat.is24HourFormat(this)
                val lines = opts.map { (delay, segs) ->
                    val at = if (delay == 0.0) "Now" else TripMath.clock(System.currentTimeMillis() + (delay * 1000).toLong(), is24)
                    val worst = segs.maxByOrNull { it.peak }
                    val weather = if (worst == null) "dry" else "${RadarIntensity.label(worst.peak).lowercase(Locale.US)}, ${dist(segs.sumOf { it.lengthM })}"
                    "$at — $weather${if (delay == best) "  ✓ suggested" else ""}"
                }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🕒 Best time to leave")
                    .setMessage(lines.joinToString("\n") + "\n\nEach line: the worst weather you'd drive through and for how far, if you set off then. Projected from current storm motion; less reliable more than an hour or two out.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    // ---------------------------------------------------------------- current conditions

    /** Radar at your exact position, from whichever nowcast is newest. */
    private fun radarConditionHere(): Condition? {
        val loc = lastLocation ?: return null
        val nc = listOfNotNull(routeNowcast, watchNowcast).maxByOrNull { it.takenAt } ?: return null
        val lvl = StormMotion.levelAfter(nc.grid, GeoPoint(loc.latitude, loc.longitude), nc.motion ?: Motion(0.0, 0.0, 0.0), nc.ageS())
        return ConditionRules.fromRadar(lvl)
    }

    private fun currentConditions(): List<Condition> =
        if (!conditionWarnings) emptyList() else ConditionRules.merge(listOfNotNull(radarConditionHere()) + alertConditions + obsConditions)

    /** Every 5 minutes (and after ~6 mi of driving): nearest NWS station's observation + alerts here. */
    private fun runConditions(loc: Location) {
        if (conditionsInFlight) return
        conditionsInFlight = true
        conditionsAt = loc
        thread {
            var obs: List<Condition> = obsConditions
            var src = obsSource
            var alerts: List<Condition> = alertConditions
            try {
                val key = String.format(Locale.US, "%.1f,%.1f", loc.latitude, loc.longitude)
                val station = stationCache?.takeIf { it.first == key }?.second ?: run {
                    val pt = httpJson(String.format(Locale.US, "https://api.weather.gov/points/%.4f,%.4f", loc.latitude, loc.longitude))
                    val list = httpJson(pt.getJSONObject("properties").getString("observationStations"))
                    val props = list.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
                    (props.getString("stationIdentifier") to props.optString("name")).also { stationCache = key to it }
                }
                val o = httpJson("https://api.weather.gov/stations/${station.first}/observations/latest").getJSONObject("properties")
                val ts = runCatching { java.time.Instant.parse(o.optString("timestamp")).toEpochMilli() }.getOrDefault(0L)
                fun num(name: String): Pair<Double?, String?> = o.optJSONObject(name)?.let {
                    (if (it.isNull("value")) null else it.optDouble("value")) to it.optString("unitCode")
                } ?: (null to null)
                obs = if (System.currentTimeMillis() - ts > 2 * 3600_000L) emptyList() else {
                    val vis = num("visibility").first
                    val gust = num("windGust").let { ConditionRules.toMps(it.first, it.second) }
                    val wind = num("windSpeed").let { ConditionRules.toMps(it.first, it.second) }
                    ConditionRules.fromObservation(o.optString("textDescription"), vis, gust, wind, num("temperature").first, unitSystem)
                }
                src = "NWS station ${station.first}${if (station.second.isNotBlank()) " (${station.second})" else ""}"
            } catch (_: Exception) { /* outside the US, or NWS busy: keep the last values */ }
            try {
                val feats = httpJson(String.format(Locale.US, "%s%.4f,%.4f", NWS_ALERTS, loc.latitude, loc.longitude)).optJSONArray("features") ?: JSONArray()
                alerts = (0 until feats.length()).mapNotNull { i ->
                    feats.getJSONObject(i).optJSONObject("properties")?.optString("event")?.let { ConditionRules.fromAlert(it) }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                obsConditions = obs; obsSource = src; alertConditions = alerts
                conditionsInFlight = false
                renderConditions()
            }
        }
    }

    /** The strip under the banner, plus one spoken warning per condition (repeated after 45 min). */
    private fun renderConditions() {
        if (!::conditionsChip.isInitialized) return
        val list = currentConditions()
        if (list.isEmpty()) { conditionsChip.visibility = View.GONE; return }
        val shown = list.take(2).joinToString("  ·  ") { "${it.icon} ${it.text}" } + if (list.size > 2) "  +${list.size - 2}" else ""
        conditionsChip.text = shown
        val worst = list.maxOf { it.level }
        conditionsChip.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (worst >= 2) Color.argb(230, 183, 28, 28) else Color.argb(230, 191, 120, 0))
            setStroke(dp(1), Color.argb(160, 255, 255, 255))
        }
        conditionsChip.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        list.firstOrNull { now - (conditionSpokenAt[it.key] ?: 0L) > 45 * 60_000L }?.let { c ->
            list.forEach { conditionSpokenAt.putIfAbsent(it.key, 0L) }
            conditionSpokenAt[c.key] = now
            speak("Caution. ${c.text.replace(" · ", ", ")}.")
        }
    }

    private fun showConditions() {
        val list = currentConditions()
        if (list.isEmpty()) return
        val lines = list.map { c ->
            val from = when (c.source) { "radar" -> "radar at your position"; "alert" -> "active NWS alert"; else -> obsSource.ifBlank { "nearest NWS station" } }
            "${c.icon}  ${c.text}\n      from $from"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Conditions here")
            .setMessage(lines.joinToString("\n\n") + "\n\nStations can be several miles away; fog and showers can be very local. Slow down and leave extra room.")
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- parking spot

    private fun parking(): JSONObject? = runCatching { prefs.getString("parking", null)?.let { JSONObject(it) } }.getOrNull()

    private fun parkedAgo(at: Long): String {
        val min = ((System.currentTimeMillis() - at) / 60_000.0).roundToInt()
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${TripMath.duration(min * 60.0)} ago"
            else -> "${min / (24 * 60)} day${if (min >= 48 * 60) "s" else ""} ago"
        }
    }

    /** Remembers where the car is, looks up the street address, and marks it on the map. */
    private fun saveParking(loc: Location, auto: Boolean = false) {
        val o = JSONObject().put("lat", loc.latitude).put("lon", loc.longitude).put("at", System.currentTimeMillis()).put("address", "")
        prefs.edit().putString("parking", o.toString()).apply()
        carStormKey = ""
        showParkingMarker()
        Toast.makeText(this, if (auto) "🅿️ Parking spot saved. Menu → Find my car." else "🅿️ Parking spot saved.", Toast.LENGTH_LONG).show()
        thread {
            val addr = placeSearch.reverseAddress(loc.latitude, loc.longitude) ?: return@thread
            runOnUiThread {
                val cur = parking() ?: return@runOnUiThread
                if (cur.optLong("at") == o.optLong("at")) {
                    prefs.edit().putString("parking", cur.put("address", addr).toString()).apply()
                    showParkingMarker()
                }
            }
        }
    }

    private fun showParkingMarker() {
        if (!::map.isInitialized) return
        parkingMarker?.let { map.removeMarker(it) }
        parkingMarker = null
        val car = parking() ?: return
        parkingMarker = map.addMarker(MarkerOptions().position(LatLng(car.getDouble("lat"), car.getDouble("lon")))
            .title("🅿️ Your car").snippet(listOf(car.optString("address"), "parked ${parkedAgo(car.optLong("at"))}").filter { it.isNotBlank() }.joinToString(" · ")))
    }

    private fun clearParking() {
        prefs.edit().remove("parking").apply()
        parkingMarker?.let { if (::map.isInitialized) map.removeMarker(it) }
        parkingMarker = null
    }

    private fun showFindCar() {
        val car = parking() ?: run { Toast.makeText(this, "No parking spot saved yet.", Toast.LENGTH_SHORT).show(); return }
        val carPt = GeoPoint(car.getDouble("lat"), car.getDouble("lon"))
        val lines = mutableListOf<String>()
        car.optString("address").takeIf { it.isNotBlank() }?.let { lines += it }
        lines += "Parked ${parkedAgo(car.optLong("at"))}"
        lastLocation?.let {
            val here = GeoPoint(it.latitude, it.longitude)
            val d = RouteMath.haversine(here, carPt)
            lines += if (d < 30) "You're right by it." else "${dist(d)} ${StormMotion.compass(StormMotion.bearing(here, carPt))} of you"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🚗 Your car")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Navigate") { _, _ -> goTo(PlaceResult("Your parked car", car.optString("address"), carPt.lat, carPt.lon, PlaceKind.SAVED, "car")) }
            .setNeutralButton("Clear spot") { _, _ -> clearParking() }
            .setNegativeButton("Show on map") { _, _ ->
                pauseFollow()
                val here = lastLocation
                if (here != null && RouteMath.haversine(GeoPoint(here.latitude, here.longitude), carPt) > 50) {
                    val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                        .include(LatLng(here.latitude, here.longitude)).include(LatLng(carPt.lat, carPt.lon)).build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, dp(110)))
                } else map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(carPt.lat, carPt.lon), 17.0))
            }.show()
    }

    /** One warning per storm when severe weather (or heavy, if chosen) is heading for the parked car. */
    private fun carStormAlert(arr: Arrival?) {
        if (arr == null || stormAlertMode == "off") return
        if (arr.peak < RadarIntensity.SEVERE && stormAlertMode != "heavy") return
        val key = "${arr.peak}-${(System.currentTimeMillis() / 1000 + arr.startS.toLong()) / 1800}"
        if (key == carStormKey) return
        carStormKey = key
        val hail = if (arr.peak >= RadarIntensity.SEVERE) " Hail is possible; consider moving it under cover." else ""
        val msg = if (arr.startS < 120) "A ${RadarIntensity.label(arr.peak).lowercase(Locale.US)} storm is over your parked car now.$hail"
            else "A ${RadarIntensity.label(arr.peak).lowercase(Locale.US)} storm is heading for your parked car, in about ${TripMath.duration(arr.startS)}.$hail"
        speak(msg)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🚗 Storm heading for your car")
            .setMessage(msg).setPositiveButton("OK", null)
            .setNeutralButton("Navigate to car") { _, _ -> showFindCar() }.show()
    }

    // ---------------------------------------------------------------- storm chaser

    private fun chaseNow(cell: StormCell): GeoPoint =
        chaseMotion?.let { StormMotion.moved(cell.center, it, (System.currentTimeMillis() - chaseUpdatedAt) / 1000.0) } ?: cell.center

    private fun chaseStatus(): String {
        val cell = chaseCell ?: return "🌪 Storm chase: pick a storm"
        val loc = lastLocation ?: return "🌪 Chasing"
        val here = GeoPoint(loc.latitude, loc.longitude)
        val desc = StormMotion.describe(cell.copy(center = chaseNow(cell)), here, chaseMotion, unitSystem)
        val ago = ((System.currentTimeMillis() - chaseUpdatedAt) / 60_000.0).roundToInt()
        return "🌪 Chasing: $desc · radar ${if (ago <= 0) "just now" else "$ago min ago"}"
    }

    /** Safety notice (once), then pick a storm — or chase [cell] straight away. */
    private fun startChase(cell: StormCell? = null, nc: Nowcast? = null) {
        val go = {
            if (cell != null && nc != null) beginChase(cell, nc.motion, nc.grid) else pickChaseTarget()
        }
        if (prefs.getBoolean("chaseAgreed", false)) { go(); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🌪 Storm chaser mode")
            .setMessage("Storm chasing is dangerous.\n\n• Radar is a few minutes old and storms change fast.\n• Never drive into a tornado, hail core or flooded road. Turn around, don't drown.\n• Keep an escape route and obey traffic laws.\n• Let a passenger run the app.\n\nStormNav predicts where the storm's core will be when you get there and stops short of it by the distance set in the Menu (default 2 mi). It reroutes every 2 minutes as the storm moves. It can't see tornadoes, hail or downed lines.")
            .setPositiveButton("I understand") { _, _ -> prefs.edit().putBoolean("chaseAgreed", true).apply(); go() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Lists strong storms around you to choose from. */
    private fun pickChaseTarget() {
        val loc = lastLocation ?: run { Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show(); return }
        val here = GeoPoint(loc.latitude, loc.longitude)
        Toast.makeText(this, "Finding storms near you…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val nc = nowcast(RadarWindow.forRoute(listOf(here), padM = 250_000.0, maxPx = 768))
                val cells = StormMotion.cells(nc.grid, RadarIntensity.HEAVY)
                    .sortedWith(compareByDescending<StormCell> { it.peak }.thenBy { RouteMath.haversine(here, it.center) })
                    .filter { RouteMath.haversine(here, it.center) < 250_000.0 }.take(8)
                runOnUiThread {
                    chaseGrid = nc.grid
                    if (cells.isEmpty()) {
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("No storms to chase")
                            .setMessage("Radar shows no heavy storms within about 150 miles right now.").setPositiveButton("OK", null).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Pick a storm to chase")
                        .setItems(cells.map { (if (it.peak >= RadarIntensity.SEVERE) "🟣 " else "🔴 ") + StormMotion.describe(it, here, nc.motion, unitSystem) }.toTypedArray()) { _, i ->
                            beginChase(cells[i], nc.motion, nc.grid)
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Radar unavailable: ${e.message ?: "try again"}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Long-press while chasing: chase the storm nearest that spot. */
    private fun chaseNear(p: GeoPoint) {
        val g = chaseGrid ?: watchNowcast?.grid ?: run { pickChaseTarget(); return }
        val cell = StormMotion.cells(g, RadarIntensity.HEAVY).minByOrNull { RouteMath.haversine(p, it.center) }
            ?.takeIf { RouteMath.haversine(p, it.center) < 30_000.0 }
        if (cell == null) { Toast.makeText(this, "No heavy storm near that spot.", Toast.LENGTH_SHORT).show(); return }
        beginChase(cell, chaseMotion ?: watchNowcast?.motion, g)
    }

    private fun beginChase(cell: StormCell, motion: Motion?, grid: LevelGrid) {
        endWait()
        chaseMode = true
        chaseCell = cell; chaseMotion = motion; chaseGrid = grid
        chaseUpdatedAt = System.currentTimeMillis()
        speak("Storm chase started. Stay safe.")
        routeToChase(force = true)
        ui.removeCallbacks(chaseCheck); ui.postDelayed(chaseCheck, CHASE_RECHECK_MS)
        renderHud()
    }

    /** Route to where the core will be when you arrive, stopping short by the chosen buffer. */
    private fun routeToChase(force: Boolean) {
        val cell = chaseCell ?: return
        val loc = lastLocation ?: return
        val from = GeoPoint(loc.latitude, loc.longitude)
        val core = chaseNow(cell)
        val target = StormMotion.stopShort(from, StormMotion.intercept(from, core, chaseMotion), chaseBufferMi * 1609.344)
        updateChaseMarker(core)
        val current = routeDestination
        val moved = current == null || RouteMath.haversine(GeoPoint(current.lat, current.lon), target) > 3_000.0
        if (!force && !moved) return
        if (RouteMath.haversine(from, target) < 800.0) { speak("You're at the storm's predicted position."); return }
        requestRoute(Place("Storm intercept", target.lat, target.lon),
            announce = if (force) "Routing to intercept the storm." else "Storm moved. Rerouting.")
    }

    private fun updateChaseMarker(core: GeoPoint) {
        if (!::map.isInitialized) return
        chaseMarker?.let { map.removeMarker(it) }
        chaseMarker = map.addMarker(MarkerOptions().position(LatLng(core.lat, core.lon)).title("🌪 Storm core")
            .snippet(chaseMotion?.let { "Moving ${StormMotion.compass(it.headingDeg)}" } ?: "Storm core"))
    }

    /** Every 2 minutes: re-find the storm on fresh radar near where it should be, then reroute. */
    private fun updateChase() {
        val cell = chaseCell ?: return
        val predicted = chaseNow(cell)
        lastChaseCheckAt = System.currentTimeMillis()
        thread {
            try {
                val nc = nowcast(RadarWindow.forRoute(listOf(predicted), padM = 120_000.0, maxPx = 512))
                val found = StormMotion.cells(nc.grid, RadarIntensity.HEAVY)
                    .filter { RouteMath.haversine(predicted, it.center) < 30_000.0 }
                    .minByOrNull { RouteMath.haversine(predicted, it.center) - it.peak * 4_000.0 }
                runOnUiThread {
                    if (!chaseMode) return@runOnUiThread
                    if (found == null) {
                        speak("I've lost track of the storm. It may have weakened.")
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Lost the storm")
                            .setMessage("Radar no longer shows a heavy core near where this storm was heading. It may have weakened or merged.")
                            .setPositiveButton("Pick another") { _, _ -> pickChaseTarget() }
                            .setNegativeButton("Stop chase") { _, _ -> endChase(); stopRoute() }.show()
                        return@runOnUiThread
                    }
                    chaseCell = found; chaseGrid = nc.grid
                    if (nc.motion != null) chaseMotion = nc.motion
                    chaseUpdatedAt = nc.takenAt
                    routeToChase(force = false)
                    renderHud()
                }
            } catch (_: Exception) { /* try again next cycle */ }
        }
    }

    private fun showChaseInfo() {
        val cell = chaseCell ?: run { pickChaseTarget(); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("🌪 Storm chase")
            .setMessage(chaseStatus().removePrefix("🌪 Chasing: ") + "\n\nPeak: ${RadarIntensity.label(cell.peak)}. Aiming ${if (chaseBufferMi == 0) "at the core" else "$chaseBufferMi mi short of the core"}; rerouting every 2 minutes.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Pick another") { _, _ -> pickChaseTarget() }
            .setNegativeButton("Stop chase") { _, _ -> endChase(); stopRoute() }.show()
    }

    private fun endChase() {
        chaseMode = false
        chaseCell = null
        ui.removeCallbacks(chaseCheck)
        chaseMarker?.let { if (::map.isInitialized) map.removeMarker(it) }
        chaseMarker = null
    }

    // ---------------------------------------------------------------- support

    private fun supportLinks(): List<Pair<String, String>> = listOfNotNull(
        BuildConfig.SUPPORT_PAYPAL.trim().takeIf { it.isNotEmpty() }?.let { "PayPal" to "https://paypal.me/${it.removePrefix("@")}" },
        BuildConfig.SUPPORT_VENMO.trim().takeIf { it.isNotEmpty() }?.let { "Venmo" to "https://venmo.com/u/${it.removePrefix("@")}" }
    )

    /** On opening: the first time, then at most once a week; never mid-drive; "Don't ask again" is final. */
    private fun maybeAskForCoffee(attempt: Int = 0) {
        if (supportLinks().isEmpty() || isFinishing) return
        // Don't stack on top of the first-run location permission prompt.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && attempt < 6) {
            ui.postDelayed({ maybeAskForCoffee(attempt + 1) }, 10_000); return
        }
        prefs.edit().putInt("opens", prefs.getInt("opens", 0) + 1).apply()
        val last = prefs.getLong("coffeeShownAt", 0L)
        val week = 7L * 24 * 60 * 60 * 1000
        if (prefs.getBoolean("coffeeNever", false) || System.currentTimeMillis() - last < week) return
        if (routeActive || (::searchPanel.isInitialized && searchPanel.isOpen) || (::menuPanel.isInitialized && menuPanel.isOpen)) return
        prefs.edit().putLong("coffeeShownAt", System.currentTimeMillis()).apply()
        showCoffee(fromMenu = false)
    }

    private fun showCoffee(fromMenu: Boolean) {
        val links = supportLinks()
        if (links.isEmpty()) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(10)) }
        box.addView(TextView(this).apply {
            text = "☕"; textSize = 40f; gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "StormNav is free, with no ads.\nIf it's helped you dodge a storm, you can buy me a coffee. Thank you!"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(14))
        })
        var dialog: AlertDialog? = null
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.forEach { (label, url) ->
            buttons.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
                background = HudStyle.panel(this@MainActivity, 14f, HudStyle.INFO_BLUE, 2f)
                setOnClickListener {
                    dialog?.dismiss()
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    catch (_: Exception) { Toast.makeText(this@MainActivity, url, Toast.LENGTH_LONG).show() }
                }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        box.addView(buttons)
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setView(box)
            .setNegativeButton(if (fromMenu) "Close" else "Not now", null)
        if (!fromMenu) b.setNeutralButton("Don't ask again") { _, _ -> prefs.edit().putBoolean("coffeeNever", true).apply() }
        dialog = b.create()
        dialog.show()
    }

    private fun showRadarInfo() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("StormNav radar and alerts")
            .setMessage("Radar is drawn beneath the roads so highways and streets stay visible. StormNav also reads the current NOAA radar image along your route and reports moderate-or-heavier returns ahead (refreshed every 5 minutes), plus active National Weather Service warnings at points along the route. The gauges show the NWS hourly forecast where you are. It does not yet predict storm movement, and the radar intensity is estimated from image colors, so treat it as a heads-up, not a guarantee of safe conditions. Always follow official warnings and drive safely.\n\nImagery © Esri, Maxar, Earthstar Geographics. Roads © OpenStreetMap contributors via OpenFreeMap. Radar: NOAA/NWS MRMS.")
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume(); requestGpsIfPermitted()
        ui.removeCallbacks(weatherRecheck); ui.postDelayed(weatherRecheck, WEATHER_RECHECK_MS)
        ui.removeCallbacks(stormWatch); ui.postDelayed(stormWatch, 8_000)
        ui.removeCallbacks(radarPulse); ui.post(radarPulse)
        if (::map.isInitialized) { ui.removeCallbacks(trafficPulse); ui.postDelayed(trafficPulse, 3000) }
        if (routeActive) { ui.removeCallbacks(incidentCheck); ui.postDelayed(incidentCheck, 5000) }
    }
    private fun requestGpsIfPermitted() { if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestGps() }
    override fun onPause() { ui.removeCallbacks(weatherRecheck); ui.removeCallbacks(stormWatch); ui.removeCallbacks(radarPulse); ui.removeCallbacks(conditionsCheck); ui.removeCallbacks(trafficPulse); ui.removeCallbacks(incidentCheck); mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    /**
     * Android 16 apps get the new predictive Back gesture, which no longer calls onBackPressed.
     * While the search or menu panel is open, StormNav claims Back to close it; otherwise
     * Back goes to the system as usual (with its animation).
     */
    private var backCallback: Any? = null

    private fun updateBackHandling() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val needed = searchPanel.isOpen || menuPanel.isOpen
        if (needed && backCallback == null) {
            val cb = android.window.OnBackInvokedCallback {
                if (searchPanel.isOpen) searchPanel.close() else if (menuPanel.isOpen) menuPanel.close()
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as android.window.OnBackInvokedCallback)
            backCallback = null
        }
    }

    @Deprecated("Still delivered for apps that don't opt in to predictive back")
    override fun onBackPressed() {
        when {
            ::searchPanel.isInitialized && searchPanel.isOpen -> searchPanel.close()
            ::menuPanel.isInitialized && menuPanel.isOpen -> menuPanel.close()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(radarRecheck); ui.removeCallbacks(weatherRecheck); ui.removeCallbacks(waitCheck); ui.removeCallbacks(chaseCheck); ui.removeCallbacks(autoResume); ui.removeCallbacks(conditionsCheck)
        mapView.onDestroy()
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop(); tts?.shutdown(); super.onDestroy()
    }
}
