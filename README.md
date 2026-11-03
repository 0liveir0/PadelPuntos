# Pádel Puntos

Marcador de pádel para Android que suma puntos por gestos ante la cámara. En **⚙ Ajustes** se eligen:

- **Nombres** de los dos equipos (se usan en pantalla y en el anuncio por voz).
- **Modo de detección**:
  - ✊ Puño cerrado = equipo A · ✋ Palma abierta = equipo B
  - Brazo **izquierdo** levantado = equipo A · Brazo **derecho** levantado = equipo B
    (izquierdo/derecho del propio jugador, con la muñeca por encima de la cabeza)
- **Deshacer el último punto**: ❌ brazos cruzados en X por encima de los hombros (modo brazos) o 👎 pulgar abajo (modo mano).
- **Punto de oro** (sin ventajas).

El gesto hay que mantenerlo ~1 s. Tras cada punto hay 2,5 s de pausa y hay que bajar la mano/brazo antes del siguiente.
También se puede sumar tocando la franja de cada equipo.

Incluye: 15-30-40, iguales/ventaja o punto de oro, juegos, sets al mejor de 3, tie-break a 7 en 6-6,
deshacer, voz, pitido y vibración, cámara trasera/frontal, vertical y horizontal. Los ajustes se guardan.

## Obtener el APK sin instalar nada (GitHub)
1. Crea un repositorio nuevo en GitHub y sube el contenido de esta carpeta (incluida `.github`).
2. Pestaña **Actions** → se ejecuta "Compilar APK" (unos 5 min).
3. Abre la ejecución terminada → **Artifacts** → descarga `PadelPuntos-apk` (zip con el `.apk`).
4. Pásalo al móvil e instálalo (permitir "orígenes desconocidos").

## Con Android Studio
Abre la carpeta y pulsa *Run*. El modelo de gestos de MediaPipe se descarga solo en la primera compilación.

## Ajustes en el código (`MainActivity.kt`)
`HOLD_MS` (tiempo de gesto), `COOLDOWN_MS` (pausa entre puntos), `MIN_SCORE` (confianza mínima).
