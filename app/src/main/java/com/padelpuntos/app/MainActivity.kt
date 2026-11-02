package com.padelpuntos.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        // Se conserva al girar la pantalla
        val match = PadelMatch()
        var useFrontCamera = false
        var voiceOn = true

        const val HOLD_MS = 1000L      // tiempo que hay que mantener el gesto
        const val COOLDOWN_MS = 2500L  // pausa tras sumar un punto
        const val MIN_SCORE = 0.6f     // confianza mínima del gesto
        const val FIST = "Closed_Fist"
        const val PALM = "Open_Palm"
    }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var executor: ExecutorService
    private var recognizer: GestureRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    // Estado del detector (solo en el hilo principal)
    private var candidate: Int = -1          // 0 = A (puño), 1 = B (palma)
    private var candidateSince = 0L
    private var cooldownUntil = 0L
    private var needRelease = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "Sin permiso de cámara: usa los botones (toca un equipo para sumar)"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.holdProgress)
        executor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        findViewById<android.view.View>(R.id.teamA).setOnClickListener { addPoint(0) }
        findViewById<android.view.View>(R.id.teamB).setOnClickListener { addPoint(1) }
        findViewById<Button>(R.id.undo).setOnClickListener {
            if (match.undo()) { render(); say("Punto anulado") }
        }
        findViewById<Button>(R.id.reset).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setMessage("¿Empezar un partido nuevo?")
                .setPositiveButton("Sí") { _, _ -> match.reset(); render(); say("Nuevo partido") }
                .setNegativeButton("No", null).show()
        }
        findViewById<Button>(R.id.golden).setOnClickListener {
            match.goldenPoint = !match.goldenPoint; render()
        }
        findViewById<Button>(R.id.voice).setOnClickListener { voiceOn = !voiceOn; render() }
        findViewById<Button>(R.id.flip).setOnClickListener {
            useFrontCamera = !useFrontCamera; startCamera()
        }

        executor.execute { setupRecognizer() }
        render()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- Reconocimiento de gestos (MediaPipe) ----------

    private fun setupRecognizer() {
        try {
            val base = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> runOnUiThread { onGesture(result) } }
                .setErrorListener { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, options)
        } catch (e: Exception) {
            runOnUiThread { status.text = "No se pudo cargar el modelo: ${e.message}" }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUse, analysis)
                status.text = "✊ puño = Equipo A   ·   ✋ palma = Equipo B"
            } catch (e: Exception) {
                status.text = "Error de cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val rec = recognizer
        if (rec == null) { image.close(); return }
        val bmp: Bitmap = image.toBitmap()
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        image.close()
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        try {
            rec.recognizeAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
        } catch (_: Exception) { }
    }

    private fun onGesture(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()
        var seen = -1
        var conflict = false
        for (hand in result.gestures()) {
            val top = hand.firstOrNull() ?: continue
            if (top.score() < MIN_SCORE) continue
            val t = when (top.categoryName()) { FIST -> 0; PALM -> 1; else -> -1 }
            if (t == -1) continue
            if (seen != -1 && seen != t) conflict = true
            seen = t
        }
        if (conflict) seen = -1

        if (seen == -1) needRelease = false
        if (now < cooldownUntil || needRelease || seen == -1) {
            candidate = -1; progress.progress = 0
            return
        }
        if (seen != candidate) { candidate = seen; candidateSince = now }
        val held = now - candidateSince
        progress.progress = ((held * 100) / HOLD_MS).toInt().coerceAtMost(100)
        if (held >= HOLD_MS) {
            addPoint(candidate)
            cooldownUntil = now + COOLDOWN_MS
            needRelease = true
            candidate = -1
            progress.progress = 0
        }
    }

    // ---------- Marcador ----------

    private fun addPoint(team: Int) {
        val msg = match.point(team)
        render()
        tone.startTone(if (team == 0) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 200)
        vibrate()
        say(msg)
        Toast.makeText(this, "Punto ${if (team == 0) "A ✊" else "B ✋"}", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val s = match.state
        findViewById<TextView>(R.id.pointsA).text = match.pointLabel(0)
        findViewById<TextView>(R.id.pointsB).text = match.pointLabel(1)
        findViewById<TextView>(R.id.gamesA).text = s.games[0].toString()
        findViewById<TextView>(R.id.gamesB).text = s.games[1].toString()
        val histA = s.setHistory.joinToString("  ") { it.first.toString() }
        val histB = s.setHistory.joinToString("  ") { it.second.toString() }
        findViewById<TextView>(R.id.setsA).text = "Sets: ${s.sets[0]}" + if (histA.isNotEmpty()) "  ($histA)" else ""
        findViewById<TextView>(R.id.setsB).text = "Sets: ${s.sets[1]}" + if (histB.isNotEmpty()) "  ($histB)" else ""
        findViewById<TextView>(R.id.header).text = when {
            s.finished -> "🏆 GANA EL EQUIPO ${if (s.winner == 0) "A" else "B"}"
            s.tiebreak -> "TIE-BREAK · JUEGOS · PUNTOS"
            else -> "JUEGOS · PUNTOS"
        }
        findViewById<Button>(R.id.golden).text = "Punto oro: ${if (match.goldenPoint) "SÍ" else "NO"}"
        findViewById<Button>(R.id.voice).text = "Voz: ${if (voiceOn) "SÍ" else "NO"}"
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(150)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
            ttsReady = true
        }
    }

    private fun say(text: String) {
        if (voiceOn && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "score")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer?.close()
        tts?.shutdown()
        tone.release()
    }
}
