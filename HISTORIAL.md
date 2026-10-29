# HISTORIAL de versiones, problemas conocidos y soluciones

Cada versión de VozLibre se publica en un Release propio (etiqueta `vX.Y.Z`)
con su APK y su código fuente. **Nunca se eliminan las versiones antiguas**:
así cualquiera puede ver qué problemas se conocían en cada momento y cómo se
resolvieron.

---

## 1.0.3 — versionCode 4 (actual)

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
   **Solución:** se añadieron 7 earcons incluidos en la propia app (inicio,
   parada, siguiente, anterior, alerta, acierto, error) reproducidos con
   SoundPool. No se usan los tonos del sistema ni de otro lector.

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

- **El tacto pulsaba los elementos directamente en lugar de solo anunciarlos.**
  Causa: la exploración táctil necesita un permiso que Android pide en un
  diálogo aparte; sin él, los toques atravesaban la pantalla.
  Solución parcial: detección del estado + botón de reparación.
- **Deslizar a izquierda/derecha no hacía nada** (los gestos solo llegan
  con la exploración táctil concedida).
- **El lector se quedaba «pegado» leyendo Chrome** al salir del navegador:
  se añadió la validación de la ventana activa (dejar de leer al cambiar
  de app) y la limpieza del marco verde al cambiar de ventana.
- La lista de motores de voz se recargó sin depender de la inicialización
  del motor (arreglo incompleto, completado en 1.0.3).

## 1.0.1 — versionCode 2

- Convertido en lector de pantalla real: anuncia lo que se toca, con tipo
  y estado de cada elemento; notificación de control con botones; ajustes
  de vibración y sonido.
- **Problema conocido:** crash al abrir la pantalla principal en algunos
  Android antiguos por el uso de `getColor(int)` con recursos.

## 1.0.0 — versionCode 1

- Primera versión pública: lectura continua con arrastre de scroll,
  síntesis de voz configurable (ritmo y tono), botón «Acerca de» con
  enlace al código fuente.
- **Problema conocido:** crash de inicio por recursos de color; resuelto
  usando literales de color.
