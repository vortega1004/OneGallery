package com.onegallery.app.domain

/** A named set of albums shown as one tile on the album list. Files do not move. */
data class AlbumGroup(
    val id: String,
    val name: String,
    /** Bucket ids, in the order they are shown inside the group. */
    val albumIds: List<String>
)

/**
 * How the user has arranged the album list: the order of its tiles, and which albums are
 * gathered into groups.
 *
 * This exists because the album list used to be *sorted* — by newest item, by default — so it
 * re-shuffled itself every time a photo was taken or a frame was captured, and the album you
 * reached for was no longer where you left it. An arrangement is only ever changed by the user.
 *
 * It is the app's own bookkeeping, keyed on MediaStore bucket ids, and says nothing about where
 * files are. An album that is not mentioned (a new folder) is shown after everything that is;
 * one that is mentioned but gone (a folder emptied or deleted) is simply skipped, and comes
 * back to its place if the folder does.
 *
 * [order] holds tile keys — see [albumKey] and [groupKey].
 */
data class AlbumLayout(
    val order: List<String> = emptyList(),
    val groups: Map<String, AlbumGroup> = emptyMap()
) {
    companion object {
        fun albumKey(albumId: String) = "a:$albumId"
        fun groupKey(groupId: String) = "g:$groupId"
    }
}

/** One tile on the album list. */
sealed interface AlbumEntry {
    val key: String

    data class Single(val album: Album) : AlbumEntry {
        override val key: String get() = AlbumLayout.albumKey(album.id)
    }

    data class Group(val id: String, val name: String, val albums: List<Album>) : AlbumEntry {
        override val key: String get() = AlbumLayout.groupKey(id)
        val itemCount: Int get() = albums.sumOf { it.count }
    }
}

/** One tile of an arrangement, before it is matched up with the albums themselves. */
sealed interface LayoutTile {
    data class Single(val albumId: String) : LayoutTile
    data class Group(val id: String, val name: String, val albumIds: List<String>) : LayoutTile
}

/**
 * The album list as the user arranged it.
 *
 * Tiles come in [AlbumLayout.order]; albums the layout has never seen follow, by name, so a new
 * folder has a predictable place until the user gives it one. A group whose albums have all
 * gone is not shown.
 */
fun arrangeAlbums(albums: List<Album>, layout: AlbumLayout): List<AlbumEntry> {
    val byId = albums.associateBy { it.id }
    return arrangeTiles(albums.map { it.id }, { byId.getValue(it).name }, layout).map { tile ->
        when (tile) {
            is LayoutTile.Single -> AlbumEntry.Single(byId.getValue(tile.albumId))
            is LayoutTile.Group -> AlbumEntry.Group(tile.id, tile.name, tile.albumIds.map { byId.getValue(it) })
        }
    }
}

/**
 * [arrangeAlbums] on ids alone: which tiles, in which order, for the albums that exist
 * ([albumIds]). Kept free of Android types so the rules can be tested as plain logic.
 */
fun arrangeTiles(
    albumIds: List<String>,
    nameOf: (String) -> String,
    layout: AlbumLayout
): List<LayoutTile> {
    val present = albumIds.toHashSet()
    val grouped = layout.groups.values.flatMapTo(HashSet()) { it.albumIds }
    val placed = HashSet<String>()
    val tiles = ArrayList<LayoutTile>()

    fun addGroup(group: AlbumGroup) {
        val here = group.albumIds.filter { it in present }
        if (here.isNotEmpty()) tiles += LayoutTile.Group(group.id, group.name, here)
    }

    for (key in layout.order) {
        if (!placed.add(key)) continue
        when {
            key.startsWith("a:") -> {
                val id = key.substring(2)
                // An album that has since been put in a group is shown there, not here too
                if (id in present && id !in grouped) tiles += LayoutTile.Single(id)
            }
            key.startsWith("g:") -> layout.groups[key.substring(2)]?.let { addGroup(it) }
        }
    }
    // Groups that exist but were never given a place (should not happen; costs nothing to honour)
    for (group in layout.groups.values) {
        if (placed.add(AlbumLayout.groupKey(group.id))) addGroup(group)
    }
    albumIds
        .filter { it !in grouped && AlbumLayout.albumKey(it) !in placed }
        .sortedBy { nameOf(it).lowercase() }
        .forEach { tiles += LayoutTile.Single(it) }
    return tiles
}

