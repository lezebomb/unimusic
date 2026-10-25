package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep released v21 and all original user tables intact. */
internal object PlaylistImportMigration : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `playlist_import_job` (
            `job_id` TEXT NOT NULL, `provider` TEXT NOT NULL, `account_key` TEXT NOT NULL,
            `remote_playlist_id` TEXT NOT NULL, `import_mode` TEXT NOT NULL, `state` TEXT NOT NULL,
            `playlist_id` TEXT, `snapshot_id` TEXT, `remote_name` TEXT, `remote_cover_url` TEXT,
            `total` INTEGER NOT NULL, `imported_count` INTEGER NOT NULL, `failed_count` INTEGER NOT NULL,
            `skipped_count` INTEGER NOT NULL, `run_token` TEXT, `lease_until` INTEGER NOT NULL,
            `retry_at` INTEGER NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT,
            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`job_id`))""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_import_job_provider_account_key_remote_playlist_id` ON `playlist_import_job` (`provider`, `account_key`, `remote_playlist_id`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `playlist_import_item` (
            `job_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `resource_id` TEXT NOT NULL,
            `state` TEXT NOT NULL, `failure_kind` TEXT, `attempt_count` INTEGER NOT NULL,
            `retry_at` INTEGER NOT NULL, PRIMARY KEY(`job_id`, `position`),
            FOREIGN KEY(`job_id`) REFERENCES `playlist_import_job`(`job_id`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_import_item_job_id_resource_id` ON `playlist_import_item` (`job_id`, `resource_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_import_item_job_id_state_position` ON `playlist_import_item` (`job_id`, `state`, `position`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `playlist_import_receipt` (
            `job_id` TEXT NOT NULL, `resource_id` TEXT NOT NULL, `song_id` TEXT NOT NULL,
            `identity_key` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `position` INTEGER NOT NULL,
            `removed` INTEGER NOT NULL, PRIMARY KEY(`job_id`, `resource_id`),
            FOREIGN KEY(`job_id`) REFERENCES `playlist_import_job`(`job_id`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
    }
}
