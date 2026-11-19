# StormNav 1.4.0

StormNav is an Android-first, open-source weather-aware navigation prototype. Its defining screen is a road map with a translucent NOAA radar layer drawn **on the same map canvas**, so the driver can inspect precipitation returns against actual roads and route geometry.

## Routing (free)

`Routing.kt` tries, in order: **FOSSGIS OSRM** (routing.openstreetmap.de, the community server behind openstreetmap.org directions; free, no key, ≤1 request/second, enforced by StormNav), **openrouteservice** (free plan, 2,000 directions/day; only if `orsApiKey` is set in `gradle.properties`), then the **OSRM demo server**. Trip details shows which one answered.

## Publishing to Google Play

- Targets Android 16 (API 36), as Google requires for new apps; CI attaches a signed `.aab` (App Bundle) to every release for upload.
- Store copy, content-rating and data-safety answers: `docs/PLAY_STORE.md`. Privacy policy: `docs/PRIVACY.md`. Graphics: `docs/play-store-icon-512.png`, `docs/play-feature-graphic-1024x500.png`.
- The release key (`app/signing/stormnav-release.jks`) becomes your **upload key** under Play App Signing.

## New in 1.3.0: see further ahead, current-condition warnings

- **Your marker sits lower** (80 % down the screen by default when heading-up) so more of the road ahead is visible. Menu → Map → Center / Lower / Bottom.
- **Condition warnings** where you are, from radar at your exact position (every new frame), the nearest NWS station's latest observation and active NWS alerts (every 5 min or ~6 mi): dense fog / low visibility, heavy rain or a storm over you, freezing rain and ice risk, snow, high wind gusts, flooding, winter-weather, wind and fog advisories. Shown as an amber (caution) or red (danger) strip under the banner, spoken once per condition (again after 45 min), tap for sources. Menu → Condition warnings on/off. Logic in `Conditions.kt`, tests in `ConditionsTest.kt`.
- NWS alerts checked along a route now include fog, ice, snow, winter, blizzard, dust and smoke, not just storms.

## New in 1.2.0: normal-GPS camera

- Follows you whenever you're driving, with or without a route: heading-up, tilted, you in the lower third, zoom set by speed (closer in town, further out on the highway). Parked with no route: flat north-up overview for reading radar. Menu → Map → Heading up / North up.
- Pan or pinch-zoom and a **⌖ Re-center** button appears (beside Menu in landscape, bottom-left in portrait); while navigating, following also resumes on its own 12 s after you stop touching the map.

## New in 1.1.0: parking spot, quicker trip changes

- **Parking spot:** Menu → Parking → Save parking spot (or automatically when you arrive at a destination; can be turned off). A 🅿️ marker shows it, with the street address looked up. **Find my car** shows the address, how long ago, distance and direction ("0.3 mi NE"), with Navigate / Show on map / Clear. Search has a 🅿️ My car chip.
- **Storm watch for your car:** if severe weather (hail risk), or heavy if you chose that sensitivity, is heading for the parked car within the hour, StormNav warns you once.
- **Trip actions:** tap the banner while navigating for New destination / End trip / Trip details / Storm options. A new destination also ends Storm Chaser or wait-it-out.

## New in 1.0.0: your marker and your voice

- **Map marker:** Arrow, Car, Truck (semi), Minivan or Motorcycle, drawn top-down in code (`markerBitmap` in `HudViews.kt`), painted in your route-line colour and rotated with your heading. Menu → Your marker on the map.
- **Voices:** Menu → Voice: pick from the voices installed on the phone (your language first; tap to hear each), speech speed (Slower / Normal / Faster), switch text-to-speech engine when more than one is installed (e.g. Google and Samsung), and a shortcut to Android's settings to download more voices. All remembered.

## New in 0.9.1: faster radar updates

- Checks NOAA's frame list every 30 s (Menu: 30 s / 1 min / 2 min); NOAA publishes a new frame about every 2 minutes. When one appears, the map radar swaps to that exact frame (no stale cached tiles; old layer removed after the new one loads) and the route check, storm watch and chase refresh immediately instead of on 4–5 minute timers.
- "📡 Radar 10:37 AM · 1 min ago" badge under the banner (amber if older than 10 minutes).

## New in 0.9.0: storm motion, storm watch, best time to leave, Storm Chaser

