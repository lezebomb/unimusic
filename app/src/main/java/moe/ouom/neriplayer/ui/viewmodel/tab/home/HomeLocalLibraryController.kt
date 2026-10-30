package moe.ouom.neriplayer.ui.viewmodel.tab.home

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

/** Reference snapshot: Compose keys never compare every song in the library on a tab transition. */
internal class HomeLocalLibraryState(
    val repository: LocalPlaylistRepository? = null,
    val playlists: List<LocalPlaylist> = emptyList()
)

/** One lightweight metadata subscription per Home ViewModel, retained while another tab is visible. */
internal class HomeLocalLibraryController(application: Application, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(HomeLocalLibraryState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val repository = LocalPlaylistRepository.getInstance(application)
                if (!repository.awaitInitialized()) return@launch
                repository.playlists.collect { playlists ->
                    val previous = mutableState.value
                    if (previous.repository !== repository || previous.playlists !== playlists) {
                        mutableState.value = HomeLocalLibraryState(repository, playlists)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NPLogger.w("HomeLocalLibrary", "Local metadata unavailable: type=${failure.javaClass.simpleName}")
            }
        }
    }
}
