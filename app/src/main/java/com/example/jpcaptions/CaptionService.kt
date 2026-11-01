package com.example.jpcaptions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
        private val STOP_JOB = Job(FloatArray(0), true, -1)
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
    @Volatile private var decoding = false
    @Volatile private var finalizedId = -1
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
            text = "Loading speech model…"
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

    // ---------- audio -> speech detection -> speech recognition -> English ----------
    private class Cap(val record: AudioRecord, val rate: Int, val channels: Int)

    private fun runPipeline() {
        var vad: Vad? = null
        var rec: OfflineRecognizer? = null
        var cap: Cap? = null
        var decodeThread: Thread? = null
        val queue = LinkedBlockingQueue<Job>()
        try {
            // 1) Start capturing system audio FIRST, while the screen-share permission is fresh.
            val errors = StringBuilder()
            val c = buildRecord(errors)
            if (c == null) {
                val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                showError(
                    "Could not capture system audio.\n$errors\n" +
                        "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "audio permission: $mic"
                )
                return
            }
            cap = c
            c.record.startRecording()
            if (c.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                showError("System audio capture did not start (${c.rate} Hz, ${c.channels} ch).")
                return
            }

            // 2) Load translation + speech model + voice detector (takes a few seconds).
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.JAPANESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )

            val sel = getSharedPreferences("jp", MODE_PRIVATE).getString("sel3", "reazon") ?: "reazon"
            val isReazon = sel == "reazon"
            val dir = File(filesDir, if (isReazon) "asr-reazon" else "whisper-$sel")
            val tokens = File(dir, "tokens.txt").absolutePath
            val modelCfg = if (isReazon) {
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath
                    ),
                    tokens = tokens,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "transducer"
                )
            } else {
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        language = "ja",
                        task = "transcribe",
                        tailPaddings = 1000
                    ),
                    tokens = tokens,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
            }
            val recCfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelCfg,
                decodingMethod = "greedy_search"
            )
            val r = OfflineRecognizer(null, recCfg)
            rec = r

            // Sensitive settings so quiet speech is still picked up. Shorter pauses and chunks
            // with the fast Japanese model make captions appear sooner.
            val vadCfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(filesDir, "silero_vad.onnx").absolutePath,
                    threshold = 0.35f,
                    minSilenceDuration = if (isReazon) 0.3f else 0.45f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                    maxSpeechDuration = if (isReazon) 6f else 8f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            val v = Vad(null, vadCfg)
            vad = v

            finalizedId = -1
            decodeThread = Thread { decodeLoop(r, queue) }.also { it.start() }
            ui { jpText?.text = ""; enText?.text = "Listening to system audio…" }

            val buf = ByteArray(c.rate * c.channels * 2 / 10) // ~100 ms
            val resampler = Resampler(c.rate, SAMPLE_RATE)
            var speaking = false

            // Live interim captions while someone is still talking (fast model only).
            val speechBuf = FloatArray(SAMPLE_RATE * 8)
            var bufLen = 0
            var lastInterimAt = 0L
            var segId = 0

            while (running) {
                val n = c.record.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val samples = resampler.process(toMono(buf, n, c.channels))
                if (samples.isEmpty()) continue
                agc(samples)
                v.acceptWaveform(samples)

                val sp = v.isSpeechDetected()
                if (sp && !speaking) {
                    ui { if (jpText?.text.isNullOrEmpty()) jpText?.text = "…" }
                }
                speaking = sp

                if (isReazon && sp) {
                    if (bufLen + samples.size > speechBuf.size) {
                        val drop = bufLen + samples.size - speechBuf.size
                        System.arraycopy(speechBuf, drop, speechBuf, 0, bufLen - drop)
                        bufLen -= drop
                    }
                    System.arraycopy(samples, 0, speechBuf, bufLen, samples.size)
                    bufLen += samples.size
                    val now = SystemClock.uptimeMillis()
                    if (now - lastInterimAt >= 700 && bufLen >= SAMPLE_RATE / 2 &&
                        !decoding && queue.isEmpty()
                    ) {
                        lastInterimAt = now
                        queue.offer(Job(speechBuf.copyOf(bufLen), false, segId))
                    }
                }

                while (!v.empty()) {
                    val seg = v.front()
                    v.pop()
                    queue.offer(Job(seg.samples, true, segId))
                    segId++
                    bufLen = 0
                }
            }
        } catch (e: Throwable) {
            showError("Error: ${e.message}")
        } finally {
            queue.clear()
            queue.offer(STOP_JOB)
            try { decodeThread?.join() } catch (_: InterruptedException) {}
            try { cap?.record?.stop() } catch (_: Exception) {}
            cap?.record?.release()
            vad?.release()
            rec?.release()
        }
    }

    // Runs speech recognition on audio jobs. Interim jobs are skipped when newer audio is waiting;
    // if the phone falls behind on finished sentences, older ones are skipped.
    private fun decodeLoop(r: OfflineRecognizer, queue: LinkedBlockingQueue<Job>) {
        try {
            while (true) {
                var job = queue.take()
                if (job === STOP_JOB) return
                if (!job.final) {
                    if (queue.isNotEmpty() || job.id <= finalizedId) continue
                } else {
                    while (queue.size > 2) {
                        val next = queue.poll() ?: break
                        if (next === STOP_JOB) return
                        if (next.final) job = next
                    }
                }
                decoding = true
                try {
                    val stream = r.createStream()
                    try {
                        stream.acceptWaveform(job.samples, SAMPLE_RATE)
                        r.decode(stream)
                        val text = r.getResult(stream).text.trim()
                        if (job.final) finalizedId = job.id
                        if (text.any { it.isLetterOrDigit() }) {
                            if (job.final || job.id > finalizedId) onFinal(text)
                        }
                    } finally {
                        stream.release()
                    }
                } finally {
                    decoding = false
                }
            }
        } catch (e: Throwable) {
            showError("Error: ${e.message}")
        }
    }

    // Tries several audio formats, because phones differ in what system-audio capture allows.
    @SuppressLint("MissingPermission")
    private fun buildRecord(errors: StringBuilder): Cap? {
        val proj = projection
        if (proj == null) {
            errors.append("no screen-share permission; ")
            return null
        }
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val tries = listOf(16000 to 1, 48000 to 1, 44100 to 1, 48000 to 2, 44100 to 2, 16000 to 2)
        for ((rate, ch) in tries) {
            try {
                val mask = if (ch == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    errors.append("$rate Hz/$ch ch: unsupported; ")
                    continue
                }
                val ar = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(maxOf(minBuf, rate * ch * 2))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (ar.state == AudioRecord.STATE_INITIALIZED) return Cap(ar, rate, ch)
                ar.release()
                errors.append("$rate Hz/$ch ch: not initialized; ")
            } catch (e: Exception) {
                errors.append("$rate Hz/$ch ch: ${e.javaClass.simpleName} ${e.message}; ")
            }
        }
        return null
    }

    // Converts 16-bit audio to mono floats (values in the 16-bit range).
    private fun toMono(buf: ByteArray, n: Int, ch: Int): FloatArray {
        val frames = n / (2 * ch)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val i = (f * ch + c) * 2
                sum += ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            }
            out[f] = sum.toFloat() / ch
        }
        return out
    }

    // Boosts quiet speech (whispers); near-silence is not boosted. Output is scaled to -1..1.
    private fun agc(x: FloatArray) {
        var peak = 1f
        for (v in x) {
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
        }
        if (peak >= 250f) {
            val target = (20000f / peak).coerceIn(1f, 8f)
            agcGain = if (target < agcGain) target else agcGain + (target - agcGain) * 0.05f
        }
        for (i in x.indices) {
            x[i] = (x[i] * agcGain).coerceIn(-32768f, 32767f) / 32768f
        }
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

// Simple streaming resampler (e.g. 48000 Hz -> 16000 Hz) using linear interpolation.
private class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev = 0f

    fun process(x: FloatArray): FloatArray {
        if (inRate == outRate) return x
        val n = x.size
        if (n == 0) return x
        val out = FloatArray((n / step).toInt() + 3)
        var k = 0
        while (pos < n - 1) {
            val i = Math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else x[i]
            val b = x[i + 1]
            out[k++] = a + (b - a) * frac
            pos += step
        }
        pos -= n
        prev = x[n - 1]
        return out.copyOf(k)
    }
}

private class Job(val samples: FloatArray, val final: Boolean, val id: Int)
