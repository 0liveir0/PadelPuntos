package com.padelpuntos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/** Un partido guardado. Estado: "en_curso", "terminado" o "inacabado". */
class MatchRecord(
    val id: Int,
    val players: Array<String>,      // A1, A2, B1, B2
    val aliases: Array<String>,      // alias opcional de A y B
    val golden: Boolean,
    val start: Long,
    var end: Long,
    var status: String,
    var mode: String,                // "mano" o "brazos"
    var points: List<PointEvent>,
) {
    fun teamName(t: Int): String =
        aliases[t].ifBlank { "${players[t * 2]} y ${players[t * 2 + 1]}" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("players", JSONArray(players.toList()))
        put("aliases", JSONArray(aliases.toList()))
        put("golden", golden)
        put("start", start); put("end", end)
        put("status", status); put("mode", mode)
        put("points", JSONArray().apply {
            points.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.time); put("team", e.team); put("set", e.set); put("game", e.game)
                    put("before", e.scoreBefore); put("tb", e.tiebreak); put("gold", e.goldenPoint)
                    put("wg", e.wonGame); put("ws", e.wonSet); put("src", e.source)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): MatchRecord {
            fun arr(name: String, n: Int) = Array(n) { o.getJSONArray(name).optString(it, "") }
            val pts = o.getJSONArray("points")
            return MatchRecord(
                id = o.getInt("id"), players = arr("players", 4), aliases = arr("aliases", 2),
                golden = o.optBoolean("golden"), start = o.getLong("start"), end = o.optLong("end"),
                status = o.optString("status", "inacabado"), mode = o.optString("mode", "mano"),
                points = List(pts.length()) { i ->
                    val p = pts.getJSONObject(i)
                    PointEvent(
                        p.getLong("t"), p.getInt("team"), p.getInt("set"), p.getInt("game"),
                        p.optString("before"), p.optBoolean("tb"), p.optBoolean("gold"),
                        p.optBoolean("wg"), p.optBoolean("ws"), p.optString("src", "toque")
                    )
                }
            )
        }
    }
}

/** Resumen calculado de un partido (se obtiene rejugando sus puntos). */
class MatchSummary(val rec: MatchRecord) {
    val state: MatchState
    val pointsWon = IntArray(2)
    val gamesWon = IntArray(2)
    val result: String
    /** Equipo que va por delante (sets, luego juegos, luego puntos) o -1 si empate. */
    val leader: Int

    init {
        val m = PadelMatch().apply { goldenPoint = rec.golden }
        m.replay(rec.points)
        state = m.state
        rec.points.forEach { pointsWon[it.team]++ }
        state.setHistory.forEach { gamesWon[0] += it.first; gamesWon[1] += it.second }
        gamesWon[0] += state.games[0]; gamesWon[1] += state.games[1]
        val parts = state.setHistory.map { "${it.first}-${it.second}" }.toMutableList()
        if (!state.finished && (state.games.sum() > 0 || state.points.sum() > 0))
            parts.add("${state.games[0]}-${state.games[1]}")
        result = parts.joinToString(" ")
        leader = when {
            state.finished -> state.winner
            else -> cmp(state.sets).takeIf { it != -1 }
                ?: cmp(state.games).takeIf { it != -1 }
                ?: cmp(pointsWon.toList())
        }
    }

    private fun cmp(v: List<Int>) = when { v[0] > v[1] -> 0; v[1] > v[0] -> 1; else -> -1 }
}

/** Estadística acumulada de un jugador. */
class PlayerStats(val name: String) {
    var played = 0; var won = 0; var lost = 0; var unfinished = 0
    var pointsWon = 0; var pointsPlayed = 0
    var elo = 1500.0
    val winPct get() = if (won + lost == 0) 0 else Math.round(100.0 * won / (won + lost)).toInt()
    val pointsPct get() = if (pointsPlayed == 0) 0 else Math.round(100.0 * pointsWon / pointsPlayed).toInt()
}

