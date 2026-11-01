package com.example.jpcaptions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    }

    private class Item(val url: String, val dest: File, val label: String)

    private lateinit var status: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnModels: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var downloading = false

    private val prefs by lazy { getSharedPreferences("jp", MODE_PRIVATE) }

    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) askProjection()
            else toast("Audio permission is required to capture audio.")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, CaptionService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, svc)
                moveTaskToBack(true)
            } else {
                toast("Audio capture permission was denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnModels = findViewById(R.id.btnModels)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Remove the old speech model from the previous app version (frees space).
        Thread { File(filesDir, "model-ja").deleteRecursively() }.start()

        val radioReazon = findViewById<RadioButton>(R.id.radioReazon)
        val radioSmall = findViewById<RadioButton>(R.id.radioSmall)
        val radioBase = findViewById<RadioButton>(R.id.radioBase)
        when (sel()) {
            "base" -> radioBase.isChecked = true
            "small" -> radioSmall.isChecked = true
            else -> radioReazon.isChecked = true
        }
        findViewById<RadioGroup>(R.id.modelGroup).setOnCheckedChangeListener { _, id ->
            val v = when (id) {
                R.id.radioBase -> "base"
                R.id.radioSmall -> "small"
                else -> "reazon"
            }
            prefs.edit().putString("sel3", v).apply()
            refresh()
        }

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnModels.setOnClickListener { downloadModels() }
        btnStart.setOnClickListener { requestPermissionsThenStart() }
        btnStop.setOnClickListener { stopService(Intent(this, CaptionService::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun sel(): String = prefs.getString("sel3", "reazon") ?: "reazon"

    private fun itemsFor(s: String): List<Item> {
        val vad = Item(VAD_URL, File(filesDir, "silero_vad.onnx"), "voice detector")
        if (s == "reazon") {
            val dir = File(filesDir, "asr-reazon")
            val base = "https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main"
            return listOf(
                vad,
                Item("$base/tokens.txt", File(dir, "tokens.txt"), "vocabulary"),
                Item("$base/encoder-epoch-99-avg-1.int8.onnx", File(dir, "encoder.int8.onnx"), "Japanese model part 1"),
                Item("$base/decoder-epoch-99-avg-1.onnx", File(dir, "decoder.onnx"), "Japanese model part 2"),
                Item("$base/joiner-epoch-99-avg-1.int8.onnx", File(dir, "joiner.int8.onnx"), "Japanese model part 3")
            )
        }
        val dir = File(filesDir, "whisper-$s")
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$s/resolve/main/$s"
        return listOf(
            vad,
            Item("$base-tokens.txt", File(dir, "tokens.txt"), "vocabulary"),
            Item("$base-encoder.int8.onnx", File(dir, "encoder.int8.onnx"), "Whisper part 1"),
            Item("$base-decoder.int8.onnx", File(dir, "decoder.int8.onnx"), "Whisper part 2")
        )
    }

    private fun modelsReady(s: String): Boolean =
        prefs.getBoolean("mt_ready", false) &&
            itemsFor(s).all { it.dest.exists() && it.dest.length() > 0 }

    private fun refresh() {
        val s = sel()
        val overlayOk = Settings.canDrawOverlays(this)
        val modelsOk = modelsReady(s)
        btnOverlay.text =
            if (overlayOk) "1. Display over other apps ✓" else "1. Allow display over other apps"
        btnOverlay.isEnabled = !overlayOk
        btnModels.text =
            if (modelsOk) "2. Selected model downloaded ✓" else "2. Download selected model"
        btnModels.isEnabled = !modelsOk && !downloading
        btnStart.isEnabled = overlayOk && modelsOk
        if (!downloading) {
            status.text = when {
                !overlayOk -> "Step 1: allow display over other apps."
                !modelsOk -> "Step 2: download the selected model (needs internet, once)."
                else -> "Ready ($s model). Tap START, then open your video."
            }
        }
    }

    private fun setStatus(s: String) { status.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun requestPermissionsThenStart() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(need.toTypedArray())
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ---------- model download ----------
    private fun downloadModels() {
        val s = sel()
        downloading = true
        refresh()
        Thread {
            try {
                val items = itemsFor(s)
                for ((i, item) in items.withIndex()) downloadFile(item, i + 1, items.size)
                runOnUiThread {
                    setStatus("Downloading translation model…")
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            prefs.edit().putBoolean("mt_ready", true).apply()
                            downloading = false
                            refresh()
                        }
                        .addOnFailureListener {
                            downloading = false
                            refresh()
                            setStatus("Translation model download failed. Check internet and retry.")
                        }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    refresh()
                    setStatus("Download failed: ${e.message}. Check internet and tap download again.")
                }
            }
        }.start()
    }

    private fun downloadFile(item: Item, step: Int, steps: Int) {
        if (item.dest.exists() && item.dest.length() > 0) return
        item.dest.parentFile?.mkdirs()
        val part = File(item.dest.path + ".part")
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "JPCaptions/3.0")
        if (conn.responseCode != 200) {
            throw IOException("server answered ${conn.responseCode} for ${item.label}")
        }
        val total = conn.contentLengthLong
        var lastPct = -1
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            runOnUiThread {
                                setStatus("Downloading ${item.label} ($step of $steps)… $pct%")
                            }
                        }
                    }
                }
            }
        }
        if (!part.renameTo(item.dest)) throw IOException("could not save ${item.label}")
    }

    override fun onDestroy() {
        super.onDestroy()
        translator.close()
    }
}
