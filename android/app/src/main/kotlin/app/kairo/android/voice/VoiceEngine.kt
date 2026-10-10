package app.kairo.android.voice

import app.kairo.android.R
import app.kairo.android.ui.common.friendly
import app.kairo.core.voice.AudioSpec
import app.kairo.core.voice.EndOfSpeech
import app.kairo.core.voice.PcmBuffer
import app.kairo.core.voice.levelOf
import app.kairo.core.voice.pcmToWav
import app.kairo.core.voice.rms
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.kairo.android.data.AppPrefs
import app.kairo.android.device.DeviceActions
import app.kairo.core.model.AgentEvent
import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class VoicePhase { Off, Armed, Listening, Thinking, Speaking }

data class VoiceState(
    val phase: VoicePhase = VoicePhase.Off,
    val level: Float = 0f,
    val transcript: String? = null,
    val partialReply: String = "",
    val reply: String? = null,
    val working: String? = null,
    val actions: List<AgentEvent.ToolEnd> = emptyList(),
    val attachments: List<String> = emptyList(),
    val error: String? = null,
    val conversationId: String? = null,
    val loopActive: Boolean = false,
)

/**
 * Coordinates Kairo's voice with a single owner of the microphone:
 * - Continuous mode (foreground service): listens for "Hey Kairo" / "Oye Kairo" on the phone with Vosk.
 * - Push to talk (app, assistant, tile, widget): captures one phrase.
 * After capturing, it sends the audio to /voice/turn (Whisper + agent), runs device actions and reads
 * the reply with Kokoro. Speech recognition and the voice use the account language (`settings.language`,
 * default from the app language at sign-up); the client sends its Accept-Language with every request.
 * If Kairo asks a question, it listens again.
 */
