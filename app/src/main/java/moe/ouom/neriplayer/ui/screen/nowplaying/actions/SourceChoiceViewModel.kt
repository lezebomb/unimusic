package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingSourceMatch
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision

internal enum class SourceChoiceNotice { INCOMPLETE, OFFLINE, LOAD_FAILED, SAVE_FAILED, SAVED, CLEARED, ACCOUNT_CHANGED }

internal data class SourceChoiceState(
    val song: SongItem? = null,
    val matches: List<RecordingSourceMatch> = emptyList(),
    val candidates: List<RecordingSourceCandidate> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val notice: SourceChoiceNotice? = null
)

/** This owner remembers metadata decisions; saving never interrupts current playback. */
internal class SourceChoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val backend get() = AppContainer.sourceMatchBackend
    private val _state = MutableStateFlow(SourceChoiceState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var account = accountSnapshot()

    init {
        viewModelScope.launch {
            AppContainer.biliCookieRepo.cookieFlow.map { cookies ->
                listOf(cookies["DedeUserID"], cookies["SESSDATA"], cookies["bili_jct"])
            }.distinctUntilChanged().collect { current ->
                if (account != current) {
                    account = current
                    generation++
                    work?.cancel()
                    _state.value = _state.value.copy(candidates = emptyList(), loading = false,
                        saving = false, notice = SourceChoiceNotice.ACCOUNT_CHANGED)
                }
            }
        }
    }

    fun bind(song: SongItem) {
        close()
        _state.value = SourceChoiceState(song = song)
        refresh()
    }

    fun close() {
        generation++
        work?.cancel()
        work = null
        _state.value = SourceChoiceState()
    }

    fun refresh() {
        val song = _state.value.song ?: return
        if (_state.value.saving) return
        work?.cancel()
        val token = ++generation
        val session = accountSnapshot()
        _state.value = _state.value.copy(loading = true, notice = null, candidates = emptyList())
        work = viewModelScope.launch {
            try {
                val matches = backend.matches(song)
                if (!isCurrent(token, session)) return@launch
                _state.value = _state.value.copy(matches = matches)
                val result = discoverSourceChoices(getApplication(), song)
                if (!isCurrent(token, session)) return@launch
                _state.value = _state.value.copy(candidates = result.candidates, loading = false,
                    notice = when {
                        result.incomplete -> SourceChoiceNotice.INCOMPLETE
                        result.offline -> SourceChoiceNotice.OFFLINE
                        else -> null
                    })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(token, session)) _state.value = _state.value.copy(
                    loading = false, notice = SourceChoiceNotice.LOAD_FAILED)
            }
        }
    }

    fun decide(candidate: RecordingSourceCandidate, decision: SourceMatchDecision) {
        mutate(candidate, decision)
    }

    fun clear(candidate: RecordingSourceCandidate) {
        mutate(candidate, null)
    }

    private fun mutate(candidate: RecordingSourceCandidate, decision: SourceMatchDecision?) {
        val snapshot = _state.value
        val song = snapshot.song ?: return
        if (snapshot.loading || snapshot.saving) return
        if (account != accountSnapshot()) return
        if ((snapshot.candidates + snapshot.matches.map { it.candidate }).none { it == candidate }) return
        if (decision == SourceMatchDecision.CONFIRMED && !assessSourceChoice(song, candidate).accepted) return
        val token = ++generation
        val session = accountSnapshot()
        _state.value = snapshot.copy(saving = true, notice = null)
        work = viewModelScope.launch {
            try {
                if (!isCurrent(token, session)) return@launch
                if (decision == null) backend.clear(song, candidate.key)
                else backend.decide(song, candidate, decision)
                val matches = backend.matches(song)
                if (isCurrent(token, session)) _state.value = _state.value.copy(matches = matches,
                    saving = false, notice = if (decision == null) SourceChoiceNotice.CLEARED else SourceChoiceNotice.SAVED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(token, session)) _state.value = _state.value.copy(
                    saving = false, notice = SourceChoiceNotice.SAVE_FAILED)
            }
        }
    }

    private fun isCurrent(token: Long, session: List<String?>): Boolean =
        generation == token && accountSnapshot() == session

    private fun accountSnapshot(): List<String?> = AppContainer.biliCookieRepo.cookieFlow.value.let {
        listOf(it["DedeUserID"], it["SESSDATA"], it["bili_jct"])
    }
}
