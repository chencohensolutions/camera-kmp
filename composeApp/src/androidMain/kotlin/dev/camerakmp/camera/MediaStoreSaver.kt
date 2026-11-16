package dev.camerakmp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import dev.camerakmp.model.InfoSection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves captures to the gallery without re-encoding: compressed images are written byte-for-byte as the
 * camera produced them, RAW goes through DngCreator (lossless), and HDR HEIC is encoded once from YUV.
 */
internal class MediaStoreSaver(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun newName(prefix: String): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun insert(collection: Uri, name: String, mime: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: error("MediaStore insert failed for $name")
    }

    private fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun imagesCollection() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** JPEG, JPEG_R, HEIC and HEIC_ULTRAHDR images arrive fully encoded in one plane; copy it as-is. */
    fun saveEncoded(image: Image, extension: String, mime: String): Uri {
        val name = "${newName("IMG")}.$extension"
        val uri = insert(imagesCollection(), name, mime, "Pictures/CameraKMP")
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        publish(uri)
        return uri
    }

    fun saveDng(image: Image, characteristics: CameraCharacteristics, result: CaptureResult, exifOrientation: Int): Uri {
        val name = "${newName("RAW")}.dng"
        val uri = insert(imagesCollection(), name, "image/x-adobe-dng", "Pictures/CameraKMP")
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation)
            resolver.openOutputStream(uri)!!.use { dng.writeImage(it, image) }
        }
        publish(uri)
        return uri
    }

    /** HDR extension output in YUV, compressed exactly once to HEIC with the hardware HEVC encoder. */
    fun saveYuvAsHeic(image: Image, rotationDegrees: Int, quality: Int = 95): Uri {
        require(image.format == ImageFormat.YUV_420_888)
        val tmp = File(context.cacheDir, "${newName("HDR")}.heic")
        val writer = HeifWriter.Builder(tmp.absolutePath, image.width, image.height, HeifWriter.INPUT_MODE_BUFFER)
            .setRotation(rotationDegrees)
            .setQuality(quality)
            .build()
        writer.start()
        writer.addYuvBuffer(ImageFormat.YUV_420_888, image.toI420())
        writer.stop(10_000)
        writer.close()

        val uri = insert(imagesCollection(), tmp.name, "image/heic", "Pictures/CameraKMP")
        resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        tmp.delete()
        publish(uri)
        return uri
    }

    fun createVideo(): Pair<Uri, ParcelFileDescriptor> {
        val name = "${newName("VID")}.mp4"
        val uri = insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, "video/mp4", "Movies/CameraKMP")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("Cannot open $uri")
        return uri to pfd
    }

    fun finishVideo(uri: Uri) = publish(uri)

    fun deleteQuietly(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Every EXIF tag ExifInterface knows about (found by reflection over its TAG_ constants). */
    fun readExif(uri: Uri): InfoSection {
        val entries = runCatching {
            resolver.openInputStream(uri)!!.use { stream ->
                val exif = ExifInterface(stream)
                EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }
            }
        }.getOrElse { listOf("error" to (it.message ?: it.toString())) }
        return InfoSection("EXIF (file)", entries.ifEmpty { listOf("EXIF" to "none found") })
    }

    fun readVideo(uri: Uri): List<InfoSection> {
        val sections = mutableListOf<InfoSection>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val keys = listOf(
                "Duration ms" to MediaMetadataRetriever.METADATA_KEY_DURATION,
                "Width" to MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                "Height" to MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                "Rotation" to MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                "Container bitrate" to MediaMetadataRetriever.METADATA_KEY_BITRATE,
                "Frame count" to MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
                "Capture frame rate" to MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
                "Color standard" to MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD,
                "Color transfer" to MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER,
                "MIME" to MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
            )
            val entries = keys.mapNotNull { (label, key) -> retriever.extractMetadata(key)?.let { label to it } }.toMutableList()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            if (durationMs != null && durationMs > 0 && size != null) {
                entries += "File size" to "$size bytes"
                entries += "Measured total bitrate" to "${size * 8 * 1000 / durationMs} bps"
            }
            sections += InfoSection("Video file", entries)
        } catch (e: Exception) {
            sections += InfoSection("Video file", listOf("error" to (e.message ?: e.toString())))
        } finally {
            retriever.release()
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f: MediaFormat = extractor.getTrackFormat(i)
                sections += InfoSection("Track $i", f.keys.sorted().map { key ->
                    key to runCatching {
                        when (f.getValueTypeForKey(key)) {
                            MediaFormat.TYPE_INTEGER -> f.getInteger(key).toString()
                            MediaFormat.TYPE_LONG -> f.getLong(key).toString()
                            MediaFormat.TYPE_FLOAT -> f.getFloat(key).toString()
                            MediaFormat.TYPE_STRING -> f.getString(key) ?: ""
                            MediaFormat.TYPE_BYTE_BUFFER -> "${f.getByteBuffer(key)?.remaining()} bytes"
                            else -> "?"
                        }
                    }.getOrDefault("?")
                })
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }
        return sections
    }

    companion object {
        val EXIF_TAGS: List<String> = ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
            .distinct()
            .sorted()
    }
}

/** Packs a YUV_420_888 image into contiguous I420 (Y, then U, then V), honoring row and pixel strides. */
internal fun Image.toI420(): ByteArray {
    val w = width
    val h = height
    val out = ByteArray(w * h * 3 / 2)
    var pos = 0
    for ((index, plane) in planes.withIndex()) {
        val pw = if (index == 0) w else w / 2
        val ph = if (index == 0) h else h / 2
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        for (y in 0 until ph) {
            buffer.position(y * rowStride)
            val length = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, length)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, pos, pw)
                pos += pw
            } else {
                for (x in 0 until pw) out[pos++] = row[x * pixelStride]
            }
        }
    }
    return out
}
