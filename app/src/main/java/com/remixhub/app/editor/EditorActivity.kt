package com.remixhub.app.editor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.remixhub.app.R
import com.remixhub.app.audio.AudioRecorder
import com.remixhub.app.audio.WavMixExporter
import com.remixhub.app.data.ProjectStore
import com.remixhub.app.data.RecordingClip
import java.io.File
import java.util.Locale
import java.util.UUID

class EditorActivity : Activity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        private const val AUDIO_REQUEST_CODE = 1001
        private const val MIC_PERMISSION_CODE = 1002
        private const val EXPORT_REQUEST_CODE = 1003
        private const val STATE_AUDIO_URI = "audio_uri"
        private const val STATE_RECORDING_PATH = "recording_path"
    }

    private lateinit var exportButton: Button
    private var exporting = false
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var projectStore: ProjectStore
    private lateinit var projectId: String
    private lateinit var offsetSeekBar: SeekBar
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var offsetText: TextView
    private lateinit var timelineText: TextView
    private lateinit var clipContainer: LinearLayout

    private var originalUri: Uri? = null
    private var recordingFile: File? = null

    private var originalPlayer: MediaPlayer? = null
    private var recordingPlayer: MediaPlayer? = null

    private var originalReady = false
    private var recordingReady = false
    private var isPlaying = false
    private var isPaused = false
    private var originalEnded = false
    private var recordingEnded = false
    private var recordingOffsetMs = 0
    private var playheadMs = 0
    private var timelineDurationMs = 0
    private var playbackStartedAtMs = 0L

    private lateinit var trackName: TextView
    private lateinit var recordingName: TextView
    private lateinit var playbackStatus: TextView

    private lateinit var importButton: Button
    private lateinit var recordButton: Button
    private lateinit var playPauseButton: Button

    private lateinit var originalVolume: SeekBar
    private lateinit var recordingVolume: SeekBar

    private val timelineHandler = Handler(Looper.getMainLooper())

    private val timelineTicker = object : Runnable {
        override fun run() {
            if (!isPlaying) return

            playheadMs = (SystemClock.elapsedRealtime() - playbackStartedAtMs)
                .toInt()
                .coerceIn(0, timelineDurationMs)

            startRecordingLayerIfDue()
            updateTimelineDisplay()

            if (playheadMs >= timelineDurationMs) {
                finishPlayback()
            } else {
                timelineHandler.postDelayed(this, 50L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        clipContainer = findViewById(R.id.clipContainer)

        audioRecorder = AudioRecorder(applicationContext)
        projectStore = ProjectStore(this)

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: run {
            finish()
            return
        }

        val project = try {
            projectStore.getById(projectId)
        } catch (_: Exception) {
            null
        }

        if (project == null) {
            finish()
            return
        }

        val selectedClip = project.clips.firstOrNull {
            it.id == project.activeClipId
        } ?: project.clips.firstOrNull()

        recordingFile = selectedClip
            ?.filePath
            ?.let(::File)
            ?.takeIf { it.exists() }

        recordingOffsetMs = selectedClip
            ?.offsetMs
            ?.coerceIn(0, 30000)
            ?: project.recordingOffsetMs.coerceIn(0, 30000)

        trackName = findViewById(R.id.textTrackName)
        recordingName = findViewById(R.id.textRecordingName)
        playbackStatus = findViewById(R.id.textPlaybackStatus)

        importButton = findViewById(R.id.btnImportAudio)
        recordButton = findViewById(R.id.btnRecord)
        playPauseButton = findViewById(R.id.btnPlayPause)
        exportButton = findViewById(R.id.btnExportMix)

        exportButton.setOnClickListener {
            openExportPicker()
        }

        originalVolume = findViewById(R.id.seekOriginalVolume)
        recordingVolume = findViewById(R.id.seekRecordingVolume)

        originalUri = project.originalUri?.let(Uri::parse)

        recordingFile = project.recordingPath
            ?.let(::File)
            ?.takeIf { it.exists() }

        originalVolume.progress = project.originalVolume.coerceIn(0, 100)
        recordingVolume.progress = project.recordingVolume.coerceIn(0, 100)
        recordingOffsetMs = project.recordingOffsetMs.coerceIn(0, 30000)

        findViewById<TextView>(R.id.textBack).setOnClickListener {
            finish()
        }

        importButton.setOnClickListener {
            openAudioPicker()
        }

        recordButton.setOnClickListener {
            if (audioRecorder.isRecording) {
                stopRecording()
            } else {
                checkMicrophonePermission()
            }
        }

        playPauseButton.setOnClickListener {
            togglePlayback()
        }

        originalVolume.setOnSeekBarChangeListener(
            volumeListener { volume ->
                originalPlayer?.setVolume(volume, volume)
            }
        )

        recordingVolume.setOnSeekBarChangeListener(
            volumeListener { volume ->
                recordingPlayer?.setVolume(volume, volume)
            }
        )

        offsetSeekBar = findViewById(R.id.seekRecordingOffset)
        timelineSeekBar = findViewById(R.id.seekTimeline)

        offsetText = findViewById(R.id.textOffset)
        timelineText = findViewById(R.id.textTimelinePosition)

        offsetSeekBar.progress = (recordingOffsetMs / 100).coerceIn(0, 300)

        offsetSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        recordingOffsetMs = progress * 100
                        refreshTimelineDuration()
                        updateTimelineDisplay()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    if (isPlaying) pausePlayback()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    try {
                        projectStore.update(
                            projectId,
                            "Recording moved to ${recordingOffsetMs / 1000.0}s"
                        ) { project ->
                            project.copy(
                                recordingOffsetMs = recordingOffsetMs,
                                clips = project.clips.map { clip ->
                                    if (clip.id == project.activeClipId) {
                                        clip.copy(offsetMs = recordingOffsetMs)
                                    } else {
                                        clip
                                    }
                                }
                            )
                        }
                    } catch (_: Exception) {
                        playbackStatus.text = "Unable to save recording position."
                    }
                }
            }
        )

        timelineSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        playheadMs = (progress * 100).coerceAtMost(timelineDurationMs)
                        updateTimelineDisplay()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    if (isPlaying) pausePlayback()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    seekPreviewTo(playheadMs)
                }
            }
        )

        updateTimelineDisplay()
        displayRecordingClips()
        updateTrackLabels()
        updateControls()

        if (::clipContainer.isInitialized &&
            ::projectStore.isInitialized &&
            ::projectId.isInitialized
        ) {
            for (index in 0 until clipContainer.childCount) {
                val button = clipContainer.getChildAt(index) as? Button
                if (button != null) {
                    button.isEnabled =
                        !audioRecorder.isRecording && !exporting &&
                        !button.text.contains("(Missing file)")
                }
            }
        }
    }

    private fun saveNewRecording(file: File): Boolean {
        val clip = RecordingClip(
            id = UUID.randomUUID().toString(),
            filePath = file.absolutePath,
            offsetMs = 0
        )

        return try {
            projectStore.update(
                projectId,
                "New recording clip added"
            ) { project ->
                project.copy(
                    clips = project.clips + clip,
                    activeClipId = clip.id,
                    recordingPath = clip.filePath,
                    recordingOffsetMs = 0
                )
            }

            recordingFile = file
            recordingOffsetMs = 0
            offsetSeekBar.progress = 0

            updateTrackLabels()
            displayRecordingClips()

            true
        } catch (_: Exception) {
            playbackStatus.setText(R.string.clip_save_error)
            false
        }
    }

    private fun displayRecordingClips() {
        if (!::clipContainer.isInitialized) return

        clipContainer.removeAllViews()

        val project = projectStore.getById(projectId) ?: return

        if (project.clips.isEmpty()) {
            val empty = TextView(this).apply {
                setText(R.string.recording_clips_empty)
                setTextColor(getColor(R.color.gray))
            }

            clipContainer.addView(empty)
            return
        }

        project.clips.forEachIndexed { index, clip ->
            val selected = clip.id == project.activeClipId
            val available = File(clip.filePath).exists()

            val button = Button(this).apply {
                text = buildString {
                    append(
                        getString(
                            R.string.recording_clip_format,
                            index + 1
                        )
                    )

                    if (selected) append(" (Selected)")
                    if (!available) append(" (Missing file)")
                }

                isAllCaps = false
                isEnabled = available && !audioRecorder.isRecording && !exporting

                setTextColor(
                    getColor(
                        if (selected) R.color.white
                        else R.color.black
                    )
                )

                backgroundTintList = ColorStateList.valueOf(
                    getColor(
                        if (selected) R.color.black
                        else R.color.light_gray
                    )
                )

                setOnClickListener {
                    selectRecordingClip(clip)
                }
            }

            clipContainer.addView(button)
        }
    }

    private fun selectRecordingClip(clip: RecordingClip) {
        if (audioRecorder.isRecording || exporting) return
        if (!File(clip.filePath).exists()) return

        try {
            projectStore.update(
                projectId,
                "Recording clip selected"
            ) {
                it.copy(
                    activeClipId = clip.id,
                    recordingPath = clip.filePath,
                    recordingOffsetMs = clip.offsetMs
                )
            }

            releasePlayers()

            recordingFile = File(clip.filePath)
            recordingOffsetMs = clip.offsetMs.coerceIn(0, 30000)

            offsetSeekBar.progress = recordingOffsetMs / 100

            updateTrackLabels()
            displayRecordingClips()
            preparePlayers()
        } catch (_: Exception) {
            playbackStatus.setText(R.string.clip_save_error)
        }
    }

    private fun volumeListener(
        onVolumeChanged: (Float) -> Unit
    ): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    onVolumeChanged(progress / 100f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                try {
                    when (seekBar.id) {
                        R.id.seekOriginalVolume -> {
                            projectStore.update(
                                projectId,
                                "Original volume changed"
                            ) {
                                it.copy(originalVolume = seekBar.progress)
                            }
                        }

                        R.id.seekRecordingVolume -> {
                            projectStore.update(
                                projectId,
                                "Recording volume changed"
                            ) {
                                it.copy(recordingVolume = seekBar.progress)
                            }
                        }
                    }
                } catch (_: Exception) {
                    playbackStatus.text = "Unable to save volume setting."
                }
            }
        }
    }

    // AUDIO IMPORT

    private fun openAudioPicker() {
        if (exporting || audioRecorder.isRecording) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, AUDIO_REQUEST_CODE)
    }

    @Deprecated("Native Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == EXPORT_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { destination ->
                    exportMix(destination)
                }
            }
            return
        }

        if (requestCode != AUDIO_REQUEST_CODE || resultCode != RESULT_OK) return

        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The temporary document grant can
            // still support current-session playback.
        }

        try {
            projectStore.update(
                projectId,
                "Original audio imported"
            ) {
                it.copy(originalUri = uri.toString())
            }

            originalUri = uri
            updateTrackLabels()
            preparePlayers()
        } catch (_: Exception) {
            playbackStatus.text = "Unable to save imported audio."
        }
    }

    private fun getAudioFileName(uri: Uri): String {
        var name = "Selected audio track"

        try {
            contentResolver.query(
                uri, null, null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (index >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(index) ?: name
                }
            }
        } catch (_: Exception) {
            // Keep fallback filename.
        }

        return name
    }

    private fun openExportPicker() {
        if (exporting || audioRecorder.isRecording) return

        if (originalUri == null && recordingFile == null) {
            playbackStatus.text = "No audio available to export."
            return
        }

        val projectName = projectStore.getById(projectId)?.name ?: "RemixHub"
        val safeName = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "${safeName}_mix.wav")
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, EXPORT_REQUEST_CODE)
    }

    private fun exportMix(destinationUri: Uri) {
        if (exporting || audioRecorder.isRecording) return

        val sourceOriginal = originalUri
        val sourceRecording = recordingFile
            ?.takeIf { it.exists() }
            ?.let(Uri::fromFile)

        if (sourceOriginal == null && sourceRecording == null) {
            playbackStatus.text = "No audio available to export."
            return
        }

        val originalGain = originalVolume.progress / 100f
        val recordingGain = recordingVolume.progress / 100f
        val exportOffsetMs = recordingOffsetMs

        exporting = true
        playbackStatus.setText(R.string.status_exporting)

        // Exporting should not compete with preview playback.
        releasePlayers()
        updateControls()

        Thread {
            try {
                val stream = requireNotNull(
                    contentResolver.openOutputStream(destinationUri, "w")
                ) {
                    "Unable to open export destination."
                }

                stream.use {
                    val exporter = WavMixExporter(applicationContext)
                    exporter.export(
                        originalUri = sourceOriginal,
                        recordingUri = sourceRecording,
                        originalVolume = originalGain,
                        recordingVolume = recordingGain,
                        recordingOffsetMs = exportOffsetMs,
                        destination = it
                    )
                }

                projectStore.update(
                    projectId,
                    "Mix exported as WAV"
                ) { it }

                runOnUiThread {
                    if (!isDestroyed) {
                        exporting = false
                        preparePlayers()
                        playbackStatus.setText(R.string.status_export_success)
                        updateControls()
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e(
                    "RemixHub",
                    "Audio export failed",
                    error
                )

                runOnUiThread {
                    if (!isDestroyed) {
                        exporting = false
                        preparePlayers()

                        playbackStatus.text =
                            "Export failed: " + (error.message ?: "Unknown error")

                        updateControls()
                    }
                }
            }
        }.start()
    }

    // MICROPHONE RECORDING

    private fun checkMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != MIC_PERMISSION_CODE) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            playbackStatus.setText(R.string.status_permission_denied)
        }
    }

    private fun startRecording() {
        if (audioRecorder.isRecording) return

        releasePlayers()

        try {
            audioRecorder.start()

            recordButton.setText(R.string.stop_recording)
            playbackStatus.setText(R.string.status_recording)
        } catch (_: Exception) {
            playbackStatus.setText(R.string.status_recording_error)
        }

        updateControls()
    }

    private fun stopRecording() {
        val file = audioRecorder.stop()

        if (file != null) {
            if (saveNewRecording(file)) {
                playbackStatus.setText(R.string.status_recorded)
            }
        } else {
            playbackStatus.setText(R.string.status_recording_error)
        }

        preparePlayers()
    }

    // AUDIO PREPARATION

    private fun preparePlayers() {
        releasePlayers()
        playheadMs = 0
        updateControls()

        if (originalUri == null && recordingFile == null) {
            playbackStatus.setText(R.string.status_empty)
            return
        }

        playbackStatus.setText(R.string.status_loading)

        originalUri?.let { uri ->
            originalPlayer = createPlayer(
                configure = { player ->
                    player.setDataSource(this, uri)
                },
                onReady = {
                    originalReady = true
                    updateReadyState()
                },
                onFinished = {
                    // Original audio controls the end
                    // of a two-track preview.
                }
            )
        }

        recordingFile?.let { file ->
            if (file.exists()) {
                recordingPlayer = createPlayer(
                    configure = { player ->
                        player.setDataSource(file.absolutePath)
                    },
                    onReady = {
                        recordingReady = true
                        updateReadyState()
                    },
                    onFinished = {
                    }
                )
            }
        }

        updateControls()
        exportButton.isEnabled =
            !audioRecorder.isRecording &&
            !exporting &&
            (originalUri != null || recordingFile != null)
    }

    private fun createPlayer(
        configure: (MediaPlayer) -> Unit,
        onReady: () -> Unit,
        onFinished: () -> Unit
    ): MediaPlayer? {
        val player = MediaPlayer()

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            configure(player)

            player.setOnPreparedListener {
                if (!audioRecorder.isRecording) {
                    onReady()
                }
            }

            player.setOnCompletionListener {
                onFinished()
            }

            player.setOnErrorListener { _, _, _ ->
                releasePlayers()
                playbackStatus.setText(R.string.status_error)
                true
            }

            player.prepareAsync()
            return player
        } catch (_: Exception) {
            player.release()
            playbackStatus.setText(R.string.status_error)
            return null
        }
    }

    private fun updateReadyState() {
        val ready =
            (originalUri == null || originalReady) &&
            (recordingFile == null || recordingReady)

        refreshTimelineDuration()

        if (ready) {
            playbackStatus.setText(R.string.status_ready)
        }

        updateControls()
    }

    // TWO-TRACK PLAYBACK

    private fun togglePlayback() {
        if (isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (audioRecorder.isRecording || exporting) return

        val ready =
            (originalUri == null || originalReady) &&
            (recordingFile == null || recordingReady)

        if (!ready || timelineDurationMs <= 0) return

        timelineHandler.removeCallbacks(timelineTicker)

        if (playheadMs >= timelineDurationMs) {
            playheadMs = 0
        }

        try {
            val originalGain = originalVolume.progress / 100f
            val recordingGain = recordingVolume.progress / 100f

            originalPlayer?.setVolume(originalGain, originalGain)
            recordingPlayer?.setVolume(recordingGain, recordingGain)

            // Prepare the original player at the
            // selected global timeline position.
            originalPlayer?.takeIf { originalReady }?.let {
                if (playheadMs < it.duration) {
                    it.seekTo(
                        playheadMs.toLong(),
                        MediaPlayer.SEEK_CLOSEST
                    )
                    it.start()
                }
            }

            // If the current position is already
            // past the recording start, begin from
            // the corresponding position in that layer.
            startRecordingLayerIfDue()

            playbackStartedAtMs = SystemClock.elapsedRealtime() - playheadMs

            isPlaying = true
            isPaused = false

            playbackStatus.setText(R.string.status_playing)

            updateControls()
            updateTimelineDisplay()

            timelineHandler.post(timelineTicker)
        } catch (_: IllegalStateException) {
            releasePlayers()
            playbackStatus.setText(R.string.status_error)
        }
    }

    private fun startRecordingLayerIfDue() {
        if (!recordingReady) return

        val player = recordingPlayer ?: return
        val relativePosition = playheadMs - recordingOffsetMs

        if (relativePosition < 0 || relativePosition >= player.duration) {
            return
        }

        try {
            if (!player.isPlaying) {
                player.seekTo(
                    relativePosition.toLong(),
                    MediaPlayer.SEEK_CLOSEST
                )

                player.setVolume(
                    recordingVolume.progress / 100f,
                    recordingVolume.progress / 100f
                )

                player.start()
            }
        } catch (_: IllegalStateException) {
            playbackStatus.setText(R.string.status_error)
        }
    }

    private fun pausePlayback() {
        timelineHandler.removeCallbacks(timelineTicker)

        if (isPlaying) {
            playheadMs = (SystemClock.elapsedRealtime() - playbackStartedAtMs)
                .toInt()
                .coerceIn(0, timelineDurationMs)
        }

        try {
            if (originalPlayer?.isPlaying == true) {
                originalPlayer?.pause()
            }

            if (recordingPlayer?.isPlaying == true) {
                recordingPlayer?.pause()
            }
        } catch (_: IllegalStateException) {
            releasePlayers()
            playbackStatus.setText(R.string.status_error)
            return
        }

        isPlaying = false
        isPaused = true

        playbackStatus.setText(R.string.status_paused)
        updateControls()
        updateTimelineDisplay()
    }

    private fun finishPlayback() {
        timelineHandler.removeCallbacks(timelineTicker)

        try {
            if (originalPlayer?.isPlaying == true) {
                originalPlayer?.pause()
            }

            if (recordingPlayer?.isPlaying == true) {
                recordingPlayer?.pause()
            }
        } catch (_: IllegalStateException) {
            // Players are cleaned up on activity stop.
        }

        playheadMs = timelineDurationMs
        isPlaying = false
        isPaused = false

        playbackStatus.setText(R.string.status_finished)

        updateControls()
        updateTimelineDisplay()
    }

    private fun releasePlayers() {
        timelineHandler.removeCallbacks(timelineTicker)
        val oldOriginal = originalPlayer
        val oldRecording = recordingPlayer

        originalPlayer = null
        recordingPlayer = null

        originalReady = false
        recordingReady = false
        isPlaying = false
        isPaused = false
        originalEnded = false
        recordingEnded = false

        oldOriginal?.release()
        oldRecording?.release()
        timelineDurationMs = 0
        updateTimelineDisplay()

        updateControls()
    }

    // UI STATE

    private fun updateTrackLabels() {
        trackName.text = originalUri?.let {
            getAudioFileName(it)
        } ?: getString(R.string.no_track)

        recordingName.text =
            if (recordingFile != null) {
                getString(R.string.recording_name)
            } else {
                getString(R.string.no_recording)
            }
    }

    private fun updateControls() {
        if (!::playPauseButton.isInitialized) return

        val recording = audioRecorder.isRecording
        val hasAudio = originalPlayer != null || recordingPlayer != null
        val ready =
            (originalUri == null || originalReady) &&
            (recordingFile == null || recordingReady)

        importButton.isEnabled = !recording
        playPauseButton.isEnabled = !recording && hasAudio && ready

        recordButton.setText(
            if (recording) {
                R.string.stop_recording
            } else {
                R.string.start_recording
            }
        )

        playPauseButton.setText(
            if (isPlaying) {
                R.string.pause_mix
            } else {
                R.string.play_mix
            }
        )

        if (::offsetSeekBar.isInitialized) {
            offsetSeekBar.isEnabled =
                !audioRecorder.isRecording &&
                !exporting &&
                recordingReady

            timelineSeekBar.isEnabled =
                !audioRecorder.isRecording &&
                !exporting &&
                timelineDurationMs > 0
        }
    }

    private fun refreshTimelineDuration() {
        val originalDuration =
            if (originalReady) {
                originalPlayer?.duration ?: 0
            } else {
                0
            }

        val recordingDuration =
            if (recordingReady) {
                recordingPlayer?.duration ?: 0
            } else {
                0
            }

        timelineDurationMs = maxOf(
            originalDuration,
            if (recordingReady) {
                recordingOffsetMs + recordingDuration
            } else {
                0
            }
        ).coerceAtLeast(0)

        playheadMs = playheadMs.coerceIn(0, timelineDurationMs)

        if (::timelineSeekBar.isInitialized) {
            timelineSeekBar.max = (timelineDurationMs + 99) / 100
        }

        updateTimelineDisplay()
    }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return String.format(
            Locale.US,
            "%d:%02d",
            minutes,
            seconds
        )
    }

    private fun updateTimelineDisplay() {
        if (!::timelineSeekBar.isInitialized) return

        timelineSeekBar.progress = playheadMs / 100

        timelineText.text = getString(
            R.string.timeline_position_format,
            formatTime(playheadMs),
            formatTime(timelineDurationMs)
        )

        offsetText.text = getString(
            R.string.timeline_offset_format,
            recordingOffsetMs / 1000.0
        )
    }

    private fun seekPreviewTo(positionMs: Int) {
        if (audioRecorder.isRecording || exporting) return

        timelineHandler.removeCallbacks(timelineTicker)

        if (isPlaying) {
            pausePlayback()
        }

        playheadMs = positionMs.coerceIn(0, timelineDurationMs)
        isPaused = true

        try {
            originalPlayer?.takeIf { originalReady }?.let {
                it.seekTo(
                    playheadMs.coerceAtMost(it.duration).toLong(),
                    MediaPlayer.SEEK_CLOSEST
                )
            }

            recordingPlayer?.takeIf { recordingReady }?.let {
                val positionInRecording =
                    (playheadMs - recordingOffsetMs).coerceIn(0, it.duration)

                it.seekTo(
                    positionInRecording.toLong(),
                    MediaPlayer.SEEK_CLOSEST
                )
            }
        } catch (_: IllegalStateException) {
            playbackStatus.setText(R.string.status_error)
        }

        updateTimelineDisplay()
    }

    // ACTIVITY LIFECYCLE

    override fun onResume() {
        super.onResume()

        if (!audioRecorder.isRecording &&
            originalPlayer == null &&
            recordingPlayer == null
        ) {
            preparePlayers()
        }
    }

    override fun onStop() {
        if (audioRecorder.isRecording) {
            val file = audioRecorder.stop()

            if (file != null) {
                saveNewRecording(file)
                recordingFile = file

                try {
                    projectStore.update(
                        projectId,
                        "Microphone recording saved"
                    ) {
                        it.copy(recordingPath = file.absolutePath)
                    }
                } catch (_: Exception) {
                    // The recording remains in app-private storage,
                    // but the project metadata could not be saved.
                }
            }

            updateTrackLabels()
        }

        releasePlayers()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        originalUri?.let {
            outState.putString(STATE_AUDIO_URI, it.toString())
        }

        recordingFile?.let {
            outState.putString(STATE_RECORDING_PATH, it.absolutePath)
        }

        super.onSaveInstanceState(outState)
    }
}