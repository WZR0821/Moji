package com.raydon.moji.android

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.raydon.moji.android.ai.ShareInbox
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分享: text or a picture another app shares with 墨记. Nothing of its own on
 * screen: the item goes into the inbox (text cut at 6,000 characters, only the
 * first picture, as JPEG with its long edge at most 2,048), then the app opens
 * with `moji://share` and the plan page turns it into drafts.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent
        lifecycleScope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching {
                    val text = shared.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                    val jpeg = firstImage(shared)?.let(::jpeg)
                    ShareInbox.of(this@ShareReceiverActivity).add(text, jpeg)
                }.isSuccess
            }
            if (kept) {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("moji://share"), this@ShareReceiverActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                Toast.makeText(this@ShareReceiverActivity, "没能读到可以收进的文字或图片。", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    /** The first picture: EXTRA_STREAM of SEND or SEND_MULTIPLE, else the clip some apps use instead. */
    private fun firstImage(intent: Intent): Uri? {
        if (intent.type?.startsWith("image/") != true && intent.clipData?.description?.hasMimeType("image/*") != true) return null
        val stream = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
            else -> null
        }
        return stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.let { clip: ClipData -> clip.getItemAt(0).uri }
    }

    /** Down to 2,048 on the long edge, on white where the picture is see-through, as JPEG. */
    private fun jpeg(uri: Uri): ByteArray? = runCatching { encode(uri) }.getOrNull()

    private fun encode(uri: Uri): ByteArray? {
        val limit = ShareInbox.imageLongEdge
        val decoded: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val long = maxOf(width, height)
                if (long > limit) {
                    val scale = limit.toDouble() / long
                    decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val long = maxOf(bitmap.width, bitmap.height)
            if (long > limit) {
                val scale = limit.toDouble() / long
                Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
            } else bitmap
        }
        val opaque = if (decoded.hasAlpha()) {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { blank ->
                Canvas(blank).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            }
        } else decoded
        return ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
