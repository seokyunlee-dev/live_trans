package com.livetrans.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private val permissionRequestCode = 1001
    private val screenCaptureRequestCode = 1002
    private val overlayPermissionRequestCode = 1003

    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var languageGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        languageGroup = findViewById(R.id.languageGroup)
        findViewById<Button>(R.id.startButton).setOnClickListener { startCaptureFlow() }

        when (lastSourceLanguage()) {
            SourceLanguage.JAPANESE -> languageGroup.check(R.id.langJa)
            SourceLanguage.KOREAN -> languageGroup.check(R.id.langKo)
            else -> languageGroup.check(R.id.langEn)
        }

        CaptureService.onSubtitle = { text -> runOnUiThread { subtitleText.text = text } }
    }

    override fun onDestroy() {
        CaptureService.onSubtitle = null
        super.onDestroy()
    }

    private fun selectedLanguage(): SourceLanguage = when (languageGroup.checkedRadioButtonId) {
        R.id.langJa -> SourceLanguage.JAPANESE
        R.id.langKo -> SourceLanguage.KOREAN
        else -> SourceLanguage.ENGLISH
    }

    private fun lastSourceLanguage(): SourceLanguage? {
        val name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_KEY_SOURCE_LANG, null)
        return name?.let { runCatching { SourceLanguage.valueOf(it) }.getOrNull() }
    }

    private fun startCaptureFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequestCode)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), permissionRequestCode)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "다른 앱 위에 표시 권한 허용 후 다시 눌러주세요"
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                overlayPermissionRequestCode
            )
            return
        }
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(projectionManager.createScreenCaptureIntent(), screenCaptureRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCaptureFlow()
        } else {
            statusText.text = "권한 거부됨"
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayPermissionRequestCode) {
            if (Settings.canDrawOverlays(this)) startCaptureFlow()
            return
        }
        if (requestCode == screenCaptureRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            val lang = selectedLanguage()
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_KEY_SOURCE_LANG, lang.name).apply()
            val serviceIntent = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_SOURCE_LANG, lang.name)
            startForegroundService(serviceIntent)
            statusText.text = "캡처 중 — 유튜브/브라우저에서 영상 재생 후 알림창/자막 확인"
            finish()
        } else {
            statusText.text = "캡처 권한 거부됨"
        }
    }
}
