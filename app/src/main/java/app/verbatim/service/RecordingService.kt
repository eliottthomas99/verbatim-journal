package app.verbatim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.verbatim.JournalApplication
import app.verbatim.asr.AsrTranscriber
import app.verbatim.data.model.Recording
import app.verbatim.tile.RecordTileService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private var pendingTranscriptions = 0 // touched on the main thread only
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == "STOP_RECORDING" -> {
                val wasRecording = isRecording
                val durationMs = stopRecording()
                setRecordingState(false)
                val file = outputFile
                if (wasRecording && file != null) {
                    // Stay in the foreground while transcribing: Parakeet takes ~1 GB,
                    // and Android would otherwise be free to kill the app halfway.
                    pendingTranscriptions++
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, createTranscribingNotification())
                    insertRecordingRow(file, recordingStartTime, durationMs)
                } else {
                    stopIfIdle()
                }
            }
            else -> {
                // Android 10+ requires declaring the foreground service type BEFORE calling startForeground.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                try {
                    startRecording()
                    setRecordingState(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    setRecordingState(false)
                    stopSelf()
                }
            }
        }
        // NOT_STICKY: if Android kills the service, don't silently restart a recording.
        return START_NOT_STICKY
    }

    /** The service is the single source of truth for the tile state. */
    private fun setRecordingState(recording: Boolean) {
        RecordingState.setRecording(this, recording)
        TileService.requestListeningState(this, ComponentName(this, RecordTileService::class.java))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording in progress"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(app.verbatim.R.string.app_name))
            .setContentText("Recording in progress — ensure all parties have consented")
            .setSmallIcon(app.verbatim.R.drawable.ic_stat_verbatim)
            .setOngoing(true)
            .addAction(0, "Stop", createStopPendingIntent())
            .build()
    }

    private fun createTranscribingNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(app.verbatim.R.string.app_name))
            .setContentText("Transcribing on this phone…")
            .setSmallIcon(app.verbatim.R.drawable.ic_stat_verbatim)
            .setOngoing(true)
            .build()
    }

    /** Leaves the foreground once nothing is being recorded or transcribed. Main thread only. */
    private fun stopIfIdle() {
        if (isRecording || pendingTranscriptions > 0) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createStopPendingIntent(): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = "STOP_RECORDING"
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startRecording() {
        if (isRecording) return

        recordingStartTime = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        outputFile = File(filesDir, "recording_$timestamp.m4a")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC in MPEG_4 container → .m4a (much better quality than AMR_NB / 3gp)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }

        isRecording = true
    }

    private fun stopRecording(): Long {
        if (!isRecording) return 0L

        val durationMs = System.currentTimeMillis() - recordingStartTime

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        }

        return durationMs
    }

    fun getOutputFile(): File? = outputFile

    /**
     * Transcribes the just-finished file **on the phone** and stores the
     * transcript in Room, then sends only the *text* to the server. The audio
     * never leaves the device.
     *
     * The row is inserted even when the transcription fails — a recording must
     * never be lost.
     */
    private fun insertRecordingRow(file: File, startedAt: Long, durationMs: Long) {
        val app = application as JournalApplication
        app.applicationScope.launch {
            try {
                val result = AsrTranscriber(applicationContext).transcribe(file)
                val transcript = result.getOrNull()
                val recording = Recording(
                    filename = file.name,
                    timestamp = startedAt,
                    durationMs = durationMs,
                    transcript = transcript?.text ?: "",
                    segments = transcript?.segments ?: emptyList(),
                    transcriptionError = if (result.isFailure) {
                        result.exceptionOrNull()?.message ?: "Transcription failed"
                    } else {
                        ""
                    }
                )
                // Insert first: the transcript is safe on the phone even if the
                // network is down; syncPending() retries later.
                val id = app.repository.insertRecording(recording)
                app.repository.submitTranscript(id)
            } finally {
                mainHandler.post {
                    pendingTranscriptions--
                    stopIfIdle()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
            setRecordingState(false)
        }
    }
}
