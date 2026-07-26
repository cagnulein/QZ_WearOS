package org.cagnulen.qdomyoszwift

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the Google Play "Wear app quality: Missing ongoing activity" rejection.
 *
 * ExerciseService starts a foreground service to post the Ongoing Activity notification.
 * Android 14+ (API 34) enforces that the declared foregroundServiceType's required
 * permissions are held, or startForeground() throws a SecurityException - which is why the
 * call was previously disabled. This test exercises the exact same call path and fails if it
 * crashes or if the Ongoing Activity notification never reaches the NotificationManager.
 */
@RunWith(AndroidJUnit4::class)
class OngoingActivityNotificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun postingOngoingActivityNotification_doesNotCrash_andIsVisibleToTheSystem() {
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

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = generateSequence(0) { it + 1 }
            .take(20)
            .onEach { if (it > 0) Thread.sleep(100) }
            .any { notificationManager.activeNotifications.any { n -> n.id == ONGOING_NOTIFICATION_ID } }
        assertTrue("Ongoing activity notification was not posted", posted)
    }

    private companion object {
        const val ONGOING_NOTIFICATION_ID = 1
    }
}
