package com.remixhub.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WavMixExporter(
    private val context: Context
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val MAX_SECONDS = 30

        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_FRAMES = SAMPLE_RATE * MAX_SECONDS
        private const val TIMEOUT_US = 10000L
    }

    private class DecodedTrack(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int
    ) {
        private val buffer = ByteBuffer.wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN)

        val frames: Int
            get() = pcm.size / (channels * 2)

        fun sampleAt(time: Double, channel: Int): Double {
            val position = time * sampleRate
            val index = floor(position).toInt()

            if (index < 0 || index >= frames) {
                return 0.0
            }

            val next = min(index + 1, frames - 1)
            val fraction = position - index
            val actualChannel = if (channels == 1) 0 else channel

            fun read(frame: Int): Double {
                val offset = (frame * channels + actualChannel) * 2
                return buffer.getShort(offset).toDouble()
            }

            val first = read(index)
            val second = read(next)

            return first + (second - first) * fraction
        }

        fun durationFrames(): Int {
            return ceil(
                frames.toDouble() * SAMPLE_RATE / sampleRate
            ).toInt()
        }
    }

    fun export(
        originalUri: Uri?,
        recordingUri: Uri?,
        originalVolume: Float,
        recordingVolume: Float,
        recordingOffsetMs: Int,
        destination: OutputStream
    ) {
        require(originalUri != null || recordingUri != null) {
            "There is no audio to export."
        }

        val original = originalUri?.let(::decode)
        val recording = recordingUri?.let(::decode)

        val clampedOffsetMs = recordingOffsetMs.coerceIn(0, 30000)
        val offsetFrames = (clampedOffsetMs.toLong() * SAMPLE_RATE / 1000).toInt()

        val totalFrames = min(
            MAX_FRAMES,
            max(
                original?.durationFrames() ?: 0,
                if (recording != null) {
                    recording.durationFrames() + offsetFrames
                } else {
                    0
                }
            )
        )

        require(totalFrames > 0) {
            "The audio tracks contain no samples."
        }

        val dataSize = totalFrames * CHANNELS * BYTES_PER_SAMPLE
        val out = destination.buffered()

        writeWavHeader(out, dataSize)

        val originalGain = originalVolume.coerceIn(0f, 1f).toDouble()
        val recordingGain = recordingVolume.coerceIn(0f, 1f).toDouble()
        val recordingOffsetSec = clampedOffsetMs / 1000.0

        // Process small chunks instead of allocating
        // an additional full-length output array.
        val chunkFrames = 2048
        val chunk = ByteArray(chunkFrames * CHANNELS * 2)

        var frame = 0

        while (frame < totalFrames) {
            val count = min(chunkFrames, totalFrames - frame)
            var position = 0

            for (i in 0 until count) {
                val time = (frame + i).toDouble() / SAMPLE_RATE
                val recordingTime = time - recordingOffsetSec

                for (channel in 0 until CHANNELS) {
                    val first = original?.sampleAt(time, channel) ?: 0.0
                    val second = recording?.sampleAt(recordingTime, channel) ?: 0.0

                    val mixed = (first * originalGain + second * recordingGain)
                        .roundToInt()
                        .coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()
                        )

                    // Signed 16-bit, little-endian PCM.
                    chunk[position++] = (mixed and 0xFF).toByte()
                    chunk[position++] = ((mixed ushr 8) and 0xFF).toByte()
                }
            }

            out.write(chunk, 0, position)
            frame += count
        }

        out.flush()
    }

    private fun decode(uri: Uri): DecodedTrack {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false

        try {
            if (uri.scheme == "file") {
                extractor.setDataSource(requireNotNull(uri.path))
            } else {
                extractor.setDataSource(context, uri, null)
            }

            val trackIndex = (0 until extractor.trackCount)
                .firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("No audio track found.")

            extractor.selectTrack(trackIndex)

            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))

            decoder = MediaCodec.createDecoderByType(mime)
            val codec = decoder

            codec.configure(sourceFormat, null, null, 0)
            codec.start()
            started = true

            val data = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()

            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            var maxBytes = Int.MAX_VALUE

            while (!outputEnded) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)

                    if (index >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(index))
                        input.clear()

                        val size = extractor.readSampleData(input, 0)

                        if (size < 0) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0),
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)

                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                    }

                    index >= 0 -> {
                        val format = outputFormat ?: codec.outputFormat
                        outputFormat = format

                        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }

                        require(
                            channels in 1..2 &&
                                encoding == AudioFormat.ENCODING_PCM_16BIT
                        ) {
                            "Only mono/stereo 16-bit PCM decoding is supported."
                        }

                        maxBytes = (rate.toLong() * MAX_SECONDS * channels * 2L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()

                        if (info.size > 0) {
                            val output = requireNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)

                            val room = maxBytes - data.size()
                            val bytesToCopy = min(info.size, room)
                                .coerceAtLeast(0)
                                .let { it - (it % (channels * 2)) }

                            if (bytesToCopy > 0) {
                                val bytes = ByteArray(bytesToCopy)
                                output.get(bytes)
                                data.write(bytes)
                            }
                        }

                        outputEnded =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            data.size() >= maxBytes

                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }

            val format = requireNotNull(outputFormat) {
                "Decoder did not produce an audio format."
            }

            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            return DecodedTrack(
                pcm = data.toByteArray(),
                sampleRate = sampleRate,
                channels = channels
            )
        } finally {
            if (started) {
                try {
                    decoder?.stop()
                } catch (_: Exception) {
                    // Continue resource cleanup.
                }
            }

            decoder?.release()
            extractor.release()
        }
    }

    private fun writeWavHeader(
        output: OutputStream,
        dataSize: Int
    ) {
        fun text(value: String) {
            output.write(value.toByteArray(Charsets.US_ASCII))
        }

        fun little(value: Int, bytes: Int) {
            for (i in 0 until bytes) {
                output.write((value ushr (8 * i)) and 0xFF)
            }
        }

        val blockAlign = CHANNELS * BYTES_PER_SAMPLE
        val byteRate = SAMPLE_RATE * blockAlign

        text("RIFF")
        little(36 + dataSize, 4)
        text("WAVE")

        text("fmt ")
        little(16, 4)
        little(1, 2) // PCM
        little(CHANNELS, 2)
        little(SAMPLE_RATE, 4)
        little(byteRate, 4)
        little(blockAlign, 2)
        little(16, 2)

        text("data")
        little(dataSize, 4)
    }
}