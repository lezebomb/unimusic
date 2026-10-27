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

/** Explicit backend entry. Captures portable recording identities, not playlist membership or rights. */
class UnifiedIdentitySyncBackend private constructor(
    databaseProvider: () -> NeriUserDataDatabase,
    journalProvider: () -> UnifiedIdentityJournal
) {
    private val captureMutex = Mutex()
    private val database by lazy(databaseProvider)
    private val journal by lazy(journalProvider)

    constructor(context: Context) : this(
        { NeriUserDataDatabase.getInstance(context.applicationContext ?: context) },
        { UnifiedIdentityJournal(File(requireNotNull((context.applicationContext ?: context).noBackupFilesDir),
            "unimusic-identities/device-journal-v1.json")) }
    )

    internal constructor(database: NeriUserDataDatabase, journal: UnifiedIdentityJournal) : this({ database }, { journal })

    suspend fun snapshot() = withContext(Dispatchers.IO) { journal.snapshot() }
    suspend fun mergeRemote(bytes: ByteArray) = withContext(Dispatchers.IO) { journal.mergeRemote(bytes) }

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

    private companion object { const val MAX_SCANNED_SOURCES = 200_000 }
}
