package com.example.sync

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

class AddNoteTileService : TileService() {
    
    override fun onStartListening() {
        super.onStartListening()
        // Make sure the tile is in active state so it is clickable
        try {
            val tile = qsTile
            if (tile != null) {
                tile.state = android.service.quicksettings.Tile.STATE_ACTIVE
                tile.updateTile()
            }
        } catch (e: Exception) {
            Log.e("AddNoteTileService", "Error in onStartListening: ${e.message}")
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d("AddNoteTileService", "Quick Settings Tile clicked. Launching Quick Capture (ShareActivity)...")
        
        val intent = Intent(this, com.example.ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            Log.d("AddNoteTileService", "Successfully triggered startActivityAndCollapse")
        } catch (e: Exception) {
            Log.e("AddNoteTileService", "Failed to launch ShareActivity from QS Tile: ${e.message}", e)
        }
    }
}
