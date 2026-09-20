package com.onegallery.app.ui.gif

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import org.w3c.dom.Node
import org.junit.Assert.*
import org.junit.Test

/** Decode with the JDK's independent GIF reader; do not mirror the encoder implementation. */
class GifEncoderTest {
    @Test fun `full animation decodes with correct frame colors and delay`() {
        val bytes = ByteArrayOutputStream()
        val encoder = GifEncoder(bytes, 31, 19)
        val colors = listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        colors.forEach { color -> encoder.frame(IntArray(31 * 19) { color }, 17) }
        encoder.finish()
        DesktopGifReader(bytes.toByteArray()).use { reader ->
            assertEquals(3, reader.count())
            colors.forEachIndexed { i, color ->
                val decoded = reader.read(i)
                assertEquals(31, decoded.width)
                assertEquals(19, decoded.height)
                assertEquals(color, decoded.pixels[9 * 31 + 15])
                val metadata = reader.metadata(i)
                val children = metadata.childNodes
                val control = (0 until children.length).map { children.item(it) }
                    .first { it.nodeName == "GraphicControlExtension" }
                assertEquals("17", control.attributes.getNamedItem("delayTime").nodeValue)
            }
        }
        assertTrue(bytes.toByteArray().toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }

    @Test fun `noisy indexed image round trips through all LZW code widths and dictionary resets`() {
        val random = Random(123)
        val palette = IntArray(256) { i ->
            0xff000000.toInt() or ((i and 7) * 32 shl 16) or (((i shr 3) and 7) * 32 shl 8) or ((i shr 6) * 64)
        }
        val pixels = IntArray(256 * 256) { palette[random.nextInt(256)] }
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 256, 256)
        encoder.frame(pixels, 4)
        encoder.finish()
        DesktopGifReader(output.toByteArray()).use { reader ->
            assertArrayEquals(pixels, reader.read(0).pixels)
        }
    }

    @Test fun `photographic color count is quantized to a valid decodable frame`() {
        val random = Random(42)
        val pixels = IntArray(128 * 128) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 128, 128)
        encoder.frame(pixels, 100)
        encoder.finish()
        val colors = DesktopGifReader(output.toByteArray()).use { it.read(0).pixels }
        assertTrue(colors.toSet().size <= 256)
        val error = pixels.indices.sumOf { i ->
            listOf(0, 8, 16).sumOf { shift ->
                kotlin.math.abs(((pixels[i] shr shift) and 255) - ((colors[i] shr shift) and 255))
            }.toLong()
        }.toDouble() / (pixels.size * 3)
        assertTrue("Mean channel error: $error", error < 20)
    }
}

// Android's Kotlin compile bootclasspath excludes java.desktop. The JVM running these tests
// has it; reflection keeps that test-only decoder out of the app and avoids a new dependency.
private class DesktopGifReader(bytes: ByteArray) : AutoCloseable {
    private val imageIo = Class.forName("javax.imageio.ImageIO")
    private val readerType = Class.forName("javax.imageio.ImageReader")
    private val input = imageIo.getMethod("createImageInputStream", Any::class.java)
        .invoke(null, ByteArrayInputStream(bytes))
    private val reader = (imageIo.getMethod("getImageReadersByFormatName", String::class.java)
        .invoke(null, "gif") as Iterator<*>).next()!!
    init { readerType.getMethod("setInput", Any::class.java).invoke(reader, input) }
    fun count() = readerType.getMethod("getNumImages", Boolean::class.javaPrimitiveType).invoke(reader, true) as Int
    fun metadata(index: Int): Node {
        val data = readerType.getMethod("getImageMetadata", Int::class.javaPrimitiveType).invoke(reader, index)
        return Class.forName("javax.imageio.metadata.IIOMetadata").getMethod("getAsTree", String::class.java)
            .invoke(data, "javax_imageio_gif_image_1.0") as Node
    }
    data class Image(val width: Int, val height: Int, val pixels: IntArray)
    fun read(index: Int): Image {
        val image = readerType.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, index)
        val type = Class.forName("java.awt.image.BufferedImage")
        val width = type.getMethod("getWidth").invoke(image) as Int
        val height = type.getMethod("getHeight").invoke(image) as Int
        val int = Int::class.javaPrimitiveType!!
        val pixels = type.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
            .invoke(image, 0, 0, width, height, null, 0, width) as IntArray
        return Image(width, height, pixels)
    }
    override fun close() {
        readerType.getMethod("dispose").invoke(reader)
        (input as AutoCloseable).close()
    }
}
