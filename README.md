# Pádel Puntos

Marcador de pádel para Android que suma puntos por gestos ante la cámara y guarda todos los partidos para hacer estadísticas.

## Cómo se marca (⚙ Ajustes)
**Gestos para sumar puntos** (a elegir): ninguno · mano (✊ puño = A, ✋ palma = B) · brazos (izquierdo arriba = A, derecho arriba = B).
El gesto se mantiene ~1 s; después hay 2,5 s de pausa y hay que bajar la mano/brazo antes del siguiente.

**Anular con gesto** (opcional): **brazos en cruz**, los dos extendidos a los lados a la altura de los hombros (en T), ~1 s.

**Mando Bluetooth** (cualquier botón): **1 pulsación = A · 2 = B · 3 = anular**. La app espera 0,7 s tras la última pulsación
para decidir y muestra las pulsaciones contadas debajo de la cámara. Se empareja en Ajustes de Android → Bluetooth.

También se puede sumar tocando la franja de cada equipo, y el botón ↶ de la pantalla siempre anula.

## Portada
Al abrir la app aparece la portada con **INICIAR PARTIDA** (y **Continuar partido** si hay uno en curso, e **Historial**).
Cuando un partido termina (porque alguien gana o con ⏹ Terminar) la app ofrece **Salir a la portada**.
El botón Atrás del marcador también vuelve a la portada; el partido en curso queda guardado.

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
Inacabados (se elige en Historial → Jugadores): no cuentan · cuentan como victoria de quien iba por delante ·
en **proporción** (resultado = % de juegos ganados, peso = juegos jugados / 18; un partido cortado al principio apenas mueve el ELO).

## Obtener el APK (GitHub)
Sube el contenido de esta carpeta al repositorio → pestaña **Actions** → "Compilar APK" → **Artifacts** → `PadelPuntos-apk`.
