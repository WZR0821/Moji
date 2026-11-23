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
        if (BuildConfig.DEBUG) com.raydon.moji.android.ai.AIQAFixture.activate(
            intent.getStringExtra("moji_qa_ai"), intent.getStringExtra("moji_qa_ai_delay")?.toDoubleOrNull(), intent.getStringExtra("moji_qa_ai_page"),
            intent.getStringExtra("moji_qa_route"))
        if (BuildConfig.DEBUG) clearShareInboxForQa(intent.getStringExtra("moji_qa_route"))
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
                // iOS draws body text in the system label colour (pure black / white);
                // icons, tints and ink artwork keep the graphite palette.
                Surface(color = MaterialTheme.colorScheme.background, contentColor = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black) {
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

    override fun onStart() {
        super.onStart()
        weeklyLetterCheck()
    }

    override fun onStop() {
        weeklyLetterCheck()
        super.onStop()
    }

    /**
     * 本周来信 on coming back and on going to the background: Sunday afternoon the
     * letter is written ahead so the 20:00 notification can carry its title, and
     * the notification follows the switches and the permission.
     */
    private fun weeklyLetterCheck() {
        com.raydon.moji.android.ai.AIWeeklyLetterState.shared.prepareIfNeeded(viewModel.snapshot.value, com.raydon.moji.android.ai.AIClock.now())
        com.raydon.moji.android.platform.AIWeeklyLetterNotifier.reschedule(this)
    }

    /**
     * The share inbox outlives a launch, so what ai-share-off left behind would be picked
     * up by the next scenario's plan page. Every debug scenario other than the two share
     * ones starts with it empty (the fixture's scratch folder and files/share_inbox).
     */
    private fun clearShareInboxForQa(route: String?) {
        if (route.isNullOrBlank() || route == "ai-share" || route == "ai-share-off") return
        com.raydon.moji.android.ai.ShareInbox.of(this).clear()
        com.raydon.moji.android.ai.ShareInbox(java.io.File(filesDir, com.raydon.moji.android.ai.ShareInbox.folderName)).clear()
    }

    private fun handleNavigationIntent(intent: android.content.Intent) {
        when (intent.data?.host) {
            "pomodoro" -> viewModel.openFocus()
            "plans" -> viewModel.openPlans()
            // 说一句添加: the quick-settings tile, the launcher shortcut and the link itself.
            "say" -> viewModel.startSaying()
            // 分享: ShareReceiverActivity has put something into the inbox.
            "share" -> viewModel.checkShareInbox()
            // The 本周来信 notification.
            "review" -> viewModel.openReview()
        }
    }
}
