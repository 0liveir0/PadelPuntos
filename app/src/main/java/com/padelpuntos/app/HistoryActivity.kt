package com.padelpuntos.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Historial de partidos, estadísticas por jugador (con ELO) y exportación a CSV. */
class HistoryActivity : ComponentActivity() {

    private lateinit var store: MatchStore
    private lateinit var list: ListView
    private lateinit var info: TextView
    private var view = 0             // 0 = partidos · 1 = jugadores · 2 = parejas
    private lateinit var unfinBtn: Button
    private var unfinishedMode = 0   // 0 = no cuentan · 1 = gana quien iba por delante · 2 = proporción
    private val unfinLabels = arrayOf("no cuentan", "gana quien iba por delante", "cuentan en proporción")
    private var shownMatches: List<MatchRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MatchStore(filesDir)
        unfinishedMode = getSharedPreferences("padel", Context.MODE_PRIVATE).getInt("unfinishedMode", 0)
        val pad = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(pad, pad, pad, pad)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 13f
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(btn("Partidos") { view = 0; refresh() })
        bar.addView(btn("Jugadores") { view = 1; refresh() })
        bar.addView(btn("Parejas") { view = 2; refresh() })
        bar.addView(btn("Exportar") { export() })

        info = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 13f; setPadding(pad, pad, pad, pad)
        }
        list = ListView(this)
        unfinBtn = Button(this).apply {
            textSize = 13f; isAllCaps = false
            setOnClickListener {
                unfinishedMode = (unfinishedMode + 1) % 3
                getSharedPreferences("padel", Context.MODE_PRIVATE).edit().putInt("unfinishedMode", unfinishedMode).apply()
                refresh()
            }
        }

        root.addView(bar)
        root.addView(unfinBtn)
        root.addView(info)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (view == 0) shownMatches.getOrNull(pos)?.let { confirmDelete(it) }
            true
        }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        unfinBtn.text = "Inacabados: ${unfinLabels[unfinishedMode]}  (tocar para cambiar)"
        unfinBtn.visibility = if (view != 0) View.VISIBLE else View.GONE
        when (view) { 1 -> showPlayerStats(); 2 -> showPairStats(); else -> showMatches() }
    }

    private fun showMatches() {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        shownMatches = store.all().filter { it.points.isNotEmpty() || it.status == "en_curso" }.reversed()
        info.text = if (shownMatches.isEmpty()) "Aún no hay partidos guardados."
        else "${shownMatches.size} partidos · mantén pulsado uno para borrarlo"
        val rows = shownMatches.map { r ->
            val s = MatchSummary(r)
            val end = if (r.end > 0) r.end else r.points.lastOrNull()?.time ?: r.start
            val min = (end - r.start) / 60000
            val estado = when (r.status) {
                "terminado" -> "TERMINADO"
                "inacabado" -> "INACABADO"
                else -> "EN CURSO"
            }
            val res = s.result.ifEmpty { "0-0" }
            val win = when {
                s.state.finished -> "🏆 ${r.teamName(s.state.winner)}"
                s.leader >= 0 -> "Iba ganando: ${r.teamName(s.leader)}"
                else -> "Empatados"
            }
            "#${r.id} · ${fmt.format(Date(r.start))} · $min min · $estado\n" +
                "${r.teamName(0)}  vs  ${r.teamName(1)}\n" +
                "$res   ·   puntos ${s.pointsWon[0]}-${s.pointsWon[1]}\n$win"
        }
        list.adapter = adapter(rows)
    }

    private fun showPlayerStats() {
        shownMatches = emptyList()
        val stats = store.playerStats(unfinishedMode)
        val how = when (unfinishedMode) {
            1 -> "los inacabados cuentan como victoria de quien iba por delante"
            2 -> "los inacabados mueven el ELO según el % de juegos y lo jugado"
            else -> "los inacabados no cuentan"
        }
        info.text = if (stats.isEmpty()) "Aún no hay estadísticas."
        else "Ordenado por ELO (empieza en 1500; $how).\n" +
            "J = jugados (incluye inacabados) · G = ganados · P = perdidos · I = inacabados"
        val rows = stats.mapIndexed { i, p ->
            "${i + 1}. ${p.name}   ·   ELO ${Math.round(p.elo)}\n" +
                "J ${p.played} · G ${p.won} · P ${p.lost} · I ${p.unfinished}\n" +
                "${p.winPct}% victorias · ${p.pointsPct}% de puntos ganados"
        }
        list.adapter = adapter(rows)
    }

    private fun showPairStats() {
        shownMatches = emptyList()
        val stats = store.pairStats(unfinishedMode)
        val min = MatchStore.MIN_PAIR_MATCHES
        info.text = if (stats.isEmpty()) "Aún no hay estadísticas."
        else "Clasificación por ELO de pareja (empieza en 1500). Para clasificar hacen falta $min partidos.\n" +
            "J = jugados · G = ganados · P = perdidos · I = inacabados"
        var rank = 0
        var headerDone = false
        val rows = mutableListOf<String>()
        for (p in stats) {
            val ranked = p.played >= min
            if (!ranked && !headerDone) { rows.add("— Sin clasificar (menos de $min partidos) —"); headerDone = true }
            val pos = if (ranked) "${++rank}. " else "· "
            val alias = if (p.alias.isNotBlank()) "  “${p.alias}”" else ""
            val dg = p.gamesWon - p.gamesLost
            rows.add(
                "$pos${p.name}$alias   ·   ELO ${Math.round(p.elo)}\n" +
                    "J ${p.played} · G ${p.won} · P ${p.lost} · I ${p.unfinished} · ${p.winPct}% victorias\n" +
                    "Juegos ${if (dg > 0) "+" else ""}$dg (${p.gamesWon}-${p.gamesLost}) · ${p.pointsPct}% de puntos"
            )
        }
        list.adapter = adapter(rows)
    }

    private fun adapter(rows: List<String>) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 15f
                v.typeface = Typeface.DEFAULT
                v.setTextColor(0xFFFFFFFF.toInt())
                v.setPadding(dp(8), dp(10), dp(8), dp(10))
                return v
            }
        }

    private fun confirmDelete(r: MatchRecord) {
        if (r.id == MainActivity.current?.id && r.status == "en_curso") {
            Toast.makeText(this, "Es el partido en curso: no se puede borrar", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("¿Borrar el partido #${r.id} (${r.teamName(0)} vs ${r.teamName(1)})?\nNo se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ -> store.delete(r.id); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun export() {
        try {
            val files = store.exportCsv(File(cacheDir, "export"))
            val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Pádel Puntos · partidos y puntos")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Exportar CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo exportar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
