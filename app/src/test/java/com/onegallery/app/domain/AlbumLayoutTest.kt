package com.onegallery.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album list's arrangement: what goes where, and what each edit does to it.
 *
 * The rule under test throughout is the one the feature exists for — only the user moves a tile.
 */
class AlbumLayoutTest {

    private val names = mapOf("1" to "Alpha", "2" to "Bravo", "3" to "Charlie", "4" to "Delta")
    private val a = "1"
    private val b = "2"
    private val c = "3"
    private val d = "4"

    private fun arrangeAlbums(ids: List<String>, layout: AlbumLayout) =
        arrangeTiles(ids, { names.getValue(it) }, layout)

    private fun keys(tiles: List<LayoutTile>) = tiles.map {
        when (it) {
            is LayoutTile.Single -> AlbumLayout.albumKey(it.albumId)
            is LayoutTile.Group -> AlbumLayout.groupKey(it.id)
        }
    }

    @Test fun `tiles come in the saved order whatever order the albums arrive in`() {
        val layout = AlbumLayout(order = listOf("a:3", "a:1", "a:2"))
        assertEquals(listOf("a:3", "a:1", "a:2"), keys(arrangeAlbums(listOf(a, b, c), layout)))
        assertEquals(listOf("a:3", "a:1", "a:2"), keys(arrangeAlbums(listOf(c, b, a), layout)))
    }

    @Test fun `a new album goes after everything already placed`() {
        val layout = AlbumLayout(order = listOf("a:3", "a:1"))
        assertEquals(listOf("a:3", "a:1", "a:2", "a:4"), keys(arrangeAlbums(listOf(d, a, b, c), layout)))
    }

    @Test fun `an album that has gone is skipped and returns to its place`() {
        val layout = AlbumLayout(order = listOf("a:1", "a:2", "a:3"))
        assertEquals(listOf("a:1", "a:3"), keys(arrangeAlbums(listOf(a, c), layout)))
        assertEquals(listOf("a:1", "a:2", "a:3"), keys(arrangeAlbums(listOf(a, b, c), layout)))
    }

    @Test fun `grouping puts one tile where the first chosen album was`() {
        val visible = listOf("a:1", "a:2", "a:3", "a:4")
        val layout = AlbumLayout(order = visible).grouping(setOf("a:2", "a:4"), "Trips", "g1", visible)
        val entries = arrangeAlbums(listOf(a, b, c, d), layout)
        assertEquals(listOf("a:1", "g:g1", "a:3"), keys(entries))
        val group = entries[1] as LayoutTile.Group
        assertEquals("Trips", group.name)
        assertEquals(listOf("2", "4"), group.albumIds)
    }

    @Test fun `grouping a group with an album merges them`() {
        val visible = listOf("a:1", "a:2", "a:3", "a:4")
        val first = AlbumLayout(order = visible).grouping(setOf("a:1", "a:2"), "Old", "g1", visible)
        val nowVisible = keys(arrangeAlbums(listOf(a, b, c, d), first))
        val merged = first.grouping(setOf("g:g1", "a:4"), "Both", "g2", nowVisible)
        val entries = arrangeAlbums(listOf(a, b, c, d), merged)
        assertEquals(listOf("g:g2", "a:3"), keys(entries))
        assertEquals(listOf("1", "2", "4"), (entries[0] as LayoutTile.Group).albumIds)
        assertTrue("g1" !in merged.groups)
    }

    @Test fun `ungrouping puts the albums back where the group was`() {
        val visible = listOf("a:1", "a:2", "a:3", "a:4")
        val grouped = AlbumLayout(order = visible).grouping(setOf("a:2", "a:3"), "G", "g1", visible)
        val nowVisible = keys(arrangeAlbums(listOf(a, b, c, d), grouped))
        val back = grouped.ungrouping(setOf("g1"), nowVisible)
        assertEquals(visible, keys(arrangeAlbums(listOf(a, b, c, d), back)))
    }

    @Test fun `an album taken out of a group lands straight after it`() {
        val visible = listOf("a:1", "a:2", "a:3", "a:4")
        val grouped = AlbumLayout(order = visible).grouping(setOf("a:1", "a:2", "a:3"), "G", "g1", visible)
        val nowVisible = keys(arrangeAlbums(listOf(a, b, c, d), grouped))
        val after = grouped.removingFromGroup("g1", setOf("2"), nowVisible)
        val entries = arrangeAlbums(listOf(a, b, c, d), after)
        assertEquals(listOf("g:g1", "a:2", "a:4"), keys(entries))
        assertEquals(listOf("1", "3"), (entries[0] as LayoutTile.Group).albumIds)
    }

    @Test fun `taking the last album out removes the group`() {
        val visible = listOf("a:1", "a:2")
        val grouped = AlbumLayout(order = visible).grouping(setOf("a:1", "a:2"), "G", "g1", visible)
        val after = grouped.removingFromGroup("g1", setOf("1", "2"), listOf("g:g1"))
        assertTrue(after.groups.isEmpty())
        assertEquals(listOf("a:1", "a:2"), keys(arrangeAlbums(listOf(a, b), after)))
    }

    @Test fun `a group with none of its albums on the phone is not shown`() {
        val layout = AlbumLayout(
            order = listOf("g:g1", "a:1"),
            groups = mapOf("g1" to AlbumGroup("g1", "Gone", listOf("8", "9")))
        )
        assertEquals(listOf("a:1"), keys(arrangeAlbums(listOf(a), layout)))
    }

    @Test fun `reordering inside a group keeps albums that are not on the phone right now`() {
        val layout = AlbumLayout(
            order = listOf("g:g1"),
            groups = mapOf("g1" to AlbumGroup("g1", "G", listOf("1", "9", "2")))
        )
        val reordered = layout.withGroupOrder("g1", listOf("2", "1"))
        assertEquals(listOf("2", "1", "9"), reordered.groups.getValue("g1").albumIds)
    }

    @Test fun `an arrangement survives being saved and read back`() {
        val visible = listOf("a:1", "a:2", "a:3", "a:4")
        val layout = AlbumLayout(order = visible)
            .grouping(setOf("a:2", "a:3"), "Tabs\tand\nlines \\ too", "g1", visible)
        assertEquals(layout, decodeAlbumLayout(layout.encode()))
    }

    @Test fun `unreadable lines are skipped rather than fatal`() {
        val layout = decodeAlbumLayout("a\t1\nnonsense\ng\tbroken\n\na\t2\n")
        assertEquals(listOf("a:1", "a:2"), layout.order)
        assertTrue(layout.groups.isEmpty())
    }

    @Test fun `renaming to blank is ignored`() {
        val layout = AlbumLayout(order = listOf("g:g1"), groups = mapOf("g1" to AlbumGroup("g1", "Keep", listOf("1"))))
        assertEquals("Keep", layout.renamingGroup("g1", "   ").groups.getValue("g1").name)
        assertEquals("New", layout.renamingGroup("g1", " New ").groups.getValue("g1").name)
    }
}
