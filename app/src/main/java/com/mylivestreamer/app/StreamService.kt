package com.mylivestreamer.app

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
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.multiple.MultiStream
import com.pedro.library.multiple.MultiType
import com.pedro.library.util.BitrateAdapter
import com.pedro.library.util.FpsListener
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that does the real work: captures the screen, encodes ONCE and sends the
 * same stream to every selected platform (RTMP), adapts bitrate, and draws overlays.
 */
class StreamService : Service() {

    companion object {
        const val A_START = "mls.start"
        const val A_STOP = "mls.stop"
        const val A_MUTE = "mls.mute"
        const val A_CAM = "mls.cam"
        const val A_CHAT = "mls.chat"
        const val A_AUDIO = "mls.audio"
        private const val CH = "mls_stream"
        private const val NID = 4242

        @Volatile
        var instance: StreamService? = null

        @Volatile
        var statusText: String = ""

        fun isLive(): Boolean = instance?.live == true
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var stream: MultiStream? = null
    private var projection: MediaProjection? = null
    private var active: List<Dest> = emptyList()
    private var states: Array<String> = emptyArray()
    private var bps: LongArray = LongArray(0)
    private var adapter: BitrateAdapter? = null

    @Volatile
    var live = false
    private var stopping = false
    private var startTime = 0L
    private var curFps = 0
    private var targetBitrate = 0
    private var curBitrate = 0
    private var audioMode = 0
    private var muted = false
    private var frameW = 1080
    private var frameH = 1920
    private var wakeLock: PowerManager.WakeLock? = null

    private var camFilter: SurfaceFilterRender? = null
    private var textFilter: TextFilterRender? = null
    private var logoFilter: ImageFilterRender? = null
    private val camera by lazy { CameraOverlay(applicationContext) }
    private var chatView: WebView? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statsView: TextView? = null
    private var muteBtn: TextView? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val tick = object : Runnable {
        override fun run() {
            if (!live) return
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Live stream", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_START -> if (!live) startAll(intent)
            A_STOP -> stopAll()
            A_MUTE -> toggleMute()
            A_CAM -> setCamera(camFilter == null)
            A_CHAT -> toggleChat()
            A_AUDIO -> cycleAudio()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (live || stream != null) stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun startAll(i: Intent) {
        prefs = Prefs(this)
        stopping = false
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")
        goForeground()
        instance = this
        if (data == null) {
            fail("Screen capture permission missing")
            return
        }
        try {
            setup(code, data)
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun goForeground() {
        val n = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (granted(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (granted(Manifest.permission.CAMERA)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NID, n, type)
        } else {
            startForeground(NID, n)
        }
    }

    private fun setup(code: Int, data: Intent) {
        val q = Qualities.list[prefs.resIdx.coerceIn(0, Qualities.list.lastIndex)]
        val fps = if (prefs.fps60) 60 else 30
        val rotation = if (prefs.portrait) 90 else 0
        frameW = if (prefs.portrait) q.h else q.w
        frameH = if (prefs.portrait) q.w else q.h
        targetBitrate = (prefs.mbps * 1_000_000f).toInt().coerceAtLeast(300_000)
        curBitrate = targetBitrate
        audioMode = if (Build.VERSION.SDK_INT >= 29) prefs.audioMode else 0
        active = prefs.dests.filter { it.enabled && it.url.isNotBlank() }
        if (active.isEmpty()) throw IllegalStateException(t("need_dest"))
        states = Array(active.size) { "..." }
        bps = LongArray(active.size)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("Could not start screen capture")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopAll() }
            }
        }, main)

        val checkers = Array<ConnectChecker>(active.size) { Checker(it) }
        val st = MultiStream(
            applicationContext, checkers, null, null, null,
            ScreenSource(applicationContext, mp), makeAudio(audioMode, mp)
        )
        stream = st
        // The screen only produces frames when something changes, so force a steady frame rate.
        st.getGlInterface().setForceRender(true, fps)

        val okV = st.prepareVideo(q.w, q.h, targetBitrate, fps, 2, rotation)
        if (!okV) {
            throw IllegalStateException(
                "This phone cannot encode ${q.label} at $fps fps. Choose a lower quality."
            )
        }
        val okA = st.prepareAudio(44100, true, 128_000, true, true)
        if (!okA) throw IllegalStateException("Audio setup failed")

        st.setFpsListener(object : FpsListener.Callback {
            override fun onFps(fps: Int) {
                curFps = fps
            }
        })
        adapter = BitrateAdapter(object : BitrateAdapter.Listener {
            override fun onBitrateAdapted(bitrate: Int) {
                val b = max(bitrate, targetBitrate / 4)
                curBitrate = b
                stream?.setVideoBitrateOnFly(b)
            }
        }).also { it.setMaxBitrate(targetBitrate) }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mls:stream").apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        active.forEachIndexed { i, d ->
            st.getStreamClient(MultiType.RTMP, i).setReTries(200)
            st.startStream(MultiType.RTMP, i, d.endpoint())
        }
        live = true
        startTime = SystemClock.elapsedRealtime()

        applyOverlays()
        if (prefs.panel && canDraw()) buildPanel()
        if (prefs.chatUrl.isNotBlank() && canDraw()) showChat()
        main.post(tick)
    }

    private fun makeAudio(mode: Int, mp: MediaProjection): AudioSource {
        if (Build.VERSION.SDK_INT >= 29) {
            when (mode) {
                1 -> return InternalAudioSource(mp)
                2 -> return MixAudioSource(mp)
            }
        }
        return MicrophoneSource()
    }

    // ---------------------------------------------------------------- stop

    private fun stopAll() {
        if (stopping) return
        stopping = true
        live = false
        main.removeCallbacksAndMessages(null)
        removeCameraOnly()
        hideChat()
        removePanel()
        val st = stream
        if (st != null) {
            for (i in active.indices) runCatching { st.stopStream(MultiType.RTMP, i) }
            runCatching { st.stopStream() }
            runCatching { st.release() }
        }
        stream = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        statusText = ""
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopAll()
    }

    // ---------------------------------------------------------------- connection callbacks

    private inner class Checker(private val idx: Int) : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            if (idx < states.size) states[idx] = "..."
        }

