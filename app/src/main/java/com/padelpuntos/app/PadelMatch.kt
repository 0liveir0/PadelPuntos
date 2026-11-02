package com.padelpuntos.app

/** Estado inmutable de un partido de pádel (al mejor de 3 sets, tie-break a 7 en 6-6). */
data class MatchState(
    val points: List<Int> = listOf(0, 0),
    val games: List<Int> = listOf(0, 0),
    val sets: List<Int> = listOf(0, 0),
    val setHistory: List<Pair<Int, Int>> = emptyList(),
    val tiebreak: Boolean = false,
    val finished: Boolean = false,
    val winner: Int = -1,
)

class PadelMatch {
    var goldenPoint = false
    var names = arrayOf("Equipo A", "Equipo B")
    var state = MatchState()
        private set
    private val history = ArrayDeque<MatchState>()

    /** Suma un punto al equipo 0 (A) o 1 (B). Devuelve el texto a anunciar. */
    fun point(team: Int): String {
        if (state.finished) return "El partido ha terminado"
        history.addLast(state)
        val o = 1 - team
        val p = state.points.toMutableList()
        p[team]++
        val gameWon = when {
            state.tiebreak -> p[team] >= 7 && p[team] - p[o] >= 2
            goldenPoint && p[team] == 4 && p[o] == 3 -> true
            else -> p[team] >= 4 && p[team] - p[o] >= 2
        }
        if (gameWon) return winGame(team)
        if (!state.tiebreak && p[0] == 4 && p[1] == 4) { p[0] = 3; p[1] = 3 } // vuelve a iguales
        state = state.copy(points = p)
        return spokenPoints()
    }

    private fun winGame(team: Int): String {
        val o = 1 - team
        val g = state.games.toMutableList()
        g[team]++
        val wonSet = state.tiebreak || (g[team] >= 6 && g[team] - g[o] >= 2)
        if (!wonSet) {
            val tb = g[0] == 6 && g[1] == 6
            state = state.copy(points = listOf(0, 0), games = g, tiebreak = tb)
            return "Juego ${teamName(team)}. ${g[0]} a ${g[1]}" + if (tb) ". Tie break" else ""
        }
        val s = state.sets.toMutableList()
        s[team]++
        val finished = s[team] == 2
        state = MatchState(
            sets = s, setHistory = state.setHistory + (g[0] to g[1]),
            finished = finished, winner = if (finished) team else -1
        )
        return if (finished) "Juego, set y partido. Gana ${teamName(team)}"
        else "Set para ${teamName(team)}, ${g[0]} a ${g[1]}"
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        state = history.removeLast(); return true
    }

    fun reset() { history.clear(); state = MatchState() }

    /** Texto del marcador de puntos de un equipo para la pantalla. */
    fun pointLabel(team: Int): String {
        val p = state.points[team]; val o = state.points[1 - team]
        if (state.tiebreak) return p.toString()
        if (p >= 3 && o >= 3) return if (p > o) "AD" else "40"
        return listOf("0", "15", "30", "40")[p.coerceAtMost(3)]
    }

    fun spokenPoints(): String {
        val (a, b) = state.points
        if (state.tiebreak) return "$a, $b"
        if (a >= 3 && b >= 3) return when {
            a == b -> if (goldenPoint) "Punto de oro" else "Iguales"
            a > b -> "Ventaja ${teamName(0)}"
            else -> "Ventaja ${teamName(1)}"
        }
        val words = listOf("nada", "quince", "treinta", "cuarenta")
        return if (a == b) "${words[a]} iguales" else "${words[a]}, ${words[b]}"
    }

    fun teamName(t: Int) = names[t]
}
