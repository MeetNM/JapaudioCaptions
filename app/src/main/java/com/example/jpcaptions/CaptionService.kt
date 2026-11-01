package com.example.jpcaptions

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class CaptionService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.jpcaptions.STOP"
        private const val CHANNEL_ID = "captions"
        private const val SAMPLE_RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var jpText: TextView? = null
    private var enText: TextView? = null
    private var translator: Translator? = null
    private var worker: Thread? = null

    @Volatile private var seq = 0
    private var lastPartial = ""
    private var lastPartialAt = 0L

    private val clearRunnable = Runnable {
        jpText?.text = ""
        enText?.text = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startAsForeground()

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)
            projection = p
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        showOverlay()
        worker = Thread { runPipeline() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live captions", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JP Captions is running")
            .setContentText("Showing live English captions")
            .addAction(action)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------- overlay ----------
    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 16 * dp
            }
        }
        val stop = TextView(this).apply {
            text = "✕ Stop"
            setTextColor(0xFFFF8A80.toInt())
            textSize = 13f
            gravity = Gravity.END
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            setOnClickListener { stopSelf() }
        }
        val jp = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            text = "Loading speech model…"
        }
        val en = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        root.addView(stop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(jp)
        root.addView(en)

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (90 * dp).toInt()
        }

        var lastY = 0f
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastY = e.rawY
                MotionEvent.ACTION_MOVE -> {
                    lp.y -= (e.rawY - lastY).toInt()
                    lastY = e.rawY
                    try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                }
            }
            true
        }

        try {
            wm?.addView(root, lp)
            overlay = root
            jpText = jp
            enText = en
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun ui(block: () -> Unit) { main.post { block() } }

    // ---------- audio -> text -> English ----------
    private fun runPipeline() {
        var model: Model? = null
        var rec: Recognizer? = null
        var record: AudioRecord? = null
        try {
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.JAPANESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )
            model = Model(File(filesDir, "model-ja").absolutePath)
            rec = Recognizer(model, SAMPLE_RATE.toFloat())

            val ar = buildRecord()
            if (ar == null) {
                showError("Could not capture system audio on this phone.")
                return
            }
            record = ar
            ar.startRecording()
            ui { jpText?.text = ""; enText?.text = "Listening to system audio…" }

            val buf = ByteArray(3200) // ~100 ms
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                boost(buf, n)
                if (rec.acceptWaveForm(buf, n)) {
                    val t = clean(JSONObject(rec.result).optString("text"))
                    if (t.isNotEmpty()) onFinal(t)
                } else {
                    val p = clean(JSONObject(rec.partialResult).optString("partial"))
                    if (p.isNotEmpty()) onPartial(p)
                }
            }
        } catch (e: Throwable) {
            showError("Error: ${e.message}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            rec?.close()
            model?.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(): AudioRecord? {
        val proj = projection ?: return null
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val ar = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 32000))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        return if (ar.state == AudioRecord.STATE_INITIALIZED) ar else { ar.release(); null }
    }
    private var agcGain = 1f

// Amplify quiet speech (whispers) before recognition; ignores near-silence.
private fun boost(buf: ByteArray, n: Int) {
    var peak = 1
    var i = 0
    while (i + 1 < n) {
        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
        val a = if (s < 0) -s else s
        if (a > peak) peak = a
        i += 2
    }
    if (peak >= 250) {
        val target = (20000f / peak).coerceIn(1f, 8f)
        agcGain += (target - agcGain) * 0.3f
    }
    if (agcGain < 1.05f) return
    i = 0
    while (i + 1 < n) {
        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
        val v = (s * agcGain).toInt().coerceIn(-32768, 32767)
        buf[i] = (v and 0xFF).toByte()
        buf[i + 1] = ((v shr 8) and 0xFF).toByte()
        i += 2
    }
}
    
    // Vosk's Japanese model puts spaces between words; remove them.
    private fun clean(s: String) = s.replace(" ", "").replace("\u3000", "").trim()

    private fun onPartial(t: String) {
        val now = SystemClock.uptimeMillis()
        if (t == lastPartial || now - lastPartialAt < 400) return
        lastPartial = t
        lastPartialAt = now
        val my = ++seq
        ui {
            main.removeCallbacks(clearRunnable)
            jpText?.text = t
        }
        translate(t) { en -> if (my == seq) enText?.text = en }
    }

    private fun onFinal(t: String) {
        lastPartial = ""
        val my = ++seq
        ui {
            main.removeCallbacks(clearRunnable)
            jpText?.text = t
        }
        translate(t) { en ->
            if (my == seq) {
                enText?.text = en
                main.removeCallbacks(clearRunnable)
                main.postDelayed(clearRunnable, 6000)
            }
        }
    }

    private fun translate(text: String, done: (String) -> Unit) {
        val tr = translator ?: return
        tr.translate(text)
            .addOnSuccessListener { done(it) }
            .addOnFailureListener { done("[translation failed]") }
    }

    private fun showError(msg: String) {
        ui { jpText?.text = ""; enText?.text = msg }
    }

    override fun onDestroy() {
        running = false
        try { projection?.stop() } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        translator?.close()
        super.onDestroy()
    }
}
