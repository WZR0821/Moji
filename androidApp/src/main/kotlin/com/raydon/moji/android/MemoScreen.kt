package com.raydon.moji.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.MemoChecklistItem
import com.raydon.moji.ui.InkCard
import java.util.UUID
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun MemoScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf<MemoItem?>(null) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, snapshot.memos.size) { if (!didQA && qaRoute.isNotEmpty() && snapshot.memos.isNotEmpty()) {
        didQA = true
        when (qaRoute) {
            "memo-note" -> { editing = snapshot.memos.firstOrNull { it.mode == "note" }; showEditor = true }
            "memo-checklist" -> { editing = snapshot.memos.firstOrNull { it.mode == "checklist" }; showEditor = true }
            "memo-convert" -> converting = snapshot.memos.firstOrNull { it.mode == "checklist" }
        }
    } }
    val memos = snapshot.memos.filter { memo ->
        val term = query.trim()
        term.isEmpty() || memo.title.contains(term, true) || memo.content.contains(term, true) || memo.checklistItems.any { it.text.contains(term, true) }
    }
    val groups = listOf("置顶" to memos.filter { it.isPinned }, "最近" to memos.filterNot { it.isPinned })
    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            item { MojiLargeTitle("备忘", actions = listOf(MojiIcon.Edit to { editing = null; showEditor = true })) }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MojiLineIcon(MojiIcon.Search, 18.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f))
                        BasicTextField(query, { query = it }, Modifier.weight(1f).padding(start = 8.dp), singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (query.isEmpty()) Text("搜索备忘录", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)); inner() })
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            if (memos.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MojiLineIcon(if (query.isEmpty()) MojiIcon.Note else MojiIcon.Search, 48.dp)
                    Text(if (query.isEmpty()) "纸上留白" else "没有找到相关备忘", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(if (query.isEmpty()) "只记下想法和事情，不设完成、不计时。" else "换一个关键词再试试。", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    if (query.isEmpty()) TextButton(onClick = { editing = null; showEditor = true }) { Text("写一则备忘") }
                }
            }
            groups.forEach { (label, entries) ->
                if (entries.isNotEmpty()) {
                    item { SectionCaption(label, Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
                    items(entries, key = { it.id }) { memo ->
                        MemoRow(memo, viewModel, { editing = memo; showEditor = true }, { converting = memo })
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showEditor) MemoEditorDialog(editing, viewModel) { showEditor = false; editing = null }
    converting?.let { MemoConversionDialog(it, viewModel) { converting = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoRow(memo: MemoItem, viewModel: MojiViewModel, onEdit: () -> Unit, onConvert: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val updated = Instant.parse(memo.updatedAt).atZone(ZoneId.systemDefault())
    val timestamp = when (updated.toLocalDate()) {
        LocalDate.now() -> updated.format(DateTimeFormatter.ofPattern("HH:mm"))
        LocalDate.now().minusDays(1) -> "昨天"
        else -> updated.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    val preview = if (memo.mode == "checklist") {
        val nonempty = memo.checklistItems.filter { it.text.isNotBlank() }
        val visible = if (memo.title.isBlank()) nonempty.drop(1) else nonempty
        val lines = visible.take(3).map { "${if (it.isCompleted) "◉" else "○"} ${it.text}" }
        (lines + if (visible.size > 3) listOf("另有 ${visible.size - 3} 项") else emptyList()).joinToString("\n")
    } else {
        val body = memo.content.trim()
        if (memo.title.isBlank()) body.split("\n").drop(1).joinToString("\n").trim() else body
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onEdit, onLongClick = { menu = true })) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (memo.isPinned) MojiLineIcon(MojiIcon.Pin, 13.dp, mojiVermilion)
                    Text(viewModel.displayMemoTitle(memo), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(timestamp, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.82f))
                }
                if (preview.isNotEmpty()) Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            InkDivider(Modifier.padding(start = 14.dp), 0.30f, inkSeed(memo.id.uppercase()))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("转为计划") }, { menu = false; onConvert() })
            DropdownMenuItem({ Text(if (memo.isPinned) "取消置顶" else "置顶") }, { menu = false; viewModel.toggleMemoPin(memo.id) })
            DropdownMenuItem({ Text("删除") }, { menu = false; viewModel.deleteMemo(memo.id) })
        }
    }
}

@Composable
fun MemoEditorDialog(existing: MemoItem?, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val draftId = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var checklist by remember(existing?.id) { mutableStateOf(existing?.mode == "checklist") }
    var content by remember(existing?.id) { mutableStateOf(existing?.content ?: "") }
    var entries by remember(existing?.id) { mutableStateOf(existing?.checklistItems.orEmpty().ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }) }
    var pinned by remember { mutableStateOf(existing?.isPinned ?: false) }
    var menu by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var checklistMenu by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var requestedId by remember { mutableStateOf<String?>(null) }
    val canSave = title.isNotBlank() || if (checklist) entries.any { it.text.isNotBlank() } else content.isNotBlank()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("取消", fontSize = 17.sp, modifier = Modifier.weight(1f).clickable(onClick = onDismiss))
                    Text(if (existing == null) "新建备忘" else "编辑备忘", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Ellipsis, 14.dp) }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(if (pinned) "取消置顶" else "置顶") }, { pinned = !pinned; menu = false })
                                if (existing != null) DropdownMenuItem({ Text("删除备忘") }, { menu = false; deleteConfirm = true })
                            }
                        }
                        Text("完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSave) 1f else 0.35f),
                            modifier = Modifier.clickable(enabled = canSave) { viewModel.saveMemo(draftId, title, content, checklist, checklistItems = entries, isPinned = pinned, onSaved = onDismiss) })
                    }
                }
                Surface(Modifier.fillMaxWidth().weight(1f).padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 0.dp),
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.095f))) {
                    Box {
                    InkAsset("InkHeaderWash", Modifier.padding(start = 15.dp).width(94.dp).height(10.dp).alpha(0.16f))
                    Column {
                        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(title, { title = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface),
                                decorationBox = { inner -> if (title.isEmpty()) Text("标题", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
                            if (pinned) InkSeal("藏", 23)
                        }
                        if (checklist) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(entries, key = { it.id }) { entry ->
                                MemoChecklistRow(entry, entry.id == requestedId, { focusedId = entry.id }, { text ->
                                    entries = entries.map { if (it.id == entry.id) it.copy(text = text) else it }
                                }, { checked -> entries = entries.map { if (it.id == entry.id) it.copy(isCompleted = checked) else it } },
                                { entries = entries.filterNot { it.id == entry.id }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) } },
                                { before, after ->
                                    val index = entries.indexOfFirst { it.id == entry.id }
                                    if (index >= 0) {
                                        val added = MemoChecklistItem(UUID.randomUUID().toString(), after)
                                        entries = entries.take(index) + entry.copy(text = before) + added + entries.drop(index + 1)
                                        requestedId = added.id
                                    }
                                }, {
                                    val index = entries.indexOfFirst { it.id == entry.id }
                                    if (index > 0) {
                                        val previous = entries[index - 1]
                                        entries = entries.take(index - 1) + previous.copy(text = previous.text + entry.text) + entries.drop(index + 1)
                                        requestedId = previous.id
                                        true
                                    } else false
                                })
                            }
                        } else BasicTextField(content, { content = it }, Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 25.sp),
                            decorationBox = { inner -> if (content.isEmpty()) Text("开始书写…", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
                    }
                    }
                }
                Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(24.dp).semantics { contentDescription = if (checklist) "转为正文" else "核对清单" }.clickable {
                        if (checklist) content = entries.filter { it.text.isNotBlank() }.joinToString("\n") { "${if (it.isCompleted) "✓" else "•"} ${it.text.trim()}" }
                        else {
                            entries = content.lines().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
                                val completedPrefix = listOf("- [x] ", "- [X] ", "☑︎ ", "☑ ", "✓ ", "✔ ").firstOrNull { line.startsWith(it) }
                                val prefix = completedPrefix ?: listOf("- [ ] ", "☐ ", "○ ", "• ").firstOrNull { line.startsWith(it) }
                                MemoChecklistItem(UUID.randomUUID().toString(), (prefix?.let { line.removePrefix(it) } ?: line).trim(), completedPrefix != null)
                            }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }
                            content = ""; requestedId = entries.first().id
                        }
                        checklist = !checklist
                    }) { MojiLineIcon(if (checklist) MojiIcon.TextLines else MojiIcon.Checklist, 24.dp) }
                    if (checklist) {
                        Box(Modifier.size(24.dp).semantics { contentDescription = "添加项目" }.clickable {
                            val index = entries.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: (entries.size - 1)
                            if (entries[index].text.isBlank()) requestedId = entries[index].id
                            else {
                                val added = MemoChecklistItem(UUID.randomUUID().toString())
                                entries = entries.take(index + 1) + added + entries.drop(index + 1); requestedId = added.id
                            }
                        }) { MojiLineIcon(MojiIcon.Plus, 24.dp) }
                        if (entries.any { it.isCompleted }) Box {
                            Box(Modifier.size(24.dp).semantics { contentDescription = "清单操作" }.clickable { checklistMenu = true }) { MojiLineIcon(MojiIcon.Ellipsis, 24.dp) }
                            DropdownMenu(checklistMenu, { checklistMenu = false }) { DropdownMenuItem({ Text("清除已完成") }, {
                                entries = entries.filterNot { it.isCompleted }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }; checklistMenu = false
                            }) }
                        }
                    }
                }
            }
        }
        if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除备忘？") },
            confirmButton = { TextButton(onClick = { existing?.let { viewModel.deleteMemo(it.id) }; onDismiss() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    }
}

@Composable
private fun MemoChecklistRow(item: MemoChecklistItem, requestFocus: Boolean, onFocus: () -> Unit, onText: (String) -> Unit,
                             onChecked: (Boolean) -> Unit, onDelete: () -> Unit, onSplit: (String, String) -> Unit, onBackspace: () -> Boolean) {
    var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text)) }
    var menu by remember(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) requester.requestFocus() }
    LaunchedEffect(item.text) { if (value.composition == null && value.text != item.text) value = TextFieldValue(item.text, TextRange(item.text.length)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(44.dp).combinedClickable(onClick = { onChecked(!item.isCompleted) }, onLongClick = { menu = true }), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(if (item.isCompleted) mojiVermilion else androidx.compose.ui.graphics.Color.Transparent, CircleShape).border(if (item.isCompleted) 0.dp else 1.4.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                if (item.isCompleted) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.background)
            }
            DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("删除项目", color = mojiVermilion) }, { menu = false; onDelete() }) }
        }
        BasicTextField(value, { next ->
            value = next
            if (next.composition == null && '\n' in next.text) {
                val split = next.text.indexOf('\n'); onSplit(next.text.take(split), next.text.drop(split + 1))
            } else onText(next.text)
        }, Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 12.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0 && value.composition == null && onBackspace() },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.isCompleted) 0.5f else 1f), textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null),
            decorationBox = { inner -> if (value.text.isEmpty()) Text("清单项目", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
    }
}
