package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only. Existing songs, playlists, history and sync tokens remain untouched. */
internal object UnifiedLibraryMigration : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `unified_song` (
                `song_id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL,
                `recording_version` TEXT NOT NULL, `isrc` TEXT, `musicbrainz_recording_id` TEXT,
                `created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL,
                PRIMARY KEY(`song_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_song_title` ON `unified_song` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_song_artist` ON `unified_song` (`artist`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `song_source` (
                `source_id` TEXT NOT NULL, `song_id` TEXT NOT NULL, `provider` TEXT NOT NULL,
                `resource_id` TEXT NOT NULL, `sub_resource_id` TEXT NOT NULL,
                `recording_context` TEXT NOT NULL, `device_only` INTEGER NOT NULL,
                `playback_mode` TEXT NOT NULL, `availability` TEXT NOT NULL,
                `checked_at` INTEGER, `permission_expires_at` INTEGER,
                PRIMARY KEY(`source_id`), FOREIGN KEY(`song_id`) REFERENCES `unified_song`(`song_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_source_song_id` ON `song_source` (`song_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_song_source_provider_resource_id_sub_resource_id_recording_context` ON `song_source` " +
            "(`provider`, `resource_id`, `sub_resource_id`, `recording_context`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `legacy_song_link` (
                `identity_key` TEXT NOT NULL, `source_id` TEXT NOT NULL, PRIMARY KEY(`identity_key`),
                FOREIGN KEY(`source_id`) REFERENCES `song_source`(`source_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_legacy_song_link_source_id` ON `legacy_song_link` (`source_id`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `unified_playlist` (
                `playlist_id` TEXT NOT NULL, `legacy_playlist_id` INTEGER NOT NULL,
                `name` TEXT NOT NULL, `cover_url` TEXT, `modified_at` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL, PRIMARY KEY(`playlist_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_unified_playlist_legacy_playlist_id` " +
            "ON `unified_playlist` (`legacy_playlist_id`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `unified_playlist_member` (
                `playlist_id` TEXT NOT NULL, `song_id` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `added_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL,
                PRIMARY KEY(`playlist_id`, `song_id`),
                FOREIGN KEY(`playlist_id`) REFERENCES `unified_playlist`(`playlist_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`song_id`) REFERENCES `unified_song`(`song_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_playlist_member_playlist_id_position` " +
            "ON `unified_playlist_member` (`playlist_id`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_playlist_member_song_id` ON `unified_playlist_member` (`song_id`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `playlist_origin` (
                `origin_id` TEXT NOT NULL, `playlist_id` TEXT NOT NULL, `provider` TEXT NOT NULL,
                `account_key` TEXT NOT NULL, `remote_playlist_id` TEXT NOT NULL,
                `import_mode` TEXT NOT NULL, `linked` INTEGER NOT NULL, `imported_at` INTEGER NOT NULL,
                `refreshed_at` INTEGER, `snapshot_id` TEXT, PRIMARY KEY(`origin_id`),
                FOREIGN KEY(`playlist_id`) REFERENCES `unified_playlist`(`playlist_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_origin_playlist_id` ON `playlist_origin` (`playlist_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_origin_provider_account_key_remote_playlist_id` " +
            "ON `playlist_origin` (`provider`, `account_key`, `remote_playlist_id`)")
    }
}
