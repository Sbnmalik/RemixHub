package com.remixhub.app.editor

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import com.remixhub.app.R

class EditorActivity : Activity() {

    companion object {
        private const val AUDIO_REQUEST_CODE = 1001
        private const val STATE_AUDIO_URI = "audio_uri"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var selectedAudioUri: Uri? = null
    private var isPrepared = false

    private lateinit var trackName: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var playPauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_editor)

        trackName = findViewById(R.id.textTrackName)
        playbackStatus = findViewById(R.id.textPlaybackStatus)
        playPauseButton = findViewById(R.id.btnPlayPause)

        val importButton =
            findViewById<Button>(R.id.btnImportAudio)

        val backButton =
            findViewById<TextView>(R.id.textBack)

        selectedAudioUri = savedInstanceState
            ?.getString(STATE_AUDIO_URI)
            ?.let(Uri::parse)

        backButton.setOnClickListener {
            finish()
        }

        importButton.setOnClickListener {
            openAudioPicker()
        }

        playPauseButton.setOnClickListener {
            togglePlayback()
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        startActivityForResult(intent, AUDIO_REQUEST_CODE)
    }

    @Deprecated("Uses the native Activity result API for this MVP")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != AUDIO_REQUEST_CODE ||
            resultCode != RESULT_OK
        ) {
            return
        }

        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The temporary read grant may still allow
            // playback for the current session.
        }

        selectedAudioUri = uri
        trackName.text = getAudioFileName(uri)

        loadAudio(uri)
    }

    private fun getAudioFileName(uri: Uri): String {
        var name = "Selected audio track"

        try {
            contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )?.use { cursor ->

                val index = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

                if (index >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(index) ?: name
                }
            }
        } catch (_: Exception) {
            // Keep the fallback name.
        }

        return name
    }

    private fun loadAudio(uri: Uri) {
        releasePlayer()

        playbackStatus.setText(R.string.status_loading)

        val newPlayer = MediaPlayer()
        mediaPlayer = newPlayer

        try {
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )

            newPlayer.setDataSource(this, uri)

            newPlayer.setOnPreparedListener { preparedPlayer ->
                if (mediaPlayer === preparedPlayer) {
                    isPrepared = true
                    playPauseButton.isEnabled = true
                    playPauseButton.setText(
                        R.string.play_audio
                    )
                    playbackStatus.setText(
                        R.string.status_ready
                    )
                }
            }

            newPlayer.setOnCompletionListener { completedPlayer ->
                if (mediaPlayer === completedPlayer) {
                    playPauseButton.setText(
                        R.string.play_audio
                    )
                    playbackStatus.setText(
                        R.string.status_finished
                    )
                }
            }

            newPlayer.setOnErrorListener { failedPlayer, _, _ ->
                if (mediaPlayer === failedPlayer) {
                    releasePlayer()
                    playbackStatus.setText(
                        R.string.status_error
                    )
                }
                true
            }

            newPlayer.prepareAsync()

        } catch (_: Exception) {
            releasePlayer()
            playbackStatus.setText(R.string.status_error)
        }
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return

        if (!isPrepared) return

        try {
            if (player.isPlaying) {
                player.pause()
                playPauseButton.setText(
                    R.string.play_audio
                )
                playbackStatus.setText(
                    R.string.status_paused
                )
            } else {
                player.start()
                playPauseButton.setText(
                    R.string.pause_audio
                )
                playbackStatus.setText(
                    R.string.status_playing
                )
            }
        } catch (_: IllegalStateException) {
            releasePlayer()
            playbackStatus.setText(R.string.status_error)
        }
    }

    private fun releasePlayer() {
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        isPrepared = false

        playPauseButton.isEnabled = false
        playPauseButton.setText(R.string.play_audio)

        oldPlayer?.release()
    }

    override fun onResume() {
        super.onResume()

        if (mediaPlayer == null) {
            selectedAudioUri?.let { uri ->
                trackName.text = getAudioFileName(uri)
                loadAudio(uri)
            }
        }
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedAudioUri?.let {
            outState.putString(
                STATE_AUDIO_URI,
                it.toString()
            )
        }

        super.onSaveInstanceState(outState)
    }
}