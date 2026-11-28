package com.justvideo.app.media.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.justvideo.app.core.AppLog
import com.justvideo.app.data.model.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// =====================================================================
// PreviewController.kt
// Wraps Media3's CompositionPlayer, which plays the whole timeline (all
// clips in order plus the audio track) like one video.
// The ViewModel owns it, so playback survives screen rotation.
// Everything here runs on the main thread, as Media3 players require.
// =====================================================================

/** What the play bar shows. */
data class PlaybackUi(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
)

@OptIn(UnstableApi::class)
class PreviewController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    // STEP 1: The player is created only once there's something to play.
    // This saves memory, and keeps empty projects (and automated tests)
    // free of video decoders.
    private var _player: CompositionPlayer? = null
    val player: Player? get() = _player

    private val _ui = MutableStateFlow(PlaybackUi())
    val ui: StateFlow<PlaybackUi> = _ui.asStateFlow()

    private var loadedTimeline: Timeline? = null
    private var ticker: Job? = null

    // STEP 2: Listen to the player and copy its state into `ui`.
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.value = _ui.value.copy(isPlaying = isPlaying)
            if (isPlaying) startTicker() else ticker?.cancel()
        }

        override fun onPlaybackStateChanged(state: Int) {
            _ui.value = _ui.value.copy(isBuffering = state == Player.STATE_BUFFERING)
            if (state == Player.STATE_ENDED) refreshPosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "Playback error ${error.errorCodeName}", error)
            onError("Preview error: ${error.errorCodeName}")
        }
    }

    // STEP 3: Give the player the current timeline. Called after every
    // edit. It rebuilds only if the timeline actually changed, and keeps
    // the playhead where it was (clamped to the new length).
    fun setTimeline(timeline: Timeline) {
        if (timeline == loadedTimeline) return
        loadedTimeline = timeline
        val composition = CompositionBuilder.build(timeline)
        if (composition == null) {
            release()
            _ui.value = PlaybackUi()
            return
        }
        val p = _player ?: CompositionPlayer.Builder(context).build().also {
            it.addListener(listener)
            _player = it
        }
        val keepAt = _ui.value.positionMs.coerceIn(0, timeline.durationMs)
        try {
            p.setComposition(composition, keepAt)
            p.prepare()
        } catch (e: Exception) {
            AppLog.e(TAG, "setComposition failed", e)
            onError("Couldn't load the preview: ${e.message}")
        }
        _ui.value = _ui.value.copy(positionMs = keepAt, durationMs = timeline.durationMs)
    }

    // STEP 4: Controls.
    fun togglePlay() {
        val p = _player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            // At the end? Start again from the beginning.
            if (p.playbackState == Player.STATE_ENDED || _ui.value.positionMs >= _ui.value.durationMs - 50) {
                p.seekTo(0)
            }
            p.play()
        }
    }

    fun pause() {
        _player?.pause()
    }

    /** Jump to a time (ms). Used by the scrub bar now, and the timeline in step 5. */
    fun seekTo(positionMs: Long) {
        val p = _player ?: return
        val clamped = positionMs.coerceIn(0, _ui.value.durationMs)
        p.seekTo(clamped)
        _ui.value = _ui.value.copy(positionMs = clamped)
    }

    // STEP 5: While playing, read the position ~30 times a second so the
    // time text (and later the timeline) moves smoothly.
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                refreshPosition()
                delay(33)
            }
        }
    }

    private fun refreshPosition() {
        val p = _player ?: return
        _ui.value = _ui.value.copy(positionMs = p.currentPosition.coerceIn(0, _ui.value.durationMs))
    }

    // STEP 6: Free the video decoders. Called when the editor closes for good.
    fun release() {
        ticker?.cancel()
        _player?.removeListener(listener)
        _player?.release()
        _player = null
        loadedTimeline = null
    }

    private companion object {
        const val TAG = "Preview"
    }
}
