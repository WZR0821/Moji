package com.raydon.moji.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.MemoItem
import com.raydon.moji.ui.MojiPalette
import java.time.Instant
import java.time.LocalDate
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
    val pinned = memos.filter { it.isPinned }.sortedByDescending { it.updatedAt }
    val recent = memos.filterNot { it.isPinned }.sortedByDescending { it.updatedAt }
    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                MojiLargeTitle(
                    title = "备忘",
                    actions = listOf(MojiIcon.Edit to { editing = null; showEditor = true }),
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MojiLineIcon(MojiIcon.Search, 20.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f))
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (query.isBlank()) Text("搜索备忘录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f), fontSize = 17.sp)
                                inner()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(34.dp))
            }
            if (memos.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 68.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        InkAsset("InkFocusBloom", Modifier.fillMaxSize().alpha(0.10f))
                        MojiLineIcon(if (query.isBlank()) MojiIcon.Note else MojiIcon.Search, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                    }
                    Text(if (query.isBlank()) "还没有备忘" else "没有找到相关备忘", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (query.isBlank()) "记下一段文字，或列一张核对清单。" else "换一个关键词再试试。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            if (pinned.isNotEmpty()) {
                item { SectionCaption("置顶", Modifier.padding(horizontal = 22.dp)) }
                items(pinned, key = { it.id }) { memo -> MemoRow(memo, viewModel, { editing = memo; showEditor = true }, { viewModel.toggleMemoPin(memo.id) }, { viewModel.deleteMemo(memo.id) }) }
            }
            if (recent.isNotEmpty()) {
                item { SectionCaption("最近", Modifier.padding(horizontal = 22.dp, vertical = if (pinned.isEmpty()) 0.dp else 20.dp)) }
                items(recent, key = { it.id }) { memo -> MemoRow(memo, viewModel, { editing = memo; showEditor = true }, { viewModel.toggleMemoPin(memo.id) }, { viewModel.deleteMemo(memo.id) }) }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showEditor) MemoEditorDialog(editing, viewModel) { showEditor = false; editing = null }
}

@Composable
private fun MemoRow(
    memo: MemoItem,
    viewModel: MojiViewModel,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val updated = Instant.parse(memo.updatedAt).atZone(ZoneId.systemDefault())
    val timestamp = if (updated.toLocalDate() == LocalDate.now()) updated.format(DateTimeFormatter.ofPattern("HH:mm")) else updated.format(DateTimeFormatter.ofPattern("M月d日"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onEdit)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (memo.isPinned) {
                    Box(Modifier.padding(end = 8.dp)) {
                        MojiLineIcon(MojiIcon.Pin, 16.dp, MojiPalette.Vermilion)
                    }
                }
                Text(viewModel.displayMemoTitle(memo), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(timestamp, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) {
                        MojiLineIcon(MojiIcon.Ellipsis, 16.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (memo.isPinned) "取消置顶" else "置顶") }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (memo.mode == "checklist") {
                memo.checklistItems.take(4).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Box(
                            Modifier.size(15.dp).background(if (item.isCompleted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                                .clickable { viewModel.toggleChecklistItem(memo.id, item.id) },
                            contentAlignment = Alignment.Center,
                        ) { if (!item.isCompleted) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)) }
                        Text(item.text.ifBlank { "空白项目" }, modifier = Modifier.padding(start = 7.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null)
                    }
                }
            } else {
                Text(memo.content.ifBlank { "空白备忘" }, maxLines = 3, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
            }
        }
        InkDivider(Modifier.padding(start = 28.dp), opacity = 0.26f)
    }
}

@Composable
private fun MemoEditorDialog(existing: MemoItem?, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var checklist by remember(existing?.id) { mutableStateOf(existing?.mode == "checklist") }
    var content by remember(existing?.id) {
        mutableStateOf(if (existing?.mode == "checklist") existing.checklistItems.joinToString("\n") { it.text } else existing?.content ?: "")
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(
                    title = if (existing == null) "新建备忘" else "编辑备忘",
                    leading = "取消",
                    trailing = "完成",
                    onLeading = onDismiss,
                    onTrailing = { viewModel.saveMemo(existing?.id, title, content, checklist, existing?.checklistItems ?: emptyList()); onDismiss() },
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 18.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            title, { title = it },
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            decorationBox = { inner -> if (title.isBlank()) Text("标题", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() },
                        )
                        Spacer(Modifier.height(28.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            content, { content = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (content.isBlank()) Text(if (checklist) "每行一个清单项目" else "开始书写…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { checklist = !checklist }) {
                        MojiLineIcon(MojiIcon.Checklist, 25.dp)
                        Text(if (checklist) "核对清单" else "文字备忘", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
