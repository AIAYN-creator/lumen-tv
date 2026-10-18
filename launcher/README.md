# Lumen

Launcher y salvapantallas estilo Apple TV para el Chromecast con Google TV (HD).

## Compilar e instalar

```powershell
.\deploy.ps1
```

El script compila, instala en el Chromecast, copia las fotos de `..\wallpapers` y vuelve a la pantalla de inicio. Se reconecta solo cuando ADB se cae (le pasa a menudo al Chromecast).

## Fondos

Mete fotos (`.jpg`, `.png` o `.webp`) en la carpeta `wallpapers` del proyecto y ejecuta `.\deploy.ps1`. En el inicio, la foto cambia cada 10 minutos. En el salvapantallas, cada minuto y con un zoom lento. Si no hay fotos, sale la aurora animada.

## Datos en pantalla

- **Tiempo:** Open-Meteo.
- **Avisos de la AEMET:** el feed público de MeteoAlarm, zona "Litoral norte de Valencia".
- **Partidos:** el Valencia CF y el Levante salen del calendario de LaLiga de ESPN (resultados, próximo partido y directo). TheSportsDB se saltaba partidos, como el Deportivo 1–1 Levante. El basket sale de TheSportsDB, con el directo de la Euroliga desde ESPN. Si se cae la red, cada equipo sigue mostrando su último resultado bueno.
- **Metro:** el mismo servicio que usa el panel de "próximos trenes" de metrovalencia.es. No es una API oficial, así que puede dejar de funcionar si cambian la web. El sitio no envía su certificado intermedio de Sectigo, así que la app lo incluye (`res/raw`) y solo lo usa para ese dominio.

El puerto 5555 dura hasta que se reinicia el Chromecast. Después de un reinicio, mira el puerto nuevo en Opciones para desarrolladores → Depuración inalámbrica, conecta a ese puerto y ejecuta `adb tcpip 5555`.

## Qué se ha cambiado en el Chromecast

| Cambio | Comando | Cómo revertirlo |
| --- | --- | --- |
| Launcher de Google desactivado | `pm disable-user --user 0 com.google.android.apps.tv.launcherx` | `adb shell pm enable com.google.android.apps.tv.launcherx` |
| App de configuración de Google desactivada (tenía prioridad sobre el botón Home) | `pm disable-user --user 0 com.google.android.tungsten.setupwraith` | `adb shell pm enable com.google.android.tungsten.setupwraith` |
| Lumen como pantalla de inicio | `cmd package set-home-activity dev.lumen.tv/.MainActivity` | Se deshace al reactivar los dos de arriba |
| Lumen como salvapantallas | `settings put secure screensaver_components dev.lumen.tv/.dream.ScreensaverService` | `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop` |

| Mando del móvil (servicio de accesibilidad). `deploy.ps1` lo reactiva, porque Android lo apaga al forzar la detención de la app | `settings put secure enabled_accessibility_services dev.lumen.tv/dev.lumen.tv.remote.RemoteService` y `settings put secure accessibility_enabled 1` | `settings delete secure enabled_accessibility_services` |
| Lumen puede leer qué suena (Spotify, etc.) | `cmd notification allow_listener dev.lumen.tv/dev.lumen.tv.media.MediaListener` | `cmd notification disallow_listener dev.lumen.tv/dev.lumen.tv.media.MediaListener` |
| La tele no se suspende sola: el salvapantallas hace de panel permanente. Antes se apagaba a los 30 min sin tocar nada | `settings put secure sleep_timeout -1` | `settings put secure sleep_timeout 1800000` |
| Bloatware desactivado (ver abajo) | `pm disable-user --user 0 <paquete>` | `adb shell pm enable <paquete>` |
| Animaciones del sistema a 0,5× | `settings put global window_animation_scale 0.5` (y `transition_animation_scale`, `animator_duration_scale`) | Mismo comando con `1.0` |

Segunda ronda de limpieza: `com.google.android.syncadapters.calendar` (sincronización del calendario, unos 15 MB de RAM sin uso) y `android.autoinstalls.config.google.gtvpai` (instalaciones automáticas de apps promocionadas).

Paquetes desactivados por limpieza: `com.google.android.apps.tv.dreamx` (salvapantallas de Google), `com.google.android.backdrop`, `com.google.android.tvlauncher`, `com.google.android.videos`, `com.google.android.play.games`, `com.google.android.feedback`, `com.android.tv.feedbackconsent`, `com.google.android.marvin.talkback`, `com.google.android.tv.remotecontrol.logging`, `com.android.printspooler` y `com.android.dreams.basic`.

