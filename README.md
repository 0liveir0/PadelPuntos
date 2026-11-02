# Pádel Puntos

Marcador de pádel para Android que suma puntos por gestos ante la cámara:

- ✊ **Puño cerrado** (mantenido ~1 s) → punto para el **Equipo A**
- ✋ **Palma abierta** (mantenida ~1 s) → punto para el **Equipo B**

Después de cada punto hay 2,5 s de pausa y hay que bajar la mano antes del siguiente, para evitar puntos dobles.
También se puede sumar tocando la franja de cada equipo.

Incluye: 15-30-40, iguales/ventaja o **punto de oro**, juegos, sets al mejor de 3, tie-break a 7 en 6-6,
**deshacer**, anuncio por **voz** del marcador, pitido y vibración, cámara trasera/frontal, vertical y horizontal.

## Obtener el APK sin instalar nada (GitHub)
1. Crea un repositorio nuevo en GitHub y sube el contenido de esta carpeta (incluida `.github`).
2. Pestaña **Actions** → se ejecuta "Compilar APK" (unos 5 min).
3. Abre la ejecución terminada → **Artifacts** → descarga `PadelPuntos-apk` (zip con el `.apk`).
4. Pásalo al móvil e instálalo (permitir "orígenes desconocidos").

## Con Android Studio
Abre la carpeta y pulsa *Run*. El modelo de gestos de MediaPipe se descarga solo en la primera compilación.

## Ajustes en el código (`MainActivity.kt`)
`HOLD_MS` (tiempo de gesto), `COOLDOWN_MS` (pausa entre puntos), `MIN_SCORE` (confianza mínima).
