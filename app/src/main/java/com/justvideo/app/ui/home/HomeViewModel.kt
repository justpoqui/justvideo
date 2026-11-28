package com.justvideo.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justvideo.app.data.model.ProjectSummary
import com.justvideo.app.data.repo.ProjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// =====================================================================
// HomeViewModel.kt
// Holds the Home screen's data. It survives screen rotation, and it does
// the database work off the main thread.
// =====================================================================

/** What the Home screen shows. `projects == null` means still loading. */
data class HomeUiState(val projects: List<ProjectSummary>? = null)

class HomeViewModel(private val projects: ProjectRepository) : ViewModel() {

    // STEP 1: Live list from the database, turned into screen state.
    // WhileSubscribed(5_000) keeps the query running for 5 s after the
    // screen goes away, so a rotation doesn't restart it.
    val state: StateFlow<HomeUiState> = projects.observeProjects()
        .map { HomeUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // STEP 2: Create a project, then hand its id back so the screen can
    // open it. viewModelScope always calls back on the main thread, which
    // is where navigation must happen.
    fun createProject(onCreated: (String) -> Unit, onFailed: (Exception) -> Unit) {
        viewModelScope.launch {
            try {
                onCreated(projects.createProject())
            } catch (e: Exception) {
                onFailed(e)
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { projects.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { projects.delete(id) }
    }
}
