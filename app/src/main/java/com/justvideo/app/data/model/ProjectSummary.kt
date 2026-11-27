package com.justvideo.app.data.model

// =====================================================================
// ProjectSummary.kt
// The small amount of info the Home screen shows per project.
// =====================================================================

data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMillis: Long,
    val durationMs: Long,
    val clipCount: Int,
)
