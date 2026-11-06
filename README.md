# Pádel Puntos

Marcador de pádel para Android que suma puntos con un mando Bluetooth, canta el marcador desde el que saca y guarda todos los partidos para hacer estadísticas.

## Cómo se marca
**Mando Bluetooth** (⚙ Ajustes), dos modos:
- **Pulsaciones** (cualquier botón): 1 pulsación = A · 2 = B · 3 = anular. La app espera 0,7 s tras la última pulsación.
- **Un botón para cada función**: se asignan con "Asignar botones…" (pulsar el de A, el de B y el de anular). Sin esperas.

También se puede sumar tocando la franja de cada equipo; el botón ↶ siempre anula.

## Saque
Al empezar el partido se elige quién saca primero y quién saca después (del otro equipo). La rotación sigue sola:
1º, 2º, compañero del 1º, compañero del 2º. En el tie-break el primero saca 1 punto y luego cada uno 2.
El orden se mantiene igual durante todo el partido, también al cambiar de set (no se interrumpe nada).
El botón **🎾 Saque** (o tocar el panel superior) corrige quién saca en cualquier momento.
El marcador se canta **desde el que saca** ("quince, nada" si gana el punto el que saca; "nada, quince" si lo pierde).

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
`partido_id;n;fecha;hora;equipo;set;juego;marcador_previo;tiebreak;punto_oro;gana_juego;gana_set;origen;saca;equipo_saque`
- `marcador_previo`: marcador del juego antes del punto (p. ej. `30-40`, `AD-40`, o `5-6` en tie-break) · `origen`: mando / toque · `saca`: jugador que sacaba · `equipo_saque`: A/B

## ELO (en la app)
Empieza en 1500, K = 32. Opción (por defecto activada): la variación se multiplica por ×0,75 (muy igualado) a ×1,25 (paliza) según el % de juegos del ganador, corregido para no inflar al muy favorito. La fuerza de una pareja es la media de sus dos jugadores; ambos ganan o pierden los mismos puntos.
Inacabados (se elige en Historial → Jugadores): no cuentan · cuentan como victoria de quien iba por delante ·
en **proporción** (resultado = % de juegos ganados, peso = juegos jugados / 18; un partido cortado al principio apenas mueve el ELO).

## Clasificación de parejas
Historial → **Parejas** (y pestaña Parejas de la web): ordenada por **ELO de pareja** (cada pareja empieza en 1500, K = 32).
Para clasificar hacen falta 5 partidos; el resto aparece al final como "sin clasificar".

## Obtener el APK (GitHub)
Sube el contenido de esta carpeta al repositorio → pestaña **Actions** → "Compilar APK" → **Artifacts** → `PadelPuntos-apk`.