- **Storm motion:** NOAA's radar service keeps ~2 hours of frames (about every 2 min). StormNav compares the newest frame with one ~15 min older (cross-correlating moderate-or-stronger echoes) to get speed and direction, then moves the radar forward along that motion ("nowcasting"). `StormMotion.kt`, tested in `StormMotionTest.kt` and live against NOAA in `LiveRadarTest.kt`.
- **Storm watch (even parked):** every 5 minutes while the app is open, the Storm Timer card shows *Storm here in 35 min* / *NOW* / *Nearest storm 18 mi* / *Clear*. Tap it for "Severe storm 18 mi WSW, moving ENE at 32 mph; expected here in ~35 min, lasting ~20 min." A spoken heads-up when one is due within 30 minutes (follows the storm-popup setting).
- **Routes see the future:** the route check now asks where each storm will be when you reach each stretch, and the Storm Timer, popups, detours and "wait it out" all use it. Works before you set off.
- **🕒 Best time to leave:** Trip details compares leaving now vs. every 15 minutes for 2 hours and marks the suggestion; if you're parked and waiting would dodge the storms, StormNav says so once.
- **🌪 Storm Chaser mode** (Menu → Storm chaser): safety notice once, then pick from nearby heavy/severe cells (or long-press a storm on the map, or "Chase it" from the storm-watch card). Routes to where the core will be when you arrive, stopping short by 0/1/2/5 mi (default 2), re-finds the storm on fresh radar every 2 minutes and reroutes when it moves more than ~2 mi. A map marker shows the core; the Storm card shows distance to it.

## New in 0.8.0: settings panel and themes

- **Menu is now a scrolling side panel** (right side in landscape, bottom in portrait) that leaves the map visible and undimmed, so radar opacity changes show live. Fixes the old menu cutting off its last rows (radar opacity, Buy me a coffee) in landscape.
- **Settings:** radar on/off and opacity; 6 HUD colour themes (Classic, Midnight, Forest, Sunset, Crimson, Glass); 5 route line colours chosen to stand out against radar; Satellite/Dark map; show/hide gauges; miles/km/both; voice on/off; storm popups (Severe only / Heavy + severe / Off). All remembered.
- **☕ Buy me a coffee** is the first button in the Menu; the opening card now appears on the first launch (after the location prompt), then at most weekly.

## New in 0.7.0: storm assist (detour or wait it out)

