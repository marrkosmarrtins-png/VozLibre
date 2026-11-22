# HISTORIAL de versiones, problemas conocidos y soluciones

Cada versión de VozLibre se publica en un Release propio (etiqueta `vX.Y.Z`)
con su APK y su código fuente. **Nunca se eliminan las versiones antiguas**:
así cualquiera puede ver qué problemas se conocían en cada momento y cómo se
resolvieron.

---

## 1.0 — versionCode 9 (reconstrucción estable, base de TalkBack)

### Qué se rompió en la 1.0.7 (versionCode 8) y por qué

1. **El lector no respondía a los dedos.** La 1.0.7 construyó un
   «explorador táctil propio» sobre `onMotionEvent`, asumiendo que el
   servicio recibiría los toques crudos. En Android moderno eso es falso:
   la documentación oficial dice que **si el servicio pide
   `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`, `onMotionEvent` NO recibe
   eventos de pantalla táctil** — el framework hace la exploración él
   mismo. Resultado: el lector quedaba sordo y, en el peor caso, el
   servicio interceptaba el flujo de toques sin hacer nada útil.
2. **La actualización dejó a usuarios sin lector.** Al instalar la
   actualización, Android desactiva el servicio; en Android 13+ puede
   además marcarlo como «ajuste restringido» y no se puede reactivar sin
   un paso manual (tres puntos → Permitir ajuste restringido). Sin guía,
   miles de usuarios quedaron sin lector y sin tocar.
3. **Motores de voz: solo los del sistema.** El manifiesto actual perdió
   la declaración `<queries>` del intent `TTS_SERVICE` (que ya existía en
   la 1.0.3): Android 11+ ocultó todos los motores instalados por el
   usuario (RHVoice, eSpeak, Acapela…).
4. **Las actualizaciones no se instalaban.** El manifiesto perdió también
   `REQUEST_INSTALL_PACKAGES` y el registro del proveedor `ApkProvider`.

### Correcciones de esta versión

1. **Arquitectura de la base de TalkBack, estable:** la exploración
   táctil la hace el SISTEMA (igual que con TalkBack) y VozLibre solo
   escucha sus eventos: tocar anuncia el elemento bajo el dedo en TODA la
   pantalla (apps, teclado, barra de estado, barra de navegación), el
   DOBLE TOQUE activa y el doble toque mantenido hace la pulsación
   larga. **VozLibre ya no captura nunca el flujo de toques**: aunque el
   lector fallara, el teléfono sigue respondiendo al tacto. Sin
   `onMotionEvent`, sin banderas que se quitan y ponen, sin trabajo
   pesado en el camino del tacto; todo evento envuelto en try/catch.
2. **Gestos (base de TalkBack, ejecución inmediata):** derecha o ABAJO =
   elemento siguiente; izquierda o ARRIBA = anterior; con un DESLIZADOR
   enfocado, arriba/abajo lo ajusta y anuncia su valor; DOS
   deslizamientos hacia ARRIBA seguidos = lectura continua; tocar la
   pantalla la DETIENE al instante (`TYPE_TOUCH_INTERACTION_START` y
   hover); DOS DEDOS arriba/abajo = desplazar la pantalla sin perder el
   foco (contenedor del foco → principal de la ventana → arrastre
   sintético).
3. **Se mantiene el autoenfoque de pantalla nueva:** al abrirse una app,
   un diálogo o un menú, el foco salta al PRIMER elemento de la pantalla
   nueva y se anuncia (primero el nombre de la pantalla). El barrido se
   reinicia sobre la ventana nueva y sigue el foco de la app.
4. **Motores de voz completos:** `<queries>` restaurada en el manifiesto;
   aparecen TODOS los motores instalados, del sistema y añadidos.
5. **Actualizaciones reparadas:** `REQUEST_INSTALL_PACKAGES` y el
   proveedor `ApkProvider` restaurados en el manifiesto.
6. **Guía tras actualizar:** si la versión cambió, el lector avisa con
   voz y notificación de que puede haber quedado desactivado o
   restringido, vuelve a pedir la exploración táctil y la pantalla
   principal muestra el diálogo paso a paso para reactivarlo.
7. **El servicio marcado como herramienta de accesibilidad**
   (`isAccessibilityTool`): es un lector de pantalla de propósito
   esencial, como TalkBack.
8. **versionName vuelve a la línea 1.0** (versionCode 9). La firma no
   cambia: instala directamente encima de la versión rota.

### Funciona sin nada más

- Sigue sin necesitar TalkBack ni ningún otro lector de pantalla ni
  servicios de Google: pide y sostiene la exploración táctil por sí
  mismo, y si el sistema la retira, la vuelve a pedir y guía.
