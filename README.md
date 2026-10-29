# VozLibre — Lector de pantalla libre para Android

**VozLibre** es un lector de pantalla de código abierto (GPLv3) para personas
ciegas o con baja visión. Su función estrella es la **lectura continua que
arrastra la pantalla a su paso**: lee en voz alta todo lo que hay en pantalla
y, al llegar al final, desplaza automáticamente el scroll y sigue leyendo el
contenido nuevo, página tras página.

- 100 % código abierto, sin dependencias de terceros, sin rastreadores, sin anuncios.
- Interfaz y voz en español (funciona con cualquier motor TTS instalado, p. ej. Google TTS).
- Android 7.0 (API 24) o superior.

## Características

| Función | Descripción |
|---|---|
| ▶ **Lectura continua** | Lee la pantalla completa y **arrastra el scroll a su paso** (feeds, artículos, chats). Al terminar el contenido anuncia «Fin del contenido». |
| ▶▶ **Modo barrido** | Salta de elemento en elemento (botones, textos, campos) con marco verde visual. |
| ✋ **Exploración táctil** | Habla el elemento que está bajo tu dedo; doble toque para activarlo (como TalkBack). |
| 🎚 **Ajustes de voz** | Velocidad, tono y pausa de scroll configurables y guardadas. |
| 🤚 **Anuncios reales** | Dice el TIPO de cada elemento («botón», «casilla», «campo de texto»…) y su ESTADO («marcado», «seleccionado», «desactivado»), y anuncia el título de cada pantalla nueva. |
| 📳 **Vibración y sonido** | Vibración al anunciar elementos con fuerza ajustable (1–100 %) y sonidos de navegación con volumen ajustable. |
| 🗣 **Motor de voz del sistema** | Usa por defecto el motor TTS que ya tienes configurado en el sistema (p. ej. Reconocimiento de voz de Google); puedes cambiarlo desde la app. |
| 🔔 **Notificación de control** | Botones «Leer pantalla», «Barrido», «Silenciar» y **«Desactivar lector»** disponibles desde cualquier app. |
| 🖐 **Gestos** | Arriba = leer todo · Abajo = detener · Derecha = siguiente · Izquierda = anterior. |
| ⬇️ **Actualizaciones automáticas** | Consulta `updates.json` en GitHub, avisa con una notificación («Nueva versión disponible») y muestra un diálogo con nombre de versión, **tamaño** y **descripción de novedades/arreglos**, con botones **«Más tarde»** y **«Descargar»**. Baja el APK a `Descargas/VozLibre/`, pide el permiso de apps desconocidas y ofrece instalarlo. |
| ℹ️ **Acerca de** | Diálogo con la descripción completa y botón «Código fuente» que abre el repositorio. |

## Instalación del APK

1. Copia `VozLibre-release.apk` (o `VozLibre-debug.apk`) al teléfono.
2. Al abrirlo, acepta **«Instalar de origen desconocido»** si te lo pide.
3. Abre **VozLibre** y pulsa **«Abrir ajustes de accesibilidad»**.
4. Busca **VozLibre – Lector de pantalla libre** y actívalo (Android pedirá
   confirmación: VozLibre necesita observar la pantalla para poder leerla; no
   envía ningún dato a internet — puedes auditarlo, es libre).
5. Aparecerá la notificación de control: pulsa **«▶ Leer todo»** en cualquier
   pantalla y escucha cómo VozLibre lee y arrastra el contenido.

> Consejo: instala la voz española de **Google Text-to-Speech** (Ajustes →
> Gestión de textos en voz alta) para la mejor calidad de lectura.

## Compilar tú mismo

```bash
# Requisitos: JDK 17+, Android SDK (platform 34, build-tools 34.0.0)
./gradlew assembleDebug     # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # si existe signing/vozlibre.jks, APK firmado
```

O simplemente sube este proyecto a GitHub: el workflow
`.github/workflows/build.yml` compila el APK automáticamente en la nube y lo
publica como artefacto descargable en la pestaña *Actions*.

### Firma de release (opcional)

```bash
keytool -genkeypair -v -keystore signing/vozlibre.jks \
  -alias vozlibre -keyalg RSA -keysize 2048 -validity 10000
export VOZLIBRE_STORE_PASS=tu_contraseña
export VOZLIBRE_KEY_PASS=tu_contraseña
./gradlew assembleRelease
```

## Publicar en GitHub con tu token personal

```bash
El repositorio oficial es https://github.com/marrkosmarrtins-png/VozLibre

git clone https://github.com/marrkosmarrtins-png/VozLibre.git
```

Tras el `push`, entra en **Actions** en GitHub y descarga el APK compilado.

## Estructura del proyecto

```
VozLibre/
├── .github/workflows/build.yml   ← compilación automática en la nube
├── updates.json                  ← alimenta el verificador de actualizaciones
├── CHANGELOG.md                  ← historial de versiones: problemas y soluciones
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/vozlibre/reader/
│   │   ├── ReaderService.java    ← motor: lectura continua + scroll automático
│   │   ├── MainActivity.java     ← ajustes, guía y diálogo de actualización
│   │   ├── UpdateChecker.java    ← consulta updates.json (sin librerías)
│   │   ├── HighlightOverlay.java ← marco verde del elemento leído
│   │   └── Prefs.java            ← ajustes persistentes
│   └── res/                      ← interfaz en español + iconos
├── LICENSE                       ← GPLv3
└── README.md
```

## Publicar una versión nueva (para el mantenedor)

1. Sube `versionCode` en `app/build.gradle` y compila el APK firmado.
2. Crea un **release** en GitHub con la etiqueta `v1.0.0-codeN` y adjunta el APK.
3. Actualiza `updates.json` en la rama `main` (versión, tamaño real del APK, URL
   del release y descripción de novedades). La app instalada lo detecta,
   notifica y ofrece descargar la actualización.

## Limitaciones conocidas (v1.0)

- Los gestos y el «tocar para oír» requieren aceptar el permiso de **exploración
  táctil** que Android pide aparte (la app lo detecta, avisa en voz alta y trae
  el botón «Activar exploración táctil» para volver a mostrar el aviso).
- En apps con contenidos muy repetidos (mismo texto varias veces) la lectura
  continua puede omitir repeticiones para no re-leer lo ya leído tras el scroll.
- La descripción con IA de imágenes queda preparada para futuras versiones;
  la app solo consulta GitHub para buscar actualizaciones y nunca envía datos.

## Licencia

Copyright (C) 2026 — VozLibre se distribuye bajo **GNU GPLv3**: eres libre de
usar, estudiar, modificar y redistribuir este programa; toda obra derivada
debe mantenerse igualmente libre. Consulta el archivo `LICENSE`.
