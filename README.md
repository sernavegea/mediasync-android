# MediaSync — app Android

App familiar que sincroniza fotos, vídeos, música, audios y documentos del móvil con el **servidor MediaSync** del PC de casa, que los guarda organizados.

## Descargar el APK
Cada vez que se sube un cambio, GitHub compila la app sola (pestaña **Actions**) y publica el APK en **Releases**.

1. Entra en *Releases* y descarga `MediaSync.apk` (o pásalo por WhatsApp/USB a cada móvil).
2. Instálalo (Android pedirá permitir "instalar apps de origen desconocido").
3. Abre la app → servidor `192.168.1.242:8080` → tu usuario y contraseña.
4. Concede **acceso a todos los archivos** → **Sincronizar ahora**.

Las actualizaciones se instalan encima de la anterior sin perder los ajustes (todas las versiones van firmadas con la misma clave `app/mediasync.jks`).

## Cómo funciona
- Recorre el almacenamiento del móvil (excepto `Android/data`, `Android/obb` y carpetas ocultas).
- Calcula la huella SHA-256 de cada archivo nuevo y pregunta al servidor cuáles ya tiene: **nunca sube dos veces lo mismo**.
- Sube en segundo plano con notificación; si se corta la WiFi, reintenta solo.
- Recuerda lo ya enviado, así que las siguientes sincronizaciones son rápidas.
- Opción de sincronización automática: cada 12 h cuando el móvil está cargando y con WiFi.
