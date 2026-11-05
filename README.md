# Pádel Puntos

Marcador de pádel para Android que suma puntos por gestos ante la cámara y guarda todos los partidos para hacer estadísticas.

## Gestos (se elige en ⚙ Ajustes)
| Modo | Equipo A | Equipo B | Deshacer último punto |
|---|---|---|---|
| Mano | ✊ puño | ✋ palma | 👎 pulgar abajo |
| Brazos | brazo izquierdo arriba | brazo derecho arriba | ❌ brazos cruzados en X por encima de los hombros |

**Mando Bluetooth** (opcional): botón grande = punto para A, botón pequeño = punto para B. Para anular, se elige en Ajustes: con el gesto de deshacer o con **doble pulsación rápida** del mando (en ese caso el gesto de anular se desactiva).
Se configura en ⚙ Ajustes → "Configurar mando" (detecta qué envía cada botón; si los dos envían lo mismo: 1 pulsación = A, 2 = B).

El gesto se mantiene ~1 s. Tras cada gesto hay 2,5 s de pausa y hay que bajar la mano/brazo antes del siguiente.
También se puede sumar tocando la franja de cada equipo, y deshacer con el botón ↶.

## Partidos
- **Nuevo**: se introducen los 2 jugadores de cada pareja (con autocompletado), alias opcional y punto de oro.
- El partido se **guarda tras cada punto**. Si se cierra la app, al volver pregunta si continuarlo.
- **⏹ Terminar**: cierra el partido en ese momento y lo guarda como **inacabado** con el marcador actual.
- Si se empieza otro sin terminar el actual, el anterior queda guardado como **inacabado** y también cuenta.
- **📊 Historial**: lista de partidos (mantener pulsado para borrar), estadísticas por jugador con **ELO**, y **Exportar CSV**.

## CSV exportados (separador `;`, UTF-8)
**partidos_AAAAMMDD_HHMM.csv** — una fila por partido:
`id;fecha;inicio;fin;duracion_min;A1;A2;B1;B2;nombre_A;nombre_B;estado;ganador;lider;sets_A;sets_B;juegos_A;juegos_B;puntos_A;puntos_B;resultado;punto_oro;modo_deteccion`
- `estado`: terminado / inacabado / en_curso · `ganador`: A/B (vacío si no terminó) · `lider`: quién iba por delante (A/B/empate)

**puntos_AAAAMMDD_HHMM.csv** — una fila por punto:
`partido_id;n;fecha;hora;equipo;set;juego;marcador_previo;tiebreak;punto_oro;gana_juego;gana_set;origen`
- `marcador_previo`: marcador del juego antes del punto (p. ej. `30-40`, `AD-40`, o `5-6` en tie-break) · `origen`: gesto / toque

## ELO (en la app)
Empieza en 1500, K = 32. La fuerza de una pareja es la media de sus dos jugadores; ambos ganan o pierden los mismos puntos.
Solo cuentan los partidos terminados. Los inacabados cuentan como jugados y en los puntos ganados.

## Obtener el APK (GitHub)
Sube el contenido de esta carpeta al repositorio → pestaña **Actions** → "Compilar APK" → **Artifacts** → `PadelPuntos-apk`.
