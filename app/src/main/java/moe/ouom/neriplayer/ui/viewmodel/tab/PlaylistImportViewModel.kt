package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistSummary
import moe.ouom.neriplayer.data.model.unified.PlaylistImportException
import moe.ouom.neriplayer.data.model.unified.PlaylistImportFailure
import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProgress
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState

internal data class PlaylistImportMessage(
    val resource: Int,
    val counts: List<Int> = emptyList(),
    val isError: Boolean = false,
    val detailResource: Int? = null
)

internal data class PlaylistImportUiState(
    val accountInput: String = "",
    val signedIn: Boolean = false,
    val mode: PlaylistImportMode = PlaylistImportMode.ONE_SHOT,
    val catalogAccount: String? = null,
    val catalog: List<ImportPlaylistSummary> = emptyList(),
    val catalogLoaded: Boolean = false,
    val selected: Set<String> = emptySet(),
    val loadingCatalog: Boolean = false,
    val loadingAccount: Boolean = false,
    val enqueueing: Boolean = false,
    val jobs: List<PlaylistImportProgress> = emptyList(),
    val loadingJobs: Boolean = true,
    val jobsFailed: Boolean = false,
    val busyJobs: Set<String> = emptySet(),
    val queuedBatch: Int = 0,
    val message: PlaylistImportMessage? = null,
    val failureJobId: String? = null,
    val failures: List<PlaylistImportFailure> = emptyList(),
    val loadingFailures: Boolean = false,
    val moreFailures: Boolean = false,
    val failuresFailed: Boolean = false
)

