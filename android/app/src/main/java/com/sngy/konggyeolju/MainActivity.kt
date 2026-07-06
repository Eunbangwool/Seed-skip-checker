package com.sngy.konggyeolju

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var gpsTv: TextView
    private lateinit var logList: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 권한 응답 후 다시 시도
            proceedIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.status)
        gpsTv = findViewById(R.id.gps)
        logList = findViewById(R.id.logList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        logList.adapter = adapter

        findViewById<Button>(R.id.btnConnect).setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopMonitor() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
    }

    override fun onResume() {
        super.onResume()
        AppState.uiListener = { runOnUiThread { refreshUi() } }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        AppState.uiListener = null
    }

    // ---------- 권한 & 시작 ----------
    private fun requiredPerms(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p += Manifest.permission.POST_NOTIFICATIONS
        }
        return p.toTypedArray()
    }

    private fun missingPerms(): List<String> =
        requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun onConnectClicked() {
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "플로팅 창 권한을 먼저 허용하세요", Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        startMonitor()
    }

    private fun proceedIfReady() {
        if (missingPerms().isEmpty()) {
            if (Settings.canDrawOverlays(this)) startMonitor()
            else requestOverlay()
        }
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            Toast.makeText(this, "이미 허용됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMonitor() {
        val i = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "감시 시작", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitor() {
        val i = Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
        startService(i)
    }

    // ---------- UI ----------
    private fun refreshUi() {
        statusTv.text = "상태: ${AppState.conn}"
        val sp = if (AppState.speedKmh < 0) "-" else String.format(Locale.US, "%.1f km/h", AppState.speedKmh)
        val pos = if (AppState.lat.isNaN()) "-" else String.format(Locale.US, "%.6f, %.6f", AppState.lat, AppState.lng)
        gpsTv.text = "속도: $sp   위치: $pos"

        val snapshot = synchronized(AppState.skips) { AppState.skips.toList() }
        val lines = snapshot.asReversed().map { s ->
            val t = timeFmt.format(Date(s.timeMs))
            val loc = if (s.lat.isNaN()) "위치없음" else String.format(Locale.US, "%.6f,%.6f", s.lat, s.lng)
            "$t   ${s.ch}번 결주 (누락 ${s.miss})   [$loc]"
        }
        adapter.clear()
        adapter.addAll(lines)
        adapter.notifyDataSetChanged()
    }

    // ---------- CSV 내보내기 ----------
    private fun exportCsv() {
        if (AppState.skips.isEmpty()) {
            Toast.makeText(this, "저장할 결주 기록이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("시각,채널,누락개수,위도,경도\n")
        for (s in AppState.skips) {
            val lat = if (s.lat.isNaN()) "" else s.lat.toString()
            val lng = if (s.lng.isNaN()) "" else s.lng.toString()
            sb.append("${full.format(Date(s.timeMs))},${s.ch},${s.miss},$lat,$lng\n")
        }
        val name = "결주기록_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    contentResolver.openOutputStream(uri).use { it?.write(sb.toString().toByteArray()) }
                    Toast.makeText(this, "다운로드 폴더에 저장: $name", Toast.LENGTH_LONG).show()
                    return
                }
            }
            // 폴백
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, name)
            f.writeText(sb.toString())
            Toast.makeText(this, "저장: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
