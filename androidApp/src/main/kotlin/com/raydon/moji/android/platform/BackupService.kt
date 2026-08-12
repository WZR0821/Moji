package com.raydon.moji.android.platform

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.raydon.moji.android.BuildConfig
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanBackupArchive
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object BackupService {
    fun exportJson(app: MojiApplication): String = MojiJson.encodeBackup(
        PlanBackupArchive(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = Instant.now().toString(),
            snapshot = app.repository.snapshot.value,
            preferences = app.settings.toArchive(),
        )
    )

    suspend fun importJson(app: MojiApplication, json: String) {
        val (snapshot, preferences) = MojiJson.decodeBackupOrSnapshot(json)
        app.repository.replace(snapshot)
        preferences?.let(app.settings::restore)
    }

    fun writeAutomaticBackup(app: MojiApplication): Boolean {
        val uri = app.settings.backupTreeUri?.let(Uri::parse) ?: return false
        val tree = DocumentFile.fromTreeUri(app, uri) ?: return false
        val json = exportJson(app)
        val latest = tree.findFile("Moji-latest.mojibackup")
            ?: tree.createFile("application/json", "Moji-latest.mojibackup")
            ?: return false
        app.contentResolver.openOutputStream(latest.uri, "wt")?.bufferedWriter()?.use { it.write(json) }
            ?: return false

        val dailyName = "Moji-${LocalDate.now()}.mojibackup"
        val daily = tree.findFile(dailyName) ?: tree.createFile("application/json", dailyName)
        daily?.let { file ->
            app.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(json) }
        }
        val cutoff = System.currentTimeMillis() - app.settings.backupRetentionDays * 86_400_000L
        tree.listFiles()
            .filter { it.name?.matches(Regex("Moji-\\d{4}-\\d{2}-\\d{2}\\.mojibackup")) == true }
            .filter { it.lastModified() in 1 until cutoff }
            .forEach { it.delete() }
        return true
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as MojiApplication
        return if (app.settings.backupTreeUri == null || BackupService.writeAutomaticBackup(app)) {
            Result.success()
        } else Result.retry()
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
