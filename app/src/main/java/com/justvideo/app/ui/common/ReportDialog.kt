package com.justvideo.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justvideo.app.ui.theme.Ink

// =====================================================================
// ReportDialog.kt
// Shows a long block of text (a crash report or the app log) with
// Copy and Share buttons, so you can paste it to me in chat.
// =====================================================================

@Composable
fun ReportDialog(
    title: String,
    intro: String,
    report: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // STEP 1: The intro sits above the report.
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                // STEP 2: The report text. You can scroll it both ways and
                // select parts of it.
                SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(Ink, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(report, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        },
        // STEP 3: Copy = put the whole text on the clipboard.
        confirmButton = {
            TextButton(onClick = { copyToClipboard(context, report) }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = { share(context, title, report) }) { Text("Share") }
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("JustVideo report", text))
    Toast.makeText(context, "Copied. Paste it into the chat", Toast.LENGTH_SHORT).show()
}

// STEP 4: Share = open Android's share sheet (send the text by email, messages, etc.).
private fun share(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}