        override fun onConnectionSuccess() {
            if (idx < states.size) states[idx] = "ok"
        }

        override fun onConnectionFailed(reason: String) {
            if (stopping || idx >= states.size) return
            val c = stream?.getStreamClient(MultiType.RTMP, idx)
            if (c != null && c.reTry(5000, reason, null)) {
                states[idx] = "retry"
            } else {
                states[idx] = "fail"
                Toast.makeText(
                    applicationContext, "${active[idx].preset}: $reason", Toast.LENGTH_LONG
                ).show()
                runCatching { stream?.stopStream(MultiType.RTMP, idx) }
                if (states.all { it == "fail" }) stopAll()
            }
        }

        override fun onDisconnect() {
            if (idx < states.size && states[idx] != "fail" && states[idx] != "retry") states[idx] = "off"
        }

        override fun onAuthError() {
            if (idx < states.size) states[idx] = "auth"
        }

        override fun onAuthSuccess() {}

        override fun onNewBitrate(bitrate: Long) {
            if (idx >= bps.size) return
            bps[idx] = bitrate
            // Only the first connected destination drives the automatic quality control.
            val lead = states.indexOfFirst { it == "ok" }
            if (idx == lead && prefs.autoAdapt) {
                val congested = active.indices.any {
                    runCatching { stream?.getStreamClient(MultiType.RTMP, it)?.hasCongestion() == true }
                        .getOrDefault(false)
                }
                adapter?.adaptBitrate(bitrate, congested)
            }
        }
    }

    // ---------------------------------------------------------------- overlays on the stream

    private fun pos(i: Int): TranslateTo = when (i) {
        0 -> TranslateTo.TOP_LEFT
        1 -> TranslateTo.TOP_RIGHT
        2 -> TranslateTo.BOTTOM_LEFT
        else -> TranslateTo.BOTTOM_RIGHT
    }

    private fun applyOverlays() {
        val gl = stream?.getGlInterface() ?: return

        val logo = prefs.logoPath
        if (logo.isNotBlank()) {
            val bmp = BitmapFactory.decodeFile(logo)
            if (bmp != null) {
                val f = ImageFilterRender()
                gl.addFilter(f)
                f.setImage(bmp)
                val sy = 9f
                val sx = min(40f, sy * frameH / frameW * bmp.width / bmp.height)
                f.setScale(sx, sy)
                f.setPosition(pos(prefs.logoPos))
                logoFilter = f
            }
        }

        val txt = prefs.overlayText.trim()
        if (txt.isNotEmpty()) {
            val f = TextFilterRender()
            gl.addFilter(f)
            f.setText(txt, 40f, Color.WHITE, 0x88000000.toInt())
            val sy = 5f
            val ratio = (txt.length * 0.55f + 1f) / 1.4f
            val sx = min(95f, sy * frameH / frameW * ratio)
            f.setScale(sx, sy)
            f.setPosition(pos(prefs.textPos))
            textFilter = f
        }

        if (prefs.cam) setCamera(true)
    }

    private fun setCamera(on: Boolean) {
        val gl = stream?.getGlInterface() ?: return
        if (on) {
            if (camFilter != null) return
            if (!granted(Manifest.permission.CAMERA)) {
                Toast.makeText(this, "Camera permission not given", Toast.LENGTH_SHORT).show()
                return
            }
            val f = SurfaceFilterRender { st -> camera.start(st) }
            gl.addFilter(f)
            val wp = when (prefs.camSize) {
                0 -> 18f
                1 -> 26f
                else -> 36f
            }
            f.setScale(wp, wp * frameW / frameH)
            f.rotation = intArrayOf(0, 90, 180, 270)[prefs.camRot.coerceIn(0, 3)]
            f.setPosition(pos(prefs.camPos))
            camFilter = f
        } else {
            camera.stop()
            camFilter?.let { runCatching { gl.removeFilter(it) } }
            camFilter = null
        }
    }

    private fun removeCameraOnly() {
        camera.stop()
        camFilter = null
    }

    // ---------------------------------------------------------------- audio controls

    private fun toggleMute() {
        val a = stream?.audioSource ?: return
        muted = !muted
        if (a is MicrophoneSource) {
            if (muted) a.mute() else a.unMute()
        } else if (Build.VERSION.SDK_INT >= 29 && a is MixAudioSource) {
            if (muted) a.mute() else a.unMute()
        } else {
            muted = false
        }
        muteBtn?.text = if (muted) "\uD83D\uDD07" else "\uD83C\uDFA4"
    }

    private fun cycleAudio() {
        val mp = projection ?: return
        val st = stream ?: return
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Phone sound needs Android 10+", Toast.LENGTH_SHORT).show()
            return
        }
        val next = (audioMode + 1) % 3
        try {
            st.changeAudioSource(makeAudio(next, mp))
            audioMode = next
            muted = false
            muteBtn?.text = "\uD83C\uDFA4"
            val names = listOf(t("audio_mic"), t("audio_internal"), t("audio_both"))
            Toast.makeText(this, names[next], Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Audio switch failed", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- floating panel + chat window

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildPanel() {
        if (panelView != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(0xAA000000.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

        val handle = btn("\u2807") {}
        val stop = btn("\u25A0") { stopAll() }
        stop.setTextColor(0xFFFF5252.toInt())
        val mute = btn("\uD83C\uDFA4") { toggleMute() }
        muteBtn = mute
        val audio = btn("\uD83D\uDD0A") { cycleAudio() }
        val cam = btn("\uD83D\uDCF7") { setCamera(camFilter == null) }
        val chat = btn("\uD83D\uDCAC") { toggleChat() }
        val stats = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = if (prefs.stats) View.VISIBLE else View.GONE
        }
        statsStore(stats)
        val statBtn = btn("\uD83D\uDCCA") {
            stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val others = listOf(stop, mute, audio, cam, chat, statBtn)
        val collapse = btn("\u2013") {}
        collapse.setOnClickListener {
            val hide = others.first().visibility == View.VISIBLE
            others.forEach { it.visibility = if (hide) View.GONE else View.VISIBLE }
            if (hide) stats.visibility = View.GONE
            collapse.text = if (hide) "+" else "\u2013"
        }
        row.addView(handle)
        others.forEach { row.addView(it) }
        row.addView(collapse)
        box.addView(row)
        box.addView(stats)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(90)
        }
        handle.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0
            var iy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ix + (e.rawX - tx).toInt()
                        lp.y = iy + (e.rawY - ty).toInt()
                        runCatching { wm.updateViewLayout(box, lp) }
                    }
                }
                return true
            }
        })
        wm.addView(box, lp)
        panelView = box
        panelParams = lp
    }

    private fun statsStore(v: TextView) {
        statsView = v
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        statsView = null
        muteBtn = null
    }

    private fun chatUrl(s: String): String? {
        val x = s.trim()
        if (x.isEmpty()) return null
        val yt = Regex("(?:v=|youtu\\.be/|live/)([A-Za-z0-9_-]{11})").find(x)?.groupValues?.get(1)
        if (yt != null) return "https://www.youtube.com/live_chat?is_popout=1&v=$yt"
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(x)) {
            return "https://www.youtube.com/live_chat?is_popout=1&v=$x"
        }
        return if (x.startsWith("http")) x else "https://$x"
    }

    private fun toggleChat() {
        if (chatView != null) hideChat() else showChat()
    }

    private fun showChat() {
        if (chatView != null || !canDraw()) return
        val url = chatUrl(prefs.chatUrl) ?: return
        val w = WebView(applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.setBackgroundColor(0xCC000000.toInt())
        w.webViewClient = WebViewClient()
        w.loadUrl(url)
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(),
            (dm.heightPixels * 0.28f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.chatTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        runCatching { wm.addView(w, lp) }.onSuccess { chatView = w }
    }

    private fun hideChat() {
        chatView?.let {
            runCatching { wm.removeView(it) }
            runCatching { it.destroy() }
        }
        chatView = null
    }

    // ---------------------------------------------------------------- status + notification

    private fun icon(s: String) = when (s) {
        "ok" -> "\u2713"
        "retry" -> "\u21BB"
        "fail" -> "\u2717"
        "auth" -> "\uD83D\uDD12"
        "off" -> "-"
        else -> "\u2026"
    }

    private fun updateStatus() {
        val secs = (SystemClock.elapsedRealtime() - startTime) / 1000
        var up = 0L
        bps.forEach { up += it }
        var drops = 0L
        for (i in active.indices) {
            drops += runCatching {
                stream?.getStreamClient(MultiType.RTMP, i)?.getDroppedVideoFrames() ?: 0L
            }.getOrDefault(0L)
        }
        val line1 = String.format(
            java.util.Locale.US,
            "LIVE %d:%02d | %d fps | up %.1f Mbps | video %.1f Mbps | drops %d",
            secs / 60, secs % 60, curFps, up / 1_000_000.0, curBitrate / 1_000_000.0, drops
        )
        val line2 = active.indices.joinToString("   ") { "${active[it].preset} ${icon(states[it])}" }
        statusText = "$line1\n$line2"
        statsView?.text = statusText
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NID, buildNotification(line1 + "  " + line2))
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, StreamService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MyLiveStreamer")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", actionIntent(A_STOP, 1))
            .addAction(0, "Mute", actionIntent(A_MUTE, 2))
            .addAction(0, "Camera", actionIntent(A_CAM, 3))
            .build()
    }
}
