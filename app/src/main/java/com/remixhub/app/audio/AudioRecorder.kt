
package com.remixhub.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        check(!isRecording) {
            "A recording is already in progress."
        }

        val directory = File(
            context.filesDir,
            "recordings"
        )
        directory.mkdirs()

        val file = File(
            directory,
            "recording_${System.currentTimeMillis()}.m4a"
        )

        @Suppress("DEPRECATION")
        val newRecorder =
            if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

        try {
            newRecorder.setAudioSource(
                MediaRecorder.AudioSource.MIC
            )

            newRecorder.setOutputFormat(
                MediaRecorder.OutputFormat.MPEG_4
            )

            newRecorder.setAudioEncoder(
                MediaRecorder.AudioEncoder.AAC
            )

            newRecorder.setAudioEncodingBitRate(128000)
            newRecorder.setAudioSamplingRate(44100)

            newRecorder.setOutputFile(
                file.absolutePath
            )

            newRecorder.prepare()
            newRecorder.start()

            recorder = newRecorder
            outputFile = file

        } catch (error: Exception) {
            newRecorder.release()
            file.delete()
            throw error
        }
    }

    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val file = outputFile

        recorder = null
        outputFile = null

        var successful = false

        try {
            activeRecorder.stop()
            successful = true
        } catch (_: RuntimeException) {
            // A recording may be invalid if stopped
            // too quickly or if capturing failed.
        } finally {
            activeRecorder.release()
        }

        if (!successful) {
            file?.delete()
            return null
        }

        return file
    }
}