package com.example.jpcaptions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    
    private var partialSeq = 0

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var live: TextView
    private lateinit var scroll: ScrollView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        history = findViewById(R.id.history)
        live = findViewById(R.id.live)
        scroll = findViewById(R.id.scroll)
        toggle = findViewById(R.id.toggle)

        findViewById<Button>(R.id.clear).setOnClickListener {
            history.text = ""
            live.text = ""
        }
        toggle.setOnClickListener { if (wantListening) stopListening() else startListening() }

        // Download the Japanese->English model once (~30 MB); works offline afterwards.
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                status.text = "Ready. Tap START."
                toggle.isEnabled = true
            }
            .addOnFailureListener {
                status.text = "Model download failed. Connect to the internet and reopen the app."
            }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "No speech recognition service found. Install/enable the Google app.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        wantListening = true
        toggle.text = "STOP"
        status.text = "Listening for Japanese…"
        live.text = "…"
        beginRecognizer()
    }

    private fun stopListening() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        toggle.text = "START"
        status.text = "Stopped."
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    private fun beginRecognizer() {
        if (!wantListening) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    private fun restartSoon(delayMs: Long) {
        if (!wantListening) return
        handler.postDelayed({ beginRecognizer() }, delayMs)
    }

    private fun translate(text: String, done: (String) -> Unit) {
        translator.translate(text)
            .addOnSuccessListener { done(it) }
            .addOnFailureListener { done("[translation failed]") }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle?) {
            val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            val seq = ++partialSeq
            translate(text) { en -> if (seq == partialSeq) live.text = en }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                partialSeq++
                translate(text) { en ->
                    live.text = en
                    history.append("$text\n$en\n\n")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
            restartSoon(200)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopListening()
                    status.text = "Microphone permission denied."
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(200)
                else -> restartSoon(900)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        translator.close()
    }
}
