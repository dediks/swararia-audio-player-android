package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Host verification utility only; not included in the APK. */
object SearchMetadataProbe {
    @JvmStatic fun main(args: Array<String>) {
        val mapper = ObjectMapper()
        when (args.firstOrNull()) {
            "arguments" -> println(mapper.writeValueAsString(YoutubeSearchRules.arguments(args[2], SearchProvider.valueOf(args[1]))))
            "parse" -> {
                val hits = YoutubeSearchJson.parse(File(args[1]).readText())
                require(hits.isNotEmpty() && hits.size <= YoutubeSearchRules.RESULT_LIMIT)
                require(hits.map { it.videoId }.distinct().size == hits.size)
                require(hits.all { it.url == "https://www.youtube.com/watch?v=${it.videoId}" })
                println(mapper.writeValueAsString(mapOf("result_count" to hits.size, "items" to hits)))
            }
            else -> error("Expected arguments or parse")
        }
    }
}
