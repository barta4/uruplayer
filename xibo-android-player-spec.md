# Xibo Android Player — Especificación de Desarrollo

> Player Android nativo compatible con cualquier Xibo CMS auto-hospedado o en cloud.  
> Al iniciar, la app solicita al usuario la **CMS URL** y el **Server Key** antes de operar.

---

## Configuración inicial requerida

Al primer arranque (o si no hay configuración guardada), la app muestra una pantalla de setup que solicita obligatoriamente:

| Campo | Descripción | Ejemplo |
|---|---|---|
| **CMS URL** | URL base del servidor Xibo | `https://urufile.online` |
| **Server Key** | Clave secreta configurada en el CMS | `yourServerKey123` |
| **Display Name** | Nombre con el que se registra el display | `Pantalla Recepción` |
| **Collection Interval** | Segundos entre ciclos de sincronización | `60` |

Estos valores se guardan en `SharedPreferences` y se pueden modificar luego con **5 taps en la esquina** de la pantalla principal.

El endpoint XMDS se construye automáticamente:
```
{CMS_URL}/xmds.php?v=5&method={METHOD_NAME}
```

---

## Servidor de referencia para desarrollo

- **CMS URL:** `http://urufile.online`
- **XMDS endpoint:** `http://urufile.online/xmds.php?v=5`
- **Protocolo:** SOAP RPC encoded, namespace `urn:xmds`
- **WSDL:** `http://urufile.online/xmds.php?v=5&wsdl`

---

## Stack tecnológico

| Componente | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| Min SDK | 21 (Android 5.0) |
| SOAP | ksoap2-android 3.6.4 |
| HTTP | OkHttp 4.12 |
| Background | WorkManager 2.9 |
| Base de datos | Room 2.6 |
| Imágenes | Glide 4.16 |
| Video | ExoPlayer / Media3 1.3 |

---

## Dependencias `build.gradle`

```groovy
implementation 'com.google.code.ksoap2-android:ksoap2-android:3.6.4'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
implementation 'androidx.work:work-runtime-ktx:2.9.0'
implementation 'androidx.room:room-runtime:2.6.1'
implementation 'com.github.bumptech.glide:glide:4.16.0'
implementation 'androidx.media3:media3-exoplayer:1.3.0'
implementation 'androidx.media3:media3-ui:1.3.0'
kapt 'androidx.room:room-compiler:2.6.1'
```

---

## Protocolo XMDS — Métodos implementados

El WSDL define 11 operaciones SOAP. Todas requieren `serverKey` y `hardwareKey`.

```
RegisterDisplay(serverKey, hardwareKey, displayName, clientType,
    clientVersion, clientCode, operatingSystem, macAddress,
    xmrChannel, xmrPubKey)
    → ActivationMessage (XML string)

RequiredFiles(serverKey, hardwareKey)
    → RequiredFilesXml (XML string)

GetFile(serverKey, hardwareKey, fileId, fileType,
    chunkOffset, chunkSize)
    → file (base64Binary)

Schedule(serverKey, hardwareKey)
    → ScheduleXml (XML string)

BlackList(serverKey, hardwareKey, mediaId, type, reason)
    → success (boolean)

SubmitLog(serverKey, hardwareKey, logXml)
    → success (boolean)

SubmitStats(serverKey, hardwareKey, statXml)
    → success (boolean)

MediaInventory(serverKey, hardwareKey, mediaInventory)
    → success (boolean)

GetResource(serverKey, hardwareKey, layoutId, regionId, mediaId)
    → resource (string HTML)

NotifyStatus(serverKey, hardwareKey, status)
    → success (boolean)

SubmitScreenShot(serverKey, hardwareKey, screenShot)
    → success (boolean)
```

> **Importante:** Agregar `?method=NombreMetodo` al final de cada URL de llamada SOAP para evitar rate limiting.

---

## Módulos a programar

### 1. `SetupActivity.kt`
Pantalla de primer arranque:
- Campos: CMS URL, Server Key, Display Name, Collection Interval
- Validar que la URL sea accesible antes de guardar (hacer ping al WSDL)
- Guardar en `SharedPreferences`
- Al guardar, navegar a `PlayerActivity`

---

### 2. `DeviceIdentity.kt`
- Generar `hardwareKey` único y persistente (UUID en SharedPreferences — **nunca regenerar**)
- Obtener MAC address del dispositivo
- Generar par de claves RSA para XMR (`xmrPubKey`) con `KeyPairGenerator`

---

### 3. `XmdsClient.kt`
Cliente SOAP genérico. Lee la URL base desde `SharedPreferences` en cada llamada.

```kotlin
class XmdsClient(private val context: Context) {

    private fun getCmsUrl() =
        PrefsManager(context).cmsUrl  // lee de SharedPreferences

    private fun call(method: String, params: Map<String, Any>): String {
        val envelope = SoapSerializationEnvelope(SoapEnvelope.VER11)
        val request = SoapObject("urn:xmds", method)
        // agregar serverKey, hardwareKey y params al request
        // POST a: ${getCmsUrl()}/xmds.php?v=5&method=$method
    }
}
```

---

### 4. `RegistrationManager.kt`
- Llamar `RegisterDisplay` con datos del dispositivo
- Parsear respuesta XML `<display>`:
  - `READY` → iniciar ciclo normal
  - `WAITING_APPROVAL` → mostrar pantalla de espera, reintentar en el próximo intervalo
  - `PLAYER_UPGRADE_REQUIRED` → mostrar aviso al usuario
- Si nunca se registró → redirigir a `SetupActivity`