- **Automatic popup** when radar shows a *severe* core on your route within the next hour, or a *heavy* one while the NWS has a Severe Thunderstorm or Tornado Warning on the route. It speaks the warning and offers three big buttons, once per storm. Tap the Storm Timer card (or Menu → Storm options) to open the same choices any time.
- **🔀 Find a detour:** asks OSRM for alternative routes plus two routes forced around either side of the storm (waypoints 15–60 km off the storm's middle), scores every candidate against one fresh radar image, and offers the best one if it at least halves storm exposure (or avoids the worst intensity) without adding more than 25 min / 40 %. Shows the extra time and what each route runs into. Re-routing after a wrong turn keeps the detour waypoint until you pass it.
- **⏸ Wait it out:** lists gas stations, restaurants/cafés, hotels and rest areas within 1.5 km of your route that you reach at least 3 km before the storm (OpenStreetMap via Overpass), with minutes to each. StormNav guides you there, then every 3 minutes fetches a fresh route to your real destination and checks it against radar; when the next 50 km is free of heavy storms it says so and offers **Resume trip**. "Resume now" and "Check radar now" are in the Storm Timer card while waiting.
- Decision logic is in `StormPlanner.kt` with tests in `StormPlannerTest.kt`. All of it uses radar *as it is now*; StormNav still doesn't forecast storm motion, which is why waiting re-checks instead of guessing a time.

## Signing and releases

- CI builds a **release** APK signed with `app/signing/stormnav-release.jks` (PKCS12, alias `stormnav`). The keystore is committed on purpose and is useless without its password, which lives only in the GitHub Actions secret `STORMNAV_KEYSTORE_PASSWORD`. Without that secret, builds fall back to a temporary debug key.
- **Keep the password somewhere safe** (a password manager). Updates, including future Play Store updates, must be signed with this same key. Don't make the repo public without rotating the key.
- `versionCode` is `100 + GitHub run number`, so every CI build installs as an update over the last.
- Each build is published at **Releases** with a direct `.apk` download.
- "Buy me a coffee": set `supportPaypal` / `supportVenmo` in `gradle.properties` (usernames). The card shows from the 2nd opening, at most weekly, never during a route, with "Don't ask again"; it is also in the Menu.

## New in 0.6.0: Waze-style destination search

- **Full-screen search** (Menu → Search destination, or tap the top banner when no route is active): results as you type, distance to each, recents, and Home / Work chips (long-press any result to save it).
- **Press Go and it goes:** the keyboard's Go button finds the best match and starts the route immediately. Reopen search to pick one of the other matches.
- **"Springfield Walmart", "walmart in springfield mo", "gas":** town + business queries find the town nearest you, then the business in it; a bare brand or category finds the nearest ones.
- **Street addresses** use the free US Census geocoder, which covers nearly all US addresses, including rural ones missing from OpenStreetMap. Typing only "500 Main St" fills in your current town.
- **Drop a pin:** long-press anywhere on the map to navigate there.
- New app icon (adaptive, all densities); `docs/play-store-icon-512.png` for a future store listing.
- Search logic is in `PlaceSearch.kt` with offline tests (`PlaceSearchTest`) and a live check against the real services that runs on CI (`LiveSearchTest`, `LIVE_SEARCH=1`).

## New in 0.5.0: driving HUD

The driving screen was redesigned as a car-mount HUD (works in landscape and portrait; rotating the phone no longer drops the route):

- **Top banner:** turn arrow drawn for the actual maneuver, distance to the turn, instruction using road numbers when OSRM has them ("Turn Right onto SR-29"), info button, and time + distance remaining.
- **Left gauges:** rain probability, wind and temperature from the NWS hourly forecast *at your current position* (refreshed every 10 min or ~8 km). Tap a gauge for details.
- **Right column:** analog speedometer (GPS speed), ETA clock, and a **Storm Timer**: minutes until the route reaches the next stretch of moderate-or-heavier radar returns at your current pace ("Clear", "NOW" when you're in it). It uses the existing `RadarRouteAnalyzer`; it still does not predict storm motion.
- **Map:** new satellite basemap (Esri World Imagery + OpenFreeMap road lines/labels), radar still inserted beneath roads. Dark basemap is still available. Heading-up, tilted follow camera with a blue nav arrow; dragging the map pauses follow, Menu → My location resumes.
- **Menu:** destination, my location, report hazard, stop route / trip details, satellite/dark, units (mi / km / both), radar opacity — all remembered between launches.
- New Android-free logic in `Trip.kt` (`WeatherParse`, `TripMath`, `StormTimer`, `ManeuverKind`) with tests in `TripTest.kt`; HUD widgets are canvas-drawn in `HudViews.kt` (no image assets); basemap styles in `MapStyles.kt`; new launcher icon.
- `docs/hud-preview.html` is a self-contained browser simulator of the new screen (simulated map, storm and drive) for showing the design without a phone.
- Check Esri's World Imagery terms of use before distributing beyond personal/small-group testing.

## Current prototype contents

- Destination search using the public Photon OpenStreetMap geocoder.
- Route calculation using the public OSRM demo routing endpoint, with maneuver steps.
- Foreground turn-by-turn guidance with GPS-follow camera behavior and Android Text-to-Speech prompts (advance notice and near-turn reminder).
- Route matching against the planned route geometry, with off-route detection and automatic route recalculation after several reliable GPS fixes.
- A highlighted route line drawn above the radar and road layers.
- Active NWS storm/flood/wind alert checks at route points sampled by distance (every 20 km, spacing widened on long routes so the whole trip is covered; wind-chill alerts ignored).
- **New in 0.4.0: radar on your route.** One NOAA radar image covering the route is downloaded and read pixel by pixel along the route. Stretches with moderate-or-heavier returns are reported as "Heavy rain in 42 km (~35 min), 9 km long", refreshed every 5 minutes, updated as you drive, and spoken once for heavy/severe cores. The logic lives in `RadarRoute.kt` (no Android dependencies) with JUnit tests in `app/src/test`.
- **Units:** every distance shows metric and imperial together (e.g. `4.2 km (2.6 mi)`, `350 m (1150 ft)`), including spoken prompts. The destination's forecast temperature from NWS is shown as `61°F (16°C)` (US destinations only). Formatting lives in `Units.kt`.
- Fixes in 0.4.0: `STORMNAV_SERVER` BuildConfig escaping (the old value would not compile), UI sizes now in dp (buttons were ~15 dp tall), panels respect status/navigation bars on Android 15 edge-to-edge, screen stays on while navigating, radar layer falls back to "below labels" if no road layer id matches.

- Kotlin Android app using MapLibre Native Android.
- OpenFreeMap Dark vector basemap, using OpenStreetMap data.
- NOAA CONUS MRMS base-reflectivity WMS radar overlay.
- Explicit layer ordering: radar is inserted immediately below the first road/transportation layer, leaving road casings, road fills, bridges, and labels visible above precipitation.
- Radar opacity slider (default 62%) for balancing precipitation visibility with road readability.
- Android GPS location marker and recenter button.
- Hazard-report UI (flooding, hail, fallen tree, road hazard, high winds, accident).
- Optional small FastAPI + SQLite report service with 90-minute report expiry, duplicate suppression, and confirmation counts.

This is an **early source prototype**, not a road-ready navigation product. The NOAA WMS layer request and visual alignment must still be validated on a real device against the current NOAA radar viewer. The road-over-radar layer order is implemented in the MapLibre style stack, but it has not yet been device-tested. The 0.3.0 prototype improves foreground turn-by-turn guidance with route-geometry matching and basic automatic recalculation after repeated off-route GPS readings. It checks active NWS alerts at more evenly spaced route-distance samples. It does not yet provide background navigation, traffic-aware routing, radar-derived storm-cell prediction, or automatic weather rerouting. Do not use it as a safety-critical weather alert system.

## Open it in Android Studio

1. Install current Android Studio and its Android SDK (API 35 or newer).
2. Open this `stormnav` folder as an existing Gradle project.
3. Let Gradle sync and download dependencies from Maven Central.
4. Connect the Galaxy S22+ with USB debugging enabled, or use an emulator.
5. Run the `app` configuration. The first launch asks for location permission.

The app currently starts over Indianapolis as a fallback camera position, then moves to the device's GPS location after permission is granted. This is only a default map center, not a location lookup.

## Build a debug APK without installing anything (GitHub Actions)

Push this folder to a GitHub repo and run **Actions → Build StormNav APK → Run workflow**. The debug APK is attached to the run as `StormNav-debug-apk`. Download it, then on the phone allow "install unknown apps" for your browser/file manager and open it. Unit tests run in the same workflow.

## Build a debug APK locally

From the project root, with Android SDK and JDK 17 installed (`gradle :app:testDebugUnitTest` runs the tests):

```bash
gradle :app:assembleDebug
```

The APK will be at:

`app/build/outputs/apk/debug/app-debug.apk`

For a signed release APK, create a signing configuration in Android Studio. Do not commit signing keys.

## Shared reports for 5–10 testers

The Android app can post and fetch reports when `STORMNAV_SERVER` is set to an HTTPS server URL. Pass the URL at build time:

```bash
gradle :app:assembleDebug -PstormnavServer=https://your-host.example
```

Or hard-code it in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "STORMNAV_SERVER", "\"https://your-host.example\"")
```

Run the report server:

```bash
cd server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Put it behind HTTPS before exposing it to the internet. The prototype API intentionally stores only hazard type, coordinates, timestamp, and confirmation count. It does not collect account names or GPS history. It is not hardened for public production use; add authentication, rate limits, backups, and moderation before wider release.

## Data and attribution notes

- MapLibre Native is open source: https://github.com/maplibre/maplibre-native
- OpenFreeMap provides a no-API-key MapLibre vector style and tile service. Its Dark style is used here: https://tiles.openfreemap.org/styles/dark. OpenStreetMap data is © OpenStreetMap contributors. Check OpenFreeMap service terms and capacity before growing beyond a small test group.
- NOAA/NWS explains that `api.weather.gov` is not a radar-image endpoint. Radar display data is available through RIDGE2 / NOAA GeoServer OGC services and MRMS datasets.
- NOAA's GeoServer layer directory and current WMS capabilities should be checked before deployment: https://opengeo.ncep.noaa.gov/geoserver/www/index.html

## Planned milestones

1. Validate NOAA radar tile requests and confirm road casing/fill/labels render above radar on-device; tune opacity/color/refresh.
1. Replace public demo geocoding/routing endpoints with self-hosted Photon + Valhalla/OSRM before relying on them for regular use.
2. Add background location/navigation service, notification controls, audio focus, and more rigorous maneuver progression and reroute testing.
3. Radar-ahead on route is done (0.4.0). Next: track cells between successive scans to estimate motion, so the warning can say where a storm will be at your arrival time. Calibrate the color-to-intensity mapping against the real NOAA palette (or use WMS GetFeatureInfo).
4. Add weather-aware alternate routes and stronger alert corridor geometry checks.
5. Improve community reports with report age, moderation, and shared server hardening.


## Navigation and storm-alert prototype limitations

- Photon and the OSRM public demo server are community-operated public services, not production SLAs. They may throttle or change availability. For a dependable multi-user deployment, self-host these open-source services or select a provider whose usage terms fit the project.
- Voice guidance uses Android Text-to-Speech and currently runs while the app is in the foreground. Background navigation, Android 13+ notification behavior, audio focus, rerouting, and real-world maneuver timing still need implementation and device testing.
- The trip-weather check queries active NWS alerts at route points sampled about every 20 km (with a request cap). NWS point queries report alerts whose official alert areas include those sample locations; gaps between samples can still miss narrow or small alert areas. This is not a continuous route-corridor polygon intersection and does not predict un-warned radar cells. A clean result does not mean the route is storm-free.
- Do not use this prototype as a sole source of navigation or severe-weather safety information. Test while parked, verify routes against road signs, and follow official warnings.
