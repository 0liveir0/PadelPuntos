package com.padelpuntos.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
        var prefsLoaded = false
        var lastGolden = false
        lateinit var store: MatchStore
        var current: MatchRecord? = null

        // Cómo se marca: gestos para puntos (0 = ninguno, 1 = mano ✊/✋, 2 = brazo izq./dcho.),
        // gesto de brazos en cruz para anular, y mando Bluetooth (1 = A · 2 = B · 3 = anular)
        const val G_NONE = 0
        const val G_HAND = 1
        const val G_ARMS = 2
        var pointGestures = G_HAND
        var undoGesture = true
        var remoteOn = true

        // Mando Bluetooth: se comporta como un teclado; vale cualquiera de estas teclas
        val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )
        const val PRESS_WINDOW_MS = 700L // tras la última pulsación se espera esto antes de decidir
        const val SAME_PRESS_MS = 120L   // tecla DISTINTA en <120 ms = misma pulsación física (mandos que envían 2 teclas)

        const val HOLD_MS = 1000L      // tiempo que hay que mantener el gesto
        const val COOLDOWN_MS = 2500L  // pausa tras sumar un punto
        const val FRESH_MS = 400L      // resultado de un detector válido durante este tiempo
        const val MIN_SCORE = 0.6f     // confianza mínima del gesto de mano
        const val MIN_VIS = 0.5f       // visibilidad mínima de muñeca/hombro
        const val FIST = "Closed_Fist"
        const val PALM = "Open_Palm"
        const val UNDO = 2             // código interno de "anular"

        // Índices de MediaPipe Pose (izquierda/derecha del propio jugador)
        const val NOSE = 0
        const val L_SHOULDER = 11
        const val R_SHOULDER = 12
        const val L_ELBOW = 13
        const val R_ELBOW = 14
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
        val firstStart = !prefsLoaded
        if (firstStart) { loadPrefs(); store = MatchStore(filesDir); prefsLoaded = true }

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.holdProgress)
        executor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        findViewById<View>(R.id.teamA).setOnClickListener { addPoint(0, "toque") }
        findViewById<View>(R.id.teamB).setOnClickListener { addPoint(1, "toque") }
        findViewById<Button>(R.id.undo).setOnClickListener { undoLast() }
        findViewById<Button>(R.id.reset).setOnClickListener {
            if (current?.status == "en_curso" && match.events.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage("El partido actual se guardará como INACABADO. ¿Empezar otro?")
                    .setPositiveButton("Sí") { _, _ -> showNewMatchDialog() }
                    .setNegativeButton("No", null).show()
            } else showNewMatchDialog()
        }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.finish).setOnClickListener { askFinish() }
        findViewById<Button>(R.id.history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.flip).setOnClickListener {
            useFrontCamera = !useFrontCamera; startCamera()
        }

        executor.execute { setupDetector() }
        render()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)

        if (firstStart) {
            val pending = store.inProgress()
            if (pending != null) askResume(pending) else showNewMatchDialog()
        }
    }

    // ---------- Partidos guardados ----------

    private fun askResume(pending: MatchRecord) {
        val sum = MatchSummary(pending)
        AlertDialog.Builder(this)
            .setTitle("Partido sin terminar")
            .setMessage("${pending.teamName(0)}  contra  ${pending.teamName(1)}\n${sum.result.ifEmpty { "0-0" }}\n\n¿Quieres continuarlo?")
            .setCancelable(false)
            .setPositiveButton("Continuar") { _, _ -> loadRecord(pending) }
            .setNegativeButton("Nuevo partido") { _, _ -> showNewMatchDialog(pending) }
            .show()
    }

    private fun loadRecord(r: MatchRecord) {
        current = r
        match.goldenPoint = r.golden
        match.names = arrayOf(r.teamName(0), r.teamName(1))
        match.replay(r.points)
        render()
    }

    /** Un partido que se deja sin acabar se guarda como "inacabado" (si no tenía puntos, se descarta). */
    private fun closeAsUnfinished(r: MatchRecord) {
        if (r.status != "en_curso") return
        if (r.points.isEmpty()) { store.delete(r.id); return }
        r.status = "inacabado"
        r.end = r.points.last().time
        store.save(r)
    }

    /** Cierra el partido en curso ahora mismo, aunque no esté acabado: queda guardado como "inacabado". */
    private fun askFinish() {
        val r = current
        when {
            r == null -> showNewMatchDialog()
            r.status == "terminado" -> Toast.makeText(this, "Este partido ya está terminado", Toast.LENGTH_SHORT).show()
            r.status == "inacabado" -> Toast.makeText(this, "Este partido ya está cerrado. Pulsa Nuevo para empezar otro", Toast.LENGTH_LONG).show()
            match.events.isEmpty() -> AlertDialog.Builder(this)
                .setMessage("No hay ningún punto jugado. ¿Descartar este partido y empezar otro?")
                .setPositiveButton("Sí") { _, _ -> showNewMatchDialog() }
                .setNegativeButton("No", null).show()
            else -> AlertDialog.Builder(this)
                .setTitle("Terminar partido")
                .setMessage("Se guardará como INACABADO con el marcador actual:\n\n${MatchSummary(r).result.ifEmpty { "0-0" }}  (${match.pointLabel(0)}-${match.pointLabel(1)})")
                .setPositiveButton("Terminar") { _, _ ->
                    closeAsUnfinished(r)
                    render()
                    say("Partido terminado")
                    Toast.makeText(this, "Partido guardado como inacabado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun isClosed() = current?.status == "inacabado"

    /** Guarda el partido actual tras cada punto o deshacer. */
    private fun persist() {
        val r = current ?: return
        r.points = match.events.toList()
        r.mode = modeName()
        if (match.state.finished) {
            r.status = "terminado"; r.end = r.points.lastOrNull()?.time ?: System.currentTimeMillis()
        } else {
            r.status = "en_curso"; r.end = 0
        }
        try { store.save(r) } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNewMatchDialog(pendingToClose: MatchRecord? = null) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val known = store.knownPlayers()
        val prev = current ?: pendingToClose ?: store.all().lastOrNull()
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0); textSize = 15f }
        fun player(hint: String, i: Int) = AutoCompleteTextView(this).apply {
            this.hint = hint
            setSingleLine(); threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, known))
            setText(prev?.players?.getOrNull(i) ?: "")
        }
        fun alias(i: Int) = EditText(this).apply {
            hint = "Alias de la pareja (opcional)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(prev?.aliases?.getOrNull(i) ?: "")
        }
        val p = Array(4) { player(if (it % 2 == 0) "Jugador 1" else "Jugador 2", it) }
        val al = Array(2) { alias(it) }
        val golden = CheckBox(this).apply { text = "Punto de oro (sin ventajas)"; isChecked = prev?.golden ?: lastGolden }

        box.addView(label("Equipo A (${howA()})"))
        box.addView(p[0]); box.addView(p[1]); box.addView(al[0])
        box.addView(label("Equipo B (${howB()})"))
        box.addView(p[2]); box.addView(p[3]); box.addView(al[1])
        box.addView(golden)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuevo partido")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Empezar") { _, _ ->
                val defaults = arrayOf("Jugador A1", "Jugador A2", "Jugador B1", "Jugador B2")
                val names = Array(4) { i -> p[i].text.toString().trim().replace(";", ",").ifEmpty { defaults[i] } }
                val aliases = Array(2) { i -> al[i].text.toString().trim().replace(";", ",") }
                (current ?: pendingToClose)?.let { closeAsUnfinished(it) }
                lastGolden = golden.isChecked; savePrefs()
                val rec = MatchRecord(
                    id = store.nextId(), players = names, aliases = aliases, golden = golden.isChecked,
                    start = System.currentTimeMillis(), end = 0, status = "en_curso",
                    mode = modeName(), points = emptyList()
                )
                current = rec
                match.reset()
                match.goldenPoint = rec.golden
                match.names = arrayOf(rec.teamName(0), rec.teamName(1))
                persist()
                render()
                say("Nuevo partido. ${rec.teamName(0)} contra ${rec.teamName(1)}")
            }
        // La primera vez es obligatorio crear partido; después se puede cancelar y seguir con el actual.
        if (current != null) dialog.setNegativeButton("Cancelar", null) else dialog.setCancelable(false)
        dialog.show()
    }

    // ---------- Ajustes ----------

    private fun loadPrefs() {
        val p = getSharedPreferences("padel", Context.MODE_PRIVATE)
        lastGolden = p.getBoolean("golden", false)
        voiceOn = p.getBoolean("voice", true)
        remoteOn = p.getBoolean("remoteOn", true)
        undoGesture = p.getBoolean("undoGesture", true)
        // compatibilidad con versiones anteriores ("armMode")
        pointGestures = p.getInt("pointGestures", if (p.getBoolean("armMode", false)) G_ARMS else G_HAND)
    }

    private fun savePrefs() {
        getSharedPreferences("padel", Context.MODE_PRIVATE).edit()
            .putBoolean("golden", lastGolden)
            .putBoolean("voice", voiceOn)
            .putBoolean("remoteOn", remoteOn)
            .putBoolean("undoGesture", undoGesture)
            .putInt("pointGestures", pointGestures)
            .apply()
    }

    private fun modeName() = buildList {
        if (pointGestures == G_HAND) add("mano")
        if (pointGestures == G_ARMS) add("brazos")
        if (remoteOn) add("mando")
    }.joinToString("+").ifEmpty { "toque" }

    private fun howA() = listOfNotNull(
        when (pointGestures) { G_HAND -> "✊ puño"; G_ARMS -> "brazo izquierdo"; else -> null },
        if (remoteOn) "1 pulsación" else null
    ).joinToString(" · ").ifEmpty { "tocar su franja" }

    private fun howB() = listOfNotNull(
        when (pointGestures) { G_HAND -> "✋ palma"; G_ARMS -> "brazo derecho"; else -> null },
        if (remoteOn) "2 pulsaciones" else null
    ).joinToString(" · ").ifEmpty { "tocar su franja" }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, pad / 2, 0, 0); setTypeface(typeface, android.graphics.Typeface.BOLD) }

        val rbNone = RadioButton(this).apply { id = View.generateViewId(); text = "Ninguno (solo mando o tocar la pantalla)" }
        val rbHand = RadioButton(this).apply { id = View.generateViewId(); text = "Mano: ✊ puño = A · ✋ palma = B" }
        val rbArm = RadioButton(this).apply { id = View.generateViewId(); text = "Brazos: izquierdo arriba = A · derecho arriba = B" }
        val group = RadioGroup(this).apply {
            addView(rbNone); addView(rbHand); addView(rbArm)
            check(when (pointGestures) { G_HAND -> rbHand.id; G_ARMS -> rbArm.id; else -> rbNone.id })
        }
        val undoG = CheckBox(this).apply { text = "Brazos en cruz (los dos extendidos a los lados, en T) = anular"; isChecked = undoGesture }
        val remote = CheckBox(this).apply { text = "Mando Bluetooth: 1 pulsación = A · 2 = B · 3 = anular"; isChecked = remoteOn }
        val voice = CheckBox(this).apply { text = "Anunciar el marcador por voz"; isChecked = voiceOn }

        box.addView(title("Gestos para sumar puntos"))
        box.addView(group)
        box.addView(title("Anular el último punto"))
        box.addView(undoG)
        box.addView(TextView(this).apply { text = "El botón ↶ de la pantalla siempre anula."; textSize = 12f })
        box.addView(title("Mando"))
        box.addView(remote)
        box.addView(TextView(this).apply { text = "Empareja antes el mando en Ajustes de Android → Bluetooth. Vale cualquier botón."; textSize = 12f })
        box.addView(title("Sonido"))
        box.addView(voice)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val newPoints = when (group.checkedRadioButtonId) { rbHand.id -> G_HAND; rbArm.id -> G_ARMS; else -> G_NONE }
                val changed = newPoints != pointGestures || undoG.isChecked != undoGesture
                pointGestures = newPoints
                undoGesture = undoG.isChecked
                remoteOn = remote.isChecked
                voiceOn = voice.isChecked
                pressCount = 0
                if (changed) {
                    resetDetectorState()
                    executor.execute { setupDetector() }
                }
                savePrefs()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Mando Bluetooth: 1 pulsación = A · 2 = B · 3 = anular ----------

    private val handler = Handler(Looper.getMainLooper())
    private var pressCount = 0
    private var lastPressCode = 0
    private var lastPressTime = 0L

    private val flushPresses = Runnable {
        val n = pressCount; pressCount = 0
        when (n) {
            0 -> {}
            1 -> addPoint(0, "mando")
            2 -> addPoint(1, "mando")
            else -> undoLast()
        }
        render()
    }

    /** Intercepta las teclas del mando antes de que el sistema cambie el volumen o pulse un botón. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!remoteOn || code !in REMOTE_KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onRemotePress(code, event.eventTime)
        return true
    }

    private fun onRemotePress(code: Int, time: Long) {
        // Algunos mandos envían dos teclas distintas por pulsación (p. ej. Volumen+ e Intro): la segunda no cuenta.
        // La MISMA tecla repetida siempre cuenta, por rápido que se pulse.
        if (code != lastPressCode && time - lastPressTime < SAME_PRESS_MS) return
        lastPressCode = code; lastPressTime = time
        pressCount++
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 50)
        val what = when (pressCount) { 1 -> match.names[0]; 2 -> match.names[1]; else -> "ANULAR" }
        status.text = "🎮 ${"●".repeat(pressCount.coerceAtMost(3))}  →  $what"
        handler.removeCallbacks(flushPresses)
        handler.postDelayed(flushPresses, PRESS_WINDOW_MS)
    }

    // ---------- Detección (MediaPipe) ----------

    private fun needHand() = pointGestures == G_HAND
    private fun needPose() = pointGestures == G_ARMS || undoGesture

    /** Se ejecuta siempre en el hilo del analizador, así no choca con el análisis de imágenes. */
    private fun setupDetector() {
        gestureRec?.close(); gestureRec = null
        poseRec?.close(); poseRec = null
        try {
            if (needPose()) {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setNumPoses(2)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { r: PoseLandmarkerResult, _ -> val t = posesToSignal(r); runOnUiThread { onPose(t) } }
                    .setErrorListener { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
                    .build()
                poseRec = PoseLandmarker.createFromOptions(this, opts)
            }
            if (needHand()) {
                val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { r, _ -> val t = handsToTeam(r); runOnUiThread { onHand(t) } }
                    .setErrorListener { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
                    .build()
                gestureRec = GestureRecognizer.createFromOptions(this, opts)
            }
            runOnUiThread { status.text = hintText() }
        } catch (e: Exception) {
            runOnUiThread { status.text = "No se pudo cargar el modelo: ${e.message}" }
        }
    }

    private fun hintText(): String {
        val parts = mutableListOf<String>()
        when (pointGestures) {
            G_HAND -> parts.add("✊ = ${match.names[0]} · ✋ = ${match.names[1]}")
            G_ARMS -> parts.add("brazo IZQ ↑ = ${match.names[0]} · DCHO ↑ = ${match.names[1]}")
        }
        if (undoGesture) parts.add("brazos en cruz = anular")
        if (remoteOn) parts.add("🎮 1 = A · 2 = B · 3 = anular")
        return parts.joinToString("   |   ").ifEmpty { "Toca la franja de cada equipo para sumar" }
    }

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

    /** 0 = A (brazo izq.), 1 = B (brazo dcho.), UNDO = brazos en cruz, -1 = nada. */
    private fun posesToSignal(result: PoseLandmarkerResult): Int {
        var seen = -1
        for (pose in result.landmarks()) {
            if (pose.size < 17) continue
            val t = when {
                undoGesture && armsOut(pose) -> UNDO
                pointGestures == G_ARMS -> {
                    val left = raised(pose, L_WRIST, L_SHOULDER, pose[NOSE])
                    val right = raised(pose, R_WRIST, R_SHOULDER, pose[NOSE])
                    when { left && !right -> 0; right && !left -> 1; else -> -1 }
                }
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

    /**
     * Brazos en cruz (T): los dos brazos extendidos hacia los lados, a la altura de los hombros.
     * Cada muñeca queda lejos de su hombro, hacia fuera, y a la misma altura que él.
     * Funciona con cámara frontal o trasera (se compara el lado de cada muñeca con el de su hombro).
     */
    private fun armsOut(pose: List<NormalizedLandmark>): Boolean {
        val ls = pose[L_SHOULDER]; val rs = pose[R_SHOULDER]; val lw = pose[L_WRIST]; val rw = pose[R_WRIST]
        val le = pose[L_ELBOW]; val re = pose[R_ELBOW]
        if (listOf(ls, rs, lw, rw).any { vis(it) < MIN_VIS }) return false
        val sw = kotlin.math.abs(ls.x() - rs.x())
        if (sw < 0.03f) return false                       // de perfil: no se puede valorar
        val dir = if (ls.x() > rs.x()) 1f else -1f         // hacia dónde queda "fuera" para el lado izquierdo
        val leftOut = (lw.x() - ls.x()) * dir > sw * 1.1f
        val rightOut = (rw.x() - rs.x()) * -dir > sw * 1.1f
        val tol = sw * 0.7f
        val level = kotlin.math.abs(lw.y() - ls.y()) < tol && kotlin.math.abs(rw.y() - rs.y()) < tol
        // si se ven los codos, también deben estar a la altura de los hombros (brazos estirados)
        val elbows = (vis(le) < MIN_VIS || kotlin.math.abs(le.y() - ls.y()) < tol) &&
            (vis(re) < MIN_VIS || kotlin.math.abs(re.y() - rs.y()) < tol)
        return leftOut && rightOut && level && elbows
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
        if (gestureRec == null && poseRec == null) { image.close(); return }   // sin gestos: solo vista previa
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

    // Último resultado de cada detector (pueden funcionar los dos a la vez)
    private var lastHand = -1; private var lastHandT = 0L
    private var lastPose = -1; private var lastPoseT = 0L

    private fun onHand(t: Int) { lastHand = t; lastHandT = SystemClock.uptimeMillis(); evaluate() }
    private fun onPose(t: Int) { lastPose = t; lastPoseT = SystemClock.uptimeMillis(); evaluate() }

    /** Combina mano y postura: anular tiene prioridad; si piden equipos distintos, no cuenta nada. */
    private fun evaluate() {
        val now = SystemClock.uptimeMillis()
        val h = if (gestureRec != null && now - lastHandT < FRESH_MS) lastHand else -1
        val p = if (poseRec != null && now - lastPoseT < FRESH_MS) lastPose else -1
        val signal = when {
            p == UNDO -> UNDO
            h != -1 && p != -1 && h != p -> -1
            h != -1 -> h
            else -> p
        }
        onDetection(signal)
    }

    /** Exige mantener el gesto HOLD_MS, pausa COOLDOWN_MS y bajar la mano/brazo antes del siguiente. */
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
            if (candidate == UNDO) undoLast() else addPoint(candidate, "gesto")
            cooldownUntil = now + COOLDOWN_MS
            needRelease = true
            candidate = -1
            progress.progress = 0
        }
    }

    // ---------- Marcador ----------

    private fun addPoint(team: Int, source: String) {
        if (match.state.finished || isClosed()) { say("El partido ha terminado. Pulsa Nuevo para empezar otro"); return }
        val msg = match.point(team, source)
        persist()
        render()
        tone.startTone(if (team == 0) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 200)
        vibrate()
        say(msg)
        Toast.makeText(this, "Punto para ${match.names[team]}", Toast.LENGTH_SHORT).show()
    }

    private fun undoLast() {
        if (isClosed()) { say("El partido está cerrado. Pulsa Nuevo para empezar otro"); return }
        if (match.undo()) {
            persist()
            render()
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            vibrate()
            say("Punto anulado. " + match.spokenPoints())
            Toast.makeText(this, "↶ Punto anulado", Toast.LENGTH_SHORT).show()
        } else {
            say("No hay nada que deshacer")
        }
    }

    private fun render() {
        val s = match.state
        val iconA = when (pointGestures) { G_HAND -> "✊"; G_ARMS -> "IZQ ↑"; else -> if (remoteOn) "🎮 1" else "" }
        val iconB = when (pointGestures) { G_HAND -> "✋"; G_ARMS -> "DCHO ↑"; else -> if (remoteOn) "🎮 2" else "" }
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
            isClosed() -> "⏹ PARTIDO TERMINADO (INACABADO)"
            s.tiebreak -> "TIE-BREAK · JUEGOS · PUNTOS"
            match.goldenPoint -> "PUNTO DE ORO · JUEGOS · PUNTOS"
            else -> "JUEGOS · PUNTOS"
        }
        if (pressCount == 0) status.text = hintText()
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
