package com.sx4scanpro

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** Private app storage, so no storage permission is needed. Kept here so the store itself stays Android-free and unit-testable. */
private fun duaStoreFor(context: Context) = DuaAudioStore(File(context.filesDir, "dua"))

private const val DECODE_TIMEOUT_US = 10_000L
private const val MAX_DECODE_STEPS = 200

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

/**
 * Accepts a candidate only when all three checks pass:
 * 1. the framework reports a positive duration,
 * 2. MediaPlayer prepares the file and reports a positive duration,
 * 3. MediaCodec decodes at least one non-empty audio buffer.
 * This runs on the import thread against the temporary copy, never against the saved file.
 */
private fun probePlayable(file: File): Boolean =
    hasDuration(file) && preparesInMediaPlayer(file) && decodesAudio(file)

private fun hasDuration(file: File): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
    } catch (_: Exception) {
        false
    } finally {
        runCatching { retriever.release() }
    }
}

private fun preparesInMediaPlayer(file: File): Boolean {
    val probe = MediaPlayer()
    return try {
        probe.setDataSource(file.absolutePath)
        probe.prepare()
        probe.duration > 0
    } catch (_: Exception) {
        false
    } finally {
        runCatching { probe.release() }
    }
}

private fun decodesAudio(file: File): Boolean {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    return try {
        extractor.setDataSource(file.absolutePath)
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val candidateMime = candidate.getString(MediaFormat.KEY_MIME)
            if (candidateMime != null && candidateMime.startsWith("audio/")) {
                extractor.selectTrack(i)
                audioFormat = candidate
                break
            }
        }
        val format = audioFormat ?: return false
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        val codec = MediaCodec.createDecoderByType(mime)
        decoder = codec
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        repeat(MAX_DECODE_STEPS) {
            if (!inputEnded) {
                val inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex) ?: return false
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            if (outIndex >= 0) {
                val decodedBytes = info.size
                val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                codec.releaseOutputBuffer(outIndex, false)
                if (decodedBytes > 0) return true
                if (endOfStream) return false
            }
        }
        false
    } catch (_: Exception) {
        false
    } finally {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor.release() }
    }
}

/**
 * Owns the saved copy, one MediaPlayer, and audio focus. It plays only after a tap.
 * minSdk is 26 (from build.gradle.kts), so AudioFocusRequest is always available.
 */
private class DuaAudio(
    private val context: Context,
    private val onState: (DuaState) -> Unit
) {
    private val store = duaStoreFor(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val music = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var state: DuaState = DuaPlayback.initial(store.exists())

    // Loss of focus pauses. Regaining focus does nothing: the owner taps again.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            pauseNow()
        }
    }

    fun toggle() {
        when (DuaPlayback.onTap(state)) {
            DuaState.PLAYING -> play()
            DuaState.PAUSED -> pauseNow()
            else -> Unit
        }
    }

    /** Copies and checks the picked file on a background thread. The result is posted back to the main thread. */
    fun importFile(uri: Uri) {
        if (!DuaPlayback.opensPicker(state)) return
        set(DuaState.IMPORTING)
        Thread {
            val result = try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) ImportResult.FAILED else store.importFrom(input, ::probePlayable)
            } catch (_: Exception) {
                ImportResult.FAILED
            }
            main.post { set(DuaPlayback.afterImport(result)) }
        }.start()
    }

    /** Stops playback, releases everything, and deletes the saved copy. Called only after the owner confirms. */
    fun removeFile() {
        if (state == DuaState.IMPORTING) return
        releasePlayer()
        abandonFocus()
        store.remove()
        set(DuaPlayback.afterRemove())
    }

    /** Re-checks the disk when the screen returns to the foreground. Never starts playback. */
    fun reconcile() {
        val hasFile = store.exists()
        if (!hasFile && state != DuaState.IMPORTING) {
            releasePlayer()
            abandonFocus()
        }
        var next = DuaPlayback.reconcile(state, hasFile)
        if (next == DuaState.PLAYING && !isPlayerActive()) {
            abandonFocus()
            next = DuaState.PAUSED
        }
        if (next != state) set(next)
    }

    /** Manual pause, leaving the foreground, or focus loss. Never resumes by itself. */
    fun pauseNow() {
        val current = state
        val next = DuaPlayback.pauseOnInterrupt(current)
        if (next == current) return
        val paused = runCatching { player?.pause() }.isSuccess
        abandonFocus()
        if (!paused) {
            releasePlayer()
            set(DuaPlayback.afterError())
            return
        }
        set(next)
    }

    /** Called when the composable leaves. Releases the player and focus, and never leaves PLAYING behind. */
    fun release() {
        releasePlayer()
        abandonFocus()
        val next = DuaPlayback.pauseOnInterrupt(state)
        if (next != state) set(next)
    }

    private fun play() {
        if (!store.exists()) {
            releasePlayer()
            abandonFocus()
            set(DuaState.NO_FILE)
            return
        }
        if (!requestFocus()) {
            set(DuaPlayback.afterPlay(PlayOutcome.FOCUS_DENIED))
            return
        }
        val p = player ?: createPlayer()
        if (p == null) {
            abandonFocus()
            set(DuaPlayback.afterPlay(PlayOutcome.PREPARE_FAILED))
            return
        }
        val started = try {
            p.start()
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            releasePlayer()
            abandonFocus()
            set(DuaPlayback.afterPlay(PlayOutcome.START_FAILED))
            return
        }
        set(DuaPlayback.afterPlay(PlayOutcome.STARTED))
    }

    /**
     * Built without MediaPlayer.create(), so audio attributes are set before the source is prepared.
     * The source is the private saved copy.
     */
    private fun createPlayer(): MediaPlayer? {
        val fresh = MediaPlayer()
        return try {
            fresh.setAudioAttributes(music)
            fresh.setDataSource(store.file.absolutePath)
            fresh.setOnCompletionListener { onCompleted() }
            fresh.setOnErrorListener { _, _, _ ->
                onFailed()
                true
            }
            fresh.prepare()
            player = fresh
            fresh
        } catch (_: Exception) {
            runCatching { fresh.release() }
            null
        }
    }

    /** Only a GRANTED result allows playback. FAILED means another app holds the audio. */
    private fun requestFocus(): Boolean {
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(music)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focus = it }
        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onCompleted() {
        releasePlayer()
        abandonFocus()
        set(DuaPlayback.afterCompletion())
    }

    private fun onFailed() {
        releasePlayer()
        abandonFocus()
        set(DuaPlayback.afterError())
    }

    private fun isPlayerActive(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun abandonFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /** Releases the player only. Callers set the matching state. */
    private fun releasePlayer() {
        val old = player
        player = null
        runCatching { old?.release() }
    }

    private fun set(next: DuaState) {
        // Never report PLAYING without a live player behind it.
        val safe = if (next == DuaState.PLAYING && player == null) DuaState.ERROR else next
        state = safe
        onState(safe)
    }
}

