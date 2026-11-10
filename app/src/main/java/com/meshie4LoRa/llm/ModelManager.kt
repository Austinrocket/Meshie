package com.meshie4LoRa.llm

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.ModelEntity
import com.meshie4LoRa.data.ModelSource
import com.meshie4LoRa.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "MeshChan.Models"

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    /** [waiting] explains why DownloadManager isn't transferring right now (null while it is). */
    data class Downloading(val bytes: Long, val total: Long, val waiting: String? = null) : ModelStatus
    data object Verifying : ModelStatus
    data object Downloaded : ModelStatus
}

enum class RamFit { GOOD, TIGHT, RISKY }

data class ModelUi(val model: ModelEntity, val status: ModelStatus, val fit: RamFit, val error: String?)

/** Model files on disk, downloads via DownloadManager, checksum verification, and loading into the engine. */
class ModelManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val engine: LlamaEngine,
    private val scope: CoroutineScope,
) {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val modelsDir: File get() = requireNotNull(context.getExternalFilesDir("models")) { "External storage unavailable" }
    private val totalRam: Long = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    private val progress = MutableStateFlow<Map<String, ModelStatus.Downloading>>(emptyMap())
    private val verifying = MutableStateFlow<Set<String>>(emptySet())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private var pollJob: Job? = null

    val models: Flow<List<ModelUi>> = combine(db.models().observeAll(), progress, verifying, errors) { list, prog, ver, err ->
        list.map { m ->
            val status = when {
                m.downloadId != null -> prog[m.id] ?: ModelStatus.Downloading(0, m.sizeBytes ?: 0)
                m.id in ver -> ModelStatus.Verifying
                fileOf(m).exists() -> ModelStatus.Downloaded
                else -> ModelStatus.NotDownloaded
            }
            ModelUi(m, status, fitOf(m), err[m.id])
        }
    }

    init {
        scope.launch {
            db.models().insertIfMissing(ModelCatalog.entries)
            removeOrphanDownloads()
            ensurePolling()
            loadActive()
        }
    }

    fun fileOf(m: ModelEntity) = File(modelsDir, m.fileName)

    private fun fitOf(m: ModelEntity): RamFit {
        val need = (m.sizeBytes ?: return RamFit.TIGHT) + 400L * 1024 * 1024
        val ratio = need.toDouble() / totalRam
        return when {
            ratio <= 0.45 -> RamFit.GOOD
            ratio <= 0.65 -> RamFit.TIGHT
            else -> RamFit.RISKY
        }
    }

    suspend fun download(id: String) {
        val m = db.models().get(id) ?: return
        val url = m.url ?: return
        m.downloadId?.let { downloads.remove(it) }
        db.models().upsert(m.copy(downloadId = null))
        removeOrphanDownloads()
        fileOf(m).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Model: ${m.name}")
            .setDestinationInExternalFilesDir(context, "models", m.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
        val downloadId = downloads.enqueue(request)
        db.models().upsert(m.copy(downloadId = downloadId, verified = false))
        errors.update { it - id }
        ensurePolling()
    }

    suspend fun cancelDownload(id: String) {
        val m = db.models().get(id) ?: return
        m.downloadId?.let { downloads.remove(it) }
        db.models().upsert(m.copy(downloadId = null))
        progress.update { it - id }
    }

    suspend fun delete(id: String) {
        val m = db.models().get(id) ?: return
        if (settings.activeModelId.first() == id) {
            engine.unload()
            settings.setActiveModel(null)
        }
        m.downloadId?.let { downloads.remove(it) }
        fileOf(m).delete()
        if (m.source == ModelSource.CATALOG) db.models().upsert(m.copy(downloadId = null, verified = false))
        else db.models().delete(id)
    }

    /** Adds a model from a direct .gguf URL (Hugging Face "blob" links are converted) and starts downloading it. */
    suspend fun addFromUrl(rawUrl: String) {
        val url = rawUrl.trim().replace("/blob/", "/resolve/")
        val uri = Uri.parse(url)
        require(uri.scheme == "https") { "URL must start with https://" }
        val fileName = uri.lastPathSegment.orEmpty()
        require(fileName.endsWith(".gguf", ignoreCase = true)) { "URL must point to a .gguf file" }
        val id = "custom-" + sha256Hex(url.toByteArray()).take(12)
        db.models().upsert(
            ModelEntity(
                id = id,
                name = fileName.removeSuffix(".gguf"),
                description = uri.host.orEmpty() + uri.path.orEmpty().substringBeforeLast('/'),
                url = url,
                fileName = fileName,
                source = ModelSource.CUSTOM_URL,
            )
        )
        download(id)
    }

    suspend fun importFile(uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: throw IOException("Could not read file name")
        require(name.endsWith(".gguf", ignoreCase = true)) { "Pick a .gguf file" }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(modelsDir, safeName)
        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IOException("Could not open file")
        db.models().upsert(
            ModelEntity(
                id = "imported-" + safeName.lowercase(),
                name = safeName.removeSuffix(".gguf"),
                description = "Imported file",
                fileName = safeName,
                sizeBytes = dest.length(),
                source = ModelSource.IMPORTED,
                verified = true,
            )
        )
    }

    suspend fun activate(id: String) {
        // Files kept across an app-data wipe come back unverified; check them instead of refusing to load.
        db.models().get(id)?.takeIf { it.sha256 != null && !it.verified && it.downloadId == null && fileOf(it).exists() }?.let { verify(it) }
        settings.setActiveModel(id)
        loadActive()
    }

    suspend fun loadActive() {
        val id = settings.activeModelId.first() ?: return
        val m = db.models().get(id) ?: return
        val file = fileOf(m)
        if (!file.exists()) return
        // DownloadManager pre-allocates the full file size, so an unfinished download looks complete on disk.
        if (m.downloadId != null || (m.sha256 != null && !m.verified)) {
            Log.w(TAG, "Not loading $id: download not finished or not verified")
            return
        }
        val s = settings.llmSettings.first()
        // Repacked weights are anonymous memory; with the screen off lmkd kills us above ~1.8 GB, so only small models repack.
        val repack = s.repackWeights && file.length() <= totalRam * 0.15
        engine.load(id, file.absolutePath, s.nCtx, s.nThreads, repack)
    }

    /**
     * Removes DownloadManager entries no model tracks. Two downloads into one file corrupt it, and orphans sit
     * "waiting to retry" forever. remove() deletes the target file, so files still in use are moved aside first.
     */
    private suspend fun removeOrphanDownloads() = withContext(Dispatchers.IO) {
        val models = db.models().observeAll().first()
        val tracked = models.mapNotNull { it.downloadId }.toSet()
        val keep = models.filter { it.downloadId != null || it.verified }.map { fileOf(it).absolutePath }.toSet()
        val orphans = mutableListOf<Pair<Long, String?>>()
        downloads.query(DownloadManager.Query()).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                if (id !in tracked) orphans += id to c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { Uri.parse(it).path }
            }
        }
        orphans.forEach { (id, path) ->
            val file = path?.takeIf { it in keep }?.let(::File)?.takeIf { it.exists() }
            val aside = file?.let { File(it.path + ".keep") }
            if (file != null && aside != null && !file.renameTo(aside)) {
                Log.w(TAG, "Orphan download $id left alone: couldn't move $path aside")
                return@forEach
            }
            downloads.remove(id)
            if (file != null && aside != null) aside.renameTo(file)
            Log.i(TAG, "Removed orphan download $id ($path)")
        }
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val active = db.models().observeAll().first().filter { it.downloadId != null }
                if (active.isEmpty()) break
                active.forEach { checkDownload(it) }
                delay(1_000)
            }
        }
    }

    private suspend fun checkDownload(m: ModelEntity) {
        val downloadId = m.downloadId ?: return
        val cursor = downloads.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use { c ->
            if (!c.moveToFirst()) {
                db.models().upsert(m.copy(downloadId = null))
                progress.update { it - m.id }
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress.update { it - m.id }
                    val done = m.copy(downloadId = null, sizeBytes = m.sizeBytes ?: total.takeIf { it > 0 })
                    db.models().upsert(done)
                    scope.launch { verify(done) }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Log.w(TAG, "Download ${m.id} failed: $reason")
                    downloads.remove(downloadId)
                    db.models().upsert(m.copy(downloadId = null))
                    progress.update { it - m.id }
                    errors.update { it + (m.id to "Download failed (code $reason)") }
                }
                else -> {
                    val waiting = if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING) {
                        when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused: waiting for a network"
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused: waiting for Wi-Fi"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused: the server stopped responding; Android will retry"
                            else -> if (status == DownloadManager.STATUS_PENDING) "Queued" else "Paused"
                        }
                    } else null
                    progress.update { it + (m.id to ModelStatus.Downloading(soFar, if (total > 0) total else m.sizeBytes ?: 0, waiting)) }
                }
            }
        }
    }

    private suspend fun verify(m: ModelEntity) {
        val expected = m.sha256 ?: return db.models().upsert(m.copy(verified = true))
        verifying.update { it + m.id }
        try {
            val actual = withContext(Dispatchers.IO) { fileSha256(fileOf(m)) }
            if (actual.equals(expected, ignoreCase = true)) {
                db.models().upsert(m.copy(verified = true))
                Log.i(TAG, "Verified ${m.id}")
            } else {
                Log.w(TAG, "Checksum mismatch for ${m.id}; file deleted")
                fileOf(m).delete()
                errors.update { it + (m.id to "Checksum mismatch; file deleted. Try again.") }
            }
        } finally {
            verifying.update { it - m.id }
        }
    }

    private fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