- Si no hay ningún motor de voz, guía para instalar uno gratuito
  (RHVoice, eSpeak NG).

---

## 1.0.7 — versionCode 8

### Problemas que se conocían y se resolvieron en esta versión

1. **Al entrar en una pantalla nueva, el foco se quedaba en la pantalla
   anterior.** El usuario abría WhatsApp y el lector seguía enfocado en los
   botones de la pantalla de inicio: había que barrer TODA la pantalla
   anterior para descubrir que la pantalla ya era otra. Injusto y lento.
   **Solución (arquitectura TalkBack, transición de ventanas):** al llegar
   `TYPE_WINDOW_STATE_CHANGED`, VozLibre espera un instante (220 ms) a que
   la app termine de abrirse, resuelve la ventana de APLICACIÓN activa
   (`getWindows()`: gana la enfocada; se excluyen el teclado, las capas de
   accesibilidad y las ventanas del PROPIO VozLibre) y **AUTOENFOCA el
   PRIMER ELEMENTO de la pantalla nueva**, anunciándolo con su sonido de
   ventana (primero el nombre de la pantalla —«WhatsApp»— y después el
   elemento). En modo barrido, recoge la lista de la ventana NUEVA y salta
   a su primer elemento. Si el dedo del usuario está en la pantalla, el
   autoenfoque espera: no pelea con la exploración táctil. La misma
   ventana ya activa (cambios de título de paneles web, teclado) NO roba
   el foco otra vez.

2. **El clic debía responder desde cualquier parte de la pantalla.** Si un
   campo de edición está abajo y el usuario hace el clic arriba o a la
   izquierda, el clic iba al elemento que hubiera bajo el dedo (o a nada).
   **Solución (modelo de foco de TalkBack):** el lector recuerda SIEMPRE
   cuál es el elemento enfocado (toque, barrido, foco de la app o
   autoenfoque de pantalla nueva) y **el doble toque EN CUALQUIER PARTE de
   la pantalla activa ESE elemento**, sin importar el lado donde caiga el
   doble toque. Respaldo en cascada: elemento bajo el dedo → toque real en
   el punto. La pulsación larga (doble toque mantenido) también actúa
   sobre el centro del elemento enfocado si el dedo cayó en un hueco.

3. **La lectura continua no se detenía al tocar la pantalla.** **Solución
   (gesto de TalkBack):** con la lectura continua activa (deslizar hacia
   arriba), un simple toque pone silencio AL INSTANTE y deja explorar.

4. **Faltaba el desplazamiento con DOS DEDOS, que tiene todo lector de
   pantalla.** **Solución (gestos de TalkBack):** deslizar con DOS DEDOS
   hacia arriba desplaza la pantalla hacia abajo (aparece el contenido de
   abajo) y hacia abajo retrocede, SIN cambiar el elemento enfocado. Funciona
   por dos caminos: los gestos `GESTURE_2_FINGER_SWIPE_*` del sistema
   (Android 10+) y una detección propia del arrastre de dos dedos en
   `onMotionEvent` (Android 7+), con desduplicación para no desplazar dos
   veces. Va al contenedor desplazable del elemento enfocado; si no hay, al
   principal de la ventana; si no, a un arrastre sintético con dirección.

5. **El barrido no saltaba a la app nueva.** Al cambiar de aplicación
   mientras se barre, el lector se quedaba callado con la lista vieja.
   **Solución:** si la app ya no coincide, el barrido se reinicia solo sobre
   la ventana nueva y anuncia su primer (o último) elemento.

### Funciona sin nada más

- Sigue sin necesitar TalkBack ni ningún otro lector de pantalla ni
  servicios de Google: pide y sostiene la exploración táctil por sí mismo.
- Si no hay ningún motor de voz, guía para instalar uno gratuito
  (RHVoice, eSpeak NG).

---

## 1.0.6 — versionCode 7

### Problemas que se conocían y se resolvieron en esta versión

1. **No se oía NINGÚN sonido (ni clic, ni diálogos, ni nada).**
   Causa real: los sonidos iban por `USAGE_ASSISTANCE_SONIFICATION`, que
   Android dirige al flujo del SISTEMA (STREAM_SYSTEM), un volumen que en
   muchos teléfonos está bajo o silenciado y que NO es el que sube el
   usuario. Además, SoundPool carga los archivos en segundo plano y los
   sonidos pedidos antes de terminar la carga se perdían.
   **Solución (basada en TalkBack, cuyos earcons van por medios):** los
   sonidos van ahora por `USAGE_MEDIA` (STREAM_MUSIC), el MISMO flujo por
   donde habla la voz: si se oye la voz, se oyen los sonidos. Los sonidos
   pedidos antes de la carga quedan pendientes y suenan al cargarse.
   Los 11 earcons se re-sintetizaron propios (dominio público) más
   claros y distinguibles: foco, clic, ventana, scroll, inicio, parada,
   siguiente, anterior, alerta, acierto y error.