/** The backend owns persisted work; dismissing this screen never cancels an import. */
internal class PlaylistImportViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private val backend by lazy { AppContainer.playlistImportBackend }
    private val _uiState = MutableStateFlow(
        PlaylistImportUiState(
            accountInput = savedState["playlist_import_account"] ?: "",
            mode = if (savedState.get<Boolean>("playlist_import_linked") == true) PlaylistImportMode.LINKED else PlaylistImportMode.ONE_SHOT
        )
    )
    val uiState: StateFlow<PlaylistImportUiState> = _uiState
    private var catalogJob: Job? = null
    private var accountJob: Job? = null
    private var failureJob: Job? = null
    private var jobsObserver: Job? = null
    private var sessionRevision = 0

    init {
        observeJobs()
        viewModelScope.launch {
            var previousSession: String? = null
            AppContainer.neteaseCookieRepo.cookieFlow.collect { cookies ->
                val session = cookies["MUSIC_U"]?.takeIf(String::isNotBlank)
                if (session != previousSession) {
                    sessionRevision++
                    catalogJob?.cancel()
                    accountJob?.cancel()
                    _uiState.update {
                        it.copy(catalogAccount = null, catalog = emptyList(), catalogLoaded = false,
                            selected = emptySet(), loadingCatalog = false, loadingAccount = false)
                    }
                }
                previousSession = session
                _uiState.update { it.copy(signedIn = session != null) }
            }
        }
    }

    fun observeJobs() {
        jobsObserver?.cancel()
        _uiState.update { it.copy(loadingJobs = true, jobsFailed = false) }
        jobsObserver = viewModelScope.launch {
            flow { emitAll(backend.observeJobs()) }.flowOn(Dispatchers.IO).catch { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(loadingJobs = false, jobsFailed = true) }
            }.collect { jobs ->
                _uiState.update { it.copy(jobs = jobs.filter { job -> job.provider == "netease" }, loadingJobs = false, jobsFailed = false) }
            }
        }
    }

    fun setAccountInput(value: String) {
        if (_uiState.value.enqueueing) return
        catalogJob?.cancel()
        accountJob?.cancel()
        savedState["playlist_import_account"] = value
        _uiState.update { it.copy(accountInput = value, catalogAccount = null, catalog = emptyList(),
            catalogLoaded = false, selected = emptySet(), loadingCatalog = false, loadingAccount = false, message = null) }
    }

    fun useSignedInAccount() {
        if (!_uiState.value.signedIn || _uiState.value.loadingAccount || _uiState.value.enqueueing) return
        val revision = sessionRevision
        _uiState.update { it.copy(loadingAccount = true, message = null) }
        accountJob = viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { AppContainer.neteaseClient.getCurrentUserId() }
                if (revision != sessionRevision) return@launch
                if (id <= 0) {
                    message(R.string.unimusic_import_account_failed, error = true)
                } else {
                    val input = id.toString()
                    savedState["playlist_import_account"] = input
                    _uiState.update { it.copy(accountInput = input, loadingAccount = false) }
                    loadCatalog()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message(importFailureText(error), error = true)
            } finally {
                if (accountJob === coroutineContext[Job]) _uiState.update { it.copy(loadingAccount = false) }
            }
        }
    }

    fun loadCatalog() {
        val current = _uiState.value
        if (current.loadingCatalog || current.enqueueing) return
        val account = normalizeImportAccount(current.accountInput)
        if (account == null) {
            message(R.string.unimusic_import_invalid_account, error = true)
            return
        }
        if (!current.signedIn) {
            message(R.string.unimusic_import_login_required, error = true)
            return
        }
        savedState["playlist_import_account"] = account
        _uiState.update { it.copy(accountInput = account, loadingCatalog = true, catalogAccount = null,
            catalog = emptyList(), selected = emptySet(), catalogLoaded = false, message = null) }
        catalogJob = viewModelScope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { backend.listPlaylists(account) }
                _uiState.update { it.copy(catalogAccount = account, catalog = catalog, catalogLoaded = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message(importFailureText(error), error = true)
            } finally {
                if (catalogJob === coroutineContext[Job]) _uiState.update { it.copy(loadingCatalog = false) }
            }
        }
    }

    fun setMode(mode: PlaylistImportMode) {
        if (_uiState.value.enqueueing) return
        savedState["playlist_import_linked"] = mode == PlaylistImportMode.LINKED
        _uiState.update { it.copy(mode = mode) }
    }

    fun togglePlaylist(id: String) {
        _uiState.update {
            if (it.enqueueing || it.catalog.none { playlist -> playlist.remoteId == id }) it
            else it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
        }
    }

    fun toggleAll() {
        _uiState.update {
            if (it.enqueueing) it else it.copy(selected = if (it.selected.size == it.catalog.size) emptySet()
                else it.catalog.mapTo(linkedSetOf()) { playlist -> playlist.remoteId })
        }
    }

    fun enqueueSelected() {
        val current = _uiState.value
        val account = current.catalogAccount ?: return
        if (current.enqueueing || current.busyJobs.isNotEmpty() || current.selected.isEmpty() || !current.signedIn) return
        val selected = current.catalog.filter { it.remoteId in current.selected }
        _uiState.update { it.copy(enqueueing = true, message = null) }
        viewModelScope.launch {
            val succeeded = mutableSetOf<String>()
            var firstFailure: Int? = null
            try {
                for (playlist in selected) {
                    try {
                        withContext(Dispatchers.IO) { backend.enqueue(account, playlist.remoteId, current.mode) }
                        succeeded += playlist.remoteId
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Continue so one rejected playlist cannot discard the rest of a batch.
                        if (firstFailure == null) firstFailure = importFailureText(error)
                    }
                }
                val failed = selected.size - succeeded.size
                _uiState.update {
                    it.copy(selected = it.selected - succeeded, queuedBatch = it.queuedBatch + if (succeeded.isEmpty()) 0 else 1,
                        message = PlaylistImportMessage(
                            if (failed == 0) R.string.unimusic_import_queued else R.string.unimusic_import_queue_partial,
                            if (failed == 0) listOf(succeeded.size) else listOf(succeeded.size, failed), failed > 0,
                            detailResource = firstFailure))
                }
            } finally {
                _uiState.update { it.copy(enqueueing = false) }
            }
        }
    }

    fun cancel(jobId: String) = mutateJob(jobId) { backend.cancel(jobId); R.string.unimusic_import_cancelled_notice }
    fun retry(jobId: String) = mutateJob(jobId) {
        if (backend.retry(jobId)) R.string.unimusic_import_retry_queued else R.string.unimusic_import_retry_unavailable
    }
    fun refresh(jobId: String) = mutateJob(jobId) {
        if (backend.refresh(jobId)) R.string.unimusic_import_refresh_queued else R.string.unimusic_import_refresh_unavailable
    }
    fun unlink(jobId: String) = mutateJob(jobId) { backend.unlink(jobId); R.string.unimusic_import_unlinked_notice }

    private fun mutateJob(jobId: String, action: suspend () -> Int) {
        if (_uiState.value.enqueueing || jobId in _uiState.value.busyJobs) return
        _uiState.update { it.copy(busyJobs = it.busyJobs + jobId, message = null) }
        if (_uiState.value.failureJobId == jobId) dismissFailures()
        viewModelScope.launch {
            try {
                message(withContext(Dispatchers.IO) { action() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message(importFailureText(error), error = true)
            } finally {
                _uiState.update { it.copy(busyJobs = it.busyJobs - jobId) }
            }
        }
    }

    fun showFailures(jobId: String) {
        failureJob?.cancel()
        _uiState.update { it.copy(failureJobId = jobId, failures = emptyList(), moreFailures = true,
            loadingFailures = false, failuresFailed = false) }
        loadMoreFailures()
    }

    fun loadMoreFailures() {
        val current = _uiState.value
        val jobId = current.failureJobId ?: return
        if (current.loadingFailures || !current.moreFailures) return
        _uiState.update { it.copy(loadingFailures = true, failuresFailed = false) }
        failureJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { backend.failures(jobId, FAILURE_PAGE_SIZE, current.failures.size) }
                _uiState.update { it.copy(failures = it.failures + page, moreFailures = page.size == FAILURE_PAGE_SIZE) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(failuresFailed = true) }
            } finally {
                if (failureJob === coroutineContext[Job]) _uiState.update { it.copy(loadingFailures = false) }
            }
        }
    }

    fun dismissFailures() {
        failureJob?.cancel()
        _uiState.update { it.copy(failureJobId = null, failures = emptyList(), loadingFailures = false,
            moreFailures = false, failuresFailed = false) }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun message(resource: Int, error: Boolean = false) {
        _uiState.update { it.copy(message = PlaylistImportMessage(resource, isError = error)) }
    }

    private companion object { const val FAILURE_PAGE_SIZE = 50 }
}

