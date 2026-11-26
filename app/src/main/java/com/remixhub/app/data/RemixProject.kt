package com.remixhub.app.data

data class RemixProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val originalUri: String? = null,
    val recordingPath: String? = null,
    val originalVolume: Int = 100,
    val recordingVolume: Int = 100,
    val recordingOffsetMs: Int = 0,
    val history: List<String> = emptyList()
)