2. **El foco no iba donde toca el dedo: la barra de inicio, la barra de
   navegación y otras zonas callaban.**
   **Solución:** el hit-test propio ahora recorre TODAS las ventanas,
   incluido el TECLADO (antes se saltaba: tocar teclas callaba). Si no
   hay ningún elemento bajo el dedo, VozLibre NUNCA se queda mudo:
   - barra de estado: dice la hora y el nivel de batería;
   - barra de navegación del sistema: lo anuncia y el doble toque
     envía un toque real al punto (Atrás/Inicio/Recientes funcionan);
   - zona vacía: «Zona sin elementos».

3. **Los deslizamientos debían ser SÚPER rápidos.**
   **Solución:** primer gesto ejecutado a 250 ms (antes 900), lectura
   arranca a 300 ms (antes 800), pausa entre páginas 250 ms por defecto
   (antes 450) y los desplazamientos de lista esperan solo 150–600 ms.
   Respuesta inmediata con `QUEUE_FLUSH` en navegación y exploración.

4. **Faltaba gestionar las VOCES: cargar todas, idioma del motor y voces
   del idioma.**
   **Solución:** en Ajustes hay ahora «Idioma del motor de voz»,
   «Voces disponibles para el idioma» y el botón «Cargar todas las
   voces» (una sonda temporal consulta `getVoices()` del motor elegido,
   con reintento porque algunos motores informan las voces tarde).
   La voz elegida se guarda y se aplica en cada arranque
   (`setVoice` por nombre → respaldo idioma → respaldo español).

5. **Deslizadores no ajustables.**
   **Solución (gesto de TalkBack):** con un deslizador enfocado en el
   barrido, deslizar hacia ARRIBA lo aumenta y hacia ABAJO lo baja:
   1) `ACTION_SET_PROGRESS` con el valor nuevo; 2) si no, scroll del
   nodo; 3) si no, arrastre vertical sintético. Tras ajustar anuncia el
   valor («70 por ciento»). Al barrer, todo deslizador también dice su
   valor actual.

### Funciona sin nada más

- Sigue sin necesitar TalkBack ni ningún otro lector de pantalla ni
  servicios de Google: pide y sostiene la exploración táctil por sí mismo.
- Si no hay ningún motor de voz, guía para instalar uno gratuito
  (RHVoice, eSpeak NG).

---

## 1.0.5 — versionCode 6

### Problemas que se conocían y se resolvieron en esta versión

1. **El lector no seguía el foco: se quedaba «donde te quedaste».**
   Cuando la app movía el foco (abrir un diálogo, saltar a un campo, abrir
   una pantalla nueva), VozLibre seguía anunciando el elemento anterior.
   **Solución:** se procesa `TYPE_VIEW_FOCUSED` (y el foco de accesibilidad,
   con guarda anti-eco): el lector salta al elemento enfocado, lo anuncia al
   momento y desplaza su marco verde; en barrido, sincroniza su índice con
   el foco (como el `AccessibilityFocusInterpreter` de TalkBack).

2. **Los menús de la pulsación larga no se detectaban.**
   Al hacer pulsación larga en una app, el menú emergente se abría pero el
   barrido seguía navegando por la lista vieja de la pantalla de abajo.
   **Solución:** al cambiar de ventana (`TYPE_WINDOW_STATE_CHANGED`) el
   barrido vuelve a recoger los elementos de la ventana ENFOCADA
   (`getWindows()` + `flagRetrieveInteractiveWindows`), así los menús y
   diálogos emergentes se navegan como cualquier otra pantalla. También se
   anuncia la ventana nueva con su sonido propio.

3. **Al tocar ciertas partes de la pantalla el lector no hacía nada.**
   Los eventos hover solo llegan si la vista los genera: en áreas con vistas
   «sordas» el lector callaba; y sin exploración táctil concedida no había
   respuesta alguna.
   **Solución:** explorador táctil PROPIO con la arquitectura de TalkBack
   (clase `TouchExplorer`): el servicio recibe los toques crudos
   (`onMotionEvent`, bandera `flagRequestTouchExplorationMode` con la
   capacidad `canRequestTouchExplorationMode="true"`) y hace su propio
   hit-test del nodo bajo el dedo sobre TODAS las ventanas (de la capa más
   alta a la más baja). Toca donde toques, responde. Desduplicación con los
   eventos hover del sistema para no anunciar dos veces.

