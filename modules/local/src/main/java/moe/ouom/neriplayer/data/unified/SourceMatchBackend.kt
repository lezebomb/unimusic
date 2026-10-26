package moe.ouom.neriplayer.data.unified

import android.content.Context
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*

class SourceMatchBackend(context: Context) {
    private val app = context.applicationContext
    private val store by lazy { SourceMatchRoomStore(NeriUserDataDatabase.getInstance(app)) }
    suspend fun matches(song: SongItem) = store.matches(song)
    suspend fun matches(songId: String, song: SongItem) = store.matches(songId, song)
    suspend fun subjectId(song: SongItem) = store.subjectId(song)
    suspend fun isRejected(song: SongItem, key: SongSourceKey) = store.isRejected(song, key)
    suspend fun observe(song: SongItem, candidate: RecordingSourceCandidate) = store.observe(song, candidate)
    suspend fun decide(song: SongItem, candidate: RecordingSourceCandidate, decision: SourceMatchDecision) = store.decide(song, candidate, decision)
    suspend fun clear(song: SongItem, key: SongSourceKey) = store.clear(song, key)
}
