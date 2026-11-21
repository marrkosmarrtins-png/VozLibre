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
| ▶▶ **Modo barrido** | Salta de elemento en elemento con marco verde y **desplaza las listas solas**: al llegar al último elemento visible, el contenedor avanza y siguen los elementos nuevos. |
| ✋ **Exploración táctil propia** | Sigue tu dedo por TODA la pantalla (hit-test propio sobre todas las ventanas); doble toque activa y doble toque mantenido hace la pulsación larga. | 
| 🎯 **Sigue el foco** | Anuncia los diálogos, los menús emergentes de la pulsación larga y los elementos que la app enfoca. |
| 🎚 **Ajustes de voz** | Velocidad (0.5x–5x, por defecto 2.5x), tono y pausa de scroll configurables y guardadas. |
| 🌐 **Idioma y voces** | «Cargar todas las voces» del motor, selector de **idioma** y selector de **voces disponibles para ese idioma**; la elección se recuerda y se aplica en cada arranque. |
| 🎚️ **Deslizadores ajustables** | Con un deslizador enfocado, deslizar arriba/abajo lo ajusta (como TalkBack) y anuncia su valor («70 por ciento»). |
| 🗣 **Anuncios reales** | Dice el TIPO de cada elemento («botón», «casilla», «campo de texto»…) y su ESTADO («marcado», «seleccionado», «desactivado»), y anuncia el título de cada pantalla nueva. |
| 🔊 **Sonidos para cada acción** | 11 earcons propios (dominio público) por el MISMO flujo de audio que la voz (STREAM_MUSIC): si se oye la voz, se oyen los sonidos. Foco, clic, ventana/diálogo, scroll, inicio, parada, siguiente, anterior, alerta, acierto y error. |
| 📳 **Vibración** | Vibración al anunciar elementos con fuerza ajustable (1–100 %). |
| 🗣 **Motor de voz a elección** | Lista TODOS los motores TTS instalados (sistema y añadidos, p. ej. RHVoice o eSpeak NG) y reintenta con otro si el elegido falla. |
| 🔔 **Notificación de control** | Botones «Leer pantalla», «Barrido», «Silenciar» y «Desactivar lector» disponibles desde cualquier app. |
| 🖐 **Gestos** | Arriba = leer todo · Abajo = detener (o ajustar el deslizador enfocado) · Derecha = siguiente · Izquierda = anterior. |
| ⬆️ **Actualizaciones automáticas** | Notificación y diálogo con versión, tamaño y novedades; descarga a una carpeta propia e instalación con permiso del sistema. |
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
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/vozlibre/reader/
│   │   ├── ReaderService.java    ← motor: explorador táctil propio, lectura
│   │   │                            continua, barrido con scroll, foco, sonidos
│   │   ├── MainActivity.java     ← ajustes, motores TTS y actualizaciones
│   │   ├── TtsScanner.java       ← enumera TODOS los motores de voz
│   │   ├── UpdateManager.java    ← actualizaciones automáticas
│   │   ├── Earcons.java          ← sonidos propios por acción (STREAM_MUSIC)
│   │   ├── HighlightOverlay.java ← marco verde del elemento leído
│   │   └── Prefs.java            ← ajustes persistentes
│   └── res/raw/                  ← 11 earcons .wav propios (dominio público)
├── update.json                   ← manifiesto de actualizaciones
├── HISTORIAL.md                  ← problemas y soluciones por versión
├── LICENSE                       ← GPLv3
└── README.md
```

## Créditos de sonido

Los 11 earcons de `app/src/main/res/raw` son **sintetizados por el propio
proyecto** (senos, barridos y ruido con envolventes, mono 44.1 kHz PCM 16
bits): se publican con VozLibre bajo **dominio público**, sin licencias de
terceros ni atribución obligatoria. Van por el flujo de medios
(STREAM_MUSIC), el mismo por donde habla la voz.

## Licencia

Copyright (C) 2026 — VozLibre se distribuye bajo **GNU GPLv3**: eres libre de
usar, estudiar, modificar y redistribuir este programa; toda obra derivada
debe mantenerse igualmente libre. Consulta el archivo `LICENSE`.