/** Estadística acumulada de una pareja (los dos nombres, sin importar el orden). */
class PairStats(val players: List<String>) {
    val name get() = players.joinToString(" / ")
    var alias = ""
    var played = 0; var won = 0; var lost = 0; var unfinished = 0
    var gamesWon = 0; var gamesLost = 0; var pointsWon = 0; var pointsPlayed = 0
    var elo = 1500.0
    val winPct get() = if (won + lost == 0) 0 else Math.round(100.0 * won / (won + lost)).toInt()
    val pointsPct get() = if (pointsPlayed == 0) 0 else Math.round(100.0 * pointsWon / pointsPlayed).toInt()
}

/** Guarda cada partido en un fichero JSON propio dentro de la memoria interna de la app. */
class MatchStore(baseDir: File) {
    companion object { const val MIN_PAIR_MATCHES = 5 }
    private val dir = File(baseDir, "partidos").apply { mkdirs() }

    private fun file(id: Int) = File(dir, "$id.json")

    fun all(): List<MatchRecord> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { MatchRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.start }

    fun nextId(): Int = (all().maxOfOrNull { it.id } ?: 0) + 1

    fun save(r: MatchRecord) {
        val tmp = File(dir, "${r.id}.tmp")
        tmp.writeText(r.toJson().toString())
        tmp.renameTo(file(r.id))
    }

    fun delete(id: Int) { file(id).delete() }

    fun inProgress(): MatchRecord? = all().lastOrNull { it.status == "en_curso" }

