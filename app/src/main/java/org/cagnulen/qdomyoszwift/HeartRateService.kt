package org.cagnulen.qdomyoszwift

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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

    private lateinit var sensorManager: SensorManager
    private lateinit var heartRateSensor: Sensor

    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmIntent: PendingIntent

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupAlarm()
    }

    private fun setupAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = ContextCompat.getSystemService(applicationContext, AlarmManager::class.java)
            if (alarmManager?.canScheduleExactAlarms() == false) {
                Intent().also { intent ->
                    intent.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    applicationContext.startActivity(intent)
                }
            }
        }

        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, HeartRateAlarmReceiver::class.java)
        alarmIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) and above
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 1000,
                    alarmIntent
                )
            } else {
                println("alarm permission not granted")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 (API 23) to Android 11 (API 30)
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                alarmIntent
            )
        } else {
            // Below Android 6.0 (API 23)
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                alarmIntent
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        alarmManager.cancel(alarmIntent)
        sensorManager.unregisterListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager;
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!!;
        val success = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        Log.d("HeartRateService", "onStartCommand $success");
        return START_STICKY
    }

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Monitoring")
            .setContentText("Monitoring your heart rate")
            .setSmallIcon(R.drawable.ic_run) // Assicurati di avere questa icona nelle tue risorse
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        Log.d("HeartRateService", "onSensorChanged $event");
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            heartrate = event.values[0].toInt()
            println(heartrate)
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }
}