package moe.ouom.neriplayer.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistImportMigrationTest {
    @Test fun upgrade21KeepsLibrary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("import-upgrade.db").apply { parentFile?.mkdirs() }
        // Frozen exported v21 schema is the input, not the current entity definitions.
        val schemaFile = File("schemas/${NeriUserDataDatabase::class.java.name}/21.json")
        val entities = JSONObject(schemaFile.readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO unified_song VALUES ('song', '用户标题', '歌手', '专辑', 123, 'UNKNOWN', NULL, NULL, 10, 20)")
            db.execSQL("INSERT INTO song_source VALUES ('source', 'song', 'netease', '42', '', '', 0, 'METADATA_ONLY', 'UNKNOWN', NULL, NULL)")
            db.execSQL("INSERT INTO legacy_song_link VALUES ('old-key', 'source')")
            db.execSQL("INSERT INTO unified_playlist VALUES ('list', 17, '保留名称', 'cover', 20, 0)")
            db.execSQL("INSERT INTO unified_playlist_member VALUES ('list', 'song', 7, 10, 20, 1)")
            db.execSQL("INSERT INTO playlist_origin VALUES ('origin', 'list', 'netease', '7', '10', 'LINKED', 0, 10, 20, 'old-snapshot')")
            db.version = 21
        }
        val tables = listOf("unified_song", "song_source", "legacy_song_link", "unified_playlist", "unified_playlist_member", "playlist_origin")
        val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            tables.associateWith { table -> db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            } }
        }
        val room = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file.name)
            .addMigrations(NeriUserDataDatabase.MIGRATION_21_22).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase // Room validates every v22 table and index.
            assertEquals(22, db.version)
            tables.forEach { table -> db.query("SELECT * FROM $table").use { cursor ->
                val after = buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
                assertEquals(table, before[table], after)
            } }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.query("SELECT COUNT(*) FROM playlist_import_job").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { room.close(); context.deleteDatabase(file.name) }
    }
}
