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
import com.google.android.gms.tasks.Tasks
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

        @JvmStatic
        fun sendHeartRateToPhone(context: Context) {
            val dataClient: DataClient = Wearable.getDataClient(context)

            val putDataMapRequest = PutDataMapRequest.create("/qz")
            putDataMapRequest.dataMap.putInt("heart_rate", heartrate)

            val task: Task<DataItem> = dataClient.putDataItem(putDataMapRequest.asPutDataRequest())

            task.addOnSuccessListener { dataItem ->
                Log.d(
                    "sendHeartRateToPhone",
                    "Sending heart rate was successful: $dataItem"
                )
            }

            try {
                Tasks.await(task)
            } catch (exception: Exception) {
                // Handle any exceptions that might occur while awaiting the task
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())

    // Runnable that sends heart rate every 1 second
    private val sendHeartRateRunnable = object : Runnable {
        override fun run() {
            sendHeartRateToPhone(this@HeartRateService)
            handler.postDelayed(this, 1000) // Schedule next update in 1 second
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
        wakeLock.acquire()

        Log.d("HeartRateService", "Wake lock acquired")
    }

    override fun onDestroy() {
        super.onDestroy()

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

        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = manager
        // getDefaultSensor restituisce null se manca BODY_SENSORS (o se l'orologio non ha il
        // sensore): in quel caso il servizio resta comunque vivo, perche' e' lui a pubblicare
        // l'Ongoing Activity richiesta dalle norme Wear OS.
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        heartRateSensor = sensor
        if (sensor == null) {
            Log.w("HeartRateService", "Heart rate sensor unavailable (permission denied or no sensor)")
        } else {
            val success = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("HeartRateService", "onStartCommand sensor registered: $success")
        }

        // Start periodic heart rate updates using Handler
        handler.post(sendHeartRateRunnable)
        Log.d("HeartRateService", "Started periodic heart rate updates")

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

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Monitoring")
            .setContentText("Monitoring your heart rate")
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
            .setStatus(Status.Builder().addTemplate("Monitoring heart rate").build())
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
