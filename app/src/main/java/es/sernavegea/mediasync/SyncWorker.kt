package es.sernavegea.mediasync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Hace la sincronización completa en segundo plano. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val MANUAL = "sync_manual"
        const val AUTO = "sync_auto"
        private const val CHANNEL = "sync"
        private const val NOTIF_ID = 4242

        fun runNow(ctx: Context) {
            val prefs = Prefs(ctx)
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (prefs.onlyWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(c).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, req)
        }

        /** Automática: cada 12 h, solo con WiFi y cargando (normalmente por la noche). */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!Prefs(ctx).autoSync) {
                wm.cancelUniqueWork(AUTO); return
            }
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(c).build()
            wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private var lastNotif = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparando…", 0, 0)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sincronización", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MediaSync")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Cancelar",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private suspend fun report(phase: String, done: Int, total: Int, up: Int, dup: Int, err: Int, current: String, force: Boolean = false) {
        setProgress(workDataOf("phase" to phase, "done" to done, "total" to total, "up" to up, "dup" to dup, "err" to err, "current" to current))
        val now = System.currentTimeMillis()
        if (force || now - lastNotif > 1000) {
            lastNotif = now
            val text = if (total > 0) "$phase $done de $total" else phase
            try { setForeground(foreground(text, done, total)) } catch (_: Exception) { }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val token = prefs.token ?: return@withContext Result.failure(workDataOf("error" to "No has iniciado sesión"))
        if (!Perms.hasAllFiles(ctx)) {
            prefs.lastResult = "Falta el permiso de acceso a archivos"
            return@withContext Result.failure(workDataOf("error" to prefs.lastResult))
        }

        val api = Api(prefs.server, token)
        val db = SentDb(ctx)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        var up = 0; var dup = 0; var err = 0; var done = 0; var total = 0

        try {
            report("Buscando archivos…", 0, 0, 0, 0, 0, "", force = true)
            api.ping()

            val all = Scanner.scan(Environment.getExternalStorageDirectory(), prefs)
            val pending = all.filter { !db.isSent(it.rel, it.file.length(), it.file.lastModified()) }
            total = pending.size

            for (batch in pending.chunked(40)) {
                if (isStopped) break
                // 1) Calcular huellas del lote
                val hashed = ArrayList<Pair<Candidate, String>>()
                for (c in batch) {
                    if (isStopped) break
                    report("Revisando", done, total, up, dup, err, c.file.name)
                    try { hashed.add(c to sha256(c.file)) } catch (e: Exception) { err++; done++ }
                }
                // 2) Preguntar al servidor qué tiene ya
                val existing = api.check(hashed.map { it.second })
                // 3) Subir lo que falta
                for ((c, sha) in hashed) {
                    if (isStopped) break
                    val size = c.file.length(); val mtime = c.file.lastModified()
                    if (sha in existing) {
                        db.mark(c.rel, size, mtime, sha); dup++
                    } else {
                        report("Subiendo", done, total, up, dup, err, c.file.name)
                        try {
                            val st = api.upload(c.file, c.rel, sha, device)
                            db.mark(c.rel, size, mtime, sha)
                            if (st == "duplicado") dup++ else up++
                        } catch (e: ApiException) {
                            if (e.code == 401) throw e
                            err++
                        }
                    }
                    done++
                    report("Subiendo", done, total, up, dup, err, c.file.name)
                }
            }

            if (isStopped) {
                prefs.lastResult = "Cancelada: $up subidos, $dup ya estaban"
                return@withContext Result.failure()
            }
            prefs.lastSync = System.currentTimeMillis()
            prefs.lastResult = when {
                total == 0 -> "Todo al día ✔ (${all.size} archivos revisados)"
                else -> "✔ $up nuevos · $dup ya estaban · $err errores"
            }
            Result.success(workDataOf("up" to up, "dup" to dup, "err" to err))
        } catch (e: ApiException) {
            prefs.lastResult = if (e.code == 401) "Sesión caducada: vuelve a entrar" else (e.message ?: "Error del servidor")
            if (e.code == 401) prefs.token = null
            Result.failure(workDataOf("error" to prefs.lastResult))
        } catch (e: IOException) {
            prefs.lastResult = "Sin conexión con el servidor ($up subidos). Se reintentará."
            if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf("error" to prefs.lastResult))
        }
    }
}
