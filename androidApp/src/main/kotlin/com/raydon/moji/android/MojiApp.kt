package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onRequestPermissions: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    val tabs = listOf(
        Triple("计划", MojiIcon.Checklist, 0),
        Triple("备忘", MojiIcon.Note, 1),
        Triple("时刻", MojiIcon.Timer, 2),
        Triple("回顾", MojiIcon.Chart, 3),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth().height(72.dp)) {
                    tabs.forEach { (label, icon, index) ->
                        val active = selected == index
                        Column(
                            Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { selected = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                                MojiLineIcon(
                                    icon,
                                    size = 28.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.58f),
                                )
                            }
                            Text(
                                label,
                                modifier = Modifier.padding(top = 3.dp, bottom = 7.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.58f),
                                fontSize = 11.sp,
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
                else -> ReviewScreen(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRequestPermissions)
            }
        }
    }
}
