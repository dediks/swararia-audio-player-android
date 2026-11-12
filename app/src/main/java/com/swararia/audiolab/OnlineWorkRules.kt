package com.swararia.audiolab

/** Presentation dispatch guard; read fresh state again after permission/picker returns. */
object OnlineWorkRules {
    @JvmStatic @JvmOverloads fun canStart(searchBusy: Boolean, ingestBusy: Boolean, previewBusy: Boolean = false): Boolean =
        !searchBusy && !ingestBusy && !previewBusy
}
