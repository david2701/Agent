package app.kairo.wear

import app.kairo.wear.R
import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.provider.AlarmClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kairo.core.model.AgentEvent
import app.kairo.core.model.TodayData
import app.kairo.core.voice.AudioSpec
import app.kairo.core.voice.EndOfSpeech
import app.kairo.core.voice.PcmBuffer
import app.kairo.core.voice.levelOf
import app.kairo.core.voice.pcmToWav
import app.kairo.core.voice.rms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.coroutines.resume

enum class WearPhase { Idle, Listening, Thinking, Speaking }

data class WearState(
    val phase: WearPhase = WearPhase.Idle,
    val level: Float = 0f,
    val transcript: String? = null,
    val reply: String? = null,
    val actions: List<String> = emptyList(),
    val error: String? = null,
    val today: TodayData? = null,
)

/** Voice on the watch: records a phrase, sends it to Kairo and reads the reply if the watch has a speaker. */
class WearVoice(app: Application) : AndroidViewModel(app) {
    private val api = app.wear.api
    val state = MutableStateFlow(WearState())
    private var job: Job? = null
    private var conversationId: String? = null
    private var lastTurn = 0L

    fun loadToday() = viewModelScope.launch {
        runCatching { api.today() }.onSuccess { t -> state.update { it.copy(today = t) } }
    }

    fun hasMic() = getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggle() {
        if (state.value.phase != WearPhase.Idle) { job?.cancel(); state.update { it.copy(phase = WearPhase.Idle, level = 0f) }; return }
        job = viewModelScope.launch {
            state.update { WearState(phase = WearPhase.Listening, today = it.today) }
            val wav = withContext(Dispatchers.IO) { record() }
            if (wav == null) { state.update { it.copy(phase = WearPhase.Idle, error = getApplication<Application>().getString(R.string.wear_not_heard), level = 0f) }; return@launch }
            state.update { it.copy(phase = WearPhase.Thinking, level = 0f) }
            var spoken: String? = null
            val conv = if (System.currentTimeMillis() - lastTurn < 5 * 60_000) conversationId else null
            try {
                api.voiceTurn(wav, "audio/wav", "watch.wav", conv, channel = "wear").collect { ev ->
                    when (ev) {
                        is AgentEvent.Start -> conversationId = ev.conversationId
                        is AgentEvent.Transcript -> state.update { it.copy(transcript = ev.text) }
                        is AgentEvent.ToolEnd -> state.update { it.copy(actions = it.actions + ev.summary) }
                        is AgentEvent.DeviceAction -> handle(ev)
                        is AgentEvent.Done -> { spoken = ev.spoken; lastTurn = System.currentTimeMillis(); state.update { it.copy(reply = ev.spoken) } }
                        is AgentEvent.Error -> state.update { it.copy(error = ev.message) }
                        else -> Unit
                    }
                }
                val say = spoken
                if (!say.isNullOrBlank() && hasSpeaker()) {
                    state.update { it.copy(phase = WearPhase.Speaking) }
                    play(api.speak(say))
                }
            } catch (e: Exception) {
                state.update { it.copy(error = e.friendly(getApplication<Application>())) }
            }
            state.update { it.copy(phase = WearPhase.Idle, level = 0f) }
            loadToday()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, AudioSpec.FRAME * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) return null
        val frame = ShortArray(AudioSpec.FRAME)
        val pcm = PcmBuffer()
        val eos = EndOfSpeech()
        rec.startRecording()
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                pcm.append(frame, n)
                val r = rms(frame, n)
                state.update { it.copy(level = levelOf(r)) }
                when (eos.push(r)) {
                    EndOfSpeech.Verdict.Done -> return pcmToWav(pcm.data, pcm.size)
                    EndOfSpeech.Verdict.NoSpeech -> return null
                    EndOfSpeech.Verdict.Continue -> Unit
                }
            }
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun handle(ev: AgentEvent.DeviceAction) {
        val type = (ev.action["type"] as? JsonPrimitive)?.content
        if (type == "timer") {
            val secs = ev.action["seconds"]?.jsonPrimitive?.intOrNull ?: return
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, ev.action["label"]?.jsonPrimitive?.content ?: "Kairo")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { getApplication<Application>().startActivity(intent) }
        }
    }

    private fun hasSpeaker(): Boolean {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    private suspend fun play(mp3: ByteArray) {
        val f = File(getApplication<Application>().cacheDir, "tts.mp3").apply { writeBytes(mp3) }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release(); if (cont.isActive) cont.resume(Unit) }
            mp.setOnErrorListener { p, _, _ -> p.release(); if (cont.isActive) cont.resume(Unit); true }
            mp.setOnPreparedListener { it.start() }
            cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
            mp.prepareAsync()
        }
    }
}
