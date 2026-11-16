package com.bloemstudios.admin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.bloemstudios.admin.R
import com.bloemstudios.admin.organization.findActivity

/** Copies a record's text to the clipboard or hands it to the system share sheet. */
class TextSharer internal constructor(
    private val context: Context,
    private val copyToClipboard: (String) -> Unit,
    private val chooserTitle: String,
) {
    fun copy(text: String) = copyToClipboard(text)

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, chooserTitle)
        context.findActivity()?.startActivity(chooser) ?: context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The one Copy and Share for log, audit and history details; the share sheet is titled "Share details". */
@Composable
fun rememberTextSharer(): TextSharer {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val title = stringResource(R.string.share_details_title)
    return remember(context, clipboard, title) { TextSharer(context, { clipboard.setText(AnnotatedString(it)) }, title) }
}
