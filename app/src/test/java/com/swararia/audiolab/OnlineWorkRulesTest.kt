package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class OnlineWorkRulesTest {
    @Test fun thumbnailTransferPreservesBytesAndRejectsOverflow() {
        val payload = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(payload, ThumbnailTransfer.read(payload.inputStream(), 4))
        assertArrayEquals(byteArrayOf(), ThumbnailTransfer.read(byteArrayOf().inputStream(), 4))
        assertThrows(java.io.IOException::class.java) { ThumbnailTransfer.read(payload.inputStream(), 3) }
        assertThrows(IllegalArgumentException::class.java) { ThumbnailTransfer.read(payload.inputStream(), 0) }
    }

    @Test fun previewLookupAlsoBlocksOnlineDispatch() {
        assertFalse(OnlineWorkRules.canStart(searchBusy = false, ingestBusy = false, previewBusy = true))
        assertTrue(OnlineWorkRules.canStart(searchBusy = false, ingestBusy = false, previewBusy = false))
    }

    @Test fun startsOnlyWhenBothWorkersAreIdle() {
        assertTrue(OnlineWorkRules.canStart(searchBusy = false, ingestBusy = false))
        assertFalse(OnlineWorkRules.canStart(searchBusy = true, ingestBusy = false))
        assertFalse(OnlineWorkRules.canStart(searchBusy = false, ingestBusy = true))
        assertFalse(OnlineWorkRules.canStart(searchBusy = true, ingestBusy = true))
    }
}
