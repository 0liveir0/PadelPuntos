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
        var armMode = false            // false = puño/palma, true = brazo izquierdo/derecho
        var prefsLoaded = false
        var lastGolden = false
        // Mando Bluetooth (se comporta como un teclado). Cada botón se identifica por el CONJUNTO
        // de teclas que envía en una pulsación (p. ej. grande = "24", pequeño = "24,66").
        var remoteOn = true
        var sigA = "${KeyEvent.KEYCODE_VOLUME_UP}"
        var sigB = "${KeyEvent.KEYCODE_ENTER}"
        const val BURST_MS = 160L        // teclas recibidas en <160 ms = una sola pulsación física
        const val ONE_BUTTON_MS = 600L   // mando cuyos botones envían lo mismo: tiempo entre pulsaciones
        const val DOUBLE_MS = 500L       // doble pulsación para anular
        var undoByRemote = false         // false = se anula con gesto · true = doble pulsación del mando
        lateinit var store: MatchStore
        var current: MatchRecord? = null

        const val HOLD_MS = 1000L      // tiempo que hay que mantener el gesto
        const val COOLDOWN_MS = 2500L  // pausa tras sumar un punto
        const val MIN_SCORE = 0.6f     // confianza mínima del gesto de mano
        const val MIN_VIS = 0.5f       // visibilidad mínima de muñeca/hombro
        const val FIST = "Closed_Fist"
        const val PALM = "Open_Palm"
        const val THUMB_DOWN = "Thumb_Down"
        const val UNDO = 2             // código interno del gesto "deshacer"

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
        r.mode = if (armMode) "brazos" else "mano"
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

        box.addView(label(if (armMode) "Equipo A (brazo izquierdo)" else "Equipo A (✊ puño)"))
        box.addView(p[0]); box.addView(p[1]); box.addView(al[0])
        box.addView(label(if (armMode) "Equipo B (brazo derecho)" else "Equipo B (✋ palma)"))
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
                    mode = if (armMode) "brazos" else "mano", points = emptyList()
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
        armMode = p.getBoolean("armMode", false)
        voiceOn = p.getBoolean("voice", true)
        remoteOn = p.getBoolean("remoteOn", true)
        sigA = p.getString("sigA", null) ?: "${KeyEvent.KEYCODE_VOLUME_UP}"
        sigB = p.getString("sigB", null) ?: "${KeyEvent.KEYCODE_ENTER}"
        undoByRemote = p.getBoolean("undoRemote", false)
    }

    private fun savePrefs() {
        getSharedPreferences("padel", Context.MODE_PRIVATE).edit()
            .putBoolean("golden", lastGolden)
            .putBoolean("armMode", armMode)
            .putBoolean("voice", voiceOn)
            .putBoolean("remoteOn", remoteOn)
            .putString("sigA", sigA)
            .putString("sigB", sigB)
            .putBoolean("undoRemote", undoByRemote)
            .apply()
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val rbHand = RadioButton(this).apply { id = View.generateViewId(); text = "✊ Puño = A · ✋ Palma = B · 👎 = deshacer" }
        val rbArm = RadioButton(this).apply { id = View.generateViewId(); text = "Brazo izquierdo = A · Brazo derecho = B · ❌ brazos cruzados = deshacer" }
        val group = RadioGroup(this).apply { addView(rbHand); addView(rbArm); check(if (armMode) rbArm.id else rbHand.id) }
        val voice = CheckBox(this).apply { text = "Anunciar el marcador por voz"; isChecked = voiceOn }
        val remote = CheckBox(this).apply { text = "Usar mando Bluetooth (botón grande = A · pequeño = B)"; isChecked = remoteOn }
        val rbUndoGesture = RadioButton(this).apply { id = View.generateViewId(); text = "Gesto (❌ brazos cruzados / 👎 pulgar abajo)" }
        val rbUndoRemote = RadioButton(this).apply { id = View.generateViewId(); text = "Doble pulsación rápida del mando (cualquier botón)" }
        val undoGroup = RadioGroup(this).apply {
            addView(rbUndoGesture); addView(rbUndoRemote)
            check(if (undoByRemote) rbUndoRemote.id else rbUndoGesture.id)
        }
        var dlgRef: AlertDialog? = null
        val learn = Button(this).apply {
            text = "🎮 Configurar mando…"
            setOnClickListener { dlgRef?.dismiss(); learnRemote() }
        }

        box.addView(TextView(this).apply { text = "Cómo se marca el punto con gestos" })
        box.addView(group)
        box.addView(voice)
        box.addView(remote)
        box.addView(TextView(this).apply { text = "Anular el último punto con"; setPadding(0, pad / 2, 0, 0) })
        box.addView(undoGroup)
        box.addView(learn)

        dlgRef = AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                voiceOn = voice.isChecked
                remoteOn = remote.isChecked
                undoByRemote = undoGroup.checkedRadioButtonId == rbUndoRemote.id
                pendingClicks = 0
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

    // ---------- Mando Bluetooth ----------

    private val handler = Handler(Looper.getMainLooper())
    private var learning = false

    private fun codesOf(sig: String) = sig.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    private fun sigOf(codes: Collection<Int>) = codes.toSortedSet().joinToString(",")
    private fun sameButtons() = sigA == sigB
    private fun keyName(c: Int) = when (c) {
        KeyEvent.KEYCODE_VOLUME_UP -> "Volumen +"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volumen −"
        KeyEvent.KEYCODE_ENTER -> "Intro"
        else -> KeyEvent.keyCodeToString(c).removePrefix("KEYCODE_")
    }
    private fun sigName(sig: String) = codesOf(sig).joinToString(" + ") { keyName(it) }

    // 1) Agrupa las teclas de una misma pulsación física
    private val burst = mutableSetOf<Int>()
    private val endBurst = Runnable {
        val sig = sigOf(burst); val codes = burst.toSet(); burst.clear()
        onButton(classify(sig, codes))
    }

    /** Decide qué botón se ha pulsado: 0 = A, 1 = B. */
    private fun classify(sig: String, codes: Set<Int>): Int {
        if (sameButtons()) return 0
        if (sig == sigA) return 0
        if (sig == sigB) return 1
        val onlyA = codesOf(sigA) - codesOf(sigB)
        val onlyB = codesOf(sigB) - codesOf(sigA)
        return when {
            codes.any { it in onlyB } -> 1
            codes.any { it in onlyA } -> 0
            codesOf(sigB).size > codesOf(sigA).size -> 0   // llegó solo la tecla común: es el botón "corto"
            else -> 1
        }
    }

    /** Intercepta las teclas del mando antes de que el sistema cambie el volumen o pulse un botón. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!remoteOn || learning || (code !in codesOf(sigA) && code !in codesOf(sigB))) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (burst.isEmpty()) handler.postDelayed(endBurst, BURST_MS)
            burst.add(code)
        }
        return true
    }

    // 2) Pulsaciones: punto, o anular con doble pulsación
    private var pendingClicks = 0
    private var firstButton = 0
    private val flushClicks = Runnable {
        val n = pendingClicks; pendingClicks = 0
        if (n == 0) return@Runnable
        if (!sameButtons()) {
            // Dos botones distintos: 1 pulsación = punto de su equipo · 2 pulsaciones = anular
            if (n == 1) addPoint(firstButton, "mando") else undoLast()
        } else {
            // Botones indistinguibles: 1 = A · 2 = B · 3 = anular (si se anula con el mando)
            when {
                n == 1 -> addPoint(0, "mando")
                n == 2 || !undoByRemote -> addPoint(1, "mando")
                else -> undoLast()
            }
        }
    }

    private fun onButton(button: Int) {
        if (!sameButtons() && !undoByRemote) { addPoint(button, "mando"); return }   // sin espera
        if (pendingClicks == 0) firstButton = button
        pendingClicks++
        handler.removeCallbacks(flushClicks)
        handler.postDelayed(flushClicks, if (sameButtons()) ONE_BUTTON_MS else DOUBLE_MS)
    }

    /** Asistente: detecta qué teclas envía cada botón del mando. */
    private fun learnRemote() {
        learning = true
        val sigs = mutableListOf<String>()
        val current = mutableSetOf<Int>()
        lateinit var dlg: AlertDialog
        val finishPress = Runnable {
            if (current.isEmpty()) return@Runnable
            sigs.add(sigOf(current)); current.clear()
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            if (sigs.size == 1) {
                dlg.setMessage("✔ Botón del equipo A: ${sigName(sigs[0])}\n\nAhora pulsa el botón para el EQUIPO B (${match.names[1]}).")
            } else {
                sigA = sigs[0]; sigB = sigs[1]; remoteOn = true
                pendingClicks = 0
                savePrefs()
                dlg.dismiss()
                val detail = "Equipo A: ${sigName(sigA)}\nEquipo B: ${sigName(sigB)}\n\n"
                val undoTxt = if (undoByRemote) "\nDoble pulsación rápida = anular." else ""
                val undoTxt1 = if (undoByRemote) " · 3 pulsaciones = anular" else ""
                val msg = if (!sameButtons()) {
                    detail + "✔ Mando listo: un botón para cada equipo." + undoTxt
                } else {
                    detail + "Los dos botones envían exactamente lo mismo, así que la app no puede distinguirlos.\n" +
                        "1 pulsación = ${match.names[0]} · 2 pulsaciones = ${match.names[1]}" + undoTxt1
                }
                AlertDialog.Builder(this).setTitle("🎮 Mando configurado").setMessage(msg).setPositiveButton("OK", null).show()
                say("Mando configurado")
                render()
            }
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("Configurar mando")
            .setMessage("1. Empareja antes el mando en Ajustes de Android → Bluetooth.\n\n2. Pulsa UNA VEZ el botón que dará el punto al EQUIPO A (${match.names[0]}).")
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                if (current.isEmpty()) handler.postDelayed(finishPress, 400)   // recoge todas las teclas de la pulsación
                current.add(code)
            }
            true
        }
        dlg.setOnDismissListener { learning = false; handler.removeCallbacks(finishPress) }
        dlg.show()
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

    private fun hintText(): String {
        val remoteUndo = remoteOn && undoByRemote
        val gestures = if (armMode) "Brazo IZQ. arriba = ${match.names[0]}  ·  Brazo DCHO. arriba = ${match.names[1]}"
            else "✊ puño = ${match.names[0]}  ·  ✋ palma = ${match.names[1]}"
        val undo = when {
            remoteUndo -> "anular = doble pulsación del mando"
            armMode -> "❌ brazos cruzados = anular"
            else -> "👎 = anular"
        }
        return "$gestures  ·  $undo" + if (remoteOn) "  ·  🎮 mando activo" else ""
    }

    /** 0 = A, 1 = B, 2 = deshacer, -1 = nada o gestos contradictorios. */
    private fun handsToTeam(result: GestureRecognizerResult): Int {
        var seen = -1
        for (hand in result.gestures()) {
            val top = hand.firstOrNull() ?: continue
            if (top.score() < MIN_SCORE) continue
            val t = when (top.categoryName()) { FIST -> 0; PALM -> 1; THUMB_DOWN -> UNDO; else -> -1 }
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
            if (crossedUp(pose)) {
                if (seen != -1 && seen != UNDO) return -1
                seen = UNDO; continue
            }
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

    /**
     * Brazos cruzados en X por encima de los hombros (gesto japonés de "no").
     * Cruzado = las muñecas quedan en lado contrario al de sus hombros; funciona con cámara frontal o trasera.
     */
    private fun crossedUp(pose: List<NormalizedLandmark>): Boolean {
        val lw = pose[L_WRIST]; val rw = pose[R_WRIST]; val ls = pose[L_SHOULDER]; val rs = pose[R_SHOULDER]
        if (listOf(lw, rw, ls, rs).any { vis(it) < MIN_VIS }) return false
        val torso = kotlin.math.abs(ls.x() - rs.x()).coerceAtLeast(0.03f)
        val bothUp = lw.y() < ls.y() - torso * 0.3f && rw.y() < rs.y() - torso * 0.3f
        val crossed = (lw.x() - rw.x()) * (ls.x() - rs.x()) < 0
        return bothUp && crossed
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
    private fun onDetection(raw: Int) {
        // Si se anula con el mando, el gesto de anular se ignora (evita anulaciones accidentales)
        val seen = if (raw == UNDO && remoteOn && undoByRemote) -1 else raw
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
            isClosed() -> "⏹ PARTIDO TERMINADO (INACABADO)"
            s.tiebreak -> "TIE-BREAK · JUEGOS · PUNTOS"
            match.goldenPoint -> "PUNTO DE ORO · JUEGOS · PUNTOS"
            else -> "JUEGOS · PUNTOS"
        }
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
