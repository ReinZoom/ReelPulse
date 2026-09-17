package com.reelpulse.app.service

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class QuickPauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val serviceInstance = ReelAccessibilityService.instance
        if (serviceInstance != null) {
            // Toggle the paused state internally instead of killing the service
            val isCurrentlyPaused = serviceInstance.isTrackingPaused.value
            serviceInstance.isTrackingPaused.value = !isCurrentlyPaused
            
            updateTileState()
            
            val message = if (serviceInstance.isTrackingPaused.value) 
                "ReelPulse tracking paused." 
            else 
                "ReelPulse tracking resumed."
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            // Service not running at all, prompt user to enable it in system settings
            Toast.makeText(this, "Scanner is Off. Enable in Accessibility Settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val serviceInstance = ReelAccessibilityService.instance
        val isRunning = serviceInstance != null
        val isPaused = serviceInstance?.isTrackingPaused?.value ?: false
        
        qsTile?.apply {
            when {
                !isRunning -> {
                    state = Tile.STATE_INACTIVE
                    label = "Scanner: Off"
                }
                isPaused -> {
                    state = Tile.STATE_INACTIVE
                    label = "Scanner: Paused"
                }
                else -> {
                    state = Tile.STATE_ACTIVE
                    label = "Scanner: Active"
                }
            }
            updateTile()
        }
    }
}
