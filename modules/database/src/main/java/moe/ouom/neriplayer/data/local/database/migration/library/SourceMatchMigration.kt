package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: never rewrite released schemas, sources, memberships or user edits. */
internal object SourceMatchMigration : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `song_match` (
            `match_id` TEXT NOT NULL, `song_id` TEXT NOT NULL, `provider` TEXT NOT NULL,
            `resource_id` TEXT NOT NULL, `sub_resource_id` TEXT NOT NULL, `recording_context` TEXT NOT NULL,
            `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL,
            `duration_ms` INTEGER NOT NULL, `recording_version` TEXT NOT NULL,
            `subject_fingerprint` TEXT NOT NULL, `algorithm_version` INTEGER NOT NULL,
            `observed_at` INTEGER NOT NULL, PRIMARY KEY(`match_id`),
            FOREIGN KEY(`song_id`) REFERENCES `unified_song`(`song_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_match_song_id` ON `song_match` (`song_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_song_match_song_id_provider_resource_id_sub_resource_id_recording_context` ON `song_match` (`song_id`, `provider`, `resource_id`, `sub_resource_id`, `recording_context`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `user_match_override` (
            `match_id` TEXT NOT NULL, `decision` TEXT NOT NULL, `revision` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL, PRIMARY KEY(`match_id`),
            FOREIGN KEY(`match_id`) REFERENCES `song_match`(`match_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""".trimIndent())
    }
}
