package com.justvideo.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justvideo.app.JustVideoApp
import com.justvideo.app.core.AppLog
import com.justvideo.app.ui.common.ReportDialog
import kotlinx.coroutines.launch

// =====================================================================
// HomeScreen.kt
// First screen: Auto Highlights, New Project, and your saved projects.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProject: (String) -> Unit) {
    // STEP 1: Get the ViewModel, built with the shared repositories.
    val container = (LocalContext.current.applicationContext as JustVideoApp).container
    val vm: HomeViewModel = viewModel { HomeViewModel(container.projectRepository) }
    val state by vm.state.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // STEP 2: Which popup is open (saved across rotation).
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JustVideo") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    // STEP 3: ⋮ menu → Diagnostics (the app log, for sending to me).
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Diagnostics") },
                                leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                                onClick = { menuOpen = false; showDiagnostics = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // STEP 4: The headline feature button.
            item {
                AutoHighlightsCard(onClick = {
                    scope.launch { snackbar.showSnackbar("Auto Highlights arrives in Phase 2") }
                })
            }

            // STEP 5: New project → create it in the database, then open the editor.
            item {
                Button(
                    onClick = {
                        if (creating) return@Button
                        creating = true
                        scope.launch {
                            try {
                                onOpenProject(vm.createProject())
                            } catch (e: Exception) {
                                AppLog.e("Home", "createProject failed", e)
                                snackbar.showSnackbar("Couldn't create project: ${e.message}")
                            } finally {
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text("  New project", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                Text(
                    "Projects",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // STEP 6: The project list (or a loading spinner / empty message).
            val projects = state.projects
            when {
                projects == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                projects.isEmpty() -> item {
                    Text(
                        "No projects yet. Tap New project to start.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> items(projects, key = { it.id }) { p ->
                    ProjectCard(
                        project = p,
                        onOpen = { onOpenProject(p.id) },
                        onRename = { renameId = p.id },
                        onDelete = { deleteId = p.id },
                    )
                }
            }
        }
    }

    // STEP 7: Popups.
    val list = state.projects.orEmpty()
    list.firstOrNull { it.id == renameId }?.let { p ->
        RenameProjectDialog(
            currentName = p.name,
            onConfirm = { vm.rename(p.id, it); renameId = null },
            onDismiss = { renameId = null },
        )
    }
    list.firstOrNull { it.id == deleteId }?.let { p ->
        DeleteProjectDialog(
            name = p.name,
            onConfirm = { vm.delete(p.id); deleteId = null },
            onDismiss = { deleteId = null },
        )
    }
    if (showDiagnostics) {
        ReportDialog(
            title = "Diagnostics",
            intro = "Recent app log. If something misbehaves, tap Copy and paste it to me.",
            report = AppLog.snapshot().ifBlank { "(log is empty)" },
            onDismiss = { showDiagnostics = false },
        )
    }
}
