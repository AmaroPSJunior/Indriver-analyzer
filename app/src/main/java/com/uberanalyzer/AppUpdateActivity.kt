package com.uberanalyzer

import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.uberanalyzer.update.ReleaseClient
import com.uberanalyzer.update.ReleasePolicy
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** DownloadManager owns the download; this screen resumes it after recreation or reopening. */
class AppUpdateActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("app_update", MODE_PRIVATE) }
    private val downloads by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var cancel: Button
    private var busy = false
    private var resumed = false
    private var started = false
    private var waitingPermission = false
    private var installerOpened = false
    private var readyToInstall = false
    private val poll = Runnable { resumeDownloadOrCheck() }
    private val downloadId: Long get() = prefs.getLong("download_id", -1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingPermission = savedInstanceState?.getBoolean("permission") ?: false
        installerOpened = savedInstanceState?.getBoolean("installer") ?: false
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.app_background))
        }
        root.addView(TextView(this).apply {
            text = "⬇️ Atualizar aplicativo"
            textSize = 22f
            setTextColor(getColor(R.color.app_text))
        })
        root.addView(TextView(this).apply {
            text = "Versão instalada: ${packageManager.getPackageInfo(packageName, 0).versionName}\nA atualização mantém seus dados. Confirme a instalação quando o Android solicitar."
            setTextColor(getColor(R.color.app_text))
            setPadding(0, padding, 0, padding)
        })
        status = TextView(this).apply { setTextColor(getColor(R.color.app_text)); textSize = 16f }
        action = Button(this).apply {
            text = "Verificar atualização"
            setOnClickListener { installerOpened = false; resumeDownloadOrCheck() }
        }
        cancel = Button(this).apply {
            text = "Cancelar download"
            setOnClickListener {
                handler.removeCallbacks(poll)
                clearDownload()
                show("Download cancelado.")
            }
        }
        root.addView(status)
        root.addView(action)
        root.addView(cancel)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        when {
            waitingPermission -> {
                waitingPermission = false
                if (packageManager.canRequestPackageInstalls()) resumeDownloadOrCheck()
                else show("Autorize este app a instalar atualizações para continuar.", "Instalar atualização")
            }
            installerOpened -> show("Se a instalação foi cancelada, toque abaixo para tentar novamente.", "Instalar atualização")
            readyToInstall -> install()
            !started || downloadId != -1L -> { started = true; resumeDownloadOrCheck() }
        }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission", waitingPermission)
        outState.putBoolean("installer", installerOpened)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun show(message: String, label: String = "Verificar novamente") {
        status.text = message
        action.text = label
        action.isEnabled = !busy
        cancel.isEnabled = !busy && downloadId != -1L
    }

    private fun <T> background(task: () -> T, failure: () -> Unit = {}, success: (T) -> Unit) {
        busy = true
        action.isEnabled = false
        cancel.isEnabled = false
        worker.execute {
            val result = runCatching(task)
            handler.post {
                if (isDestroyed || isFinishing) return@post
                busy = false
                result.fold(success, { error ->
                    failure()
                    show(if (error is IllegalStateException) error.message ?: "Falha na atualização."
                        else "Não foi possível atualizar. Verifique a conexão e o espaço livre e tente novamente.")
                })
            }
        }
    }

    private fun resumeDownloadOrCheck() {
        if (busy) return
        handler.removeCallbacks(poll)
        val id = downloadId
        if (id == -1L) {
            show("Consultando a release do projeto…")
            background(task = { ReleaseClient.latest() }) { release ->
                if (!ReleasePolicy.isNewer(release.tag, installedCode())) {
                    show("Seu aplicativo já está atualizado.")
                } else {
                    try {
                        val file = apkFile()
                        file.parentFile?.mkdirs()
                        check(!file.exists() || file.delete()) { "Não foi possível preparar o download." }
                        val request = DownloadManager.Request(Uri.parse(release.url))
                            .setTitle("Atualização ${release.tag}")
                            .setDescription("inDrive Mapa Rotas")
                            .setMimeType("application/vnd.android.package-archive")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationUri(Uri.fromFile(file))
                        val newId = downloads.enqueue(request)
                        if (!prefs.edit().putLong("download_id", newId).putString("tag", release.tag)
                                .putString("digest", release.digest).putLong("size", release.size).commit()) {
                            downloads.remove(newId)
                            error("Não foi possível salvar o estado do download.")
                        }
                        resumeDownloadOrCheck()
                    } catch (_: Exception) { show("Não foi possível iniciar o download. Verifique o espaço livre.") }
                }
            }
            return
        }
        try {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    clearDownload()
                    show("O download não está mais disponível. Tente novamente.")
                    return
                }
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> validateAndInstall()
                    DownloadManager.STATUS_FAILED -> {
                        clearDownload()
                        show("O download falhou. Verifique a conexão e o espaço livre.")
                    }
                    else -> {
                        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (done > ReleasePolicy.MAX_APK_BYTES || total > ReleasePolicy.MAX_APK_BYTES) {
                            clearDownload()
                            show("O arquivo excede o tamanho permitido.")
                            return
                        }
                        val percent = if (total > 0) " ${(100 * done / total).coerceIn(0, 100)}%" else ""
                        show("Baixando atualização…$percent\nO download continuará se você sair desta tela.", "Download em andamento")
                        action.isEnabled = false
                        if (resumed) handler.postDelayed(poll, 700L)
                    }
                }
            }
        } catch (_: Exception) { show("Não foi possível consultar o download. Tente novamente.") }
    }

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun installedCode() = code(packageManager.getPackageInfo(packageName, 0))

    private fun apkFile(): File {
        val directory = getExternalFilesDir(null) ?: error("Armazenamento indisponível.")
        return File(directory, "updates/update.apk")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Suppress("DEPRECATION")
    private fun validateAndInstall() {
        val tag = prefs.getString("tag", "").orEmpty()
        if (!ReleasePolicy.isNewer(tag, installedCode())) {
            clearDownload()
            show("Seu aplicativo já está atualizado.")
            return
        }
        val expectedSize = prefs.getLong("size", -1L)
        val expectedDigest = prefs.getString("digest", "").orEmpty()
        show("Verificando o APK baixado…")
        background(task = {
            val file = apkFile()
            check(file.isFile && file.length() == expectedSize && file.length() in 1..ReleasePolicy.MAX_APK_BYTES) {
                "O APK está incompleto. Baixe novamente."
            }
            if (expectedDigest.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                check("sha256:${hex(digest.digest())}".equals(expectedDigest, true)) { "O APK baixado não corresponde à release. Baixe novamente." }
            }
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("O arquivo não é um APK válido.")
            val installed = packageManager.getPackageInfo(packageName, flags)
            check(ReleasePolicy.validArchive(archive.packageName, packageName, code(archive), code(installed), tag, signers(installed), signers(archive))) {
                "O APK não é uma atualização compatível com esta instalação (versão, app ou assinatura diferente)."
            }
            check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Esta atualização exige uma versão mais recente do Android." }
        }, failure = { clearDownload() }) {
            readyToInstall = true
            if (resumed) install() else show("Download concluído. Pronto para instalar.", "Instalar atualização")
        }
    }

    private fun install() {
        readyToInstall = false
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                waitingPermission = true
                show("Permita atualizações deste aplicativo na tela do Android para continuar.", "Instalar atualização")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apkFile())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Atualização", uri)
            }
            installerOpened = true
            startActivity(intent)
        } catch (_: Exception) {
            waitingPermission = false
            installerOpened = false
            show("Não foi possível abrir o instalador. Verifique se a instalação de apps está permitida.", "Tentar instalar")
        }
    }

    private fun clearDownload() {
        val id = downloadId
        if (id != -1L) runCatching { downloads.remove(id) }
        prefs.edit().clear().commit()
        readyToInstall = false
    }
}
