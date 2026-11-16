package dev.camerakmp.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import dev.camerakmp.model.VideoPreset
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Records camera frames with the exact encoder that [VideoValidator] approved (by codec name),
 * plus optional AAC audio, into an MP4. The camera writes straight into [inputSurface].
 *
 * @param clockNs the clock that matches the camera's sensor timestamps, so audio stays in sync.
 */
internal class VideoRecorder(
    private val preset: VideoPreset,
    output: FileDescriptor,
    orientationHint: Int,
    private val recordAudio: Boolean,
    private val clockNs: () -> Long,
) {
    private val videoCodec = MediaCodec.createByCodecName(preset.encoderName)
    val inputSurface: Surface
    private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val muxerLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = mutableListOf<PendingSample>()

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    @Volatile private var stopping = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private class PendingSample(val track: () -> Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    init {
        videoCodec.configure(
            VideoValidator.encoderFormat(preset.codec.mime, preset.width, preset.height, preset.fps, preset.bitrate, videoCodec.codecInfo),
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        inputSurface = videoCodec.createInputSurface()
        muxer.setOrientationHint(orientationHint)
        if (recordAudio) setupAudio()
    }

    @SuppressLint("MissingPermission") // Permission is requested before recording starts.
    private fun setupAudio() {
        val sampleRate = 48_000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        // Low-bitrate video gets low-bitrate audio so the total stays small.
        val audioBitrate = if (preset.bitrate <= VideoValidator.LOW_BITRATE_MAX) 32_000 else 128_000
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioRecord = record
        audioCodec = codec
    }

    fun start() {
        videoCodec.start()
        videoThread = thread(name = "video-drain") { runCatching { drainVideo() }.onFailure { Log.w(TAG, "video drain", it) } }
        val record = audioRecord
        val codec = audioCodec
        if (record != null && codec != null) {
            codec.start()
            record.startRecording()
            audioThread = thread(name = "audio-encode") { runCatching { encodeAudio(record, codec) }.onFailure { Log.w(TAG, "audio encode", it) } }
        }
    }

    /** Stops encoding, finalizes the MP4 and releases everything. */
    fun stop() {
        stopping = true
        runCatching { videoCodec.signalEndOfInputStream() }
        videoThread?.join(3000)
        audioThread?.join(3000)
        runCatching { videoCodec.stop() }
        videoCodec.release()
        audioCodec?.let { runCatching { it.stop() }; it.release() }
        audioRecord?.let { runCatching { it.stop() }; it.release() }
        inputSurface.release()
        synchronized(muxerLock) {
            if (muxerStarted) runCatching { muxer.stop() }.onFailure { Log.e(TAG, "muxer stop", it) }
            muxer.release()
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = videoCodec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    videoTrack = muxer.addTrack(videoCodec.outputFormat)
                    maybeStartMuxer()
                }
                index >= 0 -> {
                    val buffer = videoCodec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        write({ videoTrack }, buffer, info)
                    }
                    videoCodec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && stopping && videoTrack < 0 -> return
            }
        }
    }

    private fun encodeAudio(record: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        while (true) {
            if (!eosSent) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val read = record.read(buffer, buffer.capacity())
                    val ptsUs = clockNs() / 1000
                    if (stopping) {
                        codec.queueInputBuffer(inIndex, 0, read.coerceAtLeast(0), ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, read.coerceAtLeast(0), ptsUs, 0)
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    audioTrack = muxer.addTrack(codec.outputFormat)
                    maybeStartMuxer()
                }
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        write({ audioTrack }, buffer, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun maybeStartMuxer() {
        val audioReady = audioCodec == null || audioTrack >= 0
        if (!muxerStarted && videoTrack >= 0 && audioReady) {
            muxer.start()
            muxerStarted = true
            for (s in pending) muxer.writeSampleData(s.track(), s.data, s.info)
            pending.clear()
        }
    }

    private fun write(track: () -> Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (muxerStarted) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer.writeSampleData(track(), buffer, info)
            } else {
                // Keep early samples (the first video keyframe) until both tracks are known.
                val copy = ByteBuffer.allocateDirect(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                copy.put(buffer)
                copy.flip()
                val infoCopy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += PendingSample(track, copy, infoCopy)
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
    }
}
