package com.example.Tickt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.math.max

class TimerService : Service() {

    companion object {
        const val CHANNEL_ID = "timer_channel"
        const val CHANNEL_NAME = "Timer"
        const val ALARM_CHANNEL_ID = "timer_alarm_channel"
        const val ALARM_CHANNEL_NAME = "Timer Alarm"
        const val NOTIF_ID_ONGOING = 1337
        const val NOTIF_ID_FINISHED = 1338

        const val ACTION_START = "com.example.clocky.ACTION_START"
        const val ACTION_PAUSE = "com.example.clocky.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.clocky.ACTION_RESUME"
        const val ACTION_STOP = "com.example.clocky.ACTION_STOP"
        const val ACTION_TICK = "com.example.clocky.ACTION_TICK"

        const val EXTRA_TOTAL_SECONDS = "extra_total_seconds"
        const val EXTRA_REMAINING = "extra_remaining"
        const val EXTRA_INITIAL_TOTAL = "extra_initial_total"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var remainingSecs = 0
    private var initialSecs = 0
    private var isRunning = false
    private var isPaused = false
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                val total = intent.getIntExtra(EXTRA_TOTAL_SECONDS, 0)
                if (total > 0) startTimerAsForeground(total)
            }
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_STOP -> stopTimerAndService()
        }
        return START_NOT_STICKY
    }

    private fun startTimerAsForeground(totalSeconds: Int) {
        handler.removeCallbacksAndMessages(null)
        runnable = null
        initialSecs = totalSeconds
        remainingSecs = totalSeconds
        isRunning = true
        isPaused = false
        val notif = buildOngoingNotification(remainingSecs, initialSecs, ongoing = true)
        startForeground(NOTIF_ID_ONGOING, notif)
        runnable = object : Runnable {
            override fun run() {
                if (!isRunning || isPaused) return
                remainingSecs = max(0, remainingSecs - 1)
                val n = buildOngoingNotification(remainingSecs, initialSecs, ongoing = true)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID_ONGOING, n)
                sendTickBroadcast(remainingSecs)
                if (remainingSecs > 0) {
                    handler.postDelayed(this, 1000L)
                } else {
                    onTimerFinished()
                }
            }
        }
        handler.postDelayed(runnable!!, 1000L)
        sendTickBroadcast(remainingSecs)
    }

    private fun onTimerFinished() {
        isRunning = false
        try { handler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        if (!App.inForeground) {
            try { stopForeground(true) } catch (_: Exception) {}
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val finishedNotif = buildFinishedNotification(initialSecs)
            nm.notify(NOTIF_ID_FINISHED, finishedNotif)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, resources.getIdentifier("finish", "raw", packageName))
            mediaPlayer?.setOnCompletionListener {
                it.release()
                mediaPlayer = null
                try { nm.cancel(NOTIF_ID_FINISHED) } catch (_: Exception) {}
                stopSelf()
            }
            mediaPlayer?.start()
        } else {
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun pauseTimer() {
        if (!isRunning || isPaused) return
        isPaused = true
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = buildOngoingNotification(remainingSecs, initialSecs, ongoing = false)
        nm.notify(NOTIF_ID_ONGOING, n)
        try { stopForeground(false) } catch (_: Exception) {}
    }

    private fun resumeTimer() {
        if (!isRunning || !isPaused) return
        isPaused = false
        val n = buildOngoingNotification(remainingSecs, initialSecs, ongoing = true)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ONGOING, n)
        startForeground(NOTIF_ID_ONGOING, n)
        runnable = object : Runnable {
            override fun run() {
                if (!isRunning || isPaused) return
                remainingSecs = max(0, remainingSecs - 1)
                val notif = buildOngoingNotification(remainingSecs, initialSecs, ongoing = true)
                val nm2 = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm2.notify(NOTIF_ID_ONGOING, notif)
                sendTickBroadcast(remainingSecs)
                if (remainingSecs > 0) {
                    handler.postDelayed(this, 1000L)
                } else {
                    onTimerFinished()
                }
            }
        }
        handler.postDelayed(runnable!!, 1000L)
    }

    private fun stopTimerAndService() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        isRunning = false
        isPaused = false
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { stopForeground(true) } catch (_: Exception) {}
        stopSelf()
    }

    private fun buildOngoingNotification(remaining: Int, initial: Int, ongoing: Boolean): Notification {
        val hours = remaining / 3600
        val mins = (remaining / 60) % 60
        val secs = remaining % 60
        val contentText = String.format("%02d:%02d:%02d", hours, mins, secs)
        val progressMax = if (initial > 0) initial else 1
        val progress = progressMax - remaining
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer running")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpen)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        builder.setProgress(progressMax, progress, false)
        return builder.build()
    }

    private fun buildFinishedNotification(initial: Int): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("Timer finished")
            .setContentText("Your timer has finished")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpen)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
        return builder.build()
    }

    private fun sendTickBroadcast(remaining: Int) {
        val b = Intent(ACTION_TICK)
        b.putExtra(EXTRA_REMAINING, remaining)
        b.putExtra(EXTRA_INITIAL_TOTAL, initialSecs)
        sendBroadcast(b)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
            val alarmCh = NotificationChannel(ALARM_CHANNEL_ID, ALARM_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            alarmCh.setShowBadge(true)
            nm.createNotificationChannel(alarmCh)
        }
    }

    override fun onDestroy() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? = null
}