package com.onegallery.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paging loop behind the library read. The termination rule is the thing worth pinning:
 * get it wrong one way and the last page is dropped, the other way and the loop never ends.
 */
class ReadInPagesTest {

    /** A provider holding ids 1..[count], honouring a limit of [pageSize] (or not). */
    private fun provider(count: Int, pageSize: Int, honoursLimit: Boolean = true): (Long) -> List<Long> = { afterId ->
        val rows = (maxOf(afterId + 1, 1L)..count.toLong()).toList()
        if (honoursLimit) rows.take(pageSize) else rows
    }

    @Test
    fun `reads every row across several pages, in order`() {
        val read = readInPages(4, { it }, provider(10, 4))
        assertEquals((1L..10L).toList(), read)
    }

    @Test
    fun `a library that is an exact multiple of the page size is read whole`() {
        assertEquals((1L..8L).toList(), readInPages(4, { it }, provider(8, 4)))
    }

    @Test
    fun `an empty library reads as empty`() {
        assertEquals(emptyList<Long>(), readInPages(4, { it }, provider(0, 4)))
    }

    @Test
    fun `a provider that ignores the limit still ends after one empty page`() {
        var calls = 0
        val read = readInPages(4, { it }) { afterId -> calls++; provider(10, 4, honoursLimit = false)(afterId) }
        assertEquals((1L..10L).toList(), read)
        assertEquals(2, calls)
    }

    @Test
    fun `each page is asked for ids after the last one seen`() {
        val asked = mutableListOf<Long>()
        readInPages(3, { it }) { afterId -> asked += afterId; provider(7, 3)(afterId) }
        assertEquals(listOf(-1L, 3L, 6L), asked)
    }
}