internal fun normalizeImportAccount(value: String): String? = value.trim()
    .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toLongOrNull()?.takeIf { it > 0 }?.toString()

internal fun canCancelImport(state: PlaylistImportState): Boolean = state in setOf(
    PlaylistImportState.QUEUED, PlaylistImportState.RUNNING, PlaylistImportState.RETRY_WAIT, PlaylistImportState.PAUSED_AUTH
)

internal fun canRetryImport(job: PlaylistImportProgress): Boolean = job.lastError != ImportFailureKind.TARGET_REMOVED && job.state in setOf(
    PlaylistImportState.RETRY_WAIT, PlaylistImportState.PAUSED_AUTH, PlaylistImportState.PARTIAL_FAILED,
    PlaylistImportState.CANCELLED, PlaylistImportState.FAILED
)

internal fun importFailureText(error: Throwable): Int = importFailureText((error as? PlaylistImportException)?.kind ?: ImportFailureKind.INTERNAL_ERROR)

internal fun importFailureText(kind: ImportFailureKind): Int = when (kind) {
    ImportFailureKind.AUTH_REQUIRED -> R.string.unimusic_import_login_required
    ImportFailureKind.ACCOUNT_CHANGED -> R.string.unimusic_import_account_changed
    ImportFailureKind.ACCESS_DENIED -> R.string.unimusic_import_access_denied
    ImportFailureKind.NOT_FOUND -> R.string.unimusic_import_not_found
    ImportFailureKind.INCOMPLETE_RESPONSE, ImportFailureKind.INVALID_RESPONSE -> R.string.unimusic_import_incomplete
    ImportFailureKind.TEMPORARY -> R.string.unimusic_import_temporary
    ImportFailureKind.LOCAL_STORAGE -> R.string.unimusic_import_storage_error
    ImportFailureKind.TARGET_REMOVED -> R.string.unimusic_import_target_removed
    ImportFailureKind.INTERNAL_ERROR -> R.string.unimusic_import_unknown_error
}
