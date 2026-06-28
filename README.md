# Universal TV Remote Control

A production-ready, premium Android app that turns any phone into a universal TV
remote. Built natively with **Kotlin**, **MVVM + Clean Architecture**, **Material
Design 3**, **AMOLED-black aesthetics**, and **Hilt + Room + Coroutines + Navigation**.

> Inspired by top Play Store remotes (Sensus Tech / FineArt / Boost) but contains
> **no copied assets, code, or branding**. Original implementation, original UX.

---

## Highlights

| Pillar               | What you get                                                                 |
| -------------------- | ----------------------------------------------------------------------------- |
| Architecture         | Single-Activity + Navigation Component, MVVM, Repository, Clean Architecture |
| DI                   | Hilt (everywhere)                                                            |
| Persistence          | Room with `TvDeviceDao`, `FavoriteRemoteDao`, `ConnectionHistoryDao`         |
| Async                | Kotlin Coroutines + Flow + StateFlow                                         |
| UI                   | XML layouts (no Compose), ViewBinding, Material 3, MotionLayout-ready        |
| Themes               | Light / Dark / **AMOLED Black** / Dynamic Material You                       |
| Languages            | 22 locale folders shipped, architecture for 80+                              |
| Connection           | Wi-Fi (NSD/mDNS), IR Blaster (`ConsumerIrManager`), Bluetooth-ready          |
| Mirroring            | `MediaProjection` foreground service                                         |
| Casting              | Photos / Videos / Audio browser using `MediaStore`                           |
| Premium / Ads        | Interface-only (no hardcoded keys) so Firebase + AdMob can drop straight in  |
| Widgets              | Home-screen Remote widget (small)                                            |
| Performance          | Edge-to-edge, splash-screen API, ViewBinding, StrictMode in debug             |

---

## Module / Package Layout

```
app/
└── src/main/java/com/universal/tvremote/control/
    ├── UniversalRemoteApp.kt               # @HiltAndroidApp
    ├── analytics/                          # Analytics + CrashReporter interfaces (no SDK locked in)
    ├── core/common/                        # AppPreferences, Resource, view extensions, ViewBinding delegate
    ├── data/
    │   ├── discovery/NsdDeviceDiscovery.kt # mDNS / NSD device scanning
    │   ├── ir/                             # IrBlaster + IrCodes (NEC / SIRC)
    │   ├── local/                          # Room AppDatabase, DAOs, Entities
    │   └── repository/                     # Repository implementations
    ├── di/                                 # Hilt modules
    ├── domain/
    │   ├── model/                          # TvDevice, RemoteKey, ConnectionState, MediaItem
    │   └── repository/                     # Repository interfaces
    ├── mirroring/MirroringService.kt       # Foreground media-projection service
    ├── premium/                            # AdsManager + BillingManager interfaces
    ├── presentation/
    │   ├── main/                           # MainActivity + MainViewModel
    │   ├── onboarding/                     # ViewPager2 onboarding
    │   ├── home/                           # Hero card + categories grid
    │   ├── search/                         # Device discovery + pairing
    │   ├── remote/                         # Standard / Numeric / Apps remotes
    │   ├── apps/                           # TV app shortcut grid
    │   ├── mirroring/                      # Mirroring permission + screen
    │   ├── cast/                           # Photos/Videos/Audio/IPTV cast picker
    │   ├── settings/                       # Premium row, theme, language, switches
    │   └── premium/                        # Paywall screen
    └── widgets/RemoteWidgetProvider.kt     # Home-screen widget
```

---

## Build & Run

```bash
# In Android Studio:
File → Open → select this folder → Sync Gradle → Run
```

Requirements:

- **Android Studio Ladybug (2024.2.1)** or newer
- **JDK 17**
- **Android Gradle Plugin 8.7.3**
- **Gradle 8.10.2**
- **Kotlin 2.0.21 + KSP 2.0.21-1.0.28**
- **minSdk 21 / compileSdk 35 / targetSdk 35**

---

## Screens (matching the screenshot pack)

| # | Screen                  | Source                                |
| - | ----------------------- | ------------------------------------- |
| 1 | Splash                  | Android 12 splash-screen API + AMOLED |
| 2 | Onboarding (6 pages)    | `fragment_onboarding` + ViewPager2    |
| 3 | Home                    | `fragment_home` — hero card + grids   |
| 4 | Discover TV             | `fragment_search_devices` + NSD scan  |
| 5 | Pairing-code dialog     | `dialog_pairing_code`                 |
| 6 | Standard remote         | `fragment_standard_remote` (gradient OK) |
| 7 | Numeric remote          | `fragment_numeric_remote`             |
| 8 | Apps grid               | `fragment_apps_remote`                |
| 9 | Mirroring instructions  | `fragment_mirroring` + foreground svc |
| 10 | Cast media picker      | `fragment_cast` + `fragment_media_picker` |
| 11 | Photos / Videos / Audio| `fragment_media_grid` + MediaStore    |
| 12 | Settings               | `fragment_settings` (premium card + rows) |
| 13 | Premium paywall        | `fragment_premium` (lifetime card)    |

---

## Customisation Hooks

| Need                       | Where                                                                   |
| -------------------------- | ----------------------------------------------------------------------- |
| Firebase Analytics         | Replace `NoopAnalytics` in `di/ServicesModule.kt` with a real binding   |
| Firebase Crashlytics       | Replace `NoopCrashReporter` likewise                                    |
| AdMob banner / interstitial| Replace `NoopAdsManager` with a Google Mobile Ads-backed impl           |
| Google Play Billing v6     | Replace `NoopBillingManager` with `BillingClient` impl                  |
| New TV brand               | Add codes in `data/ir/IrCodes.kt` or vendor REST client                 |
| New language               | Drop a `values-XX/strings.xml`, register in `resourceConfigurations`    |

---

## Themes

`AppPreferences.themeMode` and `amoledEnabled` toggle:

- **Material You** (Android 12+) - via `DynamicColors.applyToActivitiesIfAvailable`
- **Light**, **Dark**, **AMOLED Black** (pure `#000`)

`Theme.UniversalRemote.Splash` -> `postSplashScreenTheme` ensures a smooth handoff.

---

## Permissions

| Permission                            | Why                              |
| ------------------------------------- | -------------------------------- |
| `INTERNET`, `ACCESS_NETWORK_STATE`    | TV REST / WebSocket comms        |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | mDNS / SSDP discovery |
| `CONSUMER_IR` (feature, not perm)     | IR Blaster                       |
| `RECORD_AUDIO`                        | Voice commands                   |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen mirroring                 |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO`       | Cast media                       |
| `POST_NOTIFICATIONS`                  | Mirroring ongoing notif          |

---

## License

Created for CodeCanyon distribution. All names, layouts, drawables, strings,
and Kotlin sources in this repository are original. You retain the copyright
when redistributing.
