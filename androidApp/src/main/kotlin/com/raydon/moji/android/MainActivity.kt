package com.raydon.moji.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.ui.MojiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MojiViewModel by viewModels()

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.exportBackup(uri)
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::prepareRestore)
    }
    private val backupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setBackupTree)
    }
    private val restoreFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::prepareRestoreFolder)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        viewModel.message.value = if (grants.values.any { it }) "权限已更新" else "未授予权限，本地功能仍可使用"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("moji_qa_seed", false)) viewModel.seedVisualQa(intent.getStringExtra("moji_qa_route").orEmpty())
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0xFFF7F4ED.toInt(), 0xFF11100E.toInt()),
            navigationBarStyle = SystemBarStyle.auto(0xFFF7F4ED.toInt(), 0xFF11100E.toInt()),
        )
        setContent {
            val systemDark = isSystemInDarkTheme()
            val appearance by viewModel.appearanceMode.collectAsStateWithLifecycle()
            val dark = when (appearance) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            SideEffect {
                val lightSurface = 0xFFF7F4ED.toInt()
                val darkSurface = 0xFF11100E.toInt()
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(darkSurface) else SystemBarStyle.light(lightSurface, lightSurface),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(darkSurface) else SystemBarStyle.light(lightSurface, lightSurface),
                )
            }
            MojiTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MojiApp(
                        viewModel = viewModel,
                        onExportBackup = { exportBackup.launch("Moji-${java.time.LocalDate.now()}.mojibackup") },
                        onImportBackup = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onChooseBackupFolder = { backupFolder.launch(null) },
                        onRestoreBackupFolder = { restoreFolder.launch(null) },
                        onRequestPermissions = {
                            permissions.launch(
                                buildList {
                                    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                    add(Manifest.permission.READ_CALENDAR)
                                    add(Manifest.permission.WRITE_CALENDAR)
                                }.toTypedArray()
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: android.content.Intent) {
        when (intent.data?.host) { "pomodoro" -> viewModel.openFocus(); "plans" -> viewModel.openPlans() }
    }
}
