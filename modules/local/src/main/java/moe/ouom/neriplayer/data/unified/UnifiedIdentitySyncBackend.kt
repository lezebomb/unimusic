package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.unified.sync.PortableSongIdentity
import moe.ouom.neriplayer.data.model.unified.sync.PortableSongMetadata
import moe.ouom.neriplayer.data.sync.unified.*

data class RoomIdentityCaptureResult(
    val scannedSources: Int,
    val excludedDeviceSources: Int,
    val excludedUnsupportedSources: Int,
    val observedSongs: Int,
    val journal: UnifiedIdentityCaptureResult
)

data class PortableIdentityExport(val bytes: ByteArray, val capture: RoomIdentityCaptureResult)
enum class LocalIdentityLookupStatus { READY, INVALID_ID, UNKNOWN_ID, DELETED, CONFLICT, NOT_ON_DEVICE }
data class LocalIdentityLookup(val status: LocalIdentityLookupStatus, val canonicalSongId: String? = null,
    val localSongIds: List<String> = emptyList(), val decisionRevision: Long? = null)

data class IdentityBackendSyncResult(val capture: RoomIdentityCaptureResult, val remote: UnifiedIdentitySyncOutcome)

/** Explicit backend entry. Captures portable recording identities, not playlist membership or rights. */
class UnifiedIdentitySyncBackend private constructor(
    databaseProvider: () -> NeriUserDataDatabase,
    journalProvider: () -> UnifiedIdentityJournal
) {
    private val captureMutex = Mutex()
    private val syncMutex = Mutex()
    private data class AliasCache(val revision: Long, val state: UnifiedIdentityMergeResult,
        val songs: Map<String, UnifiedIdentityState>, val originals: Map<String, List<String>>)
    @Volatile private var aliasCache: AliasCache? = null
    private val database by lazy(databaseProvider)
    private val journal by lazy(journalProvider)

    constructor(context: Context) : this(
        { NeriUserDataDatabase.getInstance(context.applicationContext ?: context) },
        { UnifiedIdentityJournal(File(requireNotNull((context.applicationContext ?: context).noBackupFilesDir),
            "unimusic-identities/device-journal-v1.json")) }
    )

    internal constructor(database: NeriUserDataDatabase, journal: UnifiedIdentityJournal) : this({ database }, { journal })

    /** Resolve a foreign UUID without moving legacy keys, sources, memberships or user metadata. */
    suspend fun lookupLocal(songId: String): LocalIdentityLookup = withContext(Dispatchers.IO) {
        if (!canonicalUuid(songId)) return@withContext LocalIdentityLookup(LocalIdentityLookupStatus.INVALID_ID)
        val revision = journal.revision.value
        val cached = aliasCache?.takeIf { it.revision == revision } ?: journal.snapshot().let { state ->
            AliasCache(revision, state, state.songs.associateBy { it.songId },
                state.aliases.keys.sorted().groupBy { state.aliases.getValue(it) }).also { aliasCache = it }
        }
        val canonical = cached.state.aliases[songId]
            ?: return@withContext LocalIdentityLookup(LocalIdentityLookupStatus.UNKNOWN_ID)
        val state = cached.songs[canonical]
            ?: return@withContext LocalIdentityLookup(LocalIdentityLookupStatus.UNKNOWN_ID, canonical)
        if (state.deleted) return@withContext LocalIdentityLookup(LocalIdentityLookupStatus.DELETED, canonical)
        if (state.hasConflict) return@withContext LocalIdentityLookup(LocalIdentityLookupStatus.CONFLICT, canonical)
        val local = mutableListOf<String>()
        for (page in cached.originals.getValue(canonical).chunked(200)) {
            currentCoroutineContext().ensureActive()
            local += database.unifiedLibraryDao().songsByIds(page).map { it.songId }.sorted()
            if (local.size >= 32) break
        }
        LocalIdentityLookup(if (local.isEmpty()) LocalIdentityLookupStatus.NOT_ON_DEVICE else LocalIdentityLookupStatus.READY,
            canonical, local.take(32), revision)
    }

    suspend fun conflictsPage(offset: Int = 0, limit: Int = 20): IdentityConflictPage = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1..20)
        val captured = captureRoom()
        val snapshot = journal.decisionSnapshot()
        val pending = captured.journal.localConflictSongIds.map { snapshot.state.aliases[it] ?: it }.toSet()
        val states = snapshot.state.songs.associateBy { it.songId }
        val groups = (snapshot.state.songs.filter { it.hasConflict }.map { it.songId } + pending).distinct().sorted()
        val selected = groups.drop(offset).take(limit)
        val originals = snapshot.state.aliases.keys.filter { snapshot.state.aliases[it] in selected }
        val localRows = database.withTransaction {
            originals.chunked(200).flatMap { database.unifiedLibraryDao().songsByIds(it) }
        }.mapNotNull { row ->
            val metadata = PortableSongMetadata(row.title, row.artist, row.album, row.durationMs, row.recordingVersion)
            try { UnifiedIdentityValidation.validateMetadata(metadata);LocalIdentityMetadata(row.songId, metadata) }
            catch (_: IllegalArgumentException) { null }
        }.groupBy { snapshot.state.aliases.getValue(it.songId) }
        IdentityConflictPage(groups.size, offset, selected.map { canonical ->
            val local = localRows[canonical].orEmpty().sortedBy { it.songId }
            val metadata = (local.map { it.metadata } + states[canonical]?.concurrentRevisions.orEmpty().map { it.metadata }).distinct()
            IdentityConflictView(canonical, snapshot.revision, metadata.map { value ->
                IdentityConflictChoice(value, value != null && local.any { it.metadata == value })
            }, local)
        })
    }

    suspend fun resolveConflict(view: IdentityConflictView, choice: IdentityConflictChoice) = withContext(Dispatchers.IO) {
        require(choice in view.choices) { "Unknown conflict choice" }
        val current = database.withTransaction {
            view.localSongs.map { it.songId }.chunked(200).flatMap { database.unifiedLibraryDao().songsByIds(it) }
        }.map { LocalIdentityMetadata(it.songId, PortableSongMetadata(it.title, it.artist, it.album, it.durationMs, it.recordingVersion)) }
            .sortedBy { it.songId }
        if (current != view.localSongs.sortedBy { it.songId }) throw StaleIdentityDecisionException()
        journal.resolveConflict(view.songId, view.revision, choice.metadata, view.localSongs)
    }

    fun isAliasRevisionCurrent(revision: Long): Boolean = journal.revision.value == revision

    suspend fun snapshot() = withContext(Dispatchers.IO) { journal.snapshot() }
    suspend fun mergeRemote(bytes: ByteArray) = withContext(Dispatchers.IO) { journal.mergeRemote(bytes) }

    /** Explicit opt-in invocation only; no existing scheduler or cloud setting is changed. */
    suspend fun <V> synchronize(remote: UnifiedIdentityRemoteTransport<V>): IdentityBackendSyncResult = syncMutex.withLock {
        val capture = captureRoom()
        IdentityBackendSyncResult(capture, UnifiedIdentitySyncCoordinator(journal, remote).synchronize())
    }

    suspend fun exportCurrent(): PortableIdentityExport {
        val capture = captureRoom()
        return PortableIdentityExport(withContext(Dispatchers.IO) { journal.export() }, capture)
    }

    /** One Room read transaction followed by one journal batch; neither store deletes user rows. */
    suspend fun captureRoom(): RoomIdentityCaptureResult = withContext(Dispatchers.IO) { captureMutex.withLock {
        var scanned = 0
        var excludedDevice = 0
        var excludedUnsupported = 0
        val observations = database.withTransaction {
            val dao = database.unifiedLibraryDao()
            val metadata = linkedMapOf<String, PortableSongMetadata>()
            val sources = linkedMapOf<String, MutableList<PortableSongIdentity>>()
            var after: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.identitySourcesPage(after, 200)
                if (page.isEmpty()) break
                scanned += page.size
                require(scanned <= MAX_SCANNED_SOURCES) { "Identity capture scan limit exceeded" }
                val songs = dao.songsByIds(page.map { it.songId }.distinct()).associateBy { it.songId }
                page.forEach { source ->
                    if (source.deviceOnly) { excludedDevice++; return@forEach }
                    val identity = PortableSongIdentity(source.songId, source.provider, source.resourceId,
                        source.subResourceId, source.recordingContext)
                    val row = requireNotNull(songs[source.songId]) { "Identity source has no song" }
                    val value = PortableSongMetadata(row.title, row.artist, row.album, row.durationMs, row.recordingVersion)
                    try {
                        UnifiedIdentityValidation.validateIdentity(identity)
                        UnifiedIdentityValidation.validateMetadata(value)
                    } catch (_: IllegalArgumentException) {
                        excludedUnsupported++
                        return@forEach
                    }
                    metadata[source.songId] = value
                    sources.getOrPut(source.songId) { mutableListOf() }.add(identity)
                    require(sources.size <= UnifiedIdentityValidation.MAX_IDENTITIES) { "Too many portable songs" }
                }
                after = page.last().sourceId
            }
            sources.map { (id, keys) -> LocalIdentityObservation(id, metadata.getValue(id), keys.toList()) }
        }
        RoomIdentityCaptureResult(scanned, excludedDevice, excludedUnsupported, observations.size, journal.capture(observations))
    } }

    companion object {
        private const val MAX_SCANNED_SOURCES = 200_000
        @Volatile private var shared: UnifiedIdentitySyncBackend? = null
        fun getInstance(context: Context): UnifiedIdentitySyncBackend = shared ?: synchronized(this) {
            shared ?: UnifiedIdentitySyncBackend(context.applicationContext ?: context).also { shared = it }
        }
    }
}
