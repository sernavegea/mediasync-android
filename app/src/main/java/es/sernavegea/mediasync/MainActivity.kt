package es.sernavegea.mediasync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var manualInfo: WorkInfo? = null
    private var autoInfo: WorkInfo? = null

    private val loginBox by lazy { findViewById<View>(R.id.loginBox) }
    private val mainBox by lazy { findViewById<View>(R.id.mainBox) }
    private val etServer by lazy { findViewById<EditText>(R.id.etServer) }
    private val etUser by lazy { findViewById<EditText>(R.id.etUser) }
    private val etPass by lazy { findViewById<EditText>(R.id.etPass) }
    private val tvLoginErr by lazy { findViewById<TextView>(R.id.tvLoginErr) }
    private val btnLogin by lazy { findViewById<Button>(R.id.btnLogin) }
    private val tvHello by lazy { findViewById<TextView>(R.id.tvHello) }
    private val tvServer by lazy { findViewById<TextView>(R.id.tvServer) }
    private val permBox by lazy { findViewById<View>(R.id.permBox) }
    private val tvStatus by lazy { findViewById<TextView>(R.id.tvStatus) }
    private val tvDetail by lazy { findViewById<TextView>(R.id.tvDetail) }
    private val tvLast by lazy { findViewById<TextView>(R.id.tvLast) }
    private val progress by lazy { findViewById<ProgressBar>(R.id.progress) }
    private val btnSync by lazy { findViewById<Button>(R.id.btnSync) }
    private val btnCancel by lazy { findViewById<Button>(R.id.btnCancel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // --- login ---
        etServer.setText(prefs.server.removePrefix("http://"))
        btnLogin.setOnClickListener { doLogin() }

        // --- ajustes ---
        bindCheck(R.id.cbFotos, Categorias.FOTOS)
        bindCheck(R.id.cbVideos, Categorias.VIDEOS)
        bindCheck(R.id.cbAudio, Categorias.AUDIO)
        bindCheck(R.id.cbDocs, Categorias.DOCS)

        findViewById<MaterialSwitch>(R.id.swWifi).apply {
            isChecked = prefs.onlyWifi
            setOnCheckedChangeListener { _, v -> prefs.onlyWifi = v }
        }
        findViewById<MaterialSwitch>(R.id.swAuto).apply {
            isChecked = prefs.autoSync
            setOnCheckedChangeListener { _, v ->
                prefs.autoSync = v
                SyncWorker.schedule(this@MainActivity)
                if (v) toast("Se sincronizará sola cuando el móvil esté cargando y con WiFi")
            }
        }
        findViewById<EditText>(R.id.etExcluded).apply {
            setText(prefs.excluded)
            doAfterTextChanged { prefs.excluded = it?.toString() ?: "" }
        }

        // --- acciones ---
        findViewById<Button>(R.id.btnPerm).setOnClickListener { askFilesPermission() }
        btnSync.setOnClickListener {
            if (!Perms.hasAllFiles(this)) { askFilesPermission(); return@setOnClickListener }
            SyncWorker.runNow(this)
        }
        btnCancel.setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.MANUAL)
            autoInfo?.takeIf { it.state == WorkInfo.State.RUNNING }?.let {
                WorkManager.getInstance(this).cancelWorkById(it.id)
                SyncWorker.schedule(this) // la automática se vuelve a programar
            }
        }
        findViewById<Button>(R.id.btnWeb).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.server)))
        }
        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Volver a revisar todo")
                .setMessage("La próxima sincronización revisará otra vez todos los archivos del teléfono. No se duplicará nada en el servidor, pero tardará más.")
                .setPositiveButton("Aceptar") { _, _ -> SentDb(this).clear(); toast("Hecho") }
                .setNegativeButton("Cancelar", null).show()
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("Se detendrá la sincronización en este móvil.")
                .setPositiveButton("Cerrar sesión") { _, _ ->
                    prefs.token = null; prefs.autoSync = false
                    WorkManager.getInstance(this).cancelAllWork()
                    SentDb(this).clear()
                    showLogin()
                }
                .setNegativeButton("Cancelar", null).show()
        }

        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "MediaSync v$v"
        } catch (_: Exception) { }

        // --- seguimiento de la sincronización ---
        val wm = WorkManager.getInstance(this)
        wm.getWorkInfosForUniqueWorkLiveData(SyncWorker.MANUAL).observe(this) { manualInfo = it.lastOrNull(); render() }
        wm.getWorkInfosForUniqueWorkLiveData(SyncWorker.AUTO).observe(this) { autoInfo = it.lastOrNull(); render() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        if (prefs.token == null) showLogin() else showMain()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.token == null && mainBox.visibility == View.VISIBLE) showLogin()
        permBox.visibility = if (Perms.hasAllFiles(this)) View.GONE else View.VISIBLE
        render()
    }

    private fun bindCheck(id: Int, cat: String) {
        findViewById<CheckBox>(id).apply {
            isChecked = prefs.isEnabled(cat)
            setOnCheckedChangeListener { _, v -> prefs.setEnabled(cat, v) }
        }
    }

    private fun showLogin() {
        loginBox.visibility = View.VISIBLE; mainBox.visibility = View.GONE
    }

    private fun showMain() {
        loginBox.visibility = View.GONE; mainBox.visibility = View.VISIBLE
        tvHello.text = "Hola, ${prefs.name}"
        tvServer.text = prefs.server
        permBox.visibility = if (Perms.hasAllFiles(this)) View.GONE else View.VISIBLE
        render()
    }

    private fun doLogin() {
        val server = normalizeServer(etServer.text.toString())
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) { tvLoginErr.text = "Escribe usuario y contraseña"; return }
        btnLogin.isEnabled = false
        tvLoginErr.text = "Conectando con $server…"
        lifecycleScope.launch {
            try {
                val j = withContext(Dispatchers.IO) {
                    val api = Api(server, null)
                    api.ping()
                    api.login(user, pass, "${Build.MANUFACTURER} ${Build.MODEL}")
                }
                prefs.server = server
                prefs.token = j.getString("token")
                val u = j.getJSONObject("user")
                prefs.name = u.optString("name", user)
                etPass.setText("")
                tvLoginErr.text = ""
                showMain()
                if (!Perms.hasAllFiles(this@MainActivity)) askFilesPermission()
            } catch (e: ApiException) {
                tvLoginErr.text = e.message
            } catch (e: Exception) {
                tvLoginErr.text = "No se encuentra el servidor en $server.\n¿Estás conectado a la WiFi de casa y el PC está encendido?"
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun askFilesPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(this)
                .setTitle("Acceso a archivos")
                .setMessage("En la siguiente pantalla activa «Permitir acceso para gestionar todos los archivos». Es lo que permite copiar tus fotos, vídeos, audios y documentos al PC de casa.")
                .setPositiveButton("Continuar") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Ahora no", null).show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permBox.visibility = if (Perms.hasAllFiles(this)) View.GONE else View.VISIBLE
    }

    private fun render() {
        if (!::prefs.isInitialized) return
        val running = listOfNotNull(manualInfo, autoInfo).firstOrNull { it.state == WorkInfo.State.RUNNING }
        val waiting = manualInfo?.state == WorkInfo.State.ENQUEUED

        tvLast.text = if (prefs.lastSync > 0) {
            val f = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES"))
            "Última sincronización: ${f.format(Date(prefs.lastSync))}\n${prefs.lastResult}"
        } else prefs.lastResult.ifEmpty { "Aún no has sincronizado" }

        when {
            running != null -> {
                val d = running.progress
                val phase = d.getString("phase") ?: "Preparando…"
                val done = d.getInt("done", 0); val total = d.getInt("total", 0)
                tvStatus.text = if (total > 0) "$phase · $done de $total" else phase
                tvDetail.text = if (total > 0)
                    "✅ ${d.getInt("up", 0)} nuevos · ♻ ${d.getInt("dup", 0)} ya estaban · ❌ ${d.getInt("err", 0)}   ${d.getString("current") ?: ""}"
                else ""
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = total == 0
                if (total > 0) { progress.max = total; progress.progress = done }
                btnSync.isEnabled = false; btnCancel.visibility = View.VISIBLE
            }
            waiting -> {
                tvStatus.text = if (prefs.onlyWifi) "⏳ Esperando conexión WiFi…" else "⏳ Esperando conexión…"
                tvDetail.text = ""
                progress.visibility = View.GONE
                btnSync.isEnabled = false; btnCancel.visibility = View.VISIBLE
            }
            else -> {
                tvStatus.text = when (manualInfo?.state) {
                    WorkInfo.State.FAILED -> "⚠ No se completó"
                    WorkInfo.State.SUCCEEDED -> "✔ Sincronización terminada"
                    else -> "Listo para sincronizar"
                }
                tvDetail.text = ""
                progress.visibility = View.GONE
                btnSync.isEnabled = true; btnCancel.visibility = View.GONE
                if (prefs.token == null && mainBox.visibility == View.VISIBLE) {
                    tvLoginErr.text = prefs.lastResult; showLogin()
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
