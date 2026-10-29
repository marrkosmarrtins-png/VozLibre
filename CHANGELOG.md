# Historial de versiones de VozLibre

Cada versión conserva su código completo (etiquetas git `v1.0.0-codeN` y
releases con el APK) para que se sepa **qué problemas se conocían y cómo se
solucionaron**. Ninguna versión se borra.

---

## Código 4 — Actualizaciones automáticas (Versión 1.0)

**Añadido:**

- Verificador de actualizaciones: consulta un `updates.json` publicado en el
  propio repositorio (rama `main`, GitHub raw). Sin librerías externas.
- Notificación «Nueva versión de VozLibre disponible» con botón
  **«Descargar ahora»**, revisada al activar el lector y cada 6 horas; solo
  avisa una vez por versión.
- Al entrar a la app, diálogo **«Actualización disponible»** con:
  nombre de versión, **tamaño del APK** y **descripción de novedades y
  arreglos**, botones **«Más tarde»** y **«Descargar»**.
- Descarga con el gestor de descargas de Android hacia la carpeta
  **`Descargas/VozLibre/`** del almacenamiento compartido, donde el instalador
  queda guardado por si hay que instalarlo a mano tras un error.
- Permiso `REQUEST_INSTALL_PACKAGES`: si Android no ha concedido aún
  «Instalar apps desconocidas» a VozLibre, se abre la pantalla de permiso y
  al volver continúa el flujo de instalación.
- Publicación versionada: releases de GitHub con el APK de cada versión y
  etiquetas que preservan el código de cada una.

**Problemas conocidos de esta versión:** los mismos del código 3 si el usuario
no acepta el permiso de exploración táctil; la app ahora lo detecta y guía.

---

## Código 3 — Exploración táctil real, motores TTS y control total (Versión 1.0)

**Problemas reportados en la prueba de campo → soluciones:**

| Problema | Solución |
|---|---|
| Tocar un elemento lo **pulsaba** (como si el lector no estuviera activo) | La exploración táctil de Android exige un permiso aparte («¿Usar VozLibre con la exploración táctil?»). La app ahora detecta si falta, avisa en voz alta, muestra su estado en pantalla y trae el botón **«Activar exploración táctil»**, que quita y vuelve a poner la bandera del servicio para que el aviso del sistema reaparezca. Con el permiso concedido: tocar = anunciar, doble toque = activar. |
| **Deslizar a la izquierda** no hacía nada | Ahora deslizar a la izquierda = elemento anterior SIEMPRE (si no había barrido activo, empieza por el último); deslizar a la derecha = elemento siguiente. |
| **No aparecía ningún motor de voz** instalado | La lista solo se llenaba si el TTS de prueba inicializaba con éxito; si el motor del sistema fallaba, quedaba vacía. Ahora `getEngines()` se consulta sin necesidad de inicializar y se listan TODOS los motores (del sistema e instalados por el usuario). Si el motor elegido no arranca, VozLibre cae automáticamente al motor del sistema. |
| La lectura se **quedaba atrapada en Google Chrome** (seguía leyendo el sitio aunque estuvieras fuera, y los marcos verdes de los botones del sitio quedaban por encima de otras apps) | La lectura continua y el barrido revalidan la ventana activa en cada paso y se cortan al cambiar de aplicación; el marco verde se limpia al cambiar de ventana. |
| **No se podía desactivar** el lector | La notificación de control incluye «Desactivar lector» (`disableSelf`), que apaga el servicio por completo desde cualquier pantalla. |

---

## Código 2 — Primera versión completa (Versión 1.0)

Primera versión con todas las funciones base publicadas en GitHub:

- Lectura continua que arrastra el scroll a su paso.
- Modo barrido con marco verde.
- Exploración táctil básica (anuncia lo tocado; dependía de que el usuario
  conociera el permiso de exploración táctil — se descubrió en pruebas que
  sin aceptarlo los toques se ejecutan: corregido en el código 3).
- Selección de motor TTS (por defecto el del sistema; el selector fallaba si
  el motor actual no inicializaba — corregido en el código 3).
- Vibración y sonidos de navegación con fuerza y volumen ajustables.
- Diálogo «Acerca de» y enlace al código fuente.
- Compilación automática con GitHub Actions.

**Problemas conocidos:** tocar pulsaba en vez de anunciar, deslizar a la
izquierda no navegaba, la lista de motores podía quedar vacía y la lectura no
se cortaba al cambiar de app (todos corregidos en el código 3).

---

## Código 1 — Prototipo inicial

Primera compilación de prueba: lectura continua y barrido. Se detectaron el
crash al abrir la app (color literal en `refreshStatus`) y el motor de voz
impuesto por el fabricante; ambos corregidos a partir del código 2.