4. **Las listas no se desplazaban al barrer: siempre los mismos cuatro textos.**
   **Solución:** al llegar al último elemento visible, el barrido busca el
   contenedor desplazable (ancestro del elemento o de la ventana), ejecuta
   `ACTION_SCROLL_FORWARD` (o `BACKWARD` hacia atrás) y continúa con los
   elementos nuevos tras una pausa corta. El mismo mecanismo desplaza las
   páginas en la lectura continua, ahora con un sonido de scroll propio.

5. **Los diálogos no tenían sonidos y faltaban sonidos por acción.**
   **Solución:** se incorporaron 11 earcons REALES del pack «Interface
   Sounds» de Kenney (licencia CC0), descargados e integrados en la app:
   foco, clic, ventana/diálogo, scroll, inicio, parada, siguiente, anterior,
   alerta, acierto y error. Cada acción del lector tiene su sonido: tocar
   (foco), activar (clic), pantalla o diálogo nuevo (ventana), desplazar
   (scroll), etc. Se reproduce con SoundPool (latencia mínima).

6. **Velocidad.** El usuario pidió «velocidad extrema, ultra rápida».
   **Solución:** ritmo de voz por defecto 2.5x (ajustable 0.5x–5x), pausa
   entre páginas reducida (450 ms por defecto), arranque de lectura sin el
   retardo largo, confirmación del primer gesto acelerada y anuncios con
   `QUEUE_FLUSH` para respuesta inmediata en navegación y exploración.

### Funciona sin nada más

- Sigue sin necesitar TalkBack ni ningún otro lector de pantalla ni
  servicios de Google: pide y sostiene la exploración táctil por sí mismo.
- Si no hay ningún motor de voz, guía para instalar uno gratuito
  (RHVoice, eSpeak NG).

---

## 1.0.3 — versionCode 4/5

### Problemas que se conocían y se resolvieron en esta versión

1. **VozLibre dependía de otro lector para responder al tacto.**
   La exploración táctil solo se activaba cuando el lector secundario del
   usuario se activaba; en un teléfono sin TalkBack (sin servicios de
   Google) el lector no respondía a los dedos.
   **Solución:** VozLibre ahora pide y sostiene la exploración táctil por
   sí mismo: al activar el servicio la solicita, guía con su propia voz
   para aceptar el diálogo del sistema, reintenta el permiso desde el botón
   «Activar exploración táctil», y avisa con su voz y un sonido propio
   cuando el primer gesto queda confirmado. Si la exploración se apaga, la
   vuelve a pedir. Ya no hace falta ningún otro lector.

2. **No tenía sonidos propios (para diálogos y avisos).**
   **Solución:** se añadieron earcons incluidos en la propia app
   reproducidos con SoundPool. No se usan los tonos del sistema ni de otro
   lector.

3. **La lista de motores de voz solo mostraba los del sistema.**
   En Android 11 o superior los paquetes están ocultos por defecto
   (visibility filtering) y `getEngines()` devolvía muy pocos motores;
   además la carga dependía de que el motor inicializara con éxito.
   **Solución:** se añadió `<queries>` para el intent TTS_SERVICE en el
   manifiesto, un explorador propio (`TtsScanner`) que combina
   `getEngines()` con una consulta a PackageManager, y un botón «Volver a
   buscar motores de voz». Ahora aparecen todos los motores instalados.
   Si el motor elegido falla, VozLibre reintenta con otro disponible.

4. **Novedades de esta versión:** sistema de actualizaciones automáticas
   (notificación + diálogo con versión, tamaño y novedades, botones
   «Más tarde» y «Descargar»), permiso REQUEST_INSTALL_PACKAGES, carpeta
   interna `updates` donde queda guardado el APK descargado por si la
   instalación falla, y botón «Desactivar lector» en la notificación.

### Problemas conocidos pendientes

- En Android 13+ los APK instalados fuera de una tienda pueden quedar con
  el «ajuste restringido»: hay que pulsar «Permitir ajuste restringido» en
  los detalles de la app antes de activar el servicio.
- Si el usuario rechazó una vez el diálogo del sistema de exploración
  táctil, Android no lo vuelve a mostrar hasta que el servicio se
  desactiva y se reactiva manualmente (limitación del sistema).
- La lectura continua puede saltarse elementos sin texto en algunas apps
  con listas virtuales (p. ej. ciertas apps de mensajería).

---

## 1.0.2 — versionCode 3

- Exploración táctil real: aviso por voz y botón propio cuando falta el
  permiso del sistema.
- Deslizar a la izquierda = elemento anterior; derecha = siguiente.
- Lista completa de motores TTS y respaldo automático si el motor elegido
  falla.
- La lectura se corta al cambiar de app; marcos verdes limpiados.
- «Desactivar lector» en la notificación.
