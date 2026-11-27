package com.remixhub.app.editor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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

    private data class ExtraClipPlayer(
        val clipId: String,
        val offsetMs: Int,
        val muted: Boolean,
        val player: MediaPlayer,
        var ready: Boolean = false
    )

    private lateinit var visualTimeline: TimelineView
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

    private val extraClipPlayers = mutableListOf<ExtraClipPlayer>()
    private val finishedClipIds = mutableSetOf<String>()

    private var selectedClipId: String? = null
    private var selectedClipMuted = false

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

        selectedClipId = selectedClip?.id
        selectedClipMuted = selectedClip?.isMuted ?: false

        trackName = findViewById(R.id.textTrackName)
        recordingName = findViewById(R.id.textRecordingName)
        playbackStatus = findViewById(R.id.textPlaybackStatus)

        importButton = findViewById(R.id.btnImportAudio)
        recordButton = findViewById(R.id.btnRecord)
        playPauseButton = findViewById(R.id.btnPlayPause)
        exportButton = findViewById(R.id.btnExportMix)

        exportButton.setOnClickListener { openExportPicker() }

        originalVolume = findViewById(R.id.seekOriginalVolume)
        recordingVolume = findViewById(R.id.seekRecordingVolume)

        originalUri = project.originalUri?.let(Uri::parse)

        recordingFile = (selectedClip?.filePath ?: project.recordingPath)
            ?.let(::File)
            ?.takeIf { it.exists() }

        originalVolume.progress = project.originalVolume.coerceIn(0, 100)
        recordingVolume.progress = project.recordingVolume.coerceIn(0, 100)
        recordingOffsetMs = (selectedClip?.offsetMs ?: project.recordingOffsetMs).coerceIn(0, 30000)

        findViewById<TextView>(R.id.textBack).setOnClickListener { finish() }

        importButton.setOnClickListener { openAudioPicker() }

        recordButton.setOnClickListener {
            if (audioRecorder.isRecording) {
                stopRecording()
            } else {
                checkMicrophonePermission()
            }
        }

        playPauseButton.setOnClickListener { togglePlayback() }

        originalVolume.setOnSeekBarChangeListener(
            volumeListener { volume -> originalPlayer?.setVolume(volume, volume) }
        )
        recordingVolume.setOnSeekBarChangeListener(
            volumeListener { applyAllRecordingVolumes() }
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
                        refreshVisualTimeline()
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
                        refreshVisualTimeline()
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

        visualTimeline = findViewById(R.id.visualTimeline)

        visualTimeline.onClipSelected = { clipId ->
            if (!audioRecorder.isRecording && !exporting) {
                val clip = projectStore.getById(projectId)
                    ?.clips
                    ?.firstOrNull { it.id == clipId }

                if (clip != null) {
                    selectRecordingClip(clip)
                }
            }
        }

        visualTimeline.onClipMoved = { clipId, newOffsetMs ->
            moveClipOnTimeline(clipId, newOffsetMs)
        }

        updateTimelineDisplay()
        displayRecordingClips()
        updateTrackLabels()
        updateControls()
        refreshVisualTimeline()

        if (::clipContainer.isInitialized &&
            ::projectStore.isInitialized &&
            ::projectId.isInitialized
        ) {
            fun updateClipViews(group: LinearLayout) {
                for (index in 0 until group.childCount) {
                    when (val child = group.getChildAt(index)) {
                        is LinearLayout -> updateClipViews(child)
                        is Button, is SeekBar -> {
                            val path = child.tag as? String
                            val available = path == null || File(path).exists()
                            val canEdit = !audioRecorder.isRecording && !exporting

                            child.isEnabled = available && canEdit

                            // Rename and delete metadata are allowed
                            // even when the file is missing.
                            if (child is Button &&
                                (child.text == getString(R.string.clip_rename) ||
                                    child.text == getString(R.string.clip_delete))
                            ) {
                                child.isEnabled = canEdit
                            }
                        }
                    }
                }
            }

            updateClipViews(clipContainer)
        }
    }

    private fun effectiveClipGain(clipVolume: Int): Float {
        val master = recordingVolume.progress / 100f
        val clip = clipVolume.coerceIn(0, 100) / 100f
        return master * clip
    }

    private fun applyClipVolumePreview(clipId: String, clipVolume: Int) {
        val project = projectStore.getById(projectId) ?: return
        val clip = project.clips.firstOrNull { it.id == clipId } ?: return

        val gain = if (clip.isMuted) {
            0f
        } else {
            effectiveClipGain(clipVolume)
        }

        if (clipId == selectedClipId) {
            recordingPlayer?.setVolume(gain, gain)
        } else {
            extraClipPlayers.firstOrNull { it.clipId == clipId }?.player?.setVolume(gain, gain)
        }
    }

    private fun applyAllRecordingVolumes() {
        val project = projectStore.getById(projectId) ?: return
        project.clips.forEach { clip -> applyClipVolumePreview(clip.id, clip.volume) }
    }

    private fun saveNewRecording(file: File): Boolean {
        val nextNumber = (projectStore.getById(projectId)?.clips?.size ?: 0) + 1
        val clip = RecordingClip(
            id = UUID.randomUUID().toString(),
            filePath = file.absolutePath,
            offsetMs = 0,
            isMuted = false,
            name = "Recording $nextNumber",
            volume = 100
        )

        return try {
            projectStore.update(projectId, "New recording clip added") { project ->
                project.copy(
                    clips = project.clips + clip,
                    activeClipId = clip.id,
                    recordingPath = clip.filePath,
                    recordingOffsetMs = 0
                )
            }

            recordingFile = file
            recordingOffsetMs = 0
            selectedClipId = clip.id
            selectedClipMuted = false
            offsetSeekBar.progress = 0

            updateTrackLabels()
            displayRecordingClips()
            refreshVisualTimeline()

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
            val available = File(clip.filePath).exists()
            val selected = clip.id == project.activeClipId

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 16, 8, 24)
            }

            // Clip name and timeline information.
            val title = TextView(this).apply {
                text = buildString {
                    append(clip.name)

                    if (selected) {
                        append(" (Selected)")
                    }

                    if (!available) {
                        append(" (Missing file)")
                    }
                }

                textSize = 18f
                setTextColor(getColor(R.color.black))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            container.addView(title)

            val details = TextView(this).apply {
                text = "Clip ${index + 1} • Start: ${clip.offsetMs / 1000.0}s"
                textSize = 13f
                setTextColor(getColor(R.color.gray))
                setPadding(0, 8, 0, 12)
            }

            container.addView(details)

            // Select the clip for timeline editing.
            val selectButton = Button(this).apply {
                text = if (selected) {
                    "Selected Clip"
                } else {
                    "Select Clip"
                }

                isAllCaps = false
                tag = clip.filePath
                isEnabled = available && !audioRecorder.isRecording && !exporting

                setOnClickListener { selectRecordingClip(clip) }
            }

            container.addView(selectButton)

            // Individual clip volume.
            val volumeLabel = TextView(this).apply {
                text = getString(R.string.clip_volume_percent, clip.volume)
                setTextColor(getColor(R.color.black))
                setPadding(0, 16, 0, 4)
            }

            container.addView(volumeLabel)

            val volumeSlider = SeekBar(this).apply {
                max = 100
                progress = clip.volume
                tag = clip.filePath
                isEnabled = available && !audioRecorder.isRecording && !exporting
                progressTintList = ColorStateList.valueOf(getColor(R.color.black))
                thumbTintList = ColorStateList.valueOf(getColor(R.color.black))
            }

            volumeSlider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return

                        volumeLabel.text = getString(R.string.clip_volume_percent, progress)
                        applyClipVolumePreview(clip.id, progress)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        saveClipVolume(clip.id, seekBar.progress)
                    }
                }
            )

            container.addView(volumeSlider)

            // Mute / Unmute.
            val muteButton = Button(this).apply {
                text = if (clip.isMuted) {
                    "Unmute"
                } else {
                    "Mute"
                }

                isAllCaps = false
                tag = clip.filePath
                isEnabled = available && !audioRecorder.isRecording && !exporting

                setOnClickListener { toggleClipMute(clip.id) }
            }

            container.addView(muteButton)

            // Rename.
            val renameButton = Button(this).apply {
                setText(R.string.clip_rename)
                isAllCaps = false
                tag = clip.filePath
                isEnabled = !audioRecorder.isRecording && !exporting

                setOnClickListener { renameRecordingClip(clip) }
            }

            container.addView(renameButton)

            // Delete.
            val deleteButton = Button(this).apply {
                setText(R.string.clip_delete)
                isAllCaps = false
                tag = clip.filePath
                isEnabled = !audioRecorder.isRecording && !exporting

                setOnClickListener { confirmDeleteRecording(clip) }
            }

            container.addView(deleteButton)

            // Separate clips visually.
            val divider = View(this).apply {
                setBackgroundColor(getColor(R.color.light_gray))
            }

            container.addView(
                divider,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    topMargin = 20
                }
            )

            clipContainer.addView(container)
        }
    }

    private fun selectRecordingClip(clip: RecordingClip) {
        if (audioRecorder.isRecording || exporting) return
        if (!File(clip.filePath).exists()) return

        try {
            projectStore.update(projectId, "Recording clip selected") {
                it.copy(
                    activeClipId = clip.id,
                    recordingPath = clip.filePath,
                    recordingOffsetMs = clip.offsetMs
                )
            }

            releasePlayers()

            recordingFile = File(clip.filePath)
            recordingOffsetMs = clip.offsetMs.coerceIn(0, 30000)
            selectedClipId = clip.id
            selectedClipMuted = clip.isMuted
            offsetSeekBar.progress = recordingOffsetMs / 100

            updateTrackLabels()
            displayRecordingClips()
            preparePlayers()
            refreshVisualTimeline()
        } catch (_: Exception) {
            playbackStatus.setText(R.string.clip_save_error)
        }
    }

    private fun volumeListener(onVolumeChanged: (Float) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onVolumeChanged(progress / 100f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                try {
                    when (seekBar.id) {
                        R.id.seekOriginalVolume -> {
                            projectStore.update(projectId, "Original volume changed") {
                                it.copy(originalVolume = seekBar.progress)
                            }
                        }
                        R.id.seekRecordingVolume -> {
                            projectStore.update(projectId, "Recording volume changed") {
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == EXPORT_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { destination -> exportMix(destination) }
            }
            return
        }

        if (requestCode != AUDIO_REQUEST_CODE || resultCode != RESULT_OK) return

        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The temporary document grant can
            // still support current-session playback.
        }

        try {
            projectStore.update(projectId, "Original audio imported") {
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
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
        val project = projectStore.getById(projectId)

        if (project == null) {
            playbackStatus.text = "Project not found."
            return
        }

        val recordingGain = recordingVolume.progress / 100f
        val originalGain = originalVolume.progress / 100f

        val includedClips = project.clips.filter { !it.isMuted }
        val missingClips = includedClips.filter { !File(it.filePath).exists() }

        if (missingClips.isNotEmpty()) {
            playbackStatus.text = "Export failed: a recording file is missing."
            return
        }

        val exportClips = includedClips.map { clip ->
            val clipGain = clip.volume.coerceIn(0, 100) / 100f
            val effectiveGain = recordingGain * clipGain

            WavMixExporter.MixClip(
                uri = Uri.fromFile(File(clip.filePath)),
                offsetMs = clip.offsetMs,
                volume = effectiveGain
            )
        }

        if (sourceOriginal == null && exportClips.isEmpty()) {
            playbackStatus.text = "No unmuted audio available to export."
            return
        }

        exporting = true
        playbackStatus.setText(R.string.status_exporting)

        // Exporting should not compete with preview playback.
        releasePlayers()
        updateControls()

        Thread {
            try {
                val stream = requireNotNull(
                    contentResolver.openOutputStream(destinationUri, "w")
                ) { "Unable to open export destination." }

                stream.use {
                    val exporter = WavMixExporter(applicationContext)
                    exporter.export(
                        originalUri = sourceOriginal,
                        originalVolume = originalGain,
                        clips = exportClips,
                        destination = it
                    )
                }

                projectStore.update(projectId, "Mix exported as WAV") { it }

                runOnUiThread {
                    if (!isDestroyed) {
                        exporting = false
                        preparePlayers()
                        playbackStatus.setText(R.string.status_export_success)
                        updateControls()
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("RemixHub", "Audio export failed", error)

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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE)
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

        val project = projectStore.getById(projectId)
        val extraClips = project?.clips
            ?.filter { clip ->
                clip.id != selectedClipId && File(clip.filePath).exists()
            }
            .orEmpty()

        if (originalUri == null && recordingFile == null && extraClips.isEmpty()) {
            playbackStatus.setText(R.string.status_empty)
            refreshVisualTimeline()
            return
        }

        playbackStatus.setText(R.string.status_loading)

        originalUri?.let { uri ->
            originalPlayer = createPlayer(
                configure = { player -> player.setDataSource(this, uri) },
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
                    configure = { player -> player.setDataSource(file.absolutePath) },
                    onReady = {
                        recordingReady = true
                        updateReadyState()
                    },
                    onFinished = { selectedClipId?.let(finishedClipIds::add) }
                )
            }
        }

        extraClips.forEach { clip ->
            val audioFile = File(clip.filePath)
            val player = createPlayer(
                configure = { mediaPlayer ->
                    mediaPlayer.setDataSource(audioFile.absolutePath)
                },
                onReady = {
                    val entry = extraClipPlayers.firstOrNull { it.clipId == clip.id }
                    if (entry != null) {
                        entry.ready = true
                        updateReadyState()
                    }
                },
                onFinished = { finishedClipIds.add(clip.id) }
            )

            if (player != null) {
                extraClipPlayers.add(
                    ExtraClipPlayer(
                        clipId = clip.id,
                        offsetMs = clip.offsetMs.coerceAtLeast(0),
                        muted = clip.isMuted,
                        player = player
                    )
                )
            }
        }

        updateControls()
        exportButton.isEnabled =
            !audioRecorder.isRecording &&
            !exporting &&
            (originalUri != null || recordingFile != null || extraClipPlayers.isNotEmpty())
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

            player.setOnCompletionListener { onFinished() }

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
            (recordingFile == null || recordingReady) &&
            extraClipPlayers.all { it.ready }

        refreshTimelineDuration()
        refreshVisualTimeline()

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

    private fun renameRecordingClip(clip: RecordingClip) {
        if (audioRecorder.isRecording || exporting) return

        val input = EditText(this).apply {
            setText(clip.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setPadding(32, 24, 32, 24)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.clip_rename)
            .setView(input)
            .setNegativeButton(R.string.clip_cancel, null)
            .setPositiveButton(R.string.clip_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim().take(40)

                if (newName.isBlank()) {
                    input.error = getString(R.string.clip_name_empty)
                    return@setOnClickListener
                }

                try {
                    projectStore.update(projectId, "Recording renamed to $newName") { project ->
                        project.copy(
                            clips = project.clips.map {
                                if (it.id == clip.id) {
                                    it.copy(name = newName)
                                } else {
                                    it
                                }
                            }
                        )
                    }

                    displayRecordingClips()
                    refreshVisualTimeline()
                    playbackStatus.setText(R.string.clip_name_changed)

                    dialog.dismiss()
                } catch (_: Exception) {
                    playbackStatus.setText(R.string.clip_edit_error)
                }
            }
        }

        dialog.show()
    }

    private fun saveClipVolume(clipId: String, volume: Int) {
        try {
            projectStore.update(projectId, "Recording volume changed") { project ->
                project.copy(
                    clips = project.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(volume = volume.coerceIn(0, 100))
                        } else {
                            clip
                        }
                    }
                )
            }

            applyClipVolumePreview(clipId, volume)
            refreshVisualTimeline()
        } catch (_: Exception) {
            playbackStatus.setText(R.string.clip_edit_error)
        }
    }

    private fun startPlayback() {
        if (audioRecorder.isRecording || exporting) return

        val ready =
            (originalUri == null || originalReady) &&
            (recordingFile == null || recordingReady) &&
            extraClipPlayers.all { it.ready }

        if (!ready || timelineDurationMs <= 0) return

        timelineHandler.removeCallbacks(timelineTicker)

        if (playheadMs >= timelineDurationMs) {
            playheadMs = 0
        }
        finishedClipIds.clear()

        try {
            val originalGain = originalVolume.progress / 100f

            originalPlayer?.setVolume(originalGain, originalGain)
            applyAllRecordingVolumes()

            // Prepare the original player at the
            // selected global timeline position.
            originalPlayer?.takeIf { originalReady }?.let {
                if (playheadMs < it.duration) {
                    it.seekTo(playheadMs.toLong(), MediaPlayer.SEEK_CLOSEST)
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

    private fun toggleClipMute(clipId: String) {
        if (audioRecorder.isRecording || exporting) return

        try {
            projectStore.update(projectId, "Recording mute setting changed") { project ->
                project.copy(
                    clips = project.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(isMuted = !clip.isMuted)
                        } else {
                            clip
                        }
                    }
                )
            }

            val project = projectStore.getById(projectId)

            selectedClipMuted =
                project?.clips?.firstOrNull { it.id == selectedClipId }?.isMuted ?: false

            // Rebuild the players with the new mute state.
            preparePlayers()
            displayRecordingClips()
            refreshVisualTimeline()
        } catch (_: Exception) {
            playbackStatus.text = "Unable to update recording mute setting."
        }
    }

    private fun startRecordingLayerIfDue() {
        fun startLayer(player: MediaPlayer, offsetMs: Int, clipId: String, muted: Boolean) {
            if (muted || clipId in finishedClipIds) {
                return
            }

            val position = playheadMs - offsetMs

            if (position < 0 || position >= player.duration) {
                return
            }

            try {
                if (!player.isPlaying) {
                    player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)

                    val clip = projectStore.getById(projectId)?.clips?.firstOrNull { it.id == clipId }
                    val volume = effectiveClipGain(clip?.volume ?: 100)
                    player.setVolume(volume, volume)
                    player.start()
                }
            } catch (_: IllegalStateException) {
                playbackStatus.setText(R.string.status_error)
            }
        }

        // Selected recording.
        if (recordingReady) {
            val player = recordingPlayer
            val id = selectedClipId

            if (player != null && id != null) {
                startLayer(
                    player = player,
                    offsetMs = recordingOffsetMs,
                    clipId = id,
                    muted = selectedClipMuted
                )
            }
        }

        // All other recordings.
        extraClipPlayers.forEach { entry ->
            if (entry.ready) {
                startLayer(
                    player = entry.player,
                    offsetMs = entry.offsetMs,
                    clipId = entry.clipId,
                    muted = entry.muted
                )
            }
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

            extraClipPlayers.forEach { entry ->
                if (entry.player.isPlaying) {
                    entry.player.pause()
                }
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

            extraClipPlayers.forEach { entry ->
                if (entry.player.isPlaying) {
                    entry.player.pause()
                }
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

        extraClipPlayers.forEach { entry -> entry.player.release() }
        extraClipPlayers.clear()
        finishedClipIds.clear()

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
        trackName.text = originalUri?.let { getAudioFileName(it) } ?: getString(R.string.no_track)

        recordingName.text = if (recordingFile != null) {
            getString(R.string.recording_name)
        } else {
            getString(R.string.no_recording)
        }
    }

    private fun updateControls() {
        if (!::playPauseButton.isInitialized) return

        val recording = audioRecorder.isRecording
        val hasAudio =
            originalPlayer != null || recordingPlayer != null || extraClipPlayers.isNotEmpty()
        val ready =
            (originalUri == null || originalReady) &&
            (recordingFile == null || recordingReady) &&
            extraClipPlayers.all { it.ready }

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
            offsetSeekBar.isEnabled = !audioRecorder.isRecording && !exporting && recordingReady

            timelineSeekBar.isEnabled =
                !audioRecorder.isRecording && !exporting && timelineDurationMs > 0
        }

        if (::visualTimeline.isInitialized) {
            visualTimeline.isEnabled = !audioRecorder.isRecording && !exporting
        }
    }

    private fun refreshTimelineDuration() {
        val originalDuration = if (originalReady) {
            originalPlayer?.duration ?: 0
        } else {
            0
        }

        val selectedDuration = if (recordingReady) {
            recordingOffsetMs + (recordingPlayer?.duration ?: 0)
        } else {
            0
        }

        val extraDuration = extraClipPlayers
            .filter { it.ready }
            .maxOfOrNull { entry -> entry.offsetMs + entry.player.duration }
            ?: 0

        timelineDurationMs =
            maxOf(originalDuration, selectedDuration, extraDuration).coerceAtLeast(0)

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

        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun confirmDeleteRecording(clip: RecordingClip) {
        if (audioRecorder.isRecording || exporting) return

        AlertDialog.Builder(this)
            .setTitle(R.string.clip_delete_title)
            .setMessage(getString(R.string.clip_delete_message) + "\n\n" + clip.name)
            .setNegativeButton(R.string.clip_cancel, null)
            .setPositiveButton(R.string.clip_delete) { _, _ -> deleteRecordingClip(clip.id) }
            .show()
    }

    private fun deleteRecordingClip(clipId: String) {
        if (audioRecorder.isRecording || exporting) return

        val project = projectStore.getById(projectId) ?: return
        val clip = project.clips.firstOrNull { it.id == clipId } ?: return
        val remaining = project.clips.filterNot { it.id == clipId }

        val nextSelected =
            remaining.firstOrNull { it.id == project.activeClipId } ?: remaining.firstOrNull()

        try {
            projectStore.update(projectId, "Recording deleted: ${clip.name}") {
                it.copy(
                    clips = remaining,
                    activeClipId = nextSelected?.id,
                    recordingPath = nextSelected?.filePath,
                    recordingOffsetMs = nextSelected?.offsetMs ?: 0
                )
            }
        } catch (_: Exception) {
            playbackStatus.setText(R.string.clip_edit_error)
            return
        }

        // Stop using the old audio file before deletion.
        releasePlayers()

        selectedClipId = nextSelected?.id
        selectedClipMuted = nextSelected?.isMuted ?: false

        recordingFile = nextSelected?.filePath?.let(::File)?.takeIf { it.exists() }
        recordingOffsetMs = nextSelected?.offsetMs?.coerceIn(0, 30000) ?: 0

        offsetSeekBar.progress = recordingOffsetMs / 100
        playheadMs = 0

        // Delete only a file owned by RemixHub that
        // is no longer referenced by any saved project.
        try {
            val file = File(clip.filePath).canonicalFile
            val recordingDirectory = File(filesDir, "recordings").canonicalFile

            val stillReferenced = projectStore.getAll().any { saved ->
                saved.clips.any { File(it.filePath).canonicalFile == file } ||
                    (saved.recordingPath != null &&
                        File(saved.recordingPath).canonicalFile == file)
            }

            if (file.parentFile == recordingDirectory && !stillReferenced && file.isFile) {
                if (!file.delete()) {
                    android.util.Log.w("RemixHub", "Recording file could not be deleted")
                }
            }
        } catch (error: Exception) {
            // Metadata is already saved. If cleanup
            // fails, retain the file rather than risk
            // deleting an unrelated file.
            android.util.Log.w("RemixHub", "Recording cleanup skipped", error)
        }

        updateTrackLabels()
        displayRecordingClips()
        preparePlayers()

        playbackStatus.setText(R.string.clip_deleted)
        refreshVisualTimeline()
    }

    private fun updateTimelineDisplay() {
        if (!::timelineSeekBar.isInitialized) return

        timelineSeekBar.progress = playheadMs / 100

        timelineText.text = getString(
            R.string.timeline_position_format,
            formatTime(playheadMs),
            formatTime(timelineDurationMs)
        )

        offsetText.text = getString(R.string.timeline_offset_format, recordingOffsetMs / 1000.0)

        if (::visualTimeline.isInitialized) {
            visualTimeline.setPlayhead(playheadMs)
        }
    }

    private fun seekPreviewTo(positionMs: Int) {
        if (audioRecorder.isRecording || exporting) return

        timelineHandler.removeCallbacks(timelineTicker)

        if (isPlaying) {
            pausePlayback()
        }

        playheadMs = positionMs.coerceIn(0, timelineDurationMs)
        isPaused = true
        finishedClipIds.clear()

        try {
            originalPlayer?.takeIf { originalReady }?.let {
                it.seekTo(playheadMs.coerceAtMost(it.duration).toLong(), MediaPlayer.SEEK_CLOSEST)
            }

            recordingPlayer?.takeIf { recordingReady }?.let {
                val positionInRecording = (playheadMs - recordingOffsetMs).coerceIn(0, it.duration)
                it.seekTo(positionInRecording.toLong(), MediaPlayer.SEEK_CLOSEST)
            }

            extraClipPlayers.forEach { entry ->
                if (entry.ready) {
                    val relativePosition =
                        (playheadMs - entry.offsetMs).coerceIn(0, entry.player.duration)

                    entry.player.seekTo(relativePosition.toLong(), MediaPlayer.SEEK_CLOSEST)
                }
            }
        } catch (_: IllegalStateException) {
            playbackStatus.setText(R.string.status_error)
        }

        updateTimelineDisplay()
    }

    private fun moveClipOnTimeline(
        clipId: String,
        newOffsetMs: Int
    ) {
        if (audioRecorder.isRecording || exporting) return

        val offset = newOffsetMs.coerceIn(0, 30000)

        try {
            projectStore.update(
                projectId,
                "Clip moved to ${offset / 1000.0}s"
            ) { project ->
                project.copy(
                    clips = project.clips.map { clip ->
                        if (clip.id == clipId) {
                            clip.copy(offsetMs = offset)
                        } else {
                            clip
                        }
                    },
                    recordingOffsetMs = if (project.activeClipId == clipId) {
                        offset
                    } else {
                        project.recordingOffsetMs
                    }
                )
            }

            if (selectedClipId == clipId) {
                recordingOffsetMs = offset
                offsetSeekBar.progress = offset / 100
            }

            // Rebuild playback to use the updated offsets.
            preparePlayers()
            displayRecordingClips()
            refreshVisualTimeline()
        } catch (_: Exception) {
            playbackStatus.text = "Unable to save clip position."
            refreshVisualTimeline()
        }
    }

    private fun refreshVisualTimeline() {
        if (!::visualTimeline.isInitialized) return

        val project = projectStore.getById(projectId) ?: return

        val originalDuration = if (originalReady) {
            originalPlayer?.duration ?: 0
        } else {
            0
        }

        val bars = project.clips.map { clip ->
            val duration = when (clip.id) {
                selectedClipId -> {
                    if (recordingReady) {
                        recordingPlayer?.duration ?: 0
                    } else {
                        0
                    }
                }

                else -> {
                    extraClipPlayers.firstOrNull {
                        it.clipId == clip.id && it.ready
                    }?.player?.duration ?: 0
                }
            }

            TimelineView.ClipBar(
                id = clip.id,
                name = clip.name,
                offsetMs = clip.offsetMs,
                durationMs = duration,
                muted = clip.isMuted
            )
        }

        visualTimeline.setTimeline(
            originalDurationMs = originalDuration,
            clips = bars,
            selectedId = project.activeClipId,
            durationMs = timelineDurationMs
        )

        visualTimeline.setPlayhead(playheadMs)
    }

    // ACTIVITY LIFECYCLE

    override fun onResume() {
        super.onResume()

        if (!audioRecorder.isRecording && originalPlayer == null && recordingPlayer == null) {
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
                    projectStore.update(projectId, "Microphone recording saved") {
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
        originalUri?.let { outState.putString(STATE_AUDIO_URI, it.toString()) }

        recordingFile?.let { outState.putString(STATE_RECORDING_PATH, it.absolutePath) }

        super.onSaveInstanceState(outState)
    }
}