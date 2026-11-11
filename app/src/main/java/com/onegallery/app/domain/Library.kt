package com.onegallery.app.domain

/**
 * Everything on the phone, with the two things every screen asks of it worked out once: the
 * list of albums, and which items are in each.
 *
 * Built off the main thread each time MediaStore changes. Before this the screens derived both
 * themselves, inside composition: the album list, the open album's contents (twice), the
 * viewer's list and the move sheet each filtered or grouped the whole library on the UI thread,
 * every time a single photo arrived — and a frame capture causes three such changes.
 *
 * Compared by identity: two libraries are the same only if they are the same read.
 */
class Library private constructor(
    /** Every item, newest first (the order `MediaStoreRepository` returns). */
    val items: List<MediaItem>,
    val albums: List<Album>,
    private val byAlbum: Map<String, List<MediaItem>>
) {
    /**
     * The items of one album — or all of them, for null — in [sort] order.
     *
     * The grid and the viewer both build their list here, and must: the viewer opens by index,
     * so any disagreement between them would open a different photo than the one tapped.
     */
    fun album(albumId: String?, sort: MediaSort): List<MediaItem> {
        val inAlbum = if (albumId == null) items else byAlbum[albumId].orEmpty()
        // Already in this order: the library is read newest first, and grouping keeps it
        return if (sort == MediaSort.NEWEST_FIRST) inAlbum else inAlbum.sortedBy(sort)
    }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyMap())

        /** [items] must be newest first, as the repository returns them. */
        fun of(items: List<MediaItem>): Library {
            val byAlbum = items.groupBy { it.bucketId }
            return Library(items, byAlbum.toAlbums(), byAlbum)
        }
    }
}
