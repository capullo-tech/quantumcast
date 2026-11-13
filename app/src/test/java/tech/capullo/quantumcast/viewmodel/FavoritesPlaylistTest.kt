package tech.capullo.quantumcast.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tech.capullo.source.radiobrowser.data.db.FavoriteEntity
import tech.capullo.source.radiobrowser.data.db.FavoriteGroupEntity

/** The extended-M3U export other players read: order, grouping, and line-format safety. */
class FavoritesPlaylistTest {

    private fun fav(name: String, url: String, group: String = "", sort: Int = 0, favicon: String = "", country: String = "") = FavoriteEntity(
        uuid = name,
        name = name,
        url = url,
        favicon = favicon,
        country = country,
        tags = "",
        codec = "",
        bitrate = 0,
        addedAt = 0,
        groupId = group,
        sortOrder = sort,
    )

    private fun group(id: String, name: String, sort: Int) = FavoriteGroupEntity(id = id, name = name, sortOrder = sort, createdAt = 0)

    /** The stream URL lines, i.e. what a player will actually play, in order. */
    private fun urls(m3u: String) = m3u.lines().filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `exact format for a grouped and an ungrouped station`() {
        val out = FavoritesPlaylist.m3u(
            listOf(
                fav("Loose", " http://loose.example/s ", favicon = ""),
                fav("Jazz FM", "http://jazz.example/s", group = "g", favicon = "http://jazz.example/logo.png", country = "Germany"),
            ),
            listOf(group("g", "Jazz", 0)),
        )
        assertEquals(
            """
            #EXTM3U
            #PLAYLIST:QuantumCast favorites
            #EXTGRP:Jazz
            #EXTINF:-1 tvg-logo="http://jazz.example/logo.png" group-title="Jazz",Jazz FM (Germany)
            http://jazz.example/s
            #EXTINF:-1,Loose
            http://loose.example/s

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `groups in their sort order, stations by sort order within each, ungrouped last`() {
        val favorites = listOf(
            fav("u2", "http://u2", sort = 1),
            fav("rock-b", "http://rock-b", group = "rock", sort = 1),
            fav("jazz-a", "http://jazz-a", group = "jazz", sort = 0),
            fav("u1", "http://u1", sort = 0),
            fav("rock-a", "http://rock-a", group = "rock", sort = 0),
        )
        val groups = listOf(group("rock", "Rock", 1), group("jazz", "Jazz", 0))
        val out = FavoritesPlaylist.m3u(favorites, groups)
        assertEquals(listOf("http://jazz-a", "http://rock-a", "http://rock-b", "http://u1", "http://u2"), urls(out))
    }

    @Test
    fun `a station whose group no longer exists is exported as ungrouped, and empty groups are omitted`() {
        val out = FavoritesPlaylist.m3u(
            listOf(fav("orphan", "http://orphan", group = "deleted-group")),
            listOf(group("empty", "Empty", 0)),
        )
        assertEquals(listOf("http://orphan"), urls(out))
        assertFalse("empty group header", out.contains("#EXTGRP"))
        assertFalse("orphan must not carry a group-title", out.contains("group-title"))
    }

    @Test
    fun `newlines in names cannot inject lines and quotes cannot break attributes`() {
        val out = FavoritesPlaylist.m3u(
            listOf(fav("Evil\r\nhttp://injected.example/\n#EXTINF:-1,x", "http://real.example/s", group = "g", favicon = "http://x/\"onerror")),
            listOf(group("g", "My \"Best\"\nGroup", 0)),
        )
        assertEquals(listOf("http://real.example/s"), urls(out))
        val extinf = out.lines().single { it.startsWith("#EXTINF") }
        assertEquals(
            "#EXTINF:-1 tvg-logo=\"http://x/'onerror\" group-title=\"My 'Best' Group\",Evil http://injected.example/ #EXTINF:-1,x",
            extinf,
        )
    }
}
