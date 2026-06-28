# Supported TVs

> ⚠️ "Universal Wi-Fi remote" is mostly a marketing term. Each Smart TV brand
> speaks its own proprietary protocol — the app has to implement each one.
> What follows is what is *actually* wired up in this codebase.

Same-Wi-Fi requirement: the phone and the TV must be on the same SSID/subnet,
and the TV's network-control feature must be enabled.

## Fully working over Wi-Fi

| Brand                     | Protocol                                       | How pairing works                                     |
| ------------------------- | ---------------------------------------------- | ----------------------------------------------------- |
| **Samsung Tizen (2016+)** | WebSocket `wss://:8002` (with TLS fallback to `ws://:8001`) | TV shows an **Allow** prompt — tap Allow on the TV's physical remote. Token persisted automatically. |
| **LG webOS (2014+)**      | WebSocket `ws://:3000` (fallback `wss://:3001`) + pointer socket | Same Allow-prompt flow as Samsung. Client-key persisted automatically. |
| **Sony Bravia**           | IRCC-IP over HTTP `:80/sony/IRCC`              | Set a **Pre-Shared Key** in *Settings → Network → IP Control* and type the same key in the app. |
| **Roku / TCL Roku TV / Hisense Roku TV** | ECP HTTP `:8060/keypress/<KEY>` and `:8060/launch/<appId>` | No pairing — just connect. |
| **Any TV with IR receiver** | Phone IR blaster (`ConsumerIrManager`)      | Works without Wi-Fi; only on phones that ship with IR hardware. NEC + SIRC code patterns. |

## Detected but with limited support

| Brand                | What works                                                     | What doesn't                                          |
| -------------------- | -------------------------------------------------------------- | ----------------------------------------------------- |
| Android TV / Google TV | Discovery + manual entry + PIN dialog                        | Real command path needs the Android TV Remote v2 mTLS handshake. Stub only; UI advances but the TV won't move. |
| Apple TV             | Discovery only                                                 | MediaRemote protocol requires SRP-6a + AES-GCM pairing — not implemented. |
| Fire TV              | Discovery only                                                 | No protocol implemented. |
| Vizio SmartCast      | Discovery only                                                 | Pairing token REST not yet implemented. |
| Older Samsung (H/J series 2014-2015) | None directly                                  | Use the IR blaster path if the phone has an IR LED. |

## Auto-detection (the "universal" bit)

When the user picks **+ Add TV manually** and selects *Other / Universal*, the
app probes the IP against each protocol's well-known port:

1. `GET http://<ip>:8060/query/device-info` → Roku
2. `GET https://<ip>:8002/api/v2/` or `http://<ip>:8001/api/v2/` → Samsung Tizen
3. `TCP <ip>:3000` or `<ip>:3001` open → LG webOS
4. `GET http://<ip>/sony/system` → Sony Bravia

The first protocol that responds is used; the device record is rewritten with
the detected brand so subsequent reconnects are fast.

## Same-Wi-Fi reality check

- Some routers isolate Wi-Fi clients from each other ("AP isolation"). If two
  devices on the same SSID can't ping each other, this app can't reach the TV.
- Many ISPs ship gateway routers with mDNS / SSDP filtering on the guest
  network. Discovery will silently come up empty — fall back to manual entry.
- "Smart TVs" with no network-control feature (cheap 2014-2016 models) accept
  no Wi-Fi commands at all. Use the IR blaster path if available.
