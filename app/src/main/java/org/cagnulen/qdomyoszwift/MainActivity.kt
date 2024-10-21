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

        @JvmStatic
        lateinit var serviceIntent: Intent
        private var isFirstRun = true
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

    private fun hasRequiredPermissions(): Boolean {
        return PrepareFragment.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val REQUEST_CODE_PERMISSIONS = 123

    private fun requestRequiredPermissions() {
        ActivityCompat.requestPermissions(this, PrepareFragment.REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }    

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.d(TAG, "Is first run: $isFirstRun")

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allPermissionsGranted || isFirstRun == false) {
                onAllPermissionsGranted()
            } else {
                requestRequiredPermissions()
            }

            isFirstRun = false
        }
    }

    private fun onAllPermissionsGranted() {
        val EXTRA_FOREGROUND_SERVICE_TYPE: String = "FOREGROUND_SERVICE_TYPE";
        val FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : Int = 0x10;

        serviceIntent = Intent(this, HeartRateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            serviceIntent.putExtra(EXTRA_FOREGROUND_SERVICE_TYPE, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private fun startHeartRateService() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
        } else {
            onAllPermissionsGranted();
        }
    }
}