No se han tocado Play Store, los servicios de Google, el Chromecast integrado (para enviar contenido), el Asistente o la búsqueda por voz, ni el servicio del mando.

Para volver al Google TV de serie, ejecuta los `pm enable` y reinicia el Chromecast.

Para lanzar comandos en la tele con reconexión automática: `.\tv.ps1 "<comando>"`.

## Mando desde el móvil

Abre **http://192.168.0.18:8765** en el móvil (iPhone o Android, con el móvil en el WiFi de casa) y añádelo a la pantalla de inicio:

- **iPhone:** Safari → Compartir → "Añadir a pantalla de inicio".
- **Android:** Chrome → menú ⋮ → "Añadir a pantalla de inicio".

Qué hace:
- Superficie táctil: desliza para moverte y toca para aceptar.
- Botones de atrás, inicio, play/pausa, salvapantallas, modo noche y pista anterior/siguiente.
- El volumen **no** se puede controlar desde aquí. El Chromecast saca el HDMI siempre a volumen máximo, y esta Philips ignora el volumen por HDMI-CEC (lo probé: lo recibe y no hace nada). Para controlar el volumen hará falta el emisor de infrarrojos con ESP32 (fase 2).
- Lanzar apps, ver qué suena y escribir en la tele con el teclado del móvil.
- **Enviar pantalla:** los pasos para ver el móvil en la tele, según el sistema. Los Android usan el Chromecast integrado (Google Home → Comedor → "Enviar mi pantalla"). Los iPhone usan AirPlay a través de [AirScreen](https://play.google.com/store/apps/details?id=com.ionitech.airscreen), una app gratuita con anuncios instalada en la tele. Hay un botón que la abre desde el móvil. Lumen no hace el mirroring: solo explica los pasos y abre la app.

Cómo funciona: `RemoteService` es un servicio de accesibilidad de Lumen, la vía oficial de Android para que una app haga de mando. Además sirve la web en el puerto 8765 y solo responde a direcciones de la red local. También mantiene un "WiFi lock" que desactiva el ahorro de energía del WiFi; sin él, cada pulsación tardaba ~1 s.

Si el router le cambia la IP al Chromecast, cambia la dirección del mando. Conviene reservarle `192.168.0.18` en el DHCP del router.

## Notas, temporizador y alertas

- **Nota:** se escribe desde el mando del móvil, con firma y duración. Sale en el centro de la cabecera del salvapantallas y en el modo noche.
- **Temporizador:** minutos libres o atajos desde el móvil. Muestra la cuenta atrás en el salvapantallas, el modo noche y el móvil. En el último minuto aparece un aviso encima de cualquier app: cualquier botón del mando lo cancela y, si nadie lo toca, el Chromecast se suspende y apaga la tele por HDMI-CEC.
- **Alertas de partido:** goles (a favor y en contra) y rojas del Valencia y el Levante, encima de cualquier app. Salen de los eventos en directo de ESPN (LaLiga). Para ver cómo quedan: `POST /api/demo-alert`.
- **Modo noche:** de `NIGHT_START` a `NIGHT_END` (`Config.kt`, por defecto de 00:00 a 07:00). También se pone y se quita a mano con el botón de la luna, en el inicio y en el mando del móvil. Al ponerlo se abre el salvapantallas en modo noche. El cambio dura hasta el siguiente cambio del horario: si lo pones a las 23:00, sigue hasta las 07:00; si lo quitas a las 02:00, vuelve a la noche siguiente. Para previsualizarlo sin cambiar nada: `POST /api/key?k=nightpreview`.

## Apps propias

- **Radio:** COPE Valencia, COPE, Cadena 100, Rock FM y MegaStar. Sigue sonando en segundo plano y aparece en "Sonando" del salvapantallas. Si se queda 2 minutos en pausa, se libera sola.
- **Ambientes:** vídeos en bucle con reloj discreto. Se ponen en la carpeta `ambientes` del proyecto como `malvarrosa.mp4`, `chimenea.mp4`, `lluvia.mp4` y `bosque.mp4`, y `deploy.ps1` los copia (solo los que cambian).

- **Cámaras:** una cámara pública al azar de toda España, y OK pasa a la siguiente con un fundido suave. Las fuentes:
  - **DGT:** unas 1.960 cámaras de la red estatal, de su feed DATEX2 v3.7, con licencia CC BY. Son fotos que se renuevan cada pocos minutos; en la esquina salen la carretera, el km, la provincia, el sentido y la hora de la imagen.
  - **Ayuntamiento de València:** 680 cámaras de su geoportal (CC BY). Se ven en directo con su propio visor público, unos 18 s cada una, porque el visor corta a los 20 s. Ese visor prohíbe difundir las imágenes en otras webs: aquí solo se ven en casa, no se graban ni se publican.
    - **Cómo se ve en el Chromecast:** el visor de la ciudad va en una WebView normal, debajo de la capa de Compose, que se vuelve transparente cuando hay directo. Cada imagen del vídeo se copia a un `<canvas>`: el Chromecast pinta el `<video>` en una capa de hardware por debajo de la ventana, y cualquier cosa opaca encima lo tapa. Un vigilante en JavaScript da 0,6 s de margen (el visor va a 0,04 s del directo y el Chromecast se quedaba sin imagen), ajusta la velocidad al ritmo de cada cámara y vuelve a darle al play si el vídeo se para. La cámara solo aparece cuando su vídeo ya avanza; si en 8 s no arranca (cámara apagada), se salta.
- **GeoGuesser València:** 5 rondas de 2 minutos. La tele enseña una calle y cada uno marca en el mapa de su móvil dónde cree que está (QR en la sala de espera, o `http://<ip>:8765/geo`). Puntos: `5000 · e^(−metros/1500)`. Las fotos son de [Mapillary](https://www.mapillary.com) y el mapa de OpenStreetMap con estilo de CARTO. **Necesita un token de Mapillary:** guárdalo en `mapillary-token.txt`, en la carpeta del proyecto (no se sube a ningún sitio), y ejecuta `deploy.ps1`.

## Rendimiento (Chromecast HD: GPU y RAM justas)

- **Inicio:** el fondo se pre-renderiza en una sola imagen, las fichas animan el foco sin recomponer, y la versión release va compilada con R8 y AOT. Va a unos 30 fps al navegar (antes ~7), que es el techo del hardware.
- **Salvapantallas:** la aurora se pre-renderiza a media resolución una vez por segundo y el zoom lento de las fotos va a 10 fps. Antes repintaba degradados a pantalla completa sin parar, con la GPU saturada las 24 horas.
- **Memoria:** Cámaras, GeoGuesser y Ambientes van en un proceso aparte (`:apps`). Así el WebView y el reproductor no se quedan cargados en el launcher al cerrarlos.
- **Red:** el tiempo, los avisos, el metro, el bus y los partidos se descargan una vez y los comparten todas las pantallas (`Memo`). El feed de avisos pesa unos 750 KB, el servidor no lo comprime ni admite peticiones condicionales, y se pide como mucho cada 30 min.
- **Mando:** WebSocket persistente sin Nagle, más un WiFi lock que quita el ahorro de energía del WiFi. Unos 20–40 ms por pulsación.

## Si algo deja de funcionar

- **El mando del móvil no conecta:** comprueba que el móvil está en el WiFi de casa y que el Chromecast conserva la IP `192.168.0.18`. Si Lumen se detiene a la fuerza (desde Ajustes → Aplicaciones), Android apaga su servicio de accesibilidad: `.\deploy.ps1 -NoBuild` lo vuelve a activar.
- **Tras reiniciar el Chromecast,** ADB ya no escucha en el 5555. Mira el puerto nuevo en Opciones para desarrolladores → Depuración inalámbrica, ejecuta `adb connect <ip>:<puerto>` y luego `adb tcpip 5555`.
- **Si una actualización de Google TV reactiva su launcher,** repite los dos `pm disable-user` de la tabla de arriba.

### GeoGuesser: controles y modo administrador

- **En la tele:** OK avanza. Atrás durante una partida vuelve a la sala (mismos jugadores); en la sala, sale del juego. Al acabar, OK vuelve a la sala.
- **En el móvil:** "Salir de la partida" y "← Mando". Hay un QR pequeño en la pantalla de solución para unirse a mitad de partida.
- **Admin:** toca 5 veces seguidas "GeoGuesser · València" en el móvil y escribe el código de `lumen-admin.txt` (carpeta del proyecto; `deploy.ps1` lo copia a la tele). Ese móvil queda como admin y ve una ✕ junto a cada jugador para echarlo. Para dejar de ser admin, repite los 5 toques y deja el código vacío. Para cambiar el código, edita el fichero y vuelve a desplegar.

## Tipografía

[Inter](https://fonts.google.com/specimen/Inter) (variable, licencia SIL Open Font License), en `res/font/inter.ttf`.

## Ajustes

La ciudad, la zona de avisos, la estación de metro y las apps favoritas están en `app/src/main/java/dev/lumen/tv/Config.kt`.
