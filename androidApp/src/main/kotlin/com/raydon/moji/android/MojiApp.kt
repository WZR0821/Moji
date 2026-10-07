package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MojiApp(
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRestoreBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val selected by viewModel.selectedTab.collectAsStateWithLifecycle()
    val preview by viewModel.pendingBackup.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupNotice by viewModel.backupNotice.collectAsStateWithLifecycle()
    val storageError by viewModel.storageError.collectAsStateWithLifecycle()
    val revision = viewModel.settingsRevision.collectAsStateWithLifecycle().value
    val view = LocalView.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(storageError) { storageError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(message) {
        message?.takeUnless { it.startsWith("操作未完成：") }?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    if (message?.startsWith("操作未完成：") == true) AlertDialog(
        onDismissRequest = { viewModel.clearMessage() }, title = { Text("操作未完成") },
        text = { Text(message.orEmpty().removePrefix("操作未完成：")) },
        confirmButton = { TextButton(onClick = { viewModel.clearMessage() }) { Text("好") } },
    )
    val tabs = listOf(
        Triple("计划", MojiIcon.Checklist, 0),
        Triple("备忘", MojiIcon.Note, 1),
        Triple("时刻", MojiIcon.Timer, 2),
        Triple("回顾", MojiIcon.Chart, 3),
    )
    val texture = remember(revision) { viewModel.paperTextureEnabled }
    CompositionLocalProvider(LocalPaperTexture provides texture) {
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    androidx.compose.runtime.SideEffect { view.keepScreenOn = viewModel.keepScreenAwake && timer.running }
    androidx.compose.runtime.DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
    MojiPaperScreen {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth().height(49.dp)) {
                    tabs.forEach { (label, icon, index) ->
                        val active = selected == index
                        Column(
                            Modifier.weight(1f).fillMaxSize().semantics { this.selected = active }.clickable(role = Role.Tab) {
                                if (viewModel.hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                viewModel.selectedTab.value = index
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                                MojiLineIcon(
                                    icon,
                                    size = 25.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.58f),
                                )
                            }
                            Text(
                                label,
                                modifier = Modifier.padding(top = 2.dp, bottom = 3.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.58f),
                                fontSize = 10.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> PlanScreen(viewModel)
                1 -> MemoScreen(viewModel)
                2 -> MomentsScreen(viewModel)
                else -> ReviewScreen(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRestoreBackupFolder, onRequestPermissions)
            }
            if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    }
    }
    preview?.let { backup ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("恢复这份备份？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val data = backup.snapshot
                    Text("计划 ${data.checkInItems.size} 项 · 记录 ${data.records.size} 条\n时刻 ${data.countdowns.size} 项 · 备忘 ${data.memos.size} 条")
                    Text("这会替换当前数据和备份中的设置。恢复前会先在本机保存一份副本。", modifier = Modifier.padding(top = 16.dp))
                    if (data.checkInItems.isEmpty() && data.records.isEmpty() && data.countdowns.isEmpty() && data.memos.isEmpty()) {
                        Text("这是一份空备份，恢复后当前内容会被清空。", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmRestore, enabled = !backupBusy) { Text(if (backupBusy) "正在恢复…" else "替换并恢复") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore, enabled = !backupBusy) { Text("取消") } },
        )
    }

    backupNotice?.let { notice ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = { viewModel.backupNotice.value = null },
            title = { Text("数据备份") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { viewModel.backupNotice.value = null }) { Text("好") } },
        )
    }

}
