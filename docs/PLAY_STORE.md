# Google Play listing: copy-and-paste kit

## Store listing

**App name** (30 max): `StormNav: Weather-Aware GPS`

**Short description** (80 max):
`GPS that sees storms on your route. Detour, wait it out, or chase the storm.`

**Full description:**

```
At 4:40 one morning I was driving to work through a heavy storm. Halfway there, a tornado touched down, and the radio said it would reach the area around my work in six minutes. I was five minutes away, driving straight toward it in the dark, with rain going sideways. I made it into the parking lot and was rushed into a tornado shelter. Later that week, I dreamed my GPS had weather radar built in. I decided to make it real. That's StormNav.

StormNav is a free, ad-free GPS that watches the weather for you.

SEE STORMS BEFORE YOU HIT THEM
• Live NOAA radar under the roads, updated as soon as a new image is out (about every 2 minutes)
• Storm Timer: minutes until you reach the next band of heavy rain
• Measures how storms are moving and checks where they'll be when you get there, not just where they are now
• National Weather Service alerts along your route and where you are

DETOUR OR WAIT IT OUT
• Severe storm ahead? StormNav asks: find a detour, wait it out, or keep going
• Detours are checked against radar and shown with the extra time they add
• Wait it out: gas stations, restaurants, hotels and rest areas before the storm, and a "you're clear" alert when it passes
• Best time to leave: compare leaving now with leaving later

WARNINGS WHERE YOU ARE
• Storm watch: "Severe storm 18 mi WSW, moving ENE at 32 mph, here in about 35 minutes", even when you're parked
• Fog, low visibility, heavy rain, ice, snow, high winds and flooding warnings
• Warns you if severe weather (hail) is heading for your parked car

A REAL NAVIGATOR
• Turn-by-turn voice guidance with your choice of installed voices
• Smart search: "Springfield Walmart", street addresses, "gas", "coffee"
• Home, Work, recent places and remember-my-parking-spot
• Heading-up map that follows you, satellite or dark map, car / truck / minivan / motorcycle marker
• Car-mount HUD: rain chance, wind, temperature, speedometer, ETA

STORM CHASER MODE
For experienced chasers: pick a storm cell and StormNav routes to where its core will be when you arrive, stopping a safe distance short, and reroutes as it moves.

StormNav is free with no ads. If it helps you, you can buy the developer a coffee from inside the app.

Weather data: NOAA / National Weather Service. Maps: OpenStreetMap contributors, OpenFreeMap, Esri. StormNav is a heads-up tool. Always follow official warnings and drive safely.
```

**Category:** Maps & Navigation
**Tags:** Navigation, Weather
**Graphics:** icon `docs/play-store-icon-512.png` · feature graphic `docs/play-feature-graphic-1024x500.png` · at least 2 phone screenshots (take them on your phone: landscape HUD on a drive, search screen, storm popup, Menu)

## App content answers

- **Privacy policy URL:** a public link to `docs/PRIVACY.md` (see the README for hosting options)
- **Ads:** No, the app contains no ads
- **App access:** All functionality is available without special access (no login)
- **Content rating questionnaire:** Category "Reference, News, or Educational" or "Utility" → answer No to violence, sexual content, language, controlled substances, gambling. Users can't interact or share content. Shares location with other users: **No**. Expected rating: Everyone.
- **Target audience:** 18+ (simplest: avoids the Families program; it's a driving app)
- **News app:** No · **Government app:** No · **Financial features:** None · **Health:** No
- **Location permission:** foreground only (`ACCESS_FINE_LOCATION`); no background location, so no background-location declaration is needed

## Data safety form

- **Does your app collect or share any of the required user data types?** Yes
- **Location → Precise location:** Collected ✔ and Shared ✔. It's sent from the phone to third-party routing, search, weather and map services. They aren't under contract to you, so declaring it as shared is the safe, honest answer.
  - Shared for: App functionality
  - Processed ephemerally: Yes (StormNav keeps no server-side copy)
  - Required or optional: Required (the app is a GPS)
  - Purpose: App functionality
- **Is all user data encrypted in transit?** Yes (all requests use HTTPS)
- **Can users request that data be deleted?** Nothing is stored off the phone; uninstalling deletes local data. Choose the option that no user data is retained by the developer.
- Everything else (personal info, financial, messages, photos, contacts, app activity, device IDs): **Not collected**

## Before going public

1. **Routing:** StormNav uses the free FOSSGIS OSRM community server (no key, at most 1 request/second, which StormNav enforces), falling back to openrouteservice (free key, 2,000 routes/day shared by all users; set `orsApiKey` in gradle.properties) and then the OSRM demo server. If StormNav grows to thousands of daily drivers, self-host OSRM or Valhalla.
1. **Traffic:** live traffic tiles come from TomTom's free tier (50,000 tiles/day on their older plan; newer accounts may get a monthly allowance). One key built into the app (`tomtomApiKey` in gradle.properties or the `TOMTOM_API_KEY` repo secret) is shared by everyone; users can also paste their own key in Menu > Traffic. When the allowance runs out the traffic layer hides itself and retries every 30 minutes.
2. **Satellite imagery:** check Esri's terms of use for World Imagery in a published app; the Dark basemap (OpenFreeMap) has no such limits.
3. **Photon / Overpass:** public, fair-use servers. Fine for a small user base; self-host or use a paid tier if usage grows.