/** The tiles in [keys] order. Called with everything on screen, which is how a new album gets a place. */
fun AlbumLayout.withOrder(keys: List<String>): AlbumLayout = copy(order = keys.distinct())

/** Re-orders the albums inside one group. Ids of albums not currently on the phone are kept, at the end. */
fun AlbumLayout.withGroupOrder(groupId: String, albumIds: List<String>): AlbumLayout {
    val group = groups[groupId] ?: return this
    val kept = albumIds.filter { it in group.albumIds }
    val unseen = group.albumIds.filter { it !in kept }
    return copy(groups = groups + (groupId to group.copy(albumIds = kept + unseen)))
}

/**
 * Gathers the tiles in [keys] into one group named [name], placed where the first of them was.
 * Groups among them are dissolved into the new one. [visibleOrder] is the list as it is on
 * screen, so tiles the layout had not recorded yet keep their place.
 */
fun AlbumLayout.grouping(
    keys: Set<String>,
    name: String,
    newGroupId: String,
    visibleOrder: List<String>
): AlbumLayout {
    val chosen = visibleOrder.filter { it in keys }
    if (chosen.isEmpty()) return this
    val albumIds = chosen.flatMap { key ->
        if (key.startsWith("g:")) groups[key.substring(2)]?.albumIds.orEmpty() else listOf(key.substring(2))
    }.distinct()
    val dissolved = chosen.filter { it.startsWith("g:") }.map { it.substring(2) }.toSet()
    val newKey = AlbumLayout.groupKey(newGroupId)
    val order = visibleOrder.mapNotNull { key ->
        when {
            key == chosen.first() -> newKey
            key in keys -> null
            else -> key
        }
    }
    return AlbumLayout(
        order = order,
        groups = groups - dissolved + (newGroupId to AlbumGroup(newGroupId, name.trim(), albumIds))
    )
}

/** Dissolves [groupIds]: their albums go back on the main list, where the group was. */
fun AlbumLayout.ungrouping(groupIds: Set<String>, visibleOrder: List<String>): AlbumLayout {
    val order = visibleOrder.flatMap { key ->
        val id = key.removePrefix("g:")
        if (key.startsWith("g:") && id in groupIds) {
            groups[id]?.albumIds.orEmpty().map { AlbumLayout.albumKey(it) }
        } else {
            listOf(key)
        }
    }
    return AlbumLayout(order = order.distinct(), groups = groups - groupIds)
}

/** Takes [albumIds] out of [groupId] and puts them on the main list straight after it. */
fun AlbumLayout.removingFromGroup(
    groupId: String,
    albumIds: Set<String>,
    visibleOrder: List<String>
): AlbumLayout {
    val group = groups[groupId] ?: return this
    val leaving = group.albumIds.filter { it in albumIds }
    if (leaving.isEmpty()) return this
    val staying = group.albumIds.filter { it !in albumIds }
    val groupKey = AlbumLayout.groupKey(groupId)
    val released = leaving.map { AlbumLayout.albumKey(it) }
    val order = visibleOrder.flatMap { key ->
        when {
            key != groupKey -> listOf(key)
            // The last album out takes the group's place
            staying.isEmpty() -> released
            else -> listOf(key) + released
        }
    }
    val remaining = if (staying.isEmpty()) groups - groupId
    else groups + (groupId to group.copy(albumIds = staying))
    return AlbumLayout(order = order.distinct(), groups = remaining)
}

