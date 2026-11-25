package org.cagnulen.qdomyoszwift

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable


class HeartRateService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "HeartRateServiceChannel"
        private const val NOTIFICATION_ID = 1

        @JvmStatic
        var heartrate: Int = 0

        // Awake = sensor on, wake lock held, heart rate sent every second.
        // Asleep = none of that; the service only stays alive to hear the phone.
        const val ACTION_WAKE = "org.cagnulen.qdomyoszwift.HR_WAKE"
        const val ACTION_SLEEP = "org.cagnulen.qdomyoszwift.HR_SLEEP"

        // How long one wake from the phone lasts. Phone QZ repeats it every minute
        // while you walk, so if the phone goes quiet the watch falls asleep by itself.
        private const val PHONE_WAKE_MS = 3 * 60 * 1000L
        // Opening QZ on the watch by hand keeps it awake this long.
        private const val MANUAL_WAKE_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: HeartRateService? = null

        // Wear OS capability that phone QZ declares when it sends /qz/hr/wake and /qz/hr/sleep
        // (res/values/wear.xml of the phone app). Checked at run time, so an older phone app,
        // or one downgraded after using the new protocol, puts the watch back in legacy mode:
        // awake from the moment the service starts, with no sleep of its own, as before. The
        // phone and watch apps are released independently.
        const val PHONE_CAPABILITY = "qz_hr_wake_sleep"
        private const val START_RESTART = "restart"
        private const val START_MANUAL = "manual"

        /** Called from PhoneCommandListener. Returns false if the service is not running. */
        @JvmStatic
        fun command(action: String): Boolean {
            val svc = instance ?: return false
            svc.handler.post { svc.handle(action) }
            return true
        }

        @JvmStatic
        fun sendHeartRateToPhone(context: Context) {
            val dataClient: DataClient = Wearable.getDataClient(context)

            val putDataMapRequest = PutDataMapRequest.create("/qz")
            putDataMapRequest.dataMap.putInt("heart_rate", heartrate)

            // asynchronous: nothing waits on the task, so a slow data layer can't pile up threads
            val task: Task<DataItem> = dataClient.putDataItem(putDataMapRequest.asPutDataRequest())
            task.addOnSuccessListener { dataItem ->
                Log.d("sendHeartRateToPhone", "Sending heart rate was successful: $dataItem")
            }
            task.addOnFailureListener { e ->
                Log.w("sendHeartRateToPhone", "Sending heart rate failed", e)
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private var awake = false
    private var awakeUntil = 0L
    // null until the first capability check answers; true = a phone app that wakes the watch
    private var phoneDrives: Boolean? = null
    // what started the service, applied once phoneDrives is known
    private var pendingStart: String? = null
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        handler.post { setPhoneDrives(info.nodes.isNotEmpty(), "capability changed") }
    }

    // Runnable that sends heart rate every 1 second while awake
    private val sendHeartRateRunnable = object : Runnable {
        override fun run() {
            if (!awake) return
            if (System.currentTimeMillis() > awakeUntil) {
                Log.d("HeartRateService", "No wake from the phone in time, going to sleep")
                goToSleep()
                return
            }
            sendHeartRateToPhone(this@HeartRateService)
            handler.postDelayed(this, 1000) // Schedule next update in 1 second
        }
    }

    private fun handle(action: String) {
        // a message from the phone means its app speaks the protocol
        setPhoneDrives(true, "message from the phone")
        when (action) {
            ACTION_SLEEP -> goToSleep()
            else -> wakeUp(PHONE_WAKE_MS)
        }
    }

    private fun applyStart(reason: String) {
        val drives = phoneDrives == true
        when (reason) {
            ACTION_SLEEP -> goToSleep()
            ACTION_WAKE -> wakeUp(PHONE_WAKE_MS)
            // restarted by the system after a kill (null intent): asleep until the phone asks,
            // or awake as before with an older phone app
            START_RESTART -> if (drives) updateNotification() else wakeUpForever()
            // opened by hand
            else -> if (drives) wakeUp(MANUAL_WAKE_MS) else wakeUpForever()
        }
    }

    // Legacy mode: awake until the service is stopped, as before
    private fun wakeUpForever() {
        awakeUntil = Long.MAX_VALUE
        startSensing()
    }

    private fun setPhoneDrives(drives: Boolean, why: String) {
        val before = phoneDrives
        phoneDrives = drives
        if (before == drives) return
        Log.d("HeartRateService", "phone app wakes the watch: $drives ($why)")
        if (before == null) {
            pendingStart?.let { applyStart(it) }
            pendingStart = null
            return
        }
        if (drives) {
            // legacy -> phone mode: the endless legacy deadline must not carry over
            if (awake && awakeUntil > System.currentTimeMillis() + MANUAL_WAKE_MS)
                awakeUntil = System.currentTimeMillis() + PHONE_WAKE_MS
        } else if (awake) {
            // phone app downgraded: back to legacy, awake until stopped, as before
            awakeUntil = Long.MAX_VALUE
        }
    }

    private fun checkPhoneCapability() {
        Wearable.getCapabilityClient(this)
            .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_ALL)
            .addOnSuccessListener { info: CapabilityInfo ->
                setPhoneDrives(info.nodes.isNotEmpty(), "capability check")
            }
            .addOnFailureListener { e ->
                Log.w("HeartRateService", "capability check failed, legacy mode", e)
                setPhoneDrives(false, "capability check failed")
            }
    }

    private fun wakeUp(forMs: Long) {
        val now = System.currentTimeMillis()
        // in phone mode a deadline far beyond any wake (left from legacy mode) is dropped
        val current = if (awakeUntil > now + MANUAL_WAKE_MS) 0L else awakeUntil
        awakeUntil = maxOf(current, now + forMs)
        startSensing()
    }

    private fun startSensing() {
        if (awake) return
        awake = true
        if (!wakeLock.isHeld) wakeLock.acquire()
        val manager = sensorManager
            ?: (getSystemService(SENSOR_SERVICE) as SensorManager).also { sensorManager = it }
        // null if BODY_SENSORS is missing or there is no sensor; the service stays up anyway
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        heartRateSensor = sensor
        if (sensor == null) {
            Log.w("HeartRateService", "Heart rate sensor unavailable (permission denied or no sensor)")
        } else {
            val success = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("HeartRateService", "sensor registered: $success")
        }
        handler.removeCallbacks(sendHeartRateRunnable)
        handler.post(sendHeartRateRunnable)
        updateNotification()
        Log.d("HeartRateService", "Awake")
    }

    private fun goToSleep() {
        awakeUntil = 0L
        if (!awake) return
        awake = false
        handler.removeCallbacks(sendHeartRateRunnable)
        sensorManager?.unregisterListener(this)
        heartrate = 0
        // tell the phone the reading is gone, so it does not keep a stale number
        sendHeartRateToPhone(this@HeartRateService)
        if (wakeLock.isHeld) wakeLock.release()
        updateNotification()
        Log.d("HeartRateService", "Asleep")
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("HeartRateService", "notification update failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire wake lock to keep CPU running even when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "QZ:HeartRateMonitoring"
        )
        wakeLock.setReferenceCounted(false)
        instance = this
        Wearable.getCapabilityClient(this).addListener(capabilityListener, PHONE_CAPABILITY)
        checkPhoneCapability()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        awake = false
        Wearable.getCapabilityClient(this).removeListener(capabilityListener)

        // Stop periodic heart rate updates
        handler.removeCallbacks(sendHeartRateRunnable)

        // Release wake lock
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d("HeartRateService", "Wake lock released")
        }

        sensorManager?.unregisterListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val reason = when {
            intent == null -> START_RESTART
            intent.action == ACTION_SLEEP || intent.action == ACTION_WAKE -> intent.action!!
            else -> START_MANUAL
        }
        if (reason == ACTION_SLEEP || reason == ACTION_WAKE) setPhoneDrives(true, "started by the phone")
        if (phoneDrives == null) {
            // the capability check started in onCreate answers in a moment
            pendingStart = reason
            updateNotification()
        } else {
            applyStart(reason)
        }

        return START_STICKY
    }

    /**
     * Su Android 14+ il tipo "connectedDevice" richiede BLUETOOTH_CONNECT concesso a runtime e
     * "health" richiede BODY_SENSORS/ACTIVITY_RECOGNITION: se l'utente li nega, startForeground
     * con quel tipo lancia SecurityException. Scegliamo il tipo in base a cio' che abbiamo
     * davvero, con un ultimo fallback senza tipo, cosi' l'Ongoing Activity viene pubblicata
     * comunque invece di far crashare la app.
     */
    private fun startForegroundCompat() {
        val notification = createNotification()
        val type = when {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            hasPermission(Manifest.permission.BODY_SENSORS) ||
                hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            else -> null
        }
        if (type != null) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                Log.d("HeartRateService", "startForeground with type=$type")
                return
            } catch (e: Exception) {
                Log.e("HeartRateService", "startForeground failed with type=$type", e)
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d("HeartRateService", "startForeground without explicit type")
        } catch (e: Exception) {
            Log.e("HeartRateService", "startForeground failed", e)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Heart Rate Service"
            val descriptionText = "Monitoring heart rate"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (awake) "Sending heart rate to QZ" else "Asleep until QZ on the phone starts a walk"
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QZ heart rate")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_run)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        // This is the service that's actually running for the whole duration of a workout
        // (started as soon as the app opens, see MainActivity.onAllPermissionsGranted), unlike
        // ExerciseService which the exercise-tracking UI never calls into. Registering the
        // Ongoing Activity here is what makes the watch face indicator / recent-apps chip show
        // up during real usage.
        val ongoingActivity = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            .setStaticIcon(R.drawable.ic_run)
            .setTouchIntent(pendingIntent)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
        ongoingActivity.apply(applicationContext)

        return notificationBuilder.build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        Log.d("HeartRateService", "onSensorChanged")
        Log.d("HeartRateService", "Sensor type: ${event?.sensor?.type}")
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            heartrate = event.values[0].toInt()
            Log.d("HeartRateService", "Heart rate: $heartrate")
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }
}
