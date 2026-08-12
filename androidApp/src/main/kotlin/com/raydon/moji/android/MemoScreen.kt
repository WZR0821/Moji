package com.raydon.moji.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.MemoItem
import com.raydon.moji.ui.InkCard
import com.raydon.moji.ui.MojiPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MemoScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val memos = snapshot.memos.filter { memo ->
        query.isBlank() || listOf(memo.title, memo.content, memo.checklistItems.joinToString { it.text })
            .any { it.contains(query, ignoreCase = true) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScreenHeader("留白存念", "文字与清单独立于计划，不进入完成率") }
            item {
                OutlinedTextField(
                    query, { query = it }, label = { Text("搜索备忘") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true,
                )
            }
            if (memos.isEmpty()) item { Text("没有找到备忘。", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
            items(memos, key = { it.id }) { memo ->
                MemoRow(
                    memo,
                    viewModel,
                    onEdit = { editing = memo; showEditor = true },
                    onPin = { viewModel.toggleMemoPin(memo.id) },
                    onDelete = { viewModel.deleteMemo(memo.id) },
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
        FloatingActionButton(
            onClick = { editing = null; showEditor = true },
            containerColor = MojiPalette.Vermilion,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Text("＋", color = Color.White) }
    }
    if (showEditor) MemoEditorDialog(editing, viewModel) { showEditor = false; editing = null }
}

@Composable
private fun MemoRow(memo: MemoItem, viewModel: MojiViewModel, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    InkCard(Modifier.padding(horizontal = 20.dp).clickable(onClick = onEdit)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (memo.isPinned) Text("印", color = MojiPalette.Vermilion, modifier = Modifier.padding(end = 8.dp))
                Text(viewModel.displayMemoTitle(memo), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (memo.isPinned) "取消置顶" else "置顶") }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (memo.mode == "checklist") {
                memo.checklistItems.take(5).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.isCompleted, { viewModel.toggleChecklistItem(memo.id, item.id) })
                        Text(item.text.ifBlank { "空白项目" }, textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null)
                    }
                }
            } else {
                Text(memo.content.ifBlank { "空白备忘" }, maxLines = 5, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            }
            val updated = Instant.parse(memo.updatedAt).atZone(ZoneId.systemDefault())
            Text(updated.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun MemoEditorDialog(existing: MemoItem?, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var checklist by remember(existing?.id) { mutableStateOf(existing?.mode == "checklist") }
    var content by remember(existing?.id) {
        mutableStateOf(
            if (existing?.mode == "checklist") existing.checklistItems.joinToString("\n") { it.text }
            else existing?.content ?: ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新备忘" else "编辑备忘") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!checklist, { checklist = false }, { Text("文字") })
                    FilterChip(checklist, { checklist = true }, { Text("核对清单") })
                }
                OutlinedTextField(title, { title = it }, label = { Text("标题（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    content,
                    { content = it },
                    label = { Text(if (checklist) "每行一个清单项目" else "正文") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveMemo(existing?.id, title, content, checklist, existing?.checklistItems ?: emptyList())
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
