package com.livetrans.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Phase 0 spike: proves whether AudioPlaybackCapture can actually see audio
 * from Samsung Internet / Chrome / YouTube before any STT/translation is built.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CaptureService"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        startForeground(NOTIFICATION_ID, buildNotification("캡처 시작..."))

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            updateNotification("초기화 실패")
            stopSelf()
            return START_NOT_STICKY
        }

        record.startRecording()
        running = true
        thread(name = "capture-read") { readLoop(record, bufferSize) }

        return START_STICKY
    }

    private fun readLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        var lastUpdate = 0L
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            var peak = 0
            for (i in 0 until read) {
                val v = abs(buffer[i].toInt())
                if (v > peak) peak = v
            }

            val now = System.currentTimeMillis()
            if (now - lastUpdate > 800) {
                lastUpdate = now
                val silent = peak < 50
                val msg = if (silent) "무음 (peak=$peak)" else "소리 감지! peak=$peak"
                Log.d(TAG, msg)
                updateNotification(msg)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "캡처 테스트", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveTrans 캡처 테스트")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
