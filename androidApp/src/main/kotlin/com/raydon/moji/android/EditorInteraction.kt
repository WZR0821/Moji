package com.raydon.moji.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*

@Composable
fun rememberEditorDismiss(isDirty: Boolean, onDiscard: () -> Unit): () -> Unit {
    var confirming by remember { mutableStateOf(false) }
    val discard by rememberUpdatedState(onDiscard)
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { confirming = false; discard() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("继续编辑") } },
    )
    return { if (isDirty) confirming = true else discard() }
}
