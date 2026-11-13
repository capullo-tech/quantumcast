package tech.capullo.quantumcast.viewmodel

import tech.capullo.source.radiobrowser.data.db.FavoriteEntity
import tech.capullo.source.radiobrowser.data.db.FavoriteGroupEntity
import java.time.LocalDate

/**
 * Favorites as an extended M3U playlist for other players (VLC, Kodi, foobar2000…). Export-only:
 * it carries no station IDs, so the JSON backup stays the restorable format.
 *
 * Groups ride as `group-title` (+ `#EXTGRP`), logos as `tvg-logo`; players that don't know those
 * attributes ignore them and list the stations flat.
 */
object FavoritesPlaylist {

    fun fileName(m3u: Boolean): String = "quantumcast-favorites-${LocalDate.now()}.${if (m3u) "m3u" else "json"}"

    fun m3u(favorites: List<FavoriteEntity>, groups: List<FavoriteGroupEntity>): String {
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:QuantumCast favorites\n")
        // Same order as the Favorites screen: groups by sort order, then ungrouped.
        val ordered = groups.sortedBy { it.sortOrder }.map { g ->
            g.name to favorites.filter { it.groupId == g.id }.sortedBy { it.sortOrder }
        } + ("" to favorites.filter { f -> groups.none { it.id == f.groupId } }.sortedBy { it.sortOrder })
        for ((group, list) in ordered) {
            if (list.isEmpty()) continue
            if (group.isNotEmpty()) sb.append("#EXTGRP:").append(clean(group)).append('\n')
            for (f in list) {
                sb.append("#EXTINF:-1")
                if (f.favicon.isNotBlank()) sb.append(" tvg-logo=\"").append(attr(f.favicon)).append('"')
                if (group.isNotEmpty()) sb.append(" group-title=\"").append(attr(group)).append('"')
                val title = if (f.country.isNotBlank()) "${f.name} (${f.country})" else f.name
                sb.append(',').append(clean(title)).append('\n')
                sb.append(f.url.trim()).append('\n')
            }
        }
        return sb.toString()
    }

    // A newline in a name would break the line-based format; quotes would break an attribute.
    private fun clean(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()
    private fun attr(s: String) = clean(s).replace("\"", "'")
}
