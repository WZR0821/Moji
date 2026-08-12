package com.raydon.moji.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.raydon.moji.ui.MojiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MojiViewModel by viewModels()

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(viewModel.exportJson()) }
        }.onSuccess { viewModel.message.value = "备份已导出" }
            .onFailure { viewModel.message.value = "导出失败：${it.message}" }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()?.let(viewModel::importJson)
    }
    private val backupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setBackupTree)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        viewModel.message.value = if (grants.values.any { it }) "权限已更新" else "未授予权限，本地功能仍可使用"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (viewModel.appearance) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            MojiTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MojiApp(
                        viewModel = viewModel,
                        onExportBackup = { exportBackup.launch("Moji-${java.time.LocalDate.now()}.mojibackup") },
                        onImportBackup = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onChooseBackupFolder = { backupFolder.launch(null) },
                        onRequestPermissions = {
                            permissions.launch(
                                arrayOf(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}
