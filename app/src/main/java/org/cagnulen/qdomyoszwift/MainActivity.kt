/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cagnulen.qdomyoszwift

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.ambient.AmbientModeSupport.AmbientCallbackProvider
import dagger.hilt.android.AndroidEntryPoint

/**
 * This Activity serves a handful of functions:
 * - to host a [NavHostFragment]
 * - to capture KeyEvents
 * - to support Ambient Mode, because [AmbientCallbackProvider] must be an `Activity`.
 *
 * [MainViewModel] is used to coordinate between this Activity and the [ExerciseFragment], which
 * contains UI during an active exercise.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main), AmbientCallbackProvider {

    companion object {
        private const val TAG = "MainActivity"

        @JvmStatic
        var serviceIntent: Intent? = null
        private var permissionsRequested = false

        // Permessi base da richiedere prima
        private val BASE_PERMISSIONS = arrayOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
            android.Manifest.permission.POST_NOTIFICATIONS
        )

        // Permesso background da richiedere dopo
        private val BACKGROUND_PERMISSION = android.Manifest.permission.BODY_SENSORS_BACKGROUND
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3,
            KeyEvent.KEYCODE_STEM_PRIMARY -> {
                viewModel.sendKeyPress()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback = AmbientModeCallback()

    inner class AmbientModeCallback : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle) {
            viewModel.sendAmbientEvent(AmbientEvent.Enter(ambientDetails))
        }

        override fun onExitAmbient() {
            viewModel.sendAmbientEvent(AmbientEvent.Exit)
        }

        override fun onUpdateAmbient() {
            viewModel.sendAmbientEvent(AmbientEvent.Update)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        startHeartRateService()
        val wakeLock: PowerManager.WakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag").apply {
                    acquire()
                }
            }
    }

    private fun hasBasePermissions(): Boolean {
        return BASE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBackgroundPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, BACKGROUND_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private val REQUEST_CODE_BASE_PERMISSIONS = 123
    private val REQUEST_CODE_BACKGROUND_PERMISSION = 124

    private fun requestBasePermissions() {
        Log.d(TAG, "Requesting base permissions")
        ActivityCompat.requestPermissions(this, BASE_PERMISSIONS, REQUEST_CODE_BASE_PERMISSIONS)
    }

    private fun requestBackgroundPermission() {
        Log.d(TAG, "Requesting background permission")
        ActivityCompat.requestPermissions(this, arrayOf(BACKGROUND_PERMISSION), REQUEST_CODE_BACKGROUND_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_BASE_PERMISSIONS -> {
                val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                Log.d(TAG, "Base permissions result (all granted: $allPermissionsGranted), starting HeartRateService")
                // Avviato in ogni caso: e' questo servizio a pubblicare l'Ongoing Activity
                // richiesta dalle norme Wear OS, e HeartRateService degrada da solo se manca
                // qualche permesso. Subordinarlo ai permessi significava non mostrare nulla.
                onBasePermissionsGranted()
                // Il permesso background e' un extra: la sua richiesta non deve bloccare nulla.
                requestBackgroundPermission()
            }
            REQUEST_CODE_BACKGROUND_PERMISSION -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    Log.d(TAG, "Background sensors permission granted")
                } else {
                    // Il dialogo di sistema per BODY_SENSORS_BACKGROUND offre solo "While using
                    // app"/"Don't allow" e rimanda a una schermata di Impostazioni: la maggior
                    // parte degli utenti non lo concede. Il servizio resta comunque attivo.
                    Log.w(TAG, "Background sensors permission not granted; HeartRateService keeps running in foreground")
                }
            }
        }
    }

    private fun onBasePermissionsGranted() {
        val EXTRA_FOREGROUND_SERVICE_TYPE: String = "FOREGROUND_SERVICE_TYPE";
        val FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : Int = 0x10;

        val intent = Intent(this, HeartRateService::class.java)
        serviceIntent = intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(EXTRA_FOREGROUND_SERVICE_TYPE, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private fun startHeartRateService() {
        // L'Ongoing Activity non deve mai dipendere dall'esito dei permessi: se mancano li
        // chiediamo (e il servizio parte nella callback), altrimenti partiamo subito.
        if (!hasBasePermissions() && !permissionsRequested) {
            Log.d(TAG, "Requesting permissions for the first time")
            permissionsRequested = true
            requestBasePermissions()
            return
        }
        Log.d(TAG, "Starting HeartRateService")
        onBasePermissionsGranted()
        if (!hasBackgroundPermission()) {
            requestBackgroundPermission()
        }
    }
}
