package app.kairo.android.recording

import app.kairo.android.ui.common.friendly
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.kairo.android.R
import app.kairo.android.graph
import app.kairo.android.notify.Channels
import app.kairo.android.notify.Notifications
import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class RecorderState(
    val recordingId: String? = null,
    val active: Boolean = false,
    val startedAt: Long = 0L,
    val uploaded: Int = 0,
    val pending: Int = 0,
    val level: Float = 0f,
    val finishing: Boolean = false,
    val error: String? = null,
    val lastFinishedId: String? = null,
)

/**
 * Records meetings or long notes in 5-minute segments (AAC 16 kHz) that are uploaded
 * and transcribed while recording. When finished, the server generates a summary and action items.
 * The audio is deleted from the phone as soon as it is uploaded.
 */
class RecorderController(private val context: Context, private val api: KairoApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state
    private val uploads = Channel<Segment>(Channel.UNLIMITED)
    private var uploader: Job? = null

    data class Segment(val file: File, val seq: Int, val durationS: Int, val last: Boolean)

    fun start(title: String = "") {
        if (_state.value.active) return
        scope.launch {
            try {
                val rec = api.createRecording(title)
                _state.value = RecorderState(recordingId = rec.id, active = true, startedAt = System.currentTimeMillis())
                startUploader(rec.id)
                context.startForegroundService(Intent(context, MeetingRecorderService::class.java))
            } catch (e: Exception) {
                _state.update { it.copy(error = if (e is app.kairo.core.net.ApiException) e.friendly(context) else context.getString(R.string.rec_error_start)) }
            }
        }
    }

    fun stop() {
        if (!_state.value.active) return
        _state.update { it.copy(finishing = true) }
        context.startService(Intent(context, MeetingRecorderService::class.java).setAction(MeetingRecorderService.ACTION_STOP))
    }

    internal fun onSegment(seg: Segment) {
        _state.update { it.copy(pending = it.pending + 1) }
        uploads.trySend(seg)
    }

    internal fun onLevel(level: Float) = _state.update { it.copy(level = level) }

    private fun startUploader(id: String) {
        uploader?.cancel()
        uploader = scope.launch(Dispatchers.IO) {
            for (seg in uploads) {
                var ok = false
                var attempt = 0
                while (!ok && attempt < 5 && isActive) {
                    ok = runCatching { api.uploadSegment(id, seg.seq, seg.durationS, seg.file.readBytes(), "audio/mp4") }.isSuccess
                    if (!ok) delay(3_000L * (attempt + 1))
                    attempt++
                }
                seg.file.delete()
                _state.update { it.copy(uploaded = it.uploaded + if (ok) 1 else 0, pending = (it.pending - 1).coerceAtLeast(0), error = if (ok) it.error else context.getString(R.string.rec_error_segment)) }
                if (seg.last) {
                    runCatching { api.finishRecording(id) }
                    _state.update { RecorderState(lastFinishedId = id) }
                    break
                }
            }
        }
    }
}

class MeetingRecorderService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var seq = 0
    private var segmentStart = 0L
    private var levelJob: Job? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            finishSegment(last = true)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, Notifications.ID_RECORDING, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (recorder == null) startSegment()
        levelJob = scope.launch {
            while (isActive) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                graph.recorder.onLevel((amp / 12000f).coerceIn(0f, 1f))
                delay(120)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSegment() {
        val f = File(cacheDir, "rec-${System.currentTimeMillis()}-$seq.m4a")
        val r = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(32_000)
            setMaxDuration(SEGMENT_MS)
            setOutputFile(f.absolutePath)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && !stopping) {
                    finishSegment(last = false)
                    startSegment()
                }
            }
            prepare()
            start()
        }
        recorder = r
        currentFile = f
        segmentStart = System.currentTimeMillis()
    }

    private fun finishSegment(last: Boolean) {
        val r = recorder ?: return
        runCatching { r.stop() }
        r.release()
        recorder = null
        val f = currentFile ?: return
        val dur = ((System.currentTimeMillis() - segmentStart) / 1000).toInt()
        graph.recorder.onSegment(RecorderController.Segment(f, seq++, dur, last))
    }

    override fun onDestroy() {
        levelJob?.cancel()
        if (recorder != null) finishSegment(last = true)
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, Channels.RECORDING)
        .setSmallIcon(R.drawable.ic_stat_kairo)
        .setColor(0xFFF0A23B.toInt())
        .setContentTitle(getString(R.string.rec_notif_title))
        .setContentText(getString(R.string.rec_notif_text))
        .setUsesChronometer(true)
        .setWhen(System.currentTimeMillis())
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(Notifications.openApp(this, "recordings"))
        .addAction(0, getString(R.string.home_stop), PendingIntent.getService(this, 3, Intent(this, MeetingRecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_STOP = "app.kairo.android.STOP_RECORDING"
        const val SEGMENT_MS = 5 * 60 * 1000
    }
}
