package com.remixhub.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProjectStore(context: Context) {

    private val preferences = context.getSharedPreferences(
        "remixhub_projects",
        Context.MODE_PRIVATE
    )

    private fun readProjects(): MutableList<RemixProject> {
        val json = preferences.getString("projects", "[]") ?: "[]"
        val array = JSONArray(json)

        return MutableList(array.length()) { index ->
            val item = array.getJSONObject(index)
            val historyArray = item.optJSONArray("history")
                ?: JSONArray()

val clipsArray = item.optJSONArray("clips")

val clips = if (clipsArray != null) {
    List(clipsArray.length()) { clipIndex ->
        val clip = clipsArray.getJSONObject(clipIndex)

        RecordingClip(
            id = clip.getString("id"),
            filePath = clip.getString("filePath"),
            offsetMs = clip.optInt("offsetMs", 0)
                .coerceAtLeast(0)
        )
    }
} else {
    // Convert older single-recording projects
    // into our new in-memory clip representation.
    val oldPath = item.optString("recordingPath", "")

    if (oldPath.isNotBlank() && oldPath != "null") {
        listOf(
            RecordingClip(
                id = "legacy",
                filePath = oldPath,
                offsetMs = item.optInt(
                    "recordingOffsetMs", 0
                )
            )
        )
    } else {
        emptyList()
    }
}
``

            RemixProject(
                id = item.getString("id"),
                name = item.getString("name"),
                createdAt = item.getLong("createdAt"),
                updatedAt = item.getLong("updatedAt"),
                originalUri = item.optString("originalUri")
                    .takeIf { it.isNotEmpty() },
                recordingPath = item.optString("recordingPath")
                    .takeIf { it.isNotEmpty() },
                originalVolume = item.optInt("originalVolume", 100),
                recordingVolume = item.optInt("recordingVolume", 100),
                history = List(historyArray.length()) {
                    historyArray.getString(it)
                },
                recordingOffsetMs = item.optInt(
                                "recordingOffsetMs", 0
                        ),
                        clips = clips,
activeClipId = item.optString(
    "activeClipId", ""
).takeIf { it.isNotBlank() && it != "null" }
    ?: clips.firstOrNull()?.id,
            )
        }
    }

    private fun writeProjects(projects: List<RemixProject>) {
        val array = JSONArray()

        projects.forEach { project ->

val clipsArray = JSONArray()

project.clips.forEach { clip ->
    clipsArray.put(
        JSONObject().apply {
            put("id", clip.id)
            put("filePath", clip.filePath)
            put("offsetMs", clip.offsetMs)
        }
    )
}

            val historyArray = JSONArray()
            project.history.forEach { historyArray.put(it) }

            val item = JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("createdAt", project.createdAt)
                put("updatedAt", project.updatedAt)
                put("originalUri", project.originalUri)
                put("recordingPath", project.recordingPath)
                put("originalVolume", project.originalVolume)
                put("recordingVolume", project.recordingVolume)
                put("history", historyArray)
                put("recordingOffsetMs", project.recordingOffsetMs)
                put("clips", clipsArray)
                put("activeClipId", project.activeClipId)
            }

            array.put(item)
        }

        check(
            preferences.edit()
                .putString("projects", array.toString())
                .commit()
        ) {
            "Unable to save projects"
        }
    }

    fun getAll(): List<RemixProject> =
        readProjects().sortedByDescending { it.updatedAt }

    fun getById(id: String): RemixProject? =
        readProjects().find { it.id == id }

    fun create(): RemixProject {
        val projects = readProjects()
        val now = System.currentTimeMillis()

        val project = RemixProject(
            id = UUID.randomUUID().toString(),
            name = "Untitled Remix ${projects.size + 1}",
            createdAt = now,
            updatedAt = now,
            history = listOf("Project created")
        )

        projects.add(project)
        writeProjects(projects)

        return project
    }

    fun update(
        id: String,
        event: String? = null,
        change: (RemixProject) -> RemixProject
    ) {
        val projects = readProjects()
        val index = projects.indexOfFirst { it.id == id }

        if (index < 0) return

        val current = projects[index]
        val updated = change(current)

        projects[index] = updated.copy(
            id = current.id,
            createdAt = current.createdAt,
            updatedAt = System.currentTimeMillis(),
            history = if (event != null) {
                current.history + event
            } else {
                current.history
            }
        )

        writeProjects(projects)
    }
}