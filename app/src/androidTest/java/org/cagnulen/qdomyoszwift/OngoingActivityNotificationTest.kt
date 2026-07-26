package org.cagnulen.qdomyoszwift

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Regression test for the Google Play "Wear app quality: Missing ongoing activity" rejection.
 *
 * ExerciseService starts a foreground service to post the Ongoing Activity notification.
 * Android 14+ (API 34) enforces that the declared foregroundServiceType's required
 * permissions are held, or startForeground() throws a SecurityException - which is why the
 * call was previously disabled. This test exercises the exact same call path and fails if it
 * crashes or if startForeground() is never actually reached.
 */
@RunWith(AndroidJUnit4::class)
class OngoingActivityNotificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun postingOngoingActivityNotification_doesNotCrash_andStartsForeground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ExerciseService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as ExerciseService.LocalBinder).getService()

        val method = ExerciseService::class.java.getDeclaredMethod("postOngoingActivityNotification")
        method.isAccessible = true
        var invocationError: Throwable? = null
        // startForeground() should be called on the service's main thread, same as production use.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                method.invoke(service)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                invocationError = e.cause
            }
        }
        invocationError?.let { throw AssertionError("postOngoingActivityNotification() threw: $it", it) }

        // NotificationManager.getActiveNotifications() is unreliable for a foreground service's
        // own notification on the Wear OS emulator image (channel gets created with a healthy
        // importance, but the notification never shows up in the list). The field this method
        // sets right before calling startForeground() is a more direct signal that the call
        // completed instead of being silently blocked.
        val isForegroundField = ExerciseService::class.java.getDeclaredField("isForeground")
        isForegroundField.isAccessible = true
        val isForeground = isForegroundField.getBoolean(service)
        if (!isForeground) {
            throw AssertionError(
                "postOngoingActivityNotification() returned without setting isForeground=true, " +
                    "meaning startForeground() was never reached"
            )
        }

        // Capture visual proof *while the service is still in the foreground*, before
        // ServiceTestRule's teardown unbinds/stops it. A screenshot taken by the CI script
        // after this test method returns is too late - the service is already gone by then.
        // UiAutomation.takeScreenshot() returns null here (no focused window/Activity in this
        // service-only test, and the emulator runs with -no-window), so shell out to the same
        // screencap command the CI script used before - it captures the framebuffer directly
        // and doesn't need window focus - but run it now, at the right moment.
        Thread.sleep(1500)
        val outFile = File(context.getExternalFilesDir(null), "ongoing_activity_screenshot.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p")
            .let { pfd -> FileInputStream(pfd.fileDescriptor) }
            .use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
    }
}
