package org.cagnulen.qdomyoszwift

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    val serviceRule = ServiceTestRule()

    @Test
    fun postingOngoingActivityNotification_doesNotCrash_andIsVisibleToTheSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ExerciseService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as ExerciseService.LocalBinder).getService()

        val method = ExerciseService::class.java.getDeclaredMethod("postOngoingActivityNotification")
        method.isAccessible = true
        try {
            method.invoke(service)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError("postOngoingActivityNotification() threw: ${e.cause}", e.cause)
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = notificationManager.activeNotifications.any { it.id == ONGOING_NOTIFICATION_ID }
        assertTrue("Ongoing activity notification was not posted", posted)
    }

    private companion object {
        const val ONGOING_NOTIFICATION_ID = 1
    }
}
