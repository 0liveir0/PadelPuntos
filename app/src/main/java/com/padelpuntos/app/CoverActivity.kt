package com.padelpuntos.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Portada: pantalla de inicio con la imagen y el botón INICIAR PARTIDA. */
class CoverActivity : ComponentActivity() {

    private lateinit var store: MatchStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover)
        store = MatchStore(filesDir)

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val pending = inProgress()
            if (pending != null) {
                AlertDialog.Builder(this)
                    .setTitle("Hay un partido sin terminar")
                    .setMessage("${pending.teamName(0)} contra ${pending.teamName(1)}\n${MatchSummary(pending).result.ifEmpty { "0-0" }}\n\nSi empiezas otro, ese quedará guardado como INACABADO.")
                    .setPositiveButton("Empezar otro") { _, _ -> open(MainActivity.ACTION_NEW) }
                    .setNegativeButton("Continuar ese") { _, _ -> open(MainActivity.ACTION_CONTINUE) }
                    .setNeutralButton("Cancelar", null)
                    .show()
            } else open(MainActivity.ACTION_NEW)
        }
        findViewById<Button>(R.id.continueBtn).setOnClickListener { open(MainActivity.ACTION_CONTINUE) }
        findViewById<Button>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Muestra "Continuar" solo si hay un partido en curso
        val pending = inProgress()
        findViewById<Button>(R.id.continueBtn).visibility = if (pending != null) View.VISIBLE else View.GONE
        val last = store.all().lastOrNull { it.points.isNotEmpty() }
        findViewById<TextView>(R.id.coverInfo).text = when {
            pending != null -> "En curso: ${pending.teamName(0)} vs ${pending.teamName(1)}"
            last != null -> "Último: ${last.teamName(0)} vs ${last.teamName(1)} · ${MatchSummary(last).result}"
            else -> "Marcador por gestos y mando"
        }
    }

    private fun inProgress(): MatchRecord? =
        MainActivity.current?.takeIf { it.status == "en_curso" } ?: store.inProgress()

    private fun open(action: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ACTION, action))
    }
}
