package com.livetrans.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.abs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Captures media playback audio via AudioPlaybackCapture, runs it through an
 * offline Vosk recognizer, and translates the result to Korean with ML Kit
 * (skipped when the source language already is Korean).
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SOURCE_LANG = "source_lang"
        const val ACTION_STOP = "com.livetrans.app.ACTION_STOP"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CaptureService"
        private const val SAMPLE_RATE = 16000

        /** In-process hook so MainActivity can mirror subtitles without IPC plumbing. */
        var onSubtitle: ((String) -> Unit)? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private val recognizerLock = Any()
    private var recognizer: Recognizer? = null

    @Volatile
    private var translator: Translator? = null
    private var overlayView: TextView? = null
    private var floatingButton: TextView? = null
    private var scrimView: View? = null
    private var popupCard: LinearLayout? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val sourceLang = SourceLanguage.valueOf(
            intent?.getStringExtra(EXTRA_SOURCE_LANG) ?: SourceLanguage.ENGLISH.name
        )

        startForeground(NOTIFICATION_ID, buildNotification("초기화 중..."))
        if (overlayView == null) showOverlay()
        if (floatingButton == null) showFloatingButton()

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A second start (e.g. language changed via the popup) replaces the running session.
        tearDownSession()
        thread(name = "capture-setup") { setUpAndCapture(resultCode, resultData, sourceLang) }

        return START_STICKY
    }

    private fun tearDownSession() {
        running = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(recognizerLock) {
            recognizer?.close()
            recognizer = null
        }
        translator?.close()
        translator = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    /** Small draggable button that opens the settings popup; tap vs. drag decided by movement. */
    private fun showFloatingButton() {
        val btn = TextView(this).apply {
            text = "LT"
            setBackgroundColor(Color.argb(220, 33, 150, 243))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val size = (48 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        val wm = getSystemService(WindowManager::class.java)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(btn, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openSettingsPopup()
                    true
                }
                else -> false
            }
        }

        wm.addView(btn, params)
        floatingButton = btn
    }

    /** Language-picker card drawn as overlay windows, same technique as the subtitle bar. */
    private fun openSettingsPopup() {
        if (scrimView != null) return
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density

        val scrim = View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        scrim.setOnClickListener { closeSettingsPopup() }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(scrim, scrimParams)
        scrimView = scrim

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val options = listOf(
            SourceLanguage.JAPANESE to "일본어",
            SourceLanguage.ENGLISH to "영어",
            SourceLanguage.KOREAN to "한국어"
        )
        for ((lang, label) in options) {
            addPopupItem(card, label, density) {
                closeSettingsPopup()
                switchLanguage(lang)
            }
        }
        addPopupItem(card, "중지", density, textColor = Color.RED) {
            closeSettingsPopup()
            stopSelf()
        }
        val cardParams = WindowManager.LayoutParams(
            (200 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wm.addView(card, cardParams)
        popupCard = card
    }

    private fun addPopupItem(card: LinearLayout, label: String, density: Float, textColor: Int? = null, onClick: () -> Unit) {
        card.addView(TextView(this).apply {
            text = label
            textSize = 16f
            textColor?.let { setTextColor(it) }
            gravity = Gravity.CENTER
            val vpad = (14 * density).toInt()
            setPadding(0, vpad, 0, vpad)
            setOnClickListener { onClick() }
        })
    }

    private fun closeSettingsPopup() {
        removeOverlayWindow(scrimView)
        removeOverlayWindow(popupCard)
        scrimView = null
        popupCard = null
    }

    /**
     * Swaps the STT model and translator live; audio capture keeps running (no new consent needed).
     * The old [Recognizer] is a native object — closing it while [readLoop] is mid-call into it
     * segfaults the process, so the swap is guarded by [recognizerLock] and the old one is only
     * closed after the field has already moved readLoop onto the new instance.
     */
    private fun switchLanguage(newLang: SourceLanguage) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_KEY_SOURCE_LANG, newLang.name).apply()
        thread(name = "lang-switch") {
            try {
                val (newRecognizer, newTranslator) = loadRecognizerAndTranslator(newLang)

                val oldRecognizer = synchronized(recognizerLock) {
                    val old = recognizer
                    recognizer = newRecognizer
                    old
                }
                oldRecognizer?.close()
                translator?.close()
                translator = newTranslator
                updateNotification("자막 대기 중...")
            } catch (e: Exception) {
                Log.e(TAG, "language switch failed", e)
                updateNotification("오류: ${e.message}")
            }
        }
    }

    private fun showOverlay() {
        val tv = TextView(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 120
        }
        getSystemService(WindowManager::class.java).addView(tv, params)
        overlayView = tv
    }

    private fun setUpAndCapture(resultCode: Int, resultData: Intent, sourceLang: SourceLanguage) {
        try {
            val (newRecognizer, newTranslator) = loadRecognizerAndTranslator(sourceLang)
            recognizer = newRecognizer
            translator = newTranslator

            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection

            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                return
            }

            record.startRecording()
            running = true
            updateNotification("자막 대기 중...")
            readLoop(record, bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            updateNotification("오류: ${e.message}")
            stopSelf()
        }
    }

    /** Downloads/loads the Vosk model and (if needed) the ML Kit translator for [lang]. */
    private fun loadRecognizerAndTranslator(lang: SourceLanguage): Pair<Recognizer, Translator?> {
        updateNotification("STT 모델 준비 중... (${lang.modelName})")
        val modelDir = ModelDownloader.ensureModel(filesDir, lang.modelName, lang.zipUrl) { updateNotification(it) }
        val newRecognizer = Recognizer(Model(modelDir.absolutePath), SAMPLE_RATE.toFloat())
        val newTranslator = if (lang != SourceLanguage.KOREAN) prepareTranslator(lang.mlkitCode) else null
        return newRecognizer to newTranslator
    }

    private fun prepareTranslator(sourceMlkitCode: String): Translator {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlkitCode)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        val client = Translation.getClient(options)

        updateNotification("번역 모델 준비 중...")
        val ready = CountDownLatch(1)
        client.downloadModelIfNeeded()
            .addOnCompleteListener { ready.countDown() }
        ready.await()
        return client
    }

    private fun readLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            synchronized(recognizerLock) {
                val rec = recognizer ?: return@synchronized
                if (rec.acceptWaveForm(buffer, read)) {
                    val text = JSONObject(rec.result).optString("text")
                    if (text.isNotBlank()) emitSubtitle(text)
                }
            }
        }
    }

    private fun emitSubtitle(recognizedText: String) {
        val t = translator
        if (t == null) {
            publishSubtitle(recognizedText)
            return
        }
        t.translate(cleanupForTranslation(recognizedText))
            .addOnSuccessListener { translated -> publishSubtitle(translated) }
            .addOnFailureListener { e ->
                Log.e(TAG, "translate failed", e)
                publishSubtitle(recognizedText)
            }
    }

    /** Vosk output has no casing/punctuation; NMT models parse sentences better with both. */
    private fun cleanupForTranslation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val capitalized = trimmed.replaceFirstChar { it.uppercase() }
        return if (capitalized.last() in ".!?") capitalized else "$capitalized."
    }

    private fun publishSubtitle(text: String) {
        Log.d(TAG, "자막: $text")
        updateNotification(text)
        overlayView?.post { overlayView?.text = text }
        onSubtitle?.invoke(text)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "실시간 자막", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveTrans")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "중지",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        tearDownSession()
        removeOverlayWindow(overlayView)
        removeOverlayWindow(floatingButton)
        closeSettingsPopup()
        super.onDestroy()
    }

    private fun removeOverlayWindow(view: View?) {
        view ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "overlay already removed", e)
        }
    }
}
