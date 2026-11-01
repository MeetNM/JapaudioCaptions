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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class CaptionService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.jpcaptions.STOP"
        private const val CHANNEL_ID = "captions"
        private const val SAMPLE_RATE = 16000
        private val STOP = FloatArray(0)
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
    private var agcGain = 1f

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
        val prefs = getSharedPreferences("jp", MODE_PRIVATE)
        var textSp = prefs.getFloat("capsize", 22f)
        var showJp = prefs.getBoolean("showjp", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (110 * dp).toInt()
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xD9000000.toInt())
                cornerRadius = 16 * dp
            }
        }
        val jp = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            text = "Loading Whisper model…"
        }
        val en = TextView(this).apply {
            setTextColor(Color.WHITE)
        }

        fun applySizes() {
            en.textSize = textSp
            jp.textSize = (textSp * 0.65f).coerceAtLeast(11f)
            jp.visibility = if (showJp) View.VISIBLE else View.GONE
        }
        applySizes()

        fun chip(label: String, color: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(color)
                textSize = 15f
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener { onClick() }
            }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        top.addView(chip("A−", Color.WHITE) {
            textSp = (textSp - 2f).coerceAtLeast(12f)
            prefs.edit().putFloat("capsize", textSp).apply()
            applySizes()
        })
        top.addView(chip("A+", Color.WHITE) {
            textSp = (textSp + 2f).coerceAtMost(44f)
            prefs.edit().putFloat("capsize", textSp).apply()
            applySizes()
        })
        top.addView(chip("JP", 0xFF9CCBFF.toInt()) {
            showJp = !showJp
            prefs.edit().putBoolean("showjp", showJp).apply()
            applySizes()
        })
        top.addView(chip("✕ Stop", 0xFFFF8A80.toInt()) { stopSelf() })

        root.addView(top, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(jp)
        root.addView(en)

        // Full width (minus a small margin) so it also fits when the phone is rotated.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            horizontalMargin = 0.03f
            y = (60 * dp).toInt()
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

    // ---------- audio -> speech detection -> Whisper -> English ----------
    private fun runPipeline() {
        var vad: Vad? = null
        var rec: OfflineRecognizer? = null
        var record: AudioRecord? = null
        var decodeThread: Thread? = null
        val queue = LinkedBlockingQueue<FloatArray>()
        try {
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.JAPANESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )

            val sel = getSharedPreferences("jp", MODE_PRIVATE).getString("sel", "small") ?: "small"
            val dir = File(filesDir, "whisper-$sel")
            val whisper = OfflineWhisperModelConfig(
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.int8.onnx").absolutePath,
                language = "ja",
                task = "transcribe",
                tailPaddings = 1000
            )
            val modelCfg = OfflineModelConfig(
                whisper = whisper,
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper"
            )
            val recCfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelCfg,
                decodingMethod = "greedy_search"
            )
            val r = OfflineRecognizer(null, recCfg)
            rec = r

            // Sensitive settings so quiet speech and whispers are still picked up.
            val vadCfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(filesDir, "silero_vad.onnx").absolutePath,
                    threshold = 0.35f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                    maxSpeechDuration = 8f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            val v = Vad(null, vadCfg)
            vad = v

            decodeThread = Thread { decodeLoop(r, queue) }.also { it.start() }

            val ar = buildRecord()
            if (ar == null) {
                showError("Could not capture system audio on this phone.")
                return
            }
            record = ar
            ar.startRecording()
            ui { jpText?.text = ""; enText?.text = "Listening to system audio…" }

            val buf = ByteArray(3200) // ~100 ms
            var speaking = false
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                v.acceptWaveform(pcmToFloat(buf, n))

                val sp = v.isSpeechDetected()
                if (sp && !speaking) {
                    ui { if (jpText?.text.isNullOrEmpty()) jpText?.text = "…" }
                }
                speaking = sp

                while (!v.empty()) {
                    val seg = v.front()
                    v.pop()
                    queue.offer(seg.samples)
                }
            }
        } catch (e: Throwable) {
            showError("Error: ${e.message}")
        } finally {
            queue.clear()
            queue.offer(STOP)
            try { decodeThread?.join() } catch (_: InterruptedException) {}
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            vad?.release()
            rec?.release()
        }
    }

    // Runs Whisper on finished speech segments. If the phone falls behind, old segments are skipped.
    private fun decodeLoop(r: OfflineRecognizer, queue: LinkedBlockingQueue<FloatArray>) {
        try {
            while (true) {
                var seg = queue.take()
                if (seg === STOP) return
                while (queue.size > 2) {
                    val next = queue.poll() ?: break
                    if (next === STOP) return
                    seg = next
                }
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(seg, SAMPLE_RATE)
                    r.decode(stream)
                    val text = r.getResult(stream).text.trim()
                    if (text.any { it.isLetterOrDigit() }) onFinal(text)
                } finally {
                    stream.release()
                }
            }
        } catch (e: Throwable) {
            showError("Error: ${e.message}")
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

    // Converts 16-bit audio to floats and boosts quiet speech (whispers); near-silence is not boosted.
    private fun pcmToFloat(buf: ByteArray, n: Int): FloatArray {
        val count = n / 2
        val out = FloatArray(count)
        var peak = 1
        for (i in 0 until count) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt()
            out[i] = s.toFloat()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        if (peak >= 250) {
            val target = (20000f / peak).coerceIn(1f, 8f)
            agcGain = if (target < agcGain) target else agcGain + (target - agcGain) * 0.05f
        }
        for (i in 0 until count) {
            out[i] = (out[i] * agcGain).coerceIn(-32768f, 32767f) / 32768f
        }
        return out
    }

    private fun onFinal(t: String) {
        val my = ++seq
        ui {
            main.removeCallbacks(clearRunnable)
            jpText?.text = t
            enText?.text = ""
        }
        translate(t) { en ->
            if (my == seq) {
                enText?.text = en
                main.removeCallbacks(clearRunnable)
                main.postDelayed(clearRunnable, 8000)
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
