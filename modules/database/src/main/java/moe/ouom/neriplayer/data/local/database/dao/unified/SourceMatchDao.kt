package moe.ouom.neriplayer.data.local.database.dao.unified

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.unified.SongMatchEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UserMatchOverrideEntity

@Dao
interface SourceMatchDao {
    @Query("SELECT m.* FROM song_match m LEFT JOIN user_match_override o ON o.match_id = m.match_id " +
        "WHERE m.song_id = :songId ORDER BY CASE WHEN o.decision = 'CONFIRMED' THEN 0 ELSE 1 END, " +
        "o.updated_at DESC, m.observed_at DESC, m.match_id LIMIT 200")
    suspend fun matches(songId: String): List<SongMatchEntity>

    @Query("SELECT * FROM song_match WHERE song_id = :songId AND provider = :provider AND resource_id = :resource " +
        "AND sub_resource_id = :sub AND recording_context = :context LIMIT 1")
    suspend fun match(songId: String, provider: String, resource: String, sub: String, context: String): SongMatchEntity?

    @Query("SELECT * FROM user_match_override WHERE match_id = :id")
    suspend fun override(id: String): UserMatchOverrideEntity?

    @Upsert suspend fun upsertMatch(value: SongMatchEntity)
    @Upsert suspend fun upsertOverride(value: UserMatchOverrideEntity)
}
