package com.padelpuntos.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        // Se conserva al girar la pantalla
        val match = PadelMatch()
        var useFrontCamera = false
        var voiceOn = true
        var armMode = false            // false = puño/palma, true = brazo izquierdo/derecho
        var prefsLoaded = false

        const val HOLD_MS = 1000L      // tiempo que hay que mantener el gesto
        const val COOLDOWN_MS = 2500L  // pausa tras sumar un punto
        const val MIN_SCORE = 0.6f     // confianza mínima del gesto de mano
        const val MIN_VIS = 0.5f       // visibilidad mínima de muñeca/hombro
        const val FIST = "Closed_Fist"
        const val PALM = "Open_Palm"

        // Índices de MediaPipe Pose (izquierda/derecha del propio jugador)
        const val NOSE = 0
        const val L_SHOULDER = 11
        const val R_SHOULDER = 12
        const val L_WRIST = 15
        const val R_WRIST = 16
    }

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var executor: ExecutorService
    private var gestureRec: GestureRecognizer? = null
    private var poseRec: PoseLandmarker? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    // Estado del detector (solo en el hilo principal)
    private var candidate = -1
    private var candidateSince = 0L
    private var cooldownUntil = 0L
    private var needRelease = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "Sin permiso de cámara: toca la franja de cada equipo para sumar"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!prefsLoaded) { loadPrefs(); prefsLoaded = true }

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.holdProgress)
        executor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        findViewById<View>(R.id.teamA).setOnClickListener { addPoint(0) }
        findViewById<View>(R.id.teamB).setOnClickListener { addPoint(1) }
        findViewById<Button>(R.id.undo).setOnClickListener {
            if (match.undo()) { render(); say("Punto anulado") }
        }
        findViewById<Button>(R.id.reset).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("¿Empezar un partido nuevo?")
                .setPositiveButton("Sí") { _, _ -> match.reset(); render(); say("Nuevo partido") }
                .setNegativeButton("No", null).show()
        }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.voice).setOnClickListener { voiceOn = !voiceOn; savePrefs(); render() }
        findViewById<Button>(R.id.flip).setOnClickListener {
            useFrontCamera = !useFrontCamera; startCamera()
        }

        executor.execute { setupDetector() }
        render()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- Ajustes ----------

    private fun loadPrefs() {
        val p = getSharedPreferences("padel", Context.MODE_PRIVATE)
        match.names[0] = p.getString("nameA", "Equipo A")!!
        match.names[1] = p.getString("nameB", "Equipo B")!!
        match.goldenPoint = p.getBoolean("golden", false)
        armMode = p.getBoolean("armMode", false)
        voiceOn = p.getBoolean("voice", true)
    }

    private fun savePrefs() {
        getSharedPreferences("padel", Context.MODE_PRIVATE).edit()
            .putString("nameA", match.names[0])
            .putString("nameB", match.names[1])
            .putBoolean("golden", match.goldenPoint)
            .putBoolean("armMode", armMode)
            .putBoolean("voice", voiceOn)
            .apply()
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        fun field(v: String) = EditText(this).apply {
            setText(v); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val nameA = field(match.names[0])
        val nameB = field(match.names[1])
        val rbHand = RadioButton(this).apply { id = View.generateViewId(); text = "✊ Puño = A   ·   ✋ Palma = B" }
        val rbArm = RadioButton(this).apply { id = View.generateViewId(); text = "Brazo izquierdo arriba = A   ·   Brazo derecho arriba = B" }
        val group = RadioGroup(this).apply { addView(rbHand); addView(rbArm); check(if (armMode) rbArm.id else rbHand.id) }
        val golden = CheckBox(this).apply { text = "Punto de oro (sin ventajas)"; isChecked = match.goldenPoint }

        box.addView(label("Nombre del equipo A")); box.addView(nameA)
        box.addView(label("Nombre del equipo B")); box.addView(nameB)
        box.addView(label("Cómo se marca el punto")); box.addView(group)
        box.addView(golden)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                match.names[0] = nameA.text.toString().trim().ifEmpty { "Equipo A" }
                match.names[1] = nameB.text.toString().trim().ifEmpty { "Equipo B" }
                match.goldenPoint = golden.isChecked
                val newArm = group.checkedRadioButtonId == rbArm.id
                if (newArm != armMode) {
                    armMode = newArm
                    resetDetectorState()
                    executor.execute { setupDetector() }
                }
                savePrefs()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Detección (MediaPipe) ----------

    /** Se ejecuta siempre en el hilo del analizador, así no choca con el análisis de imágenes. */
    private fun setupDetector() {
        gestureRec?.close(); gestureRec = null
        poseRec?.close(); poseRec = null
        try {
            if (armMode) {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setNumPoses(2)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { r: PoseLandmarkerResult, _ -> val t = armsToTeam(r); runOnUiThread { onDetection(t) } }
                    .setErrorListener { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
                    .build()
                poseRec = PoseLandmarker.createFromOptions(this, opts)
            } else {
                val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { r, _ -> val t = handsToTeam(r); runOnUiThread { onDetection(t) } }
                    .setErrorListener { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
                    .build()
                gestureRec = GestureRecognizer.createFromOptions(this, opts)
            }
            runOnUiThread { status.text = hintText() }
        } catch (e: Exception) {
            runOnUiThread { status.text = "No se pudo cargar el modelo: ${e.message}" }
        }
    }

    private fun hintText() =
        if (armMode) "Brazo IZQ. arriba = ${match.names[0]}  ·  Brazo DCHO. arriba = ${match.names[1]}"
        else "✊ puño = ${match.names[0]}  ·  ✋ palma = ${match.names[1]}"

    /** 0 = A, 1 = B, -1 = nada o gestos contradictorios. */
    private fun handsToTeam(result: GestureRecognizerResult): Int {
        var seen = -1
        for (hand in result.gestures()) {
            val top = hand.firstOrNull() ?: continue
            if (top.score() < MIN_SCORE) continue
            val t = when (top.categoryName()) { FIST -> 0; PALM -> 1; else -> -1 }
            if (t == -1) continue
            if (seen != -1 && seen != t) return -1
            seen = t
        }
        return seen
    }

    /** Brazo levantado = muñeca por encima del hombro y de la cabeza. Izquierda/derecha del propio jugador. */
    private fun armsToTeam(result: PoseLandmarkerResult): Int {
        var seen = -1
        for (pose in result.landmarks()) {
            if (pose.size < 17) continue
            val left = raised(pose, L_WRIST, L_SHOULDER, pose[NOSE])
            val right = raised(pose, R_WRIST, R_SHOULDER, pose[NOSE])
            val t = when {
                left && !right -> 0
                right && !left -> 1
                else -> -1
            }
            if (t == -1) continue
            if (seen != -1 && seen != t) return -1
            seen = t
        }
        return seen
    }

    private fun vis(l: NormalizedLandmark) = l.visibility().orElse(1f)

    private fun raised(pose: List<NormalizedLandmark>, wrist: Int, shoulder: Int, nose: NormalizedLandmark): Boolean {
        val w = pose[wrist]; val s = pose[shoulder]
        if (vis(w) < MIN_VIS || vis(s) < MIN_VIS) return false
        val torso = kotlin.math.abs(pose[L_SHOULDER].x() - pose[R_SHOULDER].x()).coerceAtLeast(0.03f)
        // y crece hacia abajo: la muñeca debe quedar claramente por encima del hombro y por encima de la nariz
        return w.y() < s.y() - torso * 0.8f && w.y() < nose.y()
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
                status.text = hintText()
            } catch (e: Exception) {
                status.text = "Error de cámara: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (gestureRec == null && poseRec == null) { image.close(); return }
        val bmp: Bitmap = image.toBitmap()
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        image.close()
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val mp = BitmapImageBuilder(rotated).build()
        val ts = SystemClock.uptimeMillis()
        try {
            gestureRec?.recognizeAsync(mp, ts)
            poseRec?.detectAsync(mp, ts)
        } catch (_: Exception) { }
    }

    private fun resetDetectorState() {
        candidate = -1; needRelease = false; progress.progress = 0
    }

    /** Exige mantener el gesto HOLD_MS, pausa COOLDOWN_MS y bajar la mano/brazo antes del siguiente punto. */
    private fun onDetection(seen: Int) {
        val now = SystemClock.uptimeMillis()
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
        Toast.makeText(this, "Punto para ${match.names[team]}", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val s = match.state
        val iconA = if (armMode) "IZQ ↑" else "✊"
        val iconB = if (armMode) "DCHO ↑" else "✋"
        findViewById<TextView>(R.id.nameA).text = "$iconA  ${match.names[0]}"
        findViewById<TextView>(R.id.nameB).text = "$iconB  ${match.names[1]}"
        findViewById<TextView>(R.id.pointsA).text = match.pointLabel(0)
        findViewById<TextView>(R.id.pointsB).text = match.pointLabel(1)
        findViewById<TextView>(R.id.gamesA).text = s.games[0].toString()
        findViewById<TextView>(R.id.gamesB).text = s.games[1].toString()
        val histA = s.setHistory.joinToString("  ") { it.first.toString() }
        val histB = s.setHistory.joinToString("  ") { it.second.toString() }
        findViewById<TextView>(R.id.setsA).text = "Sets: ${s.sets[0]}" + if (histA.isNotEmpty()) "  ($histA)" else ""
        findViewById<TextView>(R.id.setsB).text = "Sets: ${s.sets[1]}" + if (histB.isNotEmpty()) "  ($histB)" else ""
        findViewById<TextView>(R.id.header).text = when {
            s.finished -> "🏆 GANA ${match.names[s.winner].uppercase()}"
            s.tiebreak -> "TIE-BREAK · JUEGOS · PUNTOS"
            match.goldenPoint -> "PUNTO DE ORO · JUEGOS · PUNTOS"
            else -> "JUEGOS · PUNTOS"
        }
        findViewById<Button>(R.id.voice).text = "Voz: ${if (voiceOn) "SÍ" else "NO"}"
        if (gestureRec != null || poseRec != null) status.text = hintText()
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
        executor.execute { gestureRec?.close(); poseRec?.close() }
        executor.shutdown()
        tts?.shutdown()
        tone.release()
    }
}