---

### 5. `CollectionCycleWorker.kt` (WorkManager PeriodicWork)
Ejecutar en orden en cada ciclo:

```
1. RegisterDisplay      → verificar estado
2. RequiredFiles        → obtener lista de archivos necesarios
3. Schedule             → obtener schedule de reproducción
4. GetFile (por chunks) → descargar archivos faltantes (chunks de 512 KB)
5. MediaInventory       → reportar archivos descargados al CMS
6. NotifyStatus         → reportar estado actual
```

---

### 6. `FileManager.kt`
- Descargar archivos en chunks via `GetFile` (respuesta base64Binary — decodificar y concatenar)
- Verificar MD5 de cada archivo descargado
- Guardar en `context.filesDir/media/`
- Registrar en Room DB

```kotlin
@Entity
data class MediaFile(
    @PrimaryKey val fileId: Int,
    val fileType: String,    // "media" | "layout" | "resource"
    val md5: String,
    val path: String,
    val downloaded: Boolean
)
```

---

### 7. `ScheduleParser.kt`
Parsear XML de schedule:

```xml
<schedule>
  <default file="layoutId"/>
  <layout file="layoutId" fromdt="..." todt="..."
          scheduleid="..." priority="..."/>
</schedule>
```

- Determinar qué layout reproducir ahora según fecha/hora y prioridad
- Fallback al layout `default` si ningún schedule está activo

---

### 8. `LayoutParser.kt`
Parsear XLF (Xibo Layout Format):

```xml
<layout width="1920" height="1080" bgcolor="#000000">
  <region id="r1" width="960" height="1080" top="0" left="0">
    <media id="m1" type="image" duration="10">
      <options>
        <uri>filename.jpg</uri>
      </options>
    </media>
  </region>
</layout>
```

- Construir estructura `Layout → List<Region> → List<Media>`
- Cada `Media` tiene: tipo, duración, opciones

---

### 9. `PlayerActivity.kt`
- Pantalla completa, sin status bar ni navigation bar
- `FrameLayout` con posicionamiento absoluto de regiones
- Por tipo de media:

| Tipo | Vista |
|---|---|
| `image` | `ImageView` + Glide |
| `video` | `TextureView` + ExoPlayer |
| `webpage` / `html` | `WebView` |
| `text` / `ticker` | `TextView` con animación scroll |

- Respetar `duration` de cada media antes de pasar al siguiente
- Pre-cargar el siguiente layout para transición sin parpadeo

---

### 10. `StatusReporter.kt`
- `NotifyStatus` en cada ciclo con JSON:

```json
{
  "currentLayoutId": 3,
  "availableSpace": 1234567890,
  "lastActivity": "2025-01-01T00:00:00"
}
```

- `SubmitStats` con XML proof-of-play de lo reproducido
- `SubmitScreenShot` cuando el CMS lo solicite (flag en respuesta de `RegisterDisplay`)

---

### 11. `SettingsActivity.kt`
Acceso con **5 taps en la esquina superior izquierda** de `PlayerActivity`:
- Editar: CMS URL, Server Key, Display Name, Collection Interval
- Botón "Olvidar este dispositivo" (borra hardwareKey y reinicia el registro)
- Guardar en `SharedPreferences` y reiniciar el worker

---

## Estructura del proyecto

```
app/src/main/java/com/tuempresa/xiboplayer/
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   └── MediaFileDao.kt
│   ├── model/
│   │   ├── MediaFile.kt
│   │   ├── Layout.kt
│   │   └── ScheduleItem.kt
│   └── prefs/
│       └── PrefsManager.kt          ← lee CMS URL y Server Key
├── xmds/
│   └── XmdsClient.kt
├── player/
│   ├── LayoutParser.kt
│   ├── ScheduleParser.kt
│   └── PlayerActivity.kt
├── manager/
│   ├── FileManager.kt
│   ├── RegistrationManager.kt
│   └── StatusReporter.kt
├── worker/
│   └── CollectionCycleWorker.kt
└── ui/
    ├── SetupActivity.kt             ← pide CMS URL y Server Key
    └── SettingsActivity.kt
```

---

## Flujo de inicio de la app

```
App abre
   │
   ├── ¿Hay CMS URL y Server Key guardados?
   │       NO  →  SetupActivity (pedir URL + Server Key)
   │       SÍ  ↓
   │
   ├── RegisterDisplay
   │       WAITING_APPROVAL  →  pantalla de espera
   │       READY  ↓
   │
   ├── CollectionCycleWorker (periódico)
   │       → RequiredFiles → Schedule → GetFile → MediaInventory → NotifyStatus
   │
   └── PlayerActivity (foreground permanente)
            → reproduce schedule en pantalla completa
            → 5 taps  →  SettingsActivity
```

---

## Notas críticas para el agente programador

- SOAP usa `SoapSerializationEnvelope(SoapEnvelope.VER11)` con encoding `http://schemas.xmlsoap.org/soap/encoding/`
- `GetFile` devuelve base64Binary en chunks — concatenar todos los chunks antes de decodificar
- `hardwareKey` debe ser **persistente** — nunca borrar ni regenerar salvo acción explícita del usuario
- La URL del CMS y el Server Key se leen **siempre desde `PrefsManager`** — nunca hardcodear
- El player debe funcionar **offline** con el último schedule y media descargado
- Agregar siempre `?method=NombreMetodo` a la URL SOAP para evitar rate limiting (HTTP 429)
- Si recibe HTTP 429, esperar el valor del header `Retry-After` antes de reintentar
