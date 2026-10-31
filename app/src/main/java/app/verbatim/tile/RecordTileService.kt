package app.verbatim.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.verbatim.service.RecordingState

class RecordTileService : TileService() {

    companion object {
        private const val TAG = "RecordTileService"
    }

    // Written by RecordingService; shared with the app's record button.
    private val isRecording: Boolean
        get() = RecordingState.isRecording(applicationContext)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // On Android 14+ (API 34), we must call startForegroundService() before the tile
        // callback returns if we want to start a foreground service. However, TileService
        // callbacks are already in the foreground context, so startForegroundService() is safe.

        if (isRecording) {
            // Stop recording
            stopRecording()
        } else {
            // Start recording
            startRecording()
        }
    }

    private fun startRecording() {
        val intent = Intent(this, app.verbatim.service.RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Don't set isRecording here: the service writes it once recording has
        // actually started, then asks the tile to refresh (requestListeningState).
    }

    private fun stopRecording() {
        // The service is already in the foreground, so a plain startService() is
        // allowed. startForegroundService() would oblige the service to call
        // startForeground() again, and Android would crash the app if it doesn't.
        val intent = Intent(this, app.verbatim.service.RecordingService::class.java).apply {
            action = "STOP_RECORDING"
        }
        startService(intent)
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (isRecording) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Recording..."
            tile.updateTile()
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Record"
            tile.updateTile()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }
}
