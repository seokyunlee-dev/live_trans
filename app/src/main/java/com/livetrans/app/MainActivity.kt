package com.livetrans.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private val permissionRequestCode = 1001
    private val screenCaptureRequestCode = 1002

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.startButton).setOnClickListener { startCaptureFlow() }
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
        if (requestCode == screenCaptureRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            startForegroundService(serviceIntent)
            statusText.text = "캡처 중 — 유튜브/브라우저에서 영상 재생 후 알림창 확인"
        } else {
            statusText.text = "캡처 권한 거부됨"
        }
    }
}
