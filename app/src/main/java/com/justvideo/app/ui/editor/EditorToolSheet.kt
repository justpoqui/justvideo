package com.justvideo.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justvideo.app.data.model.Transition
import com.justvideo.app.ui.editor.panels.CaptionsPanel
import com.justvideo.app.ui.editor.panels.ColorPanel
import com.justvideo.app.ui.editor.panels.CropPanel
import com.justvideo.app.ui.editor.panels.KeyframePanel
import com.justvideo.app.ui.editor.panels.OpacityPanel
import com.justvideo.app.ui.editor.panels.SpeedPanel
import com.justvideo.app.ui.editor.panels.StickerPicker
import com.justvideo.app.ui.editor.panels.TransitionPanel
import com.justvideo.app.ui.editor.panels.VolumePanel
import com.justvideo.app.ui.editor.toolbar.Tool

// =====================================================================
// EditorToolSheet.kt
// The panel that slides up when you pick a tool that has settings
// (Speed, Volume, Transition, Crop, Color, Captions, Stickers, Opacity,
// Keyframes). The preview stays visible above it and updates live.
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorToolSheet(
    tool: Tool,
    state: EditorUiState,
    vm: EditorViewModel,
    speechModelReady: Boolean,
    onPickLut: () -> Unit,
    onEditCaptionWords: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            val clip = state.selectedClip
            val onClip: (Boolean, (com.justvideo.app.data.model.Clip) -> com.justvideo.app.data.model.Clip) -> Unit =
                { live, f -> vm.updateSelectedClip(live, f) }
            // STEP 1: Pick the panel for the tool.
            when (tool) {
                Tool.SPEED -> clip?.let { SpeedPanel(it, vm::beginLiveEdit, onClip) }
                Tool.VOLUME -> clip?.let { VolumePanel(it, vm::beginLiveEdit, onClip) }
                Tool.TRANSITION -> clip?.let { c ->
                    TransitionPanel(
                        c, isFirst = state.timeline.videoClips.firstOrNull()?.id == c.id,
                        onStart = vm::beginLiveEdit, onChange = onClip,
                        onApplyAll = { tr: Transition ->
                            vm.updateTimeline { tl -> tl.copy(videoClips = tl.videoClips.mapIndexed { i, x -> if (i == 0) x else x.copy(transitionIn = tr) }) }
                        },
                    )
                }
                Tool.CROP -> clip?.let { CropPanel(it, vm::beginLiveEdit, onClip) }
                Tool.COLOR -> clip?.let { c ->
                    ColorPanel(
                        c, vm::beginLiveEdit, onClip, onPickLut,
                        onApplyAll = { color -> vm.updateTimeline { tl -> tl.copy(videoClips = tl.videoClips.map { it.copy(color = color) }) } },
                    )
                }
                Tool.CAPTIONS -> CaptionsPanel(
                    hasCaptions = state.timeline.videoClips.any { it.captions.isNotEmpty() },
                    show = state.timeline.showCaptions,
                    style = state.timeline.captionStyle,
                    modelReady = speechModelReady,
                    selectedOnly = state.selectedClip != null,
                    onGenerate = { onDismiss(); vm.generateCaptions() },
                    onShow = vm::setShowCaptions,
                    onStyle = vm::setCaptionStyle,
                    onEditWords = { onDismiss(); onEditCaptionWords() },
                )
                Tool.STICKER -> StickerPicker { emoji -> vm.addSticker(emoji); onDismiss() }
                Tool.OPACITY -> state.selectedOverlay?.let { o ->
                    OpacityPanel(o.opacity, vm::beginLiveEdit) { v -> vm.updateSelectedOverlay(live = true) { it.copy(opacity = v) } }
                }
                Tool.KEYFRAME -> {
                    // STEP 2: Keyframes of whatever is selected.
                    val keys = state.selectedText?.keyframes ?: state.selectedOverlay?.keyframes ?: clip?.keyframes.orEmpty()
                    val start = state.selectedText?.startMs ?: state.selectedOverlay?.startMs ?: clip?.let { vm.clipStart(it) } ?: 0
                    KeyframePanel(
                        keyframes = keys,
                        onAdd = vm::addKeyframeHere,
                        onRemove = { k ->
                            when {
                                state.selectedText != null -> vm.updateSelectedText { it.copy(keyframes = it.keyframes - k) }
                                state.selectedOverlay != null -> vm.updateSelectedOverlay { it.copy(keyframes = it.keyframes - k) }
                                else -> vm.updateSelectedClip { it.copy(keyframes = it.keyframes - k) }
                            }
                        },
                        onClear = {
                            when {
                                state.selectedText != null -> vm.updateSelectedText { it.copy(keyframes = emptyList()) }
                                state.selectedOverlay != null -> vm.updateSelectedOverlay { it.copy(keyframes = emptyList()) }
                                else -> vm.updateSelectedClip { it.copy(keyframes = emptyList()) }
                            }
                        },
                        onSeek = { local -> vm.seekTo(start + local) },
                    )
                }
                else -> Unit
            }
        }
    }
}

/** Used by the screen: does this tool open the sheet? */
fun Tool.hasSheet() = this in setOf(
    Tool.SPEED, Tool.VOLUME, Tool.TRANSITION, Tool.CROP, Tool.COLOR, Tool.CAPTIONS, Tool.STICKER, Tool.OPACITY, Tool.KEYFRAME,
)
