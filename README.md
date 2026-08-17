# UruPlayer — Cliente Android para Xibo CMS

UruPlayer es un reproductor digital signage nativo para Android, diseñado para integrarse con servidores **Xibo CMS** (auto-hospedados o en la nube) mediante el protocolo XMDS SOAP.

## ✨ Características

- 🚀 **Nativo Android (Kotlin)**: Optimizado para rendimiento y bajo consumo de recursos en dispositivos Android / Android TV.
- ⚙️ **Configuración Inicial Simple**: Solicita CMS URL, Server Key y Display Name al primer inicio.
- 🔄 **Sincronización Automática**: Comunicación continua vía XMDS SOAP con soporte offline y caché local.
- 📺 **Reproducción Multimedia**:
  - Imágenes con Glide.
  - Video mediante ExoPlayer / AndroidX Media3.
  - Layouts y regiones configurables.
- 🛠️ **Configuración Oculta**: Acceso al menú de configuración mediante 5 toques en la esquina.

## 🛠️ Stack Tecnológico

- **Lenguaje:** Kotlin
- **Min SDK:** 21 (Android 5.0 Lollipop)
- **Target SDK:** 34+
- **SOAP / XMDS:** ksoap2-android
- **Networking:** OkHttp 3 / 4
- **Almacenamiento Local:** Room Database & SharedPreferences
- **Video:** AndroidX Media3 (ExoPlayer)
- **Imágenes:** Glide
- **Tareas en segundo plano:** AndroidX WorkManager

## 🚀 Instalación y Compilación

1. Clonar el repositorio:
   ```bash
   git clone https://github.com/barta4/uruplayer.git
   ```
2. Abrir el proyecto en Android Studio.
3. Sincronizar Gradle y compilar el APK:
   ```bash
   ./gradlew assembleDebug
   ```

---
Licencia: MIT / Propietaria según corresponda.
