package com.swararia.audiolab

/** Presentation dispatch guard; read fresh state again after permission/picker returns. */
object OnlineWorkRules {
    @JvmStatic fun canStart(searchBusy: Boolean, ingestBusy: Boolean): Boolean =
        !searchBusy && !ingestBusy
}
