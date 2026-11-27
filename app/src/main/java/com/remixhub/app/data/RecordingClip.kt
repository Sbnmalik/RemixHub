package com.remixhub.app.data

data class RecordingClip(
    val id: String,
    val filePath: String,
    val offsetMs: Int = 0,
    val isMuted: Boolean = false
)