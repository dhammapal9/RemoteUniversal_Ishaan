# Architecture

## Layers

```
┌───────────────────────────────────────────────────────────────┐
│                       presentation/                            │
│   Activities, Fragments, ViewModels, Adapters, ViewBinding     │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼  (StateFlow / suspend)
┌───────────────────────────────────────────────────────────────┐
│                          domain/                              │
│   Plain Kotlin models, repository interfaces, use cases       │
│   No Android imports.                                         │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼  (impl provides)
┌───────────────────────────────────────────────────────────────┐
│                           data/                               │
│   Room, NSD discovery, IR codes, repository implementations    │
└───────────────────────────────────────────────────────────────┘
```

## Why this shape?

- **Single Activity** keeps the system bar / window-insets logic in one place
  and lets us animate transitions via Navigation Component for free.
- **Repository interfaces in `domain/`** mean the presentation layer can be
  unit-tested with hand-rolled fakes and the data layer can swap brand-specific
  protocols (WebSocket, SSDP, ADB, ECP) without touching ViewModels.
- **Hilt** wires everything; no manual ServiceLocator.
- **StateFlow** for connection state lets every screen reactively reflect the
  current TV connection without cross-screen events.

## Adding a new TV brand

1. Add the brand to `TvBrand` enum.
2. (IR) Add IR codes in `data/ir/IrCodes.kt` (NEC, SIRC, RC-5, RC-6 patterns).
3. (Wi-Fi) Create a vendor client under `data/wifi/` and wire it in
   `RemoteCommandRepositoryImpl#send` via `device.brand`.

## Threading model

| Layer        | Dispatcher                                |
| ------------ | ----------------------------------------- |
| ViewModel    | `viewModelScope` (Main)                   |
| Repository   | suspends, callers pick the dispatcher     |
| Room queries | Room's own pool via suspending DAO methods|
| Discovery    | `callbackFlow` from `NsdManager` callbacks|
| IR transmit  | Synchronous — `ConsumerIrManager` is fast |