fun AlbumLayout.renamingGroup(groupId: String, name: String): AlbumLayout {
    val group = groups[groupId] ?: return this
    val cleaned = name.trim()
    return if (cleaned.isEmpty()) this else copy(groups = groups + (groupId to group.copy(name = cleaned)))
}

/**
 * The tiles of [entries] put in [sort] order — a one-off "arrange by", after which the order is
 * the user's again. A group is placed by what it holds: its newest item, its total size, its
 * own name.
 */
fun List<AlbumEntry>.arrangedBy(sort: AlbumSort): List<AlbumEntry> {
    fun name(entry: AlbumEntry) = when (entry) {
        is AlbumEntry.Single -> entry.album.name
        is AlbumEntry.Group -> entry.name
    }.lowercase()
    fun albums(entry: AlbumEntry) = when (entry) {
        is AlbumEntry.Single -> listOf(entry.album)
        is AlbumEntry.Group -> entry.albums
    }
    return when (sort) {
        AlbumSort.NAME_ASC -> sortedBy { name(it) }
        AlbumSort.NAME_DESC -> sortedByDescending { name(it) }
        AlbumSort.NEWEST_FIRST -> sortedByDescending { entry -> albums(entry).maxOf { it.newestDate } }
        AlbumSort.OLDEST_FIRST -> sortedBy { entry -> albums(entry).minOf { it.oldestDate } }
        AlbumSort.MOST_ITEMS -> sortedByDescending { entry -> albums(entry).sumOf { it.count } }
        AlbumSort.FEWEST_ITEMS -> sortedBy { entry -> albums(entry).sumOf { it.count } }
        AlbumSort.LARGEST -> sortedByDescending { entry -> albums(entry).sumOf { it.totalBytes } }
        AlbumSort.SMALLEST -> sortedBy { entry -> albums(entry).sumOf { it.totalBytes } }
    }
}

// --- Storage form ------------------------------------------------------------------------------
// One line per tile, in order: "a<TAB>bucketId" or "g<TAB>groupId<TAB>name<TAB>id,id,id".
// Plain text rather than JSON so it needs no library and can be read in a bug report.

fun AlbumLayout.encode(): String = buildString {
    val written = HashSet<String>()
    fun groupLine(group: AlbumGroup) {
        append("g\t").append(group.id).append('\t').append(escape(group.name)).append('\t')
        append(group.albumIds.joinToString(","))
        append('\n')
    }
    for (key in order) {
        if (!written.add(key)) continue
        if (key.startsWith("a:")) {
            append("a\t").append(key.substring(2)).append('\n')
        } else if (key.startsWith("g:")) {
            groups[key.substring(2)]?.let { groupLine(it) }
        }
    }
    groups.values.filter { AlbumLayout.groupKey(it.id) !in written }.forEach { groupLine(it) }
}

/** Reads what [encode] wrote. Anything it cannot make sense of is skipped, never fatal. */
fun decodeAlbumLayout(text: String): AlbumLayout {
    val order = ArrayList<String>()
    val groups = LinkedHashMap<String, AlbumGroup>()
    for (line in text.lineSequence()) {
        val parts = line.split('\t')
        when {
            parts.size == 2 && parts[0] == "a" && parts[1].isNotEmpty() ->
                order += AlbumLayout.albumKey(parts[1])
            parts.size == 4 && parts[0] == "g" && parts[1].isNotEmpty() -> {
                val ids = parts[3].split(',').filter { it.isNotEmpty() }
                groups[parts[1]] = AlbumGroup(parts[1], unescape(parts[2]), ids)
                order += AlbumLayout.groupKey(parts[1])
            }
        }
    }
    return AlbumLayout(order.distinct(), groups)
}

private fun escape(text: String): String =
    text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun unescape(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            when (text[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                else -> out.append(text[i + 1])
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
