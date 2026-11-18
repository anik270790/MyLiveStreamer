package com.mylivestreamer.app

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val REQ_PERM = 1
        const val REQ_OVERLAY = 2
        const val REQ_CAPTURE = 3
        const val REQ_LOGO = 4
    }

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var destBox: LinearLayout
    private val rows = mutableListOf<DestRow>()
    private val ui = Handler(Looper.getMainLooper())
    private var ready = false

    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var etMbps: EditText
    private lateinit var cbAuto: CheckBox
    private lateinit var spOrient: Spinner
    private lateinit var spAudio: Spinner
    private lateinit var cbPanel: CheckBox
    private lateinit var cbCam: CheckBox
    private lateinit var spCamPos: Spinner
    private lateinit var spCamSize: Spinner
    private lateinit var spCamRot: Spinner
    private lateinit var etText: EditText
    private lateinit var spTextPos: Spinner
    private lateinit var tvLogo: TextView
    private lateinit var spLogoPos: Spinner
    private lateinit var etChat: EditText
    private lateinit var cbChatTop: CheckBox
    private lateinit var cbStats: CheckBox
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        I18n.loadOffline(this, prefs.lang)
        build()
    }

    override fun onResume() {
        super.onResume()
        ui.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(statusTick)
        if (::cbCam.isInitialized) saveAll()
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
        root.addView(this)
    }

    private fun label(text: String, parent: LinearLayout = root) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(8), 0, dp(2))
        parent.addView(this)
    }

    private fun edit(hint: String, value: String, parent: LinearLayout = root, secret: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            parent.addView(this)
        }

    private fun check(text: String, v: Boolean, parent: LinearLayout = root) = CheckBox(this).apply {
        this.text = text
        isChecked = v
        parent.addView(this)
    }

    private fun button(text: String, parent: LinearLayout = root, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            parent.addView(this)
        }

    private fun spinner(
        items: List<String>, sel: Int, parent: LinearLayout = root,
        onChange: ((Int) -> Unit)? = null
    ): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(sel.coerceIn(0, items.size - 1))
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (ready) onChange?.invoke(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        parent.addView(s)
        return s
    }

    private fun hRow(parent: LinearLayout = root) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ destination rows

    private inner class DestRow(
        val box: LinearLayout, val enabled: CheckBox, val preset: Spinner,
        val url: EditText, val key: EditText
    ) {
        fun toDest() = Dest(
            Presets.names[preset.selectedItemPosition.coerceIn(0, Presets.names.lastIndex)],
            url.text.toString(), key.text.toString(), enabled.isChecked
        )
    }

    private fun addRow(d: Dest) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        destBox.addView(box)
        val top = hRow(box)
        val en = check(t("use_it"), d.enabled, top)
        lateinit var urlEt: EditText
        val sp = spinner(Presets.names, Presets.names.indexOf(d.preset).coerceAtLeast(0), top) { pos ->
            val cur = urlEt.text.toString().trim()
            val isPreset = cur.isEmpty() || Presets.names.any { Presets.serverUrl(it) == cur }
            if (isPreset) urlEt.setText(Presets.serverUrl(Presets.names[pos]))
        }
        val rm = Button(this).apply {
            text = t("remove")
            isAllCaps = false
        }
        top.addView(rm)
        urlEt = edit(t("server_url"), d.url, box)
        val keyEt = edit(t("stream_key"), d.key, box, secret = true)
        val r2 = hRow(box)
        val row = DestRow(box, en, sp, urlEt, keyEt)
        rows += row
        button(t("paste"), r2) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!txt.isNullOrBlank()) keyEt.setText(txt.trim())
        }
        button(t("get_key"), r2) {
            val name = Presets.names[sp.selectedItemPosition.coerceIn(0, Presets.names.lastIndex)]
            val page = Presets.keyPage(name)
            if (page.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
        }
        rm.setOnClickListener {
            if (rows.size > 1) {
                rows.remove(row)
                destBox.removeView(box)
            }
        }
    }

    // ------------------------------------------------------------------ screen

    private fun build() {
        rows.clear()
        ready = false
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        TextView(this).apply {
            text = "MyLiveStreamer"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            root.addView(this)
        }

        // language
        label(t("language"))
        val codes = I18n.codes()
        spinner(codes.map { I18n.name(it) }, codes.indexOf(prefs.lang).coerceAtLeast(0)) { pos ->
            val code = codes[pos]
            if (code != prefs.lang) changeLanguage(code)
        }

        // destinations
        header(t("dests"))
        destBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(destBox)
        prefs.dests.forEach { addRow(it) }
        button(t("add_dest")) {
            addRow(Dest("Custom", "", "", true))
        }

        // quality
        header(t("quality"))
        label(t("resolution"))
        spRes = spinner(Qualities.list.map { it.label }, prefs.resIdx) { applyDefaultBitrate() }
        label(t("fps"))
        spFps = spinner(listOf("30", "60"), if (prefs.fps60) 1 else 0) { applyDefaultBitrate() }
        label(t("max_bitrate"))
        etMbps = edit("Mbps", trimFloat(prefs.mbps)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        cbAuto = check(t("auto_adapt"), prefs.autoAdapt)
        label(t("orientation"))
        spOrient = spinner(listOf(t("portrait"), t("landscape")), if (prefs.portrait) 0 else 1)
        label(t("quality_note"))

        // audio
        header(t("audio"))
        val audioItems = if (Build.VERSION.SDK_INT >= 29) {
            listOf(t("audio_mic"), t("audio_internal"), t("audio_both"))
        } else {
            listOf(t("audio_mic"))
        }
        spAudio = spinner(audioItems, prefs.audioMode)

        // controls and overlays
        header(t("controls"))
        cbPanel = check(t("panel"), prefs.panel)
        cbStats = check(t("stats"), prefs.stats)

        val positions = listOf(t("pos_tl"), t("pos_tr"), t("pos_bl"), t("pos_br"))
        cbCam = check(t("cam_overlay"), prefs.cam)
        label(t("cam_pos"))
        spCamPos = spinner(positions, prefs.camPos)
        label(t("cam_size"))
        spCamSize = spinner(listOf(t("small"), t("medium"), t("large")), prefs.camSize)
        label(t("cam_rot"))
        spCamRot = spinner(listOf("0", "90", "180", "270"), prefs.camRot)

        label(t("text_overlay"))
        etText = edit(t("text_hint"), prefs.overlayText)
        spTextPos = spinner(positions, prefs.textPos)

        label(t("logo"))
        tvLogo = label(if (prefs.logoPath.isBlank()) "-" else File(prefs.logoPath).name)
        val lr = hRow()
        button(t("pick_logo"), lr) {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_LOGO)
        }
        button(t("clear_logo"), lr) {
            prefs.logoPath = ""
            tvLogo.text = "-"
        }
        spLogoPos = spinner(positions, prefs.logoPos)

        label(t("chat"))
        etChat = edit(t("chat_hint"), prefs.chatUrl)
        cbChatTop = check(t("chat_top"), prefs.chatTop)

        // start / stop
        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(24), 0, dp(8))
            root.addView(this)
        }
        startBtn = button(t("start")) { onStartClicked() }
        stopBtn = button(t("stop")) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.A_STOP))
        }
        refreshStatus()
        ui.postDelayed({ ready = true }, 500)
    }

    private fun trimFloat(f: Float): String =
        if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private fun applyDefaultBitrate() {
        val q = Qualities.list[spRes.selectedItemPosition.coerceIn(0, Qualities.list.lastIndex)]
        val v = if (spFps.selectedItemPosition == 1) q.mbps60 else q.mbps30
        etMbps.setText(trimFloat(v))
    }

    private fun changeLanguage(code: String) {
        saveAll()
        statusView.text = t("translating")
        I18n.select(this, code, {
            prefs.lang = code
            build()
        }, {
            I18n.loadOffline(this, prefs.lang)
            toast(t("translate_fail"))
            build()
        })
    }

    private fun saveAll() {
        prefs.dests = rows.map { it.toDest() }
        prefs.resIdx = spRes.selectedItemPosition
        prefs.fps60 = spFps.selectedItemPosition == 1
        prefs.mbps = etMbps.text.toString().toFloatOrNull()?.coerceIn(0.3f, 60f) ?: prefs.mbps
        prefs.autoAdapt = cbAuto.isChecked
        prefs.portrait = spOrient.selectedItemPosition == 0
        prefs.audioMode = spAudio.selectedItemPosition
        prefs.panel = cbPanel.isChecked
        prefs.stats = cbStats.isChecked
        prefs.cam = cbCam.isChecked
        prefs.camPos = spCamPos.selectedItemPosition
        prefs.camSize = spCamSize.selectedItemPosition
        prefs.camRot = spCamRot.selectedItemPosition
        prefs.overlayText = etText.text.toString()
        prefs.textPos = spTextPos.selectedItemPosition
        prefs.logoPos = spLogoPos.selectedItemPosition
        prefs.chatUrl = etChat.text.toString()
        prefs.chatTop = cbChatTop.isChecked
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val live = StreamService.isLive()
        statusView.text = if (live) StreamService.statusText else t("idle")
        startBtn.isEnabled = !live
        stopBtn.isEnabled = live
    }

    // ------------------------------------------------------------------ start flow

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        saveAll()
        if (prefs.dests.none { it.enabled && it.url.isNotBlank() }) {
            toast(t("need_dest"))
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { !granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERM)
            return
        }
        afterPermissions()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERM) afterPermissions()
    }

    private fun afterPermissions() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            toast(t("need_mic"))
            return
        }
        val needOverlay = prefs.panel || prefs.chatUrl.isNotBlank()
        if (needOverlay && !Settings.canDrawOverlays(this)) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OVERLAY)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (!Settings.canDrawOverlays(this)) {
                    prefs.panel = false
                    prefs.chatUrl = ""
                    cbPanel.isChecked = false
                    etChat.setText("")
                    toast(t("overlay_denied"))
                }
                requestCapture()
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, StreamService::class.java)
                        .setAction(StreamService.A_START)
                        .putExtra("code", resultCode)
                        .putExtra("data", data)
                    startForegroundService(i)
                    // Go to the home screen so the phone screen shows whatever you want to stream.
                    moveTaskToBack(true)
                }
            }
            REQ_LOGO -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    runCatching {
                        val out = File(filesDir, "logo_${System.currentTimeMillis()}.png")
                        contentResolver.openInputStream(uri)?.use { inp ->
                            out.outputStream().use { inp.copyTo(it) }
                        }
                        if (prefs.logoPath.isNotBlank()) File(prefs.logoPath).delete()
                        prefs.logoPath = out.absolutePath
                        tvLogo.text = out.name
                    }
                }
            }
        }
    }
}
