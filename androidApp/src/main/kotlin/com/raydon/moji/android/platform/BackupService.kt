package com.raydon.moji.android.platform

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.raydon.moji.android.BuildConfig
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanBackupArchive
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object BackupService {
    private val mutex = Mutex()

    suspend fun exportJson(app: MojiApplication): String = withContext(Dispatchers.IO) {
        mutex.withLock { exportUnlocked(app) }
    }

    private suspend fun exportUnlocked(app: MojiApplication): String = MojiJson.encodeBackup(
        PlanBackupArchive(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = Instant.now().toString(),
            // A StateFlow value may still be its empty initial value on cold start.
            snapshot = app.repository.freshSnapshot(),
            preferences = app.settings.toArchive(),
        )
    )

    suspend fun importJson(app: MojiApplication, json: String) = withContext(Dispatchers.IO) {
        val (snapshot, preferences) = MojiJson.decodeBackupOrSnapshot(json)
        mutex.withLock {
            // Preserve a complete local recovery copy before replacing any data.
            val (name, jsonBeforeRestore) = try {
                "Moji-before-restore.mojibackup" to exportUnlocked(app)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                // A corrupt local row must not prevent recovery from a valid backup.
                // Keep its exact bytes separately instead of inventing an empty backup.
                val raw = app.repository.rawSnapshotJson() ?: throw error
                "Moji-before-restore-corrupt.json" to raw
            }
            val safetyCopy = AtomicFile(File(app.filesDir, name))
            val previous = jsonBeforeRestore.toByteArray(Charsets.UTF_8)
            val output = safetyCopy.startWrite()
            try {
                output.write(previous)
                safetyCopy.finishWrite(output)
            } catch (error: Exception) {
                safetyCopy.failWrite(output)
                throw error
            }
            app.repository.replace(MojiJson.portableSnapshot(snapshot))
            preferences?.let(app.settings::restore)
        }
    }

    suspend fun writeAutomaticBackup(app: MojiApplication): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val uri = app.settings.backupTreeUri?.let(Uri::parse) ?: return@withLock false
            val tree = DocumentFile.fromTreeUri(app, uri) ?: return@withLock false
            val json = exportUnlocked(app)
            // SAF providers do not all support atomic replacement. A fully written,
            // verified staging file must exist before the previous file is touched.
            writeVerified(app, tree, "Moji-${LocalDate.now()}.mojibackup", json)
            writeVerified(app, tree, "Moji-latest.mojibackup", json)
            if (app.settings.backupRetentionDays > 0) {
                val cutoff = System.currentTimeMillis() - app.settings.backupRetentionDays * 86_400_000L
                tree.listFiles()
                    .filter { it.name?.matches(Regex("Moji-\\d{4}-\\d{2}-\\d{2}\\.mojibackup")) == true }
                    .filter { it.lastModified() in 1 until cutoff }
                    .forEach { it.delete() }
            }
            app.settings.lastBackupAt = System.currentTimeMillis()
            true
        }
    }

    private fun writeVerified(app: MojiApplication, tree: DocumentFile, name: String, json: String) {
        val staged = tree.createFile("application/json", "Moji-pending-${UUID.randomUUID()}.mojibackup")
            ?: error("无法创建备份文件")
        var verified = false
        try {
            val output = app.contentResolver.openOutputStream(staged.uri, "wt") ?: error("无法写入备份文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            val written = app.contentResolver.openInputStream(staged.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            check(written == json) { "备份写入校验失败，原备份已保留" }
            verified = true
            tree.findFile(name)?.let { check(it.delete()) { "无法替换旧备份，完整新副本已保留" } }
            check(staged.renameTo(name)) { "文件夹不支持重命名，完整新副本已保留在 Moji-pending 文件中" }
        } finally {
            if (!verified) staged.delete()
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MojiApplication
        if (app.settings.backupTreeUri == null) return Result.success()
        return try {
            if (BackupService.writeAutomaticBackup(app)) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object BackupScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "moji.daily.backup",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