class VoiceEngine(
    private val context: Context,
    private val api: KairoApi,
    private val prefs: AppPrefs,
    private val wakeModel: WakeModelManager,
    private val deviceActions: DeviceActions,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    /** The service uses it to notify the result when the app is in the background. */
    var onTurnFinished: ((transcript: String?, reply: String?) -> Unit)? = null

    private val tts = TtsPlayer(context)
    private var loopJob: Job? = null
    private var oneShotJob: Job? = null
    private var turnJob: Job? = null
    private val captureRequested = AtomicBoolean(false)
    private val mode = AtomicInteger(MODE_WAKE)
    private var lastTurnAt = 0L
    /** Conversation picked from the chat screen for the next voice turn. */
    @Volatile private var forcedConversation: String? = null

    fun hasMicPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ Continuous mode

    fun startLoop(phrase: String) {
        if (loopJob?.isActive == true) return
        if (!wakeModel.isReady()) {
            _state.update { it.copy(error = context.getString(R.string.voice_err_no_model)) }
            return
        }
        loopJob = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(loopActive = true, phase = VoicePhase.Armed, error = null) }
            try {
                runMic(continuous = true, phrase = phrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = context.getString(R.string.voice_err_mic_detail, e.message ?: "")) }
            } finally {
                _state.update { it.copy(loopActive = false, phase = if (it.phase == VoicePhase.Armed) VoicePhase.Off else it.phase, level = 0f) }
            }
        }
    }

    fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        _state.update { it.copy(loopActive = false, phase = VoicePhase.Off, level = 0f) }
    }

    // ------------------------------------------------------------ Push to talk

    fun talk(conversationId: String? = null) {
        forcedConversation = conversationId
        if (!hasMicPermission()) {
            _state.update { it.copy(error = context.getString(R.string.voice_err_mic_permission)) }
            return
        }
        stopSpeaking()
        turnJob?.cancel()
        if (loopJob?.isActive == true) {
            captureRequested.set(true)
            mode.set(MODE_WAKE)
            return
        }
        if (oneShotJob?.isActive == true) return
        oneShotJob = scope.launch(Dispatchers.IO) {
            try {
                runMic(continuous = false, phrase = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = VoicePhase.Off, error = context.getString(R.string.voice_err_mic), level = 0f) }
            }
        }
    }

    fun cancel() {
        oneShotJob?.cancel()
        turnJob?.cancel()
        stopSpeaking()
        mode.set(MODE_WAKE)
        _state.update { it.copy(phase = if (it.loopActive) VoicePhase.Armed else VoicePhase.Off, level = 0f, working = null) }
    }

    fun stopSpeaking() = tts.stop()

    fun clearResult() = _state.update { it.copy(transcript = null, reply = null, partialReply = "", actions = emptyList(), attachments = emptyList(), error = null, working = null) }

    /** Reads a text aloud (for example the daily briefing). */
    fun say(text: String) {
        turnJob?.cancel()
        stopSpeaking()
        turnJob = scope.launch {
            try {
                _state.update { it.copy(phase = VoicePhase.Thinking, reply = text, transcript = null, actions = emptyList(), error = null) }
                val mp3 = api.speak(text)
                _state.update { it.copy(phase = VoicePhase.Speaking) }
                tts.play(mp3) { lvl -> _state.update { s -> s.copy(level = lvl) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = context.getString(R.string.voice_err_read_aloud)) }
            } finally {
                _state.update { it.copy(phase = if (it.loopActive) VoicePhase.Armed else VoicePhase.Off, level = 0f) }
            }
        }
    }

    /** Tries a Kokoro voice from Settings. */
    fun preview(voice: String) {
        scope.launch {
            runCatching {
                val mp3 = api.speak(context.getString(R.string.voice_preview_text), voice)
                tts.play(mp3) { }
            }.onFailure { _state.update { it.copy(error = context.getString(R.string.voice_err_preview)) } }
        }
    }

    /** Typed question from the Today screen. */
    fun ask(text: String, speak: Boolean) {
        turnJob?.cancel()
        stopSpeaking()
        turnJob = scope.launch {
            _state.update { it.copy(phase = VoicePhase.Thinking, transcript = text, reply = null, partialReply = "", actions = emptyList(), attachments = emptyList(), error = null) }
            runTurn(api.chat(text, currentConversation(), channel = if (speak) "voice" else "chat"), speak, continuous = false)
        }
    }

    // ------------------------------------------------------------ Microphone loop

    @SuppressLint("MissingPermission")
    private suspend fun runMic(continuous: Boolean, phrase: String?) {
        if (!hasMicPermission()) throw IllegalStateException("Microphone permission missing")
        val minBuf = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AudioSpec.FRAME * 8),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord could not start" }
        val detector = if (continuous) WakeWordDetector(wakeModel.path(), phrase ?: context.getString(R.string.wake_phrase)) else null
        val frame = ShortArray(AudioSpec.FRAME)
        val pcm = PcmBuffer()
        val eos = EndOfSpeech()
        var lastLevelAt = 0L

        suspend fun beginCapture() {
            pcm.clear()
            eos.reset()
            detector?.reset()
            mode.set(MODE_CAPTURE)
            if (runCatchingPrefs { it.earcons }) Earcon.start()
            _state.update { it.copy(phase = VoicePhase.Listening, transcript = null, reply = null, partialReply = "", actions = emptyList(), attachments = emptyList(), error = null, working = null) }
        }

        rec.startRecording()
        try {
            if (!continuous) beginCapture() else mode.set(MODE_WAKE)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (captureRequested.getAndSet(false)) beginCapture()
                when (mode.get()) {
                    MODE_WAKE -> {
                        val r = rms(frame, n)
                        eos.updateNoise(r)
                        if (detector?.accept(frame, n) == true) beginCapture()
                    }
                    MODE_CAPTURE -> {
                        pcm.append(frame, n)
                        val r = rms(frame, n)
                        val now = System.currentTimeMillis()
                        if (now - lastLevelAt > 50) {
                            lastLevelAt = now
                            _state.update { it.copy(level = levelOf(r)) }
                        }
                        when (eos.push(r)) {
                            EndOfSpeech.Verdict.Continue -> Unit
                            EndOfSpeech.Verdict.NoSpeech -> {
                                if (runCatchingPrefs { it.earcons }) Earcon.error()
                                _state.update { it.copy(phase = if (continuous) VoicePhase.Armed else VoicePhase.Off, level = 0f, error = context.getString(R.string.voice_err_not_heard)) }
                                if (continuous) mode.set(MODE_WAKE) else return
                            }
                            EndOfSpeech.Verdict.Done -> {
                                if (runCatchingPrefs { it.earcons }) Earcon.end()
                                val wav = pcmToWav(pcm.data, pcm.size)
                                mode.set(MODE_BUSY)
                                _state.update { it.copy(phase = VoicePhase.Thinking, level = 0f) }
                                if (continuous) {
                                    turnJob = scope.launch { voiceTurn(wav, continuous = true) }
                                } else {
                                    // Release the microphone before processing
                                    rec.stop()
                                    turnJob = scope.launch { voiceTurn(wav, continuous = false) }
                                    return
                                }
                            }
                        }
                    }
                    else -> Unit // MODE_BUSY: audio is discarded while Kairo thinks or speaks
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            detector?.close()
        }
    }

    private suspend fun voiceTurn(wav: ByteArray, continuous: Boolean) {
        runTurn(api.voiceTurn(wav, "audio/wav", "voice.wav", currentConversation()), speak = true, continuous = continuous)
    }

    private suspend fun runTurn(events: kotlinx.coroutines.flow.Flow<AgentEvent>, speak: Boolean, continuous: Boolean) {
        var spoken: String? = null
        var finalReply: String? = null
        try {
            events.collect { ev ->
                when (ev) {
                    is AgentEvent.Start -> _state.update { it.copy(conversationId = ev.conversationId) }
                    is AgentEvent.Transcript -> _state.update { it.copy(transcript = ev.text) }
                    is AgentEvent.Delta -> _state.update { it.copy(partialReply = it.partialReply + ev.text) }
                    is AgentEvent.ToolStart -> _state.update { it.copy(working = ev.label) }
                    is AgentEvent.ToolEnd -> _state.update { it.copy(working = null, actions = it.actions + ev) }
                    is AgentEvent.DeviceAction -> withContext(Dispatchers.Main) { deviceActions.handle(ev.action) }
                    is AgentEvent.Attachment -> _state.update { it.copy(attachments = it.attachments + ev.file.url) }
                    is AgentEvent.Done -> {
                        spoken = ev.spoken
                        finalReply = ev.text
                        lastTurnAt = System.currentTimeMillis()
                        _state.update { it.copy(reply = ev.text, partialReply = "", working = null, conversationId = ev.conversationId) }
                    }
                    is AgentEvent.Error -> _state.update { it.copy(error = ev.message, working = null) }
                }
            }
            val say = spoken
            if (speak && !say.isNullOrBlank()) {
                _state.update { it.copy(phase = VoicePhase.Speaking) }
                val mp3 = api.speak(say)
                tts.play(mp3) { lvl -> _state.update { s -> s.copy(level = lvl) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.friendly(context)) }
        } finally {
            onTurnFinished?.invoke(_state.value.transcript, finalReply ?: _state.value.error)
            val followUp = speak && runCatchingPrefs { it.followUp } && finalReply?.trim()?.endsWith("?") == true
            _state.update { it.copy(phase = if (it.loopActive) VoicePhase.Armed else VoicePhase.Off, level = 0f, working = null) }
            when {
                continuous && followUp -> captureRequested.set(true).also { mode.set(MODE_WAKE) }
                continuous -> mode.set(MODE_WAKE)
                followUp -> talk(forcedConversation)
            }
        }
    }

    /** Keeps the voice conversation going if the last turn was less than 5 minutes ago. */
    private fun currentConversation(): String? = forcedConversation
        ?: if (System.currentTimeMillis() - lastTurnAt < 5 * 60_000) _state.value.conversationId else null

    private suspend fun runCatchingPrefs(pick: (app.kairo.android.data.LocalSettings) -> Boolean): Boolean =
        runCatching { pick(prefs.current()) }.getOrDefault(true)

    private companion object {
        const val MODE_WAKE = 0
        const val MODE_CAPTURE = 1
        const val MODE_BUSY = 2
    }
}
