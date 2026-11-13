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
    // Executed verbatim by both Room and host sqlite migration tests for v2 -> v3.
    internal val MIGRATION_2_3_STATEMENTS = listOf(
        "ALTER TABLE tracks ADD COLUMN artist TEXT",
        "ALTER TABLE tracks ADD COLUMN album TEXT",
        "ALTER TABLE tracks ADD COLUMN genre TEXT",
        "ALTER TABLE tracks ADD COLUMN metadataCheckedAt INTEGER",
        "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)",
        "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `trackId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)",
        "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)",
    )
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in MIGRATION_2_3_STATEMENTS) db.execSQL(statement)
        }
    }
}
