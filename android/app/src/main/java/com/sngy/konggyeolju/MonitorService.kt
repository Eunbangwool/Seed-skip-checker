package com.sngy.konggyeolju

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_ACK = "ack"

        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1
        private const val DEV_NAME = "KongGyeolju"

        private val NUS_SVC = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // BLE
    private var adapter: BluetoothAdapter? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private val rxBuf = StringBuilder()

    // 알람
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alarming = false

    // 오버레이
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var ovTitle: TextView? = null
    private var ovChans: TextView? = null

    // 위치
    private var locMgr: LocationManager? = null

    // ==================== 생명주기 ====================
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "konggyeolju:wl").apply { acquire() }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { cleanupAndStop(); return START_NOT_STICKY }
            ACTION_ACK -> { stopAlarm(); updateOverlayNormal(); return START_STICKY }
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        startForegroundSafe()
        showOverlay()
        startBle()
        startLocation()
    }

    override fun onDestroy() {
        cleanupAndStop()
        super.onDestroy()
    }

    private fun cleanupAndStop() {
        stopAlarm()
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        removeOverlay()
        try { locMgr?.removeUpdates(locListener) } catch (_: Exception) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ==================== 포그라운드 알림 ====================
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "결주 감시", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("콩 결주 감시 중")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    private fun startForegroundSafe() {
        val n = buildNotif("센서 연결 대기 중…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(s: String) {
        AppState.conn = s
        AppState.notifyUi()
        main.post {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotif(s))
            } catch (_: Exception) {}
            updateOverlayNormal()
        }
    }

    // ==================== BLE ====================
    @SuppressLint("MissingPermission")
    private fun startBle() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = bm?.adapter
        if (adapter == null || adapter?.isEnabled != true) {
            setStatus("블루투스 꺼짐 — 켜주세요")
            return
        }
        if (!hasBt()) { setStatus("블루투스 권한 없음"); return }
        startScan()
    }

    private fun hasBt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        setStatus("센서 검색 중…")
        val filter = ScanFilter.Builder().setDeviceName(DEV_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            stopScan()
            connect(dev)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("검색 실패($errorCode) — 재시도")
            main.postDelayed({ startScan() }, 3000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        setStatus("연결 중…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("연결됨 — 서비스 탐색")
                main.post { try { g.discoverServices() } catch (_: Exception) {} }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("연결 끊김 — 재검색")
                try { g.close() } catch (_: Exception) {}
                gatt = null
                main.postDelayed({ startScan() }, 2000L)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SVC)
            val tx = svc?.getCharacteristic(NUS_TX)
            if (tx == null) { setStatus("서비스 없음"); return }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCCD)
            if (d != null) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            setStatus("감시 중")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onBytes(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            onBytes(value)
        }
    }

    private fun onBytes(bytes: ByteArray) {
        rxBuf.append(String(bytes))
        var idx = rxBuf.indexOf("\n")
        while (idx >= 0) {
            val line = rxBuf.substring(0, idx).trim()
            rxBuf.delete(0, idx + 1)
            if (line.isNotEmpty()) parseLine(line)
            idx = rxBuf.indexOf("\n")
        }
    }

    private fun parseLine(line: String) {
        try {
            val o = JSONObject(line)
            when (o.optString("t")) {
                "stat" -> {
                    val s = o.optJSONArray("s"); val k = o.optJSONArray("k"); val m = o.optJSONArray("m")
                    for (i in 0 until AppState.NCH) {
                        if (s != null && i < s.length()) AppState.seed[i] = s.getInt(i)
                        if (k != null && i < k.length()) AppState.skip[i] = k.getInt(i)
                        if (m != null && i < m.length()) AppState.missed[i] = m.getInt(i)
                    }
                    AppState.notifyUi()
                    main.post { updateOverlayNormal() }
                }
                "skip" -> {
                    val ch = o.optInt("ch")
                    val miss = o.optInt("miss", 1)
                    onSkip(ch, miss)
                }
            }
        } catch (_: Exception) { /* 깨진 줄 무시 */ }
    }

    private fun onSkip(ch: Int, miss: Int) {
        val rec = AppState.Skip(System.currentTimeMillis(), ch, miss, AppState.lat, AppState.lng)
        synchronized(AppState.skips) { AppState.skips.add(rec) }
        AppState.notifyUi()
        main.post {
            triggerAlarm()
            showAlarmOverlay(ch, miss)
        }
    }

    // ==================== 알람 ====================
    private fun triggerAlarm() {
        if (alarming) return
        alarming = true
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(this@MonitorService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 300), 0)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        alarming = false
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    // ==================== 오버레이 ====================
    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showOverlay() {
        if (overlay != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD0F1720"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        ovTitle = TextView(this).apply {
            text = "콩 결주 감시"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        ovChans = TextView(this).apply {
            text = "연결 대기…"
            setTextColor(Color.parseColor("#9FB0C0"))
            textSize = 16f
        }
        root.addView(ovTitle)
        root.addView(ovChans)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8); lp.y = dp(120)

        attachDragAndTap(root, lp)
        try { wm?.addView(root, lp) } catch (_: Exception) { return }
        overlay = root
    }

    private fun attachDragAndTap(v: View, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { // 탭 = 알람 확인
                        stopAlarm(); updateOverlayNormal()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOverlayNormal() {
        val v = overlay ?: return
        v.setBackgroundColor(Color.parseColor("#DD0F1720"))
        ovTitle?.setTextColor(Color.WHITE)
        ovTitle?.text = "콩 결주 감시 · ${AppState.conn}"
        val sb = StringBuilder()
        for (i in 0 until AppState.NCH) {
            sb.append("${i + 1}:").append(AppState.skip[i])
            if (i < AppState.NCH - 1) sb.append("  ")
        }
        ovChans?.setTextColor(Color.parseColor("#9FB0C0"))
        ovChans?.text = "결주  $sb"
    }

    private fun showAlarmOverlay(ch: Int, miss: Int) {
        val v = overlay ?: return
        v.setBackgroundColor(Color.parseColor("#EEE23C3C"))
        ovTitle?.setTextColor(Color.WHITE)
        ovTitle?.text = "⚠ ${ch}번 골 결주!"
        ovChans?.setTextColor(Color.WHITE)
        ovChans?.text = "누락 ${miss}개 · 탭하면 알람 정지"
    }

    private fun removeOverlay() {
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
    }

    // ==================== 위치 (RTK 결주좌표) ====================
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
        } catch (_: Exception) {}
        try {
            locMgr?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locListener)
        } catch (_: Exception) {}
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            AppState.lat = loc.latitude
            AppState.lng = loc.longitude
            AppState.speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else AppState.speedKmh
            AppState.notifyUi()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
}
