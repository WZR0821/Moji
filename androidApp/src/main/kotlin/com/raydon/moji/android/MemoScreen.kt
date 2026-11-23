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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
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
            "memo-convert", "ai-memo-convert" -> converting = snapshot.memos.firstOrNull { it.mode == "checklist" }
            "ai-memo-checklist", "ai-memo-checklist-applied" -> { editing = snapshot.memos.firstOrNull { it.mode == "note" && it.content.isNotEmpty() }; showEditor = true }
        }
    } }
    val memos = snapshot.memos.filter { memo ->
        val term = query.trim()
        term.isEmpty() || memo.title.contains(term, true) || memo.content.contains(term, true) || memo.checklistItems.any { it.text.contains(term, true) }
    }
    val groups = listOf("置顶" to memos.filter { it.isPinned }, "最近" to memos.filterNot { it.isPinned })
    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            // iOS search field sits at 151–187pt: the title block must end at 151pt (146.5 + 4.5).
            item { MojiLargeTitle("备忘", actions = listOf(MojiIcon.Edit to { editing = null; showEditor = true }), bottomPadding = 4.5.dp) }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = iosFill, shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.height(36.dp).padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MojiLineIcon(MojiIcon.Search, 18.dp, iosSecondaryLabel)
                        BasicTextField(query, { query = it }, Modifier.weight(1f).padding(start = 7.dp), singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (query.isEmpty()) Text("搜索备忘录", fontSize = 17.sp, color = iosSecondaryLabel); inner() })
                    }
                }
                Spacer(Modifier.height(15.dp))
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
                    // iOS List header: caption semibold, .secondary resolving to tertiary ink (190,188,185); glyph centres 236.9 / 402.2pt.
                    item { Text(label, Modifier.padding(start = 22.dp, end = 22.dp, top = 27.dp, bottom = 6.6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = iosTertiaryLabel) }
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
    var confirmDelete by remember { mutableStateOf(false) }
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
            // iOS: title→preview glyph tops 29.0pt (Android was 30.6 with 9dp).
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.5.dp)) {
                // iOS HStack(alignment: .firstTextBaseline): the pin's foot and the timestamp sit on the title baseline.
                val pinLift = with(LocalDensity.current) { 3.dp.roundToPx() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (memo.isPinned) Box(Modifier.alignBy { it.measuredHeight - pinLift }) { MojiLineIcon(MojiIcon.Pin, 17.dp, mojiVermilion) }
                    Text(viewModel.displayMemoTitle(memo), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                    Text(timestamp, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.82f), modifier = Modifier.alignByBaseline())
                }
                // iOS .secondary in the plain list button resolves to primary at 50%: (122,121,117) on paper.
                val previewInk = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)
                if (preview.isNotEmpty()) Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 21.sp, color = previewInk)
            }
        }
        InkDivider(Modifier.align(Alignment.BottomStart).padding(start = 14.dp), 0.30f, inkSeed(memo.id.uppercase()))
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("转为计划") }, { menu = false; onConvert() })
            DropdownMenuItem({ Text(if (memo.isPinned) "取消置顶" else "置顶") }, { menu = false; viewModel.toggleMemoPin(memo.id) })
            DropdownMenuItem({ Text("删除") }, { menu = false; confirmDelete = true })
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这则备忘？") }, text = { Text("删除后无法撤销。") },
        confirmButton = { TextButton(onClick = { viewModel.deleteMemo(memo.id); confirmDelete = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
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
    val showsAI = rememberAIShowsEntries()
    var showsChecklistDraft by remember { mutableStateOf(false) }
    // The text a drafted checklist replaced, kept until the memo is saved or the mode is switched by hand.
    var textBeforeChecklistDraft by remember(existing?.id) { mutableStateOf<String?>(null) }
    fun useChecklistDraft(items: List<String>) {
        if (items.isEmpty()) return
        textBeforeChecklistDraft = content
        entries = items.map { MemoChecklistItem(UUID.randomUUID().toString(), it) }
        content = ""
        checklist = true
    }
    fun restoreTextBeforeChecklistDraft() {
        val text = textBeforeChecklistDraft ?: return
        focusedId = null
        entries = listOf(MemoChecklistItem(UUID.randomUUID().toString()))
        content = text
        checklist = false
        textBeforeChecklistDraft = null
    }
    LaunchedEffect(Unit) {
        val route = viewModel.visualQaRoute.value
        if (BuildConfig.DEBUG && route.startsWith("ai-memo-checklist") && !checklist && textBeforeChecklistDraft == null) {
            kotlinx.coroutines.delay(400)
            if (route == "ai-memo-checklist-applied") useChecklistDraft(listOf("打印准考证", "确认考场路线和到达时间", "准备黑色签字笔两支", "复习损害保险的三个判例"))
            else showsChecklistDraft = true
        }
    }
    val canSave = title.isNotBlank() || if (checklist) entries.any { it.text.isNotBlank() } else content.isNotBlank()
    val editState = listOf(title, content, checklist, pinned, entries.filter { it.text.isNotBlank() })
    val initialEditState = remember(existing?.id) { editState }
    val requestDismiss = rememberEditorDismiss(editState != initialEditState, onDismiss)
    Dialog(onDismissRequest = requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // iOS editor text views use UIColor.label (pure black / white), unlike the ink-coloured chrome.
        val labelInk = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color.Black
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("取消", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).heightIn(min = 44.dp).wrapContentHeight().clickable(onClick = requestDismiss))
                    Text(if (existing == null) "新建备忘" else "编辑备忘", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Box(Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Ellipsis, 15.dp) }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(if (pinned) "取消置顶" else "置顶") }, { pinned = !pinned; menu = false })
                                if (showsAI && !checklist && content.trim().isNotEmpty()) DropdownMenuItem({ Text("整理成清单") }, { menu = false; aiEntry { showsChecklistDraft = true } })
                                if (existing != null) DropdownMenuItem({ Text("删除备忘") }, { menu = false; deleteConfirm = true })
                            }
                        }
                        // iOS: ellipsis circle spans x 296.7–318.7pt, 24.6pt clear of 保存.
                        Spacer(Modifier.width(15.dp))
                        Text("保存", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canSave) 1f else 0.35f),
                            modifier = Modifier.heightIn(min = 44.dp).wrapContentHeight().clickable(enabled = canSave) { viewModel.saveMemo(draftId, title, content, checklist, checklistItems = entries, isPinned = pinned, onSaved = onDismiss) })
                    }
                }
                Surface(Modifier.fillMaxWidth().weight(1f).padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 10.dp),
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.095f))) {
                    Box {
                    InkAsset("InkHeaderWash", Modifier.padding(start = 15.dp).width(94.dp).height(10.dp).alpha(0.16f))
                    Column {
                        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(title, { title = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, color = labelInk),
                                decorationBox = { inner -> if (title.isEmpty()) Text("标题", fontSize = 22.sp, color = iosTertiaryLabel); inner() })
                            if (pinned) InkSeal("藏", 23)
                        }
                        if (checklist && textBeforeChecklistDraft != null) Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).fillMaxWidth()
                            .inkDraftSurface(12.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            DraftSeal(18)
                            Text("这份清单是从正文整理的，保存前可以还原。", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 2, modifier = Modifier.weight(1f))
                            Text("还原正文", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.heightIn(min = 32.dp).wrapContentHeight().clickable { restoreTextBeforeChecklistDraft() })
                        }
                        if (checklist) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(entries, key = { it.id }) { entry ->
                                MemoChecklistRow(entry, labelInk, entry.id == requestedId, { focusedId = entry.id }, { text ->
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
                        } else BasicTextField(content, { content = it }, Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 5.7.dp, bottom = 8.dp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), textStyle = MaterialTheme.typography.bodyLarge.copy(color = labelInk, lineHeight = 27.sp),
                            decorationBox = { inner -> if (content.isEmpty()) Text("开始记录……", fontSize = 17.sp, color = iosTertiaryLabel); inner() })
                    }
                    }
                }
                // The iOS bottom bar: two items sit at the two edges; with the ⋯ menu as a third they line up from the left.
                val toolbarArrangement = when {
                    !checklist -> Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    entries.none { it.isCompleted } -> Arrangement.SpaceBetween
                    else -> Arrangement.spacedBy(16.dp, Alignment.Start)
                }
                Row(Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = toolbarArrangement) {
                    Row(Modifier.heightIn(min = 44.dp).semantics { contentDescription = if (checklist) "转为正文" else "转为清单" }.clickable {
                        textBeforeChecklistDraft = null
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
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { MojiLineIcon(if (checklist) MojiIcon.TextLines else MojiIcon.Checklist, 24.dp); Text(if (checklist) "转为正文" else "转为清单", fontSize = 14.sp) }
                    if (checklist) {
                        Row(Modifier.heightIn(min = 44.dp).semantics { contentDescription = "添加一项" }.clickable {
                            val index = entries.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: (entries.size - 1)
                            if (entries[index].text.isBlank()) requestedId = entries[index].id
                            else {
                                val added = MemoChecklistItem(UUID.randomUUID().toString())
                                entries = entries.take(index + 1) + added + entries.drop(index + 1); requestedId = added.id
                            }
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { MojiLineIcon(MojiIcon.Plus, 22.dp); Text("添加一项", fontSize = 14.sp) }
                        if (entries.any { it.isCompleted }) Box {
                            Box(Modifier.size(44.dp).wrapContentSize().semantics { contentDescription = "清单操作" }.clickable { checklistMenu = true }) { MojiLineIcon(MojiIcon.Ellipsis, 24.dp) }
                            DropdownMenu(checklistMenu, { checklistMenu = false }) { DropdownMenuItem({ Text("清除已完成") }, {
                                entries = entries.filterNot { it.isCompleted }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }; checklistMenu = false
                            }) }
                        }
                    }
                }
            }
        }
        if (showsChecklistDraft) {
            val memoTitle = title.trim()
            val text = content
            ChecklistDraftSheet(if (memoTitle.isEmpty()) "从正文里整理出要做的事" else "从“$memoTitle”的正文里整理出要做的事",
                load = { com.raydon.moji.android.ai.AIAssistant.shared.checklist(memoTitle, text) },
                accept = { useChecklistDraft(it) }, onDismiss = { showsChecklistDraft = false })
        }
        if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除备忘？") },
            confirmButton = { TextButton(onClick = { existing?.let { viewModel.deleteMemo(it.id) }; onDismiss() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    }
}

@Composable
private fun MemoChecklistRow(item: MemoChecklistItem, labelInk: Color, requestFocus: Boolean, onFocus: () -> Unit, onText: (String) -> Unit,
                             onChecked: (Boolean) -> Unit, onDelete: () -> Unit, onSplit: (String, String) -> Unit, onBackspace: () -> Boolean) {
    var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text)) }
    var menu by remember(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) requester.requestFocus() }
    LaunchedEffect(item.text) { if (value.composition == null && value.text != item.text) value = TextFieldValue(item.text, TextRange(item.text.length)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(44.dp).combinedClickable(onClick = { onChecked(!item.isCompleted) }, onLongClick = { menu = true }), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(if (item.isCompleted) mojiVermilion else androidx.compose.ui.graphics.Color.Transparent, CircleShape).border(if (item.isCompleted) 0.dp else 1.4.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                if (item.isCompleted) { val cut = MaterialTheme.colorScheme.background; androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                    val u = size.width / 20f
                    val check = androidx.compose.ui.graphics.Path().apply { moveTo(5.8f*u, 10.3f*u); lineTo(8.7f*u, 13.2f*u); lineTo(14.3f*u, 7.2f*u) }
                    drawPath(check, cut, style = androidx.compose.ui.graphics.drawscope.Stroke(2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                } }
            }
            DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("删除项目", color = mojiVermilion) }, { menu = false; onDelete() }) }
        }
        BasicTextField(value, { next ->
            value = next
            if (next.composition == null && '\n' in next.text) {
                val split = next.text.indexOf('\n'); onSplit(next.text.take(split), next.text.drop(split + 1))
            } else onText(next.text)
        }, Modifier.weight(1f).heightIn(min = 44.dp).padding(top = 10.dp, bottom = 13.6.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0 && value.composition == null && onBackspace() },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (item.isCompleted) iosSecondaryLabel else labelInk, textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null),
            decorationBox = { inner -> if (value.text.isEmpty()) Text("清单项目", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
    }
}
