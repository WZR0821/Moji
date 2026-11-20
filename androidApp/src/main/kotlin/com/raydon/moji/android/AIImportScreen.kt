package com.raydon.moji.android

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.raydon.moji.android.ai.AIAssistant
import com.raydon.moji.android.ai.AIDrafting
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Text in a picture, read on the device. The picture itself is never sent. */
object LocalTextRecognizer {
    suspend fun text(context: Context, uri: Uri): String {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            return suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                        continuation.resume(lines.joinToString("\n"))
                    }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } finally { recognizer.close() }
    }
}

/** 从文字或截图添加: paste text or pick a screenshot, then turn it into plan drafts. */
@Composable
fun PlanImportDialog(viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var readingPhoto by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showsDrafts by remember { mutableStateOf(false) }
    var draftSource by remember { mutableStateOf("") }
    val clean = text.trim()
    val count = AIDrafting.characterCount(clean)
    val tooLong = count > AIDrafting.maximumInputCharacters
    fun append(found: String) { text = if (text.isEmpty()) found else text + "\n" + found }
    // Like iOS's paste button: greyed out while the clipboard holds no text. Reading the
    // description does not read the clip itself, so no "pasted" notice appears.
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    fun clipboardHasText() = clipboard.primaryClipDescription?.let { it.hasMimeType("text/*") } == true
    var canPaste by remember { mutableStateOf(clipboardHasText()) }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { canPaste = clipboardHasText() }
        clipboard.addPrimaryClipChangedListener(listener)
        onDispose { clipboard.removePrimaryClipChangedListener(listener) }
    }
    LifecycleResumeEffect(clipboard) { canPaste = clipboardHasText(); onPauseOrDispose {} }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingPhoto = true; failure = null
        scope.launch {
            try {
                val found = LocalTextRecognizer.text(context, uri)
                if (found.isBlank()) failure = "这张图里没有认出文字。" else append(found)
            } catch (error: Exception) {
                failure = "这张图片读不出来，换一张试试。"
            } finally { readingPhoto = false }
        }
    }
    LaunchedEffect(Unit) {
        // Debug only: read a screenshot pushed to the app's own external files folder, so the
        // on-device recognizer is exercised without the system photo picker.
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "ai-import-ocr" && text.isEmpty()) {
            val file = java.io.File(context.getExternalFilesDir(null), "qa-ocr.png")
            readingPhoto = true
            try {
                val found = LocalTextRecognizer.text(context, Uri.fromFile(file))
                android.util.Log.i("MojiQA", "ocr ${found.length} chars: ${found.replace('\n', '|')}")
                if (found.isBlank()) failure = "这张图里没有认出文字。" else append(found)
            } catch (error: Exception) {
                android.util.Log.e("MojiQA", "ocr failed", error)
                failure = "这张图片读不出来，换一张试试。"
            } finally { readingPhoto = false }
        }
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "ai-import-drafts" && text.isEmpty()) {
            text = "周五前把志望理由書终稿交上去，另外约一下体检，今天记得给老师回邮件确认面谈时间。"
            delay(400); draftSource = text.trim(); showsDrafts = true
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            if (showsDrafts) {
                androidx.activity.compose.BackHandler { showsDrafts = false }
                val source = draftSource
                PlanDraftPage(viewModel, "计划草稿", "从这段文字里找到的事", waitingText = "正在找要做的事",
                    load = { AIAssistant.shared.importPlans(source, aiCategories(viewModel)) },
                    leading = "返回", onLeading = { showsDrafts = false }, close = onDismiss)
            } else Column(Modifier.fillMaxSize()) {
                MojiSheetBar("从文字或截图添加", "取消", "整理", onDismiss, { draftSource = clean; showsDrafts = true },
                    trailingEnabled = clean.isNotEmpty() && !tooLong && !readingPhoto)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("粘贴一段通知、聊天记录或会议纪要，或者选一张截图，把里面要做的事找出来。", fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.secondary)
                    val paper = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.095f)
                    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).background(paper, RoundedCornerShape(16.dp))
                        .drawBehind { drawRoundRect(edge, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(0.75.dp.toPx())) }
                        .padding(horizontal = 15.dp, vertical = 16.dp)) {
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 188.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 25.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (text.isEmpty()) Text("把文字粘贴到这里……", fontSize = 17.sp, color = iosTertiaryLabel); inner() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ImportCapsule("粘贴", MojiIcon.Clipboard, enabled = canPaste) {
                            val pasted = clipboard.primaryClip?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() } }?.joinToString("\n").orEmpty()
                            if (pasted.isNotBlank()) append(pasted)
                        }
                        ImportCapsule("选截图", MojiIcon.Photo, enabled = !readingPhoto) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        if (readingPhoto) InkDraftWaiting("正在认字")
                        Spacer(Modifier.weight(1f))
                        Text("$count", fontSize = 12.sp, color = if (tooLong) mojiVermilion else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
                    }
                    when {
                        failure != null -> Text(failure!!, fontSize = 13.sp, color = mojiVermilion)
                        tooLong -> Text("内容太长了，请缩短到 6,000 字以内。", fontSize = 13.sp, color = mojiVermilion)
                        else -> Text("截图里的字在本机识别，只把文字发给模型。", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportCapsule(title: String, icon: MojiIcon, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp).alpha(if (enabled) 1f else 0.35f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MojiLineIcon(icon, 17.dp, MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}
