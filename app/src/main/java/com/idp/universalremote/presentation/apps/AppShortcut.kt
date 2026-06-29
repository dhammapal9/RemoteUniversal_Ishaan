package com.idp.universalremote.presentation.apps

import com.idp.universalremote.domain.model.RemoteKey

/**
 * Catalog of streaming apps the UI can launch on the connected TV.
 *
 * Each entry maps to a [RemoteKey] which the repository forwards to whichever
 * brand-specific protocol the active TV speaks (Roku ECP, LG App-id, Samsung
 * ws-api, Android-TV polo, or generic DIAL launch). The brand color is shown
 * as the tile background so the grid stays recognisable without bundling
 * per-app drawable assets — useful for a Codecanyon-style ship-it-fast app.
 */
data class AppShortcut(
    val id: String,
    val displayName: String,
    val key: RemoteKey,
    val brandColor: Int
) {
    companion object {
        val DEFAULTS: List<AppShortcut> = listOf(
            AppShortcut("netflix",   "Netflix",       RemoteKey.APP_NETFLIX,   0xFFE50914.toInt()),
            AppShortcut("youtube",   "YouTube",       RemoteKey.APP_YOUTUBE,   0xFFFF0000.toInt()),
            AppShortcut("prime",     "Prime Video",   RemoteKey.APP_PRIME,     0xFF00A8E1.toInt()),
            AppShortcut("disney",    "Disney+",       RemoteKey.APP_DISNEY,    0xFF113CCF.toInt()),
            AppShortcut("appletv",   "Apple TV",      RemoteKey.APP_APPLE_TV,  0xFF1A1A1A.toInt()),
            AppShortcut("hbo",       "HBO Max",       RemoteKey.APP_HBO,       0xFF6E2EFF.toInt()),
            AppShortcut("hulu",      "Hulu",          RemoteKey.APP_HULU,      0xFF1CE783.toInt()),
            AppShortcut("spotify",   "Spotify",       RemoteKey.APP_SPOTIFY,   0xFF1DB954.toInt()),
            AppShortcut("paramount", "Paramount+",    RemoteKey.APP_PARAMOUNT, 0xFF0066FF.toInt()),
            AppShortcut("peacock",   "Peacock",       RemoteKey.APP_PEACOCK,   0xFFFF4F00.toInt()),
            AppShortcut("plex",      "Plex",          RemoteKey.APP_PLEX,      0xFFE5A00D.toInt()),
            AppShortcut("tubi",      "Tubi",          RemoteKey.APP_TUBI,      0xFFFA382F.toInt()),
            AppShortcut("pluto",     "Pluto TV",      RemoteKey.APP_PLUTO,     0xFF000B5C.toInt())
        )
    }
}
