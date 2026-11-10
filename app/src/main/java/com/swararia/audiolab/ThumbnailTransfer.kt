package com.swararia.audiolab

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Bounds compressed thumbnail bytes before any Android bitmap decoder is invoked. */
object ThumbnailTransfer {
    @JvmStatic fun read(input: InputStream, maxBytes: Int = 524_288): ByteArray {
        require(maxBytes in 1..1_048_576)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Thumbnail canceled")
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) {
                val single = input.read()
                if (single == -1) break
                if (output.size() == maxBytes) throw IOException("Thumbnail exceeds byte limit")
                output.write(single)
            } else {
                if (count > maxBytes - output.size()) throw IOException("Thumbnail exceeds byte limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
