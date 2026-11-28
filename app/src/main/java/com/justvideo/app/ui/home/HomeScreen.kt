package com.justvideo.app.ui.home

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.runtime.LaunchedEffect
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
import com.justvideo.app.BuildConfig
import com.justvideo.app.JustVideoApp
import com.justvideo.app.core.AppLog
import com.justvideo.app.core.update.UpdateState
import com.justvideo.app.ui.common.ReportDialog
import com.justvideo.app.ui.update.UpdateBanner
import kotlinx.coroutines.launch

// =====================================================================
// HomeScreen.kt
// First screen: update banner (when there is one), Auto Highlights,
// New Project, and your saved projects.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProject: (String) -> Unit) {
    // STEP 1: Get the ViewModel (built with the shared repositories) and
    // the app-wide updater.
    val context = LocalContext.current
    val container = (context.applicationContext as JustVideoApp).container
    val vm: HomeViewModel = viewModel { HomeViewModel(container.projectRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val updater = container.updateManager
    val updateState by updater.state.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // STEP 2: Which popup is open (saved across rotation).
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    // STEP 3: A manual "Check for updates" that finds nothing (or fails
    // without finding a build) is reported as a short snackbar.
    LaunchedEffect(updateState) {
        when (val s = updateState) {
            UpdateState.UpToDate -> {
                updater.dismiss(); snackbar.showSnackbar("You're on the latest version")
            }
            is UpdateState.Failed -> if (s.info == null) {
                updater.dismiss(); snackbar.showSnackbar(s.message)
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JustVideo") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    // STEP 4: ⋮ menu: check for updates, Diagnostics (the app
                    // log, for sending to me), and the installed version.
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Check for updates") },
                                leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                                onClick = { menuOpen = false; updater.checkNow() },
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostics") },
                                leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                                onClick = { menuOpen = false; showDiagnostics = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                enabled = false,
                                onClick = {},
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
            // STEP 5: Update banner. It only takes up space when there's
            // something to show.
            item {
                UpdateBanner(
                    state = updateState,
                    onUpdate = updater::startUpdate,
                    onLater = updater::dismiss,
                    onCancel = updater::cancelDownload,
                    onOpenSettings = {
                        runCatching { context.startActivity(updater.installPermissionIntent()) }
                    },
                    onOpenReleasePage = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                )
            }

            // STEP 6: The headline feature button.
            item {
                AutoHighlightsCard(onClick = {
                    scope.launch { snackbar.showSnackbar("Auto Highlights arrives in Phase 2") }
                })
            }

            // STEP 7: New project → create it in the database, then open the editor.
            item {
                Button(
                    onClick = {
                        if (creating) return@Button
                        creating = true
                        vm.createProject(
                            onCreated = { id -> creating = false; onOpenProject(id) },
                            onFailed = { e ->
                                creating = false
                                AppLog.e("Home", "createProject failed", e)
                                scope.launch { snackbar.showSnackbar("Couldn't create project: ${e.message}") }
                            },
                        )
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

            // STEP 8: The project list (or a loading spinner / empty message).
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

    // STEP 9: Popups.
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
            intro = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}). " +
                "Recent app log. If something misbehaves, tap Copy and paste it to me.",
            report = AppLog.snapshot().ifBlank { "(log is empty)" },
            onDismiss = { showDiagnostics = false },
        )
    }
}
