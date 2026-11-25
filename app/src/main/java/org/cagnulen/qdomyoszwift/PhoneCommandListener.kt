package org.cagnulen.qdomyoszwift

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone QZ sends /qz/hr/wake when a walk starts (and every minute while it goes on)
 * and /qz/hr/sleep when it ends. Play Services delivers these even while the watch
 * app is in the background.
 */
class PhoneCommandListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val action = when (event.path) {
            "/qz/hr/wake" -> HeartRateService.ACTION_WAKE
            "/qz/hr/sleep" -> HeartRateService.ACTION_SLEEP
            else -> return
        }
        Log.d(TAG, "phone says ${event.path}")
        // this phone app wakes the watch for workouts: from now on the watch sleeps in between
        HeartRateService.markPhoneDrives(this)
        if (HeartRateService.command(action)) return
        if (action == HeartRateService.ACTION_SLEEP) return

        // Service not running (watch rebooted, or STOP pressed on the watch): try to start it.
        // Android may refuse a foreground start from the background; then QZ has to be
        // opened on the watch once.
        try {
            val intent = Intent(this, HeartRateService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Log.d(TAG, "started HeartRateService from phone wake")
        } catch (e: Exception) {
            Log.w(TAG, "could not start HeartRateService from the background", e)
        }
    }

    companion object {
        private const val TAG = "PhoneCommandListener"
    }
}