    fun knownPlayers(): List<String> =
        all().flatMap { it.players.toList() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    // ---------- Estadísticas para la app ----------

    /**
     * Cuentan como jugados todos los partidos con algún punto (también los inacabados).
     * ELO: empieza en 1500, K = 32, la fuerza de una pareja es la media de sus dos jugadores.
     * Inacabados según [unfinishedMode]:
     *   0 = no cuentan (ni victoria/derrota ni ELO)
     *   1 = cuentan como victoria de la pareja que iba por delante (también en el ELO)
     *   2 = proporción: no suman victoria/derrota, pero mueven el ELO con
     *       resultado = % de juegos ganados y peso = juegos jugados / 18 (máx. 1)
     */
    fun playerStats(unfinishedMode: Int = 0): List<PlayerStats> {
        val map = LinkedHashMap<String, PlayerStats>()
        fun ps(n: String) = map.getOrPut(n) { PlayerStats(n) }
        for (rec in all()) {
            if (rec.points.isEmpty()) continue
            val sum = MatchSummary(rec)
            val team = arrayOf(listOf(ps(rec.players[0]), ps(rec.players[1])), listOf(ps(rec.players[2]), ps(rec.players[3])))
            val total = sum.pointsWon.sum()
            val winner: Int? = when {
                sum.state.finished -> sum.state.winner
                unfinishedMode == 1 && sum.leader >= 0 -> sum.leader
                else -> null
            }
            for (t in 0..1) for (p in team[t]) {
                p.played++
                p.pointsWon += sum.pointsWon[t]; p.pointsPlayed += total
                when (winner) {
                    null -> p.unfinished++
                    t -> p.won++
                    else -> p.lost++
                }
            }
            // resultado (0..1 para A) y peso del partido en el ELO
            val games = sum.gamesWon[0] + sum.gamesWon[1]
            val score: Pair<Double, Double>? = when {
                winner != null -> (if (winner == 0) 1.0 else 0.0) to 1.0
                unfinishedMode == 2 && games > 0 -> (sum.gamesWon[0].toDouble() / games) to minOf(1.0, games / 18.0)
                else -> null
            }
            if (score != null) {
                val ra = team[0].map { it.elo }.average()
                val rb = team[1].map { it.elo }.average()
                val expA = 1.0 / (1.0 + 10.0.pow((rb - ra) / 400.0))
                val delta = 32.0 * score.second * (score.first - expA)
                team[0].forEach { it.elo += delta }
                team[1].forEach { it.elo -= delta }
            }
        }
        return map.values.sortedByDescending { it.elo }
    }

    /**
     * Clasificación de parejas por ELO de pareja (empieza en 1500, K = 32), con la misma regla de
     * inacabados que [playerStats]. Las parejas con menos de [minMatches] partidos van al final, sin clasificar.
     */
    fun pairStats(unfinishedMode: Int = 0): List<PairStats> {
        val map = LinkedHashMap<String, PairStats>()
        fun pair(a: String, b: String): PairStats {
            val ps = listOf(a, b).sortedBy { it.lowercase() }
            return map.getOrPut(ps.joinToString("|")) { PairStats(ps) }
        }
        for (rec in all()) {
            if (rec.points.isEmpty()) continue
            val sum = MatchSummary(rec)
            val pr = arrayOf(pair(rec.players[0], rec.players[1]), pair(rec.players[2], rec.players[3]))
            val winner: Int? = when {
                sum.state.finished -> sum.state.winner
                unfinishedMode == 1 && sum.leader >= 0 -> sum.leader
                else -> null
            }
            val total = sum.pointsWon.sum()
            for (t in 0..1) {
                val p = pr[t]
                p.played++
                p.gamesWon += sum.gamesWon[t]; p.gamesLost += sum.gamesWon[1 - t]
                p.pointsWon += sum.pointsWon[t]; p.pointsPlayed += total
                when (winner) { null -> p.unfinished++; t -> p.won++; else -> p.lost++ }
                if (rec.aliases[t].isNotBlank()) p.alias = rec.aliases[t]
            }
            val games = sum.gamesWon[0] + sum.gamesWon[1]
            val score: Pair<Double, Double>? = when {
                winner != null -> (if (winner == 0) 1.0 else 0.0) to 1.0
                unfinishedMode == 2 && games > 0 -> (sum.gamesWon[0].toDouble() / games) to minOf(1.0, games / 18.0)
                else -> null
            }
            if (score != null) {
                val expA = 1.0 / (1.0 + 10.0.pow((pr[1].elo - pr[0].elo) / 400.0))
                val delta = 32.0 * score.second * (score.first - expA)
                pr[0].elo += delta; pr[1].elo -= delta
            }
        }
        return map.values.sortedWith(compareByDescending<PairStats> { it.played >= MIN_PAIR_MATCHES }.thenByDescending { it.elo })
    }

    // ---------- Exportación CSV ----------

    fun exportCsv(outDir: File): List<File> {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun c(s: String) = s.replace(";", ",").replace("\n", " ").trim()
        fun yn(b: Boolean) = if (b) "si" else "no"
        fun ab(t: Int) = when (t) { 0 -> "A"; 1 -> "B"; else -> "" }

        val matches = StringBuilder("﻿")
        matches.append("id;fecha;inicio;fin;duracion_min;A1;A2;B1;B2;nombre_A;nombre_B;estado;ganador;lider;sets_A;sets_B;juegos_A;juegos_B;puntos_A;puntos_B;resultado;punto_oro;modo_deteccion\n")
        val points = StringBuilder("﻿")
        points.append("partido_id;n;fecha;hora;equipo;set;juego;marcador_previo;tiebreak;punto_oro;gana_juego;gana_set;origen\n")

        for (r in all()) {
            if (r.points.isEmpty()) continue
            val s = MatchSummary(r)
            val end = if (r.end > 0) r.end else r.points.last().time
            val dur = ((end - r.start) / 60000).coerceAtLeast(0)
            matches.append(listOf(
                r.id, day.format(Date(r.start)), hm.format(Date(r.start)), hm.format(Date(end)), dur,
                c(r.players[0]), c(r.players[1]), c(r.players[2]), c(r.players[3]),
                c(r.teamName(0)), c(r.teamName(1)), r.status,
                if (s.state.finished) ab(s.state.winner) else "", if (s.leader == -1) "empate" else ab(s.leader),
                s.state.sets[0], s.state.sets[1], s.gamesWon[0], s.gamesWon[1], s.pointsWon[0], s.pointsWon[1],
                s.result, yn(r.golden), r.mode
            ).joinToString(";")).append('\n')
            r.points.forEachIndexed { i, e ->
                points.append(listOf(
                    r.id, i + 1, day.format(Date(e.time)), hms.format(Date(e.time)), ab(e.team), e.set, e.game,
                    e.scoreBefore, yn(e.tiebreak), yn(e.goldenPoint), yn(e.wonGame), yn(e.wonSet), e.source
                ).joinToString(";")).append('\n')
            }
        }
        val f1 = File(outDir, "partidos_$stamp.csv").apply { writeText(matches.toString()) }
        val f2 = File(outDir, "puntos_$stamp.csv").apply { writeText(points.toString()) }
        return listOf(f1, f2)
    }
}
