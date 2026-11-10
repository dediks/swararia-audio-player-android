package com.swararia.audiolab

object LibraryRules {
    fun durationLabel(milliseconds: Long?): String {
        if (milliseconds == null || milliseconds < 0) return "--:--"
        val seconds = milliseconds / 1000
        return if (seconds >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    fun validatedHttpsUrl(input: String): String {
        val value = input.trim()
        val uri = try { java.net.URI(value) } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("URL tidak valid.")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Gunakan URL HTTPS tanpa data login di dalam URL."
        }
        return value
    }

    fun renamedDisplayName(original: String, requestedTitle: String): String {
        val extension = original.substringAfterLast('.', "")
        var title = requestedTitle.trim()
        if (extension.isNotEmpty() && title.endsWith(".$extension", ignoreCase = true)) {
            title = title.dropLast(extension.length + 1).trim()
        }
        require(title.isNotBlank() && title != "." && title != "..") { "Isi nama baru terlebih dahulu." }
        require(title.none { it == '/' || it == '\\' || it.isISOControl() }) { "Nama tidak boleh berisi pemisah folder atau baris baru." }
        require(title.toByteArray(Charsets.UTF_8).size <= 180) { "Nama terlalu panjang. Maksimal 180 byte." }
        return title + if (extension.isEmpty()) "" else ".$extension"
    }
}
