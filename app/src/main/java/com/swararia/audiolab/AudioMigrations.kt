package com.swararia.audiolab

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AudioMigrations {
    // Executed verbatim by both Room and the host sqlite migration regression.
    const val ADD_ARTWORK = "ALTER TABLE tracks ADD COLUMN artworkKey TEXT"
    const val CREATE_DOWNLOADS = "CREATE TABLE IF NOT EXISTS `download_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchId` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnailUrl` TEXT, `status` TEXT NOT NULL, `progress` REAL, `stage` TEXT NOT NULL, `error` TEXT, `trackId` INTEGER, `authorized` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `publicationName` TEXT, `pendingUri` TEXT, `publicationBytes` INTEGER)"
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ADD_ARTWORK)
            db.execSQL(CREATE_DOWNLOADS)
        }
    }
}
