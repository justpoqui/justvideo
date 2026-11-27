package com.justvideo.app.data.repo

import com.justvideo.app.data.db.ProjectDao
import com.justvideo.app.data.db.ProjectEntity
import com.justvideo.app.data.model.ProjectSummary
import com.justvideo.app.data.model.Timeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// =====================================================================
// ProjectRepository.kt
// The single place the rest of the app goes to for projects.
// The screens never talk to the database directly.
// =====================================================================

/** A loaded project: what the editor works on. */
data class Project(val id: String, val name: String, val timeline: Timeline)

class ProjectRepository(
    private val dao: ProjectDao,
    private val media: MediaRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // STEP 1: Live project list for the Home screen.
    fun observeProjects(): Flow<List<ProjectSummary>> =
        dao.observeAll().map { rows ->
            rows.map { ProjectSummary(it.id, it.name, it.updatedAtMillis, it.durationMs, it.clipCount) }
        }

    // STEP 2: Create an empty project named after the current date/time.
    suspend fun createProject(): String {
        val now = clock()
        val id = UUID.randomUUID().toString()
        val name = "Project " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(now))
        val empty = Timeline()
        dao.insert(
            ProjectEntity(
                id = id,
                name = name,
                createdAtMillis = now,
                updatedAtMillis = now,
                timelineJson = TimelineJson.encode(empty),
                durationMs = 0,
                clipCount = 0,
            )
        )
        return id
    }

    // STEP 3: Load one project (null if it was deleted).
    suspend fun load(id: String): Project? {
        val row = dao.getById(id) ?: return null
        return Project(row.id, row.name, TimelineJson.decode(row.timelineJson))
    }

    // STEP 4: Save the timeline after every edit.
    suspend fun saveTimeline(id: String, timeline: Timeline) {
        val row = dao.getById(id) ?: return
        dao.upsert(
            row.copy(
                timelineJson = TimelineJson.encode(timeline),
                updatedAtMillis = clock(),
                durationMs = timeline.durationMs,
                clipCount = timeline.videoClips.size + timeline.audioClips.size,
            )
        )
    }

    suspend fun rename(id: String, newName: String) {
        val clean = newName.trim().take(60)
        if (clean.isNotEmpty()) dao.rename(id, clean, clock())
    }

    // STEP 5: Delete a project and give back file access it no longer needs.
    // (Android limits how many files an app can keep access to, so we tidy up.)
    suspend fun delete(id: String) {
        val doomed = dao.getById(id)?.let { TimelineJson.decode(it.timelineJson) }
        dao.delete(id)
        if (doomed != null) {
            val stillUsed = dao.allTimelineJson()
                .flatMap { json -> TimelineJson.decode(json).let { it.videoClips + it.audioClips } }
                .map { it.sourceUri }
                .toSet()
            (doomed.videoClips + doomed.audioClips)
                .map { it.sourceUri }
                .filterNot { it in stillUsed }
                .distinct()
                .forEach { media.releaseAccess(it) }
        }
    }
}
