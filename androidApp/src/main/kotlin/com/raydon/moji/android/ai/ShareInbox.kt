package com.raydon.moji.android.ai

import android.content.Context
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Ported from Shared/ShareInbox.swift.

/** One thing shared into Moji from another app and not yet turned into plans. */
data class ShareInboxItem(
    val id: String,
    /** Seconds since 1970. */
    val createdAt: Double,
    val text: String?,
    /** File name of the picture beside the JSON, e.g. `<uuid>.jpg`. */
    val image: String?,
)

/**
 * 分享的收件箱: ShareReceiverActivity writes, the plan page reads one item at
 * a time and deletes it once it has been drafted. A folder of the app's own
 * (`files/share_inbox/`), left out of device backups.
 */
class ShareInbox(private val directory: File) {
    /** Text past 6,000 characters is cut; only the first picture is kept, already as JPEG. */
    fun add(text: String?, jpeg: ByteArray?, now: Instant = Instant.now()): ShareInboxItem {
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }?.let { AIDrafting.prefix(it, textLimit) }
        var image: String? = null
        if (jpeg != null && jpeg.isNotEmpty()) {
            val name = "$id.jpg"
            write(File(directory, name), jpeg)
            image = name
        }
        if (clean == null && image == null) throw IllegalArgumentException("nothing to keep")
        val item = ShareInboxItem(id, now.toEpochMilli() / 1000.0, clean, image)
        write(File(directory, "$id.json"), buildJsonObject {
            put("id", item.id)
            put("createdAt", item.createdAt)
            item.text?.let { put("text", it) }
            item.image?.let { put("image", it) }
        }.toString().toByteArray(Charsets.UTF_8))
        return item
    }

    /** Everything waiting, oldest first. */
    fun items(): List<ShareInboxItem> {
        val files = directory.listFiles { file -> file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            val values = runCatching { Json.parseToJsonElement(file.readText(Charsets.UTF_8)) }.getOrNull() as? JsonObject ?: return@mapNotNull null
            val id = values["id"].aiString() ?: return@mapNotNull null
            ShareInboxItem(id, (values["createdAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0,
                values["text"].aiString(), values["image"].aiString())
        }.sortedBy { it.createdAt }
    }

    fun oldest(): ShareInboxItem? = items().firstOrNull()

    fun imageFile(item: ShareInboxItem): File? = item.image?.let { File(directory, it) }?.takeIf { it.isFile }

    fun remove(item: ShareInboxItem) {
        File(directory, "${item.id}.json").delete()
        item.image?.let { File(directory, it).delete() }
    }

    fun removeAll() {
        items().forEach(::remove)
    }

    /** Everything in the folder, including leftovers that no longer read as an item. */
    fun clear() {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.writeBytes(bytes)
            temporary.delete()
        }
    }

    companion object {
        const val folderName = "share_inbox"
        const val textLimit = 6_000
        const val imageLongEdge = 2_048

        /** The app's inbox; while the QA fixture is on, a scratch folder in the cache instead. */
        fun of(context: Context): ShareInbox {
            val app = context.applicationContext
            return if (AIQAFixture.isActive) ShareInbox(File(app.cacheDir, "qa_$folderName")) else ShareInbox(File(app.filesDir, folderName))
        }
    }
}
