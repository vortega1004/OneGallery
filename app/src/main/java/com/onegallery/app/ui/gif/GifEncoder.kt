package com.onegallery.app.ui.gif

import java.io.OutputStream

/**
 * Streaming GIF89a writer. One adaptive 256-colour palette per full, opaque frame; LZW
 * dictionary resets at 12 bits. No Android dependencies, so real decoders can test its output.
 * The caller owns the stream. Memory depends on frame size, not number of frames.
 */
internal class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {
    private var finished = false

    init {
        require(width in 1..65535 && height in 1..65535)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(byteArrayOf(0x70, 0, 0)) // No global palette.
        out.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0)) // Loop forever.
    }

    fun frame(pixels: IntArray, delayCentiseconds: Int) {
        check(!finished)
        require(pixels.size == width * height && delayCentiseconds in 1..65535)
        val (palette, indices) = quantize(pixels)
        out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)) // Keep full opaque frame.
        short(delayCentiseconds)
        out.write(byteArrayOf(0, 0, 0x2c))
        short(0); short(0); short(width); short(height)
        out.write(0x87) // Local colour table, 256 entries.
        palette.forEach { color ->
            out.write((color shr 16) and 255)
            out.write((color shr 8) and 255)
            out.write(color and 255)
        }
        out.write(8) // LZW minimum code size.
        writeLzw(indices)
    }

    fun finish() {
        check(!finished)
        finished = true
        out.write(0x3b)
        out.flush()
    }

    private fun short(value: Int) {
        out.write(value and 255)
        out.write((value shr 8) and 255)
    }

    private fun writeLzw(pixels: ByteArray) {
        val dictionary = HashMap<Int, Int>(4096)
        val block = ByteArray(255)
        var blockSize = 0
        var bits = 0
        var bitCount = 0
        var codeSize = 9
        var nextCode = 258
        fun byte(value: Int) {
            block[blockSize++] = value.toByte()
            if (blockSize == 255) {
                out.write(blockSize); out.write(block, 0, blockSize); blockSize = 0
            }
        }
        fun code(value: Int) {
            bits = bits or (value shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                byte(bits and 255)
                bits = bits ushr 8
                bitCount -= 8
            }
            // Encoder dictionary leads the decoder by one entry. Grow AFTER emitting the
            // code that lets the decoder add the entry at the previous size boundary.
            if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
        }
        code(256)
        var prefix = pixels[0].toInt() and 255
        for (i in 1 until pixels.size) {
            val suffix = pixels[i].toInt() and 255
            val key = (prefix shl 8) or suffix
            val existing = dictionary[key]
            if (existing != null) {
                prefix = existing
            } else {
                code(prefix)
                if (nextCode < 4096) {
                    dictionary[key] = nextCode++
                } else {
                    code(256)
                    dictionary.clear(); nextCode = 258; codeSize = 9
                }
                prefix = suffix
            }
        }
        code(prefix)
        code(257)
        if (bitCount > 0) byte(bits and 255)
        if (blockSize > 0) { out.write(blockSize); out.write(block, 0, blockSize) }
        out.write(0)
    }

    /** Weighted median cut over a 5-bit RGB histogram, retaining full-colour averages. */
    private fun quantize(pixels: IntArray): Pair<IntArray, ByteArray> {
        val counts = IntArray(32768)
        val red = LongArray(32768)
        val green = LongArray(32768)
        val blue = LongArray(32768)
        fun bin(p: Int) = ((p shr 9) and 0x7c00) or ((p shr 6) and 0x3e0) or ((p shr 3) and 31)
        pixels.forEach { p ->
            val b = bin(p)
            counts[b]++; red[b] += (p shr 16) and 255
            green[b] += (p shr 8) and 255; blue[b] += p and 255
        }
        data class Bucket(val bins: List<Int>) {
            val population = bins.sumOf { counts[it] }
            val channel = listOf(10, 5, 0).maxBy { shift ->
                bins.maxOf { (it shr shift) and 31 } - bins.minOf { (it shr shift) and 31 }
            }
            val spread = bins.maxOf { (it shr channel) and 31 } - bins.minOf { (it shr channel) and 31 }
        }
        val buckets = mutableListOf(Bucket(counts.indices.filter { counts[it] > 0 }))
        while (buckets.size < 256) {
            val candidate = buckets.filter { it.bins.size > 1 }
                .maxByOrNull { it.population.toLong() * it.spread } ?: break
            val sorted = candidate.bins.sortedBy { (it shr candidate.channel) and 31 }
            var sum = 0
            var split = 0
            while (split < sorted.lastIndex && sum < candidate.population / 2) {
                sum += counts[sorted[split++]]
            }
            split = split.coerceIn(1, sorted.lastIndex)
            buckets.remove(candidate)
            buckets.add(Bucket(sorted.take(split)))
            buckets.add(Bucket(sorted.drop(split)))
        }
        val palette = IntArray(256)
        val lookup = ByteArray(32768)
        buckets.forEachIndexed { index, bucket ->
            val r = (bucket.bins.sumOf { red[it] } / bucket.population).toInt()
            val g = (bucket.bins.sumOf { green[it] } / bucket.population).toInt()
            val b = (bucket.bins.sumOf { blue[it] } / bucket.population).toInt()
            palette[index] = (r shl 16) or (g shl 8) or b
            bucket.bins.forEach { lookup[it] = index.toByte() }
        }
        return palette to ByteArray(pixels.size) { lookup[bin(pixels[it])] }
    }
}
