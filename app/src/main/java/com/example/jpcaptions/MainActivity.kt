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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
    }

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
            else toast("Microphone/audio permission is required to capture audio.")
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

    private fun modelsReady() =
        prefs.getBoolean("ready", false) && File(filesDir, "model-ja/am").exists()

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val modelsOk = modelsReady()
        btnOverlay.text =
            if (overlayOk) "1. Display over other apps ✓" else "1. Allow display over other apps"
        btnOverlay.isEnabled = !overlayOk
        btnModels.text =
            if (modelsOk) "2. Models downloaded ✓" else "2. Download models (about 80 MB, once)"
        btnModels.isEnabled = !modelsOk && !downloading
        btnStart.isEnabled = overlayOk && modelsOk
        if (!downloading) {
            status.text = when {
                !overlayOk -> "Step 1: allow display over other apps."
                !modelsOk -> "Step 2: download the models (needs internet, once)."
                else -> "Ready. Tap START, then open your video."
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
        downloading = true
        refresh()
        Thread {
            try {
                val target = File(filesDir, "model-ja")
                if (!File(target, "am").exists()) downloadSpeechModel(target)
                runOnUiThread {
                    setStatus("Downloading translation model…")
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            prefs.edit().putBoolean("ready", true).apply()
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
                    setStatus("Download failed: ${e.message}. Check internet and retry.")
                }
            }
        }.start()
    }

    private fun downloadSpeechModel(target: File) {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong
        val zipFile = File(cacheDir, "model.zip")
        var lastPct = -1
        conn.inputStream.use { input ->
            zipFile.outputStream().use { out ->
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
                            runOnUiThread { setStatus("Downloading Japanese speech model… $pct%") }
                        }
                    }
                }
            }
        }
        runOnUiThread { setStatus("Unpacking speech model…") }
        target.deleteRecursively()
        target.mkdirs()
        val base = target.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name.substringAfter('/', "")
                if (name.isNotEmpty()) {
                    val out = File(target, name)
                    if (!out.canonicalPath.startsWith(base)) throw SecurityException("Bad zip entry")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        zipFile.delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        translator.close()
    }
}
