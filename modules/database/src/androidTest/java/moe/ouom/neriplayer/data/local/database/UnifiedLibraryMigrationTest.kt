package moe.ouom.neriplayer.data.local.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedLibraryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), NeriUserDataDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun upgradePreservesEveryLegacyFieldInLibraryFavoritesHistoryAndOutbox() {
        val tables = listOf("local_playlist", "track", "playlist_member", "playlist_member_token",
            "favorite_playlist", "favorite_playlist_song", "play_history", "sync_outbox")
        val before = helper.createDatabase("unified-library-preservation", 20).use { db ->
            seedFrozenVersion20(db, tables)
            tables.associateWith { snapshot(db, it) }
        }
        helper.runMigrationsAndValidate(
            "unified-library-preservation", 21, true, NeriUserDataDatabase.MIGRATION_20_21
        ).use { db ->
            tables.forEach { assertEquals("Lost legacy data in $it", before[it], snapshot(db, it)) }
            assertEquals(emptyList<List<String?>>(), snapshot(db, "unified_song"))
            assertEquals(emptyList<List<String?>>(), snapshot(db, "unified_playlist"))
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
    }

    /** Read the frozen released schema, not the new implementation's entity defaults. */
    private fun seedFrozenVersion20(db: SupportSQLiteDatabase, tables: List<String>) {
        val asset = "${NeriUserDataDatabase::class.java.name}/20.json"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
            .bufferedReader().use { JSONObject(it.readText()) }
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val byName = (0 until entities.length()).associate { index ->
            entities.getJSONObject(index).let { it.getString("tableName") to it }
        }
        for (table in tables) {
            val fields = requireNotNull(byName[table]).getJSONArray("fields")
            val values = ContentValues()
            for (index in 0 until fields.length()) {
                val field = fields.getJSONObject(index)
                val column = field.getString("columnName")
                if (field.getString("affinity") == "INTEGER") {
                    values.put(column, when (column) {
                        "playlist_id" -> 17L
                        "song_id", "id", "identity_id" -> 9001L
                        "counter" -> 42L
                        else -> 101L
                    })
                } else {
                    values.put(column, when {
                        column == "identity_key" -> "legacy-key-9001"
                        column == "source" -> "netease"
                        column.endsWith("json") -> "{\"kept\":\"收藏与自定义歌词\"}"
                        else -> "preserved:$column"
                    })
                }
            }
            db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
}
