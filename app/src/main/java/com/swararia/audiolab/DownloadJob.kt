package com.swararia.audiolab

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_jobs")
data class DownloadJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: String,
    val progress: Float?,
    val stage: String,
    val error: String?,
    val trackId: Long?,
    val authorized: Boolean,
    val createdAt: Long,
    val publicationName: String? = null,
    val pendingUri: String? = null,
    val publicationBytes: Long? = null
)
