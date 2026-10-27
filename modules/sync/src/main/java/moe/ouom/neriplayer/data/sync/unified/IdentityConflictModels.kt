package moe.ouom.neriplayer.data.sync.unified

import java.io.IOException
import moe.ouom.neriplayer.data.model.unified.sync.PortableSongMetadata

data class LocalIdentityMetadata(val songId: String, val metadata: PortableSongMetadata)
data class IdentityDecisionSnapshot(val revision: Long, val state: UnifiedIdentityMergeResult)
class StaleIdentityDecisionException : IOException("Identity record changed; refresh before choosing")

data class IdentityConflictChoice(val metadata: PortableSongMetadata?, val local: Boolean)
data class IdentityConflictView(val songId: String, val revision: Long, val choices: List<IdentityConflictChoice>,
    val localSongs: List<LocalIdentityMetadata>)
data class IdentityConflictPage(val total: Int, val offset: Int, val items: List<IdentityConflictView>)
