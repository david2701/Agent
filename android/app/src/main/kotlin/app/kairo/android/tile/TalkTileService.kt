package app.kairo.android.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.kairo.android.assist.AssistActivity

/** Tile de ajustes rápidos: un toque y Kairo escucha. */
class TalkTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Kairo"
            subtitle = "Hablar"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, AssistActivity::class.java).setAction(AssistActivity.ACTION_TALK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
