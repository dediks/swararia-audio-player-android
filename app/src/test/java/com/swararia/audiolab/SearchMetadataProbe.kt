package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Host-only verifier. Never included in APK. No signed stream URLs printed. */
object SearchMetadataProbe {
    @JvmStatic fun main(args: Array<String>) {
        val mapper = ObjectMapper()
        when (args.firstOrNull()) {
            "arguments" -> println(mapper.writeValueAsString(YoutubeSearchRules.arguments(args[2], SearchProvider.valueOf(args[1]),
                args.getOrNull(3)?.let(SearchKind::valueOf) ?: SearchKind.SONGS, args.getOrNull(4)?.toInt() ?: 0,
                args.getOrNull(5)?.takeIf { it.isNotBlank() })))
            "parse" -> {
                val kind=args.getOrNull(2)?.let(SearchKind::valueOf) ?: SearchKind.SONGS
                val hits=YoutubeSearchJson.parse(File(args[1]).readText(),kind)
                require(hits.isNotEmpty() && hits.size<=YoutubeSearchRules.RESULT_LIMIT)
                require(hits.map { it.videoId }.distinct().size==hits.size)
                require(hits.all { if(kind==SearchKind.SONGS) it.url=="https://www.youtube.com/watch?v=${it.videoId}"
                    else it.url=="https://www.youtube.com/playlist?list=${it.videoId}" })
                println(mapper.writeValueAsString(mapOf("result_count" to hits.size,"complete_count" to hits.count(YoutubeSearchRules::complete),"items" to hits)))
            }
            "preview" -> {
                val media=OnlinePreviewRules.parse(File(args[1]).readText(),args[2],"Host test")
                println(mapper.writeValueAsString(mapOf("video_id" to media.videoId,"url_policy_passed" to true)))
            }
            else -> error("Expected arguments, parse or preview")
        }
    }
}
