package com.swararia.audiolab

data class DownloadRequest(val sourceUrl: String, val title: String, val thumbnailUrl: String? = null)