/**
 * The Safar Ki Dua button for the cockpit. With no saved file, a tap opens the file browser.
 * The saved copy persists until REMOVE is confirmed. Playback is manual only and pauses when
 * the screen leaves the foreground.
 */
@Composable
internal fun SxDuaButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = remember(context) { context.findLifecycleOwner() }
    var uiState by remember(context) {
        mutableStateOf(DuaPlayback.initial(duaStoreFor(context).exists()))
    }
    val audio = remember(context) { DuaAudio(context) { uiState = it } }
    var confirmRemove by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) audio.importFile(uri)
    }

    DisposableEffect(owner, audio) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> audio.pauseNow()
                Lifecycle.Event.ON_RESUME -> audio.reconcile()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            audio.release()
        }
    }

    val playing = uiState == DuaState.PLAYING
    val label = DuaPlayback.label(uiState)
    val accent = when (uiState) {
        DuaState.PLAYING -> Sx.Primary
        DuaState.PAUSED, DuaState.ERROR, DuaState.FOCUS_DENIED, DuaState.IMPORT_FAILED -> Sx.Warning
        DuaState.IMPORTING -> Sx.MutedForeground
        else -> Sx.Foreground
    }
    val icon = when (uiState) {
        DuaState.NO_FILE, DuaState.IMPORT_FAILED -> Icons.Outlined.Add
        DuaState.PLAYING -> Icons.Outlined.Pause
        DuaState.FINISHED -> Icons.Outlined.Replay
        else -> Icons.Outlined.PlayArrow
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .sxPanel(radius = 14.dp, border = if (playing) Sx.Primary.copy(alpha = 0.6f) else Sx.Border)
            .clickable(enabled = uiState != DuaState.IMPORTING) {
                if (DuaPlayback.opensPicker(uiState)) picker.launch(arrayOf("audio/*")) else audio.toggle()
            }
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "Safar Ki Dua. $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Sx.Background, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "SAFAR KI DUA", color = Sx.Foreground, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                label, color = if (playing) Sx.Primary else Sx.MutedForeground, fontSize = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (DuaPlayback.showsRemove(uiState)) {
            Text(
                "REMOVE", color = Sx.MutedForeground, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { confirmRemove = true }.padding(6.dp)
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Sx.Card,
            titleContentColor = Sx.Foreground,
            textContentColor = Sx.MutedForeground,
            title = { Text("Remove audio?") },
            text = { Text("The Safar Ki Dua audio will be deleted from the app. You can add it again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    audio.removeFile()
                }) { Text("REMOVE") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("CANCEL") }
            }
        )
    }
}
