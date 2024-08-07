package org.cagnulen.qdomyoszwift

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var heartRateSensor: Sensor


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager;
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!!;
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        return START_STICKY
    }

    private fun sendHeartRateToPhone(heartRate: Int) {
        val dataClient: DataClient = Wearable.getDataClient(applicationContext)

        val putDataMapRequest = PutDataMapRequest.create("/qz")
        putDataMapRequest.dataMap.putInt("heart_rate", heartRate)

        val task: Task<DataItem> = dataClient.putDataItem(putDataMapRequest.asPutDataRequest())

        task.addOnSuccessListener { dataItem ->
            Log.d(
                "sendHeartRateToPhone",
                "Sending text was successful: $dataItem"
            )
        }

        try {
            Tasks.await(task)
        } catch (exception: Exception) {
            // Handle any exceptions that might occur while awaiting the task
        }
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

    override fun onSensorChanged(p0: SensorEvent?) {
        if (p0!!.sensor.getType() === Sensor.TYPE_HEART_RATE) {
            val heartRate: Float = p0!!.values.get(0)
            sendHeartRateToPhone(heartRate.toInt())
            heartrate = heartRate.toInt()
            println(heartrate)
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }
}