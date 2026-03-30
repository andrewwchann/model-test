package com.andre.alprprototype

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class PendingTrainingUploadEntry(
    val id: String,
    val payload: TrainingLogPayload,
)

class PendingTrainingUploadStore(context: Context) {
    private val gson = Gson()
    private val queueFile = File(context.filesDir, "pending_training_uploads.json")

    @Synchronized
    fun enqueue(payload: TrainingLogPayload): String {
        val entries = readEntries().toMutableList()
        val entryId = UUID.randomUUID().toString()
        entries += PendingTrainingUploadEntry(id = entryId, payload = payload)
        writeEntries(entries)
        return entryId
    }

    @Synchronized
    fun snapshot(): List<PendingTrainingUploadEntry> = readEntries()

    @Synchronized
    fun count(): Int = readEntries().size

    @Synchronized
    fun removeByIds(entryIds: Collection<String>) {
        if (entryIds.isEmpty()) {
            return
        }
        val entryIdSet = entryIds.toSet()
        val retainedEntries = readEntries().filterNot { it.id in entryIdSet }
        writeEntries(retainedEntries)
    }

    private fun readEntries(): List<PendingTrainingUploadEntry> {
        if (!queueFile.exists()) {
            return emptyList()
        }

        return try {
            val listType = object : TypeToken<List<PendingTrainingUploadEntry>>() {}.type
            gson.fromJson<List<PendingTrainingUploadEntry>>(queueFile.readText(), listType).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<PendingTrainingUploadEntry>) {
        if (entries.isEmpty()) {
            if (queueFile.exists()) {
                queueFile.delete()
            }
            return
        }
        queueFile.writeText(gson.toJson(entries))
    }
}
