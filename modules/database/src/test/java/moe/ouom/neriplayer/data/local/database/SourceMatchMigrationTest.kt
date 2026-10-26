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
class SourceMatchMigrationTest {
    @Test fun upgrade22KeepsData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("source-upgrade.db").apply { parentFile?.mkdirs() }
        val entities = JSONObject(File("schemas/${NeriUserDataDatabase::class.java.name}/22.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val tables = mutableListOf<String>()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName").also(tables::add)
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO track (identity_key, identity_id, identity_album, song_id, name, artist, album, album_id, duration_ms, payload_schema_version, durable_payload_json) VALUES ('legacy',42,'netease',42,'自定义标题','歌手','专辑',1,180000,1,'{\"customName\":\"保留\"}')")
            db.execSQL("INSERT INTO unified_song VALUES ('song','原曲','歌手','专辑',180000,'UNKNOWN',NULL,NULL,1,2)")
            db.execSQL("INSERT INTO song_source VALUES ('source','song','netease','42','','',0,'METADATA_ONLY','UNKNOWN',NULL,NULL)")
            db.execSQL("INSERT INTO legacy_song_link VALUES ('legacy','source')")
            db.execSQL("INSERT INTO unified_playlist VALUES ('list',17,'用户歌单',NULL,2,0)")
            db.execSQL("INSERT INTO unified_playlist_member VALUES ('list','song',3,1,2,1)")
            db.execSQL("INSERT INTO playlist_origin VALUES ('origin','list','netease','7','9','LINKED',0,1,2,'snapshot')")
            db.execSQL("INSERT INTO playlist_import_job VALUES ('job','netease','7','9','LINKED','COMPLETED','list','snapshot','远端名称',NULL,2,1,0,1,NULL,0,0,0,NULL,1,2)")
            db.execSQL("INSERT INTO playlist_import_receipt VALUES ('job','42','song','legacy','snapshot',0,1)")
            db.version = 22
        }
        val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            tables.associateWith { table -> db.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            } }
        }
        val room = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file.name)
            .addMigrations(NeriUserDataDatabase.MIGRATION_22_23).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(23, db.version)
            tables.forEach { table -> db.query("SELECT * FROM `$table`").use { cursor ->
                val after = buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
                assertEquals(table, before[table], after)
            } }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.query("SELECT COUNT(*) FROM song_match").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { room.close(); context.deleteDatabase(file.name) }
    }
}
